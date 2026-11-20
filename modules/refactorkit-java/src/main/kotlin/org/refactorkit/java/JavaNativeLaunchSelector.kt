package org.refactorkit.java

import java.nio.file.Path
import java.lang.reflect.Modifier

/** Pure boundary between complete native acquisition and the launcher-selection domain. */
internal object JavaNativeLaunchSelector {
    fun select(profile: JavaMainLaunchProfile, capture: JavaCompilerLaunchResolution,
               expectedSnapshotHash: String, expectedPlatformIdentity: String,
               path: Path, binaryName: String): JavaMainLaunchSelection {
        fun refused(reason: JavaMainLaunchRefusal) = JavaMainLaunchSelection.Refused(reason)
        if (capture !is JavaCompilerLaunchResolution.Available)
            return refused(JavaMainLaunchRefusal.INCOMPLETE_NATIVE_INVENTORY)
        val context = capture.captureContext
        val hashes = listOf(expectedSnapshotHash, expectedPlatformIdentity, context.snapshotHash, context.workerSha256,
            capture.platformIdentity, capture.launcherSha256, capture.compilerSha256,
            capture.provenance.executableSha256, capture.provenance.argumentsSha256)
        if (hashes.any { !SHA256.matches(it) } || capture.provenance.executableSha256 != capture.launcherSha256)
            return refused(JavaMainLaunchRefusal.AUTHORITY_UNAVAILABLE)
        if (context.snapshotHash != expectedSnapshotHash) return refused(JavaMainLaunchRefusal.CAPTURE_SNAPSHOT_MISMATCH)
        if (capture.platformIdentity != expectedPlatformIdentity) return refused(JavaMainLaunchRefusal.SDK_MISMATCH)
        // The first native acquisition contract explicitly binds --release25 to SDK25.
        if (context.launcherRelease != 25) return refused(JavaMainLaunchRefusal.UNQUALIFIED_LAUNCHER)
        if (context.sourceRelease != 25 || context.targetRelease != 25) return refused(JavaMainLaunchRefusal.UNQUALIFIED_SOURCE)
        val normalized = path.normalize()
        if (!normalized.isAbsolute && normalized.startsWith("..")) return refused(JavaMainLaunchRefusal.NATIVE_TYPE_UNAVAILABLE)
        val source = capture.sources.singleOrNull { it.path.normalize() == normalized }
            ?: return refused(JavaMainLaunchRefusal.NATIVE_TYPE_UNAVAILABLE)
        val readout = source.types.singleOrNull { it.graph.rootBinaryName == binaryName }
            ?: return refused(JavaMainLaunchRefusal.NATIVE_TYPE_UNAVAILABLE)
        val graph = readout.graph
        if (!graph.effectiveMemberInventoryComplete) return refused(JavaMainLaunchRefusal.INCOMPLETE_NATIVE_INVENTORY)
        if (!valid(graph)) return refused(JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
        val root = graph.nodes.getValue(binaryName)
        if (!root.sourceOwned || readout.sourceForm == JavaNativeLaunchSourceForm.COMPACT &&
            (root.kind != JavaNativeLaunchClassKind.CLASS || root.local || root.member || root.anonymous))
            return refused(JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
        val methods = graph.effectiveMainMethods.map { method -> JavaLaunchMethod(
            method.declaringBinaryName, method.descriptor, when (method.parameterShape) {
                JavaNativeMainParameterShape.NONE -> JavaMainParameterShape.NONE
                JavaNativeMainParameterShape.STRING_ARRAY -> JavaMainParameterShape.STRING_ARRAY
                JavaNativeMainParameterShape.OTHER -> JavaMainParameterShape.OTHER
            }, access(method.modifiers), if (Modifier.isStatic(method.modifiers)) JavaMainInvocation.STATIC else JavaMainInvocation.INSTANCE,
            method.returnsVoid,
        ) }
        val inventory = JavaNativeLaunchInventory(binaryName, context.sourceRelease, context.targetRelease,
            if (readout.sourceForm == JavaNativeLaunchSourceForm.COMPACT) JavaLaunchSourceForm.COMPACT else JavaLaunchSourceForm.DECLARED,
            methods, construction(root), complete = true, truncated = false)
        return JavaMainLaunchPolicy.select(profile, JavaLaunchAuthority(context.launcherRelease, capture.platformIdentity,
            capture.platformIdentity, capture.launcherSha256), inventory)
    }

    private fun construction(root: JavaNativeLaunchClassFacts): JavaLaunchConstruction {
        if (root.kind !in setOf(JavaNativeLaunchClassKind.CLASS, JavaNativeLaunchClassKind.RECORD) ||
            Modifier.isAbstract(root.modifiers) || root.member && !Modifier.isStatic(root.modifiers))
            return JavaLaunchConstruction.Ineligible
        if (root.local || root.anonymous) return JavaLaunchConstruction.Unavailable
        val constructors = root.methods.filter { it.constructor }
        if (constructors.isEmpty()) return JavaLaunchConstruction.Unavailable
        val zero = constructors.singleOrNull { it.parameterCount == 0 && it.descriptor == "()V" }
            ?: return JavaLaunchConstruction.Ineligible
        if (Modifier.isPrivate(zero.modifiers)) return JavaLaunchConstruction.Ineligible
        return JavaLaunchConstruction.Eligible(root.binaryName, zero.descriptor)
    }

    private fun access(modifiers: Int) = when {
        Modifier.isPublic(modifiers) -> JavaMainAccess.PUBLIC
        Modifier.isProtected(modifiers) -> JavaMainAccess.PROTECTED
        Modifier.isPrivate(modifiers) -> JavaMainAccess.PRIVATE
        else -> JavaMainAccess.PACKAGE
    }

    /** Validate provenance relationships, not inheritance/member selection already decided by javac. */
    private fun valid(graph: JavaNativeLaunchGraph): Boolean {
        val nodes = graph.nodes
        if (nodes.size !in 1..4096 || graph.rootBinaryName !in nodes || graph.effectiveMainMethods.size > 16384) return false
        var edges = 0; var members = 0
        for ((binary, node) in nodes) {
            if (binary != node.binaryName || !owner(binary) || !accessConsistent(node.modifiers)) return false
            val parents = listOfNotNull(node.superclassBinaryName) + node.interfaceBinaryNames
            edges += parents.size; members += node.methods.size
            if (edges > 32768 || members > 65536 || parents.distinct().size != parents.size || parents.any { it !in nodes }) return false
            if (node.methods.distinct().size != node.methods.size || node.methods.any {
                    it.declaringBinaryName != binary || !validMethod(it)
                }) return false
        }
        // The graph must be exactly the root's closed, acyclic native hierarchy.
        val reachable = linkedSetOf<String>(); val pending = ArrayDeque<String>(); pending.add(graph.rootBinaryName)
        while (pending.isNotEmpty()) {
            val binary = pending.removeFirst()
            if (!reachable.add(binary)) continue
            val node = nodes.getValue(binary)
            node.superclassBinaryName?.let(pending::addLast); node.interfaceBinaryNames.forEach(pending::addLast)
        }
        if (reachable.size != nodes.size) return false
        val incoming = nodes.keys.associateWith { 0 }.toMutableMap()
        for (node in nodes.values) for (parent in listOfNotNull(node.superclassBinaryName) + node.interfaceBinaryNames)
            incoming[parent] = incoming.getValue(parent) + 1
        pending.addAll(incoming.filterValues { it == 0 }.keys)
        var visited = 0
        while (pending.isNotEmpty()) {
            val node = nodes.getValue(pending.removeFirst()); visited++
            for (parent in listOfNotNull(node.superclassBinaryName) + node.interfaceBinaryNames) {
                incoming[parent] = incoming.getValue(parent) - 1
                if (incoming.getValue(parent) == 0) pending.addLast(parent)
            }
        }
        if (visited != nodes.size) return false
        val effective = graph.effectiveMainMethods
        if (effective.distinct().size != effective.size || effective.any { method ->
                method.constructor || !validMethod(method) || method !in (nodes[method.declaringBinaryName]?.methods ?: emptyList())
            }) return false
        // A complete getAllMembers inventory cannot lose a method declared on its own root.
        return nodes.getValue(graph.rootBinaryName).methods.filterNot { it.constructor }.all { it in effective }
    }

    private fun validMethod(method: JavaNativeLaunchMethodFacts): Boolean {
        if (!owner(method.declaringBinaryName) || !accessConsistent(method.modifiers) || method.parameterCount !in 0..255) return false
        val descriptor = descriptorShape(method.descriptor) ?: return false
        if (descriptor.first != method.parameterCount || descriptor.second != method.returnsVoid) return false
        if (method.constructor && !method.returnsVoid || method.defaultConstructor && (!method.constructor || method.parameterCount != 0) ||
            method.varargs && method.parameterCount == 0) return false
        return when (method.parameterShape) {
            JavaNativeMainParameterShape.NONE -> method.parameterCount == 0
            JavaNativeMainParameterShape.STRING_ARRAY -> method.parameterCount == 1 && method.descriptor.startsWith("([Ljava/lang/String;)")
            JavaNativeMainParameterShape.OTHER -> method.parameterCount > 0
        }
    }

    /** JVM descriptor syntax/arity only; it never substitutes for a native declared parameter type. */
    private fun descriptorShape(value: String): Pair<Int, Boolean>? {
        if (value.length !in 3..4096 || value[0] != '(') return null
        var at = 1
        fun type(allowVoid: Boolean): Boolean {
            var dimensions = 0
            while (at < value.length && value[at] == '[') { dimensions++; at++ }
            if (dimensions > 255 || at >= value.length) return false
            return when (value[at++]) {
                'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> true
                'V' -> allowVoid && dimensions == 0
                'L' -> {
                    val start = at; val end = value.indexOf(';', at)
                    if (end <= start) return false
                    val name = value.substring(start, end)
                    if (name.any { it in ".;[()\u0000" } || name.split('/').any { it.isEmpty() }) return false
                    at = end + 1; true
                }
                else -> false
            }
        }
        var count = 0
        while (at < value.length && value[at] != ')') {
            if (!type(false) || ++count > 255) return null
        }
        if (at >= value.length || value[at++] != ')') return null
        val result = at
        if (!type(true) || at != value.length) return null
        return count to (value[result] == 'V')
    }

    private fun owner(value: String) = value.isNotBlank() && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= 1024
    private fun accessConsistent(modifiers: Int) = Integer.bitCount(modifiers and 7) <= 1
    private val SHA256 = Regex("[0-9a-f]{64}")
}
