package org.refactorkit.testkit

import org.refactorkit.core.PatchPlan

/**
 * Outcome of running a single golden test case.
 */
data class GoldenTestResult(
    val name: String,
    val plan: PatchPlan?,
    val planErrors: List<String> = emptyList(),
    val afterErrors: List<String> = emptyList(),
) {
    val planValid: Boolean get() = planErrors.isEmpty()
    val afterValid: Boolean get() = afterErrors.isEmpty()
    val passed: Boolean get() = planValid && afterValid
    val errors: List<String> get() = planErrors + afterErrors
}
