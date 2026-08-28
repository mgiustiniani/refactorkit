package org.refactorkit.jvm.extractmethod

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-extract-method-characterization.feature
 * (REQ-JAVA-EXTRACT-METHOD-CHAR-001, row C-EXTRACT of the approved finite J1 Java catalogue,
 * baseline b470e6c). The feature is GREEN-start: production already implements
 * JavaExtractMethodPlanner in the Java adapter, so this suite asserts the real behavior rather
 * than inventing it (truthful characterization, anti-fake). It covers 22 cases total:
 * 1 successful straight-line complete-line no-argument private void extraction, 1 refusal
 * contract scenario, and the 20 deterministic real refusal MESSAGE strings asserted as
 * Examples data (no invented typed refusal codes).
 *
 * GLUE is scoped to org.refactorkit.jvm.extractmethod, a distinct package from every other
 * refusal slice, so shared refusal When/Then step regexes never collide into cross-slice step
 * ambiguity. The runner drives the real JavaLanguageAdapter + JavaProjectScanner build model +
 * JavaExtractMethodPlanner preview path through RefactoringRequest.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-extract-method-characterization.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.extractmethod")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-extract-method-characterization.json",
)
class KotlinJvmExtractMethodCharacterizationCucumberTest
