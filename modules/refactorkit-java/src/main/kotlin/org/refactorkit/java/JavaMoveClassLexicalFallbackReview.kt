package org.refactorkit.java

import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import java.util.ArrayList
import java.util.Collections
import kotlin.io.path.invariantSeparatorsPathString

/** Caller intent is recorded as typed metadata but is never authority evidence. */
enum class JavaMoveClassPromotionApproval {
    UNSPECIFIED,
    APPROVED,
    REJECTED,
}

enum class JavaMoveClassWarningAcknowledgement {
    UNSPECIFIED,
    ACKNOWLEDGED,
}

data class JavaMoveClassPromotionConfidence(val value: Double) {
    init {
        require(value.isFinite() && value in 0.0..1.0) {
            "promotion-attempt confidence must be finite and between zero and one"
        }
    }
}

enum class JavaMoveClassForceRequest {
    NOT_REQUESTED,
    REQUESTED,
}

data class JavaMoveClassPromotionAttemptMetadata(
    val approval: JavaMoveClassPromotionApproval = JavaMoveClassPromotionApproval.UNSPECIFIED,
    val warningAcknowledgement: JavaMoveClassWarningAcknowledgement =
        JavaMoveClassWarningAcknowledgement.UNSPECIFIED,
    val confidence: JavaMoveClassPromotionConfidence? = null,
    val force: JavaMoveClassForceRequest = JavaMoveClassForceRequest.NOT_REQUESTED,
) {
    companion object {
        val NONE = JavaMoveClassPromotionAttemptMetadata()

        /** Parse transport-neutral promotion metadata without granting it authority. */
        fun fromRaw(
            approval: String? = null,
            warningAcknowledgement: String? = null,
            confidence: String? = null,
            force: String? = null,
        ): JavaMoveClassPromotionAttemptMetadata {
            fun requireBoolean(name: String, value: String?) {
                if (value != null && value != "true" && value != "false") {
                    throw IllegalArgumentException("$name must be true or false")
                }
            }

            requireBoolean("approval", approval)
            requireBoolean("warningAcknowledgement", warningAcknowledgement)
            requireBoolean("force", force)
            val confidenceValue = confidence?.let { raw ->
                val value = raw.toDoubleOrNull()
                require(value != null && value.isFinite() && value in 0.0..1.0) {
                    "confidence must be a finite number between zero and one"
                }
                JavaMoveClassPromotionConfidence(value)
            }
            return JavaMoveClassPromotionAttemptMetadata(
                approval = when (approval) {
                    "true" -> JavaMoveClassPromotionApproval.APPROVED
                    "false" -> JavaMoveClassPromotionApproval.REJECTED
                    else -> JavaMoveClassPromotionApproval.UNSPECIFIED
                },
                warningAcknowledgement = if (warningAcknowledgement == "true") {
                    JavaMoveClassWarningAcknowledgement.ACKNOWLEDGED
                } else {
                    JavaMoveClassWarningAcknowledgement.UNSPECIFIED
                },
                confidence = confidenceValue,
                force = if (force == "true") {
                    JavaMoveClassForceRequest.REQUESTED
                } else {
                    JavaMoveClassForceRequest.NOT_REQUESTED
                },
            )
        }
    }
}

enum class JavaMoveClassLexicalReviewResultType {
    LEXICAL_FALLBACK_REVIEW,
}

enum class JavaMoveClassLexicalReviewAuthorityStatus {
    REVIEW_ONLY,
}

enum class JavaMoveClassLexicalReviewEvidenceKind {
    LEXICAL_FALLBACK,
}

enum class JavaMoveClassLexicalReviewManagedWriteEligibility {
    INELIGIBLE,
}

enum class JavaMoveClassLexicalReviewSemanticCompleteness {
    NOT_SEMANTICALLY_PROVEN,
}

