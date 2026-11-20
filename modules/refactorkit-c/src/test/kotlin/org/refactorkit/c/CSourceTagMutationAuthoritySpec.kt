package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C-SEMANTIC-RENAME-001: a private source tag cannot acquire incomplete external type-family authority. */
class CSourceTagMutationAuthoritySpec : StringSpec({
    fun fixture(path: String) = requireNotNull(CSourceTagMutationAuthoritySpec::class.java.getResourceAsStream(path)).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }.getValue("modes").jsonObject
    val privateInputs = fixture("/source-tag-rename/native-fixture.json.gz")
    val exposedInputs = fixture("/source-tag-exposure/native-fixture.json.gz")
    val file = Path.of("src/api.c")
    val units = setOf(file, Path.of("src/main.c"))
    fun sources(input: JsonObject) = input.getValue("sources").jsonObject.map { (path, text) ->
        Path.of(path) to text.jsonPrimitive.content
    }.toMap()
    fun trees(input: JsonObject) = input.getValue("asts").jsonObject.map { (path, ast) -> Path.of(path) to ast.jsonObject }.toMap()
    fun offset(input: JsonObject, keyword: String, name: String): Int {
        val index = sources(input).getValue(file).indexOf("$keyword $name")
        check(index >= 0)
        return index + keyword.length + 1
    }
    for (mode in listOf("c11", "c17")) {
        val privateInput = privateInputs.getValue(mode).jsonObject.getValue("before").jsonObject
        for ((keyword, name) in listOf("struct" to "meter", "union" to "payload", "enum" to "stage")) {
            "$mode private $keyword keeps exact source authority without merging the unrelated TU homonym" {
                val old = CTagRenameFactsDecoder.decodeSource(trees(privateInput), sources(privateInput), file,
                    offset(privateInput, keyword, name), units).shouldBeInstanceOf<CTagReadout.Found>().facts
                val current = CSourceTagMutationAuthority.decode(trees(privateInput), sources(privateInput), file,
                    offset(privateInput, keyword, name), units).shouldBeInstanceOf<CTagReadout.Found>().facts
                current.name shouldBe name
                current.sites shouldBe old.sites
                current.typeObservations shouldBe old.typeObservations
                current.sourceHashes shouldBe old.sourceHashes
                current.sites.size shouldBe 4
                current.sites.map { it.file }.toSet() shouldBe setOf(file)
            }
        }
        for (variant in listOf("external-return", "external-parameter", "external-object", "type-alias", "containing-record", "pointer-erasure")) {
            "$mode $variant requires source type consumer closure before mutation while navigation remains available" {
                val input = exposedInputs.getValue(mode).jsonObject.getValue(variant).jsonObject
                val keyword = input.getValue("keyword").jsonPrimitive.content
                val name = input.getValue("name").jsonPrimitive.content
                val selected = offset(input, keyword, name)
                CTagRenameFactsDecoder.decodeSource(trees(input), sources(input), file, selected, units)
                    .shouldBeInstanceOf<CTagReadout.Found>().facts.name shouldBe name
                CSourceTagMutationAuthority.decode(trees(input), sources(input), file, selected, units) shouldBe
                    CTagReadout.Refused(CTagFactsRefusal.SOURCE_TYPE_EXPOSURE_UNQUALIFIED)
            }
        }
    }
})
