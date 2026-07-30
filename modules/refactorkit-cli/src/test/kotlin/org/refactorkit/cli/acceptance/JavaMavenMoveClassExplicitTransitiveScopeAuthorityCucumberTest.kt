package org.refactorkit.cli.acceptance

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-maven-move-class-apply-authority.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.acceptance")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@REQ-JAVA-MAVEN-MOVE-AUTH-009")
class JavaMavenMoveClassExplicitTransitiveScopeAuthorityCucumberTest
