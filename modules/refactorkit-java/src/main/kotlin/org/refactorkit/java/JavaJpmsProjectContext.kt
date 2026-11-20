package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.compiler.IProblem
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.ExportsDirective
import org.eclipse.jdt.core.dom.Name
import org.eclipse.jdt.core.dom.OpensDirective
import org.eclipse.jdt.core.dom.ProvidesDirective
import org.eclipse.jdt.core.dom.RequiresDirective
import org.eclipse.jdt.core.dom.UsesDirective
import org.refactorkit.core.BuildFactAvailability
import org.refactorkit.core.BuildCompilerOption
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import java.lang.module.FindException
import java.lang.module.ModuleFinder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class JavaContextRequiresFact(
    val moduleName: String,
    val modifiers: List<String>,
)

data class JavaContextExportsFact(
    val packageName: String,
    val targets: List<String>,
)

data class JavaContextOpensFact(
    val packageName: String,
    val targets: List<String>,
)

data class JavaContextProvidesFact(
    val serviceName: String,
    val implementations: List<String>,
)

data class JavaContextModuleDescriptorFact(
    val path: Path,
    val moduleName: String,
    val open: Boolean,
    val requires: List<JavaContextRequiresFact>,
    val exports: List<JavaContextExportsFact>,
    val contentSha256: String,
    val opens: List<JavaContextOpensFact> = emptyList(),
    val uses: List<String> = emptyList(),
    val provides: List<JavaContextProvidesFact> = emptyList(),
)

data class JavaContextModulePathEntry(
    val path: Path,
    val moduleNames: List<String>,
    val fingerprint: String?,
)

data class JavaJpmsSourceSetContext(
    val availability: BuildFactAvailability,
    val modulePath: List<JavaContextModulePathEntry> = emptyList(),
    val descriptors: List<JavaContextModuleDescriptorFact> = emptyList(),
    val refusalCode: String? = null,
) {
    companion object {
        val NOT_APPLICABLE = JavaJpmsSourceSetContext(BuildFactAvailability.NOT_DECLARED)
    }
}

/** Parses only Java module descriptors and configured module-path inputs; no build or processor code runs. */
object JavaJpmsProjectContextResolver {
    private data class ParsedDescriptor(
        val fact: JavaContextModuleDescriptorFact,
        val complete: Boolean,
    )

