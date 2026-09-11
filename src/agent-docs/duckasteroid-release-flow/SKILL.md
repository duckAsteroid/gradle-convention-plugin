---
description: Opt-in duckAsteroid Gradle release-engineering plugin implementing a develop/release/main git flow, with RC tagging, final-release promotion, changelog generation, multi-module-aware aggregator tasks, and GitHub Actions workflow install/staleness-check tasks. Apply alongside duckasteroid-version (or duckasteroid-java, which applies it internally), whose VersionResolver it reuses.
---

# duckasteroid-release-flow

Opt-in release-engineering plugin for a `develop`/`release` → `main` git flow: `release`
accumulates release-candidate builds before an accepted RC is promoted to a final release on
`main`. Works the same whether the repo releases as a single unit or as several
independently-versioned modules.

Depends on `duckasteroid-version` (for `project.ext.tagPrefix`/`modulePath` and the
`commitAnalyzer { }` extension), not `duckasteroid-java` specifically — `duckasteroid-java` applies
`duckasteroid-version` internally, so either works:

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'          // applies duckasteroid-version internally
    id 'duckasteroid-release-flow' version '<version>'
}
```

```groovy
plugins {
    id 'duckasteroid-version' version '<version>'       // no Java/POM/publishing conventions needed
    id 'duckasteroid-release-flow' version '<version>'
}
```

The one remaining catch with the second form: `installReleaseWorkflows` still reads the applying
project's `JavaPluginExtension` toolchain version to template into the installed workflow's
`setup-java` step, so it needs *some* `java`-toolchain-configuring plugin present (every other task —
`tagReleaseCandidate(s)`, `promoteReleaseCandidate(s)`, `changelogFor*`, `explainVersion(s)`,
`checkReleaseWorkflows` — works with just `duckasteroid-version`).

## Tasks

Ten tasks total, all plain Gradle tasks, runnable locally as well as from CI. Five are
single-project (act on whichever project applies the plugin); five are multi-module-aware
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
- **`explainVersion`** (issue #5) — read-only preview of what `tagReleaseCandidate` would do right
  now for *this* project: last final release, every qualifying commit since it with its own
  type/scope/bump classification, the resulting overall bump, the candidate version, and the exact
  next release-candidate tag (`VersionResolver.nextReleaseCandidateTag`, reused verbatim, so it can
  never drift from what tagging would actually mint). Never tags, pushes, or writes a changelog.
  Prints to the console and, by default, writes the same data as JSON to
  `build/version-report.json` — see `versionReport { }` below.

**Multi-module aggregators (what CI actually calls):**

- **`tagReleaseCandidates`** — the task the bundled `release-candidate.yml` workflow runs on every
  push to `release`. Enumerates every project in the build with `duckasteroid-release-flow`
  applied, computes each one's candidate version exactly as an ordinary build would
  (`VersionResolver`, using that project's own `tagPrefix`/`modulePath`), skips any project whose
  candidate equals its last-final version (nothing qualifying changed under its own directory),
  and for every project that *does* qualify: generates its changelog, mints and pushes its own RC
  tag, and records `{module, tag, changelog, supersededTags, artifactsDir}` in
  `build/release-manifest.json` at the repo root. A push touching one module produces one manifest
  entry; a push touching several produces several, each versioned independently; a push with no
  qualifying commits anywhere produces an empty manifest (not an error). `supersededTags` — see
  `releaseCandidates { }` below — lists this cycle's previous RC tags that the new one replaces, for
  the bundled workflow to delete their GitHub Releases. `artifactsDir` is that project's own
  `build/libs` directory (relative to the repo root) for the bundled workflow to glob jars out of and
  attach to the GitHub Release — a directory to glob rather than literal file names, since the real
  jar/sources/javadoc file names for the release version don't exist yet when the manifest is
  written (`project.version` was already fixed to an ordinary "-SNAPSHOT" value earlier in the same
  invocation, before the tag existed) — see issue #10.
- **`promoteReleaseCandidates`** — the `main`-push counterpart. Same enumeration, but promotes
  each applying project's nearest reachable RC tag to final; a project with no pending RC this
  cycle is skipped rather than failing the whole task.
- **`explainVersions`** (issue #5) — the read-only, multi-module counterpart to `explainVersion`:
  same `releaseFlowTargets` enumeration and the same `candidate == lastFinal` skip/tag decision as
  `tagReleaseCandidates`, but never tags, pushes, or mints anything. Prints one line per applying
  project (`explainVersions: :api - TAG (would mint api/v1.5.0-RC1)` /
  `explainVersions: : - SKIP (no qualifying commits since v2.1.0)`) plus each module's detail block,
  and, by default, writes the whole set as a JSON array to `build/version-report.json` - each
  element the same shape `explainVersion` produces, with `action`/`reason` layered on.
- **`installReleaseWorkflows`** — installs the two GitHub Actions workflows the aggregator tasks
  above are meant to run from (`.github/workflows/release-candidate.yml` /
  `promote-release.yml`, bundled with the plugin) into the consumer project. Registered once on
  `rootProject` — applying the plugin to several subprojects doesn't install multiple copies or
  race on the same two files. Uses the generic `ManagedFileMarker`/`ManagedFileInstaller`/
  `ManagedFileChecker` mechanism (componentId `"release-flow"`), so it never clobbers a file it
  doesn't recognize as its own - including a file installed by a *different* duckasteroid-* plugin's
  own component, should one ever install into the same directory:
  - No file at that path → installs it.
  - File present, no `# duckasteroid-managed: ...` marker comment as the first line, or a marker for
    a different componentId → treated as foreign/hand-written → **skipped**, with a warning.
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
- **Mixed** — apply at root *and* to specific subprojects needing independent versioning. Root's
  own commit scope automatically excludes every other applying project's `modulePath` (derived from
  `releaseFlowTargets`, no manual configuration needed), so a commit entirely inside `:api` bumps
  `:api` alone rather than also bumping root (see
  [issue #8](https://github.com/duckAsteroid/gradle-convention-plugin/issues/8), fixed). This
  exclusion only applies to the release-flow tasks (`tagReleaseCandidates`/`promoteReleaseCandidates`/
  `explainVersions`) - ordinary build version resolution and the singular per-project tasks are
  intentionally unaffected.

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

## `versionReport { }` extension

Controls `explainVersion`/`explainVersions`' `build/version-report.json` file - the console
breakdown they print always happens regardless of this extension:

```groovy
versionReport {
    enabled = false                                                 // default: true — console only
    outputFile = layout.buildDirectory.file('reports/version.json') // default: build/version-report.json
}
```

Plain scalar `Property<T>`s, like `releaseCandidates { }`/`changelog { }` — no `SetProperty`
append-vs-replace gotcha, `.convention(...)` works exactly as expected. `explainVersions` reads this
from whichever applying project's script wins the registration guard (normally root) — the same
"first applying project decides" rule `installReleaseWorkflows` already uses for its Java-toolchain
substitution, not a new one.

## Typical CI wiring

Run `./gradlew installReleaseWorkflows` once (locally, or as a one-off task) to install the two
workflow files below into `.github/workflows/` — re-run it after upgrading the plugin to pick up
template changes.

- On push to `release`: `tagReleaseCandidates` (generates each qualifying project's changelog and
  mints its RC tag internally, no separate changelog step needed first), then `./gradlew assemble`
  in a fresh invocation (so the rebuilt jars carry the just-tagged release version, not the ordinary
  "-SNAPSHOT" one `tagReleaseCandidates` itself saw), then one GitHub pre-release per
  `build/release-manifest.json` entry — attaching every jar found under that entry's `artifactsDir` —
  then one `gh release delete` per tag in each entry's `supersededTags` (see `releaseCandidates { }`
  above), then `publish`.
- On push to `main`: `promoteReleaseCandidates` (same internal changelog generation), then
  `./gradlew assemble` again for the same reason, then one GitHub release per manifest entry (same
  artifact attachment), then `publish`.

Both jobs should be self-contained (tag-then-publish in one job) — a tag pushed with the default
`GITHUB_TOKEN` does not trigger other workflow runs, so a separate publish-on-tag workflow would
silently never fire.
