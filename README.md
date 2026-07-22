# DuckAsteroid's Gradle Conventions Plugin
Gradle plugins that provide conventions for my other projects.

Currently, there is only the `duckasteroid-java` plugin which provides a set of conventions for Java projects:

* Java 25 toolchain by default, overridable via `-Pduckasteroid.java.version` (or a `gradle.properties` entry)
* Group `io.github.duckasteroid`
* Versioning using git tags (as explained in my Medium article)
  * Key point is that each project version is `${gradle project path}/v{number}` 
* Maven Central repository for dependencies
* Add source and JavaDoc to the published artifacts
* Maven publishing to 
  * Maven Central as `OSSRH`
  * GitHub Packages as `GitHubPackages`
