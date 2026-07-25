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
 * batch of commits gets turned into an actual version number.
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

    // Matches "<type>[(<scope>)][!]: <description...>" against the WHOLE message (Pattern.DOTALL
    // makes '.' also match newlines, so the trailing ".*" swallows the body/footer too - we only
    // care about capturing group 1 (type) and group 3 (the optional '!'), the rest of the message
    // is handled separately by BREAKING_FOOTER_PATTERN below). A message that doesn't start with
    // this shape at all (e.g. "Merge pull request #1 ...", a merge commit's default message) fails
    // to match entirely, which is one of the two "non-conforming" cases handled in analyzeOne.
    private static final Pattern TYPE_PATTERN =
            Pattern.compile(/^(\w+)(\([^)]+\))?(!)?:\s*.*/, Pattern.DOTALL)

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
        if (message == null || message.trim().isEmpty()) {
            // Nothing to analyze at all (e.g. an --allow-empty-message commit) - not the same thing
            // as a non-conforming message, so no warning and no defensive bump here.
            return Bump.NONE
        }
        String trimmed = message.trim()
        Matcher matcher = TYPE_PATTERN.matcher(trimmed)
        if (!matcher.matches()) {
            return warnNonConforming(trimmed)
        }
        String type = matcher.group(1).toLowerCase()
        boolean breakingMarker = matcher.group(3) == '!'
        boolean breakingFooter = BREAKING_FOOTER_PATTERN.matcher(trimmed).find()
        // Breaking-ness always wins over the type-rule mapping below, per spec: "fix!:" or a fix
        // with a BREAKING CHANGE footer is MAJOR, not PATCH - unconditionally, regardless of what
        // typeRules says, since this is the one structural (non-type-based) signal.
        if (breakingMarker || breakingFooter) {
            return Bump.MAJOR
        }
        for (Bump bump : SEVERITY_DESCENDING) {
            if (typeRules.getOrDefault(bump, Collections.<String> emptySet()).contains(type)) {
                return bump
            }
        }
        // Syntactically conventional-commit-shaped, but a type that isn't in any configured set
        // (a typo, or a team convention this ruleset doesn't know about) - equally non-conforming
        // as a message that doesn't match the shape at all.
        return warnNonConforming(trimmed)
    }

    private static Bump warnNonConforming(String message) {
        String firstLine = message.readLines().find { !it.trim().isEmpty() } ?: message
        System.err.println(
                "WARNING: commit message does not follow Conventional Commits - treating as a PATCH bump: "
                        + firstLine)
        return Bump.PATCH
    }
}
