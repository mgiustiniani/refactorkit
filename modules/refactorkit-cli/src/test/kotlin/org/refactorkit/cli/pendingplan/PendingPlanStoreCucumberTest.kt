package org.refactorkit.cli.pendingplan

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("pending-plan-store-extraction.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.pendingplan")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-PENDING-PLAN-STORE-001 or @REQ-PENDING-PLAN-STORE-002",
)
class PendingPlanStoreCucumberTest
