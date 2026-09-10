package org.refactorkit.cli.mavenmodulerenamesurface002

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import org.refactorkit.cli.CliDiagnosticLineRendererProbe
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.ApprovalKind
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticDetails
import org.refactorkit.core.FileAclEntryImage
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JournalState
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchFaultInjector
import org.refactorkit.core.PatchFaultPoint
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.TransactionJournalRecord
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameMavenModulePlanner
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileOwnerAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Focused Story-BDD glue for REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-002 only. */
class JavaMavenModuleRenameSurface002Steps {
    private lateinit var scenario: Scenario
    private lateinit var repositoryRoot: Path
    private lateinit var fixtureRoot: Path
    private lateinit var temporaryRoot: Path
    private lateinit var isolatedUserHome: Path
    private lateinit var workspaceRoot: Path
    private lateinit var refusalRoot: Path
    private lateinit var permanentManifest: ExactManifest
    private lateinit var baselineManifest: ExactManifest
    private lateinit var refusalBaselineManifest: ExactManifest
    private var authorityMonitor: DeniedAuthorityMonitor? = null
    private var authorityObservationRequested = false
    private var mutationLockHeld = false
    private var previousUserHome: String? = null

    private lateinit var s0: ProjectSnapshot
    private lateinit var refusalS0: ProjectSnapshot
    private lateinit var refusalCanonicalPlan: PatchPlan
    private lateinit var canonicalPlan: PatchPlan
    private lateinit var canonicalLease: OperationAuthorityLease
    private lateinit var c1: ProjectSnapshot
    private lateinit var d0: DiagnosticBaseline
    private lateinit var baselineReactorFacts: ReactorFacts
    private lateinit var exactApplyCommand: List<String>
    private lateinit var faultFactory: Method
    private lateinit var javapEvidence: JavapEvidence

    private var directAuthorityContractReused = false
    private var selectorQualificationAccepted = false
    private var selectorArchitectureQualified = false
    private var productionCliRunCount = 0
    private var faultInjected = false
    private var faultObservedWorkspaceLock = false
    private val faultInvocationCount = AtomicInteger()
    private var refusalOriginalPom: ByteArray? = null
    private var refusalMutatedPom: ByteArray? = null
    private var expectedRefusalDiagnostic: ExpectedDiagnostic? = null
    private var refusalResult: CliResult? = null
    private var primaryResult: CliResult? = null
    private var rollbackResult: CliResult? = null
    private var transactionId: String? = null
    private var appliedRecord: TransactionJournalRecord? = null
    private var s1: ProjectSnapshot? = null

    private var sequence = 0
    private var preOracleCapturedAt = -1
    private var postOracleCapturedAt = -1
    private var firstJournalInspectionAt = -1
    private var preImageOracle: List<IndependentFileImage>? = null
    private var postImageOracle: List<IndependentFileImage>? = null
    private var rawS0Images: List<FilesystemFileImage>? = null
    private var rawS1Images: List<FilesystemFileImage>? = null
    private var literalCreatedDirectoriesOracle: List<Path>? = null

    @Before("@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-002")
    fun prepareScenario(scenario: Scenario) {
        GLOBAL_MUTATION_LOCK.lock()
        mutationLockHeld = true
        try {
            this.scenario = scenario
            repositoryRoot = locateRepositoryRoot()
            fixtureRoot = repositoryRoot.resolve(FIXTURE_PATH).normalize()
            assertTrue(Files.isDirectory(fixtureRoot, LinkOption.NOFOLLOW_LINKS), "Permanent fixture is missing")
            permanentManifest = captureManifest(fixtureRoot)
            temporaryRoot = Files.createTempDirectory("refactorkit-maven-module-rename-surface-002-")
            workspaceRoot = temporaryRoot.resolve("workspace").toAbsolutePath().normalize()
            refusalRoot = temporaryRoot.resolve("refusal").toAbsolutePath().normalize()
            isolatedUserHome = temporaryRoot.resolve("isolated-user-home").toAbsolutePath().normalize()
            Files.createDirectories(isolatedUserHome)
            previousUserHome = System.getProperty("user.home")
            System.setProperty("user.home", isolatedUserHome.toString())
            scenario.attach(
                "The source-built CLI runs in-process. Global stdout, stderr, and user.home mutation is " +
                    "serialized for the complete scenario; the refusal-only seam accepts PatchFaultInjector alone.",
                "text/plain",
                "surface-002-boundary",
            )
        } catch (failure: Throwable) {
            restoreGlobalStateAndUnlock()
            throw failure
        }
    }

    @After("@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-002")
    fun cleanScenario() {
        try {
            authorityMonitor?.let { monitor ->
                val evidence = monitor.finish()
                if (this::scenario.isInitialized) {
                    scenario.attach(evidence.toString(), "text/plain", "child-process-socket-observation")
                }
                assertFalse(evidence.prohibitedSurfaceActivityObserved, evidence.toString())
            }
            if (this::fixtureRoot.isInitialized && this::permanentManifest.isInitialized) {
                assertEquals(permanentManifest, captureManifest(fixtureRoot), "Permanent fixture was mutated")
            }
        } finally {
            try {
                authorityMonitor?.close()
            } finally {
                restoreGlobalStateAndUnlock()
            }
        }
    }

