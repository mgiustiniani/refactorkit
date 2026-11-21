package org.refactorkit.java

import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModule
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaRenameMavenModuleContract.AMBIGUOUS_ORIGIN
import org.refactorkit.java.JavaRenameMavenModuleContract.ARTIFACT_ID
import org.refactorkit.java.JavaRenameMavenModuleContract.DESCRIPTOR_UNAVAILABLE
import org.refactorkit.java.JavaRenameMavenModuleContract.DESTINATION_UNRECOGNIZED
import org.refactorkit.java.JavaRenameMavenModuleContract.DIAGNOSTICS_REGRESSION
import org.refactorkit.java.JavaRenameMavenModuleContract.DependencyOrigin
import org.refactorkit.java.JavaRenameMavenModuleContract.LEASE_KIND
import org.refactorkit.java.JavaRenameMavenModuleContract.MAVEN_PROVIDER
import org.refactorkit.java.JavaRenameMavenModuleContract.ModuleIdentity
import org.refactorkit.java.JavaRenameMavenModuleContract.OPERATION
import org.refactorkit.java.JavaRenameMavenModuleContract.OriginRecord
import org.refactorkit.java.JavaRenameMavenModuleContract.PROPERTY_MANAGED
import org.refactorkit.java.JavaRenameMavenModuleContract.ROOT_POM
import org.refactorkit.java.JavaRenameMavenModuleContract.RawPom
import org.refactorkit.java.JavaRenameMavenModuleContract.RawValue
import org.refactorkit.java.JavaRenameMavenModuleContract.SOURCE_UNRECOGNIZED
import org.refactorkit.java.JavaRenameMavenModuleContract.availableMavenModel
import org.refactorkit.java.JavaRenameMavenModuleContract.moduleDirectory
import org.refactorkit.java.JavaRenameMavenModuleContract.moduleId
import org.refactorkit.java.JavaRenameMavenModuleContract.pathString
import org.refactorkit.java.JavaRenameMavenModuleContract.refuse
import org.refactorkit.java.JavaRenameMavenModuleContract.sha256
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Proves raw-POM/effective origins and constructs one bounded ordered module-rename preview. */
internal object JavaRenameMavenModulePlanning {
    fun previewAuthorized(
        snapshot: ProjectSnapshot,
        oldModuleDir: String,
        newModuleDir: String,
        newArtifactId: String?,
    ): PatchPlan {
        val oldDir = moduleDirectory(oldModuleDir) ?: refuse(
            SOURCE_UNRECOGNIZED,
            "Source module directory must be a safe literal workspace-relative path: '$oldModuleDir'",
        )
        val newDir = moduleDirectory(newModuleDir) ?: refuse(
            DESTINATION_UNRECOGNIZED,
            "Destination module directory must be a safe literal workspace-relative path: '$newModuleDir'",
        )
        if (oldDir.startsWith(newDir) || newDir.startsWith(oldDir)) refuse(
            DESTINATION_UNRECOGNIZED,
            "Source and destination module directories must be disjoint",
        )
        if (newArtifactId != null && newArtifactId.isBlank()) refuse(
            DESTINATION_UNRECOGNIZED,
            "A changed artifact coordinate requires nonblank caller-explicit artifactId intent; blank inference is refused",
        )

        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            refuse(SOURCE_UNRECOGNIZED, "The snapshot workspace root is not a no-follow regular directory")
        }
        requireDirectoryAncestors(root, oldDir, SOURCE_UNRECOGNIZED)
        requireDirectoryAncestors(root, newDir, DESTINATION_UNRECOGNIZED)
        if (!Files.notExists(root.resolve(newDir), LinkOption.NOFOLLOW_LINKS)) {
            refuse(DESTINATION_UNRECOGNIZED, "Destination module directory '$newDir' is not absent without following links")
        }
        if (snapshot.trackedFiles.any { it.path.normalize().startsWith(newDir) }) {
            refuse(DESTINATION_UNRECOGNIZED, "Destination module directory '$newDir' is already represented by the snapshot")
        }

