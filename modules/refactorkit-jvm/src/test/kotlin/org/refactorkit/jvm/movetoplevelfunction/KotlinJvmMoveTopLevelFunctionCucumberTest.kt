package org.refactorkit.jvm.movetoplevelfunction

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/kotlin-jvm-move-top-level-function.feature
 * (REQ-KOTLIN-MOVE-FUNCTION-001, acceptance criteria AC-FUNCTION-001..006).
 *
 * The glue uses the real K2 compiler toolchain (kotlin-compiler-embeddable-2.0.21)
 * and drives the production [KotlinJvmMoveDeclarationPlanner]. The feature file
 * declares the actual production refusal codes, and the glue asserts that each
 * DECLARED code (from the feature Examples table) EQUALS the ACTUAL refusalCode the
 * planner returned, so the suite fails on a typed-code regression. Declared-to-actual
 * mappings are appended to
 * build/reports/cucumber/move-top-level-function-refusal-codes.txt.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("kotlin-jvm-move-top-level-function.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.movetoplevelfunction")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/kotlin-jvm-move-top-level-function.json",
)
class KotlinJvmMoveTopLevelFunctionCucumberTest
