package org.refactorkit.java

import org.refactorkit.core.*
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections

internal sealed interface JavaNativeEntryPointContextResolution {
    class Available(val context: JavaBoundContext, val scopeSnapshot: ProjectSnapshot,
                    selectedSources: List<SourceFile>, val capture: JavaCompilerLaunchResolution.Available) : JavaNativeEntryPointContextResolution {
        val selectedSources: List<SourceFile> = Collections.unmodifiableList(ArrayList(selectedSources))
    }
    data class Refused(val code: String, val message: String) : JavaNativeEntryPointContextResolution
}

/** Application boundary: provider context -> exact compilation closure -> native acquisition. */
internal object JavaNativeEntryPointContext {
    fun read(request: JavaProjectContextRequest, scope: JavaEntryPointScope, scan: () -> ProjectSnapshot,
             cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE): JavaNativeEntryPointContextResolution {
        val selected = request.copy(providerId = scope.providerId, moduleId = scope.moduleId, sourceSetId = scope.sourceSetId,
            requiredFacts = request.requiredFacts + JavaProjectContextFact.COMPILER_OPTIONS)
        return try {
            when (val read = JavaBoundContext.read(selected, scan, cancellation) { context -> capture(context, scope, cancellation) }) {
                is JavaBoundReadResult.Refused -> JavaNativeEntryPointContextResolution.Refused(read.code, read.message)
                is JavaBoundReadResult.Available -> read.value
            }
        } catch (failure: JavaContextAuthorityException) {
            JavaNativeEntryPointContextResolution.Refused(failure.code, failure.message.orEmpty())
        } catch (_: Exception) {
            JavaNativeEntryPointContextResolution.Refused("java.entryPoints.contextUnavailable", "Native provider context could not be acquired")
        }
    }

    private fun capture(context: JavaBoundContext, scope: JavaEntryPointScope,
                        cancellation: SemanticCancellationToken): JavaNativeEntryPointContextResolution {
        JavaEntryPointDiscovery.current(cancellation)
        val snapshot = context.snapshot
        val model = snapshot.buildModels.singleOrNull { it.providerId == scope.providerId }
            ?: fail("scopeUnavailable", "The selected provider is absent or ambiguous")
        val module = model.modules.singleOrNull { it.id == scope.moduleId }
            ?: fail("scopeUnavailable", "The selected module is absent or ambiguous")
        val set = module.sourceSets.singleOrNull { it.id == scope.sourceSetId }
            ?: fail("scopeUnavailable", "The selected source set is absent or ambiguous")
        val environment = context.view.environments[JavaEffectiveProjectContextProvider.key(scope.providerId, scope.moduleId, scope.sourceSetId)]
            ?: fail("scopeUnavailable", "The provider compiler environment is unavailable")
        val platform = context.view.sdk.singleOrNull { it.release == 25 && it.platformRelease == 25 }
            ?: fail("launcherUnavailable", "The native acquisition cell requires the selected SDK25 platform")
        if (environment.sourceLevel != 25 || environment.jpms.availability != BuildFactAvailability.NOT_DECLARED)
            fail("scopeSourceUnqualified", "Native acquisition requires a homogeneous non-JPMS source25/target25 closure")
        fun qualify(sourceSet: BuildSourceSet) {
            if (sourceSet.attributes["java.release"] != "25")
                fail("scopeSourceUnqualified", "A compile-closure source set is outside the qualified release25 cell")
            if (sourceSet.compilerOptionsAvailability != BuildFactAvailability.COMPLETE || sourceSet.compilerOptions.any {
                    it.redacted || it.name in setOf("compilerArgFile", "compilerArgs", "compilerArgument", "compilerArguments",
                        "bootclasspath", "system", "enablePreview", "executable") ||
                        it.name == "compilerId" && it.value != "javac" || it.name == "fork" && it.value != "false"
                }) fail("compilerOptionsUnqualified", "The compiler environment contains unqualified or unavailable options")
        }
        qualify(set)
        val roots = environment.sourceRoots.map { it.normalize() }.distinct()
        if (roots.any { it.isAbsolute || it.startsWith("..") }) fail("scopeUnavailable", "Compiler source roots escape the workspace")
        fun under(path: Path, root: Path) = root.toString().isEmpty() || path.normalize().startsWith(root)
        val closure = snapshot.files.filter { it.languageId == "java" && roots.any { root -> under(it.path, root) } }
        if (closure.isEmpty()) fail("analysisUnavailable", "No captured Java compilation input is available")
        val selectedSources = mutableListOf<SourceFile>()
        for (file in closure) {
            JavaEntryPointDiscovery.current(cancellation)
            val owner = snapshot.owningJavaBuildSourceRoots(file.path).singleOrNull()
                ?: fail("ownerAmbiguous", "A compilation input has no unique provider source owner")
            if (owner.providerId != scope.providerId) fail("ownerAmbiguous", "A compilation input belongs to another provider")
            qualify(owner.sourceSet)
            if (file.path.fileName.toString() == "module-info.java")
                fail("scopeSourceUnqualified", "JPMS compilation inputs are not yet qualified for this native cell")
            if (owner.module.id == module.id && owner.sourceSet.id == set.id) selectedSources.add(file)
        }
        val workspace = snapshot.workspace.root.toRealPath()
        fun absolute(path: Path) = (if (path.isAbsolute) path else workspace.resolve(path)).normalize()
        val outputs = model.modules.flatMap { owner -> owner.sourceSets.flatMap { sourceSet ->
            sourceSet.outputDirectories.map { absolute(it) to sourceSet }
        } }
        val classpath = environment.classpathEntries.distinct().mapNotNull { path ->
            val input = absolute(path)
            if (Files.exists(input, LinkOption.NOFOLLOW_LINKS)) input else {
                val sourceOwner = outputs.filter { it.first == input }.map { it.second }.singleOrNull()
                    ?: fail("classpathUnavailable", "A required compile-classpath input is missing")
                val sourceRoots = sourceOwner.sourceRoots.map { it.normalize() }
                if (sourceRoots.isEmpty() || sourceRoots.any { it !in roots })
                    fail("classpathUnavailable", "A missing output has no complete provider-owned source representation")
                qualify(sourceOwner)
                null // Only a proven project output represented by this exact source closure is redundant.
            }
        }
        // Native compilation has its own identity. Preserve the full global/index context alongside it.
        val nativeModule = Module("native:${scope.moduleId}:${scope.sourceSetId}", module.root, sourceRoots = roots,
            classpathEntries = classpath, languageSettings = mapOf("java.release" to "25", "java.sourceLevel" to "25", "java.targetLevel" to "25"))
        val scoped = snapshot.copy(modules = listOf(nativeModule), files = closure, sourceExtensions = setOf("java"),
            semanticAnalysisAuthority = null)
        return when (val captured = JavaNativeLaunchCompiler().capture(scoped, platform, classpath, cancellation)) {
            is JavaCompilerLaunchResolution.Refused -> JavaNativeEntryPointContextResolution.Refused(captured.code, captured.message)
            is JavaCompilerLaunchResolution.Available -> JavaNativeEntryPointContextResolution.Available(context, scoped, selectedSources, captured)
        }
    }

    private fun fail(code: String, message: String): Nothing = throw JavaContextAuthorityException("java.entryPoints.$code", message)
}
