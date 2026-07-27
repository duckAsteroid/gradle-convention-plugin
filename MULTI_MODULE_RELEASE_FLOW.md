# Multi-module release flow

This document describes how `duckasteroid-release-flow`, `installReleaseWorkflows`, and the bundled
GitHub Actions workflows behave, whether a repository releases as a single unit or as several
independently-versioned modules. It complements [VERSIONING.md](VERSIONING.md), which covers how a
version number itself is computed; this document covers what happens when that computation is turned
into actual tags, changelogs, and GitHub Releases. Addresses [issue #4](https://github.com/duckAsteroid/gradle-convention-plugin/issues/4).

## The core idea

Apply `duckasteroid-release-flow` to whichever projects in the build should be independently
releasable - just the root, just a subset of subprojects, or every project. Each push to `release` or
`main` runs one pair of root-level tasks - `tagReleaseCandidates` / `promoteReleaseCandidates` - which:

1. Enumerate every project in the build that has `duckasteroid-release-flow` applied.
2. For each one, compute its candidate version and its last-final version exactly as an ordinary build
   would (`VersionResolver`, using that project's own `tagPrefix` and path-scoped `modulePath`).
3. Skip any project whose candidate equals its last-final version - nothing under that project's own
   directory had a qualifying Conventional Commit since its last release, so there's nothing to tag.
4. For every project that *does* have a qualifying change, generate its changelog, mint and push its
   own tag, and record `{module, tag, changelog}` in a manifest file written once at the repo root.

The bundled workflow reads that manifest and creates one GitHub Release per entry. A push touching one
module produces one release; a push touching several produces several, each versioned independently; a
push with no qualifying commits anywhere produces none.

## Single-project repositories

Apply `duckasteroid-java` + `duckasteroid-release-flow` only at the root, with no subprojects also
applying `duckasteroid-release-flow`. `tagReleaseCandidates` finds exactly one releasable project - the
root, whose `modulePath` is empty and therefore sees every commit in the repository, not just ones
under a particular directory (this is the same "no path restriction" behavior `VersionResolver` already
has for the root project in ordinary builds). The manifest ends up with exactly one entry, and the
workflow creates exactly one GitHub Release per push, same as a plain single-module repository always
has. Nothing about running this repository as a single releasable unit requires any extra
configuration - it's what you get by simply not applying `duckasteroid-release-flow` anywhere else.

## Multi-project repositories

Apply `duckasteroid-release-flow` to the root and/or to any subset of subprojects, depending on which
ones need their own release cadence. Three shapes fall out of the same mechanism:

- **Fully independent modules.** Apply `duckasteroid-release-flow` to each subproject, not the root. A
  push that only changes `:api` produces a release for `:api` alone; `:web` is silently skipped because
  its candidate version didn't move. Each module has its own tag prefix (`api/v1.4.0`, `web/v2.1.0`,
  ...), its own changelog, its own GitHub Release.
- **One global version for the whole repo.** Apply `duckasteroid-release-flow` only at the root (as in
  the single-project case above) even though the build has subprojects - the root's unrestricted
  `modulePath` means any commit anywhere counts towards the one shared version, and no subproject ever
  gets tagged independently.
- **Mixed.** Apply `duckasteroid-release-flow` at the root *and* to specific subprojects that need
  independent versioning. Root and the opted-in subprojects are each evaluated independently and can
  both end up in the same manifest.

Worked example: root tagged `v2.0.0`, `:api` (also applying `duckasteroid-release-flow`) tagged
`api/v1.4.0`, `:web` not independently versioned. A push to `release` containing a single `fix:` commit
touching only `:api/` produces a manifest with **two** entries: `:api` at `api/v1.4.1-RC1` (its own
`fix:` commit), and root at `v2.0.1-RC1` (root's `modulePath` is always empty, so it sees that same
`api/`-scoped commit too and bumps alongside it).

> **Known limitation: root's scope isn't exclusive of independently-versioned subprojects**
> ([issue #8](https://github.com/duckAsteroid/gradle-convention-plugin/issues/8)). Because
> root's `modulePath` is unrestricted (unchanged from how it already behaves for ordinary builds - see
> [VERSIONING.md](VERSIONING.md)'s "Multi-module repositories" section), it sees every commit in the
> repo, including ones under a subproject that has its *own* `duckasteroid-release-flow` application.
> In mixed mode this means root will be tagged on essentially every push that bumps *any* opted-in
> subproject too, not just changes outside those subprojects - "root covers everything not separately
> versioned" is the intent, but isn't what the path-scoping mechanism actually produces today. Making
> root's scope exclude opted-in subprojects' paths would need `modulePath` to support exclusions, not
> just a single inclusive path - not yet designed. Until then, mixed mode is really "root releases on
> every qualifying push regardless of which module contains it, plus independently versioned
> subprojects also release on their own." Fully independent modules or a single global version (the
> other two shapes above) don't have this problem, since only one thing is ever computing a version
> for any given commit.

## Skipping unaffected modules

The skip decision is exactly "does `VersionResolver.resolveCandidateVersion(...)` return something
different from `VersionResolver.lastFinalVersion(...)` for this project" - the same path-scoped commit
analysis `VersionResolver` already performs for ordinary builds, reused rather than duplicated. No new
include/exclude configuration is needed: a module's own directory boundary, combined with Conventional
Commits classification, is what already determines its bump for every other build; `tagReleaseCandidates`
just also uses it to decide *whether to act at all*.

`promoteReleaseCandidates` applies the same idea in reverse: a project is skipped if there's no
release-candidate tag reachable from `HEAD` under its own prefix, rather than the build failing
outright the way a bare `promoteReleaseCandidate` invocation would today if it found nothing to
promote. In a mixed release wave, not every applying project necessarily had an RC cut this cycle.

## The manifest

`tagReleaseCandidates` and `promoteReleaseCandidates` each write `build/release-manifest.json` at the
repo root (overwriting whatever the other one wrote last - only one of them runs per push, matching
`release` vs. `main`):

```json
[
  { "module": ":api", "tag": "api/v1.4.1-RC1", "changelog": "api/build/changelog.md" },
  { "module": ":",    "tag": "v2.1.0-RC1",      "changelog": "build/changelog.md" }
]
```

`module` is the Gradle project path (`:` for the root), `tag` is the full tag name including prefix,
`changelog` is the path (relative to the repo root) to that project's own generated changelog. The
bundled workflow's "Create GitHub Release" step loops over this file - one `gh release create` per
entry, each pointed at its own `--notes-file` - instead of inferring a single tag via
`git describe --tags --exact-match HEAD`, which only ever worked when exactly one tag landed on a
commit.

## Registration: exactly once, regardless of application site

Gradle's own `./gradlew <taskName>` task-name matching already runs a task in *every* project under
the current directory that defines it, not just the root - this is what makes `./gradlew
tagReleaseCandidate` (singular) work today even when `duckasteroid-release-flow` is applied only to a
subproject and never to root: Gradle finds `:api:tagReleaseCandidate` and runs it with no root
involvement needed at all. That mechanism is also *exactly* what causes the bug in #4: registering the
same-named task independently in every applying project means one unqualified invocation runs all of
them, uncoordinated, in the same build.

So the new aggregator tasks can't be registered the same way the existing per-project tasks are
(once per applying project) - doing that would just move the multiple-independent-instances problem up
one level, with several aggregator instances each redoing the full enumerate/skip/tag/manifest-write
cycle and racing on the same `build/release-manifest.json`. Instead, whichever project(s)
`duckasteroid-release-flow` is applied to must register `tagReleaseCandidates` /
`promoteReleaseCandidates` / `installReleaseWorkflows` / `checkReleaseWorkflows` on `rootProject`,
guarded so the second (and third, ...) applying project's attempt to register them is a no-op:

```groovy
if (rootProject.tasks.findByName('tagReleaseCandidates') == null) {
    rootProject.tasks.register('tagReleaseCandidates') { /* ... */ }
}
```

This doesn't require `rootProject` to apply `duckasteroid-java` or `duckasteroid-release-flow` itself -
it's just a place to hang exactly one task instance, reachable the normal way (Gradle's cross-project
name matching resolves `./gradlew tagReleaseCandidates` unqualified to that single instance, same
mechanism as above, just now guaranteed to only ever be one). Applying `duckasteroid-release-flow` to
only `:api` and never to root still produces a working `tagReleaseCandidates` on root, which then
enumerates whatever *did* apply the plugin - just `:api` in that case.

## Task reference

| Task                          | Scope                          | Purpose                                                                                          |
|--------------------------------|---------------------------------|----------------------------------------------------------------------------------------------------|
| `tagReleaseCandidate`           | single project                 | Unchanged - tags/pushes the next RC for *this* project only. Useful for local, single-module work. |
| `promoteReleaseCandidate`       | single project                 | Unchanged - promotes *this* project's nearest RC to final.                                         |
| `changelogForReleaseCandidate`  | single project                 | Unchanged - previews *this* project's RC notes without tagging anything.                           |
| `changelogForRelease`           | single project                 | Unchanged - previews *this* project's final release notes.                                         |
| `tagReleaseCandidates`          | registered once on `rootProject` | New. The task CI actually calls on every push to `release` - loops every applying project, skips unaffected ones, writes the manifest. |
| `promoteReleaseCandidates`      | registered once on `rootProject` | New. The task CI actually calls on every push to `main` - loops every applying project, skips ones with no pending RC, writes the manifest. |
| `installReleaseWorkflows`       | registered once on `rootProject` | Unchanged in behavior, but now guarded so it only ever exists once - one workflow pair per repo, regardless of how many projects apply `duckasteroid-release-flow`. |
| `checkReleaseWorkflows`         | registered once on `rootProject` | Same scoping change as `installReleaseWorkflows`.                                                  |

## Installing the workflows

`installReleaseWorkflows` and `checkReleaseWorkflows` are registered exactly once, on `rootProject`,
even when `duckasteroid-release-flow` is applied to several subprojects - there is exactly one
`.github/workflows/release-candidate.yml` / `promote-release.yml` pair per repository, generated once,
regardless of how many modules within it are independently releasable. The Java toolchain
substitution they perform picks up the toolchain of whichever applying project's script wins the
registration guard first (normally root, if root applies `duckasteroid-java`); if applying projects
use a different Java version than that one, the installed workflow's `setup-java` step needs to
provision whichever versions are actually in use across all applying projects (multiple versions in
one `actions/setup-java` step), since a single CI job now has to be able to build every releasable
module, not just the one whose toolchain got picked up. Not yet designed - a spike, same as the
"Known limitation" above.
