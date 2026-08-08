package org.refactorkit.java.mavenmodulerenamesurface003

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-maven-module-rename-managed-surfaces.feature")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-003")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.java.mavenmodulerenamesurface003")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-maven-module-rename-managed-surfaces.json",
)
class JavaMavenModuleRenameSurface003CucumberTest
