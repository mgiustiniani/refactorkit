package org.refactorkit.webimporter

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.java.JavaImportTarget
import org.refactorkit.java.JavaPackageUtil
import org.refactorkit.java.JavaSourceSet
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant

enum class SourceKind { CLIPBOARD, URL, FILE, LLM, SNIPPET }
enum class LicensePolicy { WARN, BLOCK_UNKNOWN, ALLOW }

data class ImportRequest(
    val code: String,
    val targetPackage: String,
    val targetModule: String? = null,
    val sourceUrl: String? = null,
    val sourceKind: SourceKind = SourceKind.SNIPPET,
    val allowRename: Boolean = true,
    val licensePolicy: LicensePolicy = LicensePolicy.WARN,
    val snapshot: ProjectSnapshot? = null,
    val resolvedTarget: JavaImportTarget? = null,
)

data class ExternalImportPreview(
    val plan: PatchPlan,
    val primaryFile: Path?,
    val resolvedModule: String?,
    val resolvedSourceRoot: Path?,
    val sourceSet: JavaSourceSet?,
    val resolvedPackage: String,
    val packageChanges: List<PackageChange>,
    val provenance: ProvenanceRecord?,
    val unresolvedDependencies: List<String>,
    val conflicts: List<String>,
    val refusalReasons: List<String>,
    val applyEligible: Boolean,
)

data class PackageChange(val from: String, val to: String)

data class ProvenanceRecord(
    val sourceUrl: String?,
    val sourceKind: SourceKind,
    val retrievedAt: String,
    val licenseDetected: String,
    val licenseRisk: LicenseRisk,
    val originalHash: String,
)

/**
 * External Java Class Importer.
 *
 * Implements the full External Code Assimilation pipeline:
 * 1.  Acquire source
 * 2.  Strip markdown fences
 * 3.  Detect license / provenance
 * 4.  Parse top-level type declarations
 * 5.  Split public types into separate files
 * 6.  Rewrite package declaration
 * 7.  Detect naming conflicts in the target workspace
 * 8.  Generate a PatchPlan (FileEdit.Create per file)
 * 9.  Run basic diagnostics
 * 10. Return preview — apply separately via PatchEngine
 */
class ExternalJavaClassImporter {

    fun previewDetailed(request: ImportRequest): ExternalImportPreview {
        val rawPlan = preview(request)
        val plan = if (rawPlan.snapshotHash.isEmpty() && request.snapshot != null) {
            rawPlan.copy(snapshotHash = request.snapshot.hash)
        } else rawPlan
        val cleaned = stripMarkdownFences(request.code)
        val existingPackage = extractPackage(cleaned)
        val creates = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>()
        val unresolved = unresolvedImportCandidates(creates.map(FileEdit.Create::content), request.targetPackage)
        val provenance = plan.warnings.firstOrNull { it.startsWith("Provenance:") }?.let { warning ->
            val retrievedAt = Regex("retrievedAt=([^ ]+)").find(warning)?.groupValues?.get(1) ?: Instant.EPOCH.toString()
            val license = LicenseDetector.detect(cleaned)
            ProvenanceRecord(request.sourceUrl, request.sourceKind, retrievedAt, license.detected, license.risk, sha256(cleaned))
        }
        val target = request.resolvedTarget
        val sourceRoot = target?.sourceRoot ?: selectSourceRoot(request.snapshot, request.targetModule)
        val moduleName = target?.moduleName ?: selectModuleName(request.snapshot, request.targetModule, sourceRoot)
        val conflicts = if (plan.status == PatchStatus.REFUSED && plan.summary.startsWith("Naming conflict:")) {
            Regex("(?m)^  (.+\\.java)$").findAll(plan.summary).map { it.groupValues[1] }.toList()
        } else emptyList()
        return ExternalImportPreview(
            plan = plan,
            primaryFile = creates.firstOrNull()?.path,
            resolvedModule = moduleName,
            resolvedSourceRoot = sourceRoot,
            sourceSet = target?.sourceSet,
            resolvedPackage = request.targetPackage,
            packageChanges = if (existingPackage == request.targetPackage) emptyList() else listOf(PackageChange(existingPackage, request.targetPackage)),
            provenance = provenance,
            unresolvedDependencies = unresolved,
            conflicts = conflicts,
            refusalReasons = if (plan.status == PatchStatus.REFUSED) listOf(plan.summary) else emptyList(),
            applyEligible = plan.status == PatchStatus.PREVIEW,
        )
    }

