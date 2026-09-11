package io.github.duckasteroid.conventions

/**
 * `checkReleaseWorkflows` (duckasteroid-release-flow.gradle)'s read-only counterpart to
 * `ManagedFileInstaller`: parses the marker left by a matching `install(...)` call and reports
 * whether the installed file is missing, foreign (no marker, or a marker for a different
 * componentId), edited-since-install, stale, or up to date. Never mutates anything. Plain File
 * I/O, no Gradle dependency - see issue #2.
 */
class ManagedFileChecker {

    enum Status {
        MISSING, NOT_OURS, TAMPERED, STALE, UP_TO_DATE
    }

    static class CheckResult {
        final Status status
        final String installedVersion

        CheckResult(Status status, String installedVersion) {
            this.status = status
            this.installedVersion = installedVersion
        }
    }

    static CheckResult check(File target, String componentId, String currentVersion) {
        if (!target.exists()) {
            return new CheckResult(Status.MISSING, null)
        }
        String content = target.text
        int newlineIdx = content.indexOf('\n')
        String firstLine = newlineIdx < 0 ? content : content.substring(0, newlineIdx)
        ManagedFileMarker marker = ManagedFileMarker.parse(firstLine)
        if (marker == null || marker.componentId != componentId) {
            return new CheckResult(Status.NOT_OURS, null)
        }
        String body = newlineIdx < 0 ? '' : content.substring(newlineIdx + 1)
        if (!marker.matchesBody(body)) {
            return new CheckResult(Status.TAMPERED, marker.version)
        }
        if (marker.version != currentVersion) {
            return new CheckResult(Status.STALE, marker.version)
        }
        return new CheckResult(Status.UP_TO_DATE, marker.version)
    }
}
