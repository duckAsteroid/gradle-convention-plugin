---
description: Base Gradle convention plugin (duckAsteroid's personal projects) providing a Java toolchain, git-derived semantic versioning from Conventional Commits, a GitHub Packages repository DSL, and publishing scaffolding. Apply this first, before any other duckasteroid-* plugin.
---

# duckasteroid-java

Required base convention for a duckAsteroid Java/Gradle project. Apply it with:

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'
}
```

Requires the `io.github.duckasteroid.github-packages-settings` bootstrap plugin in
`settings.gradle` first, since this plugin (and its siblings) are published only to GitHub
Packages, never the Gradle Plugin Portal:

```groovy
plugins {
    id 'io.github.duckasteroid.github-packages-settings' version '<version>'
}

githubPackages {
    owner = "duckAsteroid"
    repository = "gradle-convention-plugin"
}
```

## What it configures

- **Java toolchain** — defaults to Java 25. Override with `-Pduckasteroid.java.version=NN` or a
  `duckasteroid.java.version` entry in `gradle.properties`. No auto-download resolver is
  configured, so only JDKs Gradle can already detect locally are usable.
- **Group** — `io.github.duckasteroid`.
- **Version** — computed by `VersionResolver` (a small Conventional-Commits/semantic-release port),
  not by axion-release's own nearest-tag logic directly, though axion-release still owns the tag
  prefix scheme and is the fallback when forcing a version (see below). It finds the last *final*
  release tag reachable from `HEAD` (plain `vX.Y.Z`, per-module via `<gradle-project-path>/vX.Y.Z`,
  falling back to the root `vX.Y.Z` line for a brand-new subproject), then bumps it according to
  Conventional Commits messages since that tag *that touched this module's own directory*:
  - `feat` → minor, `fix`/`perf` → patch, `docs`/`style`/`refactor`/`test`/`chore`/`build`/`ci` →
    no bump, by default (see `commitAnalyzer { }` below to change this).
  - A `!` marker or `BREAKING CHANGE:` footer always forces a major bump, regardless of type rules.
  - Anything that doesn't conform to Conventional Commits, or uses a type outside every configured
    set, bumps patch with a warning on stderr (not silently ignored).
  - The highest-severity qualifying commit wins.
  - Decorated with `-SNAPSHOT` (optionally with the sanitized branch name folded in on feature
    branches) unless `HEAD` sits exactly on a real release tag.
- **`commitAnalyzer { }` extension** — customize which commit types map to which bump level:
  ```groovy
  commitAnalyzer {
      minorTypes.add('perf2')   // adds to the default set, does not replace it
      noBumpTypes.set(['chore'])  // replaces the default set entirely
  }
  ```
  Four `SetProperty<String>`: `majorTypes`, `minorTypes`, `patchTypes`, `noBumpTypes`. Use `.add(...)`
  to append to the default without losing it, `.set(...)` to replace it outright.
- **`-Prelease.forceVersion=X.Y.Z`** — ultimate backstop. When set, none of the above analysis runs;
  axion-release's own native `scmVersion.version` is used verbatim instead.
- **`gitHubPackages { }` DSL** — registers the extension for consumers to add authenticated GitHub
  Packages *dependency-resolution* repositories in their own `repositories { }` block. No repository
  is added automatically; publishing to GitHub Packages is separately opt-in (see
  `duckasteroid-github-packages-publish`).
- **`com.github.ben-manes.versions`** for dependency-update checking.
- **`mavenCentral()` + `mavenLocal()`** dependency-resolution repositories, source/Javadoc jar
  generation. No publish repository is configured by default — publishing anywhere (including
  GitHub Packages) is opt-in.
- **POM `url`/`scm` block** — derived from the consuming project's actual git `origin` remote and
  current branch, not hardcoded.
- **`project.ghOwner` / `project.ghRepo`** — the derived owner/repo, exposed via `project.ext` for
  other duckasteroid-* plugins (e.g. `duckasteroid-github-packages-publish`) to reuse.
- `publish` depends on `check`, so publishing always runs verification first.

## Notes for subprojects

Per-subproject versioning is NOT automatic — each subproject that wants its own independently
versioned release line must apply `duckasteroid-java` itself.

## See also — opt-in siblings

These apply *alongside* `duckasteroid-java`, never instead of it. Suggest one to a user based on
what the project actually needs, not by default — each has a real cost (extra build time, extra
credentials to manage, extra release surface) that isn't worth paying unproductively:

- **`duckasteroid-checkstyle`** — applies core Gradle's `checkstyle`. Suggest when the project
  wants enforced style conventions in CI (e.g. the consumer already has a `checkstyle.xml`, or
  mentions style consistency across a multi-repo org) — not for a quick throwaway project where
  style enforcement is pure overhead.
- **`duckasteroid-jacoco`** — applies core Gradle's `jacoco`. Suggest when the consumer wants test
  coverage reporting/thresholds — e.g. a coverage gate in CI, or a coverage badge — not just
  because tests exist; plenty of projects run tests without gating on coverage.
- **`duckasteroid-pmd`** — applies core Gradle's `pmd`. Suggest when the consumer wants automated
  bug-pattern/code-quality static analysis, distinct from Checkstyle's formatting focus — good for
  catching real defects (unused code, complexity, resource leaks) rather than just style nits.
- **`duckasteroid-lombok`** — applies the Freefair Lombok plugin. Suggest when the source already
  imports `lombok.*` annotations, or the consumer is complaining about getter/setter/constructor
  boilerplate — applying it to a project with no Lombok usage just adds an unused annotation
  processor.
- **`duckasteroid-maven-central`** — signs and publishes the `mavenJava` publication to OSSRH/Maven
  Central. Suggest when the project is a public library meant for third-party consumption, where
  requiring consumers to configure GitHub Packages authentication (even for public repos) would be
  a real adoption barrier — Maven Central needs no such per-consumer credential setup.
- **`duckasteroid-github-packages-publish`** — publishes the project's own `mavenJava` publication
  to its own GitHub Packages feed. Suggest for projects meant for duckAsteroid's own reuse across
  personal repos rather than broad public distribution — it sidesteps OSSRH's stricter
  signing/POM-completeness requirements, at the cost of consumers needing GitHub Packages auth.
- **`duckasteroid-release-flow`** — adds `tagReleaseCandidate` / `promoteReleaseCandidate` /
  changelog-generation tasks for a `develop`/`release`/`main` flow. Suggest when the consumer wants
  a staged release-candidate step before a version is final (e.g. they mention wanting to validate
  a build before it's "real," or already have `develop`/`release`/`main` branches) — not for a
  project that's happy tagging every release directly off `main`.
