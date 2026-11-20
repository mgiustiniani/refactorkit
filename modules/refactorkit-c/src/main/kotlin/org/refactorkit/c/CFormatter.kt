package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.OwnedWorkspaceTextReadout
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardOpenOption
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** C formatting result: accepted edits (idempotent) or an explicit refusal. */
sealed interface CFormatResult {
    class Accepted(edits: List<TextEdit>, val idempotent: Boolean) : CFormatResult {
        val edits: List<TextEdit> = immutableCFormatList(edits)

        operator fun component1(): List<TextEdit> = edits
        operator fun component2(): Boolean = idempotent

        fun copy(edits: List<TextEdit> = this.edits, idempotent: Boolean = this.idempotent) =
            Accepted(edits, idempotent)

        override fun equals(other: Any?): Boolean = other === this ||
            other is Accepted && edits == other.edits && idempotent == other.idempotent

        override fun hashCode(): Int = 31 * edits.hashCode() + idempotent.hashCode()

        override fun toString(): String = "Accepted(edits=$edits, idempotent=$idempotent)"
    }

    class Refused(diagnostics: List<Diagnostic>) : CFormatResult {
        val diagnostics: List<Diagnostic> = immutableCFormatList(diagnostics)

        operator fun component1(): List<Diagnostic> = diagnostics

        fun copy(diagnostics: List<Diagnostic> = this.diagnostics) = Refused(diagnostics)

        override fun equals(other: Any?): Boolean = other === this ||
            other is Refused && diagnostics == other.diagnostics

        override fun hashCode(): Int = diagnostics.hashCode()

        override fun toString(): String = "Refused(diagnostics=$diagnostics)"
    }
}

private fun <T> immutableCFormatList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/**
 * Bounded clang-format formatter for acquired C source content.
 *
 * Formats a whole file or a bounded, ordered set of disjoint ranges, producing
 * minimal edits that preserve unchanged content. Both formatting passes receive
 * text through stdin; the original workspace path is only an assumed filename.
 * Idempotence is demonstrated
 * by re-formatting the proposed output without creating scratch files. Formatting
 * never implicitly organizes includes. Workspace style is selected from snapshot-
 * bound auxiliary evidence and checked around both native passes.
 */
