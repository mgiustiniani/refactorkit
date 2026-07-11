package org.refactorkit.core

object TextEdits {
    fun apply(content: String, edits: List<TextEdit>): String {
        val lineStarts = computeLineStarts(content)
        val replacements = edits.sortedWith(
            compareByDescending<TextEdit> { offsetOf(lineStarts, content.length, it.range.start) }
                .thenByDescending { offsetOf(lineStarts, content.length, it.range.end) },
        )
        val builder = StringBuilder(content)
        replacements.forEach { edit ->
            val start = offsetOf(lineStarts, content.length, edit.range.start)
            val end = offsetOf(lineStarts, content.length, edit.range.end)
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

    fun offsetOf(content: String, position: SourcePosition): Int =
        offsetOf(computeLineStarts(content), content.length, position)

    private fun computeLineStarts(content: String): List<Int> {
        val starts = mutableListOf(0)
        content.forEachIndexed { index, char ->
            if (char == '\n') starts += index + 1
        }
        return starts
    }

    private fun offsetOf(lineStarts: List<Int>, contentLength: Int, position: SourcePosition): Int {
        require(position.line < lineStarts.size) { "line ${position.line} is outside file" }
        val lineStart = lineStarts[position.line]
        val lineEndExclusive = if (position.line + 1 < lineStarts.size) {
            lineStarts[position.line + 1] - 1 // exclude the newline delimiter
        } else {
            contentLength
        }
        val lineLength = lineEndExclusive - lineStart
        require(position.character <= lineLength) {
            "character ${position.character} is outside line ${position.line} length $lineLength"
        }
        return lineStart + position.character
    }
}
