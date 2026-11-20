package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class CCMakeDiagnosticProjectionSteps {
    private var fixture: CCMakeDiagnosticProjectionFixture? = null

    @Given("a literal CMake diagnostics workspace with {string} compiler inputs")
    fun capture(state: String) {
        fixture = CCMakeDiagnosticProjectionFixture(state)
    }

    @When("the LSP client previews the captured CMake source move")
    fun preview() = requireNotNull(fixture).preview()

    @Then("managed LSP has {string} diagnostics for the current and staged files")
    fun outcome(evidence: String) = requireNotNull(fixture).assertProjection(evidence)

    @Then("the CMake diagnostics preview preserves saved files and creates no journal")
    fun intact() = requireNotNull(fixture).assertIntact()

    @After
    fun close() {
        fixture?.close()
    }
}
