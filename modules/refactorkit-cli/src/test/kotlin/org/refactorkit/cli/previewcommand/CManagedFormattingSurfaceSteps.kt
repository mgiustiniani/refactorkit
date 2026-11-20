package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class CManagedFormattingSurfaceSteps {
    private var fixture: CManagedFormattingSurfaceFixture? = null

    @Given("a native C formatting project with {string} project style")
    fun capture(style: String) {
        fixture = CManagedFormattingSurfaceFixture(style)
    }

    @When("{string} performs the managed C formatting lifecycle")
    fun execute(surface: String) = requireNotNull(fixture).execute(surface)

    @Then("the C formatting surface reports {string}")
    fun outcome(expected: String) = requireNotNull(fixture).assertOutcome(expected)

    @Then("all captured C formatting files and transaction states match the outcome")
    fun intact() = requireNotNull(fixture).assertIntact()

    @After
    fun close() {
        fixture?.close()
    }
}
