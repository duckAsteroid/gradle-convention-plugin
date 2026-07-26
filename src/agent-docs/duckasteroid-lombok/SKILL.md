---
description: Opt-in duckAsteroid Gradle convention plugin that applies the Freefair io.github.freefair.lombok plugin with no extra configuration. Apply alongside duckasteroid-java.
---

# duckasteroid-lombok

Thin opt-in plugin — applies the Freefair `io.freefair.lombok` plugin, nothing more.

```groovy
plugins {
    id 'duckasteroid-java' version '<version>'
    id 'duckasteroid-lombok' version '<version>'
}
```

No Lombok-specific configuration is provided by this plugin — configure it yourself in the
consuming project exactly as you would with the plain Freefair Lombok plugin.
