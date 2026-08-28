package org.refactorkit.jvm.javaorganize

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Cucumber runner for the Java organize-imports characterization feature
 * (REQ-JAVA-ORGANIZE-IMPORTS-CHAR-001, row C-IMPORT of the J1 catalogue).
 *
 * Glue is scoped to [org.refactorkit.jvm.javaorganize]; the Kotlin organize-imports
 * slices already occupy org.refactorkit.jvm.organizeimports, so this Java
 * characterization uses a distinct package to avoid any step-definition collision.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-organize-imports-characterization.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.javaorganize")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-organize-imports-characterization.json",
)
class KotlinJvmJavaOrganizeImportsCharacterizationCucumberTest
