package org.refactorkit.cli.navigation

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-annotated-member-navigation.feature")
@SelectClasspathResource("java-semantic-navigation-queries.feature")
@SelectClasspathResource("java-lsp-implementation-navigation.feature")
@SelectClasspathResource("java-lsp-custom-signature-rename.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.navigation")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "@REQ-JAVA-ANNOTATED-NAVIGATION-001 or @REQ-JAVA-SEMANTIC-NAVIGATION-001#java-position-queries-return-compiler-bound-types-and-call-sites or @REQ-JAVA-SEMANTIC-NAVIGATION-001#source-only-jdt-analysis-resolves-java-platform-types or @REQ-JAVA-SEMANTIC-NAVIGATION-001#maven-dependency-call-hierarchy-preserves-overloaded-method-identity or @REQ-JAVA-SEMANTIC-NAVIGATION-001#gradle-dependency-call-hierarchy-preserves-overloaded-method-identity or @REQ-JAVA-SEMANTIC-NAVIGATION-001#lsp-implementation-requests-resolve-dirty-source-types-and-method-overrides or @REQ-JAVA-LSP-SIGNATURE-RENAME-001#open-dirty-parameter-rename-is-a-versioned-client-managed-lsp-edit",
)
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,json:build/reports/cucumber/java-navigation.json")
class JavaNavigationCucumberTest
