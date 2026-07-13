package org.refactorkit.treesitter

import org.refactorkit.core.Symbol

/**
 * Packaged real Tree-sitter bindings for the first JavaScript/TypeScript slice.
 * Reflection keeps the public RefactorKit bytecode Java-8-compatible while the
 * optional native bridge (loaded only on the bundled Java 21 runtime) requires 11.
 */
class BonedeTreeSitterBinding : TreeSitterNativeBinding {
    private val api: Result<ReflectionApi> by lazy { runCatching { ReflectionApi() } }
    private val languages: Map<String, Result<Any>> by lazy {
        mapOf(
            "typescript" to runCatching { Class.forName("org.treesitter.TreeSitterTypescript").getConstructor().newInstance() },
            "javascript" to runCatching { Class.forName("org.treesitter.TreeSitterJavascript").getConstructor().newInstance() },
        )
    }

    override fun supports(languageId: String): Boolean = api.isSuccess && languages[languageId]?.isSuccess == true

    override fun outline(content: String, languageId: String): List<GenericOutline.OutlineItem> =
        parse(content, languageId) { reflection, root, bytes ->
            val items = mutableListOf<GenericOutline.OutlineItem>()
            walkNamed(reflection, root) { node ->
                val kind = OUTLINE_TYPES[reflection.type(node)] ?: return@walkNamed
                val nameNode = reflection.childByFieldName(node, "name") ?: return@walkNamed
                val name = text(reflection, nameNode, bytes) ?: return@walkNamed
                if (IDENTIFIER.matches(name)) {
                    items += GenericOutline.OutlineItem(
                        name = name,
                        kind = kind,
                        line = reflection.startRow(nameNode),
                        character = reflection.startColumn(nameNode),
                    )
                }
            }
            items.sortedWith(compareBy({ it.line }, { it.character }, { it.name }))
        } ?: emptyList()

    fun symbolKindAt(content: String, line: Int, character: Int, languageId: String): Symbol.Kind? {
        if (line < 0 || character < 0) return null
        val sourceLine = content.lineSequence().elementAtOrNull(line)?.removeSuffix("\r") ?: return null
        if (character > sourceLine.length || character > 0 && character < sourceLine.length &&
            sourceLine[character].isLowSurrogate() && sourceLine[character - 1].isHighSurrogate()) return null
        val utf8Column = sourceLine.substring(0, character).toByteArray(Charsets.UTF_8).size
        return parse(content, languageId) { reflection, root, bytes ->
            var resolved: Symbol.Kind? = null
            walkNamed(reflection, root) { node ->
                if (resolved != null || reflection.type(node) !in IDENTIFIER_NODE_TYPES ||
                    reflection.startRow(node) != line || reflection.startColumn(node) != utf8Column) return@walkNamed
                var current: Any? = node
                repeat(MAX_KIND_ANCESTORS) {
                    current = current?.let(reflection::parent)
                    val kind = current?.let { structuralSymbolKind(reflection, it, bytes) }
                    if (kind != null) {
                        resolved = kind
                        return@walkNamed
                    }
                }
            }
            resolved
        }
    }

    private fun structuralSymbolKind(reflection: ReflectionApi, node: Any, bytes: ByteArray): Symbol.Kind? {
        val type = reflection.type(node)
        if (type != "internal_module") return STRUCTURAL_SYMBOL_TYPES[type]
        val start = reflection.startByte(node)
        val end = (start + MAX_MODULE_KEYWORD_BYTES).coerceAtMost(reflection.endByte(node)).coerceAtMost(bytes.size)
        if (start !in 0 until end) return null
        val prefix = bytes.copyOfRange(start, end).toString(Charsets.UTF_8).trimStart()
        return if (prefix.startsWith("module") && prefix.drop(6).firstOrNull()?.isWhitespace() == true) {
            Symbol.Kind.MODULE
        } else Symbol.Kind.NAMESPACE
    }

    override fun findIdentifier(
        content: String,
        identifier: String,
        languageId: String,
    ): List<GenericStructuralSearch.SearchMatch> {
        if (!IDENTIFIER.matches(identifier)) return emptyList()
        return parse(content, languageId) { reflection, root, bytes ->
            val matches = mutableListOf<GenericStructuralSearch.SearchMatch>()
            walkNamed(reflection, root) { node ->
                if (reflection.type(node) !in IDENTIFIER_NODE_TYPES || text(reflection, node, bytes) != identifier) {
                    return@walkNamed
                }
                matches += GenericStructuralSearch.SearchMatch(
                    line = reflection.startRow(node),
                    character = reflection.startColumn(node),
                    length = identifier.length,
                    text = identifier,
                )
            }
            matches.sortedWith(compareBy({ it.line }, { it.character }))
        } ?: emptyList()
    }

