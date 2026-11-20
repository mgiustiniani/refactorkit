package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class CManagedClangdRefusalSteps {
    private var fixture: CManagedClangdRefusalFixture? = null

    @Given("an owned C compilation database points to an absent include directory")
    fun capture() { fixture = CManagedClangdRefusalFixture() }

    @When("{string} requests the managed C rename preview")
    fun preview(surface: String) = requireNotNull(fixture).preview(surface)

    @Then("the managed preview returns the clangd configuration refusal")
    fun refused() = requireNotNull(fixture).assertRefusal()

    @And("the refused rename leaves the compilation inputs and journal untouched")
    fun untouched() = requireNotNull(fixture).assertUnchanged()

    @After
    fun close() { fixture?.close(); fixture = null }
}
