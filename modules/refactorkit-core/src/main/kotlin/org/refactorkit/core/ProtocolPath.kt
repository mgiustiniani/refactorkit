package org.refactorkit.core

import java.nio.file.Path

/** Canonical JSON/protocol representation for workspace-relative paths. */
object ProtocolPath {
    fun serialize(path: Path): String = path.normalize().toString().replace('\\', '/')
}