    fun resolve(
        snapshot: ProjectSnapshot,
        moduleRoot: Path,
        sourceSet: BuildSourceSet,
        compilerOptions: List<BuildCompilerOption>,
        compilerOptionsAvailability: BuildFactAvailability,
        moduleSourceRoots: List<Path> = sourceSet.sourceRoots,
        configuredSdkHome: Path,
    ): JavaJpmsSourceSetContext {
        val declaredSourceLevel = sourceSet.attributes["java.release"]
            ?: sourceSet.attributes["java.sourceLevel"]
            ?: "8"
        val sourceRelease = when (val decision = JavaProjectSourceLevelPolicy.resolve(declaredSourceLevel, null)) {
            is JavaProjectSourceLevelDecision.Supported -> decision.sourceLevel
            is JavaProjectSourceLevelDecision.Refused -> return JavaJpmsSourceSetContext(
                BuildFactAvailability.UNAVAILABLE,
                refusalCode = decision.code,
            )
        }
        val sourceFiles = snapshot.files.filter { file ->
            file.languageId == "java" && file.path.fileName?.toString() == "module-info.java" &&
                moduleSourceRoots.any(file.path::startsWith)
        }
        if (sourceFiles.size > 1) return JavaJpmsSourceSetContext(
            BuildFactAvailability.AMBIGUOUS,
            refusalCode = "java.context.jpmsDescriptorAmbiguous",
        )
        val parsedDescriptor = sourceFiles.singleOrNull()?.let { parseDescriptor(it, sourceRelease) }
        if (sourceFiles.isNotEmpty() && parsedDescriptor == null) return JavaJpmsSourceSetContext(
                BuildFactAvailability.UNAVAILABLE,
                refusalCode = "java.context.jpmsDescriptorUnavailable",
            )
        val parsed = parsedDescriptor?.fact
        // Java 8 and earlier have no module-system compiler options. An incomplete
        // option inventory therefore cannot hide a JPMS input for these releases.
        if (parsed == null && sourceRelease in 1..8) {
            return JavaJpmsSourceSetContext.NOT_APPLICABLE
        }
        val configuredPaths = modulePathValues(compilerOptions, moduleRoot)
        val gradleInference = sourceSet.jpmsModulePath
        val paths = when {
            configuredPaths.availability != BuildFactAvailability.COMPLETE || configuredPaths.paths.isNotEmpty() -> configuredPaths
            parsed != null && sourceRelease >= 9 && gradleInference != null -> {
                if (gradleInference.availability != BuildFactAvailability.COMPLETE || gradleInference.inferenceEnabled == null) {
                    ModulePathResolution(emptyList(), BuildFactAvailability.PARTIAL)
                } else if (gradleInference.inferenceEnabled == true) {
                    // Evaluated Gradle module-path entries are workspace-relative in the
                    // neutral build model; resolve them from the snapshot root, not the
                    // selected subproject root or daemon's current working directory.
                    ModulePathResolution(
                        gradleInference.entries.map { entry ->
                            (if (entry.isAbsolute) entry else snapshot.workspace.root.resolve(entry))
                                .toAbsolutePath().normalize()
                        },
                        BuildFactAvailability.COMPLETE,
                    )
                } else {
                    ModulePathResolution(emptyList(), BuildFactAvailability.COMPLETE)
                }
            }
            parsed != null && sourceRelease >= 9 &&
                effectiveMavenUseModulePath(compilerOptions) == true -> resolvedMavenModulePath(sourceSet, moduleRoot)
            else -> configuredPaths
        }
        if (paths.availability != BuildFactAvailability.COMPLETE ||
            compilerOptionsAvailability !in setOf(BuildFactAvailability.COMPLETE, BuildFactAvailability.NOT_DECLARED)) {
            return JavaJpmsSourceSetContext(
                BuildFactAvailability.PARTIAL,
                descriptors = listOfNotNull(parsed),
                refusalCode = "java.context.jpmsModulePathUnavailable",
            )
        }
        if (paths.paths.isEmpty() && parsed == null) return JavaJpmsSourceSetContext.NOT_APPLICABLE
        if (paths.paths.any { !Files.exists(it) }) return JavaJpmsSourceSetContext(
            BuildFactAvailability.UNAVAILABLE,
            descriptors = listOfNotNull(parsed),
            refusalCode = "java.context.jpmsModulePathUnavailable",
        )
        val moduleReferences = try {
            val references = mutableListOf<java.lang.module.ModuleReference>()
            paths.paths.forEach { path ->
                if (Files.isDirectory(path) && !Files.isRegularFile(path.resolve("module-info.class"))) {
                    val childCount = Files.list(path).use { children -> children.limit(MAX_JPMS_MODULES.toLong() + 1).count() }
                    if (childCount > MAX_JPMS_MODULES) return JavaJpmsSourceSetContext(
                        BuildFactAvailability.PARTIAL,
                        descriptors = listOfNotNull(parsed),
                        refusalCode = "java.context.jpmsModulePathLimitExceeded",
                    )
                }
                val found = ModuleFinder.of(path).findAll()
                if (references.size + found.size > MAX_JPMS_MODULES) return JavaJpmsSourceSetContext(
                    BuildFactAvailability.PARTIAL,
                    descriptors = listOfNotNull(parsed),
                    refusalCode = "java.context.jpmsModulePathLimitExceeded",
                )
                references.addAll(found)
            }
            references
        } catch (_: FindException) {
            return JavaJpmsSourceSetContext(
                BuildFactAvailability.PARTIAL,
                descriptors = listOfNotNull(parsed),
                refusalCode = "java.context.jpmsModulePathInvalid",
            )
        } catch (_: SecurityException) {
            return JavaJpmsSourceSetContext(
                BuildFactAvailability.UNAVAILABLE,
                descriptors = listOfNotNull(parsed),
                refusalCode = "java.context.jpmsModulePathUnavailable",
            )
        } catch (_: java.io.IOException) {
            return JavaJpmsSourceSetContext(
                BuildFactAvailability.UNAVAILABLE,
                descriptors = listOfNotNull(parsed),
                refusalCode = "java.context.jpmsModulePathUnavailable",
            )
        }
        val duplicateNames = moduleReferences.groupingBy { it.descriptor().name() }.eachCount().filterValues { it > 1 }
        if (duplicateNames.isNotEmpty()) return JavaJpmsSourceSetContext(
            BuildFactAvailability.AMBIGUOUS,
            descriptors = listOfNotNull(parsed),
            refusalCode = "java.context.jpmsModulePathAmbiguous",
        )
        val moduleDescriptors = moduleReferences.associateBy { it.descriptor().name() }
        val requiredSystemModules = parsed?.requires.orEmpty().map(JavaContextRequiresFact::moduleName)
            .filter { it.startsWith("java.") || it.startsWith("jdk.") }
        val runtimeHome = runCatching { Path.of(System.getProperty("java.home")).toRealPath() }.getOrNull()
        val configuredHome = runCatching { configuredSdkHome.toRealPath() }.getOrNull()
        val currentRuntimeRelease = Runtime.version().feature()
        if (requiredSystemModules.isNotEmpty() &&
            (configuredHome == null || configuredHome != runtimeHome || sourceRelease != currentRuntimeRelease)) {
            return JavaJpmsSourceSetContext(
                BuildFactAvailability.PARTIAL,
                modulePath = modulePathEntries(paths.paths, moduleReferences, snapshot),
                descriptors = listOfNotNull(parsed),
                refusalCode = "java.context.jpmsSystemModuleGraphUnavailable",
            )
        }
        val systemModules = if (requiredSystemModules.isEmpty()) emptySet() else runCatching {
            ModuleFinder.ofSystem().findAll().map { it.descriptor().name() }.toSet()
        }.getOrDefault(emptySet())
        if (requiredSystemModules.isNotEmpty() && systemModules.isEmpty()) return JavaJpmsSourceSetContext(
            BuildFactAvailability.UNAVAILABLE,
            modulePath = modulePathEntries(paths.paths, moduleReferences, snapshot),
            descriptors = listOfNotNull(parsed),
            refusalCode = "java.context.jpmsSystemModuleGraphUnavailable",
        )
        val missingRequires = parsed?.requires.orEmpty().map(JavaContextRequiresFact::moduleName)
            .filterNot { it in systemModules || it in moduleDescriptors }
        if (missingRequires.isNotEmpty()) return JavaJpmsSourceSetContext(
            BuildFactAvailability.PARTIAL,
            modulePath = modulePathEntries(paths.paths, moduleReferences, snapshot),
            descriptors = listOfNotNull(parsed),
            refusalCode = "java.context.jpmsRequiredModuleUnavailable",
        )
        return JavaJpmsSourceSetContext(
            if (parsedDescriptor?.complete == false) BuildFactAvailability.PARTIAL else BuildFactAvailability.COMPLETE,
            modulePath = modulePathEntries(paths.paths, moduleReferences, snapshot),
            descriptors = listOfNotNull(parsed),
            refusalCode = if (parsedDescriptor?.complete == false) {
                "java.context.jpmsDescriptorIncomplete"
            } else null,
        )
    }

