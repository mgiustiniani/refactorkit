package org.refactorkit.java

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.Symbol
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Conservative Java Change Signature planner.
 *
 * Supported operations:
 * - rename one method parameter inside a non-overloaded method declaration and body;
 * - add one method parameter and update in-scope call sites with a caller-provided default expression.
 *
 * The planner refuses overloaded methods to avoid guessing which declaration/call
 * sites should be changed. It remains lexical and preview-only until the patch is
 * explicitly applied through PatchEngine.
 */
class JavaChangeSignaturePlanner(private val adapter: JavaLanguageAdapter) {

    fun previewRenameParameter(
        snapshot: ProjectSnapshot,
        symbolFqnWithMethod: String,
        oldParameterName: String,
        newParameterName: String,
    ): PatchPlan {
        val resolved = resolveSingleMethod(snapshot, symbolFqnWithMethod, "changeSignature.renameParameter")
            ?: return lastRefusal ?: refused(snapshot, "changeSignature.renameParameter", "Unable to resolve method: $symbolFqnWithMethod")
        val (ownerFqn, methodName, _, file, method) = resolved

        if (!isValidJavaIdentifier(oldParameterName)) return refused(snapshot, "changeSignature.renameParameter", "Invalid old parameter name: $oldParameterName")
        if (!isValidJavaIdentifier(newParameterName)) return refused(snapshot, "changeSignature.renameParameter", "Invalid new parameter name: $newParameterName")
        if (oldParameterName == newParameterName) return refused(snapshot, "changeSignature.renameParameter", "Old and new parameter names are the same: $oldParameterName")
        if (methodName == "<init>") return refused(snapshot, "changeSignature.renameParameter", "Constructor parameter rename is not supported in this MVP")

        val paramRange = findParameterNameRange(file.content, method.paramsStart + 1, method.paramsEnd, oldParameterName)
            ?: return refused(snapshot, "changeSignature.renameParameter", "Parameter '$oldParameterName' not found in $ownerFqn#$methodName")

        val edits = mutableListOf<TextEdit>()
        edits += TextEdit(
            range = TextEdits.rangeForOffset(file.content, paramRange.first, oldParameterName.length),
            newText = newParameterName,
        )

        val bodyContent = file.content.substring(method.bodyStart + 1, method.bodyEnd)
        val bodyBaseOffset = method.bodyStart + 1
        for (occurrence in JavaLexer.findOccurrences(bodyContent, oldParameterName)) {
            val absolute = bodyBaseOffset + occurrence.first
            edits += TextEdit(
                range = TextEdits.rangeForOffset(file.content, absolute, oldParameterName.length),
                newText = newParameterName,
            )
        }

        val sorted = dedupeAndSort(edits)
        val fileEdits = listOf(FileEdit.Modify(file.path, sorted))

        return PatchPlan(
            operation = "changeSignature.renameParameter",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.86,
            requiresUserApproval = true,
            summary = "Rename parameter '$oldParameterName' → '$newParameterName' in $ownerFqn#$methodName. 1 file affected.",
            affectedFiles = setOf(file.path),
            workspaceEdit = WorkspaceEdit(fileEdits),
            diagnosticsAfterPreview = diagnosticsAfter(snapshot, fileEdits),
            warnings = listOf(
                "Change Signature: parameter rename updates the declaration and method body only; Java call sites do not contain parameter names.",
                "Overloaded methods and constructor parameters are not supported yet.",
                "Reflection, serialization frameworks, dependency injection, and annotation processors may reference parameter names.",
            ),
            riskLevel = RiskLevel.MEDIUM,
        )
    }

