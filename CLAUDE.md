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
  - Version derived from git tags via axion-release, with a **per-module tag prefix** derived from the Gradle
    project path (e.g. `sub/module/v1.2.3` instead of the default `v1.2.3`), falling back to a plain `v` prefix
    at the root. This lets a multi-module consumer version each subproject independently from git tags. This is
    NOT auto-applied to subprojects — each subproject must apply `duckasteroid-java` itself to get its own version.
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
- `src/test/java/JavaConventionsPluginTest.java` — uses `ProjectBuilder` + Gradle TestKit to apply the plugin to
  an in-memory project and assert specific plugins/config landed, rather than a full end-to-end build.
- `build.gradle` (root) — builds the plugin JAR itself: applies `groovy-gradle-plugin`, `com.gradle.plugin-publish`,
  `com.github.ben-manes.versions`, and `axion-release` for its own versioning. Targets Java 21 for the plugin
  project itself — separate from the toolchain version `duckasteroid-java.gradle` configures for consumers.
  Any external plugin used *inside* a precompiled script plugin (e.g. axion-release, ben-manes versions, lombok,
  github-packages) must also be declared as an `implementation` dependency here using its marker-artifact
  coordinates (`<pluginId>:<pluginId>.gradle.plugin:<version>`) — declaring it only in the top-level `plugins {}`
  block of this file is not enough to make it resolvable from `src/main/groovy/*.gradle`.

## Versioning scheme (important, non-obvious)

Both this repo's own version and any version assigned to a consumer project applying `duckasteroid-java` come
from git tags via axion-release, using the pattern `{gradle project path}/v{number}` (root path collapses to
just `v{number}`). Don't hardcode versions in `build.gradle`; a release is made by pushing a matching git tag.

## Common commands

```bash
./gradlew build          # compile, run tests, assemble the plugin jar
./gradlew test           # run tests only
./gradlew test --tests JavaConventionsPluginTest   # run the single test class
./gradlew check          # verification tasks (currently just test), no publish
./gradlew publish        # publish this repo's own jar to GitHub Packages (+ local dir, mavenLocal); depends on check
./gradlew currentVersion # show the axion-release-derived version
```

CI (`.github/workflows/build-java.yml`) runs `./gradlew build` on pushes to `feature/**`, `develop`, `release`,
`main` using Temurin JDK 20. There is no separate lint-only command.

**Releases are controlled entirely by git tags.** Pushing a tag matching `v*` triggers
`.github/workflows/publish.yml`, which creates a GitHub Release from that tag (`gh release create`) and then runs
`./gradlew publish` in the *same job*. Both steps deliberately run in one workflow/job rather than as two workflows
chained via the `release: created` event — a workflow run triggered by the default `GITHUB_TOKEN` does not trigger
other workflows, so a separate release-creation workflow would silently fail to kick off the publish step. Don't
split this back into two workflows without switching the release-creation step to a PAT.