enum class JavaMoveClassLexicalReviewNextAction {
    INSPECT_RESIDUALS,
    RESTORE_SEMANTIC_EVIDENCE,
    FULL_REACTOR_RESCAN,
    REQUEST_NEW_PREVIEW,
}

data class JavaMoveClassLexicalReviewRequest(
    val operation: String,
    val symbolFqn: String,
    val targetPackage: String,
    val targetFqn: String,
) {
    init {
        require(operation == "moveClass") { "lexical review request must be moveClass" }
        require(symbolFqn.isNotBlank()) { "lexical review symbol must not be blank" }
        require(targetPackage.isNotBlank()) { "lexical review target package must not be blank" }
        require(targetFqn == "$targetPackage.${JavaPackageUtil.simpleName(symbolFqn)}") {
            "lexical review target identity must be normalized"
        }
    }
}

enum class JavaMoveClassLexicalReviewEvidenceFactType {
    LEXICAL_FALLBACK_CLASSIFIED,
    BROAD_SYNTAX_FAILURE,
}

data class JavaMoveClassLexicalReviewEvidenceFact(
    val type: JavaMoveClassLexicalReviewEvidenceFactType,
    val normalizedPath: String? = null,
    val currentContentSha256: String? = null,
    val problemId: Int? = null,
) {
    init {
        when (type) {
            JavaMoveClassLexicalReviewEvidenceFactType.LEXICAL_FALLBACK_CLASSIFIED -> {
                require(normalizedPath == null && currentContentSha256 == null && problemId == null) {
                    "fallback classification fact has no path-specific payload"
                }
            }
            JavaMoveClassLexicalReviewEvidenceFactType.BROAD_SYNTAX_FAILURE -> {
                requireLexicalReviewPath(requireNotNull(normalizedPath))
                requireLexicalReviewSha256(requireNotNull(currentContentSha256), "syntax-failure content hash")
                requireNotNull(problemId)
            }
        }
    }
}

enum class JavaMoveClassLexicalReviewRiskFact {
    DECLARATION_CANDIDATE,
    JAVA_LEXICAL_CANDIDATE,
    SYNTAX_FAILURE_SITE,
    REVIEW_ARTIFACT_SOURCE,
}

