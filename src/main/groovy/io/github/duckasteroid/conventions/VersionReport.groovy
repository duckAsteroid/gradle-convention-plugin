package io.github.duckasteroid.conventions

import groovy.json.JsonOutput

/**
 * Shared per-module version-analysis snapshot behind explainVersion/explainVersions (see issue #5) -
 * both tasks' console output and their build/version-report.json file are built from exactly the
 * same {@link Entry}, so the two can never drift from each other, and {@link #buildEntry} calls the
 * exact same {@link VersionResolver} methods tagReleaseCandidate(s) itself calls, so the preview can
 * never drift from what tagging would actually do either.
 *
 * A plain class with no Gradle dependency, like {@link VersionResolver}/{@link CommitAnalyzer}/
 * {@link ReleaseManifest} - independently unit-testable against a real throwaway git repo.
 */
class VersionReport {

    /** One commit's syntactic parse plus the bump it resolves to - see {@link CommitAnalyzer}. */
    static final class CommitBreakdown {
        final String type
        final String scope
        final String description
        final boolean breaking
        final String bump

        CommitBreakdown(String type, String scope, String description, boolean breaking, String bump) {
            this.type = type
            this.scope = scope
            this.description = description
            this.breaking = breaking
            this.bump = bump
        }

        Map<String, Object> toMap() {
            return [type: type, scope: scope, description: description, breaking: breaking, bump: bump]
        }
    }

    /**
     * One project's full version-analysis breakdown. {@code action}/{@code reason} are left unset
     * (and omitted from the JSON) for a lone {@link #buildEntry} result - explainVersion has nothing
     * to decide, it just reports. explainVersions sets them after the fact to record its own
     * skip/tag decision for that module, mirroring tagReleaseCandidates' own decision so the two
     * task can never disagree about which modules qualify.
     */
    static final class Entry {
        final String module
        final String tagPrefix
        final String modulePath
        final String lastFinal
        final List<CommitBreakdown> commits
        final String bump
        final String candidate
        final String forceVersion
        final String nextReleaseCandidateTag
        final String buildVersion
        String action
        String reason

        Entry(String module, String tagPrefix, String modulePath, String lastFinal, List<CommitBreakdown> commits,
              String bump, String candidate, String forceVersion, String nextReleaseCandidateTag, String buildVersion) {
            this.module = module
            this.tagPrefix = tagPrefix
            this.modulePath = modulePath
            this.lastFinal = lastFinal
            this.commits = commits
            this.bump = bump
            this.candidate = candidate
            this.forceVersion = forceVersion
            this.nextReleaseCandidateTag = nextReleaseCandidateTag
            this.buildVersion = buildVersion
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = [
                    module                 : module,
                    tagPrefix              : tagPrefix,
                    modulePath             : modulePath,
                    lastFinal              : lastFinal,
                    commits                : commits.collect { it.toMap() },
                    bump                   : bump,
                    candidate              : candidate,
                    forceVersion           : forceVersion,
                    nextReleaseCandidateTag: nextReleaseCandidateTag,
                    buildVersion           : buildVersion,
            ]
            if (action != null) {
                map.action = action
                map.reason = reason
            }
            return map
        }

        /** Single-entry JSON, e.g. for explainVersion's build/version-report.json. */
        String toJson() {
            return JsonOutput.prettyPrint(JsonOutput.toJson(toMap()))
        }

        /** Writes {@link #toJson()} to file, creating parent directories as needed. */
        void writeTo(File file) {
            file.parentFile?.mkdirs()
            file.text = toJson()
        }

