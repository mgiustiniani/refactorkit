package org.refactorkit.c

import org.refactorkit.core.BuildLanguageEvidence
import org.refactorkit.core.BuildLanguageFacet
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelProvider
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.SourceSetKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * Non-executable Build Model SPI projection for a captured `compile_commands.json`
 * database. Commands are parsed for flags only and never executed; no Make/CMake/
 * Meson/Bazel or project build code is run.
 */
class CCompilationDatabaseProvider(
    private val parser: CCompilationDatabaseParser = CCompilationDatabaseParser(),
) : BuildModelProvider {
    override val id: String = PROVIDER_ID

    override fun discover(request: BuildModelRequest): BuildModel {
        val workspace = request.workspaceRoot.toAbsolutePath().normalize()
        val databasePath = findCompilationDatabase(workspace)
        if (databasePath == null) {
            return unavailable(listOf(BuildModelDiagnostic(
                "c.compilationDatabaseMissing",
                "compile_commands.json was not found in the workspace",
            )), "")
        }
        val bytes = runCatching { Files.readAllBytes(databasePath) }.getOrElse {
            return unavailable(listOf(BuildModelDiagnostic(
                "c.compilationDatabaseRead",
                "compile_commands.json could not be read",
            )), "")
        }
        val discovery = parser.parse(workspace, bytes)
        if (discovery is CCompilationDatabaseDiscovery.Refused) {
            return unavailable(discovery.diagnostics.map {
                BuildModelDiagnostic(
                    code = it.code ?: "c.compilationDatabaseUnavailable",
                    message = it.message,
                    severity = it.severity,
                )
            }, "")
        }
        val database = (discovery as CCompilationDatabaseDiscovery.Available).database
        return project(database, databasePath, workspace)
    }

    private fun project(database: CCompilationDatabase, databasePath: Path, workspace: Path): BuildModel {
        val byDirectory = database.units.groupBy { it.directory }
        val diagnostics = mutableListOf<BuildModelDiagnostic>()
        val modules = byDirectory.map { (directory, units) ->
            val sourceRoots = units.map { it.file.parent }.filterNotNull()
                .map { workspace.relativize(it) }.distinct().sortedBy { it.toString() }
            val standards = units.mapNotNull { it.standard }.distinct().sorted()
            val targets = units.mapNotNull { it.target }.distinct().sorted()
            val missingStandard = units.count { it.standard == null }
            val conflictingStandard = standards.size > 1
            val conflictingTarget = targets.size > 1
            if (conflictingStandard) {
                diagnostics += BuildModelDiagnostic(
                    "c.compilationConfigurationConflict",
                    "translation units in '${ProtocolPath.serialize(directory)}' declare conflicting C standards: ${standards.joinToString()}",
                )
            }
            if (conflictingTarget) {
                diagnostics += BuildModelDiagnostic(
                    "c.compilationConfigurationConflict",
                    "translation units in '${ProtocolPath.serialize(directory)}' declare conflicting targets: ${targets.joinToString()}",
                )
            }
            if (missingStandard > 0) {
                diagnostics += BuildModelDiagnostic(
                    "c.compilationConfigurationIncomplete",
                    "$missingStandard translation unit(s) in '${ProtocolPath.serialize(directory)}' declare no C standard",
                )
            }
            BuildModule(
                id = "c:${ProtocolPath.serialize(directory)}",
                name = directory.fileName.toString().takeIf(String::isNotBlank) ?: "c",
                root = directory,
                sourceSets = listOf(BuildSourceSet(
                    id = "main",
                    kind = SourceSetKind.MAIN,
                    sourceRoots = sourceRoots,
                    attributes = linkedMapOf(
                        "backend" to "captured-compilation-database",
                        "database" to ProtocolPath.serialize(databasePath),
                        "standard" to standards.firstOrNull().orEmpty(),
                        "target" to targets.firstOrNull().orEmpty(),
                        "unitCount" to units.size.toString(),
                    ),
                    languageFacets = listOf(BuildLanguageFacet(
                        languageId = "c",
                        platformId = "native",
                        compilerId = "clang",
                        sourceVersion = standards.firstOrNull(),
                        targetVersion = targets.firstOrNull(),
                        evidence = BuildLanguageEvidence.DECLARED,
                    )),
                )),
                attributes = linkedMapOf(
                    "backend" to "captured-compilation-database",
                    "database" to ProtocolPath.serialize(databasePath),
                ),
            )
        }
        val status = when {
            diagnostics.any { it.code == "c.compilationConfigurationConflict" } -> BuildModelStatus.PARTIAL
            diagnostics.any { it.code == "c.compilationConfigurationIncomplete" } -> BuildModelStatus.PARTIAL
            else -> BuildModelStatus.AVAILABLE
        }
        return BuildModel(
            providerId = id,
            status = status,
            modules = modules.sortedBy(BuildModule::id),
            diagnostics = diagnostics,
            attributes = providerAttributes() + sortedMapOf(
                "database" to ProtocolPath.serialize(databasePath),
                "moduleCount" to modules.size.toString(),
                "unitCount" to database.units.size.toString(),
            ),
        )
    }

    private fun unavailable(diagnostics: List<BuildModelDiagnostic>, projectionHash: String): BuildModel =
        BuildModel(
            providerId = id,
            status = BuildModelStatus.UNAVAILABLE,
            modules = emptyList(),
            diagnostics = diagnostics,
            attributes = providerAttributes() + sortedMapOf("projectionHash" to projectionHash),
        )

    private fun providerAttributes(): Map<String, String> = sortedMapOf(
        "buildCodeExecution" to "denied",
        "credentialsAccess" to "denied",
        "networkAccess" to "denied",
        "providerVersion" to "1",
        // Each build-provider capability is declared separately (C03).
        "perUnitFlags" to "denied",
        "standard" to "declared",
        "defines" to "declared",
        "includes" to "declared",
        "target" to "declared",
        "sysroot" to "declared",
        "includeGraph" to "declared",
        "ownership" to "declared",
    )

    private fun findCompilationDatabase(workspace: Path): Path? {
        val candidates = listOf(
            workspace.resolve("compile_commands.json"),
            workspace.resolve("build").resolve("compile_commands.json"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    companion object {
        const val PROVIDER_ID = "c-compilation-database-v1"
    }
}
