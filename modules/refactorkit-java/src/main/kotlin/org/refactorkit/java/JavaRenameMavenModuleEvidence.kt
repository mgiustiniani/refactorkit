package org.refactorkit.java

import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticDetails
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.java.JavaRenameMavenModuleContract.AMBIGUOUS_ORIGIN
import org.refactorkit.java.JavaRenameMavenModuleContract.BASELINE_DIAGNOSTICS_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.BASELINE_JDT_DIAGNOSTICS_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.BASELINE_MANIFEST_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.BASELINE_MAVEN_DIAGNOSTICS_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.BASELINE_REACTOR_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.BASELINE_SNAPSHOT_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.BASELINE_TRACKED_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.DESCRIPTOR_UNAVAILABLE
import org.refactorkit.java.JavaRenameMavenModuleContract.EDIT_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.GateAuthority
import org.refactorkit.java.JavaRenameMavenModuleContract.LEASE_KIND
import org.refactorkit.java.JavaRenameMavenModuleContract.MAVEN_PROVIDER
import org.refactorkit.java.JavaRenameMavenModuleContract.ModuleIdentity
import org.refactorkit.java.JavaRenameMavenModuleContract.ModuleView
import org.refactorkit.java.JavaRenameMavenModuleContract.NEW_MODULE_ATTRIBUTE
import org.refactorkit.java.JavaRenameMavenModuleContract.OLD_MODULE_ATTRIBUTE
import org.refactorkit.java.JavaRenameMavenModuleContract.OPERATION
import org.refactorkit.java.JavaRenameMavenModuleContract.OriginRecord
import org.refactorkit.java.JavaRenameMavenModuleContract.RawPom
import org.refactorkit.java.JavaRenameMavenModuleContract.ReactorView
import org.refactorkit.java.JavaRenameMavenModuleContract.SOURCE_UNRECOGNIZED
import org.refactorkit.java.JavaRenameMavenModuleContract.STAGED_CANDIDATE_SNAPSHOT_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.STAGED_DIAGNOSTICS_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.STAGED_JDT_DIAGNOSTICS_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.STAGED_MANIFEST_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.STAGED_MAVEN_DIAGNOSTICS_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.STAGED_REACTOR_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.STAGED_SNAPSHOT_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.STAGED_TRACKED_HASH
import org.refactorkit.java.JavaRenameMavenModuleContract.SourceSetView
import org.refactorkit.java.JavaRenameMavenModuleContract.StageFacts
import org.refactorkit.java.JavaRenameMavenModuleContract.formatRange
import org.refactorkit.java.JavaRenameMavenModuleContract.hashStrings
import org.refactorkit.java.JavaRenameMavenModuleContract.pathString
import org.refactorkit.java.JavaRenameMavenModuleContract.refuse
import org.refactorkit.java.JavaRenameMavenModuleContract.requiredDirectChild
import org.refactorkit.java.JavaRenameMavenModuleContract.requiredHash
import org.refactorkit.java.JavaRenameMavenModuleContract.sha256
import java.nio.file.Path

