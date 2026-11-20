package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** REQ-C-STRUCTURAL-INDEX-001: addition produces a persistent bounded index value. */
class CSymbolIndexAdditionSpec : StringSpec({
    fun unit(file: String, namespace: CIdentifierNamespace = CIdentifierNamespace.ORDINARY) =
        CTranslationUnitIndex(file, listOf(CSymbolIdentity(namespace, "point", file, CVisibility.GLOBAL)))

    "a new unit is queryable in the returned index while the original stays unchanged" {
        val original = CSymbolIndex(emptyList())
        val added = unit("src/point.c")
        val returned: Any? = original.add(added)
        val updated = returned.shouldBeInstanceOf<CSymbolIndex>()
        updated.units shouldBe listOf(added)
        updated.search("point") shouldBe added.symbols
        original.units shouldBe emptyList()
        original.search("point") shouldBe emptyList()
    }

    "successive additions retain existing same-spelled symbols in their lookup spaces" {
        val ordinary = unit("src/value.c")
        val tag = unit("src/tag.c", CIdentifierNamespace.TAG)
        val member = unit("src/member.c", CIdentifierNamespace.MEMBER)
        val original = CSymbolIndex(listOf(ordinary))
        val firstReturn: Any? = original.add(tag)
        val first = firstReturn.shouldBeInstanceOf<CSymbolIndex>()
        val secondReturn: Any? = first.add(member)
        val second = secondReturn.shouldBeInstanceOf<CSymbolIndex>()
        second.units shouldBe listOf(ordinary, tag, member)
        second.search("point") shouldBe ordinary.symbols + tag.symbols + member.symbols
        second.definitions(CIdentifierNamespace.TAG, "point") shouldBe tag.symbols
        second.definitions(CIdentifierNamespace.ORDINARY, "point") shouldBe ordinary.symbols
        first.units shouldBe listOf(ordinary, tag)
        original.units shouldBe listOf(ordinary)
    }

    "adding the last admitted unit succeeds without altering the preceding index" {
        val original = CSymbolIndex((0 until CSymbolIndex.MAX_UNITS - 1).map {
            CTranslationUnitIndex("src/file$it.c", emptyList())
        })
        val added = unit("src/last.c")
        val returned: Any? = original.add(added)
        val updated = returned.shouldBeInstanceOf<CSymbolIndex>()
        updated.units.size shouldBe CSymbolIndex.MAX_UNITS
        updated.units.last() shouldBe added
        updated.search("point") shouldBe added.symbols
        original.units.size shouldBe CSymbolIndex.MAX_UNITS - 1
    }

    "a duplicate translation-unit file is rejected and the original remains queryable" {
        val existing = unit("src/point.c")
        val original = CSymbolIndex(listOf(existing))
        shouldThrow<IllegalArgumentException> {
            original.add(unit(existing.file, CIdentifierNamespace.TAG))
        }.message shouldBe "C symbol index contains duplicate translation units"
        original.units shouldBe listOf(existing)
        original.search("point") shouldBe existing.symbols
    }

    "a unit beyond the admitted capacity is rejected without changing the full index" {
        val original = CSymbolIndex((0 until CSymbolIndex.MAX_UNITS).map {
            CTranslationUnitIndex("src/file$it.c", emptyList())
        })
        shouldThrow<IllegalArgumentException> {
            original.add(unit("src/overflow.c"))
        }.message shouldBe "C symbol index unit count exceeds the bounded limit"
        original.units.size shouldBe CSymbolIndex.MAX_UNITS
        original.search("point") shouldBe emptyList()
    }
})
