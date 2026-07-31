package org.refactorkit.java

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEditSimulator
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Specialized JSON infrastructure adapter for the canonical lexical-fallback review wire format.
 * kotlinx.serialization owns JSON escaping and encoding; callers share this exact projection.
 */
object JavaMoveClassLexicalFallbackReviewJsonProjection {
    private val canonicalJson = Json { prettyPrint = false }
    private val displayJson = Json { prettyPrint = true }

    fun toJson(envelope: JavaMoveClassLexicalFallbackReviewEnvelope): JsonObject = buildJsonObject {
        put("schemaVersion", envelope.schemaVersion)
        put("canonicalization", envelope.canonicalization)
        put("hashAlgorithm", envelope.hashAlgorithm)
        put("resultType", envelope.resultType.name)
        put("semanticCompleteness", envelope.semanticCompleteness.name)
        put("request", requestJson(envelope.request))
        put("requestSha256", envelope.requestSha256)
        put("snapshotSha256", envelope.snapshotSha256)
        put("reviewArtifactSha256", envelope.reviewArtifactSha256)
        put("evidenceSha256", envelope.evidenceSha256)
        put("operationId", envelope.operationId)
        put("authorityStatus", envelope.authorityStatus.name)
        put("evidenceKind", envelope.evidenceKind.name)
        put("managedWriteEligibility", envelope.managedWriteEligibility.name)
        put("blockerCode", envelope.blockerCode)
        put("nextActions", nextActionsJson(envelope.nextActions))
        put("evidenceFacts", evidenceFactsJson(envelope.evidenceFacts))
        put("residualGuidance", residualGuidanceJson(envelope.residualGuidance))
    }

    fun render(envelope: JavaMoveClassLexicalFallbackReviewEnvelope, pretty: Boolean = true): String =
        (if (pretty) displayJson else canonicalJson).encodeToString(toJson(envelope))

    fun canonicalBytes(envelope: JavaMoveClassLexicalFallbackReviewEnvelope): ByteArray =
        canonicalJson.encodeToString(toJson(envelope)).toByteArray(Charsets.UTF_8)

    fun refusalData(envelope: JavaMoveClassLexicalFallbackReviewEnvelope): JsonObject = buildJsonObject {
        put("blocker", buildJsonObject {
            put("code", envelope.blockerCode)
            put("authorityStatus", envelope.authorityStatus.name)
            put("managedWriteEligibility", envelope.managedWriteEligibility.name)
        })
        put("envelope", toJson(envelope))
    }

    internal fun requestSha256(request: JavaMoveClassLexicalReviewRequest): String =
        lexicalReviewSha256(canonicalJson.encodeToString(requestJson(request)).toByteArray(Charsets.UTF_8))

