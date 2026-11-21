package org.refactorkit.java

import org.refactorkit.core.CodeSelection
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.FileEdit
import org.refactorkit.core.LanguageAdapter
import org.refactorkit.core.ParseResult
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.Reference
import org.refactorkit.core.RefactoringDescriptor
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RefactoringEvidence
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
import org.refactorkit.core.TextEdits
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.buildSourceRootOwnerships
import org.refactorkit.core.owningBuildSourceRoots
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

enum class JavaJdtNavigationKind { TYPE_DEFINITION, TYPE_HIERARCHY, CALL_HIERARCHY, IMPLEMENTATIONS }

data class JavaJdtTypeHierarchyEntry(val relation: String, val symbol: Symbol)
data class JavaJdtCallHierarchyMethodIdentity(
    val name: String,
    val qualifiedName: String,
    val bindingKey: String,
    val jvmIdentity: JdtJavaJvmIdentity?,
)
data class JavaJdtCallHierarchyEntry(
    val method: Symbol?,
    val methodIdentity: JavaJdtCallHierarchyMethodIdentity,
    val callSites: List<SourceLocation>,
)
data class JavaJdtImplementationEntry(val relation: String, val symbol: Symbol)

sealed interface JavaJdtNavigationProjection {
    data class TypeDefinition(
        val target: Symbol,
        val symbols: List<Symbol>,
        val provenanceHash: String,
    ) : JavaJdtNavigationProjection

    data class TypeHierarchy(
        val root: Symbol,
        val direction: String,
        val items: List<JavaJdtTypeHierarchyEntry>,
        val total: Int,
        val truncated: Boolean,
        val complete: Boolean,
        val warningCount: Int,
        val symbols: List<Symbol>,
        val provenanceHash: String,
    ) : JavaJdtNavigationProjection

    data class CallHierarchy(
        val root: Symbol,
        val direction: String,
        val items: List<JavaJdtCallHierarchyEntry>,
        val total: Int,
        val truncated: Boolean,
        val complete: Boolean,
        val warningCount: Int,
        val symbols: List<Symbol>,
        val provenanceHash: String,
    ) : JavaJdtNavigationProjection

    data class Implementations(
        val root: Symbol,
        val targetKind: String,
        val items: List<JavaJdtImplementationEntry>,
        val total: Int,
        val truncated: Boolean,
        val complete: Boolean,
        val warningCount: Int,
        val symbols: List<Symbol>,
        val provenanceHash: String,
    ) : JavaJdtNavigationProjection

    data class Refused(val diagnostic: Diagnostic) : JavaJdtNavigationProjection
}

/** Read-only lookup distinguishes a missing selection from unavailable semantic analysis. */
sealed interface JavaSymbolLookupResult {
    data class Found(val symbol: Symbol) : JavaSymbolLookupResult
    data object NotFound : JavaSymbolLookupResult
    data class AnalysisUnavailable(
        val warningCount: Int,
        val firstWarningPath: String,
        val firstWarningLine: Int,
        val firstWarningMessage: String,
    ) : JavaSymbolLookupResult
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

    /** Nullable compatibility API; callers needing refusal evidence use lookupSymbol. */
    fun findSymbol(project: ProjectSnapshot, symbolId: SymbolId): Symbol? =
        (lookupSymbol(project, symbolId) as? JavaSymbolLookupResult.Found)?.symbol

