package org.refactorkit.java

import org.refactorkit.core.BuildDependency
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.SourceSetKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/** Bounded parser for an intentionally small, non-executable Gradle DSL subset. */
internal class GradleDeclarativeModelBuilder {
    fun build(workspaceRoot: Path): BuildModel {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val buildFiles = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() in BUILD_FILES }
                .filter { path -> root.relativize(path).none { it.toString() in IGNORED_DIRECTORIES } }
                .sorted().toList()
        }
        if (buildFiles.isEmpty()) return notApplicable()
        val moduleRoots = buildFiles.map { it.parent.toAbsolutePath().normalize() }.distinct()
        val moduleIds = moduleRoots.associateWith { moduleId(root, it) }
        val diagnostics = mutableListOf<BuildModelDiagnostic>()
        val modules = buildFiles.mapNotNull { buildFile ->
            val moduleRoot = buildFile.parent.toAbsolutePath().normalize()
            val id = moduleIds.getValue(moduleRoot)
            val text = runCatching {
                require(Files.size(buildFile) <= MAX_BUILD_FILE_BYTES) { "descriptor exceeds bounded size" }
                Files.readString(buildFile)
            }.getOrElse { error ->
                diagnostics += BuildModelDiagnostic(
                    "buildModel.executionRefused",
                    "Gradle descriptor '$id' cannot be modeled declaratively: ${error.message ?: "unavailable"}",
                    id,
                )
                return@mapNotNull unavailableModule(root, moduleRoot, id)
            }
            val parsed = parseModule(root, moduleRoot, id, text, moduleIds.values.toSet())
            diagnostics += parsed.diagnostics
            parsed.module
        }
        val status = when {
            diagnostics.any { it.severity == Diagnostic.Severity.ERROR } -> BuildModelStatus.EXECUTION_REFUSED
            else -> BuildModelStatus.PARTIAL
        }
        if (diagnostics.none { it.code == "buildModel.partial" }) {
            diagnostics += BuildModelDiagnostic(
                "buildModel.partial",
                "Gradle model uses bounded declarative metadata; settings/scripts/tasks were not executed",
                severity = Diagnostic.Severity.WARNING,
            )
        }
        return BuildModel(
            providerId = PROVIDER_ID,
            status = status,
            modules = modules,
            diagnostics = diagnostics,
            attributes = mapOf(
                "ecosystem" to "gradle",
                "strategy" to "declarative-heuristic",
                "buildCodeExecution" to "denied",
                "toolingApi" to "denied",
                "credentialsAccess" to "denied",
                "networkDefault" to "denied",
                "networkAccess" to "denied",
            ),
        )
    }

    private data class ParsedModule(val module: BuildModule, val diagnostics: List<BuildModelDiagnostic>)

    private fun unavailableModule(workspaceRoot: Path, moduleRoot: Path, id: String): BuildModule = BuildModule(
        id = id,
        name = id,
        root = moduleRoot,
        sourceSets = listOf(
            BuildSourceSet(
                id = "main",
                kind = SourceSetKind.MAIN,
                sourceRoots = listOf(workspaceRoot.relativize(moduleRoot.resolve("src/main/java"))),
                outputDirectories = listOf(workspaceRoot.relativize(moduleRoot.resolve("build/classes/java/main"))),
                attributes = mapOf("java.sourceLevel" to "8", "visibility" to "main"),
            ),
            BuildSourceSet(
                id = "test",
                kind = SourceSetKind.TEST,
                sourceRoots = listOf(workspaceRoot.relativize(moduleRoot.resolve("src/test/java"))),
                outputDirectories = listOf(workspaceRoot.relativize(moduleRoot.resolve("build/classes/java/test"))),
                attributes = mapOf("java.sourceLevel" to "8", "visibility" to "test"),
            ),
        ),
        attributes = mapOf(
            "java.buildSystem" to "gradle",
            "java.buildModel.status" to "unavailable",
            "java.buildModel.message" to "Gradle descriptor cannot be modeled without denied execution",
            "java.sourceLevel" to "8",
        ),
    )

    private fun parseModule(
        workspaceRoot: Path,
        moduleRoot: Path,
        id: String,
        text: String,
        knownModuleIds: Set<String>,
    ): ParsedModule {
        val diagnostics = mutableListOf<BuildModelDiagnostic>()
        val rootsBySet = linkedMapOf(
            "main" to linkedSetOf(moduleRoot.resolve("src/main/java")),
            "test" to linkedSetOf(moduleRoot.resolve("src/test/java")),
        )
        val customNames = linkedSetOf<String>()
        CREATE_SOURCE_SET.findAll(text).map { it.groupValues[1] }.forEach(customNames::add)
        customNames.forEach { name -> rootsBySet.getOrPut(name, ::linkedSetOf) }
        EXPLICIT_SOURCE_DIRECTORIES.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val sourceSet = match.groupValues[1]
                val rawPath = match.groupValues[2]
                rootsBySet.getOrPut(sourceSet) { linkedSetOf() }
                safeRoot(workspaceRoot, moduleRoot, rawPath)?.let { rootsBySet.getValue(sourceSet).add(it) }
                    ?: diagnostics.add(unsafeRoot(id))
            }
        }
        val sourceLevel = detectSourceLevel(text)
        val dependencies = linkedMapOf<String, MutableList<BuildDependency>>()
        PROJECT_DEPENDENCY.findAll(text).forEach { match ->
            val configuration = match.groupValues[1]
            val target = match.groupValues[2].trim(':').replace(':', ':')
            val sourceSet = configurationSourceSet(configuration)
            if (target in knownModuleIds) {
                dependencies.getOrPut(sourceSet, ::mutableListOf).add(BuildDependency(
                    target,
                    if (sourceSet == "main") DependencyScope.COMPILE else DependencyScope.TEST,
                ))
            }
        }
        val sourceSets = rootsBySet.map { (name, absoluteRoots) ->
            val kind = when {
                name == "main" -> SourceSetKind.MAIN
                name == "test" -> SourceSetKind.TEST
                name.contains("integration", ignoreCase = true) -> SourceSetKind.INTEGRATION_TEST
                else -> SourceSetKind.CUSTOM
            }
            val roots = absoluteRoots.map(workspaceRoot::relativize).distinct()
            val generated = roots.filter { root ->
                val normalized = root.toString().replace('\\', '/').lowercase()
                "/build/generated/" in "/$normalized/" && workspaceRoot.resolve(root).exists()
            }
            BuildSourceSet(
                id = name,
                kind = kind,
                sourceRoots = roots,
                generatedSourceRoots = generated,
                outputDirectories = listOf(workspaceRoot.relativize(moduleRoot.resolve("build/classes/java/$name"))),
                moduleDependencies = dependencies[name].orEmpty().distinct(),
                attributes = mapOf(
                    "java.sourceLevel" to sourceLevel.toString(),
                    "visibility" to if (kind in setOf(SourceSetKind.TEST, SourceSetKind.INTEGRATION_TEST)) "test" else "main",
                ),
            )
        }
        return ParsedModule(
            BuildModule(
                id = id,
                name = id,
                root = moduleRoot,
                sourceSets = sourceSets,
                attributes = mapOf(
                    "java.buildSystem" to "gradle",
                    "java.buildModel.status" to if (diagnostics.isEmpty()) "partial" else "unavailable",
                    "java.buildModel.message" to if (diagnostics.isEmpty()) {
                        "Gradle metadata uses deterministic declarative heuristics; effective execution is disabled"
                    } else {
                        "Gradle source-root declarations escaped the workspace"
                    },
                    "java.sourceLevel" to sourceLevel.toString(),
                ),
            ),
            diagnostics,
        )
    }

    private fun moduleId(workspaceRoot: Path, moduleRoot: Path): String {
        val relative = workspaceRoot.relativize(moduleRoot)
        return if (relative.toString().isBlank()) workspaceRoot.fileName?.toString() ?: "root"
        else relative.joinToString(":")
    }

    private fun safeRoot(workspaceRoot: Path, moduleRoot: Path, raw: String): Path? {
        val parsed = runCatching { Path.of(raw) }.getOrNull() ?: return null
        val absolute = (if (parsed.isAbsolute) parsed else moduleRoot.resolve(parsed)).toAbsolutePath().normalize()
        if (!absolute.startsWith(workspaceRoot)) return null
        if (absolute.exists()) {
            val workspaceReal = runCatching { workspaceRoot.toRealPath() }.getOrNull() ?: return null
            val real = runCatching { absolute.toRealPath() }.getOrNull() ?: return null
            if (!real.startsWith(workspaceReal)) return null
        }
        return absolute
    }

    private fun unsafeRoot(moduleId: String) = BuildModelDiagnostic(
        "buildModel.executionRefused",
        "Gradle module '$moduleId' contains a source-root declaration outside the workspace",
        moduleId,
    )

    private fun detectSourceLevel(text: String): Int = SOURCE_LEVEL_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8..25 }
    } ?: 8

    private fun configurationSourceSet(configuration: String): String = when {
        configuration.startsWith("test") -> "test"
        configuration in setOf("implementation", "api", "compileOnly", "runtimeOnly") -> "main"
        else -> configuration.removeSuffix("Implementation").removeSuffix("CompileOnly").removeSuffix("RuntimeOnly")
            .replaceFirstChar(Char::lowercase)
    }

    private fun notApplicable() = BuildModel(
        providerId = PROVIDER_ID,
        status = BuildModelStatus.UNAVAILABLE,
        modules = emptyList(),
        diagnostics = listOf(BuildModelDiagnostic(
            "buildModel.notApplicable",
            "No Gradle build descriptors were discovered",
            severity = Diagnostic.Severity.INFO,
        )),
        attributes = mapOf("ecosystem" to "gradle", "strategy" to "declarative-heuristic"),
    )

    companion object {
        const val PROVIDER_ID = "gradle-declarative-v1"
        private const val MAX_BUILD_FILE_BYTES = 2L * 1024L * 1024L
        private val BUILD_FILES = setOf("build.gradle", "build.gradle.kts")
        private val IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".refactorkit", "build", "target")
        private val CREATE_SOURCE_SET = Regex("sourceSets\\s*\\.\\s*(?:create|maybeCreate)\\s*\\(\\s*[\"']([A-Za-z][A-Za-z0-9_-]{0,63})[\"']\\s*\\)")
        private val EXPLICIT_SOURCE_DIRECTORIES = listOf(
            Regex("sourceSets\\s*\\[\\s*[\"']([A-Za-z][A-Za-z0-9_-]{0,63})[\"']\\s*]\\s*\\.\\s*java\\s*\\.\\s*srcDir\\s*(?:\\(\\s*)?[\"']([^\"']+)[\"']"),
            Regex("sourceSets\\s*\\.\\s*([A-Za-z][A-Za-z0-9_-]{0,63})\\s*\\.\\s*java\\s*\\.\\s*srcDir\\s*(?:\\(\\s*)?[\"']([^\"']+)[\"']"),
        )
        private val PROJECT_DEPENDENCY = Regex(
            "([A-Za-z][A-Za-z0-9_]*)\\s*\\(\\s*project\\s*\\(\\s*[\"'](:[A-Za-z0-9_:-]+)[\"']\\s*\\)\\s*\\)",
        )
        private val SOURCE_LEVEL_PATTERNS = listOf(
            Regex("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)"),
            Regex("JavaVersion\\.VERSION_(\\d+)"),
            Regex("(?:sourceCompatibility|targetCompatibility|options\\.release(?:\\.set)?)\\D{0,20}(\\d+)"),
        )
    }
}
