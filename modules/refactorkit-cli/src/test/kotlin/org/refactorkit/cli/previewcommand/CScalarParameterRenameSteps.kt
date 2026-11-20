package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/** Scenario-scoped adapter; the fixture owns native execution and transaction assertions. */
class CScalarParameterRenameSteps {
    private var fixture: CScalarParameterRenameFixture? = null

    @Given("a C scalar parameter family uses {string} in {string}")
    fun capture(type: String, dialect: String) { fixture = CScalarParameterRenameFixture.capture(type, dialect) }

    @When("its scalar parameter rename is previewed applied and rolled back")
    fun execute() = requireNotNull(fixture).execute()

    @Then("the scalar family preserves its types positional calls and native result")
    fun verifyBehavior() = requireNotNull(fixture).verifyBehavior()

    @Then("the scalar rename preserves exact inputs and records one guarded transaction")
    fun verifyInputs() = requireNotNull(fixture).verifyInputs()

    @After
    fun close() { fixture?.close(); fixture = null }
}
