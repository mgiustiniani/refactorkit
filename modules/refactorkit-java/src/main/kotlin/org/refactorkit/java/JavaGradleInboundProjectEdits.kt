package org.refactorkit.java

import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModule
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.FileEdit
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Binds the evaluated inbound edges to a closed set of literal local build-script origins. */
internal object JavaGradleInboundProjectEdits {
    val buildNames = setOf("build.gradle", "build.gradle.kts")
    val settingsNames = setOf("settings.gradle", "settings.gradle.kts")

    class Evidence(val edits: List<FileEdit.Modify>, val scripts: List<SourceFile>)

    fun capture(
        snapshot: ProjectSnapshot,
        model: BuildModel,
        target: BuildModule,
        settings: SourceFile,
        oldPath: String,
        newPath: String,
    ): Evidence? {
        val consumers = model.modules.filter { module ->
            module.sourceSets.any { set -> set.moduleDependencies.any { it.targetModuleId == target.id } }
        }
        if (consumers.isEmpty()) return Evidence(emptyList(), emptyList())
        val buildRoot = settings.path.parent ?: Path.of("")
        val opaqueSetup = snapshot.classpathEvidence.any {
            it.kind == ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT && it.path.isAbsolute &&
                it.path.fileName.toString().let { name -> name.endsWith(".gradle") || name.endsWith(".gradle.kts") }
        }
        if (model.attributes["gradle.compositeBuildCount"] != "1" || target in consumers ||
            opaqueSetup ||
            !GradleProjectRenameScript.settingsAreLocal(settings.path.fileName.toString(), settings.content)
        ) return null

        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        if (Files.exists(workspace.resolve(buildRoot).resolve("buildSrc"), LinkOption.NOFOLLOW_LINKS)) return null
        val workspaceReal = runCatching { workspace.toRealPath() }.getOrNull() ?: return null
        val scripts = snapshot.auxiliaryFiles.filter { file ->
            file.path.fileName.toString().endsWith(".gradle") || file.path.fileName.toString().endsWith(".gradle.kts")
        }.filter { it.path != settings.path }
        if (scripts.any { it.path.fileName.toString() !in buildNames } ||
            scripts.groupingBy { it.path.parent }.eachCount().values.any { it > 1 }
        ) return null

        val modulesByDirectory = model.modules.associateBy { workspace.relativize(it.root.normalize()) }
        val modulesByProject = model.modules.associateBy { it.attributes["gradle.projectPath"] }
        val ownersByDependency = mutableMapOf<String, MutableSet<String>>()
        val edits = mutableListOf<FileEdit.Modify>()
        for (script in scripts) {
            val directory = script.path.parent ?: Path.of("")
            val module = modulesByDirectory[directory]
            if (module == null && directory != buildRoot) return null
            val physicalFile = workspace.resolve(script.path)
            if (!Files.isRegularFile(physicalFile, LinkOption.NOFOLLOW_LINKS) ||
                runCatching { physicalFile.toRealPath() }.getOrNull() != workspaceReal.resolve(script.path)
            ) return null
            val declarations = GradleProjectRenameScript.dependencies(script.path.fileName.toString(), script.content)
                ?: return null
            if (declarations.isEmpty()) continue
            val owner = module ?: return null
            for (declaration in declarations) {
                val referenced = modulesByProject[declaration.path] ?: return null
                ownersByDependency.getOrPut(referenced.id, ::mutableSetOf).add(owner.id)
            }
            val selected = declarations.filter { it.path == oldPath }
            if (selected.isEmpty()) continue
            if (owner !in consumers || selected.map { it.configuration }.toSet().size != selected.size) return null
            edits += FileEdit.Modify(script.path, selected.map { declaration ->
                val range = SourceRange(
                    TextEdits.positionForOffset(script.content, declaration.range.first),
                    TextEdits.positionForOffset(script.content, declaration.range.last + 1),
                )
                TextEdit(range, newPath)
            })
        }
        if (edits.isEmpty() || !transitiveOwners(target.id, ownersByDependency).containsAll(consumers.map { it.id })) return null
        return Evidence(edits, scripts)
    }

    // The synchronized model reports the resolved closure; only direct source origins get edits.
    private fun transitiveOwners(target: String, owners: Map<String, Set<String>>): Set<String> {
        val reached = mutableSetOf(target)
        val pending = ArrayDeque(listOf(target))
        while (pending.isNotEmpty()) {
            for (consumer in owners[pending.removeFirst()].orEmpty()) {
                if (reached.add(consumer)) pending.addLast(consumer)
            }
        }
        return reached
    }
}
