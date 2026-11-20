package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/** C03/C08/C15: captured literal include bindings must survive path and root changes. */
class CQuotedIncludeClosureSpec : StringSpec({
    val a = Path.of("src/a.c"); val b = Path.of("src/b.c")
    val h = Path.of("include/api.h"); val next = Path.of("include/public/api.h")
    val include = Path.of("include")
    val sources = mapOf(a to "#include \"api.h\"\nint a(void) { return value(); }\n",
        b to "#include \"api.h\"\nint b(void) { return value(); }\n", h to "#pragma once\nint value(void);\n")
    val roots = mapOf(a to listOf(include), b to listOf(include))
    fun facts(text: Map<Path, String> = sources, flags: Map<Path, List<Path>> = roots,
        moves: Map<Path, Path> = emptyMap(), staged: Map<Path, List<Path>> = flags) =
        CQuotedIncludeClosure.analyze(text, flags, moves, staged).shouldBeInstanceOf<CQuotedIncludeReadout.Found>().facts

    "two captured translation units retain their header and root origin" {
        val f = facts(); f.before.size shouldBe 2
        f.before.map { it.target }.toSet() shouldBe setOf(h)
        f.before.map { it.origin }.toSet() shouldBe setOf(1)
        f.after shouldBe f.before
    }
    "quoted includes prefer the captured source parent over target roots" {
        val local = Path.of("src/api.h")
        facts(text = sources + (local to "int value(void);\n")).before.map { it.target }.toSet() shouldBe setOf(local)
    }
    "nested headers retain transitive per-TU bindings" {
        val text = sources + (h to "#pragma once\n#include \"detail.h\"\n") +
            (Path.of("include/detail.h") to "int value(void);\n")
        facts(text).before.size shouldBe 4
    }
    "a public header move rewrites only actual include spelling in each consumer" {
        val f = facts(moves = mapOf(h to next))
        f.edits.size shouldBe 2
        f.edits.map { it.edit.newText }.toSet() shouldBe setOf("public/api.h")
        f.after.map { it.target }.toSet() shouldBe setOf(next)
    }
    "moving a complete source and root tree preserves existing include spelling" {
        val movedA = Path.of("component/a.c"); val movedB = Path.of("component/b.c")
        val movedH = Path.of("component/include/api.h")
        val f = facts(moves = mapOf(a to movedA, b to movedB, h to movedH),
            staged = mapOf(movedA to listOf(Path.of("component/include")), movedB to listOf(Path.of("component/include"))))
        f.edits.size shouldBe 0
        f.after.map { it.translationUnit }.toSet() shouldBe setOf(movedA, movedB)
    }
    "the compiler root search order determines which owned header is selected" {
        val first = Path.of("first/api.h"); val second = Path.of("second/api.h")
        val f = facts(text = sources + (first to "int value(void);\n") + (second to "int other(void);\n"),
            flags = mapOf(a to listOf(Path.of("second"), Path.of("first")), b to listOf(Path.of("first"), Path.of("second"))))
        f.before.associate { it.translationUnit to it.target } shouldBe mapOf(a to second, b to first)
    }
    "missing translation units cannot masquerade as a complete consumer set" {
        CQuotedIncludeClosure.analyze(sources, roots.filterKeys { it != b }) shouldBe
            CQuotedIncludeReadout.Refused(CQuotedIncludeRefusal.INCOMPLETE_TUS)
    }
    "an unresolved literal include is unavailable rather than an external clean input" {
        val result = CQuotedIncludeClosure.analyze(sources.filterKeys { it != h }, roots)
        withClue("Observed graph: ${(result as? CQuotedIncludeReadout.Found)?.facts?.before}") {
            result shouldBe CQuotedIncludeReadout.Refused(CQuotedIncludeRefusal.UNOWNED_INCLUDE)
        }
    }
    "macro includes cannot create literal source authority" {
        CQuotedIncludeClosure.analyze(sources + (a to "#include HEADER\n"), roots) shouldBe
            CQuotedIncludeReadout.Refused(CQuotedIncludeRefusal.UNSUPPORTED_SOURCE)
    }
    "conditional include configurations remain unqualified" {
        CQuotedIncludeClosure.analyze(sources + (a to "#if FLAG\n#include \"api.h\"\n#endif\n"), roots) shouldBe
            CQuotedIncludeReadout.Refused(CQuotedIncludeRefusal.UNSUPPORTED_SOURCE)
    }
    "an escaping include search candidate cannot borrow an unowned absence assertion" {
        CQuotedIncludeClosure.analyze(sources + (a to "#include \"../../outside.h\"\n"), roots) shouldBe
            CQuotedIncludeReadout.Refused(CQuotedIncludeRefusal.PATH_UNAVAILABLE)
    }
    "include cycles are typed refusals even when a header contains pragma once" {
        val result = CQuotedIncludeClosure.analyze(sources + (h to "#pragma once\n#include \"api.h\"\n"), roots)
        withClue("Observed graph: ${(result as? CQuotedIncludeReadout.Found)?.facts?.before}") {
            result shouldBe CQuotedIncludeReadout.Refused(CQuotedIncludeRefusal.CYCLE)
        }
    }
    "include-shaped block comments are not active compiler directives" {
        val f = facts(text = sources + (a to "/*\n#include \"ghost.h\"\n*/\nint a(void) { return 0; }\n"))
        f.before.map { it.translationUnit }.toSet() shouldBe setOf(b)
    }
    "an uncompiled header with includes cannot disappear from closure" {
        CQuotedIncludeClosure.analyze(sources + (Path.of("include/unused.h") to "#include \"api.h\"\n"), roots) shouldBe
            CQuotedIncludeReadout.Refused(CQuotedIncludeRefusal.UNOBSERVED_INCLUDE)
    }
    "retained include observations and edits are immutable" {
        val f = facts(moves = mapOf(h to next))
        runCatching { (f.before as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.after as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.edits as MutableList).clear() }.isFailure shouldBe true
    }
})
