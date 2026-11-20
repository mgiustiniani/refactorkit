package org.refactorkit.java

import java.util.Collections

internal enum class JavaMainLaunchProfile { INHERITED_CLASSIC, JAVA25 }
internal enum class JavaMainParameterShape { NONE, STRING_ARRAY, OTHER }
internal enum class JavaMainInvocation { STATIC, INSTANCE }
internal enum class JavaMainAccess { PUBLIC, PROTECTED, PACKAGE, PRIVATE }
internal enum class JavaLaunchSourceForm { DECLARED, COMPACT }

/** Detached authority; producing it requires independent SDK/launcher input capture. */
internal data class JavaLaunchAuthority(
    val launcherRelease: Int,
    val compilerSdkIdentity: String,
    val launcherSdkIdentity: String,
    val launcherExecutableSha256: String,
)

/** Effective-member and declared-shape facts must come from native bindings, not descriptor erasure. */
internal data class JavaLaunchMethod(
    val declaringBinaryName: String,
    val descriptor: String,
    val parameterShape: JavaMainParameterShape,
    val access: JavaMainAccess,
    val invocation: JavaMainInvocation,
    val returnsVoid: Boolean,
)

internal sealed interface JavaLaunchConstruction {
    data class Eligible(val ownerBinaryName: String, val descriptor: String) : JavaLaunchConstruction
    data object Ineligible : JavaLaunchConstruction
    data object Unavailable : JavaLaunchConstruction
}

/** Complete effective member inventory for one native launch owner. Never retains caller collections. */
internal class JavaNativeLaunchInventory(
    val binaryName: String,
    val sourceRelease: Int,
    val targetRelease: Int,
    val sourceForm: JavaLaunchSourceForm,
    methods: List<JavaLaunchMethod>,
    val construction: JavaLaunchConstruction,
    val complete: Boolean,
    val truncated: Boolean,
) {
    val methods: List<JavaLaunchMethod> = Collections.unmodifiableList(ArrayList(methods))
}

internal enum class JavaMainLaunchRefusal {
    UNQUALIFIED_LAUNCHER, AUTHORITY_UNAVAILABLE, SDK_MISMATCH, UNQUALIFIED_SOURCE,
    INCOMPLETE_NATIVE_INVENTORY, MALFORMED_NATIVE_FACTS, UNQUALIFIED_DECLARATION,
    AMBIGUOUS_MAIN, INSTANCE_CREATION_INELIGIBLE, INSTANCE_CREATION_UNAVAILABLE,
    UNQUALIFIED_CONSTRUCTION,
    CAPTURE_SNAPSHOT_MISMATCH, NATIVE_TYPE_UNAVAILABLE,
}

internal sealed interface JavaMainLaunchSelection {
    data class Selected(
        val binaryName: String,
        val declaringBinaryName: String,
        val descriptor: String,
        val invocation: JavaMainInvocation,
        val inherited: Boolean,
        val constructionDescriptor: String?,
    ) : JavaMainLaunchSelection
    data object Absent : JavaMainLaunchSelection
    data class Refused(val reason: JavaMainLaunchRefusal) : JavaMainLaunchSelection
}

/** Pure policy; native hierarchy/source acquisition and public profile qualification are separate. */
internal object JavaMainLaunchPolicy {
    private const val ARRAY_MAIN = "([Ljava/lang/String;)V"
    private const val NO_ARG_MAIN = "()V"
    private val identityPattern = Regex("[0-9a-f]{64}")

