package org.refactorkit.jvm.safedelete

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-safe-delete-characterization.feature
 * (REQ-JAVA-SAFE-DELETE-CHAR-001, row C-DELETE of the approved finite J1 Java catalogue
 * REQ-JAVA-J1-CATALOGUE-APPROVED-001, candidate bbda7e2). The feature is truthful GREEN-start:
 * production already implements JavaSafeDeletePlanner in the Java adapter, so this suite asserts
 * the REAL behavior rather than inventing it (anti-fake characterization). It covers 8 scenarios /
 * 13 cases total: 1 successful JDT-proven unused-type delete (confidence 1.0, JDT_BINDING, LOW),
 * 1 forced delete with references (confidence 0.3, HIGH, forced-delete warning), 1 lexical fallback
 * (confidence 1.0, LEXICAL_FALLBACK, LOW), 3 framework-annotation risk-elevation cases (Spring/JPA/
 * Jackson, HIGH), 1 refusal-contract scenario, 1 references-exist refusal with reference list +
 * "Use --force" phrase, 1 reference-list truncation with the "... and N more" suffix, and the 4
 * deterministic real refusal MESSAGE strings asserted as Examples data (no invented typed
 * safeDelete.xxx code, no refusalCode field).
 *
 * GLUE is scoped to org.refactorkit.jvm.safedelete, a distinct package from every other Java
 * characterization slice, so shared refusal When/Then step regexes never collide into cross-slice
 * step ambiguity. The runner drives the real toolchain: JavaProjectScanner build-model discovery +
 * JavaLanguageAdapter + JavaSafeDeletePlanner.preview.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-safe-delete-characterization.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.safedelete")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-safe-delete-characterization.json",
)
class KotlinJvmSafeDeleteCharacterizationCucumberTest
