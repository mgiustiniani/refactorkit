package org.refactorkit.java

import com.ctc.wstx.api.WstxInputProperties
import com.ctc.wstx.stax.WstxInputFactory
import org.codehaus.stax2.XMLStreamReader2
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import java.io.StringReader
import java.util.ArrayList
import java.util.Collections
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLResolver
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

internal sealed interface PomRewriteResult {
    data class Edits(val edits: List<TextEdit>) : PomRewriteResult
    data class Refused(val code: String, val message: String) : PomRewriteResult
}

/** Conservative lexical editor: it never serializes XML and accepts only direct project dependencies. */
internal object MavenPomDependencyEditor {
    fun rewrite(pom: SourceFile, request: MavenDependencyRewrite): PomRewriteResult {
        val content = pom.content
        val parsed = runCatching { WoodstoxXmlTree.parse(content) }.getOrElse {
            return PomRewriteResult.Refused(
                "mavenOwnership.ambiguousPomOrigin",
                "POM XML cannot be located losslessly: ${it.message}",
            )
        }
        val located = parsed.descendants().filter { it.localName == "dependency" }.mapNotNull { dependency ->
            dependencyCoordinate(dependency, content)?.let { dependency to it }
        }.toList()
        val directProjectDependencies = parsed.descendants()
            .filter { it.localName == "project" }
            .flatMap { project ->
                project.children.asSequence()
                    .filter { it.localName == "dependencies" }
                    .flatMap { it.children.asSequence().filter { child -> child.localName == "dependency" } }
            }
            .toSet()
        val dependencies = located.filter { (node, _) -> node in directProjectDependencies }.map { it.second }
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
                coordinate.identityOrNull() == request.source && node !in directProjectDependencies
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
                        TextEdits.positionForOffset(content, value.start),
                        TextEdits.positionForOffset(content, value.end),
                    ),
                    destination,
                )
            }
            replace("groupId", request.destination.groupId)
            replace("artifactId", request.destination.artifactId)
            replace("version", request.destination.version)

            val sourceType = occurrence.values["type"]?.text ?: "jar"
            val destinationType = request.destination.type
            val sourceClassifier = occurrence.values["classifier"]?.text
            val destinationClassifier = request.destination.classifier

            // Handle type element changes
            if (sourceType != destinationType || sourceClassifier != destinationClassifier ||
                (sourceClassifier == null && destinationClassifier != null) ||
                (sourceClassifier != null && destinationClassifier == null)) {
                // Update or insert type element if packaging differs from jar
                if (sourceType != destinationType) {
                    if (sourceType == "jar") {
                        // Insert type element after version
                        val versionEnd = occurrence.values["version"]?.end ?:
                            return PomRewriteResult.Refused(
                                "mavenOwnership.dependencyRewriteMismatch",
                                "Cannot locate version element to insert type",
                            )
                        val afterVersion = content.indexOf('>', versionEnd) + 1
                        val indent = "\n${content.substring(versionEnd - 1, versionEnd).takeWhile { it.isWhitespace() }}"
                        val insertion = "${indent}    <type>$destinationType</type>"
                        edits += TextEdit(
                            SourceRange(
                                TextEdits.positionForOffset(content, afterVersion),
                                TextEdits.positionForOffset(content, afterVersion),
                            ),
                            insertion,
                        )
                    } else {
                        // Update existing type element text
                        val typeValue = occurrence.values.getValue("type")
                        edits += TextEdit(
                            SourceRange(
                                TextEdits.positionForOffset(content, typeValue.start),
                                TextEdits.positionForOffset(content, typeValue.end),
                            ),
                            destinationType,
                        )
                    }
                } else if (sourceType != "jar" && destinationType == "jar") {
                    // Remove type element when destination is jar
                    val typeValue = occurrence.values.getValue("type")
                    val typeNode = selected.firstOrNull()?.let { it.values["type"] }
                    // Find the opening tag position
                    val openTag = content.lastIndexOf('<', typeValue.start - 1)
                    val closeTag = content.indexOf('>', typeValue.end) + 1
                    val removeStart = content.substring(0, openTag).takeLastWhile { it.isWhitespace() }.let { ws ->
                        openTag - ws.length
                    }
                    edits += TextEdit(
                        SourceRange(
                            TextEdits.positionForOffset(content, removeStart),
                            TextEdits.positionForOffset(content, closeTag),
                        ),
                        "",
                    )
                }
            }

            // Handle classifier element
            if (destinationClassifier != null) {
                if (sourceClassifier != null) {
                    // Update existing classifier
                    val clsValue = occurrence.values.getValue("classifier")
                    if (clsValue.text != destinationClassifier) {
                        edits += TextEdit(
                            SourceRange(
                                TextEdits.positionForOffset(content, clsValue.start),
                                TextEdits.positionForOffset(content, clsValue.end),
                            ),
                            destinationClassifier,
                        )
                    }
                } else {
                    // Insert classifier after type or version
                    val afterElement = occurrence.values["type"] ?: occurrence.values["version"] ?:
                        return PomRewriteResult.Refused(
                            "mavenOwnership.dependencyRewriteMismatch",
                            "Cannot locate element to insert classifier",
                        )
                    val afterPos = content.indexOf('>', afterElement.end) + 1
                    val indent = "\n${content.substring(afterElement.end - 1, afterElement.end).takeWhile { it.isWhitespace() }}"
                    val insertion = "${indent}    <classifier>$destinationClassifier</classifier>"
                    edits += TextEdit(
                        SourceRange(
                            TextEdits.positionForOffset(content, afterPos),
                            TextEdits.positionForOffset(content, afterPos),
                        ),
                        insertion,
                    )
                }
            } else if (sourceClassifier != null && destinationClassifier == null) {
                // Remove classifier element
                val clsValue = occurrence.values.getValue("classifier")
                val openTag = content.lastIndexOf('<', clsValue.start - 1)
                val closeTag = content.indexOf('>', clsValue.end) + 1
                val removeStart = content.substring(0, openTag).takeLastWhile { it.isWhitespace() }.let { ws ->
                    openTag - ws.length
                }
                edits += TextEdit(
                    SourceRange(
                        TextEdits.positionForOffset(content, removeStart),
                        TextEdits.positionForOffset(content, closeTag),
                    ),
                    "",
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

internal class XmlNode(
    val localName: String,
    val contentStart: Int,
    val contentEnd: Int,
    children: Collection<XmlNode>,
) {
    private val childValues: List<XmlNode> = Collections.unmodifiableList(ArrayList(children))
    val children: List<XmlNode> get() = childValues

    fun descendants(): Sequence<XmlNode> = sequence {
        childValues.forEach { child ->
            yield(child)
            yieldAll(child.descendants())
        }
    }
}

/** Bounded structural/range reader backed exclusively by maintained Woodstox parser locations. */
internal object WoodstoxXmlTree {
    const val parserSelection: String = "MAINTAINED_NON_EXECUTING"
    const val dtdProcessing: String = "DISABLED"
    const val externalGeneralEntities: String = "DISABLED"
    const val externalParameterEntities: String = "DISABLED"
    const val externalDtdAccess: String = "DENIED"
    const val rangeAuthority: String = "PARSER_REPORTED_ELEMENT_TEXT"

    val parserImplementation: String = WstxInputFactory::class.java.name
    val parserVersion: String = WstxInputFactory::class.java.`package`.implementationVersion
        ?.takeIf(String::isNotBlank)
        ?: error("Woodstox implementation version is unavailable")

    fun parse(content: String): XmlNode {
        require(content.length <= MAX_POM_CHARS) { "POM exceeds the bounded document limit" }
        val reader = inputFactory().createXMLStreamReader(StringReader(content)) as? XMLStreamReader2
            ?: error("Woodstox did not provide XMLStreamReader2 location authority")
        val stack = ArrayDeque<OpenElement>()
        stack.addLast(OpenElement("#document", 0, false))
        var elements = 0
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        require(++elements <= MAX_ELEMENTS) { "POM exceeds the bounded element limit" }
                        stack.addLast(OpenElement(
                            localName = reader.localName,
                            contentStart = characterOffset(
                                reader.locationInfo.endingCharOffset,
                                content.length,
                            ),
                            emptyElement = reader.isEmptyElement,
                        ))
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        require(stack.size > 1) { "Unexpected XML closing element: ${reader.localName}" }
                        val open = stack.removeLast()
                        require(open.localName == reader.localName) {
                            "Mismatched XML closing element: ${reader.localName}"
                        }
                        val closingStart = characterOffset(
                            reader.locationInfo.startingCharOffset,
                            content.length,
                        )
                        val contentEnd = if (open.emptyElement) open.contentStart else closingStart
                        require(contentEnd >= open.contentStart) {
                            "Woodstox reported an invalid element-text range for ${open.localName}"
                        }
                        stack.last().children += XmlNode(
                            localName = open.localName,
                            contentStart = open.contentStart,
                            contentEnd = contentEnd,
                            children = open.children,
                        )
                    }
                    XMLStreamConstants.DTD -> error("DTD declarations are disabled for POM range parsing")
                }
            }
            require(stack.size == 1) { "Unclosed XML element" }
            val document = stack.removeLast()
            return XmlNode("#document", 0, content.length, document.children)
        } finally {
            reader.closeCompletely()
        }
    }

    private fun inputFactory(): WstxInputFactory = WstxInputFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        setProperty(WstxInputProperties.P_MAX_CHARACTERS, MAX_POM_CHARS.toLong())
        setProperty(WstxInputProperties.P_MAX_ELEMENT_COUNT, MAX_ELEMENTS.toLong())
        xmlResolver = DENY_EXTERNAL_RESOLVER
        setProperty(WstxInputProperties.P_DTD_RESOLVER, DENY_EXTERNAL_RESOLVER)
        setProperty(WstxInputProperties.P_ENTITY_RESOLVER, DENY_EXTERNAL_RESOLVER)
        setProperty(WstxInputProperties.P_UNDECLARED_ENTITY_RESOLVER, DENY_EXTERNAL_RESOLVER)
    }

    private fun characterOffset(offset: Long, documentLength: Int): Int {
        require(offset in 0..documentLength.toLong()) {
            "Woodstox reported a character offset outside the bounded document: $offset"
        }
        return offset.toInt()
    }

    private class OpenElement(
        val localName: String,
        val contentStart: Int,
        val emptyElement: Boolean,
        val children: MutableList<XmlNode> = mutableListOf(),
    )

    private val DENY_EXTERNAL_RESOLVER = XMLResolver { _, systemId, _, _ ->
        throw XMLStreamException("External XML resource access is denied: ${systemId.orEmpty()}")
    }

    private const val MAX_POM_CHARS = 4 * 1024 * 1024
    private const val MAX_ELEMENTS = 100_000
}
