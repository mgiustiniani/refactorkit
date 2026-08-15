package org.refactorkit.jvm.movedeclaration

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/kotlin-jvm-move-companion-refusal.feature
 * (REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001, acceptance criteria AC-COMPANION-REFUSAL-001..004).
 *
 * The glue uses the real K2 compiler toolchain (kotlin-compiler-embeddable-2.0.21,
 * jvmTarget 21, jdkToolchain 21) and drives the production [KotlinJvmMoveDeclarationPlanner].
 * The feature file declares the ACTUAL production refusal code kotlin.moveCompanionStandaloneUnsupported
 * for the standalone companion selection, and the glue asserts that the DECLARED code EQUALS the
 * ACTUAL refusalCode the planner returned, so the suite fails on a typed-code regression. The nested
 * non-companion object is asserted to NOT receive that companion code (it follows its own object
 * shape handling under the whole-file contract, actual code kotlin.moveDeclarationUnsupported), and
 * the missing-approval row is asserted to keep its fail-closed precedence
 * (kotlin.moveExternalConsumerApprovalRequired). Observed declared-to-actual mappings are appended to
 * build/reports/cucumber/kotlin-jvm-move-companion-refusal-codes.txt.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("kotlin-jvm-move-companion-refusal.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.movedeclaration")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/kotlin-jvm-move-companion-refusal.json",
)
class KotlinJvmMoveCompanionRefusalCucumberTest
