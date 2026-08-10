package org.refactorkit.jvm

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
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
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinJavaPublicTypeRenamePlannerTest {
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun deleteTemporaryDirectories() {
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
            it.jvmOwner == "fixture.PublicAccount" && it.callableName == "label" &&
                it.jvmDescriptor == "(Ljava/lang/String;)Ljava/lang/String;"
        }, "expected descriptor-bearing K2 Java-method call evidence: ${available.externalCallableUsages}")
        assertTrue(available.externalCallableUsages.any {
            it.jvmOwner == "fixture.PublicAccount" && it.callableName == "<init>" &&
                it.jvmDescriptor == "()V"
        }, "expected descriptor-bearing K2 Java-constructor call evidence: ${available.externalCallableUsages}")
    }

    @Test
    fun publicJavaAddParameterUpdatesK2ProvenKotlinCallerAndRollsBack() {
        val fixture = javaDeclarationFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val planner = JavaKotlinPublicTypeRenamePlanner(adapter)

        val plan = planner.previewAddParameter(
            fixture.snapshot,
            org.refactorkit.core.SymbolId("fixture.PublicAccount#label(java.lang.String)"),
            "boolean",
            "trusted",
            "false",
            acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("jvm-java-signature-integration-test"),
            DiagnosticsGate.enabled("java-ecj-kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(fixture.root.resolve("src/main/java/fixture/PublicAccount.java").readText().contains("label(String value, boolean trusted)"))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt").readText().contains("label(\"x\", false)"))
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(fixture.root.resolve("src/main/java/fixture/PublicAccount.java").readText().contains("label(String value)"))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt").readText().contains("label(\"x\")"))
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
    fun publicTopLevelKotlinFunctionMoveUpdatesExactImportAndRollsBack() {
        val fixture = moveFixture()
        val declaration = fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt")
        val consumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        declaration.writeText(
            (
                "package fixture.api\nprivate val prefix: String = \"[\"\n" +
                    "private fun decorate(value: String): String = prefix + value + \"]\"\n" +
                    "public fun publicGreeting(value: String): String = decorate(value)\n"
                ).replace("\n", "\r\n"),
        )
        consumer.writeText(
            (
                "package fixture.consumer\nimport fixture.api.publicGreeting\n" +
                    "fun greeting(): String = publicGreeting(\"hello\")\n"
                ).replace("\n", "\r\n"),
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val declarationBefore = declaration.readBytes()
        val consumerBefore = consumer.readBytes()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "publicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("top-level-function-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        val destination = fixture.root.resolve("src/main/kotlin/fixture/api/v2/PublicGreeting.kt")
        assertTrue(destination.exists())
        assertTrue(
            declarationBefore.decodeToString().replaceFirst("package fixture.api", "package fixture.api.v2")
                .encodeToByteArray().contentEquals(destination.readBytes()),
        )
        assertTrue(
            consumerBefore.decodeToString()
                .replaceFirst("import fixture.api.publicGreeting", "import fixture.api.v2.publicGreeting")
                .encodeToByteArray().contentEquals(consumer.readBytes()),
        )
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(declarationBefore.contentEquals(declaration.readBytes()))
        assertTrue(consumerBefore.contentEquals(consumer.readBytes()))
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveSupportsImplicitPublicLowercaseFileFacade() {
        val fixture = moveFixture()
        val original = fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt")
        val declaration = fixture.root.resolve("src/main/kotlin/fixture/api/move.kt")
        val consumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        original.deleteExisting()
        declaration.writeText(
            "package fixture.api\n    fun publicGreeting(\n" +
                "    transform: (String) -> String,\n" +
                "): String = transform(\"hello\")\n",
        )
        consumer.writeText(
            "package fixture.consumer\nimport fixture.api.publicGreeting\n" +
                "fun greeting(): String = publicGreeting { it }\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val before = declaration.readBytes()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "publicGreeting" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("implicit-public-lowercase-facade-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        val destination = fixture.root.resolve("src/main/kotlin/fixture/api/v2/move.kt")
        assertTrue(destination.exists())
        assertTrue("import fixture.api.v2.publicGreeting" in consumer.readText())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(before.contentEquals(declaration.readBytes()))
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveSupportsRetainedCommentBetweenPublicAndFun() {
        val fixture = moveFixture()
        val declaration = fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt")
        declaration.writeText(
            "    package fixture.api; public /* retained */ fun publicGreeting(value: String): String = value\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer;\nimport fixture.api.publicGreeting;\n" +
                "fun greeting(): String = publicGreeting(\"hello\")\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "publicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertTrue(plan.workspaceEdit.edits.isNotEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesCrossFileOverloadFamily() {
        val fixture = moveFixture()
        val declaration = fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt")
        declaration.writeText("package fixture.api\npublic fun publicGreeting(value: String): String = value\n")
        fixture.root.resolve("src/main/kotlin/fixture/api/Other.kt").writeText(
            "package fixture.api\npublic fun publicGreeting(value: Int): String = value.toString()\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport fixture.api.publicGreeting\n" +
                "fun greeting(): String = publicGreeting(\"hello\")\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single {
                it.name == "publicGreeting" &&
                    it.location.path == Path.of("src/main/kotlin/fixture/api/PublicGreeting.kt")
            }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveFunctionShapeUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMovePreservesExplicitNonMovedDescriptorType() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/Shared.kt").writeText(
            "package fixture.api\npublic class Shared\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\nimport fixture.api.Shared\n" +
                "fun publicGreeting(value: Shared): Shared = value\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport fixture.api.Shared\nimport fixture.api.publicGreeting\n" +
                "fun greeting(value: Shared): Shared = publicGreeting(value)\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = catalogue.index.symbols.single { it.name == "publicGreeting" }
        assertEquals(
            "(Lfixture/api/Shared;)Lfixture/api/Shared;",
            catalogue.declarations.getValue(target.id).jvmDescriptor,
        )

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        val edits = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
        assertTrue(edits.none { edit ->
            edit.path == Path.of("src/main/kotlin/fixture/api/PublicGreeting.kt") &&
                edit.textEdits.any { "Shared" in it.newText }
        })
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesDestinationPackageOverloadFamily() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\nfun publicGreeting(value: String): String = value\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/api/v2/Other.kt").apply {
            parent.createDirectories()
            writeText("package fixture.api.v2\nfun publicGreeting(value: Int): String = value.toString()\n")
        }
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport fixture.api.publicGreeting\n" +
                "fun greeting(): String = publicGreeting(\"hello\")\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single {
                it.name == "publicGreeting" &&
                    it.location.path == Path.of("src/main/kotlin/fixture/api/PublicGreeting.kt")
            }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveDestinationOverloadUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesExternalDestinationPackageOverloadFamily() {
        val fixture = moveFixture()
        val dependencyJar = compileExternalPackageFunctions(fixture.toolchain, destinationCollision = true)
        fixture.root.resolve("src/main/kotlin/dep/source/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText("package dep.source\nfun publicGreeting(value: String): String = value\n")
        }
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport dep.source.publicGreeting\n" +
                "fun greeting(): String = publicGreeting(\"hello\")\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val scanned = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val snapshot = withExternalClasspath(scanned, dependencyJar)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val catalogueResult = adapter.compilerSymbols(snapshot)
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(catalogueResult, catalogueResult.toString())
        val target = catalogue.index.symbols.single { it.name == "publicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "dep.target", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status, plan.toString())
        assertEquals("kotlin.moveDestinationOverloadUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesExternalDestinationFileFacadeCollision() {
        val fixture = moveFixture()
        val dependencyJar = compileExternalPackageFunctions(fixture.toolchain, facadeCollision = true)
        fixture.root.resolve("src/main/kotlin/dep/source/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText("package dep.source\nfun publicGreeting(value: String): String = value\n")
        }
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport dep.source.publicGreeting\n" +
                "fun greeting(): String = publicGreeting(\"hello\")\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val scanned = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val snapshot = withExternalClasspath(scanned, dependencyJar)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val catalogueResult = adapter.compilerSymbols(snapshot)
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(catalogueResult, catalogueResult.toString())
        val target = catalogue.index.symbols.single { it.name == "publicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "dep.target", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status, plan.toString())
        assertEquals("kotlin.moveDestinationConflict", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesOutboundBindingSubstitution() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\nfun publicGreeting(value: String): String = dependency(value)\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/api/Dependency.kt").writeText(
            "package fixture.api\nfun dependency(value: String): String = \"source:\" + value\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/api/v2/Dependency.kt").apply {
            parent.createDirectories()
            writeText("package fixture.api.v2\nfun dependency(value: String): String = \"target:\" + value\n")
        }
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport fixture.api.publicGreeting\n" +
                "fun greeting(): String = publicGreeting(\"hello\")\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "publicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status, plan.toString())
        assertEquals("kotlin.moveOutboundBindingChanged", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesExternalPackageFunctionBindingSubstitution() {
        val fixture = moveFixture()
        val dependencyJar = compileExternalPackageFunctions(fixture.toolchain)
        fixture.root.resolve("src/main/kotlin/dep/source/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText(
                "package dep.source\nfun publicGreeting(value: String): String = dependency + helper(!value)\n",
            )
        }
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport dep.source.publicGreeting\n" +
                "fun greeting(): String = publicGreeting(\"hello\")\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val scanned = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val snapshot = withExternalClasspath(scanned, dependencyJar)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val catalogueResult = adapter.compilerSymbols(snapshot)
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(catalogueResult, catalogueResult.toString())
        val target = catalogue.index.symbols.single { it.name == "publicGreeting" }
        assertTrue(catalogue.externalCallableUsages.any {
            it.location.path == Path.of("src/main/kotlin/dep/source/PublicGreeting.kt") &&
                it.jvmOwner == "dep.source.ExternalKt" && it.callableName == "helper" &&
                it.jvmDescriptor == "(Ljava/lang/String;)Ljava/lang/String;"
        }, catalogue.toString())
        assertTrue(catalogue.externalCallableUsages.any {
            it.location.path == Path.of("src/main/kotlin/dep/source/PublicGreeting.kt") &&
                it.jvmOwner == "dep.source.ExternalKt" && it.callableName == "getDependency" &&
                it.jvmDescriptor == "()Ljava/lang/String;"
        }, catalogue.toString())
        assertTrue(catalogue.externalCallableUsages.any {
            it.location.path == Path.of("src/main/kotlin/dep/source/PublicGreeting.kt") &&
                it.jvmOwner == "dep.source.ExternalKt" && it.callableName == "not" &&
                it.jvmDescriptor == "(Ljava/lang/String;)Ljava/lang/String;" &&
                it.location.range.start.character + 1 == it.location.range.end.character
        }, catalogue.toString())

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "dep.target", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status, plan.toString())
        assertEquals("kotlin.moveOutboundBindingChanged", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRequiresExternalConsumerApproval() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic fun publicGreeting(value: String): String = value\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").deleteExisting()
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "publicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2",
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveExternalConsumerApprovalRequired", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesPublicMemberInsidePrivateContainer() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\nprivate class HiddenHost {\n" +
                "    public fun publicGreeting(value: String): String = value\n" +
                "}\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").deleteExisting()
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = catalogue.index.symbols.single { it.name == "publicGreeting" }
        assertTrue(!catalogue.declarations.getValue(target.id).isTopLevelFunction)

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveDeclarationUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesExactJavaConsumer() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic fun publicGreeting(value: String): String = value\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport fixture.api.publicGreeting\n" +
                "fun greeting(): String = publicGreeting(\"hello\")\n",
        )
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").writeText(
            "package fixture.consumer; class Caller { String value() { " +
                "return fixture.api.PublicGreetingKt.publicGreeting(\"hello\"); } }\n",
        )
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "publicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveFunctionJavaConsumerUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesDefaultParameterShape() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic fun publicGreeting(value: String = \"hello\"): String = value\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").deleteExisting()
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
            .index.symbols.single { it.name == "publicGreeting" }

        val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveFunctionShapeUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesExcludedDeclarationShapes() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").deleteExisting()
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val declaration = fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt")
        val cases = linkedMapOf(
            "extension" to "package fixture.api\npublic fun String.publicGreeting(): String = this\n",
            "suspend" to "package fixture.api\npublic suspend fun publicGreeting(value: String): String = value\n",
            "overload" to "package fixture.api\npublic fun publicGreeting(value: String): String = value\n" +
                "public fun publicGreeting(value: Int): String = value.toString()\n",
            "private-overload" to "package fixture.api\nprivate fun publicGreeting(value: Int): String = value.toString()\n" +
                "public fun publicGreeting(value: String): String = value\n",
            "jvm-name" to "package fixture.api\n@kotlin.jvm.JvmName(\"binaryGreeting\")\n" +
                "public fun publicGreeting(value: String): String = value\n",
            "file-jvm-name" to "@file:kotlin.jvm.JvmName(\"GreetingFacade\")\npackage fixture.api\n" +
                "public fun publicGreeting(value: String): String = value\n",
            "annotation" to "package fixture.api\n@Deprecated(\"legacy\")\n" +
                "public fun publicGreeting(value: String): String = value\n",
            "generic" to "package fixture.api\npublic fun <T> publicGreeting(value: T): T = value\n",
            "internal-sibling" to "package fixture.api\ninternal val sharedGreeting = \"hello\"\n" +
                "public fun publicGreeting(value: String): String = value\n",
        )
        cases.forEach { (label, content) ->
            declaration.writeText(content)
            val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
            val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
            val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot), label)
                .index.symbols.first { it.name == "publicGreeting" }

            val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
                snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
            )

            assertEquals(PatchStatus.REFUSED, plan.status, "$label: $plan")
            assertTrue(plan.workspaceEdit.edits.isEmpty(), "$label: $plan")
        }
    }

    @Test
    fun publicTopLevelKotlinFunctionMoveRefusesExcludedConsumerShapes() {
        val fixture = moveFixture()
        val declaration = fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt")
        val consumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        declaration.writeText("package fixture.api\npublic fun publicGreeting(value: String): String = value\n")
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val cases = linkedMapOf(
            "alias" to "package fixture.consumer\nimport fixture.api.publicGreeting as greet\n" +
                "fun greeting(): String = greet(\"hello\")\n",
            "star" to "package fixture.consumer\nimport fixture.api.*\n" +
                "fun greeting(): String = publicGreeting(\"hello\")\n",
            "same-package" to "package fixture.api\nfun greeting(): String = publicGreeting(\"hello\")\n",
            "fully-qualified" to "package fixture.consumer\n" +
                "fun greeting(): String = fixture.api.publicGreeting(\"hello\")\n",
            "callable-reference" to "package fixture.consumer\nimport fixture.api.publicGreeting\n" +
                "val greetingReference: (String) -> String = ::publicGreeting\n" +
                "fun greeting(): String = greetingReference(\"hello\")\n",
        )
        cases.forEach { (label, content) ->
            consumer.writeText(content)
            val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
            val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
            val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot), label)
                .index.symbols.single { it.name == "publicGreeting" }

            val plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
                snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true,
            )

            assertEquals(PatchStatus.REFUSED, plan.status, "$label: $plan")
            assertTrue(plan.workspaceEdit.edits.isEmpty(), "$label: $plan")
        }
    }

    @Test
    fun standaloneCompanionMoveHasStableRefusalAndNoEdits() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic class PublicGreeting {\n" +
                "    public companion /* compiler-PSI evidence */ object { public fun create() = PublicGreeting() }\n" +
                "    public object NestedRegistry\n" +
                "}\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").deleteExisting()
        fixture.root.resolve("src/main/java/fixture/consumer/Caller.java").deleteExisting()
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(fixture.root), fixture.toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = catalogue.index.symbols.single { it.name == "Companion" }
        val nested = catalogue.index.symbols.single { it.name == "NestedRegistry" }
        assertTrue(catalogue.declarations.getValue(target.id).isCompanion)
        assertTrue(!catalogue.declarations.getValue(nested.id).isCompanion)
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)
        val nestedPlan = planner.preview(snapshot, nested.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.moveCompanionStandaloneUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
        assertTrue(plan.affectedFiles.isEmpty())
        assertEquals(PatchStatus.REFUSED, nestedPlan.status)
        assertEquals("kotlin.moveDeclarationUnsupported", nestedPlan.refusalCode)
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
    fun publicKotlinTypeMoveUpdatesFullyQualifiedPublicTypeConsumers() {
        val fixture = moveFixture()
        val kotlinConsumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        val javaConsumer = fixture.root.resolve("src/main/java/fixture/consumer/Caller.java")
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic interface GreetingPort\npublic class PublicGreeting : GreetingPort\n",
        )
        kotlinConsumer.writeText(
            "package fixture.consumer\n" +
                "fun greeting(port: fixture.api.GreetingPort): fixture.api.PublicGreeting = " +
                "fixture.api.PublicGreeting()\n",
        )
        javaConsumer.writeText(
            "package fixture.consumer;\nclass Caller { fixture.api.GreetingPort port; " +
                "fixture.api.PublicGreeting value = new fixture.api.PublicGreeting(); }\n",
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
            plan, snapshot, ApplyAuthorization.explicit("qualified-public-sibling-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertEquals(1, kotlinConsumer.readText().split("fixture.api.v2.GreetingPort").size - 1)
        assertEquals(2, kotlinConsumer.readText().split("fixture.api.v2.PublicGreeting").size - 1)
        assertEquals(1, javaConsumer.readText().split("fixture.api.v2.GreetingPort").size - 1)
        assertEquals(2, javaConsumer.readText().split("fixture.api.v2.PublicGreeting").size - 1)
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(kotlinBefore.contentEquals(kotlinConsumer.readBytes()))
        assertTrue(javaBefore.contentEquals(javaConsumer.readBytes()))
    }

    @Test
    fun publicKotlinTypeMoveAddsImportsForSamePackagePublicTypeConsumers() {
        val fixture = moveFixture()
        val kotlinConsumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        val javaConsumer = fixture.root.resolve("src/main/java/fixture/consumer/Caller.java")
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic interface GreetingPort\npublic class PublicGreeting : GreetingPort\n",
        )
        kotlinConsumer.writeText(
            "package fixture.api\nfun greeting(port: GreetingPort): PublicGreeting = PublicGreeting()\n",
        )
        javaConsumer.writeText(
            "package fixture.api;\nclass Caller { GreetingPort port; PublicGreeting value = new PublicGreeting(); }\n",
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
            plan, snapshot, ApplyAuthorization.explicit("same-package-public-sibling-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue("package fixture.api\nimport fixture.api.v2.GreetingPort\nimport fixture.api.v2.PublicGreeting" in kotlinConsumer.readText())
        assertTrue("package fixture.api;\nimport fixture.api.v2.GreetingPort;\nimport fixture.api.v2.PublicGreeting;" in javaConsumer.readText())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(kotlinBefore.contentEquals(kotlinConsumer.readBytes()))
        assertTrue(javaBefore.contentEquals(javaConsumer.readBytes()))
    }

    @Test
    fun publicKotlinTypeMoveRefusesMixedExplicitAndStarSiblingImports() {
        val fixture = moveFixture()
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic interface GreetingPort\npublic class PublicGreeting : GreetingPort\n",
        )
        fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt").writeText(
            "package fixture.consumer\nimport fixture.api.*\nimport fixture.api.PublicGreeting\n" +
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
    fun publicKotlinTypeMoveAddsExplicitImportsForStarConsumedPublicTypes() {
        val fixture = moveFixture()
        val kotlinConsumer = fixture.root.resolve("src/main/kotlin/fixture/consumer/UseGreeting.kt")
        val javaConsumer = fixture.root.resolve("src/main/java/fixture/consumer/Caller.java")
        fixture.root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").writeText(
            "package fixture.api\npublic interface GreetingPort\npublic class PublicGreeting : GreetingPort\n",
        )
        kotlinConsumer.writeText(
            "package fixture.consumer\nimport fixture.api.*\n" +
                "fun greeting(port: GreetingPort): PublicGreeting = PublicGreeting()\n",
        )
        javaConsumer.writeText(
            "package fixture.consumer;\nimport fixture.api.*;\n" +
                "class Caller { GreetingPort port; PublicGreeting value = new PublicGreeting(); }\n",
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
            plan, snapshot, ApplyAuthorization.explicit("star-public-sibling-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue("import fixture.api.*\nimport fixture.api.v2.GreetingPort\nimport fixture.api.v2.PublicGreeting" in kotlinConsumer.readText())
        assertTrue("import fixture.api.*;\nimport fixture.api.v2.GreetingPort;\nimport fixture.api.v2.PublicGreeting;" in javaConsumer.readText())
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(kotlinBefore.contentEquals(kotlinConsumer.readBytes()))
        assertTrue(javaBefore.contentEquals(javaConsumer.readBytes()))
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
    fun publicKotlinTypeMoveCanSelectNonFilenamePublicSibling() {
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
            .index.symbols.single { it.name == "PublicGreetingPort" }
        val planner = KotlinJvmMoveDeclarationPlanner(adapter)

        val plan = planner.preview(snapshot, target.id, "fixture.api.v2", acceptExternalConsumerRisk = true)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertTrue(plan.summary.contains("led by 'PublicGreetingPort'"))
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, snapshot, ApplyAuthorization.explicit("selected-public-sibling-kotlin-move-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue("import fixture.api.v2.PublicGreetingPort" in kotlinConsumer.readText())
        assertTrue("import fixture.api.v2.PublicGreeting" in kotlinConsumer.readText())
        assertTrue("import fixture.api.v2.PublicGreetingPort;" in javaConsumer.readText())
        assertTrue("import fixture.api.v2.PublicGreeting;" in javaConsumer.readText())
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/api/v2/PublicGreeting.kt").exists())
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

    private fun compileExternalPackageFunctions(
        toolchain: KotlinSemanticToolchain,
        destinationCollision: Boolean = false,
        facadeCollision: Boolean = false,
    ): Path {
        val root = temporaryDirectory("rk-jvm-external-package-functions")
        val sourcePackage = root.resolve("src/dep/source/External.kt").apply {
            parent.createDirectories()
            writeText(
                "package dep.source\nval dependency: String = \"source-property:\"\n" +
                    "fun helper(value: String): String = \"source:\" + value\n" +
                    "operator fun String.not(): String = \"source-operator:\" + this\n",
            )
        }
        val targetPackage = root.resolve("src/dep/target/External.kt").apply {
            parent.createDirectories()
            writeText(
                "package dep.target\nval dependency: String = \"target-property:\"\n" +
                    "fun helper(value: String): String = \"target:\" + value\n" +
                    "operator fun String.not(): String = \"target-operator:\" + this\n" +
                    if (destinationCollision) {
                        "fun publicGreeting(value: Int): String = value.toString()\n"
                    } else "",
            )
        }
        val facadePackage = if (facadeCollision) root.resolve("src/dep/target/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText("package dep.target\nfun otherDependencyCallable(): String = \"other\"\n")
        } else null
        val jar = root.resolve("external-functions.jar")
        val compilerRuntime = (listOf(toolchain.compilerJar) + toolchain.compilerClasspath)
            .joinToString(File.pathSeparator)
        val compilationClasspath = toolchain.compilerClasspath.joinToString(File.pathSeparator)
        val command = mutableListOf(
            toolchain.javaExecutable.toString(), "-cp", compilerRuntime,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-d", jar.toString(), "-jdk-home", toolchain.jdkHome.toString(),
            "-classpath", compilationClasspath, "-jvm-target", "21",
            sourcePackage.toString(), targetPackage.toString(),
        )
        facadePackage?.let { command += it.toString() }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val completed = process.waitFor(120, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(completed, "external Kotlin fixture compilation timed out: $output")
        assertEquals(0, process.exitValue(), output)
        assertTrue(Files.isRegularFile(jar), "external Kotlin fixture JAR missing")
        return jar
    }

    private fun withExternalClasspath(snapshot: org.refactorkit.core.ProjectSnapshot, jar: Path): org.refactorkit.core.ProjectSnapshot {
        val evidence = ClasspathEvidence.capture(snapshot.workspace.root, jar, ClasspathEvidenceKind.ENTRY)
        return snapshot.copy(
            modules = snapshot.modules.map { module ->
                module.copy(
                    classpathEntries = (module.classpathEntries + listOf(jar)).distinct(),
                    mainClasspathEntries = (module.mainClasspathEntries + listOf(jar)).distinct(),
                    mainRuntimeClasspathEntries = (module.mainRuntimeClasspathEntries + listOf(jar)).distinct(),
                    testClasspathEntries = (module.testClasspathEntries + listOf(jar)).distinct(),
                )
            },
            buildModels = snapshot.buildModels.map { model ->
                model.copy(modules = model.modules.map { module ->
                    module.copy(sourceSets = module.sourceSets.map { sourceSet ->
                        sourceSet.copy(
                            classpathEntries = (sourceSet.classpathEntries + listOf(jar)).distinct(),
                            runtimeClasspathEntries = (sourceSet.runtimeClasspathEntries + listOf(jar)).distinct(),
                        )
                    })
                })
            },
            classpathEvidence = (snapshot.classpathEvidence.filterNot { it.path.normalize() == jar.normalize() } + evidence),
        )
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
        if (!Files.exists(root)) return
        val paths = Files.walk(root).use { stream -> stream.toList() }
        paths.sortedByDescending(Path::getNameCount).forEach { path ->
            require(!Files.isSymbolicLink(path)) { "Temporary test cleanup refuses symbolic link: $path" }
            Files.delete(path)
        }
    }
}
