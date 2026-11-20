package org.refactorkit.cli.previewcommand

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests the actual repository ignore policy against native Git in a disposable repository. */
internal class ReleaseSourceInventoryFixture private constructor(
    private val repository: Path,
    private val scratch: Path,
) : AutoCloseable {
    private val workspace = scratch.resolve("workspace")
    private val policy = repository.resolve(".gitignore")
    private val beforePolicy = sha(policy)
    private val expectedSources = setOf(".gitignore", "src/main/java/Demo.java", "docs/arc42/README.adoc",
        "qualification/typescript-toolchain/package.json", "qualification/typescript-toolchain/package-lock.json",
        "qualification/protocol-schema-toolchain/package.json", "qualification/protocol-schema-toolchain/package-lock.json",
        ".refactorkit/settings.json")
    private val localArtifacts = setOf(".refactorkit/candidates/current/refactorkit-runtime.zip",
        ".refactorkit/toolchains/clang/bin/clang", ".gradle-tmp/gradle-home/registry.bin",
        ".refactorkit/runs/proof/state.json", ".refactorkit/transactions/current.json",
        "modules/example/build/classes/Demo.class", "qualification/protocol-schema-toolchain/node_modules/ajv/package.json")
    private val before: Map<Path, String>
    private var observed: Set<String> = emptySet()

    init {
        Files.createDirectory(workspace)
        for (relative in expectedSources + localArtifacts) {
            val path = workspace.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, "first-party inert inventory fixture: $relative\n")
        }
        Files.copy(policy, workspace.resolve(".gitignore"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        before = (expectedSources + localArtifacts).associate { workspace.resolve(it).let { path -> path to sha(path) } }
    }

    fun inventory() {
        execute("init", listOf("init", "--quiet", "--template="))
        val bytes = execute("inventory", listOf("ls-files", "--cached", "--others", "--exclude-standard", "-z"))
        observed = String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.toSet()
    }

    fun verifySelection() {
        assertEquals(expectedSources, observed, "Local runtimes/toolchains/caches must not become release source inputs")
    }

    fun verifyPreservation() {
        assertEquals(beforePolicy, sha(policy), "Inventory must not change the actual ignore policy")
        before.forEach { (path, hash) ->
            assertFalse(Files.isSymbolicLink(path))
            assertEquals(hash, sha(path), "Source filtering must preserve local artifact bytes: $path")
        }
        assertTrue(localArtifacts.all { Files.isRegularFile(workspace.resolve(it)) })
    }

    private fun execute(label: String, arguments: List<String>): ByteArray {
        val stdout = scratch.resolve("$label.stdout")
        val stderr = scratch.resolve("$label.stderr")
        val command = listOf("git", "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null") + arguments
        val process = ProcessBuilder(command).directory(workspace.toFile()).apply {
            redirectOutput(stdout.toFile()); redirectError(stderr.toFile())
            val environment = environment()
            environment.keys.filter { it.startsWith("GIT_") }.toList().forEach(environment::remove)
            environment["GIT_CONFIG_GLOBAL"] = "/dev/null"
            environment["GIT_CONFIG_SYSTEM"] = "/dev/null"
        }.start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS)
            error("Native Git inventory timed out")
        }
        assertTrue(Files.size(stdout) < 65536 && Files.size(stderr) < 65536)
        assertEquals(0, process.exitValue(), Files.readString(stderr))
        return Files.readAllBytes(stdout)
    }

    override fun close() {
        Files.walk(scratch).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        private fun sha(path: Path): String = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        fun capture(): ReleaseSourceInventoryFixture {
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            return ReleaseSourceInventoryFixture(root, Files.createTempDirectory("refactorkit-release-inventory-"))
        }
    }
}
