package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import java.nio.file.Path

/** Framework families recognized by RefactorKit's Java safety model. */
enum class JavaFramework {
    SPRING,
    JPA,
    JACKSON,
}

/** One framework annotation occurrence found in a Java source file. */
data class JavaFrameworkFinding(
    val framework: JavaFramework,
    val annotationName: String,
    val path: Path,
    val line: Int,
)

/** Aggregate framework risk assessment for one declaration file. */
data class JavaFrameworkAssessment(
    val findings: List<JavaFrameworkFinding>,
) {
    val hasFindings: Boolean get() = findings.isNotEmpty()
    val frameworks: Set<JavaFramework> get() = findings.map { it.framework }.toSet()

    fun warnings(operation: String): List<String> {
        if (findings.isEmpty()) return emptyList()
        val base = findings
            .groupBy { it.framework }
            .flatMap { (framework, matches) ->
                val names = matches.map { "@${it.annotationName}" }.distinct().sorted().joinToString(", ")
                val prefix = "$framework annotation(s) detected ($names):"
                when (framework) {
                    JavaFramework.SPRING -> springWarnings(operation, prefix)
                    JavaFramework.JPA -> jpaWarnings(operation, prefix)
                    JavaFramework.JACKSON -> jacksonWarnings(operation, prefix)
                }
            }
        val locations = findings
            .sortedWith(compareBy({ it.path.toString() }, { it.line }, { it.annotationName }))
            .joinToString("; ") { "${it.path}:${it.line + 1} @${it.annotationName}" }
        return base + "Framework annotation locations: $locations"
    }

    private fun springWarnings(operation: String, prefix: String): List<String> = when (operation) {
        "renameClass" -> listOf(
            "$prefix Spring bean names may be derived from class names; check @Qualifier strings, SpEL, XML/config, and tests.",
        )
        "moveClass" -> listOf(
            "$prefix moving classes may affect component scanning, @Configuration imports, package-based conditions, and tests.",
        )
        "safeDelete" -> listOf(
            "$prefix framework-managed beans may be referenced by configuration, reflection, qualifiers, or dependency injection.",
        )
        else -> listOf("$prefix framework references require manual review.")
    }

    private fun jpaWarnings(operation: String, prefix: String): List<String> = when (operation) {
        "renameClass" -> listOf(
            "$prefix default JPA entity names derive from class names; check JPQL strings, Criteria usage, repositories, and migrations.",
        )
        "moveClass" -> listOf(
            "$prefix moving entities may affect entity scanning, persistence-unit config, generated metamodels, and tests.",
        )
        "safeDelete" -> listOf(
            "$prefix deleting entities may affect schema mappings, repositories, JPQL strings, migrations, and persisted data.",
        )
        else -> listOf("$prefix persistence mappings require manual review.")
    }

    private fun jacksonWarnings(operation: String, prefix: String): List<String> = when (operation) {
        "renameClass" -> listOf(
            "$prefix serialized type names/properties may be external API contracts; check clients and stored payloads.",
        )
        "moveClass" -> listOf(
            "$prefix moving classes may affect polymorphic type ids, default typing, and serialization configuration.",
        )
        "safeDelete" -> listOf(
            "$prefix deleting DTOs/types may break serialized API contracts and stored JSON payloads.",
        )
        else -> listOf("$prefix serialization contracts require manual review.")
    }
}

/**
 * Source-level framework annotation detector.
 *
 * This scanner intentionally does not require Spring/JPA/Jackson dependencies on
 * the classpath. It recognizes annotation names lexically while skipping Java
 * comments, string literals, character literals, and text blocks.
 */
object JavaFrameworkDetector {

