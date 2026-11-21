package org.refactorkit.java

import org.refactorkit.core.PlanReferenceAnalysis
import org.refactorkit.core.PlanReferenceCompleteness
import org.refactorkit.core.PlanReferenceEvidence
import org.refactorkit.core.PlanReferenceKind
import org.refactorkit.core.PlanReferenceProvenance
import org.refactorkit.core.PlanReferenceResourceKind
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import java.nio.file.Path

/** Exact source locations and the conservative completeness envelope for one entity-field rename. */
data class JavaFrameworkFieldRenameEvidence(
    val referenceAnalysis: PlanReferenceAnalysis,
    val editsByPath: Map<Path, List<TextEdit>>,
)

/**
 * Finds the deliberately small, source-only Spring Data JPQL cell used by entity-field rename.
 *
 * A result is never project-complete: XML/YAML/properties, JPA named queries, dynamic query
 * expressions, migrations, generated metamodels, and external consumers are outside this cell.
 * Those limits are part of the returned typed analysis and prevent managed Apply.
 */
class JavaFrameworkReferenceAnalyzer {
    fun analyzeEntityPropertyRename(
        snapshot: ProjectSnapshot,
        ownerQualifiedName: String,
        ownerPath: Path,
        oldPropertyName: String,
        newPropertyName: String,
    ): JavaFrameworkFieldRenameEvidence? {
        val ownerFile = snapshot.files.singleOrNull {
            it.languageId == "java" && it.path.normalize() == ownerPath.normalize()
        } ?: return null
        val ownerSimpleName = ownerQualifiedName.substringAfterLast('.')
        val ownerPackage = ownerQualifiedName.substringBeforeLast('.', "")
        if (JavaPackageUtil.extractPackage(ownerFile.content) != ownerPackage ||
            !declaresType(ownerFile.content, ownerSimpleName) || !hasJpaEntityAnnotation(ownerFile)
        ) return null

        val entityFiles = snapshot.files.filter { file ->
            file.languageId == "java" && hasJpaEntityAnnotation(file) && declaresType(file.content, ownerSimpleName)
        }
        val warnings = mutableListOf(
            "Reference closure is incomplete: only direct static Spring Data @Query JPQL property paths are inspected; JPA mappings, XML/YAML/properties, named queries, migrations, generated metamodels, reflection, and external consumers remain outside this analysis.",
        )
        val opaqueResourcePaths = snapshot.auxiliaryFiles.asSequence()
            .map(SourceFile::path)
            .filter(::isOpaqueQueryResource)
            .map { it.toString().replace('\\', '/') }
            .sorted()
            .toList()
        opaqueResourcePaths.take(MAX_REPORTED_OPAQUE_RESOURCES).forEach { path ->
            warnings += "JPQL reference closure cannot inspect workspace resource '$path'."
        }
        val remainingResources = opaqueResourcePaths.size - MAX_REPORTED_OPAQUE_RESOURCES
        if (remainingResources > 0) {
            warnings += "JPQL reference closure also leaves $remainingResources workspace resource(s) uninspected."
        }
        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        val references = mutableListOf<PlanReferenceEvidence>()

        if (entityFiles.size != 1 || entityFiles.singleOrNull()?.path != ownerFile.path) {
            warnings += "Entity simple name '$ownerSimpleName' is ambiguous in the workspace; JPQL aliases cannot be assigned to one owner."
        } else {
            snapshot.files.asSequence()
                .filter { it.languageId == "java" }
                .forEach { file ->
                    val imports = JavaLexer.extractImports(file.content).filterNot { it.isStatic }.map { it.name }.toSet()
                    val queryAnnotations = queryAnnotationOffsets(file.content, imports)
                    queryAnnotations.forEach { (annotationOffset, annotationName) ->
                        val literal = parseDirectQueryLiteral(file.content, annotationOffset, annotationName)
                        if (literal == null) {
                            warnings += "Spring Data @Query at ${file.path}:${lineAt(file.content, annotationOffset) + 1} is not one direct, escape-free string literal; its entity-property references are unresolved."
                            return@forEach
                        }
                        val maskedQuery = maskQuotedValuesAndComments(literal.value)
                        val fromEntries = FROM_PATTERN.findAll(maskedQuery).toList()
                        val ownerEntries = fromEntries.filter { it.groupValues[1].substringAfterLast('.') == ownerSimpleName }
                        if (ownerEntries.isEmpty()) return@forEach
                        if (fromEntries.size != 1 || ownerEntries.size != 1) {
                            warnings += "Spring Data JPQL at ${file.path}:${lineAt(file.content, annotationOffset) + 1} has multiple or unsupported entity roots; property references remain unresolved."
                            return@forEach
                        }
                        val alias = ownerEntries.single().groupValues[2]
                        if (alias.isBlank() || alias.lowercase() in JPQL_KEYWORDS) {
                            warnings += "Spring Data JPQL at ${file.path}:${lineAt(file.content, annotationOffset) + 1} has no unambiguous alias for entity '$ownerSimpleName'."
                            return@forEach
                        }
                        val propertyPattern = Regex(
                            "\\b${Regex.escape(alias)}\\s*\\.\\s*(${Regex.escape(oldPropertyName)})\\b",
                        )
                        propertyPattern.findAll(maskedQuery).forEach { match ->
                            val propertyGroup = requireNotNull(match.groups[1])
                            val offset = literal.contentOffset + propertyGroup.range.first
                            val textEdit = TextEdit(
                                TextEdits.rangeForOffset(file.content, offset, propertyGroup.value.length),
                                newText = newPropertyName,
                            )
                            editsByPath.getOrPut(file.path) { mutableListOf() } += textEdit
                            references += PlanReferenceEvidence(
                                languageId = "java",
                                kind = PlanReferenceKind.FRAMEWORK_ENTITY_PROPERTY,
                                targetOwner = ownerQualifiedName,
                                targetMember = oldPropertyName,
                                location = SourceLocation(file.path, textEdit.range),
                                resourceKind = PlanReferenceResourceKind.SOURCE_ANNOTATION_STRING,
                                provenance = PlanReferenceProvenance("spring-data.query-jpql"),
                                completeness = PlanReferenceCompleteness.COMPLETE,
                            )
                        }
                    }
                }
        }

        val sortedEdits = editsByPath.mapValues { (_, edits) ->
            edits.distinctBy { it.range }.sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
        }
        val analysis = PlanReferenceAnalysis(
            schemaVersion = "refactorkit-reference-analysis-v1",
            scope = "java-spring-data-jpql-static-property-path-v1",
            completeness = PlanReferenceCompleteness.INCOMPLETE,
            completeProject = false,
            managedWriteAuthority = "none",
            references = references.sortedWith(compareBy(
                { it.location.path.toString() }, { it.location.range.start.line }, { it.location.range.start.character },
            )),
            warnings = warnings.distinct(),
        )
        return JavaFrameworkFieldRenameEvidence(analysis, sortedEdits)
    }

