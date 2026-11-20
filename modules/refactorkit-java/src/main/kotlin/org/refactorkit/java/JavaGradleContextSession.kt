package org.refactorkit.java

import kotlinx.serialization.json.*
import org.refactorkit.core.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class GradleJavaSyncRequest(
    val expectedSnapshotHash: String,
    val sdkHome: Path,
    val gradleExecutable: Path,
    val allowBuildExecution: Boolean,
    val projectProperties: Map<String, String> = emptyMap(),
    val gradleUserHome: Path? = null,
    val gradleRuntimeHome: Path? = null,
) {
    init {
        require(expectedSnapshotHash.matches(Regex("[a-f0-9]{64}")))
        require(sdkHome.isAbsolute && gradleExecutable.isAbsolute &&
            (gradleUserHome == null || gradleUserHome.isAbsolute) &&
            (gradleRuntimeHome == null || gradleRuntimeHome.isAbsolute))
        require(projectProperties.size <= 64 && projectProperties.all { (key, value) ->
            key.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")) && value.length <= 1_024 && '\u0000' !in value
        }) { "Invalid Gradle project properties" }
    }
}

sealed interface GradleJavaSyncResult {
    data class Available(val model: BuildModel) : GradleJavaSyncResult
    data class Refused @JvmOverloads constructor(val code: String, val message: String,
        val cause: GradleExportFailureCause? = null) : GradleJavaSyncResult
}

internal const val GRADLE_RUNTIME_ARTIFACT_OWNER_PREFIX = "gradle.runtimeArtifactOwner:"

private class GradleSyncFailure(val code: String, message: String,
    val causeHint: GradleExportFailureCause? = null) : RuntimeException(message)

internal object JavaGradleBuildInputInvalidation {
    fun invalidate(model: BuildModel): BuildModel = model.copy(
        status = BuildModelStatus.UNAVAILABLE,
        diagnostics = listOf(BuildModelDiagnostic(
            "gradle.context.stale",
            "Gradle build or tool evidence changed; synchronize explicitly",
        )),
    )
}

/** Application-owned, single-project bounded receipt; handlers own no Gradle model state. */
class JavaGradleContextSession {
    private data class Receipt(val root: Path, val model: BuildModel, val inputs: List<ClasspathEvidence>,
                               val toolInputs: List<ClasspathEvidence>, val userHome: Path)
    private var receipt: Receipt? = null

    fun clear() { receipt = null }

    fun modelFor(workspaceRoot: Path): BuildModel? {
        val stored = receipt ?: return null
        val root = workspaceRoot.toAbsolutePath().normalize()
        if (root != stored.root) return null
        val current = runCatching {
            modelInputs(root, stored.userHome) == stored.inputs && stored.toolInputs.all {
                ClasspathEvidence.capture(root, it.path, it.kind) == it
            }
        }.getOrDefault(false)
        return if (current) stored.model else JavaGradleBuildInputInvalidation.invalidate(stored.model)
    }

    fun synchronize(request: GradleJavaSyncRequest, scan: () -> ProjectSnapshot,
                    cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE): GradleJavaSyncResult {
        if (!request.allowBuildExecution) return refused("gradle.sync.executionNotAuthorized", "Gradle synchronization requires explicit build-script execution authorization")
        if (cancellation.isCancellationRequested()) return refused("gradle.sync.cancelled", "Gradle synchronization was cancelled")
        return try {
            val before = scan()
            if (before.hash != request.expectedSnapshotHash) return refused("java.context.snapshotStale", "Workspace inputs changed before Gradle synchronization")
            val root = before.workspace.root.toAbsolutePath().normalize()
            val home = request.sdkHome.toRealPath()
            val runtimeHome = (request.gradleRuntimeHome ?: home).toRealPath()
            val executable = request.gradleExecutable.toRealPath()
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) fail("gradle.sync.executableUnavailable", "Configured Gradle executable is unavailable")
            if (!Files.isDirectory(home) || !Files.isRegularFile(home.resolve("bin/java"))) fail("gradle.sync.sdkUnavailable", "Configured SDK cannot run Gradle")
            if (!Files.isDirectory(runtimeHome) || !Files.isRegularFile(runtimeHome.resolve("bin/java"))) fail("gradle.sync.runtimeUnavailable", "Configured Gradle runtime JDK is unavailable")
            if (listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS").any { !System.getenv(it).isNullOrBlank() }) {
                fail("gradle.sync.launchEnvironmentUnsupported", "Ambient Gradle/JVM launch options require an explicit qualified launch policy")
            }
            val configuredUserHome = request.gradleUserHome
                ?: System.getenv("GRADLE_USER_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".gradle")
            val userHome = configuredUserHome.toAbsolutePath().normalize()
            val inputs = modelInputs(root, userHome)
            val toolInputs = toolInputs(root, executable, home, runtimeHome)
            val exported = GradleJavaModelExporter().export(root, home, runtimeHome, executable, userHome, request.projectProperties, cancellation)
            val model = GradleJavaModelProjection.project(root, home, runtimeHome, exported, request.projectProperties)
            if (modelInputs(root, userHome) != inputs || toolInputs.any { ClasspathEvidence.capture(root, it.path, it.kind) != it }) {
                fail("gradle.sync.inputsChanged", "Gradle build or tool inputs changed during synchronization")
            }
            if (scan().files.filter { it.languageId == "java" } != before.files.filter { it.languageId == "java" }) {
                fail("gradle.sync.sourceMutationDetected", "Gradle configuration changed saved Java sources; no effective model was published")
            }
            val detached = AuthoritativeDiagnosticsEvaluation(ProjectSnapshot(Workspace(root), emptyList(), emptyList(), buildModels = listOf(model)), emptyList()).snapshot.buildModels.single()
            receipt = Receipt(root, detached, inputs, toolInputs, userHome)
            GradleJavaSyncResult.Available(detached)
        } catch (failure: GradleSyncFailure) {
            refused(failure.code, failure.message.orEmpty(), failure.causeHint)
        } catch (_: Exception) {
            refused("gradle.sync.unavailable", "Effective Gradle context could not be synchronized safely")
        }
    }

    fun evidenceFor(root: Path): List<ClasspathEvidence> = receipt?.takeIf { it.root == root.toAbsolutePath().normalize() }
        ?.let { it.inputs + it.toolInputs }.orEmpty()

    private fun toolInputs(root: Path, executable: Path, sdk: Path, runtime: Path): List<ClasspathEvidence> = buildList {
        add(ClasspathEvidence.capture(root, executable, ClasspathEvidenceKind.ENTRY))
        for (relative in listOf("release", "lib/ct.sym", "lib/modules", "bin/java", "bin/javac")) {
            add(ClasspathEvidence.capture(root, sdk.resolve(relative), ClasspathEvidenceKind.ENTRY))
        }
        if (runtime != sdk) for (relative in listOf("release", "lib/modules", "bin/java")) {
            add(ClasspathEvidence.capture(root, runtime.resolve(relative), ClasspathEvidenceKind.ENTRY))
        }
        val distribution = executable.parent?.parent?.resolve("lib")
        if (distribution != null && Files.isDirectory(distribution)) add(ClasspathEvidence.capture(root, distribution, ClasspathEvidenceKind.JAR_DIRECTORY))
    }.distinct()