    @Given("each case uses a fresh no-follow disposable byte copy of {string}")
    fun freshNoFollowDisposableCopy(path: String) {
        assertEquals(FIXTURE_PATH, path)
        copyNoFollow(fixtureRoot, workspaceRoot)
        baselineManifest = captureManifest(workspaceRoot)
        assertEquals(permanentManifest, baselineManifest)
        assertFalse(Files.exists(workspaceRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("the permanent fixture remains an immutable offline reactor with one root aggregator and exactly 20 direct non-aggregator JAR children")
    fun permanentFixtureIsTwentyModuleOfflineReactor() {
        val modules = directRootModules(workspaceRoot)
        assertEquals(20, modules.size)
        assertEquals(20, modules.toSet().size)
        assertEquals("pom", directChildText(parsePom(workspaceRoot.resolve("pom.xml")), "packaging"))
        modules.forEach { module ->
            val child = parsePom(workspaceRoot.resolve(module).resolve("pom.xml"))
            assertTrue(directChildren(child, "modules").isEmpty(), "$module must not aggregate")
            assertEquals("jar", directChildText(child, "packaging") ?: "jar", "$module must be a JAR child")
        }
        s0 = scan(workspaceRoot)
        assertEquals(BuildModelStatus.AVAILABLE, s0.buildModels.single().status)
        assertEquals(20, s0.buildModels.single().modules.size)
        baselineReactorFacts = reactorFacts(workspaceRoot, s0)
        assertEquals(permanentManifest, captureManifest(fixtureRoot))
    }

    @Given("this slice reuses REQ-JAVA-MAVEN-MODULE-RENAME-001's already-qualified in-process denial contract for Maven and wrapper execution, lifecycle goals, plugins, annotation processors, settings and credential access, credential helpers, and network requests")
    fun reuseQualifiedDirectLibraryAuthorityContract() {
        val requirement = Files.readString(
            repositoryRoot.resolve("features/java-maven-module-rename.feature"),
            StandardCharsets.UTF_8,
        )
        assertTrue(requirement.contains("@REQ-JAVA-MAVEN-MODULE-RENAME-001"))
        assertTrue(requirement.contains("@implemented-and-validated"))
        assertTrue(requirement.contains("Maven and wrapper execution"))
        directAuthorityContractReused = true
    }

    @Given("only child-process starts and RefactorKit-attributable socket reads or writes are independently observed here")
    fun observeOnlyChildProcessesAndRefactorKitSockets() {
        assertTrue(directAuthorityContractReused)
        authorityObservationRequested = true
    }

    @Given("the qualified request is oldModuleDir={string}, newModuleDir={string}, and caller-explicit newArtifactId={string}")
    fun qualifiedRequest(oldModule: String, newModule: String, newArtifact: String) {
        assertEquals(OLD_MODULE, oldModule)
        assertEquals(NEW_MODULE, newModule)
        assertEquals(NEW_ARTIFACT, newArtifact)
        assertTrue(Files.isDirectory(workspaceRoot.resolve(oldModule), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(newModule), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("REQ-JAVA-MAVEN-MODULE-RENAME-001 supplies the canonical {string} PREVIEW, exact five-edit candidate {string}, immutable authority lease and auxiliary-POM evidence, baseline {string}, authoritative post-image {string}, and diagnostic multiset {string}")
    fun canonicalDirectLibraryEvidence(
        operation: String,
        candidateName: String,
        baselineName: String,
        postImageName: String,
        diagnosticsName: String,
    ) {
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, operation)
        assertEquals("C1", candidateName)
        assertEquals("S0", baselineName)
        assertEquals("S1", postImageName)
        assertEquals("D0", diagnosticsName)
        canonicalPlan = JavaRenameMavenModulePlanner().preview(s0, OLD_MODULE, NEW_MODULE, NEW_ARTIFACT)
        assertEquals(PatchStatus.PREVIEW, canonicalPlan.status, canonicalPlan.summary)
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, canonicalPlan.operation)
        assertTrue(canonicalPlan.requiresUserApproval)
        assertEquals(s0.hash, canonicalPlan.snapshotHash)
        canonicalLease = assertNotNull(canonicalPlan.authorityLease)
        assertTrue(canonicalLease.requiredFileEvidence.any { it.path == REQUIRED_POM })
        val normalized = WorkspaceEditSimulator.normalize(canonicalPlan.workspaceEdit)
        assertEquals(5, normalized.edits.size)
        assertEquals(3, normalized.edits.count { it is FileEdit.Modify })
        assertEquals(2, normalized.edits.count { it is FileEdit.Rename })
        c1 = WorkspaceEditSimulator.apply(s0, normalized)
        assertNotEquals(s0.hash, c1.hash)
        d0 = diagnostics(s0)
        assertEquals(d0.jdt, canonicalPlan.diagnosticsBefore)
        assertEquals(baselineManifest, captureManifest(workspaceRoot))
    }

    @Given("the actual source-built RefactorKit CLI has the unchanged case workspace and one independent refusal-probe copy, both at exact {string} with an empty transaction journal")
    fun sourceBuiltCliHasTwoExactBaselines(snapshotName: String) {
        assertEquals("S0", snapshotName)
        copyNoFollow(fixtureRoot, refusalRoot)
        refusalBaselineManifest = captureManifest(refusalRoot)
        assertEquals(baselineManifest, refusalBaselineManifest)
        refusalS0 = scan(refusalRoot)
        refusalCanonicalPlan = JavaRenameMavenModulePlanner().preview(
            refusalS0,
            OLD_MODULE,
            NEW_MODULE,
            NEW_ARTIFACT,
        )
        assertEquals(PatchStatus.PREVIEW, refusalCanonicalPlan.status, refusalCanonicalPlan.summary)
        assertEquals(
            WorkspaceEditSimulator.normalize(canonicalPlan.workspaceEdit),
            WorkspaceEditSimulator.normalize(refusalCanonicalPlan.workspaceEdit),
        )
        assertTrue(transactionLog(workspaceRoot).listRecordsReadOnly().isEmpty())
        assertTrue(transactionLog(refusalRoot).listRecordsReadOnly().isEmpty())
        assertFalse(Files.exists(workspaceRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(refusalRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("the exact command is `refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root <case-workspace-absolute-path> --apply`, where the value substituted for `<case-workspace-absolute-path>` is the normalized absolute root of the disposable copy used by that invocation and all other operation arguments and flags remain exact")
    fun exactApplyCommandUsesOnlyRootSubstitution() {
        exactApplyCommand = listOf(
            "refactorkit", "java", "rename-module",
            "--old-module-dir", OLD_MODULE,
            "--new-module-dir", NEW_MODULE,
            "--new-artifact-id", NEW_ARTIFACT,
            "--root", ROOT_PLACEHOLDER,
            "--apply",
        )
        assertEquals(EXACT_APPLY_COMMAND, exactApplyCommand.joinToString(" "))
        assertEquals(listOf(10), exactApplyCommand.indices.filter { exactApplyCommand[it] == ROOT_PLACEHOLDER })
    }

    @Given("REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-001's executable production-selector qualification dynamically counts provider and authoritative-factory invocations through the normal five-argument `ManagedApplyDiagnosticsGateSelector.select` entry, proving zero generic {string} provider invocations and exactly one lazy operation-owned authoritative-provider construction at first evaluation for exact operation {string}")
    fun reuseDynamicSelectorQualification(genericGate: String, operation: String) {
        assertEquals("java-jdt", genericGate)
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, operation)
        val selector = Class.forName("org.refactorkit.jvm.ManagedApplyDiagnosticsGateSelector")
        val publicFiveArgumentSelect = selector.declaredMethods.filter { method ->
            method.name == "select" && method.parameterCount == 5 && Modifier.isPublic(method.modifiers)
        }
        assertEquals(1, publicFiveArgumentSelect.size, "The normal public five-argument selector entry is unavailable")
        javapEvidence = javapRefactorKitCli()
        val applyBlock = javapEvidence.applyPlanAndLogBlock
        assertEquals(
            1,
            SELECTOR_INVOKE.findAll(applyBlock).count(),
            "RefactorKitCli.applyPlanAndLog must invoke the normal selector exactly once.\n$applyBlock",
        )
        assertFalse(
            applyBlock.contains("PatchPlan.getOperation"),
            "CLI apply contains a local operation-routing decision.\n$applyBlock",
        )
        selectorQualificationAccepted = true
    }

    @Given("the refusal probe alone may use reflection solely to invoke one private test constructor or private test factory whose sole input is `PatchFaultInjector`, retained as a private final construction-time dependency; the seam, including every synthetic bridge, is absent from public JVM bytecode and Kotlin and Java APIs, while production `main` and the unchanged default public constructor always use the no-fault construction path")
    fun privateConstructionTimeFaultSeamOnly() {
        val cliClass = RefactorKitCli::class.java
        assertTrue(
            cliClass.declaredConstructors.none { it.parameterTypes.contains(PatchFaultInjector::class.java) },
            "RefactorKitCli must expose no fault constructor",
        )
        val mainClass = Class.forName("org.refactorkit.cli.RefactorKitCliKt")
        val faultFactories = mainClass.declaredMethods.filter { method ->
            method.parameterTypes.toList() == listOf(PatchFaultInjector::class.java) &&
                method.returnType == RefactorKitCli::class.java
        }
        assertEquals(1, faultFactories.size, "Exactly one private factory may mention PatchFaultInjector")
        faultFactory = faultFactories.single()
        assertTrue(Modifier.isPrivate(faultFactory.modifiers))
        assertTrue(Modifier.isStatic(faultFactory.modifiers))
        assertFalse(faultFactory.isSynthetic)
        faultFactory.isAccessible = true

        val publicNoArg = cliClass.constructors.singleOrNull { it.parameterCount == 0 }
        assertNotNull(publicNoArg, "The production public no-argument constructor changed")
        assertEquals(
            setOf(
                emptyList(),
                listOf(
                    "org.refactorkit.java.JavaProjectScanner",
                    "org.refactorkit.java.JavaLanguageAdapter",
                    "kotlin.jvm.functions.Function0",
                    "org.refactorkit.cli.JavaMoveClassGuidanceOutputPort",
                ),
                listOf(
                    "org.refactorkit.java.JavaProjectScanner",
                    "org.refactorkit.java.JavaLanguageAdapter",
                    "kotlin.jvm.functions.Function0",
                    "org.refactorkit.cli.JavaMoveClassGuidanceOutputPort",
                    "int",
                    "kotlin.jvm.internal.DefaultConstructorMarker",
                ),
            ),
            cliClass.constructors.map { constructor -> constructor.parameterTypes.map { it.name } }.toSet(),
            "The production public constructor surface changed",
        )
        assertTrue(cliClass.constructors.none { it.parameterTypes.contains(PatchFaultInjector::class.java) })
        assertTrue(mainClass.methods.any { method ->
            method.name == "main" && Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers)
        })

        val constructionToken = Class.forName("org.refactorkit.cli.CliPatchFaultConstruction")
        val classes = listOf(cliClass, mainClass, constructionToken) + cliClass.declaredClasses
        val exposedFaultMethods = classes.flatMap { it.declaredMethods.toList() }.filter { method ->
            (Modifier.isPublic(method.modifiers) || method.isSynthetic) &&
                (method.parameterTypes.contains(PatchFaultInjector::class.java) ||
                    method.returnType == PatchFaultInjector::class.java ||
                    method.name.contains("PatchFault", ignoreCase = true) ||
                    method.name.contains("FaultInjector", ignoreCase = true))
        }
        assertTrue(exposedFaultMethods.isEmpty(), "Public/synthetic fault methods remain: $exposedFaultMethods")
        assertFalse(javapEvidence.combinedOutput.contains("patchEngineFactory"), javapEvidence.filteredFaultEvidence())
        assertFalse(javapEvidence.combinedOutput.contains("withPatchFaultInjectorForTest"), javapEvidence.filteredFaultEvidence())
        assertFalse(javapEvidence.combinedOutput.contains("access\$setPatchEngineFactory"), javapEvidence.filteredFaultEvidence())
    }

    @Given("that construction seam exposes no mutable `PatchEngine`, patch-engine factory, injector property, setter, post-construction or global injection hook, or race; it can only construct the refusal CLI and cannot substitute a diagnostics provider, diagnostics gate, planner-returned plan, CLI authorization, or the no-fault production path")
    fun constructionSeamHasNoMutableOrGlobalHook() {
        val cliClass = RefactorKitCli::class.java
        val injectorFields = cliClass.declaredFields.filter { it.type == PatchFaultInjector::class.java }
        assertEquals(1, injectorFields.size, "PatchFaultInjector must be one private construction-time field")
        val injectorField = injectorFields.single()
        assertTrue(Modifier.isPrivate(injectorField.modifiers))
        assertTrue(Modifier.isFinal(injectorField.modifiers))
        assertFalse(Modifier.isStatic(injectorField.modifiers))
        assertEquals("patchFaultInjector", injectorField.name)
        assertTrue(cliClass.declaredFields.none { field ->
            field.type.name == "org.refactorkit.core.PatchEngine" ||
                field.name.contains("patchEngineFactory", ignoreCase = true) ||
                (field.type.name == "kotlin.jvm.functions.Function1" && field.name.contains("patch", ignoreCase = true))
        })
        assertTrue((listOf(cliClass) + cliClass.declaredClasses).flatMap { it.declaredMethods.toList() }.none { method ->
            method.name.contains("setPatch", ignoreCase = true) || method.name.contains("setFault", ignoreCase = true)
        })

        val firstInjector = PatchFaultInjector { _, _, _ -> }
        val secondInjector = PatchFaultInjector { _, _, _ -> }
        val first = faultFactory.invoke(null, firstInjector) as RefactorKitCli
        val second = faultFactory.invoke(null, secondInjector) as RefactorKitCli
        val production = RefactorKitCli()
        injectorField.isAccessible = true
        assertSame(firstInjector, injectorField.get(first))
        assertSame(secondInjector, injectorField.get(second))
        assertSame(PatchFaultInjector.NONE, injectorField.get(production))
        assertTrue(injectorField.get(first) !== injectorField.get(second), "Fault state leaked across CLI instances")
        val guidanceField = cliClass.getDeclaredField("moveClassGuidanceOutput").apply { isAccessible = true }
        assertEquals(
            "org.refactorkit.cli.JavaMoveClassGuidanceJsonRenderer",
            guidanceField.get(first).javaClass.name,
            "The fault token must not replace the production guidance adapter",
        )
        val semanticFactoryField = cliClass.getDeclaredField("semanticSessionFactory").apply { isAccessible = true }
        val constructionToken = Class.forName("org.refactorkit.cli.CliPatchFaultConstruction")
        assertFalse(constructionToken.isInstance(semanticFactoryField.get(first)))
        assertTrue(exactApplyCommand.none { it.contains("fault", ignoreCase = true) })
    }

    @When("the exact command is invoked against the refusal-probe copy through that reflectively constructed refusal CLI after substituting only `<case-workspace-absolute-path>` with that copy's normalized absolute root, and the existing `BEFORE_AUTHORITY_LEASE_VALIDATION` fault point replaces the final LF byte of required {string} with ASCII space after workspace-lock acquisition")
    fun invokeRefusalCommandWithOneUnderLockPomMutation(requiredPom: String) {
        assertEquals(REQUIRED_POM.invariantSeparatorsPathString, requiredPom)
        startAuthorityMonitorIfNeeded()
        val pom = refusalRoot.resolve(REQUIRED_POM).normalize()
        assertTrue(pom.startsWith(refusalRoot))
        assertTrue(Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS))
        refusalOriginalPom = readAllBytesNoFollow(pom)
        assertTrue(assertNotNull(refusalOriginalPom).isNotEmpty())
        assertEquals('\n'.code.toByte(), assertNotNull(refusalOriginalPom).last())

        val injector = PatchFaultInjector { point, _, sequence ->
            if (point != PatchFaultPoint.BEFORE_AUTHORITY_LEASE_VALIDATION) return@PatchFaultInjector
            check(sequence == 0)
            check(faultInvocationCount.incrementAndGet() == 1)
            check(Files.isRegularFile(refusalRoot.resolve(WORKSPACE_LOCK), LinkOption.NOFOLLOW_LINKS))
            faultObservedWorkspaceLock = true
            val expected = assertNotNull(refusalOriginalPom).copyOf()
            expected[expected.lastIndex] = ' '.code.toByte()
            FileChannel.open(pom, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.position((expected.size - 1).toLong())
                val replacement = ByteBuffer.wrap(byteArrayOf(' '.code.toByte()))
                while (replacement.hasRemaining()) channel.write(replacement)
                channel.force(true)
            }
            refusalMutatedPom = expected
            faultInjected = true
        }
        val command = substituteApplyRoot(refusalRoot)
        val refusalCli = faultFactory.invoke(null, injector) as RefactorKitCli
        refusalResult = runCli(refusalCli, command)
        assertEquals(command.drop(1), commandArgumentsFor(refusalRoot))
        assertTrue(faultInjected)
        assertTrue(faultObservedWorkspaceLock)
        assertEquals(1, faultInvocationCount.get())
        expectedRefusalDiagnostic = independentlyExpectedRefusalDiagnostic()
    }

    @Then("the CLI exits non-zero, every hash placeholder in the exact stderr below is substituted with its independently retained or recomputed 64-character lowercase hexadecimal value from the lease, {string}, and the mutated refusal copy rather than a value parsed back from stderr, and each `<SP>` marker is decoded as one ASCII space")
    fun refusalHashesAreIndependent(snapshotName: String) {
        assertEquals("S0", snapshotName)
        val result = assertNotNull(refusalResult)
        assertNotEquals(0, result.exitCode, result.describe("refusal command"))
        val expected = assertNotNull(expectedRefusalDiagnostic)
        expected.hashes.values.forEach { hash -> assertTrue(SHA256.matches(hash), hash) }
        assertEquals(refusalS0.hash, expected.details.getValue("previewSnapshotSha256"))
        assertFalse(result.stderr.isBlank())
    }

    @Then("stderr consists of exactly these ordered lines followed by one line terminator and no other stderr text:")
    fun stderrIsExactlyTwoOrderedLines(table: DataTable) {
        val rows = table.asMaps()
        assertEquals(listOf("1", "2"), rows.map { it.getValue("order") })
        val diagnostic = assertNotNull(expectedRefusalDiagnostic)
        val substitutions = diagnostic.hashes.mapKeys { (key, _) -> "<$key>" }
        val decoded = rows.map { row ->
            substitutions.entries.fold(row.getValue("exact decoded line")) { line, (placeholder, value) ->
                line.replace(placeholder, value)
            }.replace("<SP>", " ")
        }
        assertEquals(listOf("Apply refused:", diagnostic.renderedLine), decoded)
        val expectedStderr = decoded.joinToString(System.lineSeparator()) + System.lineSeparator()
        assertEquals(expectedStderr, assertNotNull(refusalResult).stderr)
    }

    @Then("line 2 is the first and sole diagnostic, its code and message are exact, and its complete detail set is rendered once in ascending key order as shown")
    fun lineTwoIsTheSoleExactDiagnostic() {
        val diagnostic = assertNotNull(expectedRefusalDiagnostic)
        assertEquals("authorityLease.evidenceDrift", diagnostic.code)
        assertEquals(
            "Operation-authority lease evidence drift: kind=MAVEN_REACTOR_RAW_POM path=catalog-pricing/pom.xml " +
                "expectedContentSha256=${diagnostic.hashes.getValue("expected-content-sha256")} " +
                "observedContentSha256=${diagnostic.hashes.getValue("observed-content-sha256")} " +
                "expectedRequiredFileEvidenceSha256=${diagnostic.hashes.getValue("expected-required-file-evidence-sha256")} " +
                "observedRequiredFileEvidenceSha256=${diagnostic.hashes.getValue("observed-required-file-evidence-sha256")}",
            diagnostic.message,
        )
        assertEquals(diagnostic.details.keys.sorted(), diagnostic.details.keys.toList())
        val lines = assertNotNull(refusalResult).stderr.removeSuffix(System.lineSeparator()).split(System.lineSeparator())
        assertEquals(listOf("Apply refused:", diagnostic.renderedLine), lines)
        val renderedDetails = lines[1].substringAfter(" (").removeSuffix(")")
        assertEquals(
            diagnostic.details.entries.joinToString(", ") { (key, value) -> "$key=$value" },
            renderedDetails,
        )
        diagnostic.details.forEach { (key, value) ->
            assertEquals(1, Regex("(?<![A-Za-z])${Regex.escape(key)}=${Regex.escape(value)}").findAll(renderedDetails).count(), key)
        }
    }

    @Then("an executable probe through that same production diagnostic-line renderer maps severity ERROR, message {string}, code {string}, and empty details to exact decoded line `<SP><SP>ERROR [probe.code]: coded refusal`, so every present code renders independently of whether details are empty")
    fun codedDiagnosticWithEmptyDetailsUsesSameRenderer(message: String, code: String) {
        assertEquals("coded refusal", message)
        assertEquals("probe.code", code)
        val diagnostic = Diagnostic(message, Diagnostic.Severity.ERROR, code = code, details = DiagnosticDetails.EMPTY)
        val rendered = CliDiagnosticLineRendererProbe.render(diagnostic)
        assertEquals("  ERROR [probe.code]: coded refusal", rendered)
        assertEquals(
            listOf(
                "  ERROR: first ordinary refusal",
                "  WARNING: second ordinary refusal",
            ),
            CliDiagnosticLineRendererProbe.renderSelected(listOf(
                Diagnostic("first ordinary refusal", Diagnostic.Severity.ERROR, code = "probe.first"),
                Diagnostic("second ordinary refusal", Diagnostic.Severity.WARNING, code = "probe.second"),
            )),
            "Ordinary refusals must retain every diagnostic in order",
        )
    }

    @Then("refusal occurs before any PREPARED journal state, write-ahead-log record, or managed entry of the canonical five-entry target edit")
    fun refusalPrecedesPreparedWalAndManagedEdit() {
        assertTrue(transactionLog(refusalRoot).listRecordsReadOnly().isEmpty())
        val transactionDirectory = refusalRoot.resolve(TRANSACTION_DIRECTORY)
        if (Files.exists(transactionDirectory, LinkOption.NOFOLLOW_LINKS)) {
            assertEquals(setOf("."), captureManifest(transactionDirectory).entries.keys)
        }
        val expectedMutation = assertNotNull(refusalMutatedPom)
        val currentTracked = scanTrackedBytes(refusalRoot)
        val baselineTracked = scanTrackedBytesFromSnapshot(refusalS0)
        val c1Tracked = WorkspaceEditSimulator.apply(
            refusalS0,
            WorkspaceEditSimulator.normalize(refusalCanonicalPlan.workspaceEdit),
        ).trackedFiles.associate { it.path.normalize() to it.content.toByteArray(StandardCharsets.UTF_8) }
        canonicalPlan.workspaceEdit.affectedFiles().map(Path::normalize).forEach { path ->
            when (path) {
                REQUIRED_POM -> {
                    assertTrue(currentTracked.getValue(path).contentEquals(expectedMutation))
                    assertFalse(currentTracked.getValue(path).contentEquals(c1Tracked.getValue(path)))
                }
                else -> {
                    val expected = baselineTracked[path]
                    val actual = currentTracked[path]
                    if (expected == null) assertNull(actual, "Managed target exists after refusal: $path")
                    else assertTrue(assertNotNull(actual, "Managed source is missing after refusal: $path").contentEquals(expected))
                }
            }
        }
    }

    @Then("the refusal probe preserves the externally changed final POM byte; every other non-engine byte and every path kind remains exact {string}, no equality of the whole copy to {string} is asserted, and possible workspace-lock residue is not a PREPARED record or transaction")
    fun refusalPreservesOnlyExternalPomByte(snapshotName: String, repeatedSnapshotName: String) {
        assertEquals("S0", snapshotName)
        assertEquals("S0", repeatedSnapshotName)
        val pom = refusalRoot.resolve(REQUIRED_POM)
        val original = assertNotNull(refusalOriginalPom)
        val expected = assertNotNull(refusalMutatedPom)
        val observed = readAllBytesNoFollow(pom)
        assertTrue(observed.contentEquals(expected))
        assertEquals(original.size, observed.size)
        assertTrue(original.copyOfRange(0, original.lastIndex).contentEquals(observed.copyOfRange(0, observed.lastIndex)))
        assertEquals(' '.code.toByte(), observed.last())
        val expectedEntries = refusalBaselineManifest.entries.toMutableMap()
        expectedEntries[REQUIRED_POM.invariantSeparatorsPathString] = manifestEntry(observed)
        assertEquals(ExactManifest(expectedEntries), captureManifest(refusalRoot, excludeEngine = true))
        assertTrue(transactionLog(refusalRoot).listRecordsReadOnly().isEmpty())
        assertTrue(Files.isRegularFile(refusalRoot.resolve(WORKSPACE_LOCK), LinkOption.NOFOLLOW_LINKS))
    }

    @When("the exact command is invoked against the unchanged case workspace through the production CLI path after substituting only `<case-workspace-absolute-path>` with that copy's normalized absolute root and without the seam or fault")
    fun invokeProductionApplyCommandWithoutSeam() {
        assertEquals(baselineManifest, captureManifest(workspaceRoot))
        assertTrue(transactionLog(workspaceRoot).listRecordsReadOnly().isEmpty())
        val affectedPaths = orderedAffectedPaths()
        rawS0Images = captureFilesystemImages(workspaceRoot, affectedPaths)
        preImageOracle = assertNotNull(rawS0Images).map(FilesystemFileImage::toPreJournalOracle)
        preOracleCapturedAt = ++sequence

        val command = substituteApplyRoot(workspaceRoot)
        productionCliRunCount += 1
        primaryResult = runCli(RefactorKitCli(), command)
        assertEquals(command.drop(1), commandArgumentsFor(workspaceRoot))
        assertEquals(1, faultInvocationCount.get(), "The production path must not use the refusal injector")

        rawS1Images = captureFilesystemImages(workspaceRoot, affectedPaths)
        postImageOracle = derivePostImageOracle(assertNotNull(rawS0Images), assertNotNull(rawS1Images))
        postOracleCapturedAt = ++sequence
        s1 = scan(workspaceRoot)
    }

    @Then("`--apply` supplies explicit CLI approval for the exact planner-returned {string} plan")
    fun applySuppliesExplicitCliApproval(operation: String) {
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, operation)
        assertEquals("--apply", exactApplyCommand.last())
        assertTrue(canonicalPlan.requiresUserApproval)
        assertEquals(1, productionCliRunCount)
    }

    @Then("that real CLI apply invokes the normal five-argument `ManagedApplyDiagnosticsGateSelector.select` production entry exactly once with the exact plan, language ID {string}, the adapters current for the CLI invocation, and its normal external-gate resolver instead of a CLI-local operation `when` branch or any duplicated routing decision")
    fun realCliUsesSharedSelectorExactlyOnce(languageId: String) {
        assertEquals("java", languageId)
        assertTrue(selectorQualificationAccepted)
        val block = javapEvidence.applyPlanAndLogBlock
        assertEquals(1, SELECTOR_INVOKE.findAll(block).count(), block)
        assertFalse(block.contains("PatchPlan.getOperation"), block)
        assertTrue(block.contains("Field javaAdapter:"), block)
        assertTrue(block.contains("Field kotlinAdapter:"), block)
        assertTrue(block.contains("applyPlanAndLog\$diagnosticsGate\$1"), block)
        assertTrue(javapEvidence.combinedOutput.contains("normalExternalGateResolver"))
        assertEquals(1, productionCliRunCount)
        assertEquals(0, assertNotNull(primaryResult).exitCode, assertNotNull(primaryResult).describe("shared selector apply"))
        selectorArchitectureQualified = true
    }

    @Then("the selected real operation-owned gate {string} uses that exact plan and lease to evaluate authoritative {string} and staged {string} before PREPARED, then committed {string} with {string} after APPLIED and before CLI success")
    fun operationOwnedGateProducesAuthoritativeS1(
        gateId: String,
        baselineName: String,
        candidateName: String,
        postImageName: String,
        diagnosticsName: String,
    ) {
        assertEquals(JavaRenameMavenModulePlanner.DIAGNOSTICS_GATE_ID, gateId)
        assertEquals("S0", baselineName)
        assertEquals("C1", candidateName)
        assertEquals("S1", postImageName)
        assertEquals("D0", diagnosticsName)
        assertTrue(selectorArchitectureQualified)
        val record = readSolePrimaryRecord()
        val committed = assertNotNull(s1)
        assertSameTrackedFiles(c1, committed)
        assertNotEquals(c1.hash, committed.hash, "Authoritative S1 must refresh Maven semantic state beyond simulated C1")
        assertEquals(committed.hash, record.postSnapshotHash)
        assertEquals(d0, diagnostics(committed))
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, record.operation)
        assertEquals(canonicalLease.operation, record.operation)
    }

    @Then("combined executable evidence uses this real CLI invocation to qualify selector adoption and committed {string}, and REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-001's dynamic invocation counts to prove zero generic {string} provider invocations and exact one-time lazy authoritative-provider construction; source or bytecode occurrence and absence of a gate ID from stdout or stderr are not accepted as substitutes")
    fun combinedSelectorEvidenceUsesDynamicQualification(postImageName: String, genericGate: String) {
        assertEquals("S1", postImageName)
        assertEquals("java-jdt", genericGate)
        assertTrue(selectorQualificationAccepted)
        assertTrue(selectorArchitectureQualified)
        assertEquals(0, assertNotNull(primaryResult).exitCode)
        assertEquals(assertNotNull(s1).hash, assertNotNull(appliedRecord).postSnapshotHash)
        scenario.attach(
            "Dynamic real-CLI apply + one five-argument selector invocation in applyPlanAndLog; " +
                "REQ-001 separately qualifies zero java-jdt provider calls and one lazy authoritative construction.",
            "text/plain",
            "selector-adoption",
        )
    }

    @Then("the sole journal record has integer literal `schemaVersion` 8, never an expected value obtained from `CURRENT_SCHEMA_VERSION` or another production current-version constant, and first becomes PREPARED only after approval, live-snapshot, lease, destination, raw-POM, effective-reactor, and staged-authority validation")
    fun preparedRecordUsesLiteralSchemaEight() {
        val record = readSolePrimaryRecord()
        assertEquals(8, record.schemaVersion)
        assertEquals(JournalState.APPLIED, record.state)
        assertEquals(
            listOf(JournalState.PREPARED, JournalState.APPLYING, JournalState.APPLIED),
            record.history.map { it.state },
        )
        assertEquals(
            "filesystem, snapshot, edit, approval, precondition, and diagnostics validation passed; workspace-staging=transaction-path-v2",
            record.history.first().detail,
        )
        assertEquals(ApprovalKind.EXPLICIT_APPLY, record.transaction.approval.kind)
        assertEquals(s0.hash, record.preSnapshotHash)
        assertEquals(assertNotNull(s1).hash, record.postSnapshotHash)
        assertEquals(BuildModelStatus.AVAILABLE, assertNotNull(s1).buildModels.single().status)
        assertTrue(canonicalLease.requiredFileEvidence.any { it.path == REQUIRED_POM })
    }

    @Then("the CLI succeeds with one transaction ID after that same record reaches APPLIED with the exact five-entry forward edit, complete {string} and {string} images, and approval surface {string}")
    fun cliReturnsOneExactAppliedTransaction(
        baselineName: String,
        postImageName: String,
        approvalSurface: String,
    ) {
        assertEquals("S0", baselineName)
        assertEquals("S1", postImageName)
        assertEquals("cli", approvalSurface)
        val result = assertNotNull(primaryResult)
        val ids = TRANSACTION_OUTPUT.findAll(result.stdout).map { it.groupValues[1] }.toList()
        assertEquals(1, ids.size, result.describe("transaction output"))
        transactionId = ids.single()
        val record = readSolePrimaryRecord()
        assertEquals(8, record.schemaVersion)
        assertEquals(JournalState.APPLIED, record.state)
        assertEquals(assertNotNull(transactionId), record.transaction.id.value)
        assertEquals(approvalSurface, record.transaction.approval.surface)
        assertEquals(ApprovalKind.EXPLICIT_APPLY, record.transaction.approval.kind)
        assertEquals(5, record.forwardEdit.edits.size)
        assertEquals(WorkspaceEditSimulator.normalize(canonicalPlan.workspaceEdit), record.forwardEdit)
    }

    @Then("before any journal image or directory field is used as an oracle, independent no-follow filesystem observations capture exact {string} before apply and committed {string} before journal inspection and project both through the schema-version-8 `FileImage` field contract")
    fun independentImagesPrecedeJournalInspection(baselineName: String, postImageName: String) {
        assertEquals("S0", baselineName)
        assertEquals("S1", postImageName)
        assertTrue(preOracleCapturedAt > 0)
        assertTrue(postOracleCapturedAt > preOracleCapturedAt)
        assertTrue(firstJournalInspectionAt > postOracleCapturedAt)
        assertEquals(orderedAffectedPaths(), assertNotNull(preImageOracle).map { it.path })
        assertEquals(orderedAffectedPaths(), assertNotNull(postImageOracle).map { it.path })
    }

    @Then("the ordered pre-images and post-images in the record equal that independent oracle field by field for every canonical affected path:")
    fun orderedJournalImagesEqualIndependentOracle(table: DataTable) {
        assertEquals(EXPOSED_FILE_IMAGE_FIELDS, table.asMaps().map { it.getValue("exposed FileImage field") })
        val record = readSolePrimaryRecord()
        assertJournalImages("pre", assertNotNull(preImageOracle), record.preImages)
        assertJournalImages("post", assertNotNull(postImageOracle), record.postImages)
    }

    @Then("the record's `createdDirectories` equals this exact independently declared ordered list rather than a value copied from that record:")
    fun createdDirectoriesEqualLiteralList(table: DataTable) {
        val rows = table.asMaps()
        assertEquals((1..8).map(Int::toString), rows.map { it.getValue("order") })
        val declared = rows.map { Path.of(it.getValue("normalized directory path")).normalize() }
        assertEquals(EXPECTED_CREATED_DIRECTORIES, declared)
        literalCreatedDirectoriesOracle = declared.toList()
        assertEquals(declared, readSolePrimaryRecord().createdDirectories.map(Path::normalize))
    }

    @Then("the independent image oracle and literal directory list are retained for the rollback assertion and are never obtained from either the APPLIED record under test or a later copy of the same record")
    fun retainIndependentOraclesForRollback() {
        assertTrue(preOracleCapturedAt < firstJournalInspectionAt)
        assertTrue(postOracleCapturedAt < firstJournalInspectionAt)
        assertNotNull(preImageOracle)
        assertNotNull(postImageOracle)
        assertEquals(EXPECTED_CREATED_DIRECTORIES, assertNotNull(literalCreatedDirectoriesOracle))
        appliedRecord = readSolePrimaryRecord()
    }

    @Then("the committed non-engine workspace is exact {string} and no second record or target edit exists")
    fun committedWorkspaceIsExactS1(snapshotName: String) {
        assertEquals("S1", snapshotName)
        val committed = assertNotNull(s1)
        val expected = expectedManifestAfterPlan(baselineManifest, canonicalPlan, committed)
        assertEquals(expected, captureManifest(workspaceRoot, excludeEngine = true))
        assertEquals(1, transactionLog(workspaceRoot).listRecordsReadOnly().size)
        assertTrue(Files.isDirectory(workspaceRoot.resolve(NEW_MODULE), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(workspaceRoot.resolve(OLD_MODULE), LinkOption.NOFOLLOW_LINKS))
        assertEquals(1, directRootModules(workspaceRoot).count { it == NEW_MODULE })
        assertFalse(directRootModules(workspaceRoot).contains(OLD_MODULE))
    }

    @When("`refactorkit patch rollback <transaction-id> --root <case-workspace-absolute-path>` is invoked without force after substituting `<transaction-id>` with that transaction ID and `<case-workspace-absolute-path>` with the same normalized absolute disposable-copy root used by the primary invocation")
    fun invokeExactNormalRollbackCommand() {
        val id = assertNotNull(transactionId)
        val command = listOf("refactorkit", "patch", "rollback", id, "--root", workspaceRoot.toString())
        assertFalse(command.contains("--force"))
        assertEquals(workspaceRoot, Path.of(command.last()).toAbsolutePath().normalize())
        rollbackResult = runCli(RefactorKitCli(), command)
    }

    @Then("normal rollback succeeds and the same record with integer literal `schemaVersion` 8 reaches ROLLED_BACK without a second transaction and still equals the independent pre-image, post-image, and exact `createdDirectories` oracles")
    fun normalRollbackAdvancesSameRecordAndRetainsOracles() {
        val result = assertNotNull(rollbackResult)
        assertEquals(0, result.exitCode, result.describe("normal rollback"))
        assertTrue(result.stdout.contains("Rolled back transaction ${assertNotNull(transactionId)}."))
        assertFalse(result.stdout.contains("Force rolled back"))
        val records = transactionLog(workspaceRoot).listRecordsReadOnly()
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals(8, record.schemaVersion)
        assertEquals(assertNotNull(transactionId), record.transaction.id.value)
        assertEquals(JournalState.ROLLED_BACK, record.state)
        assertEquals(
            listOf(
                JournalState.PREPARED,
                JournalState.APPLYING,
                JournalState.APPLIED,
                JournalState.ROLLING_BACK,
                JournalState.ROLLED_BACK,
            ),
            record.history.map { it.state },
        )
        assertJournalImages("rolled-back pre", assertNotNull(preImageOracle), record.preImages)
        assertJournalImages("rolled-back post", assertNotNull(postImageOracle), record.postImages)
        assertEquals(assertNotNull(literalCreatedDirectoriesOracle), record.createdDirectories.map(Path::normalize))
        val restoredImages = captureFilesystemImages(workspaceRoot, orderedAffectedPaths())
        assertEquals(assertNotNull(rawS0Images), restoredImages, "Rollback did not restore independent S0 file metadata")
    }

    @Then("every non-engine byte, path kind, reactor fact, snapshot identity, auxiliary-POM fact, and diagnostic equals the original {string} and {string}")
    fun rollbackRestoresAllOriginalFacts(snapshotName: String, diagnosticsName: String) {
        assertEquals("S0", snapshotName)
        assertEquals("D0", diagnosticsName)
        assertEquals(baselineManifest, captureManifest(workspaceRoot, excludeEngine = true))
        val restored = scan(workspaceRoot)
        assertEquals(s0, restored)
        assertEquals(s0.hash, restored.hash)
        assertEquals(s0.auxiliaryFiles, restored.auxiliaryFiles)
        assertEquals(baselineReactorFacts, reactorFacts(workspaceRoot, restored))
        assertEquals(d0, diagnostics(restored))
        assertTrue(Files.isDirectory(workspaceRoot.resolve(OLD_MODULE), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(NEW_MODULE), LinkOption.NOFOLLOW_LINKS))
        assertEquals(permanentManifest, captureManifest(fixtureRoot))
    }

    @Then("in the primary copy only the workspace lock and the one advanced record with integer literal `schemaVersion` 8 remain as expected engine residue")
    fun onlyExpectedEngineResidueRemains() {
        val id = assertNotNull(transactionId)
        val engineRoot = workspaceRoot.resolve(ENGINE_DIRECTORY)
        val residue = captureManifest(engineRoot)
        assertEquals(setOf(".", "workspace.lock", "transactions", "transactions/$id.json"), residue.entries.keys)
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(WORKSPACE_LOCK), LinkOption.NOFOLLOW_LINKS))
        val record = transactionLog(workspaceRoot).listRecordsReadOnly().single()
        assertEquals(8, record.schemaVersion)
        assertEquals(JournalState.ROLLED_BACK, record.state)
        assertEquals(setOf("."), captureManifest(isolatedUserHome).entries.keys, "CLI touched isolated user-home state")
        assertTrue(directAuthorityContractReused)
        authorityMonitor?.assertNoRefactorKitProcessOrSocketAuthority("complete-surface-002-flow")
    }

    private fun independentlyExpectedRefusalDiagnostic(): ExpectedDiagnostic {
        val lease = assertNotNull(refusalCanonicalPlan.authorityLease)
        val evidence = lease.requiredFileEvidence.single { it.path.normalize() == REQUIRED_POM }
        val original = assertNotNull(refusalOriginalPom)
        val mutated = assertNotNull(refusalMutatedPom)
        val expectedContent = sha256(original)
        val observedContent = sha256(mutated)
        assertEquals(evidence.expectedContentSha256, expectedContent)
        val expectedEvidenceHash = independentFileEvidenceSha256(
            lease,
            lease.requiredFileEvidence.associate { it.path to it.expectedContentSha256 },
        )
        assertEquals(lease.requiredFileEvidenceSha256, expectedEvidenceHash)
        val observedIdentities = lease.requiredFileEvidence.associate { record ->
            record.path to if (record.path.normalize() == REQUIRED_POM) observedContent else record.expectedContentSha256
        }
        val observedEvidenceHash = independentFileEvidenceSha256(lease, observedIdentities)
        val observedSnapshot = snapshotHashWithMutatedFile(refusalS0, REQUIRED_POM, mutated)
        val candidateInventory = assertNotNull(lease.attributes["candidateInventoryHash"])
        val changedSourceManaged = REQUIRED_POM in refusalCanonicalPlan.workspaceEdit.affectedFiles().map(Path::normalize)
        val details = sortedMapOf(
            "authorityLayer" to "EVIDENCE_FRESHNESS",
            "changedSourceManaged" to changedSourceManaged.toString(),
            "evidenceKind" to evidence.kind,
            "expectedCandidateInventorySha256" to candidateInventory,
            "expectedContentSha256" to expectedContent,
            "expectedRequiredFileEvidenceSha256" to expectedEvidenceHash,
            "observedContentSha256" to observedContent,
            "observedRequiredFileEvidenceSha256" to observedEvidenceHash,
            "observedSnapshotSha256" to observedSnapshot,
            "path" to REQUIRED_POM.invariantSeparatorsPathString,
            "previewSnapshotSha256" to refusalS0.hash,
        )
        val message = "Operation-authority lease evidence drift: kind=${evidence.kind} " +
            "path=${REQUIRED_POM.invariantSeparatorsPathString} expectedContentSha256=$expectedContent " +
            "observedContentSha256=$observedContent expectedRequiredFileEvidenceSha256=$expectedEvidenceHash " +
            "observedRequiredFileEvidenceSha256=$observedEvidenceHash"
        val rendered = "  ERROR [authorityLease.evidenceDrift]: $message " +
            details.entries.joinToString(prefix = "(", postfix = ")") { (key, value) -> "$key=$value" }
        return ExpectedDiagnostic(
            code = "authorityLease.evidenceDrift",
            message = message,
            details = details,
            renderedLine = rendered,
            hashes = mapOf(
                "expected-content-sha256" to expectedContent,
                "observed-content-sha256" to observedContent,
                "expected-required-file-evidence-sha256" to expectedEvidenceHash,
                "observed-required-file-evidence-sha256" to observedEvidenceHash,
                "expected-candidate-inventory-sha256" to candidateInventory,
                "observed-snapshot-sha256" to observedSnapshot,
                "preview-snapshot-sha256" to refusalS0.hash,
            ),
        )
    }

    private fun independentFileEvidenceSha256(
        lease: OperationAuthorityLease,
        contentIdentities: Map<Path, String>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        lease.requiredFileEvidence.sortedBy { it.path.invariantSeparatorsPathString }.forEach { record ->
            listOf(record.kind, record.path.invariantSeparatorsPathString, contentIdentities.getValue(record.path)).forEach { part ->
                digest.update(part.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
            }
            record.attributes.toSortedMap().forEach { (key, value) ->
                digest.update(key.toByteArray(StandardCharsets.UTF_8)); digest.update(0)
                digest.update(value.toByteArray(StandardCharsets.UTF_8)); digest.update(0)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun snapshotHashWithMutatedFile(snapshot: ProjectSnapshot, path: Path, bytes: ByteArray): String {
        val replacement = String(bytes, StandardCharsets.UTF_8)
        var replacements = 0
        fun replace(files: List<SourceFile>): List<SourceFile> = files.map { file ->
            if (file.path.normalize() == path.normalize()) {
                replacements += 1
                file.copy(content = replacement)
            } else file
        }
        val files = replace(snapshot.files)
        val auxiliary = replace(snapshot.auxiliaryFiles)
        assertEquals(1, replacements, "Mutated evidence file must occur exactly once in S0")
        return ProjectSnapshot.hashSnapshot(
            snapshot.modules,
            files,
            snapshot.sourceExtensions,
            snapshot.ignoredDirectories,
            snapshot.classpathEvidence,
            snapshot.buildModels,
            auxiliary,
        )
    }

    private fun orderedAffectedPaths(): List<Path> = WorkspaceEditSimulator.normalize(canonicalPlan.workspaceEdit)
        .affectedFiles().map(Path::normalize)

    private fun captureFilesystemImages(root: Path, paths: List<Path>): List<FilesystemFileImage> = paths.map { relative ->
        val normalized = relative.normalize()
        val absolute = root.resolve(normalized).normalize()
        require(absolute.startsWith(root.toAbsolutePath().normalize())) { "Image path escaped workspace: $relative" }
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            FilesystemFileImage(normalized, null, null, null, null, null, null, null, null)
        } else {
            require(!Files.isSymbolicLink(absolute) && Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
                "Affected path is not a no-follow regular file: $relative"
            }
            val bytes = readAllBytesNoFollow(absolute)
            val content = String(bytes, StandardCharsets.UTF_8)
            val basic = Files.readAttributes(absolute, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val posixView = Files.getFileAttributeView(
                absolute,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val posix = posixView?.readAttributes()
            val ownerView = Files.getFileAttributeView(
                absolute,
                FileOwnerAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val xattrView = Files.getFileAttributeView(
                absolute,
                UserDefinedFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val xattrs = xattrView?.list()?.sorted()?.associateWith { name ->
                val buffer = ByteBuffer.allocate(xattrView.size(name))
                xattrView.read(name, buffer)
                buffer.flip()
                val value = ByteArray(buffer.remaining())
                buffer.get(value)
                Base64.getEncoder().encodeToString(value)
            }
            val aclView = Files.getFileAttributeView(
                absolute,
                AclFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val acls = aclView?.acl?.map { entry ->
                FileAclEntryImage(
                    type = entry.type().name,
                    principal = entry.principal().name,
                    permissions = entry.permissions().map(AclEntryPermission::name).sorted(),
                    flags = entry.flags().map(AclEntryFlag::name).sorted(),
                )
            }
            FilesystemFileImage(
                path = normalized,
                content = content,
                contentSha256 = sha256(bytes),
                posixPermissions = posix?.permissions()?.toSet(),
                lastModifiedMillis = basic.lastModifiedTime().toMillis(),
                ownerName = ownerView?.owner?.name,
                groupName = posix?.group()?.name,
                userDefinedAttributes = xattrs?.toSortedMap(),
                aclEntries = acls,
            )
        }
    }

    private fun derivePostImageOracle(
        before: List<FilesystemFileImage>,
        after: List<FilesystemFileImage>,
    ): List<IndependentFileImage> {
        val origins = before.associate { image -> image.path to image.path.takeIf { image.content != null } }.toMutableMap()
        WorkspaceEditSimulator.normalize(canonicalPlan.workspaceEdit).edits.forEach { edit ->
            val source = edit.path.normalize()
            when (edit) {
                is FileEdit.Modify -> Unit
                is FileEdit.Rename -> {
                    val origin = origins[source]
                    origins[source] = null
                    origins[edit.newPath.normalize()] = origin
                }
                is FileEdit.Create -> origins[source] = null
                is FileEdit.Delete -> origins[source] = null
            }
        }
        val beforeByPath = before.associateBy(FilesystemFileImage::path)
        return after.map { observed ->
            if (observed.content == null) {
                IndependentFileImage(observed.path, null, null, null, null, null, null, null, null)
            } else {
                val source = assertNotNull(origins[observed.path], "Missing source metadata origin for ${observed.path}")
                val sourceImage = assertNotNull(beforeByPath[source], "Missing S0 source image $source")
                assertEquals(sourceImage.posixPermissions, observed.posixPermissions, "post permissions ${observed.path}")
                assertEquals(sourceImage.userDefinedAttributes, observed.userDefinedAttributes, "post xattrs ${observed.path}")
                assertEquals(sourceImage.aclEntries, observed.aclEntries, "post ACLs ${observed.path}")
                IndependentFileImage(
                    path = observed.path,
                    content = observed.content,
                    contentSha256 = observed.contentSha256,
                    posixPermissions = sourceImage.posixPermissions,
                    lastModifiedMillis = null,
                    ownerName = null,
                    groupName = null,
                    userDefinedAttributes = sourceImage.userDefinedAttributes,
                    aclEntries = sourceImage.aclEntries,
                )
            }
        }
    }

    private fun assertJournalImages(
        label: String,
        expected: List<IndependentFileImage>,
        actual: List<org.refactorkit.core.FileImage>,
    ) {
        assertEquals(expected.size, actual.size, "$label image count")
        expected.zip(actual).forEachIndexed { index, (oracle, image) ->
            val item = "$label[$index] ${oracle.path.invariantSeparatorsPathString}"
            assertEquals(oracle.path, image.path.normalize(), "$item path")
            assertEquals(oracle.content, image.content, "$item content")
            assertEquals(oracle.contentSha256, image.contentSha256, "$item contentSha256")
            assertEquals(oracle.posixPermissions, image.posixPermissions, "$item posixPermissions")
            assertEquals(oracle.lastModifiedMillis, image.lastModifiedMillis, "$item lastModifiedMillis")
            assertEquals(oracle.ownerName, image.ownerName, "$item ownerName")
            assertEquals(oracle.groupName, image.groupName, "$item groupName")
            assertEquals(oracle.userDefinedAttributes, image.userDefinedAttributes, "$item userDefinedAttributes")
            assertEquals(oracle.aclEntries, image.aclEntries, "$item aclEntries")
        }
    }

    private fun readSolePrimaryRecord(): TransactionJournalRecord {
        if (firstJournalInspectionAt < 0) firstJournalInspectionAt = ++sequence
        return transactionLog(workspaceRoot).listRecordsReadOnly().single().also { appliedRecord = it }
    }

    private fun javapRefactorKitCli(): JavapEvidence {
        val classes = listOf(
            RefactorKitCli::class.java,
            Class.forName("org.refactorkit.cli.RefactorKitCliKt"),
            Class.forName("org.refactorkit.cli.CliPatchFaultConstruction"),
        ) + RefactorKitCli::class.java.declaredClasses
        val outputs = classes.associate { type -> type.name to runJavap(type) }
        val main = outputs.getValue(RefactorKitCli::class.java.name)
        return JavapEvidence(outputs, extractJavapMethod(main, "applyPlanAndLog"))
    }

    private fun runJavap(type: Class<*>): String {
        val javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
        val executable = javaHome.resolve("bin").resolve(if (isWindows()) "javap.exe" else "javap")
        val classPath = Path.of(type.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
        val process = ProcessBuilder(
            executable.toString(),
            "-classpath",
            classPath.toString(),
            "-p",
            "-v",
            type.name,
        ).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exit = process.waitFor()
        assertEquals(0, exit, "javap failed for ${type.name}: $output")
        return output
    }

    private fun extractJavapMethod(output: String, methodName: String): String {
        val lines = output.lines()
        val start = lines.indexOfFirst { line -> line.startsWith("  private") && line.contains(" $methodName(") }
        assertTrue(start >= 0, "javap did not expose private method $methodName")
        var end = lines.size
        for (index in start + 1 until lines.size) {
            val line = lines[index]
            if (line.startsWith("  ") && !line.startsWith("    ") && line.endsWith(";") && line.contains('(')) {
                end = index
                break
            }
        }
        return lines.subList(start, end).joinToString("\n")
    }

    private fun startAuthorityMonitorIfNeeded() {
        if (authorityMonitor != null) return
        assertTrue(authorityObservationRequested)
        authorityMonitor = DeniedAuthorityMonitor.start(temporaryRoot.resolve("authority-events"))
    }

    private fun substituteApplyRoot(root: Path): List<String> {
        val normalized = root.toAbsolutePath().normalize()
        assertTrue(normalized.startsWith(temporaryRoot.toAbsolutePath().normalize()))
        val substituted = exactApplyCommand.map { token -> if (token == ROOT_PLACEHOLDER) normalized.toString() else token }
        assertEquals(0, substituted.count { it == ROOT_PLACEHOLDER })
        assertEquals(exactApplyCommand.size, substituted.size)
        assertEquals(listOf(10), substituted.indices.filter { substituted[it] != exactApplyCommand[it] })
        return substituted
    }

    private fun commandArgumentsFor(root: Path): List<String> = listOf(
        "java", "rename-module",
        "--old-module-dir", OLD_MODULE,
        "--new-module-dir", NEW_MODULE,
        "--new-artifact-id", NEW_ARTIFACT,
        "--root", root.toAbsolutePath().normalize().toString(),
        "--apply",
    )

    private fun runCli(cli: RefactorKitCli, fullCommand: List<String>): CliResult {
        check(mutationLockHeld && GLOBAL_MUTATION_LOCK.isHeldByCurrentThread) {
            "System stream mutation must remain serialized for the complete scenario"
        }
        assertEquals("refactorkit", fullCommand.first())
        val originalOut = System.out
        val originalErr = System.err
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        val stdout = PrintStream(stdoutBytes, true, StandardCharsets.UTF_8)
        val stderr = PrintStream(stderrBytes, true, StandardCharsets.UTF_8)
        System.setOut(stdout)
        System.setErr(stderr)
        return try {
            val exitCode = cli.run(fullCommand.drop(1))
            stdout.flush(); stderr.flush()
            CliResult(
                exitCode = exitCode,
                stdout = stdoutBytes.toString(StandardCharsets.UTF_8),
                stderr = stderrBytes.toString(StandardCharsets.UTF_8),
                command = fullCommand.map { token -> if (Path.of(token).isAbsolute) "<absolute-path>" else token },
            )
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            stdout.close()
            stderr.close()
            stdoutBytes.close()
            stderrBytes.close()
        }
    }

    private fun scan(root: Path): ProjectSnapshot {
        val normalized = root.toAbsolutePath().normalize()
        assertTrue(normalized.startsWith(temporaryRoot.toAbsolutePath().normalize()))
        assertEquals(isolatedUserHome.toString(), System.getProperty("user.home"))
        return JavaProjectScanner().scan(normalized)
    }

    private fun diagnostics(snapshot: ProjectSnapshot): DiagnosticBaseline = DiagnosticBaseline(
        maven = snapshot.buildModels.single().diagnostics.toList(),
        jdt = JavaLanguageAdapter().authoritativeDiagnostics(snapshot, Path.of(System.getProperty("java.home"))),
    )

    private fun reactorFacts(root: Path, snapshot: ProjectSnapshot): ReactorFacts {
        val model = snapshot.buildModels.single()
        return ReactorFacts(
            directModules = directRootModules(root),
            status = model.status,
            moduleIds = model.modules.map { it.id },
            moduleRoots = model.modules.associate { module ->
                module.id to root.toAbsolutePath().normalize().relativize(module.root.toAbsolutePath().normalize())
                    .invariantSeparatorsPathString
            },
            diagnostics = model.diagnostics,
        )
    }

    private fun assertSameTrackedFiles(expected: ProjectSnapshot, actual: ProjectSnapshot) {
        assertEquals(expected.trackedFiles.sortedBy { it.path.toString() }, actual.trackedFiles.sortedBy { it.path.toString() })
        assertEquals(expected.sourceExtensions, actual.sourceExtensions)
        assertEquals(expected.ignoredDirectories, actual.ignoredDirectories)
    }

    private fun expectedManifestAfterPlan(
        baseline: ExactManifest,
        plan: PatchPlan,
        committed: ProjectSnapshot,
    ): ExactManifest {
        val expected = baseline.entries.toMutableMap()
        val affected = plan.workspaceEdit.affectedFiles().map(Path::normalize).toSet()
        affected.forEach { path -> expected.remove(path.invariantSeparatorsPathString) }
        val committedByPath = committed.trackedFiles.associateBy { it.path.normalize() }
        affected.forEach { path ->
            val source = committedByPath[path] ?: return@forEach
            expected[path.invariantSeparatorsPathString] = manifestEntry(source.content.toByteArray(StandardCharsets.UTF_8))
            var parent = path.parent
            while (parent != null) {
                expected.putIfAbsent(parent.invariantSeparatorsPathString, ManifestEntry("directory", null, null))
                parent = parent.parent
            }
        }
        return ExactManifest(expected.toSortedMap())
    }

    private fun scanTrackedBytes(root: Path): Map<Path, ByteArray> = scan(root).trackedFiles.associate { source ->
        source.path.normalize() to source.content.toByteArray(StandardCharsets.UTF_8)
    }

    private fun scanTrackedBytesFromSnapshot(snapshot: ProjectSnapshot): Map<Path, ByteArray> =
        snapshot.trackedFiles.associate { source ->
            source.path.normalize() to source.content.toByteArray(StandardCharsets.UTF_8)
        }

    private fun transactionLog(root: Path): TransactionLog = TransactionLog(root.resolve(TRANSACTION_DIRECTORY).normalize())

    private fun directRootModules(root: Path): List<String> {
        val project = parsePom(root.resolve("pom.xml"))
        val modules = directChildren(project, "modules").single()
        return directChildren(modules, "module").map { it.textContent.trim() }
    }

    private fun parsePom(path: Path): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            return factory.newDocumentBuilder().parse(input).documentElement
        }
    }

    private fun directChildren(parent: Element, localName: String): List<Element> = buildList {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && (child.localName ?: child.nodeName.substringAfter(':')) == localName) add(child)
        }
    }

    private fun directChildText(parent: Element, localName: String): String? =
        directChildren(parent, localName).singleOrNull()?.textContent?.trim()

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("modules/refactorkit-cli"))
            ) return candidate
            candidate = candidate.parent
        }
        error("Cannot locate repository root")
    }

    private fun copyNoFollow(source: Path, target: Path) {
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Copy target already exists" }
        Files.walkFileTree(
            source,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symbolic-link directory refused" }
                    val destination = target.resolve(source.relativize(dir).toString()).normalize()
                    require(destination.startsWith(target.normalize())) { "Copy path escaped target" }
                    Files.createDirectories(destination)
                    copyPosixPermissions(dir, destination)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) { "Symbolic link refused" }
                    require(attrs.isRegularFile) { "Non-regular fixture entry refused" }
                    val destination = target.resolve(source.relativize(file).toString()).normalize()
                    require(destination.startsWith(target.normalize())) { "Copy path escaped target" }
                    Files.createDirectories(assertNotNull(destination.parent))
                    Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        assertEquals(captureManifest(source), captureManifest(target))
    }

    private fun copyPosixPermissions(source: Path, target: Path) {
        runCatching { Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS)) }
    }

    private fun captureManifest(root: Path, excludeEngine: Boolean = false): ExactManifest {
        val entries = sortedMapOf<String, ManifestEntry>()
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = manifestPath(root, dir)
                    if (excludeEngine && (relative == ENGINE_DIRECTORY || relative.startsWith("$ENGINE_DIRECTORY/"))) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symbolic link refused" }
                    entries[relative] = ManifestEntry("directory", null, null)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = manifestPath(root, file)
                    if (excludeEngine && (relative == ENGINE_DIRECTORY || relative.startsWith("$ENGINE_DIRECTORY/"))) {
                        return FileVisitResult.CONTINUE
                    }
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) { "Symbolic link refused" }
                    require(attrs.isRegularFile) { "Non-regular fixture entry refused" }
                    entries[relative] = manifestEntry(readAllBytesNoFollow(file))
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return ExactManifest(entries)
    }

