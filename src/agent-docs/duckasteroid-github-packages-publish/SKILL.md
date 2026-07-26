---
description: Opt-in duckAsteroid Gradle convention plugin that publishes a project's own mavenJava publication to its own GitHub Packages feed, using the owner/repo derived by duckasteroid-java. Apply alongside duckasteroid-java.
---

# duckasteroid-github-packages-publish

Opt-in plugin to publish to *this* project's own GitHub Packages Maven feed — as opposed to
`duckasteroid-java`'s `gitHubPackages { }` DSL, which is only for resolving *dependencies* from
someone else's GitHub Packages feed.

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'                     // required first
    id 'duckasteroid-github-packages-publish' version '<version>'
}
```

## What it does

- Relies on `io.github.duckasteroid.github-packages` v0.2.0+ (a fix for a bug where the
  `gitHubPackages { }` closure always targeted `project.repositories` regardless of which
  `repositories { }` block it was invoked from, silently adding the repo to the wrong list so
  `publish` pushed nowhere).
- Targets the owner/repo derived by `duckasteroid-java` (`project.ghOwner` / `project.ghRepo`, from
  the project's actual git `origin` remote) — not a hardcoded owner.
- Registers a `publishMavenJavaPublicationToGitHubPackages-<repo>Repository` task.

## Credentials

GitHub Packages requires authentication to *read* packages even from public repos. Resolution
order (highest priority first):

1. `gradle.properties` (project or `~/.gradle/gradle.properties`): `gpr.user` / `gpr.key` (a PAT
   with appropriate scopes) — best for local dev.
2. Env vars `GH_PACKAGES_READ_USER` / `GH_PACKAGES_READ_TOKEN` — for shared read-only bot creds.
3. Env vars `GITHUB_ACTOR` / `GITHUB_TOKEN` — automatic in GitHub Actions, nothing to configure.
