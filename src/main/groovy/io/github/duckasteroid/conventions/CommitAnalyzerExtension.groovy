package io.github.duckasteroid.conventions

import org.gradle.api.provider.SetProperty

/**
 * Gradle-facing configuration for {@link CommitAnalyzer}'s type-to-bump mapping. Registered by
 * duckasteroid-java.gradle as the `commitAnalyzer { }` extension, with each property
 * *pre-populated* (via {@code addAll(...)}, not {@code convention(...)} - see the note below) with
 * the corresponding entry of {@link CommitAnalyzer#DEFAULT_TYPE_RULES}. This is an abstract class
 * rather than a plain one deliberately - Gradle auto-implements the abstract SetProperty getters
 * (its "managed properties" mechanism).
 *
 * Consumers configure it like:
 *
 * <pre>
 * commitAnalyzer {
 *     minorTypes.add('perf2')        // append 'perf2' to the default minor-bump types
 *     majorTypes.add('security')     // always treat "security: ..." commits as breaking
 *     noBumpTypes.set(['docs'])      // REPLACE the default no-bump set entirely (not append)
 * }
 * </pre>
 *
 * `.add(...)`/`.addAll(...)` appends to whatever's already in the property; `.set(...)` replaces it
 * outright. Note this is why duckasteroid-java.gradle seeds the defaults with {@code addAll(...)}
 * rather than {@code convention(...)}: a {@code Property}'s convention is only used as a fallback
 * while nothing has been explicitly added/set - the moment {@code .add(...)} is called, Gradle
 * discards the convention rather than appending to it, so seeding with {@code convention(...)}
 * would make {@code minorTypes.add('perf2')} silently end up as just {@code ['perf2']}, losing the
 * default {@code 'feat'}.
 */
abstract class CommitAnalyzerExtension {

    abstract SetProperty<String> getMajorTypes()

    abstract SetProperty<String> getMinorTypes()

    abstract SetProperty<String> getPatchTypes()

    abstract SetProperty<String> getNoBumpTypes()

    /** Materializes the four properties into the {@code Map<Bump, Set<String>>} CommitAnalyzer expects. */
    Map<CommitAnalyzer.Bump, Set<String>> toTypeRules() {
        return [
                (CommitAnalyzer.Bump.MAJOR): majorTypes.get() as Set<String>,
                (CommitAnalyzer.Bump.MINOR): minorTypes.get() as Set<String>,
                (CommitAnalyzer.Bump.PATCH): patchTypes.get() as Set<String>,
                (CommitAnalyzer.Bump.NONE) : noBumpTypes.get() as Set<String>,
        ]
    }
}
