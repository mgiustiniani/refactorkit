package org.refactorkit.java

import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.refactorkit.core.BuildFactAvailability
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityEvidenceCompleteness
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Reference
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Generates a [PatchPlan] for safe-deleting a Java type.
 *
 * Rules:
 * - Refuses deletion if Java references or captured workspace-text references exist, except
 *   a Maven-POM-only text match after clean JDT analysis, which yields a review-only plan.
 * - Requires complete captured-text scan evidence even in forced mode.
 * - In forced mode (`force = true`) deletion is allowed when references exist only
 *   after complete scanning, but a HIGH-risk warning is emitted.
 * - Only deletes the source file; build configuration changes are out of scope.
 */
class JavaSafeDeletePlanner(private val adapter: JavaLanguageAdapter) {

    fun preview(snapshot: ProjectSnapshot, symbolFqn: String, force: Boolean = false): PatchPlan {
        val simpleName = JavaPackageUtil.simpleName(symbolFqn)
        val oldPkg = JavaPackageUtil.packageOf(symbolFqn)

        val index = adapter.buildSymbols(snapshot)
        val symbol = index.symbols.find { it.id.value == symbolFqn && it.kind in DELETEABLE_KINDS }
            ?: return refused(snapshot, "safeDelete", "Symbol not found: $symbolFqn")

        val declarationFile = snapshot.files.find { it.path == symbol.location.path }
            ?: return refused(snapshot, "safeDelete", "Declaration file not found: ${symbol.location.path}")
        JavaGeneratedSourcePolicy.reason(declarationFile)?.let { reason ->
            return refused(snapshot, "safeDelete", "Generated source cannot be deleted: ${declarationFile.path} ($reason)")
        }
        if (!hasCompleteWorkspaceTextReferenceScan(snapshot)) {
            return refused(
                snapshot,
                "safeDelete",
                "Safe delete requires complete workspace text-reference scan evidence.",
            )
        }

        val frameworkAssessment = JavaFrameworkDetector.assess(declarationFile)

        // Prefer exact JDT type-binding references when semantic analysis is clean.
        val semanticReferences = findJdtTypeReferences(snapshot, symbolFqn, declarationFile.path)
        val javaReferences = semanticReferences
            ?: findReferences(snapshot, symbolFqn, simpleName, oldPkg, declarationFile.path)
        val textReferences = findWorkspaceTextReferences(snapshot, symbolFqn, simpleName, declarationFile.path)
        val references = (javaReferences + textReferences).distinctBy {
            "${it.location.path}:${it.location.range.start.line}:${it.location.range.start.character}"
        }
        val javaSourcePaths = snapshot.files.asSequence()
            .filter { it.languageId == "java" }
            .map { it.path.normalize() }
            .toList()
        val capturedMavenPomPaths = snapshot.modules.asSequence()
            .filter {
                it.languageSettings["java.buildSystem"] == "maven" &&
                    it.languageSettings["java.buildModel.status"] == "available" &&
                    it.sourceRoots.asSequence().map(Path::normalize).any { sourceRoot ->
                        javaSourcePaths.any { sourcePath -> sourcePath.startsWith(sourceRoot) }
                    }
            }
            .flatMap { module ->
                buildList {
                    add(module.languageSettings["java.maven.pomPath"])
                    val modelPomCount = module.languageSettings[JAVA_MAVEN_MODEL_POM_INPUT_COUNT_KEY]
                        ?.toIntOrNull()?.coerceIn(0, 1_024) ?: 0
                    repeat(modelPomCount) { index ->
                        add(module.languageSettings["$JAVA_MAVEN_MODEL_POM_INPUT_PREFIX$index"])
                    }
                }.asSequence()
            }
            .mapNotNull { capturedPath ->
                capturedPath?.let { path -> runCatching { Path.of(path).normalize() }.getOrNull() }
            }
            .toSet()
        val mavenPomOnlyReview = !force &&
            semanticReferences != null && semanticReferences.isEmpty() &&
            textReferences.isNotEmpty() &&
            textReferences.all { reference ->
                reference.location.path.normalize() in capturedMavenPomPaths &&
                    snapshot.auxiliaryFiles.any { it.path == reference.location.path }
            } && capturedMavenPomPaths.isNotEmpty()
        val referenceEvidence = if (semanticReferences != null) "JDT binding" else "lexical fallback"
        val textEvidence = if (textReferences.isNotEmpty()) " and workspace text scan evidence" else ""

        if (references.isNotEmpty() && !force && !mavenPomOnlyReview) {
            val refList = references.take(20).joinToString("\n") { ref ->
                "  ${ref.location.path}:${ref.location.range.start.line + 1}"
            }
            val extra = if (references.size > 20) "\n  ... and ${references.size - 20} more" else ""
            return refused(
                snapshot, "safeDelete",
                "Cannot delete $symbolFqn: ${references.size} reference(s) found using $referenceEvidence evidence$textEvidence.\n$refList$extra\n" +
                    "Use --force to delete anyway (dangerous).",
            )
        }

        val warnings = mutableListOf<String>()
        if (force && references.isNotEmpty()) {
            warnings += "Forced delete: ${references.size} reference(s) were found and ignored. This will break the build."
        }
        if (mavenPomOnlyReview) {
            val locations = textReferences.joinToString(", ") { reference ->
                "${reference.location.path}:${reference.location.range.start.line + 1}"
            }
            warnings += "Review-only proposal: Maven POM literal reference(s) at $locations remain for manual review; complete project-wide reference analysis is not advertised."
        }
        warnings += if (semanticReferences != null) {
            "Java source references were evaluated using exact JDT type-binding evidence."
        } else {
            "JDT type-binding evidence was unavailable or not clean; Java source references use lexical fallback. Review carefully."
        }
        val managedClosureLease = managedMavenClosureLease(
            snapshot = snapshot,
            symbolFqn = symbolFqn,
            declarationFile = declarationFile,
            semanticReferences = semanticReferences,
            textReferences = textReferences,
            frameworkFindings = frameworkAssessment.hasFindings,
            forced = force,
            mavenPomOnlyReview = mavenPomOnlyReview,
        )
        if (managedClosureLease == null) {
            warnings += "Captured workspace text inputs were scanned for literal target type names; non-literal or dynamic references are not resolved."
        } else {
            warnings += "Snapshot-bound Maven source and captured-text closure was verified; downstream and external consumers remain outside this workspace authority."
        }
        warnings += frameworkAssessment.warnings("safeDelete")
        val diagnosticsAfterDelete = adapter.diagnostics(snapshot.copy(
            files = snapshot.files.filterNot { it.path == declarationFile.path },
        ))

        return PatchPlan(
            operation = "safeDelete",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = if (references.isEmpty()) 1.0 else 0.3,
            requiresUserApproval = true,
            summary = if (mavenPomOnlyReview) {
                "Review-only proposal: delete $symbolFqn (${declarationFile.path}); Maven POM references remain and complete project-wide analysis is unavailable."
            } else {
                "Delete $symbolFqn (${declarationFile.path})."
            },
            affectedFiles = setOf(declarationFile.path),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Delete(declarationFile.path))),
            diagnosticsAfterPreview = diagnosticsAfterDelete,
            warnings = warnings,
            riskLevel = if (managedClosureLease != null || references.isNotEmpty() || frameworkAssessment.hasFindings || mavenPomOnlyReview) RiskLevel.HIGH else RiskLevel.LOW,
            evidence = if (semanticReferences != null && !mavenPomOnlyReview) RefactoringEvidence.JDT_BINDING else RefactoringEvidence.LEXICAL_FALLBACK,
            authorityLease = managedClosureLease,
        )
    }

    private fun managedMavenClosureLease(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        declarationFile: org.refactorkit.core.SourceFile,
        semanticReferences: List<Reference>?,
        textReferences: List<Reference>,
        frameworkFindings: Boolean,
        forced: Boolean,
        mavenPomOnlyReview: Boolean,
    ): OperationAuthorityLease? {
        if (forced || mavenPomOnlyReview || frameworkFindings || semanticReferences == null ||
            semanticReferences.isNotEmpty() || textReferences.isNotEmpty()
        ) return null
        if (!isPackagePrivateTopLevelClass(declarationFile.content, symbolFqn)) return null
        if (!hasCompleteMavenSourceClosure(snapshot, declarationFile.path)) return null
        if (snapshot.files.any(::containsDynamicLookupSurface)) return null
        if (hasJavaStringConsumer(snapshot, symbolFqn, declarationFile.path)) return null
        val capturedPaths = try {
            OwnedSourceInventoryReader.captureWorkspaceFilePaths(snapshot)
        } catch (_: OwnedSourceCaptureException) {
            return null
        }
        if (capturedPaths != snapshot.trackedFiles.mapTo(hashSetOf()) { it.path.normalize() }) return null

        val model = snapshot.buildModels.singleOrNull { it.providerId == MAVEN_EFFECTIVE_PROVIDER }
            ?: return null
        val moduleEvidence = model.modules.sortedBy { it.root.toString() }.joinToString("\n") {
            "${it.id}|${it.root}|${it.sourceSets.map(BuildSourceSet::id).sorted().joinToString(",")}"
        }
        val evidenceHash = MessageDigest.getInstance("SHA-256").digest(
            "java-safe-delete-maven-reactor-v1\n${snapshot.hash}\n$symbolFqn\n$moduleEvidence".toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        val edit = WorkspaceEdit(listOf(FileEdit.Delete(declarationFile.path)))
        return OperationAuthorityLease(
            kind = MANAGED_CLOSURE_AUTHORITY_KIND,
            operation = "safeDelete",
            snapshotHash = snapshot.hash,
            evidenceHash = evidenceHash,
            evidenceCompleteness = OperationAuthorityEvidenceCompleteness.COMPLETE,
            workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit),
            requiredClasspathEvidence = snapshot.classpathEvidence,
            attributes = mapOf(
                OperationAuthorityLease.REQUIRE_EXACT_WORKSPACE_INVENTORY to "true",
                AUTHORITY_KIND_ATTRIBUTE to MANAGED_CLOSURE_AUTHORITY_KIND,
                REFERENCE_CLOSURE_ATTRIBUTE to MANAGED_REFERENCE_CLOSURE,
                COMPLETE_PROJECT_ATTRIBUTE to "true",
                TARGET_SYMBOL_ATTRIBUTE to symbolFqn,
                TARGET_PATH_ATTRIBUTE to declarationFile.path.normalize().toString(),
                MODEL_PROVIDER_ATTRIBUTE to MAVEN_EFFECTIVE_PROVIDER,
                AUTHORITY_VALUE_ATTRIBUTE to SNAPSHOT_BOUND_AUTHORITY,
            ),
        )
    }

    private fun hasCompleteMavenSourceClosure(snapshot: ProjectSnapshot, declarationPath: Path): Boolean {
        if (snapshot.files.any { it.languageId != "java" }) return false
        val model = snapshot.buildModels.singleOrNull { it.providerId == MAVEN_EFFECTIVE_PROVIDER }
            ?: return false
        if (snapshot.buildModels.size != 1 || model.status != BuildModelStatus.AVAILABLE || model.diagnostics.isNotEmpty() ||
            model.attributes["buildCodeExecution"] != "denied" || model.attributes["credentialsAccess"] != "denied" ||
            model.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_VERSION_KEY] != JavaSafeDeleteReferenceAnalysis.SCOPE ||
            model.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_STATUS_KEY] != "complete" ||
            model.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_BINARY_INPUTS_KEY]?.toIntOrNull() != 0 ||
            model.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_UNMODELED_INPUTS_KEY]?.toIntOrNull() != 0
        ) return false
        val workspaceRoot = snapshot.workspace.root.toAbsolutePath().normalize()
        val modules = snapshot.modules.filter { it.languageSettings["java.buildSystem"] == "maven" }
        if (modules.size < 2 || modules.size != snapshot.modules.size || model.modules.size != modules.size) return false
        val moduleRoots = model.modules.map { it.root.toAbsolutePath().normalize() }.toSet()
        if (moduleRoots.size != modules.size || moduleRoots.any { !it.startsWith(workspaceRoot) }) return false
        if (modules.any { module ->
                module.root.toAbsolutePath().normalize() !in moduleRoots ||
                    model.modules.none { it.id == module.name && it.root.toAbsolutePath().normalize() == module.root.toAbsolutePath().normalize() }
            }
        ) return false
        val sourceSets = model.modules.flatMap { it.sourceSets }
        if (sourceSets.none { it.kind == SourceSetKind.MAIN && it.sourceRoots.isNotEmpty() }) return false
        if (sourceSets.any { sourceSet ->
                sourceSet.generatedSourceRoots.isNotEmpty() ||
                    sourceSet.sourceRootEvidence.size != sourceSet.sourceRoots.size ||
                    sourceSet.sourceRootEvidence.any {
                        it.availability != BuildFactAvailability.COMPLETE ||
                            it.providerId != MAVEN_EFFECTIVE_PROVIDER || it.origin == org.refactorkit.core.BuildSourceRootOrigin.GENERATED
                    } ||
                    sourceSet.compilerOptionsAvailability != BuildFactAvailability.COMPLETE ||
                    sourceSet.resolvedArtifactAvailability != BuildFactAvailability.COMPLETE ||
                    sourceSet.annotationProcessors?.let {
                        it.availability != BuildFactAvailability.COMPLETE || !it.mode.equals("none", ignoreCase = true) ||
                            it.processorPath.isNotEmpty() || it.processorPathEntries.isNotEmpty() || it.processorNames.isNotEmpty()
                    } != false
            }
        ) return false
        val javaFiles = snapshot.files.filter { it.languageId == "java" }
        if (javaFiles.isEmpty() || javaFiles.any { source ->
                val owners = snapshot.owningBuildSourceRoots(source.path)
                owners.size != 1 || owners.single().providerId != MAVEN_EFFECTIVE_PROVIDER ||
                    owners.single().modelStatus != BuildModelStatus.AVAILABLE || owners.single().generated
            }
        ) return false
        val targetOwners = snapshot.owningBuildSourceRoots(declarationPath)
        return targetOwners.singleOrNull()?.let {
            it.providerId == MAVEN_EFFECTIVE_PROVIDER && it.modelStatus == BuildModelStatus.AVAILABLE &&
                !it.generated && it.sourceSet.kind == SourceSetKind.MAIN
        } == true && model.modules.count { module -> module.sourceSets.any { set ->
            set.kind == SourceSetKind.MAIN && set.sourceRoots.isNotEmpty()
        } } >= 2
    }

    private fun isPackagePrivateTopLevelClass(source: String, symbolFqn: String): Boolean = runCatching {
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setSource(source.toCharArray())
        parser.setResolveBindings(false)
        val unit = parser.createAST(null) as org.eclipse.jdt.core.dom.CompilationUnit
        val expectedPackage = JavaPackageUtil.packageOf(symbolFqn)
        if ((unit.`package`?.name?.fullyQualifiedName.orEmpty()) != expectedPackage) return false
        val topLevelTypes = unit.types().filterIsInstance<AbstractTypeDeclaration>()
        val target = topLevelTypes.singleOrNull { it.name.identifier == JavaPackageUtil.simpleName(symbolFqn) }
            ?: return false
        if (target !is TypeDeclaration || target.isInterface) return false
        val modifiers = target.modifiers().filterIsInstance<Modifier>()
        modifiers.none { it.isPublic || it.isProtected || it.isPrivate } && target.modifiers().none {
            it is org.eclipse.jdt.core.dom.Annotation
        }
    }.getOrDefault(false)

    private fun containsDynamicLookupSurface(file: org.refactorkit.core.SourceFile): Boolean =
        DYNAMIC_LOOKUP_PATTERN.containsMatchIn(file.content)

    private fun hasJavaStringConsumer(snapshot: ProjectSnapshot, symbolFqn: String, declarationPath: Path): Boolean {
        val simpleName = JavaPackageUtil.simpleName(symbolFqn)
        val namePattern = Regex("(?<![A-Za-z0-9_$])(?:${Regex.escape(simpleName)}|${Regex.escape(symbolFqn)})(?![A-Za-z0-9_$])")
        return snapshot.files.asSequence()
            .filter { it.languageId == "java" && it.path != declarationPath }
            .flatMap { JAVA_STRING_LITERAL.findAll(it.content).asSequence() }
            .any { literal -> namePattern.containsMatchIn(literal.value) }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun hasCompleteWorkspaceTextReferenceScan(snapshot: ProjectSnapshot): Boolean {
        if (snapshot.buildModels.isEmpty()) return false
        return snapshot.buildModels.all { model ->
            model.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_STATUS_KEY] == "complete" &&
                model.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_VERSION_KEY] == "workspace-text-v1"
        }
    }

    private fun findWorkspaceTextReferences(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        simpleName: String,
        declarationPath: Path,
    ): List<Reference> {
        val token = Regex("(?<![A-Za-z0-9_$])${Regex.escape(simpleName)}(?![A-Za-z0-9_$])")
        return snapshot.auxiliaryFiles.asSequence()
            .filter { it.path != declarationPath && it.languageId != "refactorkit-expected-evidence" }
            .flatMap { file ->
                val contentReferences = file.content.lineSequence().mapIndexedNotNull { lineIndex, line ->
                    val match = token.find(line) ?: return@mapIndexedNotNull null
                    val start = SourcePosition(lineIndex, match.range.first)
                    Reference(
                        symbolId = SymbolId(symbolFqn),
                        location = SourceLocation(file.path, SourceRange(start, SourcePosition(lineIndex, match.range.last + 1))),
                    )
                }
                val pathReference = if (token.containsMatchIn(file.path.toString())) {
                    sequenceOf(
                        Reference(
                            symbolId = SymbolId(symbolFqn),
                            location = SourceLocation(file.path, SourceRange(SourcePosition(0, 0), SourcePosition(0, 0))),
                        ),
                    )
                } else {
                    emptySequence()
                }
                contentReferences + pathReference
            }
            .distinctBy { "${it.location.path}:${it.location.range.start.line}" }
            .sortedWith(compareBy<Reference> { it.location.path.toString() }.thenBy { it.location.range.start.line })
            .toList()
    }

    private fun findJdtTypeReferences(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        declarationPath: Path,
    ): List<Reference>? {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        if (analysis.warnings.isNotEmpty()) return null
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == symbolFqn && symbol.kind in JDT_DELETEABLE_KINDS
        } ?: return null
        val bindingKey = target.bindingKey ?: return null
        return analysis.references
            .filter { it.bindingKey == bindingKey && it.path != declarationPath }
            .map { reference ->
                Reference(
                    symbolId = SymbolId(symbolFqn),
                    location = SourceLocation(reference.path, reference.sourceRange),
                )
            }
            .distinctBy {
                "${it.location.path}:${it.location.range.start.line}:${it.location.range.start.character}:${it.location.range.end.character}"
            }
            .sortedWith(compareBy<Reference> { it.location.path.toString() }
                .thenBy { it.location.range.start.line }
                .thenBy { it.location.range.start.character })
    }

    private fun findReferences(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        simpleName: String,
        pkg: String,
        declarationPath: Path,
    ): List<Reference> {
        val results = mutableListOf<Reference>()
        for (file in snapshot.files) {
            if (file.path == declarationPath || file.languageId != "java") continue

            val filePkg = JavaPackageUtil.extractPackage(file.content)
            val hasImport = file.content.contains("import $symbolFqn;")
            val hasFqn = file.content.contains(symbolFqn)
            val isSamePkg = filePkg == pkg && pkg.isNotEmpty()

            if (!hasImport && !hasFqn && !isSamePkg) continue

            // Look for FQN
            for (range in JavaLexer.findOccurrences(file.content, symbolFqn)) {
                val pos = TextEdits.positionForOffset(file.content, range.first)
                results += Reference(
                    symbolId = SymbolId(symbolFqn),
                    location = SourceLocation(file.path, SourceRange(pos, pos)),
                )
            }

            // Look for simple name in same-package or importing files
            if (hasImport || isSamePkg) {
                for (range in JavaLexer.findOccurrences(file.content, simpleName)) {
                    val pos = TextEdits.positionForOffset(file.content, range.first)
                    results += Reference(
                        symbolId = SymbolId(symbolFqn),
                        location = SourceLocation(file.path, SourceRange(pos, pos)),
                    )
                }
            }
        }
        return results.distinctBy { "${it.location.path}:${it.location.range.start.line}" }
    }

    private fun refused(snapshot: ProjectSnapshot, operation: String, reason: String) = PatchPlan(
        operation = operation,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
    )

    companion object {
        private const val MAVEN_EFFECTIVE_PROVIDER = "maven-effective-v1"
        const val MANAGED_CLOSURE_AUTHORITY_KIND = "java-safe-delete-maven-reactor-v1"
        const val AUTHORITY_KIND_ATTRIBUTE = "safeDelete.authorityKind"
        const val REFERENCE_CLOSURE_ATTRIBUTE = "safeDelete.referenceClosure"
        const val COMPLETE_PROJECT_ATTRIBUTE = "safeDelete.completeProject"
        const val TARGET_SYMBOL_ATTRIBUTE = "safeDelete.targetSymbol"
        const val TARGET_PATH_ATTRIBUTE = "safeDelete.targetPath"
        const val MODEL_PROVIDER_ATTRIBUTE = "safeDelete.modelProvider"
        const val AUTHORITY_VALUE_ATTRIBUTE = "safeDelete.managedApplyAuthority"
        const val MANAGED_REFERENCE_CLOSURE = "maven-reactor-complete"
        const val SNAPSHOT_BOUND_AUTHORITY = "snapshot-bound"
        private val JAVA_STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
        private val DYNAMIC_LOOKUP_PATTERN = Regex(
            "\\b(?:Class\\s*\\.\\s*forName|ClassLoader|URLClassLoader|loadClass\\s*\\(|MethodHandles?\\s*\\.|ServiceLoader\\s*\\.\\s*load)\\b",
        )
        private val DELETEABLE_KINDS = setOf(
            Symbol.Kind.CLASS,
            Symbol.Kind.INTERFACE,
            Symbol.Kind.ENUM,
            Symbol.Kind.RECORD,
            Symbol.Kind.ANNOTATION,
        )
        private val JDT_DELETEABLE_KINDS = setOf(
            JdtJavaSemanticSymbolKind.CLASS,
            JdtJavaSemanticSymbolKind.INTERFACE,
            JdtJavaSemanticSymbolKind.ENUM,
            JdtJavaSemanticSymbolKind.RECORD,
            JdtJavaSemanticSymbolKind.ANNOTATION,
        )
    }
}

