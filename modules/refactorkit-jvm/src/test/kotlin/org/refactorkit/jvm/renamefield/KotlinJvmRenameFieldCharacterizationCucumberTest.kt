package org.refactorkit.jvm.renamefield

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-rename-field-characterization.feature
 * (REQ-JAVA-RENAME-FIELD-CHAR-001, row C-RENAME-FIELD of the approved finite J1 Java catalogue
 * REQ-JAVA-J1-CATALOGUE-APPROVED-001, candidate 95feaa6d). The feature is truthful GREEN-start:
 * production already implements JavaRenameMemberPlanner in the Java adapter, so this suite asserts
 * the REAL behavior rather than inventing it (anti-fake characterization). It covers 5 scenarios /
 * 14 cases total: 1 successful JDT owner-bound field rename (confidence 0.95, LOW), 1 lexical
 * fallback (confidence 0.88, LOW), 1 multiple-same-named-member lexical fallback (confidence 0.88,
 * MEDIUM), 1 refusal-contract scenario, and the 10 deterministic real refusal MESSAGE strings
 * asserted as Examples data (no invented typed renameMember.xxx codes).
 *
 * GLUE is scoped to org.refactorkit.jvm.renamefield plus the shared refusal glue package
 * org.refactorkit.jvm.refusals (slice glue-shared-refusal-r006), which carries the granular refusal
 * inspection steps over the scenario-scoped KotlinJvmRefusalScenarioContext port; the leaf package
 * stays distinct from every other Java characterization slice so the remaining leaf step regexes
 * never collide into cross-slice step ambiguity. The runner drives the real toolchain: JavaProjectScanner build-model discovery +
 * JavaLanguageAdapter + JavaRenameMemberPlanner.preview.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-rename-field-characterization.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.renamefield,org.refactorkit.jvm.refusals")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-rename-field-characterization.json",
)
class KotlinJvmRenameFieldCharacterizationCucumberTest
