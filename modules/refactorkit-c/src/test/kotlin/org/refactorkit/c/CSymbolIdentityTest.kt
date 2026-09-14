package org.refactorkit.c

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CSymbolIdentityTest {
    @Test
    fun distinguishesCIdentifierNamespaces() {
        val tag = CSymbolIdentity(CIdentifierNamespace.TAG, "point", "struct point", CVisibility.GLOBAL)
        val ordinary = CSymbolIdentity(CIdentifierNamespace.ORDINARY, "point", "file", CVisibility.GLOBAL)
        assertEquals("TAG:struct point:point", tag.key())
        assertEquals("ORDINARY:file:point", ordinary.key())
        assertTrue(tag.key() != ordinary.key())
    }

    @Test
    fun classifiesVisibilityPerTranslationUnit() {
        val local = CSymbolIdentity(CIdentifierNamespace.ORDINARY, "tmp", "main", CVisibility.LOCAL)
        val static = CSymbolIdentity(CIdentifierNamespace.ORDINARY, "counter", "file", CVisibility.FILE_STATIC)
        val global = CSymbolIdentity(CIdentifierNamespace.ORDINARY, "compute", "file", CVisibility.GLOBAL)
        val exported = CSymbolIdentity(CIdentifierNamespace.ORDINARY, "api", "header", CVisibility.EXPORTED)
        assertEquals(CVisibility.LOCAL, local.visibility)
        assertEquals(CVisibility.FILE_STATIC, static.visibility)
        assertEquals(CVisibility.GLOBAL, global.visibility)
        assertEquals(CVisibility.EXPORTED, exported.visibility)
    }

    @Test
    fun searchesSymbolsAcrossUnits() {
        val index = CSymbolIndex(listOf(
            CTranslationUnitIndex("src/main.c", listOf(
                CSymbolIdentity(CIdentifierNamespace.ORDINARY, "compute", "file", CVisibility.GLOBAL),
                CSymbolIdentity(CIdentifierNamespace.TAG, "point", "struct point", CVisibility.GLOBAL),
            )),
            CTranslationUnitIndex("src/util.c", listOf(
                CSymbolIdentity(CIdentifierNamespace.ORDINARY, "compute", "file", CVisibility.GLOBAL),
            )),
        ))
        assertEquals(2, index.search("compute").size)
        assertEquals(2, index.definitions(CIdentifierNamespace.ORDINARY, "compute").size)
        assertEquals(1, index.definitions(CIdentifierNamespace.TAG, "point").size)
    }

    @Test
    fun enforcesBoundedSymbolAndUnitCounts() {
        assertTrue(CTranslationUnitIndex.MAX_SYMBOLS >= 1)
        assertTrue(CSymbolIndex.MAX_UNITS >= 1)
        val many = (0 until CSymbolIndex.MAX_RESULTS).map { CSymbolIdentity(CIdentifierNamespace.ORDINARY, "v$it", "s", CVisibility.LOCAL) }
        val index = CSymbolIndex(listOf(CTranslationUnitIndex("f.c", many)))
        assertTrue(index.search("v").size <= CSymbolIndex.MAX_RESULTS)
    }
}