    fun lookupSymbol(project: ProjectSnapshot, symbolId: SymbolId): JavaSymbolLookupResult {
        lastSnapshot = project
        val lexicalSymbol = buildSymbols(project).symbols.find { it.id == symbolId }
        if (lexicalSymbol != null) return JavaSymbolLookupResult.Found(lexicalSymbol)
        if (!symbolId.value.isSignedMemberId()) return JavaSymbolLookupResult.NotFound
        val analysis = jdtCache.get(project).analysis
        // Keep the existing fail-closed authority policy; do not disguise it as absence.
        val warnings = analysis.warnings
        if (warnings.isNotEmpty()) {
            val first = warnings.first()
            // Project immutable values; never expose the cached analysis's mutable warning list.
            return JavaSymbolLookupResult.AnalysisUnavailable(
                warnings.size, first.path.toString(), first.line, first.message,
            )
        }
        val symbol = analysis.symbols.singleOrNull { it.qualifiedName == symbolId.value }
            ?: return JavaSymbolLookupResult.NotFound
        if (symbol.bindingKey.isNullOrBlank()) return JavaSymbolLookupResult.NotFound
        return JavaSymbolLookupResult.Found(symbol.toCoreSymbol())
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
            val point = location.range.start
            val kotlinBinary = cached.analysis.bindingUses.any { use ->
                use.isKotlinBinaryOwner && !use.recovered && use.jvmIdentity != null &&
                    use.path.normalize() == location.path.normalize() &&
                    point >= use.sourceRange.start && point < use.sourceRange.end
            }
            if (kotlinBinary) return JavaJdtDefinitionProjection.Refused(Diagnostic(
                "Java resolved a Kotlin JVM declaration whose source has no qualified mapping",
                Diagnostic.Severity.WARNING,
                location,
                code = "java.definitionKotlinSourceUnqualified",
            ))
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
        } catch (failure: JavaContextAuthorityException) {
            JavaJdtDefinitionProjection.Refused(Diagnostic(
                failure.message ?: "Java semantic analysis was refused by project authority",
                Diagnostic.Severity.ERROR,
                location,
                code = failure.code,
            ))
        } catch (_: RuntimeException) {
            JavaJdtDefinitionProjection.Refused(Diagnostic(
                "Eclipse JDT definition analysis failed", Diagnostic.Severity.ERROR, location,
                code = "java.definitionUnavailable",
            ))
        }
    }

    /** Compiler-bound Java navigation. Hierarchies are deliberately bounded to workspace source. */
    fun semanticNavigation(
        project: ProjectSnapshot,
        kind: JavaJdtNavigationKind,
        location: SourceLocation,
        direction: String,
        limit: Int,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): JavaJdtNavigationProjection {
        lastSnapshot = project
        val file = project.files.singleOrNull { it.path.normalize() == location.path.normalize() && it.languageId == "java" }
            ?: return navigationRefused(location, "java.navigation.pathMissing", "Java navigation path is not part of the analyzed snapshot")
        if (!validPosition(file.content, location.range.start)) return navigationRefused(
            location, "java.navigation.positionInvalid", "Java navigation position is outside the analyzed document",
        )
        val maxLimit = if (kind == JavaJdtNavigationKind.CALL_HIERARCHY) {
            org.refactorkit.core.ProtocolLimits.MAX_REFERENCE_RESULTS
        } else org.refactorkit.core.ProtocolLimits.MAX_SYMBOL_RESULTS
        if (limit !in 1..maxLimit) return navigationRefused(
            location, "java.navigation.limitInvalid", "Java navigation result limit is outside the supported bound",
        )
        val validDirection = when (kind) {
            JavaJdtNavigationKind.TYPE_DEFINITION -> direction == "target"
            JavaJdtNavigationKind.TYPE_HIERARCHY -> direction in setOf("supertypes", "subtypes", "both")
            JavaJdtNavigationKind.CALL_HIERARCHY -> direction in setOf("incoming", "outgoing")
            JavaJdtNavigationKind.IMPLEMENTATIONS -> direction == "all"
        }
        if (!validDirection) return navigationRefused(
            location, "java.navigation.directionInvalid", "Java navigation direction is not supported",
        )
        return try {
            val cached = jdtCache.get(project, cancellation)
            val allSymbols = cached.analysis.symbols.filter { !it.bindingKey.isNullOrBlank() }
            val symbols = allSymbols.map { it.toCoreSymbol() }
                .distinctBy { it.id to it.location }
                .sortedWith(compareBy({ it.id.value }, { it.location.path.toString() },
                    { it.location.range.start.line }, { it.location.range.start.character }))
            when (kind) {
                JavaJdtNavigationKind.TYPE_DEFINITION -> {
                    val target = boundTypeTarget(cached.analysis, allSymbols, location)
                        ?: return navigationRefused(
                            location, "java.typeDefinition.sourceTargetUnavailable",
                            "The selected Java value has no resolved type declaration in workspace source",
                        )
                    JavaJdtNavigationProjection.TypeDefinition(target.toCoreSymbol(), symbols, cached.provenanceHash)
                }
                JavaJdtNavigationKind.TYPE_HIERARCHY -> {
                    val root = boundTypeTarget(cached.analysis, allSymbols, location)
                        ?: return navigationRefused(
                            location, "java.typeHierarchy.sourceTargetUnavailable",
                            "The selected Java value has no resolved type declaration in workspace source",
                        )
                    val typeKinds = setOf(
                        JdtJavaSemanticSymbolKind.CLASS, JdtJavaSemanticSymbolKind.INTERFACE,
                        JdtJavaSemanticSymbolKind.ENUM, JdtJavaSemanticSymbolKind.RECORD,
                        JdtJavaSemanticSymbolKind.ANNOTATION,
                    )
                    val typesByName = allSymbols.filter { it.kind in typeKinds }.groupBy { it.qualifiedName }
                    val parents = cached.analysis.typeInheritance.filter {
                        !it.subtypeBindingKey.isNullOrBlank() && !it.supertypeBindingKey.isNullOrBlank()
                    }.groupBy({ it.subtypeQualifiedName }, { it.supertypeQualifiedName })
                    val children = cached.analysis.typeInheritance.filter {
                        !it.subtypeBindingKey.isNullOrBlank() && !it.supertypeBindingKey.isNullOrBlank()
                    }.groupBy({ it.supertypeQualifiedName }, { it.subtypeQualifiedName })
                    val entries = linkedMapOf<String, JavaJdtTypeHierarchyEntry>()
                    var omittedExternal = false
                    fun collect(start: String, edges: Map<String, List<String>>, relation: String) {
                        val visited = mutableSetOf(start)
                        val queue = ArrayDeque<String>()
                        edges[start].orEmpty().sorted().forEach(queue::add)
                        while (queue.isNotEmpty()) {
                            if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
                            val name = queue.removeFirst()
                            if (!visited.add(name)) continue
                            val symbol = typesByName[name]?.singleOrNull()
                            if (symbol == null || symbol.recovered || symbol.bindingKey.isNullOrBlank()) {
                                omittedExternal = true
                                continue
                            }
                            entries.putIfAbsent(name, JavaJdtTypeHierarchyEntry(relation, symbol.toCoreSymbol()))
                            edges[name].orEmpty().sorted().forEach(queue::add)
                        }
                    }
                    if (direction in setOf("supertypes", "both")) collect(root.qualifiedName, parents, "supertype")
                    if (direction in setOf("subtypes", "both")) collect(root.qualifiedName, children, "subtype")
                    val ordered = entries.values.sortedWith(compareBy<JavaJdtTypeHierarchyEntry> { it.relation }
                        .thenBy { it.symbol.id.value }.thenBy { it.symbol.location.path.toString() })
                    val warningCount = cached.analysis.warnings.size + if (omittedExternal) 1 else 0
                    JavaJdtNavigationProjection.TypeHierarchy(
                        root.toCoreSymbol(), direction, ordered.take(limit), ordered.size, ordered.size > limit,
                        cached.analysis.warnings.isEmpty() && !omittedExternal && ordered.size <= limit,
                        warningCount, symbols, cached.provenanceHash,
                    )
                }
                JavaJdtNavigationKind.CALL_HIERARCHY -> {
                    val methodKinds = setOf(JdtJavaSemanticSymbolKind.METHOD, JdtJavaSemanticSymbolKind.CONSTRUCTOR)
                    val root = boundTarget(cached.analysis, allSymbols, location)?.takeIf { it.kind in methodKinds }
                        ?: return navigationRefused(
                            location, "java.callHierarchy.methodTargetUnavailable",
                            "The selected Java position does not resolve to a source method or constructor",
                        )
                    val sourceMethods = allSymbols.filter { it.kind in methodKinds && !it.recovered &&
                        !it.bindingKey.isNullOrBlank() }
                    val methodsByJvmIdentity = sourceMethods.filter { it.jvmIdentity != null }
                        .groupBy { it.jvmIdentity!! }
                    val methodsByBindingKey = sourceMethods.groupBy { it.bindingKey!! }
                    val rootJvmIdentity = root.jvmIdentity?.takeIf { it.kind == JdtJavaJvmIdentityKind.CALLABLE }
                    val matching = cached.analysis.invocations.filter { invocation ->
                        val selectedJvmIdentity = if (direction == "incoming") {
                            invocation.methodJvmIdentity
                        } else invocation.callerMethodJvmIdentity
                        val selectedBindingKey = if (direction == "incoming") {
                            invocation.methodBindingKey
                        } else invocation.callerMethodBindingKey
                        val selectedName = if (direction == "incoming") {
                            invocation.methodQualifiedName
                        } else invocation.callerMethodQualifiedName
                        when {
                            rootJvmIdentity != null && selectedJvmIdentity != null -> selectedJvmIdentity == rootJvmIdentity
                            !root.bindingKey.isNullOrBlank() && !selectedBindingKey.isNullOrBlank() ->
                                selectedBindingKey == root.bindingKey
                            else -> selectedName == root.qualifiedName
                        }
                    }
                    var omittedExternal = false
                    val groupedCalls = linkedMapOf<String, MutableList<JdtJavaSemanticInvocation>>()
                    matching.forEach { invocation ->
                        val bindingKey = if (direction == "incoming") {
                            invocation.callerMethodBindingKey
                        } else invocation.methodBindingKey
                        val jvmIdentity = if (direction == "incoming") {
                            invocation.callerMethodJvmIdentity
                        } else invocation.methodJvmIdentity
                        val qualifiedName = if (direction == "incoming") {
                            invocation.callerMethodQualifiedName
                        } else invocation.methodQualifiedName
                        val recovered = if (direction == "incoming") {
                            invocation.callerBindingRecovered
                        } else invocation.methodBindingRecovered
                        if (qualifiedName.isNullOrBlank() || bindingKey.isNullOrBlank() || recovered) {
                            omittedExternal = true
                            return@forEach
                        }
                        val groupKey = jvmIdentity?.let {
                            "${it.kind}:${it.ownerBinaryName}:${it.memberName}:${it.descriptor}"
                        } ?: "binding:$bindingKey"
                        groupedCalls.getOrPut(groupKey, ::mutableListOf).add(invocation)
                    }
                    val entries = groupedCalls.values.mapNotNull { calls ->
                        val representative = calls.first()
                        val bindingKey = if (direction == "incoming") {
                            representative.callerMethodBindingKey
                        } else representative.methodBindingKey
                        val jvmIdentity = if (direction == "incoming") {
                            representative.callerMethodJvmIdentity
                        } else representative.methodJvmIdentity
                        val qualifiedName = if (direction == "incoming") {
                            representative.callerMethodQualifiedName
                        } else representative.methodQualifiedName
                        val name = jvmIdentity?.memberName?.takeIf { it != "<init>" }
                            ?: qualifiedName?.substringAfterLast('#')?.substringBefore('(')?.takeIf { it != "<init>" }
                            ?: jvmIdentity?.ownerBinaryName?.substringAfterLast('.')?.substringAfterLast('$')
                        if (bindingKey.isNullOrBlank() || qualifiedName.isNullOrBlank() || name.isNullOrBlank()) {
                            omittedExternal = true
                            return@mapNotNull null
                        }
                        val candidates = jvmIdentity?.let(methodsByJvmIdentity::get)
                            ?: methodsByBindingKey[bindingKey].orEmpty()
                        if (candidates.size > 1) {
                            omittedExternal = true
                            return@mapNotNull null
                        }
                        val sourceMethod = candidates.singleOrNull()?.takeIf { candidate ->
                            candidate.jvmIdentity == null || jvmIdentity == null || candidate.jvmIdentity == jvmIdentity
                        }?.toCoreSymbol()
                        JavaJdtCallHierarchyEntry(
                            sourceMethod,
                            JavaJdtCallHierarchyMethodIdentity(name, qualifiedName, bindingKey, jvmIdentity),
                            calls.map { SourceLocation(it.path, it.nameRange) }.distinct()
                                .sortedWith(compareBy({ it.path.toString() }, { it.range.start.line },
                                    { it.range.start.character })),
                        )
                    }
                    val orderedEntries = entries.sortedWith(compareBy<JavaJdtCallHierarchyEntry> {
                        it.methodIdentity.jvmIdentity?.ownerBinaryName.orEmpty()
                    }.thenBy { it.methodIdentity.jvmIdentity?.memberName.orEmpty() }
                        .thenBy { it.methodIdentity.jvmIdentity?.descriptor.orEmpty() }
                        .thenBy { it.methodIdentity.bindingKey })
                    val unavailableSourceCount = orderedEntries.count { it.method == null }
                    val warningCount = cached.analysis.warnings.size + unavailableSourceCount + if (omittedExternal) 1 else 0
                    JavaJdtNavigationProjection.CallHierarchy(
                        root.toCoreSymbol(), direction, orderedEntries.take(limit), orderedEntries.size, orderedEntries.size > limit,
                        cached.analysis.warnings.isEmpty() && !omittedExternal && unavailableSourceCount == 0 && orderedEntries.size <= limit,
                        warningCount, symbols, cached.provenanceHash,
                    )
                }
                JavaJdtNavigationKind.IMPLEMENTATIONS -> {
                    val typeKinds = setOf(
                        JdtJavaSemanticSymbolKind.CLASS, JdtJavaSemanticSymbolKind.INTERFACE,
                        JdtJavaSemanticSymbolKind.ENUM, JdtJavaSemanticSymbolKind.RECORD,
                        JdtJavaSemanticSymbolKind.ANNOTATION,
                    )
                    val typeSymbols = allSymbols.filter { it.kind in typeKinds }
                    val methodKinds = setOf(JdtJavaSemanticSymbolKind.METHOD)
                    val root = boundTarget(cached.analysis, allSymbols, location)
                        ?: return navigationRefused(
                            location, "java.implementations.targetUnavailable",
                            "The selected Java position does not resolve to a bound source type or method",
                        )
                    if (root.recovered || root.bindingKey.isNullOrBlank()) return navigationRefused(
                        location, "java.implementations.targetBindingUnavailable",
                        "The selected Java declaration has no complete JDT binding",
                    )
                    val byTypeBinding = typeSymbols.filter { !it.bindingKey.isNullOrBlank() }
                        .groupBy { it.bindingKey!! }
                    val byMethodBinding = allSymbols.filter { it.kind in methodKinds && !it.bindingKey.isNullOrBlank() }
                        .groupBy { it.bindingKey!! }
                    val entries = linkedMapOf<String, JavaJdtImplementationEntry>()
                    var incomplete = cached.analysis.warnings.isNotEmpty()
                    val targetKind: String
                    if (root.kind in typeKinds) {
                        targetKind = "type"
                        if (cached.analysis.typeInheritance.any {
                                it.subtypeBindingKey.isNullOrBlank() || it.supertypeBindingKey.isNullOrBlank()
                            }) incomplete = true
                        val rootKey = root.bindingKey?.takeIf(String::isNotBlank)
                            ?: return navigationRefused(
                                location, "java.implementations.typeBindingUnavailable",
                                "The selected type has no exact JDT binding",
                            )
                        val descendants = cached.analysis.typeInheritance.filter { relation ->
                            relation.supertypeBindingKey != null && relation.subtypeBindingKey != null
                        }.groupBy { it.supertypeBindingKey!! }
                        val visited = mutableSetOf(rootKey)
                        val queue = ArrayDeque<String>().also { it.add(rootKey) }
                        while (queue.isNotEmpty()) {
                            if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
                            val superKey = queue.removeFirst()
                            cached.analysis.typeInheritance.filter { it.supertypeBindingKey == superKey &&
                                it.subtypeBindingKey.isNullOrBlank() }.forEach { incomplete = true }
                            for (relation in descendants[superKey].orEmpty().sortedBy { it.subtypeQualifiedName }) {
                                val subtypeKey = relation.subtypeBindingKey ?: continue
                                if (!visited.add(subtypeKey)) continue
                                val subtype = byTypeBinding[subtypeKey]?.singleOrNull()
                                if (subtype == null || subtype.recovered || subtype.bindingKey.isNullOrBlank() ||
                                    subtype.qualifiedName != relation.subtypeQualifiedName) {
                                    incomplete = true
                                    continue
                                }
                                queue.add(subtypeKey)
                                if (subtype.kind in setOf(JdtJavaSemanticSymbolKind.CLASS,
                                        JdtJavaSemanticSymbolKind.ENUM, JdtJavaSemanticSymbolKind.RECORD)) {
                                    when (subtype.isAbstract) {
                                        false -> entries.putIfAbsent(subtype.bindingKey!!,
                                            JavaJdtImplementationEntry("concreteType", subtype.toCoreSymbol()))
                                        true -> Unit
                                        null -> incomplete = true
                                    }
                                }
                            }
                        }
                    } else if (root.kind in methodKinds) {
                        targetKind = "method"
                        val rootKey = root.bindingKey?.takeIf(String::isNotBlank)
                            ?: return navigationRefused(
                                location, "java.implementations.methodBindingUnavailable",
                                "The selected method has no exact JDT binding",
                            )
                        val byOverridden = cached.analysis.overrideRelations.filter { relation ->
                            !relation.overriddenBindingKey.isNullOrBlank() &&
                                !relation.overridingBindingKey.isNullOrBlank()
                        }.groupBy { it.overriddenBindingKey }
                        val visited = mutableSetOf(rootKey)
                        val queue = ArrayDeque<String>().also { it.add(rootKey) }
                        while (queue.isNotEmpty()) {
                            if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
                            for (relation in byOverridden[queue.removeFirst()].orEmpty()
                                .sortedBy { it.overridingSymbolQualifiedName }) {
                                val overridingKey = relation.overridingBindingKey
                                if (!visited.add(overridingKey)) continue
                                val method = byMethodBinding[overridingKey]?.singleOrNull()
                                if (method == null || method.recovered || method.bindingKey.isNullOrBlank()) {
                                    incomplete = true
                                    continue
                                }
                                when (method.isAbstract) {
                                    false -> entries.putIfAbsent(overridingKey,
                                        JavaJdtImplementationEntry("concreteMethod", method.toCoreSymbol()))
                                    true -> Unit
                                    null -> incomplete = true
                                }
                                queue.add(overridingKey)
                            }
                        }
                    } else {
                        return navigationRefused(
                            location, "java.implementations.targetKindUnsupported",
                            "Implementation lookup supports source types and methods only",
                        )
                    }
                    val ordered = entries.values.sortedWith(compareBy<JavaJdtImplementationEntry> { it.relation }
                        .thenBy { it.symbol.id.value }.thenBy { it.symbol.location.path.toString() }
                        .thenBy { it.symbol.location.range.start.line })
                    val truncated = ordered.size > limit
                    val warningCount = cached.analysis.warnings.size + if (incomplete && cached.analysis.warnings.isEmpty()) 1 else 0
                    JavaJdtNavigationProjection.Implementations(
                        root.toCoreSymbol(), targetKind, ordered.take(limit), ordered.size, truncated,
                        !incomplete && !truncated, warningCount, symbols, cached.provenanceHash,
                    )
                }
            }
        } catch (_: JdtJavaAnalysisCancelledException) {
            navigationRefused(location, "java.navigation.cancelled", "Java semantic navigation was cancelled")
        } catch (_: JdtJavaAnalysisLimitException) {
            navigationRefused(location, "java.navigation.sourceLimit", "Java semantic navigation exceeds the bounded source limit")
        } catch (_: RuntimeException) {
            navigationRefused(location, "java.navigation.unavailable", "Eclipse JDT semantic navigation analysis failed")
        }
    }

    private fun boundTypeTarget(
        analysis: JdtJavaSemanticAnalysisResult,
        symbols: List<JdtJavaSemanticSymbol>,
        location: SourceLocation,
    ): JdtJavaSemanticSymbol? {
        val typeKinds = setOf(
            JdtJavaSemanticSymbolKind.CLASS, JdtJavaSemanticSymbolKind.INTERFACE,
            JdtJavaSemanticSymbolKind.ENUM, JdtJavaSemanticSymbolKind.RECORD,
            JdtJavaSemanticSymbolKind.ANNOTATION,
        )
        symbols.firstOrNull { symbol ->
            symbol.kind in typeKinds && symbol.path.normalize() == location.path.normalize() &&
                symbol.sourceRange.contains(location.range.start)
        }?.let { return it }
        val use = analysis.bindingUses.firstOrNull { bindingUse ->
            bindingUse.path.normalize() == location.path.normalize() &&
                bindingUse.sourceRange.contains(location.range.start) && !bindingUse.recovered
        } ?: return null
        val qualifiedName = use.typeQualifiedName ?: return null
        return symbols.singleOrNull { it.kind in typeKinds && it.qualifiedName == qualifiedName && !it.recovered }
    }

    private fun navigationRefused(location: SourceLocation, code: String, message: String) =
        JavaJdtNavigationProjection.Refused(Diagnostic(message, Diagnostic.Severity.ERROR, location, code = code))

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
            JdtJavaSemanticSymbolKind.RECORD_COMPONENT -> Symbol.Kind.RECORD_COMPONENT
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
    fun authoritativeDiagnostics(project: ProjectSnapshot, platformHome: Path?, verbose: Boolean = false): List<Diagnostic> =
        authoritativeDiagnosticsWithPlatformSelection(project, JavaDiagnosticsPlatformSelection(projectSdkHome = platformHome), verbose)

    fun authoritativeDiagnosticsWithPlatformSelection(project: ProjectSnapshot, selection: JavaDiagnosticsPlatformSelection,
        verbose: Boolean = false,
    ): List<Diagnostic> {
        val evaluated = JavaDiagnosticsPlatformContext.evaluate(project, selection) { authorities ->
            analyzeAuthoritativeDiagnosticsOverlay(project, authorities)
        }
        val unavailable = evaluated.failures.map { unavailableSourceSet(it.moduleId, it.sourceSetId, it.code, it.message) }
        return diagnostics(project, verbose, evaluated.analysis ?: analyzeDiagnosticsOverlay(project), unavailable, evaluated.analysis == null)
    }

    fun filterDiagnosticsForModule(
        project: ProjectSnapshot,
        diagnostics: List<Diagnostic>,
        moduleId: String,
    ): List<Diagnostic> {
        val matches = javaContextBuildModels(project).flatMap { it.modules }.filter { it.id == moduleId || it.name == moduleId }
        require(matches.size == 1) { "Java diagnostics module selector is unknown or ambiguous: $moduleId" }
        val module = matches.single()
        return diagnostics.filter { diagnostic ->
            diagnostic.location?.path?.normalize()?.let { path -> project.owningJavaBuildSourceRoots(path).any { it.module.id == module.id } }
                ?: diagnostic.message.contains("source set '${module.id}:")
        }
    }

    /** Maps the compiler errors of an already-attested analysis; never parses saved text. */
    internal fun compilerDiagnostics(project: ProjectSnapshot, analysis: JdtJavaSemanticAnalysisResult): List<Diagnostic> {
        require(analysis.snapshotHash == project.hash) { "Compiler diagnostics belong to another snapshot" }
        val paths = project.files.filter { it.languageId == "java" }.map { it.path }.toSet()
        require(analysis.warnings.all { it.path in paths }) { "Compiler diagnostic source is outside the captured Java frame" }
        return (analysis.warnings.filterNot { isResolvedModuleExportWarning(project, it) }
            .map(::compilerDiagnostic) + crossSourceTypeDiagnostics(project, analysis)).distinct().sortedWith(compareBy<Diagnostic> { ProtocolPath.serialize(requireNotNull(it.location).path) }
                .thenBy { it.location!!.range.start }.thenBy { it.code }.thenBy { it.message })
    }

    /** Adapter extraction only: namespace decisions belong to the independently specified domain. */
    private fun crossSourceTypeDiagnostics(project: ProjectSnapshot, analysis: JdtJavaSemanticAnalysisResult): List<Diagnostic> =
        JavaCrossSourceTypeCollisions.diagnostics(analysis.symbols.mapNotNull { symbol ->
            if (symbol.evidence != JdtJavaSemanticEvidence.JDT_BINDING || symbol.recovered) return@mapNotNull null
            val identity = symbol.jvmIdentity?.takeIf { it.kind == JdtJavaJvmIdentityKind.TYPE } ?: return@mapNotNull null
            val owner = project.owningJavaBuildSourceRoots(symbol.path).singleOrNull() ?: return@mapNotNull null
            if (owner.modelStatus != org.refactorkit.core.BuildModelStatus.AVAILABLE) return@mapNotNull null
            JavaNativeTypeDeclaration(JavaTypeCompilationScope(owner.providerId, owner.module.id, owner.sourceSet.id),
                identity.ownerBinaryName, SourceLocation(symbol.path, symbol.sourceRange))
        })

    private fun compilerDiagnostic(warning: JdtJavaSemanticWarning): Diagnostic {
        val syntax = warning.category == JdtJavaDiagnosticCategory.SYNTAX
        return Diagnostic(message = warning.message, severity = Diagnostic.Severity.ERROR,
            location = SourceLocation(warning.path, warning.sourceRange),
            code = if (syntax) "java.jdt.syntax" else "java.jdt.typeResolution",
            evidence = DiagnosticEvidence.COMPILER,
            category = if (syntax) DiagnosticCategory.SYNTAX else DiagnosticCategory.TYPE_RESOLUTION)
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
                else -> (module.languageSettings["java.sourceLevel.refusalCode"] ?: "sourceLevel.unavailable") to
                    module.languageSettings["java.sourceLevel.message"].orEmpty()
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
        }.map(::compilerDiagnostic)
        if (!suppressTypeResolution) diagnostics += crossSourceTypeDiagnostics(project, semanticAnalysis)
        val symbols = buildSymbols(project).symbols

        val typeSymbols = symbols.filter { it.kind in TYPE_KINDS }
        val duplicateGroups = if (project.buildModels.isEmpty()) {
            typeSymbols.groupBy { it.id.value }
                .filterValues { it.size > 1 }
                .map { (id, duplicates) -> id to duplicates }
        } else {
            typeSymbols.mapNotNull { symbol ->
                val moduleRoots = project.owningBuildSourceRoots(symbol.location.path)
                    .map { it.module.root.normalize() }
                    .distinct()
                moduleRoots.singleOrNull()?.let { moduleRoot ->
                    (moduleRoot to symbol.id.value) to symbol
                }
            }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }
                .map { (scope, duplicates) -> scope.second to duplicates }
        }
        duplicateGroups.forEach { (id, duplicates) ->
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
                val actualName = file.path.fileName?.toString() ?: continue
                if (actualName != expected) {
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

    internal fun analyzeDiagnosticsOverlay(project: ProjectSnapshot): JdtJavaSemanticAnalysisResult =
        try {
            withDiagnosticsOverlay(project, JdtJavaSemanticAnalyzer()::analyze)
        } catch (failure: JavaContextAuthorityException) {
            val matchingRefusal = project.modules.any { module ->
                module.languageSettings["java.sourceLevel.refusalCode"] == failure.code
            }
            if (!matchingRefusal) throw failure
            JdtJavaSemanticAnalysisResult(emptyList(), snapshotHash = project.hash)
        }

    internal fun analyzeAuthoritativeDiagnosticsOverlay(
        project: ProjectSnapshot,
        authorities: Map<Int, JavaReleasePlatformAuthority>,
    ): JdtAuthoritativeJavaAnalysisResolution = withDiagnosticsOverlay(project) { overlay ->
        JdtJavaSemanticAnalyzer().analyzeAuthoritatively(overlay, authorities)
    }

    internal fun <T> withDiagnosticsOverlay(project: ProjectSnapshot, analyze: (ProjectSnapshot) -> T): T {
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

    override fun availableRefactorings(selection: CodeSelection): List<RefactoringDescriptor> =
        JavaAdapterRegistration.descriptors()

    override fun applyRefactoring(request: RefactoringRequest): PatchPlan = when (request.operation) {
        "renameClass"  -> applyRenameClass(request)
        "renameMember" -> applyRenameMember(request)
        "extractMethod" -> applyExtractMethod(request)
        "extractVariable" -> applyExtractVariable(request)
        "extractConstant" -> applyExtractConstant(request)
        "extractField" -> applyExtractField(request)
        "encapsulateField" -> request.symbolId?.let { symbolId ->
            JavaEncapsulateFieldPlanner(this).preview(request.snapshot, symbolId)
        } ?: notImplemented(request, "encapsulateField requires a bound field symbol")
        "extractParameter" -> applyExtractParameter(request)
        "extractInterface" -> applyExtractInterface(request)
        "extractSuperclass" -> applyExtractSuperclass(request)
        "changeSignature.renameParameter", "renameParameter" -> applyRenameParameter(request)
        "changeSignature.changeParameterType", "changeParameterType" -> applyChangeParameterType(request)
        "changeSignature.addParameter", "addParameter" -> applyAddParameter(request)
        "changeSignature.reorderParameters", "reorderParameters" -> applyReorderParameters(request)
        "changeSignature.removeParameter", "removeParameter" -> applyRemoveParameter(request)
        "moveClass"    -> applyMoveClass(request)
        "movePackage" -> applyMovePackage(request)
        "moveSourceRoot" -> applyMoveSourceRoot(request)
        "java.moveAcrossMavenModules" -> applyMoveAcrossMavenModules(request)
        "java.createMavenModule" -> applyCreateMavenModule(request)
        "organizeImports" -> applyOrganizeImports(request)
        "batchCleanup" -> applyBatchCleanup(request)
        "formatFile" -> applyFormatFile(request)
        "safeDelete"   -> applySafeDelete(request)
        "makeStatic" -> applyMakeStatic(request)
        "changeVisibility" -> applyChangeVisibility(request)
        "changeType" -> applyChangeType(request)
        "inlineVariable" -> applyInlineVariable(request)
        "inlineConstant" -> applyInlineConstant(request)
        "inlineMethod" -> applyInlineMethod(request)
        "introduceParameterObject" -> applyIntroduceParameterObject(request)
        "extractDelegate" -> applyExtractDelegate(request)
        "pullUpMember" -> applyPullUpMember(request)
        "pushDownMember" -> applyPushDownMember(request)
        "moveMember" -> applyMoveMember(request)
        else           -> notImplemented(request, "Unknown operation: ${request.operation}")
    }

    private fun applyMoveSourceRoot(request: RefactoringRequest): PatchPlan {
        val from = request.arguments["from"]
            ?: return notImplemented(request, "moveSourceRoot requires arguments.from")
        val to = request.arguments["to"]
            ?: return notImplemented(request, "moveSourceRoot requires arguments.to")
        val command = JavaRefactoringPreviewCommand.MoveSourceRoot(Path.of(from), Path.of(to))
        return JavaRefactoringPreviewDispatcher().preview(request.snapshot, this, command)
    }

    private fun applyMovePackage(request: RefactoringRequest): PatchPlan {
        val fromPackage = request.arguments["fromPackage"]
            ?: return notImplemented(request, "movePackage requires arguments.fromPackage")
        val toPackage = request.arguments["toPackage"]
            ?: return notImplemented(request, "movePackage requires arguments.toPackage")
        return JavaMovePackagePlanner(this).preview(
            request.snapshot,
            JavaMovePackageRequest(fromPackage, toPackage),
        )
    }

    private fun applyCreateMavenModule(request: RefactoringRequest): PatchPlan {
        val moduleName = request.arguments["moduleName"]
            ?: return notImplemented(request, "java.createMavenModule requires arguments.moduleName")
        val parentPom = request.arguments["parentPom"]
            ?: return notImplemented(request, "java.createMavenModule requires arguments.parentPom")
        return JavaCreateMavenModulePlanner().preview(request.snapshot, moduleName, Path.of(parentPom))
    }

    private fun applyMoveAcrossMavenModules(request: RefactoringRequest): PatchPlan {
        fun required(name: String): String = request.arguments[name]
            ?: throw MissingOwnershipArgument(
                notImplemented(request, "java.moveAcrossMavenModules requires arguments.$name"),
            )
        return try {
            val rewrite = request.arguments["dependencyPom"]?.let { pom ->
                MavenDependencyRewrite(
                    pomPath = Path.of(pom),
                    source = MavenDependencyIdentity(
                        required("sourceGroupId"), required("sourceArtifactId"), required("sourceVersion"),
                        request.arguments["sourceType"] ?: "jar", request.arguments["sourceClassifier"],
                    ),
                    destination = MavenDependencyIdentity(
                        required("destinationGroupId"), required("destinationArtifactId"), required("destinationVersion"),
                        request.arguments["destinationType"] ?: "jar", request.arguments["destinationClassifier"],
                    ),
                    allIdenticalOccurrences = request.arguments["allIdenticalOccurrences"]?.let { value ->
                        value.toBooleanStrictOrNull()
                            ?: throw IllegalArgumentException("allIdenticalOccurrences must be true or false")
                    } ?: false,
                )
            }
            JavaMoveAcrossMavenModulesPlanner(this).preview(
                request.snapshot,
                Path.of(required("from")),
                Path.of(required("to")),
                listOfNotNull(rewrite),
            )
        } catch (missing: MissingOwnershipArgument) {
            missing.plan
        } catch (error: IllegalArgumentException) {
            notImplemented(request, "Invalid java.moveAcrossMavenModules argument: ${error.message}")
        }
    }

    private class MissingOwnershipArgument(val plan: PatchPlan) : RuntimeException()

    private fun applyRenameMember(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId
            ?: return notImplemented(request, "renameMember requires symbolId")
        val newName = request.arguments["newName"]
            ?: return notImplemented(request, "renameMember requires arguments.newName")
        val command = JavaRefactoringPreviewCommand.RenameMember(symbol, newName)
        return JavaRefactoringPreviewDispatcher().preview(request.snapshot, this, command)
    }

    private fun applyRenameClass(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId
            ?: return notImplemented(request, "renameClass requires symbolId")
        val newName = request.arguments["newName"]
            ?: return notImplemented(request, "renameClass requires arguments.newName")
        val command = JavaRefactoringPreviewCommand.RenameClass(symbol, newName)
        return JavaRefactoringPreviewDispatcher().preview(request.snapshot, this, command)
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

    private fun applyExtractVariable(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return notImplemented(request, "extractVariable requires arguments.file")
        val startLine = request.arguments["startLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractVariable requires arguments.startLine")
        val startCharacter = request.arguments["startCharacter"]?.toIntOrNull()
            ?: return notImplemented(request, "extractVariable requires arguments.startCharacter")
        val endLine = request.arguments["endLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractVariable requires arguments.endLine")
        val endCharacter = request.arguments["endCharacter"]?.toIntOrNull()
            ?: return notImplemented(request, "extractVariable requires arguments.endCharacter")
        val name = request.arguments["name"]
            ?: return notImplemented(request, "extractVariable requires arguments.name")
        val selection = SourceRange(SourcePosition(startLine, startCharacter), SourcePosition(endLine, endCharacter))
        return JavaExtractVariablePlanner(this).preview(
            request.snapshot,
            JavaExtractVariableRequest(java.nio.file.Paths.get(file), selection, name),
        )
    }

    private fun applyExtractConstant(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return notImplemented(request, "extractConstant requires arguments.file")
        val startLine = request.arguments["startLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractConstant requires arguments.startLine")
        val startCharacter = request.arguments["startCharacter"]?.toIntOrNull()
            ?: return notImplemented(request, "extractConstant requires arguments.startCharacter")
        val endLine = request.arguments["endLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractConstant requires arguments.endLine")
        val endCharacter = request.arguments["endCharacter"]?.toIntOrNull()
            ?: return notImplemented(request, "extractConstant requires arguments.endCharacter")
        val name = request.arguments["name"]
            ?: return notImplemented(request, "extractConstant requires arguments.name")
        val selection = SourceRange(SourcePosition(startLine, startCharacter), SourcePosition(endLine, endCharacter))
        return JavaExtractConstantPlanner(this).preview(
            request.snapshot,
            JavaExtractConstantRequest(java.nio.file.Paths.get(file), selection, name),
        )
    }

    private fun applyExtractField(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return notImplemented(request, "extractField requires arguments.file")
        val startLine = request.arguments["startLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractField requires arguments.startLine")
        val startCharacter = request.arguments["startCharacter"]?.toIntOrNull()
            ?: return notImplemented(request, "extractField requires arguments.startCharacter")
        val endLine = request.arguments["endLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractField requires arguments.endLine")
        val endCharacter = request.arguments["endCharacter"]?.toIntOrNull()
            ?: return notImplemented(request, "extractField requires arguments.endCharacter")
        val name = request.arguments["name"]
            ?: return notImplemented(request, "extractField requires arguments.name")
        val selection = SourceRange(SourcePosition(startLine, startCharacter), SourcePosition(endLine, endCharacter))
        return JavaExtractConstantPlanner(this).previewInstanceField(
            request.snapshot,
            JavaExtractConstantRequest(java.nio.file.Paths.get(file), selection, name),
        )
    }

    private fun applyExtractParameter(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return notImplemented(request, "extractParameter requires arguments.file")
        val startLine = request.arguments["startLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractParameter requires arguments.startLine")
        val startCharacter = request.arguments["startCharacter"]?.toIntOrNull()
            ?: return notImplemented(request, "extractParameter requires arguments.startCharacter")
        val endLine = request.arguments["endLine"]?.toIntOrNull()
            ?: return notImplemented(request, "extractParameter requires arguments.endLine")
        val endCharacter = request.arguments["endCharacter"]?.toIntOrNull()
            ?: return notImplemented(request, "extractParameter requires arguments.endCharacter")
        val name = request.arguments["name"]
            ?: return notImplemented(request, "extractParameter requires arguments.name")
        val selection = SourceRange(SourcePosition(startLine, startCharacter), SourcePosition(endLine, endCharacter))
        return JavaExtractParameterPlanner(this).preview(
            request.snapshot,
            JavaExtractParameterRequest(java.nio.file.Paths.get(file), selection, name),
        )
    }

    private fun applyExtractInterface(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "extractInterface requires a JDT-bound class symbol")
        val file = request.arguments["file"]
            ?: return notImplemented(request, "extractInterface requires arguments.file")
        val interfaceName = request.arguments["interfaceName"]
            ?: return notImplemented(request, "extractInterface requires arguments.interfaceName")
        val methodName = request.arguments["methodName"]
            ?: return notImplemented(request, "extractInterface requires arguments.methodName")
        return JavaExtractInterfacePlanner(this).preview(
            request.snapshot,
            JavaExtractInterfaceRequest(Path.of(file), symbol, interfaceName, methodName),
        )
    }

    private fun applyExtractSuperclass(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "extractSuperclass requires a JDT-bound class symbol")
        val file = request.arguments["file"]
            ?: return notImplemented(request, "extractSuperclass requires arguments.file")
        val superclassName = request.arguments["superclassName"]
            ?: return notImplemented(request, "extractSuperclass requires arguments.superclassName")
        val methodName = request.arguments["methodName"]
            ?: return notImplemented(request, "extractSuperclass requires arguments.methodName")
        return JavaExtractSuperclassPlanner(this).preview(
            request.snapshot,
            JavaExtractSuperclassRequest(Path.of(file), symbol, superclassName, methodName),
        )
    }

    private fun applyMakeStatic(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "makeStatic requires a JDT-bound method symbol")
        return JavaMakeStaticPlanner(this).preview(request.snapshot, symbol)
    }

    private fun applyChangeVisibility(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "changeVisibility requires a JDT-bound method symbol")
        val visibility = request.arguments["visibility"]
            ?: return notImplemented(request, "changeVisibility requires arguments.visibility")
        return JavaChangeVisibilityPlanner(this).preview(request.snapshot, symbol, visibility)
    }

    private fun applyChangeType(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return notImplemented(request, "changeType requires an exact JDT-bound field symbol")
        val file = request.arguments["file"]?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: return notImplemented(request, "changeType requires the selected Java declaration path")
        val bindingKey = request.arguments["bindingKey"]
            ?: return notImplemented(request, "changeType requires the selected field binding key")
        val newType = request.arguments["newType"]
            ?: return notImplemented(request, "changeType requires arguments.newType")
        return JavaTypeRepairPlanner(this).preview(request.snapshot, symbol, file, bindingKey, newType)
    }

    private fun applyPullUpMember(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value ?: return applyPullUpMemberRefusal(request)
        val file = request.arguments["file"] ?: return applyPullUpMemberRefusal(request)
        val targetType = request.arguments["targetType"] ?: return applyPullUpMemberRefusal(request)
        return JavaPullUpMemberPlanner(this).preview(
            request.snapshot,
            JavaPullUpMemberRequest(Path.of(file), symbol, targetType),
        )
    }

    private fun applyPushDownMember(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value ?: return applyPushDownMemberRefusal(request)
        val file = request.arguments["file"] ?: return applyPushDownMemberRefusal(request)
        val targetType = request.arguments["targetType"] ?: return applyPushDownMemberRefusal(request)
        val path = runCatching { Path.of(file) }.getOrElse { return applyPushDownMemberRefusal(request) }
        return JavaPushDownMemberPlanner(this).preview(
            request.snapshot,
            JavaPushDownMemberRequest(path, symbol, targetType),
        )
    }

    private fun applyMoveMember(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId?.value
            ?: return refused(request, "java.moveMember.selectionRequired", "moveMember requires an exact saved JDT member selection.")
        val file = request.arguments["file"]?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: return refused(request, "java.moveMember.selectionRequired", "moveMember requires the selected member source path.")
        val bindingKey = request.arguments["bindingKey"]
            ?: return refused(request, "java.moveMember.selectionRequired", "moveMember requires the selected member binding key.")
        val memberKind = request.arguments["memberKind"]
            ?: return refused(request, "java.moveMember.memberKindUnsupported", "moveMember requires arguments.memberKind.")
        val targetType = request.arguments["targetType"]
            ?: return refused(request, "java.moveMember.targetUnavailable", "moveMember requires arguments.targetType.")
        val targetBindingKey = request.arguments["targetBindingKey"]
            ?: return refused(request, "java.moveMember.targetBindingRequired", "moveMember requires the exact target type binding key.")
        return JavaMoveMemberPlanner(this).preview(
            request.snapshot,
            JavaMoveMemberRequest(file, symbol, bindingKey, memberKind, targetType, targetBindingKey),
        )
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
        val command = JavaRefactoringPreviewCommand.OrganizeImports(java.nio.file.Paths.get(file))
        return JavaRefactoringPreviewDispatcher().preview(request.snapshot, this, command)
    }

    private fun applyBatchCleanup(request: RefactoringRequest): PatchPlan =
        JavaBatchCleanupPlanner(this).preview(request)

    private fun applyFormatFile(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return notImplemented(request, "formatFile requires arguments.file")
        return JavaFormatFilePlanner(this).preview(request.snapshot, java.nio.file.Paths.get(file), request.arguments)
    }

    private fun applySafeDelete(request: RefactoringRequest): PatchPlan {
        val symbol = request.symbolId
            ?: return notImplemented(request, "safeDelete requires symbolId")
        val force = request.arguments["force"]?.toBoolean() ?: false
        val command = JavaRefactoringPreviewCommand.SafeDelete(symbol, force)
        return JavaRefactoringPreviewDispatcher().preview(request.snapshot, this, command)
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

    /** Binding-backed inline support is intentionally limited to the planner's proven literal cell. */
    private fun applyInlineVariable(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return refused(request, "java.inlineVariable.unsupported", "inlineVariable requires a selected Java file and local name.")
        val name = request.arguments["name"]
            ?: return refused(request, "java.inlineVariable.unsupported", "inlineVariable requires a selected Java file and local name.")
        val path = runCatching { Path.of(file) }.getOrElse {
            return refused(request, "java.inlineVariable.invalidRequest", "inlineVariable received an invalid Java file path.")
        }
        return JavaInlineVariablePlanner(this).preview(request.snapshot, JavaInlineVariableRequest(path, name))
    }

    private fun applyInlineConstant(request: RefactoringRequest): PatchPlan {
        val file = request.arguments["file"]
            ?: return refused(request, "java.inlineConstant.invalidRequest", "inlineConstant requires arguments.file and arguments.name.")
        val name = request.arguments["name"]
            ?: return refused(request, "java.inlineConstant.invalidRequest", "inlineConstant requires arguments.file and arguments.name.")
        val path = runCatching { Path.of(file) }.getOrElse {
            return refused(request, "java.inlineConstant.invalidRequest", "inlineConstant received an invalid Java file path.")
        }
        return JavaInlineConstantPlanner(this).preview(request.snapshot, JavaInlineConstantRequest(path, name))
    }

    private fun applyIntroduceParameterObject(request: RefactoringRequest): PatchPlan {
        val path = request.arguments["file"]?.let { runCatching { Path.of(it).normalize() }.getOrNull() }
        val bindingKey = request.arguments["bindingKey"]?.takeIf(String::isNotBlank)
        val typeName = request.arguments["typeName"]
        val parameters = request.arguments["parameters"]?.let { encoded ->
            (runCatching { Json.parseToJsonElement(encoded) }.getOrNull() as? JsonArray)?.map {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return@let null
            }
        }
        if (path == null || bindingKey == null || typeName == null || parameters?.size != 2) {
            return refused(request, "java.introduceParameterObject.invalidRequest",
                "An exact Java method binding, type name and two parameter names are required.")
        }
        return JavaIntroduceParameterObjectPlanner(this).preview(
            request.snapshot, JavaIntroduceParameterObjectRequest(path, bindingKey, typeName, parameters),
        )
    }

    private fun applyExtractDelegate(request: RefactoringRequest): PatchPlan {
        val path = request.arguments["file"]?.let { runCatching { Path.of(it).normalize() }.getOrNull() }
        val bindingKey = request.arguments["bindingKey"]?.takeIf(String::isNotBlank)
        val typeName = request.arguments["typeName"]
        val members = request.arguments["members"]?.let { encoded ->
            (runCatching { Json.parseToJsonElement(encoded) }.getOrNull() as? JsonArray)?.map {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return@let null
            }
        }
        if (path == null || bindingKey == null || typeName == null || members == null) {
            return refused(request, "java.extractDelegate.invalidRequest",
                "An exact Java method binding, type name and a list of member names are required.")
        }
        return JavaExtractDelegatePlanner(this).preview(
            request.snapshot, JavaExtractDelegateRequest(path, bindingKey, typeName, members),
        )
    }

    private fun applyInlineMethod(request: RefactoringRequest): PatchPlan {
        val path = request.arguments["file"]?.let { runCatching { Path.of(it).normalize() }.getOrNull() }
        val key = request.arguments["bindingKey"]?.takeIf(String::isNotBlank)
        if (path == null || key == null) return refused(request, "java.inlineMethod.unsupported",
            "Inline Method requires an exact saved Java method binding.")
        return JavaInlineMethodPlanner(this).preview(request.snapshot, path, key)
    }

    /**
     * N-PULL-UP refusal-only production behavior (J1 catalogue row, REQ-JAVA-HIERARCHY-PULL-UP-REFUSAL-001).
     * pullUpMember is absent from the Java adapter; this branch fails closed deterministically with
     * the stable typed refusal code java.hierarchy.pullUp.unsupported, an empty WorkspaceEdit and
     * affected-file set, no approval, no managed-write authority lease, and no lock/WAL/transaction.
     */
    private fun applyPullUpMemberRefusal(request: RefactoringRequest): PatchPlan = refused(
        request,
        "java.hierarchy.pullUp.unsupported",
        "Hierarchy pull-up member Java refactoring is unsupported; refused deterministically with java.hierarchy.pullUp.unsupported",
    )

    /**
     * Unsupported push-down shapes retain the J1 typed-refusal behavior.
     * This branch fails closed deterministically with
     * the stable typed refusal code java.hierarchy.pushDown.unsupported, an empty WorkspaceEdit and
     * affected-file set, no approval, no managed-write authority lease, and no lock/WAL/transaction.
     */
    private fun applyPushDownMemberRefusal(request: RefactoringRequest): PatchPlan = refused(
        request,
        "java.hierarchy.pushDown.unsupported",
        "Hierarchy push-down member Java refactoring is unsupported; refused deterministically with java.hierarchy.pushDown.unsupported",
    )

    private fun refused(request: RefactoringRequest, code: String, message: String) = PatchPlan(
        operation = request.operation,
        status = PatchStatus.REFUSED,
        snapshotHash = request.snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        refusalCode = code,
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
                if (text.isNotEmpty()) lines += lineStart until lineEnd
            }
            lineStart = lineEnd + 1
            lineDepth = depth
        }

        while (i < endExclusive) {
            when {
                content[i] == '@' && depth == 0 && content.substring(lineStart, i).isBlank() -> {
                    val end = annotationEnd(content, i, endExclusive)
                    if (end > i) {
                        // Drop the annotation prefix, not its declaration; retain original offsets.
                        i = end
                        lineStart = end
                        lineDepth = depth
                    } else i++
                }
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

    /** Skip one complete leading annotation, including nested arguments and multiline literals. */
    private fun annotationEnd(content: String, start: Int, limit: Int): Int {
        fun whitespace(from: Int): Int {
            var cursor = from
            while (cursor < limit && content[cursor].isWhitespace()) cursor++
            return cursor
        }
        var cursor = start + 1
        if (cursor >= limit || !Character.isJavaIdentifierStart(content[cursor])) return start
        while (true) {
            val nameStart = cursor
            while (cursor < limit && Character.isJavaIdentifierPart(content[cursor])) cursor++
            if (content.substring(nameStart, cursor) == "interface") return start
            val next = whitespace(cursor)
            if (next >= limit || content[next] != '.') break
            cursor = whitespace(next + 1)
            if (cursor >= limit || !Character.isJavaIdentifierStart(content[cursor])) return start
        }
        val arguments = whitespace(cursor)
        if (arguments >= limit || content[arguments] != '(') return cursor
        cursor = arguments + 1
        var parentheses = 1
        while (cursor < limit) {
            when {
                startsLineComment(content, cursor) -> cursor = skipLineComment(content, cursor, limit)
                startsBlockComment(content, cursor) -> cursor = skipBlockComment(content, cursor, limit)
                content[cursor] == '"' -> cursor = skipStringLiteral(content, cursor, limit)
                content[cursor] == '\'' -> cursor = skipCharLiteral(content, cursor, limit)
                content[cursor] == '(' -> { parentheses++; cursor++ }
                content[cursor] == ')' -> {
                    parentheses--
                    cursor++
                    if (parentheses == 0) return cursor
                }
                else -> cursor++
            }
        }
        return start // Incomplete annotation: never guess where a declaration begins.
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

/**
 * Produces a binding-backed `makeStatic` plan for a deliberately small Java subset.
 * The operation keeps implicit calls from the same class, and refuses all other
 * receiver/reference shapes until their behavior can be migrated with proof.
 */
class JavaMakeStaticPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, symbol: String): PatchPlan {
        val analysis = runCatching { JdtJavaSemanticAnalyzer().analyze(snapshot) }.getOrElse { failure ->
            return refused(snapshot, "java.makeStatic.analysisUnavailable", "JDT binding analysis is unavailable: ${failure.message}")
        }
        if (analysis.warnings.isNotEmpty()) {
            return refused(snapshot, "java.makeStatic.bindingIncomplete", "JDT reported unresolved or recovered Java bindings")
        }

        val target = analysis.methods.singleOrNull { it.qualifiedName == symbol }
            ?: return refused(snapshot, "java.makeStatic.methodUnavailable", "No unique JDT-bound method matches '$symbol'")
        if (target.evidence != JdtJavaSemanticEvidence.JDT_BINDING || target.bindingKey.isBlank()) {
            return refused(snapshot, "java.makeStatic.bindingIncomplete", "The selected method has no complete JDT binding")
        }
        target.makeStaticRefusal?.let { reason ->
            return refused(snapshot, "java.makeStatic.unsupportedShape", reason)
        }
        val file = snapshot.files.singleOrNull { it.path == target.path }
            ?: return refused(snapshot, "java.makeStatic.sourceUnavailable", "The selected method source is not unique in the snapshot")
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.makeStatic.generatedSource", "Generated source cannot be changed: $reason")
        }
        val insertionOffset = target.staticModifierInsertionOffset
            ?: return refused(snapshot, "java.makeStatic.sourceUnavailable", "JDT did not provide a modifier insertion point")
        if (insertionOffset !in 0..file.content.length) {
            return refused(snapshot, "java.makeStatic.sourceUnavailable", "JDT modifier insertion point is outside the source file")
        }

        val owner = target.qualifiedName.substringBefore('#')
        val calls = analysis.invocations.filter { it.methodBindingKey == target.bindingKey }
        val unhandledReferences = analysis.references.filter { reference ->
            reference.bindingKey == target.bindingKey && calls.none { call ->
                call.path == reference.path && call.nameRange == reference.sourceRange
            }
        }
        if (unhandledReferences.isNotEmpty() || analysis.methodReferenceUses.any { it.methodBindingKey == target.bindingKey }) {
            return refused(snapshot, "java.makeStatic.methodReferenceUnsupported", "Method references or non-invocation references to the method are not supported")
        }
        if (calls.any { call ->
                call.receiverKind != JdtJavaInvocationReceiverKind.IMPLICIT ||
                    call.callerMethodQualifiedName?.substringBefore('#') != owner
            }) {
            return refused(snapshot, "java.makeStatic.callSiteUnsupported", "Only implicit call sites from the declaring class are supported")
        }

        val beforeDiagnostics = adapter.diagnostics(snapshot)
        val edit = TextEdit(TextEdits.rangeForOffset(file.content, insertionOffset, 0), " static")
        val stagedFile = file.copy(content = TextEdits.apply(file.content, listOf(edit)))
        val staged = snapshot.copy(files = snapshot.files.map { if (it.path == file.path) stagedFile else it })
        val stagedAnalysis = runCatching { JdtJavaSemanticAnalyzer().analyze(staged) }.getOrElse {
            return refused(snapshot, "java.makeStatic.stagedAnalysisUnavailable", "JDT could not validate the staged static method")
        }
        val stagedTarget = stagedAnalysis.methods.singleOrNull { it.qualifiedName == target.qualifiedName }
        val stagedCalls = stagedAnalysis.invocations.filter { it.methodQualifiedName == target.qualifiedName }
        if (stagedAnalysis.warnings.isNotEmpty() || stagedTarget == null ||
            !org.eclipse.jdt.core.dom.Modifier.isStatic(stagedTarget.modifiers) ||
            stagedCalls.size != calls.size || stagedCalls.any { it.receiverKind != JdtJavaInvocationReceiverKind.IMPLICIT }
        ) {
            return refused(snapshot, "java.makeStatic.stagedValidationFailed", "Staged JDT analysis did not preserve the selected method and its call sites")
        }

        val afterDiagnostics = adapter.diagnostics(staged)
        val baselineErrors = beforeDiagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).toSet()
        val introducedErrors = afterDiagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot { it in baselineErrors }
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.makeStatic.diagnosticsFailed", "Static conversion introduces ${introducedErrors.size} diagnostic error(s)")
        }

        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.98,
            requiresUserApproval = true,
            summary = "Make $symbol static without changing its implicit same-class call sites.",
            affectedFiles = setOf(file.path),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.path, listOf(edit)))),
            diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics,
            warnings = listOf("Static conversion can change reflective behavior; review this high-risk change before applying."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String =
        "${diagnostic.severity}|${diagnostic.code}|${diagnostic.message}"

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.JDT_BINDING,
        refusalCode = code,
    )

    companion object {
        const val OPERATION = "makeStatic"
    }
}

