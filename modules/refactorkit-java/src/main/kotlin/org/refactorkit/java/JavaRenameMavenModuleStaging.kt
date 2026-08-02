package org.refactorkit.java

import org.refactorkit.core.AuthoritativeDiagnosticsEvaluation
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticDetails
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.java.JavaRenameMavenModuleContract.DESCRIPTOR_UNAVAILABLE
import org.refactorkit.java.JavaRenameMavenModuleContract.DIAGNOSTICS_REGRESSION
import org.refactorkit.java.JavaRenameMavenModuleContract.GateAuthority
import org.refactorkit.java.JavaRenameMavenModuleContract.GateExpectation
import org.refactorkit.java.JavaRenameMavenModuleContract.MAVEN_PROVIDER
import org.refactorkit.java.JavaRenameMavenModuleContract.REPOSITORY_IDENTITY_HASH_SUFFIX
import org.refactorkit.java.JavaRenameMavenModuleContract.StageFacts
import org.refactorkit.java.JavaRenameMavenModuleContract.availableMavenModel
import org.refactorkit.java.JavaRenameMavenModuleContract.hashStrings
import org.refactorkit.java.JavaRenameMavenModuleContract.javaPlatformHome
import org.refactorkit.java.JavaRenameMavenModuleContract.refuse
import org.refactorkit.java.JavaRenameMavenModuleContract.sha256
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Owns offline staging plus semantic snapshot rehydration and authoritative evaluation. */
internal object JavaRenameMavenModuleStaging {
    fun stageAndAttest(
        snapshot: ProjectSnapshot,
        simulated: ProjectSnapshot,
        edit: WorkspaceEdit,
    ): StageFacts = JavaRenameMavenModuleWorkspace.withWorkspaceCopy(snapshot.workspace.root) { workspace, emptyRepository ->
        val baselineManifest = JavaRenameMavenModuleWorkspace.manifestHash(workspace)
        val rebuiltBaseline = JavaRenameMavenModuleWorkspace.scanOffline(workspace, emptyRepository)
        if (JavaRenameMavenModuleEvidence.trackedInventoryHash(rebuiltBaseline) != JavaRenameMavenModuleEvidence.trackedInventoryHash(snapshot)) {
            refuse(DESCRIPTOR_UNAVAILABLE, "The full no-follow copy does not reconcile to the immutable baseline snapshot")
        }
        val baseline = rehydrateSemanticSnapshot(snapshot, rebuiltBaseline)
        if (baseline != snapshot || baseline.hash != snapshot.hash) {
            refuse(DESCRIPTOR_UNAVAILABLE, "The copied offline Maven baseline does not reproduce exact S0")
        }
        val baselineModel = availableMavenModel(baseline)
        val expectedBaselineReactor = JavaRenameMavenModuleEvidence.reactorHash(snapshot, availableMavenModel(snapshot))
        if (JavaRenameMavenModuleEvidence.reactorHash(baseline, baselineModel) != expectedBaselineReactor) {
            refuse(DESCRIPTOR_UNAVAILABLE, "The copied offline Maven reactor differs from the immutable baseline model")
        }
        val rebuiltBaselineJdt = JavaLanguageAdapter().authoritativeDiagnostics(rebuiltBaseline, javaPlatformHome())
        val baselineJdt = rebaseDiagnostics(
            rebuiltBaselineJdt,
            rebuiltBaseline.workspace.root,
            snapshot.workspace.root,
        )
        val incomingJdt = JavaLanguageAdapter().authoritativeDiagnostics(snapshot, javaPlatformHome())
        if (baselineJdt != incomingJdt) {
            refuse(DIAGNOSTICS_REGRESSION, "Copied baseline JDT diagnostics differ from the caller's immutable model")
        }

        JavaRenameMavenModuleWorkspace.applyInOrder(workspace, edit)
        val stagedManifest = JavaRenameMavenModuleWorkspace.manifestHash(workspace)
        val rebuiltStaged = JavaRenameMavenModuleWorkspace.scanOffline(workspace, emptyRepository)
        val rebuiltStagedModel = rebuiltStaged.buildModels.singleOrNull()
        if (rebuiltStagedModel?.providerId != MAVEN_PROVIDER || rebuiltStagedModel.status != BuildModelStatus.AVAILABLE) {
            val evidence = rebuiltStagedModel?.diagnostics.orEmpty().joinToString("; ") { diagnostic ->
                "${diagnostic.code}:${diagnostic.moduleId.orEmpty()}:${diagnostic.message}"
            }.ifBlank {
                rebuiltStagedModel?.status?.name ?: rebuiltStaged.buildModels
                    .joinToString(",") { "${it.providerId}:${it.status}" }
                    .ifBlank { "MODEL_ABSENT" }
            }
            refuse(DESCRIPTOR_UNAVAILABLE, "The complete staged Maven effective reactor is unavailable offline: $evidence")
        }
        val staged = rehydrateSemanticSnapshot(simulated, rebuiltStaged)
        val stagedModel = availableMavenModel(staged)
        val rebuiltStagedJdt = JavaLanguageAdapter().authoritativeDiagnostics(rebuiltStaged, javaPlatformHome())
        val stagedJdt = rebaseDiagnostics(rebuiltStagedJdt, rebuiltStaged.workspace.root, snapshot.workspace.root)
        val baselineDiagnostics = JavaRenameMavenModuleEvidence.mavenDiagnostics(baselineModel.diagnostics) + baselineJdt
        val stagedDiagnostics = JavaRenameMavenModuleEvidence.mavenDiagnostics(stagedModel.diagnostics) + stagedJdt
        StageFacts(
            baselineManifestHash = baselineManifest,
            stagedManifestHash = stagedManifest,
            baselineTrackedHash = JavaRenameMavenModuleEvidence.trackedInventoryHash(baseline),
            stagedTrackedHash = JavaRenameMavenModuleEvidence.trackedInventoryHash(staged),
            stagedCandidateSnapshotHash = simulated.hash,
            baselineSnapshot = baseline,
            stagedSnapshot = staged,
            baselineModel = baselineModel,
            stagedModel = stagedModel,
            baselineMavenDiagnostics = JavaRenameMavenModuleEvidence.canonicalMavenDiagnostics(baselineModel.diagnostics),
            stagedMavenDiagnostics = JavaRenameMavenModuleEvidence.canonicalMavenDiagnostics(stagedModel.diagnostics),
            baselineJdt = baselineJdt,
            stagedJdt = stagedJdt,
            baselineJdtCanonical = JavaRenameMavenModuleEvidence.canonicalDiagnostics(baseline, baselineJdt),
            stagedJdtCanonical = JavaRenameMavenModuleEvidence.canonicalDiagnostics(staged, stagedJdt),
            baselineDiagnosticsHash = JavaRenameMavenModuleEvidence.exactDiagnosticsHash(baselineDiagnostics),
            stagedDiagnosticsHash = JavaRenameMavenModuleEvidence.exactDiagnosticsHash(stagedDiagnostics),
        )
    }