    fun previewAddParameter(
        snapshot: ProjectSnapshot,
        symbolFqnWithMethod: String,
        parameterType: String,
        parameterName: String,
        defaultExpression: String,
    ): PatchPlan {
        val resolved = resolveSingleMethod(snapshot, symbolFqnWithMethod, "changeSignature.addParameter")
            ?: return lastRefusal ?: refused(snapshot, "changeSignature.addParameter", "Unable to resolve method: $symbolFqnWithMethod")
        val (ownerFqn, methodName, owner, declarationFile, method) = resolved

        if (methodName == "<init>") return refused(snapshot, "changeSignature.addParameter", "Constructor add-parameter is not supported yet")
        blockingSignatureDeclarationRisk(declarationFile, method, owner, "add-parameter")?.let { reason ->
            return refused(snapshot, "changeSignature.addParameter", reason)
        }
        if (!isSupportedParameterType(parameterType)) return refused(snapshot, "changeSignature.addParameter", "Unsupported or unsafe parameter type: $parameterType")
        if (!isValidJavaIdentifier(parameterName)) return refused(snapshot, "changeSignature.addParameter", "Invalid parameter name: $parameterName")
        if (findParameterNameRange(declarationFile.content, method.paramsStart + 1, method.paramsEnd, parameterName) != null) {
            return refused(snapshot, "changeSignature.addParameter", "Parameter '$parameterName' already exists in $ownerFqn#$methodName")
        }
        val currentParams = parseParameters(declarationFile.content, method.paramsStart + 1, method.paramsEnd)
        if (currentParams.any { it.text.contains("...") }) {
            return refused(snapshot, "changeSignature.addParameter", "Cannot add a parameter after an existing varargs parameter in $ownerFqn#$methodName")
        }
        if (!isSafeDefaultExpression(defaultExpression)) {
            return refused(snapshot, "changeSignature.addParameter", "Default expression must be a single Java expression without semicolons, newlines, or top-level commas")
        }

        val scopedFiles = scopedFiles(snapshot, ownerFqn, methodName, declarationFile.path)
        val methodReference = firstMethodReference(scopedFiles, methodName)
        if (methodReference != null) {
            return refused(snapshot, "changeSignature.addParameter", "Method reference '$methodName' found at $methodReference. Add-parameter cannot safely update method references; rewrite or remove the method reference first.")
        }
        blockingChangeSignatureRisk(declarationFile, scopedFiles, methodName)?.let { reason ->
            return refused(snapshot, "changeSignature.addParameter", reason)
        }
        val risk = assessChangeSignatureRisk(declarationFile, method, ownerFqn, "add-parameter")

        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        val existingParams = declarationFile.content.substring(method.paramsStart + 1, method.paramsEnd).trim()
        val declarationInsertion = if (existingParams.isEmpty()) {
            "${parameterType.trim()} $parameterName"
        } else {
            ", ${parameterType.trim()} $parameterName"
        }
        editsByPath.getOrPut(declarationFile.path) { mutableListOf() } += TextEdit(
            range = TextEdits.rangeForOffset(declarationFile.content, method.paramsEnd, 0),
            newText = declarationInsertion,
        )

        val defaultArg = defaultExpression.trim()
        for (sourceFile in scopedFiles) {
            val declarationOffsets = findMethodDeclarations(sourceFile.content, methodName).map { it.nameStart }.toSet()
            val invocations = findMethodInvocations(sourceFile.content, methodName)
                .filterNot { it.nameStart in declarationOffsets }
                .filter { isLikelyTargetInvocation(sourceFile, it, declarationFile.path, ownerFqn, methodName) }

            for (invocation in invocations) {
                val existingArgs = sourceFile.content.substring(invocation.argsStart + 1, invocation.argsEnd).trim()
                val insertion = if (existingArgs.isEmpty()) defaultArg else ", $defaultArg"
                editsByPath.getOrPut(sourceFile.path) { mutableListOf() } += TextEdit(
                    range = TextEdits.rangeForOffset(sourceFile.content, invocation.argsEnd, 0),
                    newText = insertion,
                )
            }
        }

        val fileEdits = editsByPath.mapNotNull { (path, edits) ->
            val sorted = dedupeAndSort(edits)
            if (sorted.isEmpty()) null else FileEdit.Modify(path, sorted)
        }

        val affected = fileEdits.map { it.path }.toSet()
        val callSiteCount = fileEdits.sumOf { it.textEdits.size } - 1

        return PatchPlan(
            operation = "changeSignature.addParameter",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.82,
            requiresUserApproval = true,
            summary = "Add parameter '${parameterType.trim()} $parameterName' to $ownerFqn#$methodName and update $callSiteCount in-scope call site(s). ${affected.size} file(s) affected.",
            affectedFiles = affected,
            workspaceEdit = WorkspaceEdit(fileEdits),
            diagnosticsAfterPreview = diagnosticsAfter(snapshot, fileEdits),
            warnings = risk.warnings + listOf(
                "Change Signature add-parameter is lexical and requires manual review of the preview.",
                "Method references, generated declaration files, and string-literal method-name references are detected and refused.",
                "Call-site updates are limited to likely target invocations in files that appear to reference $ownerFqn.",
            ),
            riskLevel = risk.riskLevel,
        )
    }

