package org.refactorkit.java

import org.refactorkit.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in, read-only compiler acquisition. Public profile qualification is a separate gate. */
internal class JavaNativeLaunchCompiler {
    /** The caller supplies one provider-owned scope and its exact compile classpath. */
    fun capture(snapshot: ProjectSnapshot, authority: JavaReleasePlatformAuthority,
                classpathEntries: List<Path>,
                cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE): JavaCompilerLaunchResolution {
        fun refused(code: String, message: String) = JavaCompilerLaunchResolution.Refused(code, message)
        if (cancellation.isCancellationRequested()) return refused("java.launch.cancelled", "Launch-facts acquisition cancelled")
        if (authority.platformRelease != 25 || authority.release != 25) return refused(
            "java.launch.sdkUnqualified", "Native launch-facts acquisition requires an explicitly selected SDK25")
        val files = snapshot.files.filter { it.languageId == "java" }
        if (files.isEmpty() || files.size > 10000 || files.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() } > 64L * 1024 * 1024 ||
            files.any { it.path.fileName.toString() == "module-info.java" }) return refused(
            "java.launch.scopeUnqualified", "This compiler acquisition slice requires bounded non-JPMS Java sources")
        if (snapshot.modules.isEmpty() || snapshot.modules.any {
                (it.languageSettings["java.release"] ?: it.languageSettings["java.sourceLevel"]) != "25" ||
                    it.languageSettings["java.targetLevel"]?.let { target -> target != "25" } == true
            }) return refused("java.launch.sourceUnqualified", "This compiler acquisition slice requires source/target25")
        var overlay: SemanticWorkspaceOverlay? = null
        return try {
            val capturedSnapshotHash = snapshot.hash
            val resolver = JavaReleasePlatformAuthorityResolver()
            fun current() = (resolver.revalidate(authority) as? JavaReleasePlatformResolution.Available)
                ?.authority?.identitySha256 == authority.identitySha256
            if (!current()) return refused("java.launch.contextStale", "SDK platform evidence changed")
            val home = authority.platformHome.toRealPath()
            val java = home.resolve(if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java")
            val javac = home.resolve(if (System.getProperty("os.name").startsWith("Windows")) "bin/javac.exe" else "bin/javac")
            require(java.toRealPath().startsWith(home) && javac.toRealPath().startsWith(home))
            val javaHash = sha(java); val javacHash = sha(javac)
            val workspace = snapshot.workspace.root.toRealPath()
            val sourceImages = files.associate { file ->
                val path = (if (file.path.isAbsolute) file.path else workspace.resolve(file.path)).normalize()
                require(path.startsWith(workspace) && path.toRealPath() == path && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                require(Files.size(path) == file.content.toByteArray(Charsets.UTF_8).size.toLong() && Files.readString(path) == file.content)
                path to sha(path)
            }
            require(sourceImages.size == files.size)
            require(classpathEntries.size <= 1024)
            val classpath = classpathEntries.map { path ->
                (if (path.isAbsolute) path else workspace.resolve(path)).normalize().toRealPath()
            }.distinct()
            val classpathImages = classpath.associateWith(::classpathHash)
            val owned = SemanticWorkspaceOverlay.create(snapshot.copy(files = files), maxFiles = 10000, maxBytes = 64L * 1024 * 1024)
            overlay = owned
            val worker = owned.root.resolve("NativeJavaLaunchWorker.java")
            // A source file at this path would collide with the private worker and is refused.
            require(!Files.exists(worker))
            val workerBytes = requireNotNull(javaClass.getResourceAsStream("/org/refactorkit/java/NativeJavaLaunchWorker.java"))
                .use { it.readNBytes(128 * 1024 + 1) }
            require(workerBytes.size <= 128 * 1024)
            Files.write(worker, workerBytes)
            val workerHash = sha(worker)
            val request = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC); output.writeUTF(home.toString()); output.writeUTF(owned.root.toString()); output.writeInt(files.size)
                    files.forEach { file ->
                        val path = requireNotNull(owned.toOverlayPath(file.path))
                        output.writeUTF(owned.root.relativize(path).toString())
                    }
                    output.writeInt(classpath.size); classpath.forEach { output.writeUTF(it.toString()) }
                }
            }.toByteArray()
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "refactorkit-native-launch-output").apply { isDaemon = true } }
            try {
                ExternalSemanticProcessManager(maxProcesses = 1).use { manager ->
                    manager.launch(SemanticProcessSpec(
                        id = "java-launch-facts", executable = java, workingDirectory = owned.root,
                        environment = mapOf("LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8"),
                        arguments = listOf("-Xmx256m", "-XX:-UsePerfData", "-Djava.io.tmpdir=${owned.root}",
                            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                            "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                            worker.toString()),
                        limits = SemanticProcessLimits(maxStdoutBytes = 16L * 1024 * 1024, maxStderrBytes = 65536),
                    )).use { process ->
                        require(process.provenance.executableSha256 == javaHash)
                        val response = executor.submit<ByteArray> { process.output.readBytes() }
                        process.input.use { it.write(request) }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                        while (!process.awaitExit(50)) {
                            if (cancellation.isCancellationRequested()) {
                                process.cancel(); return refused("java.launch.cancelled", "Launch-facts acquisition cancelled")
                            }
                            if (System.nanoTime() >= deadline) {
                                process.cancel(); return refused("java.launch.timeout", "Native compiler acquisition exceeded its time limit")
                            }
                        }
                        if (process.exitCode != 0 || process.stderrTruncated()) return refused(
                            "java.launch.compilerFailed", "The selected compiler did not produce complete launch facts")
                        val payload = response.get(2, TimeUnit.SECONDS)
                        if (cancellation.isCancellationRequested()) return refused("java.launch.cancelled", "Launch-facts acquisition cancelled")
                        if (!current() || snapshot.hash != capturedSnapshotHash || sha(worker) != workerHash ||
                            sha(java) != javaHash || sha(javac) != javacHash ||
                            sourceImages.any { (path, image) -> sha(path) != image } ||
                            classpathImages.any { (path, image) -> classpathHash(path) != image } ||
                            owned.verifySourcesUnchanged().isNotEmpty()) return refused(
                            "java.launch.inputsChanged", "Native compiler inputs changed during acquisition")
                        return JavaNativeLaunchWire.decode(payload, files, owned, authority, classpathImages,
                            javaHash, javacHash, process.provenance,
                            JavaNativeLaunchCaptureContext(capturedSnapshotHash, 25, 25, authority.platformRelease, workerHash))
                    }
                }
            } finally { executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS) }
        } catch (_: Exception) {
            refused("java.launch.acquisitionUnavailable", "Complete native compiler facts could not be acquired")
        } finally { overlay?.close() }
    }

    private fun classpathHash(path: Path): String {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.size(path) <= 256L * 1024 * 1024)
            return sha(path)
        }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0; var bytes = 0L
        Files.walk(path).use { paths -> paths.sorted().forEach { entry ->
            require(!Files.isSymbolicLink(entry))
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && ++count <= 16384)
                bytes += Files.size(entry); require(bytes <= 256L * 1024 * 1024)
                val relative = path.relativize(entry).toString().toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(relative.size).array())
                digest.update(relative)
                digest.update(sha(entry).toByteArray(Charsets.US_ASCII))
            }
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha(path: Path): String {
        require(Files.isRegularFile(path))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(8192)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { internal const val MAGIC = 0x524b4c32 }
}

