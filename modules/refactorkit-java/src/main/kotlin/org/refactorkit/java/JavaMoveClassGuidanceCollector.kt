package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import kotlin.io.path.invariantSeparatorsPathString

internal sealed interface JavaMoveClassGuidanceCollectionResult {
    data object NotApplicable : JavaMoveClassGuidanceCollectionResult
    data class Guidance(val value: JavaMoveClassReviewOnlyGuidance) : JavaMoveClassGuidanceCollectionResult
    data class Refused(val code: String, val summary: String) : JavaMoveClassGuidanceCollectionResult
}

/** Collects candidate-total immutable guidance after snapshot-bound evidence has been validated. */
internal object JavaMoveClassGuidanceCollector {
    private const val MAVEN_PROVIDER = "maven-effective-v1"

    fun collect(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        targetPackage: String,
    ): JavaMoveClassGuidanceCollectionResult {
        if (!JAVA_MOVE_GUIDANCE_SHA256_PATTERN.matches(snapshot.hash)) {
            return JavaMoveClassGuidanceCollectionResult.Refused(
                "java.maven.moveClass.snapshot.invalid",
                "The move-class snapshot identity is invalid.",
            )
        }
        val model = snapshot.buildModels.singleOrNull { it.providerId == MAVEN_PROVIDER }
            ?: return JavaMoveClassGuidanceCollectionResult.NotApplicable
        val expectedSources = when (val result = JavaMoveClassGuidanceEvidence.expectedSources(snapshot, model)) {
            is JavaMoveClassGuidanceEvidenceResult.Valid -> result.value
            is JavaMoveClassGuidanceEvidenceResult.Invalid -> return result.toCollectionRefusal()
        }
        val enumeratedSources = when (val result = JavaMoveClassGuidanceEvidence.enumerateJavaSources(snapshot, model)) {
            is JavaMoveClassGuidanceEvidenceResult.Valid -> result.value
            is JavaMoveClassGuidanceEvidenceResult.Invalid -> return result.toCollectionRefusal()
        }
        val sourceByPath = enumeratedSources.associateBy(JavaMoveClassGuidanceEnumeratedSource::path)
        if (sourceByPath.size != enumeratedSources.size) {
            return JavaMoveClassGuidanceCollectionResult.Refused(
                "java.maven.moveClass.sourceInventory.duplicate",
                "The move-class source inventory contains duplicate paths.",
            )
        }

        val analysis = runCatching { JdtJavaSemanticAnalyzer().analyze(snapshot) }.getOrNull()
            ?: return JavaMoveClassGuidanceCollectionResult.NotApplicable
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == symbolFqn && symbol.path in sourceByPath &&
                symbol.bindingKey != null && !symbol.recovered && symbol.kind in MOVEABLE_KINDS
        } ?: return JavaMoveClassGuidanceCollectionResult.NotApplicable
        val targetBindingKey = requireNotNull(target.bindingKey)
        val candidates = JavaMoveClassTargetAuthorityEvaluator.candidateInventory(
            snapshot,
            model,
            analysis,
            symbolFqn,
            targetBindingKey,
        )
        val targetSimpleName = JavaPackageUtil.simpleName(symbolFqn)
        val missingExpectedTargetSources = expectedSources.filter { source ->
            !source.observedInSourceInventory &&
                (JavaLexer.findOccurrences(source.content, symbolFqn).isNotEmpty() ||
                    JavaLexer.findOccurrences(source.content, targetSimpleName).isNotEmpty())
        }
        val expectedByPath = expectedSources.associateBy(JavaMoveClassGuidanceExpectedSource::path)
        val readableSources = (
            enumeratedSources + missingExpectedTargetSources.map(JavaMoveClassGuidanceExpectedSource::asEnumeratedSource)
        ).distinctBy(JavaMoveClassGuidanceEnumeratedSource::path)
        val candidateFacts = (candidates.mapNotNull { candidate ->
            val source = sourceByPath[candidate.path.normalize()] ?: return@mapNotNull null
            val recoveredRange = recoveredRangeAt(candidate, source.content, targetSimpleName, analysis)
            CandidateFact(
                normalizeGuidanceCandidate(candidate, recoveredRange),
                source,
                recoveredRange,
            )
        } + missingExpectedTargetSources.flatMap { expected ->
            val source = expected.asEnumeratedSource()
            lexicalCandidateRanges(source.content, symbolFqn, targetSimpleName).map { (range, text) ->
                CandidateFact(
                    JavaMoveClassCandidateRecord(
                        path = source.path,
                        sourceRange = range,
                        lexicalText = text,
                        sourceSet = "${source.mavenModule}:${source.sourceSet}",
                        classification = JavaMoveClassCandidateClassification.UNRESOLVED,
                        bindingKey = null,
                    ),
                    source,
                    recoveredRange = null,
                )
            }
        }).sortedWith(CANDIDATE_FACT_ORDER)
        if (candidateFacts.isEmpty()) return JavaMoveClassGuidanceCollectionResult.NotApplicable