class CFormatter(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val style: String? = null,
) {
    private var testTransport: CFormatProcessTransport? = null
    private var testProcessObserver: ((SemanticProcessSpec) -> Unit)? = null

    /** Test-only transport injection; kept internal and available only to the module's test fixtures. */
    internal fun useProcessTransportForTest(transport: CFormatProcessTransport) {
        testTransport = transport
    }

    /** Observes the real native process request without replacing its execution. */
    internal fun observeProcessForTest(observer: (SemanticProcessSpec) -> Unit) {
        testProcessObserver = observer
    }

    fun formatWholeFile(snapshot: ProjectSnapshot, file: Path): CFormatResult {
        val source = readSource(snapshot, file) ?: return refused("clang.formatSourceMissing", "Format source is missing, ambiguous or outside the acquired snapshot")
        return formatText(snapshot, file, source, null)
    }

    fun formatRange(snapshot: ProjectSnapshot, file: Path, range: SourceRange): CFormatResult =
        formatRanges(snapshot, file, listOf(range))

    fun formatRanges(snapshot: ProjectSnapshot, file: Path, ranges: List<SourceRange>): CFormatResult {
        val source = readSource(snapshot, file) ?: return refused("clang.formatSourceMissing", "Format source is missing, ambiguous or outside the acquired snapshot")
        if (!validRangeSet(source.content, ranges)) {
            return refused("clang.formatInvalidRange", "Formatting ranges must be canonical, ordered, disjoint, and within the captured source")
        }
        return formatText(snapshot, file, source, ranges)
    }

    private fun formatText(snapshot: ProjectSnapshot, file: Path, sourceInput: CFormattingSourceInput, ranges: List<SourceRange>?): CFormatResult {
        if (!Files.isRegularFile(toolchain.clangFormatExecutable)) {
            return refused("clang.formatUnavailable", "Hash-bound clang-format executable is unavailable")
        }
        if (!sourceInput.isCurrent()) return sourceChanged()
        if (!runtimeIsCurrent()) return runtimeUnavailable()
        val source = sourceInput.content
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val normalized = if (file.isAbsolute) file.toAbsolutePath().normalize() else root.resolve(file).normalize()
        if (!normalized.startsWith(root) || normalized == root) {
            return refused("clang.formatSourceMissing", "Format source is outside the snapshot")
        }
        val styleInput = when (val resolution = resolveStyle(snapshot, normalized)) {
            is StyleResolution.Ready -> resolution.input
            is StyleResolution.Refused -> return styleRefused(resolution.code, resolution.message)
        }
        if (!styleInput.isCurrent()) return styleChanged()
        val arguments = formatArguments(styleInput, normalized, ranges)
        val formatted = runFormat(snapshot, arguments, sourceInput, styleInput, source) ?: return when {
            !sourceInput.isCurrent() -> sourceChanged()
            !styleInput.isCurrent() -> styleChanged()
            !runtimeIsCurrent() -> runtimeUnavailable()
            else -> refused("clang.formatFailed", "clang-format failed")
        }
        if (!sourceInput.isCurrent()) return sourceChanged()
        if (!styleInput.isCurrent()) return styleChanged()
        if (!runtimeIsCurrent()) return runtimeUnavailable()
        // A failed or misbehaving formatter must never turn an existing file into
        // an empty-file patch, even when it emitted no usable output.
        if (source.isNotEmpty() && formatted.isEmpty()) {
            return refused("clang.formatFailed", "clang-format returned empty output for a nonempty file")
        }
        val edits = diff(source, formatted)
        if (edits.isEmpty()) return CFormatResult.Accepted(emptyList(), idempotent = true)
        val idempotent = verifyIdempotent(snapshot, file, formatted, ranges, sourceInput, styleInput)
        if (!sourceInput.isCurrent()) return sourceChanged()
        if (!styleInput.isCurrent()) return styleChanged()
        if (!runtimeIsCurrent()) return runtimeUnavailable()
        if (!idempotent) return refused("clang.formatNonIdempotent", "clang-format was not idempotent")
        return CFormatResult.Accepted(edits, idempotent = true)
    }

    private fun runFormat(
        snapshot: ProjectSnapshot,
        arguments: List<String>,
        sourceInput: CFormattingSourceInput,
        styleInput: StyleInput,
        input: String,
    ): String? {
        if (!sourceInput.isCurrent() || !styleInput.isCurrent() || !runtimeIsCurrent()) return null
        val spec = SemanticProcessSpec(
            id = "clang-format-${SEQUENCE.getAndIncrement()}",
            executable = toolchain.clangFormatExecutable,
            arguments = arguments,
            workingDirectory = snapshot.workspace.root.toAbsolutePath().normalize(),
            limits = SemanticProcessLimits(
                maxStdoutBytes = MAX_OUTPUT_BYTES,
                maxStderrBytes = MAX_STDERR_BYTES,
                gracefulShutdownMillis = 1_000,
            ),
        )
        testTransport?.let { transport ->
            // This bounded seam exercises the formatter response policy only. It never
            // grants an executable identity or substitutes for native process evidence.
            val response = transport.execute(spec, input)
            if (response.stdout.toByteArray(Charsets.UTF_8).size > spec.limits.maxStdoutBytes ||
                response.stderrBytes > spec.limits.maxStderrBytes || response.exitCode != 0 || response.stderrTruncated ||
                !sourceInput.isCurrent() || !styleInput.isCurrent() || !runtimeIsCurrent()) return null
            return response.stdout
        }
        testProcessObserver?.invoke(spec)
        val execution = processManager.launch(spec)
        val reader = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "refactorkit-clang-format-output").apply { isDaemon = true }
        }
        val writer = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "refactorkit-clang-format-input").apply { isDaemon = true }
        }
        try {
            val future = reader.submit<String> { execution.output.bufferedReader(Charsets.UTF_8).readText() }
            val inputBytes = input.toByteArray(Charsets.UTF_8)
            val inputFuture = writer.submit {
                execution.input.use { output ->
                    output.write(inputBytes)
                    output.flush()
                }
            }
            if (!execution.awaitExit(REQUEST_TIMEOUT_MILLIS)) {
                execution.cancel()
                return null
            }
            inputFuture.get(2, TimeUnit.SECONDS)
            val formatted = future.get(2, TimeUnit.SECONDS)
            // An unsuccessful process can emit an empty or partial stdout. Neither is
            // authoritative formatting output, including during idempotence checking.
            if (execution.exitCode != 0 || execution.stderrTruncated() || !sourceInput.isCurrent() ||
                !styleInput.isCurrent() || !runtimeIsCurrent() ||
                execution.provenance.executableSha256 != (toolchain.runtime as ClangToolchainRuntimeCaptureResult.Captured)
                    .facts.formatter.manifest.executable.artifact.evidence.sha256) return null
            return formatted
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            return null
        } finally {
            reader.shutdownNow()
            writer.shutdownNow()
            execution.close()
        }
    }

    private fun verifyIdempotent(
        snapshot: ProjectSnapshot,
        file: Path,
        formatted: String,
        ranges: List<SourceRange>?,
        sourceInput: CFormattingSourceInput,
        styleInput: StyleInput,
    ): Boolean {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val normalized = if (file.isAbsolute) file.toAbsolutePath().normalize() else root.resolve(file).normalize()
        // Earlier formatted regions can move later line selections. clang-format's
        // replacement XML maps original line boundaries to the proposed output.
        val idempotenceRanges = if (ranges != null && ranges.size > 1) {
            val replacementXml = runFormat(
                snapshot,
                formatArguments(styleInput, normalized, ranges, outputReplacementsXml = true),
                sourceInput,
                styleInput,
                sourceInput.content,
            ) ?: return false
            val replacements = parseReplacementLineDeltas(replacementXml, sourceInput.content) ?: return false
            rebaseRanges(sourceInput.content, ranges, replacements) ?: return false
        } else ranges
        // Reformat the proposed output through stdin with rebased coordinates,
        // retaining the original source identity without creating scratch files.
        val arguments = formatArguments(styleInput, normalized, idempotenceRanges)
        return runFormat(snapshot, arguments, sourceInput, styleInput, formatted) == formatted
    }

    private fun formatArguments(
        styleInput: StyleInput,
        normalizedFile: Path,
        ranges: List<SourceRange>?,
        outputReplacementsXml: Boolean = false,
    ): List<String> = buildList {
        add("-style=${styleInput.argument}")
        add("-sort-includes=false") // formatting never owns include organization
        ranges?.forEach { range -> add("-lines=${range.start.line + 1}:${range.end.line + 1}") }
        if (outputReplacementsXml) add("--output-replacements-xml")
        add("--assume-filename=$normalizedFile")
    }

    private data class ReplacementLineDelta(
        val offset: Int,
        val endOffset: Int,
        val lineDelta: Int,
    )

    private fun parseReplacementLineDeltas(xml: String, source: String): List<ReplacementLineDelta>? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
            if (root.tagName != "replacements" || root.getAttribute("incomplete_format") != "false") return null
            val sourceBytes = source.toByteArray(Charsets.UTF_8)
            val nodes = root.getElementsByTagName("replacement")
            val replacements = (0 until nodes.length).map { index ->
                val node = nodes.item(index)
                val element = node as? org.w3c.dom.Element ?: return null
                val offset = element.getAttribute("offset").toIntOrNull() ?: return null
                val length = element.getAttribute("length").toIntOrNull() ?: return null
                if (offset < 0 || length < 0 || offset > sourceBytes.size - length) return null
                var removedNewlines = 0
                for (byteIndex in offset until offset + length) {
                    if (sourceBytes[byteIndex] == '\n'.code.toByte()) removedNewlines++
                }
                val insertedNewlines = node.textContent.count { it == '\n' }
                ReplacementLineDelta(offset, offset + length, insertedNewlines - removedNewlines)
            }
            val ordered = replacements.sortedWith(compareBy<ReplacementLineDelta> { it.offset }.thenBy { it.endOffset })
            if (ordered.zipWithNext().any { (left, right) -> left.endOffset > right.offset }) return null
            ordered
        } catch (_: Exception) {
            null
        }
    }

    private fun rebaseRanges(
        source: String,
        ranges: List<SourceRange>,
        replacements: List<ReplacementLineDelta>,
    ): List<SourceRange>? {
        val lineStartByteOffsets = mutableListOf(0)
        var charStart = 0
        var byteOffset = 0
        source.forEachIndexed { index, character ->
            if (character == '\n') {
                byteOffset += source.substring(charStart, index + 1).toByteArray(Charsets.UTF_8).size
                lineStartByteOffsets += byteOffset
                charStart = index + 1
            }
        }
        fun rebaseLine(line: Int): Int? {
            val boundaryOffset = lineStartByteOffsets.getOrNull(line) ?: return null
            var lineDelta = 0
            for (replacement in replacements) {
                if (replacement.offset < boundaryOffset && replacement.endOffset > boundaryOffset) return null
                val precedesBoundary = replacement.endOffset < boundaryOffset ||
                    (replacement.endOffset == boundaryOffset && replacement.endOffset > replacement.offset) ||
                    (replacement.endOffset == replacement.offset && replacement.offset <= boundaryOffset)
                if (precedesBoundary) lineDelta += replacement.lineDelta
            }
            return line + lineDelta
        }

        return ranges.map { range ->
            val startLine = rebaseLine(range.start.line) ?: return null
            val endLine = rebaseLine(range.end.line) ?: return null
            if (startLine > endLine) return null
            SourceRange(
                SourcePosition(startLine, range.start.character),
                SourcePosition(endLine, range.end.character),
            )
        }
    }

    private fun resolveStyle(snapshot: ProjectSnapshot, source: Path): StyleResolution {
        if (style != null) {
            return if (style == LLVM_STYLE) StyleResolution.Ready(StyleInput(LLVM_STYLE))
            else StyleResolution.Refused("clang.formatStyleUnavailable", "Only the explicit LLVM style override is supported")
        }
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        var directory: Path? = source.parent
        while (directory != null && directory.startsWith(root)) {
            val dotStyle = directory.resolve(DOT_STYLE_NAME)
            val underscoreStyle = directory.resolve(UNDERSCORE_STYLE_NAME)
            val dot = inspectStylePath(dotStyle)
            val underscore = inspectStylePath(underscoreStyle)
            if (dot is StylePathObservation.Refused) return dot.toResolution()
            if (underscore is StylePathObservation.Refused) return underscore.toResolution()
            val present = listOf(dot, underscore).filterIsInstance<StylePathObservation.Present>()
            if (present.size > 1) return StyleResolution.Refused(
                "clang.formatStyleUnavailable", "Both .clang-format and _clang-format exist in the nearest style directory",
            )
            present.singleOrNull()?.let { selected ->
                val relative = root.relativize(selected.path).normalize()
                val captured = snapshot.auxiliaryFiles.filter { it.path.normalize() == relative }
                if (captured.size != 1 || captured.single().languageId != CFormattingStyleSnapshot.LANGUAGE_ID) {
                    return StyleResolution.Refused(
                        "clang.formatStyleUncaptured", "The nearest workspace style file is not bound to this project snapshot",
                    )
                }
                val content = captured.single().content
                val expectedBytes = content.toByteArray(Charsets.UTF_8)
                if (expectedBytes.size > CFormattingStyleSnapshot.MAX_STYLE_FILE_BYTES) {
                    return StyleResolution.Refused("clang.formatStyleUnavailable", "The captured style file exceeds the bounded size")
                }
                if (INHERIT_PARENT_STYLE.containsMatchIn(content)) {
                    return StyleResolution.Refused(
                        "clang.formatStyleUnavailable", "InheritParentConfig cannot bind style outside the captured workspace",
                    )
                }
                val currentBytes = CFormattingStyleSnapshot.readBoundedNoFollow(selected.path)
                    ?: return StyleResolution.Refused("clang.formatStyleChanged", "The captured style file is missing or unavailable")
                if (!currentBytes.contentEquals(expectedBytes)) {
                    return StyleResolution.Refused("clang.formatStyleChanged", "The captured style file changed after project scan")
                }
                return StyleResolution.Ready(StyleInput("file:${selected.path}", selected.path, expectedBytes))
            }
            if (directory == root) break
            directory = directory.parent
        }
        return StyleResolution.Ready(StyleInput(LLVM_STYLE))
    }

    private fun inspectStylePath(path: Path): StylePathObservation = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            attributes.isSymbolicLink -> StylePathObservation.Refused(
                "clang.formatStyleUnavailable", "Indirect workspace style files do not authorize formatting",
            )
            !attributes.isRegularFile -> StylePathObservation.Refused(
                "clang.formatStyleUnavailable", "A workspace style path is not a regular file",
            )
            else -> StylePathObservation.Present(path)
        }
    } catch (_: NoSuchFileException) {
        StylePathObservation.Missing
    } catch (_: Exception) {
        StylePathObservation.Refused("clang.formatStyleUnavailable", "Workspace style evidence cannot be inspected safely")
    }

    private sealed interface StyleResolution {
        data class Ready(val input: StyleInput) : StyleResolution
        data class Refused(val code: String, val message: String) : StyleResolution
    }

    private sealed interface StylePathObservation {
        data object Missing : StylePathObservation
        data class Present(val path: Path) : StylePathObservation
        data class Refused(val code: String, val message: String) : StylePathObservation {
            fun toResolution() = StyleResolution.Refused(code, message)
        }
    }

    private data class StyleInput(
        val argument: String,
        private val livePath: Path? = null,
        private val capturedBytes: ByteArray? = null,
    ) {
        fun isCurrent(): Boolean = livePath?.let { path ->
            CFormattingStyleSnapshot.readBoundedNoFollow(path)?.contentEquals(capturedBytes) == true
        } ?: true
    }

    private fun readSource(snapshot: ProjectSnapshot, file: Path): CFormattingSourceInput? =
        CFormattingSourceInput.select(snapshot, file)

    private fun validRangeSet(source: String, ranges: List<SourceRange>): Boolean {
        if (ranges.isEmpty() || ranges.size > MAX_RANGES) return false
        val lineCount = if (source.isEmpty()) 0 else source.count { it == '\n' } + if (source.endsWith('\n')) 0 else 1
        if (ranges.any { it.end.line >= lineCount }) return false
        return ranges.zipWithNext().all { (left, right) -> left.end.line < right.start.line }
    }

    private fun sourceChanged() = refused("clang.formatSourceChanged",
        "Acquired C source is missing, indirect, unavailable or changed")

    private fun styleChanged() = styleRefused("clang.formatStyleChanged", "Captured C formatting style is missing or changed")

    private fun styleRefused(code: String, message: String) = CFormatResult.Refused(listOf(Diagnostic(
        message = message, severity = Diagnostic.Severity.ERROR, code = code,
        evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY,
    )))

    private fun runtimeIsCurrent() = ClangToolchainRuntimeCapture().checkCurrent(toolchain) == ClangToolchainRuntimeCurrency.Current
    private fun runtimeUnavailable() = CFormatResult.Refused(listOf(Diagnostic(
        "Retained native runtime inputs or formatter binding are unavailable or changed", Diagnostic.Severity.ERROR,
        code = "clang.formatRuntimeUnavailable", evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY)))

    internal fun diff(original: String, formatted: String): List<TextEdit> {
        var i = 0
        while (i < original.length && i < formatted.length && original[i] == formatted[i]) i++
        var j = original.length - 1
        var k = formatted.length - 1
        while (j >= i && k >= i && original[j] == formatted[k]) { j--; k-- }
        if (j < i && k < i) return emptyList()
        val start = positionOf(original, i)
        val end = positionOf(original, j + 1)
        val newText = formatted.substring(i, k + 1)
        return listOf(TextEdit(SourceRange(start, end), newText))
    }

    private fun positionOf(text: String, index: Int): SourcePosition {
        var line = 0
        var lineStart = 0
        var i = 0
        while (i < index) {
            if (text[i] == '\n') { line++; lineStart = i + 1 }
            i++
        }
        return SourcePosition(line, index - lineStart)
    }

    private fun refused(code: String, message: String) = CFormatResult.Refused(listOf(
        Diagnostic(message = message, severity = Diagnostic.Severity.ERROR, code = code, evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION),
    ))

    companion object {
        private val SEQUENCE = AtomicLong(1)
        private const val LLVM_STYLE = "LLVM"
        private const val DOT_STYLE_NAME = ".clang-format"
        private const val UNDERSCORE_STYLE_NAME = "_clang-format"
        private val INHERIT_PARENT_STYLE = Regex("(?im)^\\s*(?:BasedOnStyle\\s*:\\s*['\"]?InheritParentConfig|InheritParentConfig\\s*:\\s*true)(?:\\s|$)")
        private const val MAX_OUTPUT_BYTES = 8L * 1024L * 1024L
        private const val MAX_STDERR_BYTES = 4 * 1024 * 1024
        private const val MAX_RANGES = 32
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
    }
}

