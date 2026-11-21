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
@SelectClasspathResource("java-native-diagnostic-source-ownership.feature")
@SelectClasspathResource("java-immutable-editor-overlay.feature")
@SelectClasspathResource("java-editor-mutation-preview.feature")
@SelectClasspathResource("java-27-maven-semantic-analysis-refusal.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.previewcommand")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-JAVA-PROJECT-CONTEXT-001#native-compiler-diagnostics-retain-explicit-source-set-ownership or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#native-sdk26-api-authority-retains-versioned-schema-boundaries or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#java26-editor-diagnostics-use-the-selected-compiler-and-platform or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#java25-editor-diagnostics-retain-native-scope-and-query-authority or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#java25-editor-diagnostics-use-the-selected-compiler-for-implicit-type-names or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#legacy-context-source26-is-refused-without-clamping-sdk27-or-versioned-metadata or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#native-sdk27-api-profiles-satisfy-versioned-context-schemas-without-source27-admission or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#sdk27-api-authority-retains-evaluated-gradle-and-declared-release-ownership or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#sdk27-native-api-context-keeps-older-source-grammar-and-release-authority-distinct or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-context-publishes-complete-aggregator-and-parent-input-evidence or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#evaluated-gradle-source-roots-retain-provenance-for-editor-diagnostics or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#java-compiler-diagnostics-close-native-cross-source-type-families or @REQ-JAVA-EDITOR-OVERLAY-001#java-editor-queries-analyze-closed-sdk-overlays-or-refuse-stale-document-versions or @REQ-JAVA-PROJECT-CONTEXT-001#java-editor-compiler-diagnostics-retain-selected-sdk-and-closed-frame or @REQ-JAVA-PROJECT-CONTEXT-001#dependency-source-navigation-is-context-bound-read-only-and-honest or @REQ-JAVA-PROJECT-CONTEXT-001#dependency-declaration-ranges-require-exact-attested-jvm-identity or @REQ-JAVA-PROJECT-CONTEXT-001#editor-dependency-source-navigation-retains-the-exact-closed-overlay or @REQ-JAVA-PROJECT-CONTEXT-001#selected-sdk-platform-declarations-retain-exact-current-release-source-authority or @REQ-JAVA-PROJECT-CONTEXT-001#sdk-source-editor-reads-retain-the-selected-cross-document-platform-binding or @REQ-JAVA-PROJECT-CONTEXT-001#platform-source-reads-refuse-sdk-archive-drift-and-inconsistent-zip-evidence or @REQ-JAVA-PROJECT-CONTEXT-001#platform-source-metadata-never-associates-an-unrelated-or-unqualified-source-owner or @REQ-JAVA-PROJECT-CONTEXT-001#source-navigation-selects-the-owning-compiler-api-profile or @REQ-JAVA-PROJECT-CONTEXT-001#standalone-java-diagnostics-selects-explicit-sdk-apis-without-runtime-inference or @REQ-JAVA-PROJECT-CONTEXT-001#java-context-isolates-language-providers-without-hiding-workspace-inputs or @REQ-JAVA-PROJECT-CONTEXT-001#maven-source-target-context-attests-native-sdk-apis-separately-from-release or @REQ-JAVA-PROJECT-CONTEXT-001#declared-plain-java-context-owns-roots-classpath-compiler-and-sdk or @REQ-JAVA-PROJECT-CONTEXT-001#provider-owned-entry-point-discovery-selects-the-native-enclosing-type or @REQ-JAVA-PROJECT-CONTEXT-001#provider-owned-entry-point-discovery-uses-selected-sdk-and-effective-target or @REQ-JAVA-PROJECT-CONTEXT-001#provider-owned-entry-point-query-retains-native-wire-bounds-and-cancellation or @REQ-JAVA-PROJECT-CONTEXT-001#provider-owned-entry-point-discovery-pins-scope-sdk-and-snapshot or @REQ-JAVA-EDITOR-PREVIEW-001#sdk-26-editor-preview-attests-the-acquired-context-schema or @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-26-v7-class-rename-retains-managed-authority or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-java-26-diagnostics-use-the-selected-platform or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-java-26-v7-navigation-consumes-the-verified-sdk or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#configured-maven-build-helper-add-source-roots-are-advertised-with-exact-owner-or-refused or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-java-26-context-binds-source-to-selected-jdk-platform or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-metadata-is-available-with-unqualified-source-analysis or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-v7-metadata-remains-available-with-unqualified-source-analysis or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-reactor-metadata-remains-available-with-unqualified-source-analysis or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-v7-source-owner-is-available-with-unqualified-semantic-analysis or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-v7-symbol-analysis-uses-the-qualified-jdt-platform-or-refuses or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-release-27-is-refused-without-lower-source-fallback or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-runtime-selection-is-discoverable-before-project-open or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-source-target-context-distinguishes-compiler-facts-from-sdk-selection or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-test-jvm-settings-are-source-set-bound-and-fail-closed or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-test-jvm-plugin-defaults-require-a-versioned-origin-and-a-clean-argline-layer or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-test-jvm-settings-inherit-from-one-literal-workspace-parent or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-test-jvm-build-model-retains-per-execution-selection or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-test-executions-expose-the-selected-jvm-with-bounded-provenance or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#active-failsafe-profile-binds-test-jvm-facts-to-its-execution or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-reactor-annotation-processor-context-projects-workspace-outputs-and-generated-sources or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#maven-reactor-annotation-processor-context-refreshes-after-workspace-output-materializes or " +
        "@REQ-JAVA-27-SEMANTIC-ANALYSIS-001#maven-java-27-definition-and-class-rename-refuse-unqualified-source-analysis or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#selected-jdk-8-attests-the-legacy-platform-for-maven-and-gradle or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#gradle-processor-options-retain-the-selected-java-api-platform",
)
class MavenBuildHelperProjectContextCucumberTest
