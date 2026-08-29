package org.refactorkit.jvm.renameclass

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-rename-class-characterization.feature
 * (REQ-JAVA-RENAME-CLASS-CHAR-001, row C-RENAME-TYPE of the approved finite J1 Java catalogue
 * REQ-JAVA-J1-CATALOGUE-APPROVED-001, baseline 8aa49c3). The feature is truthful GREEN-start:
 * production already implements JavaRenameClassPlanner in the Java adapter, so this suite
 * asserts the REAL behavior rather than inventing it (anti-fake characterization). It covers
 * 6 scenarios / 14 cases total: 1 successful exact JDT rename, 3 framework-annotation risk
 * elevation cases (Spring/JPA/Jackson), 1 large-rename (12 files) MEDIUM-risk case, 1
 * lexical-fallback case, 1 refusal-contract case, and the 7 deterministic real refusal MESSAGE
 * strings asserted as Examples data (no invented typed renameClass.xxx codes). Evidence for
 * the GREEN 14/14 run is 0df18185.
 *
 * GLUE is scoped to org.refactorkit.jvm.renameclass, a distinct package from every other Java
 * characterization slice, so shared refusal When/Then step regexes never collide into cross-slice
 * step ambiguity. The runner drives the real toolchain: JavaProjectScanner build-model discovery +
 * JavaLanguageAdapter + JavaRenameClassPlanner.preview.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-rename-class-characterization.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.renameclass")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-rename-class-characterization.json",
)
class KotlinJvmRenameClassCharacterizationCucumberTest