/** A bounded process-response seam used only by the C module's Gradle test fixtures. */
internal fun interface CFormatProcessTransport {
    fun execute(spec: SemanticProcessSpec, stdin: String): CFormatProcessResponse
}

/** Process output has no more authority than the limits enforced by the real process manager. */
internal data class CFormatProcessResponse(
    val stdout: String,
    val exitCode: Int,
    val stderrBytes: Int = 0,
    val stderrTruncated: Boolean = false,
)

/** Captures bounded direct clang-format configs into the workspace snapshot hash. */
object CFormattingStyleSnapshot {
    internal const val LANGUAGE_ID = "clang-format-style"
    internal const val MAX_STYLE_FILE_BYTES = 65_536
    private const val MAX_STYLE_FILES = 256
    private const val MAX_TOTAL_STYLE_BYTES = 4 * 1024 * 1024
    private val styleFileNames = listOf(".clang-format", "_clang-format")
    private val languages = setOf("c", "cpp", "objective-c")

    /**
     * Attach formatter inputs to a composed snapshot using only the captured C-family source
     * directories and their workspace ancestors. Unsafe or unreadable styles remain unbound.
     */
    fun captureForSources(snapshot: ProjectSnapshot): ProjectSnapshot {
        val sources = snapshot.files.filter { it.languageId in languages }
        if (sources.isEmpty()) return snapshot
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val candidates = sources.asSequence()
            .map { root.resolve(it.path).normalize() }.filter { it != root && it.startsWith(root) }
            .flatMap { source -> generateSequence(source.parent) { directory ->
                if (directory == root) null else directory.parent
            } }
            .distinct()
            .take(CProjectScanPolicy().maxEntries)
            .flatMap { directory -> styleFileNames.asSequence().map { root.relativize(directory.resolve(it)) } }
        return capture(snapshot, root, candidates)
    }

