package org.refactorkit.jvm.movesourceroot

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-move-source-root-characterization.feature
 * (REQ-JAVA-MOVE-SOURCE-ROOT-CHAR-001, row C-ROOT of the approved finite J1 Java catalogue,
 * baseline 4557c45). The feature is GREEN-start: the production JavaMoveSourceRootPlanner
 * already exists in the Java adapter, so this suite asserts the real behavior rather than
 * inventing it (truthful characterization, anti-fake). It covers 1 successful whole-root
 * rename-only move plus the 11 deterministic typed refusals asserted as Examples data.
 *
 * GLUE is scoped to org.refactorkit.jvm.movesourceroot, a distinct package from every other
 * refusal slice, so the shared refusal When/Then step regexes never collide into cross-slice
 * step ambiguity. The runner drives the real JavaLanguageAdapter + JavaProjectScanner build
 * model + JavaMoveSourceRootPlanner preview path.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-move-source-root-characterization.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.movesourceroot")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-move-source-root-characterization.json",
)
class KotlinJvmMoveSourceRootCharacterizationCucumberTest
