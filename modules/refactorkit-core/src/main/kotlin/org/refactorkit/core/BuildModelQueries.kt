package org.refactorkit.core

import java.nio.file.Path

/** Provenance-rich ownership of one workspace-relative source root. */
data class BuildSourceRootOwnership(
    val providerId: String,
    val modelStatus: BuildModelStatus,
    val module: BuildModule,
    val sourceSet: BuildSourceSet,
    val root: Path,
    val generated: Boolean,
)

/** All source-root declarations, preserving provider/module/source-set provenance. */
fun ProjectSnapshot.buildSourceRootOwnerships(): List<BuildSourceRootOwnership> =
    buildModels.flatMap { model ->
        model.modules.flatMap { module ->
            module.sourceSets.flatMap { sourceSet ->
                sourceSet.sourceRoots.map { root ->
                    BuildSourceRootOwnership(
                        providerId = model.providerId,
                        modelStatus = model.status,
                        module = module,
                        sourceSet = sourceSet,
                        root = root.normalize(),
                        generated = root.normalize() in sourceSet.generatedSourceRoots.map(Path::normalize),
                    )
                }
            }
        }
    }

fun ProjectSnapshot.exactBuildSourceRootOwnerships(root: Path): List<BuildSourceRootOwnership> {
    val normalized = root.normalize()
    return buildSourceRootOwnerships().filter { it.root == normalized }
}

/** Longest-prefix source ownership for a workspace-relative file or directory. */
fun ProjectSnapshot.owningBuildSourceRoots(path: Path): List<BuildSourceRootOwnership> {
    val normalized = path.normalize()
    val candidates = buildSourceRootOwnerships().filter { normalized.startsWith(it.root) }
    val longest = candidates.maxOfOrNull { it.root.nameCount } ?: return emptyList()
    return candidates.filter { it.root.nameCount == longest }
}
