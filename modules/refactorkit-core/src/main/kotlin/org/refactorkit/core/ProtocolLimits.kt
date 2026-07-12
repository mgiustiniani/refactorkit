package org.refactorkit.core

/** Versioned defensive limits for local stdio protocol surfaces. */
object ProtocolLimits {
    const val MAX_REQUEST_BYTES = 1_048_576
    const val MAX_LSP_FRAME_BYTES = 1_048_576
    const val MAX_PENDING_PLANS = 128
    const val MAX_SYMBOL_RESULTS = 200
    const val MAX_REFERENCE_RESULTS = 1_000
    const val MAX_SOURCE_FILE_BYTES = 10_485_760
}
