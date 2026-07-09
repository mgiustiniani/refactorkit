package org.refactorkit.testkit

/** Parsed `request.json` from a golden test case directory. */
data class GoldenRequest(
    val operation: String,
    val symbol: String? = null,
    val arguments: Map<String, String> = emptyMap(),
)
