package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class CNativeSysrootContextSpec : StringSpec({
    val workspace = Path.of("/unread/workspace")
    val directory = workspace.resolve("module")
    val sdk = directory.resolve("sdk")
    fun unit(flags: List<String> = emptyList(), root: Path? = null, fileName: String = "main.c") = CCompilationUnit(
        directory.resolve(fileName), directory,
        listOf("/unread/clang", "-std=c17") + flags + listOf("-c", fileName),
        null, "c17", emptyList(), emptyList(), null, root,
    )
    fun refused(reason: CNativeSysrootRefusal) = CNativeSysrootAdmission.Refused(reason)
    fun invalid(reason: CNativeSysrootRefusal) = CNativeSysrootLayout.Refused(reason)
    fun dir(path: String) = CNativeSysrootEntry(Path.of(path), CNativeSysrootEntryKind.DIRECTORY)
    fun header(path: String = "usr/include/api.h", bytes: Int = 20) =
        CNativeSysrootEntry(Path.of(path), CNativeSysrootEntryKind.HEADER, bytes, "a".repeat(64))
    val base = listOf(dir(""), dir("usr"), dir("usr/include"))

    "implicit contexts retain their existing selection without filesystem access" {
        CNativeSysrootPolicy.select(workspace, emptyList()) shouldBe CNativeSysrootAdmission.ImplicitDefault
        CNativeSysrootPolicy.select(workspace, listOf(unit(), unit())) shouldBe CNativeSysrootAdmission.ImplicitDefault
    }
    "relative separate and assigned absolute sysroots resolve to one coherent root" {
        val units = listOf(unit(listOf("--sysroot", "sdk"), sdk), unit(listOf("--sysroot=$sdk"), sdk))
        CNativeSysrootPolicy.select(workspace, units) shouldBe CNativeSysrootAdmission.Owned(sdk)
        CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("--sysroot=sdk"), sdk))) shouldBe CNativeSysrootAdmission.Owned(sdk)
    }
    "include and output operands cannot manufacture a sysroot or unsupported spelling" {
        CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("-I", "--sysroot=ghost", "-o", "-isysroot")))) shouldBe
            CNativeSysrootAdmission.ImplicitDefault
    }
    "Linux isysroot is not silently repaired into sysroot" {
        CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("-isysroot", "sdk"), sdk))) shouldBe
            refused(CNativeSysrootRefusal.UNSUPPORTED_SPELLING)
    }
    "duplicate selectors and contradictory root records for one source are refused" {
        CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("--sysroot=sdk", "--sysroot=sdk"), sdk))) shouldBe
            refused(CNativeSysrootRefusal.MULTIPLE_SYSROOT_FLAGS)
        val selected = unit(listOf("--sysroot=sdk"), sdk)
        CNativeSysrootPolicy.select(workspace, listOf(selected, unit())) shouldBe refused(CNativeSysrootRefusal.INCONSISTENT_CONTEXTS)
        CNativeSysrootPolicy.select(workspace, listOf(selected, unit(listOf("--sysroot=other"), directory.resolve("other")))) shouldBe
            refused(CNativeSysrootRefusal.INCONSISTENT_CONTEXTS)
    }
    "distinct translation units retain independent owned roots" {
        val other = directory.resolve("other")
        CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("--sysroot=sdk"), sdk),
            unit(listOf("--sysroot=other"), other, "other.c"))) shouldBe
            CNativeSysrootAdmission.PerTranslationUnit(listOf(other, sdk))
    }
    "implicit sysroot metadata remains absent beside an explicit peer" {
        CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("--sysroot=sdk"), sdk), unit(fileName = "other.c"))) shouldBe
            CNativeSysrootAdmission.PerTranslationUnit(listOf(sdk))
    }
    "normalized aliases of one source cannot create independent roots" {
        CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("--sysroot=sdk"), sdk),
            unit(listOf("--sysroot=other"), directory.resolve("other"), "nested/../main.c"))) shouldBe
            refused(CNativeSysrootRefusal.INCONSISTENT_CONTEXTS)
    }
    "root multiplicity is bounded before any filesystem capture" {
        val maximum = CNativeSysrootPolicy.MAX_ROOTS
        fun contexts(count: Int) = (0 until count).map { index ->
            unit(listOf("--sysroot=sdk$index"), directory.resolve("sdk$index"), "source$index.c")
        }
        CNativeSysrootPolicy.select(workspace, contexts(maximum)) shouldBe CNativeSysrootAdmission.PerTranslationUnit(
            (0 until maximum).map { directory.resolve("sdk$it") }.sortedBy { it.toString() })
        CNativeSysrootPolicy.select(workspace, contexts(maximum + 1)) shouldBe refused(CNativeSysrootRefusal.INPUT_LIMIT)
    }
    "unowned roots and roots that consume the whole workspace are refused" {
        for (root in listOf(workspace, Path.of("/outside/sdk"))) {
            CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("--sysroot=$root"), root))) shouldBe
                refused(CNativeSysrootRefusal.UNOWNED_SYSROOT)
        }
    }
    "missing operands empty paths and mismatched metadata remain unavailable" {
        CNativeSysrootPolicy.select(workspace, listOf(unit().copy(arguments = listOf("clang", "--sysroot")))) shouldBe
            refused(CNativeSysrootRefusal.INVALID_OPTIONS)
        CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("--sysroot=")))) shouldBe refused(CNativeSysrootRefusal.INVALID_OPTIONS)
        CNativeSysrootPolicy.select(workspace, listOf(unit(listOf("--sysroot=sdk"), directory.resolve("other")))) shouldBe
            refused(CNativeSysrootRefusal.METADATA_MISMATCH)
        CNativeSysrootPolicy.select(workspace, listOf(unit(emptyList(), sdk))) shouldBe refused(CNativeSysrootRefusal.METADATA_MISMATCH)
    }
    "closed header layout includes required empty directories and exact header identity" {
        CNativeSysrootPolicy.layout(base + header()) shouldBe CNativeSysrootLayout.Admitted
        CNativeSysrootPolicy.layout(base + dir("usr/include/nested") + header("usr/include/nested/api.h")) shouldBe CNativeSysrootLayout.Admitted
    }
    "unknown regular inputs and driver-search directories are outside the header-only profile" {
        for (extra in listOf(dir("usr/lib"), CNativeSysrootEntry(Path.of("usr/include/module.modulemap"), CNativeSysrootEntryKind.OTHER))) {
            CNativeSysrootPolicy.layout(base + header() + extra) shouldBe invalid(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
        }
    }
    "missing ancestry duplicates malformed hashes and escaping names cannot form a qualified footprint" {
        CNativeSysrootPolicy.layout(base) shouldBe invalid(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
        CNativeSysrootPolicy.layout(base + header() + header()) shouldBe invalid(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
        CNativeSysrootPolicy.layout(base + header().copy(sha256 = "invalid")) shouldBe invalid(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
        for (path in listOf("../api.h", "/outside/api.h", "usr/include/../api.h", "usr/include/missing/api.h")) {
            CNativeSysrootPolicy.layout(base + header(path)) shouldBe invalid(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
        }
    }
    "file entry and byte bounds are explicit rather than partial inventories" {
        val dirs = (0 until 60).map { dir("usr/include/empty$it") }
        CNativeSysrootPolicy.layout(base + dirs + header()) shouldBe CNativeSysrootLayout.Admitted
        CNativeSysrootPolicy.layout(base + dirs + dir("usr/include/overflow") + header()) shouldBe invalid(CNativeSysrootRefusal.INPUT_LIMIT)
        CNativeSysrootPolicy.layout(base + (0..16).map { header("usr/include/h$it.h") }) shouldBe invalid(CNativeSysrootRefusal.INPUT_LIMIT)
        CNativeSysrootPolicy.layout(base + header(bytes = CNativeSysrootPolicy.MAX_FILE_BYTES + 1)) shouldBe invalid(CNativeSysrootRefusal.INPUT_LIMIT)
        CNativeSysrootPolicy.layout(base + (0..8).map { header("usr/include/h$it.h", CNativeSysrootPolicy.MAX_FILE_BYTES) }) shouldBe
            invalid(CNativeSysrootRefusal.INPUT_LIMIT)
    }
    "external SDK authorization is explicit and its absence preserves refusal" {
        val request = CReadOnlySdkRequest(Path.of("/unread/external-sdk"), "fixture:first-party", "LicenseRef-Fixture")
        val context = unit(listOf("--sysroot=${request.root}"), request.root)
        CReadOnlySdkPolicy.scope(workspace, listOf(request)) shouldBe CReadOnlySdkScope.ADMITTED
        CNativeSysrootPolicy.select(workspace, listOf(context)) shouldBe refused(CNativeSysrootRefusal.UNOWNED_SYSROOT)
        CNativeSysrootPolicy.select(workspace, listOf(context), listOf(request)) shouldBe CNativeSysrootAdmission.Owned(request.root)
    }
    "invalid overlapping and excessive SDK scopes never authorize reads" {
        val valid = CReadOnlySdkRequest(Path.of("/unread/external-sdk"), "fixture", "LicenseRef-Fixture")
        for (request in listOf(valid.copy(root = Path.of("relative")), valid.copy(root = Path.of("/")),
            valid.copy(root = workspace), valid.copy(root = workspace.parent), valid.copy(root = Path.of("/unread/x/../external-sdk")),
            valid.copy(root = Path.of("/unread/with\ncontrol")), valid.copy(provenance = ""), valid.copy(license = "\nunknown"), valid.copy(license = "x".repeat(1025)))) {
            CReadOnlySdkPolicy.scope(workspace, listOf(request)) shouldBe CReadOnlySdkScope.INVALID
        }
        CReadOnlySdkPolicy.scope(workspace, listOf(valid, valid)) shouldBe CReadOnlySdkScope.INVALID
        CReadOnlySdkPolicy.scope(workspace, listOf(valid, valid.copy(root = valid.root.resolve("nested")))) shouldBe CReadOnlySdkScope.INVALID
        CReadOnlySdkPolicy.scope(workspace, List(CNativeSysrootPolicy.MAX_ROOTS + 1) { valid }) shouldBe CReadOnlySdkScope.INPUT_LIMIT
    }
    "SDK identity binds metadata and exact sorted tree facts" {
        val request = CReadOnlySdkRequest(Path.of("/unread/external-sdk"), "fixture", "LicenseRef-Fixture")
        val entries = base + header()
        val id = CReadOnlySdkPolicy.identity(request, entries)
        id.length shouldBe 64
        CReadOnlySdkPolicy.identity(request, entries.reversed()) shouldBe id
        listOf(CReadOnlySdkPolicy.identity(request.copy(license = "unknown"), entries),
            CReadOnlySdkPolicy.identity(request.copy(provenance = "different"), entries),
            CReadOnlySdkPolicy.identity(request.copy(root = Path.of("/unread/other-sdk")), entries),
            CReadOnlySdkPolicy.identity(request, base + header().copy(sha256 = "b".repeat(64)))).forEach { (it == id) shouldBe false }
    }
    "SDK declarations and edits stay read only even when selected from a workspace use" {
        val request = CReadOnlySdkRequest(Path.of("/unread/external-sdk"), "fixture", "LicenseRef-Fixture")
        val source = workspace.resolve("main.c"); val external = request.root.resolve("usr/include/api.h")
        CReadOnlySdkPolicy.rename(workspace, source, external, listOf(request)) shouldBe CReadOnlySdkRename.READ_ONLY
        CReadOnlySdkPolicy.rename(workspace, external, null, listOf(request)) shouldBe CReadOnlySdkRename.READ_ONLY
        CReadOnlySdkPolicy.rename(workspace, source, null, listOf(request)) shouldBe CReadOnlySdkRename.UNAVAILABLE
        CReadOnlySdkPolicy.rename(workspace, source, source, listOf(request)) shouldBe CReadOnlySdkRename.SOURCE_OWNED
        CReadOnlySdkPolicy.edits(workspace, listOf(source, external), listOf(request)) shouldBe CReadOnlySdkRename.READ_ONLY
        CReadOnlySdkPolicy.edits(workspace, listOf(Path.of("/unowned/file.c")), listOf(request)) shouldBe CReadOnlySdkRename.UNAVAILABLE
        CReadOnlySdkPolicy.edits(workspace, listOf(source), listOf(request)) shouldBe CReadOnlySdkRename.SOURCE_OWNED
    }
    "SDK literal dependencies are closed before native acquisition gains authority" {
        val api = Path.of("usr/include/api.h"); val helper = Path.of("usr/include/helper.h")
        CReadOnlySdkPolicy.closedHeaders(mapOf(api to "#pragma once\nint compute(void);\n")) shouldBe true
        CReadOnlySdkPolicy.closedHeaders(mapOf(api to "#pragma once\n#include \"helper.h\"\n", helper to "#pragma once\nint compute(void);\n")) shouldBe true
        for (text in listOf("#include \"missing.h\"\n", "#include \"../../../outside.h\"\n", "#include \"/unowned/header.h\"\n", "#include HEADER\n", "#include <stdio.h>\n")) {
            CReadOnlySdkPolicy.closedHeaders(mapOf(api to text)) shouldBe false
        }
    }
    "external SDK and owned per TU roots retain independent scope" {
        val request = CReadOnlySdkRequest(Path.of("/unread/external-sdk"), "fixture", "LicenseRef-Fixture")
        val own = unit(listOf("--sysroot=sdk"), sdk)
        val external = unit(listOf("--sysroot=${request.root}"), request.root, "other.c")
        CNativeSysrootPolicy.select(workspace, listOf(own, external), listOf(request)) shouldBe
            CNativeSysrootAdmission.PerTranslationUnit(listOf(request.root, sdk))
    }
    fun provenance(sdkIdentity: String? = null) = ClangToolchainProvenance(
        clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
        targetTriple = "x86_64-unknown-linux-gnu", resourceDir = "/unread/resources", evidence = emptyList(),
        nativeRuntimeIdentity = "runtime", readOnlySdkIdentity = sdkIdentity,
    )
    "no SDK identity retains the exact legacy toolchain fingerprint" {
        val value = provenance()
        value.toolchainIdentity() shouldBe ClangToolchainIdentity.of(value)
        CReadOnlySdkPolicy.sdkBundleIdentity(emptyList()) shouldBe null
    }
    "SDK toolchain binding changes with SDK inputs and preserves runtime identity meaning" {
        val value = provenance("a".repeat(64)); val identity = value.toolchainIdentity()
        (identity == provenance().toolchainIdentity()) shouldBe false
        (identity == provenance("b".repeat(64)).toolchainIdentity()) shouldBe false
        (identity == value.copy(targetTriple = "i386-unknown-linux-gnu").toolchainIdentity()) shouldBe false
        value.nativeRuntimeIdentity shouldBe "runtime"
    }
    "SDK input order does not change the canonical bundle identity" {
        val a = CReadOnlySdkProvenance(Path.of("/unread/sdk-a"), "first", "LicenseRef-A", "a".repeat(64))
        val z = CReadOnlySdkProvenance(Path.of("/unread/sdk-z"), "second", "LicenseRef-Z", "b".repeat(64))
        CReadOnlySdkPolicy.sdkBundleIdentity(listOf(a, z)) shouldBe CReadOnlySdkPolicy.sdkBundleIdentity(listOf(z, a))
    }
    "SDK bundle identity binds every root provenance and declared license" {
        val a = CReadOnlySdkProvenance(Path.of("/unread/sdk-a"), "first", "LicenseRef-A", "a".repeat(64))
        val id = CReadOnlySdkPolicy.sdkBundleIdentity(listOf(a))
        listOf(a.copy(root = Path.of("/unread/other")), a.copy(provenance = "different"), a.copy(license = "unknown")).forEach {
            (CReadOnlySdkPolicy.sdkBundleIdentity(listOf(it)) == id) shouldBe false
        }
    }
    "SDK bundle identity binds tree identity and membership" {
        val a = CReadOnlySdkProvenance(Path.of("/unread/sdk-a"), "first", "LicenseRef-A", "a".repeat(64))
        val z = a.copy(root = Path.of("/unread/sdk-z"))
        val id = CReadOnlySdkPolicy.sdkBundleIdentity(listOf(a))
        (CReadOnlySdkPolicy.sdkBundleIdentity(listOf(a.copy(identity = "b".repeat(64)))) == id) shouldBe false
        (CReadOnlySdkPolicy.sdkBundleIdentity(listOf(a, z)) == id) shouldBe false
    }
    "SDK metadata rejects invalid UTF16 rather than hashing replacement bytes" {
        val valid = CReadOnlySdkRequest(Path.of("/unread/sdk-a"), "origin", "LicenseRef-A")
        for (bad in listOf("\uD800", "\uDC00", "bad\uD800suffix")) {
            CReadOnlySdkPolicy.scope(workspace, listOf(valid.copy(provenance = bad))) shouldBe CReadOnlySdkScope.INVALID
            CReadOnlySdkPolicy.scope(workspace, listOf(valid.copy(license = bad))) shouldBe CReadOnlySdkScope.INVALID
        }
        CReadOnlySdkPolicy.scope(workspace, listOf(valid.copy(provenance = "origin \uD83D\uDE00"))) shouldBe CReadOnlySdkScope.ADMITTED
    }
    "selection input count is bounded independently from individual compiler records" {
        CNativeSysrootPolicy.select(workspace, List(CCompilationDatabasePolicy.DEFAULT_MAX_UNITS + 1) { unit() }) shouldBe
            refused(CNativeSysrootRefusal.INPUT_LIMIT)
    }
})
