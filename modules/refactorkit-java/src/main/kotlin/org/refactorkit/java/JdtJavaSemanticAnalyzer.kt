package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.compiler.IProblem
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.Annotation
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.CreationReference
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.ConstructorInvocation
import org.eclipse.jdt.core.dom.BooleanLiteral
import org.eclipse.jdt.core.dom.CharacterLiteral
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.NullLiteral
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.ParenthesizedExpression
import org.eclipse.jdt.core.dom.EnumDeclaration
import org.eclipse.jdt.core.dom.ExpressionMethodReference
import org.eclipse.jdt.core.dom.FieldAccess
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ImportDeclaration
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.eclipse.jdt.core.dom.SuperConstructorInvocation
import org.eclipse.jdt.core.dom.SuperFieldAccess
import org.eclipse.jdt.core.dom.SuperMethodInvocation
import org.eclipse.jdt.core.dom.SuperMethodReference
import org.eclipse.jdt.core.dom.StringLiteral
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.RecordDeclaration
import org.eclipse.jdt.core.dom.TextBlock
import org.eclipse.jdt.core.dom.ThisExpression
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.TypeMethodReference
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.eclipse.jdt.core.dom.VariableDeclarationStatement
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.OperationCanceledException
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Files
import java.nio.file.Path

internal sealed interface JdtBoundCompilationUnitResolution<out T> {
    data class Available<T>(val value: T) : JdtBoundCompilationUnitResolution<T>
    data class Refused(val message: String) : JdtBoundCompilationUnitResolution<Nothing>
}

/**
 * Early Eclipse JDT-backed analysis prototype for the v0.3.0 line.
 *
 * This class intentionally exposes evidence, not refactoring authority. Existing
 * lexical planners remain the safety fallback until JDT-backed behavior is wired
 * into planners and covered by semantic tests.
 */
class JdtJavaSemanticAnalyzer {
    /** Run a bounded operation while the exact source-set/JDK JDT bindings remain open. */
    internal fun <T> withBoundCompilationUnit(
        snapshot: ProjectSnapshot,
        filePath: Path,
        action: (CompilationUnit, BuildSourceSet) -> T,
    ): JdtBoundCompilationUnitResolution<T> {
        val authority = snapshot.semanticAnalysisAuthority as? JavaContextAuthority
            ?: return JdtBoundCompilationUnitResolution.Refused(
                "Binding-aware extraction requires an SDK-bound Java project context.",
            )
        val file = snapshot.files.singleOrNull { it.path.normalize() == filePath.normalize() }
            ?: return JdtBoundCompilationUnitResolution.Refused("The selected Java source is absent from the bound snapshot.")
        val ownership = snapshot.owningJavaBuildSourceRoots(file.path).singleOrNull()
            ?: return JdtBoundCompilationUnitResolution.Refused(
                "The selected Java source has no unique effective build source-set owner.",
            )
        if (ownership.generated || ownership.modelStatus != org.refactorkit.core.BuildModelStatus.AVAILABLE) {
            return JdtBoundCompilationUnitResolution.Refused(
                "Binding-aware extraction requires a non-generated source in an available build model.",
            )
        }
        val model = snapshot.buildModels.singleOrNull { it.providerId == ownership.providerId }
            ?: return JdtBoundCompilationUnitResolution.Refused("The selected source-set provider is ambiguous.")
        val sourceSet = ownership.sourceSet
        if (sourceSet.attributes["java.classpath.status"] == "unavailable") {
            return JdtBoundCompilationUnitResolution.Refused("The selected source-set compile classpath is unavailable.")
        }
        val release = when (val decision = JavaProjectSourceLevelPolicy.resolve(
            sourceSet.attributes["java.release"], sourceSet.attributes["java.sourceLevel"],
        )) {
            is JavaProjectSourceLevelDecision.Supported -> decision.sourceLevel
            is JavaProjectSourceLevelDecision.Refused ->
                return JdtBoundCompilationUnitResolution.Refused("${decision.code}: ${decision.message}")
        }
        val apiProfile = try { JavaSourceSetPlatformContext.profile(sourceSet, authority.platforms) }
            catch (failure: JavaContextAuthorityException) {
                return JdtBoundCompilationUnitResolution.Refused("${failure.code}: ${failure.message}")
            }
        val platform = authority.platforms[apiProfile]
            ?: return JdtBoundCompilationUnitResolution.Refused("The selected Java $release platform is not attested.")
        val environment = try {
            JavaBuildSourceSetContextResolver.resolve(model, ownership.module, sourceSet)
        } catch (_: Exception) {
            return JdtBoundCompilationUnitResolution.Refused("The selected source-set compile environment is unavailable.")
        }
        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        fun absolute(path: Path): Path = (if (path.isAbsolute) path else workspace.resolve(path)).toAbsolutePath().normalize()
        if (environment.sourceRoots.none { absolute(file.path).startsWith(absolute(it)) }) {
            return JdtBoundCompilationUnitResolution.Refused(
                "The selected source is outside its effective source-set roots.",
            )
        }
        val sourceRoots = environment.sourceRoots.map(::absolute)
            .filter(Files::isDirectory).distinct().toTypedArray()
        if (sourceRoots.isEmpty()) {
            return JdtBoundCompilationUnitResolution.Refused("The selected source-set source roots are unavailable.")
        }
        val classpath = environment.classpathEntries.map(::absolute).filter(Files::exists).distinct().toTypedArray()
        return try {
            authority.requireCurrent()
            JavaReleasePlatformClasspath.materialize(platform).use { platformClasspath ->
                JdtClasspathResourceScope().use { resources ->
                    val unit = try {
                        parse(
                            file,
                            sourceRoots.map(Path::toString).toTypedArray(),
                            classpath.map(Path::toString).toTypedArray(),
                            release,
                            platform,
                            platformClasspath,
                            resources,
                        )
                    } catch (_: Exception) {
                        return JdtBoundCompilationUnitResolution.Refused(
                            "JDT could not resolve the selected source with its attested Java $release environment.",
                        )
                    }
                    if (unit.problems.any { it.isError && it.id and IProblem.Syntax != 0 }) {
                        return JdtBoundCompilationUnitResolution.Refused(
                            "The selected Java source has syntax errors; binding-aware extraction is refused.",
                        )
                    }
                    val value = action(unit, sourceSet)
                    authority.requireCurrent()
                    JdtBoundCompilationUnitResolution.Available(value)
                }
            }
        } catch (failure: JavaContextAuthorityException) {
            JdtBoundCompilationUnitResolution.Refused("The SDK-bound Java project context is stale: ${failure.message.orEmpty()}")
        } catch (_: Exception) {
            JdtBoundCompilationUnitResolution.Refused(
                "The attested JDT analysis environment could not be kept available for extraction.",
            )
        }
    }

    /** Resolve an attested in-memory dependency source with the existing SDK/parser backend. */
    fun dependencySourceDeclarations(
        file: SourceFile, sourceRoots: List<Path>, classpath: List<Path>,
        platform: JavaReleasePlatformAuthority, cancellation: SemanticCancellationToken,
        platformModuleName: String? = null,
    ): JdtJavaSourceDeclarations = JavaReleasePlatformClasspath.materialize(platform).use { projection ->
        JdtClasspathResourceScope().use declarations@ { resources ->
            if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
            val unit = parse(file, sourceRoots.filter { Files.isDirectory(it) }.map(Path::toString).toTypedArray(),
                classpath.filter { Files.exists(it) }.map(Path::toString).toTypedArray(), platform.release, platform, projection, resources,
                platformModuleName, cancellation = cancellation)
            val ambiguous = unit.problems.any { it.id in setOf(IProblem.DuplicateMethod, IProblem.DuplicateMethodErasure,
                IProblem.DuplicateField, IProblem.DuplicateTypes, IProblem.MethodNameClash, IProblem.MethodNameClashHidden) }
            val invalidSyntax = unit.problems.any { it.isError && it.id and IProblem.Syntax != 0 }
            if (ambiguous || invalidSyntax) return@declarations JdtJavaSourceDeclarations(emptyList(), emptySet(), emptySet(),
                if (ambiguous) JdtJavaSourceDeclarationStatus.AMBIGUOUS else JdtJavaSourceDeclarationStatus.UNAVAILABLE)
            val declarations = mutableListOf<JdtJavaSourceDeclaration>()
            val owners = mutableSetOf<String>()
            val implicitIdentities = mutableSetOf<JdtJavaJvmIdentity>()
            fun completeType(type: ITypeBinding): Boolean = !type.isRecovered && !type.erasure.isRecovered &&
                (!type.isArray || !type.elementType.isRecovered)
            fun add(node: org.eclipse.jdt.core.dom.ASTNode, name: SimpleName, binding: IBinding?) {
                if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
                if (binding == null || binding.isRecovered || binding.key.isNullOrBlank() ||
                    node.flags and (org.eclipse.jdt.core.dom.ASTNode.MALFORMED or org.eclipse.jdt.core.dom.ASTNode.RECOVERED) != 0) return
                if (binding is IMethodBinding && (binding.parameterTypes.any { !completeType(it) } ||
                    (!binding.isConstructor && !completeType(binding.returnType)))) return
                if (binding is IVariableBinding && !completeType(binding.type)) return
                if (platformModuleName != null) {
                    val owner = when (binding) { is ITypeBinding -> binding; is IMethodBinding -> binding.declaringClass; is IVariableBinding -> binding.declaringClass; else -> null }
                    if (owner?.module?.name != platformModuleName) return
                }
                val identity = jvmIdentity(binding) ?: return
                val sourceModifiers = if (binding is ITypeBinding) {
                    (binding.modifiers and 0x6200.inv()) or
                        (if (binding.isInterface || binding.isAnnotation) 0x0200 else 0) or
                        (if (binding.isAnnotation) 0x2000 else 0) or (if (binding.isEnum) 0x4000 else 0)
                } else binding.modifiers
                declarations += JdtJavaSourceDeclaration(identity, rangeFor(unit, node.startPosition, node.length),
                    rangeFor(unit, name.startPosition, name.length), binding.key, sourceModifiers)
                if (identity.kind == JdtJavaJvmIdentityKind.TYPE) {
                    owners += identity.ownerBinaryName
                    (binding as ITypeBinding).declaredMethods.filter { !it.isRecovered &&
                        (it.isDefaultConstructor || it.isSyntheticRecordMethod || it.isSynthetic) &&
                        it.parameterTypes.all(::completeType) && (it.isConstructor || completeType(it.returnType)) }
                        .mapNotNullTo(implicitIdentities, ::jvmIdentity)
                }
            }
            unit.accept(object : ASTVisitor() {
                override fun visit(node: TypeDeclaration): Boolean { add(node, node.name, node.resolveBinding()); return true }
                override fun visit(node: EnumDeclaration): Boolean { add(node, node.name, node.resolveBinding()); return true }
                override fun visit(node: RecordDeclaration): Boolean { add(node, node.name, node.resolveBinding()); return true }
                override fun visit(node: AnnotationTypeDeclaration): Boolean { add(node, node.name, node.resolveBinding()); return true }
                override fun visit(node: MethodDeclaration): Boolean { add(node, node.name, node.resolveBinding()); return true }
                override fun visit(node: AnnotationTypeMemberDeclaration): Boolean { add(node, node.name, node.resolveBinding()); return true }
                override fun visit(node: VariableDeclarationFragment): Boolean {
                    if (node.parent is FieldDeclaration) add(node.parent, node.name, node.resolveBinding())
                    return true
                }
                override fun visit(node: org.eclipse.jdt.core.dom.EnumConstantDeclaration): Boolean {
                    add(node, node.name, node.resolveVariable()); return true
                }
            })
            JdtJavaSourceDeclarations(declarations, owners, implicitIdentities)
        }
    }

    /** API 0.2 entry point retained while ephemeral binary evidence remains additive. */
    fun analyze(
        snapshot: ProjectSnapshot,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): JdtJavaSemanticAnalysisResult = analyze(snapshot, cancellation, emptyList())

    fun analyze(
        snapshot: ProjectSnapshot,
        additionalClasspathEntries: List<Path>,
    ): JdtJavaSemanticAnalysisResult = analyze(snapshot, SemanticCancellationToken.NONE, additionalClasspathEntries)

    fun analyze(
        snapshot: ProjectSnapshot,
        cancellation: SemanticCancellationToken,
        additionalClasspathEntries: List<Path>,
    ): JdtJavaSemanticAnalysisResult {
        val configured = snapshot.semanticAnalysisAuthority
            ?: return try {
                analyzeInternal(snapshot, cancellation, additionalClasspathEntries, emptyMap(), emptyMap())
            } catch (failure: JdtJpmsAnalysisException) {
                throw JavaContextAuthorityException(failure.code, failure.message.orEmpty())
            }
        require(configured is JavaContextAuthority) { "Unsupported Java semantic analysis authority" }
        configured.requireCurrent()
        return when (val resolution = analyzeAuthoritatively(snapshot, configured.platforms, cancellation, additionalClasspathEntries)) {
            is JdtAuthoritativeJavaAnalysisResolution.Available -> resolution.analysis
            is JdtAuthoritativeJavaAnalysisResolution.Refused -> throw JavaContextAuthorityException(resolution.code, resolution.message)
        }
    }

