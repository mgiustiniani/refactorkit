package org.refactorkit.kotlin

import org.junit.jupiter.api.AfterEach
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.kotlin.bridge.KotlinCompilerBridgeMain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinCompilerDiagnosticsTest {
    private val temporaryRoots = mutableListOf<Path>()

    @AfterEach
    fun removeTemporaryRoots() {
        temporaryRoots.asReversed().forEach { it.toFile().deleteRecursively() }
        temporaryRoots.clear()
    }

    @Test
    fun realK2WorkerReturnsCompilerDiagnosticsWithoutMutatingSources() {
        val root = project("class Broken(val missing: MissingType)\n")
        val source = root.resolve("src/main/kotlin/fixture/Broken.kt")
        val original = source.readText()
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = KotlinCompilerDiagnostics(toolchain).analyze(snapshot)

        val available = assertIs<KotlinCompilerDiagnosticsResult.Available>(result)
        val unresolved = available.diagnostics.firstOrNull { it.message.contains("unresolved", ignoreCase = true) }
            ?: error(available.toString())
        assertEquals(DiagnosticEvidence.COMPILER, unresolved.evidence)
        assertEquals(org.refactorkit.core.DiagnosticLocationPrecision.LINE_ONLY, unresolved.locationPrecision)
        assertEquals(Path.of("src/main/kotlin/fixture/Broken.kt"), unresolved.location?.path)
        assertEquals(1, unresolved.location?.range?.start?.line)
        assertEquals(original, source.readText())
        assertEquals(snapshot.hash, available.attestation.snapshotHash)
        assertTrue(available.attestation.process != null)
        assertEquals(KotlinCompilerDiagnostics.BACKEND, available.attestation.backend)
    }

    @Test
    fun validKotlinSourceProducesAvailableEmptyDiagnostics() {
        val root = project("class Valid\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val analyzed = KotlinCompilerDiagnostics(toolchain).analyze(snapshot)
        val result = assertIs<KotlinCompilerDiagnosticsResult.Available>(analyzed, analyzed.toString())

        assertTrue(result.diagnostics.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun successfulK2CompilationReturnsDurableJvmTypeSymbolsWithExactPsiRanges() {
        val declarations = """
            /*😀*/ class Holder {
                fun member(input: String): Int = input.length
                companion object { fun companionCall(): String = "companion" }
                object NestedRegistry { fun nestedObjectCall(): String = "nested" }
                class Nested
                interface NestedPort { fun nestedCall(): Unit }
                enum class NestedMode { ACTIVE }
                annotation class NestedMarker
            }
            object Registry { fun find(): String = "found" }
            data object DataRegistry { fun dataCall(): String = "data" }
            interface Port { fun execute(value: Int): String }
            enum class Mode { ACTIVE; fun modeLabel(): String = name }
            annotation class Marker
            fun topLevel(value: Int): String = value.toString()
            fun String.extensionCall(prefix: String): String = prefix + this
            suspend fun suspendCall(value: Int): String = value.toString()
            fun <T> genericCall(value: T): T = value
            fun defaultCall(value: String = "default"): String = value
            fun callTopLevel(): String = topLevel(1)
            fun callMember(holder: Holder): Int = holder.member("value")
            fun callCompanion(): String = Holder.companionCall()
            fun callObject(): String = Registry.find()
            fun callExtension(): String = "value".extensionCall("prefix")
        """.trimIndent() + "\n"
        val root = project(declarations)
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val analyzed = KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(analyzed, analyzed.toString())

        assertEquals(KotlinCompilerDiagnostics.SYMBOL_BACKEND, result.attestation.backend)
        assertEquals(
            mapOf(
                "Companion" to org.refactorkit.core.Symbol.Kind.OBJECT,
                "DataRegistry" to org.refactorkit.core.Symbol.Kind.OBJECT,
                "callCompanion" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "callExtension" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "callMember" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "callObject" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "callTopLevel" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "companionCall" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "dataCall" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "defaultCall" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "execute" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "extensionCall" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "find" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "genericCall" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "holder" to org.refactorkit.core.Symbol.Kind.PARAMETER,
                "Holder" to org.refactorkit.core.Symbol.Kind.CLASS,
                "input" to org.refactorkit.core.Symbol.Kind.PARAMETER,
                "Marker" to org.refactorkit.core.Symbol.Kind.ANNOTATION,
                "Mode" to org.refactorkit.core.Symbol.Kind.ENUM,
                "Nested" to org.refactorkit.core.Symbol.Kind.CLASS,
                "NestedMarker" to org.refactorkit.core.Symbol.Kind.ANNOTATION,
                "NestedMode" to org.refactorkit.core.Symbol.Kind.ENUM,
                "NestedPort" to org.refactorkit.core.Symbol.Kind.INTERFACE,
                "NestedRegistry" to org.refactorkit.core.Symbol.Kind.OBJECT,
                "Port" to org.refactorkit.core.Symbol.Kind.INTERFACE,
                "prefix" to org.refactorkit.core.Symbol.Kind.PARAMETER,
                "member" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "modeLabel" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "nestedCall" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "nestedObjectCall" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "Registry" to org.refactorkit.core.Symbol.Kind.OBJECT,
                "suspendCall" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "T" to org.refactorkit.core.Symbol.Kind.TYPE_PARAMETER,
                "topLevel" to org.refactorkit.core.Symbol.Kind.FUNCTION,
                "value" to org.refactorkit.core.Symbol.Kind.PARAMETER,
            ),
            result.index.symbols.sortedBy { it.name }.associate { it.name to it.kind },
        )
        assertEquals(result.index.symbols.size, result.index.symbols.map { it.id }.distinct().size)
        val namesById = result.index.symbols.associate { it.id to it.name }
        val functionIds = result.index.symbols.filter { it.kind == org.refactorkit.core.Symbol.Kind.FUNCTION }
            .mapTo(mutableSetOf()) { it.id }
        assertEquals(
            listOf("companionCall", "extensionCall", "find", "member", "topLevel"),
            result.usages.filter { it.targetId in functionIds }.map { namesById.getValue(it.targetId) }.sorted(),
        )
        result.usages.forEach { usage ->
            val source = snapshot.files.single { it.path == usage.location.path }.content
            val range = usage.location.range
            val line = source.lineSequence().elementAt(range.start.line)
            assertEquals(namesById.getValue(usage.targetId), line.substring(range.start.character, range.end.character))
        }
        result.index.symbols.forEach { symbol ->
            val idPattern = when (symbol.kind) {
                org.refactorkit.core.Symbol.Kind.FUNCTION -> Regex("kotlin-jvm-callable-v1:[0-9a-f]{64}")
                org.refactorkit.core.Symbol.Kind.PARAMETER -> Regex("kotlin-jvm-parameter-v1:[0-9a-f]{64}")
                org.refactorkit.core.Symbol.Kind.TYPE_PARAMETER -> Regex("kotlin-jvm-type-parameter-v1:[0-9a-f]{64}")
                else -> Regex("kotlin-jvm-type-v1:[0-9a-f]{64}")
            }
            assertTrue(symbol.id.value.matches(idPattern), symbol.toString())
            assertEquals(Path.of("src/main/kotlin/fixture/Broken.kt"), symbol.location.path)
            val source = snapshot.files.single { it.path == symbol.location.path }.content
            val line = source.lineSequence().elementAt(symbol.location.range.start.line)
            val selected = line.substring(symbol.location.range.start.character, symbol.location.range.end.character)
            assertEquals(if (symbol.name == "Companion") "object" else symbol.name, selected)
        }
        assertEquals(
            "/*😀*/ class ".length,
            result.index.symbols.single { it.name == "Holder" }.location.range.start.character,
        )
        assertNotNull(result.attestation.process)

        root.resolve("src/main/kotlin/fixture/Broken.kt").writeText(
            "package fixture\n\n\n$declarations",
        )
        val shiftedSnapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val shifted = assertIs<KotlinCompilerSymbolsResult.Available>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(shiftedSnapshot),
        )
        assertEquals(
            result.index.symbols.associate { it.name to it.id },
            shifted.index.symbols.associate { it.name to it.id },
        )
        assertEquals(result.usages.map { it.targetId }, shifted.usages.map { it.targetId })
        assertTrue(shifted.usages.first().location.range.start.line > result.usages.first().location.range.start.line)
        assertTrue(shifted.index.symbols.single { it.name == "Holder" }.location.range.start.line >
            result.index.symbols.single { it.name == "Holder" }.location.range.start.line)
    }

    @Test
    fun successfulK2CompilationReturnsCompilerProvenTypeUsagesWithExactPsiRanges() {
        val root = project("""
            annotation class Marker
            interface Port
            class Box<T>
            class Outer { class Nested }
            object Registry
            @Marker
            class Consumer(
                val port: Port,
                val boxes: Box<Outer.Nested>,
            ) {
                fun construct(): Outer.Nested = Outer.Nested()
                fun check(value: Any): Boolean = value is Outer.Nested
                fun cast(value: Any): Outer.Nested = value as Outer.Nested
                fun registry(): Registry = Registry
            }
        """.trimIndent() + "\n")
        root.resolve("src/main/kotlin/consumer").createDirectories()
        root.resolve("src/main/kotlin/consumer/Imported.kt").writeText("""
            package consumer
            import fixture.Box
            class Imported(val box: Box<String>)
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val analyzed = KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(analyzed, analyzed.toString())
        val typesById = result.index.symbols.filter { it.kind != org.refactorkit.core.Symbol.Kind.FUNCTION }
            .associateBy { it.id }
        val usages = result.usages.mapNotNull { usage -> typesById[usage.targetId]?.let { it to usage.location } }

        assertTrue(usages.isNotEmpty())
        assertTrue(usages.map { it.first.name }.toSet().containsAll(
            setOf("Marker", "Port", "Box", "Outer", "Nested", "Registry"),
        ))
        assertTrue(usages.count { it.first.name == "Box" } >= 3, usages.toString())
        assertTrue(usages.count { it.first.name == "Nested" } >= 5, usages.toString())
        usages.forEach { (symbol, location) ->
            val source = snapshot.files.single { it.path == location.path }.content
            val range = location.range
            val line = source.lineSequence().elementAt(range.start.line)
            assertEquals(symbol.name, line.substring(range.start.character, range.end.character))
        }
    }

    @Test
    fun privateTypeRenamePreviewUsesCompleteK2TokensAndStagedCompilerDiagnostics() {
        val root = project("""
            private class Secret
            private fun create(value: Secret): Secret = Secret()
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = symbols.index.symbols.single { it.name == "Secret" }

        val plan = KotlinPrivateDeclarationRenamePlanner(adapter).preview(snapshot, target.id, "Credential")

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(4, plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>()
            .flatMap { it.textEdits }.size)
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
        assertTrue(plan.requiresUserApproval)
        assertEquals(org.refactorkit.core.RefactoringEvidence.NATIVE_AST, plan.evidence)
    }

    @Test
    fun privateFunctionRenamePreviewUsesDirectK2CallEvidence() {
        val root = project("""
            private fun calculate(value: Int): Int = value + 1
            private fun invokeCalculation(): Int = calculate(1)
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = symbols.index.symbols.single { it.name == "calculate" }

        val plan = KotlinPrivateDeclarationRenamePlanner(adapter).preview(snapshot, target.id, "compute")

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(2, plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>()
            .flatMap { it.textEdits }.size)
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun privatePropertyRenameUsesFieldIdentityAndResolvedAccesses() {
        val root = project("""
            private var counter: Int = 0
            private fun increment(): Int { counter += 1; return counter }
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = symbols.index.symbols.single { it.name == "counter" }

        val plan = KotlinPrivateDeclarationRenamePlanner(adapter).preview(snapshot, target.id, "total")

        assertEquals(org.refactorkit.core.Symbol.Kind.PROPERTY, target.kind)
        assertTrue(target.id.value.matches(Regex("kotlin-jvm-property-v1:[0-9a-f]{64}")))
        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(3, plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>()
            .flatMap { it.textEdits }.size)
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun privateFunctionParameterRenameUsesOwnerDescriptorOrdinalIdentity() {
        val root = project("private fun format(value: Int): String = value.toString()\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = symbols.index.symbols.single { it.name == "value" }

        val plan = KotlinPrivateDeclarationRenamePlanner(adapter).preview(snapshot, target.id, "number")

        assertEquals(org.refactorkit.core.Symbol.Kind.PARAMETER, target.kind)
        assertTrue(target.id.value.matches(Regex("kotlin-jvm-parameter-v1:[0-9a-f]{64}")))
        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(2, plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>()
            .flatMap { it.textEdits }.size)
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun privateFunctionTypeParameterRenameUsesExactFirSymbolEvidence() {
        val root = project("private fun <T> identity(value: T): T = value\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = symbols.index.symbols.single { it.name == "T" }

        val plan = KotlinPrivateDeclarationRenamePlanner(adapter).preview(snapshot, target.id, "R")

        assertEquals(org.refactorkit.core.Symbol.Kind.TYPE_PARAMETER, target.kind)
        assertTrue(target.id.value.matches(Regex("kotlin-jvm-type-parameter-v1:[0-9a-f]{64}")))
        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(3, plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>()
            .flatMap { it.textEdits }.size)
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun compilerOutputLetsJdtProveJavaUsesOfPublicKotlinType() {
        val root = project("public class PublicGreeting\n")
        root.resolve("src/main/java/fixture/Caller.java").apply {
            java.nio.file.Files.createDirectories(parent)
            writeText(
                "package fixture; class Caller { PublicGreeting value = new PublicGreeting(); }\n",
            )
        }
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        var javaUses = emptyList<org.refactorkit.java.JdtJavaSemanticBindingUse>()

        val result = adapter.compilerDiagnosticsWithOutput(snapshot) { output ->
            javaUses = org.refactorkit.java.JdtJavaSemanticAnalyzer().analyze(
                snapshot,
                additionalClasspathEntries = listOf(output),
            ).bindingUses.filter { it.symbolQualifiedName == "fixture.PublicGreeting" }
        }

        val available = assertIs<KotlinCompilerDiagnosticsResult.Available>(result)
        val target = available.symbols!!.symbols.single { it.name == "PublicGreeting" }
        assertEquals("fixture.PublicGreeting", available.declarations.getValue(target.id).jvmIdentity)
        assertTrue(javaUses.size >= 2, "expected binary-backed JDT uses, got $javaUses")
        assertTrue(javaUses.all { it.simpleName == "PublicGreeting" })
    }

    @Test
    fun privateTypeRenameRefusesPublicCrossLanguageAndIncompleteImportBoundaries() {
        val root = project("class PublicType\nprivate class PrivateType\n")
        val toolchain = toolchain(root)
        fun preview(name: String, target: String): org.refactorkit.core.PatchPlan {
            val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
            val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
            val symbol = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
                .index.symbols.single { it.name == target }
            return KotlinPrivateDeclarationRenamePlanner(adapter).preview(snapshot, symbol.id, name)
        }

        assertEquals("kotlin.renameVisibilityUnsupported", preview("Renamed", "PublicType").refusalCode)
        root.resolve("src/main/java/fixture").createDirectories()
        root.resolve("src/main/java/fixture/Caller.java").writeText(
            "package fixture; class Caller { PublicType value; }\n",
        )
        assertEquals("kotlin.renameCrossLanguageIncomplete", preview("RenamedPublic", "PublicType").refusalCode)
        assertEquals("kotlin.renameCrossLanguageIncomplete", preview("RenamedPrivate", "PrivateType").refusalCode)
    }

    @Test
    fun compilerPayloadAttestsSourceVisibilityForRenameEligibility() {
        val root = project("class Placeholder\n")
        root.resolve("src/main/kotlin/fixture/Broken.kt").writeText("""
            package fixture
            private class PrivateType
            internal class InternalType
            class PublicType
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = assertIs<KotlinCompilerSymbolsResult.Available>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )
        val symbols = result.index.symbols.associateBy { it.name }

        assertEquals(KotlinDeclarationVisibility.PRIVATE, result.declarations.getValue(symbols.getValue("PrivateType").id).visibility)
        assertEquals(KotlinDeclarationVisibility.INTERNAL, result.declarations.getValue(symbols.getValue("InternalType").id).visibility)
        assertEquals(KotlinDeclarationVisibility.PUBLIC, result.declarations.getValue(symbols.getValue("PublicType").id).visibility)
    }

    @Test
    fun anonymousObjectsAndLocalsAreExcludedWhileDirectPropertiesUseFieldEvidence() {
        val root = project("""
            class Container {
                val anonymous = object {}
                private val callback: () -> Unit = {}
                fun outer() {
                    class Local
                    fun local() {}
                    Local()
                    local()
                }
            }
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val analyzed = KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(analyzed, analyzed.toString())

        assertEquals(listOf("Container", "anonymous", "callback", "outer"), result.index.symbols.map { it.name }.sorted())
        assertEquals(
            setOf(org.refactorkit.core.Symbol.Kind.CLASS, org.refactorkit.core.Symbol.Kind.FUNCTION,
                org.refactorkit.core.Symbol.Kind.PROPERTY),
            result.index.symbols.map { it.kind }.toSet(),
        )
        assertTrue(result.usages.isEmpty())
    }

    @Test
    fun callableReferenceTargetStaysExcludedWhilePropertyInvokeReferenceIsResolved() {
        val root = project("""
            fun target(): String = "value"
            val reference = ::target
            fun callReference(): String = reference()
            fun sameName(sameName: String): String = sameName
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = assertIs<KotlinCompilerSymbolsResult.Available>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )

        assertEquals(listOf("callReference", "reference", "sameName", "sameName", "target"), result.index.symbols.map { it.name }.sorted())
        val names = result.index.symbols.associate { it.id to it.name }
        assertEquals(listOf("reference", "sameName"), result.usages.map { names.getValue(it.targetId) })
    }

    @Test
    fun callableIdentityChangesWhenJvmDescriptorChanges() {
        val root = project("fun convert(value: Int): String = value.toString()\n")
        val toolchain = toolchain(root)
        fun functionId(): org.refactorkit.core.SymbolId {
            val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
            return assertIs<KotlinCompilerSymbolsResult.Available>(
                KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
            ).index.symbols.single { it.name == "convert" }.id
        }
        val integerId = functionId()
        root.resolve("src/main/kotlin/fixture/Broken.kt").writeText(
            "package fixture\n\n// offset-only change\nfun convert(value: Int): String = value.toString()\n",
        )
        assertEquals(integerId, functionId())
        root.resolve("src/main/kotlin/fixture/Broken.kt").writeText(
            "package fixture\nfun convert(value: String): String = value\n",
        )

        val stringId = functionId()

        assertTrue(integerId != stringId)
        assertTrue(integerId.value.startsWith("kotlin-jvm-callable-v1:"))
        assertTrue(stringId.value.startsWith("kotlin-jvm-callable-v1:"))
    }

    @Test
    fun jvmRenamedFunctionRefusesWithoutSourceToBinaryGuessing() {
        val root = project("@kotlin.jvm.JvmName(\"binaryName\")\nfun sourceName(): String = \"value\"\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = assertIs<KotlinCompilerSymbolsResult.Refused>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )

        assertEquals("kotlin.symbolCallableEvidenceMissing", result.reason.code)
    }

    @Test
    fun overloadedFunctionNameRefusesWithoutGuessingJvmDescriptor() {
        val root = project("""
            fun calculate(value: Int): String = value.toString()
            fun calculate(value: String): String = value
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = assertIs<KotlinCompilerSymbolsResult.Refused>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )

        assertEquals("kotlin.symbolCallableEvidenceAmbiguous", result.reason.code)
    }

    @Test
    fun compilationErrorsRefuseSymbolsWithoutWeakFallback() {
        val root = project("class Broken(val missing: MissingType)\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = assertIs<KotlinCompilerSymbolsResult.Refused>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )

        assertEquals("kotlin.symbolCompilationFailed", result.reason.code)
        assertNotNull(result.attestation.process)
    }

    @Test
    fun transientWorkerExitRetriesOnceWithinAggregateDeadline() {
        val root = project("class Valid\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = KotlinCompilerDiagnostics(toolchain, 30_000, FlakyKotlinCompilerBridge::class.java)
            .analyze(snapshot)

        assertIs<KotlinCompilerDiagnosticsResult.Available>(result)
    }

    @Test
    fun workerTimeoutTerminatesProcessAndReturnsTypedAttestation() {
        val root = project("class Valid\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = assertIs<KotlinCompilerDiagnosticsResult.Error>(
            KotlinCompilerDiagnostics(toolchain, 100, SlowKotlinCompilerBridge::class.java).analyze(snapshot),
        )

        assertEquals("kotlin.compilerDiagnosticsTimeout", result.failure.code)
        assertTrue(result.attestation.process != null)
    }

    @Test
    fun malformedIncompleteAndMismatchedWorkerOutputFailClosed() {
        val root = project("class Valid\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val diagnostics = KotlinCompilerDiagnostics(toolchain)

        val malformed = assertIs<KotlinCompilerDiagnosticsResult.Error>(
            diagnostics.parseWorkerOutputForTest("not-json", snapshot),
        )
        assertEquals("kotlin.compilerDiagnosticsInvalid", malformed.failure.code)
        val incomplete = assertIs<KotlinCompilerDiagnosticsResult.Error>(
            diagnostics.parseWorkerOutputForTest(
                """{"schema":1,"complete":false,"failure":"kotlin.compilerOutputLimit"}""", snapshot,
            ),
        )
        assertEquals("kotlin.compilerOutputLimit", incomplete.failure.code)
        val mismatch = assertIs<KotlinCompilerDiagnosticsResult.Error>(
            diagnostics.parseWorkerOutputForTest(
                """{"schema":1,"complete":true,"snapshotHash":"${"0".repeat(64)}","exitCode":"OK","xmlBase64":""}""",
                snapshot,
            ),
        )
        assertEquals("kotlin.compilerDiagnosticsSnapshotMismatch", mismatch.failure.code)
        val xml = java.util.Base64.getEncoder().encodeToString("<MESSAGES/>".toByteArray())
        val malformedSymbols = assertIs<KotlinCompilerDiagnosticsResult.Available>(
            diagnostics.parseWorkerOutputForTest(
                """{"schema":1,"complete":true,"snapshotHash":"${snapshot.hash}","exitCode":"OK","xmlBase64":"$xml","symbolsComplete":true,"symbols":[{}]}""",
                snapshot,
            ),
        )
        assertEquals("kotlin.compilerSymbolsInvalid", malformedSymbols.symbolFailure?.code)
        assertEquals(null, malformedSymbols.symbols)
    }

    @Test
    fun missingQualifiedCompilerRuntimeRefusesBeforeProcessExecution() {
        val root = project("enum class Mode { ACTIVE }\n")
        val complete = toolchain(root)
        val incomplete = complete.copy(
            compilerClasspath = complete.compilerClasspath.filterNot {
                it.fileName.toString() == "annotations-13.0.jar"
            },
        )
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), incomplete)

        val result = assertIs<KotlinCompilerDiagnosticsResult.Refused>(
            KotlinCompilerDiagnostics(incomplete).analyze(snapshot),
        )

        assertEquals("kotlin.compilerRuntimeUnavailable", result.reason.code)
        assertEquals(null, result.attestation.process)
    }

    @Test
    fun bridgeRejectsCompilerPluginsAndScriptsBeforeLoadingCompiler() {
        val root = temporaryDirectory("refactorkit-kotlin-bridge-rejection")
        val source = root.resolve("Source.kt").also { it.writeText("class Source\n") }
        val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        val bridgeLocation = Path.of(KotlinCompilerBridgeMain::class.java.protectionDomain.codeSource.location.toURI())
        val process = ProcessBuilder(
            java.toString(), "-cp", bridgeLocation.toString(), KotlinCompilerBridgeMain::class.java.name,
            "a".repeat(64), root.toString(), "--", "-Xplugin=untrusted.jar", source.toString(),
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        assertTrue(output.contains("\"complete\":false"))
        assertTrue(output.contains("kotlin.bridgeArgumentsInvalid"))
    }

    @Test
    fun scriptsPartialModelsAndToolchainDriftRefuseBeforeCompilerExecution() {
        val root = project("class Valid\n")
        root.resolve("src/main/kotlin/fixture/setup.kts").writeText("println(\"not executed\")\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val scripts = assertIs<KotlinCompilerDiagnosticsResult.Refused>(KotlinCompilerDiagnostics(toolchain).analyze(snapshot))
        assertEquals("kotlin.scriptSemanticsUnsupported", scripts.reason.code)
        assertEquals(null, scripts.attestation.process)

        val evidence = toolchain.provenance.evidence.single { it.role == "compiler-classpath-000" }
        Files.write(evidence.path, byteArrayOf(0), java.nio.file.StandardOpenOption.APPEND)
        val drift = assertIs<KotlinCompilerDiagnosticsResult.Refused>(KotlinCompilerDiagnostics(toolchain).analyze(snapshot))
        assertEquals("kotlin.toolchainEvidenceChanged", drift.reason.code)
        assertEquals(null, drift.attestation.process)
    }

    private fun temporaryDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(temporaryRoots::add)

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private fun project(source: String): Path {
        val root = temporaryDirectory("refactorkit-kotlin-compiler-project")
        root.resolve("pom.xml").writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>compiler-fixture</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin>
                <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
        root.resolve("src/main/kotlin/fixture").createDirectories()
        root.resolve("src/main/kotlin/fixture/Broken.kt").writeText("package fixture\n$source")
        return root
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
        assertEquals(requiredRuntimePrefixes.size, runtime.distinctBy { it.fileName.toString() }.size)
        val toolchainRoot = temporaryDirectory("refactorkit-kotlin-real-toolchain")
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
        return when (discovery) {
            is KotlinToolchainDiscovery.Available -> discovery.toolchain
            is KotlinToolchainDiscovery.Refused -> error(discovery.diagnostics.joinToString { "${it.code}: ${it.message}" })
        }
    }
}

object FlakyKotlinCompilerBridge {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val marker = Path.of(arguments[1]).resolve(".transient-worker-retried")
        if (Files.notExists(marker)) {
            Files.createFile(marker)
            kotlin.system.exitProcess(17)
        }
        print("{\"schema\":1,\"complete\":true,\"snapshotHash\":\"${arguments[0]}\"," +
            "\"exitCode\":\"OK\",\"xmlBase64\":\"PE1FU1NBR0VTLz4=\"," +
            "\"symbolsComplete\":true,\"symbols\":[],\"usagesComplete\":true,\"usages\":[]}")
    }
}

object SlowKotlinCompilerBridge {
    @JvmStatic
    fun main(arguments: Array<String>) {
        Thread.sleep(30_000)
    }
}
