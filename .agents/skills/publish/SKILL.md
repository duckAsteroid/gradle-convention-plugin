---
name: publish
description: "Reference documentation for the Gradle plugin `io.github.duckasteroid.agent-docs.publish`. Use this skill when configuring, writing, or troubleshooting Gradle builds that apply it. Validates a project's src/agent-docs/ directory and distributes it as a Maven sidecar zip or embeds it in the project's own jar, stamping the Agent-Docs manifest attribute so agent-docs-resolve-gradle-plugin (or any Agent-Docs-aware tool) can discover it."
metadata:
  pluginId: io.github.duckasteroid.agent-docs.publish
---

# Agent Docs Publish Plugin

Plugin ID: `io.github.duckasteroid.agent-docs.publish`

Apply this plugin to a library or Gradle plugin project to validate and distribute its
`src/agent-docs/` directory per the [Agent-Docs convention](https://github.com/duckasteroid/agent-docs).
This plugin isn't required to participate in the convention — the manifest attribute and docs
layout can be hand-written by anyone — but it automates validation and packaging.

Distribution is controlled by `agentDocsPublish.distribution`:

- **`SIDECAR`** (default) — packages the docs into a separate zip artifact (classifier
  `agent-docs`), attached to every `MavenPublication` when `maven-publish` is applied, and stamps
  the main `jar` task's manifest with `Agent-Docs: maven:<group>:<artifact>:<version>`.
- **`EMBEDDED`** — copies the docs into the project's own jar under `agent-docs/` and stamps the
  manifest with `Agent-Docs: classpath`. No separate artifact is published.

When `java-gradle-plugin` is applied, the default flips to `EMBEDDED` — plugin jars aren't
resolved as Maven dependencies, so a sidecar has no consumer-side resolution path — and an
explicit `SIDECAR` override fails the build at configuration time.

## Docs source layout

```text
src/agent-docs/
  SKILL.md          ← required; frontmatter (and `description`) optional
  references/       ← optional reference docs
  assets/           ← optional assets (an `assets/sources` entry is reserved, not allowed)
  scripts/          ← optional scripts
```

For `java-gradle-plugin` projects, the docs root is instead a parent of one bundle subdirectory
per id declared via `gradlePlugin { plugins { ... } }` — always, even when only one id is
declared, never a bundle at the docs root itself:

```text
src/agent-docs/
  <pluginId>/
    SKILL.md
    references/ ...
```

Do not include a `name:` field in `SKILL.md` frontmatter — the resolver overwrites it from
GAV/plugin-id coordinates at extraction time.

## Tasks added

- `validateAgentDocs` — validates the docs directory (or each per-id subdirectory) against the
  Agent Skills spec
- `packageAgentDocs` — packages docs into `build/agent-docs/<project-name>-agent-docs.zip`; runs
  validation first and is wired into `assemble`; skipped when distribution is `EMBEDDED`
- `prepareEmbeddedAgentDocs` — copies (and strips `name:` frontmatter from) docs into the jar's
  own resources; runs validation first; skipped when distribution is `SIDECAR`

## Extension

```groovy
agentDocsPublish {
  docsDirectory = file('src/agent-docs')          // default
  disabledValidationRules = []                     // optional; list of rule IDs to skip
  distribution = AgentDocsDistribution.SIDECAR     // default; forced to EMBEDDED under java-gradle-plugin
}
```

Renamed from `agentDocs` in 2.2.0 to avoid colliding with `io.github.duckasteroid.agent-docs`
(resolve)'s own `agentDocs` extension when both plugins are applied to the same project. The old
`agentDocs { ... }` name still works as a deprecated alias when only this plugin is applied; use
`agentDocsPublish { ... }` if you also apply the resolve plugin.

Disable rules from the CLI: `./gradlew validateAgentDocs -PagentDocs.disabledValidationRules=skill-name,skill-description`

## Validation rule IDs

| Rule ID | What it checks |
|---|---|
| `docs-directory-exists` | Docs directory is present |
| `skill-entrypoint` | Exactly one root-level `SKILL.md` (case-insensitive) |
| `standard-directories` | `scripts`, `references`, `assets` are directories if present |
| `assets-sources-reserved` | `assets/sources` is absent — that path is reserved for a consumer's `includeSources` output |
| `skill-frontmatter-structure` | When present, `SKILL.md` frontmatter opened with `---` is properly closed; frontmatter itself is optional |
| `skill-description` | `description`, when present, is <= 700 characters (leaves headroom for the resolver-generated prefix under the Agent Skills format's 1024-character limit); presence is optional |
| `skill-name` | Warning-only: publisher `name` is ignored at packaging time |
| `plugin-bundle-directories` | For `java-gradle-plugin` projects: one subdirectory per declared id, matching subdirectory per id, no top-level `SKILL.md` |
