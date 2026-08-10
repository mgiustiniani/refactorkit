package org.refactorkit.core

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Transient, language-neutral result produced for one exact snapshot candidate.
 *
 * The complete value graph is reconstructed at this boundary. This prevents a
 * provider's caller-owned collections from changing either the authoritative
 * value or the semantic state from which its fresh snapshot hash was computed.
 */
class AuthoritativeDiagnosticsEvaluation(
    snapshot: ProjectSnapshot,
    diagnostics: List<Diagnostic>,
) {
    val snapshot: ProjectSnapshot = deeplyDetachedSnapshot(snapshot)
    private val diagnosticValues: List<Diagnostic> = immutableList(
        diagnostics.map(::deeplyDetachedDiagnostic),
    )

    val diagnostics: List<Diagnostic> get() = diagnosticValues

    override fun equals(other: Any?): Boolean =
        other is AuthoritativeDiagnosticsEvaluation &&
            snapshot == other.snapshot &&
            diagnosticValues == other.diagnosticValues

    override fun hashCode(): Int = 31 * snapshot.hashCode() + diagnosticValues.hashCode()

    override fun toString(): String =
        "AuthoritativeDiagnosticsEvaluation(snapshot=$snapshot, diagnostics=$diagnosticValues)"
}

private fun deeplyDetachedSnapshot(source: ProjectSnapshot): ProjectSnapshot = ProjectSnapshot(
    workspace = Workspace(source.workspace.root),
    modules = immutableList(source.modules.map(::deeplyDetachedModule)),
    files = immutableList(source.files.map { SourceFile(it.path, it.content, it.languageId) }),
    sourceExtensions = immutableSet(source.sourceExtensions),
    ignoredDirectories = immutableSet(source.ignoredDirectories),
    classpathEvidence = immutableList(source.classpathEvidence.map {
        ClasspathEvidence(it.path, it.kind, it.fingerprint)
    }),
    buildModels = immutableList(source.buildModels.map(::deeplyDetachedBuildModel)),
    auxiliaryFiles = immutableList(source.auxiliaryFiles.map { SourceFile(it.path, it.content, it.languageId) }),
)

private fun deeplyDetachedModule(source: Module): Module = Module(
    name = source.name,
    root = source.root,
    sourceRoots = immutableList(source.sourceRoots),
    classpathEntries = immutableList(source.classpathEntries),
    dependencies = immutableList(source.dependencies),
    languageSettings = immutableMap(source.languageSettings),
    mainSourceRoots = immutableList(source.mainSourceRoots),
    testSourceRoots = immutableList(source.testSourceRoots),
    generatedSourceRoots = immutableList(source.generatedSourceRoots),
    generatedTestSourceRoots = immutableList(source.generatedTestSourceRoots),
    mainClasspathEntries = immutableList(source.mainClasspathEntries),
    mainRuntimeClasspathEntries = immutableList(source.mainRuntimeClasspathEntries),
    testClasspathEntries = immutableList(source.testClasspathEntries),
    mainDependencies = immutableList(source.mainDependencies),
    testDependencies = immutableList(source.testDependencies),
    mainOutputDirectories = immutableList(source.mainOutputDirectories),
    testOutputDirectories = immutableList(source.testOutputDirectories),
)

private fun deeplyDetachedBuildModel(source: BuildModel): BuildModel = BuildModel(
    providerId = source.providerId,
    status = source.status,
    modules = immutableList(source.modules.map(::deeplyDetachedBuildModule)),
    diagnostics = immutableList(source.diagnostics.map {
        BuildModelDiagnostic(it.code, it.message, it.moduleId, it.severity)
    }),
    attributes = immutableMap(source.attributes),
)

private fun deeplyDetachedBuildModule(source: BuildModule): BuildModule = BuildModule(
    id = source.id,
    name = source.name,
    root = source.root,
    sourceSets = immutableList(source.sourceSets.map(::deeplyDetachedBuildSourceSet)),
    attributes = immutableMap(source.attributes),
)

private fun deeplyDetachedBuildSourceSet(source: BuildSourceSet): BuildSourceSet = BuildSourceSet(
    id = source.id,
    kind = source.kind,
    sourceRoots = immutableList(source.sourceRoots),
    generatedSourceRoots = immutableList(source.generatedSourceRoots),
    outputDirectories = immutableList(source.outputDirectories),
    classpathEntries = immutableList(source.classpathEntries),
    runtimeClasspathEntries = immutableList(source.runtimeClasspathEntries),
    moduleDependencies = immutableList(source.moduleDependencies.map {
        BuildDependency(it.targetModuleId, it.scope)
    }),
    attributes = immutableMap(source.attributes),
    languageFacets = immutableList(source.languageFacets.map { facet ->
        BuildLanguageFacet(
            languageId = facet.languageId,
            platformId = facet.platformId,
            compilerId = facet.compilerId,
            sourceVersion = facet.sourceVersion,
            targetVersion = facet.targetVersion,
            targetRuntimeVersion = facet.targetRuntimeVersion,
            compilerPluginIds = immutableList(facet.compilerPluginIds),
            evidence = facet.evidence,
        )
    }),
)

private fun deeplyDetachedDiagnostic(source: Diagnostic): Diagnostic = Diagnostic(
    message = source.message,
    severity = source.severity,
    location = source.location?.let { location ->
        SourceLocation(
            path = location.path,
            range = SourceRange(
                start = SourcePosition(location.range.start.line, location.range.start.character),
                end = SourcePosition(location.range.end.line, location.range.end.character),
            ),
        )
    },
    code = source.code,
    evidence = source.evidence,
    category = source.category,
    locationPrecision = source.locationPrecision,
    details = DiagnosticDetails(source.details.fields),
)

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

/**
 * Evaluates one supplied candidate without receiving edit, approval, lock, WAL,
 * apply, transaction, or rollback authority.
 */
fun interface AuthoritativeDiagnosticsProvider {
    fun evaluate(candidate: ProjectSnapshot): AuthoritativeDiagnosticsEvaluation
}
