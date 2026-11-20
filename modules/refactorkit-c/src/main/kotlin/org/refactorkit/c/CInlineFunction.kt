package org.refactorkit.c

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Path
import java.security.MessageDigest

/** Value and binding identities contain no process-local Clang pointer IDs or source offsets. */
internal sealed interface CInlineValue {
    data class Integer(val value: Int) : CInlineValue
    data class Read(val binding: String) : CInlineValue
    data class Parameter(val index: Int) : CInlineValue
    data class Binary(val operator: String, val left: CInlineValue, val right: CInlineValue) : CInlineValue
    data class StringCharacter(val value: String, val arrayType: String, val index: Int) : CInlineValue
    data class Call(val arguments: List<CInlineValue>) : CInlineValue
}

internal data class CInlineInitialization(val binding: String, val type: String, val value: CInlineValue)
internal data class CInlineImage(
    val owner: String,
    val type: String,
    val initializations: List<CInlineInitialization>,
    val returned: CInlineValue,
) {
    fun replaceCall(body: CInlineValue): CInlineImage {
        fun substitute(value: CInlineValue, arguments: List<CInlineValue>): CInlineValue = when (value) {
            is CInlineValue.Parameter -> arguments[value.index]
            is CInlineValue.Binary -> value.copy(left = substitute(value.left, arguments), right = substitute(value.right, arguments))
            else -> value
        }
        fun replace(value: CInlineValue): CInlineValue = when (value) {
            is CInlineValue.Call -> substitute(body, value.arguments)
            is CInlineValue.Binary -> value.copy(left = replace(value.left), right = replace(value.right))
            else -> value
        }
        return copy(initializations = initializations.map { it.copy(value = replace(it.value)) }, returned = replace(returned))
    }

    /** Length-prefixed fields avoid ambiguity even for compiler-decoded string constants. */
    fun identity(): String {
        val bytes = buildString {
            fun field(value: String) { append(value.length).append(':').append(value) }
            fun value(value: CInlineValue) {
                when (value) {
                    is CInlineValue.Integer -> { field("int"); field(value.value.toString()) }
                    is CInlineValue.Read -> { field("read"); field(value.binding) }
                    is CInlineValue.Parameter -> { field("parameter"); field(value.index.toString()) }
                    is CInlineValue.Binary -> { field("binary"); field(value.operator); value(value.left); value(value.right) }
                    is CInlineValue.StringCharacter -> { field("string-char-int"); field(value.value); field(value.arrayType); field(value.index.toString()) }
                    is CInlineValue.Call -> { field("call"); field(value.arguments.size.toString()); value.arguments.forEach(::value) }
                }
            }
            field("int-eager-source-binding-v1"); field(owner); field(type); field(initializations.size.toString())
            initializations.forEach { field(it.binding); field(it.type); value(it.value) }
            value(returned)
        }.toByteArray(Charsets.UTF_8)
        return inlineSha256(bytes)
    }
}

internal data class CInlineFunctionRewrite(
    val declarations: List<CFunctionByteSpan>,
    val call: CFunctionByteSpan,
    val expression: CFunctionByteSpan,
    val parameterUses: List<Pair<CFunctionByteSpan, Int>>,
    val arguments: List<CFunctionByteSpan>,
    val body: CInlineValue,
)
internal data class CInlineFunctionFacts(val image: CInlineImage, val rewrite: CInlineFunctionRewrite?)
internal sealed interface CInlineFunctionFactsResult {
    data class Available(val snapshotHash: String, val facts: CInlineFunctionFacts) : CInlineFunctionFactsResult
    data class Unavailable(val diagnostic: Diagnostic) : CInlineFunctionFactsResult
}