/** Shared fail-closed boundary for managed Java safeDelete entry points. */
object JavaSafeDeleteManagedApplyPolicy {
    const val REFUSAL_CODE = "java.safeDelete.referenceClosureIncomplete"
    const val REFUSAL_MESSAGE =
        "Managed safeDelete is unavailable because project, dynamic, framework, and external reference closure is incomplete."

    fun isSnapshotBoundMavenClosure(plan: PatchPlan): Boolean {
        val lease = plan.authorityLease ?: return false
        val attributes = lease.attributes
        val delete = plan.workspaceEdit.edits.singleOrNull() as? FileEdit.Delete ?: return false
        return plan.operation == "safeDelete" && plan.status == PatchStatus.PREVIEW &&
            plan.requiresUserApproval && plan.affectedFiles == setOf(delete.path) &&
            plan.evidence == RefactoringEvidence.JDT_BINDING &&
            lease.operation == plan.operation && lease.snapshotHash == plan.snapshotHash &&
            lease.evidenceCompleteness == OperationAuthorityEvidenceCompleteness.COMPLETE &&
            lease.workspaceEditSha256 == WorkspaceEditIdentity.sha256(plan.workspaceEdit) &&
            lease.requiresExactWorkspaceInventory &&
            attributes[JavaSafeDeletePlanner.AUTHORITY_KIND_ATTRIBUTE] == JavaSafeDeletePlanner.MANAGED_CLOSURE_AUTHORITY_KIND &&
            attributes[JavaSafeDeletePlanner.REFERENCE_CLOSURE_ATTRIBUTE] == JavaSafeDeletePlanner.MANAGED_REFERENCE_CLOSURE &&
            attributes[JavaSafeDeletePlanner.COMPLETE_PROJECT_ATTRIBUTE] == "true" &&
            attributes[JavaSafeDeletePlanner.MODEL_PROVIDER_ATTRIBUTE] == "maven-effective-v1" &&
            attributes[JavaSafeDeletePlanner.TARGET_SYMBOL_ATTRIBUTE].isNullOrBlank().not() &&
            attributes[JavaSafeDeletePlanner.TARGET_PATH_ATTRIBUTE] == delete.path.normalize().toString() &&
            attributes[JavaSafeDeletePlanner.AUTHORITY_VALUE_ATTRIBUTE] == JavaSafeDeletePlanner.SNAPSHOT_BOUND_AUTHORITY &&
            !attributes["javaContextHash"].isNullOrBlank() &&
            !attributes["javaContextSchemaVersion"].isNullOrBlank()
    }
}