    fun analyzeAuthoritatively(
        snapshot: ProjectSnapshot,
        platformAuthorities: Map<Int, JavaReleasePlatformAuthority>,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
        additionalClasspathEntries: List<Path> = emptyList(),
    ): JdtAuthoritativeJavaAnalysisResolution {
        val requiredReleases = snapshot.files.filter { it.languageId == "java" }.map { file ->
            val compatibilityOwner = snapshot.modules
                .filter { module -> module.sourceRoots.any { file.path.normalize().startsWith(it.normalize()) } }
                .maxByOrNull { module -> module.root.nameCount }
            compatibilityOwner?.languageSettings?.get("java.sourceLevel.refusalCode")?.let { code ->
                return JdtAuthoritativeJavaAnalysisResolution.Refused(
                    code,
                    compatibilityOwner.languageSettings["java.sourceLevel.message"]
                        ?: "The declared Java source/API level is unavailable or unsupported",
                )
            }
            val owner = findBuildOwner(snapshot, file.path)
                ?: return JdtAuthoritativeJavaAnalysisResolution.Refused(
                    "java.diagnostics.sourceSetUnavailable",
                    "Java source has no unique authoritative build source-set owner",
                )
            val sourceSet = snapshot.owningJavaBuildSourceRoots(file.path).singleOrNull()?.sourceSet
            if (sourceSet == null) return JdtAuthoritativeJavaAnalysisResolution.Refused(
                "java.platform.explicitJdkRequired",
                "Java source set has no effective source level; an explicit qualified JDK platform is required",
            )
            when (val decision = JavaProjectSourceLevelPolicy.resolve(
                sourceSet.attributes["java.release"], sourceSet.attributes["java.sourceLevel"],
            )) {
                is JavaProjectSourceLevelDecision.Supported -> Unit
                is JavaProjectSourceLevelDecision.Refused -> return JdtAuthoritativeJavaAnalysisResolution.Refused(decision.code, decision.message)
            }
            val profile = try { JavaSourceSetPlatformContext.profile(sourceSet, platformAuthorities) }
                catch (failure: JavaContextAuthorityException) {
                    return JdtAuthoritativeJavaAnalysisResolution.Refused(failure.code, failure.message.orEmpty())
                }
            if (platformAuthorities[profile] == null) return JdtAuthoritativeJavaAnalysisResolution.Refused(
                "java.platform.authorityUnavailable", "No Java $profile API platform authority was configured",
            )
            profile
        }.toSet()
        val rebound = requiredReleases.associateWith { release ->
            val configured = platformAuthorities[release]
                ?: return JdtAuthoritativeJavaAnalysisResolution.Refused(
                    "java.platform.authorityUnavailable",
                    "No Java $release platform authority was configured",
                )
            val current = JavaReleasePlatformAuthorityResolver().revalidate(configured)
            val authority = (current as? JavaReleasePlatformResolution.Available)?.authority
                ?: return JdtAuthoritativeJavaAnalysisResolution.Refused(
                    (current as JavaReleasePlatformResolution.Refused).code,
                    current.message,
                )
            if (authority.identitySha256 != configured.identitySha256) return JdtAuthoritativeJavaAnalysisResolution.Refused(
                "java.platform.evidenceDrift",
                "Java $release platform evidence changed after configuration",
            )
            authority
        }
        val projections = try {
            rebound.mapValues { JavaReleasePlatformClasspath.materialize(it.value) }
        } catch (failure: Exception) {
            return JdtAuthoritativeJavaAnalysisResolution.Refused(
                "java.platform.classpathProjectionFailed",
                "Attested Java platform signatures could not be projected within safety limits: ${failure.message}",
            )
        }
        val result = try {
            analyzeInternal(snapshot, cancellation, additionalClasspathEntries, rebound, projections)
        } catch (failure: JavaContextAuthorityException) {
            return JdtAuthoritativeJavaAnalysisResolution.Refused(failure.code, failure.message.orEmpty())
        } catch (failure: JdtJpmsAnalysisException) {
            return JdtAuthoritativeJavaAnalysisResolution.Refused(failure.code, failure.message.orEmpty())
        } finally {
            projections.values.forEach(JavaReleasePlatformClasspath::close)
        }
        rebound.forEach { (release, configured) ->
            val after = JavaReleasePlatformAuthorityResolver().revalidate(configured)
            if ((after as? JavaReleasePlatformResolution.Available)?.authority?.identitySha256 != configured.identitySha256) {
                return JdtAuthoritativeJavaAnalysisResolution.Refused(
                    "java.platform.evidenceDrift",
                    "Java $release platform evidence changed during analysis",
                )
            }
        }
        return JdtAuthoritativeJavaAnalysisResolution.Available(result, rebound.mapValues { it.value.identitySha256 })
    }

    private fun analyzeInternal(
        snapshot: ProjectSnapshot,
        cancellation: SemanticCancellationToken,
        additionalClasspathEntries: List<Path>,
        platformAuthorities: Map<Int, JavaReleasePlatformAuthority>,
        platformClasspaths: Map<Int, JavaReleasePlatformClasspath>,
    ): JdtJavaSemanticAnalysisResult = JdtClasspathResourceScope().use { classpathResources ->
        val buildEnvironments = mutableMapOf<String, JavaBuildSourceSetContext>()
        val fileAnalyses = snapshot.files
            .filter { it.languageId == "java" }
            .map { file ->
                if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
                val owner = snapshot.modules
                    .filter { module -> module.sourceRoots.any { file.path.normalize().startsWith(it.normalize()) } }
                    .maxByOrNull { module -> module.root.nameCount }
                owner?.languageSettings?.get("java.sourceLevel.refusalCode")?.let { code ->
                    throw JavaContextAuthorityException(
                        code,
                        owner.languageSettings["java.sourceLevel.message"]
                            ?: "The declared Java source/API level is unavailable or unsupported",
                    )
                }
                val buildOwner = findBuildOwner(snapshot, file.path)
                    ?: owner?.let { findCompatibilityBuildOwner(snapshot.buildModels, it) }
                val buildEnvironment = buildOwner?.let { (model, buildModule) ->
                    val sourceSet = snapshot.owningJavaBuildSourceRoots(file.path).singleOrNull()?.sourceSet
                        ?: selectedBuildSourceSet(file, buildModule)
                    val key = JavaEffectiveProjectContextProvider.key(model.providerId, buildModule.id, sourceSet.id)
                    buildEnvironments.getOrPut(key) {
                        buildSemanticEnvironment(snapshot, model, buildModule, sourceSet, platformAuthorities)
                    }
                }
                val isTestSource = buildEnvironment?.testSource ?: owner?.let { module ->
                    (module.testSourceRoots + module.generatedTestSourceRoots).any {
                        file.path.normalize().startsWith(it.normalize())
                    }
                } == true
                val visibleModules = owner?.let { visibleModules(it, snapshot.modules, isTestSource) } ?: snapshot.modules
                val sourceRootPaths = buildEnvironment?.sourceRoots ?: buildList {
                    if (owner != null) {
                        addAll(owner.mainSourceRoots)
                        addAll(owner.generatedSourceRoots)
                        if (isTestSource) {
                            addAll(owner.testSourceRoots)
                            addAll(owner.generatedTestSourceRoots)
                        }
                    }
                    visibleModules.filter { it != owner }.forEach { dependency ->
                        addAll(dependency.mainSourceRoots)
                        addAll(dependency.generatedSourceRoots)
                    }
                }.ifEmpty { visibleModules.flatMap(Module::sourceRoots) }
                val classpathPaths = ((buildEnvironment?.classpathEntries ?: buildList {
                    if (owner != null) addAll(if (isTestSource) owner.testClasspathEntries else owner.mainClasspathEntries)
                    visibleModules.filter { it != owner }.forEach {
                        addAll(if (isTestSource) it.mainRuntimeClasspathEntries else it.mainClasspathEntries)
                    }
                }.ifEmpty { visibleModules.flatMap(Module::classpathEntries) }) + additionalClasspathEntries).distinct()
                val sourceRoots = sourceRootPaths
                    .map { snapshot.workspace.root.resolve(it).toAbsolutePath().normalize().toString() }
                    .filter { Files.isDirectory(Path.of(it)) }
                    .distinct().toTypedArray()
                val classpathEntries = classpathPaths
                    .map { snapshot.workspace.root.resolve(it).toAbsolutePath().normalize().toString() }
                    .filter { Files.exists(Path.of(it)) }
                    .distinct().toTypedArray()
                val sourceLevel = buildEnvironment?.sourceLevel ?: owner?.let { module ->
                    requireSupportedJavaProjectSourceLevel(
                        module.languageSettings["java.release"], module.languageSettings["java.sourceLevel"],
                    )
                } ?: JavaProjectSourceLevelPolicy.DEFAULT_SOURCE_LEVEL
                val apiProfile = if (platformAuthorities.isEmpty()) sourceLevel else {
                    val sourceSet = snapshot.owningJavaBuildSourceRoots(file.path).singleOrNull()?.sourceSet
                        ?: throw JavaContextAuthorityException("java.diagnostics.sourceSetUnavailable", "No unique API profile owner")
                    JavaSourceSetPlatformContext.profile(sourceSet, platformAuthorities)
                }
                analyzeFileWithReferences(
                    file, sourceRoots, classpathEntries, sourceLevel,
                    platformAuthorities[apiProfile], platformClasspaths[apiProfile], classpathResources, cancellation,
                    buildEnvironment?.jpms ?: JavaJpmsSourceSetContext.NOT_APPLICABLE,
                    buildEnvironment?.compilerOptions.orEmpty(),
                )
            }
        if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
        val symbols = fileAnalyses.flatMap { it.symbols }
        val symbolsByKey = symbols.mapNotNull { symbol -> symbol.bindingKey?.let { it to symbol } }.toMap()
        val symbolsByQualifiedName = symbols.groupBy { it.qualifiedName }
        val references = fileAnalyses.flatMap { analysis ->
            analysis.rawReferences.mapNotNull { raw ->
                val target = raw.bindingKey?.let(symbolsByKey::get)
                    ?: raw.symbolQualifiedName?.let { symbolsByQualifiedName[it]?.singleOrNull() }
                    ?: return@mapNotNull null
                // Independent JDT parser environments can assign different source-binding keys,
                // notably on Windows. Qualified identity selects the exact overload; references
                // then carry the canonical declaration key consumed by planners.
                val canonicalBindingKey = target.bindingKey ?: return@mapNotNull null
                JdtJavaSemanticReference(
                    simpleName = raw.simpleName,
                    symbolQualifiedName = target.qualifiedName,
                    symbolKind = target.kind,
                    symbolSignature = target.memberSignature,
                    path = raw.path,
                    line = raw.line,
                    sourceRange = raw.sourceRange,
                    bindingKey = canonicalBindingKey,
                    evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                    recovered = raw.recovered,
                    jvmIdentity = target.jvmIdentity,
                )
            }
        }
        val bindingUses = fileAnalyses.flatMap { analysis ->
            analysis.rawReferences.mapNotNull { raw ->
                val bindingKey = raw.bindingKey ?: return@mapNotNull null
                JdtJavaSemanticBindingUse(
                    simpleName = raw.simpleName,
                    path = raw.path,
                    line = raw.line,
                    sourceRange = raw.sourceRange,
                    bindingKey = bindingKey,
                    symbolQualifiedName = raw.symbolQualifiedName,
                    isImport = raw.isImport,
                    evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                    recovered = raw.recovered,
                    jvmIdentity = raw.jvmIdentity,
                    typeBindingKey = raw.typeBindingKey,
                    typeQualifiedName = raw.typeQualifiedName,
                    isKotlinBinaryOwner = raw.isKotlinBinaryOwner,
                )
            }
        }
        val warnings = fileAnalyses.flatMap { it.warnings }
        val parameters = fileAnalyses.flatMap { it.parameters }
        val methods = fileAnalyses.flatMap { it.methods }
        val invocations = fileAnalyses.flatMap { it.invocations }
        val methodReferences = fileAnalyses.flatMap { it.methodReferences }
        val methodReferenceUses = fileAnalyses.flatMap { it.methodReferenceUses }
        val overrideRelations = buildOverrideRelations(
            fileAnalyses.flatMap { it.methodBindings },
            fileAnalyses.flatMap { it.inheritances },
        )
        if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
        return JdtJavaSemanticAnalysisResult(
            symbols = symbols,
            references = references,
            warnings = warnings,
            overrideRelations = overrideRelations,
            bindingUses = bindingUses,
            parameters = parameters,
            methods = methods,
            invocations = invocations,
            methodReferences = methodReferences,
            methodReferenceUses = methodReferenceUses,
            entryPointSources = fileAnalyses.mapNotNull { it.entryPointSource },
            typeInheritance = fileAnalyses.flatMap { it.inheritances }.map { relation ->
                JdtJavaSemanticTypeInheritance(
                    relation.subtypeQualifiedName,
                    relation.supertypeQualifiedName,
                    relation.subtypeBindingKey,
                    relation.supertypeBindingKey,
                    JdtJavaSemanticEvidence.JDT_BINDING,
                )
            },
            snapshotHash = snapshot.hash,
        )
    }

