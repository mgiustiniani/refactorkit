package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/** REQ-C-RUNTIME-MANIFEST-001; own domain captures, no filesystem or native execution. */
class ClangRuntimeManifestSpec : StringSpec({
    "a complete cyclic shared graph preserves every occurrence and has the independent identity" {
        val fixture = runtimeFixture(); val manifest = fixture.composed()
        manifest.identity shouldBe "eaabacadc0e783dd2319ff0feca55728690cde4143276e28bf3b51da9de8b15f"
        manifest.objects.size shouldBe 5
        manifest.bindings.size shouldBe 7
        manifest.bindings.filter { it.requester == fixture.root.path() }.map { it.neededIndex to it.target } shouldBe
            listOf(0 to fixture.libraries[0].path(), 1 to fixture.libraries[1].path(), 2 to fixture.libraries[0].path())
        manifest.interpreter.requestedPath shouldBe Path.of("/lib64/ld-linux-x86-64.so.2")
        manifest.objects.single { it.path() == fixture.libraries[0].path() }.requestedPath shouldBe
            Path.of("/sdk/bin/../lib/libA.so.1")
    }
    "missing extra repeated and ambiguous edges refuse including transitive declarations" {
        val f = runtimeFixture()
        f.copy(edges = f.edges.dropLast(1)).refused(ClangRuntimeManifestRefusal.INCOMPLETE_GRAPH)
        f.copy(edges = f.edges.filterNot { it.requester == f.libraries[0].path() }).refused(ClangRuntimeManifestRefusal.INCOMPLETE_GRAPH)
        f.copy(edges = f.edges + f.edges.first()).refused(ClangRuntimeManifestRefusal.AMBIGUOUS_GRAPH)
        f.copy(edges = f.edges + f.edges.first().copy(neededIndex = 99)).refused(ClangRuntimeManifestRefusal.INCOMPLETE_GRAPH)
        f.copy(edges = f.edges.drop(1) + f.edges.first().copy(neededIndex = -1)).refused(ClangRuntimeManifestRefusal.INCOMPLETE_GRAPH)
        f.copy(edges = f.edges.mapIndexed { i, e -> if (i == 0) e.copy(target = Path.of("/missing/libA.so.1")) else e })
            .refused(ClangRuntimeManifestRefusal.INCOMPLETE_GRAPH)
        f.copy(libraries = f.libraries + f.libraries.first()).refused(ClangRuntimeManifestRefusal.AMBIGUOUS_GRAPH)
        val collision = runtimeObject("/other/libA.so.1", "/other/libA.so.1", ClangArtifactRole.SHARED_LIBRARY, 'f', 1000,
            emptyList(), soname = "libA.so.1")
        f.copy(libraries = f.libraries + collision).refused(ClangRuntimeManifestRefusal.AMBIGUOUS_GRAPH)
    }
    "roles interpreter names evidence and disconnected objects cannot claim graph authority" {
        val f = runtimeFixture(); val a = f.libraries.first()
        f.copy(root = f.root.withRole(ClangArtifactRole.SHARED_LIBRARY)).refused(ClangRuntimeManifestRefusal.ROLE_MISMATCH)
        f.copy(loader = f.loader.withRole(ClangArtifactRole.SHARED_LIBRARY)).refused(ClangRuntimeManifestRefusal.ROLE_MISMATCH)
        f.copy(loader = f.loader.copy(requestedPath = Path.of("/other/ld-linux-x86-64.so.2")))
            .refused(ClangRuntimeManifestRefusal.ROLE_MISMATCH)
        f.copy(root = f.root.withFacts { copy(interpreter = null, hasDynamicSegment = false) })
            .refused(ClangRuntimeManifestRefusal.ROLE_MISMATCH)
        val executableLibrary = a.withFacts { copy(interpreter = "/usr/lib/ld-linux-x86-64.so.2") }
        val withExecutableLibrary = f.copy(libraries = listOf(executableLibrary) + f.libraries.drop(1)).composed()
        withExecutableLibrary.objects.single { it.path() == a.path() }.artifact.facts.interpreter shouldBe
            "/usr/lib/ld-linux-x86-64.so.2"
        withExecutableLibrary.identity shouldNotBe f.composed().identity
        f.copy(loader = f.loader.withFacts { copy(interpreter = "/another/loader") }).refused(ClangRuntimeManifestRefusal.ROLE_MISMATCH)
        for (changed in listOf(a.withRole(ClangArtifactRole.CLANG), a.withFacts { copy(objectType = CElfObjectType.EXECUTABLE) },
            a.withFacts { copy(dynamicFlags1 = 0x8000000) })) {
            f.copy(libraries = listOf(changed) + f.libraries.drop(1)).refused(ClangRuntimeManifestRefusal.ROLE_MISMATCH)
        }
        f.copy(edges = f.edges.mapIndexed { i, e -> if (i == 0) e.copy(target = f.libraries[1].path()) else e })
            .refused(ClangRuntimeManifestRefusal.NAME_MISMATCH)
        f.copy(root = f.root.copy(artifact = f.root.artifact.copy(evidence = f.root.artifact.evidence.copy(sha256 = "f".repeat(64)))))
            .refused(ClangRuntimeManifestRefusal.INVALID_INPUT)
        f.copy(root = f.root.copy(artifact = f.root.artifact.copy(evidence = f.root.artifact.evidence.copy(role = "shared-library"))))
            .refused(ClangRuntimeManifestRefusal.INVALID_INPUT)
        val detached = runtimeObject("/usr/lib/detached.so", "/usr/lib/detached.so", ClangArtifactRole.SHARED_LIBRARY,
            'f', 1000, emptyList(), soname = "detached.so")
        f.copy(libraries = f.libraries + detached).refused(ClangRuntimeManifestRefusal.UNREACHABLE_OBJECT)
    }
    "bounds admit their boundary and refuse oversized graphs bytes text aliases and invalid representations" {
        val f = runtimeFixture()
        f.copy(libraries = List(255) { f.libraries.first() }).refused(ClangRuntimeManifestRefusal.INPUT_LIMIT)
        f.copy(edges = List(4097) { f.edges.first() }).refused(ClangRuntimeManifestRefusal.INPUT_LIMIT)
        val leaves = (0 until 254).map { runtimeObject("/sdk/lib/lib$it.so", "/sdk/lib/lib$it.so",
            ClangArtifactRole.SHARED_LIBRARY, 'f', 64, emptyList(), soname = "lib$it.so") }
        val many = RuntimeFixture(f.root.withFacts { copy(needed = leaves.map { it.artifact.facts.soname!! }) }, f.loader,
            leaves, leaves.mapIndexed { index, obj -> ClangRuntimeDependencyBinding(f.root.path(), index, obj.path()) })
        many.composed().objects.size shouldBe 256
        val repeated = RuntimeFixture(f.root.withFacts { copy(needed = List(512) { "leaf.so" }) }, f.loader,
            listOf(runtimeObject("/sdk/lib/leaf.so", "/sdk/lib/leaf.so", ClangArtifactRole.SHARED_LIBRARY, 'f', 64,
                emptyList(), soname = "leaf.so")), List(512) { ClangRuntimeDependencyBinding(f.root.path(), it, Path.of("/sdk/lib/leaf.so")) })
        repeated.composed().bindings.size shouldBe 512
        repeated.copy(root = repeated.root.withFacts { copy(needed = needed + "leaf.so") }).refused(ClangRuntimeManifestRefusal.INPUT_LIMIT)
        val edgeLeaves = (0 until 8).map { runtimeObject("/sdk/lib/edge$it.so", "/sdk/lib/edge$it.so", ClangArtifactRole.SHARED_LIBRARY,
            'f', 64, List(511) { "ld-linux-x86-64.so.2" }, soname = "edge$it.so") }
        val fullEdges = RuntimeFixture(f.root.withFacts { copy(needed = edgeLeaves.map { it.artifact.facts.soname!! }) }, f.loader,
            edgeLeaves, edgeLeaves.mapIndexed { i, obj -> ClangRuntimeDependencyBinding(f.root.path(), i, obj.path()) } +
                edgeLeaves.flatMap { obj -> (0 until 511).map { ClangRuntimeDependencyBinding(obj.path(), it, f.loader.path()) } })
        fullEdges.composed().bindings.size shouldBe 4096
        fullEdges.copy(libraries = listOf(edgeLeaves[0].withFacts { copy(needed = needed + "ld-linux-x86-64.so.2") }) + edgeLeaves.drop(1))
            .refused(ClangRuntimeManifestRefusal.INPUT_LIMIT)
        val maximumObject = f.root.withSize(512L * 1024 * 1024)
        f.copy(root = maximumObject).composed()
        f.copy(root = maximumObject.withSize(512L * 1024 * 1024 + 1)).refused(ClangRuntimeManifestRefusal.INPUT_LIMIT)
        f.copy(root = maximumObject.withSize(512L * 1024 * 1024 - 64), loader = f.loader.withSize(512L * 1024 * 1024),
            libraries = listOf(f.libraries[0].withSize(512L * 1024 * 1024), f.libraries[1].withSize(512L * 1024 * 1024),
                f.libraries[2].withSize(64))).composed()
        f.copy(root = maximumObject, loader = f.loader.withSize(512L * 1024 * 1024),
            libraries = f.libraries.map { it.withSize(512L * 1024 * 1024) }).refused(ClangRuntimeManifestRefusal.INPUT_LIMIT)
        f.copy(root = f.root.withFacts { copy(runpath = "é".repeat(2048)) }).composed()
        f.copy(root = f.root.withFacts { copy(runpath = "é".repeat(2049)) }).refused(ClangRuntimeManifestRefusal.INPUT_LIMIT)
        f.copy(root = f.root.withFacts { copy(runpath = "\uD800") }).refused(ClangRuntimeManifestRefusal.INVALID_INPUT)
        f.copy(root = f.root.withFacts { copy(interpreter = "/bad\u0000path") }).refused(ClangRuntimeManifestRefusal.INVALID_INPUT)
        f.copy(root = f.root.withFacts { copy(needed = listOf("${'$'}ORIGIN/lib.so")) }).refused(ClangRuntimeManifestRefusal.INVALID_INPUT)
        f.copy(root = f.root.copy(requestedPath = Path.of("relative/clangd"))).refused(ClangRuntimeManifestRefusal.INVALID_INPUT)
        val aliases = (0 until 32).map { ClangNativePathAlias(Path.of("/aliases/a$it"), Path.of("target$it")) }
        f.copy(root = f.root.copy(aliases = aliases)).composed()
        f.copy(root = f.root.copy(aliases = aliases + aliases.first())).refused(ClangRuntimeManifestRefusal.INPUT_LIMIT)
        f.copy(root = f.root.copy(aliases = listOf(aliases.first(), aliases.first()))).refused(ClangRuntimeManifestRefusal.INVALID_INPUT)
        f.copy(root = f.root.withSize(0)).refused(ClangRuntimeManifestRefusal.INVALID_INPUT)
    }
    "composed captures are defensive immutable snapshots after input mutation" {
        val original = runtimeFixture()
        val aliases = original.libraries[2].aliases.toMutableList()
        val needed = original.libraries[0].artifact.facts.needed.toMutableList()
        val libraries = original.libraries.toMutableList()
        libraries[0] = libraries[0].withFacts { copy(needed = needed) }
        libraries[2] = libraries[2].copy(aliases = aliases)
        val edges = original.edges.toMutableList()
        val manifest = original.copy(libraries = libraries, edges = edges).composed()
        aliases.clear(); needed.clear(); libraries.clear(); edges.clear()
        manifest.identity shouldBe original.composed().identity
        manifest.objects.single { it.path() == original.libraries[0].path() }.artifact.facts.needed shouldBe listOf("libShared.so.1")
        manifest.objects.single { it.path() == original.libraries[2].path() }.aliases shouldBe original.libraries[2].aliases
        shouldThrow<UnsupportedOperationException> { (manifest.objects as MutableList).clear() }
        shouldThrow<UnsupportedOperationException> { (manifest.bindings as MutableList).clear() }
        shouldThrow<UnsupportedOperationException> { (manifest.executable.artifact.facts.needed as MutableList).clear() }
        shouldThrow<UnsupportedOperationException> { (manifest.interpreter.aliases as MutableList).clear() }
    }
    "permutations preserve identity while each captured input and selected edge binds it" {
        val f = runtimeFixture(); val baseline = f.composed().identity
        f.copy(libraries = f.libraries.reversed(), edges = f.edges.reversed()).composed().identity shouldBe baseline
        val root = f.root
        val rootChanges = listOf(root.withRole(ClangArtifactRole.CLANG), root.withSize(1001),
            root.copy(artifact = root.artifact.copy(evidence = root.artifact.evidence.copy(sha256 = "f".repeat(64)),
                facts = root.artifact.facts.copy(sha256 = "f".repeat(64)))),
            root.withFacts { copy(objectType = CElfObjectType.EXECUTABLE) }, root.withFacts { copy(dynamicFlags = 1) },
            root.withFacts { copy(dynamicFlags1 = dynamicFlags1 + 1) }, root.withFacts { copy(rpath = "") },
            root.withFacts { copy(runpath = "${'$'}ORIGIN/elsewhere") })
        for (changed in rootChanges) f.copy(root = changed).composed().identity shouldNotBe baseline
        f.copy(loader = f.loader.copy(aliases = listOf(ClangNativePathAlias(Path.of("/lib64"), Path.of("/usr/lib")))))
            .composed().identity shouldNotBe baseline
        f.copy(libraries = listOf(f.libraries[0].copy(requestedPath = f.libraries[0].requestedPath.normalize())) + f.libraries.drop(1))
            .composed().identity shouldNotBe baseline
        val reorderedRoot = root.withFacts { copy(needed = listOf("libB.so.1", "libA.so.1", "libA.so.1")) }
        val reorderedEdges = f.edges.map { if (it.requester == root.path() && it.neededIndex < 2)
            it.copy(neededIndex = 1 - it.neededIndex) else it }
        f.copy(root = reorderedRoot, edges = reorderedEdges).composed().identity shouldNotBe baseline
        val oldPath = f.libraries[0].path(); val newPath = Path.of("/other/libA.so.1")
        val moved = f.libraries[0].copy(artifact = f.libraries[0].artifact.copy(
            evidence = f.libraries[0].artifact.evidence.copy(path = newPath)))
        f.copy(libraries = listOf(moved) + f.libraries.drop(1), edges = f.edges.map {
            it.copy(requester = if (it.requester == oldPath) newPath else it.requester,
                target = if (it.target == oldPath) newPath else it.target) }).composed().identity shouldNotBe baseline
    }
})

