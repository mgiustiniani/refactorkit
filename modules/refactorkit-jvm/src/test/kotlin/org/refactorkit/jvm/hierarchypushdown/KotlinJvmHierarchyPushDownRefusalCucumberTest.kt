package org.refactorkit.jvm.hierarchypushdown

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-hierarchy-push-down-refusal.feature
 * (REQ-JAVA-HIERARCHY-PUSH-DOWN-REFUSAL-001, row N-PUSH-DOWN of the approved J1 catalogue,
 * baseline 6791b88). The feature is RED-to-GREEN in this slice: before the production fix the
 * JavaLanguageAdapter.applyRefactoring has no pushDownMember branch and falls through to the
 * generic notImplemented fallback, so a pushDownMember request returns PatchStatus.REFUSED with
 * the "Unknown operation: pushDownMember" summary and a null typed refusalCode; the RED suite
 * proves refusalCode=null. After the minimal production fix (an explicit pushDownMember branch
 * returning REFUSED with refusalCode=java.hierarchy.pushDown.unsupported, an empty WorkspaceEdit,
 * an empty affected-file set, no approval, no managed-write lease, and no lock/WAL/transaction)
 * the suite passes GREEN.
 *
 * GLUE is scoped to org.refactorkit.jvm.hierarchypushdown, a distinct package from the other
 * refusal slices (org.refactorkit.jvm.hierarchypullup, org.refactorkit.jvm.inlinerefusal and
 * org.refactorkit.jvm.inlinemethodrefusal), so the shared When/Then step regexes of the refusal
 * features never collide into cross-slice step ambiguity.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-hierarchy-push-down-refusal.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.hierarchypushdown")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-hierarchy-push-down-refusal.json",
)
class KotlinJvmHierarchyPushDownRefusalCucumberTest