/** Pure, source-owned Clang AST decoder for the first managed inline-function cell. */
internal object CInlineFunctionFactsDecoder {
    fun decode(ast: JsonObject, file: Path, source: ByteArray, function: String, candidate: Boolean): CInlineFunctionFacts {
        val children = CFunctionFamilyFactsDecoder::children
        val all = CFunctionFamilyFactsDecoder.flatten(ast)
        fun span(node: JsonObject) = CFunctionFamilyFactsDecoder.span(node, file, source)
        require(all.none { kind(it).endsWith("Attr") || kind(it) in setOf("GCCAsmStmt", "MSAsmStmt") }) { "attributes or assembly are unqualified" }
        val functions = children(ast).filter { kind(it) == "FunctionDecl" }
        // No hidden globals, record fields, imported declarations or additional consumers in this cell.
        require(children(ast).all { kind(it) == "FunctionDecl" ||
            kind(it) == "TypedefDecl" && it["isImplicit"] == JsonPrimitive(true) }) { "unqualified translation-unit declarations" }
        functions.forEach { span(it) }
        val family = functions.filter { text(it, "name") == function }
        require(if (candidate) family.isEmpty() else family.isNotEmpty()) { "function family differs from expected image" }
        val caller = functions.filter { text(it, "name") != function }.single()
        val callerName = requireNotNull(text(caller, "name"))
        val callerParameters = children(caller).filter { kind(it) == "ParmVarDecl" }
        val callerType = functionType(caller, callerParameters)
        val callerBody = children(caller).single { kind(it) == "CompoundStmt" }
        require(children(caller).all { kind(it) in setOf("ParmVarDecl", "CompoundStmt") })
        val callerBindings = mutableMapOf<String, CInlineValue>()
        callerParameters.forEachIndexed { index, node ->
            require(intType(node)); span(node)
            callerBindings[id(node)] = CInlineValue.Read("$callerName/parameter/$index/${text(node, "name")}")
        }
        val parameterUses = mutableListOf<Pair<CFunctionByteSpan, Int>>()
        val familyIds = family.map(::id).toSet()
        val calls = mutableListOf<JsonObject>()

        fun expression(node: JsonObject, bindings: Map<String, CInlineValue>, allowCall: Boolean): CInlineValue {
            span(node)
            require(intType(node)) { "non-int value or implicit conversion" }
            val nested = children(node)
            return when (kind(node)) {
                "ParenExpr" -> expression(nested.single(), bindings, allowCall)
                "IntegerLiteral" -> CInlineValue.Integer(requireNotNull(text(node, "value")?.toIntOrNull()))
                "ImplicitCastExpr" -> when (text(node, "castKind")) {
                    "LValueToRValue" -> {
                        val ref = nested.single()
                        require(kind(ref) == "DeclRefExpr" && intType(ref)) { "non-local or qualified read" }
                        expression(ref, bindings, false)
                    }
                    "IntegralCast" -> {
                        // The sole non-int leaf is an in-bounds read of an ordinary string constant.
                        val load = nested.single()
                        require(kind(load) == "ImplicitCastExpr" && type(load) == "char" && text(load, "castKind") == "LValueToRValue")
                        val subscript = children(load).single()
                        require(kind(subscript) == "ArraySubscriptExpr" && type(subscript) == "char")
                        val parts = children(subscript); require(parts.size == 2)
                        val decay = parts[0]
                        require(kind(decay) == "ImplicitCastExpr" && type(decay) == "char *" && text(decay, "castKind") == "ArrayToPointerDecay")
                        val literal = children(decay).single()
                        require(kind(literal) == "StringLiteral")
                        val arrayType = type(literal)
                        val size = Regex("char\\[([0-9]+)]").matchEntire(arrayType)?.groupValues?.get(1)?.toIntOrNull()
                        val index = expression(parts[1], bindings, false) as? CInlineValue.Integer
                        require(size != null && size > 0 && index != null && index.value in 0 until size)
                        listOf(load, subscript, decay, literal).forEach { span(it) }
                        CInlineValue.StringCharacter(requireNotNull(text(literal, "value")), arrayType, index.value)
                    }
                    else -> error("unqualified conversion")
                }
                "DeclRefExpr" -> {
                    val ref = node["referencedDecl"] as? JsonObject ?: error("missing binding")
                    require(intType(ref))
                    val value = requireNotNull(bindings[id(ref)]) { "unbound or captured declaration" }
                    if (value is CInlineValue.Parameter) parameterUses += span(node) to value.index
                    value
                }
                "BinaryOperator" -> {
                    val operator = text(node, "opcode")
                    require(operator in setOf("+", "-", "*") && nested.size == 2)
                    CInlineValue.Binary(requireNotNull(operator), expression(nested[0], bindings, allowCall), expression(nested[1], bindings, allowCall))
                }
                "CallExpr" -> {
                    require(allowCall && !candidate && nested.isNotEmpty())
                    val decay = nested.first()
                    require(kind(decay) == "ImplicitCastExpr" && text(decay, "castKind") == "FunctionToPointerDecay")
                    val ref = children(decay).single()
                    require(kind(ref) == "DeclRefExpr" && id(ref["referencedDecl"] as JsonObject) in familyIds)
                    span(decay); span(ref); calls += node
                    val arguments = nested.drop(1).map { expression(it, bindings, false) }
                    // Reads of initialized non-volatile locals and literal constants are total and effect-free.
                    require(arguments.all { it is CInlineValue.Integer || it is CInlineValue.Read })
                    CInlineValue.Call(arguments)
                }
                else -> error("unsupported expression, effects or control flow")
            }
        }

        val statements = children(callerBody)
        require(statements.isNotEmpty() && kind(statements.last()) == "ReturnStmt")
        val initializations = statements.dropLast(1).mapIndexed { index, statement ->
            require(kind(statement) == "DeclStmt")
            val declaration = children(statement).single()
            require(kind(declaration) == "VarDecl" && intType(declaration) &&
                text(declaration, "storageClass") == null && declaration["tls"] == null)
            val value = expression(children(declaration).single(), callerBindings, true)
            val binding = "$callerName/local/$index/${requireNotNull(text(declaration, "name"))}"
            require(callerBindings.put(id(declaration), CInlineValue.Read(binding)) == null)
            CInlineInitialization(binding, type(declaration), value)
        }
        val returned = expression(children(statements.last()).single(), callerBindings, true)
        val image = CInlineImage(callerName, callerType, initializations, returned)
        if (candidate) return CInlineFunctionFacts(image, null)

        val definition = family.single { children(it).any { node -> kind(node) == "CompoundStmt" } }
        val parameters = children(definition).filter { kind(it) == "ParmVarDecl" }
        val signature = functionType(definition, parameters)
        val seen = mutableSetOf<String>()
        family.forEach { declaration ->
            require(text(declaration, "storageClass") == "static" && functionType(declaration,
                children(declaration).filter { kind(it) == "ParmVarDecl" }) == signature)
            require(children(declaration).all { kind(it) in setOf("ParmVarDecl", "CompoundStmt") })
            val previous = text(declaration, "previousDecl")
            require(if (seen.isEmpty()) previous == null else previous in seen)
            require(seen.add(id(declaration)))
        }
        val body = children(definition).single { kind(it) == "CompoundStmt" }
        val returnStatement = children(body).single()
        require(kind(returnStatement) == "ReturnStmt")
        val valueNode = children(returnStatement).single()
        val bindings = parameters.mapIndexed { index, node ->
            require(intType(node)); span(node)
            id(node) to CInlineValue.Parameter(index)
        }.toMap()
        require(bindings.size == parameters.size)
        val value = expression(valueNode, bindings, false)
        val call = calls.single()
        val args = children(call).drop(1)
        require(args.size == parameters.size)
        val familyReferences = all.filter { kind(it) == "DeclRefExpr" &&
            (it["referencedDecl"] as? JsonObject)?.let(::id) in familyIds }
        val directReference = children(children(call).first()).single()
        require(familyReferences.size == 1 && familyReferences.single() == directReference) { "unclosed function consumers" }
        return CInlineFunctionFacts(image, CInlineFunctionRewrite(family.map(::span), span(call), span(valueNode),
            parameterUses.toList(), args.map(::span), value))
    }

