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
            !settingsPattern(settings.path.fileName.toString()).matches(settings.content)
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
        val origins = mutableSetOf<String>()
        val edits = mutableListOf<FileEdit.Modify>()
        for (script in scripts) {
            val directory = script.path.parent ?: Path.of("")
            val module = modulesByDirectory[directory]
            if (module == null && directory != buildRoot) return null
            val physicalFile = workspace.resolve(script.path)
            if (!Files.isRegularFile(physicalFile, LinkOption.NOFOLLOW_LINKS) ||
                runCatching { physicalFile.toRealPath() }.getOrNull() != workspaceReal.resolve(script.path)
            ) return null
            val match = buildPatterns(script.path.fileName.toString()).firstNotNullOfOrNull {
                it.matchEntire(script.content)
            } ?: return null
            val path = match.groups["path"] ?: continue
            if (path.value != oldPath || module == null || module !in consumers || !origins.add(module.id)) return null
            val range = SourceRange(
                TextEdits.positionForOffset(script.content, path.range.first),
                TextEdits.positionForOffset(script.content, path.range.last + 1),
            )
            edits += FileEdit.Modify(script.path, listOf(TextEdit(range, newPath)))
        }
        if (origins != consumers.mapTo(mutableSetOf()) { it.id }) return null
        return Evidence(edits, scripts)
    }

    private const val path = """:[A-Za-z0-9._:-]+"""
    private const val name = """[A-Za-z0-9._-]+"""
    private const val separator = """(?:[ \t]*;[ \t\r\n]*|[ \t]*\r?\n[ \t\r\n]*)"""
    private const val compatibility = """(?:sourceCompatibility|targetCompatibility)\s*=\s*JavaVersion\.VERSION_[0-9_]+"""
    private val javaBlock = """(?:java\s*\{\s*$compatibility(?:$separator$compatibility)?\s*;?\s*\}\s*)?"""
    private val kotlinSettings = Regex("""\s*(?:rootProject\.name\s*=\s*"$name"$separator)?include\s*\(\s*"$path"(?:\s*,\s*"$path")*\s*\)\s*;?\s*""")
    private val groovyName = """(?:"$name"|'$name')"""
    private val groovyPath = """(?:"$path"|'$path')"""
    private val groovyPaths = """$groovyPath(?:\s*,\s*$groovyPath)*"""
    private val groovySettings = Regex("""\s*(?:rootProject\.name\s*=\s*$groovyName$separator)?include(?:\s+$groovyPaths|\s*\(\s*$groovyPaths\s*\))\s*;?\s*""")
    private val kotlinBuild = Regex("""\s*(?:plugins\s*\{\s*(?:java|`java-library`|base)\s*;?\s*\}\s*)?$javaBlock(?:dependencies\s*\{\s*implementation\s*\(\s*project\s*\(\s*"(?<path>$path)"\s*\)\s*\)\s*;?\s*\}\s*)?""")
    private const val pluginId = """(?:java(?:-library)?|base)"""
    private val groovyPlugin = """(?:'$pluginId'|"$pluginId")"""
    private val groovyPrefix = """\s*(?:plugins\s*\{\s*id(?:\s+$groovyPlugin|\s*\(\s*$groovyPlugin\s*\))\s*;?\s*\}\s*)?$javaBlock"""
    private val groovyProject = """project\s*\(\s*(?<quote>['"])(?<path>$path)\k<quote>\s*\)"""
    private val groovyBuilds = listOf(
        Regex("""$groovyPrefix(?:dependencies\s*\{\s*implementation\s+$groovyProject\s*;?\s*\}\s*)?"""),
        Regex("""$groovyPrefix(?:dependencies\s*\{\s*implementation\s*\(\s*$groovyProject\s*\)\s*;?\s*\}\s*)?"""),
    )

    private fun settingsPattern(name: String) = if (name.endsWith(".kts")) kotlinSettings else groovySettings
    private fun buildPatterns(name: String) = if (name.endsWith(".kts")) listOf(kotlinBuild) else groovyBuilds
}
