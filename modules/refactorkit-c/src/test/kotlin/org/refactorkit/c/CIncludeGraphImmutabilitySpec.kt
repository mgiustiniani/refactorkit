package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.refactorkit.core.Diagnostic
import java.nio.file.Path

/** Spec BDD for the include graph's defensive value boundary. */
class CIncludeGraphImmutabilitySpec : StringSpec({
    val diagnostic = Diagnostic("include input exceeds its bound", Diagnostic.Severity.WARNING)
    val main = Path.of("src/main.c")
    val header = Path.of("include/api.h")

    "include directives snapshot caller-owned diagnostic lists" {
        val suppliedDiagnostics = mutableListOf(diagnostic)
        val directive = CIncludeDirective(
            line = 1,
            target = "api.h",
            kind = CIncludeKind.QUOTED,
            resolved = header,
            ownership = CFileOwnership.PUBLIC,
            diagnostics = suppliedDiagnostics,
        )

        suppliedDiagnostics.clear()

        directive.diagnostics shouldBe listOf(diagnostic)
        shouldThrow<UnsupportedOperationException> {
            (directive.diagnostics as MutableList<Diagnostic>).clear()
        }
        directive.copy() shouldBe directive
        val copiedDiagnostics = mutableListOf(diagnostic)
        val copiedDirective = directive.copy(diagnostics = copiedDiagnostics)
        copiedDiagnostics.clear()
        copiedDirective.diagnostics shouldBe listOf(diagnostic)
    }

    "include graphs snapshot input collections and expose read-only views" {
        val suppliedFiles = mutableListOf(main, header)
        val suppliedEdges = mutableListOf(CIncludeEdge(main, header, 1, CIncludeKind.QUOTED))
        val suppliedOwnership = linkedMapOf(main to CFileOwnership.PRIVATE, header to CFileOwnership.PUBLIC)
        val suppliedDiagnostics = mutableListOf(diagnostic)
        val graph = CIncludeGraph(suppliedFiles, suppliedEdges, suppliedOwnership, suppliedDiagnostics)

        suppliedFiles.clear()
        suppliedEdges.clear()
        suppliedOwnership.clear()
        suppliedDiagnostics.clear()

        graph.files shouldBe listOf(main, header)
        graph.edges shouldBe listOf(CIncludeEdge(main, header, 1, CIncludeKind.QUOTED))
        graph.ownership shouldBe mapOf(main to CFileOwnership.PRIVATE, header to CFileOwnership.PUBLIC)
        graph.diagnostics shouldBe listOf(diagnostic)
        graph.copy() shouldBe graph
        val copiedFiles = mutableListOf(main)
        val copiedGraph = graph.copy(files = copiedFiles)
        copiedFiles.clear()
        copiedGraph.files shouldBe listOf(main)
        shouldThrow<UnsupportedOperationException> { (graph.files as MutableList<Path>).clear() }
        shouldThrow<UnsupportedOperationException> { (graph.edges as MutableList<CIncludeEdge>).clear() }
        shouldThrow<UnsupportedOperationException> { (graph.ownership as MutableMap<Path, CFileOwnership>).clear() }
        shouldThrow<UnsupportedOperationException> { (graph.diagnostics as MutableList<Diagnostic>).clear() }
    }

    "include graphs reject duplicate edge identities but retain repeated source includes on distinct lines" {
        val edge = CIncludeEdge(main, header, 1, CIncludeKind.QUOTED)
        val ownership = mapOf(main to CFileOwnership.PRIVATE, header to CFileOwnership.PUBLIC)

        shouldThrow<IllegalArgumentException> {
            CIncludeGraph(listOf(main, header), listOf(edge, edge), ownership, emptyList())
        }
        CIncludeGraph(listOf(main, header), listOf(edge, edge.copy(line = 2)), ownership, emptyList()).edges shouldBe
            listOf(edge, edge.copy(line = 2))
    }

    "a filesystem root has unknown ownership rather than dereferencing a missing file name" {
        classifyCFileOwnership(Path.of("/workspace"), Path.of("/"), emptyList()) shouldBe CFileOwnership.UNKNOWN
    }
})
