package org.refactorkit.c

internal enum class CNativeTarget(val triple: String) {
    I386_LINUX("i386-unknown-linux-gnu"),
    X86_64_LINUX("x86_64-unknown-linux-gnu"),
}

internal enum class CNativeTargetRefusal {
    CONTEXT_LIMIT, INVALID_OPTIONS, MULTIPLE_TARGET_FLAGS, UNQUALIFIED_TARGET,
    METADATA_MISMATCH, INCONSISTENT_CONTEXTS,
}

internal sealed interface CNativeTargetAdmission {
    data object ImplicitDefault : CNativeTargetAdmission
    data object PerTranslationUnit : CNativeTargetAdmission
    data class Explicit(val target: CNativeTarget) : CNativeTargetAdmission
    data class Refused(val reason: CNativeTargetRefusal) : CNativeTargetAdmission
}

/** Pure target selection only; path ownership and all other flag admission remain separate. */
internal object CNativeTargetPolicy {
    fun admit(units: List<CCompilationUnit>): CNativeTargetAdmission {
        if (units.size > CCompilationDatabasePolicy.DEFAULT_MAX_UNITS) {
            return CNativeTargetAdmission.Refused(CNativeTargetRefusal.CONTEXT_LIMIT)
        }
        val parser = CCompilationDatabaseParser()
        val selections = mutableSetOf<CNativeTargetAdmission>()
        val sourceContexts = mutableMapOf<java.nio.file.Path, CNativeTargetAdmission>()
        for (unit in units) {
            val options = try { parser.optionValues(unit.arguments) } catch (_: RuntimeException) {
                return CNativeTargetAdmission.Refused(CNativeTargetRefusal.INVALID_OPTIONS)
            }
            val targets = options.filter { it.first in setOf("-target", "--target") }.map { it.second }
            if (targets.size > 1) return CNativeTargetAdmission.Refused(CNativeTargetRefusal.MULTIPLE_TARGET_FLAGS)
            val declared = targets.singleOrNull()
            val selection = if (declared == null) CNativeTargetAdmission.ImplicitDefault else {
                val target = CNativeTarget.entries.singleOrNull { it.triple == declared }
                    ?: return CNativeTargetAdmission.Refused(CNativeTargetRefusal.UNQUALIFIED_TARGET)
                CNativeTargetAdmission.Explicit(target)
            }
            if (unit.target != declared) return CNativeTargetAdmission.Refused(CNativeTargetRefusal.METADATA_MISMATCH)
            val previous = sourceContexts.put(unit.file.normalize(), selection)
            if (previous != null && previous != selection) {
                return CNativeTargetAdmission.Refused(CNativeTargetRefusal.INCONSISTENT_CONTEXTS)
            }
            selections += selection
        }
        return if (selections.size <= 1) selections.singleOrNull() ?: CNativeTargetAdmission.ImplicitDefault
        else CNativeTargetAdmission.PerTranslationUnit
    }
}
