package io.github.duckasteroid.conventions

import java.security.MessageDigest

/**
 * The "# duckasteroid-workflow-version: X sha256:Y" marker comment that installReleaseWorkflows
 * (duckasteroid-release-flow.gradle) stamps as the first line of every workflow file it installs,
 * and that checkReleaseWorkflows later re-parses to detect staleness/tampering - see issue #2 for
 * the full design. The hash covers everything BELOW the marker line (the templated workflow
 * body), never the marker line itself - a self-attestation, not a tamper-proof checksum (see the
 * issue's "known limitation" section for why that's an accepted tradeoff).
 */
class WorkflowMarker {

    public static final String PREFIX = '# duckasteroid-workflow-version: '
    private static final String SHA_MARKER = ' sha256:'

    final String version
    final String sha256

    WorkflowMarker(String version, String sha256) {
        this.version = version
        this.sha256 = sha256
    }

    String render() {
        "${PREFIX}${version}${SHA_MARKER}${sha256}"
    }

    boolean matchesBody(String body) {
        sha256 == sha256Of(body)
    }

    static WorkflowMarker forBody(String version, String body) {
        new WorkflowMarker(version, sha256Of(body))
    }

    static String sha256Of(String body) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        digest.digest(body.getBytes('UTF-8')).collect { String.format('%02x', it) }.join()
    }

    /** Parses a marker line, or returns null if it isn't one (foreign/hand-written file). */
    static WorkflowMarker parse(String firstLine) {
        if (firstLine == null || !firstLine.startsWith(PREFIX)) {
            return null
        }
        String rest = firstLine.substring(PREFIX.length())
        int shaIdx = rest.indexOf(SHA_MARKER)
        if (shaIdx < 0) {
            return null
        }
        new WorkflowMarker(rest.substring(0, shaIdx), rest.substring(shaIdx + SHA_MARKER.length()).trim())
    }
}
