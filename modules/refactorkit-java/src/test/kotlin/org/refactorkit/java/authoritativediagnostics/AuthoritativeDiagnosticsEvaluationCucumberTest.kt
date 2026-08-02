package org.refactorkit.java.authoritativediagnostics

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("authoritative-diagnostics-evaluation.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.java.authoritativediagnostics")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-001 or " +
        "@REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-002 or " +
        "@REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-003 or " +
        "@REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-004",
)
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/authoritative-diagnostics-evaluation.json",
)
class AuthoritativeDiagnosticsEvaluationCucumberTest
