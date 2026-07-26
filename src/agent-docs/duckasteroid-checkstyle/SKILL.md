---
description: Opt-in duckAsteroid Gradle convention plugin that applies core Gradle's checkstyle plugin with no extra configuration. Apply alongside duckasteroid-java.
---

# duckasteroid-checkstyle

Thin opt-in plugin — applies Gradle's built-in `checkstyle` plugin, nothing more.

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'
    id 'duckasteroid-checkstyle' version '<version>'
}
```

No `checkstyle { }` configuration (rule set, toolVersion, etc.) is provided by this plugin —
configure that yourself in the consuming project exactly as you would with plain
`id 'checkstyle'`.
