package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticAnalysisAuthority
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.AuthoritativeDiagnosticsEvaluation
import org.refactorkit.core.AuthoritativeDiagnosticsProvider
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.OperationAuthorityEvidenceCompleteness
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.security.MessageDigest
import java.util.Collections

class JavaContextAuthorityException(val code: String, message: String) : RuntimeException(message)

/** Application-owned immutable authority passed to every JDT invocation and cache lookup. */
class JavaContextAuthority(view: JavaProjectContextView) : SemanticAnalysisAuthority {
    override val providerId = "java-project-context-v1"
    override val identitySha256 = view.contextHash
    val platforms: Map<Int, JavaReleasePlatformAuthority> = Collections.unmodifiableMap(view.sdk.associateBy { it.release })

    fun requireCurrent() {
        for (expected in platforms.values) {
            val current = JavaReleasePlatformAuthorityResolver().revalidate(expected)
            if ((current as? JavaReleasePlatformResolution.Available)?.authority?.identitySha256 != expected.identitySha256) {
                throw JavaContextAuthorityException("java.context.contextStale", "Configured SDK evidence changed; reacquire project context")
            }
        }
    }
}

sealed interface JavaBoundContextResolution {
    data class Available(val context: JavaBoundContext) : JavaBoundContextResolution
    data class Refused(val code: String, val message: String) : JavaBoundContextResolution
}

sealed interface JavaBoundReadResult<out T> {
    data class Available<T>(val context: JavaBoundContext, val value: T) : JavaBoundReadResult<T>
    data class Refused(val code: String, val message: String) : JavaBoundReadResult<Nothing>
}

data class JavaContextPreview(val plan: PatchPlan, val diagnosticsGate: DiagnosticsGate)

/** Snapshot/SDK authority for project-structure operations that do not mutate Java semantics. */
object JavaProjectStructuralContextAuthority {
    const val AUTHORITY_KIND = "java-project-snapshot-structural-v1"
    private const val MARKER = "javaContextAuthorityKind"

    fun bindMavenModulePlan(
        plan: PatchPlan,
        view: JavaProjectContextView,
        schemaVersion: String,
        scan: () -> ProjectSnapshot,
    ): PatchPlan {
        if (plan.operation != JavaCreateMavenModulePlanner.OPERATION || plan.status != PatchStatus.PREVIEW) {
            throw JavaContextAuthorityException("java.context.operationUnsupported",
                "Structural Java context can bind only a Maven Add Module preview")
        }
        if (view.sdk.isEmpty()) throw JavaContextAuthorityException("java.context.sdkRequired",
            "Structural Java context requires explicit SDK evidence")
        if (scan().hash != plan.snapshotHash) throw JavaContextAuthorityException("java.context.snapshotStale",
            "Workspace inputs changed during the Maven Add Module preview")
        for (expected in view.sdk) {
            val current = JavaReleasePlatformAuthorityResolver().revalidate(expected)
            if ((current as? JavaReleasePlatformResolution.Available)?.authority?.identitySha256 != expected.identitySha256) {
                throw JavaContextAuthorityException("java.context.contextStale",
                    "Configured SDK evidence changed during the Maven Add Module preview")
            }
        }
        val previous = requireNotNull(plan.authorityLease)
        val sdkHome = try { view.sdk.first().platformHome.toRealPath().toString() } catch (_: Exception) {
            throw JavaContextAuthorityException("java.context.contextStale",
                "Configured SDK home changed during the Maven Add Module preview")
        }
        val attributes = previous.attributes + mapOf(
            MARKER to AUTHORITY_KIND,
            "retainInTransactionJournal" to "true",
            "javaContextHash" to view.contextHash,
            "javaContextSchemaVersion" to schemaVersion,
            "javaSdkHome" to sdkHome,
            "javaPlatformIdentities" to platformIdentities(view),
        )
        val lease = OperationAuthorityLease(
            kind = previous.kind,
            operation = previous.operation,
            snapshotHash = previous.snapshotHash,
            evidenceHash = JavaRenameMavenModuleEvidence.authorityEvidenceHash(
                attributes, previous.requiredFileEvidence, previous.requiredClasspathEvidence,
            ),
            evidenceCompleteness = previous.evidenceCompleteness,
            workspaceEditSha256 = previous.workspaceEditSha256,
            requiredClasspathEvidence = previous.requiredClasspathEvidence,
            requiredFileEvidence = previous.requiredFileEvidence,
            attributes = attributes,
        )
        return plan.copy(authorityLease = lease)
    }

