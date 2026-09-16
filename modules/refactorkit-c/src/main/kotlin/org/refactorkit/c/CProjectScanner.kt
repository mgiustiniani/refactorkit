package org.refactorkit.c

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
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
class CProjectScanner {

    fun scan(root: Path): ProjectSnapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val paths = Files.walk(normalizedRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }.map { it }.toList()
        }
        val sources = paths
            .filter { isCSource(it) }
            .map { path ->
                val rel = ProtocolPath.serialize(normalizedRoot.relativize(path))
                SourceFile(Path.of(rel), Files.readString(path), languageIdFor(path))
            }
            .sortedBy { it.path.toString() }
        return ProjectSnapshot(Workspace(normalizedRoot), emptyList(), sources)
    }

    private fun languageIdFor(path: Path): String {
        val name = path.fileName.toString().lowercase()
        return when {
            name.endsWith(".m") || name.endsWith(".mm") -> "objective-c"
            name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx") ||
                name.endsWith(".hh") || name.endsWith(".hpp") || name.endsWith(".hxx") -> "cpp"
            else -> "c"
        }
    }

    private fun isCSource(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.endsWith(".c") || name.endsWith(".h") ||
            name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx") ||
            name.endsWith(".hh") || name.endsWith(".hpp") || name.endsWith(".hxx") ||
            name.endsWith(".m") || name.endsWith(".mm")
    }
}