    fun previewReorderParameters(
        snapshot: ProjectSnapshot,
        symbolFqnWithMethod: String,
        newOrder: List<String>,
    ): PatchPlan {
        val resolved = resolveSingleMethod(snapshot, symbolFqnWithMethod, "changeSignature.reorderParameters")
            ?: return lastRefusal ?: refused(snapshot, "changeSignature.reorderParameters", "Unable to resolve method: $symbolFqnWithMethod")
        val (ownerFqn, methodName, owner, declarationFile, method) = resolved

        if (methodName == "<init>") return refused(snapshot, "changeSignature.reorderParameters", "Constructor parameter reorder is not supported yet")
        blockingSignatureDeclarationRisk(declarationFile, method, owner, "reorder-parameters")?.let { reason ->
            return refused(snapshot, "changeSignature.reorderParameters", reason)
        }
        val params = parseParameters(declarationFile.content, method.paramsStart + 1, method.paramsEnd)
        if (params.size < 2) return refused(snapshot, "changeSignature.reorderParameters", "Method '$methodName' must have at least two parameters to reorder")

        val requested = newOrder.map { it.trim() }.filter { it.isNotEmpty() }
        if (requested.size != params.size) {
            return refused(snapshot, "changeSignature.reorderParameters", "New order must list exactly ${params.size} parameter name(s): ${params.joinToString(", ") { it.name }}")
        }
        if (requested.toSet().size != requested.size) return refused(snapshot, "changeSignature.reorderParameters", "New order contains duplicate parameter names: ${requested.joinToString(", ")}")

        val existingNames = params.map { it.name }
        if (requested.toSet() != existingNames.toSet()) {
            return refused(snapshot, "changeSignature.reorderParameters", "New order must contain the existing parameter names only: ${existingNames.joinToString(", ")}")
        }
        if (requested == existingNames) return refused(snapshot, "changeSignature.reorderParameters", "New parameter order is identical to the current order")

        val scopedFiles = scopedFiles(snapshot, ownerFqn, methodName, declarationFile.path)
        val methodReference = firstMethodReference(scopedFiles, methodName)
        if (methodReference != null) {
            return refused(snapshot, "changeSignature.reorderParameters", "Method reference '$methodName' found at $methodReference. Reorder-parameters cannot safely update method references; rewrite or remove the method reference first.")
        }
        blockingChangeSignatureRisk(declarationFile, scopedFiles, methodName)?.let { reason ->
            return refused(snapshot, "changeSignature.reorderParameters", reason)
        }
        val risk = assessChangeSignatureRisk(declarationFile, method, ownerFqn, "reorder-parameters")

        val oldIndexByName = existingNames.withIndex().associate { it.value to it.index }
        val reorderedIndexes = requested.map { oldIndexByName.getValue(it) }
        val varargsIndex = params.indexOfFirst { it.text.contains("...") }
        if (varargsIndex >= 0 && reorderedIndexes.last() != varargsIndex) {
            return refused(snapshot, "changeSignature.reorderParameters", "Varargs parameter '${params[varargsIndex].name}' must remain last")
        }
        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()

        val reorderedDeclaration = reorderedIndexes.joinToString(", ") { params[it].text.trim() }
        editsByPath.getOrPut(declarationFile.path) { mutableListOf() } += TextEdit(
            range = TextEdits.rangeForOffset(declarationFile.content, method.paramsStart + 1, method.paramsEnd - method.paramsStart - 1),
            newText = reorderedDeclaration,
        )

        var callSiteCount = 0
        for (sourceFile in scopedFiles) {
            val declarationOffsets = findMethodDeclarations(sourceFile.content, methodName).map { it.nameStart }.toSet()
            val invocations = findMethodInvocations(sourceFile.content, methodName)
                .filterNot { it.nameStart in declarationOffsets }
                .filter { isLikelyTargetInvocation(sourceFile, it, declarationFile.path, ownerFqn, methodName) }

            for (invocation in invocations) {
                val argsText = sourceFile.content.substring(invocation.argsStart + 1, invocation.argsEnd)
                val args = splitTopLevelCommas(argsText).map { it.trim() }
                if (args.size != params.size || args.any { it.isEmpty() }) {
                    val location = "${sourceFile.path}:${TextEdits.positionForOffset(sourceFile.content, invocation.nameStart).line + 1}"
                    return refused(snapshot, "changeSignature.reorderParameters", "Cannot safely reorder call site at $location: expected ${params.size} argument(s), found ${args.count { it.isNotEmpty() }}")
                }
                val reorderedArgs = reorderedIndexes.joinToString(", ") { args[it] }
                editsByPath.getOrPut(sourceFile.path) { mutableListOf() } += TextEdit(
                    range = TextEdits.rangeForOffset(sourceFile.content, invocation.argsStart + 1, invocation.argsEnd - invocation.argsStart - 1),
                    newText = reorderedArgs,
                )
                callSiteCount++
            }
        }

        val fileEdits = editsByPath.mapNotNull { (path, edits) ->
            val sorted = dedupeAndSort(edits)
            if (sorted.isEmpty()) null else FileEdit.Modify(path, sorted)
        }
        val affected = fileEdits.map { it.path }.toSet()

        return PatchPlan(
            operation = "changeSignature.reorderParameters",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.80,
            requiresUserApproval = true,
            summary = "Reorder parameters of $ownerFqn#$methodName to (${requested.joinToString(", ")}) and update $callSiteCount in-scope call site(s). ${affected.size} file(s) affected.",
            affectedFiles = affected,
            workspaceEdit = WorkspaceEdit(fileEdits),
            diagnosticsAfterPreview = diagnosticsAfter(snapshot, fileEdits),
            warnings = risk.warnings + listOf(
                "Change Signature reorder-parameters is lexical and requires manual review of the preview.",
                "Method references, generated declaration files, and string-literal method-name references are detected and refused.",
                "Call-site updates are limited to likely target invocations in files that appear to reference $ownerFqn.",
            ),
            riskLevel = risk.riskLevel,
        )
    }

