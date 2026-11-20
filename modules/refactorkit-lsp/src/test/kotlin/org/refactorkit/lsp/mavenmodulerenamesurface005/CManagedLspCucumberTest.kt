package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("c-lsp-managed-include-order.feature")
@SelectClasspathResource("c-lsp-managed-operation.feature")
@SelectClasspathResource("c-lsp-native-queries.feature")
@SelectClasspathResource("c-lsp-managed-statement-helper.feature")
@SelectClasspathResource("c-lsp-managed-prefix-rename.feature")
@SelectClasspathResource("c-lsp-managed-safe-delete.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.lsp.mavenmodulerenamesurface005")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/c-managed-lsp.json",
)
class CManagedLspCucumberTest
