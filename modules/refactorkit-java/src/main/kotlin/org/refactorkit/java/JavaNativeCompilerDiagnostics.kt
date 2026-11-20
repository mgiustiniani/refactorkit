package org.refactorkit.java

import org.refactorkit.core.*
import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Selected-compiler acquisition for source/target25 and26. Scopes come exclusively from the build-model resolver. */
internal class JavaNativeCompilerDiagnostics {
    data class Capture(val diagnostics: List<Diagnostic>, val paths: Set<Path>, val provenanceHash: String, val backend: String)

    fun capture(snapshot: ProjectSnapshot, sourceSnapshotHash: String, authority: JavaContextAuthority,
                cancellation: SemanticCancellationToken): Capture? {
        val files = snapshot.files.filter { it.languageId == "java" }
        val groups = files.groupBy { file -> snapshot.owningJavaBuildSourceRoots(file.path).single() }
            .entries.groupBy { (owner, _) -> Triple(owner.providerId, owner.module.id, owner.sourceSet.id) }
        val captured = linkedSetOf<Path>()
        val releases = sortedSetOf<Int>()
        val diagnostics = mutableListOf<Diagnostic>()
        require(sourceSnapshotHash.matches(Regex("[a-f0-9]{64}")))
        val evidence = StringBuilder("java-sdk-compiler-diagnostics-v1\nexplicit-source-set-diagnostics-v1\n$sourceSnapshotHash\n${authority.identitySha256}\n")
        authority.requireCurrent()
        checkCancellation(cancellation)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        for ((key, entries) in groups) {
            val owner = entries.first().key
            val sourceSet = owner.sourceSet
            val source = sourceSet.attributes["java.release"] ?: sourceSet.attributes["java.sourceLevel"]
            val languageRelease = source?.toIntOrNull()?.takeIf { it in setOf(25, 26) } ?: continue
            if (sourceSet.attributes["java.targetLevel"]?.let { it != source } == true) continue
            val platform = authority.platforms[JavaSourceSetPlatformContext.profile(sourceSet, authority.platforms)]
                ?.takeIf { it.release == languageRelease && it.platformRelease == languageRelease } ?: continue
            val model = snapshot.buildModels.single { it.providerId == owner.providerId }
            val environment = JavaBuildSourceSetContextResolver.resolve(model, owner.module, sourceSet)
            val ownFiles = entries.flatMap { it.value }.distinctBy { it.path }
            // JPMS remains on its existing independently qualified backend.
            if (files.any { it.path.fileName.toString() == "module-info.java" &&
                    environment.sourceRoots.any { root -> it.path.startsWith(root) } }) continue
            val unsafe = environment.compilerOptions.any { option -> option.redacted ||
                option.name in setOf("compilerArgs", "compilerArgument", "compilerArguments", "compilerArgFile", "bootclasspath", "system", "enablePreview", "executable") ||
                option.name == "compilerId" && option.value != "javac" || option.name == "fork" && option.value != "false" ||
                option.name == "compilerArg" && !JavaSdkCompilerArguments.isApiNeutral(option.value)
            }
            if (unsafe) throw JavaContextAuthorityException("java.diagnostics.compilerOptionsUnqualified",
                "Native compiler diagnostics require qualified effective compiler arguments")
            checkCancellation(cancellation)
            val result = acquire(snapshot, ownFiles, environment, platform, cancellation, deadline,
                sourceSet.attributes["java.release"] != null)
            val ownPaths = ownFiles.mapTo(linkedSetOf()) { it.path }
            captured.addAll(ownPaths)
            releases.add(languageRelease)
            // javac can also diagnose a main dependency implicitly loaded by a test group.
            // Its owning group supplies the authoritative error and explicit-source range.
            diagnostics.addAll(result.first.filter { requireNotNull(it.location).path in ownPaths })
            evidence.append(key).append('\n').append(result.second).append('\n')
        }
        authority.requireCurrent()
        return if (captured.isEmpty()) null else Capture(diagnostics.distinct(), captured, hash(evidence.toString().toByteArray()),
            releases.joinToString("+") { "jdk$it-javac-compiler-errors-v1" })
    }

