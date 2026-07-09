package org.refactorkit.core

object TextEdits {
    fun apply(content: String, edits: List<TextEdit>): String {
        val lineStarts = computeLineStarts(content)
        val replacements = edits.sortedWith(
            compareByDescending<TextEdit> { offsetOf(lineStarts, it.range.start) }
                .thenByDescending { offsetOf(lineStarts, it.range.end) },
        )
        val builder = StringBuilder(content)
        replacements.forEach { edit ->
            val start = offsetOf(lineStarts, edit.range.start)
            val end = offsetOf(lineStarts, edit.range.end)
            require(start <= end) { "edit start must be <= edit end" }
            builder.replace(start, end, edit.newText)
        }
        return builder.toString()
    }

    fun rangeForOffset(content: String, offset: Int, length: Int): SourceRange {
        val start = positionForOffset(content, offset)
        val end = positionForOffset(content, offset + length)
        return SourceRange(start, end)
    }

    fun positionForOffset(content: String, offset: Int): SourcePosition {
        require(offset in 0..content.length) { "offset $offset is outside content length ${content.length}" }
        var line = 0
        var lineStart = 0
        for (index in 0 until offset) {
            if (content[index] == '\n') {
                line += 1
                lineStart = index + 1
            }
        }
        return SourcePosition(line, offset - lineStart)
    }

    fun offsetOf(content: String, position: SourcePosition): Int = offsetOf(computeLineStarts(content), position)

    private fun computeLineStarts(content: String): List<Int> {
        val starts = mutableListOf(0)
        content.forEachIndexed { index, char ->
            if (char == '\n') starts += index + 1
        }
        return starts
    }

    private fun offsetOf(lineStarts: List<Int>, position: SourcePosition): Int {
        require(position.line < lineStarts.size) { "line ${position.line} is outside file" }
        return lineStarts[position.line] + position.character
    }
}
