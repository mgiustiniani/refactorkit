package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.refactorkit.core.*
import java.nio.file.Path

/** REQ-JAVA-PROJECT-CONTEXT-001: a successful single-unit parse cannot erase cross-source type collisions. */
class JavaCrossSourceTypeCollisionsSpec : StringSpec({
    val main = JavaTypeCompilationScope("maven-effective-v1", "app", "main")
    fun declaration(path: String, binary: String = "demo.Duplicate", scope: JavaTypeCompilationScope = main) =
        JavaNativeTypeDeclaration(scope, binary, SourceLocation(Path.of(path), SourceRange(SourcePosition(0, 20), SourcePosition(0, 29))))
    fun collisions(vararg values: JavaNativeTypeDeclaration) = JavaCrossSourceTypeCollisions.diagnostics(values.toList())

    "two captured source declarations of one binary type report both exact locations" {
        val a = declaration("src/main/java/demo/A.java"); val b = declaration("src/main/java/demo/B.java")
        val errors = collisions(a, b)
        errors.map { it.location }.toSet() shouldBe setOf(a.location, b.location)
        errors.size shouldBe 2
        errors.all { it.severity == Diagnostic.Severity.ERROR && it.code == "java.jdt.typeResolution" &&
            it.evidence == DiagnosticEvidence.COMPILER && it.message.contains("demo.Duplicate") } shouldBe true
    }
    "different roots in one compilation scope do not hide a duplicate binary declaration" {
        val a = declaration("src/main/java/demo/A.java"); val b = declaration("generated/java/demo/B.java")
        collisions(b, a).map { it.location } shouldBe listOf(b.location, a.location).sortedBy { it.path.toString() }
    }
    "independent modules may define the same binary name" {
        collisions(declaration("one/src/main/java/demo/A.java"), declaration("two/src/main/java/demo/B.java", scope = main.copy(moduleId = "other"))) shouldBe emptyList()
    }
    "test compilation may shadow a main binary without merging their compilation scopes" {
        collisions(declaration("src/main/java/demo/A.java"), declaration("src/test/java/demo/B.java", scope = main.copy(sourceSetId = "test"))) shouldBe emptyList()
    }
    "the same simple name in different enclosing binary types is not a collision" {
        collisions(declaration("A.java", "demo.A\$Nested"), declaration("B.java", "demo.B\$Nested")) shouldBe emptyList()
    }
    "repeated observations of one physical declaration are not separate declarations" {
        val a = declaration("src/main/java/demo/A.java")
        collisions(a, a.copy()) shouldBe emptyList()
    }
})
