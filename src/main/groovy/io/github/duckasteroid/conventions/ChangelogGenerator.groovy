package io.github.duckasteroid.conventions

/**
 * Turns a list of commit messages into grouped Markdown release notes, the same idea implemented by
 * tools like conventional-changelog and git-cliff: group by the {@link CommitAnalyzer.Bump} each
 * commit resolves to (using the same {@code typeRules} the version bump itself uses - see
 * {@link CommitAnalyzerExtension}), not by the raw commit type word, so a custom `majorTypes` entry
 * (e.g. `security`) ends up under Breaking Changes exactly like a `!`-marked commit would, and a
 * `noBumpTypes` entry (docs/chore/etc.) is omitted exactly like it doesn't affect the version.
 *
 * A non-conforming commit (see {@link CommitAnalyzer}) still gets a PATCH-bump classification (and
 * still prints its stderr warning, since {@link CommitAnalyzer#analyzeOne} is what's called here) -
 * it appears under Bug Fixes using its raw first line as the description, consistent with it having
 * actually bumped the version.
 *
 * A plain class with no Gradle dependency, like {@link CommitAnalyzer} and {@link VersionResolver} -
 * see {@link VersionResolver#commitMessagesForChangelog} for how the commit list itself is obtained.
 */
class ChangelogGenerator {

    private static final String NO_CHANGES_TEXT = 'No user-facing changes.'

    /**
     * @param messages full commit messages (subject + body/footer), typically from
     *        {@link VersionResolver#commitMessagesForChangelog}
     * @param typeRules see {@link CommitAnalyzer#DEFAULT_TYPE_RULES}; should be the same rules used
     *        for the version bump itself so the changelog and the version agree
     */
    static String generate(List<String> messages, Map<CommitAnalyzer.Bump, Set<String>> typeRules = CommitAnalyzer.DEFAULT_TYPE_RULES) {
        List<CommitAnalyzer.ParsedCommit> breaking = []
        List<CommitAnalyzer.ParsedCommit> features = []
        List<CommitAnalyzer.ParsedCommit> fixes = []

        messages.each { String message ->
            // parse() (display text) and analyzeOne() (bump/section routing) each re-parse the
            // message rather than sharing one pass - deliberately kept simple/decoupled rather than
            // threading a richer "parsed + bump" result through, since commit counts per release are
            // small (dozens, not millions) and the extra parse is negligible. One side effect worth
            // knowing: if VersionResolver's own version computation ran in the same process for the
            // same commit range (as it does, back-to-back, in tagReleaseCandidate/
            // promoteReleaseCandidate), a non-conforming commit's stderr warning prints twice - once
            // from there, once from analyzeOne() here. Harmless, just slightly noisy.
            CommitAnalyzer.ParsedCommit parsed = CommitAnalyzer.parse(message)
            if (parsed.empty) {
                return
            }
            switch (CommitAnalyzer.analyzeOne(message, typeRules)) {
                case CommitAnalyzer.Bump.MAJOR:
                    breaking << parsed
                    break
                case CommitAnalyzer.Bump.MINOR:
                    features << parsed
                    break
                case CommitAnalyzer.Bump.PATCH:
                    fixes << parsed
                    break
                default:
                    // Bump.NONE - deliberately no-release types (docs/chore/etc.) don't get a line.
                    break
            }
        }

        StringBuilder out = new StringBuilder()
        appendSection(out, 'Breaking Changes', breaking)
        appendSection(out, 'Features', features)
        appendSection(out, 'Bug Fixes', fixes)

        String result = out.toString().trim()
        return result.isEmpty() ? NO_CHANGES_TEXT : result
    }

    private static void appendSection(StringBuilder out, String title, List<CommitAnalyzer.ParsedCommit> commits) {
        if (commits.isEmpty()) {
            return
        }
        if (out.length() > 0) {
            // Each commit line already ends with its own '\n', so only one more is needed here to
            // get a single blank line between sections, not two.
            out.append('\n')
        }
        out.append("## ${title}\n")
        commits.each { CommitAnalyzer.ParsedCommit commit ->
            out.append('- ')
            if (commit.scope) {
                out.append("**${commit.scope}:** ")
            }
            out.append(commit.description)
            out.append('\n')
        }
    }
}
