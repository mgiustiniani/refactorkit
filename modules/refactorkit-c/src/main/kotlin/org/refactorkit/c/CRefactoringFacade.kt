package org.refactorkit.c

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Library-level C integration facade. Composes the bounded C planners and
 * exposes preview/apply through the core `PatchEngine`, preserving the local
 * safety gates (preview, diagnostics, approval, rollback).
 */
class CRefactoringFacade(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val compilerRuntimeProvider: ClangToolchainRuntimeProvider = ClangToolchainRuntimeCapture(),
) : AutoCloseable {
    private val rename = CRenamePlanner(toolchain, processManager)
    private val renamePrefix = CRenamePrefixPlanner(toolchain, processManager)
    private val move = CMovePlanner()
    private val format = CFormatter(toolchain, processManager)
    private val organizeIncludes = COrganizeIncludesPlanner()
    private val safeDelete = CSafeDeletePlanner(toolchain, processManager)
    private val signature = CSignaturePlanner(toolchain, processManager)
    private val inline = CInlinePlanner(toolchain, processManager)
    private val relocate = CRelocateComponentPlanner()
    private val compilerDiagnostics = CCompilerDiagnostics(toolchain, processManager, compilerRuntimeProvider)

    /**
     * Exact-version compiler diagnostics gate required by PatchEngine for managed C apply.
     *
     * This generic gate has no retained per-TU dialect and compiler-flag
     * authority. It refuses even when the project has no build file: invoking
     * Clang with its host-default dialect is not evidence for the project's
     * configured C11/C17 context. Operation-specific gates may apply only when
     * they rebuild and retain their own exact compiler evidence.
     */
    /**
     * A narrowly admitted, preview-bound CMake move gate. Unlike the generic C
     * gate this captures the exact compilation database at preview time and
     * rechecks it before, during, and after the managed transaction. Unsupported
     * contexts still fail closed; callers must retain this gate with the plan.
     */
    fun diagnosticsGate(snapshot: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate =
        if (plan.operation == "renameSymbol") {
            CManagedSymbolRenameContext(toolchain, processManager).gate(snapshot, plan)
        } else if (plan.operation == "renameCSymbolPrefix") {
            CManagedRenamePrefixContext(toolchain, processManager).gate(snapshot, plan)
        } else if (plan.operation == "extractExpression") {
            CManagedScalarContext(toolchain, processManager).gate(snapshot, plan)
        } else if (plan.operation == "inlineLocalVariable") {
            CManagedInlineLocalContext(toolchain, processManager).gate(snapshot, plan)
        } else if (plan.operation == "inlineFunction") {
            CManagedInlineFunctionContext(toolchain, processManager).gate(snapshot, plan)
        } else if (plan.operation == "extractStatements") {
            CManagedStatementContext(toolchain, processManager).gate(snapshot, plan)
        } else if (plan.operation in setOf("changeSignature", "reorderParameters", "addParameter")) {
            CManagedSignatureContext(toolchain, processManager).gate(snapshot, plan)
        } else if (plan.operation == "formatFile") {
            CManagedFormatContext(toolchain, compilerDiagnostics).gate(snapshot, plan)
        } else if (plan.operation == "safeDelete") {
            val symbol = plan.authorityLease?.attributes?.get("c.safeDelete.Symbol")
            CManagedFormatContext(toolchain, compilerDiagnostics, "safeDelete", processManager, symbol).gate(snapshot, plan)
        } else if (plan.operation == "organizeIncludes") {
            CManagedIncludeOrderContext(toolchain, compilerDiagnostics).gate(snapshot, plan)
        } else if (plan.operation in setOf("moveSource", "relocateComponent") &&
            plan.authorityLease?.kind == CManagedCMakeIncludeContext.LEASE_KIND) {
            CManagedCMakeIncludeContext(toolchain, processManager, compilerDiagnostics).gate(snapshot, plan)
        } else if (plan.operation in setOf("moveSource", "relocateComponent") && snapshot.auxiliaryFiles.any { it.path == Path.of("CMakeLists.txt") } &&
            Files.exists(snapshot.workspace.root.toAbsolutePath().normalize().resolve("compile_commands.json"), LinkOption.NOFOLLOW_LINKS)) {
            CManagedCMakeContext(toolchain, compilerDiagnostics).gate(snapshot, plan)
        } else diagnosticsGate()

    fun diagnosticsGate(): DiagnosticsGate = DiagnosticsGate.enabled("clang-exact-v1") { _ ->
        error("c.compilationContextUnavailable: no exact snapshot-bound C standard and per-translation-unit compiler flags are connected to the generic managed diagnostics gate")
    }

    /**
     * Starts only the clangd-backed C planners required by [operation].
     *
     * Operations that do not consult clangd never start the persistent semantic
     * client. Compiler-backed extract/inline previews may launch bounded one-shot
     * Clang AST processes against captured source and build inputs.
     */
    fun startFor(snapshot: ProjectSnapshot, operation: String) {
        val starters: List<Pair<String, (ProjectSnapshot) -> Unit>> = when (operation) {
            "renameSymbol" -> listOf("rename" to rename::start)
            "renamePrefix" -> listOf("renamePrefix" to renamePrefix::start)
            "safeDelete" -> listOf("safeDelete" to safeDelete::start)
            "changeSignature" -> listOf("signature" to signature::start)
            else -> emptyList()
        }
        var started = 0
        try {
            starters.forEach { (_, start) -> start(snapshot); started++ }
        } catch (failure: Throwable) {
            closeStarted(starters.take(started).map { it.first })
            throw failure
        }
    }

    /** Installs a bounded process-response seam for module test fixtures only. */
    internal fun useFormatterProcessTransportForTest(transport: CFormatProcessTransport) =
        format.useProcessTransportForTest(transport)

    /** Observes a real formatter launch in a module test fixture without changing its result. */
    internal fun observeFormatterProcessForTest(observer: (org.refactorkit.core.SemanticProcessSpec) -> Unit) =
        format.observeProcessForTest(observer)

    /** Starts every clangd-backed C planner against the snapshot, closing them all on failure. */
    fun start(snapshot: ProjectSnapshot) {
        val starters = listOf(
            "rename" to rename::start,
            "renamePrefix" to renamePrefix::start,
            "safeDelete" to safeDelete::start,
            "signature" to signature::start,
        )
        var started = 0
        try {
            starters.forEach { (_, start) -> start(snapshot); started++ }
        } catch (failure: Throwable) {
            closeStarted(starters.take(started).map { it.first })
            throw failure
        }
    }

    private fun closeStarted(names: List<String>) {
        names.forEach { name ->
            runCatching {
                when (name) {
                    "rename" -> rename.close()
                    "renamePrefix" -> renamePrefix.close()
                    "safeDelete" -> safeDelete.close()
                    "signature" -> signature.close()
                }
            }
        }
    }

    fun preview(snapshot: ProjectSnapshot, operation: String, args: Map<String, String>): PatchPlan {
        if ("forwardHeaders" in args && operation != "relocateComponent") {
            return refusedDiagnostic(snapshot, operation, "c.cmakeInclude.forwardingAuthorityUnavailable",
                "Forwarding headers require component relocation")
        }
        val file = args["file"]?.let(Path::of)
        return when (operation) {
            "renameSymbol" -> {
                val symbol = args["symbol"] ?: return missingArgument(snapshot, operation, "arguments.symbol")
                val newName = args["newName"] ?: return missingArgument(snapshot, operation, "arguments.newName")
                val selectedLocation = if (listOf("file", "line", "character").any(args::containsKey)) {
                    resolveSelectedToken(snapshot, symbol, args)
                } else {
                    resolveSymbol(snapshot, symbol)
                }
                val resolved = selectedLocation
                when (resolved) {
                    is SymbolResolution.Found -> {
                        val tagNamespace = CManagedTagRenameContext(toolchain, processManager).namespacePreflight(snapshot,
                            resolved.location.file, resolved.location.line, resolved.location.character, newName)
                        val possibleCollision = hasPotentialRenameTargetCollision(snapshot, newName)
                        val typedefNamespace = if (CManagedTypedefRenameContext.isTypedefSelection(snapshot, args))
                            CManagedTypedefRenameContext(toolchain, processManager).namespacePreflight(snapshot,
                                resolved.location.file, resolved.location.line, resolved.location.character, newName)
                        else CTypedefNamespacePreflight.OutsideTypedefSelection
                        if (typedefNamespace is CTypedefNamespacePreflight.Refused) {
                            refused(snapshot, operation, listOf(typedefNamespace.diagnostic)).copy(
                                refusalCode = typedefNamespace.diagnostic.code,
                                evidence = if (typedefNamespace.diagnostic.evidence == DiagnosticEvidence.COMPILER)
                                    RefactoringEvidence.COMPILER_PROVEN else RefactoringEvidence.STRUCTURAL,
                                diagnosticsAfterPreview = listOf(typedefNamespace.diagnostic),
                            )
                        } else if (tagNamespace is CTagNamespacePreflight.Refused) {
                            refused(snapshot, operation, listOf(tagNamespace.diagnostic)).copy(
                                refusalCode = tagNamespace.diagnostic.code,
                                evidence = RefactoringEvidence.COMPILER_PROVEN,
                                diagnosticsAfterPreview = listOf(tagNamespace.diagnostic),
                            )
                        } else if (tagNamespace != CTagNamespacePreflight.CapturedTagDestination &&
                            typedefNamespace != CTypedefNamespacePreflight.CapturedTypedefDestination && possibleCollision) {
                            refused(snapshot, operation, listOf(Diagnostic(
                                message = "Rename target '$newName' already occurs as a C-family identifier in the captured workspace; refusing a possible collision or lookup change",
                                severity = Diagnostic.Severity.ERROR,
                                code = "c.renameTargetIdentifierPresent",
                                evidence = DiagnosticEvidence.STRUCTURAL,
                                category = DiagnosticCategory.SAFETY,
                            ))).copy(refusalCode = "c.renameTargetIdentifierPresent")
                        } else {
                            val plan = rename.rename(resolved.location.file, resolved.location.line, resolved.location.character, newName, snapshot)
                                .toPlan(snapshot, "renameSymbol")
                            if (plan.status == PatchStatus.REFUSED && plan.refusalCode == "clangd.refused" &&
                                typedefNamespace == CTypedefNamespacePreflight.CapturedTypedefDestination) {
                                try { CManagedTypedefRenameContext(toolchain, processManager).compilerNamespacePreview(snapshot, args) }
                                catch (failure: Exception) {
                                    plan.copy(warnings = plan.warnings + "Compiler typedef closure unavailable: ${failure.message}")
                                }
                            } else CManagedSymbolRenameContext(toolchain, processManager).preview(snapshot, plan, args)
                        }
                    }
                    is SymbolResolution.Refused -> refused(snapshot, "renameSymbol", resolved.diagnostics)
                        .copy(refusalCode = resolved.diagnostics.firstOrNull()?.code)
                }
            }
            "renamePrefix" -> previewPrefix(snapshot, parseMapping(args), startSemanticSession = false)
            "moveSource" -> {
                val plan = move.preview(snapshot,
                    file ?: return missingArgument(snapshot, operation, "arguments.file"),
                    Path.of(args["target"] ?: return missingArgument(snapshot, operation, "arguments.target")))
                if (plan.status == PatchStatus.PREVIEW &&
                    snapshot.auxiliaryFiles.any { it.path == Path.of("CMakeLists.txt") } &&
                    Files.exists(snapshot.workspace.root.toAbsolutePath().normalize().resolve("compile_commands.json"), LinkOption.NOFOLLOW_LINKS)) {
                    cmakeMovePreview(snapshot, plan, requireNotNull(file), Path.of(requireNotNull(args["target"])))
                } else plan
            }
            "formatFile" -> {
                val formatFile = file ?: return missingArgument(snapshot, operation, "arguments.file")
                val rawStartLine = args["startLine"]
                val rawEndLine = args["endLine"]
                val rawRanges = args["ranges"]
                if (rawRanges == null && rawStartLine == null && rawEndLine == null) {
                    format.formatWholeFile(snapshot, formatFile).toPlan(snapshot, "formatFile", formatFile)
                        .let { plan -> if (plan.status == PatchStatus.PREVIEW) CManagedFormatContext(toolchain, compilerDiagnostics).preview(snapshot, plan) else plan }
                } else {
                    fun lineIndex(raw: String?): Int? = raw?.toIntOrNull()?.takeIf {
                        it >= 0 && it.toString() == raw
                    }
                    val ranges = when {
                        rawRanges != null && rawStartLine == null && rawEndLine == null -> parseFormatRanges(rawRanges)
                        rawRanges == null -> {
                            val startLine = lineIndex(rawStartLine)
                            val endLine = lineIndex(rawEndLine)
                            if (startLine != null && endLine != null && startLine <= endLine) {
                                listOf(SourceRange(SourcePosition(startLine, 0), SourcePosition(endLine, 0)))
                            } else null
                        }
                        else -> null
                    }
                    val root = snapshot.workspace.root.toAbsolutePath().normalize()
                    val selectedPath = if (formatFile.isAbsolute) formatFile.toAbsolutePath().normalize()
                    else root.resolve(formatFile).normalize()
                    val captured = snapshot.files.singleOrNull {
                        root.resolve(it.path).normalize() == selectedPath
                    }
                    val lineCount = captured?.content?.let { content ->
                        if (content.isEmpty()) 0
                        else content.count { it == '\n' } + if (content.endsWith('\n')) 0 else 1
                    }
                    if (ranges == null || (lineCount != null && ranges.any { it.end.line >= lineCount })) {
                        refusedDiagnostic(
                            snapshot, operation, "clang.formatInvalidRange",
                            "Formatting requires one canonical zero-based inclusive startLine/endLine pair or up to 32 ordered, disjoint ranges encoded as start:end,start:end within the captured source",
                        ).copy(refusalCode = "clang.formatInvalidRange")
                    } else {
                        format.formatRanges(snapshot, formatFile, ranges)
                            .toPlan(snapshot, "formatFile", formatFile)
                            .let { plan -> if (plan.status == PatchStatus.PREVIEW) CManagedFormatContext(toolchain, compilerDiagnostics).preview(snapshot, plan) else plan }
                    }
                }
            }
            "organizeIncludes" -> {
                val plan = organizeIncludes.preview(snapshot, file ?: return missingArgument(snapshot, operation, "arguments.file"))
                when {
                    plan.status == PatchStatus.PREVIEW -> CManagedIncludeOrderContext(toolchain, compilerDiagnostics).preview(snapshot, plan)
                    plan.status == PatchStatus.REFUSED && plan.refusalCode == null ->
                        plan.copy(refusalCode = "c.includeOrder.refused")
                    else -> plan
                }
            }
            "safeDelete" -> {
                val symbol = args["symbol"] ?: return missingArgument(snapshot, operation, "arguments.symbol")
                val plan = safeDelete.preview(snapshot, symbol)
                if (plan.status == PatchStatus.PREVIEW) {
                    CManagedFormatContext(toolchain, compilerDiagnostics, "safeDelete", processManager, symbol).preview(snapshot, plan)
                } else plan
            }
            "changeSignature" -> signature.renameParameter(
                snapshot,
                file ?: return missingArgument(snapshot, operation, "arguments.file"),
                args["symbol"] ?: return missingArgument(snapshot, operation, "arguments.symbol"),
                args["oldParam"] ?: return missingArgument(snapshot, operation, "arguments.oldParam"),
                args["newParam"] ?: return missingArgument(snapshot, operation, "arguments.newParam"),
            )
            "reorderParameters" -> {
                val mapping = args["originalIndices"] ?: return missingArgument(snapshot, operation, "arguments.originalIndices")
                fun invalidMapping(): PatchPlan = refused(snapshot, operation, listOf(Diagnostic(
                    "arguments.originalIndices must contain a bounded comma-separated original-index mapping",
                    Diagnostic.Severity.ERROR, code = "c.signatureReorder.invalid_mapping",
                ))).copy(refusalCode = "c.signatureReorder.invalid_mapping")
                if (mapping.length > 255) return invalidMapping()
                val order = mapping.split(',').map { it.toIntOrNull() ?: return invalidMapping() }
                signature.reorderParameters(snapshot, file ?: return missingArgument(snapshot, operation, "arguments.file"),
                    args["symbol"] ?: return missingArgument(snapshot, operation, "arguments.symbol"), order)
            }
            "addParameter" -> {
                for (key in listOf("file", "symbol", "parameterName", "parameterOrdinal", "callerExpression")) {
                    if (args[key] == null) return missingArgument(snapshot, operation, "arguments.$key").copy(refusalCode = "c.missingArgument")
                }
                val ordinal = args.getValue("parameterOrdinal").toIntOrNull()
                    ?: return refusedDiagnostic(snapshot, operation, "c.signatureAdd.invalid_position", "arguments.parameterOrdinal must be an integer")
                        .copy(refusalCode = "c.signatureAdd.invalid_position")
                signature.addParameter(snapshot, requireNotNull(file), args.getValue("symbol"), args.getValue("parameterName"),
                    ordinal, args.getValue("callerExpression"))
            }
            "extractStatements" -> {
                for (key in listOf("file", "symbol", "helperName", "startByte", "endByte")) {
                    if (args[key] == null) return missingArgument(snapshot, operation, "arguments.$key").copy(refusalCode = "c.missingArgument")
                }
                fun offset(key: String): Int? = args.getValue(key).takeIf { it.length <= 10 }?.let { raw ->
                    raw.toIntOrNull()?.takeIf { it >= 0 && it.toString() == raw }
                }
                val start = offset("startByte")
                val end = offset("endByte")
                if (start == null || end == null || end <= start) {
                    return refusedDiagnostic(snapshot, operation, "c.statementExtract.invalid_selection",
                        "arguments.startByte/endByte must identify a nonempty UTF-8 byte extent with canonical nonnegative integers")
                        .copy(refusalCode = "c.statementExtract.invalid_selection")
                }
                CStatementExtractionPlanner(toolchain, processManager).preview(snapshot, requireNotNull(file), args.getValue("symbol"),
                    CFunctionByteSpan(start, end), args.getValue("helperName"))
            }
            "extractExpression" -> {
                val selectedFile = file ?: return missingArgument(snapshot, operation, "arguments.file")
                val source = snapshot.files.singleOrNull { it.path.normalize() == selectedFile.normalize() }
                    ?: return refusedDiagnostic(snapshot, operation, "c.scalarExtract.fileUnavailable", "arguments.file must identify a captured source")
                        .copy(refusalCode = "c.scalarExtract.fileUnavailable")
                val selection = try {
                    val range = parseRange(args) ?: return invalidRange(snapshot, operation)
                    val start = TextEdits.offsetOf(source.content, range.start)
                    val end = TextEdits.offsetOf(source.content, range.end)
                    require(start < end)
                    for (offset in listOf(start, end)) {
                        require(offset == 0 || offset == source.content.length ||
                            !(source.content[offset].isLowSurrogate() && source.content[offset - 1].isHighSurrogate()))
                    }
                    CFunctionByteSpan(source.content.substring(0, start).toByteArray(Charsets.UTF_8).size,
                        source.content.substring(0, end).toByteArray(Charsets.UTF_8).size)
                } catch (_: IllegalArgumentException) { return invalidRange(snapshot, operation) }
                CScalarExtractionPlanner(toolchain, processManager).preview(snapshot, selectedFile, selection,
                    args["tempName"] ?: "tmp", args["symbol"])
            }
            "inlineFunction" -> CNativeInlineFunctionPlanner(toolchain, processManager).preview(snapshot,
                file ?: return missingArgument(snapshot, operation, "arguments.file"),
                args["symbol"] ?: return missingArgument(snapshot, operation, "arguments.symbol"))
            "inlineLocalVariable" -> inline.inlineLocal(snapshot, file ?: return missingArgument(snapshot, operation, "arguments.file"), args["symbol"] ?: return missingArgument(snapshot, operation, "arguments.symbol"))
            "relocateComponent" -> {
                val componentDir = args["componentDir"]?.let(Path::of)
                    ?: return missingArgument(snapshot, operation, "arguments.componentDir")
                val newDir = args["newDir"]?.let(Path::of)
                    ?: return missingArgument(snapshot, operation, "arguments.newDir")
                val mapping = parseMapping(args)
                val forwarding = args["forwardHeaders"]?.let { raw ->
                    val values = raw.split(',')
                    if (values.any { !it.matches(Regex("[A-Za-z_0-9./-]{1,512}")) }) {
                        return refuseComponentForwarding(snapshot, "Forwarding requires explicit workspace-relative header paths")
                    }
                    values.map(Path::of).also { paths ->
                        if (paths.any { it.isAbsolute || it != it.normalize() || it.startsWith("..") }) {
                            return refuseComponentForwarding(snapshot, "Forwarding requires normalized workspace-relative header paths")
                        }
                    }
                }.orEmpty()
                val plan = relocate.preview(snapshot, componentDir, newDir)
                if (plan.status != PatchStatus.PREVIEW) return plan
                if (snapshot.auxiliaryFiles.any { it.path == Path.of("CMakeLists.txt") } &&
                    Files.exists(snapshot.workspace.root.toAbsolutePath().normalize().resolve("compile_commands.json"), LinkOption.NOFOLLOW_LINKS)) {
                    cmakeMovePreview(snapshot, plan, componentDir, newDir, mapping, forwarding)
                } else if (forwarding.isNotEmpty()) {
                    refuseComponentForwarding(snapshot, "Forwarding headers require captured CMake and compilation inputs")
                } else if (mapping.isEmpty()) {
                    plan
                } else {
                    refuseComponentPrefix(snapshot, "Component API mapping requires captured CMake and compilation inputs")
                }
            }
            else -> unknownOperation(snapshot, operation)
        }
    }

    private fun cmakeMovePreview(snapshot: ProjectSnapshot, plan: PatchPlan, from: Path, to: Path,
        mapping: Map<String, String> = emptyMap(), forwarding: List<Path> = emptyList()): PatchPlan {
        val result = CManagedCMakeIncludeContext(toolchain, processManager, compilerDiagnostics)
            .preview(snapshot, plan, from, to, mapping, forwarding)
        if (result is CMakeIncludePreview.Available) return result.plan
        if (forwarding.isNotEmpty()) {
            return refuseComponentForwarding(snapshot, (result as? CMakeIncludePreview.Unavailable)?.diagnostic?.message
                ?: "Forwarding headers require qualified include roots and compilation inputs")
        }
        if (mapping.isNotEmpty()) {
            return refuseComponentPrefix(snapshot, (result as? CMakeIncludePreview.Unavailable)?.diagnostic?.message
                ?: "Component API mapping requires qualified include roots and compilation inputs")
        }
        val previous = if (plan.operation == "relocateComponent")
            CManagedCMakeContext(toolchain, compilerDiagnostics).preview(snapshot, plan, from, to)
        else CManagedCMakeContext(toolchain, compilerDiagnostics).preview(snapshot, plan)
        return if (result is CMakeIncludePreview.Unavailable) previous.copy(
            warnings = previous.warnings + "${result.diagnostic.code}: ${result.diagnostic.message}") else previous
    }

    private fun refuseComponentForwarding(snapshot: ProjectSnapshot, message: String): PatchPlan {
        val code = "c.cmakeInclude.forwardingAuthorityUnavailable"
        return refusedDiagnostic(snapshot, "relocateComponent", code, message).copy(refusalCode = code)
    }

    private fun refuseComponentPrefix(snapshot: ProjectSnapshot, message: String): PatchPlan {
        val code = "c.cmakeInclude.prefixAuthorityUnavailable"
        return refusedDiagnostic(snapshot, "relocateComponent", code, message).copy(refusalCode = code)
    }

    /** Preview through the bounded managed-apply authority required by editor surfaces. */
    fun previewForManagedApply(snapshot: ProjectSnapshot, operation: String, args: Map<String, String>): PatchPlan {
        if (operation == "renamePrefix") {
            return previewPrefix(snapshot, parseMapping(args), startSemanticSession = true)
        }
        startFor(snapshot, operation)
        return preview(snapshot, operation, args)
    }

    private fun previewPrefix(snapshot: ProjectSnapshot, mapping: Map<String, String>, startSemanticSession: Boolean): PatchPlan {
        val context = CManagedRenamePrefixContext(toolchain, processManager)
        context.preflight(snapshot, mapping)?.let { return it }
        if (startSemanticSession) startFor(snapshot, "renamePrefix")
        return context.preview(snapshot, renamePrefix.preview(snapshot, mapping), mapping)
    }

    /**
     * Applies a previewed C plan through the central PatchEngine.
     *
     * Approval comes from the calling surface (CLI/daemon/LSP/MCP), never
     * fabricated inside the adapter. Diagnostic authority is selected here from
     * the operation, snapshot and plan. A caller-created clean gate is not
     * evidence and cannot authorize a C write.
     */
    fun apply(
        snapshot: ProjectSnapshot,
        plan: PatchPlan,
        authorization: ApplyAuthorization,
        @Suppress("UNUSED_PARAMETER")
        diagnosticsGate: DiagnosticsGate,
    ): ApplyResult {
        val selectedGate = this.diagnosticsGate(snapshot, plan)
        return PatchEngine(snapshot.workspace.root).apply(plan, snapshot, authorization, selectedGate)
    }

    override fun close() {
        rename.close()
        renamePrefix.close()
        safeDelete.close()
        signature.close()
    }

    private sealed interface SymbolResolution {
        data class Found(val location: Location) : SymbolResolution
        data class Refused(val diagnostics: List<Diagnostic>) : SymbolResolution
    }

    /**
     * Resolves a symbol to a single binding location, refusing ambiguity.
     *
     * A name that resolves to distinct declarations in more than one file is
     * ambiguous (same-name/shadowing risk): the rename must not guess the first
     * textual occurrence. Absent names are refused explicitly instead of throwing.
     */
    private fun resolveSymbol(snapshot: ProjectSnapshot, symbol: String): SymbolResolution {
        val candidates = mutableListOf<Location>()
        for (file in snapshot.files.filter { it.languageId in setOf("c", "cpp", "objective-c") }.sortedBy { it.path.toString() }) {
            val tokens = CTokenizer().tokenize(file.content)
            for (token in tokens) {
                if (token.type != CTokenType.IDENTIFIER || token.text != symbol) continue
                val line = token.line - 1
                // Use the token's own column; a first-substring indexOf(symbol) would
                // seed into an earlier identifier that merely contains the symbol name.
                val char = token.column
                candidates += Location(file.path.normalize(), line, char)
            }
        }
        val distinctFiles = candidates.map { it.file }.distinct()
        return when {
            candidates.isEmpty() -> SymbolResolution.Refused(listOf(Diagnostic(
                message = "Symbol '$symbol' has no definition in this snapshot",
                severity = Diagnostic.Severity.ERROR,
                code = "c.renameSymbolNotFound",
                evidence = DiagnosticEvidence.STRUCTURAL,
                category = DiagnosticCategory.TYPE_RESOLUTION,
            )))
            distinctFiles.size > 1 -> SymbolResolution.Refused(listOf(Diagnostic(
                message = "Symbol '$symbol' is ambiguous across ${distinctFiles.size} files; rename is refused",
                severity = Diagnostic.Severity.ERROR,
                code = "c.renameSymbolAmbiguous",
                evidence = DiagnosticEvidence.STRUCTURAL,
                category = DiagnosticCategory.TYPE_RESOLUTION,
            )))
            else -> SymbolResolution.Found(candidates.first())
        }
    }

    /**
     * Resolves an explicit workspace-relative token selection without guessing
     * which same-spelled occurrence the caller intended. The selected lexical
     * token only seeds clangd; clangd remains responsible for binding-matched
     * edits and the normalizer still constrains those edits to this snapshot.
     */
    private fun resolveSelectedToken(
        snapshot: ProjectSnapshot,
        symbol: String,
        args: Map<String, String>,
    ): SymbolResolution {
        fun refuse(message: String) = SymbolResolution.Refused(listOf(Diagnostic(
            message = message,
            severity = Diagnostic.Severity.ERROR,
            code = "c.renameSelectionInvalid",
            evidence = DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.SAFETY,
        )))

        val pathText = args["file"] ?: return refuse("Exact C rename selection requires arguments.file")
        val lineText = args["line"] ?: return refuse("Exact C rename selection requires arguments.line")
        val characterText = args["character"] ?: return refuse("Exact C rename selection requires arguments.character")
        val selectedPath = runCatching { Path.of(pathText) }.getOrNull()
            ?: return refuse("arguments.file is not a valid workspace-relative C source path")
        val normalizedPath = selectedPath.normalize()
        if (selectedPath.isAbsolute || normalizedPath.toString() == "." || normalizedPath.startsWith("..")) {
            return refuse("arguments.file must be a safe workspace-relative C source path")
        }
        fun canonicalIndex(raw: String): Int? = raw.toIntOrNull()?.takeIf { it >= 0 && it.toString() == raw }
        val line = canonicalIndex(lineText) ?: return refuse("arguments.line must be a canonical nonnegative zero-based line")
        val character = canonicalIndex(characterText)
            ?: return refuse("arguments.character must be a canonical nonnegative zero-based character")
        val source = snapshot.files.singleOrNull {
            it.path.normalize() == normalizedPath && it.languageId in setOf("c", "cpp", "objective-c")
        } ?: return refuse("arguments.file must identify one captured C-family source")
        val selectedToken = CTokenizer().tokenize(source.content).singleOrNull { token ->
            token.type == CTokenType.IDENTIFIER && token.text == symbol &&
                token.line - 1 == line && token.column == character
        } ?: return refuse("The exact selection must identify the requested C identifier token '$symbol'")
        return SymbolResolution.Found(Location(source.path.normalize(), selectedToken.line - 1, selectedToken.column))
    }

    private fun parseMapping(args: Map<String, String>): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        for ((k, v) in args) {
            if (k.startsWith("rename.")) mapping[k.removePrefix("rename.")] = v
        }
        return mapping
    }

    private fun parseFormatRanges(raw: String): List<SourceRange>? {
        if (raw.isEmpty() || raw.length > 512) return null
        val pairs = raw.split(',')
        if (pairs.size !in 1..32) return null
        val ranges = pairs.map { pair ->
            val endpoints = pair.split(':')
            if (endpoints.size != 2) return null
            fun canonicalIndex(value: String): Int? = value.toIntOrNull()?.takeIf {
                it >= 0 && it.toString() == value
            }
            val start = canonicalIndex(endpoints[0]) ?: return null
            val end = canonicalIndex(endpoints[1]) ?: return null
            if (start > end) return null
            SourceRange(SourcePosition(start, 0), SourcePosition(end, 0))
        }
        if (ranges.zipWithNext().any { (left, right) -> left.end.line >= right.start.line }) return null
        return ranges
    }

    private fun parseRange(args: Map<String, String>): SourceRange? {
        val startLine = args["startLine"]?.toIntOrNull() ?: return null
        val startChar = args["startChar"]?.toIntOrNull() ?: return null
        val endLine = args["endLine"]?.toIntOrNull() ?: return null
        val endChar = args["endChar"]?.toIntOrNull() ?: return null
        return SourceRange(SourcePosition(startLine, startChar), SourcePosition(endLine, endChar))
    }

    private fun missingArgument(snapshot: ProjectSnapshot, operation: String, argument: String) =
        refusedDiagnostic(snapshot, operation, "c.missingArgument", "Missing $argument")

    private fun invalidRange(snapshot: ProjectSnapshot, operation: String) =
        refusedDiagnostic(snapshot, operation, "c.invalidRange", "Invalid or missing extract range arguments")
            .copy(refusalCode = "c.invalidRange")

    private fun unknownOperation(snapshot: ProjectSnapshot, operation: String) =
        refusedDiagnostic(snapshot, operation, "c.unknownOperation", "Unknown C operation: $operation")

    private fun refusedDiagnostic(snapshot: ProjectSnapshot, operation: String, code: String, message: String) =
        refused(snapshot, operation, listOf(Diagnostic(
            message = message,
            severity = Diagnostic.Severity.ERROR,
            code = code,
            evidence = DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.SAFETY,
        )))

    /**
     * Clangd may return a rename edit even when the new spelling already exists
     * and would collide or change lookup in the affected C code. The collision
     * policy excludes only direct tag-name tokens in C outside preprocessing
     * directives; ordinary and uncertain occurrences still refuse
     * conservatively. The tokenizer excludes comments and string/character
     * literals, and this check never produces rename edits.
     */
    private fun hasPotentialRenameTargetCollision(snapshot: ProjectSnapshot, newName: String): Boolean {
        return snapshot.files.asSequence()
            .filter { it.languageId in setOf("c", "cpp", "objective-c") }
            .any { source -> CTargetNameCollisionPolicy.hasPotentialConflict(source.languageId, source.content, newName) }
    }

    private data class Location(val file: Path, val line: Int, val character: Int)

    private fun CRenamePlannerResult.toPlan(snapshot: ProjectSnapshot, operation: String): PatchPlan = when (this) {
        is CRenamePlannerResult.Accepted -> PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "C rename preview",
            affectedFiles = normalized.workspaceEdit.affectedFiles(),
            workspaceEdit = normalized.workspaceEdit,
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = emptyList(),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.COMPILER_PROVEN,
        )
        is CRenamePlannerResult.Refused -> refused(snapshot, operation, diagnostics)
            .copy(refusalCode = diagnostics.firstOrNull()?.code)
    }

    private fun CFormatResult.toPlan(snapshot: ProjectSnapshot, operation: String, file: Path): PatchPlan = when (this) {
        is CFormatResult.Accepted -> {
            val relFile = snapshot.workspace.root.relativize(snapshot.workspace.root.resolve(file).normalize()).normalize()
            val workspaceEdit = if (edits.isEmpty()) WorkspaceEdit(emptyList())
            else WorkspaceEdit(listOf(FileEdit.Modify(relFile, edits)))
            PatchPlan(
                operation = operation,
                status = PatchStatus.PREVIEW,
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                requiresUserApproval = true,
                summary = if (edits.isEmpty()) "C format preview (no changes)" else "C format preview",
                affectedFiles = workspaceEdit.affectedFiles(),
                workspaceEdit = workspaceEdit,
                diagnosticsBefore = emptyList(),
                diagnosticsAfterPreview = emptyList(),
                warnings = listOf("Formatting edits are idempotent=$idempotent."),
                riskLevel = RiskLevel.MEDIUM,
                evidence = RefactoringEvidence.COMPILER_PROVEN,
            )
        }
        is CFormatResult.Refused -> refused(snapshot, operation, diagnostics)
            .copy(refusalCode = diagnostics.firstOrNull()?.code)
    }

    private fun refused(snapshot: ProjectSnapshot, operation: String, diagnostics: List<Diagnostic>) = PatchPlan(
        operation = operation,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = diagnostics.joinToString("; ") { it.message },
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = diagnostics.map { it.message },
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
    )
}