    fun previewRemoveParameter(
        snapshot: ProjectSnapshot,
        symbolFqnWithMethod: String,
        parameterName: String,
    ): PatchPlan {
        val resolved = resolveSingleMethod(snapshot, symbolFqnWithMethod, "changeSignature.removeParameter")
            ?: return lastRefusal ?: refused(snapshot, "changeSignature.removeParameter", "Unable to resolve method: $symbolFqnWithMethod")
        val (ownerFqn, methodName, owner, declarationFile, method) = resolved

        if (methodName == "<init>") return refused(snapshot, "changeSignature.removeParameter", "Constructor parameter removal is not supported yet")
        blockingSignatureDeclarationRisk(declarationFile, method, owner, "remove-parameter")?.let { reason ->
            return refused(snapshot, "changeSignature.removeParameter", reason)
        }
        if (!isValidJavaIdentifier(parameterName)) return refused(snapshot, "changeSignature.removeParameter", "Invalid parameter name: $parameterName")

        val params = parseParameters(declarationFile.content, method.paramsStart + 1, method.paramsEnd)
        val removeIndex = params.indexOfFirst { it.name == parameterName }
        if (removeIndex < 0) return refused(snapshot, "changeSignature.removeParameter", "Parameter '$parameterName' not found in $ownerFqn#$methodName")

        val bodyContent = declarationFile.content.substring(method.bodyStart + 1, method.bodyEnd)
        if (JavaLexer.findOccurrences(bodyContent, parameterName).isNotEmpty()) {
            return refused(snapshot, "changeSignature.removeParameter", "Parameter '$parameterName' is used in the method body of $ownerFqn#$methodName; remove-parameter cannot safely rewrite the implementation")
        }

        val scopedFiles = scopedFiles(snapshot, ownerFqn, methodName, declarationFile.path)
        val methodReference = firstMethodReference(scopedFiles, methodName)
        if (methodReference != null) {
            return refused(snapshot, "changeSignature.removeParameter", "Method reference '$methodName' found at $methodReference. Remove-parameter cannot safely update method references; rewrite or remove the method reference first.")
        }
        blockingChangeSignatureRisk(declarationFile, scopedFiles, methodName)?.let { reason ->
            return refused(snapshot, "changeSignature.removeParameter", reason)
        }
        val risk = assessChangeSignatureRisk(declarationFile, method, ownerFqn, "remove-parameter")

        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        val newDeclarationParams = params.filterIndexed { index, _ -> index != removeIndex }
            .joinToString(", ") { it.text.trim() }
        editsByPath.getOrPut(declarationFile.path) { mutableListOf() } += TextEdit(
            range = TextEdits.rangeForOffset(declarationFile.content, method.paramsStart + 1, method.paramsEnd - method.paramsStart - 1),
            newText = newDeclarationParams,
        )

        var callSiteCount = 0
        for (sourceFile in scopedFiles) {
            val declarationOffsets = findMethodDeclarations(sourceFile.content, methodName).map { it.nameStart }.toSet()
            val invocations = findMethodInvocations(sourceFile.content, methodName)
                .filterNot { it.nameStart in declarationOffsets }
                .filter { isLikelyTargetInvocation(sourceFile, it, declarationFile.path, ownerFqn, methodName) }

            for (invocation in invocations) {
                val argsText = sourceFile.content.substring(invocation.argsStart + 1, invocation.argsEnd)
                val args = splitTopLevelCommas(argsText).map { it.trim() }
                if (args.size != params.size || args.any { it.isEmpty() }) {
                    val location = "${sourceFile.path}:${TextEdits.positionForOffset(sourceFile.content, invocation.nameStart).line + 1}"
                    return refused(snapshot, "changeSignature.removeParameter", "Cannot safely remove argument at $location: expected ${params.size} argument(s), found ${args.count { it.isNotEmpty() }}")
                }
                val newArgs = args.filterIndexed { index, _ -> index != removeIndex }.joinToString(", ")
                editsByPath.getOrPut(sourceFile.path) { mutableListOf() } += TextEdit(
                    range = TextEdits.rangeForOffset(sourceFile.content, invocation.argsStart + 1, invocation.argsEnd - invocation.argsStart - 1),
                    newText = newArgs,
                )
                callSiteCount++
            }
        }

        val fileEdits = editsByPath.mapNotNull { (path, edits) ->
            val sorted = dedupeAndSort(edits)
            if (sorted.isEmpty()) null else FileEdit.Modify(path, sorted)
        }
        val affected = fileEdits.map { it.path }.toSet()

        return PatchPlan(
            operation = "changeSignature.removeParameter",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.80,
            requiresUserApproval = true,
            summary = "Remove parameter '$parameterName' from $ownerFqn#$methodName and update $callSiteCount in-scope call site(s). ${affected.size} file(s) affected.",
            affectedFiles = affected,
            workspaceEdit = WorkspaceEdit(fileEdits),
            diagnosticsAfterPreview = diagnosticsAfter(snapshot, fileEdits),
            warnings = risk.warnings + listOf(
                "Change Signature remove-parameter is lexical and requires manual review of the preview.",
                "The parameter must be unused in the method body; method references, generated declaration files, and string-literal method-name references are detected and refused.",
                "Call-site updates are limited to likely target invocations in files that appear to reference $ownerFqn.",
            ),
            riskLevel = risk.riskLevel,
        )
    }

