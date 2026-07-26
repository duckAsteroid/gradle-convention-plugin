---
description: Opt-in duckAsteroid Gradle release-engineering plugin implementing a develop/release/main git flow, with RC tagging, final-release promotion, and changelog generation tasks. Apply alongside duckasteroid-java, whose VersionResolver it reuses.
---

# duckasteroid-release-flow

Opt-in release-engineering plugin for a `develop`/`release` → `main` git flow: `release`
accumulates release-candidate builds before an accepted RC is promoted to a final release on
`main`.

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'          // required first
    id 'duckasteroid-release-flow' version '<version>'
}
```

## Tasks

All four reuse `VersionResolver` (the tagging two also respect the `-Prelease.forceVersion`
backstop) and are plain Gradle tasks, runnable locally as well as from CI:

- **`tagReleaseCandidate`** — tags/pushes the next `X.Y.Z-RCn` (auto-incrementing `n`). Intended to
  run on every push to `release`.
- **`promoteReleaseCandidate`** — strips the `-RCn` suffix off the nearest reachable RC tag and
  tags/pushes the final `X.Y.Z`. Intended to run on every push to `main`.
- **`changelogForReleaseCandidate`** / **`changelogForRelease`** — generate Markdown release notes
  to `build/changelog.md`, for a CI step to feed into `gh release create --notes-file`. These are
  deliberately separate from the tag/promote tasks (so notes can be previewed locally before
  actually cutting a release), but **must run before** their tagging counterpart in the same job —
  they look for the *previous* RC tag / the last *final* tag reachable from HEAD, which the
  about-to-be-created new tag would otherwise shadow.

## `changelog { }` extension

Controls `changelogForReleaseCandidate`'s "since" boundary via `rcScope`:

```groovy
changelog {
    rcScope = ChangelogScope.SINCE_PREVIOUS_RC  // default: SINCE_LAST_RELEASE
}
```

- `SINCE_LAST_RELEASE` (default) — the whole release cycle so far.
- `SINCE_PREVIOUS_RC` — just the delta since the previous RC, falling back to
  `SINCE_LAST_RELEASE` automatically when there's no previous RC yet.

`changelogForRelease` always uses `SINCE_LAST_RELEASE`, regardless of this setting — a final
release's notes should be the complete picture.

## Typical CI wiring

- On push to `release`: `changelogForReleaseCandidate` (must run first) then `tagReleaseCandidate`,
  then `publish`.
- On push to `main`: `changelogForRelease` (must run first) then `promoteReleaseCandidate`, then
  `publish`.

Both jobs should be self-contained (tag-then-publish in one job) — a tag pushed with the default
`GITHUB_TOKEN` does not trigger other workflow runs, so a separate publish-on-tag workflow would
silently never fire.
