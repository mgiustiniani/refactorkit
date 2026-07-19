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
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.SemanticHoverSection
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
import org.refactorkit.core.buildSourceRootOwnerships
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

sealed interface JavaJdtHoverProjection {
    data class Available(
        val range: SourceRange?,
        val sections: List<SemanticHoverSection>,
        val complete: Boolean,
        val warningCount: Int,
        val symbols: List<Symbol>,
        val provenanceHash: String,
    ) : JavaJdtHoverProjection
    data class Refused(val diagnostic: Diagnostic) : JavaJdtHoverProjection
}

sealed interface JavaJdtReferencesProjection {
    data class Available(
        val references: List<Reference>,
        val total: Int,
        val truncated: Boolean,
        val complete: Boolean,
        val warningCount: Int,
        val symbols: List<Symbol>,
        val provenanceHash: String,
    ) : JavaJdtReferencesProjection
    data class Refused(val diagnostic: Diagnostic) : JavaJdtReferencesProjection
}

sealed interface JavaJdtDefinitionProjection {
    data class Available(
        val location: SourceLocation?,
        val symbols: List<Symbol>,
        val provenanceHash: String,
    ) : JavaJdtDefinitionProjection
    data class Refused(val diagnostic: Diagnostic) : JavaJdtDefinitionProjection
}