    private fun manifestEntry(bytes: ByteArray): ManifestEntry = ManifestEntry("regular-file", bytes.size.toLong(), sha256(bytes))

    private fun manifestPath(root: Path, path: Path): String =
        root.relativize(path).normalize().invariantSeparatorsPathString.ifBlank { "." }

    private fun readAllBytesNoFollow(path: Path): ByteArray =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.readBytes() }

    private fun deleteNoFollow(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    makeWritableNoFollow(file)
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    makeWritableNoFollow(dir)
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun makeWritableNoFollow(path: Path) {
        Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.setReadOnly(false)
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.let { view ->
                val permissions = view.readAttributes().permissions().toMutableSet()
                permissions += PosixFilePermission.OWNER_WRITE
                Files.setPosixFilePermissions(path, permissions)
            }
    }

    private fun restoreGlobalStateAndUnlock() {
        try {
            previousUserHome?.let { System.setProperty("user.home", it) } ?: System.clearProperty("user.home")
            if (this::temporaryRoot.isInitialized) deleteNoFollow(temporaryRoot)
        } finally {
            if (mutationLockHeld) {
                mutationLockHeld = false
                GLOBAL_MUTATION_LOCK.unlock()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private data class ManifestEntry(val kind: String, val size: Long?, val sha256: String?)
    private data class ExactManifest(val entries: Map<String, ManifestEntry>)
    private data class DiagnosticBaseline(val maven: List<BuildModelDiagnostic>, val jdt: List<Diagnostic>)
    private data class ReactorFacts(
        val directModules: List<String>,
        val status: BuildModelStatus,
        val moduleIds: List<String>,
        val moduleRoots: Map<String, String>,
        val diagnostics: List<BuildModelDiagnostic>,
    )
    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String, val command: List<String>) {
        fun describe(label: String): String = "$label failed: exit=$exitCode command=$command stdout=$stdout stderr=$stderr"
    }
    private data class ExpectedDiagnostic(
        val code: String,
        val message: String,
        val details: Map<String, String>,
        val renderedLine: String,
        val hashes: Map<String, String>,
    )
    private data class FilesystemFileImage(
        val path: Path,
        val content: String?,
        val contentSha256: String?,
        val posixPermissions: Set<PosixFilePermission>?,
        val lastModifiedMillis: Long?,
        val ownerName: String?,
        val groupName: String?,
        val userDefinedAttributes: Map<String, String>?,
        val aclEntries: List<FileAclEntryImage>?,
    ) {
        fun toPreJournalOracle() = IndependentFileImage(
            path,
            content,
            contentSha256,
            posixPermissions,
            lastModifiedMillis,
            ownerName,
            groupName,
            userDefinedAttributes,
            aclEntries,
        )
    }
    private data class IndependentFileImage(
        val path: Path,
        val content: String?,
        val contentSha256: String?,
        val posixPermissions: Set<PosixFilePermission>?,
        val lastModifiedMillis: Long?,
        val ownerName: String?,
        val groupName: String?,
        val userDefinedAttributes: Map<String, String>?,
        val aclEntries: List<FileAclEntryImage>?,
    )
    private data class JavapEvidence(
        val outputs: Map<String, String>,
        val applyPlanAndLogBlock: String,
    ) {
        val combinedOutput: String = outputs.values.joinToString("\n")
        fun filteredFaultEvidence(): String = combinedOutput.lineSequence()
            .filter { line ->
                line.contains("PatchFault", ignoreCase = true) ||
                    line.contains("patchEngineFactory", ignoreCase = true) ||
                    line.contains("withPatch", ignoreCase = true)
            }
            .take(80)
            .joinToString("\n")
    }

    private data class ActivityEvidence(
        val processEvents: List<String>,
        val socketEvents: List<String>,
        val newDescendants: Set<Long>,
        val note: String,
    ) {
        val prohibitedSurfaceActivityObserved: Boolean
            get() = processEvents.isNotEmpty() || socketEvents.isNotEmpty() || newDescendants.isNotEmpty()
    }

    private class DeniedAuthorityMonitor private constructor(
        private val recording: Recording,
        private val outputDirectory: Path,
        private val baselineDescendants: Set<Long>,
    ) : AutoCloseable {
        private var dumpSequence = 0
        private val observedDescendants = linkedSetOf<Long>()
        private var running = true
        private var closed = false

        fun assertNoRefactorKitProcessOrSocketAuthority(label: String) {
            val evidence = evidence(label)
            assertTrue(evidence.processEvents.isEmpty(), "RefactorKit process authority observed: ${evidence.processEvents}")
            assertTrue(evidence.socketEvents.isEmpty(), "RefactorKit socket authority observed: ${evidence.socketEvents}")
            assertTrue(evidence.newDescendants.isEmpty(), "New child process observed: ${evidence.newDescendants}")
        }

        fun finish(): ActivityEvidence {
            if (closed) return ActivityEvidence(emptyList(), emptyList(), observedDescendants, "already closed")
            return try {
                observedDescendants += currentDescendants() - baselineDescendants
                if (running) {
                    recording.stop()
                    running = false
                }
                val path = outputDirectory.resolve("final.jfr")
                recording.dump(path)
                activityEvidence(RecordingFile.readAllEvents(path), observedDescendants, "final")
            } finally {
                close()
            }
        }

        override fun close() {
            if (closed) return
            var failure: Throwable? = null
            if (running) {
                try {
                    recording.stop()
                } catch (stopFailure: Throwable) {
                    failure = stopFailure
                }
            }
            running = false
            closed = true
            try {
                recording.close()
            } catch (closeFailure: Throwable) {
                failure?.addSuppressed(closeFailure) ?: run { failure = closeFailure }
            }
            failure?.let { throw it }
        }

        private fun evidence(label: String): ActivityEvidence {
            observedDescendants += currentDescendants() - baselineDescendants
            val path = outputDirectory.resolve("checkpoint-${dumpSequence++}.jfr")
            recording.dump(path)
            return activityEvidence(RecordingFile.readAllEvents(path), observedDescendants, label)
        }

        private fun activityEvidence(
            events: List<RecordedEvent>,
            descendants: Set<Long>,
            note: String,
        ): ActivityEvidence {
            val processEvents = events.filter { it.eventType.name == "jdk.ProcessStart" }.map { event ->
                "pid=${event.getLong("pid")} command=${event.getString("command")}"
            }
            val socketEvents = events.filter { event ->
                event.eventType.name in setOf("jdk.SocketRead", "jdk.SocketWrite") && hasRefactorKitFrame(event)
            }.map { event ->
                "${event.eventType.name} ${event.getString("host")}:${event.getInt("port")}"
            }
            return ActivityEvidence(processEvents.distinct(), socketEvents.distinct(), descendants.toSet(), note)
        }

        private fun hasRefactorKitFrame(event: RecordedEvent): Boolean = event.stackTrace?.frames.orEmpty().any { frame ->
            frame.method.type.name.startsWith("org.refactorkit.")
        }

        companion object {
            fun start(outputDirectory: Path): DeniedAuthorityMonitor {
                Files.createDirectories(outputDirectory)
                val recording = Recording()
                try {
                    recording.enable("jdk.ProcessStart").withStackTrace()
                    recording.enable("jdk.SocketRead").withThreshold(Duration.ZERO).withStackTrace()
                    recording.enable("jdk.SocketWrite").withThreshold(Duration.ZERO).withStackTrace()
                    val descendants = currentDescendants()
                    recording.start()
                    return DeniedAuthorityMonitor(recording, outputDirectory, descendants)
                } catch (failure: Throwable) {
                    try {
                        recording.close()
                    } catch (closeFailure: Throwable) {
                        failure.addSuppressed(closeFailure)
                    }
                    throw failure
                }
            }

            private fun currentDescendants(): Set<Long> {
                val result = linkedSetOf<Long>()
                ProcessHandle.current().descendants().use { descendants -> descendants.forEach { result += it.pid() } }
                return result
            }
        }
    }

    private companion object {
        val GLOBAL_MUTATION_LOCK = ReentrantLock(true)
        val SHA256 = Regex("[a-f0-9]{64}")
        val SELECTOR_INVOKE = Regex("ManagedApplyDiagnosticsGateSelector\\.select")
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val OLD_MODULE = "catalog-model"
        const val NEW_MODULE = "catalog-domain"
        const val NEW_ARTIFACT = "catalog-domain"
        const val ROOT_PLACEHOLDER = "<case-workspace-absolute-path>"
        const val ENGINE_DIRECTORY = ".refactorkit"
        const val WORKSPACE_LOCK = ".refactorkit/workspace.lock"
        const val TRANSACTION_DIRECTORY = ".refactorkit/transactions"
        const val EXACT_APPLY_COMMAND =
            "refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain " +
                "--new-artifact-id catalog-domain --root <case-workspace-absolute-path> --apply"
        val REQUIRED_POM: Path = Path.of("catalog-pricing/pom.xml")
        val TRANSACTION_OUTPUT = Regex("(?m)^Applied\\. Transaction: (transaction-[0-9a-f-]+)$")
        val EXPOSED_FILE_IMAGE_FIELDS = listOf(
            "path",
            "content",
            "contentSha256",
            "posixPermissions",
            "lastModifiedMillis",
            "ownerName",
            "groupName",
            "userDefinedAttributes",
            "aclEntries",
        )
        val EXPECTED_CREATED_DIRECTORIES = listOf(
            Path.of("catalog-domain"),
            Path.of("catalog-domain/src"),
            Path.of("catalog-domain/src/main"),
            Path.of("catalog-domain/src/main/java"),
            Path.of("catalog-domain/src/main/java/com"),
            Path.of("catalog-domain/src/main/java/com/acme"),
            Path.of("catalog-domain/src/main/java/com/acme/catalog"),
            Path.of("catalog-domain/src/main/java/com/acme/catalog/legacy"),
        )
    }
}
