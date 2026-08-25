package org.refactorkit.jvm.inlinerefusal

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-inline-variable-refusal.feature
 * (REQ-JAVA-INLINE-VARIABLE-REFUSAL-001, row N-INLINE-VAR of the approved J1 catalogue,
 * baseline 8aa49c3). The feature is @implemented-and-validated: the RED-to-GREEN cycle is
 * complete (GREEN c05533f7). Production JavaLanguageAdapter.applyRefactoring now has an
 * inlineVariable branch that emits the typed refusal code java.inlineVariable.unsupported with
 * an empty edit and no managed-write authority. The glue drives the real production adapter and
 * asserts that the DECLARED typed refusal code java.inlineVariable.unsupported EQUALS the ACTUAL
 * refusalCode the adapter returned, so the suite fails on any typed-code regression.
 *
 * GLUE is scoped to org.refactorkit.jvm.inlinerefusal to avoid cross-slice step ambiguity.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-inline-variable-refusal.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.inlinerefusal")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-inline-variable-refusal.json",
)
class KotlinJvmInlineVariableRefusalCucumberTest
