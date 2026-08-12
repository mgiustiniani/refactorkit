package org.refactorkit.cli.packagedmavenmodulerename

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.LinkedHashMap

internal object QualificationRunRegistry {
    private var fixtureManifest: TreeManifest? = null
    private var candidateManifest: TreeManifest? = null
    private var candidateReadOnlyEvidence: CandidateReadOnlyEvidence? = null
    private var repositoryIdentity: RepositoryIdentity? = null
    private var candidateArchiveIdentity: CandidateArchiveIdentity? = null
    private var buildJdkIdentity: BuildJdkIdentity? = null
    private var candidateRuntimeIdentity: CandidateRuntimeIdentity? = null
    private val copyRoots = LinkedHashMap<String, Path>()
    private val copyIdentities = LinkedHashMap<String, String>()

    @Synchronized
    fun bindFixture(manifest: TreeManifest) {
        val retained = fixtureManifest
        if (retained == null) fixtureManifest = manifest else check(retained == manifest) {
            "Permanent fixture M0 changed between packaged surface cases"
        }
    }

    @Synchronized
    fun bindRepository(identity: RepositoryIdentity) {
        val retained = repositoryIdentity
        if (retained == null) repositoryIdentity = identity else check(retained == identity) {
            "Repository identity changed between packaged surface cases"
        }
    }

    @Synchronized
    fun bindCandidate(
        manifest: TreeManifest,
        archiveIdentity: CandidateArchiveIdentity,
        readOnlyEvidence: CandidateReadOnlyEvidence,
    ) {
        val retainedManifest = candidateManifest
        val retainedArchive = candidateArchiveIdentity
        if (retainedManifest == null) candidateManifest = manifest else check(retainedManifest == manifest) {
            "Extracted candidate image changed between packaged surface cases"
        }
        if (retainedArchive == null) candidateArchiveIdentity = archiveIdentity else check(
            retainedArchive == archiveIdentity,
        ) {
            "Candidate archive, checksum, or verifier identity changed between packaged surface cases"
        }
        val retainedReadOnly = candidateReadOnlyEvidence
        if (retainedReadOnly == null) candidateReadOnlyEvidence = readOnlyEvidence else check(
            retainedReadOnly == readOnlyEvidence,
        ) {
            "Extracted candidate read-only evidence changed between packaged surface cases"
        }
    }

    @Synchronized
    fun bindToolchain(buildJdk: BuildJdkIdentity, candidateRuntime: CandidateRuntimeIdentity) {
        val retainedBuildJdk = buildJdkIdentity
        if (retainedBuildJdk == null) buildJdkIdentity = buildJdk else check(retainedBuildJdk == buildJdk) {
            "Exact build JDK identity changed between packaged surface cases"
        }
        val retainedCandidateRuntime = candidateRuntimeIdentity
        if (retainedCandidateRuntime == null) candidateRuntimeIdentity = candidateRuntime else check(
            retainedCandidateRuntime == candidateRuntime,
        ) {
            "Candidate runtime identity changed between packaged surface cases"
        }
    }

    @Synchronized
    fun registerCopy(surface: String, copyRoot: Path, s0Identity: String) {
        check(surface !in copyRoots) { "Surface registered more than one disposable copy: $surface" }
        val normalized = copyRoot.toAbsolutePath().normalize()
        check(copyRoots.values.none { it.toAbsolutePath().normalize() == normalized }) {
            "Packaged surface copies are not pairwise distinct"
        }
        check(copyIdentities.values.none { it == s0Identity }) {
            "Packaged surface copy identities are not pairwise distinct"
        }
        copyRoots[surface] = normalized
        copyIdentities[surface] = s0Identity
    }

    @Synchronized
    fun registeredCopyCount(): Int = copyRoots.size

    @Synchronized
    fun copyRootsArePairwiseDistinct(): Boolean =
        copyRoots.values.map { it.toAbsolutePath().normalize() }.toSet().size == copyRoots.size

    @Synchronized
    fun copyIdentitiesArePairwiseDistinct(): Boolean = copyIdentities.values.toSet().size == copyIdentities.size