private data class RuntimeFixture(val root: ClangNativePathCaptureResult.Captured,
    val loader: ClangNativePathCaptureResult.Captured, val libraries: List<ClangNativePathCaptureResult.Captured>,
    val edges: List<ClangRuntimeDependencyBinding>) {
    fun result() = ClangRuntimeManifest.compose(root, loader, libraries, edges)
    fun composed() = result().shouldBeInstanceOf<ClangRuntimeManifestComposition.Composed>().manifest
    fun refused(reason: ClangRuntimeManifestRefusal) { result() shouldBe ClangRuntimeManifestComposition.Refused(reason) }
}

private fun runtimeFixture(): RuntimeFixture {
    val root = runtimeObject("/sdk/bin/clangd", "/sdk/bin/clangd", ClangArtifactRole.CLANGD, 'a', 1000,
        listOf("libA.so.1", "libB.so.1", "libA.so.1"), interpreter = "/lib64/ld-linux-x86-64.so.2",
        runpath = "${'$'}ORIGIN/../lib", flags1 = 0x8000000)
    val loader = runtimeObject("/lib64/ld-linux-x86-64.so.2", "/usr/lib/ld-linux-x86-64.so.2", ClangArtifactRole.INTERPRETER,
        'b', 1500, emptyList(), soname = "ld-linux-x86-64.so.2", aliases = listOf(ClangNativePathAlias(Path.of("/lib64"), Path.of("usr/lib"))))
    val a = runtimeObject("/sdk/bin/../lib/libA.so.1", "/sdk/lib/libA.so.1", ClangArtifactRole.SHARED_LIBRARY,
        'c', 2000, listOf("libShared.so.1"), soname = "libA.so.1")
    val b = runtimeObject("/sdk/lib/libB.so.1", "/sdk/lib/libB.so.1", ClangArtifactRole.SHARED_LIBRARY,
        'd', 2100, listOf("libShared.so.1"), soname = "libB.so.1")
    val c = runtimeObject("/usr/lib/libShared.so.1", "/usr/lib/libShared.so.1.2", ClangArtifactRole.SHARED_LIBRARY,
        'e', 2200, listOf("libA.so.1", "ld-linux-x86-64.so.2"), soname = "libShared.so.1",
        aliases = listOf(ClangNativePathAlias(Path.of("/usr/lib/libShared.so.1"), Path.of("libShared.so.1.2"))))
    return RuntimeFixture(root, loader, listOf(a, b, c), listOf(
        ClangRuntimeDependencyBinding(root.path(), 0, a.path()), ClangRuntimeDependencyBinding(root.path(), 1, b.path()),
        ClangRuntimeDependencyBinding(root.path(), 2, a.path()), ClangRuntimeDependencyBinding(a.path(), 0, c.path()),
        ClangRuntimeDependencyBinding(b.path(), 0, c.path()), ClangRuntimeDependencyBinding(c.path(), 0, a.path()),
        ClangRuntimeDependencyBinding(c.path(), 1, loader.path())))
}

