package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Symbol
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildSourceRootOwnership
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.exactBuildSourceRootOwnerships
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Path
import java.nio.file.Paths

/** Focused, side-effect-free move request preflight that never constructs a patch plan. */
internal sealed interface JavaMoveClassRequestValidation {
    data class Supported(
        val symbolFqn: String,
        val targetPackage: String,
        val oldPackage: String,
        val simpleName: String,
        val newFqn: String,
        val declarationFile: SourceFile,
        val newRelativePath: Path,
        val sourceOwner: BuildSourceRootOwnership?,
        val targetOwner: BuildSourceRootOwnership?,
    ) : JavaMoveClassRequestValidation {
        val isCrossModuleMove: Boolean
            get() = sourceOwner != null && targetOwner != null && sourceOwner.module.id != targetOwner.module.id
    }

    data class Refused(
        val summary: String,
        val code: String? = null,
    ) : JavaMoveClassRequestValidation
}

internal object JavaMoveClassRequestValidator {
    fun validate(
        snapshot: ProjectSnapshot,
        adapter: JavaLanguageAdapter,
        symbolFqn: String,
        targetPackage: String,
        targetModuleId: String? = null,
    ): JavaMoveClassRequestValidation {
        val oldPackage = JavaPackageUtil.packageOf(symbolFqn)
        val simpleName = JavaPackageUtil.simpleName(symbolFqn)
        val newFqn = JavaPackageUtil.fqn(targetPackage, simpleName)

        if (oldPackage == targetPackage) {
            return refused("Source and target packages are the same: $targetPackage")
        }
        if (!isValidPackageName(targetPackage)) {
            return refused("Invalid target package: $targetPackage")
        }

        val index = adapter.buildSymbols(snapshot)
        val symbol = index.symbols.filter { it.id.value == symbolFqn }.singleOrNull()
            ?.takeIf { it.kind in MOVEABLE_KINDS }
            ?: return refused("Symbol not found or not a moveable type: $symbolFqn")
        val declarationPath = symbol.location.path.normalize()
        val declarationFile = snapshot.files.singleOrNull {
            it.path.normalize() == declarationPath && it.languageId == "java"
        } ?: return refused("Declaration file not found: ${symbol.location.path}")
        JavaGeneratedSourcePolicy.reason(declarationFile)?.let { reason ->
            return refused("Generated source cannot be rewritten: ${declarationFile.path} ($reason)")
        }

        val sourceOwner = snapshot.owningBuildSourceRoots(declarationFile.path.normalize()).singleOrNull()
        val packageOwner = targetPackageOwner(snapshot, targetPackage, sourceOwner)
        if (targetModuleId == null && packageOwner == null && hasOwnedTargetPackageFiles(snapshot, targetPackage)) {
                return refused(
                    "Target package ownership is ambiguous or incompatible with the source set: $targetPackage",
                    "java.moveClass.targetPackageOwner.ambiguous",
                )
        }
        val targetOwner = if (targetModuleId == null) {
            packageOwner
        } else {
            when (val selection = selectedTargetModuleOwner(snapshot, sourceOwner, targetModuleId)) {
                is TargetModuleOwnerSelection.Refused -> return refused(selection.summary, selection.code)
                is TargetModuleOwnerSelection.Selected -> selection.owner
            }.also { selectedOwner ->
                if (hasOwnedTargetPackageFiles(snapshot, targetPackage) &&
                    (packageOwner == null || !sameSourceRootOwner(packageOwner, selectedOwner))
                ) {
                    return refused(
                        "Existing target package is not owned by the explicitly selected module: $targetPackage",
                        "java.moveClass.targetModulePackageOwnerMismatch",
                    )
                }
            }
        }
        val newRelativePath = targetOwner?.root?.resolve(JavaPackageUtil.packageToPath(targetPackage))
            ?.resolve("$simpleName.java")
            ?: computeNewPath(
                declarationFile.path.normalize(),
                oldPackage,
                targetPackage,
                simpleName,
            )
            .normalize()
        if (targetOwner != null && sourceOwner == null) {
            return refused(
                "Source build ownership is required when resolving a declared target package: $symbolFqn",
                "java.moveClass.sourceOwnershipUnavailable",
            )
        }
        if (sourceOwner != null && targetOwner != null &&
            (sourceOwner.providerId != targetOwner.providerId || sourceOwner.modelStatus != targetOwner.modelStatus)
        ) {
            return refused(
                "Source and target package resolve through different build-model authorities",
                "java.moveClass.targetPackageOwner.ambiguous",
            )
        }
        val crossModuleTarget = sourceOwner != null && targetOwner != null &&
            sourceOwner.module.id != targetOwner.module.id
        if (crossModuleTarget && (sourceOwner!!.providerId != targetOwner!!.providerId ||
                sourceOwner.modelStatus != BuildModelStatus.AVAILABLE ||
                targetOwner.modelStatus != BuildModelStatus.AVAILABLE ||
                sourceOwner.sourceSet.kind != targetOwner.sourceSet.kind ||
                sourceOwner.sourceSet.kind != SourceSetKind.MAIN
            )
        ) {
            return refused(
                "Cross-module move requires one available build model and compatible main source sets",
                "java.moveClass.crossModuleContextUnavailable",
            )
        }
        if (index.symbols.any { it.id.value == newFqn } ||
            snapshot.trackedFiles.any { it.path.normalize() == newRelativePath }
        ) {
            return refused("Move target already exists: $newFqn ($newRelativePath)")
        }
        return JavaMoveClassRequestValidation.Supported(
            symbolFqn = symbolFqn,
            targetPackage = targetPackage,
            oldPackage = oldPackage,
            simpleName = simpleName,
            newFqn = newFqn,
            declarationFile = declarationFile,
            newRelativePath = newRelativePath,
            sourceOwner = sourceOwner,
            targetOwner = targetOwner,
        )
    }

