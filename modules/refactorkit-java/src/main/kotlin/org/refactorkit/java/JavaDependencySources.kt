package org.refactorkit.java

import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.util.IClassFileReader
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.owningBuildSourceRoots
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class JavaDependencySourceRequest(
    val context: JavaProjectContextRequest,
    val path: Path,
    val position: SourcePosition,
    val expectedAssociationHash: String? = null,
    val editorOverlayHash: String? = null,
) {
    init {
        require(context.sdkHome != null && context.expectedContextHash != null) { "Explicit SDK and context hash are required" }
        require(!path.isAbsolute && path.toString().isNotBlank() && !path.normalize().startsWith("..")) { "Saved source path must be workspace relative" }
        require(expectedAssociationHash == null || expectedAssociationHash.matches(Regex("[a-f0-9]{64}"))) { "Association hash must be SHA-256" }
        require(editorOverlayHash == null || editorOverlayHash.matches(Regex("[a-f0-9]{64}"))) { "Editor overlay hash must be SHA-256" }
    }
}

data class JavaDependencySourceAssociation(
    val associationHash: String,
    val resourceUri: String,
    val providerId: String,
    val moduleId: String,
    val sourceSetId: String,
    val binaryPath: Path,
    val binarySha256: String,
    val sourceArchivePath: Path,
    val sourceArchiveSha256: String,
    val sourceEntry: String,
    val sourceSha256: String,
    val identity: JdtJavaJvmIdentity,
    val provenanceKind: String,
    val componentId: String,
    val sourceFile: String,
)

sealed interface JavaDependencySourceResult {
    data class Available(val association: JavaDependencySourceAssociation, val content: String?) : JavaDependencySourceResult
    data class Unavailable(val code: String, val message: String) : JavaDependencySourceResult
    data class Ambiguous(val code: String, val message: String, val candidates: List<Path>) : JavaDependencySourceResult
    data class Refused(val code: String, val message: String) : JavaDependencySourceResult
}

/** Provider-owned, read-only navigation. No dependency resolution, extraction or source writes. */
class JavaDependencySources {
    fun read(request: JavaDependencySourceRequest, scan: () -> ProjectSnapshot,
             cancellation: SemanticCancellationToken): JavaBoundReadResult<JavaDependencySourceResult> =
        JavaBoundContext.read(request.context, scan, cancellation) { context ->
            readInContext(context, request, cancellation)
        }

    internal fun readInContext(context: JavaBoundContext, request: JavaDependencySourceRequest,
                               cancellation: SemanticCancellationToken): JavaDependencySourceResult = try {
        lookup(context, request, cancellation)
    } catch (failure: SourceFailure) {
        JavaDependencySourceResult.Refused(failure.code, failure.message.orEmpty())
    } catch (_: Exception) {
        JavaDependencySourceResult.Refused("java.sources.readUnavailable", "Dependency archive could not be read safely")
    }

    internal fun binaryDeclaresIdentity(association: JavaDependencySourceAssociation, sourceModifiers: Int? = null): Boolean {
        val bytes = archiveBytes(association.binaryPath)
        if (hash(bytes) != association.binarySha256) return false
        val identity = association.identity
        val classBytes = zipEntry(bytes, identity.ownerBinaryName.replace('.', '/') + ".class", 2 * 1024 * 1024, true) ?: return false
        val reader = ToolFactory.createDefaultClassFileReader(ByteArrayInputStream(classBytes), IClassFileReader.ALL_BUT_METHOD_BODIES) ?: return false
        if (String(reader.className) != identity.ownerBinaryName.replace('.', '/')) return false
        fun modifiersMatch(binaryModifiers: Int): Boolean {
            val mask = if (identity.kind == JdtJavaJvmIdentityKind.TYPE) 0x6200 else 0x000f
            return sourceModifiers == null || binaryModifiers and mask == sourceModifiers and mask
        }
        return when (identity.kind) {
            JdtJavaJvmIdentityKind.TYPE -> modifiersMatch(reader.accessFlags)
            JdtJavaJvmIdentityKind.CALLABLE -> reader.methodInfos.any { String(it.name) == identity.memberName && String(it.descriptor) == identity.descriptor && modifiersMatch(it.accessFlags) }
            JdtJavaJvmIdentityKind.FIELD -> reader.fieldInfos.any { String(it.name) == identity.memberName && String(it.descriptor) == identity.descriptor && modifiersMatch(it.accessFlags) }
        }
    }