private fun runtimeObject(requested: String, resolved: String, role: ClangArtifactRole, hash: Char, size: Long,
    needed: List<String>, soname: String? = null, aliases: List<ClangNativePathAlias> = emptyList(),
    interpreter: String? = null, runpath: String? = null, flags1: Long = 0) = ClangNativePathCaptureResult.Captured(
    Path.of(requested), aliases, ClangArtifactCaptureResult.Captured(role,
        ClangToolchainFileEvidence(role.evidenceRole, Path.of(resolved), hash.toString().repeat(64), size),
        CElfDynamicInputFacts(hash.toString().repeat(64), interpreter, needed, soname, null, runpath, 0, flags1, true, CElfObjectType.DYNAMIC)))

private fun ClangNativePathCaptureResult.Captured.path() = artifact.evidence.path
private fun ClangNativePathCaptureResult.Captured.withFacts(change: CElfDynamicInputFacts.() -> CElfDynamicInputFacts) =
    copy(artifact = artifact.copy(facts = artifact.facts.change()))
private fun ClangNativePathCaptureResult.Captured.withRole(role: ClangArtifactRole) =
    copy(artifact = artifact.copy(role = role, evidence = artifact.evidence.copy(role = role.evidenceRole)))
private fun ClangNativePathCaptureResult.Captured.withSize(size: Long) =
    copy(artifact = artifact.copy(evidence = artifact.evidence.copy(size = size)))
