package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class CDownloadedReleaseSteps {
    private var fixture: DownloadedReleaseFixture? = null

    @Given("downloaded release evidence has {string}")
    fun prepare(condition: String) { fixture = DownloadedReleaseFixture(condition) }

    @When("the independent Linux asset verifier checks the downloaded set")
    fun execute() = requireNotNull(fixture).execute()

    @Then("the downloaded release result is {string}")
    fun verify(expected: String) = requireNotNull(fixture).verify(expected)

    @And("runtime execution and the review receipt match that outcome")
    fun execution() = requireNotNull(fixture).verifySideEffects()

    @After
    fun close() { fixture?.close(); fixture = null }
}