    private fun functionType(node: JsonObject, parameters: List<JsonObject>): String {
        require(node["variadic"] != JsonPrimitive(true) && parameters.all(::intType))
        val expected = if (parameters.isEmpty()) "int (void)" else "int (${parameters.joinToString(", ") { "int" }})"
        require(type(node) == expected) { "unqualified or non-prototype function type" }
        return expected
    }
    private fun kind(node: JsonObject) = text(node, "kind").orEmpty()
    private fun id(node: JsonObject) = requireNotNull(text(node, "id"))
    private fun type(node: JsonObject) = text(node["type"] as? JsonObject, "qualType").orEmpty()
    private fun intType(node: JsonObject) = type(node) == "int"
    private fun text(node: JsonObject?, key: String) = (node?.get(key) as? JsonPrimitive)?.content
}

/** Edits only compiler-owned parameter reads, the one direct call, and its complete declaration family. */
internal object CInlineFunctionRenderer {
    fun edit(source: String, path: Path, facts: CInlineFunctionRewrite): WorkspaceEdit {
        val bytes = source.toByteArray(Charsets.UTF_8)
        fun content(span: CFunctionByteSpan) = bytes.copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
        var expression = content(facts.expression)
        facts.parameterUses.sortedByDescending { it.first.start }.forEach { (use, index) ->
            require(use.start >= facts.expression.start && use.endExclusive <= facts.expression.endExclusive)
            val start = bytes.copyOfRange(facts.expression.start, use.start).toString(Charsets.UTF_8).length
            val end = bytes.copyOfRange(facts.expression.start, use.endExclusive).toString(Charsets.UTF_8).length
            expression = expression.replaceRange(start, end, content(facts.arguments[index]))
        }
        fun edit(span: CFunctionByteSpan, replacement: String): TextEdit {
            val offset = bytes.copyOfRange(0, span.start).toString(Charsets.UTF_8).length
            val length = content(span).length
            return TextEdit(TextEdits.rangeForOffset(source, offset, length), replacement)
        }
        val removals = facts.declarations.map { declaration ->
            var end = declaration.endExclusive
            // Clang declaration ranges omit the prototype semicolon.
            if (end < bytes.size && bytes[end] == ';'.code.toByte()) end++
            while (end < bytes.size && bytes[end].toInt().toChar() in setOf(' ', '\t', '\r', '\n')) end++
            edit(CFunctionByteSpan(declaration.start, end), "")
        }
        return WorkspaceEdit(listOf(FileEdit.Modify(path, removals + edit(facts.call, "($expression)"))))
    }
}