    private val ANNOTATION_FRAMEWORKS: Map<String, JavaFramework> = buildMap {
        // Spring
        listOf(
            "Component", "Service", "Repository", "Controller", "RestController",
            "Configuration", "Bean", "Autowired", "Qualifier", "RequestMapping",
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
            "ConfigurationProperties",
        ).forEach { put(it, JavaFramework.SPRING) }

        // JPA / Jakarta Persistence
        listOf(
            "Entity", "Table", "Column", "Id", "GeneratedValue", "OneToMany", "ManyToOne",
            "OneToOne", "ManyToMany", "JoinColumn", "JoinTable", "Embeddable", "Embedded",
            "MappedSuperclass", "Transient", "Version",
        ).forEach { put(it, JavaFramework.JPA) }

        // Jackson
        listOf(
            "JsonProperty", "JsonTypeName", "JsonSubTypes", "JsonTypeInfo", "JsonCreator",
            "JsonIgnore", "JsonAlias", "JsonValue", "JsonDeserialize", "JsonSerialize",
        ).forEach { put(it, JavaFramework.JACKSON) }
    }

    fun assess(file: SourceFile): JavaFrameworkAssessment = JavaFrameworkAssessment(detect(file))

    fun assess(snapshot: ProjectSnapshot, path: Path): JavaFrameworkAssessment =
        snapshot.files.find { it.path == path }?.let(::assess) ?: JavaFrameworkAssessment(emptyList())

    fun detect(file: SourceFile): List<JavaFrameworkFinding> {
        if (file.languageId != "java") return emptyList()
        val content = file.content
        val results = mutableListOf<JavaFrameworkFinding>()
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                c == '/' && content.getOrNull(i + 1) == '/' -> {
                    i = content.indexOf('\n', i).takeIf { it >= 0 }?.plus(1) ?: content.length
                }
                c == '/' && content.getOrNull(i + 1) == '*' -> {
                    i = content.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: content.length
                }
                c == '"' -> i = skipStringOrTextBlock(content, i)
                c == '\'' -> i = skipCharLiteral(content, i)
                c == '@' -> {
                    val parsed = parseAnnotationName(content, i + 1)
                    if (parsed != null) {
                        val (name, nextOffset) = parsed
                        val simple = name.substringAfterLast('.')
                        val framework = ANNOTATION_FRAMEWORKS[simple]
                        if (framework != null) {
                            results += JavaFrameworkFinding(
                                framework = framework,
                                annotationName = simple,
                                path = file.path,
                                line = lineForOffset(content, i),
                            )
                        }
                        i = nextOffset
                    } else i++
                }
                else -> i++
            }
        }
        return results.distinctBy { "${it.path}:${it.line}:${it.annotationName}" }
    }

    private fun parseAnnotationName(content: String, start: Int): Pair<String, Int>? {
        var i = start
        if (i >= content.length || !isQualifiedIdentStart(content[i])) return null
        while (i < content.length && isQualifiedIdentPart(content[i])) i++
        return content.substring(start, i) to i
    }

    private fun skipStringOrTextBlock(content: String, quoteOffset: Int): Int {
        val len = content.length
        // Text block """
        if (quoteOffset + 2 < len && content[quoteOffset + 1] == '"' && content[quoteOffset + 2] == '"') {
            var i = quoteOffset + 3
            while (i + 2 < len) {
                if (content[i] == '"' && content[i + 1] == '"' && content[i + 2] == '"') return i + 3
                i++
            }
            return len
        }
        var i = quoteOffset + 1
        while (i < len && content[i] != '\n') {
            if (content[i] == '\\') i += 2
            else if (content[i] == '"') return i + 1
            else i++
        }
        return i
    }

    private fun skipCharLiteral(content: String, quoteOffset: Int): Int {
        var i = quoteOffset + 1
        while (i < content.length && content[i] != '\n') {
            if (content[i] == '\\') i += 2
            else if (content[i] == '\'') return i + 1
            else i++
        }
        return i
    }

    private fun lineForOffset(content: String, offset: Int): Int = content.take(offset).count { it == '\n' }

    private fun isQualifiedIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'
    private fun isQualifiedIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$' || c == '.'
}