    private fun targetPackageOwner(
        snapshot: ProjectSnapshot,
        targetPackage: String,
        sourceOwner: BuildSourceRootOwnership?,
    ): BuildSourceRootOwnership? {
        val packageFiles = snapshot.files.filter { file ->
            file.languageId == "java" &&
                JavaGeneratedSourcePolicy.reason(file) == null &&
                JavaPackageUtil.extractPackage(file.content) == targetPackage
        }
        if (packageFiles.isEmpty()) return null
        val owners = packageFiles.map { file ->
            snapshot.owningBuildSourceRoots(file.path.normalize()).singleOrNull() ?: return null
        }
        val sourceKind = sourceOwner?.sourceSet?.kind
        val compatible = if (sourceKind == null) owners else owners.filter { it.sourceSet.kind == sourceKind }
        val distinct = compatible.distinctBy { owner ->
            listOf(owner.providerId, owner.module.id, owner.sourceSet.id, owner.root.toString())
        }
        return distinct.singleOrNull()
    }

    private sealed interface TargetModuleOwnerSelection {
        data class Selected(val owner: BuildSourceRootOwnership) : TargetModuleOwnerSelection
        data class Refused(val summary: String, val code: String) : TargetModuleOwnerSelection
    }

    private fun selectedTargetModuleOwner(
        snapshot: ProjectSnapshot,
        sourceOwner: BuildSourceRootOwnership?,
        targetModuleId: String,
    ): TargetModuleOwnerSelection {
        if (targetModuleId.isBlank() || targetModuleId.length > 128) {
            return TargetModuleOwnerSelection.Refused(
                "Selected destination module ID is invalid",
                "java.moveClass.targetModuleIdInvalid",
            )
        }
        if (sourceOwner == null || sourceOwner.modelStatus != BuildModelStatus.AVAILABLE) {
            return TargetModuleOwnerSelection.Refused(
                "Explicit target-module selection requires available source-module ownership",
                "java.moveClass.crossModuleContextUnavailable",
            )
        }
        val model = snapshot.buildModels.singleOrNull { it.providerId == sourceOwner.providerId }
            ?.takeIf { it.status == BuildModelStatus.AVAILABLE }
            ?: return TargetModuleOwnerSelection.Refused(
                "Explicit target-module selection requires the source's available build model",
                "java.moveClass.crossModuleContextUnavailable",
            )
        val module = model.modules.singleOrNull { it.id == targetModuleId }
            ?: return TargetModuleOwnerSelection.Refused(
                "Selected module ID is not uniquely present in the source build-model provider: $targetModuleId",
                "java.moveClass.targetModuleUnavailable",
            )
        val mainRoots = module.sourceSets.asSequence()
            .filter { it.kind == SourceSetKind.MAIN }
            .flatMap { sourceSet ->
                sourceSet.sourceRoots.asSequence()
                    .filterNot { root -> sourceSet.generatedSourceRoots.any { it.normalize() == root.normalize() } }
                    .map { sourceSet to it.normalize() }
            }
            .distinctBy { (sourceSet, root) -> sourceSet.id to root }
            .toList()
        val roots = mainRoots.map { it.second }.distinct()
        if (roots.size != 1 || mainRoots.size != 1) {
            return TargetModuleOwnerSelection.Refused(
                "Selected module must own exactly one non-generated main source root: $targetModuleId",
                "java.moveClass.targetModuleSourceRootUnavailable",
            )
        }
        val root = roots.single()
        val exactOwners = snapshot.exactBuildSourceRootOwnerships(root)
        val owner = exactOwners.singleOrNull()
        if (owner == null || owner.providerId != model.providerId || owner.modelStatus != BuildModelStatus.AVAILABLE ||
            owner.module.id != module.id || owner.generated || owner.sourceSet.kind != SourceSetKind.MAIN
        ) {
            return TargetModuleOwnerSelection.Refused(
                "Selected module source root is not uniquely owned as a non-generated main root: $targetModuleId",
                "java.moveClass.targetModuleSourceRootUnavailable",
            )
        }
        return TargetModuleOwnerSelection.Selected(owner)
    }