internal class CNativeInlineFunctionPlanner(
    private val toolchain: ClangSemanticToolchain,
    private val manager: ExternalSemanticProcessManager,
) {
    fun preview(snapshot: ProjectSnapshot, file: Path, function: String): PatchPlan {
        if (!function.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]{0,127}"))) return refuse(snapshot, "invalidName")
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val absolute = (if (file.isAbsolute) file else root.resolve(file)).normalize()
        if (!absolute.startsWith(root) || absolute == root) return refuse(snapshot, "sourceUnavailable")
        val path = root.relativize(absolute)
        val source = snapshot.files.singleOrNull { it.path.normalize() == path } ?: return refuse(snapshot, "sourceUnavailable")
        val service = CFunctionFactsService(toolchain, manager)
        val before = when (val result = service.analyzeInlineFunction(snapshot, path, function)) {
            is CInlineFunctionFactsResult.Available -> result.takeIf { it.snapshotHash == snapshot.hash }?.facts
            is CInlineFunctionFactsResult.Unavailable -> return refuse(snapshot, result.diagnostic.code.orEmpty(), result.diagnostic.message)
        } ?: return refuse(snapshot, "factsUnavailable")
        val rewrite = before.rewrite ?: return refuse(snapshot, "familyUnavailable")
        val edit = try { CInlineFunctionRenderer.edit(source.content, path, rewrite) }
        catch (_: Exception) { return refuse(snapshot, "sourceRangeInvalid") }
        val candidate = try { WorkspaceEditSimulator.apply(snapshot, edit) }
        catch (_: Exception) { return refuse(snapshot, "sourceRangeInvalid") }
        val after = when (val result = service.analyzeInlineFunctionPreview(snapshot, candidate, path, function)) {
            is CInlineFunctionFactsResult.Available -> result.takeIf { it.snapshotHash == candidate.hash }?.facts
            is CInlineFunctionFactsResult.Unavailable -> return refuse(snapshot, result.diagnostic.code.orEmpty(), result.diagnostic.message)
        } ?: return refuse(snapshot, "candidateUnavailable")
        if (after.image != before.image.replaceCall(rewrite.body)) return refuse(snapshot, "semanticImageMismatch")
        val resource = (toolchain.resources as? ClangResourceCaptureResult.Captured)?.snapshot?.identity
            ?: return refuse(snapshot, "resourceUnavailable")
        val runtime = toolchain.provenance.nativeRuntimeIdentity ?: return refuse(snapshot, "runtimeUnavailable")
        return PatchPlan(operation = "inlineFunction", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 1.0, requiresUserApproval = true, summary = "Inline compiler-bound '$function' and remove its internal declaration family",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit, diagnosticsBefore = emptyList(), diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Qualified for a single internal int function, atomic int arguments and one straight-line caller in captured C11/C17 inputs."),
            riskLevel = RiskLevel.MEDIUM, evidence = RefactoringEvidence.COMPILER_PROVEN,
            authorityLease = OperationAuthorityLease(kind = "c.inline-function-int-expression-v1", operation = "inlineFunction",
                snapshotHash = snapshot.hash, evidenceHash = candidate.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit),
                attributes = mapOf("c.file" to path.toString(), "c.functionName" to function,
                    "c.inlineFunctionModelPolicy" to "int-eager-source-binding-v1", "c.nativeRuntimeIdentity" to runtime,
                    "c.resourceDirectoryIdentity" to resource, "c.beforeSemanticSha256" to before.image.identity(),
                    "c.afterSemanticSha256" to after.image.identity())),
        )
    }

    private fun refuse(snapshot: ProjectSnapshot, reason: String, detail: String = reason) = PatchPlan(
        operation = "inlineFunction", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash, confidence = 0.0,
        requiresUserApproval = true, summary = "Compiler-bound function inline refused: $detail", affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(emptyList()), diagnosticsBefore = emptyList(), diagnosticsAfterPreview = emptyList(),
        warnings = listOf(detail), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.COMPILER_PROVEN,
        refusalCode = if (reason.startsWith("c.")) reason else "c.inlineFunction.$reason",
    )
}

