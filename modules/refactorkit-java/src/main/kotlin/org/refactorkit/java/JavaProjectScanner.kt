package org.refactorkit.java

import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import java.util.stream.Collectors

class JavaProjectScanner {
    fun scan(root: Path): ProjectSnapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val moduleRoots = detectModuleRoots(normalizedRoot).ifEmpty { listOf(normalizedRoot) }
        val modules = moduleRoots.map { moduleRoot ->
            val sourceRoots = conventionalSourceRoots(moduleRoot)
            Module(
                name = moduleName(normalizedRoot, moduleRoot),
                root = moduleRoot,
                sourceRoots = sourceRoots.map(normalizedRoot::relativize),
                classpathEntries = conventionalClasspathEntries(moduleRoot).map(normalizedRoot::relativize),
            )
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

        val classpathEvidence = buildList {
            modules.flatMap(Module::classpathEntries).forEach { path ->
                add(ClasspathEvidence.capture(normalizedRoot, path, ClasspathEvidenceKind.ENTRY))
            }
            moduleRoots.forEach { moduleRoot ->
                conventionalOutputDirectories(moduleRoot).forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, normalizedRoot.relativize(path), ClasspathEvidenceKind.ENTRY))
                }
                localJarDirectories(moduleRoot).forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, normalizedRoot.relativize(path), ClasspathEvidenceKind.JAR_DIRECTORY))
                }
                classpathDeclarationFiles(moduleRoot).forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, normalizedRoot.relativize(path), ClasspathEvidenceKind.DECLARATION_FILE))
                }
            }
        }.distinctBy { it.path to it.kind }.sortedWith(
            compareBy<ClasspathEvidence> { it.path.toString() }.thenBy { it.kind.name },
        )

        return ProjectSnapshot(
            workspace = Workspace(normalizedRoot),
            modules = modules,
            files = files,
            sourceExtensions = setOf("java"),
            ignoredDirectories = emptySet(),
            classpathEvidence = classpathEvidence,
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

    private fun conventionalOutputDirectories(root: Path): List<Path> = listOf(
        root.resolve("target/classes"),
        root.resolve("target/test-classes"),
        root.resolve("build/classes/java/main"),
        root.resolve("build/classes/java/test"),
    )

    private fun localJarDirectories(root: Path): List<Path> = listOf(root.resolve("lib"), root.resolve("libs"))

    private fun classpathDeclarationFiles(root: Path): List<Path> = listOf(
        root.resolve(".refactorkit/classpath"),
        root.resolve("target/classpath.txt"),
        root.resolve("build/classpath.txt"),
    )

    private fun conventionalClasspathEntries(root: Path): List<Path> {
        val outputDirectories = conventionalOutputDirectories(root).filter { it.exists() && it.isDirectory() }
        val localJars = localJarDirectories(root)
            .filter { it.exists() && it.isDirectory() }
            .flatMap { directory ->
                Files.walk(directory).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                        .collect(Collectors.toList())
                }
            }
        return (outputDirectories + localJars + declaredClasspathEntries(root))
            .distinct()
            .sortedBy { it.toString() }
    }

    private fun declaredClasspathEntries(root: Path): List<Path> = classpathDeclarationFiles(root)
        .filter { it.exists() && Files.isRegularFile(it) }
        .flatMap { classpathFile ->
            runCatching {
                classpathFile.readText().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .flatMap { it.split(File.pathSeparatorChar).asSequence() }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { entry ->
                        val path = Path.of(entry)
                        if (path.isAbsolute) path.normalize() else root.resolve(path).normalize()
                    }
                    .filter { path ->
                        path.exists() && (path.isDirectory() || path.fileName.toString().endsWith(".jar"))
                    }
                    .toList()
            }.getOrDefault(emptyList())
        }

    private fun moduleName(workspaceRoot: Path, moduleRoot: Path): String {
        val relative = workspaceRoot.relativize(moduleRoot)
        return if (relative.toString().isBlank()) workspaceRoot.fileName?.toString() ?: "root" else relative.toString().replace('\\', ':').replace('/', ':')
    }

    private fun isIgnoredDirectory(workspaceRoot: Path, path: Path): Boolean {
        val relative = workspaceRoot.relativize(path).toString().replace('\\', '/')
        return relative.split('/').any { it in setOf("build", "target", ".gradle", ".git", ".refactorkit") }
    }
}
