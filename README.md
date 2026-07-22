# DuckAsteroid's Gradle Conventions Plugin
Gradle plugins that provide conventions for my other projects.

## `duckasteroid-java`

The base convention plugin for Java projects. Apply it via `id 'duckasteroid-java'`. It provides:

* Java 25 toolchain by default, overridable via `-Pduckasteroid.java.version` (or a `gradle.properties` entry)
* Group `io.github.duckasteroid`
* Versioning using git tags, via the [`axion-release`](https://github.com/allegro/axion-release-plugin) plugin (as explained in my Medium article) — no version string is ever committed to `build.gradle`
  * The expected tag prefix is derived from the Gradle project path, so each project's version is `${gradle project path}/v{number}`. For example a subproject at `:sub:module` looks for tags like `sub/module/v1.2.3`, while the root project just looks for `v1.2.3`
  * This makes versioning **multi-module friendly**: each module in a multi-project build can be tagged and released independently, without bumping the version of unrelated modules in the same repo
  * If no tag matches a subproject's own prefix yet, it falls back to a plain `v` prefix tag at the repo root, and finally to `0.0.0+notag` if no matching tag exists at all
  * Between tags, axion-release computes a version like `0.0.2-develop-SNAPSHOT` — the next patch version, the current branch name, and a `SNAPSHOT` suffix — until the next matching tag is pushed
  * Useful tasks: `./gradlew currentVersion` shows the computed version for a project; `./gradlew release` tags the repo with the next version (or push a matching git tag yourself)
* Maven Central repository for dependencies
* Add source and JavaDoc to the published artifacts
* Maven publishing to 
  * Maven Central as `OSSRH`
  * GitHub Packages as `GitHubPackages`, configured via my [gradle-github-packages](https://github.com/duckAsteroid/gradle-github-packages) plugin
* Dependency update checking via the `com.github.ben-manes.versions` plugin (run `./gradlew dependencyUpdates`)

## Opt-in plugins

These are not applied by `duckasteroid-java` — apply them alongside it if you want them:

* `duckasteroid-checkstyle` — applies `checkstyle`
* `duckasteroid-jacoco` — applies `jacoco`
* `duckasteroid-pmd` — applies `pmd`
* `duckasteroid-lombok` — applies the [Freefair Lombok plugin](https://github.com/freefair/gradle-plugins) (`io.freefair.lombok`)
