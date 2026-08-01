---
description: Opt-in duckAsteroid Gradle release-engineering plugin implementing a develop/release/main git flow, with RC tagging, final-release promotion, changelog generation, multi-module-aware aggregator tasks, and GitHub Actions workflow install/staleness-check tasks. Apply alongside duckasteroid-java, whose VersionResolver it reuses.
---

# duckasteroid-release-flow

Opt-in release-engineering plugin for a `develop`/`release` → `main` git flow: `release`
accumulates release-candidate builds before an accepted RC is promoted to a final release on
`main`. Works the same whether the repo releases as a single unit or as several
independently-versioned modules.

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'          // required first
    id 'duckasteroid-release-flow' version '<version>'
}
```

## Tasks

Eight tasks total, all plain Gradle tasks, runnable locally as well as from CI. Four are
single-project (act on whichever project applies the plugin); four are multi-module-aware
aggregators registered exactly once, on `rootProject`, no matter how many (or which) projects
apply the plugin.

**Single-project:**

- **`tagReleaseCandidate`** — tags/pushes the next `X.Y.Z-RCn` for *this* project alone (auto-
  incrementing `n`), derived from `VersionResolver` (or `-Prelease.forceVersion` if set). Useful
  for local, single-module work.
- **`promoteReleaseCandidate`** — strips the `-RCn` suffix off *this* project's nearest reachable
  RC tag and tags/pushes the final `X.Y.Z` (or `-Prelease.forceVersion` if set).
- **`changelogForReleaseCandidate`** / **`changelogForRelease`** — generate Markdown release notes
  for *this* project to `build/changelog.md`. Only needed for local preview or the single-project
  singular tasks above — the aggregator tasks below generate each project's changelog internally.

**Multi-module aggregators (what CI actually calls):**

- **`tagReleaseCandidates`** — the task the bundled `release-candidate.yml` workflow runs on every
  push to `release`. Enumerates every project in the build with `duckasteroid-release-flow`
  applied, computes each one's candidate version exactly as an ordinary build would
  (`VersionResolver`, using that project's own `tagPrefix`/`modulePath`), skips any project whose
  candidate equals its last-final version (nothing qualifying changed under its own directory),
  and for every project that *does* qualify: generates its changelog, mints and pushes its own RC
  tag, and records `{module, tag, changelog, supersededTags}` in `build/release-manifest.json` at
  the repo root. A push touching one module produces one manifest entry; a push touching several
  produces several, each versioned independently; a push with no qualifying commits anywhere
  produces an empty manifest (not an error). `supersededTags` — see `releaseCandidates { }` below
  — lists this cycle's previous RC tags that the new one replaces, for the bundled workflow to
  delete their GitHub Releases.
- **`promoteReleaseCandidates`** — the `main`-push counterpart. Same enumeration, but promotes
  each applying project's nearest reachable RC tag to final; a project with no pending RC this
  cycle is skipped rather than failing the whole task.
- **`installReleaseWorkflows`** — installs the two GitHub Actions workflows the aggregator tasks
  above are meant to run from (`.github/workflows/release-candidate.yml` /
  `promote-release.yml`, bundled with the plugin) into the consumer project. Registered once on
  `rootProject` — applying the plugin to several subprojects doesn't install multiple copies or
  race on the same two files. Never clobbers a file it doesn't recognize as its own:
  - No file at that path → installs it.
  - File present, no `# duckasteroid-workflow-version: ...` marker comment as the first line →
    treated as foreign/hand-written → **skipped**, with a warning.
  - File present, marker found, its `sha256:` hash matches the file's current body → untouched
    since install → **overwritten** with the current template.
  - File present, marker found, hash does **not** match → edited locally since install →
    **skipped**, with a warning pointing at `-Pduckasteroid.workflows.force=true` to discard those
    edits and take the new version anyway.
  - The one per-consumer variable (the Java toolchain version) is filled in from whichever
    applying project's script wins the registration guard (normally root, if root applies
    `duckasteroid-java`) — see "Multi-module scoping" below for the limitation this implies when
    applying projects use different Java versions.
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

## Multi-module scoping

Apply `duckasteroid-release-flow` to whichever projects should be independently releasable — just
the root, just a subset of subprojects, or every project. Three shapes fall out of the same
mechanism (see `MULTI_MODULE_RELEASE_FLOW.md` for the full writeup with worked examples):

