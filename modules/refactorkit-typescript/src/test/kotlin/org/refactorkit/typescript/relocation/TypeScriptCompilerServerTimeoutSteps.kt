package org.refactorkit.typescript.relocation

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then

/** Transport-test glue delegates setup, execution and observations to a disposable fixture. */
class TypeScriptCompilerServerTimeoutSteps {
    private var fixture: TypeScriptCompilerServerTimeoutFixture? = null

    @Given("a compiler-server peer that stalls during {string} with a bounded deadline")
    fun stalledPeer(phase: String) {
        fixture = TypeScriptCompilerServerTimeoutFixture(phase)
    }

    @Given("a compiler-server peer returns a malformed rename response where {string}")
    fun malformedPeer(fault: String) {
        fixture = TypeScriptCompilerServerTimeoutFixture("rename", responseFault = fault)
    }

    @Then("the malformed compiler response is refused without any partial edit proposal")
    fun malformedResponse() = requireNotNull(fixture).verifyMalformedResponse()

    @When("the compiler client opens the project and requests file-rename edits")
    fun request() = requireNotNull(fixture).request()

    @Then("the stalled compiler exchange terminates within the timeout budget without an edit proposal")
    fun noProposal() = requireNotNull(fixture).verifyNoProposal()

    @Then("the compiler process is stopped and the workspace is unchanged")
    fun stoppedAndReadOnly() = requireNotNull(fixture).verifyStoppedAndReadOnly()

    @After
    fun cleanup() {
        fixture?.close()
        fixture = null
    }
}
