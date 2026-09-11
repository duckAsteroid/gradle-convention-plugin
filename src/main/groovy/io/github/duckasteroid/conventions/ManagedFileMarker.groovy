package io.github.duckasteroid.conventions

import java.security.MessageDigest

/**
 * The "# duckasteroid-managed: componentId version sha256:hash" marker comment that a
 * `ManagedFileInstaller.install(...)` call stamps as the first line of every file it installs, and
 * that a matching `ManagedFileChecker.check(...)` call later re-parses to detect staleness/
 * tampering - see issue #2 for the original design (then scoped to just the two release-flow
 * workflow files; generalized so any duckasteroid-* plugin can install/track its own versioned,
 * marker-stamped file the same way, without misreading a different component's marker as its own).
 * The hash covers everything BELOW the marker line (the templated body), never the marker line
 * itself - a self-attestation, not a tamper-proof checksum (see issue #2's "known limitation"
 * section for why that's an accepted tradeoff).
 *
 * `componentId` namespaces the marker by whichever plugin/feature installed the file (e.g.
 * `"release-flow"`) - `duckasteroid-release-flow.gradle`'s `installReleaseWorkflows` is the only
 * caller today, but namespacing from the start means a second installer (e.g. a
 * `duckasteroid-java`-owned composite action, discussed separately) can never have its file
 * misread as belonging to a different component, or vice versa.
 */
class ManagedFileMarker {

    public static final String PREFIX = '# duckasteroid-managed: '
    private static final String SHA_MARKER = ' sha256:'

    final String componentId
    final String version
    final String sha256

    ManagedFileMarker(String componentId, String version, String sha256) {
        this.componentId = componentId
        this.version = version
        this.sha256 = sha256
    }

    String render() {
        "${PREFIX}${componentId} ${version}${SHA_MARKER}${sha256}"
    }

    boolean matchesBody(String body) {
        sha256 == sha256Of(body)
    }

    static ManagedFileMarker forBody(String componentId, String version, String body) {
        new ManagedFileMarker(componentId, version, sha256Of(body))
    }

    static String sha256Of(String body) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        digest.digest(body.getBytes('UTF-8')).collect { String.format('%02x', it) }.join()
    }

    /** Parses a marker line, or returns null if it isn't one (foreign/hand-written file). */
    static ManagedFileMarker parse(String firstLine) {
        if (firstLine == null || !firstLine.startsWith(PREFIX)) {
            return null
        }
        String rest = firstLine.substring(PREFIX.length())
        int spaceIdx = rest.indexOf(' ')
        if (spaceIdx < 0) {
            return null
        }
        String componentId = rest.substring(0, spaceIdx)
        String remainder = rest.substring(spaceIdx + 1)
        int shaIdx = remainder.indexOf(SHA_MARKER)
        if (shaIdx < 0) {
            return null
        }
        new ManagedFileMarker(componentId, remainder.substring(0, shaIdx),
                remainder.substring(shaIdx + SHA_MARKER.length()).trim())
    }
}
