package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.EnumDeclaration
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
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
                if (target.path == raw.path && target.line == raw.line && target.simpleName == raw.simpleName) return@mapNotNull null
                JdtJavaSemanticReference(
                    simpleName = raw.simpleName,
                    symbolQualifiedName = target.qualifiedName,
                    symbolKind = target.kind,
                    path = raw.path,
                    line = raw.line,
                    bindingKey = raw.bindingKey,
                    evidence = JdtJavaSemanticEvidence.JDT_BINDING,
                )
            }
        }
        return JdtJavaSemanticAnalysisResult(symbols, references)
    }

    fun analyzeFile(file: SourceFile): List<JdtJavaSemanticSymbol> =
        analyzeFileWithReferences(file, emptyArray()).symbols

    private fun analyzeFileWithReferences(file: SourceFile, sourceRoots: Array<String>): FileAnalysis {
        if (file.languageId != "java") return FileAnalysis(emptyList(), emptyList())
        val compilationUnit = parse(file, sourceRoots)
        val packageName = compilationUnit.`package`?.name?.fullyQualifiedName ?: JavaPackageUtil.extractPackage(file.content)
        val symbols = mutableListOf<JdtJavaSemanticSymbol>()
        val rawReferences = mutableListOf<RawReference>()
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
                    startPosition = node.startPosition,
                    bindingQualifiedName = binding?.qualifiedName,
                    bindingKey = binding?.key,
                )
                symbols += typeSymbol
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
                    startPosition = node.startPosition,
                    bindingQualifiedName = binding?.qualifiedName,
                    bindingKey = binding?.key,
                )
                symbols += enumSymbol
                ownerStack += enumSymbol.qualifiedName
                return true
            }

            override fun endVisit(node: EnumDeclaration) {
                if (ownerStack.isNotEmpty()) ownerStack.removeAt(ownerStack.lastIndex)
            }

            override fun visit(node: MethodDeclaration): Boolean {
                val owner = ownerStack.lastOrNull() ?: JavaPackageUtil.fqn(packageName, file.path.fileName.toString().removeSuffix(".java"))
                val binding = node.resolveBinding()
                symbols += symbol(
                    file = file,
                    compilationUnit = compilationUnit,
                    packageName = packageName,
                    ownerQualifiedName = owner,
                    simpleName = node.name.identifier,
                    kind = if (node.isConstructor) JdtJavaSemanticSymbolKind.CONSTRUCTOR else JdtJavaSemanticSymbolKind.METHOD,
                    startPosition = node.startPosition,
                    bindingQualifiedName = null,
                    bindingKey = binding?.key,
                )
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
                        startPosition = fragment.startPosition,
                        bindingQualifiedName = null,
                        bindingKey = binding?.key,
                    )
                }
                return true
            }

            override fun visit(node: SimpleName): Boolean {
                rawReferences += RawReference(
                    simpleName = node.identifier,
                    path = file.path,
                    line = (compilationUnit.getLineNumber(node.startPosition) - 1).coerceAtLeast(0),
                    bindingKey = node.resolveBinding()?.key,
                )
                return true
            }
        })

        return FileAnalysis(symbols, rawReferences)
    }

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
    ): JdtJavaSemanticSymbol {
        val qualifiedName = bindingQualifiedName
            ?.takeIf { it.isNotBlank() }
            ?: when (kind) {
                JdtJavaSemanticSymbolKind.METHOD,
                JdtJavaSemanticSymbolKind.FIELD,
                JdtJavaSemanticSymbolKind.CONSTRUCTOR -> "${ownerQualifiedName ?: JavaPackageUtil.fqn(packageName, simpleName)}#$simpleName"
                else -> JavaPackageUtil.fqn(packageName, simpleName)
            }
        return JdtJavaSemanticSymbol(
            simpleName = simpleName,
            qualifiedName = qualifiedName,
            kind = kind,
            path = file.path,
            line = (compilationUnit.getLineNumber(startPosition) - 1).coerceAtLeast(0),
            bindingKey = bindingKey,
            evidence = if (bindingKey.isNullOrBlank()) {
                JdtJavaSemanticEvidence.JDT_PARSE
            } else {
                JdtJavaSemanticEvidence.JDT_BINDING
            },
        )
    }

    private data class FileAnalysis(
        val symbols: List<JdtJavaSemanticSymbol>,
        val rawReferences: List<RawReference>,
    )

    private data class RawReference(
        val simpleName: String,
        val path: Path,
        val line: Int,
        val bindingKey: String?,
    )
}

data class JdtJavaSemanticAnalysisResult(
    val symbols: List<JdtJavaSemanticSymbol>,
    val references: List<JdtJavaSemanticReference> = emptyList(),
)

data class JdtJavaSemanticSymbol(
    val simpleName: String,
    val qualifiedName: String,
    val kind: JdtJavaSemanticSymbolKind,
    val path: Path,
    val line: Int,
    val bindingKey: String?,
    val evidence: JdtJavaSemanticEvidence,
)

data class JdtJavaSemanticReference(
    val simpleName: String,
    val symbolQualifiedName: String,
    val symbolKind: JdtJavaSemanticSymbolKind,
    val path: Path,
    val line: Int,
    val bindingKey: String?,
    val evidence: JdtJavaSemanticEvidence,
)

enum class JdtJavaSemanticSymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    METHOD,
    FIELD,
    CONSTRUCTOR,
}

enum class JdtJavaSemanticEvidence {
    JDT_BINDING,
    JDT_PARSE,
    LEXICAL_FALLBACK,
}