- **Fully independent modules** — apply only to subprojects, not root. A push touching `:api`
  alone releases `:api` alone; `:web` is silently skipped.
- **One global version for the whole repo** — apply only at the root even with subprojects
  present. Root's unrestricted `modulePath` means any commit anywhere counts toward the one shared
  version.
- **Mixed** — apply at root *and* to specific subprojects needing independent versioning. Known
  limitation: root's scope isn't currently exclusive of opted-in subprojects' paths, so root ends
  up tagged on essentially every qualifying push, not just changes outside those subprojects (see
  [issue #8](https://github.com/duckAsteroid/gradle-convention-plugin/issues/8)).

`installReleaseWorkflows`'s Java-toolchain substitution picks up whichever applying project's
script wins the registration guard first — if applying projects use different Java versions, the
installed workflow's single `setup-java` step can't represent that; not yet designed.

## `changelog { }` extension

Controls `changelogForReleaseCandidate`'s (and the `tagReleaseCandidates` aggregator's) "since"
boundary via `rcScope`:

```groovy
changelog {
    rcScope = ChangelogScope.SINCE_PREVIOUS_RC  // default: SINCE_LAST_RELEASE
}
```

- `SINCE_LAST_RELEASE` (default) — the whole release cycle so far.
- `SINCE_PREVIOUS_RC` — just the delta since the previous RC, falling back to
  `SINCE_LAST_RELEASE` automatically when there's no previous RC yet.

`changelogForRelease` and `promoteReleaseCandidates` always use `SINCE_LAST_RELEASE`, regardless of
this setting — a final release's notes should be the complete picture.

## `releaseCandidates { }` extension

Release candidates aren't permanent — only whichever one eventually gets promoted matters, and any
earlier one in the same cycle is either absorbed into a later RC or simply abandoned. By default,
`tagReleaseCandidates` deletes every previous RC's **GitHub Release** (never the git tag, never the
already-published package — those stay put forever) as soon as a newer RC exists for that module,
regardless of whether the candidate version itself changed along the way:

```groovy
releaseCandidates {
    pruneSuperseded = false   // default: true — keep every RC's GitHub Release forever instead
    retain = 2                // default: 0 — also keep the 2 most recent RCs besides the new one,
                               // counted by recency, not by version
}
```

- `pruneSuperseded = false` fully opts out — every RC's GitHub Release lives forever, matching the
  plugin's pre-#6 behavior.
- `retain` counts backward from the brand-new RC by recency, ignoring version boundaries: if a
  qualifying commit raises the bump mid-cycle (e.g. `v1.4.0-RC3` followed by `v1.4.1-RC1` — a
  "leapfrog"), `v1.4.0-RC3` still counts toward `retain` even though its base version differs from
  the new one.
- **Leapfrog warning**: whenever the freshly computed candidate's version differs from the nearest
  current-cycle RC's version, both `tagReleaseCandidate` and `tagReleaseCandidates` print a stderr
  warning — purely informational, never blocks, same philosophy as `CommitAnalyzer`'s
  non-conforming-commit warning.
- `tagReleaseCandidate` (singular) only prints the leapfrog warning — pruning is a manifest/
  GitHub-Release concern, and the singular task has no GitHub integration point of its own.
- `promoteReleaseCandidate(s)` never prunes anything — by the time a promotion happens there's only
  ever one live RC for that module (each new RC already superseded the one before it), so there's
  nothing left to clean up.

## Typical CI wiring

Run `./gradlew installReleaseWorkflows` once (locally, or as a one-off task) to install the two
workflow files below into `.github/workflows/` — re-run it after upgrading the plugin to pick up
template changes.

- On push to `release`: `tagReleaseCandidates` (generates each qualifying project's changelog and
  mints its RC tag internally, no separate changelog step needed first), then one GitHub
  pre-release per `build/release-manifest.json` entry, then one `gh release delete` per tag in
  each entry's `supersededTags` (see `releaseCandidates { }` above), then `publish`.
- On push to `main`: `promoteReleaseCandidates` (same internal changelog generation), then one
  GitHub release per manifest entry, then `publish`.

Both jobs should be self-contained (tag-then-publish in one job) — a tag pushed with the default
`GITHUB_TOKEN` does not trigger other workflow runs, so a separate publish-on-tag workflow would
silently never fire.
