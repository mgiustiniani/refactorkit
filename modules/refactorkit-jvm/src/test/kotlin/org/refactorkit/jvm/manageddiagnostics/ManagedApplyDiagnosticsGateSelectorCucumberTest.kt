package org.refactorkit.jvm.manageddiagnostics

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("managed-apply-diagnostics-gate-selector.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.manageddiagnostics")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-MANAGED-APPLY-DIAGNOSTICS-SELECTOR-001 or @REQ-MANAGED-APPLY-DIAGNOSTICS-SELECTOR-002",
)
class ManagedApplyDiagnosticsGateSelectorCucumberTest