    private fun findBuildOwner(snapshot: ProjectSnapshot, path: Path): Pair<BuildModel, BuildModule>? {
        val ownership = snapshot.owningJavaBuildSourceRoots(path).singleOrNull() ?: return null
        val model = snapshot.buildModels.singleOrNull { it.providerId == ownership.providerId } ?: return null
        return model to ownership.module
    }

    private fun findCompatibilityBuildOwner(buildModels: List<BuildModel>, owner: Module): Pair<BuildModel, BuildModule>? =
        buildModels.asSequence().mapNotNull { model ->
            model.modules.firstOrNull { it.id == owner.name && it.root.normalize() == owner.root.normalize() }
                ?.let { model to it }
        }.firstOrNull()

    private fun buildSemanticEnvironment(
        snapshot: ProjectSnapshot,
        model: BuildModel,
        owner: BuildModule,
        selectedSet: BuildSourceSet,
        platformAuthorities: Map<Int, JavaReleasePlatformAuthority>,
    ): JavaBuildSourceSetContext {
        val environment = JavaBuildSourceSetContextResolver.resolve(model, owner, selectedSet)
        val selectedSourceRoots = selectedSet.sourceRoots
        val hasModuleDescriptor = snapshot.files.any { source ->
            source.languageId == "java" && source.path.fileName?.toString() == "module-info.java" &&
                selectedSourceRoots.any { snapshot.workspace.root.resolve(source.path).startsWith(snapshot.workspace.root.resolve(it)) }
        }
        val modulePathOption = Regex("(?:^|\\s)(?:--module-path|-p)(?:=|\\s|$)")
        val hasModulePathOption = selectedSet.compilerOptions.any { option -> modulePathOption.containsMatchIn(option.value) }
        if (!hasModuleDescriptor && !hasModulePathOption) return environment
        val authority = platformAuthorities[JavaSourceSetPlatformContext.profile(selectedSet, platformAuthorities)]
            ?: throw JdtJpmsAnalysisException(
                "java.context.sdkRequired",
                "JPMS semantic analysis requires configured SDK authority for source set ${selectedSet.id}",
            )
        val jpms = JavaJpmsProjectContextResolver.resolve(
            snapshot,
            owner.root,
            selectedSet,
            selectedSet.compilerOptions,
            selectedSet.compilerOptionsAvailability,
            selectedSourceRoots,
            authority.platformHome,
        )
        return environment.copy(jpms = jpms)
    }

