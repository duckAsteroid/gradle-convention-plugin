package io.github.duckasteroid.conventions

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Minimal Groovy port of the semantic-release commit-analyzer concept
 * (https://github.com/semantic-release/commit-analyzer), scoped to the default
 * Conventional Commits (https://www.conventionalcommits.org/) / Angular rule set only - there is
 * deliberately no configurable "preset" system here, unlike the JS original, since this plugin only
 * ever needs the one rule set. The default rules:
 *
 *   feat                                  -> MINOR
 *   fix, perf                             -> PATCH
 *   docs, style, refactor, test,
 *   chore, build, ci                      -> NONE  (known, deliberately-no-release types)
 *   anything that doesn't conform to the
 *   "type: description" shape at all, or
 *   uses a type outside the lists above    -> PATCH, and a warning is printed to stderr
 *
 * ...except that a '!' after the type/scope (e.g. "feat!:" or "feat(api)!:"), OR a
 * "BREAKING CHANGE:" (or "BREAKING-CHANGE:") footer anywhere in the message, always forces MAJOR
 * regardless of the type - a breaking fix is still a breaking change.
 *
 * The non-conforming rule (-> PATCH + warning, rather than silently NONE) is a deliberate departure
 * from the semantic-release original, which just ignores anything it doesn't recognize: here, a
 * commit that isn't in Conventional Commits form at all - a typo'd type, a merge commit's default
 * message, a commit predating this convention's adoption, someone not bothering to follow the
 * convention - is treated as a small, defensive PATCH bump instead. The rationale is that *silently*
 * ignoring an unrecognized commit risks silently ignoring a real, released change; a PATCH bump (the
 * smallest possible) plus a visible warning errs towards "the version moved, and you were told why"
 * rather than "nothing happened and nobody noticed".
 *
 * ## Configuring which types map to which bump
 *
 * Which type-word maps to which {@link Bump} is data, not hardcoded logic: it's a
 * {@code Map<Bump, Set<String>>} (see {@link #DEFAULT_TYPE_RULES}), optionally passed to
 * {@link #analyze(List, Map)} / {@link #analyzeOne(String, Map)} to override or extend it. Consumers
 * of the Gradle plugin do this via the {@code commitAnalyzer { }} extension registered by
 * duckasteroid-java.gradle (see {@link CommitAnalyzerExtension}), which exposes one
 * {@code SetProperty<String>} per bump level (`majorTypes`, `minorTypes`, `patchTypes`,
 * `noBumpTypes`) - each can be appended to (`.add(...)`/`.addAll(...)`) or replaced outright
 * (`.set(...)`) independently of the others.
 *
 * `majorTypes` defaults to *empty*, since MAJOR is normally driven by the structural '!'/BREAKING
 * CHANGE detection above rather than by type at all - it exists so a project can ALSO force specific
 * types (e.g. `security`) to always be MAJOR on their own, without needing every such commit to
 * remember the '!' marker.
 *
 * When classifying a single type, the four levels are checked from **most to least severe**
 * (MAJOR, MINOR, PATCH, NONE - see {@link #analyzeOne(String, Map)}), so if a type is (mis)configured
 * into more than one set, the highest-severity match wins: a type present in both `majorTypes` and
 * `patchTypes` resolves to MAJOR.
 *
 * See {@link io.github.duckasteroid.conventions.VersionResolver} for how the result of analyzing a
 * batch of commits gets turned into an actual version number, and {@link ChangelogGenerator} for how
 * {@link #parse(String)} - the syntactic parse, independent of typeRules - gets turned into release
 * notes.
 */
class CommitAnalyzer {

    /**
     * The four possible outcomes of analyzing a commit (or a batch of commits), in *ascending*
     * severity. Deliberately ordered this way so that "highest bump wins across a batch" can be
     * implemented as a plain ordinal() comparison (see {@link #analyze(List, Map)}) rather than a
     * hand-written precedence table.
     */
    static enum Bump {
        NONE, PATCH, MINOR, MAJOR
    }

    /**
     * The syntactic parse of a commit message, independent of any type -> bump configuration - see
     * {@link #parse(String)}. Used by {@link ChangelogGenerator} to render release notes; also used
     * internally by {@link #analyzeOne(String, Map)}.
     */
    static final class ParsedCommit {
        /** True if there was nothing to parse at all (a null/blank message, e.g. --allow-empty-message). */
        final boolean empty
        /** True if the message matched the "type[(scope)][!]: description" subject-line shape. */
        final boolean conforms
        /** Lowercase type word (e.g. "feat"), or null if !conforms or empty. */
        final String type
        /** Scope text without parens (e.g. "api" from "feat(api):"), or null if absent/!conforms/empty. */
        final String scope
        /** The subject-line description after the colon; the trimmed first line verbatim if !conforms. */
        final String description
        /** '!' marker on the subject line, or a BREAKING CHANGE/BREAKING-CHANGE footer anywhere in the body. */
        final boolean breaking

        ParsedCommit(boolean empty, boolean conforms, String type, String scope, String description, boolean breaking) {
            this.empty = empty
            this.conforms = conforms
            this.type = type
            this.scope = scope
            this.description = description
            this.breaking = breaking
        }
    }

    /**
     * The built-in type -> bump mapping, used whenever a caller doesn't supply its own. See the
     * class doc for why MAJOR's default set is empty.
     */
    public static final Map<Bump, Set<String>> DEFAULT_TYPE_RULES = Collections.unmodifiableMap([
            (Bump.MAJOR): Collections.<String> emptySet(),
            (Bump.MINOR): (['feat'] as Set<String>).asImmutable(),
            (Bump.PATCH): (['fix', 'perf'] as Set<String>).asImmutable(),
            (Bump.NONE) : (['docs', 'style', 'refactor', 'test', 'chore', 'build', 'ci'] as Set<String>).asImmutable(),
    ])

    // Bump levels in the order they're checked against a type: most severe first, so that a type
    // present in more than one set (through misconfiguration) resolves to the highest one.
    private static final List<Bump> SEVERITY_DESCENDING =
            Collections.unmodifiableList([Bump.MAJOR, Bump.MINOR, Bump.PATCH, Bump.NONE])

    // Matches "<type>[(<scope>)][!]: <description>" against just the SUBJECT LINE (the first line of
    // the message) - group 1 = type, group 3 = scope text (without parens), group 4 = the optional
    // '!', group 5 = the description text. Deliberately scoped to the first line only (unlike the
    // message as a whole) so the captured description doesn't swallow the body/footer too.
    private static final Pattern SUBJECT_PATTERN =
            Pattern.compile(/^(\w+)(\(([^)]+)\))?(!)?:\s*(.*)$/)

    // The Conventional Commits spec defines a BREAKING CHANGE footer as a line starting with
    // literally "BREAKING CHANGE:" or "BREAKING-CHANGE:" (case-sensitive), which can appear
    // anywhere in the message body/footer, not just as the first line - hence MULTILINE + '^' rather
    // than anchoring to the start of the whole message.
    private static final Pattern BREAKING_FOOTER_PATTERN =
            Pattern.compile(/^BREAKING[ -]CHANGE:/, Pattern.MULTILINE)

    /**
     * The overall bump for a range of commits (e.g. every commit since the last release): the
     * highest individual {@link #analyzeOne(String, Map)} result across the whole batch wins, so a
     * single {@code feat!:} commit among ten {@code chore:} commits still means MAJOR. An empty
     * batch is Bump.NONE, meaning "nothing here warrants a new version" - the caller then decides
     * what to do with that (VersionResolver leaves the base version unchanged in that case).
     *
     * @param typeRules see {@link #DEFAULT_TYPE_RULES}; defaults to the built-in mapping
     */
    static Bump analyze(List<String> messages, Map<Bump, Set<String>> typeRules = DEFAULT_TYPE_RULES) {
        Bump result = Bump.NONE
        messages.each { String message ->
            Bump bump = analyzeOne(message, typeRules)
            if (bump.ordinal() > result.ordinal()) {
                result = bump
            }
        }
        return result
    }

    /**
     * Classifies a single full commit message (subject + blank line + body/footer, if any).
     *
     * @param typeRules see {@link #DEFAULT_TYPE_RULES}; defaults to the built-in mapping
     */
    static Bump analyzeOne(String message, Map<Bump, Set<String>> typeRules = DEFAULT_TYPE_RULES) {
        ParsedCommit parsed = parse(message)
        if (parsed.empty) {
            // Nothing to analyze at all - not the same thing as a non-conforming message, so no
            // warning and no defensive bump here.
            return Bump.NONE
        }
        if (parsed.breaking) {
            // Breaking-ness always wins over the type-rule mapping below, per spec: "fix!:" or a
            // fix with a BREAKING CHANGE footer is MAJOR, not PATCH - unconditionally, regardless
            // of what typeRules says, since this is the one structural (non-type-based) signal.
            return Bump.MAJOR
        }
        if (parsed.conforms) {
            for (Bump bump : SEVERITY_DESCENDING) {
                if (typeRules.getOrDefault(bump, Collections.<String> emptySet()).contains(parsed.type)) {
                    return bump
                }
            }
        }
        // Either didn't conform to "type: description" at all, or conformed but used a type that
        // isn't in any configured set (a typo, or a team convention this ruleset doesn't know about)
        // - equally non-conforming either way.
        return warnNonConforming(message.trim())
    }

    /**
     * The syntactic parse of a commit message - type/scope/description/breaking-ness - independent
     * of any type -> bump configuration. See {@link ParsedCommit}.
     */
    static ParsedCommit parse(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new ParsedCommit(true, false, null, null, '', false)
        }
        String trimmed = message.trim()
        int newlineIndex = trimmed.indexOf('\n')
        String subjectLine = (newlineIndex == -1 ? trimmed : trimmed.substring(0, newlineIndex)).trim()
        boolean breakingFooter = BREAKING_FOOTER_PATTERN.matcher(trimmed).find()

        Matcher matcher = SUBJECT_PATTERN.matcher(subjectLine)
        if (!matcher.matches()) {
            return new ParsedCommit(false, false, null, null, subjectLine, breakingFooter)
        }
        String type = matcher.group(1).toLowerCase()
        String scope = matcher.group(3)
        boolean breakingMarker = matcher.group(4) == '!'
        String description = matcher.group(5)
        return new ParsedCommit(false, true, type, scope, description, breakingMarker || breakingFooter)
    }

    private static Bump warnNonConforming(String message) {
        String firstLine = message.readLines().find { !it.trim().isEmpty() } ?: message
        System.err.println(
                "WARNING: commit message does not follow Conventional Commits - treating as a PATCH bump: "
                        + firstLine)
        return Bump.PATCH
    }
}
