package org.refactorkit.jvm.inlinemethodrefusal

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-inline-method-refusal.feature
 * (REQ-JAVA-INLINE-METHOD-REFUSAL-001, row N-INLINE-METHOD of the approved J1 catalogue,
 * baseline 8aa49c3). The feature is @not-implemented: the RED-to-GREEN cycle is in progress.
 * Production JavaLanguageAdapter.applyRefactoring has an inlineVariable branch but NO
 * inlineMethod branch, so an inlineMethod request falls through to the generic notImplemented
 * fallback ("Unknown operation: inlineMethod") with refusalCode=null. The glue drives the real
 * production adapter and asserts that the DECLARED typed refusal code java.inlineMethod.unsupported
 * EQUALS the ACTUAL refusalCode the adapter returned, so the suite FAILS on the genuine RED
 * (refusalCode=null) until production emits the typed inline-method refusal.
 *
 * GLUE is scoped to org.refactorkit.jvm.inlinemethodrefusal, a distinct package from the
 * N-INLINE-VAR slice (org.refactorkit.jvm.inlinerefusal), so the shared When/Then step regexes
 * of the two refusal features never collide into cross-slice step ambiguity.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-inline-method-refusal.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.inlinemethodrefusal")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-inline-method-refusal.json",
)
class KotlinJvmInlineMethodRefusalCucumberTest