    private fun selectedBuildSourceSet(file: SourceFile, owner: BuildModule): BuildSourceSet = owner.sourceSets
        .filter { sourceSet -> sourceSet.sourceRoots.any { file.path.normalize().startsWith(it.normalize()) } }
        .maxByOrNull { sourceSet -> sourceSet.sourceRoots.maxOfOrNull { it.nameCount } ?: 0 }
        ?: owner.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }
        ?: BuildSourceSet("compatibility-main", SourceSetKind.MAIN)

    private fun visibleModules(owner: Module, modules: List<Module>, testSource: Boolean): List<Module> {
        val byName = modules.associateBy(Module::name)
        val visible = linkedMapOf(owner.name to owner)
        val pending = ArrayDeque(if (testSource) owner.testDependencies else owner.mainDependencies)
        while (pending.isNotEmpty()) {
            val dependency = pending.removeFirst()
            if (dependency in visible) continue
            val module = byName[dependency] ?: continue
            visible[dependency] = module
            pending.addAll(module.mainDependencies)
        }
        return visible.values.toList()
    }

    fun analyzeFile(file: SourceFile, sourceLevel: Int = 25): List<JdtJavaSemanticSymbol> =
        JdtClasspathResourceScope().use { classpathResources ->
            analyzeFileWithReferences(
                file,
                emptyArray(),
                emptyArray(),
                requireSupportedJavaProjectSourceLevel(null, sourceLevel.toString()),
                null,
                null,
                classpathResources,
                SemanticCancellationToken.NONE,
            ).symbols
        }

    private fun analyzeFileWithReferences(
        file: SourceFile,
        sourceRoots: Array<String>,
        classpathEntries: Array<String>,
        sourceLevel: Int,
        platformAuthority: JavaReleasePlatformAuthority?,
        platformClasspath: JavaReleasePlatformClasspath?,
        classpathResources: JdtClasspathResourceScope,
        cancellation: SemanticCancellationToken,
        jpmsContext: JavaJpmsSourceSetContext = JavaJpmsSourceSetContext.NOT_APPLICABLE,
        compilerOptions: List<org.refactorkit.core.BuildCompilerOption> = emptyList(),
    ): FileAnalysis {
        if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
        if (file.languageId != "java") return FileAnalysis(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val compilationUnit = parse(
            file,
            sourceRoots,
            classpathEntries,
            sourceLevel,
            platformAuthority,
            platformClasspath,
            classpathResources,
            jpmsContext = jpmsContext,
            compilerOptions = compilerOptions,
            cancellation = cancellation,
        )
        val packageName = compilationUnit.`package`?.name?.fullyQualifiedName ?: JavaPackageUtil.extractPackage(file.content)
        val symbols = mutableListOf<JdtJavaSemanticSymbol>()
        val rawReferences = mutableListOf<RawReference>()
        val methodBindings = mutableListOf<MethodBindingRecord>()
        val inheritances = mutableListOf<TypeInheritanceRecord>()
        val ownerStack = mutableListOf<String>()
        val parameters = mutableListOf<JdtJavaSemanticParameter>()
        val methods = mutableListOf<JdtJavaSemanticMethod>()
        val invocations = mutableListOf<JdtJavaSemanticInvocation>()
        val methodReferences = mutableListOf<JdtJavaSemanticMethodReference>()
        val methodReferenceUses = mutableListOf<JdtJavaSemanticMethodReferenceUse>()
        val entryPointMethods = mutableListOf<JavaNativeMainMethod>()
        val entryPointTypes = mutableListOf<JavaNativeEntryPointType>()
        var entryPointSyntaxComplete = true
        var entryPointVisitedNodes = 0
        val kotlinBinaryOwners = mutableMapOf<ITypeBinding, Boolean>()

        fun isKotlinBinaryOwner(binding: IBinding?): Boolean {
            if (binding == null || binding.isRecovered) return false
            val owner = when (binding) {
                is ITypeBinding -> binding.typeDeclaration
                is IMethodBinding -> binding.methodDeclaration.declaringClass?.typeDeclaration
                is IVariableBinding -> binding.variableDeclaration.declaringClass?.typeDeclaration
                else -> null
            } ?: return false
            if (owner.isFromSource || owner.isRecovered) return false
            return kotlinBinaryOwners.getOrPut(owner) {
                owner.annotations.any { annotation ->
                    val type = annotation.annotationType
                    type != null && !type.isRecovered && type.qualifiedName == "kotlin.Metadata"
                }
            }
        }

        compilationUnit.accept(object : ASTVisitor() {
            override fun preVisit(node: ASTNode) {
                if (++entryPointVisitedNodes % 256 == 0 && cancellation.isCancellationRequested())
                    throw JdtJavaAnalysisCancelledException()
                if (node.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) entryPointSyntaxComplete = false
                if (entryPointTypes.size <= 16_384) {
                    val type = when (node) {
                        is TypeDeclaration -> JavaEntryPointNativeProjection.type(file, node, node.resolveBinding())
                        is EnumDeclaration -> JavaEntryPointNativeProjection.type(file, node, node.resolveBinding())
                        is RecordDeclaration -> JavaEntryPointNativeProjection.type(file, node, node.resolveBinding())
                        is AnnotationTypeDeclaration -> JavaEntryPointNativeProjection.type(file, node, node.resolveBinding())
                        is AnonymousClassDeclaration -> JavaEntryPointNativeProjection.type(file, node, node.resolveBinding())
                        else -> null
                    }
                    if (type != null) entryPointTypes += type
                }
            }

            override fun visit(node: TypeDeclaration): Boolean {
                val binding = node.resolveBinding()
                val typeSymbol = symbol(
                    file = file,
                    compilationUnit = compilationUnit,
                    packageName = packageName,
                    ownerQualifiedName = ownerStack.lastOrNull(),
                    simpleName = node.name.identifier,
                    kind = if (node.isInterface) JdtJavaSemanticSymbolKind.INTERFACE else JdtJavaSemanticSymbolKind.CLASS,
                    startPosition = node.name.startPosition,
                    bindingQualifiedName = binding?.qualifiedName,
                    bindingKey = binding?.key,
                    bindingRecovered = binding?.isRecovered == true,
                    memberSignature = null,
                    jvmIdentity = jvmIdentity(binding),
                    documentation = node.javadoc?.toString(),
                    isAbstract = binding?.let { Modifier.isAbstract(it.modifiers) },
                )
                symbols += typeSymbol
                inheritances += typeInheritanceRecords(node, packageName, typeSymbol.qualifiedName, typeSymbol.bindingKey)
                ownerStack += typeSymbol.qualifiedName
                return true
            }

            override fun endVisit(node: TypeDeclaration) {
                if (ownerStack.isNotEmpty()) ownerStack.removeAt(ownerStack.lastIndex)
            }

            override fun visit(node: AnnotationTypeDeclaration): Boolean {
                val binding = node.resolveBinding()
                val annotationSymbol = symbol(
                    file = file,
                    compilationUnit = compilationUnit,
                    packageName = packageName,
                    ownerQualifiedName = ownerStack.lastOrNull(),
                    simpleName = node.name.identifier,
                    kind = JdtJavaSemanticSymbolKind.ANNOTATION,
                    startPosition = node.name.startPosition,
                    bindingQualifiedName = binding?.qualifiedName,
                    bindingKey = binding?.key,
                    bindingRecovered = binding?.isRecovered == true,
                    memberSignature = null,
                    jvmIdentity = jvmIdentity(binding),
                    documentation = node.javadoc?.toString(),
                    isAbstract = binding?.let { Modifier.isAbstract(it.modifiers) },
                )
                symbols += annotationSymbol
                ownerStack += annotationSymbol.qualifiedName
                return true
            }

            override fun endVisit(node: AnnotationTypeDeclaration) {
                if (ownerStack.isNotEmpty()) ownerStack.removeAt(ownerStack.lastIndex)
            }

            override fun visit(node: EnumDeclaration): Boolean {
                val binding = node.resolveBinding()
                val enumSymbol = symbol(
                    file = file,
                    compilationUnit = compilationUnit,
                    packageName = packageName,
                    ownerQualifiedName = ownerStack.lastOrNull(),
                    simpleName = node.name.identifier,
                    kind = JdtJavaSemanticSymbolKind.ENUM,
                    startPosition = node.name.startPosition,
                    bindingQualifiedName = binding?.qualifiedName,
                    bindingKey = binding?.key,
                    bindingRecovered = binding?.isRecovered == true,
                    memberSignature = null,
                    jvmIdentity = jvmIdentity(binding),
                    documentation = node.javadoc?.toString(),
                    isAbstract = binding?.let { Modifier.isAbstract(it.modifiers) },
                )
                symbols += enumSymbol
                inheritances += enumInheritanceRecords(node, packageName, enumSymbol.qualifiedName, enumSymbol.bindingKey)
                ownerStack += enumSymbol.qualifiedName
                return true
            }

            override fun endVisit(node: EnumDeclaration) {
                if (ownerStack.isNotEmpty()) ownerStack.removeAt(ownerStack.lastIndex)
            }

            override fun visit(node: RecordDeclaration): Boolean {
                val binding = node.resolveBinding()
                val recordSymbol = symbol(
                    file = file,
                    compilationUnit = compilationUnit,
                    packageName = packageName,
                    ownerQualifiedName = ownerStack.lastOrNull(),
                    simpleName = node.name.identifier,
                    kind = JdtJavaSemanticSymbolKind.RECORD,
                    startPosition = node.name.startPosition,
                    bindingQualifiedName = binding?.qualifiedName,
                    bindingKey = binding?.key,
                    bindingRecovered = binding?.isRecovered == true,
                    memberSignature = null,
                    jvmIdentity = jvmIdentity(binding),
                    documentation = node.javadoc?.toString(),
                    isAbstract = binding?.let { Modifier.isAbstract(it.modifiers) },
                )
                symbols += recordSymbol
                inheritances += recordInheritanceRecords(node, packageName, recordSymbol.qualifiedName, recordSymbol.bindingKey)
                node.recordComponents().filterIsInstance<SingleVariableDeclaration>().forEachIndexed { index, component ->
                    val componentBinding = component.resolveBinding()?.variableDeclaration
                    val componentName = component.name.identifier
                    symbols += symbol(
                        file = file,
                        compilationUnit = compilationUnit,
                        packageName = packageName,
                        ownerQualifiedName = recordSymbol.qualifiedName,
                        simpleName = componentName,
                        kind = JdtJavaSemanticSymbolKind.RECORD_COMPONENT,
                        startPosition = component.name.startPosition,
                        bindingQualifiedName = bindingQualifiedName(componentBinding),
                        bindingKey = componentBinding?.key,
                        bindingRecovered = componentBinding?.isRecovered == true,
                        memberSignature = null,
                        jvmIdentity = jvmIdentity(componentBinding),
                        recordComponentIndex = index,
                    )
                    val generatedAccessor = binding?.declaredMethods?.singleOrNull { method ->
                        method.name == componentName && method.parameterTypes.isEmpty() &&
                            !method.isConstructor
                    }
                    if (generatedAccessor != null) {
                        symbols += symbol(
                            file = file,
                            compilationUnit = compilationUnit,
                            packageName = packageName,
                            ownerQualifiedName = recordSymbol.qualifiedName,
                            simpleName = componentName,
                            kind = JdtJavaSemanticSymbolKind.METHOD,
                            startPosition = component.name.startPosition,
                            bindingQualifiedName = generatedAccessor.qualifiedName(),
                            bindingKey = generatedAccessor.methodDeclaration.key,
                            bindingRecovered = generatedAccessor.isRecovered,
                            memberSignature = "$componentName()",
                            hoverSignature = "generated accessor $componentName()",
                            jvmIdentity = jvmIdentity(generatedAccessor),
                        )
                    }
                }
                ownerStack += recordSymbol.qualifiedName
                return true
            }

            override fun endVisit(node: RecordDeclaration) {
                if (ownerStack.isNotEmpty()) ownerStack.removeAt(ownerStack.lastIndex)
            }

            override fun visit(node: AnnotationTypeMemberDeclaration): Boolean {
                val owner = ownerStack.lastOrNull()
                    ?: JavaPackageUtil.fqn(packageName, file.path.fileName.toString().removeSuffix(".java"))
                val binding = node.resolveBinding()
                val signature = "${node.name.identifier}()"
                val memberSymbol = symbol(
                    file = file,
                    compilationUnit = compilationUnit,
                    packageName = packageName,
                    ownerQualifiedName = owner,
                    simpleName = node.name.identifier,
                    kind = JdtJavaSemanticSymbolKind.METHOD,
                    startPosition = node.name.startPosition,
                    bindingQualifiedName = null,
                    bindingKey = binding?.key,
                    bindingRecovered = binding?.isRecovered == true,
                    memberSignature = signature,
                    jvmIdentity = jvmIdentity(binding),
                    hoverSignature = "${node.type} ${node.name.identifier}()",
                    documentation = node.javadoc?.toString(),
                    isAbstract = binding?.let { Modifier.isAbstract(it.modifiers) },
                )
                symbols += memberSymbol
                if (binding != null) methodBindings += MethodBindingRecord(memberSymbol, binding)
                return true
            }

            override fun visit(node: MethodDeclaration): Boolean {
                val owner = ownerStack.lastOrNull() ?: JavaPackageUtil.fqn(packageName, file.path.fileName.toString().removeSuffix(".java"))
                val binding = node.resolveBinding()
                if (!node.isConstructor && node.name.identifier == "main")
                    entryPointMethods += JavaEntryPointNativeProjection.method(file, node, binding)
                val signature = methodSignature(node, binding)
                val methodSymbol = symbol(
                    file = file,
                    compilationUnit = compilationUnit,
                    packageName = packageName,
                    ownerQualifiedName = owner,
                    simpleName = node.name.identifier,
                    kind = if (node.isConstructor) JdtJavaSemanticSymbolKind.CONSTRUCTOR else JdtJavaSemanticSymbolKind.METHOD,
                    startPosition = node.name.startPosition,
                    bindingQualifiedName = null,
                    bindingKey = binding?.key,
                    bindingRecovered = binding?.isRecovered == true,
                    memberSignature = signature,
                    jvmIdentity = jvmIdentity(binding),
                    hoverSignature = methodHoverSignature(node, binding),
                    documentation = node.javadoc?.toString(),
                    isAbstract = binding?.let { Modifier.isAbstract(it.modifiers) },
                )
                val accessorPlaceholder = symbols.indexOfFirst { existing ->
                    existing.kind == JdtJavaSemanticSymbolKind.METHOD &&
                        existing.bindingKey == methodSymbol.bindingKey &&
                        existing.path == methodSymbol.path &&
                        existing.ownerQualifiedName == methodSymbol.ownerQualifiedName
                }
                if (accessorPlaceholder >= 0) symbols[accessorPlaceholder] = methodSymbol else symbols += methodSymbol
                if (binding != null && !node.isConstructor) {
                    methodBindings += MethodBindingRecord(methodSymbol, binding)
                }
                if (binding != null) {
                    val bounds = parenthesisInterior(file.content, node.name.startPosition + node.name.length)
                        ?: return true
                    methods += JdtJavaSemanticMethod(
                        qualifiedName = methodSymbol.qualifiedName,
                        signature = signature,
                        bindingKey = binding.methodDeclaration.key,
                        path = file.path,
                        parameterListRange = rangeFor(compilationUnit, bounds.first, bounds.last - bounds.first + 1),
                        constructor = node.isConstructor,
                        modifiers = node.modifiers,
                        evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                        canonicalConstructor = binding.isCanonicalConstructor,
                        declarationRange = rangeFor(compilationUnit, node.startPosition, node.length),
                        staticModifierInsertionOffset = privateModifierEndOffset(node),
                        makeStaticRefusal = makeStaticRefusal(node),
                    )
                }
                node.parameters().filterIsInstance<SingleVariableDeclaration>().forEachIndexed { index, parameter ->
                    val parameterBinding = parameter.resolveBinding()?.variableDeclaration ?: return@forEachIndexed
                    val identifier = parameter.name
                    parameters += JdtJavaSemanticParameter(
                        methodQualifiedName = methodSymbol.qualifiedName,
                        methodSignature = methodSymbol.memberSignature ?: return@forEachIndexed,
                        methodBindingKey = binding?.methodDeclaration?.key ?: return@forEachIndexed,
                        parameterBindingKey = parameterBinding.key,
                        name = identifier.identifier,
                        index = index,
                        path = file.path,
                        declarationRange = rangeFor(compilationUnit, parameter.startPosition, parameter.length),
                        typeRange = rangeFor(compilationUnit, parameter.type.startPosition, parameter.type.length),
                        sourceRange = rangeFor(compilationUnit, identifier.startPosition, identifier.length),
                        evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                    )
                }
                return true
            }

            override fun visit(node: MethodInvocation): Boolean {
                val binding = node.resolveMethodBinding()?.methodDeclaration ?: return true
                val callerBinding = enclosingMethodBinding(node)
                val bounds = parenthesisInterior(file.content, node.name.startPosition + node.name.length)
                    ?: return true
                val arguments = node.arguments().filterIsInstance<Expression>()
                invocations += JdtJavaSemanticInvocation(
                    methodQualifiedName = binding.qualifiedName(),
                    methodBindingKey = binding.key,
                    callerMethodQualifiedName = callerBinding?.qualifiedName(),
                    methodJvmIdentity = jvmIdentity(binding),
                    callerMethodBindingKey = callerBinding?.key,
                    callerMethodJvmIdentity = callerBinding?.let(::jvmIdentity),
                    methodBindingRecovered = binding.isRecovered,
                    callerBindingRecovered = callerBinding?.isRecovered == true,
                    path = file.path,
                    nameRange = rangeFor(compilationUnit, node.name.startPosition, node.name.length),
                    argumentListRange = rangeFor(compilationUnit, bounds.first, bounds.last - bounds.first + 1),
                    argumentRanges = arguments.map {
                        rangeFor(compilationUnit, it.startPosition, it.length)
                    },
                    argumentLiteralFlags = arguments.map(::isSideEffectFreeJavaLiteral),
                    evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                    receiverKind = if (node.expression == null) JdtJavaInvocationReceiverKind.IMPLICIT else JdtJavaInvocationReceiverKind.EXPLICIT,
                )
                return true
            }

            override fun visit(node: SuperMethodInvocation): Boolean {
                val binding = node.resolveMethodBinding()?.methodDeclaration ?: return true
                val callerBinding = enclosingMethodBinding(node)
                val bounds = parenthesisInterior(file.content, node.name.startPosition + node.name.length)
                    ?: return true
                val arguments = node.arguments().filterIsInstance<Expression>()
                invocations += JdtJavaSemanticInvocation(
                    methodQualifiedName = binding.qualifiedName(),
                    methodBindingKey = binding.key,
                    callerMethodQualifiedName = callerBinding?.qualifiedName(),
                    methodJvmIdentity = jvmIdentity(binding),
                    callerMethodBindingKey = callerBinding?.key,
                    callerMethodJvmIdentity = callerBinding?.let(::jvmIdentity),
                    methodBindingRecovered = binding.isRecovered,
                    callerBindingRecovered = callerBinding?.isRecovered == true,
                    path = file.path,
                    nameRange = rangeFor(compilationUnit, node.name.startPosition, node.name.length),
                    argumentListRange = rangeFor(compilationUnit, bounds.first, bounds.last - bounds.first + 1),
                    argumentRanges = arguments.map {
                        rangeFor(compilationUnit, it.startPosition, it.length)
                    },
                    argumentLiteralFlags = arguments.map(::isSideEffectFreeJavaLiteral),
                    evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                    receiverKind = JdtJavaInvocationReceiverKind.SUPER,
                )
                return true
            }

            override fun visit(node: TypeMethodReference): Boolean {
                val binding = node.resolveMethodBinding()?.methodDeclaration ?: return true
                methodReferenceUses += semanticMethodReferenceUse(compilationUnit, file, binding, node.startPosition, node.length)
                if (Modifier.isStatic(binding.modifiers)) methodReferences += semanticMethodReference(
                    compilationUnit, file, binding, node.startPosition, node.length, JdtJavaSemanticMethodReferenceKind.STATIC_METHOD,
                )
                return true
            }

            override fun visit(node: ExpressionMethodReference): Boolean {
                val binding = node.resolveMethodBinding()?.methodDeclaration ?: return true
                methodReferenceUses += semanticMethodReferenceUse(compilationUnit, file, binding, node.startPosition, node.length)
                val kind = when {
                    Modifier.isStatic(binding.modifiers) -> JdtJavaSemanticMethodReferenceKind.STATIC_METHOD
                    isPrivateThisMethodReference(node, binding) -> JdtJavaSemanticMethodReferenceKind.PRIVATE_THIS_METHOD
                    else -> return true
                }
                methodReferences += semanticMethodReference(compilationUnit, file, binding, node.startPosition, node.length, kind)
                return true
            }

            override fun visit(node: SuperMethodReference): Boolean {
                val binding = node.resolveMethodBinding()?.methodDeclaration ?: return true
                methodReferenceUses += semanticMethodReferenceUse(compilationUnit, file, binding, node.startPosition, node.length)
                return true
            }

            override fun visit(node: CreationReference): Boolean {
                val binding = node.resolveMethodBinding()?.methodDeclaration ?: return true
                methodReferences += semanticMethodReference(compilationUnit, file, binding, node.startPosition, node.length, JdtJavaSemanticMethodReferenceKind.CONSTRUCTOR)
                methodReferenceUses += semanticMethodReferenceUse(compilationUnit, file, binding, node.startPosition, node.length)
                return true
            }

            override fun visit(node: FieldDeclaration): Boolean {
                val owner = ownerStack.lastOrNull() ?: JavaPackageUtil.fqn(packageName, file.path.fileName.toString().removeSuffix(".java"))
                node.fragments().filterIsInstance<VariableDeclarationFragment>().forEach { fragment ->
                    val binding = fragment.resolveBinding()
                    symbols += symbol(
                        file = file,
                        compilationUnit = compilationUnit,
                        packageName = packageName,
                        ownerQualifiedName = owner,
                        simpleName = fragment.name.identifier,
                        kind = JdtJavaSemanticSymbolKind.FIELD,
                        startPosition = fragment.name.startPosition,
                        bindingQualifiedName = null,
                        bindingKey = binding?.key,
                        bindingRecovered = binding?.isRecovered == true,
                        memberSignature = null,
                        jvmIdentity = jvmIdentity(binding),
                        hoverSignature = "${node.type} ${fragment.name.identifier}",
                        documentation = node.javadoc?.toString(),
                    )
                }
                return true
            }

            override fun visit(node: ImportDeclaration): Boolean {
                val binding = node.resolveBinding()
                val importedName = node.name.fullyQualifiedName
                val simpleName = importedName.substringAfterLast('.')
                val simpleNameStart = node.name.startPosition + node.name.length - simpleName.length
                rawReferences += RawReference(
                    simpleName = simpleName,
                    path = file.path,
                    line = (compilationUnit.getLineNumber(simpleNameStart) - 1).coerceAtLeast(0),
                    sourceRange = rangeFor(compilationUnit, simpleNameStart, simpleName.length),
                    bindingKey = declarationBindingKey(binding),
                    symbolQualifiedName = bindingQualifiedName(binding),
                    isImport = true,
                    recovered = binding?.isRecovered == true,
                    jvmIdentity = jvmIdentity(binding),
                    typeBindingKey = typeDefinitionBinding(binding)?.key,
                    typeQualifiedName = typeDefinitionBinding(binding)?.qualifiedName?.takeIf(String::isNotBlank),
                    isKotlinBinaryOwner = isKotlinBinaryOwner(binding),
                )
                return false
            }

            override fun visit(node: ClassInstanceCreation): Boolean {
                val binding = node.resolveConstructorBinding()?.methodDeclaration ?: return true
                val bounds = parenthesisInterior(file.content, node.type.startPosition + node.type.length)
                if (bounds != null) invocations += constructorInvocation(
                    compilationUnit, file, binding, node.type.startPosition, node.type.length, bounds,
                    node.arguments().filterIsInstance<Expression>(),
                    enclosingMethodBinding(node),
                )
                rawReferences += RawReference(
                    simpleName = binding.declaringClass?.name ?: node.type.toString(),
                    path = file.path,
                    line = (compilationUnit.getLineNumber(node.startPosition) - 1).coerceAtLeast(0),
                    sourceRange = rangeFor(compilationUnit, node.type.startPosition, node.type.length),
                    bindingKey = declarationBindingKey(binding),
                    symbolQualifiedName = bindingQualifiedName(binding),
                    isImport = false,
                    recovered = binding.isRecovered,
                    jvmIdentity = jvmIdentity(binding),
                    typeBindingKey = typeDefinitionBinding(binding)?.key,
                    typeQualifiedName = typeDefinitionBinding(binding)?.qualifiedName?.takeIf(String::isNotBlank),
                    isKotlinBinaryOwner = isKotlinBinaryOwner(binding),
                )
                return true
            }

            override fun visit(node: ConstructorInvocation): Boolean {
                val binding = node.resolveConstructorBinding()?.methodDeclaration ?: return true
                val bounds = parenthesisInterior(file.content, node.startPosition)
                if (bounds != null) invocations += constructorInvocation(
                    compilationUnit, file, binding, node.startPosition, minOf(4, node.length), bounds,
                    node.arguments().filterIsInstance<Expression>(),
                    enclosingMethodBinding(node),
                )
                return true
            }

            override fun visit(node: SuperConstructorInvocation): Boolean {
                val binding = node.resolveConstructorBinding()?.methodDeclaration ?: return true
                val bounds = parenthesisInterior(file.content, node.startPosition)
                if (bounds != null) invocations += constructorInvocation(
                    compilationUnit, file, binding, node.startPosition, minOf(5, node.length), bounds,
                    node.arguments().filterIsInstance<Expression>(),
                    enclosingMethodBinding(node),
                )
                return true
            }

            override fun visit(node: SimpleName): Boolean {
                if (node.isDeclarationName()) return true
                val binding = node.resolveBinding()
                rawReferences += RawReference(
                    simpleName = node.identifier,
                    path = file.path,
                    line = (compilationUnit.getLineNumber(node.startPosition) - 1).coerceAtLeast(0),
                    sourceRange = rangeFor(compilationUnit, node.startPosition, node.length),
                    bindingKey = declarationBindingKey(binding),
                    symbolQualifiedName = bindingQualifiedName(binding),
                    isImport = false,
                    recovered = binding?.isRecovered == true,
                    jvmIdentity = jvmIdentity(binding),
                    typeBindingKey = typeDefinitionBinding(binding)?.key,
                    typeQualifiedName = typeDefinitionBinding(binding)?.qualifiedName?.takeIf(String::isNotBlank),
                    isKotlinBinaryOwner = isKotlinBinaryOwner(binding),
                )
                return true
            }
        })

        val warnings = compilationUnit.problems
            .filter { it.isError }
            .map { problem ->
                val start = problem.sourceStart.coerceAtLeast(0)
                val length = (problem.sourceEnd - problem.sourceStart + 1).coerceAtLeast(1)
                JdtJavaSemanticWarning(
                    path = file.path,
                    line = (problem.sourceLineNumber - 1).coerceAtLeast(0),
                    sourceRange = rangeFor(compilationUnit, start, length),
                    message = problem.message,
                    category = if ((problem.id and IProblem.Syntax) != 0) {
                        JdtJavaDiagnosticCategory.SYNTAX
                    } else {
                        JdtJavaDiagnosticCategory.TYPE_RESOLUTION
                    },
                    problemId = problem.id,
                    evidence = JdtJavaSemanticEvidence.JDT_PARSE,
                )
            }
        return FileAnalysis(symbols, rawReferences, warnings, methodBindings, inheritances, parameters, methods, invocations, methodReferences, methodReferenceUses,
            JavaNativeEntryPointSource(file.path, entryPointSyntaxComplete && warnings.isEmpty(), entryPointMethods, entryPointTypes))
    }

    private fun declarationBindingKey(binding: IBinding?): String? = when (binding) {
        is ITypeBinding -> binding.typeDeclaration.key
        is IMethodBinding -> binding.methodDeclaration.key
        is IVariableBinding -> binding.variableDeclaration.key
        else -> binding?.key
    }

    private fun typeDefinitionBinding(binding: IBinding?): ITypeBinding? {
        val selected = when (binding) {
            is ITypeBinding -> binding
            is IVariableBinding -> binding.type
            is IMethodBinding -> if (binding.isConstructor) binding.declaringClass else binding.returnType
            else -> null
        } ?: return null
        val element = if (selected.isArray) selected.elementType else selected
        if (element.isRecovered || element.isPrimitive || element.isTypeVariable || element.isWildcardType) return null
        return element.typeDeclaration.takeUnless { it.isRecovered || it.key.isNullOrBlank() }
    }

    private fun bindingQualifiedName(binding: IBinding?): String? = when (binding) {
        is ITypeBinding -> binding.typeDeclaration.qualifiedName.takeIf(String::isNotBlank)
        is IMethodBinding -> binding.methodDeclaration.qualifiedName()
        is IVariableBinding -> binding.variableDeclaration.let { variable ->
            variable.declaringClass?.qualifiedName?.takeIf(String::isNotBlank)?.let { "$it#${variable.name}" }
        }
        else -> null
    }

    private fun jvmIdentity(binding: IBinding?): JdtJavaJvmIdentity? = when (binding) {
        is ITypeBinding -> binding.typeDeclaration.jvmBinaryName()?.let { binaryName ->
            JdtJavaJvmIdentity(
                kind = JdtJavaJvmIdentityKind.TYPE,
                ownerBinaryName = binaryName,
                memberName = "",
                descriptor = "L${binaryName.replace('.', '/')};",
            )
        }
        is IMethodBinding -> binding.methodDeclaration.let { method ->
            val owner = method.declaringClass?.typeDeclaration?.jvmBinaryName() ?: return@let null
            val parameters = method.parameterTypes.map { it.jvmDescriptor() ?: return@let null }.joinToString("")
            val result = if (method.isConstructor) "V" else method.returnType?.jvmDescriptor() ?: return@let null
            JdtJavaJvmIdentity(
                kind = JdtJavaJvmIdentityKind.CALLABLE,
                ownerBinaryName = owner,
                memberName = if (method.isConstructor) "<init>" else method.name,
                descriptor = "($parameters)$result",
            )
        }
        is IVariableBinding -> binding.variableDeclaration.let { variable ->
            val owner = variable.declaringClass?.typeDeclaration?.jvmBinaryName() ?: return@let null
            val descriptor = variable.type?.jvmDescriptor() ?: return@let null
            JdtJavaJvmIdentity(JdtJavaJvmIdentityKind.FIELD, owner, variable.name, descriptor)
        }
        else -> null
    }

    private fun ITypeBinding.jvmBinaryName(): String? =
        binaryName?.takeIf(String::isNotBlank) ?: qualifiedName.takeIf(String::isNotBlank)

    private fun ITypeBinding.jvmDescriptor(): String? {
        if (isArray) {
            val element = elementType?.jvmDescriptor() ?: return null
            return "[".repeat(dimensions) + element
        }
        if (isPrimitive) return when (name) {
            "boolean" -> "Z"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            "void" -> "V"
            else -> null
        }
        val erased = erasure ?: this
        val binaryName = erased.jvmBinaryName() ?: return null
        return "L${binaryName.replace('.', '/')};"
    }

    private fun buildOverrideRelations(
        methods: List<MethodBindingRecord>,
        inheritances: List<TypeInheritanceRecord>,
    ): List<JdtJavaSemanticOverrideRelation> {
        val sourceMethodsByQualifiedName = methods.groupBy { it.symbol.qualifiedName }
        val parentsBySubtype = inheritances.groupBy { it.subtypeQualifiedName }
            .mapValues { entry -> entry.value.map { it.supertypeQualifiedName }.toSet() }
        fun inheritsFrom(subtype: String?, supertype: String?): Boolean {
            if (subtype == null || supertype == null || subtype == supertype) return false
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            parentsBySubtype[subtype].orEmpty().forEach(queue::add)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) continue
                if (current == supertype) return true
                parentsBySubtype[current].orEmpty().forEach(queue::add)
            }
            return false
        }
        val seen = mutableSetOf<String>()
        val relations = mutableListOf<JdtJavaSemanticOverrideRelation>()
        for (sub in methods) {
            if (!sub.binding.isOverrideCandidate()) continue
            for (superMethod in methods) {
                if (sub === superMethod || !superMethod.binding.isOverrideCandidate()) continue
                val subKey = sub.symbol.bindingKey ?: continue
                val superKey = superMethod.symbol.bindingKey ?: continue
                if (subKey == superKey) continue
                val overrides = runCatching { sub.binding.overrides(superMethod.binding) }.getOrDefault(false) ||
                    runCatching {
                        val subType = sub.binding.declaringClass
                        val superType = superMethod.binding.declaringClass
                        subType != null && superType != null &&
                            subType.qualifiedName != superType.qualifiedName &&
                            subType.isSubTypeCompatible(superType) &&
                            superMethod.binding.isInheritedBy(subType) &&
                            sub.binding.isSubsignature(superMethod.binding)
                    }.getOrDefault(false) ||
                    (inheritsFrom(sub.symbol.ownerQualifiedName, superMethod.symbol.ownerQualifiedName) &&
                        superMethod.binding.isInheritedBy(sub.binding.declaringClass) &&
                        sub.symbol.memberSignature == superMethod.symbol.memberSignature)
                if (!overrides) continue
                val relationKey = "$subKey->$superKey"
                if (!seen.add(relationKey)) continue
                relations += JdtJavaSemanticOverrideRelation(
                    overridingSymbolQualifiedName = sub.symbol.qualifiedName,
                    overriddenSymbolQualifiedName = superMethod.symbol.qualifiedName,
                    overridingBindingKey = subKey,
                    overriddenBindingKey = superKey,
                    evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                )
            }
        }

        val sourceBindingKeys = methods.mapNotNull { it.symbol.bindingKey }.toSet()
        for (sub in methods) {
            if (!sub.binding.isOverrideCandidate()) continue
            val subKey = sub.symbol.bindingKey ?: continue
            inheritedTypes(sub.binding.declaringClass).forEach { inheritedType ->
                inheritedType.declaredMethods
                    .filter { it.isOverrideCandidate() && it.key !in sourceBindingKeys }
                    .filter { inheritedMethod ->
                        runCatching { sub.binding.overrides(inheritedMethod) }.getOrDefault(false)
                    }
                    .forEach { inheritedMethod ->
                        val inheritedQualifiedName = inheritedMethod.qualifiedName()
                        val sourceDeclaration = sourceMethodsByQualifiedName[inheritedQualifiedName]?.singleOrNull()
                        val inheritedKey = sourceDeclaration?.symbol?.bindingKey
                            ?: inheritedMethod.key
                            ?: return@forEach
                        val relationKey = "$subKey->$inheritedKey"
                        if (!seen.add(relationKey)) return@forEach
                        relations += JdtJavaSemanticOverrideRelation(
                            overridingSymbolQualifiedName = sub.symbol.qualifiedName,
                            overriddenSymbolQualifiedName = sourceDeclaration?.symbol?.qualifiedName ?: inheritedQualifiedName,
                            overridingBindingKey = subKey,
                            overriddenBindingKey = inheritedKey,
                            evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                        )
                    }
            }
        }
        return relations
    }

    private fun inheritedTypes(type: ITypeBinding?): List<ITypeBinding> {
        if (type == null) return emptyList()
        val result = mutableListOf<ITypeBinding>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<ITypeBinding>()
        type.superclass?.let(queue::add)
        type.interfaces.forEach(queue::add)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val key = current.key ?: current.qualifiedName
            if (!visited.add(key)) continue
            result += current
            current.superclass?.let(queue::add)
            current.interfaces.forEach(queue::add)
        }
        return result
    }

    private fun isPrivateThisMethodReference(node: ExpressionMethodReference, binding: IMethodBinding): Boolean {
        if (binding.isRecovered || !Modifier.isPrivate(binding.modifiers) || binding.isVarargs || node.typeArguments().isNotEmpty()) {
            return false
        }
        val receiver = node.expression as? ThisExpression ?: return false
        if (receiver.qualifier != null) return false
        val receiverType = receiver.resolveTypeBinding()?.typeDeclaration ?: return false
        val declaringType = binding.declaringClass?.typeDeclaration ?: return false
        if (receiverType.isRecovered || declaringType.isRecovered || !receiverType.isEqualTo(declaringType)) return false

        // An explicit local target type fixes the SAM independently of overload selection at call sites.
        val local = node.parent as? VariableDeclarationFragment ?: return false
        if (local.initializer !== node || local.parent !is VariableDeclarationStatement) return false
        val targetType = local.resolveBinding()?.type ?: return false
        val sam = targetType.functionalInterfaceMethod ?: return false
        return !targetType.isRecovered && !sam.isRecovered && sam.parameterTypes.size == binding.parameterTypes.size &&
            node.resolveTypeBinding()?.isEqualTo(targetType) == true
    }

    private fun semanticMethodReference(
        compilationUnit: CompilationUnit,
        file: SourceFile,
        binding: IMethodBinding,
        start: Int,
        length: Int,
        kind: JdtJavaSemanticMethodReferenceKind,
    ) = JdtJavaSemanticMethodReference(
        methodQualifiedName = binding.qualifiedName(), methodBindingKey = binding.key,
        path = file.path, sourceRange = rangeFor(compilationUnit, start, length),
        kind = kind, evidence = JdtJavaSemanticEvidence.JDT_BINDING,
    )

    private fun semanticMethodReferenceUse(
        compilationUnit: CompilationUnit,
        file: SourceFile,
        binding: IMethodBinding,
        start: Int,
        length: Int,
    ) = JdtJavaSemanticMethodReferenceUse(
        methodQualifiedName = binding.qualifiedName(), methodBindingKey = binding.key,
        path = file.path, sourceRange = rangeFor(compilationUnit, start, length),
    )

    private fun constructorInvocation(
        compilationUnit: CompilationUnit,
        file: SourceFile,
        binding: IMethodBinding,
        nameStart: Int,
        nameLength: Int,
        bounds: IntRange,
        arguments: List<Expression>,
        callerMethodBinding: IMethodBinding?,
    ): JdtJavaSemanticInvocation = JdtJavaSemanticInvocation(
        methodQualifiedName = binding.qualifiedName(),
        methodBindingKey = binding.key,
        callerMethodQualifiedName = callerMethodBinding?.qualifiedName(),
        methodJvmIdentity = jvmIdentity(binding),
        callerMethodBindingKey = callerMethodBinding?.key,
        callerMethodJvmIdentity = callerMethodBinding?.let(::jvmIdentity),
        methodBindingRecovered = binding.isRecovered,
        callerBindingRecovered = callerMethodBinding?.isRecovered == true,
        path = file.path,
        nameRange = rangeFor(compilationUnit, nameStart, nameLength),
        argumentListRange = rangeFor(compilationUnit, bounds.first, bounds.last - bounds.first + 1),
        argumentRanges = arguments.map { rangeFor(compilationUnit, it.startPosition, it.length) },
        argumentLiteralFlags = arguments.map(::isSideEffectFreeJavaLiteral),
        evidence = JdtJavaSemanticEvidence.JDT_BINDING,
    )

    private fun isSideEffectFreeJavaLiteral(expression: Expression): Boolean = when (expression) {
        is ParenthesizedExpression -> isSideEffectFreeJavaLiteral(expression.expression)
        is BooleanLiteral, is CharacterLiteral, is NullLiteral, is NumberLiteral, is StringLiteral, is TextBlock -> true
        else -> false
    }

    private fun IMethodBinding.qualifiedName(): String {
        val owner = declaringClass?.qualifiedName.orEmpty()
        val canonicalName = if (isConstructor) "<init>" else name
        val signature = "$canonicalName(${parameterTypes.joinToString(",") { it.qualifiedName.takeIf(String::isNotBlank) ?: it.name }})"
        return "$owner#$signature"
    }

    private fun enclosingMethodBinding(node: org.eclipse.jdt.core.dom.ASTNode): IMethodBinding? {
        var ancestor = node.parent
        while (ancestor != null) {
            if (ancestor is MethodDeclaration) return ancestor.resolveBinding()?.methodDeclaration
            ancestor = ancestor.parent
        }
        return null
    }

    private fun privateModifierEndOffset(node: MethodDeclaration): Int =
        node.modifiers().filterIsInstance<Modifier>().singleOrNull { it.isPrivate }
            ?.let { it.startPosition + it.length }
            ?: node.name.startPosition

    /** A deliberately narrow, binding-backed subset for the first makeStatic slice. */
    private fun makeStaticRefusal(node: MethodDeclaration): String? {
        if (node.isConstructor) return "Constructors cannot be made static"
        if (!Modifier.isPrivate(node.modifiers)) return "Only private methods are supported"
        if (Modifier.isStatic(node.modifiers)) return "Method is already static"
        if (Modifier.isSynchronized(node.modifiers)) return "Synchronized methods are not supported because static conversion changes the monitor"
        if (node.modifiers().any { it is Annotation }) return "Annotated methods are not supported because framework semantics may depend on instance methods"
        if (node.body == null) return "Methods without a body are not supported"
        val owner = node.parent as? TypeDeclaration
            ?: return "Only methods declared directly in an ordinary Java class are supported"
        if (owner.isInterface || owner.parent !is CompilationUnit) {
            return "Only top-level ordinary Java classes are supported"
        }

        val targetBindingKey = node.resolveBinding()?.methodDeclaration?.key
        var refusal: String? = null
        node.body.accept(object : ASTVisitor() {
            override fun visit(node: TypeDeclaration): Boolean = false
            override fun visit(node: EnumDeclaration): Boolean = false
            override fun visit(node: RecordDeclaration): Boolean = false
            override fun visit(node: AnnotationTypeDeclaration): Boolean = false
            override fun visit(node: AnonymousClassDeclaration): Boolean = false

            override fun visit(node: ThisExpression): Boolean {
                refusal = "Method body uses this"
                return false
            }

            override fun visit(node: SuperMethodInvocation): Boolean {
                refusal = "Method body uses super"
                return false
            }

            override fun visit(node: SuperFieldAccess): Boolean {
                refusal = "Method body uses super"
                return false
            }

            override fun visit(node: MethodInvocation): Boolean {
                val binding = node.resolveMethodBinding()
                if (binding == null || binding.isRecovered) {
                    refusal = "Method body contains an unresolved method invocation"
                } else if (binding.methodDeclaration.key != targetBindingKey && !Modifier.isStatic(binding.modifiers) &&
                    (node.expression == null || node.expression is ThisExpression)) {
                    refusal = "Method body calls an instance method through its receiver"
                }
                return refusal == null
            }

            override fun visit(node: FieldAccess): Boolean {
                val binding = node.resolveFieldBinding()
                if (binding == null || binding.isRecovered) {
                    refusal = "Method body contains an unresolved field access"
                } else if (!Modifier.isStatic(binding.modifiers) && node.expression is ThisExpression) {
                    refusal = "Method body reads instance state through this"
                }
                return refusal == null
            }

            override fun visit(node: SimpleName): Boolean {
                val binding = node.resolveBinding()
                if (binding is IVariableBinding && binding.isField && !Modifier.isStatic(binding.modifiers)) {
                    val isQualifiedFieldName = (node.parent is FieldAccess && (node.parent as FieldAccess).name === node) ||
                        (node.parent is org.eclipse.jdt.core.dom.QualifiedName && (node.parent as org.eclipse.jdt.core.dom.QualifiedName).name === node)
                    if (!isQualifiedFieldName) refusal = "Method body reads an instance field implicitly"
                }
                return refusal == null
            }
        })
        return refusal
    }

    private fun IMethodBinding.isOverrideCandidate(): Boolean =
        !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers)

    private fun IMethodBinding.isInheritedBy(subType: ITypeBinding?): Boolean {
        if (subType == null) return false
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) return true
        val ownerPackage = declaringClass?.`package`?.name
        val subtypePackage = subType.`package`?.name
        return ownerPackage != null && ownerPackage == subtypePackage
    }

    private fun typeInheritanceRecords(
        node: TypeDeclaration,
        packageName: String,
        subtypeQualifiedName: String,
        subtypeBindingKey: String?,
    ): List<TypeInheritanceRecord> {
        val superTypes = mutableListOf<Pair<String, String?>>()
        node.superclassType?.let { type ->
            val binding = type.resolveBinding()?.typeDeclaration?.takeUnless { it.isRecovered }
            val name = binding?.qualifiedName?.takeIf(String::isNotBlank)
                ?: qualifyTypeName(packageName, type.toString())
            superTypes += name to binding?.key
        }
        node.superInterfaceTypes().forEach { rawType ->
            val type = rawType as? org.eclipse.jdt.core.dom.Type ?: return@forEach
            val binding = type.resolveBinding()?.typeDeclaration?.takeUnless { it.isRecovered }
            val name = binding?.qualifiedName?.takeIf(String::isNotBlank)
                ?: qualifyTypeName(packageName, type.toString())
            superTypes += name to binding?.key
        }
        return superTypes.distinct().map { (name, key) ->
            TypeInheritanceRecord(subtypeQualifiedName, name, subtypeBindingKey, key)
        }
    }

    private fun recordInheritanceRecords(
        node: RecordDeclaration,
        packageName: String,
        subtypeQualifiedName: String,
        subtypeBindingKey: String?,
    ): List<TypeInheritanceRecord> = node.superInterfaceTypes().mapNotNull { rawType ->
        val type = rawType as? org.eclipse.jdt.core.dom.Type ?: return@mapNotNull null
        val binding = type.resolveBinding()?.typeDeclaration?.takeUnless { it.isRecovered }
        val name = binding?.qualifiedName?.takeIf(String::isNotBlank)
            ?: qualifyTypeName(packageName, type.toString())
        name to binding?.key
    }.distinct().map { (name, key) ->
        TypeInheritanceRecord(subtypeQualifiedName, name, subtypeBindingKey, key)
    }

    private fun enumInheritanceRecords(
        node: EnumDeclaration,
        packageName: String,
        subtypeQualifiedName: String,
        subtypeBindingKey: String?,
    ): List<TypeInheritanceRecord> = node.superInterfaceTypes().mapNotNull { rawType ->
        val type = rawType as? org.eclipse.jdt.core.dom.Type ?: return@mapNotNull null
        val binding = type.resolveBinding()?.typeDeclaration?.takeUnless { it.isRecovered }
        val name = binding?.qualifiedName?.takeIf(String::isNotBlank)
            ?: qualifyTypeName(packageName, type.toString())
        TypeInheritanceRecord(subtypeQualifiedName, name, subtypeBindingKey, binding?.key)
    }.distinct()

    private fun qualifyTypeName(packageName: String, name: String): String =
        if (name.contains('.')) name else JavaPackageUtil.fqn(packageName, name)

    /** Whole-file association requires an actual parsed namespace/owner, independently of member ranges. */
    fun hasPlatformSourceOwner(
        file: SourceFile,
        identity: JdtJavaJvmIdentity,
        release: Int,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): Boolean {
        if (!JavaParserReleaseSupport.supportsRelease(release)) return false
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT); parser.setSource(file.content.toCharArray())
        parser.setCompilerOptions(JavaCore.getOptions().also { JavaCore.setComplianceOptions(if (release == 8) "1.8" else release.toString(), it) })
        val unit = createAst(parser, cancellation)
        if (unit.problems.any { it.isError && it.id and IProblem.Syntax != 0 }) return false
        if (unit.getPackage()?.name?.fullyQualifiedName.orEmpty() != identity.ownerBinaryName.substringBeforeLast('.', "")) return false
        val top = identity.ownerBinaryName.substringAfterLast('.').substringBefore('$')
        val owners = unit.types().filterIsInstance<org.eclipse.jdt.core.dom.AbstractTypeDeclaration>().filter { it.name.identifier == top }
        return owners.size == 1 && owners.single().flags and org.eclipse.jdt.core.dom.ASTNode.MALFORMED == 0
    }

    /** Exact binary origin from the same selected-SDK parser, never inferred from a type name. */
    fun platformBinarySelection(file: SourceFile, sourceRoots: List<Path>, classpath: List<Path>,
                                platform: JavaReleasePlatformAuthority, position: SourcePosition,
                                cancellation: SemanticCancellationToken): List<JdtJavaPlatformBinary> =
        platformBinarySelection(file, sourceRoots, classpath, platform, position, cancellation, platform.release)

    fun platformBinarySelection(file: SourceFile, sourceRoots: List<Path>, classpath: List<Path>,
                                platform: JavaReleasePlatformAuthority, position: SourcePosition,
                                cancellation: SemanticCancellationToken, sourceLevel: Int): List<JdtJavaPlatformBinary> =
        JavaReleasePlatformClasspath.materialize(platform).use { projection -> JdtClasspathResourceScope().use { resources ->
            val unit = parse(file, sourceRoots.filter(Files::isDirectory).map(Path::toString).toTypedArray(),
                classpath.filter(Files::exists).map(Path::toString).toTypedArray(), sourceLevel, platform, projection, resources,
                cancellation = cancellation)
            val found = mutableListOf<JdtJavaPlatformBinary>()
            fun capture(binding: IBinding?, start: Int, length: Int) {
                if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
                val range = rangeFor(unit, start, length)
                if (position < range.start || position >= range.end || binding == null || binding.isRecovered) return
                val identity = jvmIdentity(binding) ?: return
                val owner = when (binding) { is ITypeBinding -> binding; is IMethodBinding -> binding.methodDeclaration.declaringClass
                    is IVariableBinding -> binding.variableDeclaration.declaringClass; else -> null }?.typeDeclaration ?: return
                if (owner.isRecovered) return
                // Qualified DOM bridge, as with the standalone resolver/platform bridges.
                val field = owner.javaClass.getDeclaredField("binding").apply { isAccessible = true }
                val binary = field.get(owner) as? org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding ?: return
                val entry = String(binary.fileName ?: return)
                val origin = binary.path ?: return
                if (origin.scheme != "jrt" || origin.authority != null || origin.query != null || origin.fragment != null) return
                val module = origin.path?.removePrefix("/")?.substringBefore('/')
                    ?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.]*")) } ?: return
                if (entry != identity.ownerBinaryName.replace('.', '/') + ".class" || origin.path != "/$module/$entry") return
                // Java 8 grammar need not expose a module binding. The native binary
                // origin still names the exact JRT module; never derive it from a package.
                val domModule = owner.module?.name
                val compilerModule = binary.module()
                if (sourceLevel >= 9 && (domModule != module || compilerModule.isUnnamed())) return
                if (!domModule.isNullOrEmpty() && domModule != module) return
                if (!compilerModule.isUnnamed() && String(compilerModule.moduleName) != module) return
                found += JdtJavaPlatformBinary(identity, module, entry, origin.toString())
            }
            unit.accept(object : ASTVisitor() {
                override fun visit(node: SimpleName): Boolean { capture(node.resolveBinding(), node.startPosition, node.length); return true }
                override fun visit(node: ClassInstanceCreation): Boolean { capture(node.resolveConstructorBinding(), node.type.startPosition, node.type.length); return true }
            })
            found.distinct()
        } }

    private fun preparePlatformSourceModule(parser: ASTParser, moduleName: String) {
        val field = ASTParser::class.java.getDeclaredField("unitResolver").apply { isAccessible = true }
        val original = field.get(parser) as org.eclipse.jdt.internal.core.dom.ICompilationUnitResolver
        field.set(parser, java.lang.reflect.Proxy.newProxyInstance(original.javaClass.classLoader,
            arrayOf(org.eclipse.jdt.internal.core.dom.ICompilationUnitResolver::class.java)) { _, method, arguments ->
            val prepared = arguments?.clone()
            prepared?.indices?.forEach { index ->
                val unit = prepared[index] as? org.eclipse.jdt.internal.compiler.env.ICompilationUnit ?: return@forEach
                prepared[index] = java.lang.reflect.Proxy.newProxyInstance(unit.javaClass.classLoader,
                    arrayOf(org.eclipse.jdt.internal.compiler.env.ICompilationUnit::class.java)) { _, member, values ->
                    when (member.name) {
                        "getModuleName" -> moduleName.toCharArray()
                        "module" -> (values!![0] as org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment).getModule(moduleName.toCharArray())
                        else -> try { member.invoke(unit, *(values ?: emptyArray())) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.cause ?: e }
                    }
                }
            }
            try { method.invoke(original, *(prepared ?: emptyArray())) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.cause ?: e }
        })
    }

    private fun parse(
        file: SourceFile,
        sourceRoots: Array<String>,
        classpathEntries: Array<String>,
        sourceLevel: Int,
        platformAuthority: JavaReleasePlatformAuthority?,
        platformClasspath: JavaReleasePlatformClasspath?,
        classpathResources: JdtClasspathResourceScope,
        platformModuleName: String? = null,
        jpmsContext: JavaJpmsSourceSetContext = JavaJpmsSourceSetContext.NOT_APPLICABLE,
        compilerOptions: List<org.refactorkit.core.BuildCompilerOption> = emptyList(),
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): CompilationUnit {
        if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
        require(JavaParserReleaseSupport.supportsRelease(sourceLevel)) {
            "Java $sourceLevel is not supported by the installed JDT parser and compiler"
        }
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setSource(file.content.toCharArray())
        val packageName = JavaPackageUtil.extractPackage(file.content)
        val unitName = buildString {
            if (packageName.isNotEmpty()) append(packageName.replace('.', '/')).append('/')
            append(file.path.fileName.toString())
        }
        parser.setUnitName(unitName)
        val compliance = if (sourceLevel == 8) JavaCore.VERSION_1_8 else sourceLevel.toString()
        parser.setCompilerOptions(JavaCore.getOptions().also { options ->
            JavaCore.setComplianceOptions(compliance, options)
            if (platformAuthority != null) options[JavaCore.COMPILER_RELEASE] =
                if (platformAuthority.release == sourceLevel) JavaCore.ENABLED else JavaCore.DISABLED
        })
        val effectiveClasspath = if (platformAuthority == null) classpathEntries else {
            requireNotNull(platformClasspath).environmentPaths.map(Path::toString).toTypedArray() + classpathEntries
        }
        // Even a source-only workspace needs the running JVM system library for
        // bindings such as java.lang.String. Omitting setEnvironment for empty
        // project paths makes JDT fall back to an unconfigured workspace VM and
        // can fail with "Missing system library" in library/daemon use.
        parser.setEnvironment(effectiveClasspath, sourceRoots, null, platformAuthority == null)
        parser.setResolveBindings(true)
        parser.setBindingsRecovery(true)
        parser.setStatementsRecovery(true)
        classpathResources.prepare(parser)
        val projectModuleName = JdtJpmsClasspath.prepare(
            parser,
            classpathResources,
            sourceRoots,
            sourceLevel,
            compilerOptions,
            jpmsContext,
        )
        platformClasspath?.prepare(parser)
        if (platformModuleName != null && projectModuleName != null && platformModuleName != projectModuleName) {
            throw JdtJpmsAnalysisException(
                "java.context.jpmsSemanticAnalysisUnavailable",
                "The selected source requires conflicting project and platform module identities",
            )
        }
        (projectModuleName ?: platformModuleName)?.let { preparePlatformSourceModule(parser, it) }
        return createAst(parser, cancellation)
    }

    private fun createAst(parser: ASTParser, cancellation: SemanticCancellationToken): CompilationUnit {
        val monitor = object : NullProgressMonitor() {
            override fun isCanceled(): Boolean = super.isCanceled() || cancellation.isCancellationRequested()
        }
        return try {
            parser.createAST(monitor) as CompilationUnit
        } catch (_: OperationCanceledException) {
            throw JdtJavaAnalysisCancelledException()
        }.also {
            if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
        }
    }

    private fun symbol(
        file: SourceFile,
        compilationUnit: CompilationUnit,
        packageName: String,
        ownerQualifiedName: String?,
        simpleName: String,
        kind: JdtJavaSemanticSymbolKind,
        startPosition: Int,
        bindingQualifiedName: String?,
        bindingKey: String?,
        bindingRecovered: Boolean = false,
        memberSignature: String?,
        hoverSignature: String? = null,
        documentation: String? = null,
        jvmIdentity: JdtJavaJvmIdentity? = null,
        recordComponentIndex: Int? = null,
        isAbstract: Boolean? = null,
    ): JdtJavaSemanticSymbol {
        val qualifiedName = bindingQualifiedName
            ?.takeIf { it.isNotBlank() }
            ?: when (kind) {
                JdtJavaSemanticSymbolKind.METHOD,
                JdtJavaSemanticSymbolKind.CONSTRUCTOR -> "${ownerQualifiedName ?: JavaPackageUtil.fqn(packageName, simpleName)}#${memberSignature ?: simpleName}"
                JdtJavaSemanticSymbolKind.FIELD,
                JdtJavaSemanticSymbolKind.RECORD_COMPONENT -> "${ownerQualifiedName ?: JavaPackageUtil.fqn(packageName, simpleName)}#$simpleName"
                else -> ownerQualifiedName?.let { "$it.$simpleName" } ?: JavaPackageUtil.fqn(packageName, simpleName)
            }
        return JdtJavaSemanticSymbol(
            simpleName = simpleName,
            qualifiedName = qualifiedName,
            kind = kind,
            path = file.path,
            line = (compilationUnit.getLineNumber(startPosition) - 1).coerceAtLeast(0),
            ownerQualifiedName = ownerQualifiedName,
            memberSignature = memberSignature,
            hoverSignature = (hoverSignature ?: "${kind.name.lowercase()} $qualifiedName")
                .take(MAX_HOVER_SIGNATURE_CHARS),
            documentation = documentation?.take(MAX_HOVER_DOCUMENTATION_CHARS),
            sourceRange = rangeFor(compilationUnit, startPosition, simpleName.length),
            bindingKey = bindingKey,
            evidence = if (bindingKey.isNullOrBlank()) {
                JdtJavaSemanticEvidence.JDT_PARSE
            } else {
                JdtJavaSemanticEvidence.JDT_BINDING
            },
            recovered = bindingRecovered,
            jvmIdentity = jvmIdentity,
            recordComponentIndex = recordComponentIndex,
            isAbstract = isAbstract,
        )
    }

    private fun rangeFor(compilationUnit: CompilationUnit, startPosition: Int, length: Int): SourceRange {
        val startLine = (compilationUnit.getLineNumber(startPosition) - 1).coerceAtLeast(0)
        val startColumn = compilationUnit.getColumnNumber(startPosition).coerceAtLeast(0)
        val endPosition = startPosition + length
        val endLine = (compilationUnit.getLineNumber(endPosition) - 1).coerceAtLeast(startLine)
        val endColumn = compilationUnit.getColumnNumber(endPosition).coerceAtLeast(0)
        return SourceRange(
            SourcePosition(startLine, startColumn),
            SourcePosition(endLine, endColumn),
        )
    }

    private fun methodHoverSignature(node: MethodDeclaration, binding: IMethodBinding?): String {
        val parameterTypes = binding?.parameterTypes
            ?.map { type -> type.qualifiedName.takeIf { it.isNotBlank() } ?: type.name }
            ?: node.parameters().filterIsInstance<SingleVariableDeclaration>().map { it.type.toString() }
        val parameters = parameterTypes.joinToString(", ")
        if (node.isConstructor) return "${node.name.identifier}($parameters)"
        val returnType = binding?.returnType?.let { it.qualifiedName.takeIf(String::isNotBlank) ?: it.name }
            ?: node.returnType2?.toString() ?: "void"
        return "$returnType ${node.name.identifier}($parameters)"
    }

    private fun methodSignature(node: MethodDeclaration, binding: IMethodBinding?): String {
        val parameterTypes = binding?.parameterTypes
            ?.map { type -> type.qualifiedName.takeIf { it.isNotBlank() } ?: type.name }
            ?: node.parameters().filterIsInstance<SingleVariableDeclaration>().map { it.type.toString() }
        val name = if (node.isConstructor) "<init>" else node.name.identifier
        return "$name(${parameterTypes.joinToString(",")})"
    }

    private fun SimpleName.isDeclarationName(): Boolean {
        val parent = parent
        return when (parent) {
            is TypeDeclaration -> parent.name === this
            is AnnotationTypeDeclaration -> parent.name === this
            is AnnotationTypeMemberDeclaration -> parent.name === this
            is EnumDeclaration -> parent.name === this
            is RecordDeclaration -> parent.name === this
            is MethodDeclaration -> parent.name === this
            is VariableDeclarationFragment -> parent.name === this
            is SingleVariableDeclaration -> parent.name === this
            else -> false
        }
    }

    private fun parenthesisInterior(content: String, from: Int): IntRange? {
        val open = content.indexOf('(', from)
        if (open < 0) return null
        var depth = 0
        var index = open
        while (index < content.length) {
            when (content[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return (open + 1)..(index - 1)
                }
                '"' -> {
                    index++
                    while (index < content.length && content[index] != '"') {
                        if (content[index] == '\\') index++
                        index++
                    }
                }
                '\'' -> {
                    index++
                    while (index < content.length && content[index] != '\'') {
                        if (content[index] == '\\') index++
                        index++
                    }
                }
            }
            index++
        }
        return null
    }

    private companion object {
        const val MAX_HOVER_SIGNATURE_CHARS = 8_192
        const val MAX_HOVER_DOCUMENTATION_CHARS = 16_384
    }

    private data class FileAnalysis(
        val symbols: List<JdtJavaSemanticSymbol>,
        val rawReferences: List<RawReference>,
        val warnings: List<JdtJavaSemanticWarning>,
        val methodBindings: List<MethodBindingRecord>,
        val inheritances: List<TypeInheritanceRecord>,
        val parameters: List<JdtJavaSemanticParameter>,
        val methods: List<JdtJavaSemanticMethod>,
        val invocations: List<JdtJavaSemanticInvocation>,
        val methodReferences: List<JdtJavaSemanticMethodReference>,
        val methodReferenceUses: List<JdtJavaSemanticMethodReferenceUse>,
        val entryPointSource: JavaNativeEntryPointSource? = null,
    )

    private data class MethodBindingRecord(
        val symbol: JdtJavaSemanticSymbol,
        val binding: IMethodBinding,
    )

    private data class TypeInheritanceRecord(
        val subtypeQualifiedName: String,
        val supertypeQualifiedName: String,
        val subtypeBindingKey: String?,
        val supertypeBindingKey: String?,
    )

    private data class RawReference(
        val simpleName: String,
        val path: Path,
        val line: Int,
        val sourceRange: SourceRange,
        val bindingKey: String?,
        val symbolQualifiedName: String?,
        val isImport: Boolean,
        val recovered: Boolean,
        val jvmIdentity: JdtJavaJvmIdentity? = null,
        val typeBindingKey: String? = null,
        val typeQualifiedName: String? = null,
        val isKotlinBinaryOwner: Boolean = false,
    )
}