/** Constructs and validates immutable lease, origin, reactor, diagnostic, and hash evidence. */
internal object JavaRenameMavenModuleEvidence {
    fun gateAuthority(plan: PatchPlan): GateAuthority {
        require(plan.operation == OPERATION && plan.status == PatchStatus.PREVIEW) {
            "The diagnostics gate requires a java.renameMavenModule preview"
        }
        val lease = requireNotNull(plan.authorityLease) {
            "The diagnostics gate requires an operation-authority lease"
        }
        require(lease.kind == LEASE_KIND && lease.operation == OPERATION && lease.snapshotHash == plan.snapshotHash) {
            "The operation-authority lease does not belong to this Maven module rename"
        }
        val attributes = lease.attributes
        require(attributes.getValue(BASELINE_SNAPSHOT_HASH) == lease.snapshotHash) {
            "The Maven module-rename baseline identity differs from its authority lease"
        }
        require(attributes.getValue(EDIT_HASH) == workspaceEditHash(plan.workspaceEdit)) {
            "The Maven module-rename edit differs from its authority lease"
        }
        require(lease.evidenceHash == authorityEvidenceHash(
            attributes,
            lease.requiredFileEvidence,
            lease.requiredClasspathEvidence,
        )) {
            "The Maven module-rename evidence identity differs from its authority lease"
        }
        return GateAuthority(
            baselineSnapshotHash = requiredHash(attributes, BASELINE_SNAPSHOT_HASH),
            stagedCandidateSnapshotHash = requiredHash(attributes, STAGED_CANDIDATE_SNAPSHOT_HASH),
            stagedSnapshotHash = requiredHash(attributes, STAGED_SNAPSHOT_HASH),
            baselineTrackedHash = requiredHash(attributes, BASELINE_TRACKED_HASH),
            stagedTrackedHash = requiredHash(attributes, STAGED_TRACKED_HASH),
            baselineManifestHash = requiredHash(attributes, BASELINE_MANIFEST_HASH),
            stagedManifestHash = requiredHash(attributes, STAGED_MANIFEST_HASH),
            baselineReactorHash = requiredHash(attributes, BASELINE_REACTOR_HASH),
            stagedReactorHash = requiredHash(attributes, STAGED_REACTOR_HASH),
            baselineMavenDiagnosticsHash = requiredHash(attributes, BASELINE_MAVEN_DIAGNOSTICS_HASH),
            stagedMavenDiagnosticsHash = requiredHash(attributes, STAGED_MAVEN_DIAGNOSTICS_HASH),
            baselineJdtDiagnosticsHash = requiredHash(attributes, BASELINE_JDT_DIAGNOSTICS_HASH),
            stagedJdtDiagnosticsHash = requiredHash(attributes, STAGED_JDT_DIAGNOSTICS_HASH),
            baselineDiagnosticsHash = requiredHash(attributes, BASELINE_DIAGNOSTICS_HASH),
            stagedDiagnosticsHash = requiredHash(attributes, STAGED_DIAGNOSTICS_HASH),
            oldModule = requiredDirectChild(attributes.getValue(OLD_MODULE_ATTRIBUTE), "lease source module"),
            newModule = requiredDirectChild(attributes.getValue(NEW_MODULE_ATTRIBUTE), "lease destination module"),
        )
    }

    fun validateStagedReactor(
        baseline: ReactorView,
        staged: ReactorView,
        oldModule: Path,
        newModule: Path,
        sourceIdentity: ModuleIdentity,
        targetArtifact: String,
        baselineConsumers: List<BuildModule>,
        stagedRootModules: List<String>,
        expectedRootModules: List<String>,
    ) {
        if (stagedRootModules != expectedRootModules) {
            refuse(AMBIGUOUS_ORIGIN, "The staged root module declaration list is not the exact ordered successor")
        }
        val oldName = pathString(oldModule)
        val newName = pathString(newModule)
        if (staged.modules.size != baseline.modules.size || oldName in staged.modules || newName !in staged.modules) {
            refuse(DESCRIPTOR_UNAVAILABLE, "The staged reactor does not contain the exact renamed 1:1 module set")
        }
        baseline.modules.forEach { (id, before) ->
            val expectedId = if (id == oldName) newName else id
            val after = staged.modules[expectedId]
                ?: refuse(DESCRIPTOR_UNAVAILABLE, "Staged reactor module is missing: $expectedId")
            val expected = before.renamed(oldName, newName, if (id == oldName) targetArtifact else before.artifactId)
            if (after != expected) {
                refuse(DESCRIPTOR_UNAVAILABLE, "Staged reactor facts differ outside the authorized module identity: $expectedId")
            }
        }
        val source = staged.modules.getValue(newName)
        if (source.groupId != sourceIdentity.groupId || source.artifactId != targetArtifact ||
            source.version != sourceIdentity.version || source.packaging != "jar"
        ) {
            refuse(DESCRIPTOR_UNAVAILABLE, "The staged source coordinate is not the exact requested JAR identity")
        }
        val expectedConsumers = baselineConsumers.mapTo(linkedSetOf()) { it.id }
        val actualConsumers = staged.modules.values.filter { module ->
            module.sourceSets.any { sourceSet ->
                sourceSet.kind == SourceSetKind.MAIN.name &&
                    sourceSet.dependencies.any { it.first == newName && it.second == DependencyScope.COMPILE.name }
            }
        }.mapTo(linkedSetOf()) { it.id }
        if (actualConsumers != expectedConsumers) {
            refuse(DESCRIPTOR_UNAVAILABLE, "The staged direct effective consumer edges are not the exact renamed successor")
        }
    }

