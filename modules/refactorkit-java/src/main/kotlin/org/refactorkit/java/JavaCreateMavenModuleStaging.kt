package org.refactorkit.java

import org.refactorkit.core.AuthoritativeDiagnosticsEvaluation
import org.refactorkit.core.AuthoritativeDiagnosticsProvider
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.java.JavaRenameMavenModuleContract.MAVEN_PROVIDER
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Offline staging and lease-bound authoritative validation for Maven Add Module. */
internal object JavaCreateMavenModuleStaging {
    private const val LEASE_KIND = "java-create-maven-module-v1"
    private const val BASELINE_HASH = "baselineSnapshotHash"
    private const val CANDIDATE_HASH = "stagedCandidateSnapshotHash"
    private const val STAGED_HASH = "stagedAuthoritativeSnapshotHash"
    private const val BASELINE_TRACKED = "baselineTrackedInventoryHash"
    private const val STAGED_TRACKED = "stagedTrackedInventoryHash"
    private const val BASELINE_MANIFEST = "baselineWorkspaceManifestHash"
    private const val STAGED_MANIFEST = "stagedWorkspaceManifestHash"
    private const val BASELINE_REACTOR = "baselineReactorHash"
    private const val STAGED_REACTOR = "stagedReactorHash"
    private const val BASELINE_MAVEN_DIAGNOSTICS = "baselineMavenDiagnosticsHash"
    private const val STAGED_MAVEN_DIAGNOSTICS = "stagedMavenDiagnosticsHash"
    private const val BASELINE_JDT_DIAGNOSTICS = "baselineJdtDiagnosticsHash"
    private const val STAGED_JDT_DIAGNOSTICS = "stagedJdtDiagnosticsHash"
    private const val PARENT_POM = "parentPomPath"
    private const val MODULE_NAME = "moduleName"
    private const val GROUP_ID = "parentGroupId"
    private const val ARTIFACT_ID = "parentArtifactId"
    private const val VERSION = "parentVersion"

    internal data class Facts(
        val baseline: ProjectSnapshot,
        val staged: ProjectSnapshot,
        val baselineModel: BuildModel,
        val stagedModel: BuildModel,
        val baselineManifest: String,
        val stagedManifest: String,
        val baselineDiagnostics: List<Diagnostic>,
        val stagedDiagnostics: List<Diagnostic>,
        val baselineMavenCanonical: List<String>,
        val stagedMavenCanonical: List<String>,
        val baselineJdtCanonical: List<String>,
        val stagedJdtCanonical: List<String>,
    )