enum class JdtJavaJvmIdentityKind { TYPE, CALLABLE, FIELD }

/** JVM-level identity projected from one exact JDT binding without exposing JDT handles. */
data class JdtJavaJvmIdentity(
    val kind: JdtJavaJvmIdentityKind,
    val ownerBinaryName: String,
    val memberName: String,
    val descriptor: String,
)

sealed interface JdtAuthoritativeJavaAnalysisResolution {
    data class Available(
        val analysis: JdtJavaSemanticAnalysisResult,
        val platformIdentitiesByRelease: Map<Int, String>,
    ) : JdtAuthoritativeJavaAnalysisResolution

    data class Refused(val code: String, val message: String) : JdtAuthoritativeJavaAnalysisResolution
}

class JdtJavaSemanticAnalysisResult(
    symbols: List<JdtJavaSemanticSymbol>,
    references: List<JdtJavaSemanticReference> = emptyList(),
    warnings: List<JdtJavaSemanticWarning> = emptyList(),
    overrideRelations: List<JdtJavaSemanticOverrideRelation> = emptyList(),
    bindingUses: List<JdtJavaSemanticBindingUse> = emptyList(),
    parameters: List<JdtJavaSemanticParameter> = emptyList(),
    methods: List<JdtJavaSemanticMethod> = emptyList(),
    invocations: List<JdtJavaSemanticInvocation> = emptyList(),
    methodReferences: List<JdtJavaSemanticMethodReference> = emptyList(),
    val snapshotHash: String = "",
    typeInheritance: List<JdtJavaSemanticTypeInheritance> = emptyList(),
    methodReferenceUses: List<JdtJavaSemanticMethodReferenceUse> = emptyList(),
    entryPointSources: List<JavaNativeEntryPointSource> = emptyList(),
) {
    private val entryPointSourceState = detachedSemanticList(entryPointSources)
    private val symbolState = detachedSemanticList(symbols)
    private val referenceState = detachedSemanticList(references)
    private val warningState = detachedSemanticList(warnings)
    private val overrideRelationState = detachedSemanticList(overrideRelations)
    private val bindingUseState = detachedSemanticList(bindingUses)
    private val parameterState = detachedSemanticList(parameters)
    private val methodState = detachedSemanticList(methods)
    private val invocationState = detachedSemanticList(invocations.map { invocation ->
        JdtJavaSemanticInvocation(
            methodQualifiedName = invocation.methodQualifiedName,
            methodBindingKey = invocation.methodBindingKey,
            path = invocation.path,
            nameRange = invocation.nameRange,
            argumentListRange = invocation.argumentListRange,
            argumentRanges = invocation.argumentRanges,
            argumentLiteralFlags = invocation.argumentLiteralFlags,
            evidence = invocation.evidence,
            callerMethodQualifiedName = invocation.callerMethodQualifiedName,
            receiverKind = invocation.receiverKind,
            methodJvmIdentity = invocation.methodJvmIdentity,
            callerMethodBindingKey = invocation.callerMethodBindingKey,
            callerMethodJvmIdentity = invocation.callerMethodJvmIdentity,
            methodBindingRecovered = invocation.methodBindingRecovered,
            callerBindingRecovered = invocation.callerBindingRecovered,
        )
    })
    private val methodReferenceState = detachedSemanticList(methodReferences)
    private val methodReferenceUseState = detachedSemanticList(methodReferenceUses)
    private val typeInheritanceState = detachedSemanticList(typeInheritance)

    val entryPointSources: List<JavaNativeEntryPointSource> get() = detachedSemanticList(entryPointSourceState)
    val symbols: List<JdtJavaSemanticSymbol> get() = detachedSemanticList(symbolState)
    val references: List<JdtJavaSemanticReference> get() = detachedSemanticList(referenceState)
    val warnings: List<JdtJavaSemanticWarning> get() = detachedSemanticList(warningState)
    val overrideRelations: List<JdtJavaSemanticOverrideRelation> get() = detachedSemanticList(overrideRelationState)
    val bindingUses: List<JdtJavaSemanticBindingUse> get() = detachedSemanticList(bindingUseState)
    val parameters: List<JdtJavaSemanticParameter> get() = detachedSemanticList(parameterState)
    val methods: List<JdtJavaSemanticMethod> get() = detachedSemanticList(methodState)
    val invocations: List<JdtJavaSemanticInvocation> get() = detachedSemanticList(invocationState)
    val methodReferences: List<JdtJavaSemanticMethodReference> get() = detachedSemanticList(methodReferenceState)
    val methodReferenceUses: List<JdtJavaSemanticMethodReferenceUse> get() = detachedSemanticList(methodReferenceUseState)
    val typeInheritance: List<JdtJavaSemanticTypeInheritance> get() = detachedSemanticList(typeInheritanceState)

    fun copy(snapshotHash: String = this.snapshotHash): JdtJavaSemanticAnalysisResult =
        JdtJavaSemanticAnalysisResult(
            symbolState, referenceState, warningState, overrideRelationState, bindingUseState, parameterState,
            methodState, invocationState, methodReferenceState, snapshotHash, typeInheritanceState, methodReferenceUseState, entryPointSourceState,
        )

    override fun toString(): String = "JdtJavaSemanticAnalysisResult(" +
        "symbols=${symbolState.size}, references=${referenceState.size}, warnings=${warningState.size}, " +
        "overrideRelations=${overrideRelationState.size}, bindingUses=${bindingUseState.size}, " +
        "parameters=${parameterState.size}, methods=${methodState.size}, invocations=${invocationState.size}, " +
        "methodReferences=${methodReferenceState.size}, typeInheritance=${typeInheritanceState.size}, snapshotHash=$snapshotHash)"
}