class JavaLanguageAdapter(
    private val jdtCache: JdtJavaAnalysisCache = JdtJavaAnalysisCache(),
) : LanguageAdapter {
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

    fun semanticDefinition(
        project: ProjectSnapshot,
        location: SourceLocation,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): JavaJdtDefinitionProjection {
        lastSnapshot = project
        val file = project.files.singleOrNull { it.path.normalize() == location.path.normalize() && it.languageId == "java" }
            ?: return JavaJdtDefinitionProjection.Refused(Diagnostic(
                "Java definition path is not part of the saved snapshot",
                Diagnostic.Severity.ERROR,
                location,
                code = "java.definitionPathMissing",
            ))
        if (!validPosition(file.content, location.range.start)) return JavaJdtDefinitionProjection.Refused(Diagnostic(
            "Java definition position is outside the saved document",
            Diagnostic.Severity.ERROR,
            location,
            code = "java.definitionPositionInvalid",
        ))
        return try {
            val cached = jdtCache.get(project, cancellation)
            val boundSymbols = cached.analysis.symbols.filter { !it.bindingKey.isNullOrBlank() }
            val target = boundTarget(cached.analysis, boundSymbols, location)
            val symbols = boundSymbols.map { it.toCoreSymbol() }
                .distinctBy { it.id to it.location }
                .sortedWith(compareBy({ it.id.value }, { it.location.path.toString() },
                    { it.location.range.start.line }, { it.location.range.start.character }))
            JavaJdtDefinitionProjection.Available(
                target?.let { SourceLocation(it.path, it.sourceRange) },
                symbols,
                cached.provenanceHash,
            )
        } catch (_: JdtJavaAnalysisCancelledException) {
            JavaJdtDefinitionProjection.Refused(Diagnostic(
                "Java definition query was cancelled", Diagnostic.Severity.WARNING, location,
                code = "java.definitionCancelled",
            ))
        } catch (_: JdtJavaAnalysisLimitException) {
            JavaJdtDefinitionProjection.Refused(Diagnostic(
                "Java definition analysis exceeds the bounded source limit", Diagnostic.Severity.ERROR, location,
                code = "java.definitionSourceLimit",
            ))
        } catch (_: RuntimeException) {
            JavaJdtDefinitionProjection.Refused(Diagnostic(
                "Eclipse JDT definition analysis failed", Diagnostic.Severity.ERROR, location,
                code = "java.definitionUnavailable",
            ))
        }
    }

    fun semanticHover(
        project: ProjectSnapshot,
        location: SourceLocation,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): JavaJdtHoverProjection {
        lastSnapshot = project
        val file = project.files.singleOrNull { it.path.normalize() == location.path.normalize() && it.languageId == "java" }
            ?: return JavaJdtHoverProjection.Refused(Diagnostic(
                "Java hover path is not part of the saved snapshot", Diagnostic.Severity.ERROR, location,
                code = "java.hoverPathMissing",
            ))
        if (!validPosition(file.content, location.range.start)) return JavaJdtHoverProjection.Refused(Diagnostic(
            "Java hover position is outside the saved document", Diagnostic.Severity.ERROR, location,
            code = "java.hoverPositionInvalid",
        ))
        return try {
            val cached = jdtCache.get(project, cancellation)
            val boundSymbols = cached.analysis.symbols.filter { !it.bindingKey.isNullOrBlank() }
            val target = boundTarget(cached.analysis, boundSymbols, location)
            val selectedRange = boundSymbols.firstOrNull {
                it.path.normalize() == location.path.normalize() && it.sourceRange.contains(location.range.start)
            }?.sourceRange ?: cached.analysis.references.firstOrNull {
                it.path.normalize() == location.path.normalize() && it.sourceRange.contains(location.range.start)
            }?.sourceRange
            val sections = if (target == null) emptyList() else buildList {
                add(SemanticHoverSection(
                    SemanticHoverSection.Format.MARKDOWN,
                    "```java\n${target.hoverSignature}\n```",
                ))
                add(SemanticHoverSection(
                    SemanticHoverSection.Format.PLAIN_TEXT,
                    "${target.kind.name.lowercase()} ${target.qualifiedName}".take(8_192),
                ))
                target.documentation?.takeIf(String::isNotBlank)?.let { documentation ->
                    add(SemanticHoverSection(SemanticHoverSection.Format.PLAIN_TEXT, documentation))
                }
            }
            val symbols = boundSymbols.map { it.toCoreSymbol() }
                .distinctBy { it.id to it.location }
                .sortedWith(compareBy({ it.id.value }, { it.location.path.toString() },
                    { it.location.range.start.line }, { it.location.range.start.character }))
            JavaJdtHoverProjection.Available(
                selectedRange, sections, cached.analysis.warnings.isEmpty(), cached.analysis.warnings.size,
                symbols, cached.provenanceHash,
            )
        } catch (_: JdtJavaAnalysisCancelledException) {
            JavaJdtHoverProjection.Refused(Diagnostic(
                "Java hover query was cancelled", Diagnostic.Severity.WARNING, location,
                code = "java.hoverCancelled",
            ))
        } catch (_: JdtJavaAnalysisLimitException) {
            JavaJdtHoverProjection.Refused(Diagnostic(
                "Java hover analysis exceeds the bounded source limit", Diagnostic.Severity.ERROR, location,
                code = "java.hoverSourceLimit",
            ))
        } catch (_: RuntimeException) {
            JavaJdtHoverProjection.Refused(Diagnostic(
                "Eclipse JDT hover analysis failed", Diagnostic.Severity.ERROR, location,
                code = "java.hoverUnavailable",
            ))
        }
    }

    fun semanticReferences(
        project: ProjectSnapshot,
        location: SourceLocation,
        includeDeclaration: Boolean,
        limit: Int,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): JavaJdtReferencesProjection {
        require(limit in 1..org.refactorkit.core.ProtocolLimits.MAX_REFERENCE_RESULTS) {
            "Java semantic reference limit is invalid"
        }
        lastSnapshot = project
        val file = project.files.singleOrNull { it.path.normalize() == location.path.normalize() && it.languageId == "java" }
            ?: return JavaJdtReferencesProjection.Refused(Diagnostic(
                "Java references path is not part of the saved snapshot", Diagnostic.Severity.ERROR, location,
                code = "java.referencesPathMissing",
            ))
        if (!validPosition(file.content, location.range.start)) return JavaJdtReferencesProjection.Refused(Diagnostic(
            "Java references position is outside the saved document", Diagnostic.Severity.ERROR, location,
            code = "java.referencesPositionInvalid",
        ))
        return try {
            val cached = jdtCache.get(project, cancellation)
            val boundSymbols = cached.analysis.symbols.filter { !it.bindingKey.isNullOrBlank() }
            val target = boundTarget(cached.analysis, boundSymbols, location)
            val all = if (target == null) emptyList() else buildList {
                val symbolId = SymbolId(target.qualifiedName)
                if (includeDeclaration) add(Reference(symbolId, SourceLocation(target.path, target.sourceRange)))
                cached.analysis.references.filter { it.bindingKey == target.bindingKey }.forEach { use ->
                    add(Reference(symbolId, SourceLocation(use.path, use.sourceRange)))
                }
            }.distinctBy { it.location }.sortedWith(compareBy(
                { it.location.path.toString() }, { it.location.range.start.line },
                { it.location.range.start.character }, { it.location.range.end.line },
                { it.location.range.end.character },
            ))
            val symbols = boundSymbols.map { it.toCoreSymbol() }
                .distinctBy { it.id to it.location }
                .sortedWith(compareBy({ it.id.value }, { it.location.path.toString() },
                    { it.location.range.start.line }, { it.location.range.start.character }))
            JavaJdtReferencesProjection.Available(
                all.take(limit), all.size, all.size > limit,
                cached.analysis.warnings.isEmpty(), cached.analysis.warnings.size,
                symbols, cached.provenanceHash,
            )
        } catch (_: JdtJavaAnalysisCancelledException) {
            JavaJdtReferencesProjection.Refused(Diagnostic(
                "Java references query was cancelled", Diagnostic.Severity.WARNING, location,
                code = "java.referencesCancelled",
            ))
        } catch (_: JdtJavaAnalysisLimitException) {
            JavaJdtReferencesProjection.Refused(Diagnostic(
                "Java references analysis exceeds the bounded source limit", Diagnostic.Severity.ERROR, location,
                code = "java.referencesSourceLimit",
            ))
        } catch (_: RuntimeException) {
            JavaJdtReferencesProjection.Refused(Diagnostic(
                "Eclipse JDT references analysis failed", Diagnostic.Severity.ERROR, location,
                code = "java.referencesUnavailable",
            ))
        }
    }

    fun semanticCacheStatus(): JdtJavaAnalysisCacheStatus = jdtCache.status()

    fun clearSemanticCache() = jdtCache.clear()

    private fun boundTarget(
        analysis: JdtJavaSemanticAnalysisResult,
        boundSymbols: List<JdtJavaSemanticSymbol>,
        location: SourceLocation,
    ): JdtJavaSemanticSymbol? {
        boundSymbols.firstOrNull { symbol ->
            symbol.path.normalize() == location.path.normalize() && symbol.sourceRange.contains(location.range.start)
        }?.let { return it }
        val reference = analysis.references.firstOrNull { reference ->
            reference.path.normalize() == location.path.normalize() &&
                !reference.bindingKey.isNullOrBlank() && reference.sourceRange.contains(location.range.start)
        } ?: return null
        return boundSymbols.singleOrNull { it.bindingKey == reference.bindingKey }
            ?: boundSymbols.singleOrNull {
                it.qualifiedName == reference.symbolQualifiedName && it.kind == reference.symbolKind &&
                    it.memberSignature == reference.symbolSignature
            }
    }

    private fun validPosition(content: String, position: SourcePosition): Boolean {
        val lines = content.split('\n')
        return position.line in lines.indices && position.character <= lines[position.line].length
    }

    private fun findJdtSignedSymbol(project: ProjectSnapshot, symbolId: SymbolId): Symbol? {
        if (!symbolId.value.isSignedMemberId()) return null
        val analysis = jdtCache.get(project).analysis
        if (analysis.warnings.isNotEmpty()) return null
        val semanticSymbol = analysis.symbols.singleOrNull { it.qualifiedName == symbolId.value } ?: return null
        if (semanticSymbol.bindingKey.isNullOrBlank()) return null
        return semanticSymbol.toCoreSymbol()
    }

    private fun findJdtSymbolAtLocation(project: ProjectSnapshot, location: SourceLocation): Symbol? {
        val analysis = jdtCache.get(project).analysis
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
        val analysis = jdtCache.get(project).analysis
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
        val analysis = jdtCache.get(project).analysis
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

    override fun diagnostics(project: ProjectSnapshot): List<Diagnostic> = diagnostics(project, verbose = false)

    fun diagnostics(project: ProjectSnapshot, verbose: Boolean): List<Diagnostic> =
        diagnostics(project, verbose, analyzeDiagnosticsOverlay(project), emptyList(), false)

    /**
     * Release-aware diagnostics for CLI/daemon product authority. The configured
     * platform is explicit input; the running VM is never substituted.
     */
    fun authoritativeDiagnostics(project: ProjectSnapshot, platformHome: Path?, verbose: Boolean = false): List<Diagnostic> {
        val sourceSets = project.buildModels.flatMap { model -> model.modules.flatMap { module ->
            module.sourceSets.filter { sourceSet -> sourceSet.sourceRoots.any { root ->
                project.files.any { it.languageId == "java" && it.path.startsWith(root) }
            } }.map { sourceSet -> Triple(module.id, sourceSet.id, sourceSet.attributes["java.release"]?.toIntOrNull()) }
        } }
        val unavailable = mutableListOf<Diagnostic>()
        sourceSets.filter { it.third == null }.forEach { (module, sourceSet, _) ->
            unavailable += unavailableSourceSet(
                module, sourceSet, "java.platform.explicitJdkRequired",
                "No effective Maven --release is available; an explicit qualified JDK platform policy is required",
            )
        }
        if (platformHome == null) sourceSets.filter { it.third != null }.forEach { (module, sourceSet, release) ->
            unavailable += unavailableSourceSet(
                module, sourceSet, "java.platform.authorityUnavailable",
                "No explicit Java $release platform authority was configured",
            )
        }
        if (unavailable.isNotEmpty()) {
            return diagnostics(project, verbose, analyzeDiagnosticsOverlay(project), unavailable, true)
        }
        val authorities = sourceSets.mapNotNull { it.third }.distinct().associateWith { release ->
            when (val resolved = JavaReleasePlatformAuthorityResolver().resolve(requireNotNull(platformHome), release)) {
                is JavaReleasePlatformResolution.Available -> resolved.authority
                is JavaReleasePlatformResolution.Refused -> {
                    sourceSets.filter { it.third == release }.forEach { (module, sourceSet, _) ->
                        unavailable += unavailableSourceSet(module, sourceSet, resolved.code, resolved.message)
                    }
                    null
                }
            }
        }.filterValues { it != null }.mapValues { requireNotNull(it.value) }
        if (unavailable.isNotEmpty()) {
            return diagnostics(project, verbose, analyzeDiagnosticsOverlay(project), unavailable, true)
        }
        return when (val resolved = analyzeAuthoritativeDiagnosticsOverlay(project, authorities)) {
            is JdtAuthoritativeJavaAnalysisResolution.Available ->
                diagnostics(project, verbose, resolved.analysis, emptyList(), false)
            is JdtAuthoritativeJavaAnalysisResolution.Refused -> {
                sourceSets.forEach { (module, sourceSet, _) ->
                    unavailable += unavailableSourceSet(module, sourceSet, resolved.code, resolved.message)
                }
                diagnostics(project, verbose, analyzeDiagnosticsOverlay(project), unavailable, true)
            }
        }
    }

    fun filterDiagnosticsForModule(
        project: ProjectSnapshot,
        diagnostics: List<Diagnostic>,
        moduleId: String,
    ): List<Diagnostic> {
        val matches = project.buildModels.flatMap { it.modules }.filter { it.id == moduleId || it.name == moduleId }
        require(matches.size == 1) { "Java diagnostics module selector is unknown or ambiguous: $moduleId" }
        val module = matches.single()
        val roots = module.sourceSets.flatMap { it.sourceRoots }.map(Path::normalize)
        return diagnostics.filter { diagnostic ->
            diagnostic.location?.path?.normalize()?.let { path -> roots.any(path::startsWith) }
                ?: diagnostic.message.contains("source set '${module.id}:")
        }
    }

    private fun unavailableSourceSet(module: String, sourceSet: String, code: String, detail: String) = Diagnostic(
        message = "Java analysis unavailable for source set '$module:$sourceSet': $detail",
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.PROJECT_STRUCTURE,
    )

    private fun diagnostics(
        project: ProjectSnapshot,
        verbose: Boolean,
        semanticAnalysis: JdtJavaSemanticAnalysisResult,
        additionalUnavailable: List<Diagnostic>,
        suppressTypeResolution: Boolean,
    ): List<Diagnostic> {
        lastSnapshot = project
        val diagnostics = additionalUnavailable.toMutableList()
        val unavailableModules = project.modules.filter { module ->
            module.languageSettings["java.buildModel.status"] == "unavailable" ||
                module.languageSettings["java.sourceLevel.status"] == "unavailable"
        }
        unavailableModules.forEach { module ->
            val (code, message) = when {
                module.languageSettings["java.buildModel.status"] == "unavailable" ->
                    "buildModel.unavailable" to module.languageSettings["java.buildModel.message"].orEmpty()
                else -> "sourceLevel.unavailable" to module.languageSettings["java.sourceLevel.message"].orEmpty()
            }
            diagnostics += Diagnostic(
                message = "Java analysis unavailable for module '${module.name}': ${message.ifBlank { code }}",
                severity = Diagnostic.Severity.ERROR,
                code = code,
                evidence = DiagnosticEvidence.STRUCTURAL,
                category = DiagnosticCategory.PROJECT_STRUCTURE,
            )
        }
        val unavailableModuleIds = unavailableModules.map { it.name }.toSet()
        val unavailableSourceSets = project.buildModels.flatMap { model -> model.modules.flatMap { module ->
            module.sourceSets.filter { sourceSet ->
                module.id !in unavailableModuleIds &&
                    sourceSet.attributes["java.classpath.status"] == "unavailable" &&
                    sourceSet.sourceRoots.any { root ->
                        project.files.any { it.languageId == "java" && it.path.normalize().startsWith(root.normalize()) }
                    }
            }.map { sourceSet -> module to sourceSet }
        } }
        unavailableSourceSets.forEach { (module, sourceSet) ->
            diagnostics += unavailableSourceSet(
                module.id,
                sourceSet.id,
                "classpath.unavailable",
                sourceSet.attributes["java.classpath.message"] ?: "Required offline classpath artifacts are unavailable",
            )
        }
        diagnostics += semanticAnalysis.warnings.filterNot { warning ->
            isResolvedModuleExportWarning(project, warning)
        }.filter { warning ->
            (!suppressTypeResolution && verbose) || warning.category == JdtJavaDiagnosticCategory.SYNTAX ||
                (!suppressTypeResolution && unavailableModules.none { module ->
                    sourceRoots(project, module.name, module.root).any { warning.path.normalize().startsWith(it.normalize()) }
                } && unavailableSourceSets.none { (_, sourceSet) ->
                    sourceSet.sourceRoots.any { warning.path.normalize().startsWith(it.normalize()) }
                })
        }.map { warning ->
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

        val sourceRoots = if (project.buildModels.isNotEmpty()) {
            project.buildSourceRootOwnerships().map { it.root }.distinct()
        } else {
            project.modules.flatMap { it.sourceRoots }.distinct()
        }
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

    private fun isResolvedModuleExportWarning(project: ProjectSnapshot, warning: JdtJavaSemanticWarning): Boolean {
        if (warning.path.fileName.toString() != "module-info.java" ||
            !warning.message.startsWith("The package ") ||
            !warning.message.endsWith(" does not exist or is empty")) return false
        val packageName = warning.message.removePrefix("The package ").removeSuffix(" does not exist or is empty")
        val buildOwner = project.owningBuildSourceRoots(warning.path).singleOrNull()
        val ownerRoots = if (buildOwner != null) {
            buildOwner.module.sourceSets.flatMap { it.sourceRoots }.distinct()
        } else {
            val owner = project.modules.filter { module -> module.sourceRoots.any { warning.path.startsWith(it) } }
                .maxByOrNull { it.root.nameCount } ?: return false
            owner.sourceRoots
        }
        return project.files.any { file ->
            file.path != warning.path && ownerRoots.any(file.path::startsWith) &&
                JavaPackageUtil.extractPackage(file.content) == packageName
        }
    }

    private fun sourceRoots(project: ProjectSnapshot, moduleName: String, moduleRoot: Path): List<Path> {
        if (project.buildModels.isNotEmpty()) {
            return project.buildModels.asSequence().flatMap { it.modules.asSequence() }
                .firstOrNull { it.id == moduleName && it.root.normalize() == moduleRoot.normalize() }
                ?.sourceSets?.flatMap { it.sourceRoots }.orEmpty()
        }
        return project.modules.firstOrNull { it.name == moduleName && it.root.normalize() == moduleRoot.normalize() }
            ?.sourceRoots.orEmpty()
    }

    private fun analyzeDiagnosticsOverlay(project: ProjectSnapshot): JdtJavaSemanticAnalysisResult =
        withDiagnosticsOverlay(project, JdtJavaSemanticAnalyzer()::analyze)

    internal fun analyzeAuthoritativeDiagnosticsOverlay(
        project: ProjectSnapshot,
        authorities: Map<Int, JavaReleasePlatformAuthority>,
    ): JdtAuthoritativeJavaAnalysisResolution = withDiagnosticsOverlay(project) { overlay ->
        JdtJavaSemanticAnalyzer().analyzeAuthoritatively(overlay, authorities)
    }

    private fun <T> withDiagnosticsOverlay(project: ProjectSnapshot, analyze: (ProjectSnapshot) -> T): T {
        val overlayRoot = Files.createTempDirectory("refactorkit-jdt-diagnostics-")
        return try {
            project.files.filter { it.languageId == "java" }.forEach { file ->
                val target = overlayRoot.resolve(file.path).normalize()
                require(target.startsWith(overlayRoot)) { "Diagnostic overlay path escapes root: ${file.path}" }
                Files.createDirectories(requireNotNull(target.parent))
                Files.writeString(target, file.content)
            }
            val originalRoot = project.workspace.root.toAbsolutePath().normalize()
            fun originalClasspath(entries: List<Path>): List<Path> = entries.map { entry ->
                if (entry.isAbsolute) entry else originalRoot.resolve(entry).normalize()
            }
            val modules = project.modules.map { module ->
                val moduleRoot = module.root.toAbsolutePath().normalize()
                val relativeRoot = if (moduleRoot.startsWith(originalRoot)) originalRoot.relativize(moduleRoot)
                    else Path.of(module.name.replace(':', '/'))
                module.copy(
                    root = overlayRoot.resolve(relativeRoot),
                    classpathEntries = originalClasspath(module.classpathEntries),
                    mainClasspathEntries = originalClasspath(module.mainClasspathEntries),
                    mainRuntimeClasspathEntries = originalClasspath(module.mainRuntimeClasspathEntries),
                    testClasspathEntries = originalClasspath(module.testClasspathEntries),
                )
            }
            val buildModels = project.buildModels.map { model -> model.copy(modules = model.modules.map { module ->
                val moduleRoot = module.root.toAbsolutePath().normalize()
                val relativeRoot = if (moduleRoot.startsWith(originalRoot)) originalRoot.relativize(moduleRoot)
                    else Path.of(module.id.replace(':', '/'))
                module.copy(
                    root = overlayRoot.resolve(relativeRoot),
                    sourceSets = module.sourceSets.map { sourceSet ->
                        sourceSet.copy(
                            classpathEntries = originalClasspath(sourceSet.classpathEntries),
                            runtimeClasspathEntries = originalClasspath(sourceSet.runtimeClasspathEntries),
                        )
                    },
                )
            }) }
            analyze(project.copy(workspace = Workspace(overlayRoot), modules = modules, buildModels = buildModels))
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
        RefactoringDescriptor("changeSignature.changeParameterType", "Change one JDT-bound Java parameter type", RiskLevel.MEDIUM),
        RefactoringDescriptor("changeSignature.addParameter", "Add Java parameter with default call-site expression", RiskLevel.MEDIUM),
        RefactoringDescriptor("changeSignature.reorderParameters", "Reorder Java parameters and call-site arguments", RiskLevel.MEDIUM),
        RefactoringDescriptor("changeSignature.removeParameter", "Remove unused Java parameter and call-site argument", RiskLevel.MEDIUM),
        RefactoringDescriptor("moveClass", "Move Java class", RiskLevel.MEDIUM),
        RefactoringDescriptor("moveSourceRoot", "Move Java source root without changing FQCNs", RiskLevel.MEDIUM),
        RefactoringDescriptor("organizeImports", "Organize imports", RiskLevel.LOW),
        RefactoringDescriptor("formatFile", "Format Java compilation unit", RiskLevel.LOW),
        RefactoringDescriptor("safeDelete", "Safe delete", RiskLevel.MEDIUM),
    )

    override fun applyRefactoring(request: RefactoringRequest): PatchPlan = when (request.operation) {
        "renameClass"  -> applyRenameClass(request)
        "renameMember" -> applyRenameMember(request)
        "extractMethod" -> applyExtractMethod(request)
        "changeSignature.renameParameter", "renameParameter" -> applyRenameParameter(request)
        "changeSignature.changeParameterType", "changeParameterType" -> applyChangeParameterType(request)
        "changeSignature.addParameter", "addParameter" -> applyAddParameter(request)
        "changeSignature.reorderParameters", "reorderParameters" -> applyReorderParameters(request)
        "changeSignature.removeParameter", "removeParameter" -> applyRemoveParameter(request)
        "moveClass"    -> applyMoveClass(request)
        "moveSourceRoot" -> applyMoveSourceRoot(request)
        "organizeImports" -> applyOrganizeImports(request)
        "formatFile" -> applyFormatFile(request)
        "safeDelete"   -> applySafeDelete(request)
        else           -> notImplemented(request, "Unknown operation: ${request.operation}")
    }

    private fun applyMoveSourceRoot(request: RefactoringRequest): PatchPlan {
        val from = request.arguments["from"]
            ?: return notImplemented(request, "moveSourceRoot requires arguments.from")
        val to = request.arguments["to"]
            ?: return notImplemented(request, "moveSourceRoot requires arguments.to")
        return JavaMoveSourceRootPlanner(this).preview(request.snapshot, Path.of(from), Path.of(to))
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
        return JavaChangeSignaturePlanner(this).previewRenameParameter(
            request.snapshot, symbol, oldName, newName,
            acceptExternalConsumerRisk = request.arguments["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun applyChangeParameterType(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "changeParameterType requires symbolId")
        val name = request.arguments["name"]
            ?: request.arguments["parameterName"]
            ?: return notImplemented(request, "changeParameterType requires arguments.name")
        val type = request.arguments["type"]
            ?: request.arguments["newType"]
            ?: return notImplemented(request, "changeParameterType requires arguments.type")
        return JavaChangeSignaturePlanner(this).previewChangeParameterType(
            request.snapshot, symbol, name, type,
            includeHierarchy = request.arguments["includeHierarchy"]?.toBooleanStrictOrNull() ?: false,
            acceptExternalConsumerRisk = request.arguments["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
        )
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
        return JavaChangeSignaturePlanner(this).previewAddParameter(
            request.snapshot,
            symbol,
            type,
            name,
            defaultExpression,
            includeHierarchy = request.arguments["includeHierarchy"]?.toBooleanStrictOrNull() ?: false,
            acceptExternalConsumerRisk = request.arguments["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
            migrateFunctionalReferences = request.arguments["migrateFunctionalReferences"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun applyReorderParameters(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "reorderParameters requires symbolId")
        val order = request.arguments["order"]
            ?: request.arguments["newOrder"]
            ?: return notImplemented(request, "reorderParameters requires arguments.order")
        return JavaChangeSignaturePlanner(this).previewReorderParameters(
            request.snapshot, symbol, order.split(','),
            includeHierarchy = request.arguments["includeHierarchy"]?.toBooleanStrictOrNull() ?: false,
            acceptExternalConsumerRisk = request.arguments["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun applyRemoveParameter(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "removeParameter requires symbolId")
        val name = request.arguments["name"]
            ?: request.arguments["parameterName"]
            ?: return notImplemented(request, "removeParameter requires arguments.name")
        return JavaChangeSignaturePlanner(this).previewRemoveParameter(
            request.snapshot,
            symbol,
            name,
            includeHierarchy = request.arguments["includeHierarchy"]?.toBooleanStrictOrNull() ?: false,
            acceptExternalConsumerRisk = request.arguments["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
        )
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
