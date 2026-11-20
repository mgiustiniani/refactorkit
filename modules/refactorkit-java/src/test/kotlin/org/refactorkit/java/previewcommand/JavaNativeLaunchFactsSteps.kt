package org.refactorkit.java.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.eclipse.jdt.core.dom.Modifier
import org.refactorkit.core.TextEdits
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.java.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Thin scenario adapter; native analysis and its independent assertions belong to the fixture. */
class JavaNativeLaunchFactsSteps {
    private var fixture: NativeLaunchFactsFixture? = null
    @Given("an attested Java25 launch-facts fixture uses {string}")
    fun capture(shape: String) { fixture = NativeLaunchFactsFixture(shape) }
    @When("the selected SDK compiler analysis captures detached launch facts")
    fun analyze() = requireNotNull(fixture).analyze()
    @Then("native launch facts retain their exact compiler owners methods and construction")
    fun verifyFacts() = requireNotNull(fixture).verifyFacts()
    @Then("native hierarchy facts preserve effective main ownership and declared parameter shapes")
    fun verifyHierarchy() = requireNotNull(fixture).verifyHierarchy()
    @Then("native launch acquisition returns the exact refusal without a partial inventory")
    fun verifyRefusal() = requireNotNull(fixture).verifyRefusal()
    @Then("native selections match an independent Java25 launch control")
    fun verifySelection() = requireNotNull(fixture).verifySelection()
    @Then("native main declarations retain exact source ranges or attested binary origins")
    fun verifyOrigins() = requireNotNull(fixture).verifyOrigins()
    @Then("launch-facts acquisition preserves inputs and existing classic facts")
    fun verifyInputs() = requireNotNull(fixture).verifyInputs()
    @After
    fun close() { fixture?.close(); fixture = null }
}