/**
 * A deliberately bounded visibility repair. Only an explicit promotion to public
 * is supported, and instance methods must be final so the visibility change cannot
 * open a new override dispatch path.
 */
class JavaChangeVisibilityPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, symbol: String, requestedVisibility: String): PatchPlan {
        if (requestedVisibility != "public") {
            return refused(snapshot, "java.changeVisibility.targetUnsupported", "The first changeVisibility cell supports promotion to public only.")
        }
        val analysis = runCatching { JdtJavaSemanticAnalyzer().analyze(snapshot) }.getOrElse {
            return refused(snapshot, "java.changeVisibility.analysisUnavailable", "JDT binding analysis is unavailable for the selected snapshot.")
        }
        if (analysis.warnings.isNotEmpty()) {
            return refused(snapshot, "java.changeVisibility.bindingIncomplete", "JDT reported unresolved or recovered Java bindings.")
        }
        val target = analysis.methods.singleOrNull { it.qualifiedName == symbol }
            ?: return refused(snapshot, "java.changeVisibility.methodUnavailable", "No unique JDT-bound method matches '$symbol'.")
        if (target.evidence != JdtJavaSemanticEvidence.JDT_BINDING || target.bindingKey.isBlank() || target.constructor) {
            return refused(snapshot, "java.changeVisibility.bindingIncomplete", "The selected method has no complete editable JDT method binding.")
        }
        val file = snapshot.files.singleOrNull { it.path.normalize() == target.path.normalize() }
            ?: return refused(snapshot, "java.changeVisibility.sourceUnavailable", "The selected Java method source is absent or ambiguous.")
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.changeVisibility.generatedSource", "Generated Java source cannot be changed: $reason")
        }
        val beforeDiagnostics = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.changeVisibility.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, file.path) { unit, _ ->
            previewBound(snapshot, file, target, requestedVisibility, beforeDiagnostics, unit)
        }
        return when (resolution) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> refused(
                snapshot,
                "java.changeVisibility.analysisUnavailable",
                "A complete SDK-bound Java source-set environment is unavailable for the selected method.",
            )
        }
    }

    private fun previewBound(
        snapshot: ProjectSnapshot,
        file: SourceFile,
        target: JdtJavaSemanticMethod,
        requestedVisibility: String,
        beforeDiagnostics: List<Diagnostic>,
        unit: org.eclipse.jdt.core.dom.CompilationUnit,
    ): PatchPlan {
        val content = file.content
        val method = unit.types().filterIsInstance<org.eclipse.jdt.core.dom.TypeDeclaration>()
            .flatMap { type -> type.bodyDeclarations().filterIsInstance<org.eclipse.jdt.core.dom.MethodDeclaration>() }
            .singleOrNull { declaration -> declaration.resolveBinding()?.key == target.bindingKey }
            ?: return refused(snapshot, "java.changeVisibility.methodAmbiguous", "The selected JDT method must identify one source declaration in the saved compilation unit.")
        val owner = method.parent as? org.eclipse.jdt.core.dom.TypeDeclaration
            ?: return refused(snapshot, "java.changeVisibility.ownerUnsupported", "Only a method declared directly by an ordinary top-level class is supported.")
        val ownerBinding = owner.resolveBinding()
        val binding = method.resolveBinding()
        if (owner.parent !== unit || unit.types().size != 1 || owner.isInterface || ownerBinding == null ||
            ownerBinding.isRecovered || ownerBinding.key.isNullOrBlank() ||
            owner.superclassType != null || owner.superInterfaceTypes().isNotEmpty() || owner.typeParameters().isNotEmpty() ||
            owner.modifiers().any { it.toString() == "sealed" || it.toString() == "non-sealed" } ||
            method.flags and (org.eclipse.jdt.core.dom.ASTNode.MALFORMED or org.eclipse.jdt.core.dom.ASTNode.RECOVERED) != 0 ||
            binding == null || binding.isRecovered || binding.key != target.bindingKey || binding.declaringClass?.key != ownerBinding.key) {
            return refused(snapshot, "java.changeVisibility.bindingIncomplete", "The selected method owner or JDT method binding is not complete and unique.")
        }
        if (method.isConstructor || method.body == null || method.typeParameters().isNotEmpty() ||
            method.modifiers().any { it is org.eclipse.jdt.core.dom.Annotation }) {
            return refused(snapshot, "java.changeVisibility.declarationUnsupported", "Only an annotated-free concrete method on a top-level ordinary class is supported.")
        }
        val flags = binding.modifiers
        if (org.eclipse.jdt.core.dom.Modifier.isPublic(flags)) {
            return refused(snapshot, "java.changeVisibility.alreadyPublic", "The selected method is already public.")
        }
        if (!org.eclipse.jdt.core.dom.Modifier.isPrivate(flags) && !org.eclipse.jdt.core.dom.Modifier.isProtected(flags) &&
            method.modifiers().filterIsInstance<org.eclipse.jdt.core.dom.Modifier>().any {
                it.keyword == org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.PUBLIC_KEYWORD ||
                    it.keyword == org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.PROTECTED_KEYWORD ||
                    it.keyword == org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.PRIVATE_KEYWORD
            }) {
            return refused(snapshot, "java.changeVisibility.visibilityUnsupported", "The selected method has an unsupported explicit visibility modifier.")
        }
        if (!org.eclipse.jdt.core.dom.Modifier.isStatic(flags) && !org.eclipse.jdt.core.dom.Modifier.isFinal(flags) &&
            !org.eclipse.jdt.core.dom.Modifier.isFinal(ownerBinding.modifiers)) {
            return refused(snapshot, "java.changeVisibility.overrideClosureUnsupported", "The method is not final; widening access could introduce an unproven external override path.")
        }
        val returnTypeBinding = binding.returnType
        if (binding.parameterTypes.any { it.isRecovered } || returnTypeBinding == null || returnTypeBinding.isRecovered) {
            return refused(snapshot, "java.changeVisibility.bindingIncomplete", "The method signature contains an unresolved or recovered type binding.")
        }
        val sourceRoot = snapshot.owningBuildSourceRoots(file.path).singleOrNull()
            ?: return refused(snapshot, "java.changeVisibility.sourceRootUnavailable", "The selected method has no unique effective Java source-set root.")
        if (sourceRoot.generated || sourceRoot.modelStatus != org.refactorkit.core.BuildModelStatus.AVAILABLE) {
            return refused(snapshot, "java.changeVisibility.sourceRootUnavailable", "Visibility repair requires an available non-generated Java source set.")
        }

        val explicitVisibility = method.modifiers().filterIsInstance<org.eclipse.jdt.core.dom.Modifier>()
            .filter { modifier ->
                modifier.keyword == org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.PRIVATE_KEYWORD ||
                    modifier.keyword == org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.PROTECTED_KEYWORD ||
                    modifier.keyword == org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.PUBLIC_KEYWORD
            }
        if (explicitVisibility.size > 1) {
            return refused(snapshot, "java.changeVisibility.declarationUnsupported", "The selected declaration has conflicting visibility modifiers.")
        }
        val edit = if (explicitVisibility.isNotEmpty()) {
            val modifier = explicitVisibility.single()
            if (modifier.keyword == org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.PUBLIC_KEYWORD) {
                return refused(snapshot, "java.changeVisibility.alreadyPublic", "The selected method is already public.")
            }
            val start = modifier.startPosition
            val end = start + modifier.length
            if (start < 0 || end < start || end > content.length) {
                return refused(snapshot, "java.changeVisibility.sourceUnavailable", "JDT's visibility source range is outside the saved file.")
            }
            val oldText = content.substring(start, end)
            if (oldText !in setOf("private", "protected")) {
                return refused(snapshot, "java.changeVisibility.declarationUnsupported", "JDT could not identify a stable visibility token in the saved source.")
            }
            TextEdit(TextEdits.rangeForOffset(content, start, modifier.length), requestedVisibility)
        } else {
            val offset = (method.modifiers().filterIsInstance<org.eclipse.jdt.core.dom.Modifier>().minOfOrNull { it.startPosition }
                ?: method.returnType2?.startPosition)
                ?: return refused(snapshot, "java.changeVisibility.sourceUnavailable", "JDT did not provide a stable method modifier insertion point.")
            if (offset !in 0..content.length) {
                return refused(snapshot, "java.changeVisibility.sourceUnavailable", "JDT's visibility insertion point is outside the saved source.")
            }
            TextEdit(TextEdits.rangeForOffset(content, offset, 0), "$requestedVisibility ")
        }
        val stagedContent = runCatching { TextEdits.apply(content, listOf(edit)) }.getOrElse {
            return refused(snapshot, "java.changeVisibility.editInvalid", "The visibility edit does not fit the exact saved source.")
        }
        val staged = snapshot.copy(files = snapshot.files.map { candidate ->
            if (candidate.path.normalize() == file.path.normalize()) candidate.copy(content = stagedContent) else candidate
        })
        val stagedBindingProof = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(staged, file.path) { stagedUnit, _ ->
            val stagedMethods = stagedUnit.types().filterIsInstance<org.eclipse.jdt.core.dom.TypeDeclaration>()
                .flatMap { type -> type.bodyDeclarations().filterIsInstance<org.eclipse.jdt.core.dom.MethodDeclaration>() }
            val stagedMethod = stagedMethods.singleOrNull { it.resolveBinding()?.key == target.bindingKey }
                ?: return@withBoundCompilationUnit false
            val stagedBinding = stagedMethod.resolveBinding() ?: return@withBoundCompilationUnit false
            stagedBinding.key == target.bindingKey && org.eclipse.jdt.core.dom.Modifier.isPublic(stagedBinding.modifiers)
        }
        if (stagedBindingProof !is JdtBoundCompilationUnitResolution.Available || !stagedBindingProof.value) {
            return refused(snapshot, "java.changeVisibility.stagedValidationFailed", "Staged JDT analysis did not preserve the exact method binding with public visibility.")
        }
        val afterDiagnostics = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.changeVisibility.stagedDiagnosticsUnavailable", "Java diagnostics are unavailable for the staged source.")
        }
        val baselineErrors = beforeDiagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).toSet()
        val introducedErrors = afterDiagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot { it in baselineErrors }
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.changeVisibility.diagnosticsFailed", "Visibility widening introduces ${introducedErrors.size} diagnostic error(s).")
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.98,
            requiresUserApproval = true,
            summary = "Promote ${target.qualifiedName} to public visibility without changing its binding or dispatch eligibility.",
            affectedFiles = setOf(file.path),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.path, listOf(edit)))),
            diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics,
            warnings = listOf("Public visibility expands the source and reflective API; review external consumers before applying."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String =
        "${diagnostic.severity}|${diagnostic.code}|${diagnostic.message}"

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.JDT_BINDING,
        refusalCode = code,
    )

    companion object {
        const val OPERATION = "changeVisibility"
    }
}
