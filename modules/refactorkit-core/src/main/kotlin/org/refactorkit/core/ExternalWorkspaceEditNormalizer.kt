package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path

sealed interface ExternalFileEditProposal {
    val path: Path

    data class Modify(
        override val path: Path,
        val edits: List<TextEdit>,
        val documentVersion: Long? = null,
    ) : ExternalFileEditProposal

    data class Create(
        override val path: Path,
        val content: String,
    ) : ExternalFileEditProposal

    data class Delete(override val path: Path) : ExternalFileEditProposal

    data class Rename(
        override val path: Path,
        val newPath: Path,
    ) : ExternalFileEditProposal
}

data class ExternalWorkspaceEditProposal(
    val providerId: String,
    val providerVersion: String?,
    val edits: List<ExternalFileEditProposal>,
)

data class NormalizedExternalWorkspaceEdit(
    val workspaceEdit: WorkspaceEdit,
    val documentVersions: Map<Path, Long>,
    val providerId: String,
    val providerVersion: String?,
    val evidence: RefactoringEvidence = RefactoringEvidence.LANGUAGE_SERVER,
)

sealed interface ExternalWorkspaceEditNormalization {
    data class Accepted(val normalized: NormalizedExternalWorkspaceEdit) : ExternalWorkspaceEditNormalization
    data class Refused(val diagnostics: List<Diagnostic>) : ExternalWorkspaceEditNormalization
}

data class ExternalWorkspaceEditLimits(
    val maxFileEdits: Int = ProtocolLimits.MAX_EXTERNAL_FILE_EDITS,
    val maxTextEdits: Int = ProtocolLimits.MAX_EXTERNAL_TEXT_EDITS,
    val maxReplacementBytes: Long = ProtocolLimits.MAX_EXTERNAL_REPLACEMENT_BYTES,
) {
    init {
        require(maxFileEdits in 1..10_000)
        require(maxTextEdits in 1..100_000)
        require(maxReplacementBytes in 1..512L * 1024L * 1024L)
    }
}

/**
 * Converts an untrusted compiler/language-server edit proposal into core paths
 * and coordinate space. Acceptance is only a proposal-validation step; it does
 * not authorize or apply the resulting [WorkspaceEdit].
 */