    private fun modulePathEntries(
        paths: List<Path>,
        references: List<java.lang.module.ModuleReference>,
        snapshot: ProjectSnapshot,
    ): List<JavaContextModulePathEntry> = paths.map { path ->
        val pathUri = path.toUri().normalize()
        val names = references.filter { reference ->
            val location = reference.location().orElse(null)?.normalize() ?: return@filter false
            location == pathUri || location.scheme == pathUri.scheme && location.path?.startsWith(pathUri.path.orEmpty().trimEnd('/') + "/") == true
        }.map { it.descriptor().name() }.distinct().sorted()
        val evidence = snapshot.classpathEvidence.firstOrNull { item ->
            val evidencePath = if (item.path.isAbsolute) item.path else snapshot.workspace.root.resolve(item.path)
            evidencePath.toAbsolutePath().normalize() == path
        }?.fingerprint
        JavaContextModulePathEntry(path, names, evidence)
    }

    private data class ModulePathResolution(val paths: List<Path>, val availability: BuildFactAvailability)

    /**
     * Maven Compiler Plugin 3.11+ places the resolved compile path on the module
     * path by default for Java 9+ sources with module-info.java. The Maven provider
     * records that effective default as a compiler option; use only the already
     * resolved, ordered source-set classpath here and refuse incomplete artifacts.
     */
    private fun effectiveMavenUseModulePath(options: List<BuildCompilerOption>): Boolean? =
        options.lastOrNull { it.name == "useModulePath" && it.origin.startsWith("maven-compiler-plugin") }
            ?.value?.toBooleanStrictOrNull()

