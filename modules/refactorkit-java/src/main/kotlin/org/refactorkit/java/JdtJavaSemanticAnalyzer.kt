package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.EnumDeclaration
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.RecordDeclaration
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.nio.file.Path

/**
 * Early Eclipse JDT-backed analysis prototype for the v0.3.0 line.
 *
 * This class intentionally exposes evidence, not refactoring authority. Existing
 * lexical planners remain the safety fallback until JDT-backed behavior is wired
 * into planners and covered by semantic tests.
 */
class JdtJavaSemanticAnalyzer {
    fun analyze(snapshot: ProjectSnapshot): JdtJavaSemanticAnalysisResult {
        val sourceRoots = snapshot.modules
            .flatMap { it.sourceRoots }
            .map { snapshot.workspace.root.resolve(it).toAbsolutePath().normalize().toString() }
            .distinct()
            .toTypedArray()
        val fileAnalyses = snapshot.files
            .filter { it.languageId == "java" }
            .map { analyzeFileWithReferences(it, sourceRoots) }
        val symbols = fileAnalyses.flatMap { it.symbols }
        val symbolsByKey = symbols.mapNotNull { symbol -> symbol.bindingKey?.let { it to symbol } }.toMap()
        val references = fileAnalyses.flatMap { analysis ->
            analysis.rawReferences.mapNotNull { raw ->
                val target = raw.bindingKey?.let(symbolsByKey::get) ?: return@mapNotNull null
                JdtJavaSemanticReference(
                    simpleName = raw.simpleName,
                    symbolQualifiedName = target.qualifiedName,
                    symbolKind = target.kind,
                    symbolSignature = target.memberSignature,
                    path = raw.path,
                    line = raw.line,
                    sourceRange = raw.sourceRange,
                    bindingKey = raw.bindingKey,
                    evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                )
            }
        }
        val warnings = fileAnalyses.flatMap { it.warnings }
        val overrideRelations = buildOverrideRelations(
            fileAnalyses.flatMap { it.methodBindings },
            fileAnalyses.flatMap { it.inheritances },
        )
        return JdtJavaSemanticAnalysisResult(symbols, references, warnings, overrideRelations)
    }

    fun analyzeFile(file: SourceFile): List<JdtJavaSemanticSymbol> =
        analyzeFileWithReferences(file, emptyArray()).symbols

    private fun analyzeFileWithReferences(file: SourceFile, sourceRoots: Array<String>): FileAnalysis {
        if (file.languageId != "java") return FileAnalysis(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val compilationUnit = parse(file, sourceRoots)
        val packageName = compilationUnit.`package`?.name?.fullyQualifiedName ?: JavaPackageUtil.extractPackage(file.content)
        val symbols = mutableListOf<JdtJavaSemanticSymbol>()
        val rawReferences = mutableListOf<RawReference>()
        val methodBindings = mutableListOf<MethodBindingRecord>()
        val inheritances = mutableListOf<TypeInheritanceRecord>()
        val ownerStack = mutableListOf<String>()

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
                )
                symbols += typeSymbol
                inheritances += typeInheritanceRecords(node, packageName, typeSymbol.qualifiedName)
                ownerStack += typeSymbol.qualifiedName
                return true
            }

