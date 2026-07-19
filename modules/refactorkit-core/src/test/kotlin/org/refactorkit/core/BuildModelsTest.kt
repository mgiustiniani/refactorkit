package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class BuildModelsTest {
    @Test
    fun buildModelRejectsUnknownModuleEdges() {
        assertFailsWith<IllegalArgumentException> {
            BuildModel(
                providerId = "test",
                status = BuildModelStatus.AVAILABLE,
                modules = listOf(BuildModule(
                    id = "app",
                    name = "app",
                    root = Path.of("app"),
                    sourceSets = listOf(BuildSourceSet(
                        id = "main",
                        kind = SourceSetKind.MAIN,
                        moduleDependencies = listOf(BuildDependency("missing", DependencyScope.COMPILE)),
                    )),
                )),
            )
        }
    }

    @Test
    fun generatedRootsMustBelongToTheirSourceSet() {
        assertFailsWith<IllegalArgumentException> {
            BuildSourceSet(
                id = "test",
                kind = SourceSetKind.TEST,
                sourceRoots = listOf(Path.of("src/test/java")),
                generatedSourceRoots = listOf(Path.of("target/generated-test-sources")),
            )
        }
    }

    @Test
    fun providerProfileSelectionIsBoundedAndNonContradictory() {
        assertFailsWith<IllegalArgumentException> {
            BuildModelSelection(activeProfiles = setOf("release"), inactiveProfiles = setOf("release"))
        }
        assertFailsWith<IllegalArgumentException> {
            BuildModelSelection(activeProfiles = (1..65).map { "profile-$it" }.toSet())
        }
        val request = BuildModelRequest(
            workspaceRoot = Path.of("."),
            selections = mapOf("maven-effective-v1" to BuildModelSelection(activeProfiles = setOf("release"))),
        )
        assertEquals(setOf("release"), request.selections.getValue("maven-effective-v1").activeProfiles)
    }

    @Test
    fun sourceSetPathsRejectAbsoluteAndTraversalMetadata() {
        val absolute = Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve("absolute-source")
        listOf(Path.of("../outside"), absolute).forEach { unsafe ->
            assertFailsWith<IllegalArgumentException> {
                BuildSourceSet(id = "main", kind = SourceSetKind.MAIN, sourceRoots = listOf(unsafe))
            }
        }
    }

    @Test
    fun buildModelChangesAreSnapshotHashBound() {
        val root = Files.createTempDirectory("refactorkit-build-model-hash")
        val source = SourceFile(Path.of("src/main/java/example/App.java"), "package example; class App {}\n", "java")
        val module = Module("app", root, sourceRoots = listOf(Path.of("src/main/java")))
        val initialModel = BuildModel(
            providerId = "test",
            status = BuildModelStatus.AVAILABLE,
            modules = listOf(BuildModule(
                id = "app",
                name = "app",
                root = root,
                sourceSets = listOf(BuildSourceSet(
                    id = "main",
                    kind = SourceSetKind.MAIN,
                    sourceRoots = listOf(Path.of("src/main/java")),
                    attributes = mapOf("languageLevel" to "17"),
                )),
            )),
        )
        val changedModel = initialModel.copy(modules = initialModel.modules.map { buildModule ->
            buildModule.copy(sourceSets = buildModule.sourceSets.map { sourceSet ->
                sourceSet.copy(attributes = mapOf("languageLevel" to "21"))
            })
        })

        val initial = ProjectSnapshot(Workspace(root), listOf(module), listOf(source), buildModels = listOf(initialModel))
        val changed = ProjectSnapshot(Workspace(root), listOf(module), listOf(source), buildModels = listOf(changedModel))

        assertNotEquals(initial.hash, changed.hash)

        val runtimeChangedModel = initialModel.copy(modules = initialModel.modules.map { buildModule ->
            buildModule.copy(sourceSets = buildModule.sourceSets.map { sourceSet ->
                sourceSet.copy(runtimeClasspathEntries = listOf(Path.of("repository/runtime-api.jar")))
            })
        })
        val runtimeChanged = ProjectSnapshot(
            Workspace(root), listOf(module), listOf(source), buildModels = listOf(runtimeChangedModel),
        )
        assertNotEquals(initial.hash, runtimeChanged.hash)
    }
}
