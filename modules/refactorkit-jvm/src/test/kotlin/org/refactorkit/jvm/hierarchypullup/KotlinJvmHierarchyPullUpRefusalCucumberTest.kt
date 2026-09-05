package org.refactorkit.jvm.hierarchypullup

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/java-hierarchy-pull-up-refusal.feature
 * (REQ-JAVA-HIERARCHY-PULL-UP-REFUSAL-001, row N-PULL-UP of the approved J1 catalogue,
 * baseline 6791b88). The feature is RED-to-GREEN in this slice: before the production fix the
 * JavaLanguageAdapter.applyRefactoring has no pullUpMember branch and falls through to the
 * generic notImplemented fallback, so a pullUpMember request returns PatchStatus.REFUSED with
 * the "Unknown operation: pullUpMember" summary and a null typed refusalCode; the RED suite
 * proves refusalCode=null. After the minimal production fix (an explicit pullUpMember branch
 * returning REFUSED with refusalCode=java.hierarchy.pullUp.unsupported, an empty WorkspaceEdit,
 * an empty affected-file set, no approval, no managed-write lease, and no lock/WAL/transaction)
 * the suite passes GREEN.
 *
 * GLUE is scoped to org.refactorkit.jvm.hierarchypullup plus the shared refusal glue
 * package org.refactorkit.jvm.refusals (slice glue-shared-refusal-r003), which carries the
 * refusal-inspection steps over the scenario-scoped KotlinJvmRefusalScenarioContext port.
 * The leaf package stays distinct from the other refusal slices (org.refactorkit.jvm.inlinerefusal
 * and org.refactorkit.jvm.inlinemethodrefusal) so the remaining leaf step regexes of the
 * refusal features never collide into cross-slice step ambiguity.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-hierarchy-pull-up-refusal.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.jvm.hierarchypullup,org.refactorkit.jvm.refusals")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-hierarchy-pull-up-refusal.json",
)
class KotlinJvmHierarchyPullUpRefusalCucumberTest
