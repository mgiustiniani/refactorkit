package org.refactorkit.java

import org.refactorkit.core.SourceRange
import java.util.Collections
import java.nio.file.Path
import org.refactorkit.core.SemanticProcessProvenance

/** Detached compiler ACL values. No compiler tree or binding escapes the worker. */
enum class JavaNativeLaunchSourceForm { DECLARED, COMPACT }
enum class JavaNativeLaunchClassKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION }
enum class JavaNativeMainParameterShape { NONE, STRING_ARRAY, OTHER }
enum class JavaNativeLaunchFactsRefusal { NOT_CAPTURED, INVALID_RANGE, BINDING_UNAVAILABLE, INPUT_LIMIT, AMBIGUOUS_BINARY_OWNER }
enum class JavaNativeBinaryOriginKind { CLASSPATH, PLATFORM }
enum class JavaNativeOriginRefusal { NATIVE_LOCATION_UNAVAILABLE, UNATTESTED_BINARY_INPUT }

sealed interface JavaNativeDeclarationOrigin {
    data class Source(val path: Path, val range: SourceRange) : JavaNativeDeclarationOrigin
    data class Binary(val kind: JavaNativeBinaryOriginKind, val uri: String, val moduleName: String?,
                      val inputPath: Path, val inputSha256: String) : JavaNativeDeclarationOrigin
    data class Unavailable(val reason: JavaNativeOriginRefusal) : JavaNativeDeclarationOrigin
}

/**
 * Main descriptors are exact JVM erasures. Constructor descriptors describe declared
 * language-model parameters; synthetic outer/capture parameters are not manufactured.
 * Construction qualification must consume native class/nesting flags as well.
 */
data class JavaNativeLaunchMethodFacts(
    val declaringBinaryName: String,
    val descriptor: String,
    val modifiers: Int,
    val parameterShape: JavaNativeMainParameterShape,
    val parameterCount: Int,
    val returnsVoid: Boolean,
    val constructor: Boolean,
    val defaultConstructor: Boolean,
    val varargs: Boolean,
    val origin: JavaNativeDeclarationOrigin = JavaNativeDeclarationOrigin.Unavailable(JavaNativeOriginRefusal.NATIVE_LOCATION_UNAVAILABLE),
)

class JavaNativeLaunchClassFacts(
    val binaryName: String,
    val packageName: String,
    val kind: JavaNativeLaunchClassKind,
    val modifiers: Int,
    val sourceOwned: Boolean,
    val local: Boolean,
    val member: Boolean,
    val anonymous: Boolean,
    val superclassBinaryName: String?,
    interfaceBinaryNames: List<String>,
    methods: List<JavaNativeLaunchMethodFacts>,
) {
    val interfaceBinaryNames: List<String> = Collections.unmodifiableList(ArrayList(interfaceBinaryNames))
    val methods: List<JavaNativeLaunchMethodFacts> = Collections.unmodifiableList(ArrayList(methods))
}

class JavaNativeLaunchGraph(val rootBinaryName: String, nodes: Map<String, JavaNativeLaunchClassFacts>,
                           effectiveMainMethods: List<JavaNativeLaunchMethodFacts> = emptyList(),
                           val effectiveMemberInventoryComplete: Boolean = false) {
    val nodes: Map<String, JavaNativeLaunchClassFacts> = Collections.unmodifiableMap(LinkedHashMap(nodes))
    val effectiveMainMethods: List<JavaNativeLaunchMethodFacts> = Collections.unmodifiableList(ArrayList(effectiveMainMethods))
}

sealed interface JavaNativeLaunchTypeReadout {
    data class Available(val range: SourceRange, val sourceForm: JavaNativeLaunchSourceForm,
                         val graph: JavaNativeLaunchGraph) : JavaNativeLaunchTypeReadout
    data class Unavailable(val reason: JavaNativeLaunchFactsRefusal) : JavaNativeLaunchTypeReadout
}

internal class JavaCompilerLaunchSource(val path: Path, types: List<JavaNativeLaunchTypeReadout.Available>) {
    val types: List<JavaNativeLaunchTypeReadout.Available> = Collections.unmodifiableList(ArrayList(types))
}

internal sealed interface JavaCompilerLaunchResolution {
    class Available(sources: List<JavaCompilerLaunchSource>, val platformIdentity: String,
                    val launcherSha256: String, val compilerSha256: String,
                    val provenance: SemanticProcessProvenance,
                    val captureContext: JavaNativeLaunchCaptureContext,
                    classpathInputs: Map<Path, String> = emptyMap()) : JavaCompilerLaunchResolution {
        val sources: List<JavaCompilerLaunchSource> = Collections.unmodifiableList(ArrayList(sources))
        val classpathInputs: Map<Path, String> = Collections.unmodifiableMap(LinkedHashMap(classpathInputs))
    }
    data class Refused(val code: String, val message: String) : JavaCompilerLaunchResolution
}

/** Captured by the acquisition service, never inferred from the engine JVM. */
internal data class JavaNativeLaunchCaptureContext(
    val snapshotHash: String,
    val sourceRelease: Int,
    val targetRelease: Int,
    val launcherRelease: Int,
    val workerSha256: String,
)
