package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.internal.compiler.batch.FileSystem
import org.eclipse.jdt.internal.compiler.batch.Main
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants
import org.eclipse.jdt.internal.compiler.env.IBinaryAnnotation
import org.eclipse.jdt.internal.compiler.env.IBinaryModule
import org.eclipse.jdt.internal.compiler.env.IModule
import org.eclipse.jdt.internal.core.dom.ICompilationUnitResolver
import org.refactorkit.core.BuildCompilerOption
import java.io.PrintWriter
import java.io.Writer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path

/**
 * Adds the effective module path to JDT's standalone resolver.
 *
 * ASTParser.setEnvironment only accepts classpath/sourcepath arrays. JDT 3.44's
 * resolver does expose ECJ's module-aware classpath entries at the resolver
 * boundary, so module-path entries are created with ECJ's own module-path
 * parser and kept distinct from ordinary classpath entries. A complete source
 * descriptor is projected as an ECJ binary module descriptor and associated
 * with its exact source root. The descriptor is snapshot data; no build code or
 * project classes are loaded or executed.
 */
internal object JdtJpmsClasspath {
    fun prepare(
        parser: ASTParser,
        resources: JdtClasspathResourceScope,
        sourceRoots: Array<String>,
        sourceLevel: Int,
        compilerOptions: List<BuildCompilerOption>,
        context: JavaJpmsSourceSetContext,
    ): String? {
        val applicable = context.modulePath.isNotEmpty() || context.descriptors.isNotEmpty()
        if (!applicable) return null
        if (context.availability != org.refactorkit.core.BuildFactAvailability.COMPLETE) {
            throw JdtJpmsAnalysisException(
                context.refusalCode ?: "java.context.jpmsSemanticAnalysisUnavailable",
                "The selected source set's JPMS environment is not complete enough for semantic analysis",
            )
        }
        if (sourceLevel < 9) throw JdtJpmsAnalysisException(
            "java.context.jpmsSemanticAnalysisUnavailable",
            "JPMS semantic analysis requires a configured Java 9 or later source level",
        )
        rejectUnmodeledModuleOptions(compilerOptions)

        val descriptor = context.descriptors.singleOrNull()
        val modulePathEntries = modulePathEntries(context.modulePath.map { it.path }, sourceLevel)
        resources.trackClasspaths(modulePathEntries)
        val expectedNames = context.modulePath.flatMap { it.moduleNames }.toSet()
        val observedNames = modulePathEntries.flatMap { it.getModuleNames(null) }.toSet()
        if (expectedNames != observedNames) throw JdtJpmsAnalysisException(
            "java.context.jpmsModulePathUnavailable",
            "ECJ could not reproduce the snapshot-bound module-path module inventory",
        )
        if (descriptor != null && descriptor.moduleName in observedNames) throw JdtJpmsAnalysisException(
            "java.context.jpmsModulePathAmbiguous",
            "The source module name collides with a module-path dependency",
        )

        val descriptorRoot = descriptor?.path?.normalize()?.parent
        val sourceRoot = descriptorRoot?.let { wanted ->
            sourceRoots.map { Path.of(it).toAbsolutePath().normalize() }.singleOrNull { candidate ->
                if (wanted.isAbsolute) candidate == wanted.toAbsolutePath().normalize() else candidate.endsWith(wanted)
            }
        }
        if (descriptor != null && sourceRoot == null) throw JdtJpmsAnalysisException(
            "java.context.jpmsDescriptorUnavailable",
            "The complete module descriptor has no unique effective source-root owner",
        )
        val sourceModule = descriptor?.let { fact ->
            binaryModule(fact.copy(path = requireNotNull(sourceRoot).resolve(fact.path.fileName)))
        }
        val sourceModuleName = descriptor?.moduleName

        val field = ASTParser::class.java.getDeclaredField("unitResolver").apply { isAccessible = true }
        val delegate = field.get(parser) as ICompilationUnitResolver
        val proxy = Proxy.newProxyInstance(
            ICompilationUnitResolver::class.java.classLoader,
            arrayOf(ICompilationUnitResolver::class.java),
        ) { _, method, arguments ->
            val prepared = arguments?.clone()
            if (prepared != null) prepared.indices.forEach { index ->
                val entries = prepared[index] as? List<*> ?: return@forEach
                if (entries.isEmpty() || entries.any { it !is FileSystem.Classpath }) return@forEach
                resources.trackClasspaths(entries)
                val classpathEntries = entries.filterIsInstance<FileSystem.Classpath>()
                val sourceLocation = sourceRoot?.let { root ->
                    classpathEntries.singleOrNull { entry -> samePath(entry.getPath(), root) }
                }
                if (sourceModule != null && sourceLocation == null) throw JdtJpmsAnalysisException(
                    "java.context.jpmsDescriptorUnavailable",
                    "JDT did not expose the descriptor's effective source root",
                )
                sourceLocation?.acceptModule(sourceModule)
                val moduleEntries = modulePathEntries.map { it as FileSystem.Classpath }
                val moduleEntryPaths = moduleEntries.mapNotNull { entry -> normalizedPath(entry.getPath()) }.toSet()
                val filtered = classpathEntries.filterNot { entry ->
                    normalizedPath(entry.getPath()) in moduleEntryPaths
                }
                prepared[index] = moduleEntries + filtered
            }
            try {
                method.invoke(delegate, *(prepared ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }
        field.set(parser, proxy)
        return sourceModuleName
    }

    private fun modulePathEntries(paths: List<Path>, sourceLevel: Int): List<FileSystem.Classpath> {
        if (paths.isEmpty()) return emptyList()
        val options = JavaCore.getOptions().also {
            JavaCore.setComplianceOptions(if (sourceLevel == 8) JavaCore.VERSION_1_8 else sourceLevel.toString(), it)
        }
        val parser = ModulePathMain(options, sourceLevel.toString())
        return paths.distinct().flatMap { path -> parser.entries(path.toAbsolutePath().normalize().toString()) }
    }

    private class ModulePathMain(options: MutableMap<String, String>, release: String) : Main(
        PrintWriter(Writer.nullWriter()), PrintWriter(Writer.nullWriter()), false, options,
    ) {
        init { releaseVersion = release }
        fun entries(path: String): List<FileSystem.Classpath> = handleModulepath(path)
    }

    internal fun binaryModule(descriptor: JavaContextModuleDescriptorFact): IBinaryModule {
        val requires = descriptor.requires.map { requirement ->
            val modifiers = requirement.modifiers.fold(0) { flags, modifier -> flags or when (modifier) {
                "static" -> ClassFileConstants.ACC_STATIC_PHASE
                "transitive" -> ClassFileConstants.ACC_TRANSITIVE
                else -> 0
            } }
            Proxy.newProxyInstance(
                IModule.IModuleReference::class.java.classLoader,
                arrayOf(IModule.IModuleReference::class.java),
            ) { proxy, method, arguments -> when (method.name) {
                "name" -> requirement.moduleName.toCharArray()
                "getModifiers" -> modifiers
                "isStatic" -> modifiers and ClassFileConstants.ACC_STATIC_PHASE != 0
                "isTransitive" -> modifiers and ClassFileConstants.ACC_TRANSITIVE != 0
                "toString" -> requirement.moduleName
                "hashCode" -> requirement.moduleName.hashCode()
                "equals" -> proxy === arguments?.getOrNull(0)
                else -> null
            } } as IModule.IModuleReference
        }.toTypedArray()
        val exports = descriptor.exports.map { export ->
            val targets = export.targets.takeIf { it.isNotEmpty() }?.map(String::toCharArray)?.toTypedArray()
            Proxy.newProxyInstance(
                IModule.IPackageExport::class.java.classLoader,
                arrayOf(IModule.IPackageExport::class.java),
            ) { proxy, method, arguments -> when (method.name) {
                "name" -> export.packageName.toCharArray()
                "targets" -> targets
                "isQualified" -> targets != null
                "toString" -> export.packageName
                "hashCode" -> export.packageName.hashCode()
                "equals" -> proxy === arguments?.getOrNull(0)
                else -> null
            } } as IModule.IPackageExport
        }.toTypedArray()
        val opens = descriptor.opens.map { open ->
            val targets = open.targets.takeIf { it.isNotEmpty() }?.map(String::toCharArray)?.toTypedArray()
            Proxy.newProxyInstance(
                IModule.IPackageExport::class.java.classLoader,
                arrayOf(IModule.IPackageExport::class.java),
            ) { proxy, method, arguments -> when (method.name) {
                "name" -> open.packageName.toCharArray()
                "targets" -> targets
                "isQualified" -> targets != null
                "toString" -> open.packageName
                "hashCode" -> open.packageName.hashCode()
                "equals" -> proxy === arguments?.getOrNull(0)
                else -> null
            } } as IModule.IPackageExport
        }.toTypedArray()
        val uses = descriptor.uses.map(String::toCharArray).toTypedArray()
        val provides = descriptor.provides.map { provider ->
            val implementations = provider.implementations.map(String::toCharArray).toTypedArray()
            Proxy.newProxyInstance(
                IModule.IService::class.java.classLoader,
                arrayOf(IModule.IService::class.java),
            ) { proxy, method, arguments -> when (method.name) {
                "name" -> provider.serviceName.toCharArray()
                "with" -> implementations
                "toString" -> provider.serviceName
                "hashCode" -> provider.serviceName.hashCode()
                "equals" -> proxy === arguments?.getOrNull(0)
                else -> null
            } } as IModule.IService
        }.toTypedArray()
        return Proxy.newProxyInstance(
            IBinaryModule::class.java.classLoader,
            arrayOf(IBinaryModule::class.java),
        ) { proxy, method, arguments -> when (method.name) {
            "name" -> descriptor.moduleName.toCharArray()
            "requires" -> requires
            "exports" -> exports
            "opens" -> opens
            "uses" -> uses
            "provides" -> provides
            "isOpen" -> descriptor.open
            "isAutomatic", "isAutoNameFromManifest" -> false
            "getAnnotations" -> emptyArray<IBinaryAnnotation>()
            "getTagBits" -> 0L
            "getURI" -> descriptor.path.toUri()
            "toString" -> descriptor.moduleName
            "hashCode" -> descriptor.moduleName.hashCode()
            "equals" -> proxy === arguments?.getOrNull(0)
            else -> null
        } } as IBinaryModule
    }

    private fun rejectUnmodeledModuleOptions(options: List<BuildCompilerOption>) {
        val unmodeled = setOf(
            "--add-exports", "--add-reads", "--add-opens", "--add-modules", "--limit-modules",
            "--patch-module", "--upgrade-module-path", "--module-source-path", "--system", "--module",
        )
        val optionPattern = Regex("(?:^|\\s)(--[a-z-]+)(?:=|\\s|$)")
        val unsupported = options.asSequence().flatMap { option ->
            if (option.redacted) sequenceOf("<redacted>") else sequence {
                yieldAll(optionPattern.findAll(option.value).map { it.groupValues[1] })
                if (Regex("(?:^|\\s)-m(?:=|\\s|$)").containsMatchIn(option.value)) yield("-m")
            }
        }.firstOrNull { it == "<redacted>" || it in unmodeled || it == "-m" }
        if (unsupported != null) throw JdtJpmsAnalysisException(
            "java.context.jpmsSemanticAnalysisUnavailable",
            "The selected source set declares an additional module option that is not represented by the complete JPMS context",
        )
    }

    private fun samePath(value: String, path: Path): Boolean = normalizedPath(value) == path

    private fun normalizedPath(value: String): Path? = runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull()
}

internal class JdtJpmsAnalysisException(val code: String, message: String) : IllegalStateException(message)