/** Rebuilds authority before the transaction and verifies both semantic images during diagnostics. */
internal class CManagedInlineFunctionContext(
    private val toolchain: ClangSemanticToolchain,
    private val manager: ExternalSemanticProcessManager,
) {
    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate {
        val lease = plan.authorityLease
        if (plan.status != PatchStatus.PREVIEW || !plan.requiresUserApproval || plan.operation != "inlineFunction" ||
            plan.evidence != RefactoringEvidence.COMPILER_PROVEN || plan.snapshotHash != before.hash || before.copy().hash != before.hash ||
            lease?.kind != "c.inline-function-int-expression-v1" || lease.operation != plan.operation || lease.snapshotHash != before.hash ||
            lease.workspaceEditSha256 != WorkspaceEditIdentity.sha256(plan.workspaceEdit)) return refused()
        val file: Path
        val function: String
        try {
            file = Path.of(requireNotNull(lease.attributes["c.file"]).also { require(it.length <= 4096) })
            function = requireNotNull(lease.attributes["c.functionName"]).also { require(it.length <= 128) }
            val rebuilt = CNativeInlineFunctionPlanner(toolchain, manager).preview(before, file, function)
            require(rebuilt.status == PatchStatus.PREVIEW && rebuilt.authorityLease == lease)
        } catch (_: Exception) { return refused() }
        val service = CFunctionFactsService(toolchain, manager)
        return DiagnosticsGate.enabled("clang-inline-function-v1") { candidate ->
            require(candidate.workspace.root.toAbsolutePath().normalize() == before.workspace.root.toAbsolutePath().normalize() &&
                candidate.copy().hash == candidate.hash)
            val isBefore = candidate.hash == before.hash
            require(isBefore || candidate.hash == lease.evidenceHash) { "c.inlineFunction.authorityInvalid: unapproved source image" }
            val result = if (isBefore) service.analyzeInlineFunction(candidate, file, function)
                else if (CCompilationSnapshotCapture.isCurrent(before)) service.analyzeInlineFunctionPreview(before, candidate, file, function)
                else service.analyzeInlineFunctionCandidate(candidate, file, function)
            when (result) {
                is CInlineFunctionFactsResult.Available -> {
                    val expected = lease.attributes[if (isBefore) "c.beforeSemanticSha256" else "c.afterSemanticSha256"]
                    require(result.snapshotHash == candidate.hash && result.facts.image.identity() == expected) {
                        "c.inlineFunction.authorityInvalid: compiler semantic image differs"
                    }
                    emptyList<Diagnostic>()
                }
                is CInlineFunctionFactsResult.Unavailable -> error("${result.diagnostic.code}: ${result.diagnostic.message}")
            }
        }
    }
    private fun refused() = DiagnosticsGate.enabled("clang-inline-function-v1") {
        error("c.inlineFunction.authorityInvalid: current compiler-bound preview and exact semantic images are required")
    }
}

private fun inlineSha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
