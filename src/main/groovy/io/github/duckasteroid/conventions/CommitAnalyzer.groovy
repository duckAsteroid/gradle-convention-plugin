package io.github.duckasteroid.conventions

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Minimal Groovy port of the semantic-release commit-analyzer concept
 * (https://github.com/semantic-release/commit-analyzer), scoped to the default
 * Conventional Commits / Angular rule set: feat -> minor, fix/perf -> patch,
 * a '!' marker or a BREAKING CHANGE footer -> major, anything else -> no bump.
 */
class CommitAnalyzer {

    static enum Bump {
        NONE, PATCH, MINOR, MAJOR
    }

    private static final Pattern TYPE_PATTERN =
            Pattern.compile(/^(\w+)(\([^)]+\))?(!)?:\s*.*/, Pattern.DOTALL)
    private static final Pattern BREAKING_FOOTER_PATTERN =
            Pattern.compile(/^BREAKING[ -]CHANGE:/, Pattern.MULTILINE)

    /** Highest bump found across a batch of full commit messages (subject + body + footer). */
    static Bump analyze(List<String> messages) {
        Bump result = Bump.NONE
        messages.each { String message ->
            Bump bump = analyzeOne(message)
            if (bump.ordinal() > result.ordinal()) {
                result = bump
            }
        }
        return result
    }

    static Bump analyzeOne(String message) {
        if (message == null || message.trim().isEmpty()) {
            return Bump.NONE
        }
        String trimmed = message.trim()
        Matcher matcher = TYPE_PATTERN.matcher(trimmed)
        if (!matcher.matches()) {
            return Bump.NONE
        }
        String type = matcher.group(1).toLowerCase()
        boolean breakingMarker = matcher.group(3) == '!'
        boolean breakingFooter = BREAKING_FOOTER_PATTERN.matcher(trimmed).find()
        if (breakingMarker || breakingFooter) {
            return Bump.MAJOR
        }
        switch (type) {
            case 'feat':
                return Bump.MINOR
            case 'fix':
            case 'perf':
                return Bump.PATCH
            default:
                return Bump.NONE
        }
    }
}
