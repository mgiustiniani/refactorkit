package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** C10: old-style definitions cannot authorize signature transformations. */
class CFunctionPrototypeAdmissionSpec : StringSpec({
    val fixtures = GZIPInputStream(requireNotNull(CFunctionPrototypeAdmissionSpec::class.java
        .getResourceAsStream("/function-family/prototype-admission/native-fixture.json.gz")))
        .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    fun decode(dialect: String, shape: String): CFunctionFamilyFactsReadout {
        val captured = fixtures.getValue("dialects").jsonObject.getValue(dialect).jsonObject.getValue(shape).jsonObject
        val source = captured.getValue("source").jsonPrimitive.content.toByteArray(Charsets.UTF_8)
        val ast = Json.parseToJsonElement(captured.getValue("astJson").jsonPrimitive.content).jsonObject
        return CFunctionFamilyFactsDecoder.decode(ast, Path.of("fixture.c"), source, "weighted")
    }
    for (dialect in listOf("c11", "c17")) {
        "$dialect old-style definition cannot become a complete signature family" {
            decode(dialect, "old-style") shouldBe
                CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
        }
        "$dialect owned prototyped declarations retain their complete direct family" {
            val facts = decode(dialect, "prototyped").shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts
            facts.family shouldBe CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC
            facts.declarations.size shouldBe 2
            facts.declarations.all { it.parameterClause is CFunctionParameterClauseEvidence.NonEmpty } shouldBe true
            facts.calls.size shouldBe 1
        }
    }
})