    fun isStructural(plan: PatchPlan): Boolean =
        plan.authorityLease?.attributes?.get(MARKER) == AUTHORITY_KIND

    fun validate(plan: PatchPlan, request: JavaProjectContextRequest?, scan: () -> ProjectSnapshot) {
        val attributes = plan.authorityLease?.attributes ?: return
        if (attributes[MARKER] != AUTHORITY_KIND) return
        if (request == null) throw JavaContextAuthorityException("java.context.authorityRequired",
            "Structural project preview requires its original javaContext at apply")
        if (request.expectedSnapshotHash != plan.snapshotHash) throw JavaContextAuthorityException(
            "java.context.snapshotStale", "Apply snapshot differs from the exact preview snapshot")
        if (request.expectedContextHash != attributes["javaContextHash"] || request.contextSchemaVersion !=
            (attributes["javaContextSchemaVersion"] ?: JavaProjectContextRequest.DEFAULT_CONTEXT_SCHEMA_VERSION)) {
            throw JavaContextAuthorityException("java.context.contextStale",
                "Apply context differs from the retained structural authority")
        }
        val currentSdkHome = try { request.sdkHome?.toRealPath()?.toString() } catch (_: Exception) { null }
        if (currentSdkHome != attributes["javaSdkHome"]) throw JavaContextAuthorityException(
            "java.context.contextStale", "Apply SDK differs from the structural preview authority")
        when (val current = JavaEffectiveProjectContextProvider().read(request, scan)) {
            is JavaProjectContextResolution.Refused -> throw JavaContextAuthorityException(current.code, current.message)
            is JavaProjectContextResolution.Available -> if (current.view.contextHash != attributes["javaContextHash"] ||
                platformIdentities(current.view) != attributes["javaPlatformIdentities"]) {
                throw JavaContextAuthorityException("java.context.contextStale",
                    "SDK or project context changed since structural preview")
            }
        }
    }

    fun rollbackAuthority(
        record: org.refactorkit.core.TransactionJournalRecord,
        request: JavaProjectContextRequest?,
        scan: () -> ProjectSnapshot,
    ): org.refactorkit.core.RollbackAuthorityResolution? {
        val attributes = record.authorityAttributes
        if (attributes[MARKER] != AUTHORITY_KIND) return null
        fun refused(code: String, message: String) = org.refactorkit.core.RollbackAuthorityResolution.Refused(code, message)
        if (request == null) return refused("java.context.authorityRequired",
            "Structural project rollback requires its original javaContext")
        if (request.expectedContextHash != attributes["javaContextHash"] ||
            request.contextSchemaVersion != (attributes["javaContextSchemaVersion"]
                ?: JavaProjectContextRequest.DEFAULT_CONTEXT_SCHEMA_VERSION)) {
            return refused("java.context.contextStale", "Rollback context differs from the original transaction")
        }
        if (request.expectedSnapshotHash != record.postSnapshotHash) return refused("java.context.snapshotStale",
            "Rollback requires the exact committed snapshot")
        return try {
            if (request.sdkHome?.toRealPath()?.toString() != attributes["javaSdkHome"]) return refused(
                "java.context.contextStale", "Rollback SDK differs from the original transaction")
            val current = scan()
            if (current.hash != record.postSnapshotHash) return refused("java.context.snapshotStale",
                "Workspace inputs changed after the transaction")
            when (val acquired = JavaEffectiveProjectContextProvider().read(
                request.copy(expectedContextHash = null), { current },
            )) {
                is JavaProjectContextResolution.Refused -> refused(acquired.code, acquired.message)
                is JavaProjectContextResolution.Available -> if (!containsPlatformEvidence(
                    attributes["javaPlatformIdentities"], platformIdentities(acquired.view),
                )) {
                    refused("java.context.contextStale", "Original SDK platform evidence changed")
                } else org.refactorkit.core.RollbackAuthorityResolution.NotRequired
            }
        } catch (failure: JavaContextAuthorityException) {
            refused(failure.code, failure.message.orEmpty())
        } catch (_: Exception) {
            refused("java.context.rollbackAuthorityUnavailable", "Structural rollback authority could not be reacquired")
        }
    }