            override fun endVisit(node: TypeDeclaration) {
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
                )
                symbols += recordSymbol
                inheritances += recordInheritanceRecords(node, packageName, recordSymbol.qualifiedName)
                ownerStack += recordSymbol.qualifiedName
                return true
            }

            override fun endVisit(node: RecordDeclaration) {
                if (ownerStack.isNotEmpty()) ownerStack.removeAt(ownerStack.lastIndex)
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
                )
                symbols += methodSymbol
                if (binding != null && !node.isConstructor) {
                    methodBindings += MethodBindingRecord(methodSymbol, binding)
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
                    )
                }
                return true
            }

            override fun visit(node: ClassInstanceCreation): Boolean {
                val binding = node.resolveConstructorBinding() ?: return true
                rawReferences += RawReference(
                    simpleName = binding.declaringClass?.name ?: node.type.toString(),
                    path = file.path,
                    line = (compilationUnit.getLineNumber(node.startPosition) - 1).coerceAtLeast(0),
                    sourceRange = rangeFor(compilationUnit, node.type.startPosition, node.type.length),
                    bindingKey = binding.key,
                )
                return true
            }

            override fun visit(node: SimpleName): Boolean {
                if (node.isDeclarationName()) return true
                rawReferences += RawReference(
                    simpleName = node.identifier,
                    path = file.path,
                    line = (compilationUnit.getLineNumber(node.startPosition) - 1).coerceAtLeast(0),
                    sourceRange = rangeFor(compilationUnit, node.startPosition, node.length),
                    bindingKey = node.resolveBinding()?.key,
                )
                return true
            }
        })

        val warnings = compilationUnit.problems
            .filter { it.isError }
            .map { problem ->
                JdtJavaSemanticWarning(
                    path = file.path,
                    line = (problem.sourceLineNumber - 1).coerceAtLeast(0),
                    message = problem.message,
                    evidence = JdtJavaSemanticEvidence.JDT_PARSE,
                )
            }
        return FileAnalysis(symbols, rawReferences, warnings, methodBindings, inheritances)
    }

    private fun buildOverrideRelations(
        methods: List<MethodBindingRecord>,
        inheritances: List<TypeInheritanceRecord>,
    ): List<JdtJavaSemanticOverrideRelation> {
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
            for (superMethod in methods) {
                if (sub === superMethod) continue
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
                            sub.binding.isSubsignature(superMethod.binding)
                    }.getOrDefault(false) ||
                    (inheritsFrom(sub.symbol.ownerQualifiedName, superMethod.symbol.ownerQualifiedName) &&
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
        return relations
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
        return superTypes.distinct().map { supertype ->
            TypeInheritanceRecord(subtypeQualifiedName, supertype)
        }
    }

    private fun recordInheritanceRecords(
        node: RecordDeclaration,
        packageName: String,
        subtypeQualifiedName: String,
    ): List<TypeInheritanceRecord> {
        val superTypes = mutableListOf<String>()
        node.superInterfaceTypes().forEach { rawType ->
            val type = rawType as? org.eclipse.jdt.core.dom.Type ?: return@forEach
            superTypes += type.resolveBinding()?.qualifiedName?.takeIf { it.isNotBlank() }
                ?: qualifyTypeName(packageName, type.toString())
        }
        return superTypes.distinct().map { supertype ->
            TypeInheritanceRecord(subtypeQualifiedName, supertype)
        }
    }

    private fun qualifyTypeName(packageName: String, name: String): String =
        if (name.contains('.')) name else JavaPackageUtil.fqn(packageName, name)

    private fun parse(file: SourceFile, sourceRoots: Array<String>): CompilationUnit {
        val parser = ASTParser.newParser(AST.JLS21)
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setSource(file.content.toCharArray())
        parser.setUnitName(file.path.toString().replace('\\', '/'))
        parser.setCompilerOptions(JavaCore.getOptions().also { JavaCore.setComplianceOptions(JavaCore.VERSION_21, it) })
        if (sourceRoots.isNotEmpty()) {
            parser.setEnvironment(emptyArray(), sourceRoots, null, true)
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
            is EnumDeclaration -> parent.name === this
            is RecordDeclaration -> parent.name === this
            is MethodDeclaration -> parent.name === this
            is VariableDeclarationFragment -> parent.name === this
            is SingleVariableDeclaration -> parent.name === this
            else -> false
        }
    }

    private data class FileAnalysis(
        val symbols: List<JdtJavaSemanticSymbol>,
        val rawReferences: List<RawReference>,
        val warnings: List<JdtJavaSemanticWarning>,
        val methodBindings: List<MethodBindingRecord>,
        val inheritances: List<TypeInheritanceRecord>,
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
    )
}

data class JdtJavaSemanticAnalysisResult(
    val symbols: List<JdtJavaSemanticSymbol>,
    val references: List<JdtJavaSemanticReference> = emptyList(),
    val warnings: List<JdtJavaSemanticWarning> = emptyList(),
    val overrideRelations: List<JdtJavaSemanticOverrideRelation> = emptyList(),
)

data class JdtJavaSemanticSymbol(
    val simpleName: String,
    val qualifiedName: String,
    val kind: JdtJavaSemanticSymbolKind,
    val path: Path,
    val line: Int,
    val ownerQualifiedName: String?,
    val memberSignature: String?,
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

data class JdtJavaSemanticWarning(
    val path: Path,
    val line: Int,
    val message: String,
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
    METHOD,
    FIELD,
    CONSTRUCTOR,
}

enum class JdtJavaSemanticEvidence {
    JDT_BINDING,
    JDT_PARSE,
    LEXICAL_FALLBACK,
}
