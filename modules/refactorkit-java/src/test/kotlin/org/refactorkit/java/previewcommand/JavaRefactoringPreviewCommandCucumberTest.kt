package org.refactorkit.java.previewcommand

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-refactoring-preview-command-boundary.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.java.previewcommand")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-JAVA-PREVIEW-COMMAND-001 or " +
        "@REQ-JAVA-PREVIEW-COMMAND-002 or " +
        "@REQ-JAVA-PREVIEW-COMMAND-004",
)
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-refactoring-preview-command-boundary.json",
)
class JavaRefactoringPreviewCommandCucumberTest
