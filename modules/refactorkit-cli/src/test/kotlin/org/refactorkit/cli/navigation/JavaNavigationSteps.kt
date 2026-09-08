package org.refactorkit.cli.navigation

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class JavaNavigationSteps {
    private val fixture = JavaNavigationFixture()

    @Given("a packaged Java navigation workspace contains these declarations and a compiler API consumer:")
    fun prepare(table: DataTable) = fixture.prepare(table.asMaps())

    @When("the packaged CLI lists and locates those members before and after an unrelated type-resolution failure")
    fun navigate() = fixture.navigate()

    @Then("each annotated member is listed once at its exact source location and signed callable definitions work with the embedded compiler APIs")
    fun verifyDeclarations() = fixture.verifyDeclarations()

    @Then("absent symbols and unavailable semantic analysis have distinct diagnostics while every read preserves the workspace bytes")
    fun verifyRefusalsAndReadOnly() = fixture.verifyRefusalsAndReadOnly()

    @After
    fun cleanup() = fixture.close()
}
