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
@SelectClasspathResource("java-gradle-effective-context.feature")
@SelectClasspathResource("java-gradle-kotlin-compiler-ownership.feature")
@SelectClasspathResource("java-27-maven-semantic-analysis-refusal.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.previewcommand")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-JAVA-GRADLE-CONTEXT-001#gradle-composite-resync-retains-parent-owned-artifacts-after-run or " +
        "@REQ-JAVA-GRADLE-CONTEXT-001#gradle-selected-context-retains-build-tree-project-path-across-compilation or " +
        "@REQ-JAVA-GRADLE-CONTEXT-001#aggregate-gradle-context-exports-the-evaluated-build-tree-project-path or " +
        "@REQ-JAVA-GRADLE-CONTEXT-001#runtime-classpath-ownership-follows-resolved-project-artifacts-at-a-shared-jar-path or " +
        "@REQ-JAVA-GRADLE-CONTEXT-001#composite-gradle-context-qualifies-workspace-build-identity-or-refuses-ambiguous-stale-and-outside-roots or " +
        "@REQ-JAVA-GRADLE-CONTEXT-001#explicit-gradle-sync-provides-effective-java-context-or-refuses-execution-and-stale-evidence or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#offline-gradle-plugin-resolution-reports-bounded-process-evidence or " +
        "@REQ-JAVA-GRADLE-CONTEXT-001#failed-gradle-export-reports-bounded-redacted-process-cause or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-26-diagnostics-use-the-selected-platform or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-26-v7-class-rename-retains-managed-authority or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-26-v7-navigation-consumes-the-verified-sdk or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-8-11-1-jpms-context-uses-public-build-tree-identity or @REQ-JAVA-GRADLE-CONTEXT-001#included-gradle-app-selection-retains-build-root-project-path-and-project-directory or @REQ-JAVA-GRADLE-CONTEXT-001#aggregate-gradle-context-retains-qualified-workspace-identities-across-repeated-sync or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-26-context-binds-source-to-selected-jdk-platform or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-27-metadata-is-available-with-unqualified-source-analysis or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-27-v7-metadata-remains-available-with-unqualified-source-analysis or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-27-v7-source-owner-is-available-with-unqualified-semantic-analysis or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-27-definition-query-returns-the-typed-source-analysis-refusal or @REQ-JAVA-PROJECT-CONTEXT-001#gradle-release-27-is-refused-without-lower-source-fallback or @REQ-JAVA-27-SEMANTIC-ANALYSIS-001#gradle-java-27-definition-and-class-rename-refuse-unqualified-source-analysis",
)
class JavaGradle8111EffectiveContextCucumberTest
