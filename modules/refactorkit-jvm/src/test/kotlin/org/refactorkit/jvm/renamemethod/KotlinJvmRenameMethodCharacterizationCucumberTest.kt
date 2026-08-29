package org.refactorkit.jvm.renamemethod

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-rename-method-characterization.feature
 * (REQ-JAVA-RENAME-METHOD-CHAR-001, row C-RENAME-METHOD of the approved finite J1 Java catalogue
 * REQ-JAVA-J1-CATALOGUE-APPROVED-001, candidate d902107). The feature is truthful GREEN-start:
 * production already implements JavaRenameMemberPlanner in the Java adapter, so this suite asserts
 * the REAL behavior rather than inventing it (anti-fake characterization). It covers 4 scenarios /
 * 14 cases total: 1 successful signed-JDT method rename (confidence 0.93, LOW), 1 override-family
 * rename (confidence 0.91, MEDIUM), 1 refusal-contract scenario, and the 11 deterministic real
 * refusal MESSAGE strings asserted as Examples data (no invented typed renameMember.xxx codes). Four
 * unreachable refusal rows were removed by the feature reconcile at d902107.
 *
 * GLUE is scoped to org.refactorkit.jvm.renamemethod, a distinct package from every other Java
 * characterization slice, so shared refusal When/Then step regexes never collide into cross-slice
 * step ambiguity. The runner drives the real toolchain: JavaProjectScanner build-model discovery +
 * JavaLanguageAdapter + JavaRenameMemberPlanner.preview.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-rename-method-characterization.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.renamemethod")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-rename-method-characterization.json",
)
class KotlinJvmRenameMethodCharacterizationCucumberTest
