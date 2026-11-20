package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.refactorkit.core.BuildFactAvailability
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.Workspace
import java.nio.file.Path
import java.security.MessageDigest

class JavaJpmsProjectContextSpec : StringSpec({
    "the JDT module projection retains opens uses and provides directives" {
        val descriptor = JavaContextModuleDescriptorFact(
            path = Path.of("src/main/java/module-info.java"),
            moduleName = "fixture.jpms",
            open = false,
            requires = emptyList(),
            exports = emptyList(),
            contentSha256 = "0".repeat(64),
            opens = listOf(
                JavaContextOpensFact("fixture.internal", listOf("fixture.framework", "fixture.serializer")),
                JavaContextOpensFact("fixture.reflection", emptyList()),
            ),
            uses = listOf("fixture.spi.Engine", "fixture.spi.Renderer"),
            provides = listOf(JavaContextProvidesFact(
                "fixture.spi.Engine", listOf("fixture.impl.DefaultEngine", "fixture.impl.ZedEngine"),
            )),
        )

        val module = JdtJpmsClasspath.binaryModule(descriptor)

        module.opens().map { open ->
            String(open.name()) to (open.targets()?.map(::String) ?: emptyList()) to open.isQualified()
        } shouldBe listOf(
            ("fixture.internal" to listOf("fixture.framework", "fixture.serializer")) to true,
            ("fixture.reflection" to emptyList<String>()) to false,
        )
        module.uses().map(::String) shouldBe listOf("fixture.spi.Engine", "fixture.spi.Renderer")
        module.provides().map { provider ->
            String(provider.name()) to provider.with().map(::String)
        } shouldBe listOf(
            "fixture.spi.Engine" to listOf("fixture.impl.DefaultEngine", "fixture.impl.ZedEngine"),
        )
    }

    "a complete module descriptor retains every standard directive in deterministic order" {
        val descriptorText = """
            module fixture.jpms {
                uses fixture.spi.Renderer;
                opens fixture.internal to fixture.serializer, fixture.framework;
                provides fixture.spi.Engine with fixture.impl.ZedEngine, fixture.impl.DefaultEngine;
                uses fixture.spi.Engine;
            }
        """.trimIndent() + "\n"
        val root = Path.of("/workspace")
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("src/main/java/module-info.java"), descriptorText, "java")),
        )
        val sourceSet = BuildSourceSet(
            id = "main",
            kind = SourceSetKind.MAIN,
            sourceRoots = listOf(Path.of("src/main/java")),
            attributes = mapOf("java.release" to "21", "java.sourceLevel" to "21"),
        )

        val result = JavaJpmsProjectContextResolver.resolve(
            snapshot = snapshot,
            moduleRoot = root,
            sourceSet = sourceSet,
            compilerOptions = emptyList(),
            compilerOptionsAvailability = BuildFactAvailability.COMPLETE,
            configuredSdkHome = Path.of(System.getProperty("java.home")),
        )

        result.availability shouldBe BuildFactAvailability.COMPLETE
        result.refusalCode shouldBe null
        val descriptor = result.descriptors.single()
        descriptor.moduleName shouldBe "fixture.jpms"
        descriptor.opens.single() shouldBe JavaContextOpensFact(
            "fixture.internal",
            listOf("fixture.framework", "fixture.serializer"),
        )
        descriptor.uses shouldBe listOf("fixture.spi.Engine", "fixture.spi.Renderer")
        descriptor.provides.single() shouldBe JavaContextProvidesFact(
            "fixture.spi.Engine",
            listOf("fixture.impl.DefaultEngine", "fixture.impl.ZedEngine"),
        )
        descriptor.contentSha256 shouldBe MessageDigest.getInstance("SHA-256")
            .digest(descriptorText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
})
