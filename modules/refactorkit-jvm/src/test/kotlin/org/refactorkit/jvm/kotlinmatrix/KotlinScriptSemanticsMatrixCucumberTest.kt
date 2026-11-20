package org.refactorkit.jvm.kotlinmatrix

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/** One shared Story runner for the seven host-specific identities in REQ-KOTLIN-MATRIX-001. */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("kotlin-script-semantics-matrix.feature")
@ConfigurationParameter(
    key = GLUE_PROPERTY_NAME,
    value = "org.refactorkit.jvm.kotlinmatrix",
)
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/kotlin-script-semantics-matrix.json",
)
class KotlinScriptSemanticsMatrixCucumberTest
