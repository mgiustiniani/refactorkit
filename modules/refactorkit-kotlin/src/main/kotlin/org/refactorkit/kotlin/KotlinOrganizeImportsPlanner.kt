package org.refactorkit.kotlin

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Compiler-counterfactual Kotlin/JVM import organization with bounded project layout. */
class KotlinOrganizeImportsPlanner(
    private val kotlin: KotlinLanguageAdapter,
) {
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = kotlin.diagnostics(snapshot)

    fun preview(snapshot: ProjectSnapshot, path: Path): PatchPlan {
        val normalizedPath = path.normalize()
        val source = snapshot.files.singleOrNull { it.path.normalize() == normalizedPath }
            ?: return refused(snapshot, "kotlin.organizeImportsFileMissing", "Kotlin organize imports target is absent")
        if (source.languageId != "kotlin" || source.path.toString().endsWith(".kts")) return refused(
            snapshot, "kotlin.organizeImportsFileUnsupported", "Kotlin organize imports requires one saved .kt file",
        )
        val ownership = snapshot.owningBuildSourceRoots(source.path)
        if (ownership.isEmpty() || ownership.map { it.root.normalize() }.distinct().size != 1 ||
            ownership.any { it.generated || it.modelStatus != BuildModelStatus.AVAILABLE }) return refused(
            snapshot, "kotlin.organizeImportsSourceOwnershipUnavailable",
            "Kotlin organize imports requires one authoritative non-generated source root",
        )
        val before = when (val result = kotlin.compilerDiagnostics(snapshot)) {
            is KotlinCompilerDiagnosticsResult.Available -> result
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.organizeImportsEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.organizeImportsEvidenceUnavailable", result.failure.message,
            )
        }
        if (before.symbolFailure != null || before.symbols == null ||
            before.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return refused(
            snapshot, "kotlin.organizeImportsBaselineIncomplete",
            "Kotlin organize imports requires complete error-free K2 declaration and usage evidence", before.diagnostics,
        )
        val block = importBlock(source) ?: return refused(
            snapshot, "kotlin.organizeImportsShapeUnsupported",
            "Kotlin organize imports requires one uncommented bounded import block",
        )
        if (block.imports.size > MAX_IMPORTS) return refused(
            snapshot, "kotlin.organizeImportsLimit",
            "Kotlin organize imports accepts at most $MAX_IMPORTS directives",
        )
        val style = when (val result = projectStyle(snapshot, source)) {
            is StyleResult.Available -> result.style
            is StyleResult.Refused -> return refused(snapshot, result.code, result.message)
        }
        val baselineFingerprint = semanticFingerprint(before, snapshot)
        val unused = linkedSetOf<ImportLine>()
        for (importLine in block.imports) {
            if (importLine.star) continue
            val removal = WorkspaceEdit(listOf(FileEdit.Modify(source.path, listOf(TextEdit(
                SourceRange(
                    TextEdits.positionForOffset(source.content, importLine.startOffset),
                    TextEdits.positionForOffset(source.content, importLine.endOffset),
                ),
                "",
            )))))
            val counterfactual = runCatching { WorkspaceEditSimulator.apply(snapshot, removal) }.getOrElse {
                return refused(snapshot, "kotlin.organizeImportsPreviewInvalid", it.message ?: "Invalid import preview")
            }
            when (val result = kotlin.compilerDiagnostics(counterfactual)) {
                is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                    snapshot, result.reason.code ?: "kotlin.organizeImportsCounterfactualUnavailable",
                    result.reason.message,
                )
                is KotlinCompilerDiagnosticsResult.Error -> return refused(
                    snapshot, result.failure.code ?: "kotlin.organizeImportsCounterfactualUnavailable",
                    result.failure.message,
                )
                is KotlinCompilerDiagnosticsResult.Available -> {
                    if (result.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) continue
                    if (result.symbolFailure != null || result.symbols == null) return refused(
                        snapshot, "kotlin.organizeImportsCounterfactualIncomplete",
                        "K2 did not return complete declaration and usage evidence after isolated import removal",
                        result.diagnostics,
                    )
                    if (semanticFingerprint(result, counterfactual) != baselineFingerprint) return refused(
                        snapshot, "kotlin.organizeImportsBindingSubstitution",
                        "Removing '${importLine.directive.trim()}' compiles but changes a compiler-resolved binding",
                        result.diagnostics,
                    )
                    unused += importLine
                }
            }
        }
        val retained = block.imports.filterNot { it in unused }
        val newline = if ("\r\n" in source.content) "\r\n" else "\n"
        val replacement = style.format(retained, newline)
        val original = source.content.substring(block.startOffset, block.endOffset)
        if (replacement == original) return refused(
            snapshot, "kotlin.organizeImportsNoChange", "Kotlin imports are already organized",
        )
        val edit = TextEdit(
            SourceRange(
                TextEdits.positionForOffset(source.content, block.startOffset),
                TextEdits.positionForOffset(source.content, block.endOffset),
            ),
            replacement,
        )
        val workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(source.path, listOf(edit))))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "kotlin.organizeImportsPreviewInvalid", it.message ?: "Invalid import preview")
        }
        val after = when (val result = kotlin.compilerDiagnostics(staged)) {
            is KotlinCompilerDiagnosticsResult.Available -> result
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.organizeImportsStagedEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.organizeImportsStagedEvidenceUnavailable", result.failure.message,
            )
        }
        if (after.symbolFailure != null || after.symbols == null ||
            after.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return refused(
            snapshot, "kotlin.organizeImportsDiagnosticsRegression",
            "Organized imports do not retain complete error-free K2 evidence", after.diagnostics,
        )
        if (semanticFingerprint(after, staged) != baselineFingerprint) return refused(
            snapshot, "kotlin.organizeImportsBindingSubstitution",
            "Organized imports change a compiler-resolved declaration or usage binding", after.diagnostics,
        )
        return PatchPlan(
            operation = "organizeImports", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.99, requiresUserApproval = true,
            summary = "Remove ${unused.size} compiler-proven unused Kotlin import(s) and format ${retained.size} retained import(s).",
            affectedFiles = workspaceEdit.affectedFiles(), workspaceEdit = workspaceEdit,
            diagnosticsBefore = before.diagnostics, diagnosticsAfterPreview = after.diagnostics,
            warnings = style.evidencePath?.let { listOf("Kotlin import layout is bound to $it") }.orEmpty(),
            riskLevel = RiskLevel.LOW, evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun semanticFingerprint(
        result: KotlinCompilerDiagnosticsResult.Available,
        snapshot: ProjectSnapshot,
    ): SemanticFingerprint {
        val files = snapshot.files.associateBy { it.path.normalize() }
        fun selection(path: Path, range: SourceRange): String {
            val source = files.getValue(path.normalize())
            val start = TextEdits.offsetOf(source.content, range.start)
            val end = TextEdits.offsetOf(source.content, range.end)
            return source.content.substring(start, end)
        }
        fun isImport(path: Path, range: SourceRange): Boolean {
            val source = files[path.normalize()] ?: return false
            return source.content.lineSequence().elementAtOrNull(range.start.line)
                ?.trimStart()?.startsWith("import ") == true
        }
        val symbols = requireNotNull(result.symbols).symbols.sortedBy { it.id.value }.map { symbol ->
            val declaration = result.declarations.getValue(symbol.id)
            listOf(
                symbol.id.value, symbol.name, symbol.kind.name, symbol.location.path.normalize().toString(),
                declaration.visibility.name, declaration.jvmIdentity, declaration.jvmOwner,
                declaration.jvmName, declaration.jvmDescriptor,
            ).joinToString("\u0000")
        }
        val internal = result.usages.filterNot { isImport(it.location.path, it.location.range) }.map {
            listOf(it.location.path.normalize(), it.targetId.value, selection(it.location.path, it.location.range))
                .joinToString("\u0000")
        }.sorted()
        val externalTypes = result.externalTypeUsages.filterNot {
            isImport(it.location.path, it.location.range)
        }.map {
            listOf(it.location.path.normalize(), it.jvmBinaryName, selection(it.location.path, it.location.range))
                .joinToString("\u0000")
        }.sorted()
        val externalCallables = result.externalCallableUsages.filterNot {
            isImport(it.location.path, it.location.range)
        }.map {
            listOf(
                it.location.path.normalize(), it.jvmOwner, it.callableName, it.jvmDescriptor,
                selection(it.location.path, it.location.range),
            ).joinToString("\u0000")
        }.sorted()
        return SemanticFingerprint(symbols, internal, externalTypes, externalCallables)
    }

    private fun projectStyle(snapshot: ProjectSnapshot, source: SourceFile): StyleResult {
        val target = snapshot.workspace.root.resolve(source.path).toAbsolutePath().normalize()
        val candidates = snapshot.classpathEvidence.filter {
            it.kind == ClasspathEvidenceKind.DECLARATION_FILE &&
                it.path.fileName?.toString() in STYLE_FILES
        }.mapNotNull { evidence ->
            val absolute = if (evidence.path.isAbsolute) evidence.path.normalize()
            else snapshot.workspace.root.resolve(evidence.path).toAbsolutePath().normalize()
            val parent = absolute.parent ?: return@mapNotNull null
            if (!target.startsWith(parent)) return@mapNotNull null
            StyleCandidate(evidence, absolute, parent.nameCount)
        }.sortedWith(compareByDescending<StyleCandidate> { it.depth }.thenBy { it.path.toString() })
        val editor = candidates.firstOrNull { it.path.fileName.toString() == ".editorconfig" }
        if (editor != null) return readStyleCandidate(editor) { text -> editorConfigStyle(text, editor.evidence.path) }
        val gradle = candidates.firstOrNull { it.path.fileName.toString() == "gradle.properties" }
        if (gradle != null) return readStyleCandidate(gradle) { text -> gradleStyle(text, gradle.evidence.path) }
        return StyleResult.Available(ImportStyle(listOf(LayoutToken.CatchAll), null))
    }

    private fun readStyleCandidate(
        candidate: StyleCandidate,
        parse: (String) -> StyleResult,
    ): StyleResult {
        if (Files.isSymbolicLink(candidate.path) ||
            !Files.isRegularFile(candidate.path, LinkOption.NOFOLLOW_LINKS)) return StyleResult.Refused(
            "kotlin.organizeImportsStyleStale", "Kotlin import style evidence is no longer a no-follow regular file",
        )
        val size = runCatching { Files.size(candidate.path) }.getOrNull()
            ?: return StyleResult.Refused("kotlin.organizeImportsStyleStale", "Kotlin import style evidence is unreadable")
        if (size > MAX_STYLE_BYTES) return StyleResult.Refused(
            "kotlin.organizeImportsStyleUnsupported", "Kotlin import style evidence exceeds $MAX_STYLE_BYTES bytes",
        )
        val actual = runCatching {
            ClasspathEvidence.fingerprint(candidate.path, ClasspathEvidenceKind.DECLARATION_FILE)
        }.getOrNull()
        if (actual != candidate.evidence.fingerprint) return StyleResult.Refused(
            "kotlin.organizeImportsStyleStale", "Kotlin import style evidence changed after the project snapshot",
        )
        val text = runCatching { Files.readString(candidate.path) }.getOrNull()
            ?: return StyleResult.Refused("kotlin.organizeImportsStyleStale", "Kotlin import style evidence is unreadable")
        if ('\u0000' in text) return StyleResult.Refused(
            "kotlin.organizeImportsStyleUnsupported", "Kotlin import style evidence contains an invalid NUL byte",
        )
        return parse(text)
    }

    private fun editorConfigStyle(text: String, path: Path): StyleResult {
        var applies = true
        val values = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                applies = line.substring(1, line.length - 1).trim() in SUPPORTED_KOTLIN_SECTIONS
                return@forEach
            }
            val key = line.substringBefore('=', "").trim()
            if (applies && key == "ij_kotlin_imports_layout") values += line.substringAfter('=').trim()
        }
        if (values.isEmpty()) return StyleResult.Available(ImportStyle(listOf(LayoutToken.CatchAll), path))
        if (values.size != 1) return StyleResult.Refused(
            "kotlin.organizeImportsStyleUnsupported", "Kotlin import layout must be declared exactly once",
        )
        val tokens = values.single().split(',').map(String::trim)
        if (tokens.size !in 1..MAX_LAYOUT_TOKENS || tokens.any(String::isEmpty)) return StyleResult.Refused(
            "kotlin.organizeImportsStyleUnsupported", "Kotlin import layout token count is invalid",
        )
        val parsed = tokens.map { token ->
            when {
                token == "*" -> LayoutToken.CatchAll
                token == "^" -> LayoutToken.Aliases
                PACKAGE_LAYOUT.matches(token) -> LayoutToken.Package(token.removeSuffix(".**"))
                else -> return StyleResult.Refused(
                    "kotlin.organizeImportsStyleUnsupported", "Unsupported Kotlin import layout token: $token",
                )
            }
        }
        if (parsed.count { it == LayoutToken.CatchAll } != 1 ||
            parsed.count { it == LayoutToken.Aliases } > 1 || parsed.distinct().size != parsed.size) {
            return StyleResult.Refused(
                "kotlin.organizeImportsStyleUnsupported", "Kotlin import layout requires unique tokens and one '*'",
            )
        }
        return StyleResult.Available(ImportStyle(parsed, path))
    }

    private fun gradleStyle(text: String, path: Path): StyleResult {
        val values = text.lineSequence().map(String::trim).filter {
            it.isNotEmpty() && !it.startsWith("#") && it.substringBefore('=', "").trim() == "kotlin.code.style"
        }.map { it.substringAfter('=').trim() }.toList()
        if (values.isEmpty()) return StyleResult.Available(ImportStyle(listOf(LayoutToken.CatchAll), path))
        if (values != listOf("official")) return StyleResult.Refused(
            "kotlin.organizeImportsStyleUnsupported", "Only exact kotlin.code.style=official is qualified",
        )
        return StyleResult.Available(ImportStyle(listOf(LayoutToken.CatchAll), path))
    }

    private fun importBlock(source: SourceFile): ImportBlock? {
        val packageMatch = Regex("(?m)^[ \\t]*package\\s+[A-Za-z_][A-Za-z0-9_.]*[ \\t]*$").find(source.content)
            ?: return null
        val matches = IMPORT_REGEX.findAll(source.content).toList()
        if (matches.isEmpty()) return null
        val lines = source.content.lineSequence().toList()
        if (lines.any { it.trimStart().startsWith("import ") && !IMPORT_LINE.matches(it) }) return null
        val aliases = matches.mapNotNull { it.groups[3]?.value }
        if (aliases.size != aliases.distinct().size || matches.any {
                it.groups[2]!!.value.endsWith(".*") && it.groups[3] != null
            }) return null
        val imports = matches.map { match ->
            val start = match.range.first
            val line = TextEdits.positionForOffset(source.content, start).line
            val identity = match.groups[2]!!.value
            ImportLine(
                match.groups[1]!!.value, identity, identity.endsWith(".*"), match.groups[3] != null,
                line, start, match.range.last + 1,
            )
        }
        val packageLine = TextEdits.positionForOffset(source.content, packageMatch.range.first).line
        if (imports.first().line <= packageLine ||
            lines.subList(packageLine + 1, imports.first().line).any { it.isNotBlank() }) return null
        val importsByLine = imports.associateBy(ImportLine::line)
        if ((imports.first().line..imports.last().line).any { line ->
                lines[line].isNotBlank() && line !in importsByLine
            }) return null
        val previous = lines.getOrNull(imports.first().line - 1)?.trimStart().orEmpty()
        val nextNonBlank = lines.drop(imports.last().line + 1).firstOrNull { it.isNotBlank() }
        if (previous.startsWith("//") || previous.startsWith("/*") ||
            nextNonBlank?.trimStart()?.let { it.startsWith("//") || it.startsWith("/*") } == true) return null
        return ImportBlock(imports, imports.first().startOffset, imports.last().endOffset)
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = "organizeImports", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = message, affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(), diagnosticsAfterPreview = diagnostics, warnings = listOf(message),
        riskLevel = RiskLevel.LOW, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = code,
    )

    private data class SemanticFingerprint(
        val symbols: List<String>,
        val internalUsages: List<String>,
        val externalTypeUsages: List<String>,
        val externalCallableUsages: List<String>,
    )

    private data class ImportBlock(
        val imports: List<ImportLine>,
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class ImportLine(
        val directive: String,
        val identity: String,
        val star: Boolean,
        val alias: Boolean,
        val line: Int,
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class StyleCandidate(
        val evidence: ClasspathEvidence,
        val path: Path,
        val depth: Int,
    )

    private sealed interface StyleResult {
        data class Available(val style: ImportStyle) : StyleResult
        data class Refused(val code: String, val message: String) : StyleResult
    }

    private sealed interface LayoutToken {
        data object CatchAll : LayoutToken
        data object Aliases : LayoutToken
        data class Package(val prefix: String) : LayoutToken
    }

    private data class ImportStyle(
        val layout: List<LayoutToken>,
        val evidencePath: Path?,
    ) {
        fun format(imports: List<ImportLine>, newline: String): String {
            val groups = layout.map { token ->
                imports.filter { line -> group(line) == token }.sortedBy { it.directive.trim() }
            }.filter(List<ImportLine>::isNotEmpty)
            return groups.joinToString(newline + newline) { group ->
                group.joinToString(newline) { it.directive }
            }
        }

        private fun group(line: ImportLine): LayoutToken {
            if (line.alias) layout.firstOrNull { it == LayoutToken.Aliases }?.let { return it }
            layout.filterIsInstance<LayoutToken.Package>().firstOrNull { token ->
                line.identity == token.prefix || line.identity.startsWith(token.prefix + ".")
            }?.let { return it }
            return layout.single { it == LayoutToken.CatchAll }
        }
    }

    companion object {
        private const val MAX_IMPORTS = 32
        private const val MAX_STYLE_BYTES = 64L * 1024L
        private const val MAX_LAYOUT_TOKENS = 16
        private val STYLE_FILES = setOf(".editorconfig", "gradle.properties")
        private val SUPPORTED_KOTLIN_SECTIONS = setOf("*.kt", "**.kt", "*.{kt,kts}")
        private val PACKAGE_LAYOUT = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*\\.\\*\\*")
        private val IMPORT_REGEX = Regex(
            "(?m)^([ \\t]*import\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+(?:\\.\\*)?)" +
                "(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?[ \\t]*)$",
        )
        private val IMPORT_LINE = Regex(
            "[ \\t]*import\\s+[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+(?:\\.\\*)?" +
                "(?:\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*)?[ \\t]*",
        )
    }
}
