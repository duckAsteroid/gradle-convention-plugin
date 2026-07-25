# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A set of Gradle plugins (precompiled script plugins, written in Groovy) that provide shared build conventions for
the author's other projects. Consumers apply them by ID, e.g. `id 'duckasteroid-java'`, instead of copy-pasting
build logic. This is not a general-purpose third-party plugin suite — group `io.github.duckasteroid`, POM
developer metadata, etc. are all specific to the author.

The repo builds *itself* with `pl.allegro.tech.build.axion-release`, so the root `build.gradle` is both the build
of this plugin project and (via `groovy-gradle-plugin`) the mechanism that compiles `src/main/groovy/*.gradle`
files into applicable plugins. Each `.gradle` file's basename becomes its plugin ID automatically — no manual
`gradlePlugin { plugins { register(...) } }` block is needed.

**Publishing target is deliberately GitHub Packages only.** `com.gradle.plugin-publish` is applied (for the plugin
metadata/marker artifacts it generates), but nothing invokes `./gradlew publishPlugins`, and that's intentional —
this suite is not meant to be published to the Gradle Plugin Portal, only to GitHub Packages via `./gradlew publish`
(see `.github/workflows/publish.yml`). Don't wire up `publishPlugins` without checking with the author first.

## Architecture

The convention plugins are split so consumers can opt into only what they want, rather than one monolithic plugin:

- `duckasteroid-java.gradle` (ID `duckasteroid-java`) — the base/required convention. Applies:
  - A Java toolchain, defaulting to **Java 25**, overridable per-consumer via `-Pduckasteroid.java.version=NN`
    or a `gradle.properties` entry (no auto-download resolver is configured — this only picks up JDKs Gradle can
    already detect on the machine)
  - Group `io.github.duckasteroid`
  - Version derived from git via a **per-module tag prefix** derived from the Gradle project path (e.g.
    `sub/module/v1.2.3` instead of the default `v1.2.3`), falling back to a plain `v` prefix at the root. This
    lets a multi-module consumer version each subproject independently. This is NOT auto-applied to
    subprojects — each subproject must apply `duckasteroid-java` itself to get its own version.
  - `project.version` is computed by `VersionResolver`
    (`src/main/groovy/io/github/duckasteroid/conventions/VersionResolver.groovy`), a small port of the
    semantic-release [`commit-analyzer`](https://github.com/semantic-release/commit-analyzer) concept (parsing
    logic in the sibling `CommitAnalyzer.groovy`): find the last *final* release tag (plain `vX.Y.Z`, no
    suffix) reachable from `HEAD` — falling back to each of `fallbackPrefixes` in turn (just `['v']`, i.e. the
    root project's tags, passed from `duckasteroid-java.gradle`) if this module has no tag of its own yet, so a
    brand-new subproject inherits the root's version line instead of starting over at `0.0.0` — then bump it
    according to the Conventional Commits messages since that tag *that touched this module's own directory*
    (`modulePath`, computed as `project.projectDir` relativized against `rootProject.projectDir` and passed
    through; equivalent to `git log <range> -- modulePath`, via JGit's `Git.log().addPath(...)` porcelain
    command) — `feat` → minor, `fix`/`perf` → patch, `!` marker or `BREAKING CHANGE:` footer → major, highest
    bump across the qualifying commits wins. This is what keeps a `feat:` commit in one subproject from
    bumping an unrelated sibling subproject's version in the same monorepo. Decorated `-SNAPSHOT` (optionally
    with the sanitized branch name folded in on feature branches) unless `HEAD` sits exactly on a real tag.
    Both classes are plain Groovy classes with no Gradle dependency and are unit-tested directly
    (`CommitAnalyzerTest`, `VersionResolverTest` — the latter builds a real throwaway git repo per test rather
    than mocking git, including fixtures with real file changes under different subdirectories to exercise the
    path-scoping). They talk to git via **JGit**, not the `git` CLI, specifically because this computation runs
    at Gradle *configuration* time (`version = ...`), where starting an external process is incompatible with
    the configuration cache — axion-release's own JGit usage is why the pre-existing `ghOwner`/`ghBranch`
    detection below has always worked the same way.
  - `modulePath` is also exposed as `project.ext.modulePath`, next to `project.ext.tagPrefix`, for
    `duckasteroid-release-flow`'s `tagReleaseCandidate` task to reuse (it calls `resolveCandidateVersion` with
    the same `modulePath`/`fallbackPrefixes` so RC candidate computation matches ordinary-build computation).
  - `-Prelease.forceVersion=X.Y.Z` is the ultimate backstop: if set, none of the above analysis runs at all and
    axion-release's own native handling (`scmVersion.version`) is used verbatim — see
    [axion's force_version docs](https://axion-release-plugin.readthedocs.io/en/latest/configuration/force_version/).
    axion-release itself is still applied and still owns raw tag prefix configuration/discovery; it's just no
    longer what computes `project.version` day-to-day.
  - `com.github.ben-manes.versions` (dependency-update checking) and `io.github.duckasteroid.github-packages`
    (the author's own plugin for an authenticated GitHub Packages Maven repo, with a 3-tier credential fallback —
    see its own repo for details). Applied here **only** to register the `gitHubPackages { owner = ...; repo = ... }`
    DSL for consumers to use in their own dependency-resolution `repositories { }` block — no repository is added
    by default, and consumers can add as many `gitHubPackages { }` entries as they need for whichever repos host
    their dependencies. Actually *publishing* to GitHub Packages is opt-in — see
    `duckasteroid-github-packages-publish` below.
  - `mavenCentral()` + `mavenLocal()` repositories for dependency resolution, source/Javadoc jar generation. No
    publish repository is configured by default — not even GitHub Packages — so applying just `duckasteroid-java`
    leaves `publish` with nothing to push to except `mavenLocal`. Maven Central/OSSRH publishing+signing and
    GitHub Packages publishing are both opt-in (see below) so consumers aren't forced into a publish target they
    don't want.
  - The POM's project `url` and `scm` block (connection/developerConnection/url) are derived at configuration
    time from the consuming project's actual git `origin` remote and current branch (via JGit, transitively on
    the classpath through axion-release) rather than hardcoded — this fixes a real bug where the old code assumed
    `github.com/duckAsteroid/${rootProject.name}` and branch `master` unconditionally, which is wrong whenever
    the repo name doesn't match `rootProject.name` or the default branch isn't `master` (this repo's own default
    branch is `main`, for instance). Falls back to the old `duckAsteroid`/`rootProject.name`/`main` assumptions
    only when there's no real git checkout to read (e.g. `JavaConventionsPluginTest`'s in-memory `ProjectBuilder`
    project) so the fallback never fires for a real consumer.
  - The derived owner/repo are also exposed as `project.ghOwner` / `project.ghRepo` (via `project.ext`) so other
    duckasteroid-* convention plugins applied alongside this one (e.g. `duckasteroid-github-packages-publish`) can
    reuse them without recomputing.
  - `publish` depends on `check`, so publishing always runs verification first (`build.gradle:63-65`)