    private fun acquire(snapshot: ProjectSnapshot, files: List<SourceFile>, environment: JavaBuildSourceSetContext,
                        platform: JavaReleasePlatformAuthority, cancellation: SemanticCancellationToken,
                        deadline: Long, release: Boolean): Pair<List<Diagnostic>, String> {
        val workspace = snapshot.workspace.root.toRealPath()
        val allFiles = snapshot.files.filter { it.languageId == "java" }.associateBy { it.path }
        if (allFiles.size > 10000 || allFiles.values.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() } > 64L * 1024 * 1024)
            throw JavaContextAuthorityException("java.diagnostics.limitExceeded", "Native compiler source budget exceeded")
        val sourceImages = allFiles.mapKeys { workspace.resolve(it.key).normalize() }.mapValues { (path, file) ->
            require(path.startsWith(workspace) && path.toRealPath() == path && Files.readString(path) == file.content)
            sha(path)
        }
        val roots = environment.sourceRoots.map { workspace.resolve(it).normalize() }
            .filter(Files::isDirectory).distinct()
        require(roots.all { it.startsWith(workspace) && it.toRealPath() == it })
        val classpath = environment.classpathEntries.map { it.toAbsolutePath().normalize().toRealPath() }.distinct()
        require(classpath.size <= 1024 && roots.size <= 1024)
        val classpathImages = classpath.associateWith(::classpathHash)
        val home = platform.platformHome.toRealPath()
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val java = home.resolve("bin/java$suffix"); val javac = home.resolve("bin/javac$suffix")
        require(java.toRealPath().startsWith(home) && javac.toRealPath().startsWith(home))
        val javaHash = sha(java); val javacHash = sha(javac)
        val privateRoot = Files.createTempDirectory("refactorkit-native-diagnostics-")
        try {
            val worker = privateRoot.resolve("NativeJavaDiagnosticsWorker.java")
            val workerBytes = requireNotNull(javaClass.getResourceAsStream("/org/refactorkit/java/NativeJavaDiagnosticsWorker.java"))
                .use { it.readNBytes(128 * 1024 + 1) }
            require(workerBytes.size <= 128 * 1024)
            Files.write(worker, workerBytes)
            val workerHash = sha(worker)
            val request = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC); out.writeUTF(home.toString()); out.writeUTF(workspace.toString())
                out.writeInt(files.size); files.forEach { out.writeUTF(it.path.toString()) }
                out.writeInt(roots.size); roots.forEach { out.writeUTF(workspace.relativize(it).toString()) }
                out.writeInt(classpath.size); classpath.forEach { out.writeUTF(it.toString()) }
                out.writeBoolean(release)
                out.writeInt(platform.release)
            } }.toByteArray()
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "refactorkit-compiler-errors-output").apply { isDaemon = true } }
            try {
                ExternalSemanticProcessManager(maxProcesses = 1).use { manager ->
                    manager.launch(SemanticProcessSpec("java-compiler-errors", java,
                        arguments = listOf("-Xmx256m", "-XX:-UsePerfData", "-Djava.io.tmpdir=$privateRoot", worker.toString()),
                        workingDirectory = privateRoot, environment = mapOf("LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8"),
                        limits = SemanticProcessLimits(maxStdoutBytes = 16L * 1024 * 1024, maxStderrBytes = 65536))).use { process ->
                        require(process.provenance.executableSha256 == javaHash)
                        val response = executor.submit<ByteArray> { process.output.readBytes() }
                        process.input.use { it.write(request) }
                        while (!process.awaitExit(50)) {
                            checkCancellation(cancellation)
                            if (System.nanoTime() >= deadline) throw JavaContextAuthorityException(
                                "java.diagnostics.timeout", "Native compiler diagnostics exceeded the query time budget")
                        }
                        checkCancellation(cancellation)
                        if (process.exitCode != 0 || process.stderrTruncated()) throw JavaContextAuthorityException(
                            "java.diagnostics.compilerUnavailable", "Selected compiler could not produce a complete error inventory")
                        val values = decode(response.get(2, TimeUnit.SECONDS), files, allFiles)
                        require(sha(worker) == workerHash && sha(java) == javaHash && sha(javac) == javacHash &&
                            sourceImages.all { (path, image) -> sha(path) == image } &&
                            classpathImages.all { (path, image) -> classpathHash(path) == image })
                        val provenance = buildString {
                            append(platform.identitySha256).append('\n').append(javaHash).append('\n').append(javacHash).append('\n').append(workerHash)
                            append('\n').append(release).append('\n').append(environment.compilerOptions)
                            files.forEach { append('\n').append(it.path) }
                            classpathImages.forEach { (path, image) -> append('\n').append(path).append(':').append(image) }
                        }
                        return values to hash(provenance.toByteArray())
                    }
                }
            } finally { executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS) }
        } finally { Files.walk(privateRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun decode(bytes: ByteArray, files: List<SourceFile>, allFiles: Map<Path, SourceFile>): List<Diagnostic> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC)
        fun count(maximum: Int) = input.readInt().also { require(it in 0..maximum) }
        fun path() = Path.of(input.readUTF()).also { require(!it.isAbsolute && !it.normalize().startsWith("..") && it in allFiles) }
        val parsed = List(count(10000)) { path() }
        require(parsed.size == files.size && parsed.toSet() == files.map { it.path }.toSet())
        val errors = List(count(10000)) {
            val source = allFiles.getValue(path())
            val start = input.readInt(); val end = input.readInt()
            require(start >= 0 && end >= start && end <= source.content.length)
            val code = input.readUTF(); require(code.startsWith("compiler.err."))
            val message = input.readUTF(); require(message.isNotBlank())
            Diagnostic(message, Diagnostic.Severity.ERROR, SourceLocation(source.path,
                TextEdits.rangeForOffset(source.content, start, end - start)), "java.javac.$code",
                evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION)
        }
        require(input.read() == -1)
        return errors
    }

    private fun classpathHash(path: Path): String {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.size(path) <= 256L * 1024 * 1024); return sha(path)
        }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0; var bytes = 0L
        Files.walk(path).use { paths -> paths.sorted().forEach { entry ->
            require(!Files.isSymbolicLink(entry))
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && ++count <= 16384)
                bytes += Files.size(entry); require(bytes <= 256L * 1024 * 1024)
                val name = path.relativize(entry).toString().toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.size).array()); digest.update(name)
                digest.update(sha(entry).toByteArray(Charsets.US_ASCII))
            }
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha(path: Path) = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun checkCancellation(token: SemanticCancellationToken) { if (token.isCancellationRequested()) throw JdtJavaAnalysisCancelledException() }
    companion object { private const val MAGIC = 0x524b4431 }
}