    fun authoritativeEvaluation(
        candidate: ProjectSnapshot,
        authority: GateAuthority,
    ): AuthoritativeDiagnosticsEvaluation {
        val candidateTrackedHash = JavaRenameMavenModuleEvidence.trackedInventoryHash(candidate)
        val expectation = when (candidateTrackedHash) {
            authority.baselineTrackedHash -> GateExpectation(
                authoritativeSnapshotHash = authority.baselineSnapshotHash,
                admittedCandidateSnapshotHashes = setOf(authority.baselineSnapshotHash),
                manifestHash = authority.baselineManifestHash,
                reactorHash = authority.baselineReactorHash,
                mavenDiagnosticsHash = authority.baselineMavenDiagnosticsHash,
                jdtDiagnosticsHash = authority.baselineJdtDiagnosticsHash,
                diagnosticsHash = authority.baselineDiagnosticsHash,
                destinationPresent = false,
            )
            authority.stagedTrackedHash -> GateExpectation(
                authoritativeSnapshotHash = authority.stagedSnapshotHash,
                admittedCandidateSnapshotHashes = setOf(
                    authority.stagedCandidateSnapshotHash,
                    authority.stagedSnapshotHash,
                ),
                manifestHash = authority.stagedManifestHash,
                reactorHash = authority.stagedReactorHash,
                mavenDiagnosticsHash = authority.stagedMavenDiagnosticsHash,
                jdtDiagnosticsHash = authority.stagedJdtDiagnosticsHash,
                diagnosticsHash = authority.stagedDiagnosticsHash,
                destinationPresent = true,
            )
            else -> error("Candidate tracked-file identity is outside the Maven module-rename authority lease")
        }
        require(candidate.hash in expectation.admittedCandidateSnapshotHashes) {
            "Candidate semantic snapshot identity is outside the Maven module-rename authority lease"
        }
        return JavaRenameMavenModuleWorkspace.withWorkspaceCopy(candidate.workspace.root) { workspace, emptyRepository ->
            JavaRenameMavenModuleWorkspace.reconcileTrackedSnapshot(workspace, emptyRepository, candidate)
            require(JavaRenameMavenModuleWorkspace.manifestHash(workspace) == expectation.manifestHash) {
                "Full staged workspace identity differs from the Maven module-rename authority lease"
            }
            val destination = workspace.resolve(authority.newModule)
            require(Files.exists(destination, LinkOption.NOFOLLOW_LINKS) == expectation.destinationPresent) {
                "Destination no-follow fact differs from the Maven module-rename authority lease"
            }
            if (expectation.destinationPresent) {
                require(Files.isDirectory(workspace.resolve(authority.oldModule), LinkOption.NOFOLLOW_LINKS)) {
                    "The staged source hierarchy was not preserved as empty directories"
                }
            }
            val rebuilt = JavaRenameMavenModuleWorkspace.scanOffline(workspace, emptyRepository)
            require(JavaRenameMavenModuleEvidence.trackedInventoryHash(rebuilt) == candidateTrackedHash) {
                "Rebuilt tracked-file identity differs from the staged candidate"
            }
            val authoritative = rehydrateSemanticSnapshot(candidate, rebuilt)
            require(authoritative.hash == expectation.authoritativeSnapshotHash) {
                "Rebuilt authoritative snapshot identity differs from the operation-authority lease"
            }
            if (!expectation.destinationPresent) {
                require(authoritative == candidate) {
                    "Authoritative baseline evaluation must return exact S0"
                }
            }
            val model = authoritative.buildModels.singleOrNull()
            require(model?.providerId == MAVEN_PROVIDER && model.status == BuildModelStatus.AVAILABLE) {
                "The staged Maven effective reactor is unavailable offline"
            }
            require(JavaRenameMavenModuleEvidence.reactorHash(authoritative, model) == expectation.reactorHash) {
                "The rebuilt Maven reactor identity differs from the operation-authority lease"
            }
            val mavenCanonical = JavaRenameMavenModuleEvidence.canonicalMavenDiagnostics(model.diagnostics)
            require(hashStrings(mavenCanonical) == expectation.mavenDiagnosticsHash) {
                "The rebuilt Maven diagnostics differ from the operation-authority lease"
            }
            val rebuiltJdt = JavaLanguageAdapter().authoritativeDiagnostics(rebuilt, javaPlatformHome())
            val jdt = rebaseDiagnostics(rebuiltJdt, rebuilt.workspace.root, candidate.workspace.root)
            require(hashStrings(JavaRenameMavenModuleEvidence.canonicalDiagnostics(authoritative, jdt)) == expectation.jdtDiagnosticsHash) {
                "The rebuilt authoritative JDT diagnostics differ from the operation-authority lease"
            }
            val diagnostics = JavaRenameMavenModuleEvidence.mavenDiagnostics(model.diagnostics) + jdt
            require(JavaRenameMavenModuleEvidence.exactDiagnosticsHash(diagnostics) == expectation.diagnosticsHash) {
                "The exact Maven and authoritative JDT diagnostic multiset differs from the operation-authority lease"
            }
            AuthoritativeDiagnosticsEvaluation(authoritative, diagnostics)
        }
    }

