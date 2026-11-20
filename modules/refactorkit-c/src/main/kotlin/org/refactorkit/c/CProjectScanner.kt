package org.refactorkit.c

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Workspace
import java.nio.file.Path

/**
 * Offline structural scan of a C-family source tree.
 *
 * Builds a [ProjectSnapshot] containing only translation-unit sources
 * (`.c`, `.h`, `.cc`, `.cpp`, `.cxx`, `.hh`, `.hpp`, `.hxx`, `.m`, `.mm`).
 * Paths are workspace-relative in canonical `/` form and each source carries a
 * C language id (`c`, `cpp` or `objective-c`) so the deterministic C planners can
 * locate the selected file and enumerate component members.
 *
 * No external toolchain is invoked: this is the structural baseline consumed by
 * the golden harness when a request targets a C operation.
 */
class CProjectScanner(private val policy: CProjectScanPolicy = CProjectScanPolicy()) {

    fun scan(root: Path): ProjectSnapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val inventory = CSourceSnapshotReader(policy).capture(normalizedRoot)
        val sources = inventory.sources
        val capturedBuildInputs = CCompilationSnapshotCapture.capture(
            CMakeManifestCapture.capture(ProjectSnapshot(Workspace(normalizedRoot), emptyList(), sources)),
        )
        return CFormattingStyleSnapshot.capture(capturedBuildInputs, inventory.regularPaths)
    }

}
