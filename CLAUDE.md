# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A set of Gradle plugins (precompiled script plugins, written in Groovy) that provide shared build conventions for
the author's other projects. Consumers apply them by ID, e.g. `id 'duckasteroid-java'`, instead of copy-pasting
build logic. Every plugin here is scoped to the author's own (`duckAsteroid`) projects — e.g. the GitHub Packages
repo is hardcoded to owner `duckAsteroid` — this is not a general-purpose third-party plugin suite.

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
    see its own repo for details) — both used to configure the `GitHubPackages` publish repository
  - `mavenCentral()` + `mavenLocal()` repositories, source/Javadoc jar generation, Maven publishing to both `OSSRH`
    (Sonatype/Maven Central staging) and `GitHubPackages`, plus artifact signing
  - `publish` depends on `check`, so publishing always runs verification first (`build.gradle:63-65`)
- `duckasteroid-checkstyle.gradle`, `duckasteroid-jacoco.gradle`, `duckasteroid-pmd.gradle` — thin opt-in plugins,
  each just applying the one corresponding core Gradle plugin. Consumers add the ones they want alongside
  `duckasteroid-java`.
- `duckasteroid-lombok.gradle` — opt-in plugin applying `io.freefair.lombok`.
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
`main` using Temurin JDK 20. `.github/workflows/publish.yml` runs `./gradlew publish` on GitHub release creation.
There is no separate lint-only command; checkstyle/pmd run as part of `build`/`check`.