    private fun rehydrateSemanticSnapshot(
        candidate: ProjectSnapshot,
        rebuilt: ProjectSnapshot,
    ): ProjectSnapshot {
        val fromRoot = rebuilt.workspace.root.toAbsolutePath().normalize()
        val toRoot = candidate.workspace.root.toAbsolutePath().normalize()
        return candidate.copy(
            modules = rebuilt.modules.map { module -> module.copy(
                root = rebasePath(module.root, fromRoot, toRoot),
                sourceRoots = module.sourceRoots.map { rebasePath(it, fromRoot, toRoot) },
                classpathEntries = module.classpathEntries.map { rebasePath(it, fromRoot, toRoot) },
                languageSettings = rebaseAttributes(module.languageSettings, fromRoot, toRoot),
                mainSourceRoots = module.mainSourceRoots.map { rebasePath(it, fromRoot, toRoot) },
                testSourceRoots = module.testSourceRoots.map { rebasePath(it, fromRoot, toRoot) },
                generatedSourceRoots = module.generatedSourceRoots.map { rebasePath(it, fromRoot, toRoot) },
                generatedTestSourceRoots = module.generatedTestSourceRoots.map { rebasePath(it, fromRoot, toRoot) },
                mainClasspathEntries = module.mainClasspathEntries.map { rebasePath(it, fromRoot, toRoot) },
                mainRuntimeClasspathEntries = module.mainRuntimeClasspathEntries.map { rebasePath(it, fromRoot, toRoot) },
                testClasspathEntries = module.testClasspathEntries.map { rebasePath(it, fromRoot, toRoot) },
                mainOutputDirectories = module.mainOutputDirectories.map { rebasePath(it, fromRoot, toRoot) },
                testOutputDirectories = module.testOutputDirectories.map { rebasePath(it, fromRoot, toRoot) },
            ) },
            classpathEvidence = rebuilt.classpathEvidence.map { evidence ->
                val rebasedPath = rebasePath(evidence.path, fromRoot, toRoot)
                if (evidence.kind == ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT) {
                    ClasspathEvidence.capture(toRoot, rebasedPath, evidence.kind)
                } else {
                    evidence.copy(path = rebasedPath)
                }
            },
            buildModels = rebuilt.buildModels.map { model -> model.copy(
                diagnostics = model.diagnostics.map { diagnostic ->
                    diagnostic.copy(message = rebaseWorkspaceText(diagnostic.message, fromRoot, toRoot))
                },
                attributes = rebaseAttributes(model.attributes, fromRoot, toRoot),
                modules = model.modules.map { module -> module.copy(
                    root = rebasePath(module.root, fromRoot, toRoot),
                    attributes = rebaseAttributes(module.attributes, fromRoot, toRoot),
                    sourceSets = module.sourceSets.map { sourceSet -> sourceSet.copy(
                        sourceRoots = sourceSet.sourceRoots.map { rebasePath(it, fromRoot, toRoot) },
                        generatedSourceRoots = sourceSet.generatedSourceRoots.map { rebasePath(it, fromRoot, toRoot) },
                        outputDirectories = sourceSet.outputDirectories.map { rebasePath(it, fromRoot, toRoot) },
                        classpathEntries = sourceSet.classpathEntries.map { rebasePath(it, fromRoot, toRoot) },
                        runtimeClasspathEntries = sourceSet.runtimeClasspathEntries.map { rebasePath(it, fromRoot, toRoot) },
                        attributes = rebaseAttributes(sourceSet.attributes, fromRoot, toRoot),
                    ) },
                ) },
            ) },
        )
    }

