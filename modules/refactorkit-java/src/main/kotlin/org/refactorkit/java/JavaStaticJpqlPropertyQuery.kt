package org.refactorkit.java

import org.eclipse.jdt.core.dom.MemberValuePair
import org.eclipse.jdt.core.dom.NormalAnnotation
import org.eclipse.jdt.core.dom.StringLiteral
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.TextEdits
import javax.lang.model.SourceVersion

internal object JavaStaticJpqlPropertyQuery {
    const val JAKARTA_NAMED_QUERY = "jakarta.persistence.NamedQuery"

    fun locationsForNamedQuery(
        entity: TypeDeclaration,
        file: SourceFile,
        property: String,
    ): List<SourceLocation>? {
        val annotation = entity.modifiers().filterIsInstance<NormalAnnotation>().singleOrNull {
            val type = it.resolveAnnotationBinding()?.annotationType
            type != null && !type.isRecovered && !type.isFromSource && type.qualifiedName == JAKARTA_NAMED_QUERY
        } ?: return null
        val members = annotation.values().filterIsInstance<MemberValuePair>()
        if (members.size != 2) return null
        val name = members.singleOrNull { it.name.identifier == "name" }?.value as? StringLiteral ?: return null
        if (literalText(file, name).isNullOrBlank()) return null
        val query = members.singleOrNull { it.name.identifier == "query" }?.value as? StringLiteral ?: return null
        return propertyLocations(file, query, entity.name.identifier, property)
    }

    fun propertyLocations(
        file: SourceFile,
        literal: StringLiteral,
        entityName: String,
        property: String,
    ): List<SourceLocation>? {
        val value = literalText(file, literal) ?: return null
        val query = QUERY_SHAPE.matchEntire(value) ?: return null
        val alias = query.groups["rootAlias"]!!.value
        val selected = query.groups["selected"]!!
        val filtered = query.groups["filtered"]
        if (query.groups["entity"]!!.value != entityName || !persistentIdentifier(alias) ||
            !alias.equals(query.groups["selectAlias"]!!.value, ignoreCase = true) || selected.value != property ||
            (filtered != null && (filtered.value != property || !alias.equals(query.groups["filterAlias"]!!.value, ignoreCase = true)))
        ) return null
        return listOfNotNull(selected, filtered).map { token ->
            SourceLocation(file.path, TextEdits.rangeForOffset(file.content, literal.startPosition + 1 + token.range.first, token.value.length))
        }
    }

    private fun literalText(file: SourceFile, literal: StringLiteral): String? {
        val value = literal.literalValue
        val source = file.content.substring(literal.startPosition, literal.startPosition + literal.length)
        return value.takeIf { source == "\"$it\"" }
    }

    fun persistentIdentifier(name: String): Boolean =
        IDENTIFIER.matches(name) && !SourceVersion.isKeyword(name) && name.lowercase() !in JPQL_KEYWORDS

    private const val WORD = "[A-Za-z][A-Za-z0-9_]*"
    private val IDENTIFIER = Regex(WORD)
    private val QUERY_SHAPE = Regex(
        "\\s*(?i:select)\\s+(?<selectAlias>$WORD)\\s*\\.\\s*(?<selected>$WORD)" +
            "\\s+(?i:from)\\s+(?<entity>$WORD)\\s+(?:(?i:as)\\s+)?(?<rootAlias>$WORD)" +
            "(?:\\s+(?i:where)\\s+(?<filterAlias>$WORD)\\s*\\.\\s*(?<filtered>$WORD)\\s+(?i:is)(?:\\s+(?i:not))?\\s+(?i:null))?\\s*",
    )
    private val JPQL_KEYWORDS = """
        abs all and any as asc avg between bit_length both by case char_length character_length class coalesce concat count
        current_date current_time current_timestamp delete desc distinct else empty end entry escape exists false fetch from
        group having in index inner is join key leading left length like local_date local_datetime local_time locate lower
        max member min mod new not null nullif object of on or order outer position right select set size some sqrt
        substring sum then trailing treat trim true type union update upper value when where
    """.trim().split(Regex("\\s+")).toSet()
}
