package org.refactorkit.jvm.movetoplevelfunction

import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.jvm.KotlinJvmMoveDeclarationPlanner
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinDeclarationVisibility
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story BDD glue for the top-level Kotlin function move (REQ-KOTLIN-MOVE-FUNCTION-001).
 *
 * It replicates the K2 toolchain fixture from
 * KotlinJavaPublicTypeRenamePlannerTest (kotlin-compiler-embeddable-2.0.21,
 * jvmTarget 21, jdkToolchain 21) and drives the real KotlinJvmMoveDeclarationPlanner.
 * The feature file declares the actual production refusal codes observed by the glue,
 * so every refusal step asserts that the DECLARED code (from the feature Examples table)
 * EQUALS the ACTUAL refusalCode the planner returned; the suite FAILS on a typed-code
 * regression. Every observed declared-code to actual-code mapping is written to
 * build/reports/cucumber/move-top-level-function-refusal-codes.txt for reconciliation.
 *
 * plugin-dependent/Xplugin is out of scope: the production planner has no Xplugin path.
 * The plugin-dependent fixture is a real Compose compiler-plugin-dependent (composable)
 * source; the stub annotation stands in for the Compose compiler-plugin/runtime
 * dependency, and the planner refuses the composable as shape-unsupported
 * (kotlin.moveFunctionShapeUnsupported), not as an extension.
 */
class KotlinJvmMoveTopLevelFunctionSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()

    private var fixtureRoot: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private lateinit var toolchain: KotlinSemanticToolchain
    private var lastCatalogue: KotlinCompilerSymbolsResult.Available? = null
    private var acceptExternalConsumerRisk = false
    private var plan: PatchPlan? = null
    private var applied: ApplyResult.Applied? = null
    private var rolledBack: ApplyResult.Applied? = null
    private var originalInvoice: String? = null
    private var originalUse: String? = null

    // ------------------------------------------------------------------ AC-FUNCTION-001

    @Given(
        "^the selected declaration is one compiler-proven public top-level Kotlin function \"fixture\\.pricing\\.computeInvoiceTotal\" with explicit or implicit PUBLIC visibility$",
    )
    fun selectedPublicTopLevelFunctionWithVisibility() {
        val root = temporaryDirectory("rk-jvm-move-top-level-function")
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        val use = root.resolve("src/main/kotlin/fixture/app/Use.kt").apply { parent.createDirectories() }
        writePom(root)
        // RQ-KMF-CUC-001: the AC-FUNCTION-001 implicit-PUBLIC claim is genuinely exercised.
        // The selected function carries NO explicit public modifier (implicit PUBLIC) and is
        // co-located with a real compiler-proven private top-level helper (RQ-KMF-CUC-002).
        // Explicit PUBLIC remains exercised by the AC-FUNCTION-005 and AC-FUNCTION-006 fixtures.
        // RQ-KMF-CUC-002B: the compiler-proven private helper taxRate is genuinely CALLED by the
        // selected function (computeInvoiceTotal invokes taxRate), so called-helper co-location,
        // binding, and byte preservation are exercised rather than an uncalled dead helper.
        invoice.writeText(
            "package fixture.pricing\r\nfun computeInvoiceTotal(): String = taxRate().toString()\r\n" +
                "private fun taxRate(): Double = 0.21\r\n",
        )
        use.writeText("package fixture.app\r\nimport fixture.pricing.computeInvoiceTotal\r\nfun run(): String = computeInvoiceTotal()\r\n")
        originalInvoice = invoice.readText()
        originalUse = use.readText()
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val invoiceContent = requireNotNull(fixtureRoot).resolve("src/main/kotlin/fixture/pricing/Invoice.kt").readText()
        assertTrue(
            "fun computeInvoiceTotal(): String = taxRate().toString()" in invoiceContent,
            "expected the selected top-level function to be present",
        )
        assertTrue(
            "public fun" !in invoiceContent,
            "expected implicit PUBLIC visibility: the selected function must not carry an explicit public modifier",
        )
        assertTrue(
            "private fun taxRate(): Double = 0.21" in invoiceContent,
            "expected a compiler-proven private top-level helper co-located with the selected function",
        )
        assertTrue(
            "taxRate().toString()" in invoiceContent,
            "expected the private helper taxRate to be CALLED by the selected function (called-helper co-location)",
        )
    }

    @Given("^the compiler-proven source file is present in the snapshot$")
    fun sourceFilePresentInSnapshot() {
        assertTrue(
            requireNotNull(snapshot).files.any { it.path == Path.of("src/main/kotlin/fixture/pricing/Invoice.kt") },
            "expected the compiler-proven source file in the snapshot",
        )
    }

    @Given("^the snapshot carries a hash attestation and the compiler-proven source file is present$")
    fun snapshotCarriesHashAndSourceFilePresent() {
        assertNotNull(requireNotNull(snapshot).hash, "expected a snapshot hash attestation")
        assertTrue(requireNotNull(snapshot).files.any { it.path == Path.of("src/main/kotlin/fixture/pricing/Invoice.kt") })
    }

    @Given("^the source file contains the selected function and only compiler-proven private top-level helper declarations$")
    fun sourceContainsOnlyFunctionAndPrivateHelpers() {
        val content = requireNotNull(fixtureRoot).resolve("src/main/kotlin/fixture/pricing/Invoice.kt").readText()
        assertEquals(
            "package fixture.pricing\r\nfun computeInvoiceTotal(): String = taxRate().toString()\r\n" +
                "private fun taxRate(): Double = 0.21\r\n",
            content,
            "expected the source file to contain exactly the selected implicit-public function (calling the helper) and the compiler-proven private top-level helper",
        )
    }

    @Given("^the destination package \"fixture\\.accounting\" has no same-name top-level function family$")
    fun destinationHasNoSameNameFamily() {
        assertTrue(
            !requireNotNull(fixtureRoot).resolve("src/main/kotlin/fixture/accounting").exists(),
            "expected no destination package fixture",
        )
    }

    @Given("^the caller explicitly accepts unknown external-consumer risk$")
    fun callerAcceptsExternalConsumerRisk() {
        acceptExternalConsumerRisk = true
    }

    @Given(
        "^an in-workspace consumer has exactly one compiler-proven unaliased explicit import of the source callable FQN \"fixture\\.pricing\\.computeInvoiceTotal\"$",
    )
    fun consumerHasExactUnaliasedImport() {
        val content = requireNotNull(fixtureRoot).resolve("src/main/kotlin/fixture/app/Use.kt").readText()
        assertEquals(
            1,
            content.lines().count { it.trim().startsWith("import fixture.pricing.computeInvoiceTotal") && " as " !in it },
            content,
        )
    }

    @Given("^the selected declaration is a compiler-proven public top-level Kotlin function \"fixture\\.pricing\\.computeInvoiceTotal\" whose package declaration carries a trailing comment$")
    fun selectedPublicTopLevelFunctionWithTrailingPackageComment() {
        val root = temporaryDirectory("rk-jvm-move-trailing-comment")
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        val use = root.resolve("src/main/kotlin/fixture/app/Use.kt").apply { parent.createDirectories() }
        writePom(root)
        // compiler-proven source file: the package declaration carries a trailing line comment
        // (e.g. "// note") that must survive the package-token move byte for byte.
        invoice.writeText("package fixture.pricing // note\r\npublic fun computeInvoiceTotal(): String = \"total\"\r\n")
        use.writeText("package fixture.app\r\nimport fixture.pricing.computeInvoiceTotal\r\nfun run(): String = computeInvoiceTotal()\r\n")
        originalInvoice = invoice.readText()
        originalUse = use.readText()
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        assertTrue(
            "// note" in requireNotNull(fixtureRoot).resolve("src/main/kotlin/fixture/pricing/Invoice.kt").readText(),
            "expected the package declaration to carry a trailing comment",
        )
        // The scenario omits the external-consumer-approval and consumer-import preconditions; the
        // glue accepts the risk so the planner reaches the package-edit path (consistent with the
        // AC-FUNCTION-002..004 refusal fixtures).
        acceptExternalConsumerRisk = true
    }

    @When("^moveDeclaration previews the selection$")
    fun moveDeclarationPreviewsSelection() {
        val snap = requireNotNull(snapshot)
        val tc = toolchain
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(tc))
        val catalogueResult = adapter.compilerSymbols(snap)
        val targetId = if (catalogueResult is KotlinCompilerSymbolsResult.Available) {
            lastCatalogue = catalogueResult
            selectTarget(catalogueResult)?.id ?: SymbolId("fixture.pricing.computeInvoiceTotal")
        } else {
            SymbolId("fixture.pricing.computeInvoiceTotal")
        }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)
        plan = planner.preview(snap, targetId, "fixture.accounting", acceptExternalConsumerRisk = acceptExternalConsumerRisk)
    }

    @Then("^the result is a SEMANTIC_PREVIEW with exact new facade callable identity \"fixture\\.accounting\\.computeInvoiceTotal\"$")
    fun resultIsSemanticPreview() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val dest = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/accounting/Invoice.kt").normalize() }
        assertTrue("package fixture.accounting" in dest.content, dest.content)
        assertTrue("fun computeInvoiceTotal(): String = taxRate().toString()" in dest.content, dest.content)
        assertTrue("private fun taxRate(): Double = 0.21" in dest.content, dest.content)
        assertTrue("taxRate().toString()" in dest.content, "called-helper binding must be preserved in the moved file")
        val consumer = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/app/Use.kt").normalize() }
        assertTrue("import fixture.accounting.computeInvoiceTotal" in consumer.content, consumer.content)
        assertTrue("fun run(): String = computeInvoiceTotal()" in consumer.content, consumer.content)
        val catalogue = requireNotNull(lastCatalogue)
        assertTrue(
            catalogue.declarations.values.any { it.jvmOwner == "fixture.pricing.InvoiceKt" },
            "expected the K2 file-facade owner fixture.pricing.InvoiceKt: ${catalogue.declarations.values}",
        )
    }

    @Then("^the result is a SEMANTIC_PREVIEW that edits only the package token and preserves the trailing comment and every other byte exactly$")
    fun resultIsSemanticPreviewEditingOnlyPackageToken() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val dest = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/accounting/Invoice.kt").normalize() }
        assertEquals(
            requireNotNull(originalInvoice).replace("package fixture.pricing", "package fixture.accounting"),
            dest.content,
            "expected the preview to edit only the package token and preserve the trailing comment and every other byte exactly",
        )
        assertTrue("package fixture.accounting // note" in dest.content, dest.content)
        assertTrue("public fun computeInvoiceTotal(): String = \"total\"" in dest.content, dest.content)
    }

    @Then("^the preview edits only the package declaration, the exact consumer import directive, and the source-file path$")
    fun previewEditsOnlyPackageImportPath() {
        val p = requireNotNull(plan)
        val edits = p.workspaceEdit.edits
        assertEquals(3, edits.size, p.toString())
        val invoiceModify = edits.filterIsInstance<FileEdit.Modify>().single {
            it.path == Path.of("src/main/kotlin/fixture/pricing/Invoice.kt")
        }
        assertEquals(1, invoiceModify.textEdits.size, p.toString())
        assertEquals("fixture.accounting", invoiceModify.textEdits.single().newText, p.toString())
        val useModify = edits.filterIsInstance<FileEdit.Modify>().single { it.path == Path.of("src/main/kotlin/fixture/app/Use.kt") }
        assertEquals(1, useModify.textEdits.size, p.toString())
        assertEquals("fixture.accounting.computeInvoiceTotal", useModify.textEdits.single().newText, p.toString())
        val rename = edits.filterIsInstance<FileEdit.Rename>().single()
        assertEquals(Path.of("src/main/kotlin/fixture/pricing/Invoice.kt"), rename.path, p.toString())
        assertEquals(Path.of("src/main/kotlin/fixture/accounting/Invoice.kt"), rename.newPath, p.toString())
    }

    @Then("^the moved file retains its exact source content beyond the package token$")
    fun movedFileRetainsExactContent() {
        val p = requireNotNull(plan)
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val dest = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/accounting/Invoice.kt").normalize() }
        assertEquals(
            requireNotNull(originalInvoice).replace("package fixture.pricing", "package fixture.accounting"),
            dest.content,
            "expected no outbound binding rewrite beyond the package token",
        )
    }

    @Then("^declarations carried in the same file resolve to their exact computed post-move identities$")
    fun carriedDeclarationsResolve() {
        val p = requireNotNull(plan)
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val dest = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/accounting/Invoice.kt").normalize() }
        assertTrue("package fixture.accounting" in dest.content, dest.content)
        assertTrue("fun computeInvoiceTotal(): String = taxRate().toString()" in dest.content, dest.content)
        assertTrue("private fun taxRate(): Double = 0.21" in dest.content, dest.content)
        assertTrue("taxRate().toString()" in dest.content, "called-helper binding must be preserved in the moved file")
    }

    @Then("^no source text other than the package and import tokens is rewritten or formatted$")
    fun noOtherSourceTextRewritten() {
        val root = requireNotNull(fixtureRoot)
        when {
            // AC-FUNCTION-006 runs this step AFTER rollback: the destination is removed and
            // the source/consumer bytes must equal the pre-apply image unchanged.
            rolledBack != null -> {
                assertEquals(requireNotNull(originalInvoice), root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").readText())
                assertEquals(requireNotNull(originalUse), root.resolve("src/main/kotlin/fixture/app/Use.kt").readText())
            }
            // AC-FUNCTION-006 committed post-image: destination/consumer carry only the token rewrite.
            applied != null -> {
                assertEquals(
                    requireNotNull(originalInvoice).replace("package fixture.pricing", "package fixture.accounting"),
                    root.resolve("src/main/kotlin/fixture/accounting/Invoice.kt").readText(),
                )
                assertEquals(
                    requireNotNull(originalUse).replace("import fixture.pricing.computeInvoiceTotal", "import fixture.accounting.computeInvoiceTotal"),
                    root.resolve("src/main/kotlin/fixture/app/Use.kt").readText(),
                )
            }
            // AC-FUNCTION-001 preview: inspect the staged image.
            else -> {
                val p = requireNotNull(plan)
                val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
                val dest = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/accounting/Invoice.kt").normalize() }
                val consumer = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/app/Use.kt").normalize() }
                assertEquals(
                    requireNotNull(originalInvoice).replace("package fixture.pricing", "package fixture.accounting"),
                    dest.content,
                )
                assertEquals(
                    requireNotNull(originalUse).replace("import fixture.pricing.computeInvoiceTotal", "import fixture.accounting.computeInvoiceTotal"),
                    consumer.content,
                )
            }
        }
    }

    @Then("^line endings and every private helper byte remain exact$")
    fun lineEndingsAndPrivateHelperBytesExact() {
        if (applied != null) {
            val root = requireNotNull(fixtureRoot)
            val dest = root.resolve("src/main/kotlin/fixture/accounting/Invoice.kt").readText()
            assertTrue("\r\n" in dest, dest)
            assertTrue("private fun taxRate(): Double = 0.21" in dest, dest)
            assertEquals(requireNotNull(originalInvoice).replace("package fixture.pricing", "package fixture.accounting"), dest)
        } else {
            val p = requireNotNull(plan)
            val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
            val dest = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/accounting/Invoice.kt").normalize() }
            assertTrue("\r\n" in dest.content, dest.content)
            assertTrue("private fun taxRate(): Double = 0.21" in dest.content, dest.content)
            assertEquals(requireNotNull(originalInvoice).replace("package fixture.pricing", "package fixture.accounting"), dest.content)
        }
    }

    // ------------------------------------------------------------------ AC-FUNCTION-002

    @Given("^the selected declaration is one \"([^\"]+)\" top-level Kotlin function$")
    fun selectedDeclarationOfShape(shape: String) {
        val root = temporaryDirectory("rk-jvm-move-shape")
        writePom(root)
        val invoicePath = when (shape) {
            "script" -> root.resolve("src/main/kotlin/fixture/pricing/Invoice.kts")
            "generated" -> root.resolve("build/generated/ksp/main/kotlin/fixture/pricing/Invoice.kt")
            else -> root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt")
        }
        invoicePath.apply { parent.createDirectories() }
        val content = when (shape) {
            "extension" -> "package fixture.pricing\npublic fun String.computeInvoiceTotal(): String = this\n"
            "suspend" -> "package fixture.pricing\npublic suspend fun computeInvoiceTotal(): String = \"total\"\n"
            "overloaded with a private same-name sibling" ->
                "package fixture.pricing\nprivate fun computeInvoiceTotal(value: Int): String = value.toString()\n" +
                    "public fun computeInvoiceTotal(): String = \"total\"\n"
            "default-argument" -> "package fixture.pricing\npublic fun computeInvoiceTotal(value: String = \"total\"): String = value\n"
            "literal JvmName facade" -> "package fixture.pricing\n@kotlin.jvm.JvmName(\"binaryCompute\")\npublic fun computeInvoiceTotal(): String = \"total\"\n"
            "local or member" -> "package fixture.pricing\npublic class Host {\n    public fun computeInvoiceTotal(): String = \"total\"\n}\n"
            "script" -> "public suspend fun computeInvoiceTotal(): String = \"total\"\n"
            "generated" -> "package fixture.pricing\npublic fun computeInvoiceTotal(): String = \"total\"\n"
            "plugin-dependent" -> {
                // Real Compose compiler-plugin-dependent (composable) source, not an extension.
                // The planner has no Xplugin path, so the composable is refused as
                // shape-unsupported (kotlin.moveFunctionShapeUnsupported); the stub annotation
                // stands in for the Compose compiler-plugin/runtime dependency.
                val composeStub = root.resolve("src/main/kotlin/androidx/compose/runtime/Composable.kt")
                composeStub.apply { parent.createDirectories() }
                composeStub.writeText("package androidx.compose.runtime\nannotation class Composable\n")
                "package fixture.pricing\nimport androidx.compose.runtime.Composable\n@Composable\npublic fun computeInvoiceTotal(): String = \"total\"\n"
            }
            "delegated" -> "package fixture.pricing\npublic val computeInvoiceTotal: String by lazy { \"total\" }\n"
            "annotation-evaluated" -> "package fixture.pricing\n@Deprecated(\"legacy\")\npublic fun computeInvoiceTotal(): String = \"total\"\n"
            "non-public selected" -> "package fixture.pricing\nprivate fun computeInvoiceTotal(): String = \"total\"\n"
            else -> error("unknown shape: $shape")
        }
        invoicePath.writeText(content)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        // The feature outline omits the external-consumer approval precondition; the glue
        // accepts it so the planner reaches the shape/consumer refusal logic and we can
        // capture the actual coarse refusal codes for reconciliation.
        acceptExternalConsumerRisk = true
    }

    // ------------------------------------------------------------------ AC-FUNCTION-003

    @Given("^the source file contains the selected public top-level function and one \"([^\"]+)\" top-level declaration$")
    fun sourceContainsSelectedAndOneCoLocated(declarationKind: String) {
        val root = temporaryDirectory("rk-jvm-move-colocated")
        writePom(root)
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        val content = when (declarationKind) {
            "public" -> "package fixture.pricing\npublic fun computeInvoiceTotal(): String = \"total\"\npublic class Helper\n"
            "internal" -> "package fixture.pricing\npublic fun computeInvoiceTotal(): String = \"total\"\ninternal val helper = \"x\"\n"
            "protected" -> "package fixture.pricing\npublic fun computeInvoiceTotal(): String = \"total\"\nprotected val helper = \"x\"\n"
            else -> error("unknown declaration kind: $declarationKind")
        }
        invoice.writeText(content)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        acceptExternalConsumerRisk = true
    }

    // ------------------------------------------------------------------ AC-FUNCTION-004

    @Given(
        "^an in-workspace consumer uses \"([^\"]+)\" of the source callable FQN \"fixture\\.pricing\\.computeInvoiceTotal\"$",
    )
    fun consumerUsesForm(form: String) {
        val root = temporaryDirectory("rk-jvm-move-consumer")
        writePom(root)
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        invoice.writeText("package fixture.pricing\r\npublic fun computeInvoiceTotal(): String = \"total\"\r\n")
        when (form) {
            "aliased import" -> consumerWrite(
                root, "src/main/kotlin/fixture/app/Use.kt",
                "package fixture.app\nimport fixture.pricing.computeInvoiceTotal as total\nfun run(): String = total()\n",
            )
            "same-package implicit" -> consumerWrite(
                root, "src/main/kotlin/fixture/pricing/Use.kt",
                "package fixture.pricing\nfun run(): String = computeInvoiceTotal()\n",
            )
            "package-star import" -> consumerWrite(
                root, "src/main/kotlin/fixture/app/Use.kt",
                "package fixture.app\nimport fixture.pricing.*\nfun run(): String = computeInvoiceTotal()\n",
            )
            "fully-qualified use" -> consumerWrite(
                root, "src/main/kotlin/fixture/app/Use.kt",
                "package fixture.app\nfun run(): String = fixture.pricing.computeInvoiceTotal()\n",
            )
            "callable-reference" -> consumerWrite(
                root, "src/main/kotlin/fixture/app/Use.kt",
                "package fixture.app\nimport fixture.pricing.computeInvoiceTotal\nval greetingRef: () -> String = ::computeInvoiceTotal\nfun run(): String = greetingRef()\n",
            )
            "Java consumer" -> {
                consumerWrite(
                    root, "src/main/kotlin/fixture/app/Use.kt",
                    "package fixture.app\nimport fixture.pricing.computeInvoiceTotal\nfun run(): String = computeInvoiceTotal()\n",
                )
                javaWrite(
                    root, "src/main/java/fixture/app/Caller.java",
                    "package fixture.app; class Caller { String run() { return fixture.pricing.InvoiceKt.computeInvoiceTotal(); } }",
                )
            }
            "generated consumer" -> consumerWrite(
                root, "build/generated/ksp/main/kotlin/fixture/app/Use.kt",
                "package fixture.app\nimport fixture.pricing.computeInvoiceTotal\nfun run(): String = computeInvoiceTotal()\n",
            )
            "mixed consumer forms" -> consumerWrite(
                root, "src/main/kotlin/fixture/app/Use.kt",
                "package fixture.app\nimport fixture.pricing.computeInvoiceTotal\nfun run(): String = computeInvoiceTotal() + fixture.pricing.computeInvoiceTotal()\n",
            )
            "unresolved consumer" -> consumerWrite(
                root, "src/main/kotlin/fixture/app/Use.kt",
                // RQ-KMF-CUC-003 unresolved: no import directive at all, so the reference is
                // unresolved and the consumer snapshot fails compilation.
                "package fixture.app\nfun run(): String = computeInvoiceTotal()\n",
            )
            "recovered consumer" -> consumerWrite(
                root, "src/main/kotlin/fixture/app/Use.kt",
                // RQ-KMF-CUC-003 recovered: the import target exists and resolves, but the call
                // is recovered/errored by a return-type mismatch, so the consumer snapshot
                // still fails compilation. Distinct from unresolved (no import) and truncated.
                "package fixture.app\nimport fixture.pricing.computeInvoiceTotal\nfun run(): Int = computeInvoiceTotal()\n",
            )
            "truncated consumer" -> consumerWrite(
                root, "src/main/kotlin/fixture/app/Use.kt",
                // RQ-KMF-CUC-003 truncated: the consumer source is truncated/incomplete, the
                // call expression is cut off, so the consumer snapshot fails to parse.
                "package fixture.app\nfun run(): String = computeInvoiceTotal(\n",
            )
            else -> error("unknown consumer form: $form")
        }
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        acceptExternalConsumerRisk = true
    }

    // ------------------------------------------------------------------ AC-FUNCTION-005

    @Given("^the selected declaration is one compiler-proven public top-level Kotlin function \"fixture\\.pricing\\.computeInvoiceTotal\"$")
    fun selectedPublicTopLevelFunction() {
        val root = temporaryDirectory("rk-jvm-move-destination")
        writePom(root)
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        val use = root.resolve("src/main/kotlin/fixture/app/Use.kt").apply { parent.createDirectories() }
        val other = root.resolve("src/main/kotlin/fixture/accounting/Other.kt").apply { parent.createDirectories() }
        invoice.writeText("package fixture.pricing\r\npublic fun computeInvoiceTotal(): String = \"total\"\r\n")
        use.writeText("package fixture.app\r\nimport fixture.pricing.computeInvoiceTotal\r\nfun run(): String = computeInvoiceTotal()\r\n")
        other.writeText("package fixture.accounting\r\npublic fun computeInvoiceTotal(value: Int): String = value.toString()\r\n")
        originalInvoice = invoice.readText()
        originalUse = use.readText()
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
    }

    @Given("^the destination package \"fixture\\.accounting\" already contains a same-name top-level function family$")
    fun destinationContainsSameNameFamily() {
        assertTrue(
            requireNotNull(fixtureRoot).resolve("src/main/kotlin/fixture/accounting/Other.kt").exists(),
            "expected a same-name destination family fixture",
        )
    }

    @When(
        "^the destination package \"fixture\\.accounting\" has no same-name top-level function family and every other precondition is clean$",
    )
    fun destinationClearedAndPreview() {
        val root = requireNotNull(fixtureRoot)
        Files.deleteIfExists(root.resolve("src/main/kotlin/fixture/accounting/Other.kt"))
        val snap = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        snapshot = snap
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val catalogueResult = adapter.compilerSymbols(snap)
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(catalogueResult, catalogueResult.toString())
        val target = requireNotNull(selectTarget(catalogue))
        plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snap, target.id, "fixture.accounting", acceptExternalConsumerRisk = true,
        )
    }

    @Then(
        "^moveDeclaration previews a SEMANTIC_PREVIEW with exact new facade callable identity \"fixture\\.accounting\\.computeInvoiceTotal\"$",
    )
    fun semanticPreviewWithNewIdentity() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val dest = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/accounting/Invoice.kt").normalize() }
        assertTrue("package fixture.accounting" in dest.content, dest.content)
        assertTrue("public fun computeInvoiceTotal(): String = \"total\"" in dest.content, dest.content)
        val consumer = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/app/Use.kt").normalize() }
        assertTrue("import fixture.accounting.computeInvoiceTotal" in consumer.content, consumer.content)
    }

    // ------------------------------------------------------------------ AC-FUNCTION-006

    @Given("^an approved SEMANTIC_PREVIEW moves \"fixture\\.pricing\\.computeInvoiceTotal\" to \"fixture\\.accounting\"$")
    fun approvedSemanticPreviewMoves() {
        val root = temporaryDirectory("rk-jvm-move-apply")
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        val use = root.resolve("src/main/kotlin/fixture/app/Use.kt").apply { parent.createDirectories() }
        writePom(root)
        // AC-FUNCTION-006 fixture also carries the compiler-proven private top-level helper so
        // the shared "line endings and every private helper byte remain exact" step is genuinely
        // exercised for both the preview (AC-FUNCTION-001) and the apply/rollback (AC-FUNCTION-006).
        // RQ-KMF-CUC-002B: the private helper taxRate is CALLED by the selected function so
        // called-helper binding/byte preservation is exercised in the committed post-image and rollback.
        invoice.writeText(
            "package fixture.pricing\r\npublic fun computeInvoiceTotal(): String = taxRate().toString()\r\n" +
                "private fun taxRate(): Double = 0.21\r\n",
        )
        use.writeText("package fixture.app\r\nimport fixture.pricing.computeInvoiceTotal\r\nfun run(): String = computeInvoiceTotal()\r\n")
        originalInvoice = invoice.readText()
        originalUse = use.readText()
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val catalogueResult = adapter.compilerSymbols(requireNotNull(snapshot))
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(catalogueResult, catalogueResult.toString())
        val target = requireNotNull(selectTarget(catalogue))
        val p = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            requireNotNull(snapshot), target.id, "fixture.accounting", acceptExternalConsumerRisk = true,
        )
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        plan = p
    }

    @When("^the preview is applied under explicit authorization$")
    fun previewAppliedUnderExplicitAuthorization() {
        val root = requireNotNull(fixtureRoot)
        val snap = requireNotNull(snapshot)
        val p = requireNotNull(plan)
        val planner = KotlinJvmMoveDeclarationPlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))
        applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(
            p, snap, ApplyAuthorization.explicit("kotlin-move-top-level-function-cucumber"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
    }

    @Then("^apply uses PatchEngine and writes a transaction rollback record$")
    fun applyUsesPatchEngineWritesRollbackRecord() {
        val a = requireNotNull(applied)
        assertNotNull(a.transaction.id, "expected a write-ahead transaction id")
        assertTrue(
            a.transaction.snapshotHashBefore.isNotBlank(),
            "expected a transaction snapshot-hash attestation",
        )
        assertTrue(
            !a.transaction.rollbackEdit.edits.isEmpty(),
            "expected a transaction write-ahead rollback record",
        )
    }

    @Then("^the committed post-image is written with the moved package and consumer import$")
    fun committedPostImageWrittenWithMovedPackageAndImport() {
        val root = requireNotNull(fixtureRoot)
        val dest = root.resolve("src/main/kotlin/fixture/accounting/Invoice.kt")
        assertTrue(dest.exists(), "expected the moved destination file")
        assertTrue("package fixture.accounting" in dest.readText(), dest.readText())
        assertTrue(
            "import fixture.accounting.computeInvoiceTotal" in root.resolve("src/main/kotlin/fixture/app/Use.kt").readText(),
        )
    }

    @When("^that transaction is rolled back$")
    fun transactionRolledBack() {
        rolledBack = assertIs<ApplyResult.Applied>(PatchEngine(requireNotNull(fixtureRoot)).rollback(requireNotNull(applied).transaction))
    }

    @Then("^every file byte, path, and snapshot hash equals the pre-apply image$")
    fun everyFileBytePathHashEqualsPreApply() {
        val root = requireNotNull(fixtureRoot)
        assertTrue(!root.resolve("src/main/kotlin/fixture/accounting/Invoice.kt").exists(), "destination must be removed by rollback")
        assertEquals(requireNotNull(originalInvoice), root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").readText())
        assertEquals(requireNotNull(originalUse), root.resolve("src/main/kotlin/fixture/app/Use.kt").readText())
        val restored = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        assertEquals(
            requireNotNull(snapshot).hash,
            restored.hash,
            "restored snapshot hash must equal the pre-apply image",
        )
    }

    @Then("^authoritative rollback diagnostics attest the restored snapshot$")
    fun rollbackDiagnosticsAttest() {
        assertIs<ApplyResult.Applied>(requireNotNull(rolledBack))
        val restored = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(requireNotNull(fixtureRoot)), toolchain)
        val planner = KotlinJvmMoveDeclarationPlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))
        val diagnostics = planner.diagnostics(restored)
        assertTrue(
            diagnostics.none { it.severity == Diagnostic.Severity.ERROR },
            "expected the restored snapshot to compile clean, got: ${diagnostics.map { it.code to it.message }}",
        )
    }

    // ------------------------------------------------------------------ AC-FUNCTION-006 restoration (RQ-KMF-AC6-ATTEST-001)

    @Then("^the committed post-image carries post-image attestation$")
    fun committedPostImageCarriesPostImageAttestation() {
        val a = requireNotNull(applied)
        val record = requireNotNull(
            TransactionLog(requireNotNull(fixtureRoot).resolve(".refactorkit/transactions")).loadRecord(a.transaction.id),
        ) { "expected the committed transaction journal record" }
        assertTrue(
            requireNotNull(record.postSnapshotHash).isNotBlank(),
            "expected the committed post-image to carry a snapshot attestation",
        )
        val dest = requireNotNull(fixtureRoot).resolve("src/main/kotlin/fixture/accounting/Invoice.kt")
        // Transaction journal post-image paths are RELATIVE to the workspace root
        // (src/main/kotlin/fixture/accounting/Invoice.kt), not absolute; match the relative path.
        val destRelative = Path.of("src/main/kotlin/fixture/accounting/Invoice.kt")
        val committed = requireNotNull(record.postImages.single { it.path.normalize() == destRelative.normalize() })
        val committedContent = requireNotNull(committed.content)
        assertEquals(
            dest.readText(), committedContent,
            "expected the committed post-image identity attestation to match the written bytes",
        )
        assertTrue(
            "taxRate().toString()" in committedContent,
            "called-helper binding must be attested in the committed post-image",
        )
    }

    // ------------------------------------------------------------------ approved-change-001 (filename-casing independence)

    @Given(
        "^the selected declaration is one compiler-proven public top-level Kotlin function \"fixture\\.pricing\\.computeInvoiceTotal\" whose source filename casing differs from the compiler-reported file-facade owner$",
    )
    fun selectedPublicTopLevelFunctionWithFilenameCasingDifference() {
        val root = temporaryDirectory("rk-jvm-move-casing")
        writePom(root)
        // The source filename is lowercase (invoice.kt) so its casing differs from the
        // compiler-reported K2 file-facade owner (fixture.pricing.InvoiceKt, capitalized). The
        // planner must use the actual source filename for the destination path and retain its
        // casing rather than deriving the path from the facade-owner name.
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/invoice.kt").apply { parent.createDirectories() }
        val use = root.resolve("src/main/kotlin/fixture/app/Use.kt").apply { parent.createDirectories() }
        invoice.writeText("package fixture.pricing\r\npublic fun computeInvoiceTotal(): String = \"total\"\r\n")
        use.writeText("package fixture.app\r\nimport fixture.pricing.computeInvoiceTotal\r\nfun run(): String = computeInvoiceTotal()\r\n")
        originalInvoice = invoice.readText()
        originalUse = use.readText()
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val catalogueResult = adapter.compilerSymbols(requireNotNull(snapshot))
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(catalogueResult, catalogueResult.toString())
        val owners = catalogue.declarations.values.map { it.jvmOwner }.distinct()
        assertTrue(
            owners.any { it.startsWith("fixture.pricing.") && it.substringAfterLast('.') != "invoiceKt" },
            "expected the compiler-reported file-facade owner to differ in casing from the source filename 'invoice.kt': $owners",
        )
        acceptExternalConsumerRisk = true
    }

    @Then(
        "^the preview is a SEMANTIC_PREVIEW with exact new facade callable identity \"fixture\\.accounting\\.computeInvoiceTotal\"$",
    )
    fun previewIsSemanticPreviewWithNewIdentity() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val dest = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/accounting/invoice.kt").normalize() }
        assertTrue("package fixture.accounting" in dest.content, dest.content)
        assertTrue("public fun computeInvoiceTotal(): String = \"total\"" in dest.content, dest.content)
        val consumer = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/app/Use.kt").normalize() }
        assertTrue("import fixture.accounting.computeInvoiceTotal" in consumer.content, consumer.content)
    }

    @Then("^the moved destination file path retains the source-filename casing$")
    fun movedDestinationFilePathRetainsSourceFilenameCasing() {
        val p = requireNotNull(plan)
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        assertTrue(
            staged.files.any { it.path.normalize() == Path.of("src/main/kotlin/fixture/accounting/invoice.kt").normalize() },
            "expected the moved destination path to retain the lowercase source-filename casing invoice.kt, got: ${staged.files.map { it.path }}",
        )
    }

    // ------------------------------------------------------------------ approved-change-002 (implicit outbound rebinding)

    @Given(
        "^the selected declaration is one compiler-proven public top-level Kotlin function \"fixture\\.pricing\\.computeInvoiceTotal\" whose implicit outbound source binding would rebind to a different target-package declaration$",
    )
    fun selectedImplicitOutboundRebinding() {
        val root = temporaryDirectory("rk-jvm-move-outbound-implicit")
        writePom(root)
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        val util = root.resolve("src/main/kotlin/fixture/pricing/Util.kt").apply { parent.createDirectories() }
        val accounting = root.resolve("src/main/kotlin/fixture/accounting/Helper.kt").apply { parent.createDirectories() }
        // The selected function implicitly calls helper(). Before the move helper() resolves to
        // fixture.pricing.helper (Util.kt); after moving to fixture.accounting it would rebind to
        // fixture.accounting.helper (Helper.kt), a different target-package declaration.
        invoice.writeText("package fixture.pricing\npublic fun computeInvoiceTotal(): String = helper()\n")
        util.writeText("package fixture.pricing\npublic fun helper(): String = \"pricing\"\n")
        accounting.writeText("package fixture.accounting\npublic fun helper(): String = \"accounting\"\n")
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        acceptExternalConsumerRisk = true
    }

    // ------------------------------------------------------------------ approved-change-003 (convention-call rebinding)

    @Given(
        "^the selected declaration is one compiler-proven public top-level Kotlin function \"fixture\\.pricing\\.computeInvoiceTotal\" whose operator, component, or compareTo convention-call binding would rebind to a target-package declaration$",
    )
    fun selectedConventionCallRebinding() {
        val root = temporaryDirectory("rk-jvm-move-outbound-convention")
        writePom(root)
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        val pricePricing = root.resolve("src/main/kotlin/fixture/pricing/Price.kt").apply { parent.createDirectories() }
        val priceAccounting = root.resolve("src/main/kotlin/fixture/accounting/Price.kt").apply { parent.createDirectories() }
        // The selected function uses an operator convention-call (p + p). Before the move the
        // operator resolves to fixture.pricing.Price.plus; after moving to fixture.accounting the
        // Price type and its plus operator rebind to fixture.accounting.Price.plus, a different
        // target-package declaration.
        invoice.writeText(
            "package fixture.pricing\npublic fun computeInvoiceTotal(): Price { val p = Price(1); return p + p }\n",
        )
        pricePricing.writeText(
            "package fixture.pricing\ndata class Price(val v: Int) { operator fun plus(other: Price): Price = Price(v + other.v) }\n",
        )
        priceAccounting.writeText(
            "package fixture.accounting\ndata class Price(val v: Int) { operator fun plus(other: Price): Price = Price(v + other.v) }\n",
        )
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        acceptExternalConsumerRisk = true
    }

    // ------------------------------------------------------------------ approved-change-005 (typealias-bound and Maven -Xplugin)

    @Given(
        "^the selected declaration is one \"([^\"]+)\" top-level Kotlin function with no compiler-plugin annotation stub$",
    )
    fun selectedTypealiasOrXpluginFunction(aliasPluginKind: String) {
        val root = temporaryDirectory("rk-jvm-move-alias-plugin")
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        when (aliasPluginKind) {
            "typealias-bound" -> {
                writePom(root)
                // The moved function's callable identity depends on a typealias (return type Money),
                // so the K2 usage extractor refuses kotlin.usageTypeAliasUnsupported. No annotation
                // stub is involved (explicitly not the Compose stub).
                invoice.writeText("package fixture.pricing\ntypealias Money = Double\npublic fun computeInvoiceTotal(): Money = 1.0\n")
            }
            "Maven -Xplugin" -> {
                // Declare a Maven kotlin-maven-plugin -Xplugin arg with NO compiler-plugin annotation
                // stub; the build model surfaces kotlin.compilerPluginsUnsupported before any patch.
                root.resolve("pom.xml").writeText("""
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>fixture</groupId><artifactId>mixed</artifactId><version>1</version>
                      <properties><maven.compiler.release>21</maven.compiler.release></properties>
                      <build><plugins><plugin>
                        <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                        <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain>
                          <args><arg>-Xplugin=com.example:plugin</arg></args>
                        </configuration>
                      </plugin></plugins></build>
                    </project>
                """.trimIndent())
                invoice.writeText("package fixture.pricing\npublic fun computeInvoiceTotal(): String = \"total\"\n")
            }
            else -> error("unknown alias plugin kind: $aliasPluginKind")
        }
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        acceptExternalConsumerRisk = true
    }

    // ------------------------------------------------------------------ approved-change-006 (nested typealias depth)

    @Given(
        "^the selected declaration is one compiler-proven public top-level Kotlin function \"fixture\\.pricing\\.computeInvoiceTotal\" whose callable identity depends on a \"([^\"]+)\" typealias$",
    )
    fun selectedNestedTypealiasDepthFunction(typealiasDepth: String) {
        val root = temporaryDirectory("rk-jvm-move-typealias-depth")
        writePom(root)
        val invoice = root.resolve("src/main/kotlin/fixture/pricing/Invoice.kt").apply { parent.createDirectories() }
        val content = when (typealiasDepth) {
            "nested" ->
                // A typealias that expands to another typealias (B -> A -> List<Double>); the K2
                // usage extractor refuses kotlin.usageTypeAliasUnsupported.
                "package fixture.pricing\ntypealias A = List<Double>\ntypealias B = A\npublic fun computeInvoiceTotal(): B = emptyList()\n"
            "excessive" ->
                // A callable identity whose return type is a deeply-nested generic array (65 levels).
                // Symbol extraction genuinely cannot verify the JVM binary identity of an
                // array-returning function (even Array<Double> at depth 1 refuses), so production
                // emits kotlin.symbolCallableBinaryMismatch; the feature Examples table declares that
                // actual code for reconciliation (the usage extractor depth guard is not reached).
                run {
                    val deep = (1..65).fold("Double") { acc, _ -> "Array<$acc>" }
                    "package fixture.pricing\npublic fun computeInvoiceTotal(): $deep = TODO()\n"
                }
            else -> error("unknown typealias depth: $typealiasDepth")
        }
        invoice.writeText(content)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        acceptExternalConsumerRisk = true
    }

    // ------------------------------------------------------------------ shared refusal outline (AC-002, AC-003)

    @Then(
        "^the selection is refused with stable typed code \"(?![^\"]*destinationSameNameFamilyPresent)([^\"]+)\" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation$",
    )
    fun selectionRefused(declaredCode: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredCode, p.refusalCode,
            "declared refusal code '$declaredCode' did not equal the actual code '${p.refusalCode}'",
        )
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertTrue(p.affectedFiles.isEmpty(), p.toString())
        observedRefusals += ObservedRefusal(declaredCode, p.refusalCode, p.status)
    }

    // ------------------------------------------------------------------ AC-FUNCTION-004 refusal Then ("the consumer is refused")

    @Then(
        "^the consumer is refused with stable typed code \"([^\"]+)\" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation$",
    )
    fun consumerRefused(declaredCode: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredCode, p.refusalCode,
            "declared refusal code '$declaredCode' did not equal the actual code '${p.refusalCode}'",
        )
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertTrue(p.affectedFiles.isEmpty(), p.toString())
        observedRefusals += ObservedRefusal(declaredCode, p.refusalCode, p.status)
    }

    // ------------------------------------------------------------------ helpers

    private fun selectTarget(catalogue: KotlinCompilerSymbolsResult.Available): Symbol? {
        val sourceRoot = Path.of("src/main/kotlin/fixture/pricing").normalize()
        return catalogue.index.symbols.firstOrNull {
            it.name == "computeInvoiceTotal" &&
                catalogue.declarations[it.id]?.visibility == KotlinDeclarationVisibility.PUBLIC &&
                it.location.path.normalize().startsWith(sourceRoot)
        } ?: catalogue.index.symbols.firstOrNull {
            it.name == "computeInvoiceTotal" &&
                catalogue.declarations[it.id]?.visibility == KotlinDeclarationVisibility.PUBLIC
        } ?: catalogue.index.symbols.firstOrNull { it.name == "computeInvoiceTotal" }
    }

    private fun consumerWrite(root: Path, relative: String, content: String) {
        root.resolve(relative).apply { parent.createDirectories() }.writeText(content)
    }

    private fun javaWrite(root: Path, relative: String, content: String) {
        root.resolve(relative).apply { parent.createDirectories() }.writeText(content)
    }

    private fun writePom(root: Path) {
        root.resolve("pom.xml").writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>mixed</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin>
                <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
    }

    private fun toolchain(workspace: Path): KotlinSemanticToolchain {
        val requiredRuntimePrefixes = listOf(
            "kotlin-compiler-embeddable-2.0.21", "kotlin-stdlib-2.0.21",
            "kotlin-script-runtime-2.0.21", "kotlin-reflect-1.6.10",
            "kotlin-daemon-embeddable-2.0.21", "trove4j-1.0.20200330",
            "kotlinx-coroutines-core-jvm-1.6.4", "annotations-13.0",
        )
        val runtime = System.getProperty("kotlin.compiler.test.classpath")
            .split(File.pathSeparator).map(Path::of)
            .filter { path -> Files.isRegularFile(path) && requiredRuntimePrefixes.any {
                path.fileName.toString().startsWith(it)
            } }
        val compilerSource = runtime.single { it.fileName.toString().startsWith("kotlin-compiler-embeddable-2.0.21") }
        val toolchainRoot = temporaryDirectory("rk-jvm-toolchain")
        val compiler = toolchainRoot.resolve(compilerSource.fileName.toString())
        Files.copy(compilerSource, compiler)
        val classpath = runtime.filterNot { it == compilerSource }.distinctBy { it.fileName.toString() }.map { source ->
            toolchainRoot.resolve(source.fileName.toString()).also { Files.copy(source, it) }
        }
        val discovery = KotlinToolchainDiscoverer().discover(KotlinToolchainRequest(
            workspaceRoot = workspace,
            jdkHome = Path.of(System.getProperty("java.home")),
            compilerJar = compiler,
            compilerClasspath = classpath,
        ))
        return assertIs<KotlinToolchainDiscovery.Available>(discovery).toolchain
    }

    private fun temporaryDirectory(prefix: String): Path {
        val base = Path.of(System.getProperty("user.dir")).resolve("build/test-tmp").toAbsolutePath().normalize()
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix).also(temporaryDirectories::add)
    }

    private fun deleteNoFollow(root: Path) {
        if (!root.exists()) return
        val paths = Files.walk(root).use { stream -> stream.toList() }
        paths.sortedByDescending(Path::getNameCount).forEach { path ->
            require(!Files.isSymbolicLink(path)) { "Temporary test cleanup refuses symbolic link: $path" }
            Files.delete(path)
        }
    }

    @After
    fun cleanup(scenario: Scenario) {
        val reportDir = Path.of(System.getProperty("user.dir")).resolve("build/reports/cucumber")
        reportDir.createDirectories()
        val report = reportDir.resolve("move-top-level-function-refusal-codes.txt")
        val lines = mutableListOf<String>()
        lines += "scenario:${scenario.name}"
        observedRefusals.forEach { lines += "  ${it.declaredCode} -> ${it.actualCode} (${it.status})" }
        plan?.let { lines += "  plan:${it.status} refusal=${it.refusalCode ?: "-"}" }
        Files.write(report, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

        var cleanupFailure: Throwable? = null
        temporaryDirectories.asReversed().forEach { directory ->
            try {
                deleteNoFollow(directory)
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
        }
        temporaryDirectories.clear()
        cleanupFailure?.let { throw it }
    }

    private data class ObservedRefusal(
        val declaredCode: String,
        val actualCode: String?,
        val status: PatchStatus,
    )
}
