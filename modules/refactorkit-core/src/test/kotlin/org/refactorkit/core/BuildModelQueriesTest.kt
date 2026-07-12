package org.refactorkit.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildModelQueriesTest {
    @Test
    fun exactAndLongestPrefixOwnershipPreserveSourceSetProvenance() {
        val main = BuildSourceSet(
            id = "main",
            kind = SourceSetKind.MAIN,
            sourceRoots = listOf(Path.of("module/src/main/java"), Path.of("module/target/generated-sources/annotations")),
            generatedSourceRoots = listOf(Path.of("module/target/generated-sources/annotations")),
        )
        val snapshot = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = emptyList(),
            buildModels = listOf(BuildModel(
                providerId = "fixture-v1",
                status = BuildModelStatus.AVAILABLE,
                modules = listOf(BuildModule("module", "module", Path.of("/workspace/module"), listOf(main))),
            )),
        )

        val exact = snapshot.exactBuildSourceRootOwnerships(Path.of("module/src/main/java")).single()
        assertEquals("fixture-v1", exact.providerId)
        assertEquals(SourceSetKind.MAIN, exact.sourceSet.kind)
        assertFalse(exact.generated)

        val generated = snapshot.owningBuildSourceRoots(
            Path.of("module/target/generated-sources/annotations/example/Generated.java"),
        ).single()
        assertTrue(generated.generated)
        assertEquals("module", generated.module.id)
    }

    @Test
    fun longestPrefixAvoidsParentRootAmbiguity() {
        val snapshot = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = emptyList(),
            buildModels = listOf(BuildModel(
                providerId = "fixture-v1",
                status = BuildModelStatus.PARTIAL,
                modules = listOf(BuildModule(
                    "module", "module", Path.of("/workspace/module"), listOf(
                        BuildSourceSet("custom", SourceSetKind.CUSTOM, sourceRoots = listOf(Path.of("module/src"))),
                        BuildSourceSet("main", SourceSetKind.MAIN, sourceRoots = listOf(Path.of("module/src/main/java"))),
                    ),
                )),
            )),
        )

        val owner = snapshot.owningBuildSourceRoots(Path.of("module/src/main/java/example/App.java")).single()
        assertEquals("main", owner.sourceSet.id)
    }
}