    private fun modelInputs(root: Path, userHome: Path): List<ClasspathEvidence> {
        val files = mutableListOf<Path>()
        var visited = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (path != root && path.fileName.toString() in setOf(".git", ".gradle", ".refactorkit", "build", "target", "node_modules")) return FileVisitResult.SKIP_SUBTREE
                if (++visited > 32_768) fail("gradle.sync.limitExceeded", "Gradle input traversal exceeds its node limit")
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (++visited > 32_768) fail("gradle.sync.limitExceeded", "Gradle input traversal exceeds its node limit")
                val name = path.fileName.toString()
                if (attributes.isRegularFile && (name.endsWith(".gradle") || name.endsWith(".gradle.kts") ||
                    name in setOf("gradle.properties", "gradle.lockfile", "gradle-wrapper.properties", "verification-metadata.xml") ||
                    name.endsWith(".toml") || root.relativize(path).any { it.toString() == "buildSrc" })) files.add(path)
                if (files.size > 4_096) fail("gradle.sync.limitExceeded", "Gradle input inventory exceeds its limit")
                return FileVisitResult.CONTINUE
            }
        })
        if (files.size > 4_096) fail("gradle.sync.limitExceeded", "Gradle model input inventory exceeds its limit")
        val external = buildList {
            val properties = userHome.resolve("gradle.properties"); if (Files.isRegularFile(properties)) add(properties)
            for (name in listOf("init.gradle", "init.gradle.kts")) userHome.resolve(name).takeIf { Files.isRegularFile(it) }?.let(::add)
            val init = userHome.resolve("init.d")
            if (Files.isDirectory(init)) Files.walk(init, 2).use { paths -> addAll(paths.filter { Files.isRegularFile(it) }.limit(65).toList()) }
        }
        if (external.size > 64) fail("gradle.sync.limitExceeded", "Gradle user initialization inventory exceeds its limit")
        return (files + external).distinct().sortedBy(Path::toString).map { path ->
            if (Files.size(path) > 4L * 1_024 * 1_024) fail("gradle.sync.limitExceeded", "Gradle model input exceeds the byte limit")
            ClasspathEvidence.capture(root, if (path.startsWith(root)) root.relativize(path) else path, ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT)
        }
    }

    private fun refused(code: String, message: String, cause: GradleExportFailureCause? = null) =
        GradleJavaSyncResult.Refused(code, message, cause)
}

private fun fail(code: String, message: String, cause: GradleExportFailureCause? = null): Nothing =
    throw GradleSyncFailure(code, message, cause)