private fun <T> detachedSemanticList(source: Collection<T>): List<T> =
    java.util.Collections.unmodifiableList(java.util.ArrayList(source))

data class JdtJavaSemanticMethod(
    val qualifiedName: String,
    val signature: String,
    val bindingKey: String,
    val path: Path,
    val parameterListRange: SourceRange,
    val constructor: Boolean,
    val modifiers: Int,
    val evidence: JdtJavaSemanticEvidence,
    val canonicalConstructor: Boolean = false,
    val declarationRange: SourceRange? = null,
    val staticModifierInsertionOffset: Int? = null,
    val makeStaticRefusal: String? = null,
)

class JdtJavaSemanticInvocation(
    val methodQualifiedName: String,
    val methodBindingKey: String,
    val path: Path,
    val nameRange: SourceRange,
    val argumentListRange: SourceRange,
    argumentRanges: List<SourceRange>,
    val evidence: JdtJavaSemanticEvidence,
    argumentLiteralFlags: List<Boolean> = emptyList(),
    val callerMethodQualifiedName: String? = null,
    val receiverKind: JdtJavaInvocationReceiverKind = JdtJavaInvocationReceiverKind.IMPLICIT,
    val methodJvmIdentity: JdtJavaJvmIdentity? = null,
    val callerMethodBindingKey: String? = null,
    val callerMethodJvmIdentity: JdtJavaJvmIdentity? = null,
    val methodBindingRecovered: Boolean = false,
    val callerBindingRecovered: Boolean = false,
) {
    private val argumentRangeState = detachedSemanticList(argumentRanges)
    private val argumentLiteralFlagState = detachedSemanticList(argumentLiteralFlags)
    val argumentRanges: List<SourceRange> get() = detachedSemanticList(argumentRangeState)
    val argumentLiteralFlags: List<Boolean> get() = detachedSemanticList(argumentLiteralFlagState)
}

