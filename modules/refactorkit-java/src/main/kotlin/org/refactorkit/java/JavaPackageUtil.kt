package org.refactorkit.java

import java.nio.file.Path
import java.nio.file.Paths

object JavaPackageUtil {

    private val PACKAGE_REGEX = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")

    /** Extracts the package declaration from Java source, or "" if absent. */
    fun extractPackage(content: String): String =
        PACKAGE_REGEX.find(content)?.groupValues?.get(1) ?: ""

    /** Converts a package name to a relative directory path. */
    fun packageToPath(pkg: String): Path =
        Paths.get(pkg.replace('.', '/'))

    /** Converts a relative source path (e.g. src/main/java/com/example/Foo.java) to a package name. */
    fun pathToPackage(filePath: Path, sourceRoot: Path): String {
        val relative = try {
            sourceRoot.relativize(filePath)
        } catch (_: IllegalArgumentException) {
            filePath
        }
        val parent = relative.parent ?: return ""
        return parent.toString().replace('/', '.').replace('\\', '.')
    }

    /** Returns the simple class name from a FQN (e.g. "UserManager" from "com.example.UserManager"). */
    fun simpleName(fqn: String): String = fqn.substringAfterLast('.')

    /** Returns the package part of a FQN (e.g. "com.example" from "com.example.UserManager"). */
    fun packageOf(fqn: String): String {
        val last = fqn.lastIndexOf('.')
        return if (last < 0) "" else fqn.substring(0, last)
    }

    /** Rebuilds a FQN from package + simple name. */
    fun fqn(pkg: String, simpleName: String): String =
        if (pkg.isEmpty()) simpleName else "$pkg.$simpleName"

    /**
     * Given a source file path relative to the workspace root and a list of known
     * source roots (also relative), returns the best-matching source root.
     */
    fun detectSourceRoot(filePath: Path, sourceRoots: List<Path>): Path? =
        sourceRoots.firstOrNull { filePath.startsWith(it) }
}
