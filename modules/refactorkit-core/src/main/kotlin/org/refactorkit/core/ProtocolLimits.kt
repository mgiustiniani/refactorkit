package org.refactorkit.core

/** Versioned defensive limits for local stdio protocol surfaces. */
object ProtocolLimits {
    const val MAX_REQUEST_BYTES = 1_048_576
    const val MAX_LSP_FRAME_BYTES = 1_048_576
    const val MAX_PENDING_PLANS = 128
    const val MAX_SYMBOL_RESULTS = 200
    const val MAX_REFERENCE_RESULTS = 1_000
    const val MAX_SOURCE_FILE_BYTES = 10_485_760
    const val MAX_PREVIEW_DIFF_BYTES = 524_288
    const val MAX_PREVIEW_DIFF_FILES = 128
    const val MAX_PREVIEW_HUNKS_PER_FILE = 64
    const val MAX_PREVIEW_LINES_PER_HUNK = 2_000
    const val MAX_PREVIEW_DIAGNOSTICS = 500
    const val MAX_PREVIEW_DIAGNOSTIC_BYTES = 262_144
    const val MAX_DIAGNOSTIC_MESSAGE_CHARS = 4_096
    const val MAX_DAEMON_STDERR_BYTES = 65_536
}
