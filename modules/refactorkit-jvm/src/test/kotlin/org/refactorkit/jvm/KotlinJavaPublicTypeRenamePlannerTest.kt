package org.refactorkit.jvm

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinJavaPublicTypeRenamePlannerTest {
    @Test
    fun ephemeralJavaCompilationFailurePublishesNoConsumerEvidence() {
        val fixture = javaDeclarationFixture()
        fixture.root.resolve("src/main/java/fixture/PublicAccount.java").writeText(
            "package fixture; public class PublicAccount { syntax }\n",
        )
        val broken = JavaProjectScanner().scan(fixture.root)
        var consumed = false

        val result = JavaEphemeralCompiler().compile(broken) { consumed = true }

        val refusal = assertIs<JavaEphemeralCompilationResult.Refused>(result)
        assertEquals("jvm.javaCompilationFailed", refusal.code)
        assertTrue(!consumed)
    }

    @Test
    fun ephemeralJavaClassesLetK2ProveKotlinUsesOfPublicJavaType() {
        val fixture = javaDeclarationFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        var kotlinResult: org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult? = null

        val compilation = JavaEphemeralCompiler().compile(fixture.snapshot) { output ->
            kotlinResult = adapter.compilerDiagnosticsWithAdditionalClasspath(fixture.snapshot, listOf(output))
        }

        val compiled = assertIs<JavaEphemeralCompilationResult.Available>(compilation, compilation.toString())
        assertTrue(compiled.outputHash.matches(Regex("[0-9a-f]{64}")))
        val available = assertIs<org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult.Available>(kotlinResult, kotlinResult.toString())
        assertEquals(null, available.symbolFailure)
        val usages = available.externalTypeUsages.filter { it.jvmBinaryName == "fixture.PublicAccount" }
        assertTrue(usages.size >= 2, "expected exact K2 uses of Java binary identity, got $usages")
        assertTrue(available.attestation.ephemeralClasspathHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(available.externalCallableUsages.any {
            it.jvmOwner == "fixture.PublicAccount" && it.callableName == "label"
        }, "expected exact K2 Java-method call evidence: ${available.externalCallableUsages}")
    }

    @Test
    fun publicJavaMethodRenameUpdatesKotlinCallerAndRollsBack() {
        val fixture = javaDeclarationFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val planner = JavaKotlinPublicTypeRenamePlanner(adapter)
        val plan = planner.preview(
            fixture.snapshot,
            org.refactorkit.core.SymbolId("fixture.PublicAccount#label(java.lang.String)"),
            "describe",
            acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertTrue(plan.workspaceEdit.edits.none { it is FileEdit.Rename })
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("jvm-java-method-integration-test"),
            DiagnosticsGate.enabled("java-ecj-kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/PublicAccount.java")).contains("String describe("))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt")).contains("account.describe("))
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/PublicAccount.java")).contains("String label("))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt")).contains("account.label("))
    }

    @Test
    fun publicJavaTypeRenameUsesJdtAndK2ExactTokens() {
        val fixture = javaDeclarationFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val plan = JavaKotlinPublicTypeRenamePlanner(adapter).preview(
            fixture.snapshot,
            org.refactorkit.core.SymbolId("fixture.PublicAccount"),
            "CustomerAccount",
            acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertTrue(plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().single().let {
            it.path == Path.of("src/main/java/fixture/PublicAccount.java") &&
                it.newPath == Path.of("src/main/java/fixture/CustomerAccount.java")
        })
        assertEquals(
            setOf(
                Path.of("src/main/java/fixture/PublicAccount.java"),
                Path.of("src/main/java/fixture/CustomerAccount.java"),
                Path.of("src/main/kotlin/fixture/UseAccount.kt"),
            ),
            plan.affectedFiles,
        )
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun publicJavaTypeRenameAppliesAndRollsBackOneMixedTransaction() {
        val fixture = javaDeclarationFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val planner = JavaKotlinPublicTypeRenamePlanner(adapter)
        val plan = planner.preview(
            fixture.snapshot, org.refactorkit.core.SymbolId("fixture.PublicAccount"), "CustomerAccount", true,
        )
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())

        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("jvm-symmetric-integration-test"),
            DiagnosticsGate.enabled("java-ecj-kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(!Files.exists(fixture.root.resolve("src/main/java/fixture/PublicAccount.java")))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/CustomerAccount.java")).contains("CustomerAccount"))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt")).contains("CustomerAccount"))

        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(Files.exists(fixture.root.resolve("src/main/java/fixture/PublicAccount.java")))
        assertTrue(!Files.exists(fixture.root.resolve("src/main/java/fixture/CustomerAccount.java")))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt")).contains("PublicAccount"))
    }

    @Test
    fun publicKotlinTypeMoveRequiresExternalConsumerApproval() {
        val fixture = moveFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            fixture.snapshot, target.id, "fixture.api.v2",
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveExternalConsumerApprovalRequired", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicKotlinTypeMoveRefusesStarConsumerOfAdditionalPublicType() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic interface GreetingPort\npublic class PublicGreeting : GreetingPort\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport fixture.api.*\n" +
                "fun greeting(port: GreetingPort): PublicGreeting = PublicGreeting()\n",
        )
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.movePublicSiblingImportUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicKotlinTypeMovePreservesAliasedConsumerOfAdditionalPublicType() {
        val fixture = moveFixture()
        val kotlinConsumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic interface GreetingPort\npublic class PublicGreeting : GreetingPort\n",
        )
        kotlinConsumer.writeText(
            "package fixture.consumer\nimport fixture.api.GreetingPort as Port\n" +
                "import fixture.api.PublicGreeting\nfun greeting(port: Port): PublicGreeting = PublicGreeting()\n",
        )
        val beforeBytes = kotlinConsumer.readBytes()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("aliased-public-sibling-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue("import fixture.api.v2.GreetingPort as Port" in kotlinConsumer.readText())
        assertTrue("fun greeting(port: Port): PublicGreeting = PublicGreeting()" in kotlinConsumer.readText())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(beforeBytes.contentEquals(kotlinConsumer.readBytes()))
    }

    @Test
    fun publicKotlinTypeMoveCarriesAdditionalPublicTypeWithExplicitConsumers() {
        val fixture = moveFixture()
        val declaration = fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt")
        val kotlinConsumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        val javaConsumer = fixture.root.resolve("src/main/java/fixture/consumer/Caller.java")
        declaration.writeText(
            "package fixture.api\npublic interface PublicGreetingPort\npublic class PublicGreeting : PublicGreetingPort\n",
        )
        kotlinConsumer.writeText(
            "package fixture.consumer\nimport fixture.api.PublicGreeting\nimport fixture.api.PublicGreetingPort\n" +
                "fun greeting(port: PublicGreetingPort): PublicGreeting = PublicGreeting()\n",
        )
        javaConsumer.writeText(
            "package fixture.consumer;\nimport fixture.api.PublicGreeting;\nimport fixture.api.PublicGreetingPort;\n" +
                "class Caller { PublicGreetingPort port; PublicGreeting value = new PublicGreeting(); }\n",
        )
        val kotlinBefore = kotlinConsumer.readBytes()
        val javaBefore = javaConsumer.readBytes()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("public-sibling-kotlin-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue("import fixture.api.v2.PublicGreetingPort" in kotlinConsumer.readText())
        assertTrue("import fixture.api.v2.PublicGreeting" in kotlinConsumer.readText())
        assertTrue("import fixture.api.v2.PublicGreetingPort;" in javaConsumer.readText())
        assertTrue("import fixture.api.v2.PublicGreeting;" in javaConsumer.readText())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(kotlinBefore.contentEquals(kotlinConsumer.readBytes()))
        assertTrue(javaBefore.contentEquals(javaConsumer.readBytes()))
    }

    @Test
    fun publicKotlinTypeMoveRefusesNonPrivateTopLevelHelper() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\ninternal class SharedGreetingState\npublic class PublicGreeting\n",
        )
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveFileShapeUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicKotlinTypeMoveCarriesCompilerProvenPrivateTopLevelHelpers() {
        val fixture = moveFixture()
        val declaration = fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt")
        declaration.writeText(
            "package fixture.api\n" +
                "private class GreetingState\n" +
                "private val defaultGreetingState: GreetingState = GreetingState()\n" +
                "private fun greetingState(): GreetingState = defaultGreetingState\n" +
                "public class PublicGreeting { private val state = greetingState() }\n",
        )
        val beforeBytes = declaration.readBytes()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("private-helper-kotlin-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        val destination = fixture.root.resolve("src/main/kotlin/fixture/api/v2/PublicGreeting.kt")
        assertTrue("private class GreetingState" in destination.readText())
        assertTrue("private val defaultGreetingState" in destination.readText())
        assertTrue("private fun greetingState()" in destination.readText())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(beforeBytes.contentEquals(declaration.readBytes()))
    }

    @Test
    fun publicKotlinTypeMovePreservesExactKotlinImportAlias() {
        val fixture = moveFixture()
        val kotlinConsumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        kotlinConsumer.writeText(
            "package fixture.consumer\nimport fixture.api.PublicGreeting as ApiGreeting\n" +
                "fun greeting(): ApiGreeting = ApiGreeting()\n",
        )
        val beforeBytes = kotlinConsumer.readBytes()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("aliased-kotlin-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue("import fixture.api.v2.PublicGreeting as ApiGreeting" in kotlinConsumer.readText())
        assertTrue("fun greeting(): ApiGreeting = ApiGreeting()" in kotlinConsumer.readText())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(beforeBytes.contentEquals(kotlinConsumer.readBytes()))
    }

    @Test
    fun publicKotlinTypeMoveSupportsNoInWorkspaceConsumers() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").deleteExisting()
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertTrue(plan.summary.contains("0 compiler-proven consumer file(s)"))
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("unused-kotlin-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/api/v2/PublicGreeting.kt").exists())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").exists())
    }

    @Test
    fun publicKotlinTypeMoveSupportsKotlinOnlyConsumers() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("kotlin-only-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/api/v2/PublicGreeting.kt").exists())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").exists())
    }

    @Test
    fun publicKotlinTypeMoveSupportsJavaOnlyConsumers() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("java-only-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/api/v2/PublicGreeting.kt").exists())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").exists())
    }

    @Test
    fun publicKotlinTypeMoveRefusesMixedImportedAndQualifiedUses() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport fixture.api.PublicGreeting\n" +
                "fun greeting(value: PublicGreeting): PublicGreeting = fixture.api.PublicGreeting()\n",
        )
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveImportShapeUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicKotlinTypeMoveUpdatesCompilerProvenFullyQualifiedUses() {
        val fixture = moveFixture()
        val kotlinConsumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        val javaConsumer = fixture.root.resolve("src/main/java/fixture/consumer/Caller.java")
        kotlinConsumer.writeText(
            "package fixture.consumer\nfun greeting(): fixture.api.PublicGreeting = fixture.api.PublicGreeting()\n",
        )
        javaConsumer.writeText(
            "package fixture.consumer;\nclass Caller { fixture.api.PublicGreeting value = new fixture.api.PublicGreeting(); }\n",
        )
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("kotlin-qualified-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertEquals(2, Regex(Regex.escape("fixture.api.v2.PublicGreeting")).findAll(kotlinConsumer.readText()).count())
        assertEquals(2, Regex(Regex.escape("fixture.api.v2.PublicGreeting")).findAll(javaConsumer.readText()).count())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertEquals(2, Regex(Regex.escape("fixture.api.PublicGreeting")).findAll(kotlinConsumer.readText()).count())
        assertEquals(2, Regex(Regex.escape("fixture.api.PublicGreeting")).findAll(javaConsumer.readText()).count())
    }

    @Test
    fun publicKotlinTypeMoveAddsExplicitImportsForStarConsumersAndRollsBack() {
        val fixture = moveFixture()
        val kotlinConsumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        val javaConsumer = fixture.root.resolve("src/main/java/fixture/consumer/Caller.java")
        kotlinConsumer.writeText(
            "package fixture.consumer\nimport fixture.api.*\nfun greeting(): PublicGreeting = PublicGreeting()\n",
        )
        javaConsumer.writeText(
            "package fixture.consumer;\nimport fixture.api.*;\n" +
                "class Caller { PublicGreeting value = new PublicGreeting(); }\n",
        )
        val kotlinBefore = kotlinConsumer.readBytes()
        val javaBefore = javaConsumer.readBytes()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("star-import-kotlin-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue("import fixture.api.*\nimport fixture.api.v2.PublicGreeting" in kotlinConsumer.readText())
        assertTrue("import fixture.api.*;\nimport fixture.api.v2.PublicGreeting;" in javaConsumer.readText())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(kotlinBefore.contentEquals(kotlinConsumer.readBytes()))
        assertTrue(javaBefore.contentEquals(javaConsumer.readBytes()))
    }

    @Test
    fun publicKotlinTypeMoveAddsImportsForCompilerProvenSamePackageConsumers() {
        val fixture = moveFixture()
        val kotlinConsumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        val javaConsumer = fixture.root.resolve("src/main/java/fixture/consumer/Caller.java")
        kotlinConsumer.writeText("package fixture.api\nfun greeting(): PublicGreeting = PublicGreeting()\n")
        javaConsumer.writeText(
            "package fixture.api;\nclass Caller { PublicGreeting value = new PublicGreeting(); }\n",
        )
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("kotlin-same-package-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(kotlinConsumer.readText().contains("package fixture.api\nimport fixture.api.v2.PublicGreeting\n"))
        assertTrue(javaConsumer.readText().contains("package fixture.api;\nimport fixture.api.v2.PublicGreeting;\n"))
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(!kotlinConsumer.readText().contains("import fixture.api.v2.PublicGreeting"))
        assertTrue(!javaConsumer.readText().contains("import fixture.api.v2.PublicGreeting"))
    }

    @Test
    fun publicKotlinTypeMoveUpdatesExplicitKotlinAndJavaImportsAndRollsBack() {
        val fixture = moveFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)
        val plan = planner.preview(
            fixture.snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(
            setOf(
                Path.of("src/main/kotlin/fixture/api/PublicGreeting.kt"),
                Path.of("src/main/kotlin/fixture/api/v2/PublicGreeting.kt"),
                Path.of("src/main/kotlin/fixture/consumer/UseGreeting.kt"),
                Path.of("src/main/java/fixture/consumer/Caller.java"),
            ),
            plan.affectedFiles,
        )
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("kotlin-move-integration-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        val destination = fixture.root.resolve("src/main/kotlin/fixture/api/v2/PublicGreeting.kt")
        assertTrue(Files.exists(destination))
        assertTrue(Files.readString(destination).contains("package fixture.api.v2"))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt"))
            .contains("import fixture.api.v2.PublicGreeting"))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/consumer/Caller.java"))
            .contains("import fixture.api.v2.PublicGreeting;"))
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(Files.exists(fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt")))
        assertTrue(!Files.exists(destination))
    }

    @Test
    fun publicKotlinFunctionRenameUpdatesJavaCallerAndRollsBack() {
        val fixture = fixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
            .index.symbols.single { it.name == "render" }
        val planner = KotlinJavaPublicTypeRenamePlanner(adapter)
        val plan = planner.preview(
            fixture.snapshot, target.id, "display", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(
            setOf(
                Path.of("src/main/kotlin/fixture/PublicGreeting.kt"),
                Path.of("src/main/java/fixture/Caller.java"),
            ),
            plan.affectedFiles,
        )
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("jvm-function-integration-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/PublicGreeting.kt")).contains("fun display"))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/Caller.java")).contains(".display("))
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/PublicGreeting.kt")).contains("fun render"))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/Caller.java")).contains(".render("))
    }

    @Test
    fun publicKotlinTypeRenameRequiresExplicitExternalConsumerAcceptance() {
        val fixture = fixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
        val target = symbols.index.symbols.single { it.name == "PublicGreeting" }

        val plan = KotlinJavaPublicTypeRenamePlanner(adapter).preview(
            fixture.snapshot,
            target.id,
            "RenamedGreeting",
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("jvm.renameExternalConsumerApprovalRequired", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicKotlinTypeRenameUsesK2AndJdtExactTokens() {
        val fixture = fixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
        val target = symbols.index.symbols.single { it.name == "PublicGreeting" }

        val plan = KotlinJavaPublicTypeRenamePlanner(adapter).preview(
            fixture.snapshot,
            target.id,
            "RenamedGreeting",
            acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(
            setOf(
                Path.of("src/main/kotlin/fixture/PublicGreeting.kt"),
                Path.of("src/main/java/fixture/Caller.java"),
            ),
            plan.affectedFiles,
        )
        val edits = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>().flatMap { it.textEdits }
        assertTrue(edits.size >= 5, "expected declaration plus Kotlin and Java uses, got $edits")
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun publicKotlinTypeRenameAppliesAndRollsBackOneMixedTransaction() {
        val fixture = fixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJavaPublicTypeRenamePlanner(adapter)
        val plan = planner.preview(
            fixture.snapshot, target.id, "RenamedGreeting", acceptExternalConsumerRisk = true,
        )
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())

        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan,
            fixture.snapshot,
            ApplyAuthorization.explicit("jvm-integration-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/PublicGreeting.kt").toFile().readText().contains("RenamedGreeting"))
        assertTrue(fixture.root.resolve("src/main/java/fixture/Caller.java").toFile().readText().contains("RenamedGreeting"))

        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/PublicGreeting.kt").toFile().readText().contains("PublicGreeting"))
        assertTrue(fixture.root.resolve("src/main/java/fixture/Caller.java").toFile().readText().contains("PublicGreeting"))
    }

    private data class Fixture(
        val root: Path,
        val snapshot: org.refactorkit.core.ProjectSnapshot,
        val toolchain: KotlinSemanticToolchain,
    )

    private fun moveFixture(): Fixture {
        val root = temporaryDirectory("rk-jvm-public-move")
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
        root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText("package fixture.api\npublic class PublicGreeting\n")
        }
        root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").apply {
            parent.createDirectories()
            writeText("package fixture.consumer\nimport fixture.api.PublicGreeting\nfun greeting(): PublicGreeting = PublicGreeting()\n")
        }
        root.resolve("src/main/java/fixture/consumer/Caller.java").apply {
            parent.createDirectories()
            writeText("package fixture.consumer;\nimport fixture.api.PublicGreeting;\nclass Caller { PublicGreeting value = new PublicGreeting(); }\n")
        }
        val toolchain = toolchain(root)
        return Fixture(root, KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain), toolchain)
    }

    private fun javaDeclarationFixture(): Fixture {
        val root = temporaryDirectory("rk-jvm-java-declaration")
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
        root.resolve("src/main/java/fixture/PublicAccount.java").apply {
            parent.createDirectories()
            writeText("package fixture; public class PublicAccount { public String label(String value) { return value; } }\n")
        }
        root.resolve("src/main/kotlin/fixture/UseAccount.kt").apply {
            parent.createDirectories()
            writeText("package fixture\r\nfun account(): PublicAccount = PublicAccount()\r\nfun label(account: PublicAccount): String = account.label(\"x\")\r\n")
        }
        val toolchain = toolchain(root)
        return Fixture(root, KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain), toolchain)
    }

    private fun fixture(): Fixture {
        val root = temporaryDirectory("rk-jvm-public-rename")
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
        root.resolve("src/main/kotlin/fixture/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText("""
                package fixture
                public class PublicGreeting {
                    public fun render(value: String): String = value
                }
                public fun localGreeting(): PublicGreeting = PublicGreeting()
            """.trimIndent() + "\n")
        }
        root.resolve("src/main/java/fixture/Caller.java").apply {
            parent.createDirectories()
            writeText("""
                package fixture;
                class Caller {
                    PublicGreeting value = new PublicGreeting();
                    String render() { return value.render("x"); }
                }
            """.trimIndent() + "\n")
        }
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        return Fixture(root, snapshot, toolchain)
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
        return Files.createTempDirectory(base, prefix)
    }
}
