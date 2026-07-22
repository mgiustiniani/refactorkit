package org.refactorkit.java

import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits

internal sealed interface PomRewriteResult {
    data class Edits(val edits: List<TextEdit>) : PomRewriteResult
    data class Refused(val code: String, val message: String) : PomRewriteResult
}

/** Conservative lexical editor: it never serializes XML and accepts only direct project dependencies. */
internal object MavenPomDependencyEditor {
    fun rewrite(pom: SourceFile, request: MavenDependencyRewrite): PomRewriteResult {
        val parsed = runCatching { XmlLexicalTree.parse(pom.content) }.getOrElse {
            return PomRewriteResult.Refused(
                "mavenOwnership.ambiguousPomOrigin",
                "POM XML cannot be located losslessly: ${it.message}",
            )
        }
        val located = parsed.descendants().filter { it.localName == "dependency" }.mapNotNull { dependency ->
            dependencyCoordinate(dependency, pom.content)?.let { dependency to it }
        }.toList()
        val dependencies = located.filter { (node, _) ->
            node.parent?.localName == "dependencies" && node.parent?.parent?.localName == "project"
        }.map { it.second }
        if (located.any { (_, coordinate) ->
                coordinate.mayRepresent(request.source) &&
                    coordinate.values.values.any { value -> "\${" in value.text }
            }) {
            return PomRewriteResult.Refused(
                "mavenOwnership.propertyManagedCoordinate",
                "Property/interpolation-backed dependency coordinates are not writable in the first ownership row",
            )
        }
        if (located.any { (node, coordinate) ->
                coordinate.identityOrNull() == request.source &&
                    !(node.parent?.localName == "dependencies" && node.parent?.parent?.localName == "project")
            }) {
            return PomRewriteResult.Refused(
                "mavenOwnership.ambiguousPomOrigin",
                "Profile or dependency-management coordinate origins are not writable in the first ownership row",
            )
        }
        val matching = dependencies.filter { it.identityOrNull() == request.source }
        if (matching.isEmpty()) {
            return PomRewriteResult.Refused(
                "mavenOwnership.dependencyRewriteMismatch",
                "No exact literal source dependency exists in ${request.pomPath}",
            )
        }
        if (matching.size > 1 && !request.allIdenticalOccurrences) {
            return PomRewriteResult.Refused(
                "mavenOwnership.ambiguousPomOrigin",
                "Multiple identical dependency occurrences require allIdenticalOccurrences=true",
            )
        }
        val selected = if (request.allIdenticalOccurrences) matching else listOf(matching.single())
        val edits = mutableListOf<TextEdit>()
        for (occurrence in selected) {
            fun replace(name: String, destination: String) {
                val value = occurrence.values.getValue(name)
                if (value.text != destination) edits += TextEdit(
                    SourceRange(
                        TextEdits.positionForOffset(pom.content, value.start),
                        TextEdits.positionForOffset(pom.content, value.end),
                    ),
                    destination,
                )
            }
            replace("groupId", request.destination.groupId)
            replace("artifactId", request.destination.artifactId)
            replace("version", request.destination.version)
            val sourceType = occurrence.values["type"]?.text ?: "jar"
            val destinationType = request.destination.type
            if (
                sourceType != destinationType || request.destination.classifier != null ||
                occurrence.values["classifier"] != null
            ) {
                return PomRewriteResult.Refused(
                    "mavenOwnership.dependencyRewriteMismatch",
                    "The first ownership row cannot add/remove type or classifier elements",
                )
            }
        }
        if (edits.isEmpty()) {
            return PomRewriteResult.Refused(
                "mavenOwnership.dependencyRewriteMismatch",
                "Dependency rewrite would be a no-op",
            )
        }
        return PomRewriteResult.Edits(edits)
    }

    private data class Value(val text: String, val start: Int, val end: Int)

    private data class Coordinate(val values: Map<String, Value>) {
        fun mayRepresent(identity: MavenDependencyIdentity): Boolean {
            fun matches(name: String, expected: String): Boolean = values[name]?.text?.let { value ->
                value == expected || "\${" in value
            } ?: false
            val type = values["type"]?.text ?: "jar"
            val classifier = values["classifier"]?.text
            return matches("groupId", identity.groupId) && matches("artifactId", identity.artifactId) &&
                matches("version", identity.version) && (type == identity.type || "\${" in type) &&
                (classifier == identity.classifier || classifier?.contains("\${") == true)
        }

