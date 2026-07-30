package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Symbol
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
    ) : JavaMoveClassRequestValidation

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

        val newRelativePath = computeNewPath(
            declarationFile.path.normalize(),
            oldPackage,
            targetPackage,
            simpleName,
        ).normalize()
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
        )
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