    private fun lookup(context: JavaBoundContext, request: JavaDependencySourceRequest,
                       cancellation: SemanticCancellationToken): JavaDependencySourceResult {
        fun unavailable(code: String, message: String): JavaDependencySourceResult =
            if (request.expectedAssociationHash == null) JavaDependencySourceResult.Unavailable(code, message)
            else JavaDependencySourceResult.Refused("java.sources.associationStale", "Source association is no longer available; repeat lookup")
        val snapshot = context.snapshot
        val path = request.path.normalize()
        val file = snapshot.files.singleOrNull { it.path.normalize() == path && it.languageId == "java" }
            ?: return unavailable("java.sources.savedSourceRequired", "Lookup requires a saved Java source in this snapshot")
        val lines = file.content.split('\n')
        if (request.position.line !in lines.indices || request.position.character >= lines[request.position.line].length)
            return unavailable("java.sources.bindingUnavailable", "Cursor is outside a saved Java token")
        val owners = snapshot.owningJavaBuildSourceRoots(path)
        if (owners.size != 1) return JavaDependencySourceResult.Refused("java.sources.ownerAmbiguous", "Saved source has no unique provider/source-set owner")
        val owner = owners.single()
        val analysis = requireNotNull(context.view.analysis).analysis
        val identities = analysis.bindingUses.filter { use -> use.path.normalize() == path && !use.recovered &&
            use.sourceRange?.let { request.position >= it.start && request.position < it.end } == true }
            .mapNotNull { it.jvmIdentity }.distinct()
        // JDT emits the constructor call binding and its type token at the same cursor.
        // Prefer the actual resolved constructor only when the type identifies that same owner.
        val constructors = identities.filter { it.kind == JdtJavaJvmIdentityKind.CALLABLE && it.memberName == "<init>" }
        val bindings = if (constructors.size == 1 && identities.all { it == constructors.single() ||
            (it.kind == JdtJavaJvmIdentityKind.TYPE && it.ownerBinaryName == constructors.single().ownerBinaryName) }) constructors else identities
        if (bindings.size != 1) return unavailable("java.sources.bindingUnavailable", "Cursor has no unique resolved JVM identity")
        val identity = bindings.single()
        if (!identity.ownerBinaryName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")))
            return unavailable("java.sources.binaryNameUnsupported", "Binary identity cannot name a safe class entry")
        val environment = context.view.environments[JavaEffectiveProjectContextProvider.key(owner.providerId, owner.module.id, owner.sourceSet.id)]
            ?: return unavailable("java.sources.environmentUnavailable", "Source-set compile environment is unavailable")
        val paths = environment.compileClasspath.map { snapshot.workspace.root.resolve(it.path).normalize() }.distinct()
        if (paths.size > 128) fail("java.sources.limitExceeded", "Dependency lookup exceeds 128 scoped classpath entries")
        val entry = identity.ownerBinaryName.replace('.', '/') + ".class"
        val binaries = paths.filter { binary ->
            checkCancelled(cancellation)
            when {
                Files.isDirectory(binary, NOFOLLOW_LINKS) -> Files.isRegularFile(binary.resolve(entry), NOFOLLOW_LINKS)
                Files.isRegularFile(binary, NOFOLLOW_LINKS) && binary.fileName.toString().endsWith(".jar") -> hasClass(binary, entry)
                else -> false
            }
        }
        if (binaries.size > 1) return JavaDependencySourceResult.Ambiguous("java.sources.binaryAmbiguous",
            "Multiple scoped classpath entries contain the exact bound binary; no source was selected", binaries)
        val binary = binaries.singleOrNull()
            ?: return unavailable("java.sources.binaryUnavailable", "Binding has no qualified dependency JAR; workspace and SDK definitions use their existing navigation")
        if (!Files.isRegularFile(binary, NOFOLLOW_LINKS))
            return unavailable("java.sources.attachmentUnavailable", "Compiled directory has no qualified source attachment")
        val mapping = attachment(owner.providerId, owner.module.attributes + owner.sourceSet.attributes, owner.sourceSet.id, binary)
            ?: return unavailable("java.sources.attachmentUnavailable", "Provider has no qualified source attachment for this exact binary")
        if (!Files.isRegularFile(mapping.path, NOFOLLOW_LINKS))
            return unavailable("java.sources.attachmentUnavailable", "Selected source archive is not locally materialized")
        val binaryBytes = archiveBytes(binary)
        val classBytes = zipEntry(binaryBytes, entry, 2 * 1024 * 1024, rejectMultiRelease = true)
            ?: return unavailable("java.sources.binaryUnavailable", "Exact binary class entry is unavailable")
        val reader = ToolFactory.createDefaultClassFileReader(ByteArrayInputStream(classBytes), IClassFileReader.CLASSFILE_ATTRIBUTES)
            ?: return unavailable("java.sources.classfileUnavailable", "Dependency classfile cannot be decoded")
        if (String(reader.className) != identity.ownerBinaryName.replace('.', '/'))
            fail("java.sources.classfileMismatch", "Classfile identity differs from its bound binary entry")
        val sourceFile = reader.sourceFileAttribute?.sourceFileName?.let(::String)
            ?: return unavailable("java.sources.sourceFileUnavailable", "Classfile has no SourceFile attribute")
        if (!sourceFile.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*\\.java")))
            return unavailable("java.sources.sourceFileUnsupported", "Classfile SourceFile is not a supported Java basename")
        val sourceEntry = entry.substringBeforeLast('/', "").let { if (it.isEmpty()) sourceFile else "$it/$sourceFile" }
        val sourceArchive = archiveBytes(mapping.path)
        val sourceBytes = zipEntry(sourceArchive, sourceEntry, 128 * 1024)
            ?: return unavailable("java.sources.sourceEntryUnavailable", "Provider source archive does not contain the classfile SourceFile entry")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(sourceBytes)).toString()
        val unit = ASTParser.newParser(AST.getJLSLatest()).apply {
            setSource(text.toCharArray()); setKind(ASTParser.K_COMPILATION_UNIT)
            setCompilerOptions(mapOf(JavaCore.COMPILER_SOURCE to "25", JavaCore.COMPILER_COMPLIANCE to "25", JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM to "25"))
        }
            .createAST(null) as CompilationUnit
        val expectedPackage = identity.ownerBinaryName.substringBeforeLast('.', "")
        val topName = identity.ownerBinaryName.substringAfterLast('.').substringBefore('$')
        if (unit.getPackage()?.name?.fullyQualifiedName.orEmpty() != expectedPackage ||
            unit.types().filterIsInstance<AbstractTypeDeclaration>().none { it.name.identifier == topName })
            return unavailable("java.sources.sourceIdentityMismatch", "Source entry does not declare the binary package and owning top-level type")
        val binaryHash = hash(binaryBytes); val archiveHash = hash(sourceArchive); val sourceHash = hash(sourceBytes)
        val associationFields = listOf("java-dependency-source-v1", snapshot.hash, context.view.contextHash,
            owner.providerId, owner.module.id, owner.sourceSet.id, binary.toString(), binaryHash,
            mapping.path.toString(), archiveHash, sourceEntry, sourceHash, identity.kind.name,
            identity.ownerBinaryName, identity.memberName, identity.descriptor, mapping.kind, mapping.componentId)
        val associationHash = hash((if (request.editorOverlayHash == null) associationFields else
            associationFields + listOf("immutable-editor-overlay", request.editorOverlayHash))
            .joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        if (request.expectedAssociationHash != null && request.expectedAssociationHash != associationHash)
            return JavaDependencySourceResult.Refused("java.sources.associationStale", "Binary/source bytes or provenance changed; repeat lookup")
        checkCancelled(cancellation)
        val association = JavaDependencySourceAssociation(associationHash,
            "refactorkit-java-source://$associationHash/" + URLEncoder.encode(sourceEntry, "UTF-8"),
            owner.providerId, owner.module.id, owner.sourceSet.id, binary, binaryHash, mapping.path,
            archiveHash, sourceEntry, sourceHash, identity, mapping.kind, mapping.componentId, sourceFile)
        return JavaDependencySourceResult.Available(association, if (request.expectedAssociationHash != null) text else null)
    }

    private data class Attachment(val path: Path, val kind: String, val componentId: String)

    private fun attachment(provider: String, attributes: Map<String, String>,
                           sourceSet: String, binary: Path): Attachment? {
        if (provider == "gradle-effective-v1") {
            val count = attributes["java.sourceAttachment.count"]?.toIntOrNull() ?: return null
            if (count !in 0..4096) fail("java.sources.limitExceeded", "Gradle attachment provenance exceeds 4096 records")
            val mappings = (0 until count).mapNotNull { index ->
                val prefix = "java.sourceAttachment.$index"
                if (attributes["$prefix.binaryPath"] != binary.toString()) return@mapNotNull null
                Attachment(Path.of(requireNotNull(attributes["$prefix.sourcePath"])),
                    "GRADLE_RESOLVED_COMPONENT_SOURCES_VARIANT_AND_CLASSFILE_SOURCE_FILE", requireNotNull(attributes["$prefix.componentId"]))
            }.distinct()
            if (mappings.size > 1) fail("java.sources.attachmentAmbiguous", "Gradle component has multiple source artifacts; no attachment was selected")
            return mappings.singleOrNull()
        }
        if (provider != "maven-effective-v1") return null
        // Reuse the provider's selected fixed-version normal-JAR records; never resolve a second graph.
        val count = attributes["java.maven.selector.count"]?.toIntOrNull() ?: return null
        if (count !in 0..8192) fail("java.sources.limitExceeded", "Maven attachment provenance exceeds its record budget")
        val mappings = (0 until count).mapNotNull { index ->
            val prefix = "java.maven.selector.$index"
            fun field(name: String) = attributes["$prefix.$name"].orEmpty()
            if (field("sourceSet") != sourceSet || field("projection") != (if (sourceSet == "test") "TEST" else "COMPILE") || field("outcome") != "SELECTED" ||
                field("type") != "jar" || field("classifier").isNotEmpty()) return@mapNotNull null
            val group = field("groupId"); val artifact = field("artifactId"); val version = field("declaredVersion")
            if (!group.matches(Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")) ||
                !artifact.matches(Regex("[A-Za-z0-9_.-]+")) || !version.matches(Regex("[A-Za-z0-9_.-]+")) ||
                version.endsWith("-SNAPSHOT") || artifact in setOf(".", "..") || version in setOf(".", "..")) return@mapNotNull null
            val suffix = Path.of(group.replace('.', '/'), artifact, version, "$artifact-$version.jar")
            if (!binary.endsWith(suffix)) return@mapNotNull null
            Attachment(binary.resolveSibling("$artifact-$version-sources.jar"),
                "MAVEN_SELECTED_COORDINATE_AND_CLASSFILE_SOURCE_FILE", "$group:$artifact:$version")
        }.distinct()
        return mappings.singleOrNull()
    }

    private fun hasClass(path: Path, name: String): Boolean = ZipFile(path.toFile()).use { zip ->
        if (zip.size() > 32768) fail("java.sources.limitExceeded", "Dependency archive exceeds 32768 entries")
        val matches = zip.entries().asSequence().count { it.name == name }
        if (matches > 1) fail("java.sources.archiveAmbiguous", "Dependency archive contains duplicate class entries")
        matches == 1
    }

    private fun archiveBytes(path: Path): ByteArray {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > 32L * 1024 * 1024)
            fail("java.sources.limitExceeded", "Archive must be a regular file of at most 32 MiB")
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { stream ->
            stream.readNBytes(32 * 1024 * 1024 + 1).also {
                if (it.size > 32 * 1024 * 1024) fail("java.sources.limitExceeded", "Archive grew beyond its byte budget")
            }
        }
    }

    private fun zipEntry(bytes: ByteArray, name: String, limit: Int, rejectMultiRelease: Boolean = false): ByteArray? {
        // Inspect the central directory in hasClass; bounded stream decoding rejects duplicate selected entries.
        var result: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            var inflated = 0L
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > 32768) fail("java.sources.limitExceeded", "Archive exceeds 32768 entries")
                if (rejectMultiRelease && entry.name.startsWith("META-INF/versions/"))
                    fail("java.sources.multiReleaseUnsupported", "Multi-release binary source selection is not qualified")
                val selected = entry.name == name
                if (selected) {
                    if (result != null || entry.isDirectory) fail("java.sources.archiveAmbiguous", "Selected source/class entry is duplicated or is a directory")
                }
                val output = if (selected) ByteArrayOutputStream() else null
                while (true) {
                    val size = zip.read(buffer)
                    if (size < 0) break
                    inflated += size
                    if (inflated > 64L * 1024 * 1024) fail("java.sources.limitExceeded", "Archive exceeds the 64 MiB inflation budget")
                    if (output != null) {
                        if (output.size() + size > limit) fail("java.sources.limitExceeded", "Selected entry exceeds its byte budget")
                        output.write(buffer, 0, size)
                    }
                }
                if (output != null) result = output.toByteArray()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun checkCancelled(token: SemanticCancellationToken) { if (token.isCancellationRequested()) fail("java.sources.cancelled", "Dependency source lookup was cancelled") }
    private class SourceFailure(val code: String, message: String) : RuntimeException(message)
    private fun fail(code: String, message: String): Nothing = throw SourceFailure(code, message)
}
