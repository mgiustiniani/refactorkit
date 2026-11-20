package org.refactorkit.java

import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.util.IClassFileReader
import org.refactorkit.core.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

data class JdtJavaPlatformBinary(val identity: JdtJavaJvmIdentity, val moduleName: String, val classEntry: String, val originUri: String)
enum class JavaPlatformSourceOperation { LOOKUP, DECLARATION, READ }
data class JavaPlatformSourceRequest(val context: JavaProjectContextRequest, val path: Path, val position: SourcePosition,
                                    val expectedPlatformIdentitySha256: String, val expectedAssociationHash: String? = null,
                                    val expectedOverlayHash: String? = null)
data class JavaPlatformSourceAssociation(val associationHash: String, val resourceUri: String,
    val providerId: String, val moduleId: String, val sourceSetId: String, val platform: JavaReleasePlatformAuthority,
    val binary: JdtJavaPlatformBinary, val classSha256: String, val sourceArchivePath: Path,
    val sourceArchiveSha256: String, val sourceEntry: String, val sourceSha256: String, val sourceFile: String)
sealed interface JavaPlatformSourceResult {
    data class Available(val association: JavaPlatformSourceAssociation, val content: String? = null,
                         val declaration: JdtJavaSourceDeclaration? = null) : JavaPlatformSourceResult
    data class Unavailable(val code: String, val message: String) : JavaPlatformSourceResult
    data class Refused(val code: String, val message: String) : JavaPlatformSourceResult
    data class Ambiguous(val code: String, val message: String) : JavaPlatformSourceResult
}

/** Application-owned platform queries, independent of dependency-JAR association and budgets. */
class JavaPlatformSources {
    fun read(operation: JavaPlatformSourceOperation, request: JavaPlatformSourceRequest, scan: () -> ProjectSnapshot,
             cancellation: SemanticCancellationToken): JavaBoundReadResult<JavaPlatformSourceResult> =
        JavaBoundContext.read(request.context, scan, cancellation) { context -> resolve(operation, request, context, context.snapshot.workspace.root, cancellation) }

    fun readEditor(session: JavaEditorOverlaySession, operation: JavaPlatformSourceOperation, request: JavaPlatformSourceRequest,
                   scan: () -> ProjectSnapshot, overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay,
                   cancellation: SemanticCancellationToken): JavaEditorReadResult<JavaPlatformSourceResult> =
        session.read(request.context, request.path, requireNotNull(request.expectedOverlayHash), scan, overlayFactory, cancellation) { context, _, staged ->
            resolve(operation, request, context, staged.workspace.root, cancellation)
        }