    @Synchronized
    fun everyOtherCopyIsAbsent(surface: String): Boolean = copyRoots
        .filterKeys { it != surface }
        .values
        .all { Files.notExists(it, LinkOption.NOFOLLOW_LINKS) }

    @Synchronized
    fun writeGlobalManifests(
        manifestDirectory: Path,
        fixture: TreeManifest,
        candidate: TreeManifest,
        readOnlyEvidence: CandidateReadOnlyEvidence,
        repository: RepositoryIdentity,
        archiveIdentity: CandidateArchiveIdentity,
        buildJdk: BuildJdkIdentity,
        candidateRuntime: CandidateRuntimeIdentity,
    ) {
        bindFixture(fixture)
        bindRepository(repository)
        bindCandidate(candidate, archiveIdentity, readOnlyEvidence)
        bindToolchain(buildJdk, candidateRuntime)
        check(readOnlyEvidence.archiveSha256Before == archiveIdentity.archiveSha256)
        check(readOnlyEvidence.archiveSha256After == archiveIdentity.archiveSha256)
        check(candidateRuntime.javaExecutableSha256 == candidate.entries.getValue(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "runtime/bin/java.exe"
            } else {
                "runtime/bin/java"
            },
        ).sha256)
        check(candidateRuntime.releaseSha256 == candidate.entries.getValue("runtime/release").sha256)
        check(candidateRuntime.modulesImageSha256 == candidate.entries.getValue("runtime/lib/modules").sha256)
        writeStableJson(
            manifestDirectory.resolve("fixture-m0-no-follow-manifest.json"),
            fixture.toJson("permanent-fixture-M0"),
        )
        writeStableJson(
            manifestDirectory.resolve("candidate-extracted-no-follow-manifest.json"),
            buildJsonObject {
                candidate.toJson("extracted-candidate-image").forEach { (name, value) -> put(name, value) }
                put("archiveIdentity", archiveIdentity.toJson())
                put("buildJdk", buildJdk.toJson())
                put("candidateRuntime", candidateRuntime.toJson())
                put("readOnly", readOnlyEvidence.toJson())
                put("revision", repository.toJson())
            },
        )
        writeStableJson(
            manifestDirectory.resolve("candidate-archive-checksum.json"),
            buildJsonObject {
                put("archiveName", archiveIdentity.archiveName)
                put("sha256", archiveIdentity.archiveSha256)
                put("checksumSha256", archiveIdentity.checksumSha256)
                put("verifierPlatform", archiveIdentity.verifierPlatform)
                put("verifierRecordSha256", archiveIdentity.verifierRecordSha256)
                put("verifierCompleteLogSha256", archiveIdentity.verifierCompleteLogSha256)
                put("buildJdk", buildJdk.toJson())
                put("candidateRuntime", candidateRuntime.toJson())
                put("repositoryCommit", repository.commit)
                put("repositoryTree", repository.tree)
                put("repositoryWorktreeState", repository.worktreeState)
                put("repositoryStatusSha256", repository.statusSha256)
            },
        )
        writePairwiseReport(manifestDirectory)
    }

    @Synchronized
    fun writePairwiseReport(manifestDirectory: Path) {
        writeJson(
            manifestDirectory.resolve("pairwise-disposable-copies.json"),
            buildJsonObject {
                put("count", copyRoots.size)
                put("pathsPairwiseDistinct", copyRootsArePairwiseDistinct())
                put("identitiesPairwiseDistinct", copyIdentitiesArePairwiseDistinct())
                put("copies", buildJsonArray {
                    copyRoots.keys.forEach { surface ->
                        add(buildJsonObject {
                            put("surface", surface)
                            put("copyIdentity", copyIdentities.getValue(surface))
                            put("path", "<disposable-copy:${surface.replace(' ', '-')}>")
                        })
                    }
                })
            },
        )
    }

    private fun writeStableJson(path: Path, value: kotlinx.serialization.json.JsonElement) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val before = sha256(path)
            writeJson(path, value)
            check(sha256(path) == before) { "Stable global manifest changed within one run: ${path.fileName}" }
        } else {
            writeJson(path, value)
        }
    }
}
