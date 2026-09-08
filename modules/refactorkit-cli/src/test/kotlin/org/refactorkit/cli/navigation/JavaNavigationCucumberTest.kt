package org.refactorkit.cli.navigation

import io.cucumber.junit.platform.engine.Constants.FILTER_NAME_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-annotated-member-navigation.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.navigation")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@REQ-JAVA-ANNOTATED-NAVIGATION-001")
@ConfigurationParameter(key = FILTER_NAME_PROPERTY_NAME, value = "^Packaged navigation preserves annotated declarations and distinguishes unresolved analysis$")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,json:build/reports/cucumber/java-annotated-member-navigation.json")
class JavaNavigationCucumberTest