    private fun sameSourceRootOwner(left: BuildSourceRootOwnership, right: BuildSourceRootOwnership): Boolean =
        left.providerId == right.providerId && left.module.id == right.module.id &&
            left.sourceSet.id == right.sourceSet.id && left.root == right.root

    private fun hasOwnedTargetPackageFiles(snapshot: ProjectSnapshot, targetPackage: String): Boolean =
        snapshot.files.any { file ->
            file.languageId == "java" && JavaPackageUtil.extractPackage(file.content) == targetPackage
        }

    private fun computeNewPath(
        oldPath: Path,
        oldPackage: String,
        newPackage: String,
        simpleName: String,
    ): Path {
        val oldPackageParts = if (oldPackage.isEmpty()) 0 else oldPackage.split('.').size
        var current: Path? = oldPath.parent
        repeat(oldPackageParts) { current = current?.parent }
        val sourceRoot = current ?: Paths.get(".")
        return sourceRoot.resolve(JavaPackageUtil.packageToPath(newPackage)).resolve("$simpleName.java")
    }

    private fun isValidPackageName(packageName: String): Boolean =
        packageName.isNotBlank() && packageName.split('.').all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_' || segment.first() == '$') &&
                segment.all(JavaLexer::isIdentChar)
        }

    private fun refused(summary: String, code: String? = null) =
        JavaMoveClassRequestValidation.Refused(summary, code)

    private val MOVEABLE_KINDS = setOf(
        Symbol.Kind.CLASS,
        Symbol.Kind.INTERFACE,
        Symbol.Kind.ENUM,
        Symbol.Kind.RECORD,
        Symbol.Kind.ANNOTATION,
    )
}
