package org.refactorkit.java

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.FileEdit
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Exact offline projection of the snapshot after a literal Gradle module creation edit. */
internal object JavaGradleModuleStagedSnapshot {
    private const val TEXT_INPUT_COUNT = "java.safeDelete.textReferenceScan.inputs"
    private const val TEXT_INPUT_BYTES = "java.safeDelete.textReferenceScan.bytes"
    private const val TEXT_INPUT_STATUS = "java.safeDelete.textReferenceScan.status"
    private const val MAX_TEXT_INPUTS = 8_192
    private const val MAX_TEXT_INPUT_BYTES = 1_048_576L
    private const val MAX_TOTAL_TEXT_BYTES = 33_554_432L

    fun evaluate(baseline: ProjectSnapshot, edit: WorkspaceEdit): ProjectSnapshot {
        val normalized = WorkspaceEditSimulator.normalize(edit)
        val settingsEdit = normalized.edits.filterIsInstance<FileEdit.Modify>().singleOrNull()
            ?: throw IllegalArgumentException("Gradle module staging requires one settings-file modification")
        val settingsName = settingsEdit.path.fileName?.toString()
        require(settingsName == "settings.gradle" || settingsName == "settings.gradle.kts") {
            "Gradle module staging requires the exact settings.gradle or settings.gradle.kts file"
        }
        val buildEdit = edit.edits.filterIsInstance<FileEdit.Create>().singleOrNull()
            ?: throw IllegalArgumentException("Gradle module staging requires one new project build script")
        val expectedBuildName = if (settingsName.endsWith(".kts")) "build.gradle.kts" else "build.gradle"
        require(buildEdit.path.fileName?.toString() == expectedBuildName) {
            "New project build script must match the settings DSL"
        }
        require(normalized.edits.size == 2 && normalized.edits.filterIsInstance<FileEdit.Create>().size == 1) {
            "Gradle module staging accepts only the settings append and one build-script creation"
        }
        val buildRoot = settingsEdit.path.parent?.normalize() ?: Path.of("")
        require(!buildEdit.path.isAbsolute && !buildEdit.path.normalize().startsWith("..") &&
            (buildRoot.toString().isEmpty() || buildEdit.path.normalize().startsWith(buildRoot))
        ) {
            "New Gradle project must remain under the selected settings build root"
        }

        val priorModel = baseline.buildModels.singleOrNull { it.providerId == "gradle-effective-v1" }
            ?.takeIf { it.status == BuildModelStatus.AVAILABLE }
            ?: throw IllegalArgumentException("One available synchronized Gradle model is required")
        require(baseline.buildModels.size == 1) {
            "Gradle module staging cannot project additional build-model providers"
        }
        require(priorModel.modules.any { it.attributes["gradle.buildRoot"] != null }) {
            "Synchronized Gradle model has no build-root identity"
        }
        val settingsBefore = baseline.trackedFiles.singleOrNull {
            it.path.normalize() == settingsEdit.path.normalize()
        }?.content ?: throw IllegalArgumentException("Selected Gradle settings file is absent from the snapshot")
        val settingsAfter = WorkspaceEditSimulator.apply(baseline, WorkspaceEdit(listOf(settingsEdit)))
            .trackedFiles.single { it.path.normalize() == settingsEdit.path.normalize() }.content
        val buildAfter = buildEdit.content
        require(settingsAfter.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_INPUT_BYTES) {
            "Updated Gradle settings exceed the bounded text-input size"
        }
        require(buildAfter.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_INPUT_BYTES) {
            "New Gradle build script exceeds the bounded text-input size"
        }

        val attributes = priorModel.attributes
        require(attributes[TEXT_INPUT_STATUS] == "complete") {
            "Complete safe-delete text-input inventory is required to project the post-snapshot"
        }
        val inputCount = attributes[TEXT_INPUT_COUNT]?.toIntOrNull()
            ?: throw IllegalArgumentException("Gradle snapshot lacks a bounded text-input count")
        val inputBytes = attributes[TEXT_INPUT_BYTES]?.toLongOrNull()
            ?: throw IllegalArgumentException("Gradle snapshot lacks a bounded text-input byte count")
        val nextInputCount = inputCount + 1
        val nextInputBytes = inputBytes - settingsBefore.toByteArray(StandardCharsets.UTF_8).size +
            settingsAfter.toByteArray(StandardCharsets.UTF_8).size +
            buildAfter.toByteArray(StandardCharsets.UTF_8).size
        require(nextInputCount <= MAX_TEXT_INPUTS && nextInputBytes in 0..MAX_TOTAL_TEXT_BYTES) {
            "Gradle module would exceed the complete text-input inventory limits"
        }

        val staged = WorkspaceEditSimulator.apply(baseline, edit)
        require(staged.trackedFiles.singleOrNull { it.path.normalize() == buildEdit.path.normalize() }?.content == buildAfter) {
            "New Gradle build script is not represented exactly in the staged snapshot"
        }
        val invalidatedModel = JavaGradleBuildInputInvalidation.invalidate(priorModel).copy(
            attributes = attributes + mapOf(
                TEXT_INPUT_COUNT to nextInputCount.toString(),
                TEXT_INPUT_BYTES to nextInputBytes.toString(),
            ),
        )
        return staged.copy(buildModels = listOf(invalidatedModel))
    }

    fun isInvalidated(snapshot: ProjectSnapshot): Boolean =
        snapshot.buildModels.singleOrNull { it.providerId == "gradle-effective-v1" }?.let { model ->
            model.status == BuildModelStatus.UNAVAILABLE &&
                model.diagnostics.singleOrNull()?.code == "gradle.context.stale"
        } == true
}
