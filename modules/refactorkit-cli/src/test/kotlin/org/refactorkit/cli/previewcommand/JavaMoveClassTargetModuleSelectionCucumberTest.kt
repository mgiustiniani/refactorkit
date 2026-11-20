package org.refactorkit.cli.previewcommand

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-move-class-cross-module.feature")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-JAVA-MOVE-CLASS-CROSS-MODULE-001#moves-a-class-to-an-explicit-module-before-its-target-package-exists or " +
        "@REQ-JAVA-MOVE-CLASS-CROSS-MODULE-001#explains-missing-gradle-outputs-without-relaxing-bindings",
)
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.previewcommand")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty")
class JavaMoveClassTargetModuleSelectionCucumberTest