    fun reactorView(snapshot: ProjectSnapshot, model: BuildModel): ReactorView = ReactorView(
        modules = model.modules.associate { module ->
            module.id to ModuleView(
                id = module.id,
                name = module.name,
                root = buildPath(snapshot, module.root),
                groupId = module.attributes.getValue("java.maven.groupId"),
                artifactId = module.attributes.getValue("java.maven.artifactId"),
                version = module.attributes.getValue("java.maven.version"),
                packaging = module.attributes.getValue("java.maven.packaging"),
                sourceSets = module.sourceSets.map { sourceSet -> sourceSetView(snapshot, sourceSet) },
            )
        },
    )

    private fun sourceSetView(snapshot: ProjectSnapshot, sourceSet: BuildSourceSet): SourceSetView = SourceSetView(
        id = sourceSet.id,
        kind = sourceSet.kind.name,
        sourceRoots = sourceSet.sourceRoots.map { buildPath(snapshot, it) },
        generatedSourceRoots = sourceSet.generatedSourceRoots.map { buildPath(snapshot, it) },
        outputDirectories = sourceSet.outputDirectories.map { buildPath(snapshot, it) },
        classpathEntries = sourceSet.classpathEntries.map { buildPath(snapshot, it) },
        runtimeClasspathEntries = sourceSet.runtimeClasspathEntries.map { buildPath(snapshot, it) },
        dependencies = sourceSet.moduleDependencies.map { it.targetModuleId to it.scope.name },
        attributes = sourceSet.attributes.toSortedMap().mapValues { (_, value) -> normalizeWorkspaceValue(snapshot, value) },
    )

    fun reactorHash(snapshot: ProjectSnapshot, model: BuildModel): String {
        val view = reactorView(snapshot, model)
        return hashStrings(buildList {
            add(model.providerId)
            add(model.status.name)
            model.attributes.toSortedMap().forEach { (key, value) ->
                add("model:$key=${normalizeWorkspaceValue(snapshot, value)}")
            }
            view.modules.toSortedMap().forEach { (id, module) ->
                add("module:$id:${module.name}:${module.root}:${module.groupId}:${module.artifactId}:${module.version}:${module.packaging}")
                module.sourceSets.sortedBy(SourceSetView::id).forEach { sourceSet ->
                    add("sourceSet:${sourceSet.id}:${sourceSet.kind}")
                    sourceSet.sourceRoots.forEach { add("source:$it") }
                    sourceSet.generatedSourceRoots.forEach { add("generated:$it") }
                    sourceSet.outputDirectories.forEach { add("output:$it") }
                    sourceSet.classpathEntries.forEach { add("classpath:$it") }
                    sourceSet.runtimeClasspathEntries.forEach { add("runtime:$it") }
                    sourceSet.dependencies.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                        .forEach { add("dependency:${it.first}:${it.second}") }
                    sourceSet.attributes.forEach { (key, value) -> add("sourceSetAttribute:$key=$value") }
                }
            }
        })
    }

