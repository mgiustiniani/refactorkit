package org.refactorkit.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/** REQ-JAVA-PROJECT-CONTEXT-001 prerequisite: language-neutral root ownership. */
class WorkspaceRootOwnershipSpec : StringSpec({
    fun snapshot(vararg roots: Pair<String, String>) = ProjectSnapshot(
        Workspace(Path.of("/workspace")), emptyList(), emptyList(),
        buildModels = listOf(BuildModel("declared-fixture-v1", BuildModelStatus.AVAILABLE,
            listOf(BuildModule("app", "app", Path.of("/workspace"), roots.map { (id, root) ->
                BuildSourceSet(id, SourceSetKind.MAIN, sourceRoots = listOf(Path.of(root)))
            })))),
    )

    "a dot source root owns a flat workspace-relative source" {
        val owners = snapshot("main" to ".").owningBuildSourceRoots(Path.of("Runner.java"))
        owners.map { it.sourceSet.id } shouldBe listOf("main")
    }
    "an empty normalized source root owns a flat workspace-relative source" {
        val owners = snapshot("main" to "").owningBuildSourceRoots(Path.of("Runner.java"))
        owners.map { it.sourceSet.id } shouldBe listOf("main")
    }
    "a nested source root takes precedence over workspace-root ownership" {
        val owners = snapshot("fallback" to ".", "nested" to "src").owningBuildSourceRoots(Path.of("src/Runner.java"))
        owners.map { it.sourceSet.id } shouldBe listOf("nested")
    }
    "distinct declarations of the same normalized workspace root retain ambiguity" {
        val owners = snapshot("first" to ".", "second" to "").owningBuildSourceRoots(Path.of("Runner.java"))
        owners.map { it.sourceSet.id } shouldBe listOf("first", "second")
    }
    "a path traversing outside the workspace has no source owner" {
        snapshot("main" to ".").owningBuildSourceRoots(Path.of("../Runner.java")) shouldBe emptyList()
    }
    "an absolute path cannot acquire relative workspace-root ownership" {
        snapshot("main" to ".").owningBuildSourceRoots(Path.of("/outside/Runner.java")) shouldBe emptyList()
    }
})