    fun select(profile: JavaMainLaunchProfile, authority: JavaLaunchAuthority,
               inventory: JavaNativeLaunchInventory): JavaMainLaunchSelection {
        fun refuse(reason: JavaMainLaunchRefusal) = JavaMainLaunchSelection.Refused(reason)
        val launcher = authority.launcherRelease
        if (launcher !in setOf(21, 25) || profile == JavaMainLaunchProfile.JAVA25 && launcher != 25)
            return refuse(JavaMainLaunchRefusal.UNQUALIFIED_LAUNCHER)
        if (listOf(authority.compilerSdkIdentity, authority.launcherSdkIdentity, authority.launcherExecutableSha256)
                .any { !identityPattern.matches(it) }) return refuse(JavaMainLaunchRefusal.AUTHORITY_UNAVAILABLE)
        if (authority.compilerSdkIdentity != authority.launcherSdkIdentity)
            return refuse(JavaMainLaunchRefusal.SDK_MISMATCH)
        if (inventory.sourceRelease !in 8..25 || inventory.targetRelease !in inventory.sourceRelease..launcher ||
            inventory.sourceForm == JavaLaunchSourceForm.COMPACT &&
                (inventory.sourceRelease != 25 || inventory.targetRelease != 25))
            return refuse(JavaMainLaunchRefusal.UNQUALIFIED_SOURCE)
        if (!inventory.complete || inventory.truncated)
            return refuse(JavaMainLaunchRefusal.INCOMPLETE_NATIVE_INVENTORY)
        fun boundedOwner(value: String) = value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 1024
        if (!boundedOwner(inventory.binaryName) || inventory.methods.size > 16_384 ||
            inventory.methods.any { !boundedOwner(it.declaringBinaryName) || it.descriptor.isBlank() || it.descriptor.length > 4096 })
            return refuse(JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
        if (inventory.methods.any { method ->
                method.descriptor.endsWith("V") != method.returnsVoid || method.returnsVoid && when (method.parameterShape) {
                    JavaMainParameterShape.NONE -> method.descriptor != NO_ARG_MAIN
                    JavaMainParameterShape.STRING_ARRAY -> method.descriptor != ARRAY_MAIN
                    JavaMainParameterShape.OTHER -> false
                }
            }) return refuse(JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
        val relevant = inventory.methods.filter { method -> when (profile) {
            JavaMainLaunchProfile.INHERITED_CLASSIC -> method.access == JavaMainAccess.PUBLIC && method.invocation == JavaMainInvocation.STATIC
            JavaMainLaunchProfile.JAVA25 -> method.access != JavaMainAccess.PRIVATE
        } }
        if (relevant.any { it.returnsVoid && it.parameterShape == JavaMainParameterShape.OTHER &&
                it.descriptor in setOf(ARRAY_MAIN, NO_ARG_MAIN) })
            return refuse(JavaMainLaunchRefusal.UNQUALIFIED_DECLARATION)
        val candidates = relevant.filter { method -> method.returnsVoid && when (method.parameterShape) {
            JavaMainParameterShape.STRING_ARRAY -> method.descriptor == ARRAY_MAIN
            JavaMainParameterShape.NONE -> profile == JavaMainLaunchProfile.JAVA25 && method.descriptor == NO_ARG_MAIN
            JavaMainParameterShape.OTHER -> false
        } }
        val parameterized = candidates.filter { it.parameterShape == JavaMainParameterShape.STRING_ARRAY }
        val preferred = parameterized.ifEmpty { candidates }
        if (preferred.isEmpty()) return JavaMainLaunchSelection.Absent
        if (preferred.size != 1) return refuse(JavaMainLaunchRefusal.AMBIGUOUS_MAIN)
        val method = preferred.single()
        val constructor = if (method.invocation == JavaMainInvocation.STATIC) null else when (val creation = inventory.construction) {
            JavaLaunchConstruction.Ineligible -> return refuse(JavaMainLaunchRefusal.INSTANCE_CREATION_INELIGIBLE)
            JavaLaunchConstruction.Unavailable -> return refuse(JavaMainLaunchRefusal.INSTANCE_CREATION_UNAVAILABLE)
            is JavaLaunchConstruction.Eligible -> {
                if (creation.ownerBinaryName != inventory.binaryName || creation.descriptor != NO_ARG_MAIN)
                    return refuse(JavaMainLaunchRefusal.UNQUALIFIED_CONSTRUCTION)
                creation.descriptor
            }
        }
        return JavaMainLaunchSelection.Selected(inventory.binaryName, method.declaringBinaryName, method.descriptor,
            method.invocation, method.declaringBinaryName != inventory.binaryName, constructor)
    }
}
