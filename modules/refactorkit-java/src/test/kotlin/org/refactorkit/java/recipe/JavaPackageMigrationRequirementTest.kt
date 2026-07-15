package org.refactorkit.java.recipe

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPreviewRenderer
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaProjectScanner
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaPackageMigrationRequirementTest {
    private val oldPackage = "com.acme.cluster.control.ratis"
    private val newPackage = "com.acme.storage.infrastructure.control.ratis"

    @Test
    fun exactPackageMigrationCoversProductionTestsPackageInfoAndExcludesGeneratedSources() {
        val root = fixture()
        val before = sourceTree(root)
        val result = RecipeEngine().run(recipe(), params(), root, dryRun = true)

        val preview = assertIs<RecipeResult.Preview>(result)
        val rendered = PatchPreviewRenderer(root).render(preview.recipePlan)
        assertTrue(rendered.contains("com/acme/storage/infrastructure/control/ratis/PublicAdapter.java"))
        assertFalse(rendered.contains("target/generated-sources"))
        assertTrue(preview.recipePlan.affectedFiles.none { it.toString().replace('\\', '/').contains("target/") })

        val staged = WorkspaceEditSimulator.apply(JavaProjectScanner().scan(root), preview.recipePlan.workspaceEdit)
        fun staged(path: String) = staged.files.single { it.path == Path.of(path) }.content
        assertTrue(staged("adapter/src/main/java/com/acme/storage/infrastructure/control/ratis/PublicAdapter.java")
            .contains("PackageHelper"))
        assertTrue(staged("adapter/src/main/java/com/acme/storage/infrastructure/control/ratis/PackageHelper.java")
            .contains("class PackageHelper"))
        assertTrue(staged.files.any {
            it.path == Path.of("adapter/src/main/java/com/acme/storage/infrastructure/control/ratis/package-info.java")
        })
        assertTrue(staged.files.any {
            it.path == Path.of("adapter/src/test/java/com/acme/storage/infrastructure/control/ratis/PublicAdapterTest.java")
        })
        val subpackage = "adapter/src/test/java/com/acme/cluster/control/ratis/sub/SubpackageTest.java"
        assertTrue(staged(subpackage).contains("package $oldPackage.sub;"))
        assertTrue(staged(subpackage).contains("import $newPackage.PublicAdapter;"))
        assertTrue(staged(subpackage).contains(
            "src/main/java/com/acme/storage/infrastructure/control/ratis/PublicAdapter.java",
        ))
        assertTrue(staged(subpackage).contains("\"$oldPackage.sub\""))
        assertTrue(staged("bootstrap/src/main/java/com/acme/bootstrap/Bootstrap.java")
            .contains("import $newPackage.PublicAdapter;"))
        assertTrue(staged("bootstrap/src/main/java/com/acme/bootstrap/Bootstrap.java")
            .contains("\"$newPackage\""))
        assertTrue(staged("test-consumer/src/test/java/com/acme/consumer/AdapterConsumerTest.java")
            .contains("import $newPackage.PublicAdapter;"))
        assertEquals(before, sourceTree(root), "preview modified fixture bytes")
        assertFalse(root.resolve(".refactorkit").exists(), "preview created transaction metadata")

        val summary = preview.stepPlans.first { it.stepType == "movePackage" }.message
        assertTrue(summary.contains("package-info=1"), summary)
        assertTrue(summary.contains("class="), summary)
    }

    @Test
    fun applyUsesOneTransactionAndRollbackRestoresFixtureByteForByte() {
        val root = fixture()
        val before = sourceTree(root)
        val result = RecipeEngine().run(recipe(), params(), root, dryRun = false)
        val applied = assertIs<RecipeResult.Applied>(result)
        assertEquals(1, applied.transactionIds.size)
        assertEquals(1, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size)

        val transaction = TransactionLog(root.resolve(".refactorkit/transactions"))
            .load(TransactionId.parseOrNull(applied.transactionIds.single())!!)
        assertNotNull(transaction)
        assertIs<ApplyResult.Applied>(PatchEngine(root).rollback(transaction))
        assertEquals(before, sourceTree(root))
    }

    @Test
    fun mavenTestSourceSetInheritsSiblingProductionDependencies() {
        val root = fixture()
        val errors = org.refactorkit.java.JavaLanguageAdapter()
            .diagnostics(JavaProjectScanner().scan(root))
            .filter { it.severity == Diagnostic.Severity.ERROR }
        assertTrue(errors.isEmpty(), errors.joinToString("\n") { it.message })
    }

    @Test
    fun diagnosticsAreBaselineAwareAndStrictModeRemainsAvailable() {
        val root = fixture()
        val baseline = diagnostic("baseline", "port/src/main/java/com/acme/port/Broken.java")
        val engine = RecipeEngine(diagnosticsProvider = { listOf(baseline) })
        val preview = assertIs<RecipeResult.Preview>(engine.run(recipe(), params(), root, dryRun = true))
        val delta = preview.stepPlans.first { it.stepType == "runDiagnostics" }.diagnosticDelta
        assertNotNull(delta)
        assertEquals(1, delta.baselineErrors)
        assertEquals(1, delta.stagedErrors)
        assertEquals(0, delta.introducedErrors)
        assertEquals(0, delta.resolvedErrors)
        assertEquals(1, delta.unchangedErrors)

        val strict = RecipeLoader.load(recipeYaml(strict = true).byteInputStream())
        val refused = assertIs<RecipeResult.Failed>(engine.run(strict, params(), root, dryRun = true))
        assertTrue(refused.reason.contains("strict", ignoreCase = true), refused.reason)
    }

    @Test
    fun introducedDiagnosticErrorsRefuseWithDeltaCounts() {
        val root = fixture()
        val baseline = diagnostic("baseline", "port/src/main/java/com/acme/port/Broken.java")
        val introduced = diagnostic("introduced", "bootstrap/src/main/java/com/acme/bootstrap/Bootstrap.java")
        val engine = RecipeEngine(diagnosticsProvider = { snapshot ->
            if (snapshot.files.any { it.content.contains("package $newPackage;") }) listOf(baseline, introduced)
            else listOf(baseline)
        })
        val failed = assertIs<RecipeResult.Failed>(engine.run(recipe(), params(), root, dryRun = true))
        val delta = failed.stepPlans.first { it.stepType == "runDiagnostics" }.diagnosticDelta
        assertNotNull(delta)
        assertEquals(1, delta.introducedErrors)
        assertTrue(failed.reason.contains("introduced=1"), failed.reason)
    }

    @Test
    fun malformedPackagesAndTargetConflictsFailClosed() {
        val malformed = assertIs<RecipeResult.Failed>(RecipeEngine().run(
            recipe(), mapOf("oldPackage" to oldPackage, "newPackage" to "bad..package"), fixture(), true,
        ))
        assertTrue(malformed.reason.contains("package", ignoreCase = true))

        val conflictRoot = fixture()
        write(conflictRoot,
            "adapter/src/main/java/com/acme/storage/infrastructure/control/ratis/PublicAdapter.java",
            "package $newPackage;\npublic class PublicAdapter {}\n")
        val conflict = assertIs<RecipeResult.Failed>(RecipeEngine().run(recipe(), params(), conflictRoot, true))
        assertTrue(conflict.reason.contains("conflict", ignoreCase = true) ||
            conflict.reason.contains("already exists", ignoreCase = true), conflict.reason)
        assertFalse(conflictRoot.resolve(".refactorkit").exists())
    }

    private fun recipe() = RecipeLoader.load(recipeYaml(strict = false).byteInputStream())

    private fun recipeYaml(strict: Boolean) = """
        id: java.package-migration-requirement
        name: Package migration requirement
        language: java
        parameters:
          oldPackage: string
          newPackage: string
        steps:
          - type: movePackage
            from: "{{ oldPackage }}"
            to: "{{ newPackage }}"
          - type: organizeImports
          - type: runDiagnostics
            strict: $strict
          - type: summarizePatch
    """.trimIndent()

    private fun params() = mapOf("oldPackage" to oldPackage, "newPackage" to newPackage)

    private fun fixture(): Path {
        val root = Files.createTempDirectory("rk-package-migration")
        write(root, "pom.xml", parentPom())
        write(root, "port/pom.xml", modulePom("port"))
        write(root, "adapter/pom.xml", modulePom("adapter", "port"))
        write(root, "bootstrap/pom.xml", modulePom("bootstrap", "adapter"))
        write(root, "test-consumer/pom.xml", modulePom("test-consumer", "adapter"))
        write(root, "port/src/main/java/com/acme/port/ClusterPort.java",
            "package com.acme.port;\npublic interface ClusterPort {}\n")
        write(root, "port/src/main/java/com/acme/port/Broken.java",
            "package com.acme.port;\npublic class Broken {}\n")
        write(root, "adapter/src/main/java/com/acme/cluster/control/ratis/PublicAdapter.java", """
            package $oldPackage;
            import com.acme.port.ClusterPort;
            public class PublicAdapter implements ClusterPort {
                private final PackageHelper helper = new PackageHelper();
                public String value() { return helper.value(); }
            }
        """.trimIndent() + "\n")
        write(root, "adapter/src/main/java/com/acme/cluster/control/ratis/PackageHelper.java", """
            package $oldPackage;
            final class PackageHelper { String value() { return "ok"; } }
        """.trimIndent() + "\n")
        write(root, "adapter/src/main/java/com/acme/cluster/control/ratis/package-info.java", """
            @Deprecated
            package $oldPackage;
        """.trimIndent() + "\n")
        write(root, "adapter/src/test/java/com/acme/cluster/control/ratis/PublicAdapterTest.java",
            "package $oldPackage;\nclass PublicAdapterTest { PublicAdapter value; PackageHelper helper; }\n")
        write(root, "adapter/src/test/java/com/acme/cluster/control/ratis/sub/SubpackageTest.java",
            "package $oldPackage.sub;\nimport $oldPackage.PublicAdapter;\n" +
                "class SubpackageTest { PublicAdapter value; String source = " +
                "\"src/main/java/com/acme/cluster/control/ratis/PublicAdapter.java\"; " +
                "String glue = \"$oldPackage.sub\"; }\n")
        write(root, "adapter/target/generated-sources/protobuf/java/com/acme/cluster/control/ratis/GeneratedMessage.java",
            "// generated - do not edit\npackage $oldPackage;\npublic class GeneratedMessage {}\n")
        write(root, "bootstrap/src/main/java/com/acme/bootstrap/Bootstrap.java",
            "package com.acme.bootstrap;\nimport $oldPackage.PublicAdapter;\n" +
                "class Bootstrap { PublicAdapter adapter; String expectedPackage = \"$oldPackage\"; }\n")
        write(root, "test-consumer/src/test/java/com/acme/consumer/AdapterConsumerTest.java",
            "package com.acme.consumer;\nimport $oldPackage.PublicAdapter;\nclass AdapterConsumerTest { PublicAdapter adapter; }\n")
        return root
    }

    private fun parentPom() = """
        <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId><artifactId>fixture</artifactId><version>1</version><packaging>pom</packaging>
          <modules><module>port</module><module>adapter</module><module>bootstrap</module><module>test-consumer</module></modules>
          <properties><maven.compiler.release>17</maven.compiler.release></properties>
        </project>
    """.trimIndent()

    private fun modulePom(artifact: String, dependency: String? = null) = """
        <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
          <parent><groupId>com.acme</groupId><artifactId>fixture</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
          <artifactId>$artifact</artifactId>
          ${dependency?.let { "<dependencies><dependency><groupId>com.acme</groupId><artifactId>$it</artifactId><version>1</version></dependency></dependencies>" }.orEmpty()}
        </project>
    """.trimIndent()

    private fun diagnostic(message: String, path: String) = Diagnostic(
        message, Diagnostic.Severity.ERROR,
        SourceLocation(Path.of(path), SourceRange(SourcePosition(0, 0), SourcePosition(0, 1))),
        code = "fixture.error",
    )

    private fun write(root: Path, relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        path.writeText(content)
    }

    private fun sourceTree(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile)
            .filter { !it.startsWith(root.resolve(".refactorkit")) }
            .toList()
            .associate { path -> root.relativize(path).toString().replace('\\', '/') to sha256(path) }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
