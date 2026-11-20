package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/** Scenario-scoped adapter; the fixture owns native Git execution and its independent oracle. */
class ReleaseSourceInventorySteps {
    private var fixture: ReleaseSourceInventoryFixture? = null

    @Given("a release source inventory contains authored files and local qualification artifacts")
    fun capture() { fixture = ReleaseSourceInventoryFixture.capture() }

    @When("Git inventories that fixture with the repository ignore policy")
    fun inventory() = requireNotNull(fixture).inventory()

    @Then("only authored source and pinned qualification manifests enter the release source inventory")
    fun verifySelection() = requireNotNull(fixture).verifySelection()

    @Then("release source filtering preserves every local artifact byte-for-byte")
    fun verifyPreservation() = requireNotNull(fixture).verifyPreservation()

    @After
    fun close() { fixture?.close(); fixture = null }
}
