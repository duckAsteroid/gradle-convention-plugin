package io.github.duckasteroid.conventions

import groovy.json.JsonOutput

/**
 * The {@code build/release-manifest.json} file written by the {@code tagReleaseCandidates} /
 * {@code promoteReleaseCandidates} aggregator tasks (duckasteroid-release-flow.gradle) - one entry
 * per project that actually got tagged on a given run, read back by the bundled GitHub Actions
 * workflow to turn into one {@code gh release create} per entry, instead of guessing a single tag
 * via {@code git describe --tags --exact-match HEAD} (which only ever worked when exactly one
 * project released per push). See "The manifest" in MULTI_MODULE_RELEASE_FLOW.md for the format.
 *
 * A plain class with no Gradle dependency, like {@link VersionResolver}/{@link ChangelogGenerator} -
 * kept separate from the aggregator tasks themselves so the JSON shape is independently testable.
 */
class ReleaseManifest {

    static class Entry {
        final String module
        final String tag
        final String changelog
        final List<String> supersededTags

        Entry(String module, String tag, String changelog, List<String> supersededTags = []) {
            this.module = module
            this.tag = tag
            this.changelog = changelog
            this.supersededTags = supersededTags
        }

        Map<String, Object> toMap() {
            // LinkedHashMap (Groovy map literals preserve insertion order) so the JSON key order
            // always matches the documented example: module, tag, changelog, supersededTags.
            return [module: module, tag: tag, changelog: changelog, supersededTags: supersededTags]
        }
    }

    private final List<Entry> entries = []

    /**
     * @param module the Gradle project path, e.g. ":" for the root or ":api" for a subproject
     * @param tag the full tag name that was just created, including its prefix
     * @param changelog the generated changelog's path, relative to the repo root
     * @param supersededTags this cycle's previous release-candidate tags whose GitHub Release
     *        should be deleted now that {@code tag} supersedes them (see the {@code releaseCandidates
     *        { } } extension) - empty when pruning is disabled or nothing qualifies. The tags
     *        themselves are never deleted, only their GitHub Release.
     */
    void add(String module, String tag, String changelog, List<String> supersededTags = []) {
        entries << new Entry(module, tag, changelog, supersededTags)
    }

    boolean isEmpty() {
        return entries.isEmpty()
    }

    int size() {
        return entries.size()
    }

    String toJson() {
        // JsonOutput.prettyPrint("[]") renders as "[\n    \n]" (a blank indented line inside the
        // brackets) rather than the compact "[]" one might expect - harmless to jq either way, but
        // special-cased here so an empty manifest reads cleanly.
        if (entries.isEmpty()) {
            return '[]'
        }
        return JsonOutput.prettyPrint(JsonOutput.toJson(entries.collect { it.toMap() }))
    }

    /** Writes this manifest as JSON to file, creating parent directories as needed. */
    void writeTo(File file) {
        file.parentFile?.mkdirs()
        file.text = toJson()
    }
}
