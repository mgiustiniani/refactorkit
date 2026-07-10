package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.EnumDeclaration
import org.eclipse.jdt.core.dom.TypeDeclaration
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
        val symbols = snapshot.files
            .filter { it.languageId == "java" }
            .flatMap(::analyzeFile)
        return JdtJavaSemanticAnalysisResult(symbols)
    }

    fun analyzeFile(file: SourceFile): List<JdtJavaSemanticSymbol> {
        if (file.languageId != "java") return emptyList()
        val parser = ASTParser.newParser(AST.JLS21)
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setSource(file.content.toCharArray())
        parser.setUnitName(file.path.fileName?.toString() ?: "Source.java")
        parser.setCompilerOptions(JavaCore.getOptions().also { JavaCore.setComplianceOptions(JavaCore.VERSION_21, it) })
        parser.setResolveBindings(true)
        parser.setBindingsRecovery(true)
        parser.setStatementsRecovery(true)

        val compilationUnit = parser.createAST(null) as CompilationUnit
        val packageName = compilationUnit.`package`?.name?.fullyQualifiedName ?: JavaPackageUtil.extractPackage(file.content)
        val symbols = mutableListOf<JdtJavaSemanticSymbol>()

        compilationUnit.accept(object : ASTVisitor() {
            override fun visit(node: TypeDeclaration): Boolean {
                val binding = node.resolveBinding()
                symbols += symbol(
                    file = file,
                    compilationUnit = compilationUnit,
                    packageName = packageName,
                    simpleName = node.name.identifier,
                    kind = if (node.isInterface) JdtJavaSemanticSymbolKind.INTERFACE else JdtJavaSemanticSymbolKind.CLASS,
                    startPosition = node.startPosition,
                    bindingQualifiedName = binding?.qualifiedName,
                    bindingKey = binding?.key,
                )
                return true
            }

            override fun visit(node: EnumDeclaration): Boolean {
                val binding = node.resolveBinding()
                symbols += symbol(
                    file = file,
                    compilationUnit = compilationUnit,
                    packageName = packageName,
                    simpleName = node.name.identifier,
                    kind = JdtJavaSemanticSymbolKind.ENUM,
                    startPosition = node.startPosition,
                    bindingQualifiedName = binding?.qualifiedName,
                    bindingKey = binding?.key,
                )
                return true
            }
        })

        return symbols
    }

    private fun symbol(
        file: SourceFile,
        compilationUnit: CompilationUnit,
        packageName: String,
        simpleName: String,
        kind: JdtJavaSemanticSymbolKind,
        startPosition: Int,
        bindingQualifiedName: String?,
        bindingKey: String?,
    ): JdtJavaSemanticSymbol {
        val qualifiedName = bindingQualifiedName
            ?.takeIf { it.isNotBlank() }
            ?: JavaPackageUtil.fqn(packageName, simpleName)
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
}

data class JdtJavaSemanticAnalysisResult(
    val symbols: List<JdtJavaSemanticSymbol>,
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

enum class JdtJavaSemanticSymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
}

enum class JdtJavaSemanticEvidence {
    JDT_BINDING,
    JDT_PARSE,
    LEXICAL_FALLBACK,
}
