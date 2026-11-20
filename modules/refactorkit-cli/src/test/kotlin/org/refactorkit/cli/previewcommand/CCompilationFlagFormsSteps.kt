package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.cucumber.java.en.And
import kotlinx.serialization.json.*
import org.refactorkit.c.*
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ExternalSemanticProcessManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CCompilationFlagFormsSteps {
    private val fixture = CompilationFlagFormsFixture()

    @Given("a native C compilation flag fixture {string} in {string} mode")
    fun prepare(profile: String, standard: String) = fixture.prepare(profile, standard)

    @When("the captured flag model drives the reference clangd header definition query")
    fun query() = fixture.query()

    @Then("the flag projection and native definition retain the exact configured context")
    fun verify() = fixture.verify()

    @Then("the inadmissible include option is refused before semantic process startup")
    fun refused() = fixture.verifyRefusal()

    @Then("the compilation option outcome is {string} with exact model and native context")
    fun outcome(outcome: String) = fixture.verifyOutcome(outcome)

    @And("flag context acquisition preserves project bytes and closes its semantic process")
    fun confined() = fixture.verifyConfinement()

    @After
    fun close() = fixture.close()
}

private class CompilationFlagFormsFixture : AutoCloseable {
    private var root: Path? = null
    private var outside: Path? = null
    private var profile = ""
    private lateinit var module: Path
    private lateinit var header: Path
    private lateinit var standard: String
    private lateinit var includeFlag: String
    private var ownedSysroot: Path? = null
    private val toolchainSdk: Boolean get() = profile.startsWith("toolchain SDK ")
    private val explicitSdk: Boolean get() = profile.startsWith("external SDK ") || toolchainSdk
    private var previousSdkIdentity: String? = null
    private var previousToolchainIdentity: String? = null
    private var configuredSdkIdentity: String? = null
    private var configuredToolchainIdentity: String? = null
    private var sdkProvenance: List<CReadOnlySdkProvenance> = emptyList()
    private var sdkRename: CRenameResult? = null
    private var sdkSourceRename: CRenameResult? = null
    private var otherSysrootHeader: Path? = null
    private val independentSysroots: Boolean get() = profile.startsWith("sysroot per-TU ")
    private lateinit var arguments: List<String>
    private lateinit var toolchain: ClangSemanticToolchain
    private var baseline: Map<Path, ByteArray> = emptyMap()
    private var parsed: CCompilationDatabaseDiscovery? = null
    private var model: org.refactorkit.core.BuildModel? = null
    private var launchFailure: Throwable? = null
    private var definition: CClangdSemanticResult? = null
    private var admitted: JsonObject? = null
    private var admittedUnits: List<JsonObject> = emptyList()
    private var otherDefinition: CClangdSemanticResult? = null
    private val independentTargets: Boolean get() = profile.startsWith("target per-TU ")
    private var pid: Long? = null
    private var managerEmpty = false
    private fun selectedTarget(): String? = when (profile) {
        "target short i386", "target assigned i386", "target per-TU explicit", "target per-TU implicit" -> "i386-unknown-linux-gnu"
        "target assigned x86_64" -> "x86_64-unknown-linux-gnu"
        else -> null
    }
    private val source = "int main(void) { return compute(); }\n"

