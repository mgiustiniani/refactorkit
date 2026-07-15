package org.refactorkit.core

import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Canonical JSON/protocol representation for workspace-relative paths. */
object ProtocolPath {
    fun parseRelative(raw: String): Path {
        require(raw.length in 1..MAX_PATH_CHARS && '\u0000' !in raw) { "protocol path is invalid" }
        require('\\' !in raw && !WINDOWS_DRIVE.containsMatchIn(raw)) {
            "protocol path must use workspace-relative '/' form"
        }
        val segments = raw.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "protocol path must be canonical and workspace-relative"
        }
        val parsed = try {
            Path.of(raw)
        } catch (_: InvalidPathException) {
            throw IllegalArgumentException("protocol path is invalid")
        }
        require(!parsed.isAbsolute && parsed.toString().isNotBlank() && !parsed.normalize().startsWith("..")) {
            "protocol path must be workspace-relative"
        }
        return parsed.normalize()
    }

    fun serialize(path: Path): String = path.normalize().toString().replace('\\', '/')

    private const val MAX_PATH_CHARS = 4_096
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
}
