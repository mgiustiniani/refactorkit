package org.refactorkit.java

import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString

enum class JavaMoveClassCandidateClassification {
    BOUND_TARGET,
    BOUND_OTHER,
    UNRESOLVED,
}

data class JavaMoveClassCandidateRecord(
    val path: Path,
    val sourceRange: SourceRange,
    val lexicalText: String,
    val sourceSet: String,
    val classification: JavaMoveClassCandidateClassification,
    val bindingKey: String?,
)

data class JavaMoveClassOfflineMissingEntry(
    val identity: String,
    val affectedSourceSet: String,
    val expectedPath: Path,
    val expectedIdentityManifest: Path,
    val expectedSha256: String,
    val providedTypes: Set<String>,
    val negativePresenceFingerprint: String,
    val leaf: Boolean,
)

data class JavaMoveClassMissingBinarySelection(
    val sourceSet: String,
    val projection: String,
    val dependencyPath: String,
    val effectiveScope: String,
)

data class JavaMoveClassNoFollowAbsenceObservation(
    val phase: String,
    val status: String,
    val path: Path,
    val fingerprint: String,
    val factHash: String,
)

data class JavaMoveClassSelectedMissingBinaryRecord(
    val selectedCoordinate: String,
    val selectedVersion: String,
    val type: String,
    val classifier: String,
    val extension: String,
    val repositoryProvider: String,
    val repositoryLayout: String,
    val repositoryRoot: Path,
    val repositoryPolicy: String,
    val repositoryIdentityHash: String,
    val deterministicPomPath: Path,
    val deterministicJarPath: Path,
    val selectingPaths: Set<JavaMoveClassMissingBinarySelection>,
    val affectedAuthoritySourceSets: Set<String>,
    val selectedPomContentHash: String,
    val parsedDescriptorIdentityHash: String,
    val effectiveInputContentHashes: Map<Path, String>,
    val graphLeafProof: String,
    val graphLeafProofHash: String,
    val beforeJarAbsence: JavaMoveClassNoFollowAbsenceObservation,
    val stagedJarAbsence: JavaMoveClassNoFollowAbsenceObservation,
    val expectedIdentityManifest: Path?,
    val expectedJarContentHash: String?,
    val providedTypes: Set<String>,
)

data class JavaMoveClassRetainedDiagnosticIdentity(
    val providerConfigurationHash: String,
    val problemId: Int,
    val category: JdtJavaDiagnosticCategory,
    val severity: Diagnostic.Severity,
    val path: Path,
    val sourceRange: SourceRange,
    val message: String,
    val missingExternalType: String,
    val positiveNonConcealmentReason: String,
) {
    fun toDiagnostic(): Diagnostic = Diagnostic(
        message = message,
        severity = severity,
        location = org.refactorkit.core.SourceLocation(path, sourceRange),
        code = "java.jdt.problem.$problemId",
        evidence = DiagnosticEvidence.COMPILER,
        category = when (category) {
            JdtJavaDiagnosticCategory.SYNTAX -> DiagnosticCategory.SYNTAX
            JdtJavaDiagnosticCategory.TYPE_RESOLUTION -> DiagnosticCategory.TYPE_RESOLUTION
        },
    )
}

data class JavaMoveClassAuthorityAttestation(
    val valid: Boolean,
    val phase: String?,
    val retainedDiagnostics: List<JavaMoveClassRetainedDiagnosticIdentity>,
    val blockers: List<String>,
)

data class JavaMoveClassTargetAuthorityLease(
    val beforeSnapshotHash: String,
    val stagedSnapshotHash: String,
    val evidenceHash: String,
    val oldFqn: String,
    val newFqn: String,
    val oldDeclarationPath: Path,
    val newDeclarationPath: Path,
    val targetBindingKey: String,
    val targetRange: SourceRange,
    val ownerSourceSet: String,
    val observerSourceSets: Set<String>,
    val candidatesBefore: List<JavaMoveClassCandidateRecord>,
    val candidatesStaged: List<JavaMoveClassCandidateRecord>,
    val offlineMissingEntries: List<JavaMoveClassOfflineMissingEntry>,
    val selectedMissingBinaryRecords: List<JavaMoveClassSelectedMissingBinaryRecord>,
    val retainedDiagnosticsBefore: List<JavaMoveClassRetainedDiagnosticIdentity>,
    val retainedDiagnosticsStaged: List<JavaMoveClassRetainedDiagnosticIdentity>,
    val allDiagnosticsBefore: List<JavaMoveClassRetainedDiagnosticIdentity>,
    val allDiagnosticsStaged: List<JavaMoveClassRetainedDiagnosticIdentity>,
    val coreLease: OperationAuthorityLease,
) {
    fun attest(snapshot: ProjectSnapshot): JavaMoveClassAuthorityAttestation =
        JavaMoveClassTargetAuthorityEvaluator.attest(this, snapshot)

    /** Global diagnostics remain active; this adds exact target-lease attestation. */
    fun managedDiagnostics(snapshot: ProjectSnapshot): List<Diagnostic> {
        val attestation = attest(snapshot)
        check(attestation.valid) {
            "Target-scoped moveClass authority lease is no longer valid: ${attestation.blockers.joinToString()}"
        }
        return JavaLanguageAdapter().diagnostics(snapshot) +
            attestation.retainedDiagnostics.map(JavaMoveClassRetainedDiagnosticIdentity::toDiagnostic)
    }
}

internal data class MoveAuthoritySourceSet(
    val moduleId: String,
    val sourceSetId: String,
) {
    fun displayName(): String = "$moduleId:$sourceSetId"
}

internal data class MoveAuthorityObserverClosure(
    val model: BuildModel,
    val owner: MoveAuthoritySourceSet,
    val sourceSets: Set<MoveAuthoritySourceSet>,
)

internal data class JavaMoveClassSemanticSelection(
    val analysis: JdtJavaSemanticAnalysisResult,
    val target: JdtJavaSemanticSymbol,
    val references: List<JdtJavaSemanticReference>,
    val closure: MoveAuthorityObserverClosure,
    val excludedWarningSourceSets: Set<MoveAuthoritySourceSet>,
)

internal data class JavaMoveClassOfflineAuthorityPrepared(
    val snapshot: ProjectSnapshot,
    val symbolFqn: String,
    val declarationPath: Path,
    val selection: JavaMoveClassSemanticSelection,
    val candidates: List<JavaMoveClassCandidateRecord>,
    val offlineMissingEntries: List<JavaMoveClassOfflineMissingEntry>,
    val selectedMissingBinaryRecords: List<JavaMoveClassSelectedMissingBinaryRecord>,
    val retainedDiagnostics: List<JavaMoveClassRetainedDiagnosticIdentity>,
    val allDiagnostics: List<JavaMoveClassRetainedDiagnosticIdentity>,
)

internal data class JavaMoveClassMissingEvidence(
    val systemPathEntries: List<JavaMoveClassOfflineMissingEntry>,
    val selectedBinaryRecords: List<JavaMoveClassSelectedMissingBinaryRecord>,
)

internal sealed interface JavaMoveClassOfflineAuthorityPreparation {
    data class Eligible(val prepared: JavaMoveClassOfflineAuthorityPrepared) : JavaMoveClassOfflineAuthorityPreparation
    data class Ineligible(val blockers: List<String>) : JavaMoveClassOfflineAuthorityPreparation
}

internal data class JavaMoveClassDiagnosticIdentityDriftEvidence(
    val mavenModule: String,
    val sourceSet: String,
    val path: Path,
    val before: JavaMoveClassRetainedDiagnosticIdentity,
    val staged: JavaMoveClassRetainedDiagnosticIdentity,
    val beforeDiagnosticMultisetSha256: String,
    val stagedDiagnosticMultisetSha256: String,
    val changedFields: List<JavaMoveClassGuidanceDiagnosticChangedField>,
    val stagedOverlaySha256: String,
)

internal object JavaMoveClassTargetAuthorityEvaluator {
    private const val MAVEN_PROVIDER = "maven-effective-v1"
    private const val LEASE_KIND = "java.maven.moveClass.targetScoped.v1"
    private const val PROVIDER_CONFIGURATION =
        "eclipse-jdt-core:3.44.0|AST:JLS25|resolveBindings=true|bindingsRecovery=true|statementsRecovery=true"

