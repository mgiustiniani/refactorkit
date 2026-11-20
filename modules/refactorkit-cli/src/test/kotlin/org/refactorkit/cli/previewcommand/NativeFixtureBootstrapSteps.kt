package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/** Scenario-scoped adapter; process execution and independent assertions live in the fixture. */
class NativeFixtureBootstrapSteps {
    private var fixture: NativeFixtureBootstrapFixture? = null

    @Given("a native fixture bootstrap request has {string}")
    fun request(case: String) {
        fixture = NativeFixtureBootstrapFixture.capture(case)
    }

    @When("the native fixture bootstrap validates its explicitly selected inputs")
    fun validate() = requireNotNull(fixture).execute()

    @Then("native fixture bootstrap reports {string} before qualification starts")
    fun outcome(expected: String) = requireNotNull(fixture).verifyOutcome(expected)

    @Then("native fixture bootstrap preserves selected artifacts and writes only approved outputs")
    fun invariants() = requireNotNull(fixture).verifyInvariants()

    @After
    fun close() {
        fixture?.close()
        fixture = null
    }
}