enum class JdtJavaInvocationReceiverKind { IMPLICIT, EXPLICIT, SUPER }

data class JdtJavaSemanticMethodReference(
    val methodQualifiedName: String,
    val methodBindingKey: String,
    val path: Path,
    val sourceRange: SourceRange,
    val kind: JdtJavaSemanticMethodReferenceKind,
    val evidence: JdtJavaSemanticEvidence,
)

enum class JdtJavaSemanticMethodReferenceKind { STATIC_METHOD, CONSTRUCTOR, PRIVATE_THIS_METHOD }

data class JdtJavaSemanticMethodReferenceUse(
    val methodQualifiedName: String,
    val methodBindingKey: String,
    val path: Path,
    val sourceRange: SourceRange,
)

data class JdtJavaSemanticParameter(
    val methodQualifiedName: String,
    val methodSignature: String,
    val methodBindingKey: String,
    val parameterBindingKey: String,
    val name: String,
    val index: Int,
    val path: Path,
    val declarationRange: SourceRange,
    val typeRange: SourceRange,
    val sourceRange: SourceRange,
    val evidence: JdtJavaSemanticEvidence,
)

data class JdtJavaSemanticSymbol(
    val simpleName: String,
    val qualifiedName: String,
    val kind: JdtJavaSemanticSymbolKind,
    val path: Path,
    val line: Int,
    val ownerQualifiedName: String?,
    val memberSignature: String?,
    val hoverSignature: String,
    val documentation: String?,
    val sourceRange: SourceRange,
    val bindingKey: String?,
    val evidence: JdtJavaSemanticEvidence,
    val recovered: Boolean = false,
    val jvmIdentity: JdtJavaJvmIdentity? = null,
    val recordComponentIndex: Int? = null,
    val isAbstract: Boolean? = null,
)

