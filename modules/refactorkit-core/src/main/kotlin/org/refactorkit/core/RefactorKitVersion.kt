package org.refactorkit.core

/**
 * Public version metadata exposed by CLI, daemon, LSP, and MCP surfaces.
 *
 * VERSION tracks the current implementation release. API_VERSION tracks the
 * currently documented integration-contract baseline for beta consumers.
 */
object RefactorKitVersion {
    const val NAME = "RefactorKit"
    const val VERSION = "0.4.0-SNAPSHOT"
    const val API_VERSION = "0.2"
}
