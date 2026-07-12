package org.refactorkit.java

import org.refactorkit.core.CodeSelection
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.LanguageAdapter
import org.refactorkit.core.ParseResult
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Reference
import org.refactorkit.core.RefactoringDescriptor
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SymbolIndex
import org.refactorkit.core.SymbolResolution
import org.refactorkit.core.TextEdit
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class JavaLanguageAdapter : LanguageAdapter {
    @Volatile private var lastSnapshot: ProjectSnapshot? = null

    override fun languageId(): String = "java"

    override fun parse(file: SourceFile): ParseResult = ParseResult(file)

    override fun buildSymbols(project: ProjectSnapshot): SymbolIndex {
        lastSnapshot = project
        val symbols = project.files
            .filter { it.languageId == "java" }
            .flatMap { file -> discoverSymbols(file) }
        return SymbolIndex(symbols)
    }

    override fun resolveSymbol(location: SourceLocation): SymbolResolution {
        val project = lastSnapshot ?: return SymbolResolution(
            symbol = null,
            diagnostics = listOf(Diagnostic(
                message = "No project snapshot available; buildSymbols(project) or diagnostics(project) must be called first",
                severity = Diagnostic.Severity.WARNING,
                location = location,
                code = "java.noSnapshot",
            )),
        )
        return resolveSymbol(project, location)
    }

    fun resolveSymbol(project: ProjectSnapshot, location: SourceLocation): SymbolResolution {
        lastSnapshot = project
        val file = project.files.find { it.path == location.path } ?: return SymbolResolution(
            symbol = null,
            diagnostics = listOf(Diagnostic(
                message = "File not found in project snapshot: ${location.path}",
                severity = Diagnostic.Severity.WARNING,
                location = location,
                code = "java.fileNotInSnapshot",
            )),
        )
        findJdtSymbolAtLocation(project, location)?.let { return SymbolResolution(it) }
        val word = wordAt(file.content, location.range.start.line, location.range.start.character)
            ?: return SymbolResolution(symbol = null)
        val symbols = buildSymbols(project).symbols

        symbols.firstOrNull { isDefinitionWord(it, location, word) }?.let { return SymbolResolution(it) }

        val filePackage = JavaPackageUtil.extractPackage(file.content)
        val importedFqns = JavaLexer.extractImports(file.content)
            .filterNot { it.isStatic || it.name.endsWith(".*") }
            .associateBy { it.name.substringAfterLast('.') }
        val importedSymbol = importedFqns[word]?.name?.let { fqn -> symbols.firstOrNull { it.id.value == fqn } }
        if (importedSymbol != null) return SymbolResolution(importedSymbol)

        val samePackageType = symbols.firstOrNull { it.kind in TYPE_KINDS && it.id.value == JavaPackageUtil.fqn(filePackage, word) }
        if (samePackageType != null) return SymbolResolution(samePackageType)

        val sameFileMember = symbols.firstOrNull { it.location.path == file.path && it.name == word && it.kind !in TYPE_KINDS }
        if (sameFileMember != null) return SymbolResolution(sameFileMember)

        val byName = symbols.filter { it.name == word }
        return when (byName.size) {
            0 -> SymbolResolution(symbol = null)
            1 -> SymbolResolution(symbol = byName.single())
            else -> SymbolResolution(
                symbol = byName.first(),
                diagnostics = listOf(Diagnostic(
                    message = "Ambiguous Java symbol '$word'; returning first of ${byName.size} candidates",
                    severity = Diagnostic.Severity.WARNING,
                    location = location,
                    code = "java.ambiguousSymbol",
                )),
            )
        }
    }

    override fun findReferences(symbolId: SymbolId): List<Reference> =
        lastSnapshot?.let { findReferences(it, symbolId) } ?: emptyList()

    fun searchSymbols(project: ProjectSnapshot, query: String): List<Symbol> {
        lastSnapshot = project
        val combined = (buildSymbols(project).symbols + signedJdtMemberSymbols(project))
            .distinctBy { it.id.value }
        return if (query.isBlank()) {
            combined
        } else {
            combined.filter { symbol ->
                symbol.name.contains(query, ignoreCase = true) ||
                    symbol.id.value.contains(query, ignoreCase = true)
            }
        }
    }

    fun findSymbol(project: ProjectSnapshot, symbolId: SymbolId): Symbol? {
        lastSnapshot = project
        val lexicalSymbol = buildSymbols(project).symbols.find { it.id == symbolId }
        if (lexicalSymbol != null) return lexicalSymbol
        return findJdtSignedSymbol(project, symbolId)
    }

    fun findReferences(project: ProjectSnapshot, symbolId: SymbolId): List<Reference> {
        lastSnapshot = project
        findJdtSignedReferences(project, symbolId)?.let { return it }
        val index = buildSymbols(project)
        val symbol = index.symbols.find { it.id == symbolId } ?: return emptyList()
        val simpleName = symbol.name
        val references = mutableListOf<Reference>()

        if (symbol.kind in TYPE_KINDS) {
            val packageName = JavaPackageUtil.packageOf(symbol.id.value)
            for (file in project.files.filter { it.languageId == "java" }) {
                val filePackage = JavaPackageUtil.extractPackage(file.content)
                val hasDirectImport = file.content.contains("import ${symbol.id.value};")
                val isSamePackage = packageName.isNotEmpty() && filePackage == packageName
                val isDeclarationFile = file.path == symbol.location.path

                for (range in JavaLexer.findOccurrences(file.content, symbol.id.value)) {
                    references += referenceFor(file, symbol.id, range)
                }

                if (hasDirectImport || isSamePackage || isDeclarationFile) {
                    for (range in JavaLexer.findOccurrences(file.content, simpleName)) {
                        val ref = referenceFor(file, symbol.id, range)
                        if (!isDefinitionLocation(ref.location, symbol.location)) {
                            references += ref
                        }
                    }
                }
            }
        } else {
            // Member symbol — scope search to files that reference the declaring type.
            // This eliminates false positives from same-name methods in unrelated classes.
            val ownerFqn = symbol.id.value.substringBefore('#')
            val ownerPkg = JavaPackageUtil.packageOf(ownerFqn)
            val ownerSimple = JavaPackageUtil.simpleName(ownerFqn)
            val ownerPkgStar = "import ${ownerPkg}.*;"

            for (file in project.files.filter { it.languageId == "java" }) {
                val filePkg = JavaPackageUtil.extractPackage(file.content)
                val inScope = file.path == symbol.location.path ||
                    file.content.contains("import $ownerFqn;") ||
                    (ownerPkg.isNotEmpty() && file.content.contains(ownerPkgStar)) ||
                    (ownerPkg.isNotEmpty() && filePkg == ownerPkg) ||
                    file.content.contains(ownerFqn) ||
                    file.content.contains(ownerSimple)

                if (!inScope) continue

                for (range in JavaLexer.findOccurrences(file.content, simpleName)) {
                    val ref = referenceFor(file, symbol.id, range)
                    if (!isDefinitionLocation(ref.location, symbol.location)) {
                        references += ref
                    }
                }
            }
        }

        return references.distinctBy {
            "${it.location.path}:${it.location.range.start.line}:${it.location.range.start.character}:${it.location.range.end.character}"
        }.sortedWith(compareBy<Reference> { it.location.path.toString() }
            .thenBy { it.location.range.start.line }
            .thenBy { it.location.range.start.character })
    }

    private fun findJdtSignedSymbol(project: ProjectSnapshot, symbolId: SymbolId): Symbol? {
        if (!symbolId.value.isSignedMemberId()) return null
        val analysis = JdtJavaSemanticAnalyzer().analyze(project)
        if (analysis.warnings.isNotEmpty()) return null
        val semanticSymbol = analysis.symbols.singleOrNull { it.qualifiedName == symbolId.value } ?: return null
        if (semanticSymbol.bindingKey.isNullOrBlank()) return null
        return semanticSymbol.toCoreSymbol()
    }

    private fun findJdtSymbolAtLocation(project: ProjectSnapshot, location: SourceLocation): Symbol? {
        val analysis = JdtJavaSemanticAnalyzer().analyze(project)
        if (analysis.warnings.isNotEmpty()) return null
        analysis.symbols.firstOrNull { symbol ->
            symbol.path == location.path &&
                !symbol.bindingKey.isNullOrBlank() &&
                symbol.sourceRange.contains(location.range.start)
        }?.let { return it.toCoreSymbol() }

        val reference = analysis.references.firstOrNull { reference ->
            reference.path == location.path &&
                !reference.bindingKey.isNullOrBlank() &&
                reference.sourceRange.contains(location.range.start)
        } ?: return null
        val target = analysis.symbols.singleOrNull { it.bindingKey == reference.bindingKey } ?: return null
        if (target.bindingKey.isNullOrBlank()) return null
        return target.toCoreSymbol()
    }

    private fun signedJdtMemberSymbols(project: ProjectSnapshot): List<Symbol> {
        val analysis = JdtJavaSemanticAnalyzer().analyze(project)
        if (analysis.warnings.isNotEmpty()) return emptyList()
        return analysis.symbols
            .filter { symbol ->
                symbol.kind in setOf(JdtJavaSemanticSymbolKind.METHOD, JdtJavaSemanticSymbolKind.CONSTRUCTOR) &&
                    !symbol.bindingKey.isNullOrBlank() &&
                    symbol.memberSignature?.contains('(') == true
            }
            .map { it.toCoreSymbol() }
    }

    private fun findJdtSignedReferences(project: ProjectSnapshot, symbolId: SymbolId): List<Reference>? {
        if (!symbolId.value.isSignedMemberId()) return null
        val analysis = JdtJavaSemanticAnalyzer().analyze(project)
        val semanticSymbol = analysis.symbols.singleOrNull { it.qualifiedName == symbolId.value } ?: return null
        val bindingKey = semanticSymbol.bindingKey ?: return null
        return analysis.references
            .filter { it.bindingKey == bindingKey }
            .map { Reference(SymbolId(semanticSymbol.qualifiedName), SourceLocation(it.path, it.sourceRange)) }
            .distinctBy {
                "${it.location.path}:${it.location.range.start.line}:${it.location.range.start.character}:${it.location.range.end.character}"
            }.sortedWith(compareBy<Reference> { it.location.path.toString() }
                .thenBy { it.location.range.start.line }
                .thenBy { it.location.range.start.character })
    }

    private fun String.isSignedMemberId(): Boolean = contains('#') && contains('(') && endsWith(')')

    private fun SourceRange.contains(position: SourcePosition): Boolean = start <= position && position < end

    private fun JdtJavaSemanticSymbol.toCoreSymbol(): Symbol = Symbol(
        id = SymbolId(qualifiedName),
        name = simpleName,
        kind = when (kind) {
            JdtJavaSemanticSymbolKind.CLASS -> Symbol.Kind.CLASS
            JdtJavaSemanticSymbolKind.INTERFACE -> Symbol.Kind.INTERFACE
            JdtJavaSemanticSymbolKind.ENUM -> Symbol.Kind.ENUM
            JdtJavaSemanticSymbolKind.RECORD -> Symbol.Kind.RECORD
            JdtJavaSemanticSymbolKind.ANNOTATION -> Symbol.Kind.ANNOTATION
            JdtJavaSemanticSymbolKind.METHOD -> Symbol.Kind.METHOD
            JdtJavaSemanticSymbolKind.FIELD -> Symbol.Kind.FIELD
            JdtJavaSemanticSymbolKind.CONSTRUCTOR -> Symbol.Kind.CONSTRUCTOR
        },
        location = SourceLocation(path, sourceRange),
        languageId = "java",
    )

    override fun diagnostics(project: ProjectSnapshot): List<Diagnostic> {
        lastSnapshot = project
        val diagnostics = mutableListOf<Diagnostic>()
        val semanticAnalysis = analyzeDiagnosticsOverlay(project)
        diagnostics += semanticAnalysis.warnings.map { warning ->
            val syntax = warning.category == JdtJavaDiagnosticCategory.SYNTAX
            Diagnostic(
                message = warning.message,
                severity = Diagnostic.Severity.ERROR,
                location = SourceLocation(warning.path, warning.sourceRange),
                code = if (syntax) "java.jdt.syntax" else "java.jdt.typeResolution",
                evidence = DiagnosticEvidence.COMPILER,
                category = if (syntax) DiagnosticCategory.SYNTAX else DiagnosticCategory.TYPE_RESOLUTION,
            )
        }
        val symbols = buildSymbols(project).symbols

        symbols.filter { it.kind in TYPE_KINDS }
            .groupBy { it.id.value }
            .filterValues { it.size > 1 }
            .forEach { (id, duplicates) ->
                duplicates.forEach { symbol ->
                    diagnostics += Diagnostic(
                        message = "Duplicate Java symbol: $id",
                        severity = Diagnostic.Severity.ERROR,
                        location = symbol.location,
                        code = "java.duplicateSymbol",
                        evidence = DiagnosticEvidence.STRUCTURAL,
                        category = DiagnosticCategory.PROJECT_STRUCTURE,
                    )
                }
            }

        val sourceRoots = project.modules.flatMap { it.sourceRoots }.distinct()
        for (file in project.files.filter { it.languageId == "java" }) {
            val declaredPackage = JavaPackageUtil.extractPackage(file.content)
            val sourceRoot = JavaPackageUtil.detectSourceRoot(file.path, sourceRoots)
            if (sourceRoot != null) {
                val pathPackage = JavaPackageUtil.pathToPackage(file.path, sourceRoot)
                if (declaredPackage != pathPackage) {
                    diagnostics += Diagnostic(
                        message = "Package declaration '$declaredPackage' does not match source path '$pathPackage' in ${file.path}",
                        severity = Diagnostic.Severity.WARNING,
                        location = startOfFile(file),
                        code = "java.packagePathMismatch",
                        evidence = DiagnosticEvidence.STRUCTURAL,
                        category = DiagnosticCategory.PROJECT_STRUCTURE,
                    )
                }
            }

            val publicTypes = discoverSymbols(file).filter { symbol ->
                symbol.kind in TYPE_KINDS && isPublicTypeDeclaration(file.content, symbol.name)
            }
            for (symbol in publicTypes) {
                val expected = "${symbol.name}.java"
                if (file.path.fileName.toString() != expected) {
                    diagnostics += Diagnostic(
                        message = "Public Java type '${symbol.name}' should be declared in $expected",
                        severity = Diagnostic.Severity.ERROR,
                        location = symbol.location,
                        code = "java.publicTypeFileNameMismatch",
                        evidence = DiagnosticEvidence.STRUCTURAL,
                        category = DiagnosticCategory.PROJECT_STRUCTURE,
                    )
                }
            }
        }

        return diagnostics
    }

    private fun analyzeDiagnosticsOverlay(project: ProjectSnapshot): JdtJavaSemanticAnalysisResult {
        val overlayRoot = Files.createTempDirectory("refactorkit-jdt-diagnostics-")
        return try {
            project.files.filter { it.languageId == "java" }.forEach { file ->
                val target = overlayRoot.resolve(file.path).normalize()
                require(target.startsWith(overlayRoot)) { "Diagnostic overlay path escapes root: ${file.path}" }
                Files.createDirectories(requireNotNull(target.parent))
                Files.writeString(target, file.content)
            }
            val originalRoot = project.workspace.root.toAbsolutePath().normalize()
            val modules = project.modules.map { module ->
                val moduleRoot = module.root.toAbsolutePath().normalize()
                val relativeRoot = if (moduleRoot.startsWith(originalRoot)) {
                    originalRoot.relativize(moduleRoot)
                } else {
                    Path.of(module.name.replace(':', '/'))
                }
                module.copy(
                    root = overlayRoot.resolve(relativeRoot),
                    classpathEntries = module.classpathEntries.map { entry ->
                        if (entry.isAbsolute) entry else originalRoot.resolve(entry).normalize()
                    },
                )
            }
            JdtJavaSemanticAnalyzer().analyze(project.copy(
                workspace = Workspace(overlayRoot),
                modules = modules,
            ))
        } finally {
            Files.walk(overlayRoot).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    override fun availableRefactorings(selection: CodeSelection): List<RefactoringDescriptor> = listOf(
        RefactoringDescriptor("renameClass", "Rename Java class", RiskLevel.LOW),
        RefactoringDescriptor("renameMember", "Rename Java method or field", RiskLevel.LOW),
        RefactoringDescriptor("extractMethod", "Extract Java method (limited MVP)", RiskLevel.MEDIUM),
        RefactoringDescriptor("changeSignature.renameParameter", "Rename Java parameter (limited change signature)", RiskLevel.MEDIUM),
        RefactoringDescriptor("changeSignature.addParameter", "Add Java parameter with default call-site expression", RiskLevel.MEDIUM),
        RefactoringDescriptor("changeSignature.reorderParameters", "Reorder Java parameters and call-site arguments", RiskLevel.MEDIUM),
        RefactoringDescriptor("changeSignature.removeParameter", "Remove unused Java parameter and call-site argument", RiskLevel.MEDIUM),
        RefactoringDescriptor("moveClass", "Move Java class", RiskLevel.MEDIUM),
        RefactoringDescriptor("organizeImports", "Organize imports", RiskLevel.LOW),
        RefactoringDescriptor("formatFile", "Format Java compilation unit", RiskLevel.LOW),
        RefactoringDescriptor("safeDelete", "Safe delete", RiskLevel.MEDIUM),
    )

    override fun applyRefactoring(request: RefactoringRequest): PatchPlan = when (request.operation) {
        "renameClass"  -> applyRenameClass(request)
        "renameMember" -> applyRenameMember(request)
        "extractMethod" -> applyExtractMethod(request)
        "changeSignature.renameParameter", "renameParameter" -> applyRenameParameter(request)
        "changeSignature.addParameter", "addParameter" -> applyAddParameter(request)
        "changeSignature.reorderParameters", "reorderParameters" -> applyReorderParameters(request)
        "changeSignature.removeParameter", "removeParameter" -> applyRemoveParameter(request)
        "moveClass"    -> applyMoveClass(request)
        "organizeImports" -> applyOrganizeImports(request)
        "formatFile" -> applyFormatFile(request)
        "safeDelete"   -> applySafeDelete(request)
        else           -> notImplemented(request, "Unknown operation: ${request.operation}")
    }

    private fun applyRenameMember(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "renameMember requires symbolId")
        val newName = request.arguments["newName"]
            ?: return notImplemented(request, "renameMember requires arguments.newName")
        return JavaRenameMemberPlanner(this).preview(request.snapshot, symbol, newName)
    }

    private fun applyRenameClass(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "renameClass requires symbolId")
        val newName = request.arguments["newName"]
            ?: return notImplemented(request, "renameClass requires arguments.newName")
        return JavaRenameClassPlanner(this).preview(request.snapshot, symbol, newName)
    }

    private fun applyExtractMethod(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return notImplemented(request, "extractMethod requires arguments.file")
        val startLine = request.arguments["startLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractMethod requires arguments.startLine")
        val endLine = request.arguments["endLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractMethod requires arguments.endLine")
        val methodName = request.arguments["methodName"]
            ?: return notImplemented(request, "extractMethod requires arguments.methodName")
        return JavaExtractMethodPlanner().preview(request.snapshot, java.nio.file.Paths.get(file), startLine, endLine, methodName)
    }

    private fun applyRenameParameter(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "renameParameter requires symbolId")
        val oldName = request.arguments["oldName"]
            ?: request.arguments["oldParameterName"]
            ?: return notImplemented(request, "renameParameter requires arguments.oldName")
        val newName = request.arguments["newName"]
            ?: request.arguments["newParameterName"]
            ?: return notImplemented(request, "renameParameter requires arguments.newName")
        return JavaChangeSignaturePlanner(this).previewRenameParameter(request.snapshot, symbol, oldName, newName)
    }

    private fun applyAddParameter(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "addParameter requires symbolId")
        val type = request.arguments["type"]
            ?: request.arguments["parameterType"]
            ?: return notImplemented(request, "addParameter requires arguments.type")
        val name = request.arguments["name"]
            ?: request.arguments["parameterName"]
            ?: return notImplemented(request, "addParameter requires arguments.name")
        val defaultExpression = request.arguments["default"]
            ?: request.arguments["defaultExpression"]
            ?: return notImplemented(request, "addParameter requires arguments.default")
        return JavaChangeSignaturePlanner(this).previewAddParameter(request.snapshot, symbol, type, name, defaultExpression)
    }

    private fun applyReorderParameters(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "reorderParameters requires symbolId")
        val order = request.arguments["order"]
            ?: request.arguments["newOrder"]
            ?: return notImplemented(request, "reorderParameters requires arguments.order")
        return JavaChangeSignaturePlanner(this).previewReorderParameters(request.snapshot, symbol, order.split(','))
    }

    private fun applyRemoveParameter(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "removeParameter requires symbolId")
        val name = request.arguments["name"]
            ?: request.arguments["parameterName"]
            ?: return notImplemented(request, "removeParameter requires arguments.name")
        return JavaChangeSignaturePlanner(this).previewRemoveParameter(request.snapshot, symbol, name)
    }

    private fun applyMoveClass(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "moveClass requires symbolId")
        val targetPkg = request.arguments["targetPackage"]
            ?: return notImplemented(request, "moveClass requires arguments.targetPackage")
        return JavaMoveClassPlanner(this).preview(request.snapshot, symbol, targetPkg)
    }

    private fun applyOrganizeImports(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return notImplemented(request, "organizeImports requires arguments.file")
        return JavaOrganizeImportsPlanner().previewSingleFile(request.snapshot, java.nio.file.Paths.get(file))
    }

    private fun applyFormatFile(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return notImplemented(request, "formatFile requires arguments.file")
        return JavaFormatFilePlanner(this).preview(request.snapshot, java.nio.file.Paths.get(file))
    }

    private fun applySafeDelete(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "safeDelete requires symbolId")
        val force = request.arguments["force"]?.toBoolean() ?: false
        return JavaSafeDeletePlanner(this).preview(request.snapshot, symbol, force)
    }

    private fun notImplemented(request: RefactoringRequest, reason: String) = PatchPlan(
        operation = request.operation,
        status = PatchStatus.REFUSED,
        snapshotHash = request.snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
    )

    override fun formatEdits(edits: List<TextEdit>): List<TextEdit> = edits

    private fun discoverSymbols(file: SourceFile): List<Symbol> {
        val packageName = packageRegex.find(file.content)?.groupValues?.get(1)
        val symbols = mutableListOf<Symbol>()

        topLevelTypeRegex.findAll(file.content).forEach { match ->
            if (!isTopLevelOffset(file.content, match.range.first)) return@forEach

            val kind = when (match.groupValues[1]) {
                "class" -> Symbol.Kind.CLASS
                "interface" -> Symbol.Kind.INTERFACE
                "enum" -> Symbol.Kind.ENUM
                "record" -> Symbol.Kind.RECORD
                "@interface" -> Symbol.Kind.ANNOTATION
                else -> Symbol.Kind.UNKNOWN
            }
            val name = match.groupValues[2]
            val nameOffset = match.groups[2]?.range?.first ?: match.range.first
            val id = SymbolId(listOfNotNull(packageName, name).joinToString("."))
            val typeSymbol = Symbol(
                id = id,
                name = name,
                kind = kind,
                location = SourceLocation(file.path, rangeForOffset(file.content, nameOffset, name.length)),
                languageId = "java",
            )
            symbols += typeSymbol
            symbols += discoverMembers(file, typeSymbol, match.range.last + 1)
        }
        return symbols
    }

    private fun discoverMembers(file: SourceFile, typeSymbol: Symbol, searchStart: Int): List<Symbol> {
        val body = findTypeBody(file.content, searchStart) ?: return emptyList()
        val members = mutableListOf<Symbol>()
        for (line in topLevelMemberLines(file.content, body.first + 1, body.last)) {
            val text = file.content.substring(line)
            val constructorRegex = Regex("^\\s*(?:(?:public|protected|private)\\s+)?${Regex.escape(typeSymbol.name)}\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[^{}]+)?\\{")
            constructorRegex.find(text)?.let { match ->
                val nameOffset = line.first + (match.groups[0]?.range?.first ?: 0) + text.indexOf(typeSymbol.name).coerceAtLeast(0)
                members += memberSymbol(file, typeSymbol, Symbol.Kind.CONSTRUCTOR, typeSymbol.name, "${typeSymbol.id.value}#<init>", nameOffset)
                return@let
            }

            methodRegex.find(text)?.let { match ->
                val name = match.groupValues[1]
                if (name !in CONTROL_KEYWORDS) {
                    val nameOffset = line.first + (match.groups[1]?.range?.first ?: return@let)
                    members += memberSymbol(file, typeSymbol, Symbol.Kind.METHOD, name, "${typeSymbol.id.value}#$name", nameOffset)
                }
            }

            fieldRegex.find(text)?.let { match ->
                val name = match.groupValues[1]
                val nameOffset = line.first + (match.groups[1]?.range?.first ?: return@let)
                members += memberSymbol(file, typeSymbol, Symbol.Kind.FIELD, name, "${typeSymbol.id.value}#$name", nameOffset)
            }
        }
        return members.distinctBy { "${it.kind}:${it.id.value}:${it.location.range.start.line}:${it.location.range.start.character}" }
    }

    private fun memberSymbol(
        file: SourceFile,
        owner: Symbol,
        kind: Symbol.Kind,
        name: String,
        id: String,
        nameOffset: Int,
    ): Symbol = Symbol(
        id = SymbolId(id),
        name = name,
        kind = kind,
        location = SourceLocation(file.path, rangeForOffset(file.content, nameOffset, name.length)),
        languageId = owner.languageId,
    )

    private fun referenceFor(file: SourceFile, symbolId: SymbolId, range: IntRange): Reference = Reference(
        symbolId = symbolId,
        location = SourceLocation(file.path, rangeForOffset(file.content, range.first, range.last - range.first + 1)),
    )

    private fun isDefinitionLocation(reference: SourceLocation, definition: SourceLocation): Boolean =
        reference.path == definition.path && reference.range.start == definition.range.start

    private fun isDefinitionWord(symbol: Symbol, location: SourceLocation, word: String): Boolean =
        symbol.name == word && symbol.location.path == location.path &&
            location.range.start.line == symbol.location.range.start.line &&
            location.range.start.character in symbol.location.range.start.character..symbol.location.range.end.character

    private fun wordAt(content: String, line: Int, character: Int): String? {
        val lines = content.lines()
        if (line !in lines.indices) return null
        val lineContent = lines[line]
        if (character > lineContent.length) return null
        var start = character
        var end = character
        if (start == lineContent.length && start > 0 && JavaLexer.isIdentChar(lineContent[start - 1])) start--
        while (start > 0 && JavaLexer.isIdentChar(lineContent[start - 1])) start--
        while (end < lineContent.length && JavaLexer.isIdentChar(lineContent[end])) end++
        return if (start < end) lineContent.substring(start, end) else null
    }

    private fun startOfFile(file: SourceFile): SourceLocation = SourceLocation(
        file.path,
        SourceRange(SourcePosition(0, 0), SourcePosition(0, 0)),
    )

    private fun isPublicTypeDeclaration(content: String, name: String): Boolean =
        Regex("(?m)^\\s*public\\s+(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*\\b(?:class|interface|enum|record)\\s+${Regex.escape(name)}\\b")
            .containsMatchIn(content)

    private fun rangeForOffset(content: String, offset: Int, length: Int): SourceRange {
        var line = 0
        var lineStart = 0
        for (index in 0 until offset.coerceAtMost(content.length)) {
            if (content[index] == '\n') {
                line += 1
                lineStart = index + 1
            }
        }
        val character = offset - lineStart
        return SourceRange(SourcePosition(line, character), SourcePosition(line, character + length))
    }

    private fun findTypeBody(content: String, searchStart: Int): IntRange? {
        var i = searchStart
        while (i < content.length && content[i] != '{') i++
        if (i >= content.length) return null
        val open = i
        var depth = 0
        while (i < content.length) {
            when {
                startsLineComment(content, i) -> i = skipLineComment(content, i, content.length)
                startsBlockComment(content, i) -> i = skipBlockComment(content, i, content.length)
                content[i] == '"' -> i = skipStringLiteral(content, i, content.length)
                content[i] == '\'' -> i = skipCharLiteral(content, i, content.length)
                content[i] == '{' -> { depth++; i++ }
                content[i] == '}' -> {
                    depth--
                    if (depth == 0) return open..i
                    i++
                }
                else -> i++
            }
        }
        return null
    }

    private fun topLevelMemberLines(content: String, start: Int, endExclusive: Int): List<IntRange> {
        val lines = mutableListOf<IntRange>()
        var i = start
        var lineStart = start
        var depth = 0
        var lineDepth = 0

        fun finishLine(lineEnd: Int) {
            if (lineDepth == 0) {
                val text = content.substring(lineStart, lineEnd).trim()
                if (text.isNotEmpty() && !text.startsWith("@")) lines += lineStart until lineEnd
            }
            lineStart = lineEnd + 1
            lineDepth = depth
        }

        while (i < endExclusive) {
            when {
                content[i] == '\n' -> { finishLine(i); i++ }
                startsLineComment(content, i) -> i = skipLineComment(content, i, endExclusive)
                startsBlockComment(content, i) -> i = skipBlockComment(content, i, endExclusive)
                content[i] == '"' -> i = skipStringLiteral(content, i, endExclusive)
                content[i] == '\'' -> i = skipCharLiteral(content, i, endExclusive)
                content[i] == '{' -> { depth++; i++ }
                content[i] == '}' -> { if (depth > 0) depth--; i++ }
                else -> i++
            }
        }
        if (lineStart < endExclusive && lineDepth == 0) lines += lineStart until endExclusive
        return lines
    }

    private fun isTopLevelOffset(content: String, offset: Int): Boolean {
        var i = 0
        var depth = 0
        val limit = offset.coerceAtMost(content.length)
        while (i < limit) {
            when {
                startsLineComment(content, i) -> i = skipLineComment(content, i, limit)
                startsBlockComment(content, i) -> i = skipBlockComment(content, i, limit)
                content[i] == '"' -> i = skipStringLiteral(content, i, limit)
                content[i] == '\'' -> i = skipCharLiteral(content, i, limit)
                content[i] == '{' -> { depth++; i++ }
                content[i] == '}' -> { if (depth > 0) depth--; i++ }
                else -> i++
            }
        }
        return depth == 0
    }

    private fun startsLineComment(content: String, offset: Int): Boolean =
        offset + 1 < content.length && content[offset] == '/' && content[offset + 1] == '/'

    private fun startsBlockComment(content: String, offset: Int): Boolean =
        offset + 1 < content.length && content[offset] == '/' && content[offset + 1] == '*'

    private fun skipLineComment(content: String, start: Int, limit: Int): Int {
        val nl = content.indexOf('\n', start)
        return if (nl < 0 || nl >= limit) limit else nl
    }

    private fun skipBlockComment(content: String, start: Int, limit: Int): Int {
        val end = content.indexOf("*/", start + 2)
        return if (end < 0 || end + 2 >= limit) limit else end + 2
    }

    private fun skipStringLiteral(content: String, start: Int, limit: Int): Int {
        var i = start + 1
        return if (i + 1 < limit && content[i] == '"' && content[i + 1] == '"') {
            i += 2
            while (i + 2 < limit) {
                if (content[i] == '"' && content[i + 1] == '"' && content[i + 2] == '"') return i + 3
                i++
            }
            limit
        } else {
            while (i < limit && content[i] != '"' && content[i] != '\n') {
                if (content[i] == '\\') i++
                i++
            }
            if (i < limit) i + 1 else i
        }
    }

    private fun skipCharLiteral(content: String, start: Int, limit: Int): Int {
        var i = start + 1
        while (i < limit && content[i] != '\'' && content[i] != '\n') {
            if (content[i] == '\\') i++
            i++
        }
        return if (i < limit) i + 1 else i
    }

    companion object {
        private val packageRegex = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;")
        private val topLevelTypeRegex = Regex("(?m)^\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*(class|interface|enum|record|@interface)\\s+([A-Za-z_][A-Za-z0-9_]*)")
        private val methodRegex = Regex("^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|default)\\s+)*(?:<[^>]+>\\s*)?[A-Za-z_$][\\w$<>,.?\\[\\] ]*\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[^{}]+)?(?:\\{|;)")
        private val fieldRegex = Regex("^\\s*(?:(?:public|protected|private|static|final|volatile|transient)\\s+)*(?!class\\b|interface\\b|enum\\b|record\\b|@interface\\b)(?:[A-Za-z_$][\\w$<>,.?\\[\\] ]+)\\s+([A-Za-z_$][\\w$]*)\\s*(?:=[^;]*)?;")
        private val CONTROL_KEYWORDS = setOf("if", "for", "while", "switch", "catch", "return", "new")
        private val TYPE_KINDS = setOf(Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.ENUM, Symbol.Kind.RECORD, Symbol.Kind.ANNOTATION)
    }
}