    private fun <T> parse(
        content: String,
        languageId: String,
        operation: (ReflectionApi, Any, ByteArray) -> T,
    ): T? {
        val reflection = api.getOrNull() ?: return null
        val language = languages[languageId]?.getOrNull() ?: return null
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SOURCE_BYTES) return null
        return runCatching {
            val parser = reflection.newParser()
            check(reflection.setLanguage(parser, language)) { "Tree-sitter grammar is incompatible with runtime" }
            reflection.setTimeout(parser, PARSE_TIMEOUT_MICROS)
            val tree = reflection.parseString(parser, content) ?: return null
            operation(reflection, reflection.rootNode(tree), bytes)
        }.getOrNull()
    }

    private fun walkNamed(reflection: ReflectionApi, root: Any, visitor: (Any) -> Unit) {
        val stack = ArrayDeque<Any>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            visitor(node)
            for (index in reflection.namedChildCount(node) - 1 downTo 0) {
                reflection.namedChild(node, index)?.let(stack::addLast)
            }
        }
    }

    private fun text(reflection: ReflectionApi, node: Any, bytes: ByteArray): String? {
        val start = reflection.startByte(node)
        val end = reflection.endByte(node)
        if (start < 0 || end < start || end > bytes.size || end - start > MAX_IDENTIFIER_BYTES) return null
        return bytes.copyOfRange(start, end).toString(Charsets.UTF_8)
    }

    private class ReflectionApi {
        private val parserClass = Class.forName("org.treesitter.TSParser")
        private val languageClass = Class.forName("org.treesitter.TSLanguage")
        private val treeClass = Class.forName("org.treesitter.TSTree")
        private val nodeClass = Class.forName("org.treesitter.TSNode")
        private val pointClass = Class.forName("org.treesitter.TSPoint")
        private val parserConstructor = parserClass.getConstructor()
        private val setLanguage = parserClass.getMethod("setLanguage", languageClass)
        private val setTimeout = parserClass.getMethod("setTimeoutMicros", java.lang.Long.TYPE)
        private val parseString = parserClass.getMethod("parseString", treeClass, String::class.java)
        private val rootNode = treeClass.getMethod("getRootNode")
        private val type = nodeClass.getMethod("getType")
        private val childByFieldName = nodeClass.getMethod("getChildByFieldName", String::class.java)
        private val namedChildCount = nodeClass.getMethod("getNamedChildCount")
        private val namedChild = nodeClass.getMethod("getNamedChild", Integer.TYPE)
        private val startByte = nodeClass.getMethod("getStartByte")
        private val endByte = nodeClass.getMethod("getEndByte")
        private val startPoint = nodeClass.getMethod("getStartPoint")
        private val parent = nodeClass.getMethod("getParent")
        private val pointRow = pointClass.getMethod("getRow")
        private val pointColumn = pointClass.getMethod("getColumn")

        fun newParser(): Any = parserConstructor.newInstance()
        fun setLanguage(parser: Any, language: Any): Boolean = setLanguage.invoke(parser, language) as Boolean
        fun setTimeout(parser: Any, micros: Long) = setTimeout.invoke(parser, micros).let { Unit }
        fun parseString(parser: Any, content: String): Any? = parseString.invoke(parser, null, content)
        fun rootNode(tree: Any): Any = rootNode.invoke(tree)
        fun type(node: Any): String = type.invoke(node) as String
        fun childByFieldName(node: Any, name: String): Any? = childByFieldName.invoke(node, name)
        fun namedChildCount(node: Any): Int = namedChildCount.invoke(node) as Int
        fun namedChild(node: Any, index: Int): Any? = namedChild.invoke(node, index)
        fun startByte(node: Any): Int = startByte.invoke(node) as Int
        fun endByte(node: Any): Int = endByte.invoke(node) as Int
        fun startRow(node: Any): Int = pointRow.invoke(startPoint.invoke(node)) as Int
        fun startColumn(node: Any): Int = pointColumn.invoke(startPoint.invoke(node)) as Int
        fun parent(node: Any): Any? = parent.invoke(node)
    }

    companion object {
        const val BACKEND = "tree-sitter-jni-0.25.3"
        const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
        const val MAX_NODES = 100_000
        const val MAX_IDENTIFIER_BYTES = 1_024
        const val PARSE_TIMEOUT_MICROS = 2_000_000L
        const val MAX_KIND_ANCESTORS = 8
        const val MAX_MODULE_KEYWORD_BYTES = 64

        private val IDENTIFIER = Regex("[" + '$' + "_\\p{L}][" + '$' + "_\\p{L}\\p{N}]*")
        private val IDENTIFIER_NODE_TYPES = setOf(
            "identifier", "property_identifier", "private_property_identifier",
            "shorthand_property_identifier", "shorthand_property_identifier_pattern", "type_identifier",
        )
        private val STRUCTURAL_SYMBOL_TYPES = mapOf(
            "required_parameter" to Symbol.Kind.PARAMETER,
            "optional_parameter" to Symbol.Kind.PARAMETER,
            "rest_pattern" to Symbol.Kind.PARAMETER,
            "type_alias_declaration" to Symbol.Kind.TYPE_ALIAS,
            "module" to Symbol.Kind.MODULE,
        )
        private val OUTLINE_TYPES = mapOf(
            "class_declaration" to GenericOutline.OutlineItem.Kind.CLASS,
            "abstract_class_declaration" to GenericOutline.OutlineItem.Kind.CLASS,
            "interface_declaration" to GenericOutline.OutlineItem.Kind.INTERFACE,
            "enum_declaration" to GenericOutline.OutlineItem.Kind.ENUM,
            "function_declaration" to GenericOutline.OutlineItem.Kind.FUNCTION,
            "generator_function_declaration" to GenericOutline.OutlineItem.Kind.FUNCTION,
            "method_definition" to GenericOutline.OutlineItem.Kind.METHOD,
            "public_field_definition" to GenericOutline.OutlineItem.Kind.PROPERTY,
            "type_alias_declaration" to GenericOutline.OutlineItem.Kind.UNKNOWN,
        )
    }
}
