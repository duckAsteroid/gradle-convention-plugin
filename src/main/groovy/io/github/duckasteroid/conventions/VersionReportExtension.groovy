package io.github.duckasteroid.conventions

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * Gradle-facing configuration for explainVersion/explainVersions' structured JSON report (see issue
 * #5), registered by duckasteroid-release-flow.gradle as the `versionReport { }` extension. The
 * console breakdown these tasks print always happens regardless of this extension - it only
 * controls the {@code build/version-report.json} file.
 *
 * Plain scalar Property<T>s, like {@link ChangelogExtension}/{@link ReleaseCandidatesExtension} -
 * no SetProperty append-vs-replace gotcha here, `.convention(...)` works exactly as expected.
 *
 * <pre>
 * versionReport {
 *     enabled = false                                                 // default: true
 *     outputFile = layout.buildDirectory.file('reports/version.json') // default: build/version-report.json
 * }
 * </pre>
 */
abstract class VersionReportExtension {

    abstract Property<Boolean> getEnabled()

    abstract RegularFileProperty getOutputFile()
}
