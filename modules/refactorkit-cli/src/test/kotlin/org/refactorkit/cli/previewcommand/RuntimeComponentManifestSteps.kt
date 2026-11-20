package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/** Scenario-scoped transport glue; the fixture owns the real Gradle invocation. */
class RuntimeComponentManifestSteps {
    private var fixture: RuntimeComponentManifestFixture? = null

    @Given("a source build runtime component manifest request has {string}")
    fun prepare(case: String) { fixture = RuntimeComponentManifestFixture(case) }

    @When("Gradle resolves and records the selected distribution components")
    fun execute() = requireNotNull(fixture).execute()

    @Then("the runtime component manifest reports {string}")
    fun outcome(expected: String) = requireNotNull(fixture).outcome(expected)

    @Then("component observation preserves source and binds every packaged JAR byte")
    fun invariants() = requireNotNull(fixture).invariants()

    @After
    fun close() { fixture?.close(); fixture = null }
}