    fun stage(
        snapshot: ProjectSnapshot,
        simulated: ProjectSnapshot,
        edit: WorkspaceEdit,
        moduleName: String,
        parentPom: Path,
        modulePom: Path,
        groupId: String,
        artifactId: String,
        version: String,
        existingModules: List<String>,
    ): Facts = JavaRenameMavenModuleWorkspace.withWorkspaceCopy(snapshot.workspace.root) { workspace, emptyRepository ->
        val baselineManifest = JavaRenameMavenModuleWorkspace.manifestHash(workspace)
        val scannedBaseline = JavaRenameMavenModuleWorkspace.scanOffline(workspace, emptyRepository)
        require(trackedHash(scannedBaseline) == trackedHash(snapshot)) { "Copied baseline tracked inventory differs" }
        val baseline = JavaRenameMavenModuleStaging.rehydrateSemanticSnapshot(snapshot, scannedBaseline)
        require(baseline.hash == snapshot.hash) { "Copied offline baseline does not reproduce the selected snapshot" }
        val baselineModel = availableModel(baseline)
        require(reactorHash(baseline, baselineModel) == reactorHash(snapshot, availableModel(snapshot))) {
            "Copied offline baseline Maven reactor differs from the selected model"
        }
        val baselineJdt = JavaLanguageAdapter().authoritativeDiagnostics(scannedBaseline,
            JavaRenameMavenModuleContract.javaPlatformHome())
        val selectedJdt = JavaLanguageAdapter().authoritativeDiagnostics(snapshot,
            JavaRenameMavenModuleContract.javaPlatformHome())
        val baselineJdtCanonical = JavaRenameMavenModuleEvidence.canonicalDiagnostics(baseline, rebaseDiagnostics(
            baselineJdt, scannedBaseline.workspace.root, snapshot.workspace.root,
        ))
        require(baselineJdtCanonical == JavaRenameMavenModuleEvidence.canonicalDiagnostics(snapshot, selectedJdt)) {
            "Copied offline baseline JDT diagnostics differ from the selected snapshot"
        }

        applyEdit(workspace, edit)
        val stagedManifest = JavaRenameMavenModuleWorkspace.manifestHash(workspace)
        val scannedStaged = JavaRenameMavenModuleWorkspace.scanOffline(workspace, emptyRepository)
        require(trackedHash(scannedStaged) == trackedHash(simulated)) { "Offline staged tracked inventory differs from the simulated edit" }
        val staged = JavaRenameMavenModuleStaging.rehydrateSemanticSnapshot(simulated, scannedStaged)
        val stagedModel = availableModel(staged)
        attestNewModule(baseline, baselineModel, staged, stagedModel, moduleName, parentPom, modulePom,
            groupId, artifactId, version, existingModules)
        val stagedJdt = JavaLanguageAdapter().authoritativeDiagnostics(scannedStaged,
            JavaRenameMavenModuleContract.javaPlatformHome())
        val stagedJdtCanonical = JavaRenameMavenModuleEvidence.canonicalDiagnostics(staged, rebaseDiagnostics(
            stagedJdt, scannedStaged.workspace.root, snapshot.workspace.root,
        ))
        val baselineMavenCanonical = JavaRenameMavenModuleEvidence.canonicalMavenDiagnostics(baselineModel.diagnostics)
        val stagedMavenCanonical = JavaRenameMavenModuleEvidence.canonicalMavenDiagnostics(stagedModel.diagnostics)
        require(baselineMavenCanonical == stagedMavenCanonical) { "Adding a Maven module introduces Maven diagnostics" }
        require(baselineJdtCanonical == stagedJdtCanonical) { "Adding a Maven module introduces Java diagnostics" }
        val baselineDiagnostics = JavaRenameMavenModuleEvidence.mavenDiagnostics(baselineModel.diagnostics) +
            rebaseDiagnostics(baselineJdt, scannedBaseline.workspace.root, snapshot.workspace.root)
        val stagedDiagnostics = JavaRenameMavenModuleEvidence.mavenDiagnostics(stagedModel.diagnostics) +
            rebaseDiagnostics(stagedJdt, scannedStaged.workspace.root, snapshot.workspace.root)
        Facts(baseline, staged, baselineModel, stagedModel, baselineManifest, stagedManifest,
            baselineDiagnostics, stagedDiagnostics, baselineMavenCanonical, stagedMavenCanonical,
            baselineJdtCanonical, stagedJdtCanonical)
    }

