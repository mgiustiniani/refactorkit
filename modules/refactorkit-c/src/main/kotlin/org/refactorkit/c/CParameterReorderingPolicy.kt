package org.refactorkit.c

import java.util.Collections
import java.util.ArrayList

/** Captured compiler evidence; this policy never infers argument effects from source text. */
enum class CArgumentEffectEvidence { NO_OBSERVABLE_EFFECT, OBSERVABLE_EFFECT, UNAVAILABLE }

enum class CDirectCallFamilyEvidence { COMPLETE_FILE_STATIC, UNAVAILABLE, ADDRESS_TAKEN, EXTERNAL_CONSUMERS }

internal enum class CParameterReorderingRefusal {
    INVALID_MAPPING, INPUT_LIMIT, NO_CHANGE, FAMILY_UNAVAILABLE, INDIRECT_CALLS,
    EXTERNAL_CONSUMERS, ARGUMENT_ARITY, EFFECT_UNAVAILABLE, OBSERVABLE_EFFECT,
}

internal sealed interface CParameterReorderingDecision {
    class Accepted(originalIndices: List<Int>) : CParameterReorderingDecision {
        val originalIndices: List<Int> = immutableParameterOrder(originalIndices)

        operator fun component1(): List<Int> = originalIndices

        fun copy(originalIndices: List<Int> = this.originalIndices) = Accepted(originalIndices)

        override fun equals(other: Any?): Boolean = other === this ||
            other is Accepted && originalIndices == other.originalIndices

        override fun hashCode(): Int = originalIndices.hashCode()

        override fun toString(): String = "Accepted(originalIndices=$originalIndices)"
    }

    data class Refused(val reason: CParameterReorderingRefusal) : CParameterReorderingDecision
}

private fun immutableParameterOrder(values: Collection<Int>): List<Int> =
    Collections.unmodifiableList(ArrayList(values))

/** Pure admission over supplied facts, not compiler proof or a source-edit plan. */
internal object CParameterReorderingPolicy {
    fun decide(
        parameterCount: Int,
        originalIndices: List<Int>,
        family: CDirectCallFamilyEvidence,
        directCallArgumentEffects: List<List<CArgumentEffectEvidence>>,
    ): CParameterReorderingDecision {
        if (parameterCount !in 0..64 || directCallArgumentEffects.size > 8_192) {
            return refused(CParameterReorderingRefusal.INPUT_LIMIT)
        }
        if (originalIndices.size != parameterCount) return refused(CParameterReorderingRefusal.INVALID_MAPPING)
        val order = originalIndices.toList()
        if (order.any { it !in 0 until parameterCount } || order.toSet().size != parameterCount) {
            return refused(CParameterReorderingRefusal.INVALID_MAPPING)
        }
        if (order.indices.all { order[it] == it }) return refused(CParameterReorderingRefusal.NO_CHANGE)
        when (family) {
            CDirectCallFamilyEvidence.UNAVAILABLE -> return refused(CParameterReorderingRefusal.FAMILY_UNAVAILABLE)
            CDirectCallFamilyEvidence.ADDRESS_TAKEN -> return refused(CParameterReorderingRefusal.INDIRECT_CALLS)
            CDirectCallFamilyEvidence.EXTERNAL_CONSUMERS -> return refused(CParameterReorderingRefusal.EXTERNAL_CONSUMERS)
            CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC -> Unit
        }
        if (directCallArgumentEffects.any { it.size != parameterCount }) {
            return refused(CParameterReorderingRefusal.ARGUMENT_ARITY)
        }
        if (directCallArgumentEffects.any { CArgumentEffectEvidence.UNAVAILABLE in it }) {
            return refused(CParameterReorderingRefusal.EFFECT_UNAVAILABLE)
        }
        if (directCallArgumentEffects.any { CArgumentEffectEvidence.OBSERVABLE_EFFECT in it }) {
            return refused(CParameterReorderingRefusal.OBSERVABLE_EFFECT)
        }
        return CParameterReorderingDecision.Accepted(order)
    }

    private fun refused(reason: CParameterReorderingRefusal) = CParameterReorderingDecision.Refused(reason)
}