    /** Returns only the bounded SHA-256 identity; the hidden canonical artifact is never exposed. */
    fun reviewArtifactSha256(snapshot: ProjectSnapshot, compatibilityPlan: PatchPlan): String {
        val sourceByPath = snapshot.trackedFiles.associateBy { it.path.normalize() }
        val normalized = WorkspaceEditSimulator.normalize(compatibilityPlan.workspaceEdit)
        val facts = normalized.edits.map { fileEdit ->
            when (fileEdit) {
                is FileEdit.Modify -> buildJsonObject {
                    put("kind", "MODIFY")
                    put("path", fileEdit.path.normalize().invariantSeparatorsPathString)
                    put("currentContentSha256", currentContentSha256(sourceByPath, fileEdit.path))
                    put("spans", buildJsonArray {
                        fileEdit.textEdits.sortedWith(
                            compareBy({ it.range.start.line }, { it.range.start.character },
                                { it.range.end.line }, { it.range.end.character }),
                        ).forEach { textChange ->
                            add(buildJsonObject {
                                put("startLine", textChange.range.start.line)
                                put("startCharacter", textChange.range.start.character)
                                put("endLine", textChange.range.end.line)
                                put("endCharacter", textChange.range.end.character)
                                put(
                                    "replacementSha256",
                                    lexicalReviewSha256(textChange.newText.toByteArray(Charsets.UTF_8)),
                                )
                            })
                        }
                    })
                }
                is FileEdit.Create -> buildJsonObject {
                    put("kind", "CREATE")
                    put("path", fileEdit.path.normalize().invariantSeparatorsPathString)
                    put("currentContentStatus", "ABSENT")
                    put("contentSha256", lexicalReviewSha256(fileEdit.content.toByteArray(Charsets.UTF_8)))
                    put("overwrite", fileEdit.overwrite)
                }
                is FileEdit.Delete -> buildJsonObject {
                    put("kind", "DELETE")
                    put("path", fileEdit.path.normalize().invariantSeparatorsPathString)
                    put("currentContentSha256", currentContentSha256(sourceByPath, fileEdit.path))
                }
                is FileEdit.Rename -> buildJsonObject {
                    put("kind", "RENAME")
                    put("path", fileEdit.path.normalize().invariantSeparatorsPathString)
                    put("targetPath", fileEdit.newPath.normalize().invariantSeparatorsPathString)
                    put("currentContentSha256", currentContentSha256(sourceByPath, fileEdit.path))
                }
            }
        }.sortedBy(::canonicalElementText)
        return lexicalReviewSha256(canonicalJson.encodeToString(JsonArray(facts)).toByteArray(Charsets.UTF_8))
    }

    internal fun evidenceSha256(
        requestSha256: String,
        snapshotSha256: String,
        reviewArtifactSha256: String,
        evidenceFacts: List<JavaMoveClassLexicalReviewEvidenceFact>,
        residualGuidance: JavaMoveClassLexicalReviewResidualGuidance,
    ): String = lexicalReviewSha256(canonicalJson.encodeToString(buildJsonObject {
        put("schemaVersion", JavaMoveClassLexicalFallbackReviewEnvelope.SCHEMA_VERSION)
        put("canonicalization", JavaMoveClassLexicalFallbackReviewEnvelope.CANONICALIZATION)
        put("hashAlgorithm", JavaMoveClassLexicalFallbackReviewEnvelope.HASH_ALGORITHM)
        put("resultType", JavaMoveClassLexicalReviewResultType.LEXICAL_FALLBACK_REVIEW.name)
        put(
            "semanticCompleteness",
            JavaMoveClassLexicalReviewSemanticCompleteness.NOT_SEMANTICALLY_PROVEN.name,
        )
        put("authorityStatus", JavaMoveClassLexicalReviewAuthorityStatus.REVIEW_ONLY.name)
        put("evidenceKind", JavaMoveClassLexicalReviewEvidenceKind.LEXICAL_FALLBACK.name)
        put("managedWriteEligibility", JavaMoveClassLexicalReviewManagedWriteEligibility.INELIGIBLE.name)
        put("blockerCode", JavaMoveClassLexicalFallbackReviewEnvelope.BLOCKER_CODE)
        put("nextActions", nextActionsJson(JavaMoveClassLexicalReviewNextAction.values().asList()))
        put("requestSha256", requestSha256)
        put("snapshotSha256", snapshotSha256)
        put("reviewArtifactSha256", reviewArtifactSha256)
        put("evidenceFacts", evidenceFactsJson(evidenceFacts))
        put("residualGuidance", residualGuidanceJson(residualGuidance))
    }).toByteArray(Charsets.UTF_8))

