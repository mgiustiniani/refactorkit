package org.refactorkit.jvm.refusals

import org.refactorkit.core.PatchPlan

/**
 * Narrow scenario-scoped port for shared refusal glue.
 *
 * Story BDD glue for refusal scenarios (REQ-*-REFUSAL-*) moves its mutable scenario state
 * into one port instead of duplicating fields per leaf glue class. Cucumber's picocontainer
 * container instantiates this class once per scenario and injects the same instance into every
 * step-definition class whose constructor requests it, so a leaf glue class that produces a
 * plan and a shared glue class that inspects it observe the same object within one scenario.
 *
 * The port intentionally exposes nothing beyond the current refusal patch plan: no fixture
 * roots, snapshots, reports, or cleanup state cross the port, and no production behavior is
 * decided here. Mutable state stays scenario-scoped (never static, never an object singleton).
 */
class KotlinJvmRefusalScenarioContext {
    /** The patch plan the leaf glue produced for the current scenario, or null before a preview. */
    var plan: PatchPlan? = null
}