        fun identityOrNull(): MavenDependencyIdentity? {
            val group = values["groupId"]?.text ?: return null
            val artifact = values["artifactId"]?.text ?: return null
            val version = values["version"]?.text ?: return null
            if (listOf(group, artifact, version).any { "\${" in it || '&' in it }) return null
            val type = values["type"]?.text ?: "jar"
            val classifier = values["classifier"]?.text
            return runCatching { MavenDependencyIdentity(group, artifact, version, type, classifier) }.getOrNull()
        }
    }

    private fun dependencyCoordinate(node: XmlNode, content: String): Coordinate? {
        val coordinateNames = setOf("groupId", "artifactId", "version", "type", "classifier")
        val selected = node.children.filter { it.localName in coordinateNames }
        if (selected.groupBy { it.localName }.any { it.value.size != 1 }) return null
        val values = selected.associate { child ->
            if (child.children.isNotEmpty()) return null
            val raw = content.substring(child.contentStart, child.contentEnd)
            if ('<' in raw || '>' in raw) return null
            val leading = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
            val trailing = raw.indexOfLast { !it.isWhitespace() }
            child.localName to Value(
                raw.substring(leading, trailing + 1),
                child.contentStart + leading,
                child.contentStart + trailing + 1,
            )
        }
        return Coordinate(values)
    }
}

private data class XmlNode(
    val localName: String,
    val contentStart: Int,
    var contentEnd: Int,
    val parent: XmlNode?,
    val children: MutableList<XmlNode> = mutableListOf(),
) {
    fun descendants(): Sequence<XmlNode> = sequence {
        children.forEach { child ->
            yield(child)
            yieldAll(child.descendants())
        }
    }
}

/** Minimal bounded XML tokenizer used only to locate exact element text offsets. */
private object XmlLexicalTree {
    fun parse(content: String): XmlNode {
        require(content.length <= MAX_POM_CHARS) { "POM exceeds the bounded lexical limit" }
        val document = XmlNode("#document", 0, content.length, null)
        val stack = ArrayDeque<XmlNode>()
        stack.addLast(document)
        var cursor = 0
        var elements = 0
        while (cursor < content.length) {
            val open = content.indexOf('<', cursor)
            if (open < 0) break
            when {
                content.startsWith("<!--", open) -> cursor = terminated(content, open + 4, "-->")
                content.startsWith("<![CDATA[", open) -> cursor = terminated(content, open + 9, "]]>")
                content.startsWith("<?", open) -> cursor = terminated(content, open + 2, "?>")
                content.startsWith("<!", open) -> cursor = scanDeclaration(content, open + 2)
                else -> {
                    val close = scanTagEnd(content, open + 1)
                    val body = content.substring(open + 1, close).trim()
                    val closing = body.startsWith('/')
                    val selfClosing = body.endsWith('/')
                    val name = body.removePrefix("/").removeSuffix("/").trimStart()
                        .takeWhile { !it.isWhitespace() && it != '/' }
                    require(name.isNotBlank()) { "Empty XML element name" }
                    val local = name.substringAfter(':')
                    if (closing) {
                        require(stack.size > 1 && stack.last().localName == local) {
                            "Mismatched XML closing element: $name"
                        }
                        stack.removeLast().contentEnd = open
                    } else {
                        require(++elements <= MAX_ELEMENTS) { "POM exceeds the bounded element limit" }
                        val parent = stack.last()
                        val node = XmlNode(
                            local,
                            close + 1,
                            if (selfClosing) close + 1 else content.length,
                            parent,
                        )
                        parent.children += node
                        if (!selfClosing) stack.addLast(node)
                    }
                    cursor = close + 1
                }
            }
        }
        require(stack.size == 1) { "Unclosed XML element" }
        return document
    }

    private fun scanTagEnd(content: String, start: Int): Int {
        var quote: Char? = null
        for (index in start until content.length) {
            val current = content[index]
            if (quote != null) {
                if (current == quote) quote = null
            } else when (current) {
                '\'', '"' -> quote = current
                '>' -> return index
            }
        }
        error("Unclosed XML tag")
    }

    private fun scanDeclaration(content: String, start: Int): Int {
        var bracketDepth = 0
        var quote: Char? = null
        for (index in start until content.length) {
            val current = content[index]
            if (quote != null) {
                if (current == quote) quote = null
            } else when (current) {
                '\'', '"' -> quote = current
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                '>' -> if (bracketDepth == 0) return index + 1
            }
        }
        error("Unclosed XML declaration")
    }

    private fun terminated(content: String, start: Int, marker: String): Int {
        val end = content.indexOf(marker, start)
        require(end >= 0) { "Unclosed XML lexical section" }
        return end + marker.length
    }

    private const val MAX_POM_CHARS = 4 * 1024 * 1024
    private const val MAX_ELEMENTS = 100_000
}