        val blockers = mutableListOf<JavaMoveClassGuidanceBlocker>()
        missingExpectedTargetSources.forEach { source ->
            blockers += JavaMoveClassGuidanceBlocker.MissingReadableSourceInventoryEntry(
                source.mavenModule,
                source.sourceSet,
                source.path,
                source.manifestPath,
                source.manifestContentSha256,
                source.expectedContentSha256,
                source.contentSha256,
                JavaMoveClassSourceInventoryObservation.MISSING,
            )
        }
        val targetCandidateSourceSets = candidateFacts.filter {
            it.candidate.classification == JavaMoveClassCandidateClassification.BOUND_TARGET
        }.mapTo(hashSetOf()) { "${it.source.mavenModule}:${it.source.sourceSet}" }
        when (val result = JavaMoveClassGuidanceEvidence.classpathFingerprintBlockers(
            snapshot,
            model,
            targetCandidateSourceSets,
        )) {
            is JavaMoveClassGuidanceEvidenceResult.Valid -> blockers += result.value
            is JavaMoveClassGuidanceEvidenceResult.Invalid -> return result.toCollectionRefusal()
        }
        // Broad parse failure remains the legacy lexical-fallback case (REQ-005).
        // This slice recognizes only recovered target-use evidence without a syntax failure.
        if (analysis.warnings.none { it.category == JdtJavaDiagnosticCategory.SYNTAX }) {
            candidateFacts.filter { fact ->
                fact.candidate.classification == JavaMoveClassCandidateClassification.UNRESOLVED &&
                    fact.recoveredRange != null
            }.groupBy { fact -> fact.source.mavenModule to fact.source.sourceSet }
                .values.mapNotNull { facts -> facts.minWithOrNull(CANDIDATE_FACT_ORDER) }
                .forEach { fact ->
                    blockers += JavaMoveClassGuidanceBlocker.RecoveredTargetUse(
                        fact.source.mavenModule,
                        fact.source.sourceSet,
                        fact.candidate.path,
                        requireNotNull(fact.recoveredRange),
                        fact.source.contentSha256,
                    )
                }
        }
        when (val result = JavaMoveClassGuidanceEvidence.generatedInventoryBlockers(snapshot, model)) {
            is JavaMoveClassGuidanceEvidenceResult.Valid -> blockers += result.value
            is JavaMoveClassGuidanceEvidenceResult.Invalid -> return result.toCollectionRefusal()
        }
        if (blockers.isEmpty()) return JavaMoveClassGuidanceCollectionResult.NotApplicable