    fun preview(request: ImportRequest): PatchPlan {
        // Step 1: strip markdown fences
        val code = stripMarkdownFences(request.code)

        // Step 2: detect license / provenance
        val license = LicenseDetector.detect(code)
        val provenance = ProvenanceRecord(
            sourceUrl = request.sourceUrl,
            sourceKind = request.sourceKind,
            retrievedAt = Instant.now().toString(),
            licenseDetected = license.detected,
            licenseRisk = license.risk,
            originalHash = sha256(code),
        )

        // Step 3: block if policy demands it
        if (request.licensePolicy == LicensePolicy.BLOCK_UNKNOWN && license.risk == LicenseRisk.UNKNOWN) {
            return refused(
                "Import blocked: license is unknown and licensePolicy is BLOCK_UNKNOWN.",
                provenance,
            )
        }

        // Step 4: validate target package and detect top-level types
        if (!isValidPackageName(request.targetPackage)) {
            return refused("Invalid target package: ${request.targetPackage}", provenance)
        }

        val types = JavaClassSplitter.split(code)
        if (types.isEmpty()) {
            return refused("No top-level Java type detected in the provided code.", provenance)
        }

        val publicTypes = types.filter { it.isPublic }
        if (publicTypes.isEmpty()) {
            return refused("No public top-level type detected. At least one public type is required.", provenance)
        }

        // Step 5: detect existing package
        val existingPackage = extractPackage(code)

        // Step 6: build per-file contents
        val targetPkg = request.targetPackage
        val sourceRoot = request.resolvedTarget?.sourceRoot ?: selectSourceRoot(request.snapshot, request.targetModule)
        val filePlans = mutableListOf<Triple<String, Path, String>>() // (typeName, relativePath, content)

        val useSingleFile = publicTypes.size == 1 && types.size > 1
        if (useSingleFile) {
            // One public type: whole file goes in one file, rewrite package
            val mainType = publicTypes.first()
            val content = normalizeImportedContent(rewritePackage(code, existingPackage, targetPkg), targetPkg)
            val relPath = toRelativePath(request.resolvedTarget, sourceRoot, targetPkg, mainType.name)
            filePlans += Triple(mainType.name, relPath, content)
        } else {
            // Multiple public types or only one type: split
            for (t in publicTypes) {
                val fileContent = normalizeImportedContent(buildSingleFileContent(code, t, existingPackage, targetPkg), targetPkg)
                val relPath = toRelativePath(request.resolvedTarget, sourceRoot, targetPkg, t.name)
                filePlans += Triple(t.name, relPath, fileContent)
            }
        }

        // Step 7: detect naming conflicts against workspace
        val warnings = mutableListOf<String>()
        val snapshot = request.snapshot
        val conflictPaths = mutableListOf<String>()

        if (snapshot != null) {
            for ((_, relPath, _) in filePlans) {
                val existsInSnapshot = snapshot.files.any { it.path == relPath }
                val existsOnDisk = java.nio.file.Files.exists(snapshot.workspace.root.resolve(relPath))
                if (existsInSnapshot || existsOnDisk) {
                    conflictPaths += relPath.toString()
                }
            }
        }

        if (conflictPaths.isNotEmpty()) {
            return refused(
                "Naming conflict: the following files already exist:\n" +
                    conflictPaths.joinToString("\n") { "  $it" } +
                    "\nChoose a different target package or rename the class.",
                provenance,
            )
        }

        // Step 8: license warnings
        when (license.risk) {
            LicenseRisk.UNKNOWN ->
                warnings += "No license detected in the provided code. Review before committing. " +
                    "License policy: ${request.licensePolicy}."
            LicenseRisk.HIGH ->
                warnings += "Detected license '${license.detected}' has HIGH risk (copyleft). " +
                    "Review your project's license compatibility before importing."
            LicenseRisk.MEDIUM ->
                warnings += "Detected license '${license.detected}' has MEDIUM risk. " +
                    "Review terms before use."
            LicenseRisk.LOW -> {} // no warning needed
        }

        warnings += provenanceWarning(provenance)
        warnings += "Target source root: $sourceRoot."
        val riskyImports = unresolvedImportCandidates(filePlans.map { it.third }, targetPkg)
        if (riskyImports.isNotEmpty()) {
            warnings += "Potential unresolved external imports: ${riskyImports.joinToString(", ")}. " +
                "Review dependencies before applying."
        } else {
            warnings += "Unresolved imports are NOT fully type-resolved in this MVP. Review import statements manually."
        }

        // Step 9: build patch plan
        val edits = filePlans.map { (_, relPath, content) ->
            FileEdit.Create(path = relPath, content = content, overwrite = false)
        }

        val affectedFiles = filePlans.map { it.second }.toSet()

        return PatchPlan(
            operation = "importExternalJavaClass",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot?.hash ?: "",
            confidence = if (license.risk == LicenseRisk.UNKNOWN) 0.6 else 0.9,
            requiresUserApproval = true,
            summary = "Import ${publicTypes.map { it.name }.joinToString(", ")} into package $targetPkg " +
                "(${filePlans.size} file(s)).",
            affectedFiles = affectedFiles,
            workspaceEdit = WorkspaceEdit(edits),
            warnings = warnings,
            riskLevel = when (license.risk) {
                LicenseRisk.HIGH -> RiskLevel.HIGH
                LicenseRisk.MEDIUM -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun stripMarkdownFences(input: String): String {
        val lines = input.trimIndent().lines()
        val start = lines.indexOfFirst { it.startsWith("```") }
        val end = lines.indexOfLast { it.startsWith("```") }
        return if (start >= 0 && end > start) {
            lines.subList(start + 1, end).joinToString("\n").trim()
        } else {
            input.trim()
        }
    }

    private fun extractPackage(code: String): String {
        val match = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(code)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun rewritePackage(code: String, oldPkg: String, newPkg: String): String {
        if (oldPkg.isEmpty()) return if (newPkg.isEmpty()) code else "package $newPkg;\n\n$code"
        return if (newPkg.isEmpty()) {
            Regex("""(?m)^\s*package\s+[\w.]+\s*;\s*""").replace(code, "")
        } else {
            Regex("""(?m)^\s*package\s+[\w.]+\s*;""").replace(code, "package $newPkg;")
        }
    }

    private fun buildSingleFileContent(
        fullCode: String,
        type: JavaClassSplitter.ExtractedType,
        existingPackage: String,
        targetPackage: String,
    ): String {
        // Extract imports from the original file
        val imports = IMPORT_REGEX.findAll(fullCode)
            .joinToString("\n") { it.value.trim() }
        val typeContent = type.content
        return buildString {
            if (targetPackage.isNotEmpty()) appendLine("package $targetPackage;")
            if (imports.isNotBlank()) {
                appendLine()
                appendLine(imports)
            }
            appendLine()
            append(typeContent.trimStart())
        }
    }

    private fun normalizeImportedContent(content: String, targetPackage: String): String {
        val lines = content.lines()
        val importLines = lines.filter { it.trimStart().startsWith("import ") }
        if (importLines.isEmpty()) return ensureTrailingNewline(content.trim())

        val nonImportLines = lines.filterNot { it.trimStart().startsWith("import ") }
        val sortedImports = organizeImports(importLines, targetPackage)
        val packageIndex = nonImportLines.indexOfFirst { it.trimStart().startsWith("package ") }
        if (packageIndex < 0) return ensureTrailingNewline(content.trim())

        val withoutExtraBlankAfterPackage = nonImportLines.toMutableList()
        while (packageIndex + 1 < withoutExtraBlankAfterPackage.size && withoutExtraBlankAfterPackage[packageIndex + 1].isBlank()) {
            withoutExtraBlankAfterPackage.removeAt(packageIndex + 1)
        }

        val result = mutableListOf<String>()
        result += withoutExtraBlankAfterPackage.take(packageIndex + 1)
        if (sortedImports.isNotEmpty()) {
            result += ""
            result += sortedImports
        }
        result += ""
        result += withoutExtraBlankAfterPackage.drop(packageIndex + 1).dropWhile { it.isBlank() }
        return ensureTrailingNewline(result.joinToString("\n").trimEnd())
    }

    private fun organizeImports(importLines: List<String>, targetPackage: String): List<String> {
        val seen = linkedSetOf<String>()
        return importLines
            .map { it.trim() }
            .filter { it.matches(IMPORT_REGEX) }
            .filter { importLine ->
                val imported = importLine.removePrefix("import ").removePrefix("static ").removeSuffix(";").trim()
                val isStatic = importLine.startsWith("import static ")
                val pkg = imported.substringBeforeLast('.', "")
                val samePackage = !isStatic && pkg == targetPackage && targetPackage.isNotEmpty()
                !samePackage && seen.add(importLine)
            }
            .sortedWith(compareBy({ if (it.startsWith("import static ")) 1 else 0 }, { importGroup(it) }, { it }))
    }

    private fun unresolvedImportCandidates(contents: List<String>, targetPackage: String): List<String> = contents
        .flatMap { IMPORT_REGEX.findAll(it).map { match -> match.value.trim() } }
        .map { it.removePrefix("import ").removePrefix("static ").removeSuffix(";").trim() }
        .filterNot { imported ->
            imported.startsWith("java.") ||
                imported.startsWith("javax.") ||
                imported.startsWith("jakarta.") ||
                (targetPackage.isNotEmpty() && imported.startsWith("$targetPackage."))
        }
        .distinct()
        .sorted()

    private fun importGroup(importLine: String): Int {
        val imported = importLine.removePrefix("import ").removePrefix("static ")
        return when {
            imported.startsWith("java.") -> 0
            imported.startsWith("javax.") -> 1
            imported.startsWith("jakarta.") -> 2
            imported.startsWith("org.") -> 3
            imported.startsWith("com.") -> 4
            else -> 5
        }
    }

    private fun selectModuleName(snapshot: ProjectSnapshot?, targetModule: String?, sourceRoot: Path): String? {
        if (snapshot == null) return null
        return targetModule?.let { requested ->
            snapshot.modules.find { it.name == requested || it.root.fileName?.toString() == requested }?.name
        } ?: snapshot.modules.singleOrNull { sourceRoot in it.sourceRoots }?.name
    }

    private fun selectSourceRoot(snapshot: ProjectSnapshot?, targetModule: String?): Path {
        if (snapshot == null) return DEFAULT_SOURCE_ROOT
        val module = targetModule?.let { requested ->
            snapshot.modules.find { it.name == requested || it.root.fileName?.toString() == requested }
        }
        val roots = (module?.sourceRoots ?: snapshot.modules.flatMap { it.sourceRoots })
        return roots.firstOrNull { it.toString().replace('\\', '/').endsWith("src/main/java") }
            ?: roots.firstOrNull()
            ?: DEFAULT_SOURCE_ROOT
    }

    private fun toRelativePath(target: JavaImportTarget?, sourceRoot: Path, pkg: String, simpleName: String): Path =
        (target?.directory ?: sourceRoot.resolve(JavaPackageUtil.packageToPath(pkg))).resolve("$simpleName.java")

    private fun isValidPackageName(pkg: String): Boolean =
        pkg.isEmpty() || pkg.split('.').all(::isValidJavaIdentifier)

    private fun isValidJavaIdentifier(name: String): Boolean =
        name.isNotEmpty() && (name[0].isLetter() || name[0] == '_' || name[0] == '$') &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '$' }

    private fun ensureTrailingNewline(text: String): String = if (text.endsWith("\n")) text else "$text\n"

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun provenanceWarning(provenance: ProvenanceRecord): String =
        "Provenance: sourceKind=${provenance.sourceKind} " +
            "sourceUrl=${provenance.sourceUrl ?: "(none)"} " +
            "retrievedAt=${provenance.retrievedAt} " +
            "licenseDetected=${provenance.licenseDetected} " +
            "licenseRisk=${provenance.licenseRisk} " +
            "originalHash=${provenance.originalHash}"

    private fun refused(reason: String, provenance: ProvenanceRecord) = PatchPlan(
        operation = "importExternalJavaClass",
        status = PatchStatus.REFUSED,
        snapshotHash = "",
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(
            reason,
            provenanceWarning(provenance),
            "No files were written. Review provenance, license policy, and naming conflicts before trying a new import; RefactorKit never overwrites existing files by default.",
        ),
        riskLevel = RiskLevel.HIGH,
    )

    companion object {
        private val DEFAULT_SOURCE_ROOT: Path = Paths.get("src/main/java")
        private val IMPORT_REGEX = Regex("""(?m)^\s*import\s+(?:static\s+)?[\w.]+(?:\.\*)?\s*;""")
    }
}