    internal fun capture(snapshot: ProjectSnapshot, discoveredPaths: List<Path>): ProjectSnapshot {
        if (snapshot.files.none { it.languageId in languages }) return snapshot
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val candidates = discoveredPaths.asSequence()
            .filter { it.fileName?.toString() in styleFileNames }.map { it.toAbsolutePath().normalize() }
            .filter { it.startsWith(root) }.distinct().sortedBy(Path::toString)
            .take(MAX_STYLE_FILES).map(root::relativize)
        return capture(snapshot, root, candidates)
    }

    private fun capture(snapshot: ProjectSnapshot, root: Path, candidates: Sequence<Path>): ProjectSnapshot {
        val tracked = snapshot.trackedFiles.mapTo(mutableSetOf()) { root.relativize(root.resolve(it.path).normalize()) }
        val additions = mutableListOf<SourceFile>()
        var remainingBytes = MAX_TOTAL_STYLE_BYTES
        for (relative in candidates) {
            if (relative in tracked) continue
            val observation = try {
                OwnedSourceInventoryReader.readWorkspaceTextInput(root, relative, MAX_STYLE_FILE_BYTES)
            } catch (_: OwnedSourceCaptureException) {
                continue
            }
            if (observation !is OwnedWorkspaceTextReadout.Captured) continue
            val byteCount = observation.content.toByteArray(Charsets.UTF_8).size
            if (byteCount > remainingBytes) continue
            remainingBytes -= byteCount
            additions += SourceFile(relative, observation.content, LANGUAGE_ID)
            if (additions.size == MAX_STYLE_FILES || remainingBytes == 0) break
        }
        return if (additions.isEmpty()) snapshot else snapshot.copy(auxiliaryFiles = snapshot.auxiliaryFiles + additions)
    }

    internal fun readBoundedNoFollow(path: Path): ByteArray? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > MAX_STYLE_FILE_BYTES) {
            null
        } else {
            val buffer = ByteBuffer.allocate(MAX_STYLE_FILE_BYTES + 1)
            Files.newByteChannel(path, setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
                while (buffer.hasRemaining()) {
                    val read = channel.read(buffer)
                    if (read < 0) break
                }
            }
            if (buffer.position() > MAX_STYLE_FILE_BYTES) null else buffer.array().copyOf(buffer.position())
        }
    } catch (_: Exception) {
        null
    }
}