data class JdtJavaSemanticReference(
    val simpleName: String,
    val symbolQualifiedName: String,
    val symbolKind: JdtJavaSemanticSymbolKind,
    val symbolSignature: String?,
    val path: Path,
    val line: Int,
    val sourceRange: SourceRange,
    val bindingKey: String?,
    val evidence: JdtJavaSemanticEvidence,
    val recovered: Boolean = false,
    val jvmIdentity: JdtJavaJvmIdentity? = null,
)

data class JdtJavaSemanticBindingUse(
    val simpleName: String,
    val path: Path,
    val line: Int,
    val sourceRange: SourceRange,
    val bindingKey: String,
    val symbolQualifiedName: String?,
    val isImport: Boolean,
    val evidence: JdtJavaSemanticEvidence,
    val recovered: Boolean = false,
    val jvmIdentity: JdtJavaJvmIdentity? = null,
    val typeBindingKey: String? = null,
    val typeQualifiedName: String? = null,
    val isKotlinBinaryOwner: Boolean = false,
) {
    constructor(
        simpleName: String,
        path: Path,
        line: Int,
        sourceRange: SourceRange,
        bindingKey: String,
        symbolQualifiedName: String?,
        isImport: Boolean,
        evidence: JdtJavaSemanticEvidence,
        recovered: Boolean,
        jvmIdentity: JdtJavaJvmIdentity?,
        typeBindingKey: String?,
        typeQualifiedName: String?,
    ) : this(simpleName, path, line, sourceRange, bindingKey, symbolQualifiedName, isImport, evidence,
        recovered, jvmIdentity, typeBindingKey, typeQualifiedName, false)

    /** API 0.2 compatibility constructor retained while qualified binary identity is additive. */
    constructor(
        simpleName: String,
        path: Path,
        line: Int,
        sourceRange: SourceRange,
        bindingKey: String,
        isImport: Boolean,
        evidence: JdtJavaSemanticEvidence,
    ) : this(simpleName, path, line, sourceRange, bindingKey, null, isImport, evidence, false)
}

enum class JdtJavaDiagnosticCategory {
    SYNTAX,
    TYPE_RESOLUTION,
}

data class JdtJavaSemanticWarning(
    val path: Path,
    val line: Int,
    val sourceRange: SourceRange,
    val message: String,
    val category: JdtJavaDiagnosticCategory,
    val problemId: Int,
    val evidence: JdtJavaSemanticEvidence,
)

data class JdtJavaSemanticOverrideRelation(
    val overridingSymbolQualifiedName: String,
    val overriddenSymbolQualifiedName: String,
    val overridingBindingKey: String,
    val overriddenBindingKey: String,
    val evidence: JdtJavaSemanticEvidence,
)

data class JdtJavaSemanticTypeInheritance(
    val subtypeQualifiedName: String,
    val supertypeQualifiedName: String,
    val subtypeBindingKey: String?,
    val supertypeBindingKey: String?,
    val evidence: JdtJavaSemanticEvidence,
)

enum class JdtJavaSemanticSymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION,
    METHOD,
    FIELD,
    RECORD_COMPONENT,
    CONSTRUCTOR,
}

enum class JdtJavaSemanticEvidence {
    JDT_BINDING,
    JDT_PARSE,
    LEXICAL_FALLBACK,
}