    private fun isOpaqueQueryResource(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.endsWith(".jpql") || name.endsWith(".hql") || name == "jpa-named-queries.properties" ||
            (name in JPA_DESCRIPTORS && path.parent?.fileName?.toString().equals("META-INF", ignoreCase = true))
    }

    private fun queryAnnotationOffsets(content: String, imports: Set<String>): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        if ("org.springframework.data.jpa.repository.Query" in imports) {
            JavaLexer.findOccurrences(content, "@Query").forEach { result += it.first to "@Query" }
        }
        val qualified = "@org.springframework.data.jpa.repository.Query"
        JavaLexer.findOccurrences(content, qualified).forEach { result += it.first to qualified }
        return result.distinctBy { it.first }.sortedBy { it.first }
    }

    private fun parseDirectQueryLiteral(content: String, annotationOffset: Int, annotationName: String): QueryLiteral? {
        var cursor = annotationOffset + annotationName.length
        while (cursor < content.length && content[cursor].isWhitespace()) cursor++
        if (content.getOrNull(cursor) != '(') return null
        cursor++
        while (cursor < content.length && content[cursor].isWhitespace()) cursor++
        if (content.startsWith("value", cursor) && !JavaLexer.isIdentChar(content.getOrNull(cursor + 5) ?: ' ')) {
            cursor += 5
            while (cursor < content.length && content[cursor].isWhitespace()) cursor++
            if (content.getOrNull(cursor) != '=') return null
            cursor++
            while (cursor < content.length && content[cursor].isWhitespace()) cursor++
        }
        if (content.getOrNull(cursor) != '"') return null
        val quoteOffset = cursor
        cursor++
        val valueStart = cursor
        var escaped = false
        while (cursor < content.length) {
            when (content[cursor]) {
                '\\' -> {
                    escaped = true
                    cursor += 2
                }
                '"' -> break
                '\n', '\r' -> return null
                else -> cursor++
            }
        }
        if (cursor >= content.length) return null
        val valueEnd = cursor
        cursor++
        while (cursor < content.length && content[cursor].isWhitespace()) cursor++
        if (content.getOrNull(cursor) != ')' || escaped) return null
        return QueryLiteral(content.substring(valueStart, valueEnd), quoteOffset + 1)
    }

    private fun hasJpaEntityAnnotation(file: SourceFile): Boolean {
        val imports = JavaLexer.extractImports(file.content).filterNot { it.isStatic }.map { it.name }.toSet()
        val simple = "@Entity"
        val qualifiedNames = listOf("@jakarta.persistence.Entity", "@javax.persistence.Entity")
        return (imports.any { it == "jakarta.persistence.Entity" || it == "javax.persistence.Entity" } &&
            JavaLexer.findOccurrences(file.content, simple).isNotEmpty()) ||
            qualifiedNames.any { JavaLexer.findOccurrences(file.content, it).isNotEmpty() }
    }

    private fun declaresType(content: String, simpleName: String): Boolean {
        for (keyword in TYPE_KEYWORDS) {
            for (range in JavaLexer.findOccurrences(content, keyword)) {
                val suffix = content.substring(range.last + 1)
                if (Regex("^\\s+${Regex.escape(simpleName)}\\b").containsMatchIn(suffix)) return true
            }
        }
        return false
    }

    private fun maskQuotedValuesAndComments(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        while (index < chars.size) {
            when {
                chars[index] == '\'' || chars[index] == '"' -> {
                    val quote = chars[index]
                    chars[index++] = ' '
                    while (index < chars.size) {
                        if (chars[index] == quote) {
                            chars[index++] = ' '
                            if (quote == '\'' && index < chars.size && chars[index] == '\'') {
                                chars[index++] = ' '
                                continue
                            }
                            break
                        }
                        chars[index] = if (chars[index] == '\n' || chars[index] == '\r') chars[index] else ' '
                        index++
                    }
                }
                chars[index] == '-' && chars.getOrNull(index + 1) == '-' -> {
                    while (index < chars.size && chars[index] != '\n') chars[index++] = ' '
                }
                chars[index] == '/' && chars.getOrNull(index + 1) == '*' -> {
                    chars[index++] = ' '
                    chars[index++] = ' '
                    while (index + 1 < chars.size && !(chars[index] == '*' && chars[index + 1] == '/')) {
                        chars[index] = if (chars[index] == '\n' || chars[index] == '\r') chars[index] else ' '
                        index++
                    }
                    if (index + 1 < chars.size) {
                        chars[index++] = ' '
                        chars[index++] = ' '
                    }
                }
                else -> index++
            }
        }
        return String(chars)
    }

    private fun lineAt(content: String, offset: Int): Int = content.take(offset).count { it == '\n' }

    private data class QueryLiteral(val value: String, val contentOffset: Int)

    private companion object {
        const val MAX_REPORTED_OPAQUE_RESOURCES = 32
        val JPA_DESCRIPTORS = setOf("orm.xml", "persistence.xml")
        val TYPE_KEYWORDS = listOf("class", "record", "enum", "interface")
        val JPQL_KEYWORDS = setOf("as", "where", "join", "left", "right", "inner", "outer", "fetch", "on", "group", "order", "having")
        val FROM_PATTERN = Regex(
            "(?i)\\bfrom\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)(?:\\s+(?:as\\s+)?([A-Za-z_$][A-Za-z0-9_$]*))?",
        )
    }
}
