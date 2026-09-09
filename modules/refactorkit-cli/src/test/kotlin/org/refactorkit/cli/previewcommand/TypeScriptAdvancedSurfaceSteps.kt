package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class TypeScriptAdvancedSurfaceSteps {
    private var fixture: TypeScriptAdvancedSurfaceFixture? = null

    @Given("an isolated compiler-backed project migration workspace for {string}")
    fun workspace(surface: String) { fixture = TypeScriptAdvancedSurfaceFixture(surface) }

    @When("the project migration is requested through that public surface")
    fun preview() = requireNotNull(fixture).preview()

    @Then("the public migration preview is compiler-proven and leaves the workspace unchanged")
    fun previewEvidence() = requireNotNull(fixture).assertPreview()

    @Then("explicit public apply records one transaction and public rollback restores every original file")
    fun transaction() = requireNotNull(fixture).applyAndRollback()

    @When("the exact TypeScript compiler action {string} is previewed through that public surface")
    fun compilerAction(operation: String) = requireNotNull(fixture).previewAction(operation)

    @When("a compiler declaration move omits the separate project caller")
    fun incompleteMove() = requireNotNull(fixture).previewCompositeMoveRefusal()

    @When("the TypeScript organize-import mode {string} is previewed through that public surface")
    fun organize(mode: String) = requireNotNull(fixture).previewOrganize(mode)

    @When("the shipped TypeScript relocation recipe is previewed through that public surface")
    fun recipe() = requireNotNull(fixture).previewRecipe()

    @When("the public TypeScript operation {string} is requested with {string}")
    fun refusal(operation: String, authority: String) = requireNotNull(fixture).requestRefusal(operation, authority)

    @Then("the public refusal reports {string} without a transaction or workspace changes")
    fun refusalEvidence(code: String) = requireNotNull(fixture).assertRefusal(code)

    @When("a replacement semantic session previews the same recipe child")
    fun replaceSession() = requireNotNull(fixture).replaceRecipeSession()

    @Then("the old child refuses before WAL while the new child can apply and roll back")
    fun sessionEvidence() = requireNotNull(fixture).rejectOldRecipeThenApplyNew()

    @When("LSP ownership and a TypeScript project migration command are inspected")
    fun lsp() = requireNotNull(fixture).inspectLspOwnership()

    @Then("LSP advertises client ownership and refuses the unmanaged command without workspace writes")
    fun lspEvidence() = requireNotNull(fixture).assertLspOwnership()

    @When("the TypeScript catalogue is requested through that public surface")
    fun catalogue() = requireNotNull(fixture).catalogue()

    @Then("the public catalogue contains eight exact families and creates no plan or transaction")
    fun catalogueEvidence() = requireNotNull(fixture).assertCatalogue()

    @When("CLI help is requested without opening a semantic session")
    fun help() = requireNotNull(fixture).help()

    @Then("help names the advanced TypeScript command arguments without changing the workspace")
    fun helpEvidence() = requireNotNull(fixture).assertHelp()

    @After
    fun close() { fixture?.close() }
}
