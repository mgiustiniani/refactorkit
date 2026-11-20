package org.refactorkit.jvm

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.Symbol
import org.refactorkit.core.owningBuildSourceRoots
import org.refactorkit.java.JavaBoundContext
import org.refactorkit.java.JavaEffectiveProjectContextProvider
import org.refactorkit.java.JdtJavaJvmIdentity
import org.refactorkit.java.JdtJavaJvmIdentityKind
import org.refactorkit.kotlin.KotlinCompilerDeclarationEvidence
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainRequest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipFile

sealed interface GradleKotlinDefinition {
    data class Available(val location: SourceLocation, val provenanceHash: String) : GradleKotlinDefinition
    data class Refused(val code: String, val message: String) : GradleKotlinDefinition
}

/** Read-only join from a bound Java cursor to one owned, byte-attested Kotlin JVM declaration. */
object GradleKotlinDefinitionService {
    private const val PROVIDER = "gradle-effective-v1"
    private const val MAX_CLASS_BYTES = 2 * 1024 * 1024
    private const val MAX_CLASSPATH = 128
    private val BINARY_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

    fun resolve(
        context: JavaBoundContext,
        cursor: SourceLocation,
        toolchainRequest: KotlinToolchainRequest,
        cancellation: SemanticCancellationToken,
    ): GradleKotlinDefinition {
        if (cancellation.isCancellationRequested()) return cancelled()
        return try {
            resolveBound(context, cursor, toolchainRequest, cancellation)
        } catch (_: java.io.IOException) {
            unqualified("Kotlin binary or compiler evidence could not be read safely")
        } catch (_: IllegalArgumentException) {
            unqualified("Kotlin binary or compiler evidence was invalid")
        }
    }

