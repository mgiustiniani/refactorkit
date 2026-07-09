package org.refactorkit.java

import org.refactorkit.core.RiskLevel
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaFrameworkDetectorTest {

    @Test
    fun detectsSpringJpaAndJacksonAnnotations() {
        val file = org.refactorkit.core.SourceFile(
            path = java.nio.file.Paths.get("src/main/java/com/example/UserEntity.java"),
            languageId = "java",
            content = """
                package com.example;
                import jakarta.persistence.Entity;
                import com.fasterxml.jackson.annotation.JsonTypeName;
                import org.springframework.stereotype.Service;

                @Service
                @Entity
                @JsonTypeName("user")
                public class UserEntity {}
            """.trimIndent(),
        )

        val findings = JavaFrameworkDetector.detect(file)
        assertTrue(findings.any { it.framework == JavaFramework.SPRING && it.annotationName == "Service" })
        assertTrue(findings.any { it.framework == JavaFramework.JPA && it.annotationName == "Entity" })
        assertTrue(findings.any { it.framework == JavaFramework.JACKSON && it.annotationName == "JsonTypeName" })
    }

    @Test
    fun ignoresAnnotationsInsideCommentsAndStrings() {
        val content = "package com.example;\n" +
            "// @Service should be ignored\n" +
            "/* @Entity should also be ignored */\n" +
            "public class Foo {\n" +
            "    String text = \"@JsonTypeName ignored\";\n" +
            "    String block = \"\"\"\n" +
            "        @Controller ignored\n" +
            "    \"\"\";\n" +
            "}\n"
        val file = org.refactorkit.core.SourceFile(
            path = java.nio.file.Paths.get("Foo.java"),
            content = content,
            languageId = "java",
        )

        assertTrue(JavaFrameworkDetector.detect(file).isEmpty())
    }

    @Test
    fun renameSpringClassEscalatesRiskAndWarns() {
        val root = tempProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class UserService {}
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = JavaRenameClassPlanner(JavaLanguageAdapter()).preview(snap, "com.example.UserService", "AccountService")

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status)
        assertEquals(RiskLevel.HIGH, plan.riskLevel)
        assertTrue(plan.warnings.any { it.contains("Spring", ignoreCase = true) && it.contains("bean", ignoreCase = true) })
        assertTrue(plan.warnings.any { it.contains("@Service") })
    }

    @Test
    fun moveJpaEntityEscalatesRiskAndWarns() {
        val root = tempProject(
            "src/main/java/com/example/UserEntity.java" to """
                package com.example;
                import jakarta.persistence.Entity;
                @Entity
                public class UserEntity {}
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = JavaMoveClassPlanner(JavaLanguageAdapter()).preview(snap, "com.example.UserEntity", "com.example.domain")

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status)
        assertEquals(RiskLevel.HIGH, plan.riskLevel)
        assertTrue(plan.warnings.any { it.contains("JPA", ignoreCase = true) || it.contains("entity", ignoreCase = true) })
        assertTrue(plan.warnings.any { it.contains("@Entity") })
    }

    @Test
    fun safeDeleteJacksonDtoEscalatesRiskAndWarns() {
        val root = tempProject(
            "src/main/java/com/example/UserDto.java" to """
                package com.example;
                import com.fasterxml.jackson.annotation.JsonProperty;
                public class UserDto {
                    @JsonProperty("user_name")
                    public String name;
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = JavaSafeDeletePlanner(JavaLanguageAdapter()).preview(snap, "com.example.UserDto", force = false)

        assertEquals(org.refactorkit.core.PatchStatus.PREVIEW, plan.status)
        assertEquals(RiskLevel.HIGH, plan.riskLevel)
        assertTrue(plan.warnings.any { it.contains("Jackson", ignoreCase = true) || it.contains("serialized", ignoreCase = true) })
        assertTrue(plan.warnings.any { it.contains("@JsonProperty") })
    }

    @Test
    fun unannotatedRenameRemainsLowRisk() {
        val root = tempProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = JavaRenameClassPlanner(JavaLanguageAdapter()).preview(snap, "com.example.UserManager", "AccountManager")

        assertEquals(RiskLevel.LOW, plan.riskLevel)
        assertFalse(plan.warnings.any { it.contains("annotation locations", ignoreCase = true) })
    }

    private fun tempProject(vararg entries: Pair<String, String>): java.nio.file.Path {
        val root = Files.createTempDirectory("framework-detector-test")
        for ((rel, content) in entries) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root
    }
}
