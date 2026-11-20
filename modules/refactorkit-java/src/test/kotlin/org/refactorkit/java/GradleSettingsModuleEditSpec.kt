package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.refactorkit.core.BuildDependency
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path

class GradleSettingsModuleEditSpec : StringSpec({
    "Kotlin settings append a literal include and infer the conventional nested project directory" {
        val content = """pluginManagement {
    repositories { gradlePluginPortal() }
}
rootProject.name = "catalog"
include(":app")
"""

        GradleSettingsModuleEdit.propose("settings.gradle.kts", content, ":services:catalog") shouldBe
            GradleSettingsModuleEditResult.Supported(
                GradleSettingsModuleEditProposal(
                    dsl = GradleSettingsDsl.KOTLIN,
                    projectPath = ":services:catalog",
                    projectDirectory = Path.of("services", "catalog"),
                    buildScriptPath = Path.of("services", "catalog", "build.gradle.kts"),
                    insertionOffset = content.length,
                    insertedText = "include(\":services:catalog\")\n",
                ),
            )
    }

    "Groovy settings preserve CRLF and terminate a trailing line comment before the new include" {
        val content = "rootProject.name = 'sample'\r\ninclude 'app' // existing module"

        GradleSettingsModuleEdit.propose("settings.gradle", content, ":tools:api") shouldBe
            GradleSettingsModuleEditResult.Supported(
                GradleSettingsModuleEditProposal(
                    dsl = GradleSettingsDsl.GROOVY,
                    projectPath = ":tools:api",
                    projectDirectory = Path.of("tools", "api"),
                    buildScriptPath = Path.of("tools", "api", "build.gradle"),
                    insertionOffset = content.length,
                    insertedText = "\r\ninclude ':tools:api'\r\n",
                ),
            )
    }

    "settings that remap project directories are refused because a new directory cannot be inferred" {
        val content = "include(\":app\")\nproject(\":app\").projectDir = file(\"modules/application\")\n"

        GradleSettingsModuleEdit.propose("settings.gradle.kts", content, ":services:catalog") shouldBe
            GradleSettingsModuleEditResult.Refused("java.createGradleModule.projectDirectoryMappingUnsupported")
    }

    "malformed settings lexical structure and unsafe project paths are refused" {
        GradleSettingsModuleEdit.propose("settings.gradle.kts", "include(\":app\"", ":services:catalog") shouldBe
            GradleSettingsModuleEditResult.Refused("java.createGradleModule.settingsSyntaxUnsupported")
        GradleSettingsModuleEdit.propose("settings.gradle", "include 'app'\n", ":../escape") shouldBe
            GradleSettingsModuleEditResult.Refused("java.createGradleModule.invalidProjectPath")
    }

    "canonical Gradle project paths honor the IDE segment and depth limits" {
        val maxSegment = "a" + "b".repeat(254)
        val accepted = GradleSettingsModuleEdit.propose(
            "settings.gradle.kts", "", ":${(1..32).joinToString(":") { "m$it" }}",
        )
        (accepted is GradleSettingsModuleEditResult.Supported) shouldBe true

        val maxLengthAccepted = GradleSettingsModuleEdit.propose("settings.gradle", "", ":$maxSegment")
        (maxLengthAccepted is GradleSettingsModuleEditResult.Supported) shouldBe true
        GradleSettingsModuleEdit.propose("settings.gradle", "", ":${maxSegment}b") shouldBe
            GradleSettingsModuleEditResult.Refused("java.createGradleModule.invalidProjectPath")
        GradleSettingsModuleEdit.propose(
            "settings.gradle", "", ":${(1..33).joinToString(":") { "m$it" }}",
        ) shouldBe GradleSettingsModuleEditResult.Refused("java.createGradleModule.invalidProjectPath")
    }

    "Kotlin settings rename one unconditional project path and pin its existing directory" {
        val content = "rootProject.name = \"selected-build\"\ninclude(\":app\")\n"

        val result = GradleSettingsModuleEdit.proposeRename(
            "settings.gradle.kts", content, ":app", ":services:catalog",
        ) as GradleSettingsModuleRenameResult.Supported

        result.proposal.dsl shouldBe GradleSettingsDsl.KOTLIN
        result.proposal.physicalProjectDirectory shouldBe Path.of("app")
        content.substring(result.proposal.pathLiteralStart, result.proposal.pathLiteralEnd) shouldBe ":app"
        result.proposal.insertedText shouldBe
            "project(\":services:catalog\").projectDir = file(\"app\")\n"
    }

    "Groovy settings preserve CRLF while pinning a renamed module to its original directory" {
        val content = "rootProject.name = 'selected-build'\r\ninclude ':app'\r\n"

        val result = GradleSettingsModuleEdit.proposeRename(
            "settings.gradle", content, ":app", ":services:catalog",
        ) as GradleSettingsModuleRenameResult.Supported

        result.proposal.dsl shouldBe GradleSettingsDsl.GROOVY
        result.proposal.physicalProjectDirectory shouldBe Path.of("app")
        content.substring(result.proposal.pathLiteralStart, result.proposal.pathLiteralEnd) shouldBe ":app"
        result.proposal.insertedText shouldBe
            "project(':services:catalog').projectDir = file('app')\r\n"
    }

    "Gradle project path rename refuses pre-existing directory mappings and non-literal includes" {
        GradleSettingsModuleEdit.proposeRename(
            "settings.gradle.kts",
            "include(\":app\")\nproject(\":app\").projectDir = file(\"modules/app\")\n",
            ":app", ":renamed",
        ) shouldBe GradleSettingsModuleRenameResult.Refused(
            "java.renameGradleModule.projectDirectoryMappingUnsupported",
        )
        GradleSettingsModuleEdit.proposeRename(
            "settings.gradle.kts", "if (useApp) include(\":app\")\n", ":app", ":renamed",
        ) shouldBe GradleSettingsModuleRenameResult.Refused(
            "java.renameGradleModule.settingsIncludeUnsupported",
        )
    }

    "rename planner projects unchanged physical source-set identity and refuses inbound project dependencies" {
        fun captured(withInboundDependency: Boolean): Pair<ProjectSnapshot, BuildModel> {
            val workspace = Files.createTempDirectory("rk-gradle-rename-spec-").toAbsolutePath().normalize()
            val buildRoot = workspace.resolve("selected-build")
            val appRoot = buildRoot.resolve("app")
            val consumerRoot = buildRoot.resolve("consumer")
            Files.createDirectories(appRoot)
            Files.createDirectories(consumerRoot)
            val settingsPath = Path.of("selected-build/settings.gradle.kts")
            val settings = "rootProject.name = \"selected-build\"\ninclude(\":app\")\n"
            Files.writeString(workspace.resolve(settingsPath), settings)
            val appSourceSet = BuildSourceSet(
                id = "main",
                kind = SourceSetKind.MAIN,
                sourceRoots = listOf(Path.of("selected-build/app/src/main/java")),
                outputDirectories = listOf(Path.of("selected-build/app/build/classes/java/main")),
                classpathEntries = listOf(Path.of("selected-build/app/build/classes/java/main"), Path.of("lib/dependency.jar")),
                runtimeClasspathEntries = listOf(Path.of("lib/dependency.jar")),
            )
            val consumerSourceSet = BuildSourceSet(
                id = "main",
                kind = SourceSetKind.MAIN,
                moduleDependencies = if (withInboundDependency) listOf(
                    BuildDependency(":app", DependencyScope.COMPILE),
                ) else emptyList(),
            )
            val model = BuildModel(
                providerId = "gradle-effective-v1",
                status = BuildModelStatus.AVAILABLE,
                modules = listOf(
                    BuildModule(
                        id = ":app", name = "app", root = appRoot,
                        sourceSets = listOf(appSourceSet),
                        attributes = mapOf("gradle.buildRoot" to "selected-build", "gradle.projectPath" to ":app"),
                    ),
                    BuildModule(
                        id = ":consumer", name = "consumer", root = consumerRoot,
                        sourceSets = listOf(consumerSourceSet),
                        attributes = mapOf("gradle.buildRoot" to "selected-build", "gradle.projectPath" to ":consumer"),
                    ),
                ),
                attributes = mapOf(
                    "gradle.workspaceRoot" to workspace.toRealPath().toString(),
                    "gradle.exportSha256" to "a".repeat(64),
                    "gradle.compositeBuildCount" to "1",
                    "java.safeDelete.textReferenceScan.status" to "complete",
                    "java.safeDelete.textReferenceScan.inputs" to "1",
                    "java.safeDelete.textReferenceScan.bytes" to settings.toByteArray().size.toString(),
                ),
            )
            val snapshot = ProjectSnapshot(
                workspace = Workspace(workspace),
                modules = emptyList(),
                files = emptyList(),
                buildModels = listOf(model),
                auxiliaryFiles = listOf(SourceFile(settingsPath, settings, "gradle-settings")),
            )
            return snapshot to model
        }

        val (supportedSnapshot, supportedModel) = captured(withInboundDependency = false)
        val supported = JavaRenameGradleModulePlanner().preview(
            supportedSnapshot, supportedModel, "selected-build", ":app", ":services:catalog",
        )
        supported.status shouldBe org.refactorkit.core.PatchStatus.PREVIEW
        supported.affectedFiles.map(Path::toString) shouldBe listOf("selected-build/settings.gradle.kts")
        supported.authorityLease?.attributes?.get("physicalProjectDirectory") shouldBe "selected-build/app"

        val (dependentSnapshot, dependentModel) = captured(withInboundDependency = true)
        val refused = JavaRenameGradleModulePlanner().preview(
            dependentSnapshot, dependentModel, "selected-build", ":app", ":services:catalog",
        )
        refused.status shouldBe org.refactorkit.core.PatchStatus.REFUSED
        refused.refusalCode shouldBe "java.renameGradleModule.inboundProjectDependencyUnsupported"
        refused.affectedFiles shouldBe emptySet()
    }
})
