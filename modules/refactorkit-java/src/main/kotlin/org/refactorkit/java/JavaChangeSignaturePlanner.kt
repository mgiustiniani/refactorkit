package org.refactorkit.java

import org.eclipse.jdt.core.dom.Modifier
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.Symbol
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Conservative Java Change Signature planner.
 *
 * Supported operations:
 * - rename one JDT-bound method parameter, with exact signed overload selection;
 * - bounded lexical add/remove/reorder rows retained as experimental compatibility
 *   operations until their JDT-backed replacements are independently qualified.
 *
 * Every result is preview-only until explicitly applied through PatchEngine.
 */
class JavaChangeSignaturePlanner(private val adapter: JavaLanguageAdapter) {

    fun previewRenameParameter(
        snapshot: ProjectSnapshot,
        symbolFqnWithMethod: String,
        oldParameterName: String,
        newParameterName: String,
    ): PatchPlan {
        val operation = "changeSignature.renameParameter"
        if (!isValidJavaIdentifier(oldParameterName)) return refused(snapshot, operation, "Invalid old parameter name: $oldParameterName")
        if (!isValidJavaIdentifier(newParameterName)) return refused(snapshot, operation, "Invalid new parameter name: $newParameterName")
        if (oldParameterName == newParameterName) return refused(snapshot, operation, "Old and new parameter names are the same: $oldParameterName")
        val hash = symbolFqnWithMethod.indexOf('#')
        if (hash <= 0 || hash == symbolFqnWithMethod.lastIndex) {
            return refused(snapshot, operation, "Symbol must be in the form <FQN>#<method> or <FQN>#<method>(<types>)")
        }
        val ownerFqn = symbolFqnWithMethod.substring(0, hash)
        val selector = symbolFqnWithMethod.substring(hash + 1)
        if (selector.substringBefore('(') == "<init>") return refused(snapshot, operation, "Constructor parameter rename is not supported")

        val analyzer = JdtJavaSemanticAnalyzer()
        val before = analyzer.analyze(snapshot)
        val methodPrefix = "$ownerFqn#${selector.substringBefore('(')}"
        val methods = before.symbols.filter { symbol ->
            symbol.kind == JdtJavaSemanticSymbolKind.METHOD &&
                if ('(' in selector) symbol.qualifiedName == "$ownerFqn#$selector"
                else symbol.qualifiedName.substringBefore('(') == methodPrefix
        }
        if (methods.isEmpty()) return refused(snapshot, operation, "JDT method not found: $symbolFqnWithMethod")
        if (methods.size != 1) {
            return refused(snapshot, operation, "Method selector is overloaded or ambiguous; use the exact signed selector <FQN>#<method>(<types>)")
        }
        val method = methods.single()
        val parameters = before.parameters.filter { it.methodQualifiedName == method.qualifiedName && it.name == oldParameterName }
        if (parameters.size != 1) return refused(snapshot, operation, "JDT parameter '$oldParameterName' is missing or ambiguous in ${method.qualifiedName}")
        val parameter = parameters.single()
        val file = snapshot.files.singleOrNull { it.path == parameter.path }
            ?: return refused(snapshot, operation, "Authoritative parameter source is missing: ${parameter.path}")
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, operation, "Declaration file ${file.path} is generated code ($reason)")
        }
        val uses = before.bindingUses.filter { it.bindingKey == parameter.parameterBindingKey }
        val ranges = listOf(parameter.sourceRange) + uses.map { it.sourceRange }
        if (ranges.any { range ->
                val start = TextEdits.offsetOf(file.content, range.start)
                val end = TextEdits.offsetOf(file.content, range.end)
                file.content.substring(start, end) != oldParameterName
            }) {
            return refused(snapshot, operation, "JDT parameter evidence does not map one-to-one to exact '$oldParameterName' tokens")
        }
        val edits = dedupeAndSort(ranges.map { TextEdit(it, newParameterName) })
        if (edits.size != ranges.size) return refused(snapshot, operation, "JDT parameter evidence contains duplicate ranges")
        val fileEdits = listOf(FileEdit.Modify(file.path, edits))
        val stagedFiles = snapshot.files.map { source ->
            if (source.path == file.path) source.copy(content = TextEdits.apply(source.content, edits)) else source
        }
        val stagedSnapshot = snapshot.copy(files = stagedFiles)
        val staged = analyzer.analyze(stagedSnapshot)
        val baselineWarningCounts = before.warnings.groupingBy(::jdtWarningIdentity).eachCount()
        val stagedWarningCounts = staged.warnings.groupingBy(::jdtWarningIdentity).eachCount()
        val introducedWarnings = stagedWarningCounts.filter { (identity, count) ->
            count > baselineWarningCounts.getOrDefault(identity, 0)
        }
        if (introducedWarnings.isNotEmpty()) {
            val introducedCount = introducedWarnings.entries.sumOf { (identity, count) ->
                count - baselineWarningCounts.getOrDefault(identity, 0)
            }
            return refused(snapshot, operation, "Staged JDT validation introduced $introducedCount error(s)")
        }
        val stagedParameter = staged.parameters.singleOrNull {
            it.methodQualifiedName == method.qualifiedName && it.index == parameter.index && it.name == newParameterName
        } ?: return refused(snapshot, operation, "Staged JDT parameter identity could not be re-established")
        val stagedUses = staged.bindingUses.count { it.bindingKey == stagedParameter.parameterBindingKey }
        if (stagedUses != uses.size) {
            return refused(snapshot, operation, "Staged JDT parameter usage count changed from ${uses.size} to $stagedUses")
        }

        return PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.98,
            requiresUserApproval = true,
            summary = "Rename JDT-proven parameter '$oldParameterName' → '$newParameterName' in ${method.qualifiedName}. 1 file affected.",
            affectedFiles = setOf(file.path),
            workspaceEdit = WorkspaceEdit(fileEdits),
            diagnosticsAfterPreview = diagnosticsAfter(snapshot, fileEdits),
            warnings = listOf(
                "Declaration plus ${uses.size} parameter use(s) are bound to one exact JDT variable identity.",
                "Reflection, serialization frameworks, dependency injection, and annotation processors may observe parameter names.",
            ),
            riskLevel = if (method.hoverSignature.contains("public ") || method.hoverSignature.contains("protected ")) RiskLevel.HIGH else RiskLevel.MEDIUM,
        )
    }

    fun previewAddParameter(
        snapshot: ProjectSnapshot,
        symbolFqnWithMethod: String,
        parameterType: String,
        parameterName: String,
        defaultExpression: String,
    ): PatchPlan {
        val operation = "changeSignature.addParameter"
        if (!isSupportedParameterType(parameterType)) return refused(snapshot, operation, "Unsupported or unsafe parameter type: $parameterType")
        if (!isValidJavaIdentifier(parameterName)) return refused(snapshot, operation, "Invalid parameter name: $parameterName")
        if (!isSafeDefaultExpression(defaultExpression)) {
            return refused(snapshot, operation, "Default expression must be a single Java expression without semicolons, newlines, or top-level commas")
        }
        val (selection, selectionRefusal) = selectJdtMethod(snapshot, symbolFqnWithMethod, operation)
        if (selection == null) return selectionRefusal!!
        boundedJdtSignatureRisk(snapshot, selection, operation)?.let { return refused(snapshot, operation, it) }
        if (selection.parameters.any { it.name == parameterName }) {
            return refused(snapshot, operation, "Parameter '$parameterName' already exists in ${selection.method.qualifiedName}")
        }
        if (selection.parameters.any { sourceText(selection.file.content, it.declarationRange).contains("...") }) {
            return refused(snapshot, operation, "Cannot add a parameter after an existing varargs parameter")
        }
        if (selection.invocations.any { it.argumentRanges.size != selection.parameters.size }) {
            return refused(snapshot, operation, "JDT invocation argument count is incomplete for ${selection.method.qualifiedName}")
        }

        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        val existingDeclaration = sourceText(selection.file.content, selection.method.parameterListRange).trim()
        val newDeclaration = listOf(existingDeclaration, "${parameterType.trim()} $parameterName")
            .filter(String::isNotEmpty).joinToString(", ")
        editsByPath.getOrPut(selection.file.path) { mutableListOf() } += TextEdit(
            selection.method.parameterListRange,
            newDeclaration,
        )
        val defaultArgument = defaultExpression.trim()
        selection.invocations.forEach { invocation ->
            val source = snapshot.files.singleOrNull { it.path == invocation.path }
                ?: return refused(snapshot, operation, "JDT invocation source is missing: ${invocation.path}")
            val existingArguments = sourceText(source.content, invocation.argumentListRange).trim()
            val newArguments = listOf(existingArguments, defaultArgument).filter(String::isNotEmpty).joinToString(", ")
            editsByPath.getOrPut(source.path) { mutableListOf() } += TextEdit(invocation.argumentListRange, newArguments)
        }
        val fileEdits = editsByPath.map { (path, edits) -> FileEdit.Modify(path, dedupeAndSort(edits)) }
        val staged = analyzeStaged(stagedSnapshot(snapshot, fileEdits))
        val introduced = introducedJdtWarningCount(selection.analysis, staged)
        if (introduced > 0) return refused(snapshot, operation, "Staged JDT validation introduced $introduced error(s)")
        val expectedNames = selection.parameters.map { it.name } + parameterName
        val stagedMethod = staged.methods.singleOrNull { candidate ->
            candidate.qualifiedName.substringBefore('(') == selection.method.qualifiedName.substringBefore('(') &&
                staged.parameters.filter { it.methodQualifiedName == candidate.qualifiedName }.sortedBy { it.index }.map { it.name } == expectedNames
        } ?: return refused(snapshot, operation, "Staged JDT method/new-parameter identity could not be re-established")
        val stagedInvocations = staged.invocations.filter { it.methodQualifiedName == stagedMethod.qualifiedName }
        if (stagedInvocations.size != selection.invocations.size || stagedInvocations.any {
                it.argumentRanges.size != expectedNames.size
            }) return refused(snapshot, operation, "Staged JDT invocation identity/count changed")

        val affected = fileEdits.map { it.path }.toSet()
        return PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.97,
            requiresUserApproval = true,
            summary = "Add JDT-proven parameter '${parameterType.trim()} $parameterName' to ${selection.method.qualifiedName} and update ${selection.invocations.size} in-scope call site(s) proven by JDT bindings.",
            affectedFiles = affected,
            workspaceEdit = WorkspaceEdit(fileEdits),
            diagnosticsAfterPreview = diagnosticsAfter(snapshot, fileEdits),
            warnings = listOf("Declaration and call-site argument lists are exact JDT-bound ranges."),
            riskLevel = RiskLevel.MEDIUM,
        )
    }

    fun previewReorderParameters(
        snapshot: ProjectSnapshot,
        symbolFqnWithMethod: String,
        newOrder: List<String>,
    ): PatchPlan {
        val operation = "changeSignature.reorderParameters"
        val (selection, selectionRefusal) = selectJdtMethod(snapshot, symbolFqnWithMethod, operation)
        if (selection == null) return selectionRefusal!!
        boundedJdtSignatureRisk(snapshot, selection, operation)?.let { return refused(snapshot, operation, it) }
        if (selection.parameters.size < 2) return refused(snapshot, operation, "Method must have at least two parameters to reorder")
        val requested = newOrder.map(String::trim).filter(String::isNotEmpty)
        val existing = selection.parameters.map { it.name }
        if (requested.size != existing.size || requested.toSet() != existing.toSet() || requested.distinct().size != requested.size) {
            return refused(snapshot, operation, "New order must list each existing parameter exactly once: ${existing.joinToString(", ")}")
        }
        if (requested == existing) return refused(snapshot, operation, "New parameter order is identical to the current order")
        val indexByName = existing.withIndex().associate { it.value to it.index }
        val reorderedIndexes = requested.map(indexByName::getValue)
        val varargs = selection.parameters.indexOfFirst {
            sourceText(selection.file.content, it.declarationRange).contains("...")
        }
        if (varargs >= 0 && reorderedIndexes.last() != varargs) {
            return refused(snapshot, operation, "Varargs parameter '${selection.parameters[varargs].name}' must remain last")
        }
        if (selection.invocations.any { it.argumentRanges.size != selection.parameters.size }) {
            return refused(snapshot, operation, "JDT invocation argument count is incomplete for ${selection.method.qualifiedName}")
        }

        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        editsByPath.getOrPut(selection.file.path) { mutableListOf() } += TextEdit(
            selection.method.parameterListRange,
            reorderedIndexes.joinToString(", ") {
                sourceText(selection.file.content, selection.parameters[it].declarationRange).trim()
            },
        )
        selection.invocations.forEach { invocation ->
            val source = snapshot.files.singleOrNull { it.path == invocation.path }
                ?: return refused(snapshot, operation, "JDT invocation source is missing: ${invocation.path}")
            val arguments = invocation.argumentRanges.map { sourceText(source.content, it).trim() }
            editsByPath.getOrPut(source.path) { mutableListOf() } += TextEdit(
                invocation.argumentListRange,
                reorderedIndexes.joinToString(", ") { arguments[it] },
            )
        }
        val fileEdits = editsByPath.map { (path, edits) -> FileEdit.Modify(path, dedupeAndSort(edits)) }
        val staged = analyzeStaged(stagedSnapshot(snapshot, fileEdits))
        val introduced = introducedJdtWarningCount(selection.analysis, staged)
        if (introduced > 0) return refused(snapshot, operation, "Staged JDT validation introduced $introduced error(s)")
        val originalTypes = methodParameterTypes(selection.method)
        val expectedTypes = reorderedIndexes.map { originalTypes[it] }
        val stagedMethod = staged.methods.singleOrNull { candidate ->
            candidate.qualifiedName.substringBefore('(') == selection.method.qualifiedName.substringBefore('(') &&
                methodParameterTypes(candidate) == expectedTypes &&
                staged.parameters.filter { it.methodQualifiedName == candidate.qualifiedName }.sortedBy { it.index }.map { it.name } == requested
        } ?: return refused(snapshot, operation, "Staged JDT method/parameter identity could not be re-established")
        val stagedInvocations = staged.invocations.filter { it.methodQualifiedName == stagedMethod.qualifiedName }
        if (stagedInvocations.size != selection.invocations.size || stagedInvocations.any {
                it.argumentRanges.size != selection.parameters.size
            }) return refused(snapshot, operation, "Staged JDT invocation identity/count changed")

        val affected = fileEdits.map { it.path }.toSet()
        return PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.97,
            requiresUserApproval = true,
            summary = "Reorder JDT-proven parameters of ${selection.method.qualifiedName} to (${requested.joinToString(", ")}) and update ${selection.invocations.size} bound call site(s).",
            affectedFiles = affected,
            workspaceEdit = WorkspaceEdit(fileEdits),
            diagnosticsAfterPreview = diagnosticsAfter(snapshot, fileEdits),
            warnings = listOf("Declaration and call-site argument lists are exact JDT-bound ranges."),
            riskLevel = RiskLevel.MEDIUM,
        )
    }

    fun previewRemoveParameter(
        snapshot: ProjectSnapshot,
        symbolFqnWithMethod: String,
        parameterName: String,
    ): PatchPlan {
        val operation = "changeSignature.removeParameter"
        if (!isValidJavaIdentifier(parameterName)) return refused(snapshot, operation, "Invalid parameter name: $parameterName")
        val (selection, selectionRefusal) = selectJdtMethod(snapshot, symbolFqnWithMethod, operation)
        if (selection == null) return selectionRefusal!!
        boundedJdtSignatureRisk(snapshot, selection, operation)?.let { return refused(snapshot, operation, it) }
        val removeIndex = selection.parameters.indexOfFirst { it.name == parameterName }
        if (removeIndex < 0) return refused(snapshot, operation, "JDT parameter '$parameterName' not found in ${selection.method.qualifiedName}")
        val parameter = selection.parameters[removeIndex]
        val uses = selection.analysis.bindingUses.filter { it.bindingKey == parameter.parameterBindingKey }
        if (uses.isNotEmpty()) {
            return refused(snapshot, operation, "JDT parameter '$parameterName' is used in the method body (${uses.size} bound use(s))")
        }
        if (selection.invocations.any { it.argumentRanges.size != selection.parameters.size }) {
            return refused(snapshot, operation, "JDT invocation argument count is incomplete for ${selection.method.qualifiedName}")
        }

        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        val declarationText = selection.parameters.filterIndexed { index, _ -> index != removeIndex }
            .joinToString(", ") { sourceText(selection.file.content, it.declarationRange).trim() }
        editsByPath.getOrPut(selection.file.path) { mutableListOf() } += TextEdit(
            selection.method.parameterListRange,
            declarationText,
        )
        selection.invocations.forEach { invocation ->
            val source = snapshot.files.singleOrNull { it.path == invocation.path }
                ?: return refused(snapshot, operation, "JDT invocation source is missing: ${invocation.path}")
            val arguments = invocation.argumentRanges.map { sourceText(source.content, it).trim() }
            editsByPath.getOrPut(source.path) { mutableListOf() } += TextEdit(
                invocation.argumentListRange,
                arguments.filterIndexed { index, _ -> index != removeIndex }.joinToString(", "),
            )
        }
        val fileEdits = editsByPath.map { (path, edits) -> FileEdit.Modify(path, dedupeAndSort(edits)) }
        val stagedSnapshot = stagedSnapshot(snapshot, fileEdits)
        val staged = analyzeStaged(stagedSnapshot)
        val introduced = introducedJdtWarningCount(selection.analysis, staged)
        if (introduced > 0) return refused(snapshot, operation, "Staged JDT validation introduced $introduced error(s)")
        val expectedNames = selection.parameters.filterIndexed { index, _ -> index != removeIndex }.map { it.name }
        val expectedTypes = methodParameterTypes(selection.method).filterIndexed { index, _ -> index != removeIndex }
        val stagedMethod = staged.methods.singleOrNull { candidate ->
            candidate.qualifiedName.substringBefore('(') == selection.method.qualifiedName.substringBefore('(') &&
                methodParameterTypes(candidate) == expectedTypes &&
                staged.parameters.filter { it.methodQualifiedName == candidate.qualifiedName }.sortedBy { it.index }.map { it.name } == expectedNames
        } ?: return refused(snapshot, operation, "Staged JDT method identity could not be re-established after parameter removal")
        val stagedInvocations = staged.invocations.filter { it.methodQualifiedName == stagedMethod.qualifiedName }
        if (stagedInvocations.size != selection.invocations.size || stagedInvocations.any {
                it.argumentRanges.size != selection.parameters.size - 1
            }) return refused(snapshot, operation, "Staged JDT invocation identity/count changed")

        val affected = fileEdits.map { it.path }.toSet()
        return PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.97,
            requiresUserApproval = true,
            summary = "Remove JDT-proven unused parameter '$parameterName' from ${selection.method.qualifiedName} and update ${selection.invocations.size} bound call site(s).",
            affectedFiles = affected,
            workspaceEdit = WorkspaceEdit(fileEdits),
            diagnosticsAfterPreview = diagnosticsAfter(snapshot, fileEdits),
            warnings = listOf("JDT remove-parameter declaration and call sites are exact bindings; method references and hierarchy/public boundaries remain refused."),
            riskLevel = RiskLevel.MEDIUM,
        )
    }

    private data class JdtMethodSelection(
        val analysis: JdtJavaSemanticAnalysisResult,
        val method: JdtJavaSemanticMethod,
        val symbol: JdtJavaSemanticSymbol,
        val file: org.refactorkit.core.SourceFile,
        val parameters: List<JdtJavaSemanticParameter>,
        val invocations: List<JdtJavaSemanticInvocation>,
    )

    private fun selectJdtMethod(
        snapshot: ProjectSnapshot,
        selectorText: String,
        operation: String,
    ): Pair<JdtMethodSelection?, PatchPlan?> {
        val hash = selectorText.indexOf('#')
        if (hash <= 0 || hash == selectorText.lastIndex) {
            return null to refused(snapshot, operation, "Symbol must be <FQN>#<method> or <FQN>#<method>(<types>)")
        }
        val owner = selectorText.substring(0, hash)
        val selector = selectorText.substring(hash + 1)
        val prefix = "$owner#${selector.substringBefore('(')}"
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val methods = analysis.methods.filter {
            if ('(' in selector) it.qualifiedName == "$owner#$selector"
            else it.qualifiedName.substringBefore('(') == prefix
        }
        if (methods.isEmpty()) return null to refused(snapshot, operation, "JDT method not found: $selectorText")
        if (methods.size != 1) return null to refused(
            snapshot,
            operation,
            "Method selector is overloaded or ambiguous; use exact signed selector <FQN>#<method>(<types>)",
        )
        val method = methods.single()
        if (method.constructor) return null to refused(snapshot, operation, "Constructor change signature is not supported")
        val symbol = analysis.symbols.singleOrNull {
            it.kind == JdtJavaSemanticSymbolKind.METHOD && it.qualifiedName == method.qualifiedName
        } ?: return null to refused(snapshot, operation, "JDT method symbol is missing or ambiguous: ${method.qualifiedName}")
        val file = snapshot.files.singleOrNull { it.path == method.path }
            ?: return null to refused(snapshot, operation, "Authoritative method source is missing: ${method.path}")
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return null to refused(snapshot, operation, "Declaration file ${file.path} is generated code ($reason)")
        }
        val parameters = analysis.parameters.filter { it.methodQualifiedName == method.qualifiedName }.sortedBy { it.index }
        val invocations = analysis.invocations.filter { it.methodQualifiedName == method.qualifiedName }
        return JdtMethodSelection(analysis, method, symbol, file, parameters, invocations) to null
    }

    private fun boundedJdtSignatureRisk(
        snapshot: ProjectSnapshot,
        selection: JdtMethodSelection,
        operation: String,
    ): String? {
        val ownerName = selection.method.qualifiedName.substringBefore('#')
        val owner = selection.analysis.symbols.singleOrNull {
            it.qualifiedName == ownerName && it.kind in setOf(
                JdtJavaSemanticSymbolKind.CLASS,
                JdtJavaSemanticSymbolKind.INTERFACE,
                JdtJavaSemanticSymbolKind.ENUM,
                JdtJavaSemanticSymbolKind.RECORD,
            )
        }
        if (owner?.kind == JdtJavaSemanticSymbolKind.INTERFACE) {
            return "$operation refuses interface methods until the complete implementer hierarchy is selected"
        }
        if (Modifier.isPublic(selection.method.modifiers) || Modifier.isProtected(selection.method.modifiers)) {
            return "$operation refuses public method or protected method until external-consumer risk and hierarchy evidence are complete"
        }
        if (selection.analysis.overrideRelations.any {
                it.overridingSymbolQualifiedName == selection.method.qualifiedName ||
                    it.overriddenSymbolQualifiedName == selection.method.qualifiedName
            }) return "$operation refuses @Override/implementer families until every hierarchy declaration is selected"
        val methodName = selection.method.qualifiedName.substringAfter('#').substringBefore('(')
        val scoped = scopedFiles(snapshot, ownerName, methodName, selection.file.path)
        if (firstMethodReference(scoped, methodName) != null) {
            return "$operation refuses Method reference targets until functional signatures are updated"
        }
        firstStringLiteralContaining(scoped, methodName)?.let { location ->
            return "$operation refuses String literal method-name risk at $location"
        }
        val parameterList = sourceText(selection.file.content, selection.method.parameterListRange)
        if (parameterList.contains("//") || parameterList.contains("/*")) {
            return "$operation refuses comments inside the bounded parameter list"
        }
        return null
    }

    private fun analyzeStaged(snapshot: ProjectSnapshot): JdtJavaSemanticAnalysisResult {
        val temporaryRoot = Files.createTempDirectory("refactorkit-jdt-staged-")
        try {
            snapshot.files.forEach { file ->
                require(!file.path.isAbsolute && !file.path.normalize().startsWith("..")) {
                    "staged JDT source path must remain workspace-relative: ${file.path}"
                }
                val target = temporaryRoot.resolve(file.path).normalize()
                require(target.startsWith(temporaryRoot)) { "staged JDT source escapes disposable root" }
                Files.createDirectories(target.parent)
                Files.writeString(target, file.content, Charsets.UTF_8)
            }
            return JdtJavaSemanticAnalyzer().analyze(
                snapshot.copy(workspace = snapshot.workspace.copy(root = temporaryRoot)),
            )
        } finally {
            if (Files.exists(temporaryRoot)) {
                Files.walk(temporaryRoot).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    private fun stagedSnapshot(snapshot: ProjectSnapshot, fileEdits: List<FileEdit>): ProjectSnapshot {
        val edits = fileEdits.filterIsInstance<FileEdit.Modify>().associateBy { it.path }
        return snapshot.copy(files = snapshot.files.map { file ->
            val edit = edits[file.path]
            if (edit == null) file else file.copy(content = TextEdits.apply(file.content, edit.textEdits))
        })
    }

    private fun methodParameterTypes(method: JdtJavaSemanticMethod): List<String> {
        val signature = method.signature
        val open = signature.indexOf('(')
        val close = signature.lastIndexOf(')')
        if (open < 0 || close < open) return emptyList()
        return splitTopLevelCommas(signature.substring(open + 1, close)).map(String::trim).filter(String::isNotEmpty)
    }

    private fun sourceText(content: String, range: org.refactorkit.core.SourceRange): String = content.substring(
        TextEdits.offsetOf(content, range.start),
        TextEdits.offsetOf(content, range.end),
    )

    private fun introducedJdtWarningCount(
        before: JdtJavaSemanticAnalysisResult,
        staged: JdtJavaSemanticAnalysisResult,
    ): Int {
        val baseline = before.warnings.groupingBy(::jdtWarningIdentity).eachCount()
        return staged.warnings.groupingBy(::jdtWarningIdentity).eachCount().entries.sumOf { (identity, count) ->
            (count - baseline.getOrDefault(identity, 0)).coerceAtLeast(0)
        }
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
        val previewSnapshot = snapshot.copy(files = files)
        return adapter.diagnostics(previewSnapshot)
    }

    private fun blockingChangeSignatureRisk(
        declarationFile: org.refactorkit.core.SourceFile,
        scopedFiles: List<org.refactorkit.core.SourceFile>,
        methodName: String,
    ): String? {
        JavaGeneratedSourcePolicy.reason(declarationFile)?.let { reason ->
            return "Declaration file ${declarationFile.path} is generated code ($reason); change-signature refuses generated declarations."
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

    private fun jdtWarningIdentity(warning: JdtJavaSemanticWarning): String =
        "${warning.path}|${warning.problemId}|${warning.message}"

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
