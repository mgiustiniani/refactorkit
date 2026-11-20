package org.refactorkit.daemon.requestisolation

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("daemon-request-error-isolation.feature")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-DAEMON-REQUEST-ISOLATION-001#non-fatal-handler-errors-receive-a-response-and-do-not-kill-the-daemon-worker",
)
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.daemon.requestisolation")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/daemon-request-error-isolation.json",
)
class DaemonRequestErrorIsolationCucumberTest
