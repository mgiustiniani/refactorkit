package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path

/** REQ-C04-QUERY-001: complete readonly SDK graph with distinct public location ownership. */
class CCompilerReadSourcesSpec : StringSpec({
    val workspace = Path.of("/unread/workspace")
    val sdk = Path.of("/unread/sdk")
    val header = Path.of("include/api.h")
    val sdkHeader = sdk.resolve("usr/include/api.h")
    val resource = requireNotNull(CCompilerReadSourcesSpec::class.java.getResource("/typedef-family-rename/native-fixture.json")).readText()
    val fixture = Json.parseToJsonElement(resource).jsonObject.getValue("phases").jsonObject.getValue("before").jsonObject
    val material = fixture.getValue("sources").jsonObject.map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
    val owned = material.filterKeys { it != header }
    val readonly = mapOf(sdkHeader to material.getValue(header))
    val record = CReadOnlySdkProvenance(sdk, "fixture:native-typedef-sdk", "LicenseRef-RefactorKitFixture", "a".repeat(64))
    fun create(sources: Map<Path, String> = owned, paths: Map<Path, Path> = sources.keys.associateWith { it },
        external: Map<Path, String> = readonly, records: List<CReadOnlySdkProvenance> = listOf(record)) =
        CCompilerReadSources.create(workspace, sources, paths, external, records)
    fun projection() = create().shouldBeInstanceOf<CCompilerReadSourceResult.Available>().sources
    fun remap(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, v) ->
            if (key == "file" && v is JsonPrimitive && v.isString && v.content == header.toString()) JsonPrimitive(sdkHeader.toString()) else remap(v)
        })
        is JsonArray -> JsonArray(value.map(::remap))
        else -> value
    }
    val asts = fixture.getValue("asts").jsonObject.map { (p, v) -> Path.of(p) to remap(v).jsonObject }.toMap()
    fun index(p: CCompilerReadSources = projection()) = CCompilerDeclarationIndex.analyze(asts, p.sources, p.logicalPaths)
        .shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index

    "absent SDK inputs preserve workspace logical paths without inventing an external identity" {
        val p = create(material, external = emptyMap(), records = emptyList()).shouldBeInstanceOf<CCompilerReadSourceResult.Available>().sources
        p.sources shouldBe material; p.logicalPaths shouldBe material.keys.associateWith { it }
        p.sdkIdentity shouldBe null; p.sdkPath(header) shouldBe null
    }
    "readonly SDK input receives a distinct logical name and retains its real external location" {
        val p = projection()
        val expected = Path.of(".refactorkit/readonly-sdk").resolve(record.identity).resolve("usr/include/api.h")
        p.logicalPaths.getValue(sdkHeader) shouldBe expected
        p.sdkPath(expected) shouldBe sdkHeader; p.logicalSdkPath(sdkHeader) shouldBe expected
        p.sdkRecord(expected) shouldBe record
        p.logicalPaths.filterKeys { it in owned } shouldBe owned.keys.associateWith { it }
    }
    "native SDK typedef declarations enter the complete existing compiler index" {
        val p = projection()
        val selected = index(p).declarations.filter { it.name == "value_t" && it.kind == CCompilerDeclarationKind.TYPEDEF }
        selected.size shouldBe 2
        selected.map { p.sdkPath(it.file) }.toSet() shouldBe setOf(sdkHeader)
        selected.map { it.translationUnit }.toSet() shouldBe asts.keys
    }
    "native SDK typedef definitions and references connect readonly header and workspace consumers" {
        val p = projection(); val selected = index(p).declarations.first { it.name == "value_t" && it.kind == CCompilerDeclarationKind.TYPEDEF }
        val facts = CCompilerNavigationFactsDecoder.decode(asts, p.sources, selected.id, p.logicalPaths)
            .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
        facts.definitions.size shouldBe 1
        p.sdkPath(facts.definitions.single().file) shouldBe sdkHeader
        facts.references.any { it.file == Path.of("src/api.c") } shouldBe true
        facts.references.any { it.file == Path.of("src/main.c") } shouldBe true
    }
    "SDK identity changes invalidate SDK declaration IDs while preserving workspace physical identities" {
        val p = projection()
        val newer = create(records = listOf(record.copy(identity = "b".repeat(64)))).shouldBeInstanceOf<CCompilerReadSourceResult.Available>().sources
        (p.sdkIdentity == newer.sdkIdentity) shouldBe false
        index(p).declarations.filter { p.sdkPath(it.file) != null }.map { it.id }.toSet()
            .intersect(index(newer).declarations.filter { newer.sdkPath(it.file) != null }.map { it.id }.toSet()).isEmpty() shouldBe true
        index(p).declarations.filter { it.file == Path.of("src/main.c") }.map { it.id } shouldBe
            index(newer).declarations.filter { it.file == Path.of("src/main.c") }.map { it.id }
    }
    "retained source maps remain immutable after caller input mutation" {
        val sources = owned.toMutableMap(); val extra = readonly.toMutableMap(); val records = mutableListOf(record)
        val p = create(sources, external = extra, records = records).shouldBeInstanceOf<CCompilerReadSourceResult.Available>().sources
        sources.clear(); extra.clear(); records.clear()
        p.sources.size shouldBe material.size
        runCatching { (p.sources as MutableMap).clear() }.isFailure shouldBe true
        runCatching { (p.logicalPaths as MutableMap).clear() }.isFailure shouldBe true
    }
    "readonly input outside declared SDK roots cannot become captured query authority" {
        create(external = mapOf(Path.of("/unread/other.h") to "typedef int x;\n")) shouldBe
            CCompilerReadSourceResult.Refused(CCompilerReadSourceRefusal.INVALID_SDK_SOURCE)
    }
    "a missing SDK source cannot be replaced with provenance metadata alone" {
        create(external = emptyMap()) shouldBe CCompilerReadSourceResult.Refused(CCompilerReadSourceRefusal.INVALID_SDK_SOURCE)
    }
    "workspace and SDK ownership cannot overlap physically or in the reserved logical namespace" {
        create(sources = owned + (sdkHeader to "typedef int shadow;\n"), paths = owned.keys.associateWith { it } + (sdkHeader to Path.of("shadow.h"))) shouldBe
            CCompilerReadSourceResult.Refused(CCompilerReadSourceRefusal.OWNERSHIP_COLLISION)
        create(paths = owned.keys.associateWith { Path.of(".refactorkit/readonly-sdk").resolve(it) }) shouldBe
            CCompilerReadSourceResult.Refused(CCompilerReadSourceRefusal.OWNERSHIP_COLLISION)
    }
    "SDK metadata cannot grant roots inside the workspace" {
        create(records = listOf(record.copy(root = workspace.resolve("sdk")))) shouldBe
            CCompilerReadSourceResult.Refused(CCompilerReadSourceRefusal.INVALID_SDK_SCOPE)
    }
})
