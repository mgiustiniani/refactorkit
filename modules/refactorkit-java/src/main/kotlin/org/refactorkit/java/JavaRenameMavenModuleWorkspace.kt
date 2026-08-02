package org.refactorkit.java

import org.refactorkit.core.FileEdit
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.java.JavaRenameMavenModuleContract.DESCRIPTOR_UNAVAILABLE
import org.refactorkit.java.JavaRenameMavenModuleContract.FIXTURE_REPOSITORY
import org.refactorkit.java.JavaRenameMavenModuleContract.MAX_COPY_ENTRIES
import org.refactorkit.java.JavaRenameMavenModuleContract.MAX_COPY_FILE_BYTES
import org.refactorkit.java.JavaRenameMavenModuleContract.MAX_COPY_TOTAL_BYTES
import org.refactorkit.java.JavaRenameMavenModuleContract.PlannerRefusal
import org.refactorkit.java.JavaRenameMavenModuleContract.SOURCE_UNRECOGNIZED
import org.refactorkit.java.JavaRenameMavenModuleContract.TEMP_DELETE_ATTEMPTS
import org.refactorkit.java.JavaRenameMavenModuleContract.TEMP_DELETE_RETRY_MILLIS
import org.refactorkit.java.JavaRenameMavenModuleContract.hashStrings
import org.refactorkit.java.JavaRenameMavenModuleContract.pathString
import org.refactorkit.java.JavaRenameMavenModuleContract.refuse
import org.refactorkit.java.JavaRenameMavenModuleContract.sha256
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator
import java.util.EnumSet

/** Owns bounded no-follow workspace copying, tracked-image reconciliation, and ordered staging edits. */
internal object JavaRenameMavenModuleWorkspace {
    fun <T> withWorkspaceCopy(sourceRoot: Path, action: (Path, Path) -> T): T {
        val temporary = Files.createTempDirectory("refactorkit-java-rename-maven-module-")
        val workspace = temporary.resolve("workspace")
        val emptyRepository = temporary.resolve("offline-empty-repository")
        try {
            copyNoFollow(sourceRoot.toAbsolutePath().normalize(), workspace)
            Files.createDirectory(emptyRepository)
            return action(workspace, emptyRepository)
        } finally {
            deleteNoFollow(temporary)
        }
    }

