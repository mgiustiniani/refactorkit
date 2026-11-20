package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.AuthoritativeDiagnosticsEvaluation
import org.refactorkit.core.AuthoritativeDiagnosticsProvider
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Creates one direct child of an explicitly selected Maven aggregator POM. */
class JavaCreateMavenModulePlanner {
    companion object {
        const val OPERATION = "java.createMavenModule"
        private const val INVALID_NAME = "java.createMavenModule.invalidModuleName"
        private const val DESTINATION_EXISTS = "java.createMavenModule.destinationExists"
        private const val DESCRIPTOR_UNAVAILABLE = "java.createMavenModule.parentDescriptorUnavailable"
        private const val COORDINATES_UNAVAILABLE = "java.createMavenModule.parentCoordinatesUnavailable"
        private const val MODULES_UNAVAILABLE = "java.createMavenModule.modulesUnavailable"
        const val DIAGNOSTICS_GATE_ID = "java-create-maven-module-staged-reactor-v1"
        private const val LEASE_KIND = "java-create-maven-module-v1"
        private val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val WINDOWS_DEVICE_NAME = Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")
    }

    fun preview(snapshot: ProjectSnapshot, moduleName: String, parentPomPath: Path): PatchPlan {
        if (!isSafeModuleSegment(moduleName)) return refused(
            snapshot, INVALID_NAME,
            "Maven module name must be one safe path segment of at most 64 ASCII letters, digits, dot, underscore, or hyphen",
        )

        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val parentPath = parentPomPath.normalize()
        if (parentPath.isAbsolute || parentPath.startsWith("..") || parentPath.fileName?.toString() != "pom.xml") {
            return refused(snapshot, DESCRIPTOR_UNAVAILABLE, "Parent POM path must be a safe workspace-relative pom.xml path")
        }
        val parentFile = root.resolve(parentPath).normalize()
        if (!parentFile.startsWith(root) || Files.isSymbolicLink(parentFile) ||
            !Files.isRegularFile(parentFile, LinkOption.NOFOLLOW_LINKS)
        ) {
            return refused(snapshot, DESCRIPTOR_UNAVAILABLE, "Parent POM is not a regular no-follow file inside the workspace")
        }
        val parent = snapshot.auxiliaryFiles.singleOrNull {
            it.languageId == "maven-pom" && it.path.normalize() == parentPath
        } ?: return refused(snapshot, DESCRIPTOR_UNAVAILABLE, "Parent POM is not represented by the immutable snapshot: $parentPath")

        val parentDirectory = parentPath.parent ?: Path.of("")
        val moduleDirectory = parentDirectory.resolve(moduleName).normalize()
        val newPomPath = moduleDirectory.resolve("pom.xml").normalize()
        if (!isWorkspaceRelative(moduleDirectory) || !isWorkspaceRelative(newPomPath) ||
            !root.resolve(newPomPath).normalize().startsWith(root)
        ) {
            return refused(snapshot, INVALID_NAME, "Generated Maven module paths must remain beneath the selected parent POM")
        }
        val moduleDirectoryFile = root.resolve(moduleDirectory).normalize()
        if (Files.exists(moduleDirectoryFile, LinkOption.NOFOLLOW_LINKS) || snapshot.trackedFiles.any {
                it.path.normalize() == newPomPath || it.path.normalize().startsWith(moduleDirectory)
            }
        ) {
            return refused(snapshot, DESTINATION_EXISTS, "Maven module destination already exists: $moduleDirectory")
        }

        val parsed = runCatching { WoodstoxXmlTree.parse(parent.content) }.getOrElse {
            return refused(snapshot, DESCRIPTOR_UNAVAILABLE, "Parent POM is malformed or cannot be read safely")
        }
        val project = parsed.children.singleOrNull { it.localName == "project" }
            ?: return refused(snapshot, DESCRIPTOR_UNAVAILABLE, "Parent POM must contain one project root")
        val modules = project.children.singleOrNull { it.localName == "modules" }
            ?: return refused(snapshot, MODULES_UNAVAILABLE, "Parent POM must declare one direct modules container")
        val directModuleNames = modules.children.filter { it.localName == "module" }.map { node ->
            if (node.children.isNotEmpty()) return refused(snapshot, DESCRIPTOR_UNAVAILABLE, "Module declarations must contain literal text only")
            parent.content.substring(node.contentStart, node.contentEnd).trim()
        }
        if (directModuleNames.any { it.isBlank() || "\${" in it } || directModuleNames.size != directModuleNames.toSet().size) {
            return refused(snapshot, DESCRIPTOR_UNAVAILABLE, "Parent module declarations are dynamic, blank, or duplicated")
        }
        if (moduleName in directModuleNames) {
            return refused(snapshot, DESTINATION_EXISTS, "Parent POM already declares Maven module '$moduleName'")
        }

        val model = snapshot.buildModels.singleOrNull {
            it.providerId == "maven-effective-v1" && it.status == org.refactorkit.core.BuildModelStatus.AVAILABLE
        } ?: return refused(snapshot, COORDINATES_UNAVAILABLE, "An available effective Maven reactor is required")
        val parentModule = model.modules.singleOrNull {
            it.attributes["java.maven.pomPath"]?.let(Path::of)?.normalize() == parentPath
        }
        fun directCoordinate(name: String): String? {
            val node = project.children.singleOrNull { it.localName == name } ?: return null
            if (node.children.isNotEmpty()) return null
            return decodeXmlText(parent.content.substring(node.contentStart, node.contentEnd).trim())
        }
        val declaredGroupId = directCoordinate("groupId")
        val declaredArtifactId = directCoordinate("artifactId")
        val declaredVersion = directCoordinate("version")
        val groupId = parentModule?.attributes?.get("java.maven.groupId") ?: declaredGroupId
        val artifactId = parentModule?.attributes?.get("java.maven.artifactId") ?: declaredArtifactId
        val version = parentModule?.attributes?.get("java.maven.version") ?: declaredVersion
        if (listOf(groupId, artifactId, version).any { it.isNullOrBlank() || "\${" in it }) {
            return refused(snapshot, COORDINATES_UNAVAILABLE, "Selected parent POM has incomplete or dynamic declared/effective coordinates")
        }
        if (declaredArtifactId == null || declaredArtifactId != artifactId) {
            return refused(snapshot, COORDINATES_UNAVAILABLE, "Selected parent POM artifactId does not match its effective module coordinates")
        }

        val insertionOffset = moduleInsertionOffset(parent.content, modules.contentStart, modules.contentEnd)
            ?: return refused(snapshot, MODULES_UNAVAILABLE, "Parent modules container has no safe insertion range")
        val insertion = moduleInsertion(parent.content, insertionOffset, moduleName, modules.contentStart)
        val position = TextEdits.positionForOffset(parent.content, insertionOffset)
        val moduleEdit = TextEdit(SourceRange(position, position), insertion)
        val childPom = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>${escapeXml(groupId!!)}</groupId>
                <artifactId>${escapeXml(artifactId!!)}</artifactId>
                <version>${escapeXml(version!!)}</version>
                <relativePath>../pom.xml</relativePath>
              </parent>
              <artifactId>${escapeXml(moduleName)}</artifactId>
            </project>
        """.trimIndent() + "\n"
        val edits = WorkspaceEdit(listOf(
            FileEdit.Create(newPomPath, childPom),
            FileEdit.Modify(parentPath, listOf(moduleEdit)),
        ))
        val normalizedEdit = runCatching { WorkspaceEditSimulator.normalize(edits) }.getOrElse {
            return refused(snapshot, DESCRIPTOR_UNAVAILABLE, "Maven Add Module edit cannot be normalized safely")
        }
        val simulated = runCatching { WorkspaceEditSimulator.apply(snapshot, normalizedEdit) }.getOrElse {
            return refused(snapshot, DESCRIPTOR_UNAVAILABLE, "Maven Add Module edit cannot be simulated safely")
        }
        val stage = try {
            JavaCreateMavenModuleStaging.stage(snapshot, simulated, normalizedEdit, moduleName, parentPath,
                newPomPath, groupId!!, artifactId!!, version!!, directModuleNames)
        } catch (failure: Exception) {
            return refused(snapshot, DESCRIPTOR_UNAVAILABLE,
                "Offline Maven Add Module staging failed (${failure::class.simpleName ?: "failure"}): ${failure.message.orEmpty()}")
        }
        val lease = JavaCreateMavenModuleStaging.lease(snapshot, simulated, normalizedEdit, moduleName,
            parentPath, groupId!!, artifactId!!, version!!, stage)
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Create Maven module '$moduleName' beneath $parentPath using ${groupId}:${artifactId}:${version} as its parent coordinates",
            affectedFiles = normalizedEdit.affectedFiles(),
            workspaceEdit = normalizedEdit,
            diagnosticsBefore = stage.baselineDiagnostics,
            diagnosticsAfterPreview = stage.stagedDiagnostics,
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
            warnings = listOf("Generated module contains a Maven POM; add Java sources as needed."),
            authorityLease = lease,
        )
    }

    fun diagnosticsGate(plan: PatchPlan): DiagnosticsGate = JavaCreateMavenModuleStaging.diagnosticsGate(plan)

    private fun isSafeModuleSegment(name: String): Boolean =
        SAFE_SEGMENT.matches(name) && !name.endsWith('.') && name !in setOf(".", "..") &&
            !WINDOWS_DEVICE_NAME.matches(name)

    private fun isWorkspaceRelative(path: Path): Boolean = !path.isAbsolute && !path.normalize().startsWith("..")

    private fun moduleInsertionOffset(content: String, start: Int, end: Int): Int? {
        if (start !in 0..content.length || end !in start..content.length) return null
        val inner = content.substring(start, end)
        val trailingWhitespace = inner.takeLastWhile(Char::isWhitespace)
        val offset = end - trailingWhitespace.length
        return offset.takeIf { it in start..end }
    }

    private fun moduleInsertion(content: String, offset: Int, moduleName: String, containerStart: Int): String {
        val before = content.substring(containerStart, offset)
        val lineStart = before.lastIndexOf('\n') + 1
        val linePrefix = before.substring(lineStart)
        val trailingIndent = linePrefix.takeWhile(Char::isWhitespace)
        val containerLineStart = content.lastIndexOf('\n', containerStart - 1).let { it + 1 }
        val containerIndent = content.substring(containerLineStart, containerStart).takeWhile(Char::isWhitespace)
        return if (linePrefix.isBlank() && lineStart > 0) {
            "<module>${escapeXml(moduleName)}</module>\n$containerIndent"
        } else {
            "\n${containerIndent}  <module>${escapeXml(moduleName)}</module>\n$containerIndent"
        }
    }

    private fun decodeXmlText(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("&#(x[0-9A-Fa-f]+|[0-9]+);")) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith('x')) token.drop(1).toInt(16) else token.toInt()
            String(Character.toChars(codePoint))
        }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun refused(snapshot: ProjectSnapshot, code: String, reason: String) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
        refusalCode = code,
    )
}
