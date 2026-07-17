package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.compiler.IProblem
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.EnumDeclaration
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ImportDeclaration
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.RecordDeclaration
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
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

/**
 * Early Eclipse JDT-backed analysis prototype for the v0.3.0 line.
 *
 * This class intentionally exposes evidence, not refactoring authority. Existing
 * lexical planners remain the safety fallback until JDT-backed behavior is wired
 * into planners and covered by semantic tests.
 */
class JdtJavaSemanticAnalyzer {
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
        val fileAnalyses = snapshot.files
            .filter { it.languageId == "java" }
            .map { file ->
                if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
                val owner = snapshot.modules
                    .filter { module -> module.sourceRoots.any { file.path.normalize().startsWith(it.normalize()) } }
                    .maxByOrNull { module -> module.root.nameCount }
                val buildOwner = findBuildOwner(snapshot, file.path)
                    ?: owner?.let { findCompatibilityBuildOwner(snapshot.buildModels, it) }
                val buildEnvironment = buildOwner?.let { buildSemanticEnvironment(file, it.first, it.second) }
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
                    visibleModules.filter { it != owner }.forEach { addAll(it.mainClasspathEntries) }
                }.ifEmpty { visibleModules.flatMap(Module::classpathEntries) }) + additionalClasspathEntries).distinct()
                val sourceRoots = sourceRootPaths
                    .map { snapshot.workspace.root.resolve(it).toAbsolutePath().normalize().toString() }
                    .filter { Files.isDirectory(Path.of(it)) }
                    .distinct().toTypedArray()
                val classpathEntries = classpathPaths
                    .map { snapshot.workspace.root.resolve(it).toAbsolutePath().normalize().toString() }
                    .filter { Files.exists(Path.of(it)) }
                    .distinct().toTypedArray()
                val sourceLevel = buildEnvironment?.sourceLevel
                    ?: owner?.languageSettings?.get("java.sourceLevel")?.toIntOrNull()?.coerceIn(8, 25)
                    ?: 25
                analyzeFileWithReferences(file, sourceRoots, classpathEntries, sourceLevel)
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
                )
            }
        }
        val warnings = fileAnalyses.flatMap { it.warnings }
        val parameters = fileAnalyses.flatMap { it.parameters }
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
        )
    }

    private data class BuildSemanticEnvironment(
        val testSource: Boolean,
        val sourceRoots: List<Path>,
        val classpathEntries: List<Path>,
        val sourceLevel: Int,
    )

    private fun findBuildOwner(snapshot: ProjectSnapshot, path: Path): Pair<BuildModel, BuildModule>? {
        val ownership = snapshot.owningBuildSourceRoots(path).singleOrNull() ?: return null
        val model = snapshot.buildModels.singleOrNull { it.providerId == ownership.providerId } ?: return null
        return model to ownership.module
    }

    private fun findCompatibilityBuildOwner(buildModels: List<BuildModel>, owner: Module): Pair<BuildModel, BuildModule>? =
        buildModels.asSequence().mapNotNull { model ->
            model.modules.firstOrNull { it.id == owner.name && it.root.normalize() == owner.root.normalize() }
                ?.let { model to it }
        }.firstOrNull()

    private fun buildSemanticEnvironment(
        file: SourceFile,
        model: BuildModel,
        owner: BuildModule,
    ): BuildSemanticEnvironment {
        val selectedSet = owner.sourceSets.filter { sourceSet ->
            sourceSet.sourceRoots.any { file.path.normalize().startsWith(it.normalize()) }
        }.maxByOrNull { sourceSet -> sourceSet.sourceRoots.maxOfOrNull { it.nameCount } ?: 0 }
            ?: owner.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }
        val testSource = selectedSet?.kind in setOf(SourceSetKind.TEST, SourceSetKind.INTEGRATION_TEST) ||
            selectedSet?.attributes?.get("visibility") == "test"
        val mainSet = owner.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }
        val byId = model.modules.associateBy(BuildModule::id)
        val visible = linkedMapOf(owner.id to owner)
        val pending = ArrayDeque(selectedSet?.moduleDependencies.orEmpty().map { it.targetModuleId })
        while (pending.isNotEmpty()) {
            val dependencyId = pending.removeFirst()
            if (dependencyId in visible) continue
            val dependency = byId[dependencyId] ?: continue
            visible[dependencyId] = dependency
            dependency.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }
                ?.moduleDependencies.orEmpty().mapTo(pending) { it.targetModuleId }
        }
        val sourceRoots = buildList {
            mainSet?.let { addAll(it.sourceRoots) }
            if (selectedSet != null && selectedSet != mainSet) addAll(selectedSet.sourceRoots)
            visible.values.filter { it != owner }.forEach { dependency ->
                dependency.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }?.let { addAll(it.sourceRoots) }
            }
        }.distinct()
        val classpathEntries = buildList {
            selectedSet?.let { addAll(it.classpathEntries) }
            visible.values.filter { it != owner }.forEach { dependency ->
                dependency.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }
                    ?.let { addAll(it.classpathEntries) }
            }
        }.distinct()
        val sourceLevel = selectedSet?.attributes?.get("java.sourceLevel")?.toIntOrNull()?.coerceIn(8, 25) ?: 25
        return BuildSemanticEnvironment(testSource, sourceRoots, classpathEntries, sourceLevel)
    }

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
        analyzeFileWithReferences(file, emptyArray(), emptyArray(), sourceLevel.coerceIn(8, 25)).symbols

    private fun analyzeFileWithReferences(
        file: SourceFile,
        sourceRoots: Array<String>,
        classpathEntries: Array<String>,
        sourceLevel: Int,
    ): FileAnalysis {
        if (file.languageId != "java") return FileAnalysis(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val compilationUnit = parse(file, sourceRoots, classpathEntries, sourceLevel)
        val packageName = compilationUnit.`package`?.name?.fullyQualifiedName ?: JavaPackageUtil.extractPackage(file.content)
        val symbols = mutableListOf<JdtJavaSemanticSymbol>()
        val rawReferences = mutableListOf<RawReference>()
        val methodBindings = mutableListOf<MethodBindingRecord>()
        val inheritances = mutableListOf<TypeInheritanceRecord>()
        val ownerStack = mutableListOf<String>()
        val parameters = mutableListOf<JdtJavaSemanticParameter>()

        compilationUnit.accept(object : ASTVisitor() {
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
                    memberSignature = null,
                    documentation = node.javadoc?.toString(),
                )
                symbols += typeSymbol
                inheritances += typeInheritanceRecords(node, packageName, typeSymbol.qualifiedName)
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
                    memberSignature = null,
                    documentation = node.javadoc?.toString(),
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
                    memberSignature = null,
                    documentation = node.javadoc?.toString(),
                )
                symbols += enumSymbol
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
                    memberSignature = null,
                    documentation = node.javadoc?.toString(),
                )
                symbols += recordSymbol
                inheritances += recordInheritanceRecords(node, packageName, recordSymbol.qualifiedName)
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
                    memberSignature = signature,
                    hoverSignature = "${node.type} ${node.name.identifier}()",
                    documentation = node.javadoc?.toString(),
                )
                symbols += memberSymbol
                if (binding != null) methodBindings += MethodBindingRecord(memberSymbol, binding)
                return true
            }

            override fun visit(node: MethodDeclaration): Boolean {
                val owner = ownerStack.lastOrNull() ?: JavaPackageUtil.fqn(packageName, file.path.fileName.toString().removeSuffix(".java"))
                val binding = node.resolveBinding()
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
                    memberSignature = signature,
                    hoverSignature = methodHoverSignature(node, binding),
                    documentation = node.javadoc?.toString(),
                )
                symbols += methodSymbol
                if (binding != null && !node.isConstructor) {
                    methodBindings += MethodBindingRecord(methodSymbol, binding)
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
                        sourceRange = rangeFor(compilationUnit, identifier.startPosition, identifier.length),
                        evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                    )
                }
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
                        memberSignature = null,
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
                )
                return false
            }

            override fun visit(node: ClassInstanceCreation): Boolean {
                val binding = node.resolveConstructorBinding() ?: return true
                rawReferences += RawReference(
                    simpleName = binding.declaringClass?.name ?: node.type.toString(),
                    path = file.path,
                    line = (compilationUnit.getLineNumber(node.startPosition) - 1).coerceAtLeast(0),
                    sourceRange = rangeFor(compilationUnit, node.type.startPosition, node.type.length),
                    bindingKey = declarationBindingKey(binding),
                    symbolQualifiedName = bindingQualifiedName(binding),
                    isImport = false,
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
        return FileAnalysis(symbols, rawReferences, warnings, methodBindings, inheritances, parameters)
    }

    private fun declarationBindingKey(binding: IBinding?): String? = when (binding) {
        is ITypeBinding -> binding.typeDeclaration.key
        is IMethodBinding -> binding.methodDeclaration.key
        is IVariableBinding -> binding.variableDeclaration.key
        else -> binding?.key
    }

    private fun bindingQualifiedName(binding: IBinding?): String? = when (binding) {
        is ITypeBinding -> binding.typeDeclaration.qualifiedName.takeIf(String::isNotBlank)
        is IMethodBinding -> binding.methodDeclaration.qualifiedName()
        is IVariableBinding -> binding.variableDeclaration.let { variable ->
            variable.declaringClass?.qualifiedName?.takeIf(String::isNotBlank)?.let { "$it#${variable.name}" }
        }
        else -> null
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

    private fun IMethodBinding.qualifiedName(): String {
        val owner = declaringClass?.qualifiedName.orEmpty()
        val canonicalName = if (isConstructor) "<init>" else name
        val signature = "$canonicalName(${parameterTypes.joinToString(",") { it.qualifiedName.takeIf(String::isNotBlank) ?: it.name }})"
        return "$owner#$signature"
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
    ): List<TypeInheritanceRecord> {
        val superTypes = mutableListOf<String>()
        node.superclassType?.let { type ->
            superTypes += type.resolveBinding()?.qualifiedName?.takeIf { it.isNotBlank() }
                ?: qualifyTypeName(packageName, type.toString())
        }
        node.superInterfaceTypes().forEach { rawType ->
            val type = rawType as? org.eclipse.jdt.core.dom.Type ?: return@forEach
            superTypes += type.resolveBinding()?.qualifiedName?.takeIf { it.isNotBlank() }
                ?: qualifyTypeName(packageName, type.toString())
        }
        return superTypes.distinct().map { TypeInheritanceRecord(subtypeQualifiedName, it) }
    }

    private fun recordInheritanceRecords(
        node: RecordDeclaration,
        packageName: String,
        subtypeQualifiedName: String,
    ): List<TypeInheritanceRecord> = node.superInterfaceTypes().mapNotNull { rawType ->
        val type = rawType as? org.eclipse.jdt.core.dom.Type ?: return@mapNotNull null
        type.resolveBinding()?.qualifiedName?.takeIf { it.isNotBlank() }
            ?: qualifyTypeName(packageName, type.toString())
    }.distinct().map { TypeInheritanceRecord(subtypeQualifiedName, it) }

    private fun qualifyTypeName(packageName: String, name: String): String =
        if (name.contains('.')) name else JavaPackageUtil.fqn(packageName, name)

    private fun parse(
        file: SourceFile,
        sourceRoots: Array<String>,
        classpathEntries: Array<String>,
        sourceLevel: Int,
    ): CompilationUnit {
        val parser = ASTParser.newParser(AST.JLS25)
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setSource(file.content.toCharArray())
        val packageName = JavaPackageUtil.extractPackage(file.content)
        val unitName = buildString {
            if (packageName.isNotEmpty()) append(packageName.replace('.', '/')).append('/')
            append(file.path.fileName.toString())
        }
        parser.setUnitName(unitName)
        val compliance = if (sourceLevel == 8) JavaCore.VERSION_1_8 else sourceLevel.toString()
        parser.setCompilerOptions(JavaCore.getOptions().also { JavaCore.setComplianceOptions(compliance, it) })
        if (sourceRoots.isNotEmpty() || classpathEntries.isNotEmpty()) {
            parser.setEnvironment(classpathEntries, sourceRoots, null, true)
        }
        parser.setResolveBindings(true)
        parser.setBindingsRecovery(true)
        parser.setStatementsRecovery(true)
        return parser.createAST(null) as CompilationUnit
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
        memberSignature: String?,
        hoverSignature: String? = null,
        documentation: String? = null,
    ): JdtJavaSemanticSymbol {
        val qualifiedName = bindingQualifiedName
            ?.takeIf { it.isNotBlank() }
            ?: when (kind) {
                JdtJavaSemanticSymbolKind.METHOD,
                JdtJavaSemanticSymbolKind.CONSTRUCTOR -> "${ownerQualifiedName ?: JavaPackageUtil.fqn(packageName, simpleName)}#${memberSignature ?: simpleName}"
                JdtJavaSemanticSymbolKind.FIELD -> "${ownerQualifiedName ?: JavaPackageUtil.fqn(packageName, simpleName)}#$simpleName"
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
    )

    private data class MethodBindingRecord(
        val symbol: JdtJavaSemanticSymbol,
        val binding: IMethodBinding,
    )

    private data class TypeInheritanceRecord(
        val subtypeQualifiedName: String,
        val supertypeQualifiedName: String,
    )

    private data class RawReference(
        val simpleName: String,
        val path: Path,
        val line: Int,
        val sourceRange: SourceRange,
        val bindingKey: String?,
        val symbolQualifiedName: String?,
        val isImport: Boolean,
    )
}

data class JdtJavaSemanticAnalysisResult(
    val symbols: List<JdtJavaSemanticSymbol>,
    val references: List<JdtJavaSemanticReference> = emptyList(),
    val warnings: List<JdtJavaSemanticWarning> = emptyList(),
    val overrideRelations: List<JdtJavaSemanticOverrideRelation> = emptyList(),
    val bindingUses: List<JdtJavaSemanticBindingUse> = emptyList(),
    val parameters: List<JdtJavaSemanticParameter> = emptyList(),
)

data class JdtJavaSemanticParameter(
    val methodQualifiedName: String,
    val methodSignature: String,
    val methodBindingKey: String,
    val parameterBindingKey: String,
    val name: String,
    val index: Int,
    val path: Path,
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
) {
    /** API 0.2 compatibility constructor retained while qualified binary identity is additive. */
    constructor(
        simpleName: String,
        path: Path,
        line: Int,
        sourceRange: SourceRange,
        bindingKey: String,
        isImport: Boolean,
        evidence: JdtJavaSemanticEvidence,
    ) : this(simpleName, path, line, sourceRange, bindingKey, null, isImport, evidence)
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

enum class JdtJavaSemanticSymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION,
    METHOD,
    FIELD,
    CONSTRUCTOR,
}

enum class JdtJavaSemanticEvidence {
    JDT_BINDING,
    JDT_PARSE,
    LEXICAL_FALLBACK,
}