/** Explicit external build port. It exports metadata and does not request a compilation task. */
private class GradleJavaModelExporter {
    fun export(root: Path, sdk: Path, runtime: Path, executable: Path, userHome: Path, properties: Map<String, String>,
               cancellation: SemanticCancellationToken): JsonObject {
        val temporary = Files.createTempDirectory("refactorkit-gradle-export-")
        var process: Process? = null
        try {
            val init = temporary.resolve("export.gradle"); Files.writeString(init, SCRIPT)
            val outputDirectory = Files.createDirectory(temporary.resolve("builds")); val log = temporary.resolve("process.log")
            val args = listOf(executable.toString(), "--offline", "--stacktrace", "--no-daemon", "--no-watch-fs", "--console=plain", "--max-workers=1",
                "-Dorg.gradle.java.home=$runtime", "-Dorg.gradle.java.installations.paths=$sdk",
                "-Dorg.gradle.java.installations.auto-download=false", "-Drefactorkit.exportDir=$outputDirectory",
                "--init-script", init.toString(), "refactorkitEffectiveContext_v1") + properties.toSortedMap().map { (key, value) -> "-P$key=$value" }
            process = ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).apply {
                environment().apply {
                    put("JAVA_HOME", runtime.toString())
                    put("GRADLE_USER_HOME", userHome.toString())
                    if (java.io.File.separatorChar == '/') {
                        // LC_ALL takes precedence over LC_MESSAGES. Retain its other locale categories,
                        // including the character encoding used for project paths.
                        remove("LC_ALL")?.takeIf(String::isNotEmpty)?.let { locale ->
                            listOf(
                                "LC_CTYPE", "LC_COLLATE", "LC_MONETARY", "LC_NUMERIC", "LC_TIME",
                                "LC_PAPER", "LC_NAME", "LC_ADDRESS", "LC_TELEPHONE", "LC_MEASUREMENT", "LC_IDENTIFICATION",
                            ).forEach { put(it, locale) }
                        }
                        put("LC_MESSAGES", "C")
                    }
                }
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancellation.isCancellationRequested()) fail("gradle.sync.cancelled", "Gradle synchronization was cancelled")
                if (System.nanoTime() >= deadline) fail("gradle.sync.timeout", "Gradle metadata export exceeded its time limit")
                if (Files.size(log) > GradleExportFailureClassifier.MAX_OUTPUT_BYTES) fail("gradle.sync.outputLimitExceeded", "Gradle metadata process exceeded its output limit")
            }
            // A fast exit must obey the same byte ceiling as a long-running exporter.
            val output = Files.newInputStream(log).use { it.readNBytes(GradleExportFailureClassifier.MAX_OUTPUT_BYTES + 1) }
            if (output.size > GradleExportFailureClassifier.MAX_OUTPUT_BYTES) {
                fail("gradle.sync.outputLimitExceeded", "Gradle metadata process exceeded its output limit")
            }
            val outputs = Files.list(outputDirectory).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.sorted().toList() }
            if (process.exitValue() != 0 || outputs.isEmpty()) {
                val cacheRoots = setOfNotNull(userHome.toString(), runCatching { userHome.toRealPath().toString() }.getOrNull())
                val cause = GradleExportFailureClassifier.classify(output, cacheRoots)
                fail("gradle.sync.exportFailed", "Gradle could not export its effective model offline. ${cause.message}", cause)
            }
            if (outputs.size > 1_024 || outputs.sumOf(Files::size) > 1_048_576) {
                fail("gradle.sync.outputLimitExceeded", "Gradle composite metadata export exceeds its count or byte limit")
            }
            val builds = outputs.map { Json.parseToJsonElement(Files.readString(it)).jsonObject }
                .sortedBy { it["buildPath"]?.jsonPrimitive?.content.orEmpty() }
            val rootBuild = builds.singleOrNull { it["buildPath"]?.jsonPrimitive?.content == ":" }
                ?: fail("gradle.sync.compositeIdentityAmbiguous", "Gradle did not export exactly one root build identity")
            return buildJsonObject {
                put("schema", "gradle-java-composite-effective-v1")
                put("gradleVersion", rootBuild.getValue("gradleVersion"))
                put("javaHome", rootBuild.getValue("javaHome"))
                put("builds", JsonArray(builds))
            }
        } finally {
            process?.let { running ->
                if (running.isAlive) { running.descendants().forEach { it.destroyForcibly() }; running.destroyForcibly(); running.waitFor(10, TimeUnit.SECONDS) }
            }
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    companion object {
        val SCRIPT = """
            import groovy.json.JsonOutput
            import org.gradle.api.artifacts.component.ProjectComponentIdentifier
            import org.gradle.api.artifacts.component.ModuleComponentIdentifier
            import org.gradle.api.attributes.Usage
            import org.gradle.api.attributes.Category
            import org.gradle.api.attributes.Bundling
            import org.gradle.api.attributes.DocsType
            import org.gradle.api.tasks.compile.JavaCompile
            import java.util.jar.JarFile
            gradle.projectsEvaluated {
              def exporter = rootProject.tasks.register('refactorkitEffectiveContext_v1') {
                dependsOn(gradle.includedBuilds.collect { it.task(':refactorkitEffectiveContext_v1') })
              }
              // Resolve the evaluated model before included-build task execution holds producer lifecycle locks.
              gradle.taskGraph.whenReady { graph ->
                if (graph.hasTask(exporter.get())) {
                  def modules = rootProject.allprojects.sort { it.path }.collect { p ->
                    def sets = p.extensions.findByName('sourceSets')
                    if (sets == null) return null
                    def jar = p.tasks.findByName('jar')
                    [id:p.path, buildTreeProjectPath:p.buildTreePath, name:p.name, root:p.projectDir.absolutePath,
                     artifact:jar?.archiveFile?.get()?.asFile?.absolutePath,
                     sets:sets.collect { s ->
                       def compiler = p.tasks.named(s.compileJavaTaskName, JavaCompile).get()
                       def compilerArguments = []
                       def compilerArgumentsComplete = true
                       try {
                         def arguments = compiler.options.compilerArgs.iterator()
                         def argumentCount = 0
                         def totalArgumentCharacters = 0
                         while (arguments.hasNext()) {
                           if (argumentCount >= 256) {
                             compilerArgumentsComplete = false
                             break
                           }
                           def argument = arguments.next()
                           argumentCount++
                           if (!(argument instanceof String) || argument.length() > 16384 ||
                               totalArgumentCharacters + argument.length() > 65536) {
                             compilerArgumentsComplete = false
                             continue
                           }
                           totalArgumentCharacters += argument.length()
                           def sensitive = (argument =~ /(?i)(password|secret|token|api[-_]?key|credential|authorization)\s*[=:]/).find()
                           compilerArguments.add([value:sensitive ? '<redacted>' : argument, redacted:sensitive])
                         }
                       } catch (Exception ignored) {
                         compilerArgumentsComplete = false
                       }
                       def providerInventoryComplete = false
                       def compilerArgumentProviderCount = -1
                       try {
                         compilerArgumentProviderCount = compiler.options.compilerArgumentProviders.size()
                         providerInventoryComplete = true
                       } catch (Exception ignored) {
                         compilerArgumentsComplete = false
                       }
                       def taskActionInventoryComplete = false
                       try {
                         def actions = compiler.actions
                         taskActionInventoryComplete = actions.size() == 1 &&
                           actions[0].class.name == 'org.gradle.api.internal.project.taskfactory.IncrementalTaskAction'
                       } catch (Exception ignored) {
                         compilerArgumentsComplete = false
                       }
                       if (!providerInventoryComplete || compilerArgumentProviderCount != 0 || !taskActionInventoryComplete) {
                         compilerArgumentsComplete = false
                       }
                       def compilerOptionsAvailability = compilerArgumentsComplete ? 'complete' : 'partial'
                       def annotationProcessorMetadataComplete = compilerOptionsAvailability == 'complete'
                       def annotationProcessorPaths = []
                       def annotationProcessorNamesFromServices = []
                       def explicitAnnotationProcessorNames = null
                       def annotationProcessorOptions = [:]
                       def annotationProcessorModes = []
                       try {
                         annotationProcessorPaths = compiler.options.annotationProcessorPath == null ? [] :
                           compiler.options.annotationProcessorPath.files.collect { it.canonicalPath }.unique().sort()
                       } catch (Exception ignored) {
                         annotationProcessorMetadataComplete = false
                       }
                       if (annotationProcessorPaths.size() > 256) {
                         annotationProcessorMetadataComplete = false
                         annotationProcessorPaths = annotationProcessorPaths.take(256)
                       }
                       if (compilerArguments.any { it.redacted }) annotationProcessorMetadataComplete = false
                       def annotationProcessorArguments = compilerArguments.collect { it.value }
                       for (int index = 0; index < annotationProcessorArguments.size(); index++) {
                         def argument = annotationProcessorArguments[index]
                         if (argument in ['-processorpath', '--processor-path', '--processor-module-path'] ||
                             argument.startsWith('-processorpath:') || argument.startsWith('--processor-path=') ||
                             argument.startsWith('--processor-module-path=')) {
                           annotationProcessorMetadataComplete = false
                         } else if (argument == '-processor') {
                           if (index + 1 >= annotationProcessorArguments.size() || explicitAnnotationProcessorNames != null) {
                             annotationProcessorMetadataComplete = false
                           } else {
                             explicitAnnotationProcessorNames = annotationProcessorArguments[++index].split(',').collect { it.trim() }.findAll { !it.isEmpty() }
                           }
                         } else if (argument.startsWith('-processor=')) {
                           if (explicitAnnotationProcessorNames != null) annotationProcessorMetadataComplete = false
                           explicitAnnotationProcessorNames = argument.substring('-processor='.length()).split(',').collect { it.trim() }.findAll { !it.isEmpty() }
                         } else if (argument.startsWith('-A')) {
                           def option = argument.substring(2)
                           def separator = option.indexOf('=')
                           def key = separator < 0 ? option : option.substring(0, separator)
                           def value = separator < 0 ? '' : option.substring(separator + 1)
                           if (key.isEmpty() || annotationProcessorOptions.containsKey(key) ||
                               key.length() > 512 || value.length() > 16384 || annotationProcessorOptions.size() >= 256) {
                             annotationProcessorMetadataComplete = false
                           } else {
                             annotationProcessorOptions[key] = value
                           }
                         } else if (argument in ['-proc:none', '-proc:only', '-proc:full']) {
                           annotationProcessorModes.add(argument.substring('-proc:'.length()))
                         }
                       }
                       if (annotationProcessorModes.unique().size() > 1) annotationProcessorMetadataComplete = false
                       annotationProcessorPaths.each { rawPath ->
                         def path = new File(rawPath)
                         if (!path.exists() || !path.canRead()) {
                           annotationProcessorMetadataComplete = false
                         } else try {
                           def serviceName = 'META-INF/services/javax.annotation.processing.Processor'
                           def serviceText = null
                           if (path.isDirectory()) {
                             def serviceFile = new File(path, serviceName)
                             if (serviceFile.isFile()) serviceText = serviceFile.getText('UTF-8')
                           } else {
                             def archive = new java.util.jar.JarFile(path)
                             try {
                               def serviceEntry = archive.getJarEntry(serviceName)
                               if (serviceEntry != null) {
                                 def stream = archive.getInputStream(serviceEntry)
                                 try { serviceText = stream.getText('UTF-8') } finally { stream.close() }
                               }
                             } finally { archive.close() }
                           }
                           if (serviceText != null) {
                             serviceText.readLines().collect { line ->
                               def comment = line.indexOf('#')
                               comment < 0 ? line.trim() : line.substring(0, comment).trim()
                             }.findAll { !it.isEmpty() }.each { name ->
                               if (!(name ==~ /[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*/) || name.length() > 512) {
                                 annotationProcessorMetadataComplete = false
                               } else annotationProcessorNamesFromServices.add(name)
                             }
                           }
                         } catch (Exception ignored) {
                           annotationProcessorMetadataComplete = false
                         }
                       }
                       annotationProcessorNamesFromServices = annotationProcessorNamesFromServices.unique().sort()
                       def annotationProcessorNames = explicitAnnotationProcessorNames == null ? annotationProcessorNamesFromServices : explicitAnnotationProcessorNames
                       if (explicitAnnotationProcessorNames != null && annotationProcessorPaths.isEmpty()) annotationProcessorMetadataComplete = false
                       if (annotationProcessorNames.any { !(it ==~ /[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*/) || it.length() > 512 }) {
                         annotationProcessorMetadataComplete = false
                       }
                       if (annotationProcessorNames.size() > 256) {
                         annotationProcessorMetadataComplete = false
                         annotationProcessorNames = annotationProcessorNames.take(256)
                       }
                       def annotationProcessorConfigured = !annotationProcessorPaths.isEmpty() || explicitAnnotationProcessorNames != null ||
                         !annotationProcessorOptions.isEmpty() || !annotationProcessorModes.isEmpty()
                       if (annotationProcessorPaths.isEmpty() && (!annotationProcessorOptions.isEmpty() || explicitAnnotationProcessorNames != null)) {
                         annotationProcessorMetadataComplete = false
                       }
                       def annotationProcessorAvailability = !annotationProcessorMetadataComplete ? 'partial' :
                         (annotationProcessorConfigured ? 'complete' : 'not-declared')
                       def annotationProcessorMode = annotationProcessorModes.unique().size() == 1 ? annotationProcessorModes.unique()[0] :
                         null
                       def projects = { config -> config.incoming.resolutionResult.allComponents.collect { c ->
                         c.id instanceof ProjectComponentIdentifier && !(c.id.projectPath == p.path && c.id.build.buildPath == rootProject.buildTreePath) ?
                           [buildPath:c.id.build.buildPath, projectPath:c.id.projectPath] : null
                       }.findAll { it != null }.unique() }
                       def compileProjects = projects(p.configurations.getByName(s.compileClasspathConfigurationName))
                       def runtimeConfig = p.configurations.getByName(s.runtimeClasspathConfigurationName)
                       def runtimeProjects = projects(runtimeConfig)
                       def runtimeFiles = s.runtimeClasspath.files.collect { it.absolutePath }
                       def runtimePaths = runtimeFiles.toSet()
                       def runtimeProjectArtifacts = runtimeConfig.incoming.artifacts.artifacts.findAll {
                         it.id.componentIdentifier instanceof ProjectComponentIdentifier && runtimePaths.contains(it.file.absolutePath)
                       }.collect { artifact ->
                         def component = artifact.id.componentIdentifier
                         [path:artifact.file.absolutePath, buildPath:component.build.buildPath, projectPath:component.projectPath]
                       }
                      def compileConfig = p.configurations.getByName(s.compileClasspathConfigurationName)
                      def compileFiles = compiler.classpath.files.collect { it.absolutePath }
                      def jpmsInferenceEnabled = null
                      def jpmsModulePath = []
                      def jpmsModulePathAvailability = 'complete'
                      try {
                        jpmsInferenceEnabled = compiler.modularity.inferModulePath.getOrNull()
                        if (!(jpmsInferenceEnabled instanceof Boolean)) {
                          jpmsModulePathAvailability = 'partial'
                        } else if (jpmsInferenceEnabled && compiler.source.files.any { it.name == 'module-info.java' }) {
                          if (compileFiles.size() > 4096) {
                            jpmsModulePathAvailability = 'partial'
                          } else {
                            for (rawPath in compileFiles) {
                              if (jpmsModulePathAvailability != 'complete') break
                              def classpathEntry = new File(rawPath)
                              if (!classpathEntry.exists()) {
                                jpmsModulePathAvailability = 'partial'
                              } else if (classpathEntry.isDirectory()) {
                                if (new File(classpathEntry, 'module-info.class').isFile()) {
                                  jpmsModulePath.add(classpathEntry.absolutePath)
                                }
                              } else if (classpathEntry.isFile() && classpathEntry.name.toLowerCase(Locale.ROOT).endsWith('.jar')) {
                                def dependencyArchive = new JarFile(classpathEntry)
                                try {
                                  def modular = dependencyArchive.getJarEntry('module-info.class') != null
                                  if (!modular && dependencyArchive.isMultiRelease()) {
                                    for (int version = Runtime.version().feature(); version >= 9; version--) {
                                      if (dependencyArchive.getJarEntry("META-INF/versions/${'$'}{version}/module-info.class") != null) {
                                        modular = true
                                        break
                                      }
                                    }
                                  }
                                  if (!modular) {
                                    def automaticName = dependencyArchive.manifest?.mainAttributes?.getValue('Automatic-Module-Name')
                                    if (automaticName != null) {
                                      java.lang.module.ModuleDescriptor.newAutomaticModule(automaticName).build()
                                      modular = true
                                    }
                                  }
                                  if (modular) {
                                    if (jpmsModulePath.size >= 256) {
                                      jpmsModulePathAvailability = 'partial'
                                      jpmsModulePath.clear()
                                    } else {
                                      jpmsModulePath.add(classpathEntry.absolutePath)
                                    }
                                  }
                                } finally {
                                  dependencyArchive.close()
                                }
                              }
                            }
                          }
                        }
                      } catch (Exception ignored) {
                        jpmsModulePathAvailability = 'partial'
                        jpmsModulePath.clear()
                      }
                      def binaryArtifacts = compileConfig.incoming.artifacts.artifacts.findAll {
                         it.id.componentIdentifier instanceof ModuleComponentIdentifier && compileFiles.contains(it.file.absolutePath)
                       }
                       def sourceArtifacts = compileConfig.incoming.artifactView {
                         withVariantReselection()
                         lenient = true
                         componentFilter { it instanceof ModuleComponentIdentifier }
                         attributes {
                           attribute(Usage.USAGE_ATTRIBUTE, p.objects.named(Usage, Usage.JAVA_RUNTIME))
                           attribute(Category.CATEGORY_ATTRIBUTE, p.objects.named(Category, Category.DOCUMENTATION))
                           attribute(Bundling.BUNDLING_ATTRIBUTE, p.objects.named(Bundling, Bundling.EXTERNAL))
                           attribute(DocsType.DOCS_TYPE_ATTRIBUTE, p.objects.named(DocsType, DocsType.SOURCES))
                         }
                       }.artifacts.artifacts
                       def attachments = binaryArtifacts.collectMany { binary ->
                         sourceArtifacts.findAll { it.id.componentIdentifier == binary.id.componentIdentifier }.collect { source ->
                           [binaryPath:binary.file.absolutePath, sourcePath:source.file.absolutePath, componentId:binary.id.componentIdentifier.displayName]
                         }
                       }.unique().sort { a, b -> (a.binaryPath <=> b.binaryPath) ?: (a.sourcePath <=> b.sourcePath) }
                       def sourceRoots = s.java.srcDirs.collect { it.absolutePath }.unique()
                       def kotlinCompiler = null
                       if (p.pluginManager.hasPlugin('org.jetbrains.kotlin.jvm')) {
                         def kotlinSources = s.extensions.findByName('kotlin')
                         def kotlinTask = p.tasks.findByName(s.getCompileTaskName('kotlin'))
                         if (kotlinSources instanceof org.gradle.api.file.SourceDirectorySet && kotlinTask != null) {
                           try {
                             def options = kotlinTask.compilerOptions
                             def destination = kotlinTask.destinationDirectory.getOrNull()?.asFile
                             def target = options.jvmTarget.getOrNull()?.target
                             def jdk = kotlinTask.kotlinJavaToolchain.javaVersion.getOrNull()?.majorVersion
                             def moduleName = options.moduleName.getOrNull()
                             if (destination != null && s.output.files.contains(destination) && target && jdk && moduleName) {
                               kotlinCompiler = [roots:kotlinSources.srcDirs.collect { it.absolutePath }.unique(),
                                 output:destination.absolutePath, taskPath:kotlinTask.path, target:target, jdk:jdk,
                                 moduleName:moduleName, languageVersion:options.languageVersion.getOrNull()?.version,
                                 apiVersion:options.apiVersion.getOrNull()?.version,
                                 freeArgumentsPresent:!options.freeCompilerArgs.get().isEmpty()]
                             }
                           } catch (Exception ignored) {
                             // Preserve the evaluated Java model when a Kotlin task exposes unsupported metadata.
                           }
                         }
                       }
                       def generatedRoots = ((s.output.generatedSourcesDirs.files +
                         [compiler.options.generatedSourceOutputDirectory.getOrNull()?.asFile]).findAll { it != null })
                         .collect { it.absolutePath }.unique()
                       def sourceRootPlugin = p.plugins.hasPlugin('java') ? 'java' :
                         (p.plugins.hasPlugin('java-base') ? 'java-base' : null)
                       def sourceRootEvidence = (sourceRoots + generatedRoots).unique().collect { path ->
                         [path:path, origin:generatedRoots.contains(path) ? 'generated' : 'build-plugin',
                          pluginId:sourceRootPlugin, sourceSetId:s.name, compileTaskPath:compiler.path,
                          availability:sourceRootPlugin == null ? 'partial' : 'complete']
                       }
                      [id:s.name, compileTaskPath:compiler.path, compilerArgs:compilerArguments,
                       compilerOptionsAvailability:compilerOptionsAvailability,
                       jpmsInferenceEnabled:jpmsModulePathAvailability == 'complete' ? jpmsInferenceEnabled : null,
                       jpmsModulePath:jpmsModulePath,
                       jpmsModulePathAvailability:jpmsModulePathAvailability,
                       annotationProcessorAvailability:annotationProcessorAvailability,
                       annotationProcessorPath:annotationProcessorPaths,
                       annotationProcessorNames:annotationProcessorNames,
                       annotationProcessorOptions:annotationProcessorOptions,
                       annotationProcessorMode:annotationProcessorMode,
                       roots:sourceRoots, generated:generatedRoots, sourceRootEvidence:sourceRootEvidence,
                        kotlinCompiler:kotlinCompiler,
                        outputs:s.output.files.collect { it.absolutePath },
                        compile:compileFiles, runtime:runtimeFiles, runtimeProjectArtifacts:runtimeProjectArtifacts,
                        sourceAttachments:attachments,
                        release:compiler.options.release.getOrNull(), source:compiler.sourceCompatibility, target:compiler.targetCompatibility,
                        compilerHome:compiler.javaCompiler.get().metadata.installationPath.asFile.absolutePath,
                        dependencies:(compileProjects + runtimeProjects).unique().collect { dependency ->
                          [buildPath:dependency.buildPath, projectPath:dependency.projectPath,
                           scope:compileProjects.contains(dependency) ? (runtimeProjects.contains(dependency) ? 'COMPILE' : 'PROVIDED') : 'RUNTIME']
                        }]
                     }]
                  }.findAll { it != null }
                  def buildRoot = rootProject.projectDir.canonicalFile
                  def result = [schema:'gradle-java-effective-build-v1', gradleVersion:gradle.gradleVersion,
                    javaHome:System.getProperty('java.home'), buildPath:rootProject.buildTreePath,
                    buildName:rootProject.name, buildRoot:buildRoot.absolutePath,
                    includedBuilds:gradle.includedBuilds.collect { included ->
                      [name:included.name, buildRoot:included.projectDir.canonicalPath]
                    }.sort { it.buildRoot }, modules:modules]
                  def key = java.security.MessageDigest.getInstance('SHA-256').digest(buildRoot.absolutePath.getBytes('UTF-8')).encodeHex().toString()
                  new File(System.getProperty('refactorkit.exportDir'), key + '.json').setText(JsonOutput.toJson(result), 'UTF-8')
                }
              }
            }
        """.trimIndent()
    }
}

