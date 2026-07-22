package org.refactorkit.java

import org.refactorkit.core.SourceFile
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MavenPomDependencyEditorTest {
    @Test
    fun randomizedWhitespaceNamespaceAndOpaqueXmlRemainByteExactOutsideArtifactText() {
        val random = Random(7)
        repeat(128) { iteration ->
            val newline = if (iteration % 2 == 0) "\n" else "\r\n"
            fun space(): String = " ".repeat(random.nextInt(0, 7))
            val prefix = if (iteration % 3 == 0) "m:" else ""
            val namespace = if (prefix.isEmpty()) "" else " xmlns:m=\"urn:test:maven\""
            val content = buildString {
                append("<?ownership keep='yes'?>").append(newline)
                append("<$prefix" + "project$namespace data=\"a>b\">").append(newline)
                append(space()).append("<$prefix" + "modelVersion>4.0.0</$prefix" + "modelVersion>").append(newline)
                append(space()).append("<$prefix" + "artifactId>consumer</$prefix" + "artifactId>").append(newline)
                append(space()).append("<$prefix" + "dependencies>").append(newline)
                append(space()).append("<!-- opaque dependency comment ").append(iteration).append(" -->").append(newline)
                append(space()).append("<$prefix" + "dependency unknown=\"left>right\">").append(newline)
                append(space()).append("<$prefix" + "groupId>").append(space()).append("x").append(space())
                    .append("</$prefix" + "groupId>").append(newline)
                append(space()).append("<$prefix" + "artifactId>").append(space()).append("source").append(space())
                    .append("</$prefix" + "artifactId>").append(newline)
                append(space()).append("<$prefix" + "version>").append(space()).append("1").append(space())
                    .append("</$prefix" + "version>").append(newline)
                append(space()).append("<$prefix" + "unknown><![CDATA[do-not-touch]]></$prefix" + "unknown>").append(newline)
                append(space()).append("</$prefix" + "dependency>").append(newline)
                append(space()).append("</$prefix" + "dependencies>").append(newline)
                append("</$prefix" + "project>").append(newline)
            }
            val result = assertIs<PomRewriteResult.Edits>(MavenPomDependencyEditor.rewrite(
                SourceFile(Path.of("consumer/pom.xml"), content, "maven-pom"),
                rewrite(),
            ))

            val changed = TextEdits.apply(content, result.edits)

            assertEquals(1, result.edits.size)
            assertEquals(content.replace("source", "destination"), changed)
        }
    }

    @Test
    fun malformedAndNonLiteralOriginsRefuseWithoutProducingEdits() {
        fun result(dependency: String): PomRewriteResult = MavenPomDependencyEditor.rewrite(
            SourceFile(
                Path.of("consumer/pom.xml"),
                "<project><dependencies>$dependency</dependencies></project>",
                "maven-pom",
            ),
            rewrite(),
        )
        assertEquals(
            "mavenOwnership.propertyManagedCoordinate",
            assertIs<PomRewriteResult.Refused>(result(
                "<dependency><groupId>x</groupId><artifactId>\${artifact}</artifactId><version>1</version></dependency>",
            )).code,
        )
        assertEquals(
            "mavenOwnership.ambiguousPomOrigin",
            assertIs<PomRewriteResult.Refused>(MavenPomDependencyEditor.rewrite(
                SourceFile(Path.of("consumer/pom.xml"), "<project><dependencies>", "maven-pom"),
                rewrite(),
            )).code,
        )
    }

    private fun rewrite() = MavenDependencyRewrite(
        Path.of("consumer/pom.xml"),
        MavenDependencyIdentity("x", "source", "1"),
        MavenDependencyIdentity("x", "destination", "1"),
    )
}