/** Closed bounded worker protocol; partial or malformed inventories never become an empty success. */
private object JavaNativeLaunchWire {
    fun decode(bytes: ByteArray, files: List<SourceFile>, overlay: SemanticWorkspaceOverlay,
               authority: JavaReleasePlatformAuthority, classpathInputs: Map<Path, String>, javaHash: String, javacHash: String,
               provenance: SemanticProcessProvenance, captureContext: JavaNativeLaunchCaptureContext): JavaCompilerLaunchResolution {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == JavaNativeLaunchCompiler.MAGIC)
        val status = input.readInt(); require(status in 0..1)
        val errors = List(count(input, 64)) { input.readUTF() }
        if (status == 1) {
            require(errors.isNotEmpty() && input.read() == -1)
            return JavaCompilerLaunchResolution.Refused("java.launch.compilerDiagnostics", "The selected compiler reported source errors")
        }
        require(errors.isEmpty())
        val sources = files.associateBy { requireNotNull(overlay.toOverlayPath(it.path)).normalize() }
        val origins = JavaNativeOriginDecoder(overlay, sources, authority, classpathInputs)
        val read = linkedSetOf<Path>()
        val result = List(count(input, 10000)) {
            val nativePath = Path.of(input.readUTF()); require(!nativePath.isAbsolute && !nativePath.normalize().startsWith(".."))
            val overlayPath = overlay.root.resolve(nativePath).normalize()
            val source = requireNotNull(sources[overlayPath]); require(read.add(overlayPath))
            val values = List(count(input, 4096)) {
                val start = input.readInt(); val end = input.readInt(); val compact = input.readBoolean()
                require(start >= 0 && start < end && end <= source.content.length)
                val owner = name(input)
                val nodes = linkedMapOf<String, JavaNativeLaunchClassFacts>()
                repeat(count(input, 4096)) {
                    val binary = name(input); val packageName = input.readUTF()
                    val kind = JavaNativeLaunchClassKind.valueOf(input.readUTF()); val modifiers = input.readInt()
                    val owned = input.readBoolean(); val local = input.readBoolean(); val member = input.readBoolean(); val anonymous = input.readBoolean()
                    val parent = input.readUTF().takeIf { it.isNotEmpty() }
                    val interfaces = List(count(input, 4096)) { name(input) }
                    val methods = methods(input, origins)
                    require(nodes.put(binary, JavaNativeLaunchClassFacts(binary, packageName, kind, modifiers,
                        owned, local, member, anonymous, parent, interfaces, methods)) == null)
                    require(methods.all { it.declaringBinaryName == binary })
                }
                val effective = methods(input, origins)
                require(owner in nodes && nodes.getValue(owner).sourceOwned)
                require(nodes.values.all { node ->
                    (node.superclassBinaryName == null || node.superclassBinaryName in nodes) && node.interfaceBinaryNames.all { it in nodes }
                })
                require(effective.all { !it.constructor && it.declaringBinaryName in nodes })
                JavaNativeLaunchTypeReadout.Available(TextEdits.rangeForOffset(source.content, start, end - start),
                    if (compact) JavaNativeLaunchSourceForm.COMPACT else JavaNativeLaunchSourceForm.DECLARED,
                    JavaNativeLaunchGraph(owner, nodes, effective, effectiveMemberInventoryComplete = true))
            }
            require(values.map { it.graph.rootBinaryName }.distinct().size == values.size)
            JavaCompilerLaunchSource(source.path, values)
        }
        require(read == sources.keys && input.read() == -1)
        return JavaCompilerLaunchResolution.Available(result, authority.identitySha256, javaHash, javacHash, provenance,
            captureContext, classpathInputs)
    }

    private fun methods(input: DataInputStream, origins: JavaNativeOriginDecoder) = List(count(input, 16384)) {
        val owner = name(input); val descriptor = input.readUTF(); require(descriptor.length in 3..4096)
        val modifiers = input.readInt(); val shape = JavaNativeMainParameterShape.valueOf(input.readUTF())
        val parameters = input.readInt(); require(parameters in 0..255)
        JavaNativeLaunchMethodFacts(owner, descriptor, modifiers, shape, parameters,
            input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean(), origins.read(input))
    }
    private fun count(input: DataInputStream, maximum: Int) = input.readInt().also { require(it in 0..maximum) }
    private fun name(input: DataInputStream) = input.readUTF().also { require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 1024) }
}

