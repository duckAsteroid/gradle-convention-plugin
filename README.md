# DuckAsteroid's Gradle Conventions Plugin
Gradle plugins that provide conventions for my other projects.

## `duckasteroid-java`

The base convention plugin for Java projects. Apply it via `id 'duckasteroid-java'`. It provides:

* Java 25 toolchain by default, overridable via `-Pduckasteroid.java.version` (or a `gradle.properties` entry)
* Group `io.github.duckasteroid`
* Versioning derived entirely from git — no version string is ever committed to `build.gradle`
  (see [VERSIONING.md](VERSIONING.md) for the full explanation, worked examples, and the release flow)
  * The expected tag prefix is derived from the Gradle project path, so each project's version is `${gradle project path}/v{number}`. For example a subproject at `:sub:module` looks for tags like `sub/module/v1.2.3`, while the root project just looks for `v1.2.3`
  * This makes versioning **multi-module friendly**: each module in a multi-project build can be tagged and released independently, without bumping the version of unrelated modules in the same repo
    * A module with no tag of its own yet falls back to the root project's plain `v` tag as its starting point
      (rather than starting over at `0.0.0`), mirroring axion-release's own `fallbackPrefixes`
    * Only commits that actually touched the module's own directory count towards its version bump (equivalent
      to `git log -- <module path>`) — a `feat:` commit in a sibling module doesn't bump this module's minor
      version
  * The version itself is computed by a small built-in port of the semantic-release
    [`commit-analyzer`](https://github.com/semantic-release/commit-analyzer) concept: find the last final release tag
    (a plain `vX.Y.Z`, no suffix) reachable from `HEAD` (falling back per module as above), then look at the
    [Conventional Commits](https://www.conventionalcommits.org/) messages since that tag that touched this module —
    `fix:`/`perf:` bump the patch number, `feat:` bumps minor, and a `!` marker (e.g. `feat!:`) or a
    `BREAKING CHANGE:` footer bumps major, with the highest bump across all the qualifying commits winning. So
    `1.0.0` + a `feat:` commit computes as `1.1.0`, decorated `1.1.0-SNAPSHOT` for ordinary builds unless `HEAD`
    sits exactly on a real tag (feature branches get their sanitized branch name folded in too, e.g.
    `1.0.1-cool-stuff-SNAPSHOT`). This works identically on any branch — `develop`, a feature branch, or `main`
    between releases — and survives squash-merges for free, since GitHub's squash commit message defaults to the PR
    title, so the convention rides along on whichever commit actually lands.
  * `-Prelease.forceVersion=X.Y.Z` remains the ultimate backstop, exactly as documented by axion-release
    ([force_version docs](https://axion-release-plugin.readthedocs.io/en/latest/configuration/force_version/)): if
    set, none of the above analysis runs at all and the version is used verbatim.
  * Which commit types map to which bump is configurable via the `commitAnalyzer { }` extension —
    `majorTypes`/`minorTypes`/`patchTypes`/`noBumpTypes`, each a `SetProperty<String>` pre-populated with the
    built-in defaults (`majorTypes` starts empty; major is normally driven by `!`/`BREAKING CHANGE:` instead).
    Append to one with `.add(...)`/`.addAll(...)`, or replace it outright with `.set(...)`:
    ```groovy
    commitAnalyzer {
        majorTypes.add('security')  // "security: ..." commits are always a major bump
        minorTypes.add('perf2')     // an extra type that also means minor
        noBumpTypes.set(['docs'])   // REPLACES the default no-bump set - only 'docs' is no-bump now
    }
    ```
  * Useful tasks: `./gradlew currentVersion` shows axion-release's own (unrelated) tag-based computation; the
    project's actual `version` is printed by any normal task, e.g. `./gradlew properties -q | grep '^version:'`.
    See `duckasteroid-release-flow` below for minting actual release tags.
* Maven Central repository for dependencies
* Add source and JavaDoc to the published artifacts
* Registers the `gitHubPackages { owner = '...'; repo = '...' }` DSL (via my [gradle-github-packages](https://github.com/duckAsteroid/gradle-github-packages)
  plugin) for use in your own `repositories { }` block, so you can resolve dependencies from as many GitHub
  Packages feeds as you need. Nothing is added by default — you opt in per repo, explicitly
* Dependency update checking via the `com.github.ben-manes.versions` plugin (run `./gradlew dependencyUpdates`)

No publish repository is configured by default — not even GitHub Packages — so a project applying only
`duckasteroid-java` can only `publishToMavenLocal`. Add one of the opt-in plugins below for an actual publish
target.

## Opt-in plugins

These are not applied by `duckasteroid-java` — apply them alongside it if you want them:

* `duckasteroid-checkstyle` — applies `checkstyle`
* `duckasteroid-jacoco` — applies `jacoco`
* `duckasteroid-pmd` — applies `pmd`
* `duckasteroid-lombok` — applies the [Freefair Lombok plugin](https://github.com/freefair/gradle-plugins) (`io.freefair.lombok`)
* `duckasteroid-maven-central` — for projects that also need to publish to Maven Central. Adds the `signing` plugin,
  signs the `mavenJava` publication, and adds the `OSSRH` publishing repository (staging/snapshot URLs picked based
  on whether the version ends in `SNAPSHOT`, credentials from the `ossrhUsername`/`ossrhPassword` project properties).
  Not needed for GitHub-Packages-only projects — signing keys and Central are otherwise required for every publish.
* `duckasteroid-github-packages-publish` — publish to *this* project's own GitHub Packages feed (owner/repo derived
  from its git `origin` remote).
* `duckasteroid-release-flow` — release-engineering tasks for a `develop`/`release` → `main` git flow, where
  `release` accumulates release-candidate builds before an accepted RC is promoted to a final release on `main`:
  * `tagReleaseCandidate` — tags and pushes the next `X.Y.Z-RCn` (auto-incrementing `n`), derived from the same
    conventional-commit analysis as the ordinary build version (or `release.forceVersion` if set). Intended to run
    on every push to `release`.
  * `promoteReleaseCandidate` — strips the `-RCn` suffix off the nearest reachable RC tag and tags/pushes the final
    `X.Y.Z` (or `release.forceVersion` if set). Intended to run on every push to `main`.
  * `changelogForReleaseCandidate` / `changelogForRelease` — generate grouped Markdown release notes (Breaking
    Changes/Features/Bug Fixes, from the same commits and `typeRules` as the version bump) to `build/changelog.md`,
    for feeding into `gh release create --notes-file`. Each must run *before* its tagging/promoting counterpart in
    the same job. `changelogForReleaseCandidate`'s scope (whole cycle so far, or just the delta since the previous
    RC) is configurable via `changelog { rcScope = ... }` — see [VERSIONING.md](VERSIONING.md) for details.
  * `installReleaseWorkflows` — installs the `release-candidate.yml`/`promote-release.yml` GitHub Actions workflows
    (bundled with the plugin, templated with your project's Java toolchain version) into `.github/workflows/`.
    Stamps each installed file with a `# duckasteroid-workflow-version: X sha256:Y` marker comment and never
    overwrites a file it doesn't recognize as its own or one you've edited since install (skip + warn either way) —
    pass `-Pduckasteroid.workflows.force=true` to discard local edits and take the new version anyway.
  * `checkReleaseWorkflows` — read-only: warns (never fails) if an installed workflow is missing, unmarked, edited
    since install, or older than the currently applied plugin version. Not wired into `build`/`check`; run it
    explicitly.
  * All six are plain Gradle tasks, runnable locally as well as from CI — release engineering doesn't hard-depend
    on GitHub Actions being available. This repo's own `.github/workflows/release-candidate.yml` /
    `promote-release.yml` are a readable, human-facing reference for what `installReleaseWorkflows` installs
    (identical apart from the pinned Java 21 toolchain — see [VERSIONING.md](VERSIONING.md)).
