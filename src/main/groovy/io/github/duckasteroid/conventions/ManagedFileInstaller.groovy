package io.github.duckasteroid.conventions

/**
 * Safe-install logic behind duckasteroid-* plugins' "install a versioned, self-updating file"
 * tasks (e.g. `installReleaseWorkflows` in duckasteroid-release-flow.gradle) - never clobber a file
 * it doesn't recognize as its own, or one edited since install, unless forced. Plain File I/O, no
 * Gradle dependency - see issue #2 for the skip/overwrite rule table this implements:
 *
 * - no file present -> install it
 * - file present, no marker, or a marker for a different componentId -> foreign/hand-written -> skip
 * - file present, marker found for this componentId, hash matches -> untouched since install -> overwrite
 * - file present, marker found for this componentId, hash mismatch -> edited since install -> skip, unless force
 */
class ManagedFileInstaller {

    enum Result {
        INSTALLED, OVERWRITTEN, FORCED, UP_TO_DATE, SKIPPED_FOREIGN, SKIPPED_MODIFIED
    }

    static Result install(File target, String componentId, String version, String body, boolean force) {
        ManagedFileMarker marker = ManagedFileMarker.forBody(componentId, version, body)
        String newContent = "${marker.render()}\n${body}"
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            target.text = newContent
            return Result.INSTALLED
        }
        String existingContent = target.text
        if (existingContent == newContent) {
            return Result.UP_TO_DATE
        }
        int newlineIdx = existingContent.indexOf('\n')
        String firstLine = newlineIdx < 0 ? existingContent : existingContent.substring(0, newlineIdx)
        ManagedFileMarker existingMarker = ManagedFileMarker.parse(firstLine)
        if (existingMarker == null || existingMarker.componentId != componentId) {
            return Result.SKIPPED_FOREIGN
        }
        String existingBody = newlineIdx < 0 ? '' : existingContent.substring(newlineIdx + 1)
        boolean unmodified = existingMarker.matchesBody(existingBody)
        if (!unmodified && !force) {
            return Result.SKIPPED_MODIFIED
        }
        target.text = newContent
        return unmodified ? Result.OVERWRITTEN : Result.FORCED
    }
}
