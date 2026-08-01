package io.github.duckasteroid.conventions

/**
 * Plain-file/process helpers for duckasteroid-release-flow.gradle's doLast blocks - changelog
 * writing, tagging/pushing via the git CLI, and the manifest's relative-path formatting.
 *
 * Under the configuration cache, a doLast closure is serialized at store time and rebuilt at
 * execution time with the task itself as owner/delegate rather than the original script instance -
 * so an *unqualified* call to a script-level method, even a `static` one, resolves dynamically
 * against the task's metaclass and misses (MissingMethodException). A qualified static call to an
 * external class (`ReleaseGitOps.writeChangelog(...)`, same pattern as `VersionResolver.foo(...)`
 * elsewhere in duckasteroid-release-flow.gradle) isn't dynamically dispatched at all - Groovy binds
 * it directly to the class - so these live here rather than as top-level methods in the script.
 */
class ReleaseGitOps {
    private ReleaseGitOps() {}

    /** Writes generated changelog Markdown to outputFile, creating parent directories as needed. */
    static void writeChangelog(File outputFile, String changelog) {
        outputFile.parentFile.mkdirs()
        outputFile.text = changelog
        println "Changelog written to ${outputFile}"
    }

    /** Creates an annotated tag at HEAD and pushes it to `origin` - the only state-mutating step here. */
    static void createAndPushTag(File repoDir, String tag) {
        runGit(repoDir, 'tag', '-a', tag, '-m', "Release ${tag}")
        runGit(repoDir, 'push', 'origin', tag)
    }

    /** file's path relative to base, with forward slashes regardless of OS - for the JSON manifest. */
    static String relativePath(File base, File file) {
        return base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/' as char)
    }

    /**
     * Plain ProcessBuilder rather than project.exec (unsupported under the configuration cache from
     * a task action at execution time) or VersionResolver's JGit plumbing (pushing a tag needs real
     * push credentials - the `git` CLI transparently reuses whatever's already configured, both
     * locally and via actions/checkout's persisted GITHUB_TOKEN in CI, with no extra credential
     * wiring needed here).
     */
    static void runGit(File repoDir, String... args) {
        List<String> command = ['git'] + (args as List<String>)
        Process process = new ProcessBuilder(command).directory(repoDir).redirectErrorStream(true).start()
        String output = process.inputStream.getText('UTF-8')
        int exit = process.waitFor()
        if (exit != 0) {
            throw new RuntimeException("git ${args.join(' ')} failed (${exit}): ${output.trim()}")
        }
    }
}