    private var lastRefusal: PatchPlan? = null

    private data class ResolvedMethod(
        val ownerFqn: String,
        val methodName: String,
        val owner: Symbol,
        val file: org.refactorkit.core.SourceFile,
        val method: MethodDecl,
    )

    private fun resolveSingleMethod(snapshot: ProjectSnapshot, symbolFqnWithMethod: String, operation: String): ResolvedMethod? {
        lastRefusal = null
        val parsed = parseMemberSymbol(symbolFqnWithMethod)
            ?: return refuseResolve(snapshot, operation, "Symbol must be in the form <FQN>#<method> — got: $symbolFqnWithMethod")
        val (ownerFqn, methodName) = parsed

        val index = adapter.buildSymbols(snapshot)
        val owner = index.symbols.find { it.id.value == ownerFqn && it.kind in TYPE_KINDS }
            ?: return refuseResolve(snapshot, operation, "Owner type not found: $ownerFqn")
        val file = snapshot.files.find { it.path == owner.location.path }
            ?: return refuseResolve(snapshot, operation, "Declaration file not found: ${owner.location.path}")

        val methods = findMethodDeclarations(file.content, methodName)
        if (methods.isEmpty()) return refuseResolve(snapshot, operation, "Method '$methodName' not found in $ownerFqn")
        if (methods.size > 1) return refuseResolve(snapshot, operation, "Method '$methodName' is overloaded or ambiguous in $ownerFqn; overload-aware change signature is not supported yet")

        return ResolvedMethod(ownerFqn, methodName, owner, file, methods.single())
    }

    private fun refuseResolve(snapshot: ProjectSnapshot, operation: String, reason: String): Nothing? {
        lastRefusal = refused(snapshot, operation, reason)
        return null
    }

    private data class MethodDecl(
        val nameStart: Int,
        val paramsStart: Int,
        val paramsEnd: Int,
        val bodyStart: Int,
        val bodyEnd: Int,
    )

    private data class MethodInvocation(
        val nameStart: Int,
        val argsStart: Int,
        val argsEnd: Int,
    )

    private data class ChangeSignatureRisk(
        val riskLevel: RiskLevel,
        val warnings: List<String>,
    )

    private data class ParameterDecl(
        val name: String,
        val text: String,
    )

    private fun parseParameters(content: String, start: Int, end: Int): List<ParameterDecl> =
        splitTopLevelCommas(content.substring(start, end))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { segment ->
                val name = Regex("""([A-Za-z_$][\w$]*)\s*$""").find(segment)?.groupValues?.get(1)
                if (name == null) null else ParameterDecl(name, segment)
            }

    private fun scopedFiles(
        snapshot: ProjectSnapshot,
        ownerFqn: String,
        methodName: String,
        declarationPath: Path,
    ): List<org.refactorkit.core.SourceFile> {
        val ownerPkg = JavaPackageUtil.packageOf(ownerFqn)
        val ownerSimple = JavaPackageUtil.simpleName(ownerFqn)
        val ownerPkgStar = "import ${ownerPkg}.*;"
        val staticImport = "import static $ownerFqn.$methodName;"
        val staticStarImport = "import static $ownerFqn.*;"
        return snapshot.files.filter { sourceFile ->
            sourceFile.languageId == "java" && (
                sourceFile.path == declarationPath ||
                    sourceFile.content.contains("import $ownerFqn;") ||
                    (ownerPkg.isNotEmpty() && sourceFile.content.contains(ownerPkgStar)) ||
                    sourceFile.content.contains(staticImport) ||
                    sourceFile.content.contains(staticStarImport) ||
                    sourceFile.content.contains(ownerFqn) ||
                    sourceFile.content.contains(ownerSimple)
                )
        }
    }

    private fun firstMethodReference(files: List<org.refactorkit.core.SourceFile>, methodName: String): String? {
        for (file in files) {
            val offset = JavaLexer.findOccurrences(file.content, methodName)
                .firstOrNull { isMethodReferenceAt(file.content, it.first) }
                ?: continue
            val line = TextEdits.positionForOffset(file.content, offset.first).line + 1
            return "${file.path}:$line"
        }
        return null
    }

    private fun isMethodReferenceAt(content: String, nameStart: Int): Boolean {
        val colon2 = previousNonWhitespace(content, nameStart - 1) ?: return false
        val colon1 = previousNonWhitespace(content, colon2 - 1) ?: return false
        return content.getOrNull(colon2) == ':' && content.getOrNull(colon1) == ':'
    }