    fun leaseAttributes(
        snapshot: ProjectSnapshot,
        normalizedEdit: WorkspaceEdit,
        oldDir: Path,
        newDir: Path,
        sourceIdentity: ModuleIdentity,
        targetArtifact: String,
        rawPoms: Collection<RawPom>,
        origins: List<OriginRecord>,
        stage: StageFacts,
    ): Map<String, String> = sortedMapOf(
        "authorityVersion" to "java-rename-maven-module-v1",
        "providerId" to MAVEN_PROVIDER,
        "providerStatus" to BuildModelStatus.AVAILABLE.name,
        "networkAccess" to "DENY",
        "buildCodeExecution" to "DENY",
        "settingsCredentialsAccess" to "DENY",
        "xmlParserSelection" to WoodstoxXmlTree.parserSelection,
        "xmlDtdProcessing" to WoodstoxXmlTree.dtdProcessing,
        "xmlExternalGeneralEntities" to WoodstoxXmlTree.externalGeneralEntities,
        "xmlExternalParameterEntities" to WoodstoxXmlTree.externalParameterEntities,
        "xmlExternalDtdAccess" to WoodstoxXmlTree.externalDtdAccess,
        "xmlParserImplementation" to WoodstoxXmlTree.parserImplementation,
        "xmlParserVersion" to WoodstoxXmlTree.parserVersion,
        "xmlRangeAuthority" to WoodstoxXmlTree.rangeAuthority,
        "xmlMutation" to "RANGE_ONLY_NO_SERIALIZATION",
        OLD_MODULE_ATTRIBUTE to pathString(oldDir),
        NEW_MODULE_ATTRIBUTE to pathString(newDir),
        "oldArtifactId" to sourceIdentity.artifactId,
        "newArtifactId" to targetArtifact,
        "artifactIntent" to if (sourceIdentity.artifactId == targetArtifact) "PRESERVE" else "CALLER_EXPLICIT_CHANGE",
        "destinationNoFollowFact" to "${pathString(newDir)}:ABSENT",
        "destinationAbsenceFactHash" to hashStrings(listOf(snapshot.hash, pathString(newDir), "NOFOLLOW_ABSENT")),
        BASELINE_SNAPSHOT_HASH to stage.baselineSnapshot.hash,
        STAGED_CANDIDATE_SNAPSHOT_HASH to stage.stagedCandidateSnapshotHash,
        STAGED_SNAPSHOT_HASH to stage.stagedSnapshot.hash,
        BASELINE_TRACKED_HASH to stage.baselineTrackedHash,
        STAGED_TRACKED_HASH to stage.stagedTrackedHash,
        BASELINE_MANIFEST_HASH to stage.baselineManifestHash,
        STAGED_MANIFEST_HASH to stage.stagedManifestHash,
        BASELINE_REACTOR_HASH to reactorHash(stage.baselineSnapshot, stage.baselineModel),
        STAGED_REACTOR_HASH to reactorHash(stage.stagedSnapshot, stage.stagedModel),
        BASELINE_MAVEN_DIAGNOSTICS_HASH to hashStrings(stage.baselineMavenDiagnostics),
        STAGED_MAVEN_DIAGNOSTICS_HASH to hashStrings(stage.stagedMavenDiagnostics),
        BASELINE_JDT_DIAGNOSTICS_HASH to hashStrings(stage.baselineJdtCanonical),
        STAGED_JDT_DIAGNOSTICS_HASH to hashStrings(stage.stagedJdtCanonical),
        BASELINE_DIAGNOSTICS_HASH to stage.baselineDiagnosticsHash,
        STAGED_DIAGNOSTICS_HASH to stage.stagedDiagnosticsHash,
        "stagedReactorFact" to "AVAILABLE:${MAVEN_PROVIDER}:offline",
        "diagnosticFact" to "MAVEN_AND_AUTHORITATIVE_JDT_EXACT",
        "rawPomInventoryHash" to hashStrings(rawPoms.sortedBy { pathString(it.path) }.map { "${pathString(it.path)}:${it.sha256}" }),
        "rawOriginHash" to hashStrings(origins.sortedBy { "${pathString(it.pom.path)}:${it.role}" }.map { origin ->
            "${origin.role}:${pathString(origin.pom.path)}:${formatRange(origin.value.range)}:${origin.value.text}:${origin.proof}"
        }),
        "classpathEvidenceHash" to classpathEvidenceHash(snapshot.classpathEvidence),
        "candidateInventoryHash" to trackedInventoryHash(snapshot),
        EDIT_HASH to workspaceEditHash(normalizedEdit),
    )

