package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class ModuleCrashRecoverySteps {
    private var fixture: MavenModuleCrashFixture? = null

    @Given("an isolated twenty-module reactor has 512 additional ordinary source files in the module to rename")
    fun prepare() {
        fixture = MavenModuleCrashFixture()
        requireNotNull(fixture).prepare()
    }

    @When("the public module-directory apply is killed after a module source file moves")
    fun crash() = requireNotNull(fixture).applyAndCrash()

    @Then("public recovery restores every original reactor path and byte and the interrupted WAL record")
    fun recover() = requireNotNull(fixture).recoverAndVerify()

    @When("an external {string} recovery conflict is introduced in the destination directory")
    fun conflict(kind: String) = requireNotNull(fixture).introduceConflict(kind)

    @Then("public recovery refuses without changing any reactor or foreign file")
    fun refusedRecovery() = requireNotNull(fixture).assertConflictPreserved()

    @When("the checksum-valid WAL uses the legacy unmarked layout beside an empty foreign new-style staging file")
    fun legacyCollision() = requireNotNull(fixture).legacyStagingCollision()

    @When("the marked pre-stage WAL is reconstructed with untouched sources and an empty foreign derived staging file")
    fun preStageCollision() = requireNotNull(fixture).reconstructPreStageCollision()

    @After
    fun close() { fixture?.close() }
}