        val model = availableMavenModel(snapshot)
        val rawPoms = rawReactorPoms(snapshot, model)
        val rootPom = rawPoms.getValue(ROOT_POM)
        val directModules = rootDirectModules(rootPom)
        val modelRoots = model.modules.map { JavaRenameMavenModuleEvidence.relativeModuleRoot(snapshot, it) }
        if (modelRoots.any { it.startsWith(newDir) || newDir.startsWith(it) }) {
            refuse(DESTINATION_UNRECOGNIZED, "Destination overlaps an effective Maven module: $newDir")
        }
        if (directModules.size != modelRoots.size ||
            directModules.mapTo(linkedSetOf(), RawValue::text) != modelRoots.mapTo(linkedSetOf(), ::pathString)
        ) {
            refuse(
                AMBIGUOUS_ORIGIN,
                "Root direct module declarations do not map one-to-one to the effective reactor children",
            )
        }
        if (directModules.map(RawValue::text).toSet().size != directModules.size) {
            refuse(AMBIGUOUS_ORIGIN, "Root direct module declarations are duplicate or ambiguous")
        }
        val rootModuleOrigin = directModules.singleOrNull { it.text == pathString(oldDir) }
            ?: refuse(SOURCE_UNRECOGNIZED, "Source is not exactly one direct literal root module declaration: $oldDir")
        if (directModules.any { it.text == pathString(newDir) }) {
            refuse(DESTINATION_UNRECOGNIZED, "Destination already has a direct root module declaration: $newDir")
        }

        val sourceModule = model.modules.singleOrNull { module ->
            module.id == moduleId(pathString(oldDir)) && JavaRenameMavenModuleEvidence.relativeModuleRoot(snapshot, module) == oldDir
        } ?: refuse(
            SOURCE_UNRECOGNIZED,
            "Source direct child is not exactly one effective Maven reactor module: $oldDir",
        )
        val sourcePomPath = oldDir.resolve("pom.xml")
        if (sourceModule.attributes["java.maven.pomPath"] != pathString(sourcePomPath)) {
            refuse(DESCRIPTOR_UNAVAILABLE, "The effective source module does not prove its exact child POM: $sourcePomPath")
        }
        if (sourceModule.attributes["java.maven.packaging"] != "jar") {
            refuse(SOURCE_UNRECOGNIZED, "The bounded source module must have effective JAR packaging")
        }
        val sourcePom = rawPoms[sourcePomPath]
            ?: refuse(DESCRIPTOR_UNAVAILABLE, "The exact source child POM is unavailable: $sourcePomPath")
        val projectArtifactOrigin = exactDirectValue(sourcePom, "artifactId", "source project artifact")
        if (projectArtifactOrigin.isInterpolated || '&' in projectArtifactOrigin.text) {
            refuse(PROPERTY_MANAGED, "The source project's own artifact origin is property-managed and is not writable")
        }
        val sourceIdentity = JavaRenameMavenModuleEvidence.moduleIdentity(sourceModule)
        if (projectArtifactOrigin.text != sourceIdentity.artifactId) {
            refuse(
                AMBIGUOUS_ORIGIN,
                "The source project's own artifact origin does not equal its effective module identity",
            )
        }
        if (!ARTIFACT_ID.matches(sourceIdentity.artifactId)) {
            refuse(SOURCE_UNRECOGNIZED, "The effective source artifactId is outside the bounded literal form")
        }

        val targetArtifact = newArtifactId ?: sourceIdentity.artifactId
        if (!ARTIFACT_ID.matches(targetArtifact)) {
            refuse(
                DESTINATION_UNRECOGNIZED,
                "A non-null changed artifactId must be explicit and valid: '$targetArtifact'",
            )
        }
        val artifactChanges = targetArtifact != sourceIdentity.artifactId
        if (artifactChanges && (oldDir.nameCount > 1 || newDir.nameCount > 1)) {
            refuse(DESTINATION_UNRECOGNIZED, "Nested directory moves preserve the Maven artifact coordinate")
        }
        if (artifactChanges && model.modules.any { module ->
                module !== sourceModule && module.attributes["java.maven.groupId"] == sourceIdentity.groupId &&
                    module.attributes["java.maven.artifactId"] == targetArtifact &&
                    module.attributes["java.maven.version"] == sourceIdentity.version
            }) {
            refuse(DESTINATION_UNRECOGNIZED, "The requested Maven artifact coordinate already belongs to another reactor module")
        }

        val sourceTrackedFiles = JavaRenameMavenModuleWorkspace.exactSourceTrackedFiles(snapshot, oldDir, root)
        val dependencyOrigins = if (artifactChanges) {
            directConsumerOrigins(model, rawPoms, sourceModule, sourceIdentity)
        } else {
            emptyList()
        }

