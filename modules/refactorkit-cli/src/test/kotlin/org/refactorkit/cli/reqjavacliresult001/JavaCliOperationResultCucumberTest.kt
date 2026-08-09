package org.refactorkit.cli.reqjavacliresult001

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-cli-operation-result.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.reqjavacliresult001")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@REQ-JAVA-CLI-RESULT-001")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/req-java-cli-result-001-red.json",
)
class JavaCliOperationResultCucumberTest