    private fun resolvedMavenModulePath(sourceSet: BuildSourceSet, moduleRoot: Path): ModulePathResolution {
        if (sourceSet.resolvedArtifactAvailability != BuildFactAvailability.COMPLETE) {
            return ModulePathResolution(emptyList(), BuildFactAvailability.PARTIAL)
        }
        fun absolute(path: Path): Path = (if (path.isAbsolute) path else moduleRoot.resolve(path))
            .toAbsolutePath().normalize()
        val artifacts = sourceSet.resolvedArtifacts.associateBy { absolute(it.path) }
        val classpath = sourceSet.classpathEntries.map(::absolute).distinct()
        val candidates = classpath.filter { path ->
            val artifact = artifacts[path] ?: return@filter false
            artifact.extension.equals("jar", ignoreCase = true) && Files.isRegularFile(path)
        }
        val unownedJars = classpath.filter { path ->
            path.fileName?.toString()?.endsWith(".jar", ignoreCase = true) == true && path !in artifacts
        }
        if (unownedJars.isNotEmpty()) return ModulePathResolution(emptyList(), BuildFactAvailability.PARTIAL)
        if (candidates.size > MAX_JPMS_MODULE_PATH_ENTRIES) {
            return ModulePathResolution(emptyList(), BuildFactAvailability.PARTIAL)
        }
        return ModulePathResolution(candidates, BuildFactAvailability.COMPLETE)
    }

    private fun modulePathValues(
        options: List<BuildCompilerOption>,
        moduleRoot: Path,
    ): ModulePathResolution {
        val compilerArgs = options.filter { it.name == "compilerArg" }
        val raw = mutableListOf<String>()
        var availability = BuildFactAvailability.COMPLETE
        var index = 0
        while (index < compilerArgs.size) {
            val value = compilerArgs[index].value
            when {
                value in setOf("--module-path", "-p") -> {
                    val next = compilerArgs.getOrNull(index + 1)?.value
                    if (next == null || next.startsWith('-')) availability = BuildFactAvailability.PARTIAL
                    else raw += next.also { index++ }
                }
                value.startsWith("--module-path=") -> raw += value.substringAfter('=')
                value.startsWith("-p=") -> raw += value.substringAfter('=')
                value.contains("--module-path") || value == "-p" || value.startsWith("-p=") ->
                    availability = BuildFactAvailability.PARTIAL
            }
            index++
        }
        options.filter { it.name in setOf("compilerArgument", "compilerArgs") }.forEach { option ->
            val value = option.value
            val match = Regex("(?:^|\\s)(?:--module-path|-p)(?:=|\\s)+([^\\s]+)").find(value)
            if (match == null && ("--module-path" in value || Regex("(?:^|\\s)-p(?:\\s|=)").containsMatchIn(value)) ||
                (value.contains('"') || value.contains('\'')) && ("--module-path" in value || Regex("(?:^|\\s)-p(?:\\s|=)").containsMatchIn(value))) {
                availability = BuildFactAvailability.PARTIAL
            } else match?.groupValues?.getOrNull(1)?.let(raw::add)
        }
        val paths = linkedSetOf<Path>()
        raw.forEach { value ->
            var start = 0
            while (start <= value.length) {
                if (paths.size >= MAX_JPMS_MODULE_PATH_ENTRIES) {
                    availability = BuildFactAvailability.PARTIAL
                    break
                }
                val separator = value.indexOf(java.io.File.pathSeparatorChar, start)
                val end = if (separator < 0) value.length else separator
                val rawPath = value.substring(start, end).trim()
                if (rawPath.isNotBlank()) {
                    val path = runCatching { Path.of(rawPath) }.getOrNull()
                    if (path == null) availability = BuildFactAvailability.PARTIAL
                    else paths.add((if (path.isAbsolute) path else moduleRoot.resolve(path)).toAbsolutePath().normalize())
                }
                if (separator < 0) break else start = separator + 1
            }
        }
        return ModulePathResolution(paths.toList(), availability)
    }

