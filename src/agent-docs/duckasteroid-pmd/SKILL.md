---
description: Opt-in duckAsteroid Gradle convention plugin that applies core Gradle's pmd plugin with no extra configuration. Apply alongside duckasteroid-java.
---

# duckasteroid-pmd

Thin opt-in plugin — applies Gradle's built-in `pmd` plugin, nothing more.

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'
    id 'duckasteroid-pmd' version '<version>'
}
```

No `pmd { }` configuration (rule sets, tool version, etc.) is provided by this plugin — configure
that yourself in the consuming project exactly as you would with plain `id 'pmd'`.