/** ACL from Gradle's evaluated objects to the neutral model. Resolved closure order is preserved. */
private object GradleJavaModelProjection {
    fun project(root: Path, sdk: Path, runtime: Path, document: JsonObject, properties: Map<String, String>): BuildModel {
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        val workspaceRoot = root.toRealPath()
        fun relative(value: String): Path {
            val path = Path.of(value).toAbsolutePath().normalize()
            if (!path.startsWith(workspaceRoot)) fail("gradle.sync.sourceOutsideWorkspace", "Gradle source/output ownership extends outside the open workspace")
            return workspaceRoot.relativize(path)
        }
        fun paths(value: JsonElement, owned: Boolean) = value.jsonArray.map { item ->
            val path = Path.of(item.jsonPrimitive.content).toAbsolutePath().normalize()
            if (owned) relative(path.toString()) else if (path.startsWith(workspaceRoot)) workspaceRoot.relativize(path) else path
        }.distinct()
        if (document.text("schema") != "gradle-java-composite-effective-v1" || Path.of(document.text("javaHome")).toRealPath() != runtime) {
            fail("gradle.sync.runtimeMismatch", "Gradle did not run with the explicitly selected runtime JDK")
        }
        val rawBuilds = document.getValue("builds").jsonArray.map { it.jsonObject }
        if (rawBuilds.isEmpty() || rawBuilds.size > 1_024) fail("gradle.sync.limitExceeded", "Gradle composite build inventory exceeds its limit")
        val buildRows = rawBuilds.map { build ->
            if (build.text("schema") != "gradle-java-effective-build-v1" ||
                Path.of(build.text("javaHome")).toRealPath() != runtime ||
                build.text("gradleVersion") != document.text("gradleVersion")
            ) fail("gradle.sync.compositeIdentityAmbiguous", "Gradle composite exports disagree on their toolchain identity")
            val absoluteRoot = Path.of(build.text("buildRoot")).toRealPath()
            if (!absoluteRoot.startsWith(workspaceRoot)) fail(
                "gradle.sync.includedBuildOutsideWorkspace",
                "Included Gradle build root is outside the open workspace",
            )
            val buildRoot = workspaceRoot.relativize(absoluteRoot).toString().replace('\\', '/').ifEmpty { "." }
            Triple(build, build.text("buildPath"), buildRoot)
        }.sortedBy { it.third }
        if (buildRows.map { it.second }.distinct().size != buildRows.size ||
            buildRows.map { it.third }.distinct().size != buildRows.size
        ) fail("gradle.sync.compositeIdentityAmbiguous", "Gradle build path or workspace-relative build root is duplicated")
        val rowsByRoot = buildRows.associateBy { it.third }
        val rowsByBuildPath = buildRows.associateBy { it.second }
        val rootBuild = buildRows.singleOrNull { it.second == ":" && it.third == "." }
            ?: fail("gradle.sync.compositeIdentityAmbiguous", "Gradle composite export has no unique workspace root build")
        val reachedRoots = linkedSetOf(rootBuild.third)
        val pendingBuilds = ArrayDeque(listOf(rootBuild))
        while (pendingBuilds.isNotEmpty()) {
            val (build, _, _) = pendingBuilds.removeFirst()
            build.getValue("includedBuilds").jsonArray.forEach { item ->
                val includedRoot = Path.of(item.jsonObject.text("buildRoot")).toRealPath()
                if (!includedRoot.startsWith(workspaceRoot)) fail(
                    "gradle.sync.includedBuildOutsideWorkspace",
                    "Included Gradle build root is outside the open workspace",
                )
                val key = workspaceRoot.relativize(includedRoot).toString().replace('\\', '/').ifEmpty { "." }
                val included = rowsByRoot[key] ?: fail(
                    "gradle.sync.compositeModelIncomplete",
                    "An included Gradle build has no provider-qualified model row",
                )
                if (reachedRoots.add(key)) pendingBuilds.addLast(included)
            }
        }
        if (reachedRoots.size != buildRows.size) fail(
            "gradle.sync.compositeModelIncomplete",
            "The Gradle export contains an included build outside the declared composite tree",
        )
        val raw = buildRows.flatMap { (build, _, buildRoot) ->
            build.getValue("modules").jsonArray.map { module -> Triple(build, buildRoot, module.jsonObject) }
        }
        if (raw.isEmpty() || raw.size > 1_024) fail("gradle.sync.limitExceeded", "Gradle module inventory exceeds its limit")
        val composite = buildRows.size > 1
        fun qualifiedModuleId(buildRoot: String, projectPath: String): String = if (!composite) projectPath else {
            val digest = MessageDigest.getInstance("SHA-256").digest(buildRoot.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            "gradle-$digest:$projectPath"
        }
        val ids = raw.map { (_, buildRoot, module) -> qualifiedModuleId(buildRoot, module.text("id")) }.toSet()
        if (ids.size != raw.size) fail("gradle.sync.compositeIdentityAmbiguous", "Gradle module identities are duplicated")
        fun resolveModuleId(buildPath: String, projectPath: String): String {
            val targetBuild = rowsByBuildPath[buildPath] ?: fail(
                "gradle.sync.compositeIdentityAmbiguous",
                "A Gradle project dependency has no unique included-build identity",
            )
            val targetId = qualifiedModuleId(targetBuild.third, projectPath)
            if (targetId !in ids) fail(
                "gradle.sync.compositeModelIncomplete",
                "A Gradle project dependency has no matching module row",
            )
            return targetId
        }
        val mainArtifactsByModule = raw.associate { (_, buildRoot, module) ->
            val id = qualifiedModuleId(buildRoot, module.text("id"))
            id to module["artifact"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let(::relative)
        }
        val modules = raw.map { (build, buildRoot, module) ->
            val moduleId = qualifiedModuleId(buildRoot, module.text("id"))
            val rootPath = workspaceRoot.resolve(relative(module.text("root")))
            val sets = module.getValue("sets").jsonArray
            if (sets.size > 64) fail("gradle.sync.limitExceeded", "Gradle source-set inventory exceeds its limit")
            BuildModule(id = moduleId, name = module.text("name"), root = rootPath,
                sourceSets = sets.map { value ->
                    val set = value.jsonObject; val id = set.text("id")
                    val compilerHome = Path.of(set.text("compilerHome")).toRealPath()
                    if (compilerHome != sdk) fail("gradle.sync.compilerSdkMismatch", "A Gradle source set selects a different compiler SDK")
                    val generated = paths(set.getValue("generated"), true)
                    val rawCompilerArguments = set["compilerArgs"]?.jsonArray
                        ?: fail("gradle.sync.compilerOptionsUnavailable", "Gradle did not export Java compiler arguments")
                    if (rawCompilerArguments.size > 256) fail("gradle.sync.limitExceeded", "Gradle compiler argument inventory exceeds 256 entries")
                    var compilerOptionsAvailability = when (set["compilerOptionsAvailability"]?.jsonPrimitive?.content) {
                        "complete" -> BuildFactAvailability.COMPLETE
                        "partial" -> BuildFactAvailability.PARTIAL
                        else -> fail("gradle.sync.compilerOptionsUnavailable", "Gradle compiler argument completeness was not classified")
                    }
                    val compileTaskPath = set["compileTaskPath"]?.jsonPrimitive?.content
                        ?: fail("gradle.sync.compilerOptionsUnavailable", "Gradle compiler task identity was not exported")
                    val sourceRoots = (paths(set.getValue("roots"), true) + generated).distinct()
                    val rawRootEvidence = set["sourceRootEvidence"] as? JsonArray
                        ?: fail("gradle.sync.sourceRootProvenanceUnavailable", "Gradle did not export evaluated source-root ownership")
                    if (rawRootEvidence.size > 256 || compileTaskPath.length > 256) {
                        fail("gradle.sync.limitExceeded", "Gradle source-root provenance exceeds its item or owner-identity limit")
                    }
                    val sourceRootEvidence = rawRootEvidence.map { value ->
                        val row = value as? JsonObject ?: fail("gradle.sync.sourceRootProvenanceUnavailable", "Malformed evaluated source-root evidence")
                        fun text(key: String): String = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: fail("gradle.sync.sourceRootProvenanceUnavailable", "Source-root owner fields must be strings")
                        val path = relative(text("path"))
                        if (path !in sourceRoots || text("sourceSetId") != id || text("compileTaskPath") != compileTaskPath) {
                            fail("gradle.sync.sourceRootProvenanceUnavailable", "Source-root evidence differs from its evaluated source-set/compiler owner")
                        }
                        val origin = if (path in generated) BuildSourceRootOrigin.GENERATED else BuildSourceRootOrigin.BUILD_PLUGIN
                        if (text("origin") != origin.name.lowercase().replace('_', '-')) {
                            fail("gradle.sync.sourceRootProvenanceUnavailable", "Generated-root evidence differs from evaluated compiler outputs")
                        }
                        val availability = when (text("availability")) {
                            "complete" -> BuildFactAvailability.COMPLETE
                            "partial" -> BuildFactAvailability.PARTIAL
                            else -> fail("gradle.sync.sourceRootProvenanceUnavailable", "Source-root completeness was not classified")
                        }
                        val plugin = row["pluginId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.takeIf { it.isString }?.content
                        if (plugin != null && plugin !in setOf("java", "java-base") ||
                            availability == BuildFactAvailability.COMPLETE && plugin == null) {
                            fail("gradle.sync.sourceRootProvenanceUnavailable", "Complete source roots require an evaluated Java plugin owner")
                        }
                        BuildSourceRootEvidence(path, origin, "gradle-effective-v1", pluginId = plugin,
                            executionId = compileTaskPath, availability = availability)
                    }
                    if (sourceRootEvidence.size != sourceRoots.size || sourceRootEvidence.map { it.path }.toSet() != sourceRoots.toSet()) {
                        fail("gradle.sync.sourceRootProvenanceUnavailable", "Evaluated source-root evidence must cover every root exactly once")
                    }
                    var compilerOptionsCharacterCount = 0
                    val compilerOptions = rawCompilerArguments.mapIndexed { index, element ->
                        val argument = element.jsonObject
                        val rawValue = argument["value"]?.jsonPrimitive?.content
                            ?: fail("gradle.sync.compilerOptionsUnavailable", "Gradle compiler argument value is malformed")
                        val redactionRecorded = argument["redacted"]?.jsonPrimitive?.booleanOrNull == true
                        val sensitive = redactionRecorded || Regex("(?i)(password|secret|token|api[-_]?key|credential|authorization)\\s*[=:]")
                            .containsMatchIn(rawValue)
                        val value = if (sensitive) "<redacted>" else rawValue
                        compilerOptionsCharacterCount += value.length
                        if (value.length > 16_384 || compilerOptionsCharacterCount > 65_536) {
                            compilerOptionsAvailability = BuildFactAvailability.PARTIAL
                            fail("gradle.sync.limitExceeded", "Gradle compiler argument values exceed their byte bounds")
                        }
                        val origin = "gradle-java-compile:$compileTaskPath:sourceSet=$id:compilerArgs[$index]"
                        if (origin.length > 512) fail("gradle.sync.limitExceeded", "Gradle compiler argument provenance exceeds its limit")
                        BuildCompilerOption("compilerArg", value, origin, sensitive)
                    }
                    val rawRelease = set["release"]?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive?.content
                    val release = rawRelease?.toIntOrNull()
                    if (rawRelease != null && release == null) {
                        fail("gradle.sync.sourceLevelUnavailable", "Gradle release value could not be modeled")
                    }
                    val declaredSource = set.text("source").removePrefix("1.")
                    val source = when (val decision = JavaProjectBuildMetadataSourceLevelPolicy.resolve(
                        release?.toString(), if (release == null) declaredSource else null,
                    )) {
                        is JavaProjectSourceLevelDecision.Supported -> decision.sourceLevel
                        is JavaProjectSourceLevelDecision.Refused -> fail(
                            if (decision.code == "java.context.sourceLevelUnavailable") {
                                "gradle.sync.sourceLevelUnavailable"
                            } else "gradle.sync.sourceLevelUnsupported",
                            decision.message,
                        )
                    }
                    val platformResolver = JavaReleasePlatformAuthorityResolver()
                    val platformResolution = if (source >= JavaProjectBuildMetadataSourceLevelPolicy.FIRST_UNQUALIFIED_GA_RELEASE) {
                        platformResolver.resolveMetadata(sdk, source)
                    } else {
                        platformResolver.resolve(sdk, source)
                    }
                    when (val platform = platformResolution) {
                        is JavaReleasePlatformResolution.Available -> Unit
                        is JavaReleasePlatformResolution.Refused -> fail(
                            "gradle.sync.sourceLevelUnsupported",
                            platform.message,
                        )
                    }
                    val compile = paths(set.getValue("compile"), false)
                    val runtimeEntries = paths(set.getValue("runtime"), false)
                    val dependencies = set.getValue("dependencies").jsonArray.map { edge ->
                        val dependency = edge.jsonObject
                        BuildDependency(
                            resolveModuleId(dependency.text("buildPath"), dependency.text("projectPath")),
                            DependencyScope.valueOf(dependency.text("scope")),
                        )
                    }
                    val runtimeModuleIds = dependencies.filter { it.scope != DependencyScope.PROVIDED }
                        .mapTo(mutableSetOf()) { it.targetModuleId }
                    val runtimePaths = runtimeEntries.toSet()
                    val runtimeArtifactRows = set.getValue("runtimeProjectArtifacts").jsonArray
                    if (runtimeArtifactRows.size > 4096) {
                        fail("gradle.sync.limitExceeded", "Resolved Gradle runtime project artifacts exceed 4096 entries")
                    }
                    val runtimeArtifactOwners = mutableMapOf<Path, String>()
                    runtimeArtifactRows.forEach { element ->
                        val row = element.jsonObject
                        val path = paths(JsonArray(listOf(row.getValue("path"))), false).single()
                        val targetId = resolveModuleId(row.text("buildPath"), row.text("projectPath"))
                        if (path !in runtimePaths || targetId != moduleId && targetId !in runtimeModuleIds) {
                            fail("gradle.sync.runtimeArtifactOwnerUnavailable",
                                "Resolved Gradle project artifact is outside its source-set runtime closure")
                        }
                        val prior = runtimeArtifactOwners.putIfAbsent(path, targetId)
                        if (prior != null && prior != targetId) {
                            fail("gradle.sync.runtimeArtifactOwnerAmbiguous",
                                "Multiple resolved Gradle projects own the same runtime artifact path")
                        }
                    }
                    val jpmsModulePathAvailability = when (set["jpmsModulePathAvailability"]?.jsonPrimitive?.content) {
                        "complete" -> BuildFactAvailability.COMPLETE
                        "partial" -> BuildFactAvailability.PARTIAL
                        else -> fail("gradle.sync.jpmsModulePathUnavailable", "Gradle JPMS module-path inference was not classified")
                    }
                    val jpmsInferenceEnabled = set["jpmsInferenceEnabled"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.booleanOrNull
                    val rawJpmsModulePath = set["jpmsModulePath"]?.jsonArray
                        ?: fail("gradle.sync.jpmsModulePathUnavailable", "Gradle JPMS module path was not exported")
                    if (rawJpmsModulePath.size > 256) {
                        fail("gradle.sync.limitExceeded", "Gradle JPMS module path exceeds 256 entries")
                    }
                    val jpmsModulePathFact = BuildJpmsModulePathFact(
                        availability = jpmsModulePathAvailability,
                        inferenceEnabled = jpmsInferenceEnabled,
                        entries = paths(JsonArray(rawJpmsModulePath), false),
                        origin = if (jpmsModulePathAvailability == BuildFactAvailability.COMPLETE) compileTaskPath else null,
                    )
                    val annotationProcessorAvailability = when (set["annotationProcessorAvailability"]?.jsonPrimitive?.content) {
                        "complete" -> BuildFactAvailability.COMPLETE
                        "partial" -> BuildFactAvailability.PARTIAL
                        "not-declared" -> BuildFactAvailability.NOT_DECLARED
                        else -> fail("gradle.sync.annotationProcessorContextUnavailable", "Gradle annotation processor completeness was not classified")
                    }
                    val rawAnnotationProcessorPath = set["annotationProcessorPath"]?.jsonArray
                        ?: fail("gradle.sync.annotationProcessorContextUnavailable", "Gradle annotation processor path was not exported")
                    val rawAnnotationProcessorNames = set["annotationProcessorNames"]?.jsonArray
                        ?: fail("gradle.sync.annotationProcessorContextUnavailable", "Gradle annotation processor names were not exported")
                    val rawAnnotationProcessorOptions = set["annotationProcessorOptions"]?.jsonObject
                        ?: fail("gradle.sync.annotationProcessorContextUnavailable", "Gradle annotation processor options were not exported")
                    if (rawAnnotationProcessorPath.size > 256 || rawAnnotationProcessorNames.size > 256 || rawAnnotationProcessorOptions.size > 256) {
                        fail("gradle.sync.limitExceeded", "Gradle annotation processor metadata exceeds its item limit")
                    }
                    val annotationProcessorPath = paths(JsonArray(rawAnnotationProcessorPath), false)
                    val annotationProcessorNames = rawAnnotationProcessorNames.map { it.jsonPrimitive.content }
                    val annotationProcessorOptions = rawAnnotationProcessorOptions.mapValues { (_, value) -> value.jsonPrimitive.content }
                    val annotationProcessorMode = set["annotationProcessorMode"]?.jsonPrimitive?.contentOrNull
                    val annotationProcessors = BuildAnnotationProcessorConfiguration(
                        availability = annotationProcessorAvailability,
                        processorPathEntries = annotationProcessorPath,
                        processorNames = annotationProcessorNames,
                        options = annotationProcessorOptions,
                        mode = annotationProcessorMode,
                        refusalCode = if (annotationProcessorAvailability == BuildFactAvailability.PARTIAL) {
                            "java.context.annotationProcessorContextIncomplete"
                        } else null,
                    )
                    val attachments = set["sourceAttachments"]?.jsonArray.orEmpty()
                    if (attachments.size > 4096) fail("gradle.sync.limitExceeded", "Source attachment inventory exceeds 4096 entries")
                    val sourceMetadata = buildMap<String, String> {
                        put("java.sourceAttachment.count", attachments.size.toString())
                        attachments.forEachIndexed { index, element ->
                            val attachment = element.jsonObject
                            val binary = Path.of(attachment.text("binaryPath")).toAbsolutePath().normalize()
                            val archive = Path.of(attachment.text("sourcePath")).toAbsolutePath().normalize()
                            val component = attachment.text("componentId")
                            if (compile.none { root.resolve(it).normalize() == binary } || component.isBlank() || component.length > 2048 ||
                                !binary.fileName.toString().endsWith(".jar") || !archive.fileName.toString().endsWith(".jar"))
                                fail("gradle.sync.sourceAttachmentInvalid", "Source mapping is not attached to an exact resolved compile JAR/component")
                            put("java.sourceAttachment.$index.binaryPath", binary.toString())
                            put("java.sourceAttachment.$index.sourcePath", archive.toString())
                            put("java.sourceAttachment.$index.componentId", component)
                        }
                    }
                    val outputs = paths(set.getValue("outputs"), true)
                    val kotlinCompiler = set["kotlinCompiler"]?.takeUnless { it is JsonNull }?.jsonObject
                    val kotlinProjection: Pair<BuildLanguageFacet, Map<String, String>>? = kotlinCompiler?.let { evaluated ->
                        try {
                            val roots = paths(evaluated.getValue("roots"), true).sortedBy(Path::toString)
                            val output = relative(evaluated.text("output"))
                            val task = evaluated.text("taskPath")
                            val moduleName = evaluated.text("moduleName")
                            val target = evaluated.text("target").removePrefix("1.")
                            val jdk = evaluated.text("jdk")
                            val freeArgumentsPresent = evaluated["freeArgumentsPresent"]?.jsonPrimitive?.booleanOrNull
                            if (roots.isEmpty() || roots.size > ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET ||
                                output !in outputs || !task.startsWith(':') || task.length > 512 ||
                                moduleName.isBlank() || moduleName.length > 128 || '\u0000' in moduleName ||
                                target.toIntOrNull() == null || jdk.toIntOrNull() == null || freeArgumentsPresent == null
                            ) fail("gradle.sync.kotlinCompilerEvidenceInvalid", "Kotlin compiler evidence is incomplete or outside the source set")
                            val facet = BuildLanguageFacet("kotlin", "jvm", "kotlinc",
                                evaluated["languageVersion"]?.jsonPrimitive?.contentOrNull, target, jdk,
                                evidence = BuildLanguageEvidence.DERIVED)
                            facet to buildMap<String, String> {
                                put("kotlin.compileTaskPath", task)
                                put("kotlin.moduleName", moduleName)
                                put("kotlin.outputDirectory", ProtocolPath.serialize(output))
                                put("kotlin.sourceRoot.count", roots.size.toString())
                                roots.forEachIndexed { index, path -> put("kotlin.sourceRoot.$index", ProtocolPath.serialize(path)) }
                                put("kotlin.freeCompilerArgumentsPresent", freeArgumentsPresent.toString())
                                evaluated["apiVersion"]?.jsonPrimitive?.contentOrNull?.let { put("kotlin.apiVersion", it) }
                            }
                        } catch (_: GradleSyncFailure) {
                            null // Kotlin inputs cannot confer ownership outside the evaluated workspace.
                        } catch (_: IllegalArgumentException) {
                            null // Malformed Kotlin metadata leaves the existing Java model available.
                        }
                    }
                    BuildSourceSet(id, when (id) { "main" -> SourceSetKind.MAIN; "test" -> SourceSetKind.TEST; else -> SourceSetKind.CUSTOM },
                        sourceRoots = sourceRoots, generatedSourceRoots = generated, sourceRootEvidence = sourceRootEvidence,
                        outputDirectories = outputs, classpathEntries = compile,
                        runtimeClasspathEntries = runtimeEntries,
                        compilerOptions = compilerOptions,
                        compilerOptionsAvailability = compilerOptionsAvailability,
                        compilerOptionsRefusalCode = if (compilerOptionsAvailability == BuildFactAvailability.COMPLETE) null
                            else "java.context.compilerOptionsIncomplete",
                        annotationProcessors = annotationProcessors,
                        jpmsModulePath = jpmsModulePathFact,
                        moduleDependencies = dependencies,
                        attributes = buildMap {
                            put("java.sourceLevel", source.toString()); put("java.targetLevel", release?.toString() ?: set.text("target")); put("java.compilerHome", compilerHome.toString())
                            put("classpathClosure", "resolved")
                            put("java.sourceRootProvenance.availability", if (sourceRootEvidence.all {
                                it.availability == BuildFactAvailability.COMPLETE }) "complete" else "partial")
                            if (sourceRootEvidence.any { it.availability != BuildFactAvailability.COMPLETE }) {
                                put("java.sourceRootProvenance.refusalCode", "java.context.sourceRootProvenanceIncomplete")
                            }
                            putAll(sourceMetadata)
                            runtimeArtifactOwners.forEach { (path, targetId) ->
                                if (mainArtifactsByModule[targetId] == path) {
                                    put(GRADLE_RUNTIME_ARTIFACT_OWNER_PREFIX + path, targetId)
                                }
                            }
                            release?.let { put("java.release", it.toString()) }
                            if (kotlinCompiler != null) {
                                put("kotlin.compilerEvidenceAvailability", if (kotlinProjection != null) "complete" else "partial")
                            }
                            putAll(kotlinProjection?.second.orEmpty())
                        }, languageFacets = listOfNotNull(BuildLanguageFacet("java", "jvm", "javac", source.toString(),
                            release?.toString() ?: set.text("target"), evidence = BuildLanguageEvidence.DERIVED), kotlinProjection?.first))
                }, attributes = mapOf(
                    "java.buildSystem" to "gradle", "java.buildModel.status" to "available",
                    "gradle.buildRoot" to buildRoot, "gradle.projectPath" to module.text("id"),
                    "gradle.buildPath" to build.text("buildPath"),
                    "gradle.buildTreeProjectPath" to module.text("buildTreeProjectPath"),
                    "gradle.mainArtifact" to (module["artifact"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let { relative(it).toString() }.orEmpty()),
                ))
        }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return BuildModel("gradle-effective-v1", BuildModelStatus.AVAILABLE, modules, attributes = mapOf(
            "ecosystem" to "gradle", "strategy" to "gradle-evaluated-v1", "buildCodeExecution" to "allowed-explicit",
            "credentialsAccess" to "build-controlled", "networkAccess" to "offline-resolution", "gradle.version" to document.text("gradleVersion"),
            "gradle.compilerSdkHome" to sdk.toString(), "gradle.runtimeJdkHome" to runtime.toString(),
            "gradle.compositeBuildCount" to buildRows.size.toString(),
            "gradle.exportSha256" to digest(document.toString().toByteArray(Charsets.UTF_8)),
            "gradle.workspaceRoot" to workspaceRoot.toString(),
            "gradle.projectPropertiesSha256" to digest(JsonObject(properties.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)),
            "gradle.exporterSha256" to digest(GradleJavaModelExporter.SCRIPT.toByteArray(Charsets.UTF_8))))
    }
}
