package org.refactorkit.typescript.relocation

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD (Cucumber) runner for REQ-TS-SOURCE-FILE-RELOCATION-001
 * (features/typescript-source-file-relocation.feature, row T5-R1).
 *
 * The glue is scoped to org.refactorkit.typescript.relocation and drives the
 * real TypeScript semantic adapter against the pinned typescript 5.9.3
 * compiler server (getEditsForFileRename).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("typescript-source-file-relocation.feature")
@SelectClasspathResource("typescript-advanced-operations.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.typescript.relocation")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@REQ-TS-SOURCE-FILE-RELOCATION-001 or @typescript-advanced")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/typescript-source-file-relocation.json",
)
class KotlinJvmTypeScriptSourceFileRelocationCucumberTest
