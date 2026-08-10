package org.refactorkit.jvm

import org.refactorkit.core.Symbol
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscoveryPolicy
import org.refactorkit.kotlin.KotlinToolchainRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedJvmIdentityIndexTest {
    @Test
    fun joinsDescriptorExactJavaAndKotlinDeclarationsAndReferencesInBothDirections() {
        val root = Files.createTempDirectory("refactorkit-shared-jvm-identity")
        root.resolve("pom.xml").writeText("""
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
              <artifactId>shared-jvm</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
        source(root, "src/main/java/fixture/JavaApi.java", """
            package fixture;
            public class JavaApi {
              public JavaApi() {}
              public String label(String value) { return value; }
            }
        """.trimIndent() + "\n")
        source(root, "src/main/kotlin/fixture/KotlinApi.kt", """
            package fixture
            class KotlinApi { fun answer(value: Int): String = value.toString() }
            fun useJava(): String = JavaApi().label("value")
        """.trimIndent() + "\n")
        source(root, "src/main/java/fixture/JavaCaller.java", """
            package fixture;
            class JavaCaller { String run(KotlinApi api) { return api.answer(1); } }
        """.trimIndent() + "\n")

        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val javaBase = snapshot.copy(files = snapshot.files.filter { it.path.fileName.toString() == "JavaApi.java" })
        var javaAnalysis: JdtJavaSemanticAnalysisResult? = null
        var kotlinResult: KotlinCompilerDiagnosticsResult? = null

        val javaCompilation = JavaEphemeralCompiler().compile(javaBase) { javaOutput ->
            kotlinResult = KotlinCompilerDiagnostics(toolchain).analyzeWithAdditionalClasspathAndCompiledOutput(
                snapshot,
                listOf(javaOutput),
            ) { kotlinOutput ->
                javaAnalysis = JdtJavaSemanticAnalyzer().analyze(
                    snapshot,
                    additionalClasspathEntries = listOf(kotlinOutput),
                )
            }
        }

        assertIs<JavaEphemeralCompilationResult.Available>(javaCompilation, javaCompilation.toString())
        val kotlinAvailable = assertIs<KotlinCompilerDiagnosticsResult.Available>(kotlinResult, kotlinResult.toString())
        val kotlinSymbols = KotlinCompilerSymbolsResult.Available(
            index = requireNotNull(kotlinAvailable.symbols),
            attestation = kotlinAvailable.attestation.copy(backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND),
            usages = kotlinAvailable.usages,
            externalTypeUsages = kotlinAvailable.externalTypeUsages,
            externalCallableUsages = kotlinAvailable.externalCallableUsages,
            declarations = kotlinAvailable.declarations,
        )
        val projected = assertIs<SharedJvmIdentityProjection.Available>(
            SharedJvmIdentityProjector.project(snapshot, requireNotNull(javaAnalysis), kotlinSymbols),
        ).index

        assertTrue(projected.complete)
        assertTrue(!projected.truncated)
        assertTrue(projected.provenanceSha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(projected.declarations.size, projected.declarations.map { it.identity.id }.distinct().size)
        assertTrue(projected.declarations.any {
            it.languageId == "java" && it.identity == SharedJvmIdentity(
                SharedJvmIdentityKind.CALLABLE,
                "fixture.JavaApi",
                "label",
                "(Ljava/lang/String;)Ljava/lang/String;",
            )
        })
        assertTrue(projected.declarations.any {
            it.languageId == "kotlin" && it.sourceKind == Symbol.Kind.FUNCTION &&
                it.identity == SharedJvmIdentity(
                    SharedJvmIdentityKind.CALLABLE,
                    "fixture.KotlinApi",
                    "answer",
                    "(I)Ljava/lang/String;",
                )
        })
        assertTrue(projected.crossLanguageReferences.any {
            it.sourceLanguageId == "kotlin" && it.targetLanguageId == "java" &&
                it.identity.memberName == "label" && it.identity.descriptor == "(Ljava/lang/String;)Ljava/lang/String;"
        })
        assertTrue(projected.crossLanguageReferences.any {
            it.sourceLanguageId == "java" && it.targetLanguageId == "kotlin" &&
                it.identity.memberName == "answer" && it.identity.descriptor == "(I)Ljava/lang/String;"
        })
        assertTrue(projected.crossLanguageReferences.all {
            projected.declarations.single { declaration -> declaration.identity == it.identity }.languageId == it.targetLanguageId
        })
        val declarationCount = projected.declarations.size
        (projected.declarations as MutableList).clear()
        assertEquals(declarationCount, projected.declarations.size)
        val referenceCount = projected.crossLanguageReferences.size
        (projected.crossLanguageReferences as MutableList).clear()
        assertEquals(referenceCount, projected.crossLanguageReferences.size)
    }

    @Test
    fun refusesStaleK2EvidenceBeforePublishingAnySharedIdentity() {
        val root = Files.createTempDirectory("refactorkit-shared-jvm-stale")
        root.resolve("pom.xml").writeText("""
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
              <artifactId>stale</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
        source(root, "src/main/kotlin/fixture/Value.kt", "package fixture\nclass Value\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(
            KotlinCompilerDiagnostics(toolchain).analyzeSymbols(snapshot),
        )
        val changed = snapshot.copy(files = snapshot.files.map { it.copy(content = it.content + "\n") })

        val refused = assertIs<SharedJvmIdentityProjection.Refused>(
            SharedJvmIdentityProjector.project(changed, JdtJavaSemanticAnalysisResult(emptyList()), symbols),
        )

        assertEquals("jvm.identitySnapshotMismatch", refused.code)

        val staleJava = assertIs<SharedJvmIdentityProjection.Refused>(
            SharedJvmIdentityProjector.project(
                snapshot,
                JdtJavaSemanticAnalysisResult(emptyList(), snapshotHash = "0".repeat(64)),
                symbols,
            ),
        )
        assertEquals("jvm.identityJavaSnapshotMismatch", staleJava.code)
    }

    private fun source(root: Path, relative: String, content: String) {
        root.resolve(relative).apply {
            parent.createDirectories()
            writeText(content)
        }
    }

    private fun toolchain(root: Path): KotlinSemanticToolchain {
        val requiredRuntimePrefixes = listOf(
            "kotlin-compiler-embeddable-2.0.21", "kotlin-stdlib-2.0.21",
            "kotlin-script-runtime-2.0.21", "kotlin-reflect-1.6.10",
            "kotlin-daemon-embeddable-2.0.21", "trove4j-1.0.20200330",
            "kotlinx-coroutines-core-jvm-1.6.4", "annotations-13.0",
        )
        val compilerClasspath = System.getProperty("kotlin.compiler.test.classpath")
            .split(File.pathSeparator).map(Path::of)
            .filter { path -> Files.isRegularFile(path) && requiredRuntimePrefixes.any {
                path.fileName.toString().startsWith(it)
            } }
            .distinctBy { it.fileName.toString() }
        val compilerJar = compilerClasspath.single {
            it.fileName.toString().startsWith("kotlin-compiler-embeddable-2.0.21")
        }
        val discovered = KotlinToolchainDiscoverer(KotlinToolchainDiscoveryPolicy(
            allowWorkspaceLocalToolchain = true,
        )).discover(KotlinToolchainRequest(
            workspaceRoot = root,
            jdkHome = Path.of(System.getProperty("java.home")),
            compilerJar = compilerJar,
            compilerClasspath = compilerClasspath.filterNot { it == compilerJar },
        ))
        return assertIs<KotlinToolchainDiscovery.Available>(discovered, discovered.toString()).toolchain
    }
}