    fun exactSourceTrackedFiles(snapshot: ProjectSnapshot, oldDir: Path, root: Path): List<SourceFile> {
        val tracked = snapshot.trackedFiles.filter { it.path.normalize().startsWith(oldDir) }
            .sortedBy { pathString(it.path) }
        if (tracked.none { it.path.normalize() == oldDir.resolve("pom.xml") }) {
            refuse(DESCRIPTOR_UNAVAILABLE, "The source child POM is not tracked by the immutable snapshot")
        }
        val physical = linkedSetOf<Path>()
        val sourceRoot = root.resolve(oldDir)
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceRoot)) {
            refuse(SOURCE_UNRECOGNIZED, "The source module is not a no-follow direct directory: $oldDir")
        }
        try {
            Files.walkFileTree(
                sourceRoot,
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isSymbolicLink || Files.isSymbolicLink(dir)) {
                            refuse(SOURCE_UNRECOGNIZED, "Symbolic-link source directory is refused")
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isSymbolicLink || Files.isSymbolicLink(file) || !attrs.isRegularFile) {
                            refuse(SOURCE_UNRECOGNIZED, "Non-regular or symbolic-link source content is refused")
                        }
                        physical.add(root.relativize(file).normalize())
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (refusal: PlannerRefusal) {
            throw refusal
        } catch (_: Exception) {
            refuse(SOURCE_UNRECOGNIZED, "The source module inventory cannot be read without following links")
        }
        if (physical != tracked.mapTo(linkedSetOf()) { it.path.normalize() }) {
            refuse(
                SOURCE_UNRECOGNIZED,
                "Every source-module regular file must be present in the immutable tracked snapshot before rename",
            )
        }
        return tracked
    }

    fun reconcileTrackedSnapshot(workspace: Path, emptyRepository: Path, candidate: ProjectSnapshot) {
        val observed = scanOffline(workspace, emptyRepository)
        val expectedByPath = candidate.trackedFiles.associateBy { it.path.normalize() }
        observed.trackedFiles.map { it.path.normalize() }.filterNot(expectedByPath::containsKey).forEach { path ->
            Files.delete(workspace.resolve(path))
        }
        expectedByPath.values.sortedBy { pathString(it.path) }.forEach { source ->
            val target = workspace.resolve(source.path.normalize()).normalize()
            require(target.startsWith(workspace) && !source.path.isAbsolute && !source.path.normalize().startsWith("..")) {
                "Candidate tracked path escapes the staged workspace"
            }
            Files.createDirectories(requireNotNull(target.parent))
            Files.writeString(
                target,
                source.content,
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
    }

    fun applyInOrder(workspace: Path, edit: WorkspaceEdit) {
        edit.edits.forEach { entry ->
            val source = resolveInside(workspace, entry.path)
            when (entry) {
                is FileEdit.Modify -> {
                    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
                    val updated = TextEdits.apply(Files.readString(source), entry.textEdits)
                    Files.writeString(source, updated, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                }
                is FileEdit.Rename -> {
                    val destination = resolveInside(workspace, entry.newPath)
                    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
                    require(Files.notExists(destination, LinkOption.NOFOLLOW_LINKS))
                    Files.createDirectories(requireNotNull(destination.parent))
                    Files.move(source, destination)
                }
                is FileEdit.Create, is FileEdit.Delete -> error("Maven module rename admits only Modify then Rename edits")
            }
        }
    }

    private fun copyNoFollow(source: Path, target: Path) {
        require(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
        var entries = 0
        var bytes = 0L
        Files.walkFileTree(
            source,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symbolic-link directory refused" }
                    require(++entries <= MAX_COPY_ENTRIES) { "Workspace exceeds bounded copy entry count" }
                    val destination = target.resolve(source.relativize(dir).toString()).normalize()
                    require(destination.startsWith(target.normalize())) { "Copy directory escapes staging" }
                    Files.createDirectories(destination)
                    copyPermissions(dir, destination)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file) && attrs.isRegularFile) {
                        "Non-regular or symbolic-link workspace entry refused"
                    }
                    require(++entries <= MAX_COPY_ENTRIES) { "Workspace exceeds bounded copy entry count" }
                    require(attrs.size() <= MAX_COPY_FILE_BYTES) { "Workspace file exceeds bounded copy size" }
                    bytes += attrs.size()
                    require(bytes <= MAX_COPY_TOTAL_BYTES) { "Workspace exceeds bounded copy byte count" }
                    val before = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    val content = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                        input.readNBytes(MAX_COPY_FILE_BYTES.toInt() + 1)
                    }
                    require(content.size.toLong() == before.size() && content.size.toLong() <= MAX_COPY_FILE_BYTES) {
                        "Workspace file changed or exceeded bounds during no-follow copy"
                    }
                    val after = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    require(stableIdentity(before, after)) { "Workspace file changed during no-follow copy" }
                    val destination = target.resolve(source.relativize(file).toString()).normalize()
                    require(destination.startsWith(target.normalize())) { "Copy file escapes staging" }
                    Files.createDirectories(requireNotNull(destination.parent))
                    Files.write(destination, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    copyPermissions(file, destination)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun stableIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        before.isRegularFile == after.isRegularFile && before.isSymbolicLink == after.isSymbolicLink &&
            before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
            before.fileKey() == after.fileKey()

    private fun copyPermissions(source: Path, target: Path) {
        runCatching {
            Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS))
        }
    }

    fun manifestHash(root: Path): String {
        val records = mutableListOf<String>()
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = root.relativize(dir).normalize()
                    if (relative.nameCount > 0 && relative.getName(0).toString() == ".refactorkit") {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir))
                    records += "D:${pathString(relative).ifBlank { "." }}"
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file) && attrs.isRegularFile)
                    val relative = root.relativize(file).normalize()
                    val content = Files.readAllBytes(file)
                    records += "F:${pathString(relative)}:${content.size}:${sha256(content)}"
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return hashStrings(records.sorted())
    }

    fun scanOffline(workspace: Path, emptyRepository: Path): ProjectSnapshot {
        val fixtureRepository = workspace.resolve(FIXTURE_REPOSITORY)
        val repository = if (Files.exists(fixtureRepository, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(fixtureRepository, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(fixtureRepository)) {
                "Fixture-local Maven repository is not a no-follow directory"
            }
            fixtureRepository
        } else {
            emptyRepository
        }
        return JavaProjectScanner(
            allowNetworkDependencyResolution = false,
            localMavenRepository = repository,
        ).scan(workspace)
    }

    private fun deleteNoFollow(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        var lastFailure: Exception? = null
        repeat(TEMP_DELETE_ATTEMPTS) { attempt ->
            try {
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { path ->
                        require(!Files.isSymbolicLink(path)) { "Staged cleanup encountered a symbolic link" }
                        Files.deleteIfExists(path)
                    }
                }
                return
            } catch (failure: Exception) {
                lastFailure = failure
                if (attempt + 1 < TEMP_DELETE_ATTEMPTS) Thread.sleep(TEMP_DELETE_RETRY_MILLIS)
            }
        }
        throw IllegalStateException("Cannot remove the bounded staged Maven workspace", lastFailure)
    }

    fun resolveInside(root: Path, relative: Path): Path {
        val normalized = relative.normalize()
        require(!normalized.isAbsolute && !normalized.startsWith("..")) { "Workspace edit path escapes staging" }
        val resolved = root.resolve(normalized).normalize()
        require(resolved.startsWith(root.normalize())) { "Workspace edit path escapes staging" }
        return resolved
    }
}
