package org.refactorkit.jvm.changesignature

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/kotlin-jvm-change-signature-parameter-rename.feature
 * (REQ-KOTLIN-CHANGE-SIGNATURE-001..003; 14 scenarios / 32 expanded cases across 10 scenario outlines).
 *
 * The glue uses the real K2 compiler toolchain (kotlin-compiler-embeddable-2.0.21,
 * jvmTarget 21, jdkToolchain 21) plus the JDT Java semantic analyzer, and drives the
 * production planners [org.refactorkit.kotlin.KotlinChangeSignaturePlanner] (REQ-001/REQ-002,
 * K2-level refusal codes) and [org.refactorkit.jvm.KotlinJvmChangeSignaturePlanner]
 * (REQ-003, mixed K2+JDT staged proof).
 *
 * Every refusal scenario asserts that the DECLARED stable typed code (from the feature
 * Examples table) EQUALS the ACTUAL refusalCode the planner returned, so the suite FAILS on a
 * typed-code regression. Every observed declared-to-actual mapping is appended to
 * build/reports/cucumber/kotlin-jvm-change-signature-parameter-rename-codes.txt for reconciliation.
 * GREEN scenarios assert the real preview/apply/rollback behavior (edits, risk, warnings,
 * PatchEngine transaction, rollback byte equality), never a hard-coded success path.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("kotlin-jvm-change-signature-parameter-rename.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.changesignature")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/kotlin-jvm-change-signature-parameter-rename.json",
)
class KotlinJvmChangeSignatureParameterRenameCucumberTest
