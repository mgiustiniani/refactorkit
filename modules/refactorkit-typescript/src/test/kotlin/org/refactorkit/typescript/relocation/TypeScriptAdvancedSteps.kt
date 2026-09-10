package org.refactorkit.typescript.relocation

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then

class TypeScriptAdvancedSteps {
    private var fixture: TypeScriptAdvancedFixture? = null
    private var migration: TypeScriptProjectMigrationFixture? = null
    private var nodeProbe: TypeScriptNodeProbeFixture? = null

    @Given("a real explicit Node executable for bounded lifecycle checking")
    fun realProbe() { nodeProbe = TypeScriptNodeProbeFixture() }

    @When("a completed process leaves the registry immediately before iteration")
    fun completionRace() = requireNotNull(nodeProbe).closeDuringCompletion()

    @Then("process-manager close completes without a collection exception or an owned live child")
    fun safeClose() = requireNotNull(nodeProbe).assertSafeClose()

    @When("sixty four short-lived Node version probes run sequentially")
    fun shortProbes() = requireNotNull(nodeProbe).runShortLivedProbes()

    @Then("every version probe succeeds without changing the existing attempt and timeout limits")
    fun sequenceEvidence() = requireNotNull(nodeProbe).assertSuccessfulSequence()

    @Given("an unsuitable bundled executable for a Node version probe")
    fun unsuitableProbe() { nodeProbe = TypeScriptNodeProbeFixture() }

    @When("the bounded Node version probe runs that executable")
    fun runProbe() = requireNotNull(nodeProbe).run()

    @Then("the probe refusal includes exit status 1 with the original attempt and timeout limits")
    fun probeEvidence() = requireNotNull(nodeProbe).assertExitEvidence()

    @Given("a compiler-bound TypeScript relocation workspace with {string}")
    fun workspace(condition: String) { fixture = TypeScriptAdvancedFixture(condition) }

    @When("the relocation is previewed through its active semantic adapter")
    fun preview() = requireNotNull(fixture).preview()

    @Then("the guarded relocation has outcome {string} and refusal code {string}")
    fun outcome(outcome: String, code: String) = requireNotNull(fixture).verifyOutcome(outcome, code)

    @Then("the relocation refusal states {string} without compiler write authority")
    fun relocationRefusal(message: String) = requireNotNull(fixture).verifyRelocationRefusal(message)

    @When("the owning semantic child crashes and the same adapter restarts with the original snapshot")
    fun crashAndRestart() = requireNotNull(fixture).crashSemanticChildAndRestart()

    @Then("the retained pre-restart plan and gate are refused with {string} before any transaction")
    fun preRestartApplyRefused(code: String) = requireNotNull(fixture).verifyPreRestartApplyRefused(code)

    @Then("the relocation preserves approval diagnostics workspace bytes and rollback authority")
    fun writes() = requireNotNull(fixture).verifyWriteAuthority()

    @When("the TypeScript operation {string} is requested for an existing function")
    fun functionOperation(operation: String) = requireNotNull(fixture).previewFunctionOperation(operation)

    @Then("the unsupported TypeScript operation refuses with {string}")
    fun unsupported(code: String) = requireNotNull(fixture).verifyOutcome("REFUSED", code)

    @Then("the TypeScript catalogue contains only the eight bounded compiler-backed operation families")
    fun catalogue() = requireNotNull(fixture).verifyCatalogue()

    @Then("the TypeScript operation leaves all files and transaction records unchanged")
    fun unchanged() = requireNotNull(fixture).verifyWriteAuthority()

    @When("organize imports mode {string} is previewed with quote preference {string}")
    fun organize(mode: String, quotes: String) = requireNotNull(fixture).previewOrganize(mode, quotes)

    @Then("only the compiler-approved import changes for mode {string} and quotes {string} are proposed")
    fun organized(mode: String, quotes: String) = requireNotNull(fixture).verifyOrganize(mode, quotes)

    @Then("the TypeScript operation preserves approval diagnostics and rollback")
    fun managedOperation() = requireNotNull(fixture).verifyWriteAuthority()

