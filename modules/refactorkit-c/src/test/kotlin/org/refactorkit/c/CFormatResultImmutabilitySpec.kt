package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit

/** Spec BDD for the formatter result's defensive value boundary. */
class CFormatResultImmutabilitySpec : StringSpec({
    val edit = TextEdit(SourceRange(SourcePosition(0, 0), SourcePosition(0, 1)), "x")
    val diagnostic = Diagnostic("formatting refused", Diagnostic.Severity.WARNING)

    "accepted formatting results snapshot edits and preserve data-class value behavior" {
        val suppliedEdits = mutableListOf(edit)
        val result = CFormatResult.Accepted(suppliedEdits, idempotent = true)

        suppliedEdits.clear()

        result.edits shouldBe listOf(edit)
        result.copy() shouldBe result
        val (destructuredEdits, idempotent) = result
        destructuredEdits shouldBe listOf(edit)
        idempotent shouldBe true

        val copiedEdits = mutableListOf(edit)
        val copiedResult = result.copy(edits = copiedEdits)
        copiedEdits.clear()
        copiedResult.edits shouldBe listOf(edit)
        shouldThrow<UnsupportedOperationException> {
            (result.edits as MutableList<TextEdit>).clear()
        }
    }

    "refused formatting results snapshot diagnostics and preserve data-class value behavior" {
        val suppliedDiagnostics = mutableListOf(diagnostic)
        val result = CFormatResult.Refused(suppliedDiagnostics)

        suppliedDiagnostics.clear()

        result.diagnostics shouldBe listOf(diagnostic)
        result.copy() shouldBe result
        val (destructuredDiagnostics) = result
        destructuredDiagnostics shouldBe listOf(diagnostic)

        val copiedDiagnostics = mutableListOf(diagnostic)
        val copiedResult = result.copy(diagnostics = copiedDiagnostics)
        copiedDiagnostics.clear()
        copiedResult.diagnostics shouldBe listOf(diagnostic)
        shouldThrow<UnsupportedOperationException> {
            (result.diagnostics as MutableList<Diagnostic>).clear()
        }
    }
})