/** ACL for native declaration file objects; never opens a URI or guesses a path from an owner. */
private class JavaNativeOriginDecoder(
    private val overlay: SemanticWorkspaceOverlay,
    private val sources: Map<Path, SourceFile>,
    private val authority: JavaReleasePlatformAuthority,
    private val classpathInputs: Map<Path, String>,
) {
    fun read(input: DataInputStream): JavaNativeDeclarationOrigin = when (input.readUnsignedByte()) {
        0 -> JavaNativeDeclarationOrigin.Unavailable(JavaNativeOriginRefusal.NATIVE_LOCATION_UNAVAILABLE)
        1 -> {
            val relative = Path.of(input.readUTF())
            require(!relative.isAbsolute && !relative.normalize().startsWith(".."))
            val source = requireNotNull(sources[overlay.root.resolve(relative).normalize()])
            val start = input.readInt(); val end = input.readInt()
            require(start >= 0 && end > start && end <= source.content.length)
            JavaNativeDeclarationOrigin.Source(source.path, TextEdits.rangeForOffset(source.content, start, end - start))
        }
        2 -> {
            val text = input.readUTF(); val module = input.readUTF().takeIf { it.isNotEmpty() }
            require(text.length in 1..8192 && (module == null || module.length <= 256))
            binary(text, module)
        }
        else -> error("Unknown native declaration origin")
    }

    private fun binary(text: String, module: String?): JavaNativeDeclarationOrigin {
        fun unavailable() = JavaNativeDeclarationOrigin.Unavailable(JavaNativeOriginRefusal.UNATTESTED_BINARY_INPUT)
        val uri = runCatching { URI(text) }.getOrNull() ?: return unavailable()
        if (uri.fragment != null || uri.query != null || uri.authority != null) return unavailable()
        fun attested(kind: JavaNativeBinaryOriginKind, path: Path, hash: String) =
            JavaNativeDeclarationOrigin.Binary(kind, text, module, path, hash)
        if (uri.scheme == "jrt") {
            val image = authority.currentSystemModules; val hash = authority.currentSystemModulesSha256
            if (module != null && uri.path?.startsWith("/$module/") == true && image != null && hash != null)
                return attested(JavaNativeBinaryOriginKind.PLATFORM, image, hash)
            return unavailable()
        }
        val archive = uri.scheme == "jar"
        val fileUri = if (archive) {
            val raw = uri.rawSchemeSpecificPart; val separator = raw.lastIndexOf("!/")
            if (separator <= 0 || separator + 2 >= raw.length) return unavailable()
            runCatching { URI(raw.substring(0, separator)) }.getOrNull() ?: return unavailable()
        } else uri
        if (fileUri.scheme != "file" || fileUri.authority != null || fileUri.fragment != null || fileUri.query != null) return unavailable()
        val path = runCatching { Path.of(fileUri).toAbsolutePath().normalize() }.getOrNull() ?: return unavailable()
        if (archive && path == authority.signatureArchive && module != null) {
            val entry = uri.rawSchemeSpecificPart.substringAfterLast("!/")
            if (!entry.split('/').contains(module) || !entry.endsWith(".sig")) return unavailable()
            return attested(JavaNativeBinaryOriginKind.PLATFORM, path, authority.signatureArchiveSha256)
        }
        val matching = classpathInputs.filterKeys { input ->
            if (archive) input == path else Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS) && path.startsWith(input) && path != input
        }
        if (matching.size != 1) return unavailable()
        val (boundPath, hash) = matching.entries.single()
        return attested(JavaNativeBinaryOriginKind.CLASSPATH, boundPath, hash)
    }
}
