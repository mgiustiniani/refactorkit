package org.refactorkit.java

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaMoveClassOfflineAuthorityTest {
    @Test
    fun offlineMissingLeafWithUnrelatedWarningRetainsExactTargetAuthority() {
        val root = isolatedFixture()
        Files.delete(root.resolve(ARTIFACT_PATH))
        val snapshot = JavaProjectScanner().scan(root)

        assertEquals(BuildModelStatus.OFFLINE_MISSING, snapshot.buildModels.single().status)
        val preview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            snapshot,
            PRODUCT_FQN,
            TARGET_PACKAGE,
        )
        val plan = preview.plan
        val lease = assertNotNull(preview.targetAuthorityLease, plan.warnings.toString())

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(RefactoringEvidence.JDT_BINDING, plan.evidence, plan.warnings.toString())
        assertEquals(lease.coreLease, plan.authorityLease)
        assertEquals("OFFLINE_MISSING", lease.coreLease.attributes["externalClasspathStatus"])
        assertEquals(
            setOf("catalog-pricing:main", "catalog-storefront:main", "catalog-acceptance:test"),
            lease.observerSourceSets,
        )
        assertEquals(0, lease.candidatesBefore.count {
            it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
        })
        assertEquals(1, lease.candidatesBefore.count {
            it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER
        })
        assertEquals(2, lease.retainedDiagnosticsBefore.size)
        assertTrue(lease.retainedDiagnosticsBefore.all {
            it.missingExternalType == "com.acme.fixture.external.PriceAuthority" &&
                it.positiveNonConcealmentReason.contains("exact non-recovered binding")
        })
        assertEquals(lease.retainedDiagnosticsBefore, lease.retainedDiagnosticsStaged)
        assertEquals(lease.allDiagnosticsBefore, lease.allDiagnosticsStaged)
        assertTrue(
            plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
                .filterNot { it.path == Path.of(PRODUCT_SOURCE_PATH) }
                .flatMap(FileEdit.Modify::textEdits)
                .all { it.newText.isEmpty() || it.newText.contains("com.acme.catalog.api.Product") },
            "Observer edits must be binding-derived import/FQN changes",
        )
        assertTrue(
            PatchEngine(root).validate(plan, snapshot.hash).none { it.code == "evidence.insufficient" },
            plan.warnings.toString(),
        )
        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        assertTrue(lease.attest(staged).valid, lease.attest(staged).blockers.toString())
    }

    @Test
    fun missingExpectedArtifactIdentityKeepsOfflineMoveReviewOnly() {
        val root = isolatedFixture()
        Files.delete(root.resolve(ARTIFACT_PATH))
        Files.delete(root.resolve(ARTIFACT_EVIDENCE_PATH))
        val snapshot = JavaProjectScanner().scan(root)

        val preview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            snapshot,
            PRODUCT_FQN,
            TARGET_PACKAGE,
        )

        assertNull(preview.targetAuthorityLease)
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, preview.plan.evidence)
        assertTrue(PatchEngine(root).validate(preview.plan, snapshot.hash).any {
            it.code == "evidence.insufficient"
        })
    }

    @Test
    fun targetRelevantUnresolvedCandidatePreventsOfflineAuthorityLease() {
        val root = isolatedFixture()
        Files.delete(root.resolve(ARTIFACT_PATH))
        val pricing = root.resolve(PRICING_SOURCE_PATH)
        Files.writeString(pricing, Files.readString(pricing).replace("import com.acme.catalog.legacy.Product;\n", ""))
        val snapshot = JavaProjectScanner().scan(root)

        val preview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            snapshot,
            PRODUCT_FQN,
            TARGET_PACKAGE,
        )

        assertNull(preview.targetAuthorityLease)
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, preview.plan.evidence)
        assertTrue(preview.plan.warnings.any { it.contains("candidate") || it.contains("binding") })
    }

    @Test
    fun targetRelevantExpectedMissingTypePreventsOfflineAuthorityLease() {
        val root = isolatedFixture()
        Files.delete(root.resolve(ARTIFACT_PATH))
        val manifest = root.resolve(ARTIFACT_EVIDENCE_PATH)
        Files.writeString(manifest, Files.readString(manifest) + "providedType=com.acme.external.Product\n")
        val snapshot = JavaProjectScanner().scan(root)

        val preview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            snapshot,
            PRODUCT_FQN,
            TARGET_PACKAGE,
        )

        assertNull(preview.targetAuthorityLease)
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, preview.plan.evidence)
        assertTrue(preview.plan.warnings.any { it.contains("target-relevant Product type") })
    }

    private fun isolatedFixture(): Path {
        val source = repositoryRoot().resolve(FIXTURE_PATH)
        val target = Files.createTempDirectory("refactorkit-move-class-offline-authority-").resolve("workspace")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
        return target
    }

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate
            candidate = candidate.parent
        }
        error("Cannot locate repository root")
    }

    private companion object {
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val ARTIFACT_PATH = "fixture-libs/catalog-price-contract-1.0.0.jar"
        const val ARTIFACT_EVIDENCE_PATH = "$ARTIFACT_PATH.refactorkit-evidence"
        const val PRICING_SOURCE_PATH = "catalog-pricing/src/main/java/com/acme/catalog/pricing/CatalogPrice.java"
        const val PRODUCT_SOURCE_PATH = "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
        const val PRODUCT_FQN = "com.acme.catalog.legacy.Product"
        const val TARGET_PACKAGE = "com.acme.catalog.api"
    }
}
