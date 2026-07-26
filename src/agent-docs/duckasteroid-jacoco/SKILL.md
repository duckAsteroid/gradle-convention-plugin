---
description: Opt-in duckAsteroid Gradle convention plugin that applies core Gradle's jacoco plugin with no extra configuration. Apply alongside duckasteroid-java.
---

# duckasteroid-jacoco

Thin opt-in plugin — applies Gradle's built-in `jacoco` plugin, nothing more.

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'
    id 'duckasteroid-jacoco' version '<version>'
}
```

No `jacoco { }` configuration (tool version, report formats, coverage thresholds, etc.) is
provided by this plugin — configure that yourself in the consuming project exactly as you would
with plain `id 'jacoco'`.
