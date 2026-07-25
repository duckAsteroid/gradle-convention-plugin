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
 * Derives project versions from git history rather than a single nearest tag: finds the last
 * *final* release tag reachable from HEAD (ignoring any leftover -RC tags, and falling back to
 * fallbackPrefixes - e.g. the root project's plain "v" prefix - when this module has no tag of its
 * own yet), and - unless HEAD sits exactly on a real tag - bumps it according to the
 * conventional-commit messages since then (see {@link CommitAnalyzer}), optionally restricted to
 * commits that touched modulePath so unrelated modules in the same monorepo don't bump each
 * other's version. Also used by the release-flow tasks to mint the next -RC tag and to promote an
 * accepted RC to a final release tag.
 *
 * Uses JGit (not the `git` CLI / external processes) deliberately: this is called from
 * `version = ...` at Gradle *configuration* time, where starting an external process is
 * incompatible with the configuration cache.
 */
class VersionResolver {

    private static final Pattern FINAL_VERSION_SUFFIX = ~/^(\d+)\.(\d+)\.(\d+)$/
    private static final Pattern RC_VERSION_SUFFIX = ~/^(\d+)\.(\d+)\.(\d+)-RC(\d+)$/
    private static final String TAG_REF_PREFIX = 'refs/tags/'

    /** Ordinary-build version: exact tag if HEAD is on one, else bumped-candidate + "-SNAPSHOT". */
    static String resolveBuildVersion(File repoDir, String tagPrefix, String branchName,
                                       String modulePath = '', List<String> fallbackPrefixes = []) {
        withRepo(repoDir) { Repository repo ->
            String exact = exactTagAt(repo, tagPrefix)
            if (exact != null) {
                return exact
            }
            String candidate = resolveCandidateVersionIn(repo, tagPrefix, modulePath, fallbackPrefixes)
            String sanitizedBranch = sanitizeBranch(branchName)
            return sanitizedBranch ? "${candidate}-${sanitizedBranch}-SNAPSHOT" : "${candidate}-SNAPSHOT"
        }
    }

    /** Last final release version, bumped by conventional commits since it - no decoration. */
    static String resolveCandidateVersion(File repoDir, String tagPrefix,
                                           String modulePath = '', List<String> fallbackPrefixes = []) {
        return withRepo(repoDir) { Repository repo ->
            resolveCandidateVersionIn(repo, tagPrefix, modulePath, fallbackPrefixes)
        }
    }

    /**
     * Highest final release tag (prefix + strict X.Y.Z, no suffix) reachable from HEAD. If none
     * exists under tagPrefix, retries each of fallbackPrefixes in order (e.g. a brand-new module
     * inherits the root project's version line instead of starting over at "0.0.0").
     */
    static String lastFinalVersion(File repoDir, String tagPrefix, List<String> fallbackPrefixes = []) {
        return withRepo(repoDir) { Repository repo -> lastFinalVersionLookup(repo, tagPrefix, fallbackPrefixes).v2 }
    }

    /** Full tag name (with prefix) for the next release-candidate to mint, e.g. "v1.1.0-RC2". */
    static String nextReleaseCandidateTag(File repoDir, String tagPrefix, String candidateVersion) {
        return withRepo(repoDir) { Repository repo ->
            int next = 1
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

    /** Full tag name (with prefix) for the final release, derived from the nearest reachable RC tag. */
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

    // ---- internals: all take an already-open Repository ----

    private static String resolveCandidateVersionIn(Repository repo, String tagPrefix,
                                                     String modulePath, List<String> fallbackPrefixes) {
        Tuple2<String, String> lookup = lastFinalVersionLookup(repo, tagPrefix, fallbackPrefixes)
        String matchedPrefix = lookup.v1
        String lastFinal = lookup.v2
        ObjectId sinceCommit = commitForTag(repo, "${matchedPrefix}${lastFinal}")
        List<String> messages = commitMessagesSince(repo, sinceCommit, modulePath)
        CommitAnalyzer.Bump bump = messages.isEmpty() ? CommitAnalyzer.Bump.NONE : CommitAnalyzer.analyze(messages)
        return bumpVersion(lastFinal, bump)
    }

    /**
     * Highest final release tag under tagPrefix reachable from HEAD; if none, retries each of
     * fallbackPrefixes in order. Returns (matchedPrefix, version) - the prefix is needed by callers
     * to locate the actual tag object (e.g. to bound the commit walk), since it may differ from
     * tagPrefix when a fallback matched.
     */
    private static Tuple2<String, String> lastFinalVersionLookup(Repository repo, String tagPrefix,
                                                                  List<String> fallbackPrefixes) {
        List<String> tags = tagsMergedIntoHead(repo)
        for (String candidatePrefix : ([tagPrefix] + fallbackPrefixes).unique()) {
            int[] best = null
            tags.each { String tag ->
                if (!tag.startsWith(candidatePrefix)) {
                    return
                }
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
        return new Tuple2<>(tagPrefix, '0.0.0')
    }

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
                return baseVersion
        }
    }

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int cmp = a[i] <=> b[i]
            if (cmp != 0) {
                return cmp
            }
        }
        return 0
    }

    private static String sanitizeBranch(String branchName) {
        if (!branchName || branchName in ['main', 'master', 'release', 'develop']) {
            return null
        }
        return branchName.toLowerCase().replaceAll(/[^a-z0-9]+/, '-').replaceAll(/^-+|-+$/, '')
    }

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

    /** The commit a tag ref points to, peeling annotated tags down to their target commit. */
    private static ObjectId peeledCommitId(Repository repo, Ref tagRef) {
        Ref peeled = repo.refDatabase.peel(tagRef)
        return peeled.peeledObjectId ?: peeled.objectId
    }

    private static ObjectId commitForTag(Repository repo, String tagName) {
        Ref ref = repo.refDatabase.exactRef(TAG_REF_PREFIX + tagName)
        return ref == null ? null : peeledCommitId(repo, ref)
    }

    private static List<Ref> allTagRefs(Repository repo) {
        return repo.refDatabase.getRefsByPrefix(TAG_REF_PREFIX).toList()
    }

    /** Short names of tags whose commit is an ancestor of (or equal to) HEAD. */
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
                    // Tag doesn't point at a commit (e.g. points at a blob/tree) - not relevant here
                }
            }
        } finally {
            walk.dispose()
        }
        return result
    }

    /** Tag exactly at HEAD matching this prefix (either final or -RC form), or null. */
    private static String exactTagAt(Repository repo, String tagPrefix) {
        ObjectId headId = repo.resolve('HEAD')
        if (headId == null) {
            return null
        }
        for (Ref tagRef : allTagRefs(repo)) {
            String name = shortName(tagRef)
            if (name.startsWith(tagPrefix) && peeledCommitId(repo, tagRef) == headId) {
                return name
            }
        }
        return null
    }

    /** Nearest tag (by commit distance) reachable from HEAD whose name matches prefix + suffixPattern. */
    private static String nearestReachableTag(Repository repo, String tagPrefix, Pattern suffixPattern) {
        ObjectId headId = repo.resolve('HEAD')
        if (headId == null) {
            return null
        }
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
     * Full commit messages (subject + body) reachable from HEAD, excluding sinceCommit and its
     * ancestors, optionally restricted to commits that touched modulePath (equivalent to
     * `git log <range> -- modulePath`) so unrelated modules in a monorepo don't bump each other's
     * version.
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