    private fun parseDescriptor(file: SourceFile, release: Int): ParsedDescriptor? = runCatching {
        if (file.content.length > MAX_MODULE_DESCRIPTOR_CHARS) return null
        if (!JavaParserReleaseSupport.supportsRelease(release)) return null
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setUnitName(file.path.fileName.toString())
        parser.setSource(file.content.toCharArray())
        parser.setCompilerOptions(JavaCore.getOptions().also {
            JavaCore.setComplianceOptions(if (release == 8) "1.8" else release.toString(), it)
        })
        val unit = parser.createAST(null) as CompilationUnit
        val module = unit.module ?: return null
        if (unit.problems.any { it.isError && it.id and IProblem.Syntax != 0 }) return null
        if (module.moduleStatements().size > MAX_MODULE_DIRECTIVES) return null
        val complete = module.moduleStatements().all { directive ->
            directive is RequiresDirective || directive is ExportsDirective || directive is OpensDirective ||
                directive is UsesDirective || directive is ProvidesDirective
        }
        val requires = module.moduleStatements().filterIsInstance<RequiresDirective>().map { directive ->
            JavaContextRequiresFact(
                directive.name.fullyQualifiedName,
                directive.modifiers().map { it.toString() }.sorted(),
            )
        }.sortedBy(JavaContextRequiresFact::moduleName)
        val exports = module.moduleStatements().filterIsInstance<ExportsDirective>().map { directive ->
            JavaContextExportsFact(
                directive.name.fullyQualifiedName,
                (directive.modules() as List<*>).filterIsInstance<Name>().map(Name::getFullyQualifiedName).sorted(),
            )
        }.sortedBy(JavaContextExportsFact::packageName)
        val opens = module.moduleStatements().filterIsInstance<OpensDirective>().map { directive ->
            JavaContextOpensFact(
                directive.name.fullyQualifiedName,
                (directive.modules() as List<*>).filterIsInstance<Name>().map(Name::getFullyQualifiedName).sorted(),
            )
        }.sortedBy(JavaContextOpensFact::packageName)
        val uses = module.moduleStatements().filterIsInstance<UsesDirective>()
            .map { it.name.fullyQualifiedName }.sorted()
        val provides = module.moduleStatements().filterIsInstance<ProvidesDirective>().map { directive ->
            JavaContextProvidesFact(
                directive.name.fullyQualifiedName,
                (directive.implementations() as List<*>).filterIsInstance<Name>().map(Name::getFullyQualifiedName).sorted(),
            )
        }.sortedBy(JavaContextProvidesFact::serviceName)
        if (module.name.fullyQualifiedName.length > MAX_MODULE_NAME_CHARS ||
            requires.any { it.moduleName.length > MAX_MODULE_NAME_CHARS || it.modifiers.any { modifier -> modifier.length > 32 } } ||
            exports.any { it.packageName.length > MAX_PACKAGE_NAME_CHARS || it.targets.any { target -> target.length > MAX_MODULE_NAME_CHARS } } ||
            opens.any { it.packageName.length > MAX_PACKAGE_NAME_CHARS || it.targets.any { target -> target.length > MAX_MODULE_NAME_CHARS } } ||
            uses.any { it.length > MAX_PACKAGE_NAME_CHARS } ||
            provides.any { fact -> fact.serviceName.length > MAX_PACKAGE_NAME_CHARS ||
                fact.implementations.any { it.length > MAX_PACKAGE_NAME_CHARS } }) return null
        ParsedDescriptor(
            fact = JavaContextModuleDescriptorFact(
                path = file.path,
                moduleName = module.name.fullyQualifiedName,
                open = module.isOpen,
                requires = requires,
                exports = exports,
                contentSha256 = sha256(file.content.toByteArray(Charsets.UTF_8)),
                opens = opens,
                uses = uses,
                provides = provides,
            ),
            complete = complete,
        )
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private const val MAX_JPMS_MODULES = 4_096
    private const val MAX_JPMS_MODULE_PATH_ENTRIES = 4_096
    private const val MAX_MODULE_DESCRIPTOR_CHARS = 1_048_576
    private const val MAX_MODULE_DIRECTIVES = 1_024
    private const val MAX_MODULE_NAME_CHARS = 512
    private const val MAX_PACKAGE_NAME_CHARS = 1_024
}
