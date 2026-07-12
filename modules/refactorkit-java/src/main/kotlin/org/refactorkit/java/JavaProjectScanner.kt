package org.refactorkit.java

import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.BuildDependency
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.Workspace
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

class JavaProjectScanner(
    private val allowNetworkDependencyResolution: Boolean = false,
    private val localMavenRepository: Path = Path.of(System.getProperty("user.home"), ".m2", "repository"),
) {
    fun scan(root: Path): ProjectSnapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val discoveredModuleRoots = detectModuleRoots(normalizedRoot)
        val pomFiles = findBuildFiles(normalizedRoot, "pom.xml")
        val mavenReactor = if (pomFiles.isNotEmpty()) {
            MavenEffectiveReactorBuilder(localMavenRepository, allowNetworkDependencyResolution).build(normalizedRoot, pomFiles)
        } else null
        val mavenByRoot = mavenReactor?.modules.orEmpty()
        val moduleRoots = (discoveredModuleRoots + mavenByRoot.values.filter { it.packaging != "pom" }.map(MavenModuleModel::root))
            .distinct().sortedBy(Path::toString).ifEmpty { listOf(normalizedRoot) }
        val moduleNames = moduleRoots.associateWith { moduleName(normalizedRoot, it) }
        val coordinateNames = mavenByRoot.values
            .filter { it.root in moduleRoots }
            .associate { it.coordinate to moduleNames.getValue(it.root) }
        val legacyIdentities = buildMap {
            moduleNames.forEach { (moduleRoot, name) ->
                put(name, name)
                mavenArtifactId(moduleRoot)?.let { put(it, name) }
            }
        }

        val initialModules = moduleRoots.map { moduleRoot ->
            val maven = mavenByRoot[moduleRoot]
            val mainRoots = if (maven != null) (conventionalMainSourceRoots(moduleRoot) + listOf(moduleRoot.resolve("src/main/java"))).distinct()
                else conventionalMainSourceRoots(moduleRoot)
            val testRoots = if (maven != null) (conventionalTestSourceRoots(moduleRoot) + listOf(moduleRoot.resolve("src/test/java"))).distinct()
                else conventionalTestSourceRoots(moduleRoot)
            val explicitGeneratedTest = generatedSourceRoots(moduleRoot, test = true)
            val discoveredGeneratedMain = generatedSourceRoots(moduleRoot, test = false)
            val pluginTestGenerated = discoveredGeneratedMain.filter { generatedRoot ->
                maven?.testGeneratedPathHints.orEmpty().any { hint ->
                    generatedRoot.any { component -> component.toString().contains(hint, ignoreCase = true) }
                }
            }
            val generatedMain = discoveredGeneratedMain - pluginTestGenerated.toSet()
            val generatedTest = (explicitGeneratedTest + pluginTestGenerated).distinct()
            val sourceRoots = (mainRoots + testRoots + generatedMain + generatedTest)
                .map(normalizedRoot::relativize).distinct()
            val conventionalMainOutputs = listOf(moduleRoot.resolve("target/classes"), moduleRoot.resolve("build/classes/java/main"))
                .filter { it.exists() && it.isDirectory() }
            val conventionalTestOutputs = listOf(moduleRoot.resolve("target/test-classes"), moduleRoot.resolve("build/classes/java/test"))
                .filter { it.exists() && it.isDirectory() }
            val localEntries = localJarEntries(moduleRoot) + declaredClasspathEntries(moduleRoot)
            val mainClasspath = if (maven?.modelFailure == null && maven != null) {
                (conventionalMainOutputs.map(normalizedRoot::relativize) + maven.mainArtifacts).distinct()
            } else {
                (conventionalMainOutputs + localEntries).map(normalizedRoot::relativize).distinct()
            }
            val testClasspath = if (maven?.modelFailure == null && maven != null) {
                (mainClasspath + conventionalTestOutputs.map(normalizedRoot::relativize) + maven.testArtifacts).distinct()
            } else {
                (mainClasspath + conventionalTestOutputs.map(normalizedRoot::relativize)).distinct()
            }
            val mainDependencies = maven?.takeIf { it.modelFailure == null }?.mainDependencies?.mapNotNull(coordinateNames::get)
                ?: detectModuleDependencies(moduleRoot, legacyIdentities)
            val testDependencies = maven?.takeIf { it.modelFailure == null }?.testDependencies?.mapNotNull(coordinateNames::get)
                ?: mainDependencies
            val sourceLevel = maven?.takeIf { it.modelFailure == null }?.sourceLevel ?: detectJavaSourceLevel(moduleRoot)
            val languageSettings = buildMap {
                put("java.sourceLevel", sourceLevel.toString())
                if (maven != null) {
                    put("java.buildSystem", "maven")
                    put("java.buildModel.status", if (maven.modelFailure == null) "available" else "unavailable")
                    maven.modelFailure?.let { put("java.buildModel.message", it) }
                    put("java.sourceLevel.status", if (maven.sourceLevel == null) "unavailable" else "available")
                    if (maven.sourceLevel == null) put("java.sourceLevel.message", "Effective Maven source level could not be resolved")
                    put("java.classpath.status", if (maven.missingArtifacts.isEmpty()) "available" else "unavailable")
                    if (maven.missingArtifacts.isNotEmpty()) {
                        val missing = maven.missingArtifacts.sorted()
                        val suffix = if (missing.size > 8) " (+${missing.size - 8} more)" else ""
                        put("java.classpath.message", "Offline artifacts unavailable: ${missing.take(8).joinToString(", ")}$suffix")
                    }
                } else if (listOf(moduleRoot.resolve("build.gradle"), moduleRoot.resolve("build.gradle.kts")).any { it.exists() }) {
                    put("java.buildSystem", "gradle")
                    put("java.buildModel.status", "partial")
                    put("java.buildModel.message", "Gradle metadata uses deterministic declarative heuristics; effective execution is disabled")
                } else {
                    put("java.buildSystem", "conventional")
                    put("java.buildModel.status", "partial")
                    put("java.buildModel.message", "No effective build descriptor model is available")
                }
            }
            Module(
                name = moduleNames.getValue(moduleRoot),
                root = moduleRoot,
                sourceRoots = sourceRoots,
                classpathEntries = (mainClasspath + testClasspath).distinct(),
                dependencies = (mainDependencies + testDependencies).distinct().sorted(),
                languageSettings = languageSettings,
                mainSourceRoots = (mainRoots + generatedMain).map(normalizedRoot::relativize),
                testSourceRoots = testRoots.map(normalizedRoot::relativize),
                generatedSourceRoots = generatedMain.map(normalizedRoot::relativize),
                generatedTestSourceRoots = generatedTest.map(normalizedRoot::relativize),
                mainClasspathEntries = mainClasspath,
                testClasspathEntries = testClasspath,
                mainDependencies = mainDependencies.distinct().sorted(),
                testDependencies = (mainDependencies + testDependencies).distinct().sorted(),
                mainOutputDirectories = listOf(moduleRoot.resolve("target/classes"), moduleRoot.resolve("build/classes/java/main"))
                    .map(normalizedRoot::relativize),
                testOutputDirectories = listOf(moduleRoot.resolve("target/test-classes"), moduleRoot.resolve("build/classes/java/test"))
                    .map(normalizedRoot::relativize),
            )
        }
        val initialByName = initialModules.associateBy(Module::name)
        fun unavailableDependency(module: Module, seen: Set<String> = emptySet()): String? {
            if (module.name in seen) return null
            return (module.mainDependencies + module.testDependencies).distinct().firstNotNullOfOrNull { dependencyName ->
                val dependency = initialByName[dependencyName] ?: return@firstNotNullOfOrNull null
                if (dependency.languageSettings["java.buildModel.status"] == "unavailable" ||
                    dependency.languageSettings["java.classpath.status"] == "unavailable" ||
                    dependency.languageSettings["java.sourceLevel.status"] == "unavailable") dependencyName
                else unavailableDependency(dependency, seen + module.name)
            }
        }
        val modules = initialModules.map { module ->
            val unavailable = unavailableDependency(module)
            if (unavailable == null || module.languageSettings["java.classpath.status"] == "unavailable") module
            else module.copy(languageSettings = module.languageSettings + mapOf(
                "java.classpath.status" to "unavailable",
                "java.classpath.message" to "Upstream reactor module '$unavailable' has unavailable analysis inputs",
            ))
        }

        val sourceRoots = modules.flatMap { module -> module.sourceRoots.map(normalizedRoot::resolve) }.distinct()
        val files = sourceRoots.flatMap { sourceRoot ->
            if (!sourceRoot.exists()) emptyList() else Files.walk(sourceRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                    .map { SourceFile(normalizedRoot.relativize(it), it.readText(), "java") }
                    .collect(Collectors.toList())
            }
        }.distinctBy(SourceFile::path).sortedBy { it.path.toString() }

        val classpathEvidence = buildList {
            modules.flatMap(Module::classpathEntries).forEach { path ->
                val kind = if (path.isAbsolute) ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT else ClasspathEvidenceKind.ENTRY
                add(ClasspathEvidence.capture(normalizedRoot, path, kind))
            }
            moduleRoots.forEach { moduleRoot ->
                conventionalOutputDirectories(moduleRoot).forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, normalizedRoot.relativize(path), ClasspathEvidenceKind.ENTRY))
                }
                localJarDirectories(moduleRoot).forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, normalizedRoot.relativize(path), ClasspathEvidenceKind.JAR_DIRECTORY))
                }
                (classpathDeclarationFiles(moduleRoot) + buildDescriptorFiles(moduleRoot) + formatterConfigurationFiles(moduleRoot)).forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, normalizedRoot.relativize(path), ClasspathEvidenceKind.DECLARATION_FILE))
                }
            }
            mavenReactor?.modules?.values.orEmpty().flatMap { it.modelInputs }
                .filterNot { it.startsWith(normalizedRoot) }.forEach { input ->
                    val path = evidencePath(normalizedRoot, input)
                    add(ClasspathEvidence.capture(normalizedRoot, path, ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT))
                }
            mavenReactor?.modules?.values.orEmpty().flatMap { it.importedBoms }.forEach { bom ->
                val path = evidencePath(normalizedRoot, bom)
                add(ClasspathEvidence.capture(normalizedRoot, path, ClasspathEvidenceKind.IMPORTED_BOM))
            }
        }.distinctBy { it.path.normalize() to it.kind }.sortedWith(
            compareBy<ClasspathEvidence> { it.path.toString() }.thenBy { it.kind.name },
        )

        return ProjectSnapshot(
            workspace = Workspace(normalizedRoot),
            modules = modules,
            files = files,
            sourceExtensions = setOf("java"),
            ignoredDirectories = ProjectSnapshot.DEFAULT_IGNORED_DIRECTORIES,
            classpathEvidence = classpathEvidence,
            buildModels = listOf(toBuildModel(modules)),
        )
    }

    private fun toBuildModel(modules: List<Module>): BuildModel {
        val diagnostics = modules.flatMap { module ->
            buildList {
                when (module.languageSettings["java.buildModel.status"]) {
                    "unavailable" -> add(BuildModelDiagnostic(
                        "buildModel.unavailable",
                        module.languageSettings["java.buildModel.message"] ?: "Build model unavailable",
                        module.name,
                    ))
                    "partial" -> add(BuildModelDiagnostic(
                        "buildModel.partial",
                        module.languageSettings["java.buildModel.message"] ?: "Build model is partial",
                        module.name,
                        org.refactorkit.core.Diagnostic.Severity.WARNING,
                    ))
                }
                if (module.languageSettings["java.sourceLevel.status"] == "unavailable") add(BuildModelDiagnostic(
                    "sourceLevel.unavailable",
                    module.languageSettings["java.sourceLevel.message"] ?: "Source level unavailable",
                    module.name,
                ))
                if (module.languageSettings["java.classpath.status"] == "unavailable") add(BuildModelDiagnostic(
                    "classpath.unavailable",
                    module.languageSettings["java.classpath.message"] ?: "Classpath unavailable",
                    module.name,
                ))
            }
        }
        val status = when {
            diagnostics.any { it.code == "buildModel.unavailable" } -> BuildModelStatus.UNAVAILABLE
            diagnostics.isNotEmpty() -> BuildModelStatus.PARTIAL
            else -> BuildModelStatus.AVAILABLE
        }
        val providerKinds = modules.mapNotNull { it.languageSettings["java.buildSystem"] }.distinct().sorted()
        return BuildModel(
            providerId = "java-project-model-v1",
            status = status,
            modules = modules.map { module ->
                val mainDependencies = module.mainDependencies.map { BuildDependency(it, DependencyScope.COMPILE) }
                val testDependencies = module.testDependencies.map { dependency ->
                    BuildDependency(
                        dependency,
                        if (dependency in module.mainDependencies) DependencyScope.COMPILE else DependencyScope.TEST,
                    )
                }
                BuildModule(
                    id = module.name,
                    name = module.name,
                    root = module.root,
                    sourceSets = listOf(
                        BuildSourceSet(
                            id = "main",
                            kind = SourceSetKind.MAIN,
                            sourceRoots = module.mainSourceRoots,
                            generatedSourceRoots = module.generatedSourceRoots,
                            outputDirectories = module.mainOutputDirectories,
                            classpathEntries = module.mainClasspathEntries,
                            moduleDependencies = mainDependencies,
                            attributes = mapOf("java.sourceLevel" to module.languageSettings.getValue("java.sourceLevel")),
                        ),
                        BuildSourceSet(
                            id = "test",
                            kind = SourceSetKind.TEST,
                            sourceRoots = (module.testSourceRoots + module.generatedTestSourceRoots).distinct(),
                            generatedSourceRoots = module.generatedTestSourceRoots,
                            outputDirectories = module.testOutputDirectories,
                            classpathEntries = module.testClasspathEntries,
                            moduleDependencies = testDependencies,
                            attributes = mapOf("java.sourceLevel" to module.languageSettings.getValue("java.sourceLevel")),
                        ),
                    ),
                    attributes = module.languageSettings,
                )
            },
            diagnostics = diagnostics,
            attributes = mapOf(
                "providers" to providerKinds.joinToString(","),
                "buildCodeExecution" to "denied",
                "credentialsAccess" to "denied",
                "networkDefault" to "denied",
            ),
        )
    }

    fun detectSourceRoots(root: Path): List<Path> {
        val conventionalRoots = conventionalSourceRoots(root)
        if (conventionalRoots.isNotEmpty()) return conventionalRoots
        return if (root.exists()) Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it) && (it.endsWith("src/main/java") || it.endsWith("src/test/java")) }
                .collect(Collectors.toList())
        } else emptyList()
    }

    fun detectModuleRoots(root: Path): List<Path> {
        if (!root.exists()) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it) && conventionalSourceRoots(it).isNotEmpty() }
                .filter { !isIgnoredDirectory(root, it) }
                .collect(Collectors.toList()).distinct().sortedBy { it.toString() }
        }
    }

    private fun conventionalMainSourceRoots(root: Path): List<Path> = listOf(root.resolve("src/main/java"))
        .filter { it.exists() && it.isDirectory() }
    private fun conventionalTestSourceRoots(root: Path): List<Path> = listOf(root.resolve("src/test/java"))
        .filter { it.exists() && it.isDirectory() }
    private fun conventionalSourceRoots(root: Path): List<Path> = conventionalMainSourceRoots(root) + conventionalTestSourceRoots(root)

    private fun generatedSourceRoots(root: Path, test: Boolean): List<Path> {
        val bases = if (test) listOf(root.resolve("target/generated-test-sources"), root.resolve("build/generated/sources/annotationProcessor/java/test"))
            else listOf(root.resolve("target/generated-sources"), root.resolve("build/generated/sources/annotationProcessor/java/main"))
        return bases.filter { it.exists() && it.isDirectory() }.flatMap { base ->
            val children = Files.list(base).use { stream -> stream.filter(Files::isDirectory).collect(Collectors.toList()) }
                .filter(::containsJava).sortedBy(Path::toString)
            if (children.isNotEmpty()) children else listOf(base).filter(::containsJava)
        }.fold(mutableListOf()) { roots, candidate ->
            if (roots.none { candidate.startsWith(it) || it.startsWith(candidate) }) roots.add(candidate)
            roots
        }
    }

    private fun containsJava(root: Path): Boolean = Files.walk(root).use { stream ->
        stream.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
    }

    private fun conventionalOutputDirectories(root: Path): List<Path> = listOf(
        root.resolve("target/classes"), root.resolve("target/test-classes"),
        root.resolve("build/classes/java/main"), root.resolve("build/classes/java/test"),
    )
    private fun localJarDirectories(root: Path): List<Path> = listOf(root.resolve("lib"), root.resolve("libs"))
    private fun buildDescriptorFiles(root: Path): List<Path> = listOf(root.resolve("pom.xml"), root.resolve("build.gradle"), root.resolve("build.gradle.kts"))
    private fun formatterConfigurationFiles(root: Path): List<Path> = listOf(root.resolve(".settings/org.eclipse.jdt.core.prefs"))
        .filter { it.exists() && Files.isRegularFile(it) }

    private fun findBuildFiles(root: Path, name: String): List<Path> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString() == name && !isIgnoredDirectory(root, it) }
            .collect(Collectors.toList()).sortedBy(Path::toString)
    }

    private fun evidencePath(workspaceRoot: Path, path: Path): Path =
        if (path.startsWith(workspaceRoot)) workspaceRoot.relativize(path) else path.toAbsolutePath().normalize()

    private fun mavenArtifactId(root: Path): String? {
        val pom = root.resolve("pom.xml").takeIf { it.exists() && Files.isRegularFile(it) } ?: return null
        val text = runCatching { pom.readText() }.getOrDefault("")
        val withoutParent = text.replace(Regex("(?s)<parent>.*?</parent>"), "")
        return Regex("<artifactId>\\s*([^<]+)\\s*</artifactId>").find(withoutParent)?.groupValues?.get(1)?.trim()
    }

    private fun detectModuleDependencies(root: Path, identities: Map<String, String>): List<String> {
        val dependencies = linkedSetOf<String>()
        val pom = root.resolve("pom.xml")
        if (pom.exists() && Files.isRegularFile(pom)) {
            Regex("(?s)<dependency>.*?<artifactId>\\s*([^<]+)\\s*</artifactId>.*?</dependency>")
                .findAll(runCatching { pom.readText() }.getOrDefault(""))
                .map { it.groupValues[1].trim() }.mapNotNull(identities::get).forEach(dependencies::add)
        }
        listOf(root.resolve("build.gradle"), root.resolve("build.gradle.kts"))
            .filter { it.exists() && Files.isRegularFile(it) }.forEach { buildFile ->
                Regex("project\\(\\s*['\"]:([^'\"]+)['\"]\\s*\\)").findAll(runCatching { buildFile.readText() }.getOrDefault(""))
                    .map { it.groupValues[1] }.mapNotNull(identities::get).forEach(dependencies::add)
            }
        mavenArtifactId(root)?.let(identities::get)?.let(dependencies::remove)
        return dependencies.sorted()
    }

    private fun detectJavaSourceLevel(root: Path): Int {
        val descriptors = buildDescriptorFiles(root).filter { it.exists() && Files.isRegularFile(it) }
        val candidates = descriptors.flatMap { descriptor ->
            val text = runCatching { descriptor.readText() }.getOrDefault("")
            when (descriptor.fileName.toString()) {
                "pom.xml" -> listOf(
                    Regex("<maven\\.compiler\\.release>\\s*([^<]+)\\s*</maven\\.compiler\\.release>"),
                    Regex("<maven\\.compiler\\.source>\\s*([^<]+)\\s*</maven\\.compiler\\.source>"),
                    Regex("<release>\\s*([^<]+)\\s*</release>"), Regex("<source>\\s*([^<]+)\\s*</source>"),
                ).mapNotNull { it.find(text)?.groupValues?.get(1)?.resolveMavenValue(text)?.toJavaLevelOrNull() }
                else -> listOf(
                    Regex("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)"), Regex("JavaVersion\\.VERSION_(\\d+)"),
                    Regex("(?:sourceCompatibility|targetCompatibility|options\\.release(?:\\.set)?)\\D{0,20}(\\d+)"),
                ).mapNotNull { it.find(text)?.groupValues?.get(1)?.toJavaLevelOrNull() }
            }
        }
        return candidates.firstOrNull { it in 8..25 } ?: 8
    }

    private fun String.resolveMavenValue(pom: String): String {
        val token = trim()
        val property = Regex("^\\$\\{([^}]+)}$").matchEntire(token)?.groupValues?.get(1) ?: return token
        return Regex("<$property>\\s*([^<]+)\\s*</$property>").find(pom)?.groupValues?.get(1)?.trim() ?: token
    }
    private fun String.toJavaLevelOrNull(): Int? = trim().removePrefix("1.").toIntOrNull()

    private fun classpathDeclarationFiles(root: Path): List<Path> = listOf(
        root.resolve(".refactorkit/classpath"), root.resolve("target/classpath.txt"), root.resolve("build/classpath.txt"),
    )
    private fun localJarEntries(root: Path): List<Path> = localJarDirectories(root).filter { it.exists() && it.isDirectory() }.flatMap { directory ->
        Files.walk(directory).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.collect(Collectors.toList()) }
    }
    private fun declaredClasspathEntries(root: Path): List<Path> = classpathDeclarationFiles(root)
        .filter { it.exists() && Files.isRegularFile(it) }.flatMap { classpathFile ->
            runCatching {
                classpathFile.readText().lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }
                    .flatMap { it.split(File.pathSeparatorChar).asSequence() }.map(String::trim).filter(String::isNotEmpty)
                    .map { Path.of(it).let { path -> if (path.isAbsolute) path.normalize() else root.resolve(path).normalize() } }
                    .filter { it.exists() && (it.isDirectory() || it.fileName.toString().endsWith(".jar")) }.toList()
            }.getOrDefault(emptyList())
        }

    private fun moduleName(workspaceRoot: Path, moduleRoot: Path): String {
        val relative = workspaceRoot.relativize(moduleRoot)
        return if (relative.toString().isBlank()) workspaceRoot.fileName?.toString() ?: "root"
        else relative.toString().replace('\\', ':').replace('/', ':')
    }

    private fun isIgnoredDirectory(workspaceRoot: Path, path: Path): Boolean {
        val relative = workspaceRoot.relativize(path).toString().replace('\\', '/')
        return relative.split('/').withIndex().any { (index, component) ->
            component in setOf(".gradle", ".git", ".refactorkit") ||
                (component in setOf("build", "target") && index > 0)
        }
    }
}