class JavaMoveClassLexicalReviewRiskRecord(
    val normalizedPath: String,
    val currentContentSha256: String,
    riskFacts: Collection<JavaMoveClassLexicalReviewRiskFact>,
) {
    private val riskFactValues = immutableLexicalReviewList(riskFacts)
    val managedEdit: Boolean = false
    val riskFacts: List<JavaMoveClassLexicalReviewRiskFact>
        get() = immutableLexicalReviewList(riskFactValues)

    init {
        requireLexicalReviewPath(normalizedPath)
        requireLexicalReviewSha256(currentContentSha256, "risk-record content hash")
        require(riskFactValues.isNotEmpty()) { "lexical review risk record requires typed facts" }
        require(riskFactValues == riskFactValues.distinct().sortedBy { it.name }) {
            "lexical review risk facts must be unique and canonically ordered"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is JavaMoveClassLexicalReviewRiskRecord &&
            normalizedPath == other.normalizedPath && currentContentSha256 == other.currentContentSha256 &&
            managedEdit == other.managedEdit && riskFactValues == other.riskFactValues

    override fun hashCode(): Int {
        var result = normalizedPath.hashCode()
        result = 31 * result + currentContentSha256.hashCode()
        result = 31 * result + managedEdit.hashCode()
        result = 31 * result + riskFactValues.hashCode()
        return result
    }
}

enum class JavaMoveClassLexicalReviewRecordCompleteness {
    COMPLETE,
    TRUNCATED,
}

/** Edit-free, bounded residual guidance containing only path/hash/risk identities. */
class JavaMoveClassLexicalReviewResidualGuidance internal constructor(
    val recordCompleteness: JavaMoveClassLexicalReviewRecordCompleteness,
    val truncated: Boolean,
    val totalRecordCount: Int,
    val canonicalRecordBytes: Int,
    records: Collection<JavaMoveClassLexicalReviewRiskRecord>,
) {
    private val recordValues = immutableLexicalReviewList(records)
    private val returnedRiskCategoryCountValues = immutableLexicalReviewMap(
        JavaMoveClassLexicalReviewRiskFact.values().sortedBy { it.name }.associateWith { category ->
            recordValues.count { category in it.riskFacts }
        },
    )

    val maxRecords: Int = MAX_RECORDS
    val maxCanonicalRecordBytes: Int = MAX_CANONICAL_RECORD_BYTES
    val returnedRecordCount: Int get() = recordValues.size
    val returnedRiskCategoryCounts: Map<JavaMoveClassLexicalReviewRiskFact, Int>
        get() = immutableLexicalReviewMap(returnedRiskCategoryCountValues)
    val records: List<JavaMoveClassLexicalReviewRiskRecord>
        get() = immutableLexicalReviewList(recordValues)

    init {
        require(totalRecordCount >= recordValues.size) { "total lexical review records cannot be under-reported" }
        require(recordValues.size <= MAX_RECORDS) { "lexical review record bound exceeded" }
        require(canonicalRecordBytes in 0..MAX_CANONICAL_RECORD_BYTES) {
            "lexical review canonical record-byte bound exceeded"
        }
        require(recordValues == recordValues.sortedWith(JAVA_MOVE_LEXICAL_REVIEW_RISK_ORDER)) {
            "lexical review records must be canonically ordered"
        }
        require(
            returnedRiskCategoryCountValues.keys.toList() ==
                JavaMoveClassLexicalReviewRiskFact.values().sortedBy { it.name },
        ) { "lexical review risk-category counts must be canonically ordered and complete" }
        require(truncated == (recordCompleteness == JavaMoveClassLexicalReviewRecordCompleteness.TRUNCATED)) {
            "lexical review record completeness and truncation must agree"
        }
        require(!truncated || totalRecordCount > recordValues.size) {
            "truncated lexical review guidance must report omitted records"
        }
        require(truncated || totalRecordCount == recordValues.size) {
            "complete lexical review record enumeration cannot omit records"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is JavaMoveClassLexicalReviewResidualGuidance &&
            recordCompleteness == other.recordCompleteness && truncated == other.truncated &&
            totalRecordCount == other.totalRecordCount &&
            returnedRiskCategoryCountValues == other.returnedRiskCategoryCountValues &&
            canonicalRecordBytes == other.canonicalRecordBytes && recordValues == other.recordValues

    override fun hashCode(): Int {
        var result = recordCompleteness.hashCode()
        result = 31 * result + truncated.hashCode()
        result = 31 * result + totalRecordCount
        result = 31 * result + returnedRiskCategoryCountValues.hashCode()
        result = 31 * result + canonicalRecordBytes
        result = 31 * result + recordValues.hashCode()
        return result
    }

    companion object {
        const val MAX_RECORDS: Int = 200
        const val MAX_CANONICAL_RECORD_BYTES: Int = 262_144
    }
}

/**
 * Canonical immutable non-capability envelope. Its type surface intentionally has no plan,
 * workspace mutation, range, replacement, transaction, or rollback representation.
 */
class JavaMoveClassLexicalFallbackReviewEnvelope internal constructor(
    val request: JavaMoveClassLexicalReviewRequest,
    val requestSha256: String,
    val snapshotSha256: String,
    val reviewArtifactSha256: String,
    val evidenceSha256: String,
    val operationId: String,
    evidenceFacts: Collection<JavaMoveClassLexicalReviewEvidenceFact>,
    val residualGuidance: JavaMoveClassLexicalReviewResidualGuidance,
) {
    private val evidenceFactValues = immutableLexicalReviewList(evidenceFacts)
    private val nextActionValues = immutableLexicalReviewList(JavaMoveClassLexicalReviewNextAction.values().asList())

    val schemaVersion: Int = SCHEMA_VERSION
    val canonicalization: String = CANONICALIZATION
    val hashAlgorithm: String = HASH_ALGORITHM
    val resultType: JavaMoveClassLexicalReviewResultType =
        JavaMoveClassLexicalReviewResultType.LEXICAL_FALLBACK_REVIEW
    val semanticCompleteness: JavaMoveClassLexicalReviewSemanticCompleteness =
        JavaMoveClassLexicalReviewSemanticCompleteness.NOT_SEMANTICALLY_PROVEN
    val authorityStatus: JavaMoveClassLexicalReviewAuthorityStatus =
        JavaMoveClassLexicalReviewAuthorityStatus.REVIEW_ONLY
    val evidenceKind: JavaMoveClassLexicalReviewEvidenceKind =
        JavaMoveClassLexicalReviewEvidenceKind.LEXICAL_FALLBACK
    val managedWriteEligibility: JavaMoveClassLexicalReviewManagedWriteEligibility =
        JavaMoveClassLexicalReviewManagedWriteEligibility.INELIGIBLE
    val blockerCode: String = BLOCKER_CODE
    val nextActions: List<JavaMoveClassLexicalReviewNextAction>
        get() = immutableLexicalReviewList(nextActionValues)
    val evidenceFacts: List<JavaMoveClassLexicalReviewEvidenceFact>
        get() = immutableLexicalReviewList(evidenceFactValues)

    init {
        requireLexicalReviewSha256(requestSha256, "lexical review request identity")
        requireLexicalReviewSha256(snapshotSha256, "lexical review snapshot identity")
        requireLexicalReviewSha256(reviewArtifactSha256, "lexical review artifact identity")
        requireLexicalReviewSha256(evidenceSha256, "lexical review evidence identity")
        require(OPERATION_ID_PATTERN.matches(operationId)) { "lexical review operation ID is invalid" }
        require(evidenceFactValues.isNotEmpty()) { "lexical review requires typed evidence facts" }
        require(evidenceFactValues == evidenceFactValues.sortedWith(JAVA_MOVE_LEXICAL_REVIEW_EVIDENCE_ORDER)) {
            "lexical review evidence facts must be canonically ordered"
        }
        require(evidenceFactValues.distinct().size == evidenceFactValues.size) {
            "lexical review evidence facts must be unique"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is JavaMoveClassLexicalFallbackReviewEnvelope &&
            schemaVersion == other.schemaVersion && canonicalization == other.canonicalization &&
            hashAlgorithm == other.hashAlgorithm && resultType == other.resultType &&
            semanticCompleteness == other.semanticCompleteness && request == other.request &&
            requestSha256 == other.requestSha256 && snapshotSha256 == other.snapshotSha256 &&
            reviewArtifactSha256 == other.reviewArtifactSha256 && evidenceSha256 == other.evidenceSha256 &&
            operationId == other.operationId && authorityStatus == other.authorityStatus &&
            evidenceKind == other.evidenceKind && managedWriteEligibility == other.managedWriteEligibility &&
            blockerCode == other.blockerCode && nextActionValues == other.nextActionValues &&
            evidenceFactValues == other.evidenceFactValues && residualGuidance == other.residualGuidance

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + canonicalization.hashCode()
        result = 31 * result + hashAlgorithm.hashCode()
        result = 31 * result + resultType.hashCode()
        result = 31 * result + semanticCompleteness.hashCode()
        result = 31 * result + request.hashCode()
        result = 31 * result + requestSha256.hashCode()
        result = 31 * result + snapshotSha256.hashCode()
        result = 31 * result + reviewArtifactSha256.hashCode()
        result = 31 * result + evidenceSha256.hashCode()
        result = 31 * result + operationId.hashCode()
        result = 31 * result + authorityStatus.hashCode()
        result = 31 * result + evidenceKind.hashCode()
        result = 31 * result + managedWriteEligibility.hashCode()
        result = 31 * result + blockerCode.hashCode()
        result = 31 * result + nextActionValues.hashCode()
        result = 31 * result + evidenceFactValues.hashCode()
        result = 31 * result + residualGuidance.hashCode()
        return result
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val CANONICALIZATION: String = "refactorkit.lexicalFallbackReview.canonical.v1"
        const val HASH_ALGORITHM: String = "SHA-256"
        const val BLOCKER_CODE: String = "evidence.insufficient"
        private val OPERATION_ID_PATTERN = Regex("lexical-review-[a-f0-9]{64}")
    }
}

/** Bounded Java-owned LRU correlation cache. Values are immutable envelopes only. */
class JavaMoveClassLexicalReviewAuditCache(
    val maxEntries: Int = MAX_ENTRIES,
    val maxCanonicalBytes: Int = MAX_CANONICAL_BYTES,
) {
    private val values = LinkedHashMap<String, JavaMoveClassLexicalFallbackReviewEnvelope>(16, 0.75f, true)
    private var byteCount: Int = 0

    init {
        require(maxEntries in 1..MAX_ENTRIES) { "lexical review cache entry bound is invalid" }
        require(maxCanonicalBytes in 1..MAX_CANONICAL_BYTES) { "lexical review cache byte bound is invalid" }
    }

    @Synchronized
    fun put(envelope: JavaMoveClassLexicalFallbackReviewEnvelope) {
        val bytes = JavaMoveClassLexicalFallbackReviewJsonProjection.canonicalBytes(envelope).size
        if (bytes > maxCanonicalBytes) return
        values.remove(envelope.operationId)?.let { previous ->
            byteCount -= JavaMoveClassLexicalFallbackReviewJsonProjection.canonicalBytes(previous).size
        }
        while (values.isNotEmpty() && (values.size >= maxEntries || byteCount + bytes > maxCanonicalBytes)) {
            val eldest = values.entries.iterator().next()
            values.remove(eldest.key)
            byteCount -= JavaMoveClassLexicalFallbackReviewJsonProjection.canonicalBytes(eldest.value).size
        }
        values[envelope.operationId] = envelope
        byteCount += bytes
        check(values.size <= maxEntries && byteCount <= maxCanonicalBytes)
    }

    @Synchronized
    fun find(operationId: String): JavaMoveClassLexicalFallbackReviewEnvelope? = values[operationId]

    @Synchronized
    fun clear() {
        values.clear()
        byteCount = 0
    }

    @Synchronized
    fun entryCount(): Int = values.size

    @Synchronized
    fun canonicalByteCount(): Int = byteCount

    companion object {
        const val MAX_ENTRIES: Int = 128
        const val MAX_CANONICAL_BYTES: Int = 16_777_216
    }
}

internal object JavaMoveClassLexicalFallbackReviewFactory {
    fun create(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        targetPackage: String,
        compatibilityPreview: JavaMoveClassPreview,
    ): JavaMoveClassLexicalFallbackReviewEnvelope {
        val compatibilityPlan = compatibilityPreview.plan
        require(compatibilityPlan.status == PatchStatus.PREVIEW) {
            "lexical fallback review requires a preview compatibility artifact"
        }
        require(compatibilityPlan.evidence == RefactoringEvidence.LEXICAL_FALLBACK) {
            "lexical fallback review requires legacy lexical evidence"
        }
        require(compatibilityPlan.snapshotHash == snapshot.hash) {
            "lexical fallback compatibility artifact belongs to another snapshot"
        }
        val normalizedSymbol = symbolFqn.trim()
        val normalizedTargetPackage = targetPackage.trim()
        val request = JavaMoveClassLexicalReviewRequest(
            operation = "moveClass",
            symbolFqn = normalizedSymbol,
            targetPackage = normalizedTargetPackage,
            targetFqn = "$normalizedTargetPackage.${JavaPackageUtil.simpleName(normalizedSymbol)}",
        )
        val requestSha256 = JavaMoveClassLexicalFallbackReviewJsonProjection.requestSha256(request)
        val reviewArtifactSha256 = JavaMoveClassLexicalFallbackReviewJsonProjection.reviewArtifactSha256(
            snapshot,
            compatibilityPlan,
        )
        val syntaxFacts = syntaxFailureFacts(snapshot)
        val evidenceFacts = (
            listOf(JavaMoveClassLexicalReviewEvidenceFact(
                JavaMoveClassLexicalReviewEvidenceFactType.LEXICAL_FALLBACK_CLASSIFIED,
            )) + syntaxFacts
        ).distinct().sortedWith(JAVA_MOVE_LEXICAL_REVIEW_EVIDENCE_ORDER)
        val residualGuidance = residualGuidance(snapshot, request, compatibilityPlan, syntaxFacts)
        val evidenceSha256 = JavaMoveClassLexicalFallbackReviewJsonProjection.evidenceSha256(
            requestSha256,
            snapshot.hash,
            reviewArtifactSha256,
            evidenceFacts,
            residualGuidance,
        )
        val operationId = JavaMoveClassLexicalFallbackReviewJsonProjection.operationId(
            requestSha256,
            snapshot.hash,
            reviewArtifactSha256,
            evidenceSha256,
        )
        return JavaMoveClassLexicalFallbackReviewEnvelope(
            request = request,
            requestSha256 = requestSha256,
            snapshotSha256 = snapshot.hash,
            reviewArtifactSha256 = reviewArtifactSha256,
            evidenceSha256 = evidenceSha256,
            operationId = operationId,
            evidenceFacts = evidenceFacts,
            residualGuidance = residualGuidance,
        )
    }

    private fun syntaxFailureFacts(snapshot: ProjectSnapshot): List<JavaMoveClassLexicalReviewEvidenceFact> {
        val contentByPath = snapshot.files.associateBy { it.path.normalize() }
        return runCatching { JdtJavaSemanticAnalyzer().analyze(snapshot) }.getOrNull()
            ?.warnings.orEmpty()
            .asSequence()
            .filter { it.category == JdtJavaDiagnosticCategory.SYNTAX }
            .mapNotNull { warning ->
                val source = contentByPath[warning.path.normalize()] ?: return@mapNotNull null
                JavaMoveClassLexicalReviewEvidenceFact(
                    type = JavaMoveClassLexicalReviewEvidenceFactType.BROAD_SYNTAX_FAILURE,
                    normalizedPath = source.path.normalize().invariantSeparatorsPathString,
                    currentContentSha256 = lexicalReviewSha256(source.content.toByteArray(Charsets.UTF_8)),
                    problemId = warning.problemId,
                )
            }
            .distinct()
            .sortedWith(JAVA_MOVE_LEXICAL_REVIEW_EVIDENCE_ORDER)
            .toList()
    }

    private fun residualGuidance(
        snapshot: ProjectSnapshot,
        request: JavaMoveClassLexicalReviewRequest,
        compatibilityPlan: PatchPlan,
        syntaxFacts: List<JavaMoveClassLexicalReviewEvidenceFact>,
    ): JavaMoveClassLexicalReviewResidualGuidance {
        val simpleName = JavaPackageUtil.simpleName(request.symbolFqn)
        val oldPackage = request.symbolFqn.substringBeforeLast('.', "")
        val affected = compatibilityPlan.affectedFiles.map { it.normalize().invariantSeparatorsPathString }.toSet()
        val syntaxPaths = syntaxFacts.mapNotNull { it.normalizedPath }.toSet()
        val allRecords = snapshot.files.asSequence()
            .filter { it.languageId == "java" }
            .mapNotNull { source ->
                val normalizedPath = source.path.normalize().invariantSeparatorsPathString
                val facts = buildSet {
                    if (JavaLexer.findOccurrences(source.content, request.symbolFqn).isNotEmpty() ||
                        JavaLexer.findOccurrences(source.content, simpleName).isNotEmpty()
                    ) add(JavaMoveClassLexicalReviewRiskFact.JAVA_LEXICAL_CANDIDATE)
                    if (JavaPackageUtil.extractPackage(source.content) == oldPackage &&
                        Regex("(?m)\\b(?:class|interface|enum|record|@interface)\\s+${Regex.escape(simpleName)}\\b")
                            .containsMatchIn(source.content)
                    ) add(JavaMoveClassLexicalReviewRiskFact.DECLARATION_CANDIDATE)
                    if (normalizedPath in syntaxPaths) add(JavaMoveClassLexicalReviewRiskFact.SYNTAX_FAILURE_SITE)
                    if (normalizedPath in affected) add(JavaMoveClassLexicalReviewRiskFact.REVIEW_ARTIFACT_SOURCE)
                }.sortedBy { it.name }
                if (facts.isEmpty()) null else JavaMoveClassLexicalReviewRiskRecord(
                    normalizedPath = normalizedPath,
                    currentContentSha256 = lexicalReviewSha256(source.content.toByteArray(Charsets.UTF_8)),
                    riskFacts = facts,
                )
            }
            .sortedWith(JAVA_MOVE_LEXICAL_REVIEW_RISK_ORDER)
            .toList()

        val returned = mutableListOf<JavaMoveClassLexicalReviewRiskRecord>()
        var canonicalBytes = 0
        for (record in allRecords) {
            val recordBytes = JavaMoveClassLexicalFallbackReviewJsonProjection.canonicalRecordBytes(record).size
            if (returned.size >= JavaMoveClassLexicalReviewResidualGuidance.MAX_RECORDS ||
                canonicalBytes + recordBytes > JavaMoveClassLexicalReviewResidualGuidance.MAX_CANONICAL_RECORD_BYTES
            ) break
            returned += record
            canonicalBytes += recordBytes
        }
        val truncated = returned.size < allRecords.size
        return JavaMoveClassLexicalReviewResidualGuidance(
            recordCompleteness = if (truncated) {
                JavaMoveClassLexicalReviewRecordCompleteness.TRUNCATED
            } else {
                JavaMoveClassLexicalReviewRecordCompleteness.COMPLETE
            },
            truncated = truncated,
            totalRecordCount = allRecords.size,
            canonicalRecordBytes = canonicalBytes,
            records = returned,
        )
    }
}

internal val JAVA_MOVE_LEXICAL_REVIEW_EVIDENCE_ORDER =
    compareBy<JavaMoveClassLexicalReviewEvidenceFact> { it.type.name }
        .thenBy { it.normalizedPath.orEmpty() }
        .thenBy { it.problemId ?: Int.MIN_VALUE }
        .thenBy { it.currentContentSha256.orEmpty() }

internal val JAVA_MOVE_LEXICAL_REVIEW_RISK_ORDER =
    compareBy<JavaMoveClassLexicalReviewRiskRecord> { it.normalizedPath }
        .thenBy { it.currentContentSha256 }
        .thenBy { it.riskFacts.joinToString(",") { fact -> fact.name } }

private val JAVA_MOVE_LEXICAL_REVIEW_SHA256 = Regex("[a-f0-9]{64}")

internal fun lexicalReviewSha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun requireLexicalReviewSha256(value: String, label: String) {
    require(JAVA_MOVE_LEXICAL_REVIEW_SHA256.matches(value)) { "$label must be SHA-256" }
}

private fun requireLexicalReviewPath(value: String) {
    val path = java.nio.file.Path.of(value)
    require(value.isNotBlank() && '\\' !in value && !path.isAbsolute && !path.startsWith("..") &&
        path.normalize().invariantSeparatorsPathString == value
    ) { "lexical review path must be normalized and workspace-relative" }
}

private fun <T> immutableLexicalReviewList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableLexicalReviewMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(java.util.LinkedHashMap(values))