    fun lease(
        snapshot: ProjectSnapshot,
        simulated: ProjectSnapshot,
        edit: WorkspaceEdit,
        moduleName: String,
        parentPom: Path,
        groupId: String,
        artifactId: String,
        version: String,
        facts: Facts,
    ): OperationAuthorityLease {
        val files = snapshot.trackedFiles.sortedBy { it.path.toString() }.map { source ->
            OperationAuthorityFileEvidence(
                kind = "MAVEN_ADD_MODULE_INPUT",
                path = source.path.normalize(),
                expectedContentSha256 = JavaRenameMavenModuleContract.sha256(source.content.toByteArray(Charsets.UTF_8)),
                attributes = mapOf("languageId" to source.languageId),
            )
        }
        val attributes = mapOf(
            BASELINE_HASH to snapshot.hash,
            CANDIDATE_HASH to simulated.hash,
            STAGED_HASH to facts.staged.hash,
            BASELINE_TRACKED to trackedHash(facts.baseline),
            STAGED_TRACKED to trackedHash(facts.staged),
            BASELINE_MANIFEST to facts.baselineManifest,
            STAGED_MANIFEST to facts.stagedManifest,
            BASELINE_REACTOR to reactorHash(facts.baseline, facts.baselineModel),
            STAGED_REACTOR to reactorHash(facts.staged, facts.stagedModel),
            BASELINE_MAVEN_DIAGNOSTICS to hash(facts.baselineMavenCanonical),
            STAGED_MAVEN_DIAGNOSTICS to hash(facts.stagedMavenCanonical),
            BASELINE_JDT_DIAGNOSTICS to hash(facts.baselineJdtCanonical),
            STAGED_JDT_DIAGNOSTICS to hash(facts.stagedJdtCanonical),
            PARENT_POM to pathString(parentPom),
            MODULE_NAME to moduleName,
            GROUP_ID to groupId,
            ARTIFACT_ID to artifactId,
            VERSION to version,
        )
        return OperationAuthorityLease(
            kind = LEASE_KIND,
            operation = JavaCreateMavenModulePlanner.OPERATION,
            snapshotHash = snapshot.hash,
            evidenceHash = JavaRenameMavenModuleEvidence.authorityEvidenceHash(attributes, files,
                snapshot.classpathEvidence),
            workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit),
            requiredClasspathEvidence = snapshot.classpathEvidence,
            requiredFileEvidence = files,
            attributes = attributes,
        )
    }

    fun diagnosticsGate(plan: PatchPlan): DiagnosticsGate {
        require(plan.operation == JavaCreateMavenModulePlanner.OPERATION && plan.status == PatchStatus.PREVIEW)
        val lease = requireNotNull(plan.authorityLease)
        require(lease.kind == LEASE_KIND && lease.operation == plan.operation && lease.snapshotHash == plan.snapshotHash)
        require(lease.workspaceEditSha256 == WorkspaceEditIdentity.sha256(plan.workspaceEdit))
        require(lease.evidenceHash == JavaRenameMavenModuleEvidence.authorityEvidenceHash(
            lease.attributes, lease.requiredFileEvidence, lease.requiredClasspathEvidence,
        ))
        return DiagnosticsGate.authoritative(JavaCreateMavenModulePlanner.DIAGNOSTICS_GATE_ID,
            AuthoritativeDiagnosticsProvider { candidate -> authoritativeEvaluation(candidate, lease) })
    }

    private fun authoritativeEvaluation(
        candidate: ProjectSnapshot,
        lease: OperationAuthorityLease,
    ): AuthoritativeDiagnosticsEvaluation {
        val a = lease.attributes
        val candidateTracked = trackedHash(candidate)
        val baseline = candidateTracked == a.getValue(BASELINE_TRACKED)
        val staged = candidateTracked == a.getValue(STAGED_TRACKED)
        require(baseline.xor(staged)) { "Candidate inventory is outside the Add Module authority lease" }
        val expectedHash = a.getValue(if (baseline) BASELINE_HASH else STAGED_HASH)
        val admitted = if (baseline) setOf(a.getValue(BASELINE_HASH))
            else setOf(a.getValue(CANDIDATE_HASH), a.getValue(STAGED_HASH))
        require(candidate.hash in admitted) { "Candidate snapshot is outside the Add Module authority lease" }

        return JavaRenameMavenModuleWorkspace.withWorkspaceCopy(candidate.workspace.root) { workspace, emptyRepository ->
            JavaRenameMavenModuleWorkspace.reconcileTrackedSnapshot(workspace, emptyRepository, candidate)
            val expectedManifest = a.getValue(if (baseline) BASELINE_MANIFEST else STAGED_MANIFEST)
            require(JavaRenameMavenModuleWorkspace.manifestHash(workspace) == expectedManifest) {
                "Full workspace manifest differs from the Add Module authority lease"
            }
            val pom = workspace.resolve(a.getValue(MODULE_NAME)).resolve("pom.xml")
            require(Files.exists(pom, LinkOption.NOFOLLOW_LINKS) == staged) {
                "Add Module destination presence differs from the leased phase"
            }
            val rebuilt = JavaRenameMavenModuleWorkspace.scanOffline(workspace, emptyRepository)
            require(trackedHash(rebuilt) == candidateTracked) { "Rebuilt candidate inventory differs from the lease" }
            val authoritative = JavaRenameMavenModuleStaging.rehydrateSemanticSnapshot(candidate, rebuilt)
            require(authoritative.hash == expectedHash) { "Rebuilt authoritative snapshot differs from the lease" }
            val model = availableModel(authoritative)
            require(reactorHash(authoritative, model) == a.getValue(if (baseline) BASELINE_REACTOR else STAGED_REACTOR)) {
                "Rebuilt Maven reactor differs from the Add Module lease"
            }
            val mavenCanonical = JavaRenameMavenModuleEvidence.canonicalMavenDiagnostics(model.diagnostics)
            require(hash(mavenCanonical) == a.getValue(if (baseline) BASELINE_MAVEN_DIAGNOSTICS else STAGED_MAVEN_DIAGNOSTICS)) {
                "Rebuilt Maven diagnostics differ from the Add Module lease"
            }
            val jdtRaw = JavaLanguageAdapter().authoritativeDiagnostics(rebuilt, JavaRenameMavenModuleContract.javaPlatformHome())
            val jdt = rebaseDiagnostics(jdtRaw, rebuilt.workspace.root, candidate.workspace.root)
            val jdtCanonical = JavaRenameMavenModuleEvidence.canonicalDiagnostics(authoritative, jdt)
            require(hash(jdtCanonical) == a.getValue(if (baseline) BASELINE_JDT_DIAGNOSTICS else STAGED_JDT_DIAGNOSTICS)) {
                "Rebuilt authoritative Java diagnostics differ from the Add Module lease"
            }
            AuthoritativeDiagnosticsEvaluation(authoritative,
                JavaRenameMavenModuleEvidence.mavenDiagnostics(model.diagnostics) + jdt)
        }
    }

    private fun attestNewModule(
        baseline: ProjectSnapshot,
        baselineModel: BuildModel,
        staged: ProjectSnapshot,
        stagedModel: BuildModel,
        moduleName: String,
        parentPom: Path,
        modulePom: Path,
        groupId: String,
        artifactId: String,
        version: String,
        existingModules: List<String>,
    ) {
        val stagedModule = stagedModel.modules.singleOrNull {
            it.attributes["java.maven.pomPath"]?.let(Path::of)?.normalize() == modulePom
        } ?: error("Staged reactor has no unique effective module for $modulePom")
        require(pathString(JavaRenameMavenModuleEvidence.relativeModuleRoot(staged, stagedModule)) == moduleName &&
            stagedModule.id == moduleName) {
            "Staged module is not one direct child named '$moduleName': id=${stagedModule.id}, " +
                "root=${stagedModule.root}, pom=${stagedModule.attributes["java.maven.pomPath"]}"
        }
        require(stagedModule.attributes["java.maven.groupId"] == groupId &&
            stagedModule.attributes["java.maven.artifactId"] == moduleName &&
            stagedModule.attributes["java.maven.version"] == version) {
            "Staged module does not inherit the selected parent's declared coordinates"
        }
        val before = JavaRenameMavenModuleEvidence.reactorView(baseline, baselineModel)
        val after = JavaRenameMavenModuleEvidence.reactorView(staged, stagedModel)
        require(after.modules - moduleName == before.modules) { "Existing effective reactor modules changed" }
        val parent = staged.auxiliaryFiles.singleOrNull { it.path.normalize() == parentPom }
            ?: error("Selected parent POM is absent from staged snapshot")
        val root = WoodstoxXmlTree.parse(parent.content).children.single { it.localName == "project" }
        val modules = root.children.single { it.localName == "modules" }
        val declarations = modules.children.filter { it.localName == "module" }.map { node ->
            parent.content.substring(node.contentStart, node.contentEnd).trim()
        }
        require(declarations == existingModules + moduleName) { "Parent direct module declarations differ from their exact successor" }
        val child = staged.auxiliaryFiles.singleOrNull { it.path.normalize() == modulePom }
            ?: error("Generated child POM is absent from staged snapshot")
        val project = WoodstoxXmlTree.parse(child.content).children.single { it.localName == "project" }
        val parentNode = project.children.single { it.localName == "parent" }
        val coordinates = listOf("groupId", "artifactId", "version").map { name ->
            val value = parentNode.children.single { it.localName == name }
            child.content.substring(value.contentStart, value.contentEnd).trim()
        }
        require(coordinates == listOf(groupId, artifactId, version)) { "Child POM parent GAV differs from the declared parent GAV" }
    }

    private fun applyEdit(workspace: Path, edit: WorkspaceEdit) {
        edit.edits.forEach { entry ->
            val path = JavaRenameMavenModuleWorkspace.resolveInside(workspace, entry.path)
            when (entry) {
                is FileEdit.Create -> {
                    require(!entry.overwrite && Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
                    Files.createDirectories(requireNotNull(path.parent))
                    Files.writeString(path, entry.content, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                is FileEdit.Modify -> {
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    Files.writeString(path, TextEdits.apply(Files.readString(path), entry.textEdits),
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                }
                is FileEdit.Rename, is FileEdit.Delete -> error("Maven Add Module admits only create and modify edits")
            }
        }
    }

    private fun availableModel(snapshot: ProjectSnapshot): BuildModel = snapshot.buildModels.singleOrNull {
        it.providerId == MAVEN_PROVIDER && it.status == BuildModelStatus.AVAILABLE
    } ?: error("Available offline Maven effective reactor is required")

    private fun reactorHash(snapshot: ProjectSnapshot, model: BuildModel): String =
        JavaRenameMavenModuleEvidence.reactorHash(snapshot, model)

    private fun trackedHash(snapshot: ProjectSnapshot): String = JavaRenameMavenModuleEvidence.trackedInventoryHash(snapshot)

    private fun hash(values: Iterable<String>): String = JavaRenameMavenModuleContract.hashStrings(values)

    private fun pathString(path: Path): String = JavaRenameMavenModuleContract.pathString(path)

    private fun rebaseDiagnostics(diagnostics: List<Diagnostic>, from: Path, to: Path): List<Diagnostic> = diagnostics.map { d ->
        val fromRoot = from.toAbsolutePath().normalize()
        val location = d.location
        val path = location?.path?.normalize()
        d.copy(
            message = d.message.replace(fromRoot.toString(), to.toAbsolutePath().normalize().toString()),
            location = location?.copy(path = if (path != null && path.isAbsolute && path.startsWith(fromRoot))
                to.toAbsolutePath().normalize().resolve(fromRoot.relativize(path)).normalize() else path ?: location.path),
        )
    }
}