        /** Human-readable console breakdown printed by both explainVersion and explainVersions. */
        String render() {
            StringBuilder out = new StringBuilder()
            out.append("${module}: last final release ${tagPrefix}${lastFinal}\n")
            if (commits.isEmpty()) {
                out.append("  no qualifying commits since ${tagPrefix}${lastFinal}\n")
            } else {
                commits.each { CommitBreakdown c ->
                    String scopePart = c.scope ? "(${c.scope})" : ''
                    String breakingMarker = c.breaking ? '!' : ''
                    out.append("  - [${c.bump}] ${c.type ?: '?'}${scopePart}${breakingMarker}: ${c.description}\n")
                }
            }
            out.append("  bump: ${bump}\n")
            out.append("  candidate: ${tagPrefix}${candidate}")
            out.append(forceVersion ? " (release.forceVersion=${forceVersion})\n" : "\n")
            out.append("  next release-candidate tag: ${nextReleaseCandidateTag}\n")
            out.append("  ordinary build version right now: ${buildVersion}\n")
            return out.toString()
        }
    }

    private VersionReport() {}

    /**
     * Builds one project's {@link Entry} by calling the exact same {@link VersionResolver} methods
     * tagReleaseCandidate(s) itself calls - see that task's doLast for the call sequence this
     * mirrors. Never tags, pushes, or writes anything other than the returned Entry.
     *
     * @param excludedModulePaths see {@link VersionResolver#resolveBuildVersion} - pass {@code []}
     *        for a single-project computation (matching how the singular tagReleaseCandidate/
     *        ordinary-build version resolution never apply exclusions either), or the applying
     *        project's own exclusions (from releaseFlowTargets) for the multi-module aggregator.
     */
    static Entry buildEntry(String module, File repoDir, String tagPrefix, String modulePath,
                             List<String> fallbackPrefixes, Map<CommitAnalyzer.Bump, Set<String>> typeRules,
                             List<String> excludedModulePaths, String forceVersion, String branchName) {
        String lastFinal = VersionResolver.lastFinalVersion(repoDir, tagPrefix, fallbackPrefixes)
        // The exact same commit range resolveCandidateVersion's own bump computation uses - see
        // VersionResolver.commitMessagesForChangelog's SINCE_LAST_RELEASE path vs.
        // resolveCandidateVersionIn's internal lookup, which locate the same "since" boundary the
        // same way and both end up calling commitMessagesSince with identical arguments.
        List<String> messages = VersionResolver.commitMessagesForChangelog(
                repoDir, tagPrefix, modulePath, fallbackPrefixes, ChangelogScope.SINCE_LAST_RELEASE, excludedModulePaths)
        CommitAnalyzer.Bump overallBump = CommitAnalyzer.Bump.NONE
        List<CommitBreakdown> commits = messages.collect { String message ->
            CommitAnalyzer.ParsedCommit parsed = CommitAnalyzer.parse(message)
            CommitAnalyzer.Bump bump = CommitAnalyzer.analyzeOne(message, typeRules)
            if (bump.ordinal() > overallBump.ordinal()) {
                overallBump = bump
            }
            new CommitBreakdown(parsed.type, parsed.scope, parsed.description, parsed.breaking, bump.name())
        }
        String candidate = forceVersion ?: VersionResolver.resolveCandidateVersion(
                repoDir, tagPrefix, modulePath, fallbackPrefixes, typeRules, excludedModulePaths)
        // Reused verbatim - see the class doc comment - so this can never report a tag that
        // tagReleaseCandidate(s) wouldn't actually mint right now.
        String nextTag = VersionResolver.nextReleaseCandidateTag(repoDir, tagPrefix, candidate)
        String buildVersion = VersionResolver.resolveBuildVersion(repoDir, tagPrefix, branchName, modulePath, fallbackPrefixes, typeRules)
        return new Entry(module, tagPrefix, modulePath, lastFinal, commits, overallBump.name(),
                candidate, forceVersion, nextTag, buildVersion)
    }

    /** JSON array of entries, e.g. for explainVersions' build/version-report.json - "[]" when empty. */
    static String toJsonArray(List<Entry> entries) {
        if (entries.isEmpty()) {
            return '[]'
        }
        return JsonOutput.prettyPrint(JsonOutput.toJson(entries.collect { it.toMap() }))
    }

    /** Writes {@link #toJsonArray} to file, creating parent directories as needed. */
    static void writeJsonArray(List<Entry> entries, File file) {
        file.parentFile?.mkdirs()
        file.text = toJsonArray(entries)
    }
}
