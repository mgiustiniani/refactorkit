package org.refactorkit.kotlin

import org.junit.jupiter.api.AfterEach
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.kotlin.bridge.KotlinCompilerBridgeMain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
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
        val companion = result.index.symbols.single { it.name == "Companion" }
        val nestedRegistry = result.index.symbols.single { it.name == "NestedRegistry" }
        val topLevel = result.index.symbols.single { it.name == "topLevel" }
        val member = result.index.symbols.single { it.name == "member" }
        assertTrue(result.declarations.getValue(companion.id).isCompanion)
        assertTrue(!result.declarations.getValue(nestedRegistry.id).isCompanion)
        assertEquals(1, result.declarations.values.count { it.isCompanion })
        assertTrue(result.declarations.getValue(topLevel.id).isTopLevelFunction)
        assertTrue(result.declarations.getValue(topLevel.id).isMovePlainFunction)
        assertTrue(result.declarations.getValue(topLevel.id).isTopLevelDeclaration)
        assertTrue(result.declarations.getValue(topLevel.id).sourceTopLevelDeclarationCount > 0)
        assertTrue(!result.declarations.getValue(member.id).isTopLevelFunction)
        assertTrue(!result.declarations.getValue(member.id).isMovePlainFunction)
        assertTrue(!result.declarations.getValue(member.id).isTopLevelDeclaration)
        listOf("defaultCall", "extensionCall", "genericCall", "suspendCall").forEach { name ->
            val excluded = result.index.symbols.single { it.name == name }
            assertTrue(result.declarations.getValue(excluded.id).isTopLevelFunction)
            assertTrue(!result.declarations.getValue(excluded.id).isMovePlainFunction)
        }
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
    fun successfulK2CompilationProvesExternalTypeAliasUses() {
        val root = project(
            "import java.time.Instant as Moment\nfun now(): Moment = Moment.now()\n",
        )
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = assertIs<KotlinCompilerSymbolsResult.Available>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )
        val usages = result.externalTypeUsages.filter { it.jvmBinaryName == "java.time.Instant" }

        assertEquals(3, usages.size)
        assertEquals(setOf(1, 2), usages.map { it.location.range.start.line }.toSet())
    }

    @Test
    fun successfulK2CompilationProvesImportedTypeWithoutFabricatingAUse() {
        val root = project("import java.util.UUID\nfun answer(): Int = 42\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = assertIs<KotlinCompilerSymbolsResult.Available>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )
        val usages = result.externalTypeUsages.filter { it.jvmBinaryName == "java.util.UUID" }

        assertEquals(1, usages.size)
        assertEquals(java.nio.file.Path.of("src/main/kotlin/fixture/Broken.kt"), usages.single().location.path)
        assertEquals(1, usages.single().location.range.start.line)
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
    fun successfulK2CompilationReturnsAliasUsesForTheResolvedSourceType() {
        val root = project("class PublicGreeting\n")
        root.resolve("src/main/kotlin/consumer").createDirectories()
        val consumer = root.resolve("src/main/kotlin/consumer/Aliased.kt")
        consumer.writeText(
            "package consumer\nimport fixture.PublicGreeting as ApiGreeting\n" +
                "fun greeting(value: ApiGreeting): ApiGreeting = ApiGreeting()\n",
        )
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val analyzed = KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(analyzed, analyzed.toString())
        val target = result.index.symbols.single { it.name == "PublicGreeting" }
        val selections = result.usages.filter { it.targetId == target.id && it.location.path == root.relativize(consumer) }
            .map { usage ->
                val source = consumer.readText()
                val start = org.refactorkit.core.TextEdits.offsetOf(source, usage.location.range.start)
                val end = org.refactorkit.core.TextEdits.offsetOf(source, usage.location.range.end)
                source.substring(start, end)
            }

        assertTrue(selections.contains("PublicGreeting"), selections.toString())
        assertTrue(selections.count { it == "ApiGreeting" } >= 3, selections.toString())
    }

    @Test
    fun organizeImportsRefusesCommentAttachedToImportBlock() {
        val root = project(
            "// import rationale\nimport java.time.Instant\nfun value(): Instant = Instant.now()\n",
        )
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val plan = KotlinOrganizeImportsPlanner(
            KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)),
        ).preview(snapshot, java.nio.file.Path.of("src/main/kotlin/fixture/Broken.kt"))

        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.organizeImportsShapeUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun organizeImportsRemovesCompilerProvenUnusedTypeAndSortsCrLfBlock() {
        val root = project(
            "import java.util.UUID as Id\r\n" +
                "import java.util.*\r\n" +
                "import java.util.concurrent.atomic.AtomicInteger as Counter\r\n" +
                "import java.time.*\r\n" +
                "import java.time.Instant as Moment\r\n" +
                "fun values(): Pair<Moment, Counter> = Moment.now() to Counter()\r\n",
        )
        val source = root.resolve("src/main/kotlin/fixture/Broken.kt")
        val beforeBytes = source.readBytes()
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val planner = KotlinOrganizeImportsPlanner(adapter)

        val plan = planner.preview(snapshot, root.relativize(source))

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<org.refactorkit.core.ApplyResult.Applied>(
            org.refactorkit.core.PatchEngine(root).apply(
                plan, snapshot, org.refactorkit.core.ApplyAuthorization.explicit("kotlin-organize-imports-test"),
                org.refactorkit.core.DiagnosticsGate.enabled("kotlin-k2", planner::diagnostics),
            ),
        )
        assertTrue(source.readText().contains(
            "import java.time.*\r\n" +
                "import java.time.Instant as Moment\r\n" +
                "import java.util.*\r\n" +
                "import java.util.concurrent.atomic.AtomicInteger as Counter\r\nfun values()",
        ))
        assertTrue("java.util.UUID" !in source.readText())
        assertIs<org.refactorkit.core.ApplyResult.Applied>(
            org.refactorkit.core.PatchEngine(root).rollback(applied.transaction),
        )
        assertTrue(beforeBytes.contentEquals(source.readBytes()))
    }

    @Test
    fun organizeImportsUsesCounterfactualK2EvidenceForExternalCallables() {
        val root = project(
            "import java.util.Collections.singletonList\r\n" +
                "import java.util.Collections.unmodifiableList\r\n" +
                "import kotlin.math.absoluteValue\r\n" +
                "fun values(): List<String> = unmodifiableList(listOf(\"value\"))\r\n",
        )
        val source = root.resolve("src/main/kotlin/fixture/Broken.kt")
        val beforeBytes = source.readBytes()
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val planner = KotlinOrganizeImportsPlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))

        val plan = planner.preview(snapshot, root.relativize(source))

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status, plan.toString())
        val applied = assertIs<org.refactorkit.core.ApplyResult.Applied>(
            org.refactorkit.core.PatchEngine(root).apply(
                plan, snapshot, org.refactorkit.core.ApplyAuthorization.explicit("kotlin-callable-import-test"),
                org.refactorkit.core.DiagnosticsGate.enabled("kotlin-k2", planner::diagnostics),
            ),
        )
        assertEquals(
            "package fixture\n" +
                "import java.util.Collections.unmodifiableList\r\n" +
                "fun values(): List<String> = unmodifiableList(listOf(\"value\"))\r\n",
            source.readText(),
        )
        assertIs<org.refactorkit.core.ApplyResult.Applied>(
            org.refactorkit.core.PatchEngine(root).rollback(applied.transaction),
        )
        assertTrue(beforeBytes.contentEquals(source.readBytes()))
    }

    @Test
    fun organizeImportsRefusesCompilingCallableBindingSubstitution() {
        val root = project(
            "import java.util.Collections.emptyList\n" +
                "import java.util.UUID\n" +
                "fun values(): List<String> = emptyList()\n",
        )
        val source = root.resolve("src/main/kotlin/fixture/Broken.kt")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val planner = KotlinOrganizeImportsPlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))

        val plan = planner.preview(snapshot, root.relativize(source))

        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.organizeImportsBindingSubstitution", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
        assertTrue(source.readText().contains("import java.util.Collections.emptyList"))
    }

    @Test
    fun organizeImportsRefusesUnmodeledExternalJavaFieldRatherThanRemovingUsedImport() {
        val root = project(
            "import java.lang.Integer.MAX_VALUE\n" +
                "import java.lang.Long.*\n" +
                "fun value(): String = \"${'$'}{MAX_VALUE::class.qualifiedName}:${'$'}MAX_VALUE\"\n",
        )
        val source = root.resolve("src/main/kotlin/fixture/Broken.kt")
        val before = source.readBytes()
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val planner = KotlinOrganizeImportsPlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))

        val plan = planner.preview(snapshot, root.relativize(source))

        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, plan.status, plan.toString())
        assertEquals("kotlin.usageExternalFieldUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
        assertTrue(before.contentEquals(source.readBytes()))
    }

    @Test
    fun organizeImportsUsesSnapshotBoundEditorConfigLayoutForSourceCallables() {
        val root = project(
            "import fixture.library.render\n" +
                "import java.time.Instant\n" +
                "import kotlin.math.abs\n" +
                "fun value(): String = render(Instant.now(), listOf(abs(-1).toString()))\n",
        )
        root.resolve("src/main/kotlin/fixture/library/Library.kt").apply {
            parent.createDirectories()
            writeText(
                "package fixture.library\n" +
                    "fun render(value: java.time.Instant, items: List<String>): String = value.toString() + items.size\n",
            )
        }
        root.resolve(".editorconfig").writeText(
            "root = true\n" +
                "[*.kt]\n" +
                "ij_kotlin_imports_layout = kotlin.**,java.**,*\n",
        )
        val source = root.resolve("src/main/kotlin/fixture/Broken.kt")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val planner = KotlinOrganizeImportsPlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))

        val plan = planner.preview(snapshot, root.relativize(source))

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status, plan.toString())
        val edit = plan.workspaceEdit.edits.single() as org.refactorkit.core.FileEdit.Modify
        val formatted = org.refactorkit.core.TextEdits.apply(source.readText(), edit.textEdits)
        assertTrue(
            formatted.contains(
                "import kotlin.math.abs\n\n" +
                    "import java.time.Instant\n\n" +
                    "import fixture.library.render\n",
            ),
            formatted,
        )
        assertTrue(plan.warnings.any { it.contains(".editorconfig") }, plan.warnings.toString())
    }

    @Test
    fun organizeImportsRefusesStaleOrUnsupportedProjectStyleWithoutEdits() {
        val root = project(
            "import java.time.Instant\n" +
                "import java.util.UUID\n" +
                "fun value(): Instant = Instant.now()\n",
        )
        val style = root.resolve(".editorconfig")
        style.writeText("[*.kt]\nij_kotlin_imports_layout = unsupported-token\n")
        val source = root.resolve("src/main/kotlin/fixture/Broken.kt")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val planner = KotlinOrganizeImportsPlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))

        val unsupported = planner.preview(snapshot, root.relativize(source))
        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, unsupported.status)
        assertEquals("kotlin.organizeImportsStyleUnsupported", unsupported.refusalCode)
        assertTrue(unsupported.workspaceEdit.edits.isEmpty())

        style.writeText("[*.kt]\nij_kotlin_imports_layout = java.**,*\n")
        val stale = planner.preview(snapshot, root.relativize(source))
        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, stale.status)
        assertEquals("kotlin.organizeImportsStyleStale", stale.refusalCode)
        assertTrue(stale.workspaceEdit.edits.isEmpty())
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
    fun overrideFamiliesAreExactAndExcludeSameSignatureUnrelatedMethods() {
        val root = project(
            "interface Port { fun render(value: String): String }\n" +
                "open class Base : Port { override fun render(value: String): String = value }\n" +
                "class Child : Base() { override fun render(value: String): String = super.render(value) }\n" +
                "class Unrelated { fun render(value: String): String = value }\n",
        )
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )
        val parameters = result.index.symbols.filter {
            it.kind == org.refactorkit.core.Symbol.Kind.PARAMETER && it.name == "value"
        }
        assertEquals(4, parameters.size)
        val byOwner = parameters.associateBy { result.declarations.getValue(it.id).jvmOwner }
        val family = result.declarations.getValue(byOwner.getValue("fixture.Port").id).overrideFamilyId
        assertTrue(family.matches(Regex("kotlin-override-family-v1:[0-9a-f]{64}")))
        listOf("fixture.Port", "fixture.Base", "fixture.Child").forEach { owner ->
            val evidence = result.declarations.getValue(byOwner.getValue(owner).id)
            assertEquals(family, evidence.overrideFamilyId)
            assertTrue(evidence.isHierarchyMember)
        }
        val unrelated = result.declarations.getValue(byOwner.getValue("fixture.Unrelated").id)
        assertTrue(unrelated.overrideFamilyId != family)
        assertTrue(!unrelated.isHierarchyMember)
    }

    @Test
    fun namedArgumentsResolveToExactOverloadParameterSymbols() {
        val root = project(
            "fun render(value: String = \"default\"): String = value\n" +
                "fun render(value: Int): String = value.toString()\n" +
                "fun call(): String = render(value = \"named\") + render(value = 2)\n",
        )
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )
        val parameters = result.index.symbols.filter {
            it.kind == org.refactorkit.core.Symbol.Kind.PARAMETER && it.name == "value"
        }
        assertEquals(2, parameters.size)
        val selectionsByDescriptor = parameters.associate { parameter ->
            val evidence = result.declarations.getValue(parameter.id)
            evidence.jvmDescriptor.substringBeforeLast('@') to result.usages.filter { it.targetId == parameter.id }
                .map { usage ->
                    val source = snapshot.files.single { it.path == usage.location.path }.content
                    val start = org.refactorkit.core.TextEdits.offsetOf(source, usage.location.range.start)
                    val end = org.refactorkit.core.TextEdits.offsetOf(source, usage.location.range.end)
                    source.substring(start, end)
                }
        }
        assertEquals(listOf("value", "value"), selectionsByDescriptor.getValue("(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(listOf("value", "value"), selectionsByDescriptor.getValue("(I)Ljava/lang/String;"))
    }

    @Test
    fun changeSignatureRefusesCompilerProvenExternalOverrideBoundary() {
        val root = project("""
            class ExternalImpl : java.util.function.Function<String, String> {
                override fun apply(value: String): String = value
            }
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = catalogue.index.symbols.single {
            it.name == "apply" && it.kind == org.refactorkit.core.Symbol.Kind.FUNCTION
        }
        assertTrue(catalogue.declarations.getValue(target.id).hasExternalHierarchyBoundary)

        val plan = KotlinChangeSignaturePlanner(adapter).previewRenameParameter(
            snapshot, target.id, "value", "text", acceptExternalConsumerRisk = true,
        )

        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, plan.status)
        assertEquals("kotlin.changeSignatureExternalHierarchyUnsupported", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun changeSignatureRefusesPreexistingNewNameTokenThatCouldCaptureBindings() {
        val root = project(
            "private val MAX_VALUE: Long = 100L\n" +
                "private fun value(old: Int): Long {\n" +
                "    val first = run { val MAX_VALUE = 7; old.toLong() }\n" +
                "    return first + MAX_VALUE\n" +
                "}\n",
        )
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val target = catalogue.index.symbols.single {
            it.name == "value" && it.kind == org.refactorkit.core.Symbol.Kind.FUNCTION
        }

        val plan = KotlinChangeSignaturePlanner(adapter).previewRenameParameter(
            snapshot, target.id, "old", "MAX_VALUE",
        )

        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, plan.status, plan.toString())
        assertEquals("kotlin.changeSignatureParameterConflict", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
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
    fun compilerModelsAdvancedKotlinJvmShapesAndRefusesDelegatedPropertiesExplicitly() {
        val root = project("""
            import kotlin.jvm.JvmInline
            import kotlin.jvm.JvmName
            data class DataShape(val value: Int)
            sealed class SealedShape
            @JvmInline value class ValueShape(val value: Int)
            suspend fun suspendedShape(): Int = 1
            fun String.extensionShape(): Int = 2
            @JvmName("binaryNamedShape") fun sourceNamedShape(): Int = 3
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val symbolResult = KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(symbolResult, symbolResult.toString())
        fun evidence(name: String, kind: org.refactorkit.core.Symbol.Kind): KotlinCompilerDeclarationEvidence {
            val symbol = result.index.symbols.single { it.name == name && it.kind == kind }
            return result.declarations.getValue(symbol.id)
        }

        assertTrue(evidence("DataShape", org.refactorkit.core.Symbol.Kind.CLASS).isDataClass)
        assertTrue(evidence("SealedShape", org.refactorkit.core.Symbol.Kind.CLASS).isSealedClass)
        assertTrue(evidence("ValueShape", org.refactorkit.core.Symbol.Kind.CLASS).isValueClass)
        assertTrue(evidence("suspendedShape", org.refactorkit.core.Symbol.Kind.FUNCTION).isSuspendFunction)
        assertTrue(evidence("extensionShape", org.refactorkit.core.Symbol.Kind.FUNCTION).hasExtensionReceiver)
        assertTrue(evidence("sourceNamedShape", org.refactorkit.core.Symbol.Kind.FUNCTION).hasJvmNameEffect)
        assertEquals("binaryNamedShape", evidence("sourceNamedShape", org.refactorkit.core.Symbol.Kind.FUNCTION).jvmName)

        val delegatedRoot = project("private val delegatedShape by lazy { 1 }\n")
        val delegatedToolchain = toolchain(delegatedRoot)
        val delegatedSnapshot = KotlinJvmBuildModelIntegration.attach(
            JavaProjectScanner().scan(delegatedRoot), delegatedToolchain,
        )
        val delegated = assertIs<KotlinCompilerSymbolsResult.Refused>(
            KotlinCompilerDiagnostics(delegatedToolchain).analyzeSymbols(delegatedSnapshot),
        )
        assertEquals("kotlin.symbolDelegatedPropertyUnsupported", delegated.reason.code)
    }

    @Test
    fun boundedExtractAndInlineUseExactCompilerExpressionRangesAndRollback() {
        val extractRoot = project("fun answer(): Int = 40 + 2\nfun call(): Int = answer()\n")
        val extractToolchain = toolchain(extractRoot)
        val extractSnapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(extractRoot), extractToolchain)
        val extractAdapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(extractToolchain))
        val extractSource = extractRoot.resolve("src/main/kotlin/fixture/Broken.kt")
        val extractBefore = extractSource.readBytes()

        val extract = KotlinExtractMethodPlanner(extractAdapter).preview(
            extractSnapshot, Path.of("src/main/kotlin/fixture/Broken.kt"), 2, 2, "fortyTwo",
        )

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, extract.status, extract.toString())
        val extractApplied = assertIs<org.refactorkit.core.ApplyResult.Applied>(
            org.refactorkit.core.PatchEngine(extractRoot).apply(
                extract, extractSnapshot, org.refactorkit.core.ApplyAuthorization.explicit("kotlin-extract-test"),
                org.refactorkit.core.DiagnosticsGate.enabled("kotlin-k2", extractAdapter::diagnostics),
            ),
        )
        assertTrue("fun answer(): Int = fortyTwo()" in extractSource.readText())
        assertTrue("private fun fortyTwo() = 40 + 2" in extractSource.readText())
        assertIs<org.refactorkit.core.ApplyResult.Applied>(
            org.refactorkit.core.PatchEngine(extractRoot).rollback(extractApplied.transaction),
        )
        assertTrue(extractBefore.contentEquals(extractSource.readBytes()))

        val inlineRoot = project("private fun fortyTwo() = 40 + 2\nfun call(): Int = fortyTwo()\n")
        val inlineToolchain = toolchain(inlineRoot)
        val inlineSnapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(inlineRoot), inlineToolchain)
        val inlineAdapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(inlineToolchain))
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(inlineAdapter.compilerSymbols(inlineSnapshot))
        val helper = catalogue.index.symbols.single { it.name == "fortyTwo" }
        val inlineSource = inlineRoot.resolve("src/main/kotlin/fixture/Broken.kt")
        val inlineBefore = inlineSource.readBytes()

        val inline = KotlinInlineMethodPlanner(inlineAdapter).preview(inlineSnapshot, helper.id)

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, inline.status, inline.toString())
        val inlineApplied = assertIs<org.refactorkit.core.ApplyResult.Applied>(
            org.refactorkit.core.PatchEngine(inlineRoot).apply(
                inline, inlineSnapshot, org.refactorkit.core.ApplyAuthorization.explicit("kotlin-inline-test"),
                org.refactorkit.core.DiagnosticsGate.enabled("kotlin-k2", inlineAdapter::diagnostics),
            ),
        )
        assertTrue("private fun fortyTwo" !in inlineSource.readText())
        assertTrue("fun call(): Int = (40 + 2)" in inlineSource.readText())
        assertIs<org.refactorkit.core.ApplyResult.Applied>(
            org.refactorkit.core.PatchEngine(inlineRoot).rollback(inlineApplied.transaction),
        )
        assertTrue(inlineBefore.contentEquals(inlineSource.readBytes()))
    }

    @Test
    fun extractRefusesWhenInsertedCallDoesNotBindToNewHelper() {
        val root = project(
            "import java.util.concurrent.ForkJoinPool.getCommonPoolParallelism\n" +
                "fun answer(): Int = 40 + 2\n" +
                "fun imported(): Int = getCommonPoolParallelism()\n",
        )
        val source = root.resolve("src/main/kotlin/fixture/Broken.kt")
        val before = source.readBytes()
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val planner = KotlinExtractMethodPlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))

        val plan = planner.preview(
            snapshot, root.relativize(source), 3, 3, "getCommonPoolParallelism",
        )

        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, plan.status, plan.toString())
        assertEquals("kotlin.extractCallBindingChanged", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
        assertTrue(before.contentEquals(source.readBytes()))

        val shadowRoot = project(
            "import java.util.concurrent.ForkJoinPool.*\n" +
                "fun answer(): Int = 40 + 2\n" +
                "fun imported(): Int = getCommonPoolParallelism()\n",
        )
        val shadowSource = shadowRoot.resolve("src/main/kotlin/fixture/Broken.kt")
        val shadowToolchain = toolchain(shadowRoot)
        val shadowSnapshot = KotlinJvmBuildModelIntegration.attach(
            JavaProjectScanner().scan(shadowRoot), shadowToolchain,
        )
        val shadowPlan = KotlinExtractMethodPlanner(
            KotlinLanguageAdapter(KotlinCompilerDiagnostics(shadowToolchain)),
        ).preview(shadowSnapshot, shadowRoot.relativize(shadowSource), 3, 3, "getCommonPoolParallelism")
        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, shadowPlan.status, shadowPlan.toString())
        assertEquals("kotlin.extractBindingChanged", shadowPlan.refusalCode)
        assertTrue(shadowPlan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun extractAndInlineRefuseGeneratedSourceOwnershipWithoutEdits() {
        val root = project("fun baseline(): Int = 1\n")
        val generated = root.resolve("target/generated-sources/kotlin/fixture/Generated.kt").apply {
            parent.createDirectories()
            writeText(
                "package fixture\n" +
                    "fun answer(): Int = 40 + 2\n" +
                    "private fun tiny(): Int = 20 + 22\n" +
                    "fun use(): Int = tiny()\n",
            )
        }
        val before = generated.readBytes()
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val extract = KotlinExtractMethodPlanner(adapter).preview(
            snapshot, root.relativize(generated), 2, 2, "fortyTwo",
        )
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val helper = catalogue.index.symbols.single {
            it.name == "tiny" && it.kind == org.refactorkit.core.Symbol.Kind.FUNCTION
        }
        val inline = KotlinInlineMethodPlanner(adapter).preview(snapshot, helper.id)

        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, extract.status, extract.toString())
        assertEquals("kotlin.extractSourceOwnershipUnavailable", extract.refusalCode)
        assertTrue(extract.workspaceEdit.edits.isEmpty())
        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, inline.status, inline.toString())
        assertEquals("kotlin.inlineSourceOwnershipUnavailable", inline.refusalCode)
        assertTrue(inline.workspaceEdit.edits.isEmpty())
        assertTrue(before.contentEquals(generated.readBytes()))
    }

    @Test
    fun extractAndInlineRefuseUnprovenControlAndUsageShapesWithoutEdits() {
        val root = project("private fun helper(value: Int): Int = value + 1\nfun call(): Int = helper(1) + helper(2)\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val catalogue = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(snapshot))
        val helper = catalogue.index.symbols.single { it.name == "helper" }

        val extract = KotlinExtractMethodPlanner(adapter).preview(
            snapshot, Path.of("src/main/kotlin/fixture/Broken.kt"), 2, 2, "extracted",
        )
        val inline = KotlinInlineMethodPlanner(adapter).preview(snapshot, helper.id)

        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, extract.status)
        assertEquals("kotlin.extractSelectionUnsupported", extract.refusalCode)
        assertTrue(extract.workspaceEdit.edits.isEmpty())
        assertEquals(org.refactorkit.core.PatchStatus.REFUSED, inline.status)
        assertEquals("kotlin.inlineShapeUnsupported", inline.refusalCode)
        assertTrue(inline.workspaceEdit.edits.isEmpty())
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
            open class Boundary {
                protected class ProtectedType
                protected fun protectedCall(value: String): String = value
                protected val protectedValue: String = "value"
            }
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
        listOf("ProtectedType", "protectedCall", "protectedValue").forEach { name ->
            assertEquals(
                KotlinDeclarationVisibility.PROTECTED,
                result.declarations.getValue(symbols.getValue(name).id).visibility,
            )
        }
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
    fun jvmRenamedFunctionUsesCompilerValidatedLiteralBinaryName() {
        val root = project("@kotlin.jvm.JvmName(\"binaryName\")\nfun sourceName(): String = \"value\"\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val analyzed = KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(analyzed, analyzed.toString())
        val symbol = result.index.symbols.single { it.name == "sourceName" }
        val evidence = result.declarations.getValue(symbol.id)

        assertEquals("binaryName", evidence.jvmName)
        assertEquals("fixture.BrokenKt#binaryName()Ljava/lang/String;", evidence.jvmIdentity)
        assertTrue(symbol.id.value.matches(Regex("kotlin-jvm-callable-v1:[0-9a-f]{64}")))
    }

    @Test
    fun overloadedAndJvmRenamedFunctionsUseExactFirToJvmSignatures() {
        val root = project("""
            fun calculate(value: Int): String = value.toString()
            fun calculate(value: String): String = value
            @kotlin.jvm.JvmName("binaryName")
            fun sourceName(value: Long): Long = value
            fun invokeAll(): String = calculate(1) + calculate("two") + sourceName(3).toString()
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val analyzed = KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(analyzed, analyzed.toString())
        val functions = result.index.symbols.filter { it.kind == org.refactorkit.core.Symbol.Kind.FUNCTION }
        val identities = functions.associate { symbol ->
            symbol.name to result.declarations.getValue(symbol.id).jvmIdentity
        }

        assertEquals(2, functions.count { it.name == "calculate" })
        assertTrue(result.declarations.values.any { it.jvmIdentity == "fixture.BrokenKt#calculate(I)Ljava/lang/String;" })
        assertTrue(result.declarations.values.any {
            it.jvmIdentity == "fixture.BrokenKt#calculate(Ljava/lang/String;)Ljava/lang/String;"
        })
        assertEquals("fixture.BrokenKt#binaryName(J)J", identities.getValue("sourceName"))
        assertEquals(3, result.usages.count { usage ->
            functions.singleOrNull { it.id == usage.targetId }?.name in setOf("calculate", "sourceName")
        })
    }

    @Test
    fun sourceDeclaredConstructorsUseOwnerAndExactJvmDescriptorIdentity() {
        val root = project("""
            class Service(val name: String) {
                constructor(count: Int) : this(count.toString())
            }
            fun create(): Service = Service(1)
            fun read(service: Service): String = service.name
        """.trimIndent() + "\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val analyzed = KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot)
        val result = assertIs<KotlinCompilerSymbolsResult.Available>(analyzed, analyzed.toString())
        val constructors = result.index.symbols.filter { it.kind == org.refactorkit.core.Symbol.Kind.CONSTRUCTOR }

        assertEquals(2, constructors.size)
        assertEquals(setOf("Service"), constructors.mapTo(linkedSetOf()) { it.name })
        assertEquals(
            setOf(
                "fixture.Service#<init>(Ljava/lang/String;)V",
                "fixture.Service#<init>(I)V",
            ),
            constructors.mapTo(linkedSetOf()) { result.declarations.getValue(it.id).jvmIdentity },
        )
        assertTrue(constructors.all { it.id.value.matches(Regex("kotlin-jvm-constructor-v1:[0-9a-f]{64}")) })
        val nameDeclarations = result.index.symbols.filter { it.name == "name" }
        assertEquals(
            setOf(org.refactorkit.core.Symbol.Kind.PARAMETER, org.refactorkit.core.Symbol.Kind.PROPERTY),
            nameDeclarations.mapTo(linkedSetOf()) { it.kind },
        )
        val nameProperty = nameDeclarations.single { it.kind == org.refactorkit.core.Symbol.Kind.PROPERTY }
        assertEquals("fixture.Service#property:name:Ljava/lang/String;", result.declarations.getValue(nameProperty.id).jvmIdentity)
        assertTrue(result.usages.any { it.targetId == nameProperty.id })
        val integerConstructor = constructors.single {
            result.declarations.getValue(it.id).jvmDescriptor == "(I)V"
        }
        assertTrue(result.usages.any { it.targetId == integerConstructor.id })
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
