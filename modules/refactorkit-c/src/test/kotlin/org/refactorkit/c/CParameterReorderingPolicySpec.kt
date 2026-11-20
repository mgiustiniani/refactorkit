package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** REQ-C-PARAMETER-REORDER-POLICY-001; pure domain, user-approved Kotest 5.9.1. */
class CParameterReorderingPolicySpec : StringSpec({
    val pure = CArgumentEffectEvidence.NO_OBSERVABLE_EFFECT
    fun decide(order: List<Int>, calls: List<List<CArgumentEffectEvidence>> = listOf(List(3) { pure }),
               family: CDirectCallFamilyEvidence = CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC) =
        CParameterReorderingPolicy.decide(3, order, family, calls)

    "every nonidentity permutation preserves the original parameter-to-argument association" {
        val parameters = listOf("hundreds", "tens", "units")
        val calls = listOf(listOf(9, 8, 7), listOf(1, 2, 3))
        fun evaluate(association: Map<String, Int>) = association.getValue("hundreds") * 100 +
            association.getValue("tens") * 10 + association.getValue("units")
        val permutations = listOf(listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        permutations.forEach { order ->
            val accepted = decide(order, calls.map { List(it.size) { pure } }).shouldBeInstanceOf<CParameterReorderingDecision.Accepted>()
            calls.forEach { values ->
                val before = parameters.zip(values).toMap()
                val reorderedParameters = accepted.originalIndices.map(parameters::get)
                val reorderedArguments = accepted.originalIndices.map(values::get)
                evaluate(reorderedParameters.zip(reorderedArguments).toMap()) shouldBe evaluate(before)
            }
            accepted.originalIndices shouldBe order
        }
    }

    "missing duplicate out-of-range and excessive mappings cannot produce a partial decision" {
        listOf(listOf(0, 0, 2), listOf(0, 1), listOf(0, 1, 3), listOf(-1, 1, 2)).forEach { order ->
            decide(order) shouldBe CParameterReorderingDecision.Refused(CParameterReorderingRefusal.INVALID_MAPPING)
        }
        CParameterReorderingPolicy.decide(65, (0..64).reversed().toList(), CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC, emptyList()) shouldBe
            CParameterReorderingDecision.Refused(CParameterReorderingRefusal.INPUT_LIMIT)
        CParameterReorderingPolicy.decide(3, listOf(2, 1, 0), CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC, List(8_193) { listOf(pure, pure, pure) }) shouldBe
            CParameterReorderingDecision.Refused(CParameterReorderingRefusal.INPUT_LIMIT)
    }

    "identity and zero-parameter mappings are explicit no-op refusals" {
        decide(listOf(0, 1, 2)) shouldBe CParameterReorderingDecision.Refused(CParameterReorderingRefusal.NO_CHANGE)
        CParameterReorderingPolicy.decide(0, emptyList(), CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC, emptyList()) shouldBe
            CParameterReorderingDecision.Refused(CParameterReorderingRefusal.NO_CHANGE)
    }

    "unknown indirect and external families retain distinct refusal reasons" {
        mapOf(CDirectCallFamilyEvidence.UNAVAILABLE to CParameterReorderingRefusal.FAMILY_UNAVAILABLE,
            CDirectCallFamilyEvidence.ADDRESS_TAKEN to CParameterReorderingRefusal.INDIRECT_CALLS,
            CDirectCallFamilyEvidence.EXTERNAL_CONSUMERS to CParameterReorderingRefusal.EXTERNAL_CONSUMERS).forEach { (family, reason) ->
            decide(listOf(2, 1, 0), family = family) shouldBe CParameterReorderingDecision.Refused(reason)
        }
    }

    "a later call with a different arity invalidates the whole family" {
        decide(listOf(2, 1, 0), listOf(listOf(pure, pure, pure), listOf(pure, pure))) shouldBe
            CParameterReorderingDecision.Refused(CParameterReorderingRefusal.ARGUMENT_ARITY)
    }

    "unavailable argument effects cannot inherit evidence from another call" {
        decide(listOf(2, 1, 0), listOf(listOf(pure, pure, pure), listOf(pure, CArgumentEffectEvidence.UNAVAILABLE, pure))) shouldBe
            CParameterReorderingDecision.Refused(CParameterReorderingRefusal.EFFECT_UNAVAILABLE)
    }

    "an observable effect in any call refuses the requested reordering" {
        decide(listOf(1, 0, 2), listOf(listOf(pure, pure, pure), listOf(pure, pure, CArgumentEffectEvidence.OBSERVABLE_EFFECT))) shouldBe
            CParameterReorderingDecision.Refused(CParameterReorderingRefusal.OBSERVABLE_EFFECT)
    }

    "an accepted order cannot change when the caller mutates its request" {
        val order = mutableListOf(2, 1, 0)
        val accepted = decide(order).shouldBeInstanceOf<CParameterReorderingDecision.Accepted>()
        order.reverse()
        accepted.originalIndices shouldBe listOf(2, 1, 0)
        val mutation = runCatching { (accepted.originalIndices as MutableList<Int>)[0] = 0 }
        mutation.isFailure shouldBe true
        accepted.originalIndices shouldBe listOf(2, 1, 0)

        val constructorInput = mutableListOf(2, 1, 0)
        val direct = CParameterReorderingDecision.Accepted(constructorInput)
        constructorInput.clear()
        direct.originalIndices shouldBe listOf(2, 1, 0)
        direct.copy() shouldBe direct
        direct.hashCode() shouldBe direct.copy().hashCode()
        direct.toString() shouldBe "Accepted(originalIndices=[2, 1, 0])"
        val (destructuredOrder) = direct
        destructuredOrder shouldBe listOf(2, 1, 0)

        val copyInput = mutableListOf(1, 2, 0)
        val copied = direct.copy(copyInput)
        copyInput.clear()
        copied.originalIndices shouldBe listOf(1, 2, 0)
        runCatching { (copied.originalIndices as MutableList<Int>).clear() }.isFailure shouldBe true
    }
})