    private fun resolveBound(
        context: JavaBoundContext,
        cursor: SourceLocation,
        toolchainRequest: KotlinToolchainRequest,
        cancellation: SemanticCancellationToken,
    ): GradleKotlinDefinition {
        val snapshot = context.snapshot
        val root = snapshot.workspace.root
        val point = cursor.range.start
        val identities = context.view.analysis?.analysis?.bindingUses.orEmpty().asSequence()
            .filter { use -> use.path == cursor.path && use.isKotlinBinaryOwner && !use.recovered &&
                point >= use.sourceRange.start && point < use.sourceRange.end }
            .mapNotNull { it.jvmIdentity }.distinct().toList()
        val identity = identities.singleOrNull()?.takeIf { BINARY_NAME.matches(it.ownerBinaryName) }
            ?: return unqualified("The Java cursor has no unique Kotlin JVM binding")
        val classEntry = identity.ownerBinaryName.replace('.', '/') + ".class"
        val owner = snapshot.owningBuildSourceRoots(cursor.path).singleOrNull { it.providerId == PROVIDER }
            ?: return unqualified("The Java cursor has no evaluated Gradle source-set owner")
        val model = snapshot.buildModels.singleOrNull { it.providerId == PROVIDER && it.status == BuildModelStatus.AVAILABLE }
            ?: return unqualified("The evaluated Gradle build model is unavailable")
        val sourceSet = owner.sourceSet
        val key = JavaEffectiveProjectContextProvider.key(owner.providerId, owner.module.id, sourceSet.id)
        val environment = context.view.environments[key]
            ?: return unqualified("The Java cursor has no bound compiler classpath")
        val entries = environment.classpathEntries.distinct()
        if (entries.size > MAX_CLASSPATH) return unqualified("The Java classpath exceeds the bounded Kotlin origin check")
        val boundClasses = entries.map(root::resolve).map(Path::normalize).distinct().filter { entry ->
            hasClass(entry, classEntry)
        }
        if (boundClasses.size > 1) return GradleKotlinDefinition.Refused(
            "java.definitionKotlinBinaryAmbiguous", "More than one Java classpath entry contains the bound Kotlin class",
        )
        val attrs = sourceSet.attributes
        val relativeOutput = attrs["kotlin.outputDirectory"]?.let(Path::of)
            ?: return unqualified("The evaluated source set has no owned Kotlin compiler output")
        val boundRoot = boundClasses.singleOrNull()?.takeIf { it == root.resolve(relativeOutput).normalize() }
            ?: return unqualified("The bound Kotlin class does not come from the owned Gradle output directory")
        val originalClass = boundRoot.resolve(classEntry)
        val originalBytes = classBytes(originalClass)
        val facet = sourceSet.languageFacets.singleOrNull { it.languageId == "kotlin" }
        if (attrs["kotlin.compilerEvidenceAvailability"] != "complete" || facet?.platformId != "jvm" ||
            attrs["kotlin.freeCompilerArgumentsPresent"] != "false" ||
            facet.sourceVersion !in setOf(null, "2.0") || attrs["kotlin.apiVersion"] !in setOf(null, "2.0") ||
            sourceSet.generatedSourceRoots.isNotEmpty() && owner.generated
        ) return unqualified("The evaluated Kotlin compiler inputs are outside the qualified source-mapping scope")
        val moduleName = attrs["kotlin.moduleName"]?.takeIf { it.isNotBlank() }
            ?: return unqualified("The Kotlin compiler module name is unavailable")
        val count = attrs["kotlin.sourceRoot.count"]?.toIntOrNull()?.takeIf { it in 1..64 }
            ?: return unqualified("The evaluated Kotlin source roots are unavailable")
        val roots = (0 until count).map { i ->
            val path = attrs["kotlin.sourceRoot.$i"]?.let(Path::of)
                ?: return unqualified("The evaluated Kotlin source roots are incomplete")
            if (path.isAbsolute || path.normalize().startsWith("..")) {
                return unqualified("The evaluated Kotlin source root is outside the workspace")
            }
            path.normalize()
        }
        val toolchain = when (val discovered = KotlinToolchainDiscoverer().discover(toolchainRequest)) {
            is KotlinToolchainDiscovery.Available -> discovered.toolchain
            is KotlinToolchainDiscovery.Refused -> return unqualified("The supplied Kotlin compiler toolchain could not be qualified")
        }
        if (facet.targetRuntimeVersion != toolchain.provenance.javaVersion.substringBefore('.') ||
            context.view.sdk.none { it.platformHome == toolchain.jdkHome }) {
            return unqualified("The Kotlin compiler JDK differs from the bound Gradle and Java SDK evidence")
        }
        val compilerSet = sourceSet.copy(
            sourceRoots = roots, generatedSourceRoots = emptyList(), sourceRootEvidence = emptyList(),
            classpathEntries = entries.filterNot { root.resolve(it).normalize() == boundRoot }, moduleDependencies = emptyList(),
        )
        val javaSources = snapshot.files.filter { it.languageId == "java" && environment.sourceRoots.any(it.path::startsWith) }
        val kotlinSources = snapshot.files.filter { it.languageId == "kotlin" && roots.any(it.path::startsWith) }
        if (kotlinSources.isEmpty()) return unqualified("The owned Gradle source set has no saved Kotlin declarations")
        val javaModule = Module(owner.module.id, root.resolve(owner.module.root), sourceSet.sourceRoots, entries,
            languageSettings = mapOf("java.sourceLevel" to environment.sourceLevel.toString()))
        val scoped = snapshot.copy(
            files = javaSources + kotlinSources, modules = listOf(javaModule),
            buildModels = listOf(model.copy(modules = listOf(owner.module.copy(sourceSets = listOf(compilerSet))))),
        )
        val kotlinSnapshot = KotlinJvmBuildModelIntegration.attach(scoped, toolchain)
        var analysis: KotlinCompilerDiagnosticsResult? = null
        var emitted: ByteArray? = null
        if (cancellation.isCancellationRequested()) return cancelled()
        val javaCompilation = JavaEphemeralCompiler().compile(scoped) { javaOutput ->
            if (!cancellation.isCancellationRequested()) {
                analysis = KotlinCompilerDiagnostics(toolchain).analyzeWithAdditionalClasspathAndCompiledOutput(
                    kotlinSnapshot, listOf(javaOutput), moduleName,
                ) { output -> emitted = classBytes(output.resolve(classEntry)) }
            }
        }
        if (cancellation.isCancellationRequested()) return cancelled()
        val javaEvidence = when (javaCompilation) {
            is JavaEphemeralCompilationResult.Available -> javaCompilation
            is JavaEphemeralCompilationResult.Refused ->
                return unqualified("Independent Java compilation evidence was refused (${javaCompilation.code})")
        }
        val kotlin = when (val captured = analysis) {
            is KotlinCompilerDiagnosticsResult.Available -> captured
            is KotlinCompilerDiagnosticsResult.Refused ->
                return unqualified("Independent Kotlin compilation evidence was refused (${captured.reason.code})")
            is KotlinCompilerDiagnosticsResult.Error ->
                return unqualified("Independent Kotlin compilation failed (${captured.failure.code})")
            null -> return unqualified("Independent Kotlin compilation evidence is missing")
        }
        val compileError = kotlin.diagnostics.firstOrNull { it.severity == Diagnostic.Severity.ERROR }
        val diagnostic = compileError ?: kotlin.symbolFailure
        if (diagnostic != null) return unqualified("Independent Kotlin declaration evidence failed (${diagnostic.code})")
        if (kotlin.attestation.snapshotHash != kotlinSnapshot.hash || kotlin.attestation.process == null || emitted == null) {
            return unqualified("Independent Kotlin compilation returned no snapshot-bound class output")
        }
        if (!originalBytes.contentEquals(emitted) || !originalBytes.contentEquals(classBytes(originalClass))) {
            return GradleKotlinDefinition.Refused("java.definitionKotlinBinaryDrift",
                "The bound Gradle Kotlin class differs from a fresh compilation of the saved Kotlin sources")
        }
        val location = kotlin.symbols?.symbols.orEmpty().filter { symbol ->
            kotlin.declarations[symbol.id]?.matches(identity, symbol.kind) == true
        }.singleOrNull()?.location ?: return unqualified("The attested Kotlin binary has no unique exact source declaration")
        if (kotlinSources.none { it.path == location.path }) return unqualified("The Kotlin declaration is outside the saved source scope")
        val provenanceHash = sha256(listOf(context.view.contextHash, snapshot.hash, kotlinSnapshot.hash,
            javaEvidence.outputHash, kotlin.attestation.toolchainProjectionHash, kotlin.attestation.buildProjectionHash,
            identity.ownerBinaryName, identity.memberName, identity.descriptor, sha256(originalBytes),
            location.path.toString(), location.range.toString()).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        return GradleKotlinDefinition.Available(location, provenanceHash)
    }

    private fun hasClass(entry: Path, classEntry: String): Boolean {
        if (!Files.exists(entry, NOFOLLOW_LINKS)) return false
        require(!Files.isSymbolicLink(entry))
        if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
            return Files.exists(entry.resolve(classEntry), NOFOLLOW_LINKS)
        }
        require(Files.isRegularFile(entry, NOFOLLOW_LINKS) && entry.fileName.toString().endsWith(".jar", ignoreCase = true))
        return JarFile(entry.toFile(), false, ZipFile.OPEN_READ, Runtime.Version.parse("21")).use { jar ->
            jar.getJarEntry(classEntry) != null
        }
    }

