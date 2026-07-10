package org.refactorkit.core

/**
 * Public version metadata exposed by CLI, daemon, LSP, and MCP surfaces.
 *
 * VERSION tracks the next development snapshot after the latest published
 * release. API_VERSION tracks the currently documented integration-contract
 * baseline for beta consumers.
 */
object RefactorKitVersion {
    const val NAME = "RefactorKit"
    const val VERSION = "0.3.0-SNAPSHOT"
    const val API_VERSION = "0.2"
}
