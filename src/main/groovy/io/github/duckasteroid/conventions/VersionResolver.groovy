package io.github.duckasteroid.conventions

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Derives project versions from git history rather than a single nearest tag.
 *
 * See VERSIONING.md at the repo root for the full picture (why this exists, the develop/release/main
 * flow, worked examples). In short, the algorithm for an ordinary build ({@link #resolveBuildVersion})
 * is:
 *
 *   1. If HEAD is exactly on a real tag matching tagPrefix, use it verbatim - no computation needed.
 *   2. Otherwise, find the last *final* release tag (a plain "X.Y.Z", no "-RC"/"-SNAPSHOT" suffix)
 *      reachable from HEAD under tagPrefix. If this module has no final tag of its own yet, retry
 *      each of fallbackPrefixes in turn (see {@link #lastFinalVersionLookup}) - e.g. a brand-new
 *      subproject in a multi-module build inherits the root project's version line instead of
 *      starting over at "0.0.0".
 *   3. Walk the commits between that tag and HEAD, optionally restricted to ones that touched
 *      modulePath (see {@link #commitMessagesSince}), and classify them with
 *      {@link CommitAnalyzer} using typeRules (feat/fix/perf/BREAKING CHANGE -> minor/patch/
 *      patch/major by default, but configurable via {@link CommitAnalyzerExtension}).
 *   4. Bump the last-final version by the highest classification found (see {@link #bumpVersion}),
 *      and decorate it with "-SNAPSHOT" (plus the branch name, for feature branches) since we're not
 *      exactly on a tag.
 *
 * {@link #nextReleaseCandidateTag} and {@link #promoteTag} are the two release-engineering
 * operations built on the same primitives, used by the duckasteroid-release-flow tasks rather than
 * by ordinary builds: the former mints the next "-RCn" tag for the computed candidate version, the
 * latter strips the "-RCn" suffix off the nearest reachable RC tag to produce the final release tag.
 *
 * {@link #commitMessagesForChangelog} is the equivalent supporting operation for release notes (see
 * {@link ChangelogGenerator}): it reuses the same tag-lookup machinery to decide which commits are
 * "new" for a changelog, just with its own scope rules (see {@link ChangelogScope}) rather than the
 * version-bump rules above.
 *
 * Deliberately uses JGit rather than shelling out to the `git` CLI: `resolveBuildVersion` is called
 * from `version = ...` in duckasteroid-java.gradle at Gradle *configuration* time, and starting an
 * external process during configuration is incompatible with the Gradle configuration cache (JGit is
 * a pure-Java implementation, so it doesn't trip that restriction - this is also why axion-release's
 * own JGit-based ghOwner/ghBranch detection in duckasteroid-java.gradle has always worked fine). The
 * release-flow tasks that call into this class only do so from `doLast { }` (task *execution* time,
 * where external processes are fine), but they share the same JGit-based code for simplicity and to
 * avoid two parallel implementations of the same logic.
 */
class VersionResolver {

    // Strict "X.Y.Z" with nothing else - deliberately does NOT match "-RC1"/"-SNAPSHOT" tags, since
    // those must never be mistaken for a base to bump from (see lastFinalVersionLookup).
    private static final Pattern FINAL_VERSION_SUFFIX = ~/^(\d+)\.(\d+)\.(\d+)$/
    private static final Pattern RC_VERSION_SUFFIX = ~/^(\d+)\.(\d+)\.(\d+)-RC(\d+)$/
    private static final String TAG_REF_PREFIX = 'refs/tags/'

    /**
     * The version to use for an ordinary build (e.g. `./gradlew build`, `publishToMavenLocal`).
     *
     * @param repoDir any directory inside the git repo (JGit walks upward to find the .git dir)
     * @param tagPrefix this module's own tag prefix, e.g. "v" at the root or "sub/module/v"
     * @param branchName the current git branch, used to fold a sanitized form into the -SNAPSHOT
     *        suffix on feature branches (e.g. "1.0.1-cool-stuff-SNAPSHOT") so a build made while
     *        working on a feature branch is visibly distinguishable from one made on develop/release
     * @param modulePath this module's directory relative to the repo root ("" at the root); commits
     *        that didn't touch this path don't count towards this module's bump
     * @param fallbackPrefixes tried in order if tagPrefix has no final tag reachable from HEAD yet
     * @param typeRules see {@link CommitAnalyzer#DEFAULT_TYPE_RULES}; defaults to the built-in
     *        mapping. In the Gradle plugin this normally comes from the `commitAnalyzer { }`
     *        extension (see {@link CommitAnalyzerExtension#toTypeRules}), not the bare default.
     */
    static String resolveBuildVersion(File repoDir, String tagPrefix, String branchName,
                                       String modulePath = '', List<String> fallbackPrefixes = [],
                                       Map<CommitAnalyzer.Bump, Set<String>> typeRules = CommitAnalyzer.DEFAULT_TYPE_RULES) {
        withRepo(repoDir) { Repository repo ->
            String exact = exactTagAt(repo, tagPrefix)
            if (exact != null) {
                // HEAD is a real, already-released commit (final or RC) - nothing to compute.
                return exact
            }
            String candidate = resolveCandidateVersionIn(repo, tagPrefix, modulePath, fallbackPrefixes, typeRules)
            String sanitizedBranch = sanitizeBranch(branchName)
            return sanitizedBranch ? "${candidate}-${sanitizedBranch}-SNAPSHOT" : "${candidate}-SNAPSHOT"
        }
    }

    /**
     * The last final release version, bumped by the conventional commits since it - with no
     * "-SNAPSHOT"/branch decoration. This is what an actual release tag (RC or final) should be
     * based on; {@link #resolveBuildVersion} calls this too and then decorates the result for
     * ordinary (non-release) builds.
     */
    static String resolveCandidateVersion(File repoDir, String tagPrefix,
                                           String modulePath = '', List<String> fallbackPrefixes = [],
                                           Map<CommitAnalyzer.Bump, Set<String>> typeRules = CommitAnalyzer.DEFAULT_TYPE_RULES) {
        return withRepo(repoDir) { Repository repo ->
            resolveCandidateVersionIn(repo, tagPrefix, modulePath, fallbackPrefixes, typeRules)
        }
    }

    /**
     * Highest final release tag (prefix + strict X.Y.Z, no suffix) reachable from HEAD. If none
     * exists under tagPrefix, retries each of fallbackPrefixes in order (e.g. a brand-new module
     * inherits the root project's version line instead of starting over at "0.0.0"). Falls all the
     * way back to "0.0.0" if nothing matches under any prefix - a fresh repo with no releases yet.
     */
    static String lastFinalVersion(File repoDir, String tagPrefix, List<String> fallbackPrefixes = []) {
        return withRepo(repoDir) { Repository repo -> lastFinalVersionLookup(repo, tagPrefix, fallbackPrefixes).v2 }
    }

    /**
     * The full tag name (with prefix) for the *next* release-candidate to mint, e.g. "v1.1.0-RC2".
     * Does not create or push anything itself - that's the caller's job (see
     * duckasteroid-release-flow.gradle's tagReleaseCandidate task). candidateVersion is normally
     * whatever {@link #resolveCandidateVersion} just computed, but the caller may instead pass a
     * value straight from `-Prelease.forceVersion` to bypass commit analysis entirely.
     */
    static String nextReleaseCandidateTag(File repoDir, String tagPrefix, String candidateVersion) {
        return withRepo(repoDir) { Repository repo ->
            int next = 1
            // Find every existing "<tagPrefix><candidateVersion>-RC<n>" tag reachable from HEAD and
            // pick next = highest n found + 1, so re-running this after a new commit (or re-running
            // it against the same commit, harmlessly) always advances the RC counter rather than
            // colliding with an existing tag.
            Pattern rcPattern = Pattern.compile('^' + Pattern.quote("${tagPrefix}${candidateVersion}-RC") + '(\\d+)$')
            tagsMergedIntoHead(repo).each { String tag ->
                Matcher m = rcPattern.matcher(tag)
                if (m.matches()) {
                    int n = (m.group(1) as int) + 1
                    if (n > next) {
                        next = n
                    }
                }
            }
            return "${tagPrefix}${candidateVersion}-RC${next}"
        }
    }

    /**
     * The full tag name (with prefix) for the final release, derived by stripping the "-RCn" suffix
     * off the nearest reachable release-candidate tag. Used by duckasteroid-release-flow.gradle's
     * promoteReleaseCandidate task when an accepted RC on the `release` branch is merged to `main`.
     * Throws if there's no RC tag reachable from HEAD at all - promoting only makes sense once at
     * least one RC has actually been cut.
     */
    static String promoteTag(File repoDir, String tagPrefix) {
        return withRepo(repoDir) { Repository repo ->
            Pattern rcSuffix = ~/-RC\d+$/
            String rcTag = nearestReachableTag(repo, tagPrefix, rcSuffix)
            if (rcTag == null) {
                throw new IllegalStateException(
                        "No release-candidate tag found reachable from HEAD matching '${tagPrefix}*-RC*' - nothing to promote")
            }
            Matcher m = RC_VERSION_SUFFIX.matcher(rcTag.substring(tagPrefix.length()))
            if (!m.matches()) {
                throw new IllegalStateException("Tag '${rcTag}' does not look like a release candidate")
            }
            return "${tagPrefix}${m.group(1)}.${m.group(2)}.${m.group(3)}"
        }
    }

    /**
     * Full commit messages to build a changelog from, for {@link ChangelogGenerator}. The "since"
     * boundary depends on scope:
     *
     *   - {@link ChangelogScope#SINCE_LAST_RELEASE}: the last final release tag (falling back
     *     through fallbackPrefixes exactly like {@link #resolveCandidateVersion}) - the complete
     *     picture for this release cycle so far.
     *   - {@link ChangelogScope#SINCE_PREVIOUS_RC}: the nearest reachable release-candidate tag - a
     *     delta since the last RC - falling back to the last final release tag if there is no
     *     previous RC yet (the first RC in a cycle has nothing to diff against).
     *
     * Same modulePath restriction as version resolution: only commits that touched this module's own
     * directory are included, so a changelog generated for one subproject in a monorepo doesn't list
     * changes from an unrelated sibling.
     *
     * <b>Caller must run this BEFORE minting any new tag for the current HEAD</b> (see
     * duckasteroid-release-flow.gradle's changelogForReleaseCandidate/changelogForRelease tasks,
     * which always run before tagReleaseCandidate/promoteReleaseCandidate in the same job). Both
     * scopes locate their "since" boundary by looking for a tag reachable from HEAD - if HEAD were
     * already tagged with the release currently being prepared, that brand-new tag would itself be
     * "the nearest reachable RC tag" or "the last final release tag", making the commit range (and
     * therefore the generated changelog) come back empty.
     *
     * @param repoDir any directory inside the git repo (JGit walks upward to find the .git dir)
     * @param tagPrefix this module's own tag prefix, e.g. "v" at the root or "sub/module/v"
     * @param modulePath this module's directory relative to the repo root ("" at the root)
     * @param fallbackPrefixes tried in order if tagPrefix has no final tag reachable from HEAD yet
     *        (only relevant for the SINCE_LAST_RELEASE fallback path, same as resolveCandidateVersion)
     * @param scope see {@link ChangelogScope}
     */
    static List<String> commitMessagesForChangelog(File repoDir, String tagPrefix, String modulePath,
                                                    List<String> fallbackPrefixes, ChangelogScope scope) {
        return withRepo(repoDir) { Repository repo ->
            ObjectId sinceCommit = null
            if (scope == ChangelogScope.SINCE_PREVIOUS_RC) {
                Pattern rcSuffix = ~/-RC\d+$/
                String rcTag = nearestReachableTag(repo, tagPrefix, rcSuffix)
                if (rcTag != null) {
                    sinceCommit = commitForTag(repo, rcTag)
                }
            }
            if (sinceCommit == null) {
                Tuple2<String, String> lookup = lastFinalVersionLookup(repo, tagPrefix, fallbackPrefixes)
                sinceCommit = commitForTag(repo, "${lookup.v1}${lookup.v2}")
            }
            return commitMessagesSince(repo, sinceCommit, modulePath)
        }
    }

    // ---- internals: all take an already-open Repository ----

    private static String resolveCandidateVersionIn(Repository repo, String tagPrefix, String modulePath,
                                                     List<String> fallbackPrefixes,
                                                     Map<CommitAnalyzer.Bump, Set<String>> typeRules) {
        Tuple2<String, String> lookup = lastFinalVersionLookup(repo, tagPrefix, fallbackPrefixes)
        String matchedPrefix = lookup.v1
        String lastFinal = lookup.v2
        // matchedPrefix may be a fallback prefix rather than tagPrefix itself (see
        // lastFinalVersionLookup) - the *tag object* we walk commits from has to be looked up under
        // whichever prefix actually matched, even though the version we ultimately bump and re-tag
        // always uses tagPrefix (this module's own).
        ObjectId sinceCommit = commitForTag(repo, "${matchedPrefix}${lastFinal}")
        List<String> messages = commitMessagesSince(repo, sinceCommit, modulePath)
        CommitAnalyzer.Bump bump = messages.isEmpty() ? CommitAnalyzer.Bump.NONE : CommitAnalyzer.analyze(messages, typeRules)
        return bumpVersion(lastFinal, bump)
    }

    /**
     * Highest final release tag under tagPrefix reachable from HEAD; if none, retries each of
     * fallbackPrefixes in order. Returns (matchedPrefix, version) rather than just the version,
     * because the prefix is needed by {@link #resolveCandidateVersionIn} to locate the actual tag
     * object bounding the commit walk - it may differ from tagPrefix when a fallback matched, and
     * getting this wrong would mean either double-counting commits already covered by the fallback
     * tag, or (worse) walking off the front of history looking for a tag under the wrong prefix.
     */
    private static Tuple2<String, String> lastFinalVersionLookup(Repository repo, String tagPrefix,
                                                                  List<String> fallbackPrefixes) {
        List<String> tags = tagsMergedIntoHead(repo)
        // .unique() so passing tagPrefix as one of its own fallbacks (harmless, but easy to do by
        // accident from a caller) doesn't scan the tag list twice for the same prefix.
        for (String candidatePrefix : ([tagPrefix] + fallbackPrefixes).unique()) {
            int[] best = null
            tags.each { String tag ->
                if (!tag.startsWith(candidatePrefix)) {
                    return
                }
                // The startsWith() check above is just a cheap prefilter - the real gate is this
                // regex match against the remainder, which is what actually rules out tags that
                // merely happen to share a leading substring (e.g. a "versioning/v2/v1.0.0" tag does
                // NOT spuriously match a plain "v" prefix, because "ersioning/v2/v1.0.0" doesn't
                // match ^(\d+)\.(\d+)\.(\d+)$).
                Matcher m = FINAL_VERSION_SUFFIX.matcher(tag.substring(candidatePrefix.length()))
                if (m.matches()) {
                    int[] v = [m.group(1) as int, m.group(2) as int, m.group(3) as int] as int[]
                    if (best == null || compareVersions(v, best) > 0) {
                        best = v
                    }
                }
            }
            if (best != null) {
                return new Tuple2<>(candidatePrefix, "${best[0]}.${best[1]}.${best[2]}" as String)
            }
        }
        // Nothing found under tagPrefix or any fallback - brand new repo/module, no prior release.
        return new Tuple2<>(tagPrefix, '0.0.0')
    }

    /** Applies a single CommitAnalyzer.Bump to a plain "X.Y.Z" version, standard semver rules. */
    private static String bumpVersion(String baseVersion, CommitAnalyzer.Bump bump) {
        Matcher m = FINAL_VERSION_SUFFIX.matcher(baseVersion)
        if (!m.matches()) {
            throw new IllegalStateException("Not a plain X.Y.Z version: ${baseVersion}")
        }
        int major = m.group(1) as int
        int minor = m.group(2) as int
        int patch = m.group(3) as int
        switch (bump) {
            case CommitAnalyzer.Bump.MAJOR:
                return "${major + 1}.0.0"
            case CommitAnalyzer.Bump.MINOR:
                return "${major}.${minor + 1}.0"
            case CommitAnalyzer.Bump.PATCH:
                return "${major}.${minor}.${patch + 1}"
            default:
                // Bump.NONE: nothing since the last release warrants a new version yet.
                return baseVersion
        }
    }

    /** Lexicographic comparison of [major, minor, patch] triples. */
    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int cmp = a[i] <=> b[i]
            if (cmp != 0) {
                return cmp
            }
        }
        return 0
    }

    /**
     * Turns a branch name into a version-safe suffix fragment, or null for branches that shouldn't
     * be called out in the version at all (the "steady state" branches, where a bare "-SNAPSHOT" is
     * clearer than e.g. "-release-SNAPSHOT" on every build).
     */
    private static String sanitizeBranch(String branchName) {
        if (!branchName || branchName in ['main', 'master', 'release', 'develop']) {
            return null
        }
        return branchName.toLowerCase().replaceAll(/[^a-z0-9]+/, '-').replaceAll(/^-+|-+$/, '')
    }

    /** Opens the repository containing repoDir, runs action against it, and always closes it after. */
    private static <T> T withRepo(File repoDir, Closure<T> action) {
        Repository repo = new RepositoryBuilder().readEnvironment().findGitDir(repoDir).build()
        try {
            return action.call(repo)
        } finally {
            repo.close()
        }
    }

    /** Short tag name (e.g. "v1.0.0"), not the full refs/tags/... form. */
    private static String shortName(Ref tagRef) {
        return tagRef.name.substring(TAG_REF_PREFIX.length())
    }

    /**
     * The commit a tag ref points to. Lightweight tags point directly at a commit, but *annotated*
     * tags (the kind `git tag -a` creates, which is what duckasteroid-release-flow always creates)
     * point at a separate tag object which itself points at the commit - "peeling" follows that
     * extra indirection down to the actual commit. Getting this wrong would make every annotated tag
     * silently invisible to tagsMergedIntoHead/exactTagAt/nearestReachableTag below.
     */
    private static ObjectId peeledCommitId(Repository repo, Ref tagRef) {
        Ref peeled = repo.refDatabase.peel(tagRef)
        return peeled.peeledObjectId ?: peeled.objectId
    }

    /** Looks up a tag by its exact full name (prefix + version) and returns the commit it points to. */
    private static ObjectId commitForTag(Repository repo, String tagName) {
        Ref ref = repo.refDatabase.exactRef(TAG_REF_PREFIX + tagName)
        return ref == null ? null : peeledCommitId(repo, ref)
    }

    private static List<Ref> allTagRefs(Repository repo) {
        return repo.refDatabase.getRefsByPrefix(TAG_REF_PREFIX).toList()
    }

    /**
     * Short names of every tag in the repo whose commit is HEAD itself or an ancestor of it - the
     * JGit equivalent of `git tag --merged HEAD`. Deliberately checks *every* tag in the repo rather
     * than assuming linear history, since a tag created on a since-merged branch is still reachable
     * even though it isn't on the "main line".
     */
    private static List<String> tagsMergedIntoHead(Repository repo) {
        ObjectId headId = repo.resolve('HEAD')
        if (headId == null) {
            return []
        }
        List<String> result = []
        RevWalk walk = new RevWalk(repo)
        try {
            RevCommit head = walk.parseCommit(headId)
            allTagRefs(repo).each { Ref tagRef ->
                ObjectId commitId = peeledCommitId(repo, tagRef)
                try {
                    RevCommit commit = walk.parseCommit(commitId)
                    if (commit == head || walk.isMergedInto(commit, head)) {
                        result << shortName(tagRef)
                    }
                } catch (Exception ignored) {
                    // Tag doesn't point at a commit at all (e.g. it points at a blob or tree) -
                    // not something we can ever consider a "release tag", so just skip it.
                }
            }
        } finally {
            walk.dispose()
        }
        return result
    }

    /**
     * The version (tagPrefix stripped) exactly at HEAD matching this prefix, whether a final
     * release or an "-RCn" tag - both count as "HEAD is already a real, released version, nothing
     * to compute" for {@link #resolveBuildVersion}. Returns null if HEAD isn't tagged at all under
     * this prefix. Prefix is stripped here so the return value matches every other version string
     * this class produces (resolveCandidateVersionIn/bumpVersion never include tagPrefix either).
     */
    private static String exactTagAt(Repository repo, String tagPrefix) {
        ObjectId headId = repo.resolve('HEAD')
        if (headId == null) {
            return null
        }
        for (Ref tagRef : allTagRefs(repo)) {
            String name = shortName(tagRef)
            if (name.startsWith(tagPrefix) && peeledCommitId(repo, tagRef) == headId) {
                return name.substring(tagPrefix.length())
            }
        }
        return null
    }

    /**
     * The nearest tag (by commit distance, walking HEAD's ancestry) whose name matches
     * tagPrefix + suffixPattern - the JGit equivalent of `git describe --tags --match <glob>
     * --abbrev=0`, except driven by a real regex instead of a shell glob so callers can express
     * "ends in -RC<digits>" precisely. Used by {@link #promoteTag} to find the RC being promoted.
     */
    private static String nearestReachableTag(Repository repo, String tagPrefix, Pattern suffixPattern) {
        ObjectId headId = repo.resolve('HEAD')
        if (headId == null) {
            return null
        }
        // Build a commit-id -> tag-name lookup once up front, then walk HEAD's ancestry checking
        // each commit against it - the walk naturally visits nearer commits first, so the first hit
        // is the nearest one, without needing to compare distances explicitly.
        Map<String, String> tagByCommit = [:]
        allTagRefs(repo).each { Ref tagRef ->
            String name = shortName(tagRef)
            if (name.startsWith(tagPrefix) && suffixPattern.matcher(name.substring(tagPrefix.length())).find()) {
                tagByCommit[peeledCommitId(repo, tagRef).name] = name
            }
        }
        if (tagByCommit.isEmpty()) {
            return null
        }
        RevWalk walk = new RevWalk(repo)
        try {
            walk.markStart(walk.parseCommit(headId))
            for (RevCommit commit : walk) {
                String tag = tagByCommit[commit.id.name]
                if (tag != null) {
                    return tag
                }
            }
        } finally {
            walk.dispose()
        }
        return null
    }

    /**
     * Full commit messages (subject + body/footer, exactly what {@link CommitAnalyzer} needs to spot
     * a BREAKING CHANGE footer) for every commit reachable from HEAD but not from sinceCommitOrNull
     * (i.e. the commits *since* the last release) - the JGit equivalent of
     * `git log <sinceCommit>..HEAD`. sinceCommitOrNull is null for a fresh repo/module with no prior
     * release tag at all, in which case the whole history reachable from HEAD counts.
     *
     * When modulePath is non-empty, further restricted to commits that actually touched that path
     * (equivalent to appending `-- modulePath` to the git log command above) - this is what stops a
     * change in one subproject of a multi-module build from bumping an unrelated sibling's version.
     * Uses JGit's Git porcelain LogCommand.addPath(...) rather than hand-rolling an AndTreeFilter/
     * PathFilterGroup combination, since that's exactly what `addPath` already does internally, and
     * doing it this way means we're relying on JGit's own tested implementation of "log -- path"
     * rather than a reimplementation of it.
     */
    private static List<String> commitMessagesSince(Repository repo, ObjectId sinceCommitOrNull, String modulePath) {
        ObjectId headId = repo.resolve('HEAD')
        if (headId == null) {
            return []
        }
        Git git = new Git(repo)
        def logCommand = git.log().add(headId)
        if (sinceCommitOrNull != null) {
            logCommand = logCommand.not(sinceCommitOrNull)
        }
        if (modulePath) {
            logCommand = logCommand.addPath(modulePath)
        }
        List<String> messages = []
        logCommand.call().each { RevCommit commit -> messages << commit.fullMessage }
        return messages
    }
}