    private fun rebaseAttributes(
        attributes: Map<String, String>,
        fromRoot: Path,
        toRoot: Path,
    ): Map<String, String> {
        val rebased = attributes.mapValuesTo(linkedMapOf()) { (_, value) ->
            rebaseWorkspaceText(value, fromRoot, toRoot)
        }
        attributes.keys.filter { it.endsWith(REPOSITORY_IDENTITY_HASH_SUFFIX) }.forEach { identityKey ->
            val prefix = identityKey.removeSuffix(REPOSITORY_IDENTITY_HASH_SUFFIX)
            val repositoryRootText = requireNotNull(rebased["$prefix.repository.root"]) {
                "Repository identity has no root"
            }
            val repositoryRoot = Path.of(repositoryRootText).let { path ->
                if (path.isAbsolute) path.toAbsolutePath().normalize() else toRoot.resolve(path).normalize()
            }
            val parts = listOf(
                requireNotNull(rebased["$prefix.repository.provider"]),
                requireNotNull(rebased["$prefix.repository.layout"]),
                repositoryRoot,
                requireNotNull(rebased["$prefix.repository.policy"]),
            )
            rebased[identityKey] = sha256(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        }
        return rebased
    }

    private fun rebaseDiagnostics(
        diagnostics: List<Diagnostic>,
        fromRoot: Path,
        toRoot: Path,
    ): List<Diagnostic> = diagnostics.map { diagnostic -> diagnostic.copy(
        message = rebaseWorkspaceText(diagnostic.message, fromRoot, toRoot),
        location = diagnostic.location?.let { location ->
            location.copy(path = rebasePath(location.path, fromRoot, toRoot))
        },
        details = DiagnosticDetails(diagnostic.details.fields.mapValues { (_, value) ->
            rebaseWorkspaceText(value, fromRoot, toRoot)
        }),
    ) }

    private fun rebasePath(path: Path, fromRoot: Path, toRoot: Path): Path {
        val normalized = path.normalize()
        return if (normalized.isAbsolute && normalized.toAbsolutePath().normalize().startsWith(fromRoot)) {
            toRoot.resolve(fromRoot.relativize(normalized.toAbsolutePath().normalize())).normalize()
        } else normalized
    }

    private fun rebaseWorkspaceText(value: String, fromRoot: Path, toRoot: Path): String {
        val fromNative = fromRoot.toString()
        val toNative = toRoot.toString()
        val fromPortable = fromNative.replace('\\', '/')
        val toPortable = toNative.replace('\\', '/')
        return value.replace(fromPortable, toPortable).replace(fromNative, toNative)
    }
}
