package org.refactorkit.treesitter

import org.refactorkit.core.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BonedeTreeSitterBindingTest {
    @Test
    fun packagedBindingsLoadForJavascriptAndTypescript() {
        val binding = BonedeTreeSitterBinding()
        assertTrue(binding.supports("typescript"))
        assertTrue(binding.supports("javascript"))
        assertFalse(binding.supports("python"))
    }

    @Test
    fun typescriptOutlineComesFromNamedSyntaxNodes() {
        val code = """
            // class Fake {}
            const text = "function alsoFake() {}";
            export interface Service { run(): void }
            export class Réel {
              run(): void {}
            }
            export function build(): Réel { return new Réel(); }
        """.trimIndent()

        val items = BonedeTreeSitterBinding().outline(code, "typescript")

        assertEquals(listOf("Service", "Réel", "run", "build"), items.map { it.name })
        assertEquals(
            listOf(
                GenericOutline.OutlineItem.Kind.INTERFACE,
                GenericOutline.OutlineItem.Kind.CLASS,
                GenericOutline.OutlineItem.Kind.METHOD,
                GenericOutline.OutlineItem.Kind.FUNCTION,
            ),
            items.map { it.kind },
        )
    }

    @Test
    fun classifiesTypeAliasesParametersAndNamespacesAtExactUtf16Positions() {
        val code = "type UserId = string;\nfunction find(id: UserId): void {}\nnamespace Api {}\n"
        val binding = BonedeTreeSitterBinding()

        assertEquals(Symbol.Kind.TYPE_ALIAS, binding.symbolKindAt(code, 0, 5, "typescript"))
        assertEquals(Symbol.Kind.PARAMETER, binding.symbolKindAt(code, 1, 14, "typescript"))
        assertEquals(Symbol.Kind.NAMESPACE, binding.symbolKindAt(code, 2, 10, "typescript"))
    }

    @Test
    fun identifierSearchExcludesCommentsAndStringLiterals() {
        val code = """
            const Foo = 1;
            // Foo must not match
            const text = "Foo";
            function use() { return Foo; }
        """.trimIndent()

        val matches = BonedeTreeSitterBinding().findIdentifier(code, "Foo", "javascript")

        assertEquals(2, matches.size)
        assertEquals(listOf(0, 3), matches.map { it.line })
    }

    @Test
    fun defaultAdapterUsesRealParserAndFallsBackForOtherLanguages() {
        val adapter = TreeSitterAdapter()
        val typescript = adapter.outline("// class Fake {}\nclass Real {}", "typescript")
        assertEquals(listOf("Real"), typescript.map { it.name })

        val python = adapter.outline("class PythonType:\n    pass", "python")
        assertEquals(listOf("PythonType"), python.map { it.name })
    }
}
