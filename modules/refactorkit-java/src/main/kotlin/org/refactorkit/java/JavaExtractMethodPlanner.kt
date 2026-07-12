package org.refactorkit.java

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Limited MVP Extract Method planner.
 *
 * Supported shape:
 * - Java source file only.
 * - 1-based inclusive line range.
 * - Extracts selected complete lines into `private void <methodName>()`.
 * - Replaces the selected block with `<methodName>();` at the original indentation.
 * - Inserts the new method before the final class closing brace.
 *
 * Conservative refusals:
 * - invalid method name
 * - invalid/blank range
 * - method name already exists in the file
 * - selected block contains `return`, `throw`, `break`, `continue`, or `yield`
 * - selected block appears to contain a declaration/method/class declaration
 * - selected block uses local variables/parameters declared before the range
 * - selected block declares locals that are used after the range
 *
 * This deliberately avoids guessing parameters, return values, exceptions, or
 * complex control flow. Future versions should replace this with AST/type-aware
 * analysis.
 */
class JavaExtractMethodPlanner {

    fun preview(
        snapshot: ProjectSnapshot,
        filePath: Path,
        startLine: Int,
        endLine: Int,
        methodName: String,
    ): PatchPlan {
        if (!isValidJavaIdentifier(methodName)) {
            return refused(snapshot, "Invalid Java method name: $methodName")
        }
        if (startLine <= 0 || endLine < startLine) {
            return refused(snapshot, "Invalid line range: startLine=$startLine endLine=$endLine (lines are 1-based, inclusive)")
        }

        val file = snapshot.files.find { it.path == filePath }
            ?: return refused(snapshot, "File not found in snapshot: $filePath")
        if (file.languageId != "java") return refused(snapshot, "Extract method currently supports Java files only: $filePath")
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "Generated source cannot be rewritten: $filePath ($reason)")
        }

        val content = file.content
        if (Regex("""\b${Regex.escape(methodName)}\s*\(""").containsMatchIn(content)) {
            return refused(snapshot, "Method already exists or is already called in file: $methodName")
        }

        val lineStarts = computeLineStarts(content)
        val startZero = startLine - 1
        val endZero = endLine - 1
        if (startZero !in lineStarts.indices || endZero !in lineStarts.indices) {
            return refused(snapshot, "Line range is outside file: startLine=$startLine endLine=$endLine")
        }

        val startOffset = lineStarts[startZero]
        val endOffset = if (endZero + 1 < lineStarts.size) lineStarts[endZero + 1] else content.length
        val selected = content.substring(startOffset, endOffset)
        if (selected.isBlank()) return refused(snapshot, "Selected range is blank")

        validateSelection(snapshot, content, selected, startOffset, endOffset)?.let { return it }

        val insertOffset = findFinalClassBrace(content)
            ?: return refused(snapshot, "Could not find final class closing brace for method insertion")
        if (insertOffset <= endOffset) {
            return refused(snapshot, "Selected range appears to include the class closing brace")
        }

        val firstSelectedLine = selected.lineSequence().firstOrNull().orEmpty()
        val callIndent = firstSelectedLine.takeWhile { it == ' ' || it == '\t' }
        val classCloseLineStart = lineStarts.lastOrNull { it <= insertOffset } ?: 0
        val classIndent = content.substring(classCloseLineStart, insertOffset).takeWhile { it == ' ' || it == '\t' }
        val methodIndent = classIndent + "    "
        val bodyIndent = methodIndent + "    "

        val normalizedBody = selected.trimEnd('\n', '\r')
            .lines()
            .joinToString("\n") { line ->
                if (line.isBlank()) "" else bodyIndent + line.removePrefix(callIndent)
            }
        val methodText = "\n$methodIndent private void $methodName() {\n$normalizedBody\n$methodIndent}\n"
        val replacement = "$callIndent$methodName();\n"

        val replaceEdit = TextEdit(
            SourceRange(
                TextEdits.positionForOffset(content, startOffset),
                TextEdits.positionForOffset(content, endOffset),
            ),
            replacement,
        )
        val insertPos = TextEdits.positionForOffset(content, insertOffset)
        val insertEdit = TextEdit(SourceRange(insertPos, insertPos), methodText)

        return PatchPlan(
            operation = "extractMethod",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.70,
            requiresUserApproval = true,
            summary = "Extract lines $startLine-$endLine from $filePath into method $methodName().",
            affectedFiles = setOf(filePath),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(filePath, listOf(replaceEdit, insertEdit)))),
            warnings = listOf(
                "Limited extract-method MVP: only no-argument private void methods are supported.",
                "Selection was refused if local variables, return values, exceptions, or complex control flow were detected.",
            ),
            riskLevel = RiskLevel.MEDIUM,
        )
    }

    private fun validateSelection(
        snapshot: ProjectSnapshot,
        content: String,
        selected: String,
        startOffset: Int,
        endOffset: Int,
    ): PatchPlan? {
        val forbiddenTokens = listOf("return", "throw", "break", "continue", "yield")
        forbiddenTokens.firstOrNull { JavaLexer.findOccurrences(selected, it).isNotEmpty() }?.let {
            return refused(snapshot, "Selection contains '$it'; return values/exceptions/control flow are not supported by extract-method MVP")
        }

        if (Regex("""\b(class|interface|enum|record)\b""").containsMatchIn(selected)) {
            return refused(snapshot, "Selection appears to contain a type declaration")
        }
        if (Regex("""\)\s*\{""").containsMatchIn(selected)) {
            return refused(snapshot, "Selection appears to contain a method/control block declaration; select complete statements only")
        }
        if (selected.count { it == '{' } != selected.count { it == '}' }) {
            return refused(snapshot, "Selection has unbalanced braces")
        }

        val prefix = content.substring(0, startOffset)
        val suffix = content.substring(endOffset)
        val selectedIdentifiers = identifiers(selected)
        val declaredBefore = localDeclarationNames(prefix) + nearestMethodParameterNames(prefix)
        val externalLocals = selectedIdentifiers.intersect(declaredBefore)
        if (externalLocals.isNotEmpty()) {
            return refused(snapshot, "Selection uses local variable(s)/parameter(s) declared before the range: ${externalLocals.sorted().joinToString(", ")}. Parameter extraction is not supported yet.")
        }

        val declaredInSelection = localDeclarationNames(selected)
        val usedAfter = declaredInSelection.filter { name -> JavaLexer.findOccurrences(suffix, name).isNotEmpty() }.toSet()
        if (usedAfter.isNotEmpty()) {
            return refused(snapshot, "Selection declares local variable(s) used after the range: ${usedAfter.sorted().joinToString(", ")}. Return-value extraction is not supported yet.")
        }
        return null
    }

    private fun computeLineStarts(content: String): List<Int> = buildList {
        add(0)
        content.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    }

    private fun findFinalClassBrace(content: String): Int? {
        var i = content.length - 1
        while (i >= 0 && content[i].isWhitespace()) i--
        return if (i >= 0 && content[i] == '}') i else null
    }

    private fun identifiers(content: String): Set<String> = Regex("""\b[A-Za-z_$][A-Za-z0-9_$]*\b""")
        .findAll(content)
        .map { it.value }
        .filter { it !in JAVA_KEYWORDS }
        .toSet()

    private fun localDeclarationNames(content: String): Set<String> {
        val results = mutableSetOf<String>()
        val declaration = Regex(
            """\b(?:final\s+)?(?:[A-Z_a-z$][\w$]*(?:<[^;=(){}]*>)?(?:\[\])?\s+)+([a-zA-Z_$][\w$]*)\s*(?:=|;|,)""",
        )
        declaration.findAll(content).forEach { match -> results += match.groupValues[1] }
        return results
    }

    private fun nearestMethodParameterNames(prefix: String): Set<String> {
        val methodRegex = Regex("""(?:public|protected|private|static|final|synchronized|native|abstract|\s)+[\w<>\[\], ?]+\s+\w+\s*\(([^)]*)\)\s*\{""")
        val params = methodRegex.findAll(prefix).lastOrNull()?.groupValues?.get(1)?.trim().orEmpty()
        if (params.isBlank()) return emptySet()
        return params.split(',')
            .mapNotNull { param -> param.trim().split(Regex("""\s+""")).lastOrNull()?.removePrefix("...") }
            .filter { it.isNotBlank() && isValidJavaIdentifier(it) }
            .toSet()
    }

    private fun refused(snapshot: ProjectSnapshot, reason: String) = PatchPlan(
        operation = "extractMethod",
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

    private fun isValidJavaIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!name[0].isLetter() && name[0] != '_' && name[0] != '$') return false
        return name.all { JavaLexer.isIdentChar(it) } && name !in JAVA_KEYWORDS
    }

    companion object {
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