    fun prepare(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        declarationPath: Path,
    ): JavaMoveClassOfflineAuthorityPreparation {
        val blockers = mutableListOf<String>()
        val closure = observerClosure(snapshot, declarationPath, setOf(BuildModelStatus.OFFLINE_MISSING))
        if (closure == null) {
            return JavaMoveClassOfflineAuthorityPreparation.Ineligible(
                listOf("Maven reactor/module/source-set ownership or reverse-observer closure is incomplete or ambiguous"),
            )
        }
        blockers += structuralBlockers(snapshot, closure)
        val missingEvidence = missingEvidence(snapshot, closure.model, closure.sourceSets, blockers)
        val offlineEntries = missingEvidence.systemPathEntries
        val selectedMissingBinaryRecords = missingEvidence.selectedBinaryRecords
        val unavailableClosureSourceSets = closure.sourceSets.filter { identity ->
            val module = closure.model.modules.single { it.id == identity.moduleId }
            module.sourceSets.single { it.id == identity.sourceSetId }
                .attributes["java.classpath.status"] == "unavailable"
        }
        if (offlineEntries.isEmpty() && selectedMissingBinaryRecords.isEmpty() &&
            unavailableClosureSourceSets.isNotEmpty()
        ) {
            blockers += "No complete enumerated OFFLINE_MISSING leaf evidence belongs to unavailable authority source sets: " +
                unavailableClosureSourceSets.joinToString { it.displayName() }
        }
        val targetSimpleName = JavaPackageUtil.simpleName(symbolFqn)
        val targetRelevantMissingTypes = offlineEntries.flatMap(JavaMoveClassOfflineMissingEntry::providedTypes)
            .filter { it == symbolFqn || it.substringAfterLast('.') == targetSimpleName }
            .distinct()
            .sorted()
        if (targetRelevantMissingTypes.isNotEmpty()) {
            blockers += "Expected missing-artifact identity can provide a target-relevant Product type: " +
                targetRelevantMissingTypes.joinToString()
        }

        val analysis = analyzeOverlay(snapshot)
        val closureWarnings = analysis.warnings.filter { warning ->
            sourceSet(snapshot, closure.model, warning.path) in closure.sourceSets
        }
        if (selectedMissingBinaryRecords.isNotEmpty() && closureWarnings.isNotEmpty()) {
            blockers += "Selected sidecar-free missing binaries require zero unattributed authority-closure JDT diagnostics"
        }
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == symbolFqn &&
                symbol.kind in MOVEABLE_KINDS &&
                symbol.path == declarationPath &&
                symbol.bindingKey != null &&
                symbol.evidence == JdtJavaSemanticEvidence.JDT_BINDING &&
                !symbol.recovered
        }
        if (target == null) blockers += "The selected declaration does not have one exact non-recovered JDT binding"
        val targetBindingKey = target?.bindingKey
        val candidates = if (targetBindingKey == null) emptyList() else {
            candidateInventory(snapshot, closure.model, analysis, symbolFqn, targetBindingKey)
        }
        if (candidates.isEmpty()) blockers += "The bounded Java lexical candidate inventory is empty"
        val unresolved = candidates.filter { it.classification == JavaMoveClassCandidateClassification.UNRESOLVED }
        if (unresolved.isNotEmpty()) {
            blockers += "${unresolved.size} Java Product candidate(s) lack exact non-recovered binding classification"
        }
        if (target != null) {
            blockers += targetNameLookupBlockers(snapshot, candidates, symbolFqn, declarationPath)
        }
        val targetReferences = if (targetBindingKey == null) emptyList() else analysis.references.filter { reference ->
            reference.bindingKey == targetBindingKey &&
                reference.symbolQualifiedName == symbolFqn &&
                reference.symbolKind in MOVEABLE_KINDS &&
                !reference.recovered &&
                reference.path != declarationPath
        }
        val referenceSourceSets = targetReferences.mapNotNull { reference ->
            sourceSet(snapshot, closure.model, reference.path)
        }.toSet()
        if (targetReferences.any { sourceSet(snapshot, closure.model, it.path) == null } ||
            referenceSourceSets.any { it !in closure.sourceSets }
        ) {
            blockers += "A target-bound reference is outside the structurally complete reverse-observer closure"
        }
        val candidateTargetSourceSets = candidates
            .filter { it.classification == JavaMoveClassCandidateClassification.BOUND_TARGET }
            .mapNotNull { record -> sourceSet(snapshot, closure.model, record.path) }
            .toSet()
        if (candidateTargetSourceSets.any { it !in closure.sourceSets }) {
            blockers += "A target-bound lexical candidate is outside the reverse-observer closure"
        }

