package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.SourceSetKind
import java.nio.file.Path
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST

class JavaProjectSourceLevelPolicySpec : StringSpec({
    "accepts the supported Java source-level boundaries and legacy Java 8 spelling" {
        JavaProjectSourceLevelPolicy.MAX_SUPPORTED shouldBe minOf(
            AST.getJLSLatest(), JavaCore.latestSupportedJavaVersion().toInt(),
        )
        JavaProjectSourceLevelPolicy.resolve("1.8", null) shouldBe
            JavaProjectSourceLevelDecision.Supported(8)
        JavaProjectSourceLevelPolicy.resolve(JavaProjectSourceLevelPolicy.MAX_SUPPORTED.toString(), null) shouldBe
            JavaProjectSourceLevelDecision.Supported(JavaProjectSourceLevelPolicy.MAX_SUPPORTED)
    }

    "uses the installed AST and compiler maximum instead of a hard-coded Java 25 ceiling" {
        val expectedMaximum = minOf(AST.getJLSLatest(), JavaCore.latestSupportedJavaVersion().toInt())
        JavaProjectSourceLevelPolicy.MAX_SUPPORTED shouldBe expectedMaximum
        JavaProjectSourceLevelPolicy.DEFAULT_SOURCE_LEVEL shouldBe minOf(25, expectedMaximum)
    }

    "refuses future releases only when the installed parser cannot represent them" {
        listOf(26, 27).filterNot(JavaParserReleaseSupport::supportsRelease).forEach { value ->
            val release = value.toString()
            JavaProjectSourceLevelPolicy.resolve(release, "25") shouldBe
                JavaProjectSourceLevelDecision.Refused(
                    "java.context.sourceLevelUnsupported",
                    "Declared Java release '$release' is outside the installed JDT parser/compiler range " +
                        "8-${JavaProjectSourceLevelPolicy.MAX_SUPPORTED}",
                )
        }
        listOf(26, 27).filter(JavaParserReleaseSupport::supportsRelease).forEach { value ->
            JavaProjectSourceLevelPolicy.resolve(value.toString(), "25") shouldBe
                JavaProjectSourceLevelDecision.Supported(value)
        }
    }

    "refuses source declarations above the installed parser ceiling" {
        (JavaProjectSourceLevelPolicy.MAX_SUPPORTED + 1..JavaProjectSourceLevelPolicy.MAX_SUPPORTED + 2).forEach { value ->
            val sourceLevel = value.toString()
            JavaProjectSourceLevelPolicy.resolve(null, sourceLevel) shouldBe
                JavaProjectSourceLevelDecision.Refused(
                    "java.context.sourceLevelUnsupported",
                    "Declared Java source level '$sourceLevel' is outside the installed JDT parser/compiler range " +
                        "8-${JavaProjectSourceLevelPolicy.MAX_SUPPORTED}",
                )
        }
    }

    "refuses a malformed declared level without falling back to another language level" {
        JavaProjectSourceLevelPolicy.resolve("next", "21") shouldBe
            JavaProjectSourceLevelDecision.Refused(
                "java.context.sourceLevelUnavailable",
                "The declared Java release or source level 'next' could not be resolved",
            )
    }

    "uses the existing default only when no source level is declared" {
        JavaProjectSourceLevelPolicy.resolve(null, null) shouldBe
            JavaProjectSourceLevelDecision.Supported(JavaProjectSourceLevelPolicy.DEFAULT_SOURCE_LEVEL)
    }

    "refuses a source set before binding when its release exceeds the analyzer ceiling" {
        val sourceSet = BuildSourceSet(
            id = "main",
            kind = SourceSetKind.MAIN,
            attributes = mapOf(
                "java.release" to (JavaProjectSourceLevelPolicy.MAX_SUPPORTED + 1).toString(),
                "java.sourceLevel" to JavaProjectSourceLevelPolicy.MAX_SUPPORTED.toString(),
            ),
        )
        val owner = BuildModule("app", "app", Path.of("."), listOf(sourceSet))
        val model = BuildModel("maven-effective-v1", BuildModelStatus.AVAILABLE, listOf(owner))

        val failure = shouldThrow<JavaContextAuthorityException> {
            JavaBuildSourceSetContextResolver.resolve(model, owner, sourceSet)
        }

        failure.code shouldBe "java.context.sourceLevelUnsupported"
    }

    "admits current-GA Java 26 and 27 build metadata independently from parser qualification" {
        JavaProjectBuildMetadataSourceLevelPolicy.MAX_SUPPORTED shouldBe 27
        (26..27).forEach { release ->
            JavaProjectBuildMetadataSourceLevelPolicy.supportsRelease(release) shouldBe true
            JavaProjectBuildMetadataSourceLevelPolicy.resolve(release.toString(), null) shouldBe
                JavaProjectSourceLevelDecision.Supported(release)
        }
        JavaProjectBuildMetadataSourceLevelPolicy.supportsRelease(28) shouldBe false
    }

    "v7 admits compiler-supported Java 26 semantics while preserving v6 metadata behavior" {
        JavaProjectContextSemanticReleasePolicy.requiresMetadataOnly(26, "java-project-context-v7", true, false) shouldBe false
        JavaProjectContextSemanticReleasePolicy.requiresMetadataOnly(26, "java-project-context-v6", true, false) shouldBe true
    }

    "Java 26 v7 cannot acquire semantic authority from a release number without parser support" {
        JavaProjectContextSemanticReleasePolicy.requiresMetadataOnly(26, "java-project-context-v7", false, true) shouldBe true
    }

    "new backend support alone does not unlock general Java 27 or future semantic requests" {
        JavaProjectContextSemanticReleasePolicy.requiresMetadataOnly(27, "java-project-context-v7", true, false) shouldBe true
        JavaProjectContextSemanticReleasePolicy.requiresMetadataOnly(27, "java-project-context-v7", false, true) shouldBe true
        JavaProjectContextSemanticReleasePolicy.requiresMetadataOnly(28, "java-project-context-v7", true, true) shouldBe true
    }

    "the existing explicit Java 27 symbol probe remains separate from general query qualification" {
        JavaProjectContextSemanticReleasePolicy.requiresMetadataOnly(27, "java-project-context-v7", true, true) shouldBe false
        JavaProjectContextSemanticReleasePolicy.requiresMetadataOnly(27, "java-project-context-v6", true, true) shouldBe true
    }

    "refuses metadata releases beyond the current-GA boundary without fallback" {
        val unsupportedRelease = 28
        val refusal = JavaProjectBuildMetadataSourceLevelPolicy.resolve(unsupportedRelease.toString(), "21") as
            JavaProjectSourceLevelDecision.Refused

        refusal.code shouldBe "java.context.sourceLevelUnsupported"
        refusal.message shouldBe "Declared Java release '$unsupportedRelease' is outside the installed compiler metadata range " +
            "${JavaProjectBuildMetadataSourceLevelPolicy.MIN_SUPPORTED}-${JavaProjectBuildMetadataSourceLevelPolicy.MAX_SUPPORTED}"
    }
})