    private fun blockingSignatureDeclarationRisk(
        declarationFile: org.refactorkit.core.SourceFile,
        method: MethodDecl,
        owner: Symbol,
        operation: String,
    ): String? {
        if (owner.kind == Symbol.Kind.INTERFACE) {
            return "Cannot $operation method '${owner.id.value}' in an interface; implementer updates require compiler-backed hierarchy analysis."
        }
        if (hasOverrideAnnotation(declarationFile.content, method)) {
            return "Cannot $operation an @Override method in ${owner.id.value}; override/interface hierarchy updates require compiler-backed analysis."
        }
        val visibility = methodVisibility(declarationFile.content, method)
        if (visibility == "public" || visibility == "protected") {
            return "Cannot $operation $visibility method ${owner.id.value}; external callers outside this workspace require compiler-backed analysis."
        }
        return null
    }

    private fun hasOverrideAnnotation(content: String, method: MethodDecl): Boolean {
        val prefixStart = content.lastIndexOf('\n', method.nameStart).let { lineStart ->
            var line = lineStart
            var count = 0
            while (line > 0 && count < 6) {
                line = content.lastIndexOf('\n', line - 1)
                count++
                if (line < 0) return@let 0
            }
            line + 1
        }
        val prefix = content.substring(prefixStart, method.nameStart)
        return Regex("""@(?:[\w.]+\.)?Override\b""").containsMatchIn(prefix)
    }

    private fun diagnosticsAfter(snapshot: ProjectSnapshot, fileEdits: List<FileEdit>): List<org.refactorkit.core.Diagnostic> {
        val modifiedByPath = fileEdits.filterIsInstance<FileEdit.Modify>().associateBy { it.path }
        if (modifiedByPath.isEmpty()) return emptyList()
        val files = snapshot.files.map { file ->
            val edit = modifiedByPath[file.path]
            if (edit == null) file else file.copy(content = TextEdits.apply(file.content, edit.textEdits))
        }
        val previewSnapshot = snapshot.copy(files = files, hash = ProjectSnapshot.hashFiles(files))
        return adapter.diagnostics(previewSnapshot)
    }

    private fun blockingChangeSignatureRisk(
        declarationFile: org.refactorkit.core.SourceFile,
        scopedFiles: List<org.refactorkit.core.SourceFile>,
        methodName: String,
    ): String? {
        if (isGeneratedJava(declarationFile)) {
            return "Declaration file ${declarationFile.path} appears to be generated code; change-signature refuses generated declarations."
        }
        firstStringLiteralContaining(scopedFiles, methodName)?.let { location ->
            return "String literal containing method name '$methodName' found at $location. This may be reflection/framework configuration; change-signature refuses until reviewed."
        }
        return null
    }

    private fun assessChangeSignatureRisk(
        declarationFile: org.refactorkit.core.SourceFile,
        method: MethodDecl,
        ownerFqn: String,
        operation: String,
    ): ChangeSignatureRisk {
        val warnings = mutableListOf<String>()
        var risk = RiskLevel.MEDIUM

        val visibility = methodVisibility(declarationFile.content, method)
        if (visibility == "public" || visibility == "protected") {
            risk = RiskLevel.HIGH
            warnings += "${visibility.replaceFirstChar { it.uppercaseChar() }} method $ownerFqn may have external callers outside this workspace."
        }

        val frameworkAssessment = JavaFrameworkDetector.assess(declarationFile)
        if (frameworkAssessment.hasFindings) {
            risk = RiskLevel.HIGH
            warnings += frameworkAssessment.warnings("changeSignature.$operation")
        }

        return ChangeSignatureRisk(risk, warnings)
    }

    private fun methodVisibility(content: String, method: MethodDecl): String {
        val headerStart = content.lastIndexOf('\n', method.nameStart).let { if (it < 0) 0 else it + 1 }
        val headerPrefix = content.substring(headerStart, method.nameStart)
        return when {
            Regex("""\bpublic\b""").containsMatchIn(headerPrefix) -> "public"
            Regex("""\bprotected\b""").containsMatchIn(headerPrefix) -> "protected"
            Regex("""\bprivate\b""").containsMatchIn(headerPrefix) -> "private"
            else -> "package-private"
        }
    }

    private fun isLikelyTargetInvocation(
        file: org.refactorkit.core.SourceFile,
        invocation: MethodInvocation,
        declarationPath: Path,
        ownerFqn: String,
        methodName: String,
    ): Boolean {
        val content = file.content
        val before = previousNonWhitespace(content, invocation.nameStart - 1)
        val staticImported = content.contains("import static $ownerFqn.$methodName;") || content.contains("import static $ownerFqn.*;")
        if (before == null || content.getOrNull(before) != '.') {
            return file.path == declarationPath || staticImported
        }

        val qualifier = readQualifiedTokenBefore(content, before - 1) ?: return false
        val ownerSimple = JavaPackageUtil.simpleName(ownerFqn)
        if (qualifier == ownerFqn || qualifier == ownerSimple) return true
        if (file.path == declarationPath && (qualifier == "this" || qualifier == "super")) return true
        return qualifier in likelyOwnerVariables(content, ownerFqn)
    }