        val parentPathEdit = parentPathEdit(sourcePom, oldDir, newDir)
        val sourcePomEdits = buildList {
            if (artifactChanges) add(projectArtifactOrigin.replaceWith(targetArtifact))
            parentPathEdit?.let { (origin, replacement) -> add(origin.replaceWith(replacement)) }
        }
        val modifications = buildList {
            if (sourcePomEdits.isNotEmpty()) add(FileEdit.Modify(sourcePomPath, sourcePomEdits))
            add(FileEdit.Modify(ROOT_POM, listOf(rootModuleOrigin.replaceWith(pathString(newDir)))))
            dependencyOrigins.sortedBy { pathString(it.pom.path) }.forEach { origin ->
                add(FileEdit.Modify(origin.pom.path, listOf(origin.artifact.replaceWith(targetArtifact))))
            }
        }
        val renames = sourceTrackedFiles.map { file ->
            val destination = newDir.resolve(oldDir.relativize(file.path.normalize())).normalize()
            FileEdit.Rename(file.path.normalize(), destination)
        }
        val workspaceEdit = WorkspaceEdit(modifications + renames)
        val normalizedEdit = try {
            WorkspaceEditSimulator.normalize(workspaceEdit)
        } catch (failure: Exception) {
            refuse(AMBIGUOUS_ORIGIN, "The ordered Maven module-rename edit is not normalizable: ${failure.message}")
        }
        if (normalizedEdit != workspaceEdit || normalizedEdit.edits.take(modifications.size) != modifications ||
            normalizedEdit.edits.drop(modifications.size) != renames
        ) {
            refuse(AMBIGUOUS_ORIGIN, "Modify-before-rename order could not be preserved exactly")
        }
        val simulated = try {
            WorkspaceEditSimulator.apply(snapshot, normalizedEdit)
        } catch (failure: Exception) {
            refuse(AMBIGUOUS_ORIGIN, "The ordered Maven module-rename edit cannot be simulated: ${failure.message}")
        }
        val stage = JavaRenameMavenModuleStaging.stageAndAttest(snapshot, simulated, normalizedEdit, oldDir)
        if (JavaRenameMavenModuleEvidence.trackedInventoryHash(simulated) != stage.stagedTrackedHash) {
            refuse(AMBIGUOUS_ORIGIN, "The immutable simulator image differs from the full staged workspace image")
        }
        JavaRenameMavenModuleEvidence.validateStagedReactor(
            baseline = JavaRenameMavenModuleEvidence.reactorView(snapshot, model),
            staged = JavaRenameMavenModuleEvidence.reactorView(stage.stagedSnapshot, stage.stagedModel),
            oldModule = oldDir,
            newModule = newDir,
            sourceIdentity = sourceIdentity,
            targetArtifact = targetArtifact,
            retainedEmptyOutputs = stage.retainedEmptyOutputs.toSet(),
            baselineConsumers = effectiveMainConsumers(model, sourceModule.id),
            stagedRootModules = rootDirectModules(rawPom(stage.stagedSnapshot, ROOT_POM)).map(RawValue::text),
            expectedRootModules = directModules.map { value ->
                if (value.text == pathString(oldDir)) pathString(newDir) else value.text
            },
        )
        if (stage.baselineMavenDiagnostics != stage.stagedMavenDiagnostics) {
            refuse(DIAGNOSTICS_REGRESSION, "Staged Maven diagnostics differ from the exact canonical baseline")
        }
        if (stage.baselineJdtCanonical != stage.stagedJdtCanonical) {
            refuse(DIAGNOSTICS_REGRESSION, "Staged authoritative JDT diagnostics differ from the exact canonical baseline")
        }

