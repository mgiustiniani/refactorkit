package org.refactorkit.testkit

/**
 * Parsed `expected-plan.json` from a golden test case directory.
 *
 * Only the fields present in the file are checked; absent fields are not asserted.
 */
data class GoldenExpectedPlan(
    /** Expected `status` field value, e.g. `"preview"` or `"refused"`. */
    val status: String? = null,
    /** Expected `operation` field value. */
    val operation: String? = null,
    /** If set, the actual plan summary must *contain* this string (case-insensitive). */
    val summaryContains: String? = null,
    /** Minimum number of files that must be in [PatchPlan.affectedFiles]. */
    val minAffectedFiles: Int = 0,
)
