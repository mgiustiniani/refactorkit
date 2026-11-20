package org.refactorkit.java.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.*
import org.refactorkit.java.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.*

/** Transport glue only; all provider/native orchestration belongs to the fixture/application. */
class JavaNativeEntryPointContextSteps {
    private var fixture: NativeProviderContextFixture? = null
    @Given("a provider-owned Java25 native entrypoint context uses {string}")
    fun prepare(shape: String) { fixture = NativeProviderContextFixture(shape) }
    @When("the native entrypoint application acquires the selected provider scope")
    fun acquire() = requireNotNull(fixture).acquire()
    @Then("the native context preserves exact compile closure or its typed refusal")
    fun verify() = requireNotNull(fixture).verify()
    @Then("provider scope acquisition preserves source SDK and process state")
    fun unchanged() = requireNotNull(fixture).unchanged()
    @After
    fun close() { fixture?.close(); fixture = null }
}

private class NativeProviderContextFixture(private val shape: String) : AutoCloseable {
    private val root = Files.createTempDirectory("rk-java25-provider-context-")
    private val sdk = Path.of(requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME"))).toRealPath()
    private val scanner = JavaProjectScanner()
    private val inputs: Map<Path, String>
    private val sdkImages = listOf("release", "bin/java", "bin/javac").associate { sdk.resolve(it) to sha(sdk.resolve(it)) }
    private val initialChildren = ProcessHandle.current().descendants().use { it.map(ProcessHandle::pid).toList().toSet() }
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var scope: JavaEntryPointScope
    private lateinit var request: JavaProjectContextRequest
    private lateinit var outcome: JavaNativeEntryPointContextResolution
    private var expectedContext = ""
    private val expectedSelected: Set<Path>
    private val expectedClosure: Set<Path>

    init {
        val reactor = shape in setOf("reactor", "mixed-source")
        if (reactor) {
            write("pom.xml", pom("root", "<packaging>pom</packaging><modules><module>lib</module><module>app</module><module>unrelated</module></modules>"))
            for (name in listOf("lib", "app", "unrelated")) {
                val dependency = if (name == "app") "<dependencies><dependency><groupId>demo</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies>" else ""
                write("$name/pom.xml", pom(name, dependency, if (name == "unrelated" || shape == "mixed-source" && name == "lib") 21 else 25))
            }
            write("lib/src/main/java/demo/Base.java", "package demo; public class Base { public static void main(String[] args) {} }\n")
            write("app/src/main/java/demo/Child.java", "package demo; public class Child extends Base {}\n")
            write("unrelated/src/main/java/demo/Other.java", "package demo; public class Other { void main() {} }\n")
            expectedSelected = setOf(Path.of("app/src/main/java/demo/Child.java"))
            expectedClosure = expectedSelected + setOf(Path.of("lib/src/main/java/demo/Base.java"))
        } else {
            write("pom.xml", pom("app"))
            write("src/main/java/demo/Base.java", "package demo; public class Base { public static void main(String[] args) {} }\n")
            write("src/test/java/demo/Child.java", "package demo; public class Child extends Base {}\n")
            val main = Path.of("src/main/java/demo/Base.java"); val test = Path.of("src/test/java/demo/Child.java")
            expectedSelected = setOf(if (shape == "test") test else main)
            expectedClosure = if (shape == "test") setOf(main, test) else setOf(main)
        }
        inputs = Files.walk(root).use { it.filter { p -> Files.isRegularFile(p) }.toList().associateWith(::sha) }
    }

    fun acquire() {
        snapshot = scanner.scan(root)
        val model = snapshot.buildModels.single { it.providerId == "maven-effective-v1" }
        val owner = snapshot.owningJavaBuildSourceRoots(expectedSelected.single()).single { it.providerId == model.providerId }
        val module = owner.module
        val set = owner.sourceSet
        scope = JavaEntryPointScope(model.providerId, module.id, if (shape == "missing-set") "missing" else set.id)
        val base = JavaProjectContextRequest(snapshot.hash, sdk, providerId = model.providerId, moduleId = module.id,
            sourceSetId = set.id, requiredFacts = setOf(JavaProjectContextFact.COMPILER_OPTIONS))
        val acquired = JavaEffectiveProjectContextProvider().read(base, { scanner.scan(root) })
        val view = assertIs<JavaProjectContextResolution.Available>(acquired, acquired.toString()).view
        expectedContext = view.contextHash
        request = base.copy(expectedContextHash = expectedContext)
        if (shape == "stale-snapshot") Files.writeString(root.resolve("src/main/java/demo/Base.java"),
            "package demo; public class Base { public static void main(String[] args) {} int changed; }\n")
        outcome = JavaNativeEntryPointContext.read(request, scope, { scanner.scan(root) })
    }

    fun verify() {
        val expectedRefusal = when (shape) {
            "mixed-source" -> "java.entryPoints.scopeSourceUnqualified"
            "missing-set" -> "java.context.sourceSetUnavailable"
            "stale-snapshot" -> "java.context.snapshotStale"
            else -> null
        }
        if (expectedRefusal != null) {
            val refusal = assertIs<JavaNativeEntryPointContextResolution.Refused>(outcome, outcome.toString())
            assertEquals(expectedRefusal, refusal.code)
            return
        }
        val value = assertIs<JavaNativeEntryPointContextResolution.Available>(outcome, outcome.toString())
        assertEquals(snapshot.hash, value.context.snapshot.hash)
        assertEquals(expectedContext, value.context.view.contextHash)
        assertEquals(expectedSelected, value.selectedSources.map { it.path }.toSet())
        assertEquals(expectedClosure, value.scopeSnapshot.files.map { it.path }.toSet())
        assertEquals(value.scopeSnapshot.hash, value.capture.captureContext.snapshotHash)
        assertTrue(value.scopeSnapshot.hash != snapshot.hash, "Scoped compiler identity must not relabel the global snapshot")
        val path = expectedSelected.single()
        val owner = if (shape == "main") "demo.Base" else "demo.Child"
        val selected = assertIs<JavaMainLaunchSelection.Selected>(JavaNativeLaunchSelector.select(JavaMainLaunchProfile.JAVA25,
            value.capture, value.scopeSnapshot.hash, value.capture.platformIdentity, path, owner))
        assertEquals(owner, selected.binaryName); assertEquals("demo.Base", selected.declaringBinaryName)
        assertEquals(owner != "demo.Base", selected.inherited)
        assertFalse(Files.exists(root.resolve("target")))
        println("Native provider scope shape=$shape selected=$expectedSelected closure=$expectedClosure global=${snapshot.hash} scoped=${value.scopeSnapshot.hash}")
    }

    fun unchanged() {
        if (shape == "stale-snapshot") Files.writeString(root.resolve("src/main/java/demo/Base.java"),
            "package demo; public class Base { public static void main(String[] args) {} }\n")
        assertEquals(inputs, inputs.keys.associateWith(::sha)); assertEquals(sdkImages, sdkImages.keys.associateWith(::sha))
        assertTrue(ProcessHandle.current().descendants().use { it.map(ProcessHandle::pid).toList().toSet() - initialChildren }.isEmpty())
        Files.walk(root).use { assertFalse(it.anyMatch { p -> p.fileName.toString().endsWith(".class") }) }
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private fun pom(artifact: String, extra: String = "", release: Int = 25) =
        "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>$artifact</artifactId><version>1</version><properties><maven.compiler.release>$release</maven.compiler.release></properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version><configuration><release>$release</release></configuration></plugin></plugins></build>$extra</project>\n"
    private fun write(path: String, text: String) { val p = root.resolve(path); Files.createDirectories(p.parent); Files.writeString(p, text) }
    override fun close() { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    companion object { private fun sha(p: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString("") { "%02x".format(it) } }
}
