package org.refactorkit.cli.previewcommand

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-effective-project-context.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.previewcommand")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-JAVA-PROJECT-CONTEXT-001#gradle-8-11-1-jpms-inference-exports-declared-modular-dependency or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-8-11-1-jpms-inference-opt-out-refuses-missing-required-module",
)
class JavaGradle8111JpmsInferenceCucumberTest
