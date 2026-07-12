package org.refactorkit.java

import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.lang.model.SourceVersion

enum class JavaSourceSet { MAIN, TEST, GENERATED, CUSTOM }

data class JavaImportTarget(
    val directory: Path,
    val moduleName: String,
    val sourceRoot: Path,
    val sourceSet: JavaSourceSet,
    val packageName: String,
)

data class JavaImportTargetRefusal(
    val code: String,
    val message: String,
    val evidence: List<String>,
    val nextAction: String,
)

sealed interface JavaImportTargetResolution {
    data class Resolved(val target: JavaImportTarget) : JavaImportTargetResolution
    data class Refused(val refusal: JavaImportTargetRefusal) : JavaImportTargetResolution
}

/** Resolves an existing workspace-relative directory through the scanned Java project model. */
class JavaImportTargetResolver {
    fun resolve(
        snapshot: ProjectSnapshot,
        targetDirectory: String,
        requestedPackage: String? = null,
        requestedModule: String? = null,
    ): JavaImportTargetResolution {
        val raw = targetDirectory.trim()
        if (raw.isEmpty()) return refusal("targetDirectory.empty", "targetDirectory must not be empty")
        if (WINDOWS_DRIVE_PATH.matches(raw) || raw.startsWith("\\\\") || raw.startsWith("//")) {
            return refusal("targetDirectory.absolute", "targetDirectory must be workspace-relative")
        }
        val relative = try {
            Path.of(raw)
        } catch (_: InvalidPathException) {
            return refusal("targetDirectory.invalid", "targetDirectory is not a valid path")
        }
        if (relative.isAbsolute) return refusal("targetDirectory.absolute", "targetDirectory must be workspace-relative")
        if (raw.split('/', '\\').any { it == ".." } || relative.any { it.toString() == ".." }) {
            return refusal("targetDirectory.traversal", "targetDirectory must not contain '..' traversal segments")
        }

        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        val workspaceReal = try {
            workspace.toRealPath()
        } catch (_: Exception) {
            return refusal("targetDirectory.workspaceUnavailable", "The opened workspace root cannot be canonicalized")
        }
        val candidate = workspace.resolve(relative).normalize()
        if (!candidate.startsWith(workspace)) {
            return refusal("targetDirectory.outsideWorkspace", "targetDirectory escapes the opened workspace")
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return refusal("targetDirectory.missing", "targetDirectory does not exist")
        }
        if (!Files.isDirectory(candidate)) {
            return refusal("targetDirectory.notDirectory", "targetDirectory identifies a file, not a directory")
        }
        val candidateReal = try {
            candidate.toRealPath()
        } catch (_: Exception) {
            return refusal("targetDirectory.unreadable", "targetDirectory cannot be canonicalized")
        }
        if (!candidateReal.startsWith(workspaceReal)) {
            return refusal("targetDirectory.symlinkEscape", "targetDirectory resolves outside the opened workspace")
        }
        if (containsSymbolicLink(workspace, candidate)) {
            return refusal(
                "targetDirectory.symbolicLink",
                "targetDirectory traverses a symbolic link and cannot be used by managed apply",
            )
        }

        val owners = snapshot.modules.flatMap { module ->
            module.sourceRoots.mapNotNull { sourceRoot ->
                val absoluteRoot = workspace.resolve(sourceRoot).normalize()
                val realRoot = runCatching { absoluteRoot.toRealPath() }.getOrNull() ?: return@mapNotNull null
                if (candidateReal.startsWith(realRoot)) Owner(module, sourceRoot.normalize(), realRoot) else null
            }
        }
        if (owners.isEmpty()) {
            return refusal(
                "targetDirectory.outsideSourceRoot",
                "targetDirectory is not inside a recognized Java source root",
                evidence = snapshot.modules.flatMap(Module::sourceRoots).map { "recognizedSourceRoot=$it" }.sorted(),
                nextAction = "Select an existing directory beneath one recognized Java source root.",
            )
        }
        if (owners.size != 1) {
            return refusal(
                "targetDirectory.ambiguousSourceRoot",
                "targetDirectory is owned by multiple Java module/source-root candidates",
                evidence = owners.map { "module=${it.module.name} sourceRoot=${it.relativeRoot}" }.sorted(),
                nextAction = "Correct overlapping source-root configuration before retrying.",
            )
        }

        val owner = owners.single()
        if (requestedModule != null && requestedModule != owner.module.name && requestedModule != owner.module.root.fileName?.toString()) {
            return refusal(
                "targetDirectory.moduleMismatch",
                "targetModule is inconsistent with targetDirectory ownership",
                evidence = listOf("resolvedModule=${owner.module.name}", "requestedModule=$requestedModule"),
                nextAction = "Remove targetModule or provide the resolved module name.",
            )
        }
        val packageSegments = owner.realRoot.relativize(candidateReal).map(Path::toString).filter(String::isNotEmpty)
        if (packageSegments.any { !isValidJavaIdentifier(it) }) {
            return refusal(
                "targetDirectory.nonConformingPackage",
                "targetDirectory cannot be mapped to a valid Java package",
                evidence = listOf("sourceRoot=${owner.relativeRoot}"),
                nextAction = "Select a directory whose source-root-relative segments are valid Java identifiers.",
            )
        }
        val packageName = packageSegments.joinToString(".")
        if (requestedPackage != null && requestedPackage != packageName) {
            return refusal(
                "targetDirectory.packageMismatch",
                "targetPackage is inconsistent with the package derived from targetDirectory",
                evidence = listOf("resolvedPackage=$packageName", "requestedPackage=$requestedPackage"),
                nextAction = "Remove targetPackage or set it to the resolved package.",
            )
        }
        val sourceSet = sourceSet(owner.relativeRoot)
        if (sourceSet == JavaSourceSet.GENERATED) {
            return refusal(
                "targetDirectory.generatedSource",
                "Generated Java source roots are analysis-only import targets",
                evidence = listOf("sourceRoot=${owner.relativeRoot}", "sourceSet=GENERATED"),
                nextAction = "Select a main or test source directory maintained by users.",
            )
        }
        return JavaImportTargetResolution.Resolved(
            JavaImportTarget(
                directory = workspace.relativize(candidate),
                moduleName = owner.module.name,
                sourceRoot = owner.relativeRoot,
                sourceSet = sourceSet,
                packageName = packageName,
            ),
        )
    }

    private fun containsSymbolicLink(workspace: Path, target: Path): Boolean {
        var current = workspace
        for (segment in workspace.relativize(target)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun sourceSet(sourceRoot: Path): JavaSourceSet {
        val normalized = sourceRoot.toString().replace('\\', '/').lowercase()
        return when {
            "/generated" in "/$normalized" || "generated-sources" in normalized -> JavaSourceSet.GENERATED
            normalized.endsWith("/src/main/java") || normalized == "src/main/java" -> JavaSourceSet.MAIN
            normalized.endsWith("/src/test/java") || normalized == "src/test/java" -> JavaSourceSet.TEST
            else -> JavaSourceSet.CUSTOM
        }
    }

    private fun refusal(
        code: String,
        message: String,
        evidence: List<String> = emptyList(),
        nextAction: String = "Provide an existing, safe workspace-relative Java source directory.",
    ) = JavaImportTargetResolution.Refused(JavaImportTargetRefusal(code, message, evidence, nextAction))

    private data class Owner(val module: Module, val relativeRoot: Path, val realRoot: Path)

    companion object {
        private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")

        private fun isValidJavaIdentifier(value: String): Boolean =
            SourceVersion.isIdentifier(value) && !SourceVersion.isKeyword(value)
    }
}
