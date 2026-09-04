package org.refactorkit.jvm.refusals

import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.PatchStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared Story BDD glue for refusal-inspection steps, migrated verbatim from leaf refusal glue
 * (slice glue-shared-refusal-r001). These steps express the stable testing vocabulary for
 * REFUSED patch plans: no WorkspaceEdit, no affected files, no approval, and no managed-write
 * eligibility. Scenario-specific orchestration (fixture setup, previews, persistence checks)
 * stays in leaf glue; this class only reads the [KotlinJvmRefusalScenarioContext] port.
 *
 * The instance is scenario-scoped by cucumber-picocontainer and depends on the injected
 * context only; no static or object-singleton state is used.
 */
class KotlinJvmSharedRefusalSteps(private val context: KotlinJvmRefusalScenarioContext) {

    @When("^the caller inspects the refusal plan$")
    fun callerInspectsRefusalPlan() {
        requireNotNull(context.plan) { "expected a REFUSED patch plan to inspect" }
    }

    @Then("^the refusal carries an empty WorkspaceEdit$")
    fun refusalCarriesEmptyWorkspaceEdit() {
        val p = requireNotNull(context.plan)
        assertTrue(p.workspaceEdit.edits.isEmpty(), "expected no WorkspaceEdit in the refusal: ${p.toString()}")
    }

    @Then("^the refusal carries an empty affected-file set$")
    fun refusalCarriesEmptyAffectedFiles() {
        val p = requireNotNull(context.plan)
        assertTrue(p.affectedFiles.isEmpty(), "expected no affected file in the refusal: ${p.toString()}")
    }

    @Then("^the refusal grants no approval$")
    fun refusalGrantsNoApproval() {
        val p = requireNotNull(context.plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(!p.requiresUserApproval, "expected no user approval on the refusal: ${p.toString()}")
    }

    @Then("^the refusal grants no managed-write eligibility$")
    fun refusalGrantsNoManagedWriteEligibility() {
        val p = requireNotNull(context.plan)
        // A managed-write eligible plan is PREVIEW and carries a core OperationAuthorityLease;
        // the REFUSED plan must carry none and must not be an actionable apply.
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.authorityLease == null, "expected no managed-write authority lease: ${p.toString()}")
        assertEquals(0.0, p.confidence, "expected zero confidence on a refused plan")
    }
}
