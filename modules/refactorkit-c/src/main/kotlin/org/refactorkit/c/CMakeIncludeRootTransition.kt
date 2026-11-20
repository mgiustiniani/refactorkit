package org.refactorkit.c

import java.nio.file.Path
import java.util.Collections

internal enum class CMakeIncludeRootRefusal {
    INPUT_LIMIT, PATH_UNAVAILABLE, SOURCE_SET_MISMATCH, OWNER_UNAVAILABLE,
    INCLUDE_ROOT_MISMATCH, ROOT_TRANSITION_MISMATCH,
}

internal sealed interface CMakeIncludeRootReadout {
    data class Found(val facts: CMakeIncludeRootFacts) : CMakeIncludeRootReadout
    data class Refused(val reason: CMakeIncludeRootRefusal) : CMakeIncludeRootReadout
}

internal class CMakeIncludeRootFacts(
    before: Map<Path, List<Path>>,
    after: Map<Path, List<Path>>,
    owners: Map<Path, CMakeLiteralTargetOwner>,
) {
    val before: Map<Path, List<Path>> = freeze(before)
    val after: Map<Path, List<Path>> = freeze(after)
    val owners: Map<Path, CMakeLiteralTargetOwner> = Collections.unmodifiableMap(LinkedHashMap(owners))

    private fun freeze(values: Map<Path, List<Path>>): Map<Path, List<Path>> =
        Collections.unmodifiableMap(values.mapValues { (_, paths) -> Collections.unmodifiableList(ArrayList(paths)) })
}

/** Pure flag-origin proof only. Filesystem/manifest capture and native old/staged validation remain application responsibilities. */
internal object CMakeIncludeRootTransition {
    fun analyze(
        capturedTranslationUnits: Set<Path>,
        capturedIncludes: Map<Path, List<Path>>,
        owners: Map<Path, List<CMakeLiteralTargetOwner>>,
        beforeRoots: List<CMakeLiteralIncludeRoot>,
        afterRoots: List<CMakeLiteralIncludeRoot>,
        movedSources: Map<Path, Path>,
        movedRoots: Map<Path, Path>,
    ): CMakeIncludeRootReadout {
        fun no(reason: CMakeIncludeRootRefusal) = CMakeIncludeRootReadout.Refused(reason)
        if (capturedTranslationUnits.isEmpty() || capturedTranslationUnits.size > 16 ||
            capturedIncludes.size > 16 || owners.size > 16 || beforeRoots.size > 64 || afterRoots.size > 64 ||
            movedSources.size > 16 || movedRoots.size > 64 || capturedIncludes.values.any { it.size > 64 }) {
            return no(CMakeIncludeRootRefusal.INPUT_LIMIT)
        }
        val allPaths = capturedTranslationUnits + capturedIncludes.keys + capturedIncludes.values.flatten() + owners.keys +
            beforeRoots.map { it.path } + afterRoots.map { it.path } + movedSources.keys + movedSources.values +
            movedRoots.keys + movedRoots.values
        if (allPaths.any { it.isAbsolute || it != it.normalize() || it.startsWith("..") }) {
            return no(CMakeIncludeRootRefusal.PATH_UNAVAILABLE)
        }
        if (capturedIncludes.keys != capturedTranslationUnits ||
            capturedTranslationUnits.any { !it.fileName.toString().endsWith(".c") } ||
            !capturedTranslationUnits.containsAll(movedSources.keys) || movedSources.any { (old, next) ->
                old == next || !next.fileName.toString().endsWith(".c")
            }) return no(CMakeIncludeRootRefusal.SOURCE_SET_MISMATCH)
        val stagedUnits = capturedTranslationUnits.map { movedSources[it] ?: it }
        if (stagedUnits.toSet().size != capturedTranslationUnits.size) return no(CMakeIncludeRootRefusal.SOURCE_SET_MISMATCH)
        val target = Regex("[A-Za-z_][A-Za-z_0-9.+-]{0,127}")
        if (owners.keys != capturedTranslationUnits || owners.values.any { entries ->
                entries.singleOrNull()?.let { owner ->
                    !owner.target.matches(target) || !(owner.targetKind == "add_executable" && owner.libraryType == null ||
                        owner.targetKind == "add_library" && owner.libraryType == "STATIC")
                } != false
            }) return no(CMakeIncludeRootRefusal.OWNER_UNAVAILABLE)
        val selectedOwners = owners.mapValues { (_, entries) -> entries.single() }
        val targetNames = selectedOwners.values.map { it.target }.toSet()
        if ((beforeRoots + afterRoots).any { it.target !in targetNames }) return no(CMakeIncludeRootRefusal.OWNER_UNAVAILABLE)
        val beforeByTarget = beforeRoots.groupBy { it.target }
        val before = capturedTranslationUnits.sortedBy { it.toString() }.associateWith { source ->
            beforeByTarget[selectedOwners.getValue(source).target].orEmpty().map { it.path }
        }
        if (before.any { (source, paths) -> paths.distinct().size != paths.size || capturedIncludes[source] != paths }) {
            return no(CMakeIncludeRootRefusal.INCLUDE_ROOT_MISMATCH)
        }
        val oldRootPaths = beforeRoots.map { it.path }.toSet()
        if (!oldRootPaths.containsAll(movedRoots.keys) || movedRoots.any { (old, next) -> old == next } ||
            oldRootPaths.map { movedRoots[it] ?: it }.toSet().size != oldRootPaths.size ||
            beforeRoots.map { it.copy(path = movedRoots[it.path] ?: it.path) } != afterRoots) {
            return no(CMakeIncludeRootRefusal.ROOT_TRANSITION_MISMATCH)
        }
        val afterByTarget = afterRoots.groupBy { it.target }
        val after = before.keys.associate { source ->
            (movedSources[source] ?: source) to afterByTarget[selectedOwners.getValue(source).target].orEmpty().map { it.path }
        }
        return CMakeIncludeRootReadout.Found(CMakeIncludeRootFacts(before, after, selectedOwners))
    }
}
