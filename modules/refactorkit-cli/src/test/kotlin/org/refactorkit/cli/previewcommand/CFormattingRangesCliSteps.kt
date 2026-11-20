package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class CFormattingRangesCliSteps {
    private var fixture: CFormattingRangesCliFixture? = null

    @Given("a local C file with three separate unformatted functions")
    fun capture() {
        fixture = CFormattingRangesCliFixture()
    }

    @When("the C CLI previews formatting with {string}")
    fun preview(selection: String) = requireNotNull(fixture).preview(selection)

    @Then("the native C formatting preview gives {string}")
    fun outcome(expected: String) = requireNotNull(fixture).assertOutcome(expected)

    @Then("the CLI range preview preserves source bytes and creates no transaction")
    fun intact() = requireNotNull(fixture).assertIntact()

    @After
    fun close() {
        fixture?.close()
    }
}