        val retained = retainedDiagnostics(
            snapshot,
            closure,
            analysis,
            candidates,
            offlineEntries,
            symbolFqn,
            blockers,
        )
        if (blockers.isNotEmpty() || target == null) {
            return JavaMoveClassOfflineAuthorityPreparation.Ineligible(blockers.distinct())
        }
        val allDiagnostics = diagnosticIdentities(snapshot, closure.model, analysis.warnings, offlineEntries, symbolFqn)
        val warningSourceSets = analysis.warnings.mapNotNull { warning ->
            sourceSet(snapshot, closure.model, warning.path)
        }.toSet()
        return JavaMoveClassOfflineAuthorityPreparation.Eligible(
            JavaMoveClassOfflineAuthorityPrepared(
                snapshot = snapshot,
                symbolFqn = symbolFqn,
                declarationPath = declarationPath,
                selection = JavaMoveClassSemanticSelection(
                    analysis = analysis,
                    target = target,
                    references = targetReferences,
                    closure = closure,
                    excludedWarningSourceSets = warningSourceSets - closure.sourceSets,
                ),
                candidates = candidates,
                offlineMissingEntries = offlineEntries,
                selectedMissingBinaryRecords = selectedMissingBinaryRecords,
                retainedDiagnostics = retained,
                allDiagnostics = allDiagnostics,
            ),
        )
    }

    fun complete(
        prepared: JavaMoveClassOfflineAuthorityPrepared,
        targetPackage: String,
        newDeclarationPath: Path,
        workspaceEdit: WorkspaceEdit,
    ): JavaMoveClassTargetAuthorityLease? {
        fun fail(reason: String): JavaMoveClassTargetAuthorityLease? {
            check(reason.isNotBlank())
            return null
        }
        val oldFqn = prepared.symbolFqn
        val simpleName = JavaPackageUtil.simpleName(oldFqn)
        val newFqn = JavaPackageUtil.fqn(targetPackage, simpleName)
        val staged = runCatching { WorkspaceEditSimulator.apply(prepared.snapshot, workspaceEdit) }.getOrNull()
            ?: return fail("workspace edit cannot be staged")
        val stagedClosure = observerClosure(staged, newDeclarationPath, setOf(BuildModelStatus.OFFLINE_MISSING))
            ?: return fail("staged observer closure is unavailable")
        if (stagedClosure.owner != prepared.selection.closure.owner ||
            stagedClosure.sourceSets != prepared.selection.closure.sourceSets
        ) return fail("staged observer closure identity changed")
        val analysis = analyzeOverlay(staged)
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == newFqn &&
                symbol.path == newDeclarationPath &&
                symbol.kind in MOVEABLE_KINDS &&
                symbol.bindingKey != null &&
                symbol.evidence == JdtJavaSemanticEvidence.JDT_BINDING &&
                !symbol.recovered
        } ?: return fail("staged target binding is unavailable")
        val targetKey = requireNotNull(target.bindingKey)
        val candidates = candidateInventory(staged, stagedClosure.model, analysis, newFqn, targetKey)
        val stagedUnresolved = candidates.filter {
            it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
        }
        if (candidates.isEmpty() || stagedUnresolved.isNotEmpty()) {
            return fail("staged candidate inventory contains unresolved entries: $stagedUnresolved")
        }
        if (targetNameLookupBlockers(staged, candidates, newFqn, newDeclarationPath).isNotEmpty()) {
            return fail("staged target-name lookup protection is incomplete")
        }
        if (staged.files.filter { it.languageId == "java" }.any { file ->
                JavaLexer.findOccurrences(file.content, oldFqn).isNotEmpty()
            }
        ) return fail("old FQN remains in the staged Java lexical inventory")
        val beforeOther = prepared.candidates.filter {
            it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER
        }
        val stagedOther = candidates.filter {
            it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER
        }
        if (beforeOther != stagedOther) return fail("BOUND_OTHER classification changed")

        val blockers = mutableListOf<String>()
        val stagedMissingEvidence = missingEvidence(staged, stagedClosure.model, stagedClosure.sourceSets, blockers)
        if (stagedMissingEvidence.systemPathEntries != prepared.offlineMissingEntries) {
            blockers += "staged systemPath missing-artifact identity changed"
        }
        if (stagedMissingEvidence.selectedBinaryRecords != prepared.selectedMissingBinaryRecords) {
            blockers += "staged selected-missing-binary identity changed"
        }
        val stagedClosureWarnings = analysis.warnings.filter { warning ->
            sourceSet(staged, stagedClosure.model, warning.path) in stagedClosure.sourceSets
        }
        if (prepared.selectedMissingBinaryRecords.isNotEmpty() && stagedClosureWarnings.isNotEmpty()) {
            blockers += "staged sidecar-free authority closure has unattributed JDT diagnostics"
        }
        val retained = retainedDiagnostics(
            staged,
            stagedClosure,
            analysis,
            candidates,
            prepared.offlineMissingEntries,
            newFqn,
            blockers,
        )
        if (blockers.isNotEmpty()) return fail("retained diagnostic proof failed: ${blockers.joinToString()}")
        if (retained != prepared.retainedDiagnostics) return fail("retained diagnostic identity changed")
        val allDiagnostics = diagnosticIdentities(
            staged,
            stagedClosure.model,
            analysis.warnings,
            prepared.offlineMissingEntries,
            newFqn,
        )
        if (allDiagnostics != prepared.allDiagnostics) return fail("global JDT diagnostic identity changed")

        val requiredEvidence = requiredClasspathEvidence(
            prepared.snapshot,
            prepared.offlineMissingEntries,
            prepared.selectedMissingBinaryRecords,
        ) ?: return fail("required missing-artifact classpath evidence is unavailable")
        val evidenceHash = authorityEvidenceHash(
            prepared,
            staged.hash,
            newFqn,
            newDeclarationPath,
            candidates,
            retained,
            allDiagnostics,
            workspaceEdit,
        )
        val requiredCandidateFileEvidence = prepared.candidates
            .filter { it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER }
            .groupBy { it.path.normalize() }
            .toSortedMap(compareBy(Path::toString))
            .map { (path, records) ->
                val source = prepared.snapshot.files.single { it.path.normalize() == path }
                OperationAuthorityFileEvidence(
                    kind = "CANDIDATE_INVENTORY",
                    path = path,
                    expectedContentSha256 = rawSha256(source.content.toByteArray(Charsets.UTF_8)),
                    attributes = mapOf(
                        "classifications" to records.map { it.classification.name }.distinct().sorted().joinToString(","),
                        "sourceSets" to records.map(JavaMoveClassCandidateRecord::sourceSet)
                            .distinct().sorted().joinToString(","),
                        "candidateRangeHash" to candidateHash(records),
                        "changedSourceManaged" to "false",
                    ),
                )
            }
        val coreLease = OperationAuthorityLease(
            kind = LEASE_KIND,
            operation = "moveClass",
            snapshotHash = prepared.snapshot.hash,
            evidenceHash = evidenceHash,
            workspaceEditSha256 = WorkspaceEditIdentity.sha256(workspaceEdit),
            requiredClasspathEvidence = requiredEvidence,
            requiredFileEvidence = requiredCandidateFileEvidence,
            attributes = buildMap {
                put("authorityMode", "TARGET_SCOPED")
                put("reactorStructureStatus", "COMPLETE")
                put("observerClosureStatus", "COMPLETE")
                put("externalClasspathStatus", BuildModelStatus.OFFLINE_MISSING.name)
                put("candidateInventoryHash", candidateHash(prepared.candidates))
                put("candidateInventoryEvidenceHash", OperationAuthorityLease.fileEvidenceSha256(requiredCandidateFileEvidence))
                put("stagedCandidateInventoryHash", candidateHash(candidates))
                put("stagedSnapshotHash", staged.hash)
                put("selectedMissingBinary.count", prepared.selectedMissingBinaryRecords.size.toString())
                prepared.selectedMissingBinaryRecords.forEachIndexed { index, record ->
                    val key = "selectedMissingBinary.$index"
                    put("$key.coordinate", "${record.selectedCoordinate}:${record.selectedVersion}")
                    put("$key.jarPath", record.deterministicJarPath.invariantSeparatorsPathString)
                    put("$key.pomPath", record.deterministicPomPath.invariantSeparatorsPathString)
                    put("$key.repositoryIdentityHash", record.repositoryIdentityHash)
                    put("$key.parsedDescriptorIdentityHash", record.parsedDescriptorIdentityHash)
                    put("$key.graphLeafProofHash", record.graphLeafProofHash)
                }
            },
        )
        return JavaMoveClassTargetAuthorityLease(
            beforeSnapshotHash = prepared.snapshot.hash,
            stagedSnapshotHash = staged.hash,
            evidenceHash = evidenceHash,
            oldFqn = oldFqn,
            newFqn = newFqn,
            oldDeclarationPath = prepared.declarationPath,
            newDeclarationPath = newDeclarationPath,
            targetBindingKey = requireNotNull(prepared.selection.target.bindingKey),
            targetRange = prepared.selection.target.sourceRange,
            ownerSourceSet = prepared.selection.closure.owner.displayName(),
            observerSourceSets = (prepared.selection.closure.sourceSets - prepared.selection.closure.owner)
                .mapTo(sortedSetOf(), MoveAuthoritySourceSet::displayName),
            candidatesBefore = prepared.candidates,
            candidatesStaged = candidates,
            offlineMissingEntries = prepared.offlineMissingEntries,
            selectedMissingBinaryRecords = prepared.selectedMissingBinaryRecords,
            retainedDiagnosticsBefore = prepared.retainedDiagnostics,
            retainedDiagnosticsStaged = retained,
            allDiagnosticsBefore = prepared.allDiagnostics,
            allDiagnosticsStaged = allDiagnostics,
            coreLease = coreLease,
        )
    }

    fun retainedDiagnosticIdentityDrift(
        prepared: JavaMoveClassOfflineAuthorityPrepared,
        targetPackage: String,
        newDeclarationPath: Path,
        workspaceEdit: WorkspaceEdit,
    ): JavaMoveClassDiagnosticIdentityDriftEvidence? {
        val staged = runCatching { WorkspaceEditSimulator.apply(prepared.snapshot, workspaceEdit) }.getOrNull()
            ?: return null
        val stagedClosure = observerClosure(staged, newDeclarationPath, setOf(BuildModelStatus.OFFLINE_MISSING))
            ?: return null
        if (stagedClosure.owner != prepared.selection.closure.owner ||
            stagedClosure.sourceSets != prepared.selection.closure.sourceSets
        ) return null
        val newFqn = JavaPackageUtil.fqn(targetPackage, JavaPackageUtil.simpleName(prepared.symbolFqn))
        val analysis = analyzeOverlay(staged)
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == newFqn && symbol.path == newDeclarationPath &&
                symbol.kind in MOVEABLE_KINDS && symbol.bindingKey != null && !symbol.recovered
        } ?: return null
        val candidates = candidateInventory(
            staged,
            stagedClosure.model,
            analysis,
            newFqn,
            requireNotNull(target.bindingKey),
        )
        if (candidates.isEmpty() || candidates.any {
                it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
            } || targetNameLookupBlockers(staged, candidates, newFqn, newDeclarationPath).isNotEmpty()
        ) return null
        if (staged.files.filter { it.languageId == "java" }.any { file ->
                JavaLexer.findOccurrences(file.content, prepared.symbolFqn).isNotEmpty()
            }
        ) return null
        val blockers = mutableListOf<String>()
        val stagedMissingEvidence = missingEvidence(staged, stagedClosure.model, stagedClosure.sourceSets, blockers)
        if (blockers.isNotEmpty() ||
            stagedMissingEvidence.systemPathEntries != prepared.offlineMissingEntries ||
            stagedMissingEvidence.selectedBinaryRecords != prepared.selectedMissingBinaryRecords
        ) return null
        val stagedDiagnostics = diagnosticIdentities(
            staged,
            stagedClosure.model,
            analysis.warnings,
            prepared.offlineMissingEntries,
            newFqn,
        ).map { identity ->
            if (identity.path == newDeclarationPath) identity.copy(path = prepared.declarationPath) else identity
        }.sortedWith(DIAGNOSTIC_ORDER)
        val beforeDiagnostics = prepared.allDiagnostics.sortedWith(DIAGNOSTIC_ORDER)
        if (beforeDiagnostics.size != stagedDiagnostics.size || beforeDiagnostics.isEmpty()) return null
        fun stableIdentity(identity: JavaMoveClassRetainedDiagnosticIdentity) = listOf(
            identity.providerConfigurationHash,
            identity.category,
            identity.severity,
            identity.path,
            identity.sourceRange,
        )
        val changedPairs = beforeDiagnostics.zip(stagedDiagnostics).filter { (before, after) -> before != after }
        if (changedPairs.size != 1) return null
        val (before, after) = changedPairs.single()
        if (stableIdentity(before) != stableIdentity(after)) return null
        val changedFields = buildList {
            if (before.problemId != after.problemId) add(JavaMoveClassGuidanceDiagnosticChangedField.PROBLEM_ID)
            if (before.message != after.message) add(JavaMoveClassGuidanceDiagnosticChangedField.MESSAGE)
        }
        if (changedFields != listOf(
                JavaMoveClassGuidanceDiagnosticChangedField.PROBLEM_ID,
                JavaMoveClassGuidanceDiagnosticChangedField.MESSAGE,
            )
        ) return null
        val owner = sourceSet(prepared.snapshot, prepared.selection.closure.model, before.path) ?: return null
        return JavaMoveClassDiagnosticIdentityDriftEvidence(
            mavenModule = owner.moduleId,
            sourceSet = owner.sourceSetId,
            path = before.path,
            before = before,
            staged = after,
            beforeDiagnosticMultisetSha256 = diagnosticContractHash(beforeDiagnostics),
            stagedDiagnosticMultisetSha256 = diagnosticContractHash(stagedDiagnostics),
            changedFields = changedFields,
            stagedOverlaySha256 = staged.hash,
        )
    }

    fun attest(
        lease: JavaMoveClassTargetAuthorityLease,
        snapshot: ProjectSnapshot,
    ): JavaMoveClassAuthorityAttestation {
        val before = snapshot.hash == lease.beforeSnapshotHash
        val staged = snapshot.hash == lease.stagedSnapshotHash
        if (!before && !staged) return JavaMoveClassAuthorityAttestation(
            false,
            null,
            emptyList(),
            listOf("snapshot hash differs from both leased before and staged identities"),
        )
        val targetFqn = if (before) lease.oldFqn else lease.newFqn
        val targetPath = if (before) lease.oldDeclarationPath else lease.newDeclarationPath
        val expectedCandidates = if (before) lease.candidatesBefore else lease.candidatesStaged
        val expectedRetained = if (before) lease.retainedDiagnosticsBefore else lease.retainedDiagnosticsStaged
        val expectedAll = if (before) lease.allDiagnosticsBefore else lease.allDiagnosticsStaged
        val closure = observerClosure(snapshot, targetPath, setOf(BuildModelStatus.OFFLINE_MISSING))
            ?: return JavaMoveClassAuthorityAttestation(false, null, emptyList(), listOf("structural closure is unavailable"))
        val structural = structuralBlockers(snapshot, closure)
        if (structural.isNotEmpty()) return JavaMoveClassAuthorityAttestation(false, null, emptyList(), structural)
        val blockers = mutableListOf<String>()
        val currentMissingEvidence = missingEvidence(snapshot, closure.model, closure.sourceSets, blockers)
        if (currentMissingEvidence.systemPathEntries != lease.offlineMissingEntries) {
            blockers += "leased systemPath missing-artifact identity changed"
        }
        if (currentMissingEvidence.selectedBinaryRecords != lease.selectedMissingBinaryRecords) {
            blockers += "leased selected-missing-binary identity changed"
        }
        val analysis = analyzeOverlay(snapshot)
        val closureWarnings = analysis.warnings.filter { warning ->
            sourceSet(snapshot, closure.model, warning.path) in closure.sourceSets
        }
        if (lease.selectedMissingBinaryRecords.isNotEmpty() && closureWarnings.isNotEmpty()) {
            blockers += "sidecar-free authority closure has unattributed JDT diagnostics"
        }
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == targetFqn && symbol.path == targetPath &&
                symbol.kind in MOVEABLE_KINDS && symbol.bindingKey != null && !symbol.recovered
        } ?: return JavaMoveClassAuthorityAttestation(
            false,
            null,
            emptyList(),
            listOf("leased target binding is unavailable"),
        )
        val candidates = candidateInventory(
            snapshot,
            closure.model,
            analysis,
            targetFqn,
            requireNotNull(target.bindingKey),
        )
        blockers += targetNameLookupBlockers(snapshot, candidates, targetFqn, targetPath)
        val retained = retainedDiagnostics(
            snapshot,
            closure,
            analysis,
            candidates,
            currentMissingEvidence.systemPathEntries,
            targetFqn,
            blockers,
        )
        val all = diagnosticIdentities(
            snapshot,
            closure.model,
            analysis.warnings,
            currentMissingEvidence.systemPathEntries,
            targetFqn,
        )
        if (candidates != expectedCandidates) blockers += "candidate inventory identity changed"
        if (retained != expectedRetained) blockers += "retained diagnostic identity changed"
        if (all != expectedAll) blockers += "staged/global JDT diagnostic multiset changed"
        return JavaMoveClassAuthorityAttestation(
            valid = blockers.isEmpty(),
            phase = if (before) "BEFORE" else "STAGED",
            retainedDiagnostics = retained,
            blockers = blockers.distinct(),
        )
    }

    fun availableSelection(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        declarationPath: Path,
    ): JavaMoveClassSemanticSelection? {
        val closure = observerClosure(snapshot, declarationPath, setOf(BuildModelStatus.AVAILABLE)) ?: return null
        if (closure.model.modules.any { it.attributes["java.dependencyGraph.status"] != "complete" }) return null
        val analysis = analyzeOverlay(snapshot)
        val warningSourceSets = analysis.warnings.map { warning ->
            sourceSet(snapshot, closure.model, warning.path) ?: return null
        }
        if (warningSourceSets.any { it in closure.sourceSets }) return null
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == symbolFqn && symbol.kind in MOVEABLE_KINDS &&
                symbol.path == declarationPath && symbol.bindingKey != null && !symbol.recovered
        } ?: return null
        val bindingKey = requireNotNull(target.bindingKey)
        val references = analysis.references.filter { reference ->
            reference.bindingKey == bindingKey && reference.path != declarationPath && !reference.recovered
        }
        val referenceSourceSets = references.map { reference ->
            sourceSet(snapshot, closure.model, reference.path) ?: return null
        }
        if (referenceSourceSets.any { it !in closure.sourceSets }) return null
        return JavaMoveClassSemanticSelection(
            analysis,
            target,
            references,
            closure,
            warningSourceSets.toSet() - closure.sourceSets,
        )
    }

    private fun structuralBlockers(
        snapshot: ProjectSnapshot,
        closure: MoveAuthorityObserverClosure,
    ): List<String> = buildList {
        val model = closure.model
        if (model.providerId != MAVEN_PROVIDER || model.status != BuildModelStatus.OFFLINE_MISSING) {
            add("Target-scoped exception requires one Maven OFFLINE_MISSING model")
        }
        if (snapshot.buildModels.singleOrNull { it.providerId == MAVEN_PROVIDER } != model) {
            add("Maven build-model provider identity is missing or ambiguous")
        }
        if (model.modules.isEmpty() || model.modules.size != snapshot.modules.count {
                it.languageSettings["java.buildSystem"] == "maven"
            }
        ) add("Effective reactor module inventory is incomplete")
        if (model.diagnostics.any { it.code !in setOf("classpath.offlineMissing", "classpath.unavailable") }) {
            add("A non-classpath build-model diagnostic makes reactor structure incomplete")
        }
        val auxiliaryPaths = snapshot.auxiliaryFiles.map { it.path.normalize() }.toSet()
        if (Path.of("pom.xml") !in auxiliaryPaths) add("Declared reactor root POM is not hash-bound")
        model.modules.forEach { module ->
            val pom = module.attributes["java.maven.pomPath"]?.let(Path::of)
            if (pom == null || pom.normalize() !in auxiliaryPaths) add("Module ${module.id} POM is not hash-bound")
            if (module.attributes["java.dependencyGraph.status"] != "complete") {
                add("Module ${module.id} does not have a complete hash-bound dependency graph")
            }
            if (module.sourceSets.count { it.kind == SourceSetKind.MAIN } != 1 ||
                module.sourceSets.count { it.kind == SourceSetKind.TEST } != 1
            ) add("Module ${module.id} does not have one structurally modeled main/test pair")
            module.sourceSets.forEach { sourceSet ->
                val identity = MoveAuthoritySourceSet(module.id, sourceSet.id)
                val missingCount = sourceSet.attributes["java.classpath.missing.count"]?.toIntOrNull() ?: 0
                val evidenceCount = sourceSet.attributes["java.classpath.missing.evidence.count"]?.toIntOrNull() ?: 0
                val unavailable = sourceSet.attributes["java.classpath.status"] == "unavailable"
                val dependencyCause = sourceSet.attributes["java.classpath.cause"] == "dependency"
                if (identity in closure.sourceSets && missingCount != evidenceCount) {
                    add("${identity.displayName()} has incomplete missing-artifact enumeration")
                }
                if (identity in closure.sourceSets && unavailable && missingCount == 0 && !dependencyCause) {
                    add("${identity.displayName()} has unclassified classpath unavailability")
                }
            }
        }
        snapshot.files.filter { it.languageId == "java" }.forEach { file ->
            val owners = snapshot.owningBuildSourceRoots(file.path)
            if (owners.size != 1 || owners.single().providerId != model.providerId) {
                add("Java source ownership is ambiguous or unavailable: ${file.path}")
            }
        }
    }.distinct()

    private fun missingEvidence(
        snapshot: ProjectSnapshot,
        model: BuildModel,
        authoritySourceSets: Set<MoveAuthoritySourceSet>,
        blockers: MutableList<String>,
    ): JavaMoveClassMissingEvidence {
        val systemPathEntries = mutableListOf<JavaMoveClassOfflineMissingEntry>()
        val selectedBinaryRecords = mutableListOf<JavaMoveClassSelectedMissingBinaryRecord>()
        val affectedSourceSets = authoritySourceSets.mapTo(sortedSetOf(), MoveAuthoritySourceSet::displayName)
        fun safePath(value: String?): Path? = value?.let { raw ->
            runCatching { Path.of(raw).normalize() }.getOrNull()
                ?.takeIf { !it.isAbsolute && !it.startsWith("..") }
        }
        authoritySourceSets.sortedBy(MoveAuthoritySourceSet::displayName).forEach { identity ->
            val module = model.modules.single { it.id == identity.moduleId }
            val sourceSet = module.sourceSets.single { it.id == identity.sourceSetId }
            val missingCount = sourceSet.attributes["java.classpath.missing.count"]?.toIntOrNull() ?: 0
            val evidenceCount = sourceSet.attributes["java.classpath.missing.evidence.count"]?.toIntOrNull() ?: 0
            if (missingCount != evidenceCount) {
                blockers += "${identity.displayName()} missing-artifact evidence is not candidate-total"
                return@forEach
            }
            repeat(evidenceCount) evidenceLoop@ { index ->
                val prefix = "java.classpath.missing.evidence.$index"
                val kind = sourceSet.attributes["$prefix.kind"] ?: "SYSTEM_PATH_SIDECAR"
                val artifactIdentity = sourceSet.attributes["$prefix.identity"]
                val path = safePath(sourceSet.attributes["$prefix.path"])
                val providedTypes = sourceSet.attributes["$prefix.providedTypes"].orEmpty()
                    .split(',').filter(String::isNotBlank).toSortedSet()
                val leaf = sourceSet.attributes["$prefix.leaf"] == "true"
                if (kind == "SYSTEM_PATH_SIDECAR") {
                    val manifest = safePath(sourceSet.attributes["$prefix.manifest"])
                    val sha256 = sourceSet.attributes["$prefix.sha256"]
                    val negative = path?.let { expected -> snapshot.classpathEvidence.singleOrNull { evidence ->
                        evidence.path.normalize() == expected &&
                            evidence.kind == ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT &&
                            evidence.fingerprint == "missing"
                    } }
                    val manifestEvidence = manifest?.let { expected -> snapshot.classpathEvidence.singleOrNull { evidence ->
                        evidence.path.normalize() == expected &&
                            evidence.kind == ClasspathEvidenceKind.DECLARATION_FILE &&
                            evidence.fingerprint != "missing"
                    } }
                    if (artifactIdentity == null || path == null || manifest == null ||
                        sha256 == null || !SHA256.matches(sha256) || providedTypes.isEmpty() || !leaf ||
                        negative == null || manifestEvidence == null
                    ) {
                        blockers += "${identity.displayName()} has incomplete expected-identity or negative-presence evidence"
                    } else {
                        systemPathEntries += JavaMoveClassOfflineMissingEntry(
                            artifactIdentity,
                            identity.displayName(),
                            path,
                            manifest,
                            sha256,
                            providedTypes,
                            negative.fingerprint,
                            leaf,
                        )
                    }
                    return@evidenceLoop
                }
                if (kind != "LOCAL_REPOSITORY_SELECTED_LEAF") {
                    blockers += "${identity.displayName()} has an unsupported missing-artifact evidence kind: $kind"
                    return@evidenceLoop
                }

                val groupId = sourceSet.attributes["$prefix.groupId"]
                val artifactId = sourceSet.attributes["$prefix.artifactId"]
                val version = sourceSet.attributes["$prefix.version"]
                val type = sourceSet.attributes["$prefix.type"]
                val classifier = sourceSet.attributes["$prefix.classifier"]
                val extension = sourceSet.attributes["$prefix.extension"]
                val repositoryProvider = sourceSet.attributes["$prefix.repository.provider"]
                val repositoryLayout = sourceSet.attributes["$prefix.repository.layout"]
                val repositoryPolicy = sourceSet.attributes["$prefix.repository.policy"]
                val repositoryRoot = safePath(sourceSet.attributes["$prefix.repository.root"])
                val repositoryIdentityHash = sourceSet.attributes["$prefix.repository.identityHash"]
                val pomPath = safePath(sourceSet.attributes["$prefix.pomPath"])
                val pomContentHash = sourceSet.attributes["$prefix.pomSha256"]
                val parsedDescriptorIdentityHash = sourceSet.attributes["$prefix.parsedDescriptorIdentityHash"]
                val effectiveInputCount = sourceSet.attributes["$prefix.effectiveInput.count"]?.toIntOrNull() ?: -1
                val effectiveInputs = (0 until effectiveInputCount.coerceAtLeast(0)).mapNotNull { inputIndex ->
                    val inputPrefix = "$prefix.effectiveInput.$inputIndex"
                    val inputPath = safePath(sourceSet.attributes["$inputPrefix.path"])
                    val inputHash = sourceSet.attributes["$inputPrefix.sha256"]
                    if (inputPath == null || inputHash == null) null else inputPath to inputHash
                }.toMap()
                val selectionCount = sourceSet.attributes["$prefix.selection.count"]?.toIntOrNull() ?: -1
                val selections = (0 until selectionCount.coerceAtLeast(0)).mapNotNull { selectionIndex ->
                    val selectionPrefix = "$prefix.selection.$selectionIndex"
                    val selectedSourceSet = sourceSet.attributes["$selectionPrefix.sourceSet"]
                    val projection = sourceSet.attributes["$selectionPrefix.projection"]
                    val dependencyPath = sourceSet.attributes["$selectionPrefix.dependencyPath"]
                    val effectiveScope = sourceSet.attributes["$selectionPrefix.effectiveScope"]
                    if (selectedSourceSet == null || projection == null || dependencyPath == null ||
                        effectiveScope == null
                    ) null else JavaMoveClassMissingBinarySelection(
                        selectedSourceSet,
                        projection,
                        dependencyPath,
                        effectiveScope,
                    )
                }.toSet()
                val selectedCoordinate = if (groupId != null && artifactId != null) "$groupId:$artifactId" else null
                val expectedBase = if (repositoryRoot != null && groupId != null && artifactId != null && version != null) {
                    groupId.split('.').fold(repositoryRoot, Path::resolve).resolve(artifactId).resolve(version)
                } else null
                val expectedPom = expectedBase?.resolve("$artifactId-$version.pom")?.normalize()
                val expectedJar = expectedBase?.resolve("$artifactId-$version.jar")?.normalize()
                val negative = path?.let { expected -> snapshot.classpathEvidence.singleOrNull { evidence ->
                    evidence.path.normalize() == expected &&
                        evidence.kind == ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT &&
                        evidence.fingerprint.startsWith("absent-nofollow:")
                } }
                val pomEvidence = pomPath?.let { expected -> snapshot.classpathEvidence.singleOrNull { evidence ->
                    evidence.path.normalize() == expected &&
                        evidence.kind == ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT &&
                        evidence.fingerprint != "missing"
                } }
                val effectiveEvidenceComplete = effectiveInputs.all { (inputPath, inputHash) ->
                    SHA256.matches(inputHash) && snapshot.classpathEvidence.any { evidence ->
                        evidence.path.normalize() == inputPath &&
                            evidence.kind in setOf(
                                ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT,
                                ClasspathEvidenceKind.IMPORTED_BOM,
                            ) && evidence.fingerprint != "missing"
                    }
                }
                val valid = artifactIdentity == "$selectedCoordinate:$version:jar:" && path == expectedJar &&
                    pomPath == expectedPom && type == "jar" && classifier == "" && extension == "jar" &&
                    sourceSet.attributes["$prefix.fixedRelease"] == "true" &&
                    sourceSet.attributes["$prefix.noRelocation"] == "true" && leaf &&
                    sourceSet.attributes["$prefix.leafProof"] == "SELECTED_SUBTREE_SIZE_1_OUTGOING_SELECTED_0" &&
                    repositoryProvider == MAVEN_PROVIDER && repositoryLayout == "MAVEN_2" &&
                    repositoryPolicy == "LOCAL_ONLY_NO_SETTINGS" &&
                    repositoryIdentityHash != null && SHA256.matches(repositoryIdentityHash) &&
                    pomContentHash != null && SHA256.matches(pomContentHash) &&
                    parsedDescriptorIdentityHash != null && SHA256.matches(parsedDescriptorIdentityHash) &&
                    effectiveInputCount in 1..MAX_EFFECTIVE_INPUTS && effectiveInputs.size == effectiveInputCount &&
                    effectiveInputs[pomPath] == pomContentHash && effectiveEvidenceComplete &&
                    selectionCount in 1..MAX_MISSING_SELECTIONS && selections.size == selectionCount &&
                    selections.all { it.projection in setOf("COMPILE", "RUNTIME", "TEST") } &&
                    sourceSet.attributes["$prefix.manifest"] == null &&
                    sourceSet.attributes["$prefix.sha256"] == null && providedTypes.isEmpty() &&
                    negative != null && pomEvidence != null
                if (!valid) {
                    blockers += "${identity.displayName()} has incomplete selected-leaf descriptor or no-follow absence evidence"
                    return@evidenceLoop
                }
                val sortedSelections = selections.sortedWith(
                    compareBy<JavaMoveClassMissingBinarySelection> { it.sourceSet }
                        .thenBy { it.projection }
                        .thenBy { it.dependencyPath },
                ).toCollection(linkedSetOf())
                val sortedInputs = effectiveInputs.toSortedMap(compareBy(Path::toString))
                val graphLeafProof = "SELECTED_SUBTREE_SIZE_1_OUTGOING_SELECTED_0"
                val graphLeafProofHash = hashParts(buildList {
                    add(selectedCoordinate)
                    add(version)
                    add(pomPath.invariantSeparatorsPathString)
                    add(pomContentHash)
                    add(parsedDescriptorIdentityHash)
                    add(graphLeafProof)
                    sortedSelections.forEach { add("${it.sourceSet}|${it.projection}|${it.dependencyPath}|${it.effectiveScope}") }
                    sortedInputs.forEach { (inputPath, hash) -> add("${inputPath.invariantSeparatorsPathString}|$hash") }
                })
                fun absenceObservation(phase: String) = JavaMoveClassNoFollowAbsenceObservation(
                    phase = phase,
                    status = "ABSENT",
                    path = path,
                    fingerprint = negative.fingerprint,
                    factHash = hashParts(listOf(
                        phase,
                        selectedCoordinate,
                        version,
                        path.invariantSeparatorsPathString,
                        negative.fingerprint,
                        repositoryIdentityHash,
                    )),
                )
                selectedBinaryRecords += JavaMoveClassSelectedMissingBinaryRecord(
                    selectedCoordinate = requireNotNull(selectedCoordinate),
                    selectedVersion = requireNotNull(version),
                    type = requireNotNull(type),
                    classifier = classifier,
                    extension = extension,
                    repositoryProvider = repositoryProvider,
                    repositoryLayout = repositoryLayout,
                    repositoryRoot = requireNotNull(repositoryRoot),
                    repositoryPolicy = repositoryPolicy,
                    repositoryIdentityHash = repositoryIdentityHash,
                    deterministicPomPath = pomPath,
                    deterministicJarPath = path,
                    selectingPaths = sortedSelections,
                    affectedAuthoritySourceSets = affectedSourceSets,
                    selectedPomContentHash = pomContentHash,
                    parsedDescriptorIdentityHash = parsedDescriptorIdentityHash,
                    effectiveInputContentHashes = sortedInputs,
                    graphLeafProof = graphLeafProof,
                    graphLeafProofHash = graphLeafProofHash,
                    beforeJarAbsence = absenceObservation("BEFORE"),
                    stagedJarAbsence = absenceObservation("STAGED"),
                    expectedIdentityManifest = null,
                    expectedJarContentHash = null,
                    providedTypes = emptySet(),
                )
            }
        }
        return JavaMoveClassMissingEvidence(
            systemPathEntries = systemPathEntries.distinct().sortedWith(
                compareBy<JavaMoveClassOfflineMissingEntry> { it.identity }.thenBy { it.affectedSourceSet },
            ),
            selectedBinaryRecords = selectedBinaryRecords.distinct().sortedWith(
                compareBy<JavaMoveClassSelectedMissingBinaryRecord> { it.selectedCoordinate }
                    .thenBy { it.selectedVersion },
            ),
        )
    }

    internal fun candidateInventory(
        snapshot: ProjectSnapshot,
        model: BuildModel,
        analysis: JdtJavaSemanticAnalysisResult,
        targetFqn: String,
        targetBindingKey: String,
    ): List<JavaMoveClassCandidateRecord> {
        val simpleName = JavaPackageUtil.simpleName(targetFqn)
        return snapshot.files.filter { it.languageId == "java" }.flatMap { file ->
            val owner = sourceSet(snapshot, model, file.path) ?: return@flatMap emptyList()
            val fqnRanges = JavaLexer.findOccurrences(file.content, targetFqn)
            val simpleRanges = JavaLexer.findOccurrences(file.content, simpleName).filter { simple ->
                fqnRanges.none { fqn -> simple.first >= fqn.first && simple.last <= fqn.last }
            }
            (fqnRanges + simpleRanges).sortedBy(IntRange::first).map { lexicalRange ->
                val terminalOffset = lexicalRange.last + 1 - simpleName.length
                val terminalRange = TextEdits.rangeForOffset(file.content, terminalOffset, simpleName.length)
                val targetEvidence = mutableListOf<String>()
                val otherEvidence = mutableListOf<String>()
                analysis.symbols.filter { it.path == file.path && it.sourceRange == terminalRange }.forEach { symbol ->
                    when {
                        symbol.recovered || symbol.bindingKey == null -> Unit
                        symbol.bindingKey == targetBindingKey -> targetEvidence += symbol.bindingKey
                        symbol.kind == JdtJavaSemanticSymbolKind.CONSTRUCTOR &&
                            symbol.ownerQualifiedName == targetFqn -> targetEvidence += symbol.bindingKey
                        else -> otherEvidence += symbol.bindingKey
                    }
                }
                analysis.references.filter { it.path == file.path && it.sourceRange == terminalRange }.forEach { reference ->
                    when {
                        reference.recovered || reference.bindingKey == null -> Unit
                        reference.bindingKey == targetBindingKey && reference.symbolQualifiedName == targetFqn ->
                            targetEvidence += reference.bindingKey
                        reference.symbolKind == JdtJavaSemanticSymbolKind.CONSTRUCTOR &&
                            reference.symbolQualifiedName.substringBefore('#') == targetFqn ->
                            targetEvidence += reference.bindingKey
                        else -> otherEvidence += reference.bindingKey
                    }
                }
                analysis.bindingUses.filter { it.path == file.path && it.sourceRange == terminalRange }.forEach { use ->
                    when {
                        use.recovered -> Unit
                        use.bindingKey == targetBindingKey || use.symbolQualifiedName == targetFqn ->
                            targetEvidence += use.bindingKey
                        use.symbolQualifiedName?.substringBefore('#') == targetFqn -> targetEvidence += use.bindingKey
                        else -> otherEvidence += use.bindingKey
                    }
                }
                val classification = when {
                    targetEvidence.isNotEmpty() && otherEvidence.isEmpty() ->
                        JavaMoveClassCandidateClassification.BOUND_TARGET
                    otherEvidence.isNotEmpty() && targetEvidence.isEmpty() ->
                        JavaMoveClassCandidateClassification.BOUND_OTHER
                    else -> JavaMoveClassCandidateClassification.UNRESOLVED
                }
                JavaMoveClassCandidateRecord(
                    path = file.path,
                    sourceRange = TextEdits.rangeForOffset(
                        file.content,
                        lexicalRange.first,
                        lexicalRange.last - lexicalRange.first + 1,
                    ),
                    lexicalText = file.content.substring(lexicalRange.first, lexicalRange.last + 1),
                    sourceSet = owner.displayName(),
                    classification = classification,
                    bindingKey = when (classification) {
                        JavaMoveClassCandidateClassification.BOUND_TARGET -> targetBindingKey
                        JavaMoveClassCandidateClassification.BOUND_OTHER -> otherEvidence.distinct().singleOrNull()
                        JavaMoveClassCandidateClassification.UNRESOLVED -> null
                    },
                )
            }
        }.sortedWith(
            compareBy<JavaMoveClassCandidateRecord> { it.path.invariantSeparatorsPathString }
                .thenBy { it.sourceRange.start.line }
                .thenBy { it.sourceRange.start.character },
        )
    }

    private fun targetNameLookupBlockers(
        snapshot: ProjectSnapshot,
        candidates: List<JavaMoveClassCandidateRecord>,
        targetFqn: String,
        declarationPath: Path,
    ): List<String> = buildList {
        val simpleName = targetFqn.substringAfterLast('.')
        val targetCandidates = candidates.filter {
            it.classification == JavaMoveClassCandidateClassification.BOUND_TARGET
        }
        targetCandidates.filter { candidate ->
            candidate.path != declarationPath && candidate.lexicalText == simpleName
        }.forEach { candidate ->
            val file = snapshot.files.singleOrNull { it.path == candidate.path }
            if (file == null) {
                add("Target candidate source is absent from the hash-bound inventory: ${candidate.path}")
                return@forEach
            }
            val imports = JavaLexer.extractImports(file.content)
            if (imports.any { !it.isStatic && it.name.endsWith(".*") }) {
                add("A type import-on-demand can change $simpleName lookup in ${candidate.path}")
            }
            if (imports.any { it.isStatic && (it.name.endsWith(".*") || it.name.substringAfterLast('.') == simpleName) }) {
                add("A static import can change $simpleName lookup in ${candidate.path}")
            }
            val targetImports = imports.filter { !it.isStatic && it.name == targetFqn }
            if (targetImports.size != 1) {
                add("Simple-name target lookup in ${candidate.path} lacks exactly one non-static target import")
                return@forEach
            }
            val targetImport = targetImports.single()
            val nameOffset = file.content.indexOf(targetImport.name, targetImport.startOffset)
            val importRange = nameOffset.takeIf { it >= 0 }?.let { offset ->
                TextEdits.rangeForOffset(file.content, offset, targetImport.name.length)
            }
            if (importRange == null || targetCandidates.none { record ->
                    record.path == candidate.path && record.lexicalText == targetFqn &&
                        record.sourceRange == importRange && record.bindingKey != null
                }
            ) {
                add("The exact target import in ${candidate.path} lacks a non-recovered target binding")
            }
        }
        targetCandidates.filter { candidate ->
            candidate.path != declarationPath &&
                candidate.lexicalText != simpleName && candidate.lexicalText != targetFqn
        }.forEach { candidate ->
            add("Target candidate has an unsupported lookup form in ${candidate.path}: ${candidate.lexicalText}")
        }
    }.distinct()

    private fun retainedDiagnostics(
        snapshot: ProjectSnapshot,
        closure: MoveAuthorityObserverClosure,
        analysis: JdtJavaSemanticAnalysisResult,
        candidates: List<JavaMoveClassCandidateRecord>,
        offlineEntries: List<JavaMoveClassOfflineMissingEntry>,
        targetFqn: String,
        blockers: MutableList<String>,
    ): List<JavaMoveClassRetainedDiagnosticIdentity> {
        val targetSimpleName = JavaPackageUtil.simpleName(targetFqn)
        val retained = analysis.warnings.filter { warning ->
            sourceSet(snapshot, closure.model, warning.path) in closure.sourceSets
        }.mapNotNull { warning ->
            if (warning.category != JdtJavaDiagnosticCategory.TYPE_RESOLUTION) {
                blockers += "Syntax diagnostics cannot be retained by target-scoped authority: ${warning.path}"
                return@mapNotNull null
            }
            val candidateOverlap = candidates.any { candidate ->
                candidate.path == warning.path && candidate.sourceRange.overlaps(warning.sourceRange)
            }
            if (candidateOverlap) {
                blockers += "A retained diagnostic intersects a Product candidate: ${warning.path}:${warning.line + 1}"
                return@mapNotNull null
            }
            val file = snapshot.files.single { it.path == warning.path }
            val missingType = missingTypeForWarning(file.content, warning, offlineEntries)
            if (missingType == null) {
                blockers += "A closure diagnostic lacks exact missing-artifact provenance: ${warning.path}:${warning.line + 1}"
                return@mapNotNull null
            }
            val missingSimpleName = missingType.substringAfterLast('.')
            if (missingSimpleName == targetSimpleName || missingType == targetFqn) {
                blockers += "A retained missing type can participate in Product lookup: $missingType"
                return@mapNotNull null
            }
            val importNames = JavaLexer.extractImports(file.content).map { it.name }
            val hasTargetCandidate = candidates.any {
                it.path == file.path && it.classification == JavaMoveClassCandidateClassification.BOUND_TARGET
            }
            if (hasTargetCandidate && targetFqn !in importNames && file.path !=
                analysis.symbols.singleOrNull { it.qualifiedName == targetFqn }?.path
            ) {
                blockers += "Product lookup in ${file.path} is not protected by an exact single-type import"
                return@mapNotNull null
            }
            if (importNames.any { it.endsWith(".*") }) {
                blockers += "An on-demand import prevents positive Product non-concealment proof in ${file.path}"
                return@mapNotNull null
            }
            val reason = "The exact missing type $missingType is distinct from the selected Product declaration; " +
                "its diagnostic range intersects no lexical Product candidate, every Product candidate in the file " +
                "has an exact non-recovered binding, and exact single-type imports make Product lookup independent."
            diagnosticIdentity(snapshot, closure.model, warning, missingType, reason)
        }
        return retained.sortedWith(DIAGNOSTIC_ORDER)
    }

    private fun diagnosticIdentities(
        snapshot: ProjectSnapshot,
        model: BuildModel,
        warnings: List<JdtJavaSemanticWarning>,
        offlineEntries: List<JavaMoveClassOfflineMissingEntry>,
        targetFqn: String,
    ): List<JavaMoveClassRetainedDiagnosticIdentity> = warnings.map { warning ->
        val file = snapshot.files.single { it.path == warning.path }
        val missingType = missingTypeForWarning(file.content, warning, offlineEntries) ?: "<none>"
        diagnosticIdentity(
            snapshot,
            model,
            warning,
            missingType,
            "Diagnostic identity only; target non-concealment is evaluated separately.",
        )
    }.sortedWith(DIAGNOSTIC_ORDER)

    private fun diagnosticIdentity(
        snapshot: ProjectSnapshot,
        model: BuildModel,
        warning: JdtJavaSemanticWarning,
        missingType: String,
        reason: String,
    ): JavaMoveClassRetainedDiagnosticIdentity {
        val owner = sourceSet(snapshot, model, warning.path)
        val sourceLevel = owner?.let { identity ->
            model.modules.single { it.id == identity.moduleId }.sourceSets.single { it.id == identity.sourceSetId }
                .attributes["java.sourceLevel"]
        }.orEmpty()
        val providerHash = sha256("$PROVIDER_CONFIGURATION|model=${model.providerId}|sourceLevel=$sourceLevel")
        return JavaMoveClassRetainedDiagnosticIdentity(
            providerConfigurationHash = providerHash,
            problemId = warning.problemId,
            category = warning.category,
            severity = Diagnostic.Severity.ERROR,
            path = warning.path,
            sourceRange = warning.sourceRange,
            message = warning.message,
            missingExternalType = missingType,
            positiveNonConcealmentReason = reason,
        )
    }

    private fun missingTypeForWarning(
        content: String,
        warning: JdtJavaSemanticWarning,
        offlineEntries: List<JavaMoveClassOfflineMissingEntry>,
    ): String? {
        val providedTypes = offlineEntries.flatMap { it.providedTypes }.distinct()
        val start = runCatching { TextEdits.offsetOf(content, warning.sourceRange.start) }.getOrNull() ?: return null
        val imports = JavaLexer.extractImports(content).filterNot { it.isStatic || it.name.endsWith(".*") }
        val intersectingImport = imports.singleOrNull { start in it.startOffset until it.endOffset }
        if (intersectingImport != null) return intersectingImport.name.takeIf(providedTypes::contains)
        val messageMatches = providedTypes.filter { type ->
            warning.message.contains(type) || warning.message.contains(type.substringAfterLast('.'))
        }
        return messageMatches.singleOrNull()
    }

    private fun requiredClasspathEvidence(
        snapshot: ProjectSnapshot,
        entries: List<JavaMoveClassOfflineMissingEntry>,
        selectedRecords: List<JavaMoveClassSelectedMissingBinaryRecord>,
    ): List<ClasspathEvidence>? {
        val required = mutableListOf<ClasspathEvidence>()
        entries.forEach { entry ->
            required += snapshot.classpathEvidence.singleOrNull { evidence ->
                evidence.path.normalize() == entry.expectedPath.normalize() &&
                    evidence.kind == ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT &&
                    evidence.fingerprint == entry.negativePresenceFingerprint
            } ?: return null
            required += snapshot.classpathEvidence.singleOrNull { evidence ->
                evidence.path.normalize() == entry.expectedIdentityManifest.normalize() &&
                    evidence.kind == ClasspathEvidenceKind.DECLARATION_FILE && evidence.fingerprint != "missing"
            } ?: return null
        }
        selectedRecords.forEach { record ->
            required += snapshot.classpathEvidence.singleOrNull { evidence ->
                evidence.path.normalize() == record.deterministicJarPath.normalize() &&
                    evidence.kind == ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT &&
                    evidence.fingerprint == record.beforeJarAbsence.fingerprint
            } ?: return null
            record.effectiveInputContentHashes.keys.forEach { input ->
                val candidates = snapshot.classpathEvidence.filter { evidence ->
                    evidence.path.normalize() == input.normalize() &&
                        evidence.kind in setOf(
                            ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT,
                            ClasspathEvidenceKind.IMPORTED_BOM,
                        ) && evidence.fingerprint != "missing"
                }
                required += candidates.firstOrNull {
                    it.kind == ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT
                } ?: candidates.singleOrNull() ?: return null
            }
        }
        return required.distinct().sortedWith(compareBy<ClasspathEvidence> { it.path.toString() }.thenBy { it.kind.name })
    }

    private fun authorityEvidenceHash(
        prepared: JavaMoveClassOfflineAuthorityPrepared,
        stagedSnapshotHash: String,
        newFqn: String,
        newDeclarationPath: Path,
        stagedCandidates: List<JavaMoveClassCandidateRecord>,
        stagedRetained: List<JavaMoveClassRetainedDiagnosticIdentity>,
        stagedAll: List<JavaMoveClassRetainedDiagnosticIdentity>,
        workspaceEdit: WorkspaceEdit,
    ): String = hashParts(buildList {
        add(prepared.snapshot.hash)
        add(stagedSnapshotHash)
        add(prepared.symbolFqn)
        add(newFqn)
        add(prepared.declarationPath.invariantSeparatorsPathString)
        add(newDeclarationPath.invariantSeparatorsPathString)
        add(requireNotNull(prepared.selection.target.bindingKey))
        add(prepared.selection.closure.owner.displayName())
        addAll(prepared.selection.closure.sourceSets.map(MoveAuthoritySourceSet::displayName).sorted())
        add(candidateHash(prepared.candidates))
        add(candidateHash(stagedCandidates))
        prepared.offlineMissingEntries.forEach { entry ->
            add(listOf(
                entry.identity,
                entry.affectedSourceSet,
                entry.expectedPath.invariantSeparatorsPathString,
                entry.expectedIdentityManifest.invariantSeparatorsPathString,
                entry.expectedSha256,
                entry.providedTypes.sorted().joinToString(","),
                entry.negativePresenceFingerprint,
                entry.leaf,
            ).joinToString("\u0000"))
        }
        prepared.selectedMissingBinaryRecords.forEach { record ->
            add(record.selectedCoordinate)
            add(record.selectedVersion)
            add(record.type)
            add(record.classifier)
            add(record.extension)
            add(record.repositoryProvider)
            add(record.repositoryLayout)
            add(record.repositoryRoot.invariantSeparatorsPathString)
            add(record.repositoryPolicy)
            add(record.repositoryIdentityHash)
            add(record.deterministicPomPath.invariantSeparatorsPathString)
            add(record.deterministicJarPath.invariantSeparatorsPathString)
            record.selectingPaths.forEach { selection ->
                add("${selection.sourceSet}|${selection.projection}|${selection.dependencyPath}|${selection.effectiveScope}")
            }
            addAll(record.affectedAuthoritySourceSets)
            add(record.selectedPomContentHash)
            add(record.parsedDescriptorIdentityHash)
            record.effectiveInputContentHashes.forEach { (path, hash) ->
                add("${path.invariantSeparatorsPathString}|$hash")
            }
            add(record.graphLeafProof)
            add(record.graphLeafProofHash)
            add(record.beforeJarAbsence)
            add(record.stagedJarAbsence)
            add(record.expectedIdentityManifest)
            add(record.expectedJarContentHash)
            add(record.providedTypes)
        }
        add(diagnosticHash(prepared.retainedDiagnostics))
        add(diagnosticHash(stagedRetained))
        add(diagnosticHash(prepared.allDiagnostics))
        add(diagnosticHash(stagedAll))
        add(workspaceEditIdentity(WorkspaceEditSimulator.normalize(workspaceEdit)))
        add(PROVIDER_CONFIGURATION)
    })

    private fun workspaceEditIdentity(edit: WorkspaceEdit): String = edit.edits.joinToString("\u0001") { fileEdit ->
        when (fileEdit) {
            is FileEdit.Create -> "create\u0000${fileEdit.path}\u0000${fileEdit.overwrite}\u0000${fileEdit.content}"
            is FileEdit.Delete -> "delete\u0000${fileEdit.path}"
            is FileEdit.Rename -> "rename\u0000${fileEdit.path}\u0000${fileEdit.newPath}"
            is FileEdit.Modify -> "modify\u0000${fileEdit.path}\u0000" + fileEdit.textEdits.joinToString("\u0002") {
                "${it.range.start.line}:${it.range.start.character}-${it.range.end.line}:${it.range.end.character}\u0000${it.newText}"
            }
        }
    }

    private fun candidateHash(candidates: List<JavaMoveClassCandidateRecord>): String = hashParts(candidates.map { candidate ->
        listOf(
            candidate.path.invariantSeparatorsPathString,
            candidate.sourceRange.start.line,
            candidate.sourceRange.start.character,
            candidate.sourceRange.end.line,
            candidate.sourceRange.end.character,
            candidate.lexicalText,
            candidate.sourceSet,
            candidate.classification.name,
            candidate.bindingKey.orEmpty(),
        ).joinToString("\u0000")
    })

    private fun diagnosticHash(diagnostics: List<JavaMoveClassRetainedDiagnosticIdentity>): String = hashParts(
        diagnostics.map { diagnostic ->
            listOf(
                diagnostic.providerConfigurationHash,
                diagnostic.problemId,
                diagnostic.category.name,
                diagnostic.severity.name,
                diagnostic.path.invariantSeparatorsPathString,
                diagnostic.sourceRange.start.line,
                diagnostic.sourceRange.start.character,
                diagnostic.sourceRange.end.line,
                diagnostic.sourceRange.end.character,
                diagnostic.message,
                diagnostic.missingExternalType,
                diagnostic.positiveNonConcealmentReason,
            ).joinToString("\u0000")
        },
    )

    private fun diagnosticContractHash(
        diagnostics: List<JavaMoveClassRetainedDiagnosticIdentity>,
    ): String = hashParts(diagnostics.map { diagnostic ->
        listOf(
            diagnostic.providerConfigurationHash,
            diagnostic.problemId,
            diagnostic.category.name,
            diagnostic.severity.name,
            diagnostic.path.invariantSeparatorsPathString,
            diagnostic.sourceRange.start.line,
            diagnostic.sourceRange.start.character,
            diagnostic.sourceRange.end.line,
            diagnostic.sourceRange.end.character,
            diagnostic.message,
        ).joinToString("\u0000")
    })

    internal fun observerClosure(
        snapshot: ProjectSnapshot,
        declarationPath: Path,
        allowedStatuses: Set<BuildModelStatus>,
    ): MoveAuthorityObserverClosure? {
        val targetOwnership = snapshot.owningBuildSourceRoots(declarationPath).singleOrNull() ?: return null
        if (targetOwnership.providerId != MAVEN_PROVIDER || targetOwnership.modelStatus !in allowedStatuses) return null
        val model = snapshot.buildModels.singleOrNull { it.providerId == targetOwnership.providerId }
            ?.takeIf { it.status in allowedStatuses } ?: return null
        val owner = MoveAuthoritySourceSet(targetOwnership.module.id, targetOwnership.sourceSet.id)
        val mainSourceSets = linkedMapOf<String, BuildSourceSet>()
        for (module in model.modules) {
            val main = module.sourceSets.filter { it.kind == SourceSetKind.MAIN }.singleOrNull() ?: return null
            mainSourceSets[module.id] = main
        }
        val sourceSetInventory = snapshot.files.filter { it.languageId == "java" }.map { file ->
            sourceSet(snapshot, model, file.path) ?: return null
        }.toSet()
        if (owner !in sourceSetInventory) return null
        val closure = linkedSetOf(owner)
        model.modules.forEach { module ->
            module.sourceSets.forEach { sourceSet ->
                val candidate = MoveAuthoritySourceSet(module.id, sourceSet.id)
                if (candidate !in sourceSetInventory || candidate == owner) return@forEach
                if (sourceSetObservesTarget(module.id, sourceSet, owner, mainSourceSets)) closure += candidate
            }
        }
        return MoveAuthorityObserverClosure(model, owner, closure)
    }

    internal fun sourceSet(
        snapshot: ProjectSnapshot,
        model: BuildModel,
        path: Path,
    ): MoveAuthoritySourceSet? {
        val ownership = snapshot.owningBuildSourceRoots(path).singleOrNull() ?: return null
        if (ownership.providerId != model.providerId || ownership.modelStatus != model.status) return null
        val module = model.modules.singleOrNull { it.id == ownership.module.id } ?: return null
        if (module.sourceSets.singleOrNull { it.id == ownership.sourceSet.id } == null) return null
        return MoveAuthoritySourceSet(module.id, ownership.sourceSet.id)
    }

    private fun sourceSetObservesTarget(
        moduleId: String,
        sourceSet: BuildSourceSet,
        target: MoveAuthoritySourceSet,
        mainSourceSets: Map<String, BuildSourceSet>,
    ): Boolean {
        val testVisibility = sourceSet.kind in setOf(SourceSetKind.TEST, SourceSetKind.INTEGRATION_TEST) ||
            sourceSet.attributes["visibility"] == "test"
        if (moduleId == target.moduleId && target.sourceSetId == mainSourceSets[moduleId]?.id && testVisibility) {
            return true
        }
        data class PendingDependency(val moduleId: String, val scope: DependencyScope)
        val pending = ArrayDeque(sourceSet.moduleDependencies.map { PendingDependency(it.targetModuleId, it.scope) })
        val visited = mutableSetOf<PendingDependency>()
        while (pending.isNotEmpty()) {
            val dependency = pending.removeFirst()
            if (!scopeIsVisible(dependency.scope, testVisibility) || !visited.add(dependency)) continue
            if (dependency.moduleId == target.moduleId && target.sourceSetId == mainSourceSets[target.moduleId]?.id) {
                return true
            }
            mainSourceSets[dependency.moduleId]?.moduleDependencies.orEmpty().forEach { child ->
                transitiveScope(dependency.scope, child.scope)?.let { pending += PendingDependency(child.targetModuleId, it) }
            }
        }
        return false
    }

    private fun scopeIsVisible(scope: DependencyScope, testSource: Boolean): Boolean =
        if (testSource) scope != DependencyScope.CUSTOM
        else scope in setOf(DependencyScope.COMPILE, DependencyScope.PROVIDED, DependencyScope.SYSTEM)

    private fun transitiveScope(parent: DependencyScope, child: DependencyScope): DependencyScope? = when (parent) {
        DependencyScope.COMPILE -> when (child) {
            DependencyScope.COMPILE -> DependencyScope.COMPILE
            DependencyScope.RUNTIME -> DependencyScope.RUNTIME
            else -> null
        }
        DependencyScope.PROVIDED -> when (child) {
            DependencyScope.COMPILE, DependencyScope.RUNTIME -> DependencyScope.PROVIDED
            else -> null
        }
        DependencyScope.RUNTIME -> when (child) {
            DependencyScope.COMPILE, DependencyScope.RUNTIME -> DependencyScope.RUNTIME
            else -> null
        }
        DependencyScope.TEST -> when (child) {
            DependencyScope.COMPILE, DependencyScope.RUNTIME -> DependencyScope.TEST
            else -> null
        }
        else -> null
    }

    private fun analyzeOverlay(snapshot: ProjectSnapshot): JdtJavaSemanticAnalysisResult =
        JavaLanguageAdapter().analyzeDiagnosticsOverlay(snapshot)

    private fun hashParts(parts: List<Any?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            digest.update(part.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String = hashParts(listOf(value))

    private fun rawSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private val SHA256 = Regex("[a-f0-9]{64}")
    private const val MAX_EFFECTIVE_INPUTS = 256
    private const val MAX_MISSING_SELECTIONS = 256
    private val MOVEABLE_KINDS = setOf(
        JdtJavaSemanticSymbolKind.CLASS,
        JdtJavaSemanticSymbolKind.INTERFACE,
        JdtJavaSemanticSymbolKind.ENUM,
        JdtJavaSemanticSymbolKind.RECORD,
        JdtJavaSemanticSymbolKind.ANNOTATION,
    )
    private val DIAGNOSTIC_ORDER = compareBy<JavaMoveClassRetainedDiagnosticIdentity> {
        it.path.invariantSeparatorsPathString
    }.thenBy { it.sourceRange.start.line }
        .thenBy { it.sourceRange.start.character }
        .thenBy(JavaMoveClassRetainedDiagnosticIdentity::problemId)
        .thenBy(JavaMoveClassRetainedDiagnosticIdentity::message)
}