    private fun classBytes(path: Path): ByteArray {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path)
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { stream ->
            stream.readNBytes(MAX_CLASS_BYTES + 1).also { require(it.isNotEmpty() && it.size <= MAX_CLASS_BYTES) }
        }
    }

    private fun KotlinCompilerDeclarationEvidence.matches(identity: JdtJavaJvmIdentity, kind: Symbol.Kind): Boolean {
        if (jvmOwner != identity.ownerBinaryName) return false
        return when (identity.kind) {
            JdtJavaJvmIdentityKind.TYPE -> kind in setOf(Symbol.Kind.CLASS, Symbol.Kind.OBJECT, Symbol.Kind.INTERFACE,
                Symbol.Kind.ENUM, Symbol.Kind.ANNOTATION) && identity.descriptor == "L${jvmOwner.replace('.', '/')};"
            JdtJavaJvmIdentityKind.CALLABLE -> kind in setOf(Symbol.Kind.FUNCTION, Symbol.Kind.CONSTRUCTOR) &&
                jvmName == identity.memberName && jvmDescriptor == identity.descriptor
            JdtJavaJvmIdentityKind.FIELD -> kind == Symbol.Kind.PROPERTY &&
                jvmName == identity.memberName && jvmDescriptor == identity.descriptor
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    private fun unqualified(message: String) = GradleKotlinDefinition.Refused("java.definitionKotlinSourceUnqualified", message)

    private fun cancelled() = GradleKotlinDefinition.Refused("intelligence.cancelled", "Interactive semantic query was cancelled")
}
