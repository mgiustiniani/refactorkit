package org.refactorkit.java

import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import java.util.stream.Collectors

class JavaProjectScanner {
    fun scan(root: Path): ProjectSnapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val moduleRoots = detectModuleRoots(normalizedRoot)
        val modules = moduleRoots.map { moduleRoot ->
            val sourceRoots = conventionalSourceRoots(moduleRoot)
            Module(
                name = moduleName(normalizedRoot, moduleRoot),
                root = moduleRoot,
                sourceRoots = sourceRoots.map(normalizedRoot::relativize),
            )
        }.ifEmpty {
            listOf(Module(name = normalizedRoot.fileName?.toString() ?: "root", root = normalizedRoot, sourceRoots = emptyList()))
        }

        val sourceRoots = modules.flatMap { module -> module.sourceRoots.map(normalizedRoot::resolve) }.distinct()
        val files = sourceRoots.flatMap { sourceRoot ->
            if (!sourceRoot.exists()) emptyList() else Files.walk(sourceRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                    .map { SourceFile(normalizedRoot.relativize(it), it.readText(), "java") }
                    .collect(Collectors.toList())
            }
        }.sortedBy { it.path.toString() }

        return ProjectSnapshot(
            workspace = Workspace(normalizedRoot),
            modules = modules,
            files = files,
        )
    }

    fun detectSourceRoots(root: Path): List<Path> {
        val conventionalRoots = conventionalSourceRoots(root)
        if (conventionalRoots.isNotEmpty()) return conventionalRoots

        return if (root.exists()) {
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isDirectory(it) && (it.endsWith("src/main/java") || it.endsWith("src/test/java")) }
                    .collect(Collectors.toList())
            }
        } else {
            emptyList()
        }
    }

    fun detectModuleRoots(root: Path): List<Path> {
        if (!root.exists()) return emptyList()
        return Files.walk(root).use { stream ->
            stream
                .filter { Files.isDirectory(it) && conventionalSourceRoots(it).isNotEmpty() }
                .filter { !isIgnoredDirectory(root, it) }
                .collect(Collectors.toList())
                .distinct()
                .sortedBy { it.toString() }
        }
    }

    private fun conventionalSourceRoots(root: Path): List<Path> = listOf(
        root.resolve("src/main/java"),
        root.resolve("src/test/java"),
    ).filter { it.exists() && it.isDirectory() }

    private fun moduleName(workspaceRoot: Path, moduleRoot: Path): String {
        val relative = workspaceRoot.relativize(moduleRoot)
        return if (relative.toString().isBlank()) workspaceRoot.fileName?.toString() ?: "root" else relative.toString().replace('\\', ':').replace('/', ':')
    }

    private fun isIgnoredDirectory(workspaceRoot: Path, path: Path): Boolean {
        val relative = workspaceRoot.relativize(path).toString().replace('\\', '/')
        return relative.split('/').any { it in setOf("build", "target", ".gradle", ".git", ".refactorkit") }
    }
}