    private fun platformIdentities(view: JavaProjectContextView): String =
        view.sdk.sortedBy { it.release }.joinToString(",") { "${it.release}:${it.identitySha256}" }

    private fun containsPlatformEvidence(expected: String?, current: String): Boolean {
        val expectedRows = expected.orEmpty().split(',').filter(String::isNotBlank).toSet()
        val currentRows = current.split(',').filter(String::isNotBlank).toSet()
        return expectedRows.isNotEmpty() && currentRows.containsAll(expectedRows)
    }
}

/** Query/application service; transport extracts requests and projects its results. */
class JavaBoundContext private constructor(
    val view: JavaProjectContextView,
    val contextSchemaVersion: String = JavaProjectContextRequest.DEFAULT_CONTEXT_SCHEMA_VERSION,
) {
    val authority = JavaContextAuthority(view)
    val snapshot = AuthoritativeDiagnosticsEvaluation(view.snapshot, emptyList()).snapshot.copy(semanticAnalysisAuthority = authority)
    val cache = JdtJavaAnalysisCache(analyze = { candidate, cancellation ->
        if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
        require(candidate.hash == snapshot.hash && candidate.semanticAnalysisAuthority === authority)
        authority.requireCurrent()
        requireNotNull(view.analysis).analysis
    })
    val adapter = JavaLanguageAdapter(cache)

    fun admitOperation(operation: String) {
        if (!JavaAdapterRegistration.supportsSdkMutationOperation(operation)) throw JavaContextAuthorityException(
            "java.context.operationUnsupported", "This operation has no qualified SDK-bound mutation route")
    }

    fun attestPreview(plan: PatchPlan, scan: () -> ProjectSnapshot): JavaContextPreview {
        val gate = diagnosticsGate()
        if (plan.status == PatchStatus.REFUSED) return JavaContextPreview(plan, gate)
        val candidate = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        val before = requireNotNull(gate.provider).invoke(snapshot)
        val after = requireNotNull(gate.provider).invoke(candidate)
        verifyAfterQuery(scan)?.let { throw JavaContextAuthorityException(it.code, it.message) }
        return attestPlan(plan, gate, before, after)
    }

    fun diagnosticsGate(): DiagnosticsGate {
        val diagnosticsAdapter = JavaLanguageAdapter()
        return DiagnosticsGate.authoritative("java-context-sdk-jdt", AuthoritativeDiagnosticsProvider { candidate ->
            authority.requireCurrent()
            val diagnostics = diagnosticsAdapter.diagnostics(candidate.copy(semanticAnalysisAuthority = authority))
            authority.requireCurrent()
            AuthoritativeDiagnosticsEvaluation(candidate, diagnostics)
        })
    }

    private fun attestPlan(plan: PatchPlan, gate: DiagnosticsGate, before: List<org.refactorkit.core.Diagnostic>, after: List<org.refactorkit.core.Diagnostic>): JavaContextPreview {
        val previous = plan.authorityLease
        val evidenceHash = MessageDigest.getInstance("SHA-256").digest(
            "java-context-mutation-v1\n${view.contextHash}\n${previous?.evidenceHash.orEmpty()}".toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
        val lease = OperationAuthorityLease(
            kind = previous?.kind ?: "java-context-mutation-v1", operation = plan.operation,
            snapshotHash = plan.snapshotHash, evidenceHash = evidenceHash,
            evidenceCompleteness = previous?.evidenceCompleteness ?: OperationAuthorityEvidenceCompleteness.COMPLETE,
            workspaceEditSha256 = WorkspaceEditIdentity.sha256(plan.workspaceEdit),
            requiredClasspathEvidence = (previous?.requiredClasspathEvidence.orEmpty() + snapshot.classpathEvidence)
                .distinctBy { it.path.normalize() to it.kind },
            requiredFileEvidence = previous?.requiredFileEvidence.orEmpty(),
            attributes = previous?.attributes.orEmpty() + mapOf("javaContextHash" to view.contextHash,
                "javaContextSchemaVersion" to contextSchemaVersion,
                "javaSdkHome" to view.sdk.first().platformHome.toString(),
                "rollbackAuthorityProvider" to "java-project-context-v1",
                "javaPlatformIdentities" to view.sdk.sortedBy { it.release }.joinToString(",") {
                    "${it.release}:${it.identitySha256}"
                }),
        )
        return JavaContextPreview(plan.copy(authorityLease = lease, diagnosticsBefore = before,
            diagnosticsAfterPreview = after), gate)
    }

    fun verifyAfterQuery(scan: () -> ProjectSnapshot): JavaBoundContextResolution.Refused? {
        if (scan().hash != snapshot.hash) return JavaBoundContextResolution.Refused(
            "java.context.snapshotStale", "Workspace inputs changed during the Java query")
        return try { authority.requireCurrent(); null } catch (failure: JavaContextAuthorityException) {
            JavaBoundContextResolution.Refused(failure.code, failure.message.orEmpty())
        }
    }

    companion object {
        internal fun closedEditorContext(base:JavaBoundContext,provider:ProjectSnapshot,analysis:JdtJavaSemanticAnalysisResult):JavaBoundContext =
            JavaBoundContext(base.view.copy(snapshot=provider,selectedSource=null,
                analysis=JdtAuthoritativeJavaAnalysisResolution.Available(analysis.copy(snapshotHash=provider.hash),
                    base.view.sdk.associate { it.release to it.identitySha256 })), base.contextSchemaVersion)

        fun supportedMutationOperations(): List<String> = JavaAdapterRegistration.sdkMutationOperations()

        fun rollbackAuthority(record: org.refactorkit.core.TransactionJournalRecord, request: JavaProjectContextRequest?,
                              scan: () -> ProjectSnapshot): org.refactorkit.core.RollbackAuthorityResolution {
            fun refused(code: String, message: String) = org.refactorkit.core.RollbackAuthorityResolution.Refused(code, message)
            val stored = record.authorityAttributes
            JavaProjectStructuralContextAuthority.rollbackAuthority(record, request, scan)?.let { return it }
            if ("rollbackAuthorityProvider" !in stored) return if (request == null) org.refactorkit.core.RollbackAuthorityResolution.NotRequired
                else refused("java.context.rollbackAuthorityUnavailable", "This transaction has no durable SDK rollback authority")
            if (stored["rollbackAuthorityProvider"] != "java-project-context-v1") return refused("java.context.rollbackAuthorityUnavailable", "Unknown rollback authority provider")
            if (request == null) return refused("java.context.authorityRequired", "SDK rollback requires its original javaContext")
            if (request.expectedContextHash != stored["javaContextHash"]) return refused("java.context.contextStale", "Rollback context differs from the original transaction")
            if (request.contextSchemaVersion != (stored["javaContextSchemaVersion"]
                    ?: JavaProjectContextRequest.DEFAULT_CONTEXT_SCHEMA_VERSION)) {
                return refused("java.context.contextStale", "Rollback context schema differs from the original transaction")
            }
            if (request.expectedSnapshotHash != record.postSnapshotHash) return refused("java.context.snapshotStale", "Rollback requires the exact committed snapshot")
            return try {
                if (request.sdkHome?.toRealPath()?.toString() != stored["javaSdkHome"]) return refused("java.context.contextStale", "Rollback SDK differs from the original transaction")
                val current = scan()
                if (current.hash != record.postSnapshotHash) return refused("java.context.snapshotStale", "Workspace inputs changed after the transaction")
                when (val acquired = acquire(request.copy(expectedContextHash = null), { current })) {
                    is JavaBoundContextResolution.Refused -> refused(acquired.code, acquired.message)
                    is JavaBoundContextResolution.Available -> {
                        val context = acquired.context
                        val identities = context.view.sdk.sortedBy { it.release }.joinToString(",") { "${it.release}:${it.identitySha256}" }
                        if (identities != stored["javaPlatformIdentities"]) refused("java.context.contextStale", "Original SDK platform evidence changed")
                        else org.refactorkit.core.RollbackAuthorityResolution.Available(context.view.snapshot, context.diagnosticsGate())
                    }
                }
            } catch (failure: JavaContextAuthorityException) { refused(failure.code, failure.message.orEmpty()) }
            catch (_: Exception) { refused("java.context.rollbackAuthorityUnavailable", "SDK rollback authority could not be acquired") }
        }

        fun rollbackDiagnostics(record: org.refactorkit.core.TransactionJournalRecord, snapshot: ProjectSnapshot): List<org.refactorkit.core.Diagnostic>? {
            if (record.authorityAttributes["javaContextAuthorityKind"] == JavaProjectStructuralContextAuthority.AUTHORITY_KIND) return null
            val contextHash = record.authorityAttributes["javaContextHash"] ?: return null
            val home = record.authorityAttributes["javaSdkHome"] ?: throw JavaContextAuthorityException("java.context.rollbackAuthorityUnavailable", "SDK rollback evidence is incomplete")
            return when (val acquired = acquire(JavaProjectContextRequest(
                snapshot.hash, java.nio.file.Path.of(home), expectedContextHash = contextHash,
                contextSchemaVersion = record.authorityAttributes["javaContextSchemaVersion"]
                    ?: JavaProjectContextRequest.DEFAULT_CONTEXT_SCHEMA_VERSION,
            ), { snapshot })) {
                is JavaBoundContextResolution.Refused -> throw JavaContextAuthorityException(acquired.code, acquired.message)
                is JavaBoundContextResolution.Available -> acquired.context.adapter.diagnostics(acquired.context.snapshot)
            }
        }
        fun validateApply(plan: PatchPlan, request: JavaProjectContextRequest?, scan: () -> ProjectSnapshot) {
            if (JavaProjectStructuralContextAuthority.isStructural(plan)) {
                JavaProjectStructuralContextAuthority.validate(plan, request, scan)
                return
            }
            val expectedContext = plan.authorityLease?.attributes?.get("javaContextHash") ?: return
            if (request == null) throw JavaContextAuthorityException("java.context.authorityRequired",
                "SDK-bound preview requires its original javaContext at apply")
            if (request.expectedSnapshotHash != plan.snapshotHash) throw JavaContextAuthorityException("java.context.snapshotStale",
                "Apply snapshot differs from the exact preview snapshot")
            if (request.expectedContextHash != expectedContext || request.contextSchemaVersion !=
                (plan.authorityLease?.attributes?.get("javaContextSchemaVersion")
                    ?: JavaProjectContextRequest.DEFAULT_CONTEXT_SCHEMA_VERSION)) throw JavaContextAuthorityException("java.context.contextStale",
                "Apply context differs from the retained preview authority")
            when (val current = acquire(request, scan)) {
                is JavaBoundContextResolution.Refused -> throw JavaContextAuthorityException(current.code, current.message)
                is JavaBoundContextResolution.Available -> if (current.context.view.contextHash != expectedContext) {
                    throw JavaContextAuthorityException("java.context.contextStale", "SDK or build context changed since preview")
                }
            }
        }

        fun <T> read(request: JavaProjectContextRequest, scan: () -> ProjectSnapshot,
                     cancellation: SemanticCancellationToken, query: (JavaBoundContext) -> T): JavaBoundReadResult<T> {
            val acquired = acquire(request, scan, cancellation)
            if (acquired is JavaBoundContextResolution.Refused) return JavaBoundReadResult.Refused(acquired.code, acquired.message)
            val context = (acquired as JavaBoundContextResolution.Available).context
            return try {
                val result = query(context)
                val drift = context.verifyAfterQuery(scan)
                if (drift != null) JavaBoundReadResult.Refused(drift.code, drift.message)
                else JavaBoundReadResult.Available(context, result)
            } catch (failure: JavaContextAuthorityException) {
                JavaBoundReadResult.Refused(failure.code, failure.message.orEmpty())
            }
        }

        fun acquire(request: JavaProjectContextRequest, scan: () -> ProjectSnapshot,
                    cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE): JavaBoundContextResolution =
            when (val resolution = JavaEffectiveProjectContextProvider().read(request, scan, cancellation)) {
                is JavaProjectContextResolution.Refused -> JavaBoundContextResolution.Refused(resolution.code, resolution.message)
                is JavaProjectContextResolution.Available -> if (resolution.view.effective && resolution.view.sdk.isNotEmpty()) {
                    JavaBoundContextResolution.Available(JavaBoundContext(resolution.view, request.contextSchemaVersion))
                } else if (resolution.view.metadataAvailable && resolution.view.analysis == null &&
                    resolution.view.semanticAnalysisRefusalCode != null) {
                    JavaBoundContextResolution.Refused(
                        resolution.view.semanticAnalysisRefusalCode,
                        "Build metadata is available, but Java source analysis is unqualified for this context",
                    )
                } else JavaBoundContextResolution.Refused("java.context.effectiveModelUnavailable",
                    "Java semantic operations require an effective build model and explicit SDK authority")
            }
    }
}
