package io.github.duckasteroid.conventions

import org.gradle.api.provider.Property

/**
 * Gradle-facing configuration for how the tagReleaseCandidates/tagReleaseCandidate tasks treat
 * previous release candidates once a new one is minted, registered by
 * duckasteroid-release-flow.gradle as the `releaseCandidates { }` extension. Release candidates
 * aren't permanent artifacts - only the one that eventually gets promoted matters, and any earlier
 * one is either absorbed into a later RC or simply abandoned - so by default every RC's GitHub
 * Release is deleted (not the underlying git tag or published package - see
 * {@link VersionResolver#currentCycleReleaseCandidateTags}) as soon as a newer one exists.
 *
 * Plain scalar Property<T>s, like {@link ChangelogExtension} - no SetProperty append-vs-replace
 * gotcha here, `.convention(...)` works exactly as expected.
 *
 * <pre>
 * releaseCandidates {
 *     pruneSuperseded = false   // default: true - keep every RC's GitHub Release forever instead
 *     retain = 2                // default: 0 - also keep the 2 most recent RCs besides the new one
 * }
 * </pre>
 */
abstract class ReleaseCandidatesExtension {

    abstract Property<Boolean> getPruneSuperseded()

    abstract Property<Integer> getRetain()
}
