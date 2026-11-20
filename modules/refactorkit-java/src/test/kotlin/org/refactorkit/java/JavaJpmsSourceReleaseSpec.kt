package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.refactorkit.core.BuildFactAvailability
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.Workspace
import java.nio.file.Path

class JavaJpmsSourceReleaseSpec : StringSpec({
    "Java 8 source does not require JPMS option evidence" {
        val result = resolve(sourceRelease = 8, optionsAvailability = BuildFactAvailability.PARTIAL)

        result.availability shouldBe BuildFactAvailability.NOT_DECLARED
        result.modulePath shouldBe emptyList()
        result.refusalCode shouldBe null
    }

    "Java 9 and later still require complete compiler options before JPMS can be declared absent" {
        val result = resolve(sourceRelease = 17, optionsAvailability = BuildFactAvailability.PARTIAL)

        result.availability shouldBe BuildFactAvailability.PARTIAL
        result.refusalCode shouldBe "java.context.jpmsModulePathUnavailable"
    }
})

private fun resolve(sourceRelease: Int, optionsAvailability: BuildFactAvailability): JavaJpmsSourceSetContext {
    val root = Path.of("/workspace")
    val sourceSet = BuildSourceSet(
        id = "main",
        kind = SourceSetKind.MAIN,
        attributes = mapOf(
            "java.release" to sourceRelease.toString(),
            "java.sourceLevel" to sourceRelease.toString(),
        ),
    )
    val snapshot = ProjectSnapshot(Workspace(root), modules = emptyList(), files = emptyList())
    return JavaJpmsProjectContextResolver.resolve(
        snapshot = snapshot,
        moduleRoot = root,
        sourceSet = sourceSet,
        compilerOptions = emptyList(),
        compilerOptionsAvailability = optionsAvailability,
        configuredSdkHome = Path.of("/configured-jdk"),
    )
}