class ExternalWorkspaceEditNormalizer(
    private val limits: ExternalWorkspaceEditLimits = ExternalWorkspaceEditLimits(),
) {
    fun normalize(
        snapshot: ProjectSnapshot,
        proposal: ExternalWorkspaceEditProposal,
    ): ExternalWorkspaceEditNormalization {
        val refusals = mutableListOf<Diagnostic>()
        if (!PROVIDER_ID.matches(proposal.providerId)) {
            refusals += refusal("externalEdit.providerInvalid", "External edit provider ID is invalid")
        }
        if (proposal.providerVersion != null && (proposal.providerVersion.length > 128 || '\u0000' in proposal.providerVersion)) {
            refusals += refusal("externalEdit.providerVersionInvalid", "External edit provider version is invalid")
        }
        if (proposal.edits.size > limits.maxFileEdits) {
            refusals += refusal("externalEdit.fileLimit", "External edit exceeds ${limits.maxFileEdits} file operations")
        }
        val textEditCount = proposal.edits.sumOf { (it as? ExternalFileEditProposal.Modify)?.edits?.size ?: 0 }
        if (textEditCount > limits.maxTextEdits) {
            refusals += refusal("externalEdit.textEditLimit", "External edit exceeds ${limits.maxTextEdits} text edits")
        }
        val replacementBytes = proposal.edits.sumOf {
            when (it) {
                is ExternalFileEditProposal.Modify -> it.edits.sumOf { edit -> edit.newText.toByteArray(Charsets.UTF_8).size.toLong() }
                is ExternalFileEditProposal.Create -> it.content.toByteArray(Charsets.UTF_8).size.toLong()
                else -> 0L
            }
        }
        if (replacementBytes > limits.maxReplacementBytes) {
            refusals += refusal("externalEdit.contentLimit", "External edit replacement content exceeds ${limits.maxReplacementBytes} bytes")
        }
        if (refusals.isNotEmpty()) return ExternalWorkspaceEditNormalization.Refused(refusals)

        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val versions = linkedMapOf<Path, Long>()
        val fileEdits = proposal.edits.mapNotNull { external ->
            val source = normalizePath(root, external.path, refusals) ?: return@mapNotNull null
            if (isGenerated(snapshot, source)) {
                refusals += refusal("externalEdit.generatedSource", "External edit targets generated source '${ProtocolPath.serialize(source)}'")
                return@mapNotNull null
            }
            when (external) {
                is ExternalFileEditProposal.Modify -> {
                    if (external.documentVersion != null) {
                        if (external.documentVersion < 0 || versions.putIfAbsent(source, external.documentVersion) != null) {
                            refusals += refusal("externalEdit.documentVersionInvalid", "External edit has invalid or duplicate document version")
                        }
                    }
                    FileEdit.Modify(source, external.edits)
                }
                is ExternalFileEditProposal.Create -> FileEdit.Create(source, external.content, overwrite = false)
                is ExternalFileEditProposal.Delete -> FileEdit.Delete(source)
                is ExternalFileEditProposal.Rename -> {
                    val target = normalizePath(root, external.newPath, refusals) ?: return@mapNotNull null
                    if (isGenerated(snapshot, target)) {
                        refusals += refusal("externalEdit.generatedSource", "External rename targets generated source '${ProtocolPath.serialize(target)}'")
                        return@mapNotNull null
                    }
                    FileEdit.Rename(source, target)
                }
            }
        }
        if (refusals.isNotEmpty()) return ExternalWorkspaceEditNormalization.Refused(refusals.distinctBy { it.code to it.message })

        val workspaceEdit = WorkspaceEditSimulator.normalize(WorkspaceEdit(fileEdits))
        val validationFailure = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.exceptionOrNull()
        if (validationFailure != null) {
            return ExternalWorkspaceEditNormalization.Refused(listOf(refusal(
                "externalEdit.invalidCoordinates",
                validationFailure.message ?: "External edit coordinates are invalid",
            )))
        }
        return ExternalWorkspaceEditNormalization.Accepted(NormalizedExternalWorkspaceEdit(
            workspaceEdit = workspaceEdit,
            documentVersions = versions.toSortedMap(compareBy(Path::toString)),
            providerId = proposal.providerId,
            providerVersion = proposal.providerVersion,
        ))
    }

    private fun normalizePath(root: Path, untrusted: Path, refusals: MutableList<Diagnostic>): Path? {
        val raw = untrusted.toString()
        if (raw.isBlank() || raw.contains('\u0000') || (!untrusted.isAbsolute && WINDOWS_ABSOLUTE.containsMatchIn(raw))) {
            refusals += refusal("externalEdit.pathInvalid", "External edit path is invalid")
            return null
        }
        val absolute = if (untrusted.isAbsolute) untrusted.normalize() else root.resolve(untrusted).normalize()
        if (!absolute.startsWith(root) || absolute == root) {
            refusals += refusal("externalEdit.pathOutsideWorkspace", "External edit path is outside the workspace")
            return null
        }
        var current = root
        root.relativize(absolute).forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                refusals += refusal("externalEdit.symlink", "External edit path traverses a symbolic link")
                return null
            }
        }
        return root.relativize(absolute)
    }

    private fun isGenerated(snapshot: ProjectSnapshot, path: Path): Boolean {
        val modelOwnership = snapshot.owningBuildSourceRoots(path)
        if (modelOwnership.any(BuildSourceRootOwnership::generated)) return true
        if (snapshot.buildModels.isNotEmpty()) return false
        return snapshot.modules.any { module ->
            (module.generatedSourceRoots + module.generatedTestSourceRoots).any { root -> path.normalize().startsWith(root.normalize()) }
        }
    }

    private fun refusal(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.SAFETY,
    )

    companion object {
        private val PROVIDER_ID = Regex("[a-z][a-z0-9._-]{0,63}")
        private val WINDOWS_ABSOLUTE = Regex("^(?:[A-Za-z]:[\\\\/]|\\\\\\\\)")
    }
}
