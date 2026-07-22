package org.refactorkit.java

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaMoveAcrossMavenModulesPlannerTest {
    @Test
    fun previewsAppliesAndRollsBackOneAtomicJavaAndLiteralPomMigration() {
        val root = reactorFixture()
        val scanner = JavaProjectScanner()
        val snapshot = scanner.scan(root)
        val planner = JavaMoveAcrossMavenModulesPlanner()
        val originalTree = treeHashes(root)
        val originalConsumerPom = Files.readString(root.resolve(CONSUMER_POM))

        val plan = planner.preview(snapshot, FROM, TO, listOf(rewrite()))

        assertEquals(PatchStatus.PREVIEW, plan.status, "${plan.refusalCode}: ${plan.summary}")
        assertEquals(JavaMoveAcrossMavenModulesPlanner.OPERATION, plan.operation)
        assertEquals(RefactoringEvidence.JDT_BINDING, plan.evidence)
        assertEquals(4, plan.workspaceEdit.edits.size)
        assertEquals(3, plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().size)
        assertEquals(1, plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>().size)
        assertFalse(root.resolve(TO).exists(), "preview must not mutate the workspace")
        assertEquals(originalConsumerPom, Files.readString(root.resolve(CONSUMER_POM)))
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR }, plan.diagnosticsAfterPreview.toString())

        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        val stagedPom = staged.auxiliaryFiles.single { it.path == CONSUMER_POM }.content
        assertEquals(originalConsumerPom.replace("<artifactId>source</artifactId>", "<artifactId>destination</artifactId>"), stagedPom)
        assertTrue(stagedPom.contains("<!-- dependency comment retained byte-for-byte -->"))
        assertTrue(stagedPom.contains("<unknown keep=\"yes\">opaque</unknown>"))

        val applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(
            plan,
            snapshot,
            ApplyAuthorization.explicit("maven-ownership-test"),
            DiagnosticsGate.enabled("java-maven-ownership", planner::diagnostics),
        ))
        assertFalse(root.resolve(FROM).resolve("example/shared/SharedValue.java").exists())
        assertTrue(root.resolve(TO).resolve("example/shared/SharedValue.java").exists())
        assertEquals(stagedPom, Files.readString(root.resolve(CONSUMER_POM)))
        assertTrue(planner.diagnostics(scanner.scan(root)).none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })

        assertIs<ApplyResult.Applied>(PatchEngine(root).rollback(applied.transaction))
        assertEquals(originalTree, treeHashes(root))
        assertFalse(root.resolve(TO).exists(), "rollback must remove created destination directories")
    }

    @Test
    fun sharedBuildModelOwnershipRemainsAuthoritativeWithoutLegacyModuleRoots() {
        val root = reactorFixture()
        val snapshot = JavaProjectScanner().scan(root)
        val compatibilityStripped = snapshot.copy(modules = snapshot.modules.map { module ->
            module.copy(
                sourceRoots = emptyList(),
                mainSourceRoots = emptyList(),
                testSourceRoots = emptyList(),
                generatedSourceRoots = emptyList(),
                generatedTestSourceRoots = emptyList(),
            )
        })

        val plan = JavaMoveAcrossMavenModulesPlanner().preview(
            compatibilityStripped, FROM, TO, listOf(rewrite()),
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, "${plan.refusalCode}: ${plan.summary}")
    }

    @Test
    fun languageAdapterExposesTheFirstOwnershipMigrationRow() {
        val root = reactorFixture()
        val adapter = JavaLanguageAdapter()
        val plan = adapter.applyRefactoring(RefactoringRequest(
            operation = JavaMoveAcrossMavenModulesPlanner.OPERATION,
            arguments = mapOf(
                "from" to FROM.toString(),
                "to" to TO.toString(),
                "dependencyPom" to CONSUMER_POM.toString(),
                "sourceGroupId" to SOURCE.groupId,
                "sourceArtifactId" to SOURCE.artifactId,
                "sourceVersion" to SOURCE.version,
                "destinationGroupId" to DESTINATION.groupId,
                "destinationArtifactId" to DESTINATION.artifactId,
                "destinationVersion" to DESTINATION.version,
            ),
            snapshot = JavaProjectScanner().scan(root),
        ))

        assertEquals(PatchStatus.PREVIEW, plan.status, "${plan.refusalCode}: ${plan.summary}")
        assertTrue(adapter.availableRefactorings(org.refactorkit.core.CodeSelection(
            org.refactorkit.core.SourceLocation(
                Path.of("source/src/main/java/example/shared/SharedValue.java"),
                org.refactorkit.core.SourceRange(
                    org.refactorkit.core.SourcePosition(0, 0),
                    org.refactorkit.core.SourcePosition(0, 0),
                ),
            ),
        )).any { it.id == JavaMoveAcrossMavenModulesPlanner.OPERATION })
    }

    @Test
    fun refusesMissingIntentIdentityMismatchPropertiesAndDuplicateOrigins() {
        fun plan(
            pomTransform: (String) -> String = { it },
            rewrites: List<MavenDependencyRewrite> = listOf(rewrite()),
        ): org.refactorkit.core.PatchPlan {
            val root = reactorFixture()
            val pom = root.resolve(CONSUMER_POM)
            Files.writeString(pom, pomTransform(Files.readString(pom)))
            return JavaMoveAcrossMavenModulesPlanner().preview(JavaProjectScanner().scan(root), FROM, TO, rewrites)
        }

        assertEquals("mavenOwnership.dependencyRewriteRequired", plan(rewrites = emptyList()).refusalCode)
        assertEquals(
            "mavenOwnership.dependencyRewriteMismatch",
            plan(rewrites = listOf(rewrite().copy(source = SOURCE.copy(version = "2")))).refusalCode,
        )
        assertEquals(
            "mavenOwnership.propertyManagedCoordinate",
            plan(pomTransform = { it.replace("<artifactId>source</artifactId>", "<artifactId>\${source.artifact}</artifactId>") }).refusalCode,
        )
        assertEquals(
            "mavenOwnership.ambiguousPomOrigin",
            plan(pomTransform = { pom -> pom.replace("</dependencies>", dependencyBlock() + "</dependencies>") }).refusalCode,
        )
        assertEquals(
            "mavenOwnership.ambiguousPomOrigin",
            plan(pomTransform = { pom ->
                pom.replace("<dependencies>", "<profiles><profile><id>ownership-profile</id><dependencies>")
                    .replace("</dependencies>", "</dependencies></profile></profiles>")
            }).refusalCode,
        )
        assertEquals(
            PatchStatus.PREVIEW,
            plan(
                pomTransform = { pom -> pom.replace("</dependencies>", dependencyBlock() + "</dependencies>") },
                rewrites = listOf(rewrite().copy(allIdenticalOccurrences = true)),
            ).status,
        )
    }

    @Test
    fun elevatesFrameworkAndQuotedConfigurationRiskAndRefusesGeneratedJava() {
        val riskyRoot = reactorFixture()
        Files.writeString(
            riskyRoot.resolve(FROM).resolve("example/shared/SharedValue.java"),
            "package example.shared; @interface Service {} " +
                "@Service public record SharedValue(String value) {}\n",
        )
        val consumer = riskyRoot.resolve("consumer/src/main/java/example/consumer/Consumer.java")
        Files.writeString(
            consumer,
            Files.readString(consumer).replace(
                "public class Consumer {",
                "public class Consumer { String reflected = \"example.shared.SharedValue\";",
            ),
        )

        val risky = JavaMoveAcrossMavenModulesPlanner().preview(
            JavaProjectScanner().scan(riskyRoot), FROM, TO, listOf(rewrite()),
        )

        assertEquals(PatchStatus.PREVIEW, risky.status, "${risky.refusalCode}: ${risky.summary}")
        assertEquals(org.refactorkit.core.RiskLevel.HIGH, risky.riskLevel)
        assertTrue(risky.warnings.any { "SPRING" in it && "module ownership" in it })
        assertTrue(risky.warnings.any { "Quoted moved-type identities" in it })

        val generatedRoot = reactorFixture()
        val generatedSource = generatedRoot.resolve(FROM).resolve("example/shared/SharedValue.java")
        Files.writeString(generatedSource, "// Generated - do not edit\n" + Files.readString(generatedSource))
        val generated = JavaMoveAcrossMavenModulesPlanner().preview(
            JavaProjectScanner().scan(generatedRoot), FROM, TO, listOf(rewrite()),
        )
        assertEquals("mavenOwnership.generated", generated.refusalCode)
    }

    @Test
    fun refusesAStillRequiredMovedTypeFromAnotherSourceRoot() {
        val root = reactorFixture(remainingSourceDependsOnMoved = true)

        val plan = JavaMoveAcrossMavenModulesPlanner().preview(
            JavaProjectScanner().scan(root), FROM, TO, listOf(rewrite()),
        )

        assertEquals("mavenOwnership.remainingSourceDependency", plan.refusalCode)
    }

    @Test
    fun refusesMissingDestinationDependencyWithItsStableCode() {
        val root = reactorFixture(sourceDependsOnHelper = true)

        val plan = JavaMoveAcrossMavenModulesPlanner().preview(
            JavaProjectScanner().scan(root), FROM, TO, listOf(rewrite()),
        )

        assertEquals("mavenOwnership.destinationDependencyMissing", plan.refusalCode)
    }

    @Test
    fun refusesSourceSetMismatchAndStagedDependencyCycle() {
        val mismatchRoot = reactorFixture()
        val mismatch = JavaMoveAcrossMavenModulesPlanner().preview(
            JavaProjectScanner().scan(mismatchRoot),
            FROM,
            Path.of("destination/src/test/java"),
            listOf(rewrite()),
        )
        assertEquals("mavenOwnership.sourceSetMismatch", mismatch.refusalCode)

        val cycleRoot = reactorFixture(destinationDependsOnConsumer = true)
        val cycle = JavaMoveAcrossMavenModulesPlanner().preview(
            JavaProjectScanner().scan(cycleRoot), FROM, TO, listOf(rewrite()),
        )
        assertEquals("mavenOwnership.cycle", cycle.refusalCode)
    }

    private fun reactorFixture(
        destinationDependsOnConsumer: Boolean = false,
        sourceDependsOnHelper: Boolean = false,
        remainingSourceDependsOnMoved: Boolean = false,
    ): Path {
        val root = Files.createTempDirectory("refactorkit-maven-ownership")
        val helperModule = if (sourceDependsOnHelper) "<module>helper</module>" else ""
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <modules><module>source</module><module>destination</module><module>consumer</module>$helperModule</modules>
            </project>
        """.trimIndent())
        childPom(root, "source", if (sourceDependsOnHelper) "helper" else null)
        if (remainingSourceDependsOnMoved) {
            val sourcePom = root.resolve("source/pom.xml")
            Files.writeString(sourcePom, Files.readString(sourcePom).replace(
                "</project>",
                """
                    <build><plugins><plugin>
                      <groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId>
                      <executions><execution><goals><goal>add-source</goal></goals>
                        <configuration><sources><source>src/remaining/java</source></sources></configuration>
                      </execution></executions>
                    </plugin></plugins></build>
                    </project>
                """.trimIndent(),
            ))
            val remaining = root.resolve("source/src/remaining/java/example/remaining/Remaining.java")
            Files.createDirectories(remaining.parent)
            Files.writeString(
                remaining,
                "package example.remaining; import example.shared.SharedValue; " +
                    "public class Remaining { SharedValue value; }\n",
            )
        }
        childPom(root, "destination", if (destinationDependsOnConsumer) "consumer" else null)
        childPom(root, "consumer", "source", preserveMarkup = true)
        if (sourceDependsOnHelper) {
            childPom(root, "helper")
            val helper = root.resolve("helper/src/main/java/example/helper/Helper.java")
            Files.createDirectories(helper.parent)
            Files.writeString(helper, "package example.helper; public class Helper {}\n")
        }
        val source = root.resolve(FROM).resolve("example/shared")
        Files.createDirectories(source)
        val helperImport = if (sourceDependsOnHelper) "import example.helper.Helper;\n" else ""
        val helperField = if (sourceDependsOnHelper) " static Helper helper;" else ""
        Files.writeString(source.resolve("SharedValue.java"), "package example.shared;\n${helperImport}public record SharedValue(String value) {$helperField}\n")
        Files.writeString(source.resolve("package-info.java"), "@Deprecated\npackage example.shared;\n")
        Files.writeString(root.resolve(FROM).resolve("module-info.java"), "module example.source { exports example.shared; }\n")
        val consumer = root.resolve("consumer/src/main/java/example/consumer/Consumer.java")
        Files.createDirectories(consumer.parent)
        Files.writeString(consumer, "package example.consumer;\nimport example.shared.SharedValue;\npublic class Consumer { SharedValue value = new SharedValue(\"ok\"); }\n")
        return root
    }

    private fun childPom(root: Path, module: String, dependency: String? = null, preserveMarkup: Boolean = false) {
        Files.createDirectories(root.resolve(module))
        val dependencies = dependency?.let {
            """
              <dependencies>
                <!-- dependency comment retained byte-for-byte -->
                <dependency>
                  <groupId>example</groupId>
                  <artifactId>$it</artifactId>
                  <version>1</version>
                  <unknown keep="yes">opaque</unknown>
                </dependency>
              </dependencies>
            """.trimIndent().prependIndent("  ")
        }.orEmpty()
        Files.writeString(root.resolve("$module/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>$module</artifactId>
            $dependencies
            </project>
        """.trimIndent() + if (preserveMarkup) "\n" else "")
    }

    private fun rewrite() = MavenDependencyRewrite(CONSUMER_POM, SOURCE, DESTINATION)

    private fun dependencyBlock(): String = """
        <dependency>
          <groupId>example</groupId>
          <artifactId>source</artifactId>
          <version>1</version>
        </dependency>
    """.trimIndent().prependIndent("    ") + "\n"

    private fun treeHashes(root: Path): Map<String, String> = Files.walk(root).use { stream ->
        stream.filter(Files::isRegularFile)
            .filter { !root.relativize(it).toString().replace('\\', '/').startsWith(".refactorkit/") }
            .toList().associate { path ->
                root.relativize(path).toString().replace('\\', '/') to MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }
    }

    companion object {
        private val FROM = Path.of("source/src/main/java")
        private val TO = Path.of("destination/src/main/java")
        private val CONSUMER_POM = Path.of("consumer/pom.xml")
        private val SOURCE = MavenDependencyIdentity("example", "source", "1")
        private val DESTINATION = MavenDependencyIdentity("example", "destination", "1")
    }
}