private class NativeLaunchFactsFixture(private val shape: String) : AutoCloseable {
    private val root = Files.createTempDirectory("rk-native-java25-launch-")
    private val sdk = Path.of(requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME")) {
        "An explicitly selected SDK25 is required"
    }).toRealPath()
    private val probe = "refactorkit.launch.readonly." + UUID.randomUUID()
    private val initialChildren = ProcessHandle.current().descendants().use { it.map(ProcessHandle::pid).toList().toSet() }
    private val executionMarker = root.resolve("application-executed")
    private var dependencyRoot: Path? = null
    private var dependencyClasspath: List<Path> = emptyList()
    private var dependencyImages: Map<Path, String> = emptyMap()
    private val body = "System.setProperty(\"$probe\", \"executed\"); new java.io.File(\"" +
        executionMarker.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\").mkdir();"
    private val expectedOwner = when (shape) {
        "compact" -> "Compact"
        "keyword" -> "record"
        "ignorable" -> "Or\u200Cder"
        "instance" -> "demo.Main"
        "inherited" -> "demo.Child"
        "constructor" -> "demo.NeedsArgument"
        "overridden", "hidden", "default-interface", "private-ancestor", "abstract", "generic" -> "demo.Child"
        "cancelled", "sdk-mismatch", "stale-sdk", "source-error", "source-release", "missing-classpath" -> "demo.Main"
        "private-constructor", "abstract-launch", "record-launch", "precedence" -> "demo.Main"
        "inner", "static-member" -> "demo.Outer\$Main"
        "classpath-directory", "classpath-jar", "platform-origin" -> "demo.Child"
        else -> error("Unknown native launch fixture: $shape")
    }
    private val source = root.resolve("src/main/java/").resolve(
        if (expectedOwner.startsWith("demo.")) "demo/${expectedOwner.substringAfterLast('.').substringBefore('$')}.java" else "$expectedOwner.java")
    private val content = when (shape) {
        "compact", "keyword", "ignorable" -> "void main() { $body }\n"
        "instance" -> "package demo; class Main { protected void main(String[] args) { $body } }\n"
        "inherited" -> "package demo; class Base { public static void main(String[] args) { $body } } class Child extends Base {}\n"
        "overridden" -> "package demo; class Base { void main(String[] args) { $body } } class Child extends Base { @Override void main(String[] args) { $body } }\n"
        "hidden" -> "package demo; class Base { public static void main(String[] args) { $body } } class Child extends Base { public static void main(String[] args) { $body } }\n"
        "default-interface" -> "package demo; interface Entry { default void main() { $body } } class Child implements Entry {}\n"
        "private-ancestor" -> "package demo; class Base { private void main() { $body } } class Child extends Base {}\n"
        "abstract" -> "package demo; abstract class Base { abstract void main(); } abstract class Child extends Base {}\n"
        "generic" -> "package demo; class Child<T extends String> { void main(T[] args) { $body } }\n"
        "source-error" -> "package demo; class Main { void main() { int broken = ; $body } }\n"
        "cancelled", "sdk-mismatch", "stale-sdk", "source-release", "missing-classpath" -> "package demo; class Main { void main() { $body } }\n"
        "private-constructor" -> "package demo; class Main { private Main() {} void main() { $body } }\n"
        "abstract-launch" -> "package demo; abstract class Main { void main() { $body } }\n"
        "inner" -> "package demo; class Outer { class Main { void main() { $body } } }\n"
        "record-launch" -> "package demo; record Main() { void main() { $body } }\n"
        "static-member" -> "package demo; class Outer { static class Main { void main() { $body } } }\n"
        "precedence" -> "package demo; class Main { static void main() { System.out.print(\"ZERO\"); $body } void main(String[] args) { System.out.print(\"ARRAY\"); $body } }\n"
        "classpath-directory", "classpath-jar" -> "package demo; class Child extends dep.Base {}\n"
        "platform-origin" -> "package demo; class Child extends com.sun.tools.javac.Main {}\n"
        else -> "package demo; class NeedsArgument { NeedsArgument(int value) {} void main() { $body } }\n"
    }
    private val images: Map<Path, String>
    private val sdkImages: Map<Path, String>
    private lateinit var advanced: JavaCompilerLaunchResolution
    private lateinit var legacyBefore: JdtJavaSemanticAnalysisResult
    private lateinit var legacyAfter: JdtJavaSemanticAnalysisResult
    private lateinit var authority: JavaReleasePlatformAuthority
    private lateinit var snapshotHash: String

    init {
        Files.createDirectories(source.parent); Files.writeString(source, content)
        val pom = root.resolve("pom.xml")
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>launch</artifactId><version>1</version><properties><maven.compiler.release>25</maven.compiler.release></properties></project>\n")
        images = listOf(source, pom).associateWith(::sha)
        sdkImages = listOf("release", "bin/java", "bin/javac").associate { sdk.resolve(it) to sha(sdk.resolve(it)) }
        assertEquals(null, System.getProperty(probe))
        if (shape in setOf("classpath-directory", "classpath-jar")) {
            try { prepareDependency() } catch (failure: Throwable) { close(); throw failure }
        }
    }

    fun analyze() {
        val snapshot = JavaProjectScanner().scan(root)
        snapshotHash = snapshot.hash
        authority = assertIs<JavaReleasePlatformResolution.Available>(JavaReleasePlatformAuthorityResolver().resolve(sdk, 25)).authority
        assertEquals(25, authority.platformRelease)
        val analyzer = JdtJavaSemanticAnalyzer()
        legacyBefore = assertIs<JdtAuthoritativeJavaAnalysisResolution.Available>(
            analyzer.analyzeAuthoritatively(snapshot, mapOf(25 to authority), additionalClasspathEntries = dependencyClasspath)).analysis
        val selectedAuthority = when (shape) {
            "sdk-mismatch" -> authority.copy(platformRelease = 26)
            "stale-sdk" -> authority.copy(identitySha256 = "0".repeat(64))
            else -> authority
        }
        val selectedSnapshot = if (shape == "source-release") snapshot.copy(modules = snapshot.modules.map {
            it.copy(languageSettings = it.languageSettings + mapOf("java.release" to "24", "java.sourceLevel" to "24"))
        }) else snapshot
        val classpath = if (shape == "missing-classpath") listOf(root.resolve("missing-dependency.jar")) else dependencyClasspath
        val cancellation = if (shape == "cancelled") SemanticCancellationToken { true } else SemanticCancellationToken.NONE
        advanced = JavaNativeLaunchCompiler().capture(selectedSnapshot, selectedAuthority, classpath, cancellation)
        legacyAfter = assertIs<JdtAuthoritativeJavaAnalysisResolution.Available>(
            analyzer.analyzeAuthoritatively(snapshot, mapOf(25 to authority), additionalClasspathEntries = dependencyClasspath)).analysis
    }

    fun verifyFacts() {
        val capture = assertIs<JavaCompilerLaunchResolution.Available>(advanced, advanced.toString())
        assertEquals(authority.identitySha256, capture.platformIdentity)
        assertEquals(sdkImages.getValue(sdk.resolve("bin/java")), capture.launcherSha256)
        assertEquals(sdkImages.getValue(sdk.resolve("bin/javac")), capture.compilerSha256)
        assertEquals(capture.launcherSha256, capture.provenance.executableSha256)
        assertEquals(sdk.resolve("bin/java").toRealPath(), capture.provenance.executable)
        val values = capture.sources.single().types
        val fact = values.filterIsInstance<JavaNativeLaunchTypeReadout.Available>()
            .singleOrNull { it.graph.rootBinaryName == expectedOwner }
        assertNotNull(fact, "Expected detached native launch owner '$expectedOwner', got $values")
        assertEquals(if (shape in setOf("compact", "keyword", "ignorable")) JavaNativeLaunchSourceForm.COMPACT
            else JavaNativeLaunchSourceForm.DECLARED, fact.sourceForm)
        val node = fact.graph.nodes.getValue(expectedOwner)
        assertEquals(expectedOwner, node.binaryName)
        assertTrue(node.sourceOwned)
        assertEquals(JavaNativeLaunchClassKind.CLASS, node.kind)
        val start = TextEdits.offsetOf(content, fact.range.start)
        val end = TextEdits.offsetOf(content, fact.range.end)
        assertTrue(start >= 0 && end <= content.length && start < end)
        assertTrue(content.substring(start, end).contains(if (shape == "inherited") "class Child" else "main"))
        val mainOwner = if (shape == "inherited") "demo.Base" else expectedOwner
        val methods = fact.graph.nodes.getValue(mainOwner).methods.filter { !it.constructor }
        val method = methods.single()
        assertEquals(mainOwner, method.declaringBinaryName)
        assertEquals(listOf(method), fact.graph.effectiveMainMethods)
        assertTrue(method.returnsVoid)
        assertEquals(if (shape in setOf("instance", "inherited")) "([Ljava/lang/String;)V" else "()V", method.descriptor)
        assertEquals(if (shape in setOf("instance", "inherited")) JavaNativeMainParameterShape.STRING_ARRAY
            else JavaNativeMainParameterShape.NONE, method.parameterShape)
        assertEquals(shape == "inherited", Modifier.isStatic(method.modifiers))
        if (shape == "instance") assertTrue(Modifier.isProtected(method.modifiers))
        if (shape == "inherited") assertEquals("demo.Base", node.superclassBinaryName)
        val constructors = node.methods.filter { it.constructor }
        assertEquals(1, constructors.size)
        assertEquals(if (shape == "constructor") "(I)V" else "()V", constructors.single().descriptor)
        assertEquals(if (shape == "constructor") 1 else 0, constructors.single().parameterCount)
        assertEquals(shape != "constructor", constructors.single().defaultConstructor)
        runCatching { (fact.graph.nodes as MutableMap<*, *>).clear() }.isFailure.let { assertTrue(it) }
        runCatching { (node.methods as MutableList<*>).clear() }.isFailure.let { assertTrue(it) }
        runCatching { (capture.sources.single().types as MutableList<*>).clear() }.isFailure.let { assertTrue(it) }
        runCatching { (fact.graph.effectiveMainMethods as MutableList<*>).clear() }.isFailure.let { assertTrue(it) }
        assertFalse(ProcessHandle.of(capture.provenance.pid).map { it.isAlive }.orElse(false))
        assertFalse(Files.exists(capture.provenance.workingDirectory), "Private worker inputs must be removed")
        println("Native SDK25 launch facts: shape=$shape owner=${node.binaryName} mainOwner=$mainOwner descriptor=${method.descriptor}")
    }

    fun verifyInputs() {
        fun classic(value: JdtJavaSemanticAnalysisResult) = value.entryPointSources.map {
            listOf(it.path, it.complete, it.methods, it.types)
        }
        assertEquals(classic(legacyBefore), classic(legacyAfter))
        assertEquals(legacyBefore.warnings, legacyAfter.warnings)
        assertEquals(images, images.keys.associateWith(::sha)); assertEquals(sdkImages, sdkImages.keys.associateWith(::sha))
        assertEquals(dependencyImages, dependencyImages.keys.associateWith(::sha))
        val current = assertIs<JavaReleasePlatformResolution.Available>(JavaReleasePlatformAuthorityResolver().revalidate(authority))
        assertEquals(authority.identitySha256, current.authority.identitySha256)
        assertEquals(null, System.getProperty(probe), "Native analysis must not load or execute application main")
        assertFalse(Files.exists(executionMarker), "The external worker must never execute application main")
        val children = ProcessHandle.current().descendants().use { it.map(ProcessHandle::pid).toList().toSet() }
        assertTrue((children - initialChildren).isEmpty(), "Compiler acquisition must not leave child processes")
        assertFalse(Files.exists(root.resolve("target")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        Files.walk(root).use { files -> assertFalse(files.anyMatch { it.fileName.toString().endsWith(".class") }) }
    }

    fun verifyHierarchy() {
        val capture = assertIs<JavaCompilerLaunchResolution.Available>(advanced, advanced.toString())
        val fact = capture.sources.single().types.single { it.graph.rootBinaryName == "demo.Child" }
        assertEquals(JavaNativeLaunchSourceForm.DECLARED, fact.sourceForm)
        val node = fact.graph.nodes.getValue("demo.Child")
        assertTrue(node.sourceOwned)
        assertEquals(JavaNativeLaunchClassKind.CLASS, node.kind)
        val text = content.substring(TextEdits.offsetOf(content, fact.range.start), TextEdits.offsetOf(content, fact.range.end))
        assertTrue(text.contains("class Child"))
        val effective = fact.graph.effectiveMainMethods
        if (shape == "private-ancestor") {
            assertTrue(effective.isEmpty(), "A private ancestor method must not become an inherited member")
            val privateMain = fact.graph.nodes.getValue("demo.Base").methods.single { !it.constructor }
            assertTrue(Modifier.isPrivate(privateMain.modifiers))
        } else {
            val method = effective.single()
            val owner = when (shape) { "default-interface" -> "demo.Entry"; "abstract" -> "demo.Base"; else -> "demo.Child" }
            assertEquals(owner, method.declaringBinaryName)
            assertEquals(shape == "hidden", Modifier.isStatic(method.modifiers))
            assertEquals(shape == "abstract", Modifier.isAbstract(method.modifiers))
            assertTrue(method.returnsVoid)
            assertFalse(method.constructor)
            assertEquals(if (shape in setOf("default-interface", "abstract")) "()V" else "([Ljava/lang/String;)V", method.descriptor)
            val parameterShape = when (shape) {
                "default-interface", "abstract" -> JavaNativeMainParameterShape.NONE
                "generic" -> JavaNativeMainParameterShape.OTHER
                else -> JavaNativeMainParameterShape.STRING_ARRAY
            }
            assertEquals(parameterShape, method.parameterShape)
            if (shape == "default-interface") {
                val iface = fact.graph.nodes.getValue("demo.Entry")
                assertEquals(JavaNativeLaunchClassKind.INTERFACE, iface.kind)
                assertEquals(listOf("demo.Entry"), node.interfaceBinaryNames)
            }
            if (shape in setOf("overridden", "hidden")) {
                val ancestor = fact.graph.nodes.getValue("demo.Base").methods.single { !it.constructor }
                assertEquals("demo.Base", ancestor.declaringBinaryName)
                assertEquals(method.descriptor, ancestor.descriptor)
            }
        }
        assertEquals(shape == "abstract", Modifier.isAbstract(node.modifiers))
        assertEquals("()V", node.methods.single { it.constructor }.descriptor)
        assertFalse(ProcessHandle.of(capture.provenance.pid).map { it.isAlive }.orElse(false))
        assertFalse(Files.exists(capture.provenance.workingDirectory))
        println("Native hierarchy: shape=$shape owner=${node.binaryName} effective=${effective.map { it.declaringBinaryName to it.parameterShape }}")
    }

    fun verifyRefusal() {
        val expected = when (shape) {
            "cancelled" -> "java.launch.cancelled"
            "sdk-mismatch" -> "java.launch.sdkUnqualified"
            "stale-sdk" -> "java.launch.contextStale"
            "source-error" -> "java.launch.compilerDiagnostics"
            "source-release" -> "java.launch.sourceUnqualified"
            "missing-classpath" -> "java.launch.acquisitionUnavailable"
            else -> error("Not a refusal fixture: $shape")
        }
        val refusal = assertIs<JavaCompilerLaunchResolution.Refused>(advanced, "Invalid acquisition must not expose an available inventory")
        assertEquals(expected, refusal.code)
        assertTrue(refusal.message.isNotBlank())
        println("Native acquisition refusal: shape=$shape code=${refusal.code}")
    }

    fun verifySelection() {
        val capture = assertIs<JavaCompilerLaunchResolution.Available>(advanced, advanced.toString())
        assertEquals(snapshotHash, capture.captureContext.snapshotHash)
        assertEquals(25, capture.captureContext.sourceRelease)
        assertEquals(25, capture.captureContext.targetRelease)
        assertEquals(25, capture.captureContext.launcherRelease)
        val result = JavaNativeLaunchSelector.select(JavaMainLaunchProfile.JAVA25, capture,
            snapshotHash, authority.identitySha256, root.relativize(source), expectedOwner)
        val eligible = shape !in setOf("constructor", "private-constructor", "abstract-launch", "inner")
        if (eligible) {
            val selected = assertIs<JavaMainLaunchSelection.Selected>(result)
            assertEquals(expectedOwner, selected.binaryName)
            assertEquals(if (shape == "inherited") "demo.Base" else expectedOwner, selected.declaringBinaryName)
            assertEquals(shape == "inherited", selected.inherited)
            assertEquals(if (shape == "inherited") JavaMainInvocation.STATIC else JavaMainInvocation.INSTANCE, selected.invocation)
            assertEquals(if (shape in setOf("instance", "inherited", "precedence")) "([Ljava/lang/String;)V" else "()V", selected.descriptor)
            assertEquals(if (shape == "inherited") null else "()V", selected.constructionDescriptor)
        } else assertEquals(JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.INSTANCE_CREATION_INELIGIBLE), result)
        // Prove the engine path was read-only before running the separate test-owned launcher control.
        verifyInputs()
        val control = Files.createTempDirectory("rk-java25-independent-launch-")
        try {
            val copied = control.resolve(source.fileName)
            Files.writeString(copied, content) // Exact analyzed fixture bytes, not a rewritten program.
            val classes = Files.createDirectory(control.resolve("classes"))
            val compile = nativeCommand(control, sdk.resolve("bin/javac"), listOf("-proc:none", "--release", "25", "-d", classes.toString(), copied.toString()))
            assertEquals(0, compile.first, "Independent SDK25 compilation failed")
            assertFalse(Files.exists(executionMarker), "Native compilation must not execute main")
            val launch = nativeCommand(control, sdk.resolve("bin/java"), listOf("-cp", classes.toString(), expectedOwner))
            if (eligible) {
                assertEquals(0, launch.first, "The actual selected SDK25 launcher must accept the selected owner")
                assertTrue(Files.isDirectory(executionMarker), "The independent launcher must actually invoke the fixture main")
                assertEquals(if (shape == "precedence") "ARRAY" else "", launch.second,
                    "The actual launcher must select the expected main overload")
            } else {
                assertTrue(launch.first != 0, "The actual launcher must reject ineligible construction")
                assertFalse(Files.exists(executionMarker))
            }
            println("Native selector/launcher agreement: shape=$shape owner=$expectedOwner selected=$eligible exit=${launch.first}")
        } finally {
            Files.deleteIfExists(executionMarker)
            Files.walk(control).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun nativeCommand(directory: Path, executable: Path, arguments: List<String>): Pair<Int, String> =
        ExternalSemanticProcessManager(maxProcesses = 1).use { manager ->
            manager.launch(SemanticProcessSpec("java-launch-control", executable, arguments, directory,
                environment = mapOf("LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8"),
                limits = SemanticProcessLimits(maxStdoutBytes = 65536, maxStderrBytes = 65536))).use { process ->
                process.input.close()
                assertTrue(process.awaitExit(20_000), "Independent native control exceeded its timeout")
                requireNotNull(process.exitCode) to process.output.readBytes().toString(Charsets.UTF_8)
            }
        }

    private fun prepareDependency() {
        val directory = Files.createTempDirectory("rk-java25-origin-dependency-")
        dependencyRoot = directory
        val base = directory.resolve("Base.java")
        Files.writeString(base, "package dep; public class Base { public static void main(String[] args) { $body } }\n")
        val classes = Files.createDirectory(directory.resolve("classes with spaces"))
        assertEquals(0, nativeCommand(directory, sdk.resolve("bin/javac"),
            listOf("-proc:none", "--release", "25", "-d", classes.toString(), base.toString())).first)
        val jar = directory.resolve("dependency with spaces.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("dep/Base.class").apply { time = 0 })
            Files.copy(classes.resolve("dep/Base.class"), output)
            output.closeEntry()
        }
        dependencyClasspath = listOf(if (shape == "classpath-jar") jar else classes)
        dependencyImages = Files.walk(directory).use { paths -> paths.filter(Files::isRegularFile).toList().associateWith(::sha) }
    }

    fun verifyOrigins() {
        val capture = assertIs<JavaCompilerLaunchResolution.Available>(advanced, advanced.toString())
        val type = capture.sources.single().types.single { it.graph.rootBinaryName == expectedOwner }
        val method = type.graph.effectiveMainMethods.single()
        val owner = when (shape) {
            "inherited" -> "demo.Base"; "default-interface" -> "demo.Entry"
            "classpath-directory", "classpath-jar" -> "dep.Base"
            "platform-origin" -> "com.sun.tools.javac.Main"; else -> expectedOwner
        }
        assertEquals(owner, method.declaringBinaryName)
        assertEquals(method, type.graph.nodes.getValue(owner).methods.single { !it.constructor })
        if (shape in setOf("ignorable", "inherited", "default-interface")) {
            val origin = assertIs<JavaNativeDeclarationOrigin.Source>(method.origin)
            assertEquals(root.relativize(source), origin.path)
            val declaration = when (shape) {
                "inherited" -> "public static void main(String[] args) { $body }"
                "default-interface" -> "default void main() { $body }"
                else -> "void main() { $body }"
            }
            val start = content.indexOf(declaration); assertTrue(start >= 0)
            assertEquals(TextEdits.rangeForOffset(content, start, declaration.length), origin.range)
        } else {
            val origin = assertIs<JavaNativeDeclarationOrigin.Binary>(method.origin)
            if (shape == "platform-origin") {
                assertEquals(JavaNativeBinaryOriginKind.PLATFORM, origin.kind)
                assertEquals("jdk.compiler", origin.moduleName)
                assertEquals(authority.signatureArchive, origin.inputPath)
                assertEquals(authority.signatureArchiveSha256, origin.inputSha256)
                assertTrue(origin.uri.endsWith("/jdk.compiler/com/sun/tools/javac/Main.sig"))
            } else {
                assertEquals(JavaNativeBinaryOriginKind.CLASSPATH, origin.kind)
                assertEquals(null, origin.moduleName)
                val input = dependencyClasspath.single().toRealPath()
                assertEquals(input, origin.inputPath)
                val expectedHash = if (shape == "classpath-jar") sha(input) else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val name = "dep/Base.class".toByteArray(Charsets.UTF_8)
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.size).array())
                    digest.update(name); digest.update(sha(input.resolve("dep/Base.class")).toByteArray(Charsets.US_ASCII))
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                assertEquals(expectedHash, origin.inputSha256)
                assertEquals(mapOf(input to expectedHash), capture.classpathInputs)
                assertTrue(runCatching { (capture.classpathInputs as MutableMap<*, *>).clear() }.isFailure)
                assertTrue(origin.uri.startsWith(if (shape == "classpath-jar") "jar:file:" else "file:"))
                assertTrue(origin.uri.endsWith(if (shape == "classpath-jar") "!/dep/Base.class" else "/dep/Base.class"))
                assertTrue(origin.uri.contains("%20"), "Native URI escaping must be preserved")
            }
        }
        println("Native main origin: shape=$shape launchOwner=$expectedOwner declarationOwner=$owner origin=${method.origin}")
    }

    override fun close() {
        System.clearProperty(probe)
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        dependencyRoot?.let { directory ->
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    companion object {
        private fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
    }
}
