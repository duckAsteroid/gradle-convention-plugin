---
description: Computes project.version from git tags plus Conventional Commits (a small semantic-release commit-analyzer port), independent of the java plugin. Auto-applied by duckasteroid-java; apply directly for a non-Java project, or alongside duckasteroid-release-flow without duckasteroid-java.
---

# duckasteroid-version

Computes `project.version` from git history rather than a hardcoded string or a single nearest tag.
Applied automatically by `duckasteroid-java` (existing consumers of that plugin get everything below
for free, no changes needed) — apply it directly only when you want this versioning scheme without
the rest of `duckasteroid-java`'s Java/POM/publishing conventions, e.g. a non-Java Gradle project, or
`duckasteroid-release-flow` on its own:

```groovy
plugins {
    id 'duckasteroid-version' version '<version>'
}
```

Requires the `io.github.duckasteroid.github-packages-settings` bootstrap plugin in
`settings.gradle` first (see `duckasteroid-java`'s own doc for the snippet) — published only to
GitHub Packages, never the Gradle Plugin Portal.

## What it computes

Finds the last *final* release tag reachable from `HEAD` (plain `vX.Y.Z`, per-module via
`<gradle-project-path>/vX.Y.Z`, falling back to the root `vX.Y.Z` line for a brand-new subproject),
then bumps it according to Conventional Commits messages since that tag *that touched this module's
own directory* — computed by `VersionResolver`/`CommitAnalyzer`, both plain Groovy classes with no
dependency on the `java` plugin:

- `feat` → minor, `fix`/`perf` → patch, `docs`/`style`/`refactor`/`test`/`chore`/`build`/`ci` → no
  bump, by default (see `commitAnalyzer { }` below to change this).
- A `!` marker or `BREAKING CHANGE:` footer always forces a major bump, regardless of type rules.
- Anything that doesn't conform to Conventional Commits, or uses a type outside every configured
  set, bumps patch with a warning on stderr (not silently ignored).
- The highest-severity qualifying commit wins.
- Decorated with `-SNAPSHOT` (optionally with the sanitized branch name folded in on feature
  branches) unless `HEAD` sits exactly on a real release tag.

Still applies and configures [axion-release](https://github.com/allegro/axion-release-plugin) — it
owns the raw per-module tag-prefix scheme (`scmVersion.tag`) and is what runs verbatim when
`-Prelease.forceVersion` is set (see below); axion's own *default* version computation (nearest tag,
`-SNAPSHOT` if not exactly on it) doesn't distinguish a bug fix from a breaking change, which is
exactly what the Conventional-Commits analysis above is for.

## `commitAnalyzer { }` extension

Customize which commit types map to which bump level:

```groovy
commitAnalyzer {
    minorTypes.add('perf2')     // adds to the default set, does not replace it
    noBumpTypes.set(['chore'])  // replaces the default set entirely
}
```

Four `SetProperty<String>`: `majorTypes`, `minorTypes`, `patchTypes`, `noBumpTypes`. Use `.add(...)`
to append to the default without losing it, `.set(...)` to replace it outright.

## `-Prelease.forceVersion=X.Y.Z` backstop

Ultimate backstop. When set, none of the above analysis runs; axion-release's own native
`scmVersion.version` is used verbatim instead.

## Exposed for other plugins

- **`project.ext.tagPrefix`** — this project's own tag prefix (e.g. `v` at root, `sub/module/v` for
  a subproject).
- **`project.ext.modulePath`** — this project's directory relative to the repo root (`""` at root).

`duckasteroid-release-flow` reuses both rather than recomputing them, so its RC/promotion tagging can
never disagree with what an ordinary build's `-SNAPSHOT` version would compute for the same commit.

## Notes for subprojects

Per-subproject versioning is NOT automatic — each subproject that wants its own independently
versioned release line must apply `duckasteroid-version` (or `duckasteroid-java`) itself.