- `duckasteroid-checkstyle.gradle`, `duckasteroid-jacoco.gradle`, `duckasteroid-pmd.gradle` — thin opt-in plugins,
  each just applying the one corresponding core Gradle plugin. Consumers add the ones they want alongside
  `duckasteroid-java`.
- `duckasteroid-lombok.gradle` — opt-in plugin applying `io.freefair.lombok`.
- `duckasteroid-maven-central.gradle` — opt-in plugin for consumers that also need to publish to Maven Central.
  Applies `signing`, signs the `mavenJava` publication, and adds the `OSSRH` publishing repository (release vs.
  snapshot URL picked based on whether `version` ends in `SNAPSHOT`; credentials from the `ossrhUsername`/
  `ossrhPassword` project properties). Apply alongside `duckasteroid-java`, which must already have created the
  `mavenJava` publication.
- `duckasteroid-github-packages-publish.gradle` — opt-in plugin to publish to *this* project's own GitHub Packages
  feed (`project.ghOwner` / `project.ghRepo`, as derived above — not a hardcoded owner). Relies on
  `io.github.duckasteroid.github-packages` v0.2.0+ (pinned in the root `build.gradle` dependency), which fixed
  [gradle-github-packages#3](https://github.com/duckAsteroid/gradle-github-packages/issues/3) — earlier versions
  had the `gitHubPackages { }` closure always target `project.repositories` regardless of which `repositories { }`
  block it was invoked in, so calling it inside `publishing.repositories { }` silently added the repo to the wrong
  list and `publish` pushed nowhere. Verified working end-to-end with a scratch consumer project
  (`publishMavenJavaPublicationToGitHubPackages-<repo>Repository` task appears, targeting the correct derived URL).
  (There used to be a separate duckAsteroid-hardcoded `duckasteroid-github-packages.gradle` alongside a
  self-targeting `duckasteroid-github-packages-self.gradle` — merged into just this one file, renamed to
  `-publish` to describe what it does, since the hardcoded variant wasn't needed.)
- `duckasteroid-release-flow.gradle` — opt-in release-engineering plugin for a
  `develop`/`release` → `main` git flow: `release` accumulates release-candidate builds before an accepted RC
  is promoted to a final release on `main`. Two tasks, both reusing `VersionResolver` and both respecting the
  `release.forceVersion` backstop, both plain Gradle tasks runnable locally as well as from CI:
  - `tagReleaseCandidate` — tags/pushes the next `X.Y.Z-RCn` (auto-incrementing `n`); intended to run on every
    push to `release`.
  - `promoteReleaseCandidate` — strips the `-RCn` suffix off the nearest reachable RC tag and tags/pushes the
    final `X.Y.Z`; intended to run on every push to `main`.
  - `.github/workflows/release-candidate.yml` and `.github/workflows/promote-release.yml` wire these up,
    triggered on push to `release`/`main` respectively. Each is a **single self-contained job** (tag, create
    the GitHub Release, `gradlew publish`) rather than relying on the tag push to trigger `publish.yml` — a tag
    pushed with the default `GITHUB_TOKEN` doesn't trigger other workflow runs, the same reason `publish.yml`
    combines release-creation and publishing into one job (see below). `publish.yml` (triggered directly by
    any pushed `v*` tag) is left untouched as a fallback for manually-pushed tags.
  - The `develop` branch has **not** been renamed to `release` yet (deferred by the author) —
    `build-java.yml` still lists both branch names, and `release-candidate.yml` simply won't fire until/unless
    that rename happens.
- `src/test/java/JavaConventionsPluginTest.java` — uses `ProjectBuilder` + Gradle TestKit to apply the plugin to
  an in-memory project and assert specific plugins/config landed, rather than a full end-to-end build.
- `build.gradle` (root) — builds the plugin JAR itself: applies `groovy-gradle-plugin`, `com.gradle.plugin-publish`,
  `com.github.ben-manes.versions`, and `axion-release` for its own versioning. Targets Java 21 for the plugin
  project itself — separate from the toolchain version `duckasteroid-java.gradle` configures for consumers.
  Any external plugin used *inside* a precompiled script plugin (e.g. axion-release, ben-manes versions, lombok,
  github-packages) must also be declared as an `implementation` dependency here using its marker-artifact
  coordinates (`<pluginId>:<pluginId>.gradle.plugin:<version>`) — declaring it only in the top-level `plugins {}`
  block of this file is not enough to make it resolvable from `src/main/groovy/*.gradle`.
  - Similarly, plain library dependencies used by *regular classes* under `src/main/groovy` (e.g.
    `VersionResolver.groovy`/`CommitAnalyzer.groovy`, as opposed to the auto-registered `.gradle` script
    plugins) also need their own explicit `implementation` entry even when a plugin above already pulls the
    same library in transitively — axion-release only exposes JGit on the *runtime* classpath, not `compile`,
    so `VersionResolver.groovy` needed its own pinned `org.eclipse.jgit:org.eclipse.jgit` dependency (matching
    the version axion-release already resolves) to compile at all, even though the `.gradle` script plugins
    could already reference JGit classes without it (precompiled script plugins get a broader implicit
    classpath than plain class files in the same source set).

## Versioning scheme (important, non-obvious)

A consumer project applying `duckasteroid-java` gets its version computed by `VersionResolver` from
Conventional Commits since the last final release tag (see Architecture above) — not directly from
axion-release's own nearest-tag logic, though axion-release still owns the tag prefix scheme
(`{gradle project path}/v{number}`, root path collapses to just `v{number}`) and is still the fallback when
`-Prelease.forceVersion` is set. Don't hardcode versions in `build.gradle`; a release is made by pushing a
matching git tag — either by hand, or via the `duckasteroid-release-flow` opt-in plugin's `tagReleaseCandidate`
/ `promoteReleaseCandidate` tasks.

This repo's *own* version (root `build.gradle`) is unaffected by any of this — it doesn't apply
`duckasteroid-java` to itself, and still just uses axion-release directly (see `./gradlew currentVersion`
below).

## Common commands

```bash
./gradlew build          # compile, run tests, assemble the plugin jar
./gradlew test           # run tests only
./gradlew test --tests JavaConventionsPluginTest   # run the single test class
./gradlew check          # verification tasks (currently just test), no publish
./gradlew publish        # publish this repo's own jar to GitHub Packages (+ local dir, mavenLocal); depends on check
./gradlew currentVersion # show axion-release's OWN computation for this repo's own build - unrelated to
                         # VersionResolver, since this repo doesn't apply duckasteroid-java to itself
```

For a *consumer* project applying `duckasteroid-java`, there's no dedicated "show me the version" task — any
normal task shows it, e.g. `./gradlew properties -q | grep '^version:'`. If `duckasteroid-release-flow` is also
applied: `./gradlew tagReleaseCandidate` / `./gradlew promoteReleaseCandidate` (both runnable locally, both
respect `-Prelease.forceVersion`).

CI (`.github/workflows/build-java.yml`) runs `./gradlew build` on pushes to `feature/**`, `develop`, `release`,
`main` using Temurin JDK 20. There is no separate lint-only command.

**Releases are controlled entirely by git tags.** Pushing a tag matching `v*` triggers
`.github/workflows/publish.yml`, which creates a GitHub Release from that tag (`gh release create`) and then runs
`./gradlew publish` in the *same job*. Both steps deliberately run in one workflow/job rather than as two workflows
chained via the `release: created` event — a workflow run triggered by the default `GITHUB_TOKEN` does not trigger
other workflows, so a separate release-creation workflow would silently fail to kick off the publish step. Don't
split this back into two workflows without switching the release-creation step to a PAT. The same constraint is
why `.github/workflows/release-candidate.yml` and `promote-release.yml` (triggered on push to `release`/`main`)
are each a single self-contained job rather than relying on the tag they push to trigger `publish.yml` — see the
`duckasteroid-release-flow.gradle` bullet above.