    fun authorityFileEvidence(
        snapshot: ProjectSnapshot,
        origins: List<OriginRecord>,
    ): List<OperationAuthorityFileEvidence> {
        val originsByPath = origins.groupBy { it.pom.path }
        return snapshot.trackedFiles.sortedBy { pathString(it.path) }.map { source ->
            val records = originsByPath[source.path.normalize()].orEmpty().sortedBy(OriginRecord::role)
            OperationAuthorityFileEvidence(
                kind = when (source.languageId) {
                    "maven-pom" -> "MAVEN_REACTOR_RAW_POM"
                    "java" -> "MAVEN_REACTOR_JAVA_SOURCE"
                    else -> "MAVEN_REACTOR_AUXILIARY_FILE"
                },
                path = source.path.normalize(),
                expectedContentSha256 = sha256(source.content.toByteArray(Charsets.UTF_8)),
                attributes = buildMap {
                    put("languageId", source.languageId)
                    put("byteLength", source.content.toByteArray(Charsets.UTF_8).size.toString())
                    if (records.isNotEmpty()) {
                        put("originRoles", records.joinToString(",", transform = OriginRecord::role))
                        put("originRanges", records.joinToString(",") { formatRange(it.value.range) })
                        put("originLiteralsHash", hashStrings(records.map { it.value.text }))
                        put("effectiveProofHash", hashStrings(records.map { it.proof }))
                    }
                },
            )
        }
    }

    fun authorityEvidenceHash(
        attributes: Map<String, String>,
        files: List<OperationAuthorityFileEvidence>,
        classpath: List<ClasspathEvidence>,
    ): String = hashStrings(buildList {
        attributes.toSortedMap().forEach { (key, value) -> add("attribute:$key=$value") }
        files.sortedBy { pathString(it.path) }.forEach { file ->
            add("file:${file.kind}:${pathString(file.path)}:${file.expectedContentSha256}")
            file.attributes.forEach { (key, value) -> add("fileAttribute:${pathString(file.path)}:$key=$value") }
        }
        classpath.sortedWith(compareBy<ClasspathEvidence> { pathString(it.path) }.thenBy { it.kind.name }).forEach {
            add("classpath:${pathString(it.path)}:${it.kind.name}:${it.fingerprint}")
        }
    })

    fun moduleIdentity(module: BuildModule): ModuleIdentity {
        val group = module.attributes["java.maven.groupId"]
        val artifact = module.attributes["java.maven.artifactId"]
        val version = module.attributes["java.maven.version"]
        val packaging = module.attributes["java.maven.packaging"]
        if (group.isNullOrBlank() || artifact.isNullOrBlank() || version.isNullOrBlank() || packaging.isNullOrBlank()) {
            refuse(SOURCE_UNRECOGNIZED, "The effective source module coordinate is incomplete")
        }
        return ModuleIdentity(group, artifact, version, packaging)
    }

    fun relativeModuleRoot(snapshot: ProjectSnapshot, module: BuildModule): Path {
        val root = module.root.normalize()
        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        return when {
            root.isAbsolute && root.toAbsolutePath().normalize().startsWith(workspace) ->
                workspace.relativize(root.toAbsolutePath().normalize()).normalize()
            !root.isAbsolute -> root
            else -> refuse(SOURCE_UNRECOGNIZED, "Effective Maven module root escapes the workspace")
        }
    }

    private fun buildPath(snapshot: ProjectSnapshot, path: Path): String {
        val normalized = path.normalize()
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        return when {
            normalized.isAbsolute && normalized.toAbsolutePath().normalize().startsWith(root) ->
                pathString(root.relativize(normalized.toAbsolutePath().normalize()))
            else -> pathString(normalized)
        }
    }

