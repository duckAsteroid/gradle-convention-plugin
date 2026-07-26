---
description: Opt-in duckAsteroid Gradle convention plugin for publishing to Maven Central (OSSRH) - applies signing, signs the mavenJava publication, and adds the release/snapshot OSSRH repository. Apply alongside duckasteroid-java, which must already have created the mavenJava publication.
---

# duckasteroid-maven-central

Opt-in plugin for consumers that also need to publish to Maven Central, in addition to (or
instead of) GitHub Packages.

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'        // required first - creates mavenJava
    id 'duckasteroid-maven-central' version '<version>'
}
```

## What it does

- Applies the core `signing` plugin and signs the `mavenJava` publication that `duckasteroid-java`
  already created.
- Adds an `OSSRH` publishing repository. The URL is picked automatically: the release endpoint if
  `version` does NOT end in `SNAPSHOT`, otherwise the snapshot endpoint.
- Credentials come from the `ossrhUsername` / `ossrhPassword` project properties (e.g. from
  `~/.gradle/gradle.properties`) — not from `gpr.*` (that's the GitHub Packages credential set used
  elsewhere).

## Prerequisites

`duckasteroid-java` must be applied first — this plugin signs and publishes the `mavenJava`
publication that plugin creates, it does not create publications itself.
