package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CClangdSemanticResult
import org.refactorkit.c.CReadOnlySdkRequest
import org.refactorkit.c.CClangdConfigurationException
import org.refactorkit.c.CReferenceResult
import org.refactorkit.c.CRenameResult
import org.refactorkit.c.ClangResourceCaptureResult
import org.refactorkit.c.ClangResourceCurrency
import org.refactorkit.c.ClangResourceDirectoryCapture
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangdSemanticClient
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.TextEdits
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CWarmConfigurationSteps {
    private var root: Path? = null
    private var sysrootRoot: Path? = null
    private var secondSysrootRoot: Path? = null
    private var externalSdk: Path? = null
    private var discoveredSdk = false
    private var preservedSdk: Map<Path, String> = emptyMap()
    private val definitionLine: Int get() = if (sysrootRoot == null) 5 else 1
    private lateinit var source: Path
    private lateinit var database: Path
    private lateinit var privateMaterial: Path
    private lateinit var preserved: Map<Path, String>
    private var manager: ExternalSemanticProcessManager? = null
    private var client: ClangdSemanticClient? = null
    private var pid = -1L
    private var result: Any? = null
    private var ownResources = false
    private var resourceRoot: Path? = null
    private var sdkResources: ClangResourceCaptureResult.Captured? = null
    private var resourceAfterChange: ClangResourceCaptureResult.Captured? = null
    private var resourceChange = ""
    private var processArguments: List<String> = emptyList()
    private var startupCondition: String? = null
    private var startupFailure: Throwable? = null
    private var launchDirectoriesBefore: Set<Path>? = null

    @Given("a warm C client retained a complete owned resource tree")
    fun resourceTree() { ownResources = true; capture("root") }

    @Given("a configured C client retains {string} startup resource evidence")
    fun startupResources(condition: String) { ownResources = true; startupCondition = condition; capture("root") }

    @Given("a warm C semantic session captured {string} configuration")
    fun capture(location: String) {
        discoveredSdk = location == "discovered external SDK"
        val workspace = Files.createTempDirectory("refactorkit-c-warm-config-"); root = workspace
        source = workspace.resolve("main.c")
        Files.writeString(source, "#ifdef ENABLED\nint compute(void) { return 1; }\n#else\nint disabled(void) { return 2; }\n#endif\nint main(void) { return compute(); }\n")
        database = workspace.resolve(if (location == "build") "build/compile_commands.json" else "compile_commands.json")
        Files.createDirectories(database.parent)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val sysrootFlags = if (location in setOf("owned sysroot", "two owned sysroots", "external read-only SDK", "discovered external SDK")) {
            val sdk = if (location in setOf("external read-only SDK", "discovered external SDK")) Files.createTempDirectory("rk-c-readonly-sdk-").also { externalSdk = it }
                else workspace.resolve("sdk-root")
            Files.createDirectories(sdk.resolve("usr/include"))
            sysrootRoot = sdk
            Files.writeString(sdk.resolve("usr/include/api.h"), "#pragma once\nint compute(void) { return 17; }\n")
            Files.writeString(source, "#include \"api.h\"\nint main(void) { return compute(); }\n")
            listOf("--sysroot=" + if (externalSdk == null) "sdk-root" else sdk.toString())
        } else emptyList()
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray((listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=c17", "-DENABLED=1") + sysrootFlags + listOf("-c", "main.c")).map(::JsonPrimitive)))
        }
        val units = if (location == "two owned sysroots") {
            val sdk = Files.createDirectories(workspace.resolve("z-other-sdk/usr/include")).parent.parent
            secondSysrootRoot = sdk
            Files.writeString(sdk.resolve("usr/include/api.h"), "#pragma once\nint compute(void) { return 29; }\n")
            Files.writeString(workspace.resolve("other.c"), "#include \"api.h\"\nint other(void) { return compute(); }\n")
            listOf(unit, buildJsonObject {
                put("directory", workspace.toString()); put("file", "other.c")
                put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=c17", "--sysroot=z-other-sdk", "-c", "other.c").map(::JsonPrimitive)))
            })
        } else listOf(unit)
        Files.writeString(database, JsonArray(units).toString())
        var toolchain = when (val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = workspace, clangExecutable = reference.resolve("usr/bin/clang-22"),
            clangdExecutable = reference.resolve("usr/bin/clangd"), clangFormatExecutable = reference.resolve("usr/bin/clang-format"),
            readOnlySdks = if (discoveredSdk) listOf(CReadOnlySdkRequest(requireNotNull(externalSdk), "fixture:warm-sdk", "LicenseRef-RefactorKitFixture")) else emptyList(),
        ))) {
            is ClangToolchainDiscovery.Available -> discovered.toolchain
            is ClangToolchainDiscovery.Refused -> error("Reference toolchain unavailable: ${discovered.diagnostics}")
        }
        assertEquals(listOf("22.1.8", "22.1.8", "22.1.8"), listOf(toolchain.provenance.clangVersion,
            toolchain.provenance.clangdVersion, toolchain.provenance.clangFormatVersion))
        if (ownResources) toolchain = copiedResources(toolchain)
        if (startupCondition != null) toolchain = startupInputs(toolchain)
        val processes = ExternalSemanticProcessManager(); manager = processes
        val requests = if (discoveredSdk) emptyList() else externalSdk?.let { listOf(CReadOnlySdkRequest(it, "fixture:warm-sdk", "LicenseRef-RefactorKitFixture")) }.orEmpty()
        val semantic = ClangdSemanticClient(toolchain, processes, readOnlySdks = requests); client = semantic
        if (startupCondition != null) {
            preserved = files(workspace); launchDirectoriesBefore = launchDirectories(); return
        }
        semantic.start(workspace)
        val provenance = assertNotNull(semantic.processProvenance()); pid = provenance.pid
        privateMaterial = assertNotNull(provenance.workingDirectory)
        if (ownResources) {
            val bytes = Files.newInputStream(Path.of("/proc/$pid/cmdline")).use { it.readNBytes(32_769) }
            assertTrue(bytes.size <= 32_768)
            processArguments = bytes.toString(Charsets.UTF_8).split('\u0000').dropLast(1)
            assertEquals(toolchain.clangdExecutable.toString(), processArguments.first())
        }
        assertTrue(semantic.didOpen(source, Files.readString(source)))
        assertDefinition(semantic.definition(source, definitionLine, 24))
        externalSdk?.let { sdk ->
            val captured = semantic.readOnlySdkProvenance().single()
            if (discoveredSdk) {
                assertEquals(toolchain.readOnlySdkInputs.provenance.single(), captured)
                assertEquals(toolchain.readOnlySdkInputs.identity, toolchain.provenance.readOnlySdkIdentity)
                assertNotNull(toolchain.provenance.readOnlySdkIdentity); assertTrue(toolchain.readOnlySdkInputs.isCurrent())
            }
            assertEquals(sdk, captured.root); assertEquals("fixture:warm-sdk", captured.provenance)
            assertEquals("LicenseRef-RefactorKitFixture", captured.license); assertTrue(captured.identity.matches(Regex("[0-9a-f]{64}")))
            assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
            assertEquals("aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324", toolchain.provenance.evidence.single { it.role == "clangd-executable" }.sha256)
        }
        secondSysrootRoot?.let { sdk ->
            val other = workspace.resolve("other.c"); val text = Files.readString(other)
            assertTrue(semantic.didOpen(other, text))
            val position = TextEdits.positionForOffset(text, text.indexOf("compute"))
            val found = assertIs<CClangdSemanticResult.Found>(semantic.definition(other, position.line, position.character)).definition
            assertEquals(sdk.resolve("usr/include/api.h"), found.file)
            assertEquals(1, found.startLine); assertEquals(4, found.startCharacter)
        }
    }

    private fun startupInputs(toolchain: ClangSemanticToolchain): ClangSemanticToolchain {
        val copied = requireNotNull(resourceRoot)
        val selected = when (startupCondition) {
            "intact capture" -> toolchain
            "capture missing" -> toolchain.copy(resources = ClangResourceDirectoryCapture().capture(
                copied.resolve("missing-resource-capture"),
            ))
            "capture unavailable" -> {
                resourceChange = "resource link inserted"
                Files.writeString(copied.resolve("fixture-script"), "#!/bin/sh\nprintf executed > '${copied.resolve("executed")}'\n")
                Files.createSymbolicLink(copied.resolve("include/indirect.h"), copied.resolve("fixture-script"))
                toolchain.copy(resources = assertIs<ClangResourceCaptureResult.Unavailable>(ClangResourceDirectoryCapture().capture(copied)))
            }
            "declared root differs" -> toolchain.copy(provenance = toolchain.provenance.copy(resourceDir = requireNotNull(sdkResources).snapshot.root.toString()))
            "captured bytes changed" -> {
                Files.writeString(copied.resolve("include/stddef.h"), "\n/* startup resource change */\n", StandardOpenOption.APPEND); toolchain
            }
            "captured root removed" -> {
                Files.walk(copied).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }; toolchain
            }
            else -> error("Unexpected startup resource condition: $startupCondition")
        }
        if (startupCondition !in setOf("capture unavailable", "captured root removed")) {
            resourceAfterChange = assertIs<ClangResourceCaptureResult.Captured>(ClangResourceDirectoryCapture().capture(copied))
        }
        return selected
    }

    @When("the configured C client attempts its resource-bound initialize handshake")
    fun startupHandshake() {
        val semantic = requireNotNull(client)
        startupFailure = runCatching { semantic.start(requireNotNull(root)) }.exceptionOrNull()
        if (startupFailure == null) {
            val provenance = assertNotNull(semantic.processProvenance()); pid = provenance.pid; privateMaterial = provenance.workingDirectory
            val bytes = Files.newInputStream(Path.of("/proc/$pid/cmdline")).use { it.readNBytes(32_769) }; assertTrue(bytes.size <= 32_768)
            processArguments = bytes.toString(Charsets.UTF_8).split('\u0000').dropLast(1)
            assertTrue(semantic.didOpen(source, Files.readString(source)))
            val text = Files.readString(source); val position = TextEdits.positionForOffset(text, text.indexOf("resource_value"))
            result = semantic.definition(source, position.line, position.character)
        }
    }

    @Then("semantic startup resource disposition is {string}")
    fun startupDisposition(expected: String) {
        if (expected == "pinned resource admitted") {
            assertEquals(null, startupFailure); disposition("resource found")
        } else {
            assertEquals("refused before launch", expected)
            val failure = assertIs<CClangdConfigurationException>(startupFailure, startupFailure.toString())
            assertEquals("c.clangdResourcesUnavailable", failure.diagnostic.code)
            assertEquals(null, requireNotNull(client).processProvenance()); assertEquals(null, result)
            assertEquals(requireNotNull(launchDirectoriesBefore), launchDirectories())
        }
    }

    private fun launchDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") }.toList().toSet()
    }

    private fun copiedResources(toolchain: ClangSemanticToolchain): ClangSemanticToolchain {
        val sdk = assertIs<ClangResourceCaptureResult.Captured>(toolchain.resources); sdkResources = sdk
        val copied = Files.createTempDirectory("refactorkit-c-warm-resources-"); resourceRoot = copied
        Files.walk(sdk.snapshot.root).use { paths -> paths.forEach { path ->
            val target = copied.resolve(sdk.snapshot.root.relativize(path))
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(target) else Files.copy(path, target)
        } }
        Files.writeString(copied.resolve("include/rk_resource_probe.h"), "static inline int resource_value(void) { return 73; }\n")
        Files.writeString(source, "#include <rk_resource_probe.h>\nint read_resource(void) { return resource_value(); }\n", StandardOpenOption.APPEND)
        val captured = assertIs<ClangResourceCaptureResult.Captured>(ClangResourceDirectoryCapture().capture(copied))
        return toolchain.copy(provenance = toolchain.provenance.copy(resourceDir = copied.toString()), resources = captured)
    }

    @When("{string} precedes a warm {string} query")
    fun query(change: String, query: String) {
        resourceChange = change
        when (change) {
            "captured define changes" -> Files.writeString(database, Files.readString(database).replace("-DENABLED=1", "-DDISABLED=1"))
            "captured file disappears" -> Files.delete(database)
            "competing file appears" -> {
                val competing = requireNotNull(root).resolve("build/compile_commands.json")
                Files.createDirectories(competing.parent); Files.copy(database, competing)
            }
            "disabled config changes" -> Files.writeString(requireNotNull(root).resolve(".clangd"), "CompileFlags:\n  Add: [-UENABLED]\n")
            "no input changes" -> Unit
            "SDK header changes" -> Files.writeString(requireNotNull(externalSdk).resolve("usr/include/api.h"), "\n/* changed external SDK input */\n", StandardOpenOption.APPEND)
            "SDK header appears" -> Files.writeString(requireNotNull(externalSdk).resolve("usr/include/added.h"), "#pragma once\n")
            "SDK header removed" -> Files.delete(requireNotNull(externalSdk).resolve("usr/include/api.h"))
            "SDK directory added" -> Files.createDirectory(requireNotNull(externalSdk).resolve("usr/include/new-empty"))
            "SDK link inserted" -> Files.createSymbolicLink(requireNotNull(externalSdk).resolve("usr/include/indirect.h"), requireNotNull(externalSdk).resolve("usr/include/api.h"))
            "secondary sysroot header changes" -> Files.writeString(requireNotNull(secondSysrootRoot).resolve("usr/include/api.h"), "\n/* changed second root */\n", StandardOpenOption.APPEND)
            "secondary sysroot header appears" -> Files.writeString(requireNotNull(secondSysrootRoot).resolve("usr/include/added.h"), "#pragma once\n")
            "secondary sysroot header removed" -> Files.delete(requireNotNull(secondSysrootRoot).resolve("usr/include/api.h"))
            "secondary sysroot directory added" -> Files.createDirectory(requireNotNull(secondSysrootRoot).resolve("usr/include/new-empty"))
            "secondary sysroot link inserted" -> Files.createSymbolicLink(requireNotNull(secondSysrootRoot).resolve("usr/include/indirect.h"), requireNotNull(secondSysrootRoot).resolve("usr/include/api.h"))
            "owned sysroot header changes" -> Files.writeString(requireNotNull(sysrootRoot).resolve("usr/include/api.h"), "\n/* changed owned header */\n", StandardOpenOption.APPEND)
            "owned sysroot header appears" -> Files.writeString(requireNotNull(sysrootRoot).resolve("usr/include/added.h"), "#pragma once\n")
            "owned sysroot header removed" -> Files.delete(requireNotNull(sysrootRoot).resolve("usr/include/api.h"))
            "owned sysroot directory added" -> Files.createDirectory(requireNotNull(sysrootRoot).resolve("usr/include/new-empty"))
            "owned sysroot link inserted" -> Files.createSymbolicLink(requireNotNull(sysrootRoot).resolve("usr/include/indirect.h"), requireNotNull(sysrootRoot).resolve("usr/include/api.h"))
            "captured header bytes change" -> Files.writeString(requireNotNull(resourceRoot).resolve("include/stddef.h"),
                "\n/* concurrent owned resource change */\n", StandardOpenOption.APPEND)
            "empty resource file added" -> Files.createFile(requireNotNull(resourceRoot).resolve("include/added.h"))
            "captured header removed" -> Files.delete(requireNotNull(resourceRoot).resolve("include/stddef.h"))
            "resource link inserted" -> {
                val copied = requireNotNull(resourceRoot)
                val script = copied.resolve("fixture-script")
                Files.writeString(script, "#!/bin/sh\nprintf executed > '${copied.resolve("executed")}'\n")
                Files.createSymbolicLink(copied.resolve("include/indirect.h"), script)
            }
            else -> error("Unexpected configuration change: $change")
        }
        if (ownResources) {
            val captured = ClangResourceDirectoryCapture().capture(requireNotNull(resourceRoot))
            if (change == "resource link inserted") assertIs<ClangResourceCaptureResult.Unavailable>(captured)
            else resourceAfterChange = assertIs<ClangResourceCaptureResult.Captured>(captured)
        }
        preserved = files(requireNotNull(root))
        externalSdk?.let { preservedSdk = files(it) }
        result = when (query) {
            "definition" -> requireNotNull(client).definition(source, definitionLine, 24)
            "references" -> requireNotNull(client).references(source, definitionLine, 24)
            "rename" -> requireNotNull(client).rename(source, definitionLine, 24, "total")
            "open" -> requireNotNull(client).didOpen(source, Files.readString(source))
            "resource definition" -> {
                val text = Files.readString(source); val position = TextEdits.positionForOffset(text, text.indexOf("resource_value"))
                requireNotNull(client).definition(source, position.line, position.character)
            }
            else -> error("Unexpected query: $query")
        }
    }

    @Then("the warm query disposition is {string} for its configuration authority")
    fun disposition(expected: String) {
        val changedCode = if (ownResources) "c.clangdResourcesChanged" else if (discoveredSdk) "c.clangdSdkChanged" else "c.clangdConfigurationChanged"
        when (expected) {
            "found" -> assertDefinition(result)
            "resource found" -> {
                val found = assertIs<CClangdSemanticResult.Found>(result, result.toString())
                assertEquals(requireNotNull(resourceRoot).resolve("include/rk_resource_probe.h"), found.definition.file)
                assertEquals(0, found.definition.startLine); assertEquals(18, found.definition.startCharacter)
                assertEquals(32, found.definition.endCharacter)
            }
            "references found" -> {
                val found = assertIs<CReferenceResult.Found>(result, result.toString())
                assertEquals(setOf(Triple(source, 1, 4), Triple(source, 5, 24)),
                    found.references.map { Triple(it.file, it.startLine, it.startCharacter) }.toSet())
            }
            "refused" -> {
                val diagnostics = when (val value = result) {
                    is CClangdSemanticResult.Refused -> value.diagnostics
                    is CRenameResult.Refused -> value.diagnostics
                    else -> error("Expected a refusal without a proposal, observed $value")
                }
                assertTrue(diagnostics.any { it.code == changedCode }, diagnostics.toString())
            }
            "unavailable" -> assertTrue(assertIs<CReferenceResult.Unavailable>(result, result.toString()).diagnostics.any { it.code == changedCode })
            "not opened" -> assertEquals(false, result, "Changed resources must revoke document opening")
            else -> error("Unexpected disposition: $expected")
        }
        if (ownResources && expected in setOf("resource found", "references found")) {
            assertEquals(listOf("--enable-config=false", "--background-index=false", "--pch-storage=memory",
                "--compile-commands-dir=$privateMaterial", "--resource-dir=${requireNotNull(resourceRoot)}"), processArguments.drop(1))
        }
        if (expected !in setOf("found", "resource found", "references found")) {
            assertTrue(requireNotNull(manager).activeProvenance().isEmpty())
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertFalse(Files.exists(privateMaterial), "Stale launch material must be removed")
        }
    }

    @Then("warm configuration checks preserve project bytes and reap owned processes")
    fun readOnlyAndReaped() {
        requireNotNull(client).close()
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty())
        if (pid > 0) assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        if (::privateMaterial.isInitialized) assertFalse(Files.exists(privateMaterial))
        assertEquals(preserved, files(requireNotNull(root)))
        externalSdk?.let { sdk ->
            assertEquals(preservedSdk, files(sdk))
            Files.walk(sdk).use { paths -> assertTrue(paths.noneMatch { it.fileName.toString().endsWith(".o") || it.fileName.toString() in setOf(".refactorkit", "transactions") }) }
            if (resourceChange == "SDK link inserted") assertEquals(sdk.resolve("usr/include/api.h"), Files.readSymbolicLink(sdk.resolve("usr/include/indirect.h")))
            if (resourceChange == "SDK directory added") assertTrue(Files.isDirectory(sdk.resolve("usr/include/new-empty"), NOFOLLOW_LINKS))
        }
        if (sysrootRoot != null) {
            val workspace = requireNotNull(root)
            Files.walk(workspace).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".o") || it.fileName.toString() in setOf(".refactorkit", "transactions") }) }
            if (resourceChange == "owned sysroot link inserted") assertEquals(requireNotNull(sysrootRoot).resolve("usr/include/api.h"), Files.readSymbolicLink(requireNotNull(sysrootRoot).resolve("usr/include/indirect.h")))
            if (resourceChange == "owned sysroot directory added") assertTrue(Files.isDirectory(requireNotNull(sysrootRoot).resolve("usr/include/new-empty"), NOFOLLOW_LINKS))
        }
        secondSysrootRoot?.let { sdk ->
            if (resourceChange == "secondary sysroot link inserted") assertEquals(sdk.resolve("usr/include/api.h"), Files.readSymbolicLink(sdk.resolve("usr/include/indirect.h")))
            if (resourceChange == "secondary sysroot directory added") assertTrue(Files.isDirectory(sdk.resolve("usr/include/new-empty"), NOFOLLOW_LINKS))
        }
        if (ownResources) {
            assertEquals(ClangResourceCurrency.Current, ClangResourceDirectoryCapture().checkCurrent(requireNotNull(sdkResources).snapshot))
            resourceAfterChange?.let { assertEquals(ClangResourceCurrency.Current, ClangResourceDirectoryCapture().checkCurrent(it.snapshot)) }
            val copied = requireNotNull(resourceRoot)
            if (startupCondition == "captured root removed") assertFalse(Files.exists(copied, NOFOLLOW_LINKS))
            assertFalse(Files.exists(copied.resolve("executed")))
            if (resourceChange == "resource link inserted") {
                assertEquals(copied.resolve("fixture-script"), Files.readSymbolicLink(copied.resolve("include/indirect.h")))
                assertEquals("#!/bin/sh\nprintf executed > '${copied.resolve("executed")}'\n", Files.readString(copied.resolve("fixture-script")))
            }
        }
    }

    private fun assertDefinition(value: Any?) {
        val found = assertIs<CClangdSemanticResult.Found>(value)
        assertEquals(sysrootRoot?.resolve("usr/include/api.h") ?: source, found.definition.file)
        assertEquals(1, found.definition.startLine); assertEquals(4, found.definition.startCharacter)
    }

    private fun files(workspace: Path): Map<Path, String> = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { workspace.relativize(it) to Files.readString(it) }
    }

    @After
    fun cleanup() {
        client?.close(); manager?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        externalSdk?.let { sdk -> Files.walk(sdk).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        resourceRoot?.takeIf { Files.exists(it, NOFOLLOW_LINKS) }?.let { copied -> Files.walk(copied).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