        val originRecords = buildList {
            add(OriginRecord("child-project-artifact", sourcePom, projectArtifactOrigin, sourceIdentity.coordinate))
            add(OriginRecord("root-direct-module", rootPom, rootModuleOrigin, "direct-child:${pathString(oldDir)}"))
            parentPathEdit?.let { (origin, _) ->
                add(OriginRecord("child-parent-relative-path", sourcePom, origin, pathString(ROOT_POM)))
            }
            dependencyOrigins.forEach { origin ->
                add(OriginRecord("direct-consumer-dependency", origin.pom, origin.artifact, origin.effectiveProof))
            }
        }
        val leaseAttributes = JavaRenameMavenModuleEvidence.leaseAttributes(
            snapshot = snapshot,
            normalizedEdit = normalizedEdit,
            oldDir = oldDir,
            newDir = newDir,
            sourceIdentity = sourceIdentity,
            targetArtifact = targetArtifact,
            rawPoms = rawPoms.values,
            origins = originRecords,
            stage = stage,
        )
        val requiredFiles = JavaRenameMavenModuleEvidence.authorityFileEvidence(snapshot, originRecords)
        val evidenceHash = JavaRenameMavenModuleEvidence.authorityEvidenceHash(leaseAttributes, requiredFiles, snapshot.classpathEvidence)
        val lease = OperationAuthorityLease(
            kind = LEASE_KIND,
            operation = OPERATION,
            snapshotHash = snapshot.hash,
            evidenceHash = evidenceHash,
            workspaceEditSha256 = WorkspaceEditIdentity.sha256(normalizedEdit),
            requiredClasspathEvidence = snapshot.classpathEvidence,
            requiredFileEvidence = requiredFiles,
            attributes = leaseAttributes,
        )
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = if (artifactChanges) {
                "Rename direct Maven module directory '$oldDir' to '$newDir' and explicitly change artifact " +
                    "'${sourceIdentity.artifactId}' to '$targetArtifact'; update ${dependencyOrigins.size} proven " +
                    "direct consumer dependency origin(s) and move ${renames.size} regular file(s)."
            } else {
                "Rename direct Maven module directory '$oldDir' to '$newDir' while preserving artifact " +
                    "'${sourceIdentity.artifactId}'; no child or dependency coordinate edit is authorized and " +
                    "${renames.size} regular file(s) move."
            },
            affectedFiles = normalizedEdit.affectedFiles(),
            workspaceEdit = normalizedEdit,
            diagnosticsBefore = stage.baselineJdt,
            diagnosticsAfterPreview = stage.stagedJdt,
            warnings = listOf(
                if (parentPathEdit?.first?.text == "") {
                    "A local parent relativePath is inserted at a hash-bound XML boundary; Maven XML is never serialized."
                } else {
                    "Only hash-bound raw XML element-text ranges are replaced; Maven XML is never serialized."
                },
                "The complete staged reactor was rebuilt offline with build execution, settings credentials and network denied.",
            ),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.JDT_BINDING,
            authorityLease = lease,
        )
    }


    private fun requireDirectoryAncestors(root: Path, module: Path, refusalCode: String) {
        var directory = root
        for (segment in module.parent ?: return) {
            directory = directory.resolve(segment)
            if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                refuse(refusalCode, "The path to module '$module' contains a symbolic link or non-directory")
            }
        }
    }

    private fun parentPathEdit(pom: RawPom, oldDir: Path, newDir: Path): Pair<RawValue, String>? {
        if (oldDir.parent == newDir.parent) return null
        val parents = pom.project.children.filter { it.localName == "parent" }
        if (parents.isEmpty()) return null
        val parent = parents.singleOrNull()
            ?: refuse(AMBIGUOUS_ORIGIN, "The moved module has multiple parent origins in ${pom.path}")
        val paths = parent.children.filter { it.localName == "relativePath" }
        if (paths.size > 1) refuse(AMBIGUOUS_ORIGIN, "The moved module has multiple parent relative paths in ${pom.path}")
        val explicit = paths.singleOrNull()?.let { node ->
            if (pom.content.substring(node.contentStart, node.contentEnd).isBlank()) return null
            rawValue(pom, node, "source parent relative path")
        }
        if (explicit != null && (explicit.isInterpolated || '&' in explicit.text)) {
            refuse(PROPERTY_MANAGED, "The source parent relative path is property-managed in ${pom.path}")
        }
        val originalPath = Path.of(explicit?.text ?: "../pom.xml")
        if (originalPath.isAbsolute || oldDir.resolve(originalPath).normalize() != ROOT_POM) {
            refuse(AMBIGUOUS_ORIGIN, "The source parent relative path does not denote the root POM in ${pom.path}")
        }
        val replacement = pathString(newDir.relativize(ROOT_POM))
        if (explicit != null) return if (explicit.text == replacement) null else explicit to replacement

        val closing = parentClose.matchAt(pom.content, parent.contentEnd)
            ?: refuse(AMBIGUOUS_ORIGIN, "Cannot identify the parent closing tag in ${pom.path}")
        val tag = closing.groupValues[1] + "relativePath"
        val element = "<$tag>$replacement</$tag>"
        val lineStart = pom.content.lastIndexOf('\n', parent.contentEnd - 1) + 1
        val indent = pom.content.substring(lineStart, parent.contentEnd)
        val separateLine = lineStart > 0 && indent.all { it == ' ' || it == '\t' }
        val offset = if (separateLine) lineStart else parent.contentEnd
        val newline = if (lineStart > 1 && pom.content[lineStart - 2] == '\r') "\r\n" else "\n"
        val insertion = if (separateLine) "$indent  $element$newline" else element
        val position = TextEdits.positionForOffset(pom.content, offset)
        return RawValue("", offset, offset, SourceRange(position, position)) to insertion
    }

    private val parentClose = Regex("</([A-Za-z_][A-Za-z0-9_.-]*:)?parent\\s*>")

    private fun rawReactorPoms(snapshot: ProjectSnapshot, model: BuildModel): Map<Path, RawPom> {
        val roots = model.modules.map { module ->
            val root = JavaRenameMavenModuleEvidence.relativeModuleRoot(snapshot, module)
            if (moduleDirectory(pathString(root)) != root || module.id != moduleId(pathString(root))) {
                refuse(SOURCE_UNRECOGNIZED, "Every effective Maven module must have one literal workspace-relative directory")
            }
            if (module.attributes["java.maven.packaging"] == "pom") {
                refuse(SOURCE_UNRECOGNIZED, "Nested or aggregating child modules are outside the bounded rename")
            }
            root
        }
        if (roots.any { root -> roots.count { root.startsWith(it) } != 1 }) {
            refuse(AMBIGUOUS_ORIGIN, "Effective Maven child roots overlap or are duplicate")
        }
        val expected = (listOf(ROOT_POM) + roots.map { it.resolve("pom.xml") }).toSet()
        val actual = snapshot.auxiliaryFiles.filter { it.languageId == "maven-pom" }
        if (actual.map { it.path.normalize() }.toSet() != expected || actual.size != expected.size) {
            refuse(
                DESCRIPTOR_UNAVAILABLE,
                "The snapshot must contain exactly the root POM and one raw POM for every direct effective child",
            )
        }
        val result = actual.associate { source ->
            val pom = parseRawPom(source)
            pom.path to pom
        }
        val rootPom = result.getValue(ROOT_POM)
        if (exactDirectValue(rootPom, "packaging", "root packaging").text != "pom") {
            refuse(SOURCE_UNRECOGNIZED, "The workspace root POM is not the sole aggregator")
        }
        roots.forEach { root ->
            val child = result.getValue(root.resolve("pom.xml"))
            if (child.project.children.count { it.localName == "modules" } != 0) {
                refuse(SOURCE_UNRECOGNIZED, "Direct child '$root' is itself an aggregator")
            }
        }
        return result
    }

    private fun parseRawPom(source: SourceFile): RawPom {
        val document = try {
            WoodstoxXmlTree.parse(source.content)
        } catch (failure: Exception) {
            refuse(AMBIGUOUS_ORIGIN, "POM XML cannot be located losslessly at ${source.path}: ${failure.message}")
        }
        val project = document.children.singleOrNull { it.localName == "project" }
            ?: refuse(AMBIGUOUS_ORIGIN, "POM must contain exactly one lexical project root: ${source.path}")
        if (document.children.size != 1) {
            refuse(AMBIGUOUS_ORIGIN, "POM has ambiguous top-level XML elements: ${source.path}")
        }
        return RawPom(source.path.normalize(), source.content, sha256(source.content.toByteArray(Charsets.UTF_8)), project)
    }

    private fun rawPom(snapshot: ProjectSnapshot, path: Path): RawPom {
        val source = snapshot.auxiliaryFiles.singleOrNull {
            it.languageId == "maven-pom" && it.path.normalize() == path.normalize()
        } ?: refuse(DESCRIPTOR_UNAVAILABLE, "Staged reactor POM is unavailable: $path")
        return parseRawPom(source)
    }

    private fun rootDirectModules(rootPom: RawPom): List<RawValue> {
        val containers = rootPom.project.children.filter { it.localName == "modules" }
        if (containers.size != 1) refuse(AMBIGUOUS_ORIGIN, "Root POM must have exactly one direct modules container")
        val modules = containers.single().children.filter { it.localName == "module" }.map { node ->
            rawValue(rootPom, node, "root direct module")
        }
        if (modules.isEmpty() || modules.any { it.isInterpolated || '&' in it.text || moduleDirectory(it.text) == null }) {
            refuse(AMBIGUOUS_ORIGIN, "Every root module declaration must be one literal workspace-relative directory")
        }
        return modules
    }

    private fun exactDirectValue(pom: RawPom, name: String, role: String): RawValue {
        val candidates = pom.project.children.filter { it.localName == name }
        if (candidates.size != 1) {
            refuse(AMBIGUOUS_ORIGIN, "$role must have exactly one direct raw-POM origin in ${pom.path}")
        }
        return rawValue(pom, candidates.single(), role)
    }

    private fun rawValue(pom: RawPom, node: XmlNode, role: String): RawValue {
        if (node.children.isNotEmpty()) refuse(AMBIGUOUS_ORIGIN, "$role contains nested XML in ${pom.path}")
        val raw = pom.content.substring(node.contentStart, node.contentEnd)
        if ('<' in raw || '>' in raw) refuse(AMBIGUOUS_ORIGIN, "$role is not one lossless element-text range in ${pom.path}")
        val leading = raw.indexOfFirst { !it.isWhitespace() }
        val trailing = raw.indexOfLast { !it.isWhitespace() }
        if (leading < 0 || trailing < leading) refuse(AMBIGUOUS_ORIGIN, "$role is blank in ${pom.path}")
        val start = node.contentStart + leading
        val end = node.contentStart + trailing + 1
        val text = pom.content.substring(start, end)
        return RawValue(
            text = text,
            start = start,
            end = end,
            range = SourceRange(TextEdits.positionForOffset(pom.content, start), TextEdits.positionForOffset(pom.content, end)),
        )
    }

    private fun directConsumerOrigins(
        model: BuildModel,
        rawPoms: Map<Path, RawPom>,
        sourceModule: BuildModule,
        sourceIdentity: ModuleIdentity,
    ): List<DependencyOrigin> {
        val consumers = effectiveMainConsumers(model, sourceModule.id)
        val everyEdgeConsumer = model.modules.filter { module ->
            module.sourceSets.any { sourceSet ->
                sourceSet.moduleDependencies.any { it.targetModuleId == sourceModule.id }
            }
        }.mapTo(linkedSetOf()) { it.id }
        if (!consumers.mapTo(linkedSetOf()) { it.id }.containsAll(everyEdgeConsumer)) {
            refuse(
                AMBIGUOUS_ORIGIN,
                "An effective consumer edge is profile, inherited, test-only or otherwise non-direct",
            )
        }
        val origins = consumers.map { consumer ->
            val pomPath = consumer.attributes["java.maven.pomPath"]?.let(Path::of)?.normalize()
                ?: refuse(DESCRIPTOR_UNAVAILABLE, "Effective consumer '${consumer.id}' has no exact raw POM path")
            val pom = rawPoms[pomPath]
                ?: refuse(DESCRIPTOR_UNAVAILABLE, "Effective consumer raw POM is unavailable: $pomPath")
            val dependencyNodes = directDependencyNodes(pom)
            val potential = dependencyNodes.filter { node ->
                val artifacts = node.children.filter { it.localName == "artifactId" }
                artifacts.any { artifact ->
                    val value = rawValue(pom, artifact, "dependency artifact")
                    value.text == sourceIdentity.artifactId || value.isInterpolated
                }
            }
            if (potential.size != 1) {
                refuse(
                    AMBIGUOUS_ORIGIN,
                    "Effective consumer '${consumer.id}' has duplicate or ambiguous effective-to-raw origin mapping",
                )
            }
            val dependency = potential.single()
            val artifactNodes = dependency.children.filter { it.localName == "artifactId" }
            if (artifactNodes.size != 1) {
                refuse(AMBIGUOUS_ORIGIN, "Dependency artifact origin is duplicate or ambiguous in $pomPath")
            }
            val artifact = rawValue(pom, artifactNodes.single(), "dependency artifact")
            if (artifact.isInterpolated || '&' in artifact.text) {
                refuse(
                    PROPERTY_MANAGED,
                    "The property-managed edited dependency artifact origin in $pomPath is not writable",
                )
            }
            if (artifact.text != sourceIdentity.artifactId) {
                refuse(AMBIGUOUS_ORIGIN, "Dependency artifact origin does not equal the effective reactor identity")
            }
            val group = uniqueDependencyValue(pom, dependency, "groupId")
            val version = uniqueDependencyValue(pom, dependency, "version")
            val type = optionalDependencyValue(pom, dependency, "type")
            val classifier = optionalDependencyValue(pom, dependency, "classifier")
            val scope = optionalDependencyValue(pom, dependency, "scope")
            if (group.text != sourceIdentity.groupId || group.isInterpolated || '&' in group.text) {
                refuse(AMBIGUOUS_ORIGIN, "Dependency group origin does not literally prove the effective source coordinate")
            }
            if (version.text != sourceIdentity.version && !version.isInterpolated) {
                refuse(AMBIGUOUS_ORIGIN, "Dependency version origin does not prove the effective source coordinate")
            }
            if (type != null && (type.text != "jar" || type.isInterpolated)) {
                refuse(AMBIGUOUS_ORIGIN, "Dependency type origin is outside the bounded direct JAR edge")
            }
            if (classifier != null) {
                refuse(AMBIGUOUS_ORIGIN, "Classified dependency origins are outside the bounded direct edge")
            }
            if (scope != null && (scope.text != "compile" || scope.isInterpolated)) {
                refuse(AMBIGUOUS_ORIGIN, "Dependency scope origin does not prove a direct compile edge")
            }
            DependencyOrigin(
                pom = pom,
                artifact = artifact,
                effectiveProof = "${consumer.id}:main:COMPILE->${sourceModule.id}:${sourceIdentity.coordinate}",
            )
        }

        val consumerIds = consumers.mapTo(linkedSetOf()) { it.id }
        model.modules.filterNot { it.id in consumerIds }.forEach { module ->
            val pomPath = module.attributes["java.maven.pomPath"]?.let(Path::of)?.normalize() ?: return@forEach
            val pom = rawPoms[pomPath] ?: return@forEach
            val unexplained = directDependencyNodes(pom).any { dependency ->
                dependency.children.filter { it.localName == "artifactId" }.any { node ->
                    rawValue(pom, node, "dependency artifact").text == sourceIdentity.artifactId
                }
            }
            if (unexplained) {
                refuse(AMBIGUOUS_ORIGIN, "A literal source dependency origin has no matching direct effective consumer edge")
            }
        }
        return origins
    }

    private fun directDependencyNodes(pom: RawPom): List<XmlNode> {
        val containers = pom.project.children.filter { it.localName == "dependencies" }
        if (containers.size > 1) refuse(AMBIGUOUS_ORIGIN, "Direct dependency containers are ambiguous in ${pom.path}")
        return containers.singleOrNull()?.children?.filter { it.localName == "dependency" }.orEmpty()
    }

    private fun uniqueDependencyValue(pom: RawPom, dependency: XmlNode, name: String): RawValue {
        val nodes = dependency.children.filter { it.localName == name }
        if (nodes.size != 1) {
            refuse(
                AMBIGUOUS_ORIGIN,
                "Direct dependency $name is missing, inherited, dependency-managed, duplicate or ambiguous in ${pom.path}",
            )
        }
        return rawValue(pom, nodes.single(), "dependency $name")
    }

    private fun optionalDependencyValue(pom: RawPom, dependency: XmlNode, name: String): RawValue? {
        val nodes = dependency.children.filter { it.localName == name }
        if (nodes.size > 1) refuse(AMBIGUOUS_ORIGIN, "Direct dependency $name is duplicate or ambiguous in ${pom.path}")
        return nodes.singleOrNull()?.let { rawValue(pom, it, "dependency $name") }
    }

    private fun effectiveMainConsumers(model: BuildModel, sourceModuleId: String): List<BuildModule> =
        model.modules.filter { module ->
            module.sourceSets.singleOrNull { it.kind == SourceSetKind.MAIN }?.moduleDependencies?.any {
                it.targetModuleId == sourceModuleId && it.scope == DependencyScope.COMPILE
            } == true
        }.sortedBy { it.id }
}
