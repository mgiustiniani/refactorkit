package org.refactorkit.java

import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SymbolId
import java.nio.file.Path

sealed interface JavaRefactoringPreviewCommand {
    data class RenameClass(
        val symbolId: SymbolId,
        val newName: String,
    ) : JavaRefactoringPreviewCommand

    data class RenameMember(
        val symbolId: SymbolId,
        val newName: String,
    ) : JavaRefactoringPreviewCommand

    data class MoveSourceRoot(
        val from: Path,
        val to: Path,
    ) : JavaRefactoringPreviewCommand

    data class OrganizeImports(
        val file: Path,
    ) : JavaRefactoringPreviewCommand

    data class SafeDelete(
        val symbolId: SymbolId,
        val force: Boolean,
    ) : JavaRefactoringPreviewCommand
}

class JavaRefactoringPreviewDispatcher {
    fun preview(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        command: JavaRefactoringPreviewCommand,
    ): PatchPlan = preview(
        snapshot,
        javaAdapter,
        command,
        ExistingJavaRefactoringPreviewPlannerInvoker(),
    )

    internal fun preview(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        command: JavaRefactoringPreviewCommand,
        plannerInvoker: JavaRefactoringPreviewPlannerInvoker,
    ): PatchPlan = when (command) {
        is JavaRefactoringPreviewCommand.RenameClass -> plannerInvoker.renameClass(
            snapshot,
            javaAdapter,
            command.symbolId,
            command.newName,
        )
        is JavaRefactoringPreviewCommand.RenameMember -> plannerInvoker.renameMember(
            snapshot,
            javaAdapter,
            command.symbolId,
            command.newName,
        )
        is JavaRefactoringPreviewCommand.MoveSourceRoot -> plannerInvoker.moveSourceRoot(
            snapshot,
            javaAdapter,
            command.from,
            command.to,
        )
        is JavaRefactoringPreviewCommand.OrganizeImports -> plannerInvoker.organizeImports(
            snapshot,
            command.file,
        )
        is JavaRefactoringPreviewCommand.SafeDelete -> plannerInvoker.safeDelete(
            snapshot,
            javaAdapter,
            command.symbolId,
            command.force,
        )
    }
}

internal interface JavaRefactoringPreviewPlannerInvoker {
    fun renameClass(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        symbolId: SymbolId,
        newName: String,
    ): PatchPlan

    fun renameMember(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        symbolId: SymbolId,
        newName: String,
    ): PatchPlan

    fun moveSourceRoot(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        from: Path,
        to: Path,
    ): PatchPlan

    fun organizeImports(
        snapshot: ProjectSnapshot,
        file: Path,
    ): PatchPlan

    fun safeDelete(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        symbolId: SymbolId,
        force: Boolean,
    ): PatchPlan
}

internal class ExistingJavaRefactoringPreviewPlannerInvoker : JavaRefactoringPreviewPlannerInvoker {
    override fun renameClass(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        symbolId: SymbolId,
        newName: String,
    ): PatchPlan = JavaRenameClassPlanner(javaAdapter).preview(snapshot, symbolId.value, newName)

    override fun renameMember(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        symbolId: SymbolId,
        newName: String,
    ): PatchPlan = JavaRenameMemberPlanner(javaAdapter).preview(snapshot, symbolId.value, newName)

    override fun moveSourceRoot(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        from: Path,
        to: Path,
    ): PatchPlan = JavaMoveSourceRootPlanner(javaAdapter).preview(snapshot, from, to)

    override fun organizeImports(
        snapshot: ProjectSnapshot,
        file: Path,
    ): PatchPlan = JavaOrganizeImportsPlanner().previewSingleFile(snapshot, file)

    override fun safeDelete(
        snapshot: ProjectSnapshot,
        javaAdapter: JavaLanguageAdapter,
        symbolId: SymbolId,
        force: Boolean,
    ): PatchPlan = JavaSafeDeletePlanner(javaAdapter).preview(snapshot, symbolId.value, force)
}
