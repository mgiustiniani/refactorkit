package org.refactorkit.jvm.kotlinmatrix

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class KotlinScriptSemanticsMatrixSteps(
    private val probe: KotlinScriptSemanticsMatrixProbe,
) {
    @Given("a standalone Kotlin script declares an explicit @file:DependsOn host dependency")
    fun standaloneScriptDeclaresHostDependency() = probe.createStandaloneDependencyScript()

    @Given("a standalone Kotlin script references an unbound host-provided receiver")
    fun standaloneScriptReferencesHostReceiver() = probe.createStandaloneImplicitReceiverScript()

    @And("the standalone script dependency input changes and the workspace is reprojected")
    fun standaloneScriptDependencyChanges() = probe.changeStandaloneDependencyAndReproject()

    @Given("a Gradle settings.gradle.kts script declares plugin management and a marker-writing body")
    fun gradleSettingsScriptDeclaresPluginManagement() = probe.createGradleSettingsScript()

    @Given("a Gradle build.gradle.kts script declares a plugin-provided accessor and a marker-writing body")
    fun gradleProjectScriptDeclaresPluginAccessor() = probe.createGradleProjectScript()

    @Given("a Gradle project has settings and project Kotlin scripts with a versioned plugin context")
    fun gradleProjectHasVersionedKotlinContext() = probe.createGradleProjectWithKotlinSettingsContext()

    @Given("a Gradle Kotlin DSL {string} uses nested host receiver scopes")
    fun gradleScriptUsesNestedReceiverScopes(fileName: String) = probe.createGradleNestedReceiverScript(fileName)

    @And("the Gradle settings and project script inputs change and the workspace is reprojected")
    fun gradleBuildInputsChange() = probe.changeGradleScriptsAndReproject()

    @When("^the bounded Kotlin/JVM build projection and compiler gate inspect the captured workspace$")
    fun inspectBoundedWorkspace() = probe.inspectWorkspace()

    @Then("the inspection reports {string} before launching a compiler process")
    fun inspectionReportsTypedRefusalBeforeCompiler(refusalCode: String) =
        probe.requireTypedRefusalBeforeCompiler(refusalCode)

    @And("Kotlin semantic admission exposes {string} as a structured daemon diagnostic code")
    fun admissionReportsDiagnosticCode(code: String) = probe.requireDaemonAdmissionDiagnostic(code)

    @Then("the script contributes no symbols, references, workspace edits, or managed plans")
    fun scriptContributesNoSemanticAuthority() = probe.requireNoSemanticAuthority()

    @And("the Gradle host captures {string} as an auxiliary input outside Kotlin source ownership")
    fun gradleHostCapturesAuxiliaryScript(fileName: String) =
        probe.requireGradleScriptCapturedAsHostInput(fileName)

    @After
    fun cleanup() = probe.close()
}