        val javaCandidates = candidateFacts.map { fact ->
            JavaMoveClassGuidanceJavaCandidate(
                snapshotSha256 = snapshot.hash,
                path = fact.candidate.path.normalize(),
                sourceRange = fact.candidate.sourceRange,
                contentSha256 = fact.source.contentSha256,
                lexicalText = fact.candidate.lexicalText,
                mavenModule = fact.source.mavenModule,
                sourceSet = fact.source.sourceSet,
                classification = fact.candidate.classification,
                bindingKey = fact.candidate.bindingKey,
                recoveredRange = fact.recoveredRange,
            )
        }
        val javaResiduals = collectJavaNonCodeResiduals(snapshot, readableSources, symbolFqn)
        val nonJavaResiduals = when (val result = JavaMoveClassGuidanceEvidence.collectNonJavaResiduals(
            snapshot,
            symbolFqn,
        )) {
            is JavaMoveClassGuidanceEvidenceResult.Valid -> result.value.map { occurrence ->
                JavaMoveClassGuidanceResidual(
                    snapshotSha256 = snapshot.hash,
                    path = occurrence.path,
                    sourceRange = TextEdits.rangeForOffset(
                        occurrence.content,
                        occurrence.offsetRange.first,
                        occurrence.offsetRange.last - occurrence.offsetRange.first + 1,
                    ),
                    contentSha256 = occurrence.contentSha256,
                    lexicalText = occurrence.content.substring(
                        occurrence.offsetRange.first,
                        occurrence.offsetRange.last + 1,
                    ),
                    kind = JavaMoveClassGuidanceResidualKind.NON_JAVA_RESIDUAL,
                    mavenModule = null,
                    sourceSet = null,
                )
            }
            is JavaMoveClassGuidanceEvidenceResult.Invalid -> return result.toCollectionRefusal()
        }
        val groups = JavaMoveClassGuidanceCandidateGroups(
            boundTarget = javaCandidates.filter {
                it.classification == JavaMoveClassCandidateClassification.BOUND_TARGET
            },
            boundOther = javaCandidates.filter {
                it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER
            },
            unresolved = javaCandidates.filter {
                it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
            },
            javaNonCodeResiduals = javaResiduals,
            nonJavaResiduals = nonJavaResiduals,
        )
        val omissions = missingExpectedTargetSources.map { source ->
            val expected = requireNotNull(expectedByPath[source.path])
            JavaMoveClassGuidanceOmission(
                kind = JavaMoveClassGuidanceOmissionKind.SOURCE_INVENTORY_ENTRY,
                snapshotSha256 = snapshot.hash,
                mavenModule = expected.mavenModule,
                sourceSet = expected.sourceSet,
                path = expected.path,
                sourceRange = SourceRange(
                    SourcePosition(0, 0),
                    TextEdits.positionForOffset(expected.content, expected.content.length),
                ),
                contentSha256 = expected.contentSha256,
            )
        }
        val completeness = if (omissions.isEmpty()) {
            JavaMoveClassGuidanceCandidateCompleteness.COMPLETE
        } else {
            JavaMoveClassGuidanceCandidateCompleteness.COMPLETE_WITH_TYPED_OMISSIONS
        }
        val canonicalBlockers = blockers.distinct().sortedWith(JAVA_MOVE_GUIDANCE_BLOCKER_ORDER)
        val actions = restorationActions(canonicalBlockers)
        val request = JavaMoveClassGuidanceRequest("moveClass", symbolFqn, targetPackage)
        val requestIdentity = javaMoveGuidanceHashParts(
            listOf(request.operation, request.symbolFqn, request.targetPackage),
        )
        val evidenceHash = canonicalEvidenceHash(
            requestIdentity,
            snapshot.hash,
            canonicalBlockers,
            groups,
            completeness,
            omissions,
            actions,
        )
        return JavaMoveClassGuidanceCollectionResult.Guidance(
            JavaMoveClassReviewOnlyGuidance(
                request = request,
                requestIdentitySha256 = requestIdentity,
                snapshotSha256 = snapshot.hash,
                canonicalEvidenceSha256 = evidenceHash,
                blockers = canonicalBlockers,
                candidateGroups = groups,
                candidateCompleteness = completeness,
                omissions = omissions,
                restorationActions = actions,
                vcsChecklist = JAVA_MOVE_GUIDANCE_FIXED_VCS_CHECKLIST,
            ),
        )
    }

    private fun collectJavaNonCodeResiduals(
        snapshot: ProjectSnapshot,
        sources: List<JavaMoveClassGuidanceEnumeratedSource>,
        symbolFqn: String,
    ): List<JavaMoveClassGuidanceResidual> = sources.flatMap { source ->
        val codeRanges = JavaLexer.findOccurrences(source.content, symbolFqn).toSet()
        allOccurrences(source.content, symbolFqn).filterNot(codeRanges::contains).map { range ->
            JavaMoveClassGuidanceResidual(
                snapshotSha256 = snapshot.hash,
                path = source.path,
                sourceRange = TextEdits.rangeForOffset(
                    source.content,
                    range.first,
                    range.last - range.first + 1,
                ),
                contentSha256 = source.contentSha256,
                lexicalText = source.content.substring(range.first, range.last + 1),
                kind = JavaMoveClassGuidanceResidualKind.JAVA_NON_CODE_RESIDUAL,
                mavenModule = source.mavenModule,
                sourceSet = source.sourceSet,
            )
        }
    }

    private fun recoveredRangeAt(
        candidate: JavaMoveClassCandidateRecord,
        content: String,
        simpleName: String,
        analysis: JdtJavaSemanticAnalysisResult,
    ): SourceRange? {
        if (candidate.classification != JavaMoveClassCandidateClassification.UNRESOLVED) return null
        val start = TextEdits.offsetOf(content, candidate.sourceRange.start)
        val terminal = TextEdits.rangeForOffset(
            content,
            start + candidate.lexicalText.length - simpleName.length,
            simpleName.length,
        )
        val recovered = analysis.symbols.any {
            it.path == candidate.path && it.sourceRange == terminal && it.recovered
        } || analysis.references.any {
            it.path == candidate.path && it.sourceRange == terminal && it.recovered
        } || analysis.bindingUses.any {
            it.path == candidate.path && it.sourceRange == terminal && it.recovered
        }
        return terminal.takeIf { recovered }
    }

    private fun normalizeGuidanceCandidate(
        candidate: JavaMoveClassCandidateRecord,
        recoveredRange: SourceRange?,
    ): JavaMoveClassCandidateRecord {
        val exactBindingKey = candidate.bindingKey?.takeIf(String::isNotBlank)
        val exactBoundCandidate = recoveredRange == null && exactBindingKey != null &&
            candidate.classification != JavaMoveClassCandidateClassification.UNRESOLVED
        return candidate.copy(
            classification = if (exactBoundCandidate) {
                candidate.classification
            } else {
                JavaMoveClassCandidateClassification.UNRESOLVED
            },
            bindingKey = exactBindingKey.takeIf { exactBoundCandidate },
        )
    }

    private fun lexicalCandidateRanges(
        content: String,
        symbolFqn: String,
        simpleName: String,
    ): List<Pair<SourceRange, String>> {
        val fqnRanges = JavaLexer.findOccurrences(content, symbolFqn)
        val simpleRanges = JavaLexer.findOccurrences(content, simpleName).filter { simple ->
            fqnRanges.none { fqn -> simple.first >= fqn.first && simple.last <= fqn.last }
        }
        return (fqnRanges + simpleRanges).sortedBy(IntRange::first).map { range ->
            TextEdits.rangeForOffset(content, range.first, range.last - range.first + 1) to
                content.substring(range.first, range.last + 1)
        }
    }

    private fun restorationActions(
        blockers: List<JavaMoveClassGuidanceBlocker>,
    ): List<JavaMoveClassGuidanceRestorationAction> {
        val primary = blockers.map { blocker ->
            val kind = when (blocker) {
                is JavaMoveClassGuidanceBlocker.MissingReadableSourceInventoryEntry ->
                    JavaMoveClassGuidanceRestorationKind.RESTORE_SOURCE_INVENTORY
                is JavaMoveClassGuidanceBlocker.SystemPathArtifactFingerprintMismatch ->
                    JavaMoveClassGuidanceRestorationKind.REFRESH_CLASSPATH_EVIDENCE
                is JavaMoveClassGuidanceBlocker.RecoveredTargetUse ->
                    JavaMoveClassGuidanceRestorationKind.REESTABLISH_EXACT_BINDINGS
                is JavaMoveClassGuidanceBlocker.MaterializedGeneratedRootInventoryFingerprintMismatch ->
                    JavaMoveClassGuidanceRestorationKind.EXTERNALLY_RESTORE_GENERATED_ROOT
            }
            Triple(kind, blocker, RESTORATION_PRECEDENCE.getValue(kind))
        }.distinctBy { (kind, blocker) -> Triple(kind, blocker.mavenModule, blocker.sourceSet) }
            .sortedWith(
                compareBy<Triple<JavaMoveClassGuidanceRestorationKind, JavaMoveClassGuidanceBlocker, Int>> {
                    it.third
                }.thenBy { it.second.mavenModule }.thenBy { it.second.sourceSet },
            )
        return buildList {
            primary.forEach { (kind, blocker) ->
                add(JavaMoveClassGuidanceRestorationAction(
                    size + 1,
                    kind,
                    blocker.code,
                    blocker.mavenModule,
                    blocker.sourceSet,
                ))
            }
            add(JavaMoveClassGuidanceRestorationAction(
                size + 1,
                JavaMoveClassGuidanceRestorationKind.FULL_REACTOR_RESCAN,
                null,
                null,
                null,
            ))
            add(JavaMoveClassGuidanceRestorationAction(
                size + 1,
                JavaMoveClassGuidanceRestorationKind.NEW_PREVIEW,
                null,
                null,
                null,
            ))
        }
    }

    private fun canonicalEvidenceHash(
        requestIdentity: String,
        snapshotSha256: String,
        blockers: List<JavaMoveClassGuidanceBlocker>,
        groups: JavaMoveClassGuidanceCandidateGroups,
        completeness: JavaMoveClassGuidanceCandidateCompleteness,
        omissions: List<JavaMoveClassGuidanceOmission>,
        actions: List<JavaMoveClassGuidanceRestorationAction>,
    ): String = javaMoveGuidanceHashParts(buildList {
        add("java.maven.moveClass.reviewOnlyGuidance")
        add(JavaMoveClassReviewOnlyGuidance.SCHEMA_VERSION)
        add(JavaMoveClassReviewOnlyGuidance.CHECKLIST_VERSION)
        add(requestIdentity)
        add(snapshotSha256)
        blockers.forEach { add(blockerIdentity(it)) }
        groups.allOccurrences.forEach { add(occurrenceIdentity(it)) }
        add(completeness.name)
        omissions.sortedWith(JAVA_MOVE_GUIDANCE_OMISSION_ORDER).forEach { omission ->
            add(listOf(
                omission.kind,
                omission.snapshotSha256,
                omission.mavenModule,
                omission.sourceSet,
                omission.path.invariantSeparatorsPathString,
                javaMoveGuidanceRangeIdentity(omission.sourceRange),
                omission.contentSha256,
            ).joinToString("\u0000"))
        }
        actions.forEach { action ->
            add(listOf(action.order, action.kind, action.blockerCode, action.mavenModule, action.sourceSet)
                .joinToString("\u0000"))
        }
        JAVA_MOVE_GUIDANCE_FIXED_VCS_CHECKLIST.forEach { add("${it.order}\u0000${it.verification}") }
    })

    private fun blockerIdentity(blocker: JavaMoveClassGuidanceBlocker): String = when (blocker) {
        is JavaMoveClassGuidanceBlocker.MissingReadableSourceInventoryEntry -> listOf(
            blocker.code,
            blocker.mavenModule,
            blocker.sourceSet,
            blocker.path.invariantSeparatorsPathString,
            blocker.manifestPath.invariantSeparatorsPathString,
            blocker.manifestContentSha256,
            blocker.expectedContentSha256,
            blocker.observedContentSha256,
            blocker.observedInventoryStatus,
        )
        is JavaMoveClassGuidanceBlocker.SystemPathArtifactFingerprintMismatch -> listOf(
            blocker.code,
            blocker.mavenModule,
            blocker.sourceSet,
            blocker.path.invariantSeparatorsPathString,
            blocker.manifestPath.invariantSeparatorsPathString,
            blocker.manifestContentSha256,
            blocker.expectedFingerprint,
            blocker.observedFingerprint,
        )
        is JavaMoveClassGuidanceBlocker.RecoveredTargetUse -> listOf(
            blocker.code,
            blocker.mavenModule,
            blocker.sourceSet,
            blocker.path.invariantSeparatorsPathString,
            javaMoveGuidanceRangeIdentity(blocker.sourceRange),
            blocker.contentSha256,
        )
        is JavaMoveClassGuidanceBlocker.MaterializedGeneratedRootInventoryFingerprintMismatch -> listOf(
            blocker.code,
            blocker.mavenModule,
            blocker.sourceSet,
            blocker.path.invariantSeparatorsPathString,
            blocker.manifestPath.invariantSeparatorsPathString,
            blocker.manifestContentSha256,
            blocker.expectedFingerprint,
            blocker.observedFingerprint,
        )
    }.joinToString("\u0000")

    private fun occurrenceIdentity(occurrence: JavaMoveClassGuidanceOccurrence): String = buildList {
        add(occurrence::class.simpleName.orEmpty())
        add(occurrence.snapshotSha256)
        add(occurrence.path.invariantSeparatorsPathString)
        add(javaMoveGuidanceRangeIdentity(occurrence.sourceRange))
        add(occurrence.contentSha256)
        add(occurrence.lexicalText)
        add(occurrence.managedEdit)
        when (occurrence) {
            is JavaMoveClassGuidanceJavaCandidate -> {
                add(occurrence.mavenModule)
                add(occurrence.sourceSet)
                add(occurrence.classification)
                add(occurrence.bindingKey)
                add(occurrence.recovered)
                add(occurrence.recoveredRange?.let(::javaMoveGuidanceRangeIdentity))
            }
            is JavaMoveClassGuidanceResidual -> {
                add(occurrence.kind)
                add(occurrence.mavenModule)
                add(occurrence.sourceSet)
            }
        }
    }.joinToString("\u0000")

    private fun allOccurrences(content: String, text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var offset = 0
        while (true) {
            val found = content.indexOf(text, offset)
            if (found < 0) return ranges
            ranges += found until found + text.length
            offset = found + text.length
        }
    }

    private data class CandidateFact(
        val candidate: JavaMoveClassCandidateRecord,
        val source: JavaMoveClassGuidanceEnumeratedSource,
        val recoveredRange: SourceRange?,
    )

    private val CANDIDATE_FACT_ORDER = compareBy<CandidateFact> {
        it.candidate.path.invariantSeparatorsPathString
    }.thenBy { it.candidate.sourceRange.start.line }
        .thenBy { it.candidate.sourceRange.start.character }
        .thenBy { it.candidate.classification.name }

    private val MOVEABLE_KINDS = setOf(
        JdtJavaSemanticSymbolKind.CLASS,
        JdtJavaSemanticSymbolKind.INTERFACE,
        JdtJavaSemanticSymbolKind.ENUM,
        JdtJavaSemanticSymbolKind.RECORD,
        JdtJavaSemanticSymbolKind.ANNOTATION,
    )

    private val RESTORATION_PRECEDENCE = mapOf(
        JavaMoveClassGuidanceRestorationKind.RESTORE_SOURCE_INVENTORY to 1,
        JavaMoveClassGuidanceRestorationKind.REFRESH_CLASSPATH_EVIDENCE to 2,
        JavaMoveClassGuidanceRestorationKind.REESTABLISH_EXACT_BINDINGS to 3,
        JavaMoveClassGuidanceRestorationKind.EXTERNALLY_RESTORE_GENERATED_ROOT to 4,
    )
}

private fun JavaMoveClassGuidanceExpectedSource.asEnumeratedSource() =
    JavaMoveClassGuidanceEnumeratedSource(path, mavenModule, sourceSet, content, contentSha256)

private fun JavaMoveClassGuidanceEvidenceResult.Invalid.toCollectionRefusal() =
    JavaMoveClassGuidanceCollectionResult.Refused(code, summary)
