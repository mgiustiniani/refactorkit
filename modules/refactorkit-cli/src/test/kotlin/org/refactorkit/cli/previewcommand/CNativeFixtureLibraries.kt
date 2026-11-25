package org.refactorkit.cli.previewcommand

import java.nio.file.Files
import java.nio.file.Path

internal fun nativeFixtureLibffi(): Path {
    val candidates = listOf(
        Path.of("/usr/lib/x86_64-linux-gnu/libffi.so.8"),
        Path.of("/usr/lib/libffi.so.8"),
    )
    return candidates.firstOrNull { Files.isRegularFile(it) }
        ?: error("The native C fixture needs libffi.so.8 at one of: $candidates")
}
