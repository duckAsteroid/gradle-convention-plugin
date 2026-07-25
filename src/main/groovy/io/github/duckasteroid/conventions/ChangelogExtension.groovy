package io.github.duckasteroid.conventions

import org.gradle.api.provider.Property

/**
 * Gradle-facing configuration for {@link ChangelogGenerator}, registered by
 * duckasteroid-release-flow.gradle as the `changelog { }` extension. Only `rcScope` exists - there's
 * no equivalent choice for the final release, which always covers everything since the last release
 * regardless (see {@link ChangelogScope}).
 *
 * Unlike {@link CommitAnalyzerExtension}'s SetProperty-s, this is a plain scalar
 * {@code Property<ChangelogScope>}, so the usual convention()-vs-addAll() gotcha doesn't apply here -
 * there's no "append" operation for a single value, `.convention(...)` works exactly as expected as
 * a plain fallback.
 *
 * <pre>
 * changelog {
 *     rcScope = ChangelogScope.SINCE_PREVIOUS_RC   // default: SINCE_LAST_RELEASE
 * }
 * </pre>
 */
abstract class ChangelogExtension {

    abstract Property<ChangelogScope> getRcScope()
}