    @When("compiler action {string} from refactor {string} is previewed as {string}")
    fun action(action: String, refactor: String, operation: String) = requireNotNull(fixture).previewAction(operation, refactor, action)

    @Then("the compiler action preserves the function results and its exact selection authority")
    fun actionAuthority() = requireNotNull(fixture).verifyAction()

    @When("a TypeScript migration recipe previews a relocation and non-mutating summaries")
    fun recipe() = requireNotNull(fixture).previewRecipe()

    @Then("the recipe retains the exact child plan rather than relabeling its authority")
    fun recipeAuthority() = requireNotNull(fixture).verifyRecipe()

    @When("the migration recipe is requested with {string}")
    fun recipeFault(fault: String) = requireNotNull(fixture).previewRecipe(fault)

    @Then("the recipe refuses with {string} and exposes no child plan")
    fun recipeRefusal(code: String) = requireNotNull(fixture).verifyRecipeRefusal(code)

    @Given("a TypeScript project-reference migration workspace")
    fun migrationWorkspace() { migration = TypeScriptProjectMigrationFixture() }

    @When("the sibling library project is previewed as a project-reference migration")
    fun projectMigration() = requireNotNull(migration).previewMigration()

    @When("the build model attaches configuration inputs to a source-only migration snapshot")
    fun captureMigrationInputs() = requireNotNull(migration).previewMigration(capture = true)

    @When("the project migration is previewed with auxiliary configuration inputs")
    fun auxiliaryMigration() = requireNotNull(migration).previewMigration(auxiliary = true)

    @When("the retained project migration is applied after {string}")
    fun migrationAuthorityChanged(change: String) = requireNotNull(migration).applyWithChangedAuthority(change)

    @Then("changed migration authority leaves no transaction and preserves the current files")
    fun migrationApplyRefused() = requireNotNull(migration).assertApplyRefused()

    @When("the project migration is requested with {string}")
    fun unsafeMigration(fault: String) = requireNotNull(migration).previewMigration(fault)

    @Then("the project migration refuses with {string} and preserves the workspace")
    fun migrationRefused(code: String) = requireNotNull(migration).assertMigrationRefused(code)

    @Then("the migration binds compiler edits and exact JSONC reference origins to the staged project graph")
    fun migrationAuthority() = requireNotNull(migration).assertMigration()

    @Then("unsuitable real compiler configuration suggestions are excluded while native source edits are retained")
    fun nativeConfigurationExcluded() = requireNotNull(migration).assertNativeConfigurationExcluded()

    @Then("the project migration requires approval and restores its original files through rollback")
    fun migrationTransaction() = requireNotNull(migration).applyMigrationAndRollback()

    @When("a relocated project and its reference edges are modeled from an immutable snapshot")
    fun stagedProjectModel() = requireNotNull(migration).modelStagedGraph()

    @When("the staged reference graph has {string}")
    fun stagedModelFault(fault: String) = requireNotNull(migration).modelStagedGraph(fault)

    @Then("the snapshot model refuses with {string} and exposes no project graph")
    fun refusedStagedModel(code: String) = requireNotNull(migration).assertModelRefused(code)

    @Then("the staged model owns the relocated sources and the rewritten reference graph")
    fun verifyStagedProjectModel() = requireNotNull(migration).assertStagedGraph()

    @Given("the reference graph contains {string}")
    fun referenceGraphFault(fault: String) = requireNotNull(migration).inputFault(fault)

    @Then("the composite diagnostics report only {string} errors")
    fun compositeErrors(code: String) = requireNotNull(migration).assertErrors(code)

    @When("the composite TypeScript projects are checked without emitting output")
    fun compositeDiagnostics() = requireNotNull(migration).checkDiagnostics()

    @Then("every composite project has complete clean diagnostics")
    fun cleanCompositeDiagnostics() = requireNotNull(migration).assertClean()

    @Then("the project-reference workspace and transaction records are unchanged")
    fun migrationUnchanged() = requireNotNull(migration).assertUnchanged()

    @After
    fun cleanup() {
        try { fixture?.close() } finally { migration?.close(); fixture = null; migration = null }
    }
}