    private fun likelyOwnerVariables(content: String, ownerFqn: String): Set<String> {
        val ownerSimple = Regex.escape(JavaPackageUtil.simpleName(ownerFqn))
        val ownerQualified = Regex.escape(ownerFqn)
        val typePattern = "(?:$ownerQualified|$ownerSimple)(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\s*])?"
        val regex = Regex("""(?:^|[^A-Za-z0-9_$])(?:final\s+)?$typePattern\s+([A-Za-z_$][\w$]*)\b""")
        return regex.findAll(content).mapNotNull { match ->
            val name = match.groupValues[1]
            val after = nextNonWhitespace(content, match.range.last + 1)
            if (after != null && content.getOrNull(after) == '(') null else name
        }.toSet()
    }

    private fun readQualifiedTokenBefore(content: String, endInclusive: Int): String? {
        var end = endInclusive
        while (end >= 0 && content[end].isWhitespace()) end--
        var start = end
        while (start >= 0 && (JavaLexer.isIdentChar(content[start]) || content[start] == '.')) start--
        if (start == end) return null
        return content.substring(start + 1, end + 1).takeIf { it.isNotBlank() }
    }

    private fun isGeneratedJava(file: org.refactorkit.core.SourceFile): Boolean {
        val normalized = file.path.toString().replace('\\', '/').lowercase()
        return "/generated/" in "/$normalized/" ||
            normalized.contains("/build/generated/") ||
            normalized.contains("/target/generated-") ||
            Regex("""@(?:[\w.]+\.)?Generated\b""").containsMatchIn(file.content)
    }

    private fun firstStringLiteralContaining(files: List<org.refactorkit.core.SourceFile>, needle: String): String? {
        for (file in files) {
            val offset = firstStringLiteralContaining(file.content, needle) ?: continue
            val line = TextEdits.positionForOffset(file.content, offset).line + 1
            return "${file.path}:$line"
        }
        return null
    }

    private fun firstStringLiteralContaining(content: String, needle: String): Int? {
        var i = 0
        while (i < content.length) {
            when {
                content[i] == '/' && content.getOrNull(i + 1) == '/' -> i = content.indexOf('\n', i).takeIf { it >= 0 } ?: content.length
                content[i] == '/' && content.getOrNull(i + 1) == '*' -> i = content.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: content.length
                content[i] == '\'' -> i = skipChar(content, i) + 1
                content[i] == '"' -> {
                    val start = i
                    val end = skipString(content, i).coerceAtMost(content.length - 1)
                    if (content.substring(start, end + 1).contains(needle)) return start
                    i = end + 1
                }
                else -> i++
            }
        }
        return null
    }

    private fun previousNonWhitespace(content: String, start: Int): Int? {
        var i = start
        while (i >= 0) {
            if (!content[i].isWhitespace()) return i
            i--
        }
        return null
    }

    private fun findMethodDeclarations(content: String, methodName: String): List<MethodDecl> {
        val results = mutableListOf<MethodDecl>()
        val nameRegex = Regex("""\b${Regex.escape(methodName)}\s*\(""")
        for (match in nameRegex.findAll(content)) {
            val nameStart = match.range.first
            if (nameStart > 0 && content[nameStart - 1] == '.') continue // call site
            val beforeLine = content.substring(content.lastIndexOf('\n', nameStart).let { if (it < 0) 0 else it + 1 }, nameStart)
            if (Regex("""\b(if|for|while|switch|catch|new)\s*\(?\s*$""").containsMatchIn(beforeLine)) continue

            val paramsStart = content.indexOf('(', nameStart)
            val paramsEnd = findMatching(content, paramsStart, '(', ')') ?: continue
            val bodyStart = findMethodBodyStart(content, paramsEnd) ?: continue
            val bodyEnd = findMatching(content, bodyStart, '{', '}') ?: continue
            results += MethodDecl(nameStart, paramsStart, paramsEnd, bodyStart, bodyEnd)
        }
        return results.distinctBy { it.nameStart }
    }

    private fun findMethodInvocations(content: String, methodName: String): List<MethodInvocation> {
        val results = mutableListOf<MethodInvocation>()
        for (range in JavaLexer.findOccurrences(content, methodName)) {
            val afterName = nextNonWhitespace(content, range.last + 1) ?: continue
            if (content.getOrNull(afterName) != '(') continue
            val argsEnd = findMatching(content, afterName, '(', ')') ?: continue
            results += MethodInvocation(range.first, afterName, argsEnd)
        }
        return results.distinctBy { it.nameStart }
    }

    private fun findMethodBodyStart(content: String, paramsEnd: Int): Int? {
        var i = paramsEnd + 1
        while (i < content.length) {
            when (content[i]) {
                '{' -> return i
                ';' -> return null
                '\n', '\r', '\t', ' ' -> i++
                else -> {
                    // Skip throws clause / annotations between parameter list and body.
                    i++
                }
            }
        }
        return null
    }