    private fun resolve(operation: JavaPlatformSourceOperation, request: JavaPlatformSourceRequest, context: JavaBoundContext,
                        semanticRoot: Path, cancellation: SemanticCancellationToken): JavaPlatformSourceResult = try {
        fun unavailable(code: String, message: String): JavaPlatformSourceResult = if (request.expectedAssociationHash == null)
            JavaPlatformSourceResult.Unavailable("java.platformSources.$code", message)
        else JavaPlatformSourceResult.Refused("java.platformSources.associationStale", "Platform source association is no longer available; repeat selection")
        val snapshot = context.snapshot; val path = request.path.normalize()
        val owners = snapshot.owningJavaBuildSourceRoots(path)
        if (owners.size != 1) platformSourceFailure("ownerAmbiguous", "Platform cursor has no unique source-set owner")
        val owner = owners.single()
        val environment = context.view.environments[JavaEffectiveProjectContextProvider.key(owner.providerId, owner.module.id, owner.sourceSet.id)]
            ?: platformSourceFailure("environmentUnavailable", "Owning source-set compile environment is unavailable")
        val apiProfile = JavaSourceSetPlatformContext.profile(owner.sourceSet, context.authority.platforms)
        val platform = context.authority.platforms[apiProfile]
            ?: platformSourceFailure("platformStale", "Owning effective release has no selected SDK authority")
        if (platform.identitySha256 != request.expectedPlatformIdentitySha256) platformSourceFailure("platformStale", "Selected platform identity differs from actual source-set release authority")
        if (platform.release != platform.platformRelease) unavailable("releaseSourceUnqualified", "Historical effective-release sources are not attested by the current SDK source archive")
        else {
            val file = snapshot.files.singleOrNull { it.path.normalize() == path }
                ?: platformSourceFailure("selectionUnavailable", "Platform cursor file is unavailable in the closed snapshot")
            platformSourceCancellation(cancellation)
            val root = snapshot.workspace.root
            val analyzer = JdtJavaSemanticAnalyzer()
            val roots = environment.sourceRoots.map { semanticRoot.resolve(it).normalize() }
            val classpath = environment.compileClasspath.map { root.resolve(it.path).normalize() }
            val candidates = analyzer.platformBinarySelection(file, roots, classpath, platform, request.position, cancellation, environment.sourceLevel)
            val constructors = candidates.filter { it.identity.kind == JdtJavaJvmIdentityKind.CALLABLE && it.identity.memberName == "<init>" }
            val selected = if (constructors.size == 1 && candidates.all { it == constructors.single() ||
                    it.identity.kind == JdtJavaJvmIdentityKind.TYPE && it.identity.ownerBinaryName == constructors.single().identity.ownerBinaryName }) constructors else candidates
            when {
                selected.size > 1 -> JavaPlatformSourceResult.Ambiguous("java.platformSources.binaryOriginAmbiguous", "Cursor has competing exact SDK binary origins")
                selected.isEmpty() -> unavailable("binaryOriginUnavailable", "Cursor has no exact selected-SDK JRT module/class binding")
                !Files.isRegularFile(platform.platformHome.resolve("lib/src.zip")) -> unavailable("sourceArchiveUnavailable", "Selected SDK has no locally materialized standard source archive")
                else -> {
                    val binary = selected.single(); val archives = JavaPlatformSourceArchive()
                    val bytes = archives.classBytes(platform, binary); val classHash = platformSourceHash(bytes)
                    val reader = ToolFactory.createDefaultClassFileReader(ByteArrayInputStream(bytes), IClassFileReader.ALL_BUT_METHOD_BODIES)
                        ?: platformSourceFailure("binaryOriginUnavailable", "Selected SDK classfile cannot be decoded")
                    if (String(reader.className) != binary.identity.ownerBinaryName.replace('.', '/')) platformSourceFailure("binaryOriginUnavailable", "SDK classfile owner differs from its resolved binding")
                    val sourceFile = reader.sourceFileAttribute?.sourceFileName?.let(::String)
                    if (sourceFile == null || !sourceFile.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*\\.java"))) unavailable("sourceIdentityUnavailable", "SDK classfile has no supported SourceFile basename")
                    else {
                        val entry = "${binary.moduleName}/${binary.classEntry.substringBeforeLast('/')}/$sourceFile"
                        val source = archives.source(platform.platformHome, entry, cancellation)
                        if (source == null) unavailable("sourceIdentityUnavailable", "SDK source archive lacks the exact module/SourceFile entry")
                        else {
                            val fields = listOf("java-platform-source-v1", snapshot.hash, context.view.contextHash, owner.providerId,
                                owner.module.id, owner.sourceSet.id, platform.identitySha256, binary.originUri, binary.moduleName,
                                binary.classEntry, classHash, source.path.toString(), source.archiveSha256, entry, source.sourceSha256,
                                binary.identity.kind.name, binary.identity.ownerBinaryName, binary.identity.memberName, binary.identity.descriptor,
                                request.expectedOverlayHash.orEmpty())
                            val hash = platformSourceHash(fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8))
                            if (request.expectedAssociationHash != null && request.expectedAssociationHash != hash) platformSourceFailure("associationStale", "Platform binary/source/frame association changed; repeat selection")
                            val association = JavaPlatformSourceAssociation(hash, "refactorkit-java-platform-source://$hash/$entry",
                                owner.providerId, owner.module.id, owner.sourceSet.id, platform, binary, classHash, source.path,
                                source.archiveSha256, entry, source.sourceSha256, sourceFile)
                            fun current() {
                                platformSourceCancellation(cancellation); context.authority.requireCurrent()
                                if (!archives.isCurrent(platform.platformHome, source) || platformSourceHash(archives.classBytes(platform, binary)) != classHash)
                                    platformSourceFailure("associationStale", "SDK class/source archive bytes or source path changed during query")
                            }
                            val sourceOwnerQualified = analyzer.hasPlatformSourceOwner(SourceFile(Path.of(entry), source.content, "java"), binary.identity, platform.release)
                            val declarations = if (sourceOwnerQualified && operation == JavaPlatformSourceOperation.DECLARATION) analyzer.dependencySourceDeclarations(
                                SourceFile(Path.of(entry), source.content, "java"), roots, classpath, platform, cancellation, binary.moduleName) else null
                            current()
                            when {
                                !sourceOwnerQualified -> unavailable("sourceIdentityUnavailable", "SDK source namespace, owning type or grammar does not qualify this binary")
                                declarations == null -> JavaPlatformSourceResult.Available(association, if (operation == JavaPlatformSourceOperation.READ) source.content else null)
                                declarations.status == JdtJavaSourceDeclarationStatus.AMBIGUOUS -> JavaPlatformSourceResult.Ambiguous("java.platformSources.declarationAmbiguous", "SDK source has duplicate/clashing declarations; no range is qualified")
                                declarations.status == JdtJavaSourceDeclarationStatus.UNAVAILABLE -> unavailable("sourceIdentityUnavailable", "SDK source grammar cannot qualify an exact declaration")
                                binary.identity.ownerBinaryName !in declarations.completeOwners -> unavailable("sourceIdentityUnavailable", "SDK source owner/module binding is incomplete")
                                else -> {
                                    val matches = declarations.declarations.filter { it.identity == binary.identity }
                                    when {
                                        matches.size > 1 -> JavaPlatformSourceResult.Ambiguous("java.platformSources.declarationAmbiguous", "SDK source has multiple exact declaration bindings")
                                        matches.isEmpty() && binary.identity in declarations.implicitIdentities -> unavailable("sourceIdentityUnavailable", "Implicit platform member has no explicit declaration range")
                                        matches.isEmpty() || !binaryDeclares(reader, binary.identity, matches.single().modifiers) ->
                                            JavaPlatformSourceResult.Refused("java.platformSources.declarationMismatch", "SDK source declaration differs from the exact classfile descriptor or checked modifiers")
                                        else -> JavaPlatformSourceResult.Available(association, declaration = matches.single())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (e: JavaPlatformSourceFailure) { JavaPlatformSourceResult.Refused(e.code, e.message.orEmpty()) }
      catch (e: JavaContextAuthorityException) { JavaPlatformSourceResult.Refused(e.code, e.message.orEmpty()) }
      catch (_: JdtJavaAnalysisCancelledException) { JavaPlatformSourceResult.Refused("intelligence.cancelled", "Platform source query was cancelled") }
      catch (_: java.util.zip.ZipException) { JavaPlatformSourceResult.Refused("java.platformSources.archiveUnsafe", "Selected SDK source ZIP is malformed") }
      catch (_: java.nio.charset.CharacterCodingException) { JavaPlatformSourceResult.Refused("java.platformSources.archiveUnsafe", "Selected SDK source ZIP text is not valid UTF-8") }
      catch (_: Exception) { JavaPlatformSourceResult.Refused("java.platformSources.analysisUnavailable", "Selected SDK platform source analysis could not be completed safely") }

    private fun binaryDeclares(reader: IClassFileReader, identity: JdtJavaJvmIdentity, sourceModifiers: Int): Boolean = when (identity.kind) {
        JdtJavaJvmIdentityKind.TYPE -> reader.accessFlags and 0x6200 == sourceModifiers and 0x6200
        JdtJavaJvmIdentityKind.CALLABLE -> reader.methodInfos.any { String(it.name) == identity.memberName && String(it.descriptor) == identity.descriptor && it.accessFlags and 15 == sourceModifiers and 15 }
        JdtJavaJvmIdentityKind.FIELD -> reader.fieldInfos.any { String(it.name) == identity.memberName && String(it.descriptor) == identity.descriptor && it.accessFlags and 15 == sourceModifiers and 15 }
    }
}
