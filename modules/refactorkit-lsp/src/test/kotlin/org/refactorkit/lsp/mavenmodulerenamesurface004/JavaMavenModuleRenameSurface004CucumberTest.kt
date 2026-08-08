package org.refactorkit.lsp.mavenmodulerenamesurface004

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
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-004")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.lsp.mavenmodulerenamesurface004")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-maven-module-rename-surface-004.json",
)
class JavaMavenModuleRenameSurface004CucumberTest