    private fun normalizeWorkspaceValue(snapshot: ProjectSnapshot, value: String): String {
        val root = snapshot.workspace.root.toAbsolutePath().normalize().toString().replace('\\', '/')
        return value.replace('\\', '/').replace(root, "<workspace>")
    }

    fun mavenDiagnostics(diagnostics: List<BuildModelDiagnostic>): List<Diagnostic> = diagnostics.map { diagnostic ->
        Diagnostic(
            message = diagnostic.message,
            severity = diagnostic.severity,
            code = diagnostic.code,
            evidence = DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.PROJECT_STRUCTURE,
            details = DiagnosticDetails(mapOf(
                "providerId" to MAVEN_PROVIDER,
                "moduleId" to diagnostic.moduleId.orEmpty(),
            )),
        )
    }

    fun canonicalMavenDiagnostics(diagnostics: List<BuildModelDiagnostic>): List<String> = diagnostics.map {
        listOf(it.severity.name, it.code, it.moduleId.orEmpty(), it.message).joinToString("\u0000")
    }.sorted()

    fun canonicalDiagnostics(snapshot: ProjectSnapshot, diagnostics: List<Diagnostic>): List<String> = diagnostics.map { diagnostic ->
        val location = diagnostic.location
        listOf(
            diagnostic.severity.name,
            diagnostic.code.orEmpty(),
            diagnostic.evidence?.name.orEmpty(),
            diagnostic.category?.name.orEmpty(),
            diagnostic.locationPrecision.name,
            location?.path?.let { buildPath(snapshot, it) }.orEmpty(),
            location?.range?.let(::formatRange).orEmpty(),
            diagnostic.details.fields.entries.joinToString("|") { (key, value) ->
                "$key=${normalizeWorkspaceValue(snapshot, value)}"
            },
            normalizeWorkspaceValue(snapshot, diagnostic.message),
        ).joinToString("\u0000")
    }.sorted()

    fun exactDiagnosticsHash(diagnostics: List<Diagnostic>): String = hashStrings(
        diagnostics.map { diagnostic ->
            val location = diagnostic.location
            listOf(
                diagnostic.severity.name,
                diagnostic.code.orEmpty(),
                diagnostic.evidence?.name.orEmpty(),
                diagnostic.category?.name.orEmpty(),
                diagnostic.locationPrecision.name,
                location?.path?.normalize()?.toString()?.replace('\\', '/').orEmpty(),
                location?.range?.let(::formatRange).orEmpty(),
                diagnostic.details.fields.entries.joinToString("|") { (key, value) -> "$key=$value" },
                diagnostic.message,
            ).joinToString("\u0000")
        }.sorted(),
    )

    fun trackedInventoryHash(snapshot: ProjectSnapshot): String = hashStrings(
        snapshot.trackedFiles.sortedBy { pathString(it.path) }.flatMap { source ->
            listOf(pathString(source.path), source.languageId, sha256(source.content.toByteArray(Charsets.UTF_8)))
        },
    )

    private fun workspaceEditHash(edit: WorkspaceEdit): String = hashStrings(edit.edits.flatMap { entry ->
        when (entry) {
            is FileEdit.Modify -> listOf("Modify", pathString(entry.path)) + entry.textEdits.flatMap { text ->
                listOf(formatRange(text.range), text.newText)
            }
            is FileEdit.Rename -> listOf("Rename", pathString(entry.path), pathString(entry.newPath))
            is FileEdit.Create -> listOf("Create", pathString(entry.path), entry.overwrite.toString(), entry.content)
            is FileEdit.Delete -> listOf("Delete", pathString(entry.path))
        }
    })

    private fun classpathEvidenceHash(evidence: List<ClasspathEvidence>): String = hashStrings(
        evidence.sortedWith(compareBy<ClasspathEvidence> { pathString(it.path) }.thenBy { it.kind.name }).flatMap {
            listOf(pathString(it.path), it.kind.name, it.fingerprint)
        },
    )
}
