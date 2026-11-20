package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class CSpdxRuntimeCoordinatesSteps {
    private var fixture: SpdxRuntimeCoordinatesFixture? = null

    @Given("a release SPDX coordinate fixture has {string}")
    fun prepare(case: String) { fixture = SpdxRuntimeCoordinatesFixture(case) }

    @When("SPDX qualification reconciles the supplied runtime resolution")
    fun reconcile() = requireNotNull(fixture).execute()

    @Then("the SPDX coordinate qualification reports {string}")
    fun verify(expected: String) = requireNotNull(fixture).verify(expected)

    @And("the coordinate fixture preserves its archive and original metadata")
    fun preserve() = requireNotNull(fixture).preserve()

    @After
    fun close() { fixture?.close(); fixture = null }
}
