package org.refactorkit.c

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CIncludeGraphTest {
    @Test
    fun parsesQuotedAngledAndMacroIncludes() {
        val parser = CIncludeDirectiveParser()
        val directives = parser.parse("""
            #include "local.h"
            #include <stdio.h>
            #include SOME_MACRO
            // #include "commented.h"
            int x;
        """.trimIndent())
        assertEquals(listOf("local.h", "stdio.h", "SOME_MACRO"), directives.map { it.target })
        assertEquals(listOf(CIncludeKind.QUOTED, CIncludeKind.ANGLED, CIncludeKind.MACRO), directives.map { it.kind })
        assertEquals(listOf(1, 2, 3), directives.map { it.line })
    }

    @Test
    fun buildsIncludeGraphAndClassifiesOwnership() {
        val workspace = Files.createTempDirectory("refactorkit-c-include")
        val src = workspace.resolve("src")
        val include = workspace.resolve("include")
        src.toFile().mkdirs()
        include.toFile().mkdirs()
        val main = src.resolve("main.c")
        val localHeader = src.resolve("local.h")
        val publicHeader = include.resolve("api.h")
        main.writeText("#include \"local.h\"\n#include <api.h>\n")
        localHeader.writeText("int value;\n")
        publicHeader.writeText("void api(void);\n")

        val builder = CIncludeGraphBuilder()
        val graph = builder.build(
            workspace,
            listOf(main, localHeader, publicHeader),
            listOf(include),
        )

        assertTrue(graph.edges.any { it.to == src.resolve("local.h") })
        assertTrue(graph.edges.any { it.to == include.resolve("api.h") })
        assertEquals(CFileOwnership.PRIVATE, graph.ownership[src.resolve("main.c")])
        assertEquals(CFileOwnership.PRIVATE, graph.ownership[src.resolve("local.h")])
        assertEquals(CFileOwnership.PUBLIC, graph.ownership[include.resolve("api.h")])
    }

    @Test
    fun classifiesGeneratedAndExternalFiles() {
        val workspace = Files.createTempDirectory("refactorkit-c-own")
        val generated = workspace.resolve("gen.generated.h")
        val privateHeader = workspace.resolve("impl.h")
        generated.writeText("guard")
        privateHeader.writeText("int y;")

        val builder = CIncludeGraphBuilder()
        val graph = builder.build(workspace, listOf(generated, privateHeader), listOf(workspace.resolve("include")))

        assertEquals(CFileOwnership.GENERATED, graph.ownership[generated])
        assertEquals(CFileOwnership.PRIVATE, graph.ownership[privateHeader])
    }

    @Test
    fun enforcesBoundedFileAndDirectiveCounts() {
        val workspace = Files.createTempDirectory("refactorkit-c-bounded")
        val big = " ".repeat(3 * 1024 * 1024)
        val parser = CIncludeDirectiveParser()
        val refused = parser.parse(big)
        assertTrue(refused.single().diagnostics.any { it.code == "c.includeFileLimit" })

        val many = (0 until 2000).joinToString("\n") { "#include <h$it.h>" }
        val parsed = parser.parse(many)
        assertTrue(parsed.size <= CIncludeGraphPolicy.DEFAULT_MAX_DIRECTIVES_PER_FILE)
    }

    @Test
    fun resolvesQuotedIncludeAgainstWorkspaceAndIncludeDirs() {
        val workspace = Files.createTempDirectory("refactorkit-c-resolve")
        val src = workspace.resolve("src")
        val include = workspace.resolve("include")
        src.toFile().mkdirs()
        include.toFile().mkdirs()
        val main = src.resolve("main.c")
        val quoted = src.resolve("quoted.h")
        val angled = include.resolve("angled.h")
        main.writeText("#include \"quoted.h\"\n#include <angled.h>\n")
        quoted.writeText("q")
        angled.writeText("a")

        val graph = CIncludeGraphBuilder().build(workspace, listOf(main, quoted, angled), listOf(include))
        assertTrue(graph.edges.any { it.to == src.resolve("quoted.h") })
        assertTrue(graph.edges.any { it.to == include.resolve("angled.h") })
    }
}
