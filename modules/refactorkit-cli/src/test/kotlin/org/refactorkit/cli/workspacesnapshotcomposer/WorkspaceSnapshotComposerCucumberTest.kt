package org.refactorkit.cli.workspacesnapshotcomposer

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("workspace-snapshot-composer-extraction.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.workspacesnapshotcomposer")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-WORKSPACE-SNAPSHOT-COMPOSER-001 or " +
        "@REQ-WORKSPACE-SNAPSHOT-COMPOSER-002 or " +
        "@REQ-WORKSPACE-SNAPSHOT-COMPOSER-003 or " +
        "@REQ-WORKSPACE-SNAPSHOT-COMPOSER-004 or " +
        "@REQ-WORKSPACE-SNAPSHOT-COMPOSER-005",
)
class WorkspaceSnapshotComposerCucumberTest
