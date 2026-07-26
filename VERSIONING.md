# Versioning strategy

This document explains, in detail, how `duckasteroid-java` computes a project's version and how the
opt-in `duckasteroid-release-flow` plugin turns that computation into actual git tags and releases.
For the shorter summary see [README.md](README.md); this file is the "why" and "exactly how" behind
it.

## The short version

A project's version is **never** stored anywhere - not in `build.gradle`, not in a `gradle.properties`
file, not in a version-manifest file. It's computed fresh, every build, from two things:

1. The **last final release tag** reachable from the current commit (a plain `vX.Y.Z`, no suffix).
2. The **[Conventional Commits](https://www.conventionalcommits.org/)** messages since that tag.

That computation is a small Groovy port of the semantic-release
[`commit-analyzer`](https://github.com/semantic-release/commit-analyzer) concept, living in
[`CommitAnalyzer.groovy`](src/main/groovy/io/github/duckasteroid/conventions/CommitAnalyzer.groovy)
(the commit-message classification) and
[`VersionResolver.groovy`](src/main/groovy/io/github/duckasteroid/conventions/VersionResolver.groovy)
(everything else - finding the right tag, walking commits, applying the bump, minting release tags).
Both are plain classes with no Gradle dependency, unit-tested directly. The same commit history and
classification also drives auto-generated release notes - see "Generating release notes" below.

## Why not just use axion-release's own version computation?

`duckasteroid-java` still applies and configures
[axion-release](https://github.com/allegro/axion-release-plugin) - it's what supplies the per-module
tag prefix scheme (see below) and, importantly, it's still what runs when you explicitly override the
version (see "The `release.forceVersion` backstop" below). But axion-release's own default version
computation - nearest tag, `-SNAPSHOT` if not exactly on it - doesn't know anything about *why* you're
making a release, so every commit bumps the version the same way regardless of whether it was a bug
fix or a breaking API change. Tying the bump to Conventional Commits means the version number itself
carries real information about the size of the change, computed automatically, without anyone having
to remember to run a "bump the version" command.

## Conventional Commits: the rules actually implemented

See `CommitAnalyzer`. Which type-word maps to which bump is *data* (`CommitAnalyzer.DEFAULT_TYPE_RULES`,
a `Map<Bump, Set<String>>`), not hardcoded logic - see "Configuring the rules" below for how to change
it. Out of the box:

| Commit looks like                                                              | Bump    |
|----------------------------------------------------------------------------------|---------|
| `feat: ...`                                                                       | minor   |
| `fix: ...` or `perf: ...`                                                         | patch   |
| `docs:`, `style:`, `refactor:`, `test:`, `chore:`, `build:`, or `ci:`             | none    |
| `feat!: ...` (or any type with a `!`)                                             | major   |
| a `BREAKING CHANGE:` footer anywhere in the body                                 | major   |
| anything that doesn't conform to `type: description` at all, or uses a type outside the lists above | **patch, with a warning printed to stderr** |

When there are multiple commits since the last release, **the highest bump wins** - a single
`feat!:` commit among ten `chore:` commits still means the next version is a major bump. When a type
is (mis)configured into more than one level (see below), the same "highest wins" rule applies to
picking which level a single commit falls into: checked major → minor → patch → none, so a type
present in both `majorTypes` and `patchTypes` resolves to MAJOR.

The last row is a deliberate departure from the semantic-release original, which just silently
ignores anything it doesn't recognize. A commit that doesn't match the `type: description` shape at
all (a merge commit's default message, a commit from before this convention was adopted), or one that
uses a type outside every configured set (a typo, or an in-house convention), is instead treated as a
small, defensive PATCH bump *and* logged as a warning. The reasoning: silently ignoring an
unrecognized commit risks silently ignoring a real, released change - a visible PATCH bump plus a
warning errs towards "the version moved, and you were told why" rather than "nothing happened and
nobody noticed". An empty commit message (e.g. from `--allow-empty-message`) is the one exception -
there's genuinely nothing there to classify, so it's NONE with no warning.

## Configuring the rules

Which types map to which bump is configurable per project via the `commitAnalyzer { }` extension
(registered by `duckasteroid-java.gradle`, backed by `CommitAnalyzerExtension`):

```groovy
commitAnalyzer {
    majorTypes.add('security')  // "security: ..." commits are always a major bump on their own
    minorTypes.add('perf2')     // an extra type that also means minor
    patchTypes.add('hotfix')    // an extra type that also means patch
    noBumpTypes.set(['docs'])   // REPLACES the default no-bump set - chore/style/etc. are no longer no-bump
}
```

Each of the four properties is a Gradle `SetProperty<String>`, pre-populated with the corresponding
entry of `CommitAnalyzer.DEFAULT_TYPE_RULES`. `.add(...)`/`.addAll(...)` appends an extra type on top
of that default; `.set(...)` replaces the whole set. `majorTypes` defaults to *empty*, since MAJOR is
normally driven by the structural `!`/`BREAKING CHANGE:` detection rather than by type at all -
`majorTypes` exists so a project can *also* force specific types to always be MAJOR on their own,
without every such commit needing to remember the `!` marker. That structural detection is never
configurable and always wins regardless of what the four sets say.

Two implementation details worth knowing if you're changing this code, not just using it:

- **Seeded via `addAll(...)`, not `convention(...)`.** A Gradle `Property`'s convention is only used
  as a fallback while nothing has been explicitly added/set - the moment a consumer calls `.add(...)`,
  Gradle discards the convention entirely rather than appending to it. Seeding with `convention(...)`
  would make `commitAnalyzer { minorTypes.add('perf2') }` silently end up as just `['perf2']`, losing
  the default `'feat'`. Pre-populating with `addAll(...)` instead makes the defaults part of the
  property's actual value from the start, so a later `.add(...)` genuinely appends, while `.set(...)`
  still replaces the whole thing either way.
- **Version computation is deferred to `project.afterEvaluate { }`.** Applying a precompiled script
  plugin like `duckasteroid-java` runs the whole plugin script synchronously as part of processing the
  consumer's `plugins { }` block - before the rest of their `build.gradle` (including any
  `commitAnalyzer { }` block) has executed at all. Computing `project.version` directly in the plugin
  script, rather than in `afterEvaluate`, would always see the untouched defaults and silently ignore
  the consumer's configuration.

## Worked example

Say the last final release tag is `v1.0.0`, and since then the branch has these commits:

```
docs: fix a typo in the README       -> NONE
fix: correct an off-by-one error      -> PATCH
feat: add a new export format         -> MINOR
```

The highest bump is MINOR, so the computed version is `1.1.0`. For an ordinary build (not sitting
exactly on a release tag), that gets decorated as `1.1.0-SNAPSHOT`.

Now add one more commit:

```
feat!: remove the deprecated v1 export format
```

The highest bump is now MAJOR (the `!` always wins regardless of type), so the computed version
becomes `2.0.0-SNAPSHOT` - notice it does **not** go through `1.1.0` first. The version is always
computed fresh from the last *final* tag forward, not incrementally from build to build.

## Ordinary builds vs. release tags

`VersionResolver.resolveBuildVersion` is what `project.version` is set to for every normal build
(`./gradlew build`, `publishToMavenLocal`, IDE sync, etc.):

1. If the current commit is exactly on a real tag (final or `-RCn`) matching this project's prefix,
   use it verbatim - no computation needed, this *is* a released version.
2. Otherwise, compute the candidate version as above and decorate it with `-SNAPSHOT`.
3. On a feature branch (anything other than `main`, `master`, `release`, or `develop`), the sanitized
   branch name is folded into the suffix too, e.g. `1.0.1-cool-stuff-SNAPSHOT` for a branch named
   `feature/cool-stuff` - so a jar built while working on a feature branch is visibly distinguishable
   from one built on `develop`.

This computation happens purely from local git state - no network access, no CI. If you're offline
with the repo checked out, `./gradlew build` computes the same version it would in CI.

## The `develop` → `release` → `main` flow

`duckasteroid-release-flow` (opt-in - apply it alongside `duckasteroid-java`) adds tasks that turn a
computed candidate version into an actual, pushed git tag, plus matching release notes:

```
 develop/feature branches          release                          main
──────────────────────────► merge ──────────────────► merge ──────────────────►
  every build:                each push:                each push:
  X.Y.Z-SNAPSHOT               changelogForReleaseCandidate  changelogForRelease
  (feature branches:            tagReleaseCandidate           promoteReleaseCandidate
   X.Y.Z-branch-SNAPSHOT)       → X.Y.Z-RC1, RC2, ...          → strips "-RCn"
                                                                → X.Y.Z (final)
```

- **`tagReleaseCandidate`** computes the candidate version (same computation as an ordinary build,
  minus the `-SNAPSHOT` decoration) and mints the next `X.Y.Z-RCn` tag for it - `RC1` the first time
  a given candidate version is tagged, `RC2`/`RC3`/... on each subsequent invocation for the *same*
  candidate. Intended to run on every push to `release`, so merging accepted work from `develop` into
  `release` automatically starts the RC cycle - and further commits on `release` (a fix spotted
  during RC testing, say) automatically advance it - with no manual version bookkeeping anywhere.
- **`promoteReleaseCandidate`** finds the nearest release-candidate tag reachable from the current
  commit and strips its `-RCn` suffix to produce the final release tag. Intended to run on every push
  to `main` - i.e. once an accepted RC has been merged from `release`.
- **`changelogForReleaseCandidate`** / **`changelogForRelease`** generate the matching Markdown
  release notes (see "Generating release notes" below) - each **must run before** its
  tagging/promoting counterpart in the same job, since they look for the *previous* RC tag / the
  last *final* tag reachable from HEAD, which the about-to-be-created new tag would otherwise shadow.

All four are plain Gradle tasks (`./gradlew tagReleaseCandidate`, etc.), runnable locally as well as
from CI - release engineering doesn't hard-depend on GitHub Actions being available.
[`.github/workflows/release-candidate.yml`](.github/workflows/release-candidate.yml) and
[`promote-release.yml`](.github/workflows/promote-release.yml) are this repo's own **live** workflows;
[`examples/workflows/release-candidate.yml`](examples/workflows/release-candidate.yml) and
[`promote-release.yml`](examples/workflows/promote-release.yml) are the **generic templates** for a
consumer project's own `.github/workflows/` (identical apart from the Java toolchain version - see
"Dogfooding this repo's own plugin" below). Each is a single, self-contained job (changelog, tag, cut
the GitHub Release with `--notes-file`, `gradlew publish`) rather than relying on the pushed tag to
trigger a separate workflow, because a tag pushed with the default `GITHUB_TOKEN` doesn't trigger
other workflow runs.

There's no separate manual-tag-triggered publish workflow any more - the old `publish.yml` (triggered
on any `v*` tag pushed directly) was retired once `release-candidate.yml`/`promote-release.yml` came
to cover both the RC and final-release cases via the `develop` → `release` → `main` flow.

## Dogfooding this repo's own plugin

`duckasteroid-java`/`duckasteroid-release-flow` are defined *in this repo* (`src/main/groovy/*.gradle`)
and this repo's own `build.gradle` **does** apply both to itself, pinned to a specific already-published
version (e.g. `1.0.0-RC4`), resolved from this repo's own GitHub Packages feed via
`settings.gradle`'s `io.github.duckasteroid.github-packages-settings` bootstrap - the same mechanism
any other consumer uses.

This is different from applying the *in-source* precompiled script plugin to the project that builds
it, which genuinely is circular and impossible: the plugin has to be compiled before it can be
applied, but compiling it depends on the very build script that would be applying it (confirmed
empirically - `id 'duckasteroid-java'` with no version, resolving from `src/main/groovy` in the same
build, fails with `Plugin [id: 'duckasteroid-java'] was not found... Included Builds (No included
builds contain this plugin)`). Depending on the *published* artifact instead sidesteps that entirely
- at the cost of this repo always dogfooding a released version of its own conventions rather than
whatever's on `HEAD`, so a change to `duckasteroid-java.gradle` doesn't affect this repo's own build
until a new version has actually been published and the pinned version bumped.

`project.version` for this repo's own root project **is** computed by `VersionResolver`, via
`duckasteroid-java`'s `afterEvaluate` hook, the same as any consumer. There used to be a leftover
`version = scmVersion.version` line in `build.gradle` from before this repo applied `duckasteroid-java`,
but it was dead code - the plugin's own `afterEvaluate` always fires later and overwrites it - so it's
been removed. `./gradlew currentVersion` is a red herring here: it's axion-release's own native task and
always shows axion's raw computation regardless of any of this, a different number from
`project.version`.

## Generating release notes

`ChangelogGenerator` turns a list of commit messages into grouped Markdown release notes - the same
idea implemented by [conventional-changelog](https://github.com/conventional-changelog/conventional-changelog),
[git-cliff](https://git-cliff.org/), and semantic-release's `release-notes-generator` plugin: group by
the *same* `typeRules` classification the version bump itself uses (so a custom `majorTypes` entry
ends up under Breaking Changes exactly like a `!`-marked commit would, and a `noBumpTypes` entry is
omitted exactly like it doesn't affect the version), rather than introducing a second, separate
type-to-section mapping to configure:

```markdown
## Breaking Changes
- drop support for old config format

## Features
- add support for widgets

## Bug Fixes
- correct off-by-one error
```

A non-conforming commit still gets classified PATCH (and still prints its stderr warning - see
above) and appears under Bug Fixes using its raw first line as the description, consistent with it
having actually bumped the version. An entirely no-bump batch (or a first release with truly no
qualifying commits) produces `No user-facing changes.` rather than an empty file.

`VersionResolver.commitMessagesForChangelog` supplies the commit list, choosing the "since" boundary
per `ChangelogScope`:

- **`SINCE_LAST_RELEASE`** (default) - everything since the last *final* release tag, i.e. the
  complete picture across every RC in this cycle so far. Always used for `changelogForRelease`
  (the final release's notes should be the complete picture, regardless of `rcScope`).
- **`SINCE_PREVIOUS_RC`** - only commits since the *previous* release-candidate tag - a delta, useful
  for reviewers re-checking what changed in a bumped RC rather than re-reading the whole cycle again.
  Falls back to `SINCE_LAST_RELEASE` automatically when there's no previous RC yet. Only meaningful
  for `changelogForReleaseCandidate`, controlled via:

```groovy
changelog {
    rcScope = ChangelogScope.SINCE_PREVIOUS_RC   // default: SINCE_LAST_RELEASE
}
```

## The `release.forceVersion` backstop

Every place a version gets computed checks for `-Prelease.forceVersion=X.Y.Z` **first**, before any
commit analysis runs at all:

```groovy
def forceVersion = project.findProperty('release.forceVersion')
version = forceVersion ? scmVersion.version : /* ...commit analysis... */
```

If it's set, none of the above logic runs - axion-release's own native `forceVersion` handling
(https://axion-release-plugin.readthedocs.io/en/latest/configuration/force_version/) is used
untouched, verbatim. This is deliberate and explicit, not an emergent property of some shared
"resolve version" helper: the check is repeated at every call site (the ordinary build version in
`duckasteroid-java.gradle`, and both tasks in `duckasteroid-release-flow.gradle`) so that a future
change to one of them can't accidentally make the backstop stop working elsewhere. The intent, in the
author's own words: *"Don't do anything clever, just release whatever I say the number is."*

## Multi-module repositories

The tag prefix scheme (owned by `duckasteroid-java`'s `scmVersion.tag` configuration, unchanged from
before this whole commit-analysis scheme existed) derives each module's own prefix from its Gradle
project path: `sub/module/v1.2.3` for a subproject at `:sub:module`, or plain `v1.2.3` at the root.
Each module in a multi-project build is versioned and released independently - tagging one module
doesn't affect another's version. Two behaviors specifically make this work in practice:

- **Fallback prefix.** A module with no final release tag of its own yet doesn't start over at
  `0.0.0` - it falls back to the root project's plain `v` tag as its starting point (mirroring
  axion-release's own `fallbackPrefixes` configuration). A brand-new subproject added to an
  established multi-module repo inherits the root's version line rather than looking brand new
  itself.
- **Path-scoped commit filtering.** Only commits that actually touched a module's own directory count
  towards its version bump - equivalent to `git log <range> -- <module path>`, implemented via JGit's
  `Git.log().addPath(...)`. A `feat:` commit that only touches `moduleB/` does not bump `moduleA`'s
  version, even though both modules share the same linear git history.

Worked example: root tagged `v2.0.0`, then a commit touching only `moduleA/` (`fix: bug in module
A`), then a commit touching only `moduleB/` (`feat: big feature in module B`) - `moduleA` computes
`2.0.1-SNAPSHOT` (only sees its own `fix:` commit), `moduleB` computes `2.1.0-SNAPSHOT` (only sees its
own `feat:` commit), and the root project (no path restriction) computes `2.1.0-SNAPSHOT` (sees both,
highest bump wins).

## Why JGit, not the `git` CLI

`VersionResolver` talks to git via [JGit](https://www.eclipse.org/jgit/), not by shelling out to the
`git` binary, for a specific, load-bearing reason: `resolveBuildVersion` is called from
`version = ...` in `duckasteroid-java.gradle`, which runs at Gradle *configuration* time - and
starting an external process during configuration is incompatible with the [Gradle configuration
cache](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements:external_processes).
JGit is a pure-Java git implementation, so it doesn't trip that restriction (this is also why
axion-release's own JGit-based `ghOwner`/`ghBranch` detection elsewhere in `duckasteroid-java.gradle`
has always worked fine under the configuration cache).

The `duckasteroid-release-flow` tasks that call into `VersionResolver` only do so from `doLast { }`
(task *execution* time, where external processes are fine) - but they share the same JGit-based code
for simplicity, rather than maintaining two implementations of the same logic. Creating and pushing
the actual tag, on the other hand, *does* shell out to the real `git` CLI (see `runGit` in
`duckasteroid-release-flow.gradle`) - that happens at execution time (fine under the configuration
cache) and needs real push credentials, which the `git` CLI transparently picks up from whatever's
already configured (locally, or via `actions/checkout`'s persisted `GITHUB_TOKEN` in CI) with no
extra wiring.

## Testing

- [`CommitAnalyzerTest`](src/test/java/CommitAnalyzerTest.java) - plain JUnit, table-driven over
  representative commit messages, no git involved at all. Includes the configurable-rules behavior:
  appending to/overriding each of the four sets, and the cross-set precedence rule (a type in both
  `majorTypes` and `patchTypes` resolves to MAJOR).
- [`VersionResolverTest`](src/test/java/VersionResolverTest.java) - builds a real throwaway git repo
  per test (via plain `git` CLI calls, since this is test *setup*, not the code under test) rather
  than mocking git, including fixtures with real file changes under different subdirectories to
  exercise the path-scoping behavior, custom `typeRules` maps threaded all the way through to the
  computed version, and `commitMessagesForChangelog` for both `ChangelogScope`s (including the
  previous-RC-tag fallback when no previous RC exists yet).
- [`CommitAnalyzerExtensionTest`](src/test/java/CommitAnalyzerExtensionTest.java) - applies
  `duckasteroid-java` via `ProjectBuilder` and exercises the `commitAnalyzer { }` extension itself:
  its defaults mirror `CommitAnalyzer.DEFAULT_TYPE_RULES` exactly, `.add(...)` appends onto the
  default rather than replacing it, `.set(...)` replaces it, and each of the four properties can be
  configured independently of the others.
- [`ChangelogGeneratorTest`](src/test/java/ChangelogGeneratorTest.java) - plain JUnit, no git
  involved: section grouping/ordering, scope formatting, no-bump commits omitted, non-conforming
  commits still listed under Bug Fixes, custom `typeRules` (including a custom `majorTypes` entry
  grouping under Breaking Changes without a `!` marker) threaded through correctly.
