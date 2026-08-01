package org.refactorkit.jvm.manageddiagnostics

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PlanId
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.jvm.ManagedApplyDiagnosticsGateSelector
import org.refactorkit.jvm.ManagedApplyDiagnosticsProviderFunctions
import org.refactorkit.kotlin.KotlinLanguageAdapter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ManagedApplyDiagnosticsGateSelectorSteps {
    private data class PendingInputs(
        val languageId: String,
        val operation: String,
        val evidence: RefactoringEvidence,
        val affectedFiles: Set<Path>,
    )

    private data class ProviderInvocation(
        val route: String,
        val adapter: Any,
        val snapshot: ProjectSnapshot,
    )

    private class ProviderProbe(
        val name: String,
        private val events: MutableList<String>,
    ) {
        val invocations = mutableListOf<ProviderInvocation>()
        val externalProviderSnapshots = mutableListOf<ProjectSnapshot>()
        val builtInResolverLanguages = mutableListOf<String>()
        private var explicitGateInvocation = false

        val providerFunctions = ManagedApplyDiagnosticsProviderFunctions(
            javaMavenOwnership = javaProvider(ROUTE_JAVA_MAVEN_OWNERSHIP),
            javaJdt = javaProvider(ROUTE_JAVA_JDT),
            kotlinJvmMoveDeclaration = kotlinProvider(ROUTE_KOTLIN_JVM_MOVE),
            javaKotlinPublicTypeRename = kotlinProvider(ROUTE_JAVA_KOTLIN_RENAME),
            kotlinJavaPublicTypeRename = kotlinProvider(ROUTE_KOTLIN_JAVA_RENAME),
            kotlinK2 = kotlinProvider(ROUTE_KOTLIN_K2),
        )

        fun invokeReturnedGate(gate: DiagnosticsGate, snapshot: ProjectSnapshot): List<Diagnostic> {
            assertFalse(explicitGateInvocation, "Nested diagnostics-gate invocation is not part of this fixture")
            explicitGateInvocation = true
            events += "gate-invoke:$name:${gate.id}"
            return try {
                assertNotNull(gate.provider, "The selected gate must be enabled for this acceptance fixture")(snapshot)
            } finally {
                explicitGateInvocation = false
            }
        }

        fun recordExternalProvider(snapshot: ProjectSnapshot): List<Diagnostic> {
            assertTrue(
                explicitGateInvocation,
                "The external diagnostics provider ran before the returned gate was explicitly invoked",
            )
            externalProviderSnapshots += snapshot
            events += "provider:$name:$ROUTE_EXTERNAL"
            return listOf(probeDiagnostic(ROUTE_EXTERNAL))
        }

        private fun javaProvider(
            route: String,
        ): (JavaLanguageAdapter, ProjectSnapshot) -> List<Diagnostic> = { adapter, snapshot ->
            record(route, adapter, snapshot)
        }

        private fun kotlinProvider(
            route: String,
        ): (KotlinLanguageAdapter, ProjectSnapshot) -> List<Diagnostic> = { adapter, snapshot ->
            record(route, adapter, snapshot)
        }

        private fun record(route: String, adapter: Any, snapshot: ProjectSnapshot): List<Diagnostic> {
            assertTrue(
                explicitGateInvocation,
                "Built-in provider '$route' ran during selection instead of after explicit gate invocation",
            )
            invocations += ProviderInvocation(route, adapter, snapshot)
            events += "provider:$name:$route"
            return listOf(probeDiagnostic(route))
        }

        private fun probeDiagnostic(route: String) = Diagnostic(
            message = "Synthetic diagnostics probe for $name and $route",
            severity = Diagnostic.Severity.INFO,
            code = diagnosticCode(name, route),
        )
    }

    private data class AttemptFixture(
        val attempt: String,
        val surface: String,
        val javaAdapterLabel: String,
        val kotlinAdapterLabel: String,
        val javaAdapter: JavaLanguageAdapter,
        val kotlinAdapter: KotlinLanguageAdapter,
        val probe: ProviderProbe,
    )

    private data class BuiltInRouteFixture(
        val route: String,
        val languageId: String,
        val operation: String,
        val evidence: RefactoringEvidence,
        val affectedFiles: List<String>,
    )

    private data class BuiltInSelection(
        val attempt: AttemptFixture,
        val fixture: BuiltInRouteFixture,
        val gate: DiagnosticsGate,
        val candidate: ProjectSnapshot,
    )

    private class ExternalResolverFixtureException(
        val code: String,
        message: String,
        cause: Throwable,
    ) : RuntimeException(message, cause)

    private val events = mutableListOf<String>()
    private val temporaryRoots = mutableListOf<Path>()
    private var workspaceRoot: Path? = null
    private var workspaceBaseline: Map<String, List<Byte>>? = null
    private var planSequence = 0

    private var pendingLanguageId: String? = null
    private var pendingPlan: PatchPlan? = null
    private var pendingInputs: PendingInputs? = null
    private var currentJavaAdapter: JavaLanguageAdapter? = null
    private var currentKotlinAdapter: KotlinLanguageAdapter? = null
    private var outlineProbe: ProviderProbe? = null
    private var exactOutlineExternalGate: DiagnosticsGate? = null
    private val outlineResolverLanguages = mutableListOf<String>()
    private var selectedOutlineGate: DiagnosticsGate? = null
    private var observedOutlineRow: String? = null

    private val attempts = linkedMapOf<String, AttemptFixture>()
    private val builtInSelections = mutableListOf<BuiltInSelection>()
    private val selectionSurfaces = mutableListOf<String>()
    private val successfulResolverLanguages = mutableListOf<String>()
    private val failingResolverLanguages = mutableListOf<String>()
    private val externalGateProviderSnapshots = mutableListOf<ProjectSnapshot>()
    private var exactExternalGateG: DiagnosticsGate? = null
    private var successfulExternalSelection: DiagnosticsGate? = null
    private var exactExternalFailure: ExternalResolverFixtureException? = null
    private var observedExternalFailure: Throwable? = null

    @After
    fun deleteSyntheticWorkspaces() {
        temporaryRoots.asReversed().forEach(::deleteTree)
        temporaryRoots.clear()
    }

    @Given("the selector owns only this precedence-ordered routing table:")
    fun selectorOwnsOnlyThisRoutingTable(table: DataTable) {
        assertEquals(EXPECTED_ROUTE_TABLE, table.asMaps())
        assertEquals((1..7).map(Int::toString), table.asMaps().map { it.getValue("order") })
    }

    @Given(
        """^javaAffected means exactly that an original plan\.affectedFiles final segment satisfies `fileName\.toString\(\)\.endsWith\("\.java"\)`$""",
    )
    fun javaAffectedUsesTheExactCaseSensitiveFinalSegmentPredicate() {
        assertTrue(Path.of("src/Foo.java").fileName.toString().endsWith(".java"))
        assertFalse(Path.of("src/Foo.JAVA").fileName.toString().endsWith(".java"))
        assertFalse(Path.of("src/Foo.java/Actually.kt").fileName.toString().endsWith(".java"))
    }

    @Given(
        "the pending plan has exact language ID {string}, operation {string}, evidence {string}, and original affected files {string}",
    )
    fun pendingPlanHasExactMetadata(
        languageId: String,
        operation: String,
        evidence: String,
        affectedFiles: String,
    ) {
        val paths = affectedFiles.split(',').map(String::trim).map(Path::of).toCollection(linkedSetOf())
        val parsedEvidence = RefactoringEvidence.valueOf(evidence)
        pendingLanguageId = languageId
        pendingInputs = PendingInputs(languageId, operation, parsedEvidence, paths)
        pendingPlan = syntheticPlan(operation, parsedEvidence, paths)
        currentJavaAdapter = JavaLanguageAdapter()
        currentKotlinAdapter = KotlinLanguageAdapter()
        outlineProbe = ProviderProbe("outline-${++planSequence}", events)
    }

    @Given("for an external route the surface resolver returns an exact lazy gate with ID {string}")
    fun externalResolverReturnsExactLazyGate(gateId: String) {
        val probe = requireNotNull(outlineProbe)
        exactOutlineExternalGate = DiagnosticsGate.enabled(gateId, probe::recordExternalProvider)
    }

    @When("daemon or MCP asks the stateless selector to choose the managed-apply diagnostics gate")
    fun daemonOrMcpChoosesTheGate() {
        val languageId = requireNotNull(pendingLanguageId)
        val probe = requireNotNull(outlineProbe)
        events += "select:start:outline:$languageId"
        selectedOutlineGate = ManagedApplyDiagnosticsGateSelector.select(
            plan = requireNotNull(pendingPlan),
            languageId = languageId,
            javaAdapter = requireNotNull(currentJavaAdapter),
            kotlinAdapter = requireNotNull(currentKotlinAdapter),
            externalGateResolver = { requestedLanguageId: String ->
                outlineResolverLanguages += requestedLanguageId
                events += "resolver:outline:$requestedLanguageId"
                requireNotNull(exactOutlineExternalGate)
            },
            providerFunctions = probe.providerFunctions,
        )
        events += "select:return:outline:$languageId"

        assertTrue(probe.invocations.isEmpty(), "A built-in diagnostics provider ran during selection")
        assertTrue(probe.externalProviderSnapshots.isEmpty(), "The external gate provider ran during selection")
    }

    @Then("precedence row {string} is the first match")
    fun precedenceRowIsFirstMatch(row: String) {
        val route = assertNotNull(ROUTE_BY_ROW[row], "Unknown expected precedence row: $row")
        val probe = requireNotNull(outlineProbe)
        val gate = requireNotNull(selectedOutlineGate)
        val candidate = syntheticSnapshot("outline-row-$row")

        assertTrue(probe.invocations.isEmpty())
        assertTrue(probe.externalProviderSnapshots.isEmpty())
        val diagnostics = probe.invokeReturnedGate(gate, candidate)
        observedOutlineRow = row

        if (route == ROUTE_EXTERNAL) {
            assertEquals(listOf(requireNotNull(pendingLanguageId)), outlineResolverLanguages)
            assertTrue(probe.invocations.isEmpty())
            assertEquals(listOf(candidate), probe.externalProviderSnapshots)
        } else {
            assertTrue(outlineResolverLanguages.isEmpty(), "A built-in route performed an external lookup")
            val invocation = assertNotNull(probe.invocations.singleOrNull())
            assertEquals(route, invocation.route)
            assertSame(candidate, invocation.snapshot)
            if (route in JAVA_ROUTES) {
                assertSame(requireNotNull(currentJavaAdapter), invocation.adapter)
            } else {
                assertSame(requireNotNull(currentKotlinAdapter), invocation.adapter)
            }
        }
        assertEquals(listOf(diagnosticCode(probe.name, route)), diagnostics.map(Diagnostic::code))
    }

    @Then("the returned result has exact gate ID {string} and is {string}")
    fun returnedResultHasExactGateAndProvider(gateId: String, providerOrIdentity: String) {
        val row = requireNotNull(observedOutlineRow)
        val gate = requireNotNull(selectedOutlineGate)
        assertEquals(gateId, gate.id)
        assertEquals(EXPECTED_PROVIDER_DESCRIPTION_BY_ROW.getValue(row), providerOrIdentity)

        if (row == "7") {
            assertSame(requireNotNull(exactOutlineExternalGate), gate)
        } else {
            assertTrue(gate !== exactOutlineExternalGate)
            assertEquals(1, requireNotNull(outlineProbe).invocations.size)
        }
    }

    @Then(
        "the selector does not normalize language IDs or paths, infer languages, recompute affected files, or narrow unusual metadata combinations",
    )
    fun selectorPreservesExactMetadata() {
        val expected = requireNotNull(pendingInputs)
        val plan = requireNotNull(pendingPlan)
        assertEquals(expected.languageId, pendingLanguageId)
        assertEquals(expected.operation, plan.operation)
        assertEquals(expected.evidence, plan.evidence)
        assertEquals(expected.affectedFiles, plan.affectedFiles)
        assertEquals(expected.affectedFiles.map(Path::toString).toSet(), plan.affectedFiles.map(Path::toString).toSet())

        if (observedOutlineRow == "7") {
            assertEquals(listOf(expected.languageId), outlineResolverLanguages)
        } else {
            assertTrue(outlineResolverLanguages.isEmpty())
        }
        assertWorkspaceUnchanged()
    }

    @Given("each managed-apply attempt supplies the selector with its adapter fields current for that call:")
    fun eachAttemptSuppliesCurrentAdapters(table: DataTable) {
        assertEquals(EXPECTED_ATTEMPT_TABLE, table.asMaps())
        table.asMaps().forEach { row ->
            val attempt = row.getValue("attempt")
            attempts[attempt] = AttemptFixture(
                attempt = attempt,
                surface = row.getValue("surface"),
                javaAdapterLabel = row.getValue("current Java adapter"),
                kotlinAdapterLabel = row.getValue("current Kotlin adapter"),
                javaAdapter = JavaLanguageAdapter(),
                kotlinAdapter = KotlinLanguageAdapter(),
                probe = ProviderProbe(attempt, events),
            )
        }
        assertEquals(listOf("first", "second"), attempts.keys.toList())
    }

    @Given(
        "every built-in diagnostics provider, external gate provider, and external adapter lookup is independently observable",
    )
    fun everyProviderAndLookupIsObservable() {
        assertEquals(2, attempts.size)
        assertTrue(attempts.values.all { it.probe.invocations.isEmpty() })
        establishWorkspaceBaseline()
    }

    @When("all six built-in routes and these isolated external outcomes are selected without invoking PatchEngine:")
    fun selectAllBuiltInRoutesAndExternalOutcomes(table: DataTable) {
        assertEquals(EXPECTED_EXTERNAL_OUTCOME_TABLE, table.asMaps())
        assertEquals(6, BUILT_IN_ROUTE_FIXTURES.size)

        BUILT_IN_ROUTE_FIXTURES.forEach { fixture ->
            attempts.values.forEach { attempt ->
                val plan = syntheticPlan(
                    fixture.operation,
                    fixture.evidence,
                    fixture.affectedFiles.map(Path::of).toCollection(linkedSetOf()),
                )
                val gate = selectForAttempt(attempt, plan, fixture.languageId) { unexpectedLanguageId ->
                    attempt.probe.builtInResolverLanguages += unexpectedLanguageId
                    DiagnosticsGate.disabled("unexpected-external-route-$unexpectedLanguageId")
                }
                builtInSelections += BuiltInSelection(
                    attempt = attempt,
                    fixture = fixture,
                    gate = gate,
                    candidate = syntheticSnapshot("${attempt.attempt}-${fixture.route}"),
                )
            }
        }

        val first = attempts.getValue("first")
        exactExternalGateG = DiagnosticsGate.enabled("external-exact-gate-G") { snapshot ->
            externalGateProviderSnapshots += snapshot
            events += "provider:external-success"
            emptyList()
        }
        successfulExternalSelection = selectForAttempt(
            first,
            syntheticPlan(
                operation = "rename",
                evidence = RefactoringEvidence.LANGUAGE_SERVER,
                affectedFiles = linkedSetOf(Path.of("src/Foo.ts")),
            ),
            languageId = "typescript",
        ) { languageId ->
            successfulResolverLanguages += languageId
            events += "resolver:external-success:$languageId"
            requireNotNull(exactExternalGateG)
        }

        val second = attempts.getValue("second")
        val cause = IllegalStateException("E_external exact cause")
        exactExternalFailure = ExternalResolverFixtureException(
            code = "external.adapter.lookup.fixture",
            message = "E_external exact resolver failure",
            cause = cause,
        )
        observedExternalFailure = runCatching {
            selectForAttempt(
                second,
                syntheticPlan(
                    operation = "java.moveAcrossMavenModules",
                    evidence = RefactoringEvidence.JDT_BINDING,
                    affectedFiles = linkedSetOf(Path.of("src/main/java/com/acme/Foo.java")),
                ),
                languageId = "Java",
            ) { languageId ->
                failingResolverLanguages += languageId
                events += "resolver:external-failure:$languageId"
                throw requireNotNull(exactExternalFailure)
            }
        }.exceptionOrNull()

        assertEquals(12, builtInSelections.size)
        assertWorkspaceUnchanged()
    }

    @Then(
        "every built-in route performs zero external adapter lookups and zero diagnostics-provider invocations during selection",
    )
    fun builtInSelectionIsLazyAndDoesNotResolveExternally() {
        assertTrue(attempts.values.all { it.probe.builtInResolverLanguages.isEmpty() })
        assertTrue(attempts.values.all { it.probe.invocations.isEmpty() })
        assertTrue(attempts.values.all { it.probe.externalProviderSnapshots.isEmpty() })
        assertTrue(externalGateProviderSnapshots.isEmpty())
        assertWorkspaceUnchanged()
    }

    @Then("selection performs none of this built-in provider work:")
    fun selectionPerformsNoBuiltInProviderWork(table: DataTable) {
        assertEquals(EXPECTED_DEFERRED_PROVIDER_WORK, table.asMaps())
        assertTrue(attempts.values.all { it.probe.invocations.isEmpty() })
        assertTrue(externalGateProviderSnapshots.isEmpty())
        assertTrue(events.none { it.startsWith("provider:") })
        assertWorkspaceUnchanged()
    }

    @Then(
        "each built-in gate uses the exact Java or Kotlin adapter argument supplied on its own call, without retaining a startup or previous adapter or creating a replacement",
    )
    fun eachBuiltInGateUsesItsOwnExactAdapter() {
        builtInSelections.asReversed().forEach { selection ->
            val diagnostics = selection.attempt.probe.invokeReturnedGate(selection.gate, selection.candidate)
            assertEquals(
                listOf(diagnosticCode(selection.attempt.attempt, selection.fixture.route)),
                diagnostics.map(Diagnostic::code),
            )
        }

        attempts.values.forEach { attempt ->
            val ownSelections = builtInSelections.filter { it.attempt === attempt }
            assertEquals(6, ownSelections.size)
            assertEquals(BUILT_IN_ROUTES, ownSelections.map { it.fixture.route }.toSet())
            assertEquals(6, attempt.probe.invocations.size)

            ownSelections.forEach { selection ->
                val invocation = attempt.probe.invocations.single { it.route == selection.fixture.route }
                assertSame(selection.candidate, invocation.snapshot)
                if (selection.fixture.route in JAVA_ROUTES) {
                    assertSame(attempt.javaAdapter, invocation.adapter, attempt.javaAdapterLabel)
                } else {
                    assertSame(attempt.kotlinAdapter, invocation.adapter, attempt.kotlinAdapterLabel)
                }
            }
        }

        val suppliedAdapters = attempts.values.flatMap { listOf(it.javaAdapter, it.kotlinAdapter) }
        val observedAdapters = attempts.values.flatMap { it.probe.invocations }.map(ProviderInvocation::adapter)
        assertEquals(12, observedAdapters.size)
        assertTrue(observedAdapters.all { observed -> suppliedAdapters.any { supplied -> supplied === observed } })
        assertWorkspaceUnchanged()
    }

    @Then(
        "each external selection performs exactly one synchronous adapter lookup during selection, with no lookup for built-in IDs and no lookup deferred into a provider",
    )
    fun externalLookupTimingAndCountAreExact() {
        assertEquals(listOf("typescript"), successfulResolverLanguages)
        assertEquals(listOf("Java"), failingResolverLanguages)
        assertTrue(attempts.values.all { it.probe.builtInResolverLanguages.isEmpty() })
        assertEquals(0, externalGateProviderSnapshots.size)
        assertWorkspaceUnchanged()
    }

    @Then("successful external selection returns the exact gate G unchanged while G's provider remains unexecuted")
    fun successfulExternalGateKeepsIdentityAndLaziness() {
        assertSame(requireNotNull(exactExternalGateG), requireNotNull(successfulExternalSelection))
        assertEquals("external-exact-gate-G", requireNotNull(successfulExternalSelection).id)
        assertTrue(externalGateProviderSnapshots.isEmpty())
    }

    @Then(
        "failed external selection propagates the same E_external object with its exact type, code, message, and cause, without catching, wrapping, or translating it",
    )
    fun failedExternalSelectionKeepsExactExceptionIdentity() {
        val expected = requireNotNull(exactExternalFailure)
        val actual = assertIs<ExternalResolverFixtureException>(requireNotNull(observedExternalFailure))
        assertSame(expected, actual)
        assertEquals("external.adapter.lookup.fixture", actual.code)
        assertEquals("E_external exact resolver failure", actual.message)
        assertSame(expected.cause, actual.cause)
        assertEquals("E_external exact cause", actual.cause?.message)
    }

    @Then("the selector performs none of this apply or surface-owned work:")
    fun selectorPerformsNoApplyOrSurfaceWork(table: DataTable) {
        assertEquals(EXPECTED_EXCLUDED_WORK, table.asMaps())
        assertWorkspaceUnchanged()
        assertTrue(externalGateProviderSnapshots.isEmpty())
        assertTrue(events.none { event -> EXCLUDED_EVENT_PREFIXES.any(event::startsWith) })
        assertEquals(12, attempts.values.sumOf { it.probe.invocations.size })
    }

    @Then("selector adoption is limited to these duplicate managed-apply call sites:")
    fun selectorAdoptionIsLimitedToManagedApplyCallSites(table: DataTable) {
        assertEquals(EXPECTED_ADOPTING_CALL_SITES, table.asMaps())
        assertEquals(
            setOf("daemon refactor.apply", "MCP apply_refactoring"),
            selectionSurfaces.toSet(),
        )
        assertEquals(14, selectionSurfaces.size)
    }

    @Then("these excluded surfaces preserve their existing behavior:")
    fun excludedSurfacesRemainOutsideThisFixture(table: DataTable) {
        assertEquals(EXPECTED_EXCLUDED_SURFACES, table.asMaps())
        val excludedSurfaceNames = table.asMaps().map { it.getValue("excluded surface") }.toSet()
        assertTrue(selectionSurfaces.none(excludedSurfaceNames::contains))
        assertWorkspaceUnchanged()
    }

    private fun selectForAttempt(
        attempt: AttemptFixture,
        plan: PatchPlan,
        languageId: String,
        externalGateResolver: (String) -> DiagnosticsGate,
    ): DiagnosticsGate {
        selectionSurfaces += attempt.surface
        events += "select:start:${attempt.attempt}:$languageId"
        return try {
            val selected: DiagnosticsGate = ManagedApplyDiagnosticsGateSelector.select(
                plan = plan,
                languageId = languageId,
                javaAdapter = attempt.javaAdapter,
                kotlinAdapter = attempt.kotlinAdapter,
                externalGateResolver = externalGateResolver,
                providerFunctions = attempt.probe.providerFunctions,
            )
            events += "select:return:${attempt.attempt}:$languageId"
            selected
        } catch (failure: Throwable) {
            events += "select:throw:${attempt.attempt}:$languageId"
            throw failure
        }
    }

    private fun syntheticPlan(
        operation: String,
        evidence: RefactoringEvidence,
        affectedFiles: Set<Path>,
    ): PatchPlan = PatchPlan(
        id = PlanId("plan-managed-diagnostics-${++planSequence}"),
        operation = operation,
        snapshotHash = "synthetic-snapshot-hash-$planSequence",
        confidence = 1.0,
        summary = "Synthetic managed diagnostics selector plan $planSequence",
        affectedFiles = affectedFiles,
        workspaceEdit = WorkspaceEdit(emptyList()),
        evidence = evidence,
    )

    private fun syntheticSnapshot(label: String): ProjectSnapshot = ProjectSnapshot(
        workspace = Workspace(requireWorkspaceRoot()),
        modules = emptyList(),
        files = emptyList(),
        auxiliaryFiles = emptyList(),
    ).also {
        events += "snapshot:synthetic:$label"
    }

    private fun establishWorkspaceBaseline() {
        requireWorkspaceRoot()
        workspaceBaseline = observeWorkspace()
    }

    private fun requireWorkspaceRoot(): Path {
        workspaceRoot?.let { return it }
        val root = Files.createTempDirectory("managed-apply-diagnostics-selector")
            .toAbsolutePath().normalize()
        temporaryRoots.add(root)
        val sentinel = root.resolve("fixture/sentinel.bin")
        Files.createDirectories(requireNotNull(sentinel.parent))
        Files.write(sentinel, byteArrayOf(0x13, 0x37, 0x00, 0x7f))
        workspaceRoot = root
        workspaceBaseline = observeWorkspace(root)
        return root
    }

    private fun assertWorkspaceUnchanged() {
        val baseline = workspaceBaseline ?: return
        assertEquals(baseline, observeWorkspace())
    }

    private fun observeWorkspace(root: Path = requireWorkspaceRoot()): Map<String, List<Byte>> =
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .toList()
                .associate { path ->
                    root.relativize(path).invariantSeparatorsPathString to Files.readAllBytes(path).toList()
                }
        }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    private companion object {
        const val ROUTE_JAVA_MAVEN_OWNERSHIP = "java-maven-ownership-provider"
        const val ROUTE_JAVA_JDT = "java-jdt-provider"
        const val ROUTE_KOTLIN_JVM_MOVE = "kotlin-jvm-move-provider"
        const val ROUTE_JAVA_KOTLIN_RENAME = "java-kotlin-public-type-rename-provider"
        const val ROUTE_KOTLIN_JAVA_RENAME = "kotlin-java-public-type-rename-provider"
        const val ROUTE_KOTLIN_K2 = "kotlin-k2-provider"
        const val ROUTE_EXTERNAL = "external-resolver-gate-provider"

        val JAVA_ROUTES = setOf(ROUTE_JAVA_MAVEN_OWNERSHIP, ROUTE_JAVA_JDT)
        val BUILT_IN_ROUTES = setOf(
            ROUTE_JAVA_MAVEN_OWNERSHIP,
            ROUTE_JAVA_JDT,
            ROUTE_KOTLIN_JVM_MOVE,
            ROUTE_JAVA_KOTLIN_RENAME,
            ROUTE_KOTLIN_JAVA_RENAME,
            ROUTE_KOTLIN_K2,
        )
        val ROUTE_BY_ROW = mapOf(
            "1" to ROUTE_JAVA_MAVEN_OWNERSHIP,
            "2" to ROUTE_JAVA_JDT,
            "3" to ROUTE_KOTLIN_JVM_MOVE,
            "4" to ROUTE_JAVA_KOTLIN_RENAME,
            "5" to ROUTE_KOTLIN_JAVA_RENAME,
            "6" to ROUTE_KOTLIN_K2,
            "7" to ROUTE_EXTERNAL,
        )

        fun diagnosticCode(probeName: String, route: String): String = "probe.$probeName.$route"

        val EXPECTED_ROUTE_TABLE = listOf(
            mapOf(
                "order" to "1",
                "exact predicate" to "languageId == \"java\" and operation == \"java.moveAcrossMavenModules\"",
                "gate ID rule" to "java-maven-ownership",
                "lazy provider or result" to "JavaMoveAcrossMavenModulesPlanner(currentJavaAdapter)::diagnostics",
            ),
            mapOf(
                "order" to "2",
                "exact predicate" to "languageId == \"java\" after row 1 fails",
                "gate ID rule" to "java-jdt",
                "lazy provider or result" to "currentJavaAdapter::diagnostics",
            ),
            mapOf(
                "order" to "3",
                "exact predicate" to "languageId == \"kotlin\" and javaAffected and operation == \"moveDeclaration\"",
                "gate ID rule" to "kotlin-k2-java-jdt",
                "lazy provider or result" to "KotlinJvmMoveDeclarationPlanner(currentKotlinAdapter).diagnostics(candidate)",
            ),
            mapOf(
                "order" to "4",
                "exact predicate" to "languageId == \"kotlin\" and javaAffected and operation != \"moveDeclaration\" and evidence == \"JDT_BINDING\"",
                "gate ID rule" to "kotlin-k2-java-jdt",
                "lazy provider or result" to "JavaKotlinPublicTypeRenamePlanner(currentKotlinAdapter).diagnostics(candidate)",
            ),
            mapOf(
                "order" to "5",
                "exact predicate" to "languageId == \"kotlin\" and javaAffected after rows 3 and 4 fail",
                "gate ID rule" to "kotlin-k2-java-jdt",
                "lazy provider or result" to "KotlinJavaPublicTypeRenamePlanner(currentKotlinAdapter).diagnostics(candidate)",
            ),
            mapOf(
                "order" to "6",
                "exact predicate" to "languageId == \"kotlin\" and not javaAffected",
                "gate ID rule" to "kotlin-k2",
                "lazy provider or result" to "currentKotlinAdapter.compilerDiagnostics(candidate).diagnostics",
            ),
            mapOf(
                "order" to "7",
                "exact predicate" to "every other exact languageId",
                "gate ID rule" to "resolver gate ID unchanged",
                "lazy provider or result" to "externalGateResolver(languageId) return value unchanged",
            ),
        )

        val EXPECTED_PROVIDER_DESCRIPTION_BY_ROW = mapOf(
            "1" to "lazy JavaMoveAcrossMavenModulesPlanner provider using the current Java adapter",
            "2" to "lazy diagnostics provider of the current Java adapter",
            "3" to "lazy KotlinJvmMoveDeclarationPlanner provider using the current Kotlin adapter",
            "4" to "lazy JavaKotlinPublicTypeRenamePlanner provider using the current Kotlin adapter",
            "5" to "lazy KotlinJavaPublicTypeRenamePlanner provider using the current Kotlin adapter",
            "6" to "lazy compilerDiagnostics result provider of the current Kotlin adapter",
            "7" to "the exact resolver-returned gate object unchanged",
        )

        val EXPECTED_ATTEMPT_TABLE = listOf(
            mapOf(
                "attempt" to "first",
                "surface" to "daemon refactor.apply",
                "current Java adapter" to "java-current-1",
                "current Kotlin adapter" to "kotlin-current-1",
            ),
            mapOf(
                "attempt" to "second",
                "surface" to "MCP apply_refactoring",
                "current Java adapter" to "java-current-2",
                "current Kotlin adapter" to "kotlin-current-2",
            ),
        )

        val EXPECTED_EXTERNAL_OUTCOME_TABLE = listOf(
            mapOf(
                "selection" to "external success",
                "exact language ID" to "typescript",
                "surface resolver behavior" to "return exact lazy gate G",
                "resolver calls during selection" to "exactly 1",
            ),
            mapOf(
                "selection" to "external failure",
                "exact language ID" to "Java",
                "surface resolver behavior" to "throw exact exception E_external",
                "resolver calls during selection" to "exactly 1",
            ),
        )

        val EXPECTED_DEFERRED_PROVIDER_WORK = listOf(
            mapOf("deferred provider work" to "Java adapter diagnostics"),
            mapOf(
                "deferred provider work" to
                    "Maven ownership materialization, offline scanning, rebuilding, or diagnostics",
            ),
            mapOf("deferred provider work" to "Kotlin compiler or K2 diagnostics"),
            mapOf(
                "deferred provider work" to "mixed-JVM JDT analysis or ephemeral Java compilation",
            ),
        )

        val EXPECTED_EXCLUDED_WORK = listOf(
            mapOf("excluded work" to "invoke PatchEngine or acquire the workspace lock"),
            mapOf(
                "excluded work" to "run baseline, staged, post-image, or restored-baseline diagnostics",
            ),
            mapOf(
                "excluded work" to
                    "perform recovery, regression comparison, WAL, mutation, automatic rollback, or explicit rollback",
            ),
            mapOf(
                "excluded work" to "validate or clean pending plans, Kotlin leases, or index generations",
            ),
            mapOf(
                "excluded work" to "start, stop, restart, reset, or close adapters, toolchains, or sessions",
            ),
            mapOf(
                "excluded work" to
                    "render responses or errors, refresh state, bound diagnostics, or run daemon post-success work",
            ),
        )

        val EXPECTED_ADOPTING_CALL_SITES = listOf(
            mapOf("surface" to "daemon", "adopting call site" to "DaemonSession.refactorApply"),
            mapOf("surface" to "MCP", "adopting call site" to "McpSession.toolApplyRefactoring"),
        )

        val EXPECTED_EXCLUDED_SURFACES = listOf(
            mapOf(
                "excluded surface" to "CLI",
                "behavior preserved" to
                    "Java-only Maven-or-java-jdt selection and its fresh-adapter behavior",
            ),
            mapOf(
                "excluded surface" to "managed LSP",
                "behavior preserved" to "java-jdt selection, including Maven ownership moves",
            ),
            mapOf(
                "excluded surface" to "recipes",
                "behavior preserved" to
                    "existing java-jdt or injected diagnostics path and module boundary",
            ),
            mapOf(
                "excluded surface" to "testkit and direct library",
                "behavior preserved" to
                    "caller-owned diagnostics-gate selection and direct-apply behavior",
            ),
        )

        val BUILT_IN_ROUTE_FIXTURES = listOf(
            BuiltInRouteFixture(
                ROUTE_JAVA_MAVEN_OWNERSHIP,
                "java",
                "java.moveAcrossMavenModules",
                RefactoringEvidence.STRUCTURAL,
                listOf("module-a/src/main/java/com/acme/Foo.java"),
            ),
            BuiltInRouteFixture(
                ROUTE_JAVA_JDT,
                "java",
                "renameClass",
                RefactoringEvidence.JDT_BINDING,
                listOf("src/main/java/com/acme/Foo.java"),
            ),
            BuiltInRouteFixture(
                ROUTE_KOTLIN_JVM_MOVE,
                "kotlin",
                "moveDeclaration",
                RefactoringEvidence.JDT_BINDING,
                listOf("src/main/kotlin/Foo.kt", "src/main/java/com/acme/FooUser.java"),
            ),
            BuiltInRouteFixture(
                ROUTE_JAVA_KOTLIN_RENAME,
                "kotlin",
                "renameClass",
                RefactoringEvidence.JDT_BINDING,
                listOf("src/main/java/com/acme/FooUser.java", "src/main/kotlin/Foo.kt"),
            ),
            BuiltInRouteFixture(
                ROUTE_KOTLIN_JAVA_RENAME,
                "kotlin",
                "deliberately-unusual",
                RefactoringEvidence.NATIVE_AST,
                listOf("src/main/kotlin/Foo.kt", "src/main/java/com/acme/FooUser.java"),
            ),
            BuiltInRouteFixture(
                ROUTE_KOTLIN_K2,
                "kotlin",
                "moveDeclaration",
                RefactoringEvidence.JDT_BINDING,
                listOf("src/main/kotlin/Foo.kt"),
            ),
        )

        val EXCLUDED_EVENT_PREFIXES = listOf(
            "patch-engine:",
            "workspace-lock:",
            "wal:",
            "mutation:",
            "rollback:",
            "recovery:",
            "lifecycle:",
            "render:",
            "refresh:",
            "pending-plan:",
        )
    }
}
