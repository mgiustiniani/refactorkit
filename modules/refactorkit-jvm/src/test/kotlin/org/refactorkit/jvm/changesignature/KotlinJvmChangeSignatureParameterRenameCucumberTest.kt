package org.refactorkit.jvm.changesignature

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Story BDD runner for features/kotlin-jvm-change-signature-parameter-rename.feature
 * (REQ-KOTLIN-CHANGE-SIGNATURE-001..003; 12 scenarios / 33 expanded cases across 8 scenario outlines:
 * 4 plain + 8 outlines).
 *
 * The glue uses the real K2 compiler toolchain (kotlin-compiler-embeddable-2.0.21,
 * jvmTarget 21, jdkToolchain 21) plus the JDT Java semantic analyzer, and drives the
 * production planners [org.refactorkit.kotlin.KotlinChangeSignaturePlanner] (REQ-001/REQ-002,
 * K2-level refusal codes) and [org.refactorkit.jvm.KotlinJvmChangeSignaturePlanner]
 * (REQ-003, mixed K2+JDT staged proof).
 *
 * Runner reconciled to the human-readable feature prose (feature SHA
 * e22312ccf99b3956029202bc7c0ee44a8205a27afe615334ddbed6bd410774f0): the step regexes
 * in [KotlinJvmChangeSignatureParameterRenameSteps] now match the rewritten domain/business
 * Given/When/Then wording while keeping the 17 inducible branches (12 refusals + 5 positive
 * real-behavior), 16 defensive SEMANTIC_PREVIEW branches (12 from approved change 011 + 4 REQ-001
 * family-incompleteness gates from approved change 012), and strengthened REQ-003 oracles.
 *
 * Every refusal scenario asserts that the DECLARED stable typed code (from the feature
 * Examples table) EQUALS the ACTUAL refusalCode the planner returned, so the suite FAILS on a
 * typed-code regression. Every observed declared-to-actual mapping is appended to
 * build/reports/cucumber/kotlin-jvm-change-signature-parameter-rename-codes.txt for reconciliation.
 * GREEN scenarios assert the real preview/apply/rollback behavior (edits, risk, warnings,
 * PatchEngine transaction, rollback byte equality), never a hard-coded success path.
 *
 * Approved change 011 supersedes 12 NON-INDUCIBLE defensive-gate refusal criteria. Those 12 reframed
 * defensive scenarios assert a genuine SEMANTIC_PREVIEW (read-only, no filesystem mutation) on a
 * clean compiler-proven fixture: the retained defensive gates cannot honestly fire, so the glue
 * drives the real production success path instead of a refusal. Approved change 012 extends that
 * supersession to 4 additional REQ-001 family-incompleteness criteria, which now also assert
 * SEMANTIC_PREVIEW success + read-only on the clean complete in-workspace family; the inducible
 * "lacking one exact parameter declaration at the selected ordinal" row keeps its refusal
 * kotlin.changeSignatureFamilyIncomplete. Approved change 013 retains the REQ-002 "duplicate ranges
 * refuse" criterion as a NON-INDUCIBLE defensive-gate, not an executable coalescence behavior:
 * production dedupes token ranges by the complete token location (path and full source range) (distinctBy { it.first }) before the range-invalid
 * check, which makes the duplicate-detection branch tautological (the size-vs-distinct-size check
 * cannot differ after distinctBy), and compiler parseUsages enforces unique keys, so no compiler
 * fixture can emit the same token range twice; candidate fixtures that try to induce a duplicate-range
 * refusal fail to compile. There is therefore NO executable coalescence step (no scenario step asserts
 * coalescence/dedupe behavior; the criterion is kept as a defensive-gate note only). The composite
 * token-range row drives the inducible missing/generated/mismatched case to
 * kotlin.changeSignatureRangeInvalid. The 17 inducible cases keep their real
 * refusal/positive branches unchanged.
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