    internal fun operationId(
        requestSha256: String,
        snapshotSha256: String,
        reviewArtifactSha256: String,
        evidenceSha256: String,
    ): String {
        val identity = buildJsonObject {
            put("schemaVersion", JavaMoveClassLexicalFallbackReviewEnvelope.SCHEMA_VERSION)
            put("canonicalization", JavaMoveClassLexicalFallbackReviewEnvelope.CANONICALIZATION)
            put("requestSha256", requestSha256)
            put("snapshotSha256", snapshotSha256)
            put("reviewArtifactSha256", reviewArtifactSha256)
            put("evidenceSha256", evidenceSha256)
            put(
                "semanticCompleteness",
                JavaMoveClassLexicalReviewSemanticCompleteness.NOT_SEMANTICALLY_PROVEN.name,
            )
            put("authorityStatus", JavaMoveClassLexicalReviewAuthorityStatus.REVIEW_ONLY.name)
            put("evidenceKind", JavaMoveClassLexicalReviewEvidenceKind.LEXICAL_FALLBACK.name)
            put("managedWriteEligibility", JavaMoveClassLexicalReviewManagedWriteEligibility.INELIGIBLE.name)
            put("blockerCode", JavaMoveClassLexicalFallbackReviewEnvelope.BLOCKER_CODE)
            put("nextActions", nextActionsJson(JavaMoveClassLexicalReviewNextAction.values().asList()))
        }
        return "lexical-review-${lexicalReviewSha256(canonicalJson.encodeToString(identity).toByteArray(Charsets.UTF_8))}"
    }

    internal fun canonicalRecordBytes(record: JavaMoveClassLexicalReviewRiskRecord): ByteArray =
        canonicalJson.encodeToString(riskRecordJson(record)).toByteArray(Charsets.UTF_8)

    private fun requestJson(request: JavaMoveClassLexicalReviewRequest): JsonObject = buildJsonObject {
        put("operation", request.operation)
        put("symbolFqn", request.symbolFqn)
        put("targetPackage", request.targetPackage)
        put("targetFqn", request.targetFqn)
    }

    private fun evidenceFactsJson(facts: List<JavaMoveClassLexicalReviewEvidenceFact>): JsonArray =
        buildJsonArray {
            facts.sortedWith(JAVA_MOVE_LEXICAL_REVIEW_EVIDENCE_ORDER).forEach { fact ->
                add(buildJsonObject {
                    put("type", fact.type.name)
                    fact.normalizedPath?.let { put("normalizedPath", it) }
                    fact.currentContentSha256?.let { put("currentContentSha256", it) }
                    fact.problemId?.let { put("problemId", it) }
                })
            }
        }

    private fun nextActionsJson(actions: List<JavaMoveClassLexicalReviewNextAction>): JsonArray =
        buildJsonArray {
            actions.forEach { add(JsonPrimitive(it.name)) }
        }

    private fun residualGuidanceJson(
        guidance: JavaMoveClassLexicalReviewResidualGuidance,
    ): JsonObject = buildJsonObject {
        put("recordCompleteness", guidance.recordCompleteness.name)
        put("truncated", guidance.truncated)
        put("totalRecordCount", guidance.totalRecordCount)
        put("returnedRecordCount", guidance.returnedRecordCount)
        put("returnedRiskCategoryCounts", buildJsonObject {
            guidance.returnedRiskCategoryCounts.forEach { (category, count) -> put(category.name, count) }
        })
        put("canonicalRecordBytes", guidance.canonicalRecordBytes)
        put("maxRecords", guidance.maxRecords)
        put("maxCanonicalRecordBytes", guidance.maxCanonicalRecordBytes)
        put("records", buildJsonArray {
            guidance.records.forEach { add(riskRecordJson(it)) }
        })
    }

    private fun riskRecordJson(record: JavaMoveClassLexicalReviewRiskRecord): JsonObject = buildJsonObject {
        put("normalizedPath", record.normalizedPath)
        put("currentContentSha256", record.currentContentSha256)
        put("managedEdit", record.managedEdit)
        put("riskFacts", buildJsonArray {
            record.riskFacts.forEach { add(JsonPrimitive(it.name)) }
        })
    }

    private fun currentContentSha256(
        sourceByPath: Map<java.nio.file.Path, org.refactorkit.core.SourceFile>,
        path: java.nio.file.Path,
    ): String = sourceByPath[path.normalize()]?.content?.toByteArray(Charsets.UTF_8)
        ?.let(::lexicalReviewSha256)
        ?: lexicalReviewSha256("ABSENT".toByteArray(Charsets.UTF_8))

    private fun canonicalElementText(element: JsonElement): String = canonicalJson.encodeToString(element)
}
