package org.refactorkit.java.previewcommand

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-refactoring-preview-command-boundary.feature")
@SelectClasspathResource("java-maven-ownership-jpms-authority.feature")
@SelectClasspathResource("java-inline-variable-literal-preview.feature")
@SelectClasspathResource("java-effective-project-context.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.java.previewcommand")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-JAVA-PROJECT-CONTEXT-001#native-java25-launch-facts-retain-compiler-owners-methods-and-construction or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#inherited-profile-keeps-classic-candidates-in-a-modern-java25-source-scope or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#native-java25-hierarchy-facts-preserve-member-selection-and-declared-shapes or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#native-launch-acquisition-refuses-invalid-inputs-without-partial-inventories or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#native-compiler-selection-matches-the-selected-java25-launcher or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#native-construction-and-precedence-selection-agrees-with-java25-launching or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#native-main-declarations-retain-source-ranges-and-attested-binary-origins or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#native-entrypoint-context-preserves-provider-closure-and-snapshot-boundaries or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#versioned-native-entrypoint-responses-preserve-classic-contract-and-exact-launch-facts or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#versioned-native-cursor-selection-retains-innermost-owners-and-utf16-guards or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#versioned-native-discovery-enumerates-launchable-types-and-explicit-construction-exclusions or " +
        "@REQ-JAVA-PROJECT-CONTEXT-001#native-entrypoint-capabilities-separate-versioned-profiles-from-legacy-flags or " +
        "@REQ-JAVA-PREVIEW-COMMAND-001 or " +
        "@REQ-JAVA-PREVIEW-COMMAND-002 or " +
        "@REQ-JAVA-PREVIEW-COMMAND-004 or " +
        "@REQ-JAVA-MAVEN-OWNERSHIP-001 or " +
        "@REQ-JAVA-INLINE-VARIABLE-LITERAL-001#inline-all-binding-matched-reads-of-a-local-primitive-int-literal",
)
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-refactoring-preview-command-boundary.json",
)
class JavaRefactoringPreviewCommandCucumberTest