    private fun findParameterNameRange(content: String, start: Int, end: Int, paramName: String): IntRange? {
        val params = content.substring(start, end)
        val segments = splitTopLevelCommas(params)
        var offset = start
        for (segment in segments) {
            val trimmed = segment.trim()
            if (trimmed.isNotBlank()) {
                val nameMatch = Regex("""([A-Za-z_$][\w$]*)\s*$""").find(trimmed)
                if (nameMatch?.groupValues?.get(1) == paramName) {
                    val inSegment = segment.lastIndexOf(paramName)
                    val abs = offset + inSegment
                    return abs until abs + paramName.length
                }
            }
            offset += segment.length + 1
        }
        return null
    }

    private fun splitTopLevelCommas(params: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < params.length) {
            when (params[i]) {
                '"' -> i = skipString(params, i)
                '\'' -> i = skipChar(params, i)
                '<', '(', '[', '{' -> depth++
                '>', ')', ']', '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    result += params.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        result += params.substring(start)
        return result
    }

    private fun findMatching(content: String, openOffset: Int, open: Char, close: Char): Int? {
        if (openOffset !in content.indices || content[openOffset] != open) return null
        var depth = 0
        var i = openOffset
        while (i < content.length) {
            val c = content[i]
            when {
                c == '/' && content.getOrNull(i + 1) == '/' -> i = content.indexOf('\n', i).takeIf { it >= 0 } ?: content.length
                c == '/' && content.getOrNull(i + 1) == '*' -> i = content.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(1) ?: content.length
                c == '"' -> i = skipString(content, i)
                c == '\'' -> i = skipChar(content, i)
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun nextNonWhitespace(content: String, start: Int): Int? {
        var i = start
        while (i < content.length) {
            if (!content[i].isWhitespace()) return i
            i++
        }
        return null
    }

    private fun skipString(content: String, quote: Int): Int {
        // text block: """
        if (quote + 2 < content.length && content[quote + 1] == '"' && content[quote + 2] == '"') {
            var i = quote + 3
            while (i + 2 < content.length) {
                if (content[i] == '"' && content[i + 1] == '"' && content[i + 2] == '"') return i + 2
                i++
            }
            return content.length
        }
        var i = quote + 1
        while (i < content.length && content[i] != '\n') {
            if (content[i] == '\\') i += 2
            else if (content[i] == '"') return i
            else i++
        }
        return i
    }

    private fun skipChar(content: String, quote: Int): Int {
        var i = quote + 1
        while (i < content.length && content[i] != '\n') {
            if (content[i] == '\\') i += 2
            else if (content[i] == '\'') return i
            else i++
        }
        return i
    }

    private fun parseMemberSymbol(symbol: String): Pair<String, String>? {
        val hash = symbol.indexOf('#')
        if (hash < 0) return null
        val selector = symbol.substring(hash + 1)
        return symbol.substring(0, hash) to selector.substringBefore('(')
    }

    private fun refused(snapshot: ProjectSnapshot, operation: String, reason: String) = PatchPlan(
        operation = operation,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
    )

    private fun dedupeAndSort(edits: List<TextEdit>): List<TextEdit> = edits
        .distinctBy { "${it.range.start.line}:${it.range.start.character}:${it.range.end.line}:${it.range.end.character}:${it.newText}" }
        .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }, { it.range.end.line }, { it.range.end.character }))

    private fun isSupportedParameterType(type: String): Boolean {
        val trimmed = type.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it == ';' || it == '{' || it == '}' || it == '(' || it == ')' || it == '\n' || it == '\r' }) return false
        return trimmed.any { it.isLetter() } && trimmed.all { it.isLetterOrDigit() || it.isWhitespace() || it in "_$.<>?[],@&" }
    }

    private fun isSafeDefaultExpression(expression: String): Boolean {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it == ';' || it == '\n' || it == '\r' }) return false
        return balanced(trimmed) && !hasTopLevelComma(trimmed)
    }

    private fun balanced(text: String): Boolean {
        val stack = ArrayDeque<Char>()
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> i = skipString(text, i)
                '\'' -> i = skipChar(text, i)
                '(', '[', '{' -> stack.addLast(c)
                ')' -> if (stack.removeLastOrNull() != '(') return false
                ']' -> if (stack.removeLastOrNull() != '[') return false
                '}' -> if (stack.removeLastOrNull() != '{') return false
            }
            i++
        }
        return stack.isEmpty()
    }

    private fun hasTopLevelComma(text: String): Boolean {
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '"' -> i = skipString(text, i)
                '\'' -> i = skipChar(text, i)
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) return true
            }
            i++
        }
        return false
    }

    private fun isValidJavaIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!name[0].isLetter() && name[0] != '_' && name[0] != '$') return false
        return name.all { JavaLexer.isIdentChar(it) } && name !in JAVA_KEYWORDS
    }

    companion object {
        private val TYPE_KINDS = setOf(Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.ENUM, Symbol.Kind.RECORD, Symbol.Kind.ANNOTATION)
        private val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "var", "yield", "record", "sealed", "permits", "non-sealed",
        )
    }
}