    fun prepare(profile: String, mode: String) {
        this.profile = profile
        standard = mode
        assertTrue(mode in setOf("c11", "c17"))
        val workspace = Files.createTempDirectory(Files.createDirectories(Path.of("build/test-tmp")), "c-flag-forms-").toAbsolutePath()
        root = workspace
        module = Files.createDirectory(workspace.resolve("module"))
        val include = Files.createDirectory(module.resolve(when (profile) {
            "separate define" -> "-DDECOY=7"
            "target shaped include" -> "--target=ghost"
            "sysroot shaped include" -> "--sysroot=ghost"
            "removed include separator" -> "-"
            "long system option", "literal dash system" -> "-after"
            "joined long system option" -> "-after=headers"
            "literal equal quote", "literal equal system" -> "=headers"
            "literal dash quote" -> "-headers"
            else -> "headers"
        }))
        if (profile.endsWith("outside") || profile.endsWith("symlink")) {
            outside = Files.createTempDirectory(workspace.parent, "flag-owned-outside-")
            if (profile.endsWith("symlink")) {
                Files.delete(include)
                Files.createSymbolicLink(include, outside!!)
            }
        }
        if (profile in setOf("sysroot assigned relative", "sysroot separate relative", "sysroot assigned absolute", "sysroot duplicate", "sysroot external root", "sysroot linked header", "sysroot driver layout", "sysroot Linux isysroot", "sysroot conflicting source", "sysroot per-TU explicit", "sysroot per-TU implicit")) {
            ownedSysroot = Files.createDirectories(module.resolve("sdk-root"))
        }
        if (profile == "sysroot external root" || explicitSdk) {
            outside = Files.createTempDirectory(workspace.parent, "sysroot-external-")
            ownedSysroot = outside
        }
        header = ownedSysroot?.let { Files.createDirectories(it.resolve("usr/include")).resolve("api.h") }
            ?: (if (profile.endsWith("outside")) outside!! else include).resolve("api.h")
        val targetGuard = when (selectedTarget()) {
            "i386-unknown-linux-gnu" -> "defined(ENABLED) && defined(__i386__) && __SIZEOF_POINTER__ == 4"
            "x86_64-unknown-linux-gnu" -> "defined(ENABLED) && defined(__x86_64__) && __SIZEOF_POINTER__ == 8"
            else -> "defined(ENABLED)"
        }
        val directive = if (selectedTarget() == null) "#ifdef ENABLED" else "#if $targetGuard"
        Files.writeString(header, if (independentTargets)
            "#if defined(ENABLED) && defined(__i386__) && __SIZEOF_POINTER__ == 4\nint compute(void) { return 17; }\n#elif defined(ENABLED) && defined(__x86_64__) && __SIZEOF_POINTER__ == 8\nint compute(void) { return 23; }\n#else\n#error missing_target_context\n#endif\n"
            else if (ownedSysroot != null) "#pragma once\nint compute(void) { return 17; }\n"
            else "$directive\nint compute(void) { return 17; }\n#else\nint disabled(void) { return 0; }\n#endif\n")
        Files.writeString(module.resolve("main.c"), "#include \"api.h\"\n" + source)
        if (profile == "external SDK rename") Files.writeString(module.resolve("main.c"), "static int local_value(void) { return 1; }\n", java.nio.file.StandardOpenOption.APPEND)
        if (profile == "external SDK escaped header") {
            val unowned = module.resolve("not-sdk.h")
            Files.writeString(unowned, "#pragma once\nint outside_sdk(void);\n")
            Files.writeString(header, "#include \"$unowned\"\nint compute(void) { return 17; }\n")
        }
        if (profile == "sysroot linked header") {
            outside = Files.createTempDirectory(workspace.parent, "sysroot-header-target-")
            val target = assertNotNull(outside).resolve("original.h"); Files.copy(header, target)
            Files.delete(header); Files.createSymbolicLink(header, target)
        }
        if (profile == "sysroot driver layout") Files.createDirectories(assertNotNull(ownedSysroot).resolve("usr/lib"))
        val reference = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val discoveryRequests = if (toolchainSdk) mutableListOf(CReadOnlySdkRequest(assertNotNull(ownedSysroot), "fixture:first-party-sdk", "LicenseRef-RefactorKitFixture")) else mutableListOf()
        fun discoverSdk() = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = workspace, clangExecutable = reference.resolve("usr/bin/clang-22"),
            clangdExecutable = reference.resolve("usr/bin/clangd"), clangFormatExecutable = reference.resolve("usr/bin/clang-format"),
            readOnlySdks = discoveryRequests,
        ))).toolchain
        toolchain = discoverSdk()
        if (toolchainSdk) {
            previousSdkIdentity = toolchain.readOnlySdkInputs.identity
            previousToolchainIdentity = toolchain.provenance.toolchainIdentity()
            if (profile in setOf("toolchain SDK refreshed", "toolchain SDK changed"))
                Files.writeString(header, "\n/* explicit SDK byte change */\n", java.nio.file.StandardOpenOption.APPEND)
            if (profile == "toolchain SDK refreshed") toolchain = discoverSdk()
            configuredSdkIdentity = toolchain.readOnlySdkInputs.identity
            configuredToolchainIdentity = toolchain.provenance.toolchainIdentity()
            if (profile == "toolchain SDK forged") toolchain = toolchain.copy(provenance = toolchain.provenance.copy(readOnlySdkIdentity = "f".repeat(64)))
        }
        discoveryRequests.clear() // Retained discovery inputs must not alias the caller list.
        assertTrue(toolchain.provenance.clangVersion.contains("22.1.8"))
        if (explicitSdk) {
            assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
            assertEquals("aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324", toolchain.provenance.evidence.single { it.role == "clangd-executable" }.sha256)
        }
        val flags = when (profile) {
            "toolchain SDK navigation", "toolchain SDK refreshed", "toolchain SDK forged", "toolchain SDK changed" -> { includeFlag = "--sysroot"; listOf("-DENABLED=1", "--sysroot=" + assertNotNull(ownedSysroot)) }
            "external SDK navigation", "external SDK rename", "external SDK invalid scope", "external SDK escaped header" -> { includeFlag = "--sysroot"; listOf("-DENABLED=1", "--sysroot=" + assertNotNull(ownedSysroot)) }
            "sysroot duplicate" -> { includeFlag = "--sysroot"; listOf("-DENABLED=1", "--sysroot=sdk-root", "--sysroot=sdk-root") }
            "sysroot external root" -> { includeFlag = "--sysroot"; listOf("-DENABLED=1", "--sysroot=" + assertNotNull(ownedSysroot)) }
            "sysroot linked header", "sysroot driver layout", "sysroot conflicting source", "sysroot per-TU explicit", "sysroot per-TU implicit" -> { includeFlag = "--sysroot"; listOf("-DENABLED=1", "--sysroot=sdk-root") }
            "sysroot Linux isysroot" -> { includeFlag = "-isysroot"; listOf("-DENABLED=1", "-isysroot", "sdk-root") }
            "sysroot assigned relative" -> { includeFlag = "--sysroot"; listOf("-DENABLED=1", "--sysroot=sdk-root") }
            "sysroot separate relative" -> { includeFlag = "--sysroot"; listOf("-DENABLED=1", "--sysroot", "sdk-root") }
            "sysroot assigned absolute" -> { includeFlag = "--sysroot"; listOf("-DENABLED=1", "--sysroot=" + assertNotNull(ownedSysroot)) }
            "target duplicate identical" -> { includeFlag = "-I"; listOf("-DENABLED=1", "-Iheaders", "-target", "i386-unknown-linux-gnu", "--target=i386-unknown-linux-gnu") }
            "target duplicate conflicting" -> { includeFlag = "-I"; listOf("-DENABLED=1", "-Iheaders", "-target", "i386-unknown-linux-gnu", "--target=x86_64-unknown-linux-gnu") }
            "target unknown triple" -> { includeFlag = "-I"; listOf("-DENABLED=1", "-Iheaders", "--target=ghost") }
            "target invalid long separate" -> { includeFlag = "-I"; listOf("-DENABLED=1", "-Iheaders", "--target", "i386-unknown-linux-gnu") }
            "target invalid short assign" -> { includeFlag = "-I"; listOf("-DENABLED=1", "-Iheaders", "-target=i386-unknown-linux-gnu") }
            "target conflicting source", "target per-TU explicit", "target per-TU implicit" -> { includeFlag = "-I"; listOf("-DENABLED=1", "-Iheaders", "--target=i386-unknown-linux-gnu") }
            "target short i386" -> { includeFlag = "-I"; listOf("-DENABLED=1", "-Iheaders", "-target", assertNotNull(selectedTarget())) }
            "target assigned i386", "target assigned x86_64" -> {
                includeFlag = "-I"; listOf("-DENABLED=1", "-Iheaders", "--target=" + assertNotNull(selectedTarget()))
            }
            "separate define" -> { includeFlag = "-I"; listOf("-D", "ENABLED=1", "-I", "-DDECOY=7") }
            "target shaped include", "sysroot shaped include" -> {
                includeFlag = "-I"
                listOf("-DENABLED=1", "-I", include.fileName.toString())
            }
            "standard shaped output" -> {
                includeFlag = "-I"
                listOf("-DENABLED=1", "-Iheaders")
            }
            "joined quote include" -> { includeFlag = "-iquote"; listOf("-DENABLED=1", "-iquoteheaders") }
            "joined system include" -> { includeFlag = "-isystem"; listOf("-DENABLED=1", "-isystemheaders") }
            "quote outside", "system outside", "quote symlink", "system symlink" -> {
                includeFlag = if (profile.startsWith("quote")) "-iquote" else "-isystem"
                val path = if (profile.endsWith("outside")) module.relativize(outside!!).toString() else "headers"
                listOf("-DENABLED=1", includeFlag + path)
            }
            "missing quote operand" -> { includeFlag = "-iquote"; listOf("-DENABLED=1") }
            "removed include separator" -> { includeFlag = "-I"; listOf("-DENABLED=1", "-I-") }
            "long system option" -> { includeFlag = "-isystem"; listOf("-DENABLED=1", "-isystem-after", "main.c") }
            "joined long system option" -> { includeFlag = "-isystem"; listOf("-DENABLED=1", "-isystem-after=headers") }
            "literal equal quote" -> { includeFlag = "-iquote"; listOf("-DENABLED=1", "-iquote=headers") }
            "literal equal system" -> { includeFlag = "-isystem"; listOf("-DENABLED=1", "-isystem=headers") }
            "literal dash quote" -> { includeFlag = "-iquote"; listOf("-DENABLED=1", "-iquote-headers") }
            "literal dash system" -> { includeFlag = "-isystem"; listOf("-DENABLED=1", "-isystem", "-after") }
            else -> error("Unknown C flag profile: $profile")
        }
        val outputOperand = if (profile == "standard shaped output") "-std=" + (if (mode == "c11") "c17" else "c11") else "-DOUTPUT_DECOY=9"
        arguments = listOf(toolchain.clangExecutable.toString()) +
            (if (profile == "standard shaped output") listOf("-o", outputOperand) else emptyList()) +
            listOf("-std=$mode") + flags +
            (if (profile == "long system option") listOf("-c", "-o", "-DOUTPUT_DECOY=9")
                else if (profile == "standard shaped output") listOf("-c", "main.c")
                else listOf("-c", "main.c", "-o", outputOperand)) +
            (if (profile == "missing quote operand") listOf("-iquote") else emptyList())
        val unit = buildJsonObject {
            put("directory", module.toString()); put("file", "main.c")
            put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
        }
        val units = if (profile == "target conflicting source") {
            listOf(unit, buildJsonObject {
                put("directory", module.toString()); put("file", "main.c")
                put("arguments", JsonArray(listOf(toolchain.clangExecutable.toString(), "-std=$mode", "-DENABLED=1", "-Iheaders", "--target=x86_64-unknown-linux-gnu", "-c", "main.c").map(::JsonPrimitive)))
            })
        } else if (independentTargets) {
            Files.writeString(module.resolve("other.c"), "#include \"api.h\"\nint main_other(void) { return compute(); }\n")
            listOf(unit, buildJsonObject {
                put("directory", module.toString()); put("file", "other.c")
                val otherArgs = listOf(toolchain.clangExecutable.toString(), "-std=$mode", "-DENABLED=1", "-Iheaders") +
                    (if (profile == "target per-TU explicit") listOf("--target=x86_64-unknown-linux-gnu") else emptyList()) + listOf("-c", "other.c")
                put("arguments", JsonArray(otherArgs.map(::JsonPrimitive)))
            })
        } else if (independentSysroots) {
            val otherDir = if (profile == "sysroot per-TU explicit") module.resolve("other-sdk/usr/include") else module.resolve("headers")
            otherSysrootHeader = Files.createDirectories(otherDir).resolve("api.h")
            Files.writeString(assertNotNull(otherSysrootHeader), "#pragma once\nint compute(void) { return 29; }\n")
            Files.writeString(module.resolve("other.c"), "#include \"api.h\"\nint main_other(void) { return compute(); }\n")
            listOf(unit, buildJsonObject {
                put("directory", module.toString()); put("file", "other.c")
                val search = if (profile == "sysroot per-TU explicit") listOf("--sysroot=other-sdk") else listOf("-Iheaders")
                put("arguments", JsonArray((listOf(toolchain.clangExecutable.toString(), "-std=$mode", "-DENABLED=1") + search + listOf("-c", "other.c")).map(::JsonPrimitive)))
            })
        } else if (profile == "sysroot conflicting source") {
            val other = Files.createDirectories(module.resolve("other-sdk/usr/include")).resolve("api.h")
            Files.copy(header, other)
            listOf(unit, buildJsonObject {
                put("directory", module.toString()); put("file", "main.c")
                put("arguments", JsonArray(listOf(toolchain.clangExecutable.toString(), "-std=$mode", "--sysroot=other-sdk", "-c", "main.c").map(::JsonPrimitive)))
            })
        } else listOf(unit)
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(units).toString())
        if (profile == "external SDK invalid scope") Files.delete(workspace.resolve("compile_commands.json"))
        baseline = (listOf(workspace.resolve("compile_commands.json"), module.resolve("main.c"), header) +
            if (independentTargets) listOf(module.resolve("other.c")) else if (independentSysroots) listOf(module.resolve("other.c"), assertNotNull(otherSysrootHeader)) else if (profile == "sysroot conflicting source") listOf(module.resolve("other-sdk/usr/include/api.h")) else emptyList())
            .let { paths ->
                if (profile == "external SDK invalid scope") paths.filterNot { it == workspace.resolve("compile_commands.json") }
                else if (profile == "external SDK escaped header") paths + listOf(module.resolve("not-sdk.h")) else paths
            }.associateWith(Files::readAllBytes)
    }

    fun query() {
        val workspace = requireNotNull(root)
        parsed = if (profile == "external SDK invalid scope") null else CCompilationDatabaseParser().parse(workspace, Files.readAllBytes(workspace.resolve("compile_commands.json")))
        model = CCompilationDatabaseProvider().discover(BuildModelRequest(workspace))
        ExternalSemanticProcessManager().use { manager ->
            val requests = if (explicitSdk && !toolchainSdk) mutableListOf(CReadOnlySdkRequest(assertNotNull(ownedSysroot), "fixture:first-party-sdk", if (profile == "external SDK invalid scope") "" else "LicenseRef-RefactorKitFixture")) else mutableListOf()
            ClangdSemanticClient(toolchain, manager, readOnlySdks = requests).use { client ->
                requests.clear() // Caller mutation must not change the captured explicit scope.
                launchFailure = runCatching { client.start(workspace) }.exceptionOrNull()
                if (launchFailure == null) {
                    pid = assertNotNull(client.processProvenance()).pid
                    sdkProvenance = client.readOnlySdkProvenance()
                    val command = Files.readString(Path.of("/proc/$pid/cmdline")).split('\u0000')
                    val directory = Path.of(command.single { it.startsWith("--compile-commands-dir=") }.substringAfter('='))
                    admittedUnits = Json.parseToJsonElement(Files.readString(directory.resolve("compile_commands.json"))).jsonArray.map { it.jsonObject }
                    admitted = admittedUnits.single { it.getValue("file").jsonPrimitive.content == module.resolve("main.c").toString() }
                    assertTrue(client.didOpen(module.resolve("main.c"), Files.readString(module.resolve("main.c"))))
                    definition = client.definition(module.resolve("main.c"), 1, source.lines()[0].indexOf("compute"))
                    if (profile == "external SDK rename") {
                        sdkRename = client.rename(module.resolve("main.c"), 1, source.lines()[0].indexOf("compute"), "changed")
                        sdkSourceRename = client.rename(module.resolve("main.c"), 2, 11, "local_renamed")
                    }
                    if (independentTargets || independentSysroots) {
                        val other = module.resolve("other.c")
                        assertTrue(client.didOpen(other, Files.readString(other)))
                        otherDefinition = client.definition(other, 1, "int main_other(void) { return compute(); }".indexOf("compute"))
                    }
                }
            }
            managerEmpty = manager.activeProvenance().isEmpty()
        }
    }

    fun verify() {
        if (independentSysroots) { verifyIndependentSysroots(); return }
        if (independentTargets) { verifyIndependentTargets(); return }
        val unit = assertIs<CCompilationDatabaseDiscovery.Available>(parsed).database.units.single()
        assertEquals(arguments, unit.arguments)
        assertEquals(listOf("ENABLED=1"), unit.defines, "Option operands must not manufacture or hide macros")
        assertEquals(if (ownedSysroot == null) listOf(header.parent) else emptyList(), unit.includeDirectories)
        assertEquals(standard, unit.standard)
        assertEquals(selectedTarget(), unit.target, "Explicit target is retained; include operands cannot manufacture one")
        assertEquals(ownedSysroot, unit.sysroot, "Explicit sysroot is retained; include operands cannot manufacture one")
        val projected = assertNotNull(model)
        assertEquals(BuildModelStatus.AVAILABLE, projected.status)
        val projectedUnit = projected.modules.single().sourceSets.single().compilationUnits.single()
        assertEquals(arguments, projectedUnit.arguments)
        assertEquals(standard, projectedUnit.languageFacets.single().sourceVersion)
        assertEquals(selectedTarget(), projectedUnit.languageFacets.single().targetVersion)
        assertNull(launchFailure, "Valid joined or separate options must reach the native query")
        val found = assertIs<CClangdSemanticResult.Found>(definition).definition
        assertEquals(header, found.file)
        assertEquals(1, found.startLine)
        assertEquals(4, found.startCharacter)
        val args = assertNotNull(admitted).getValue("arguments").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(toolchain.clangExecutable.toString(), args.first())
        assertEquals(listOf("-std=$standard"), args.filter { it.startsWith("-std=") })
        assertTrue("-DENABLED=1" in args)
        if (ownedSysroot == null) assertEquals(header.parent.toString(), args[args.indexOf(includeFlag) + 1])
        else {
            val index = args.indexOf("--sysroot")
            val admittedRoot = if (index >= 0) args.getOrNull(index + 1)
                else args.singleOrNull { it.startsWith("--sysroot=") }?.substringAfter('=')
            assertEquals(assertNotNull(ownedSysroot).toString(), admittedRoot)
            assertFalse(args.any { it.startsWith("-I") || it.startsWith("-isystem") || it.startsWith("-iquote") }, "Sysroot must not be replaced by a synthetic include directory")
        }
        assertFalse("-DDECOY=7" in args || "-DOUTPUT_DECOY=9" in args)
        selectedTarget()?.let { target ->
            val separate = args.indexOf("-target")
            assertTrue("--target=$target" in args || (separate >= 0 && args.getOrNull(separate + 1) == target), "Admitted native arguments must preserve the selected target")
        }
    }

    private fun verifyIndependentSysroots() {
        val units = assertIs<CCompilationDatabaseDiscovery.Available>(parsed).database.units
        assertEquals(2, units.size)
        val main = units.single { it.file == module.resolve("main.c") }
        val other = units.single { it.file == module.resolve("other.c") }
        val secondRoot = if (profile == "sysroot per-TU explicit") module.resolve("other-sdk") else null
        assertEquals(arguments, main.arguments); assertEquals(ownedSysroot, main.sysroot)
        assertEquals(secondRoot, other.sysroot)
        assertEquals(emptyList(), main.includeDirectories)
        assertEquals(if (secondRoot == null) listOf(assertNotNull(otherSysrootHeader).parent) else emptyList(), other.includeDirectories)
        val projected = assertNotNull(model).modules.single().sourceSets.single().compilationUnits
        assertEquals(2, projected.size)
        for (unit in units) {
            assertEquals(standard, unit.standard); assertEquals(listOf("ENABLED=1"), unit.defines); assertNull(unit.target)
            assertEquals(unit.arguments, projected.single { root!!.resolve(it.sourceFile) == unit.file }.arguments)
        }
        assertNull(launchFailure, "Distinct translation units must retain independent owned sysroot contexts")
        val first = assertIs<CClangdSemanticResult.Found>(definition).definition
        val second = assertIs<CClangdSemanticResult.Found>(otherDefinition).definition
        assertEquals(header, first.file); assertEquals(otherSysrootHeader, second.file)
        assertEquals(1, first.startLine); assertEquals(1, second.startLine)
        assertEquals(4, first.startCharacter); assertEquals(4, second.startCharacter)
        assertEquals(2, admittedUnits.size)
        for (unit in units) {
            val args = admittedUnits.single { it.getValue("file").jsonPrimitive.content == unit.file.toString() }.getValue("arguments").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(toolchain.clangExecutable.toString(), args.first())
            val index = args.indexOf("--sysroot")
            if (unit.sysroot != null) {
                assertTrue(index >= 0); assertEquals(unit.sysroot.toString(), args[index + 1])
                assertFalse(args.any { it.startsWith("-I") || it.startsWith("-isystem") || it.startsWith("-iquote") })
            } else {
                assertFalse(args.any { it.startsWith("--sysroot") })
                assertEquals(assertNotNull(otherSysrootHeader).parent.toString(), args[args.indexOf("-I") + 1])
            }
        }
    }

    private fun verifyIndependentTargets() {
        val units = assertIs<CCompilationDatabaseDiscovery.Available>(parsed).database.units
        assertEquals(2, units.size)
        val main = units.single { it.file == module.resolve("main.c") }
        val other = units.single { it.file == module.resolve("other.c") }
        assertEquals(arguments, main.arguments); assertEquals("i386-unknown-linux-gnu", main.target)
        val otherTarget = if (profile == "target per-TU explicit") "x86_64-unknown-linux-gnu" else null
        assertEquals(otherTarget, other.target)
        units.forEach { unit ->
            assertEquals(standard, unit.standard); assertEquals(listOf("ENABLED=1"), unit.defines)
            assertEquals(listOf(header.parent), unit.includeDirectories); assertNull(unit.sysroot)
        }
        val projected = assertNotNull(model).modules.single().sourceSets.single().compilationUnits
        assertEquals(2, projected.size)
        units.forEach { unit ->
            val p = projected.single { root!!.resolve(it.sourceFile) == unit.file }
            assertEquals(unit.arguments, p.arguments); assertEquals(unit.target, p.languageFacets.single().targetVersion)
        }
        if (otherTarget != null) assertEquals(BuildModelStatus.PARTIAL, assertNotNull(model).status)
        assertNull(launchFailure, "Distinct translation units must retain independent qualified target contexts")
        val first = assertIs<CClangdSemanticResult.Found>(definition).definition
        val second = assertIs<CClangdSemanticResult.Found>(otherDefinition).definition
        assertEquals(header, first.file); assertEquals(header, second.file)
        assertEquals(1, first.startLine); assertEquals(3, second.startLine)
        assertEquals(4, first.startCharacter); assertEquals(4, second.startCharacter)
        assertEquals(2, admittedUnits.size)
        for (unit in units) {
            val args = admittedUnits.single { it.getValue("file").jsonPrimitive.content == unit.file.toString() }
                .getValue("arguments").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(toolchain.clangExecutable.toString(), args.first())
            assertEquals(listOf("-std=$standard"), args.filter { it.startsWith("-std=") })
            assertEquals(listOfNotNull(unit.target?.let { "--target=$it" }), args.filter { it.startsWith("--target=") })
            assertEquals(header.parent.toString(), args[args.indexOf("-I") + 1]); assertTrue("-DENABLED=1" in args)
        }
    }

    fun verifyOutcome(outcome: String) = when (outcome) {
        "available" -> verify()
        "discovered SDK" -> {
            verify()
            assertEquals(configuredSdkIdentity, toolchain.provenance.readOnlySdkIdentity)
            assertEquals(configuredSdkIdentity, toolchain.readOnlySdkInputs.identity)
            assertEquals(configuredToolchainIdentity, toolchain.provenance.toolchainIdentity())
            assertEquals(toolchain.readOnlySdkInputs.provenance.single(), sdkProvenance.single())
            assertEquals(ownedSysroot, toolchain.readOnlySdkInputs.requests.single().root)
            assertTrue(toolchain.readOnlySdkInputs.isCurrent())
            assertNotNull(configuredSdkIdentity)
            if (profile == "toolchain SDK refreshed") {
                assertNotEquals(previousSdkIdentity, configuredSdkIdentity)
                assertNotEquals(previousToolchainIdentity, configuredToolchainIdentity)
            }
            Unit
        }
        "toolchain SDK refused" -> {
            val failure = assertIs<CClangdConfigurationException>(launchFailure)
            assertEquals(if (profile == "toolchain SDK forged") "c.clangdSdkUnavailable" else "c.clangdSdkChanged", failure.diagnostic.code)
            assertNull(pid); assertNull(definition); assertTrue(admittedUnits.isEmpty()); assertTrue(sdkProvenance.isEmpty())
            assertEquals(profile == "toolchain SDK forged", toolchain.readOnlySdkInputs.isCurrent())
        }
        "read-only SDK" -> {
            verify()
            val captured = sdkProvenance.single()
            assertEquals(ownedSysroot, captured.root); assertEquals("fixture:first-party-sdk", captured.provenance)
            assertEquals("LicenseRef-RefactorKitFixture", captured.license); assertTrue(captured.identity.matches(Regex("[0-9a-f]{64}")))
            if (profile == "external SDK rename") {
                assertTrue(assertIs<CRenameResult.Refused>(sdkRename).diagnostics.any { it.code == "c.sdkReadOnly" })
                val edit = assertIs<CRenameResult.Found>(sdkSourceRename).edits.single()
                assertEquals(module.resolve("main.c"), edit.file); assertEquals("local_renamed", edit.newText)
                assertEquals(2, edit.startLine); assertEquals(11, edit.startCharacter); assertEquals(22, edit.endCharacter)
            } else { assertNull(sdkRename); assertNull(sdkSourceRename) }
        }
        "SDK rejected" -> {
            val failure = assertIs<CClangdConfigurationException>(launchFailure)
            assertEquals("c.clangdConfigurationRefused", failure.diagnostic.code)
            assertTrue(failure.diagnostic.message.contains(if (profile == "external SDK invalid scope") "SDK_SCOPE_INVALID" else "UNSUPPORTED_LAYOUT"), failure.diagnostic.message)
            assertNull(pid); assertNull(definition); assertTrue(admittedUnits.isEmpty()); assertTrue(sdkProvenance.isEmpty())
            if (profile == "external SDK invalid scope") {
                assertFalse(Files.exists(root!!.resolve("compile_commands.json")))
                assertEquals(BuildModelStatus.UNAVAILABLE, assertNotNull(model).status)
            } else assertIs<CCompilationDatabaseDiscovery.Available>(parsed)
        }
        "refused" -> verifyRefusal()
        else -> error("Unknown compilation option outcome: $outcome")
    }

    fun verifyRefusal() {
        val failure = assertIs<CClangdConfigurationException>(launchFailure,
            "Invalid include modes must not become a repaired native context: $definition")
        assertEquals("c.clangdConfigurationRefused", failure.diagnostic.code)
        assertNull(pid)
        assertNull(definition)
        val sysrootReason = when (profile) {
            "sysroot duplicate" -> "MULTIPLE_SYSROOT_FLAGS"
            "sysroot external root" -> "UNOWNED_SYSROOT"
            "sysroot linked header" -> "CAPTURE_UNAVAILABLE"
            "sysroot driver layout" -> "UNSUPPORTED_LAYOUT"
            "sysroot Linux isysroot" -> "UNSUPPORTED_SPELLING"
            else -> null
        }
        sysrootReason?.let { reason ->
            assertTrue(failure.diagnostic.message.contains(reason), failure.diagnostic.message)
            val units = assertIs<CCompilationDatabaseDiscovery.Available>(parsed).database.units
            val main = units.single { it.file == module.resolve("main.c") }
            assertEquals(arguments, main.arguments); assertEquals(ownedSysroot, main.sysroot)
            assertEquals(emptyList(), main.includeDirectories); assertEquals(standard, main.standard)
            val projected = assertNotNull(model).modules.single().sourceSets.single().compilationUnits
            assertEquals(units.map { it.arguments }, projected.map { it.arguments })
        }
        if (profile == "sysroot conflicting source") {
            assertTrue(failure.diagnostic.message.contains("c.duplicateTranslationUnit"), failure.diagnostic.message)
            assertTrue(assertIs<CCompilationDatabaseDiscovery.Refused>(parsed).diagnostics.any { it.code == "c.duplicateTranslationUnit" })
            assertEquals(BuildModelStatus.UNAVAILABLE, assertNotNull(model).status)
        }
        val targetReason = when (profile) {
            "target duplicate identical", "target duplicate conflicting" -> "MULTIPLE_TARGET_FLAGS"
            "target unknown triple" -> "UNQUALIFIED_TARGET"
            "target conflicting source" -> "c.duplicateTranslationUnit"
            else -> null
        }
        targetReason?.let { assertTrue(failure.diagnostic.message.contains(it), failure.diagnostic.message) }
        if (profile == "target conflicting source") {
            assertTrue(assertIs<CCompilationDatabaseDiscovery.Refused>(parsed).diagnostics.any { it.code == "c.duplicateTranslationUnit" })
            assertEquals(BuildModelStatus.UNAVAILABLE, assertNotNull(model).status)
        }
        if (profile in setOf("missing quote operand", "removed include separator", "long system option", "joined long system option", "target invalid long separate", "target invalid short assign")) {
            assertTrue(assertIs<CCompilationDatabaseDiscovery.Refused>(parsed).diagnostics.any {
                it.code == "c.compilationDatabaseInvalid"
            })
            assertEquals(BuildModelStatus.UNAVAILABLE, assertNotNull(model).status)
        }
    }

    fun verifyConfinement() {
        baseline.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        assertTrue(managerEmpty)
        if (profile == "sysroot linked header") assertEquals(assertNotNull(outside).resolve("original.h"), Files.readSymbolicLink(header))
        if (profile == "sysroot driver layout") assertTrue(Files.isDirectory(assertNotNull(ownedSysroot).resolve("usr/lib")))
        pid?.let { assertFalse(ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false)) }
        assertFalse(Files.exists(module.resolve("-DOUTPUT_DECOY=9")))
        assertFalse(Files.exists(module.resolve("main.o")))
        assertFalse(Files.exists(module.resolve("-std=c11")))
        assertFalse(Files.exists(module.resolve("-std=c17")))
        assertFalse(Files.exists(root!!.resolve(".refactorkit/transactions")))
    }

    override fun close() {
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        outside?.let { directory -> Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
