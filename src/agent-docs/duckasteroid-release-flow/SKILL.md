---
description: Opt-in duckAsteroid Gradle release-engineering plugin implementing a develop/release/main git flow, with RC tagging, final-release promotion, changelog generation, and GitHub Actions workflow install/staleness-check tasks. Apply alongside duckasteroid-java, whose VersionResolver it reuses.
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

Six tasks total, all plain Gradle tasks, runnable locally as well as from CI:

- **`tagReleaseCandidate`** — tags/pushes the next `X.Y.Z-RCn` (auto-incrementing `n`), derived
  from `VersionResolver` (or `-Prelease.forceVersion` if set). Intended to run on every push to
  `release`.
- **`promoteReleaseCandidate`** — strips the `-RCn` suffix off the nearest reachable RC tag and
  tags/pushes the final `X.Y.Z` (or `-Prelease.forceVersion` if set). Intended to run on every push
  to `main`.
- **`changelogForReleaseCandidate`** / **`changelogForRelease`** — generate Markdown release notes
  to `build/changelog.md`, for a CI step to feed into `gh release create --notes-file`. These are
  deliberately separate from the tag/promote tasks (so notes can be previewed locally before
  actually cutting a release), but **must run before** their tagging counterpart in the same job —
  they look for the *previous* RC tag / the last *final* tag reachable from HEAD, which the
  about-to-be-created new tag would otherwise shadow.
- **`installReleaseWorkflows`** — installs the two GitHub Actions workflows this plugin's tasks are
  meant to run from (`.github/workflows/release-candidate.yml` / `promote-release.yml`, bundled
  with the plugin) into the consumer project. Never clobbers a file it doesn't recognize as its
  own:
  - No file at that path → installs it.
  - File present, no `# duckasteroid-workflow-version: ...` marker comment as the first line →
    treated as foreign/hand-written → **skipped**, with a warning.
  - File present, marker found, its `sha256:` hash matches the file's current body → untouched
    since install → **overwritten** with the current template.
  - File present, marker found, hash does **not** match → edited locally since install →
    **skipped**, with a warning pointing at `-Pduckasteroid.workflows.force=true` to discard those
    edits and take the new version anyway.
  - The one per-consumer variable (the Java toolchain version) is filled in from the applied `java`
    toolchain at install time — no manual editing needed after install.
- **`checkReleaseWorkflows`** — read-only counterpart to `installReleaseWorkflows`: warns (never
  fails the build) if an installed workflow file is missing, has no marker, has been edited since
  install (hash mismatch), or was installed from an older `duckasteroid-release-flow` version than
  what's currently applied (stale — re-run `installReleaseWorkflows` to update it). Not wired into
  `build`/`check`, so it never adds noise to an ordinary CI run; run it explicitly (e.g. as an
  occasional local/CI sanity check) instead.

The marker's hash is a self-attestation, not a tamper-proof checksum — it lives in the same file it
verifies, so it only guards against the realistic accident (editing a step without touching the
marker comment), not someone deliberately recomputing a matching hash. That's an accepted tradeoff,
not a bug.

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

Run `./gradlew installReleaseWorkflows` once (locally, or as a one-off task) to install the two
workflow files below into `.github/workflows/` — re-run it after upgrading the plugin to pick up
template changes.

- On push to `release`: `changelogForReleaseCandidate` (must run first) then `tagReleaseCandidate`,
  then `publish`.
- On push to `main`: `changelogForRelease` (must run first) then `promoteReleaseCandidate`, then
  `publish`.

Both jobs should be self-contained (tag-then-publish in one job) — a tag pushed with the default
`GITHUB_TOKEN` does not trigger other workflow runs, so a separate publish-on-tag workflow would
silently never fire.
