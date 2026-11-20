package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then

/** Scenario-scoped adapter; model inspection and assertions belong to its fixture. */
class SourceQualificationTestProfilesSteps {
    private var fixture: SourceQualificationTestProfilesFixture? = null

    @Given("source qualification fixture inputs use {string}")
    fun capture(profile: String) {
        fixture = SourceQualificationTestProfilesFixture.capture(profile)
    }

    @When("the actual Gradle CLI and LSP test task models are inspected")
    fun inspect() = requireNotNull(fixture).inspect()

    @Then("both source test tasks retain the selected runtime and evidence ownership")
    fun verifyOwnership() = requireNotNull(fixture).verifyOwnership()

    @Then("source test scheduling preserves runtime prerequisites and the Gradle executable")
    fun verifyScheduling() = requireNotNull(fixture).verifyScheduling()

    @After
    fun close() {
        fixture?.close()
        fixture = null
    }
}
