package org.refactorkit.c

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * One preview-bound, non-executing CMake/compilation-database apply cell.
 * No command from a compilation database is ever launched. Only the configured,
 * hash-checked Clang runs `-fsyntax-only` on old/staged/post-apply overlays.
 * All other contexts retain the facade's fail-closed diagnostics gate.
 */
internal class CManagedCMakeContext(
    private val toolchain: ClangSemanticToolchain,
    private val diagnostics: CCompilerDiagnostics,
) {
    /** Evaluate S0 and C1 before returning an approval-eligible preview. */
    fun preview(
        snapshot: ProjectSnapshot,
        plan: PatchPlan,
        componentDir: Path? = null,
        newDir: Path? = null,
    ): PatchPlan {
        val runtimeIdentity = currentRuntimeIdentity()
            ?: return refusedPreview(
                plan,
                IllegalStateException("c.nativeRuntimeIdentityUnavailable: complete current runtime identity is required"),
                emptyList(),
                baseline = true,
                refusalCode = "c.nativeRuntimeIdentityUnavailable",
            )
        val captured = try {
            capture(snapshot, plan, componentDir, newDir)
        } catch (failure: Exception) {
            // A structural preview remains inspectable, but must not present absent
            // or unsupported compiler evidence as an evaluated clean configuration.
            return plan.copy(warnings = plan.warnings +
                "c.previewDiagnosticsUnavailable: ${failure.message ?: "captured context unavailable"}")
        }
        val before = try {
            captured.evaluate(snapshot)
        } catch (failure: Exception) {
            return refusedPreview(plan, failure, emptyList(), baseline = true)
        }
        val staged = try {
            captured.evaluate(WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit))
        } catch (failure: Exception) {
            return refusedPreview(plan, failure, before, baseline = false)
        }
        val lease = OperationAuthorityLease(
            kind = "c.cmake-single-tu",
            operation = plan.operation,
            snapshotHash = snapshot.hash,
            evidenceHash = hash("$DIAGNOSTICS_SCOPE|$OWNERSHIP_SCOPE|$FLAG_POLICY|$CONSUMER_PROBE_POLICY|${captured.ownershipHash}|${captured.contexts.size}|${snapshot.hash}|${captured.stagedHash}|${captured.databaseHash}|${captured.clangHash}|$runtimeIdentity".toByteArray()),
            workspaceEditSha256 = WorkspaceEditIdentity.sha256(plan.workspaceEdit),
            attributes = mapOf(
                "c.compilationDatabaseSha256" to captured.databaseHash,
                "c.compilerSha256" to captured.clangHash,
                "c.nativeRuntimeIdentity" to runtimeIdentity,
                "c.stagedSnapshotSha256" to captured.stagedHash,
                "c.diagnosticsScope" to DIAGNOSTICS_SCOPE,
                "c.translationUnitCount" to captured.contexts.size.toString(),
                "c.targetOwnershipScope" to OWNERSHIP_SCOPE,
                "c.targetOwnershipSha256" to captured.ownershipHash,
                "c.componentDir" to (captured.componentDir?.toString() ?: ""),
                "c.newDir" to (captured.newDir?.toString() ?: ""),
                "c.cmakeFlagPolicy" to FLAG_POLICY,
                "c.consumerProbePolicy" to CONSUMER_PROBE_POLICY,
                "c.consumerDefinePolicy" to CONSUMER_DEFINE_POLICY,
                "c.consumerDefinePolicySha256" to captured.definePolicyHash,
                "c.consumerPastePolicy" to CONSUMER_PASTE_POLICY,
                "c.consumerPastePolicySha256" to captured.pastePolicyHash,
                "c.consumerTrigraphPolicy" to CONSUMER_TRIGRAPH_POLICY,
                "c.consumerTrigraphPolicySha256" to captured.trigraphPolicyHash,
                "c.consumerEmbedPolicy" to CONSUMER_EMBED_POLICY,
                "c.consumerEmbedPolicySha256" to captured.embedPolicyHash,
                "c.consumerNewlinePolicy" to CONSUMER_NEWLINE_POLICY,
                "c.consumerNewlinePolicySha256" to captured.newlinePolicyHash,
                "c.movedPathMacroPolicy" to MOVED_PATH_MACRO_POLICY,
                "c.movedPathMacroPolicySha256" to captured.pathMacroPolicyHash,
                "c.consumerPathDefinePolicy" to CONSUMER_PATH_DEFINE_POLICY,
                "c.consumerPathDefinePolicySha256" to captured.pathDefinePolicyHash,
                "c.movedDirectivePolicy" to MOVED_DIRECTIVE_POLICY,
                "c.movedDirectivePolicySha256" to captured.directivePolicyHash,
                "c.movedDirectiveTokenPolicy" to MOVED_DIRECTIVE_TOKEN_POLICY,
                "c.movedDirectiveTokenPolicySha256" to captured.directiveTokenPolicyHash,
            ),
        )
        return plan.copy(
            diagnosticsBefore = before,
            diagnosticsAfterPreview = staged,
            authorityLease = lease,
            warnings = plan.warnings + "c.previewDiagnosticsVerified: Clang 22.1.8 evaluated all ${captured.contexts.size} captured CMake TUs in baseline and staged overlays before approval",
        )
    }

    fun gate(snapshot: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate {
        val runtimeIdentity = currentRuntimeIdentity()
            ?: return DiagnosticsGate.enabled(GATE_ID) {
                error("c.nativeRuntimeIdentityUnavailable: complete current runtime identity is required")
            }
        val lease = plan.authorityLease
        if (lease == null || lease.kind != "c.cmake-single-tu" || lease.snapshotHash != snapshot.hash) {
            val previewFailure = if (lease == null) {
                plan.warnings.lastOrNull { it.startsWith("c.previewDiagnosticsUnavailable: ") }
                    ?.removePrefix("c.previewDiagnosticsUnavailable: ")
            } else null
            val reason = previewFailure
                ?: "c.compilationContextUnavailable: preview did not retain the exact captured CMake diagnostics lease"
            return DiagnosticsGate.enabled(GATE_ID) {
                error(reason)
            }
        }
        if (lease.attributes["c.nativeRuntimeIdentity"] != runtimeIdentity) {
            return DiagnosticsGate.enabled(GATE_ID) {
                error("c.nativeRuntimeIdentityChanged: current runtime identity differs from the approved preview lease")
            }
        }
        val captured = try {
            capture(
                snapshot,
                plan,
                lease.attributes["c.componentDir"]?.takeIf { it.isNotEmpty() }?.let(Path::of),
                lease.attributes["c.newDir"]?.takeIf { it.isNotEmpty() }?.let(Path::of),
            )
        } catch (failure: Exception) {
            val codeAndReason = failure.message?.takeIf { it.startsWith("c.") }
                ?: "c.compilationContextUnavailable: ${failure.message ?: "CMake context cannot be captured"}"
            return DiagnosticsGate.enabled(GATE_ID) { error(codeAndReason) }
        }
        if (lease.workspaceEditSha256 != WorkspaceEditIdentity.sha256(plan.workspaceEdit) ||
            lease.attributes["c.compilationDatabaseSha256"] != captured.databaseHash ||
            lease.attributes["c.compilerSha256"] != captured.clangHash ||
            lease.attributes["c.stagedSnapshotSha256"] != captured.stagedHash ||
            lease.attributes["c.diagnosticsScope"] != DIAGNOSTICS_SCOPE ||
            lease.attributes["c.translationUnitCount"] != captured.contexts.size.toString() ||
            lease.attributes["c.targetOwnershipScope"] != OWNERSHIP_SCOPE ||
            lease.attributes["c.targetOwnershipSha256"] != captured.ownershipHash ||
            lease.attributes["c.cmakeFlagPolicy"] != FLAG_POLICY ||
            lease.attributes["c.consumerProbePolicy"] != CONSUMER_PROBE_POLICY ||
            lease.attributes["c.consumerDefinePolicy"] != CONSUMER_DEFINE_POLICY ||
            lease.attributes["c.consumerDefinePolicySha256"] != captured.definePolicyHash ||
            lease.attributes["c.consumerPastePolicy"] != CONSUMER_PASTE_POLICY ||
            lease.attributes["c.consumerPastePolicySha256"] != captured.pastePolicyHash ||
            lease.attributes["c.consumerTrigraphPolicy"] != CONSUMER_TRIGRAPH_POLICY ||
            lease.attributes["c.consumerTrigraphPolicySha256"] != captured.trigraphPolicyHash ||
            lease.attributes["c.consumerEmbedPolicy"] != CONSUMER_EMBED_POLICY ||
            lease.attributes["c.consumerEmbedPolicySha256"] != captured.embedPolicyHash ||
            lease.attributes["c.consumerNewlinePolicy"] != CONSUMER_NEWLINE_POLICY ||
            lease.attributes["c.consumerNewlinePolicySha256"] != captured.newlinePolicyHash ||
            lease.attributes["c.movedPathMacroPolicy"] != MOVED_PATH_MACRO_POLICY ||
            lease.attributes["c.movedPathMacroPolicySha256"] != captured.pathMacroPolicyHash ||
            lease.attributes["c.consumerPathDefinePolicy"] != CONSUMER_PATH_DEFINE_POLICY ||
            lease.attributes["c.consumerPathDefinePolicySha256"] != captured.pathDefinePolicyHash ||
            lease.attributes["c.movedDirectivePolicy"] != MOVED_DIRECTIVE_POLICY ||
            lease.attributes["c.movedDirectivePolicySha256"] != captured.directivePolicyHash ||
            lease.attributes["c.movedDirectiveTokenPolicy"] != MOVED_DIRECTIVE_TOKEN_POLICY ||
            lease.attributes["c.movedDirectiveTokenPolicySha256"] != captured.directiveTokenPolicyHash) {
            return DiagnosticsGate.enabled(GATE_ID) {
                error("c.compilationDatabaseChanged: preview diagnostics and retained apply context do not share one captured identity")
            }
        }
        return DiagnosticsGate.enabled(GATE_ID) { candidate -> captured.evaluate(candidate) }
    }

    private fun refusedPreview(
        plan: PatchPlan,
        failure: Exception,
        before: List<Diagnostic>,
        baseline: Boolean,
        refusalCode: String = "c.previewDiagnosticsUnavailable",
    ): PatchPlan {
        val diagnostic = Diagnostic(
            message = failure.message ?: "Captured Clang diagnostics are unavailable",
            severity = Diagnostic.Severity.ERROR,
            code = refusalCode,
        )
        return plan.copy(
            status = PatchStatus.REFUSED,
            confidence = 0.0,
            requiresUserApproval = false,
            summary = "CMake move preview refused: ${diagnostic.message}",
            affectedFiles = emptySet(),
            workspaceEdit = WorkspaceEdit(),
            authorityLease = null,
            diagnosticsBefore = if (baseline) listOf(diagnostic) else before,
            diagnosticsAfterPreview = if (baseline) emptyList() else listOf(diagnostic),
            warnings = plan.warnings + diagnostic.message,
            refusalCode = diagnostic.code,
        )
    }

    private fun currentRuntimeIdentity(): String? = toolchain.provenance.nativeRuntimeIdentity?.takeIf { identity ->
        (toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity == identity
    }

    private fun capture(
        snapshot: ProjectSnapshot,
        plan: PatchPlan,
        requestedComponentDir: Path? = null,
        requestedNewDir: Path? = null,
    ): Captured {
        requireContext(plan.operation in setOf("moveSource", "relocateComponent") && plan.status == PatchStatus.PREVIEW &&
            plan.snapshotHash == snapshot.hash && plan.requiresUserApproval,
            "c.compilationContextUnavailable: only approved, snapshot-bound C relocations are admitted")
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        requireContext(snapshot.files.isNotEmpty() && snapshot.files.all { file ->
            file.languageId == "c" && (file.path.toString().endsWith(".c") || file.path.toString().endsWith(".h"))
        } && snapshot.files.any { it.path.toString().endsWith(".c") },
            "c.compilationContextUnavailable: snapshot must contain only C translation units and headers")
        val moved = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>()
        val manifestEdits = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
        requireContext(moved.isNotEmpty() && moved.map { it.path.normalize() }.distinct().size == moved.size &&
            moved.map { it.newPath.normalize() }.distinct().size == moved.size &&
            manifestEdits.count { it.path == Path.of("CMakeLists.txt") } == 1,
            "c.compilationContextUnavailable: unique moved C paths and one literal root target edit are required")
        val movedPaths = moved.associate { it.path.normalize() to it.newPath.normalize() }
        requireContext(movedPaths.all { (old, next) -> snapshot.files.any { it.path.normalize() == old } &&
            next != old && !old.isAbsolute && !old.startsWith("..") && !next.isAbsolute && !next.startsWith("..") &&
            next.fileName.toString().substringAfterLast('.', "") in setOf("c", "h") &&
            old.fileName.toString().substringAfterLast('.', "") == next.fileName.toString().substringAfterLast('.', "") },
            "c.compilationContextUnavailable: moved C sources/headers must be captured, workspace-relative and preserve file kind")
        val (componentDir, newDir) = if (plan.operation == "relocateComponent") {
            val oldDir = requestedComponentDir?.normalize()
            val destinationDir = requestedNewDir?.normalize()
            requireContext(oldDir != null && destinationDir != null && oldDir != destinationDir &&
                !oldDir.isAbsolute && !oldDir.startsWith("..") && !destinationDir.isAbsolute && !destinationDir.startsWith("..") &&
                root.resolve(oldDir).normalize().startsWith(root) && root.resolve(destinationDir).normalize().startsWith(root) &&
                movedPaths.all { (old, next) -> old.startsWith(oldDir) && next == destinationDir.resolve(oldDir.relativize(old)).normalize() } &&
                movedPaths.keys == snapshot.files.filter { it.languageId in setOf("c", "cpp", "objective-c") && it.path.normalize().startsWith(oldDir) }
                    .map { it.path.normalize() }.toSet(),
                "c.compilationContextUnavailable: component relocation paths do not cover exactly the captured component")
            oldDir!! to destinationDir!!
        } else {
            requireContext(requestedComponentDir == null && requestedNewDir == null && movedPaths.size == 1,
                "c.compilationContextUnavailable: source move must contain one path and no component attributes")
            null to null
        }
        val expectedMove = if (plan.operation == "relocateComponent") {
            CRelocateComponentPlanner().preview(snapshot, requireNotNull(componentDir), requireNotNull(newDir))
        } else {
            val (old, next) = movedPaths.entries.single().let { it.key to it.value }
            CMovePlanner().preview(snapshot, old, next)
        }
        requireContext(expectedMove.status == PatchStatus.PREVIEW && expectedMove.workspaceEdit == plan.workspaceEdit,
            "c.compilationContextUnavailable: workspace edit differs from the complete literal relocation model")
        val bound = if (plan.operation == "relocateComponent") {
            CMakeLiteralBindings.relocate(snapshot, movedPaths, requireNotNull(componentDir), requireNotNull(newDir))
        } else {
            val (old, next) = movedPaths.entries.single().let { it.key to it.value }
            CMakeLiteralBindings.move(snapshot, old, next)
        }
        requireContext(bound is CMakeBindingDecision.Bound &&
            bound.edits == manifestEdits.single { it.path == Path.of("CMakeLists.txt") }.textEdits,
            "c.compilationContextUnavailable: no unique literal C-only executable/STATIC library target binding")
        val beforeBinding = bound as CMakeBindingDecision.Bound
        requireContext(movedPaths.keys.all { old ->
            beforeBinding.sourceOwners[old].orEmpty().singleOrNull()?.let { owner ->
                owner.targetKind == "add_executable" || (owner.targetKind == "add_library" && owner.libraryType == "STATIC")
            } == true
        }, "c.compilationContextUnavailable: every moved source/header must have one qualified literal target owner")
        val movedTuOwners = movedPaths.keys.filter { it.fileName.toString().endsWith(".c") }
            .map { beforeBinding.sourceOwners.getValue(it).single() }.toSet()
        requireContext(
            if (plan.operation == "relocateComponent") movedTuOwners.isNotEmpty() && movedTuOwners.size == 1
            else movedTuOwners.size <= 1,
            "c.compilationContextUnavailable: moved translation units must share one literal executable/STATIC target owner",
        )
        requireContext(snapshot.auxiliaryFiles.singleOrNull { it.path == Path.of("CMakeLists.txt") } != null,
            "c.compilationContextUnavailable: root CMake manifest is not auxiliary snapshot evidence")
        movedPaths.forEach { (old, _) ->
            val movedFile = snapshot.files.single { it.path.normalize() == old }
            requireContext(!hasPathSensitiveInput(movedFile.content, snapshot, old),
                "c.compilationContextUnavailable: moved source/header '$old' has include or path-sensitive behavior")
        }
        movedPaths.keys.filter { it.fileName.toString().endsWith(".c") }.forEach { old ->
            val movedAbsolute = root.resolve(old).normalize()
            val hasInboundSourceInclude = snapshot.files.filter { it.path.normalize() !in movedPaths.keys }.any { other ->
                CIncludeDirectiveParser().parse(other.content).any { directive ->
                    if (directive.kind == CIncludeKind.MACRO) false
                    else {
                        val includingDirectory = root.resolve(other.path.parent ?: Path.of("")).normalize()
                        val target = if (directive.kind == CIncludeKind.QUOTED) {
                            includingDirectory.resolve(directive.target).normalize()
                        } else root.resolve(directive.target).normalize()
                        target == movedAbsolute
                    }
                }
            }
            requireContext(!hasInboundSourceInclude,
                "c.compilationContextUnavailable: another captured file includes moved C source '$old'")
        }
        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        requireContext(staged.files.size == snapshot.files.size &&
            movedPaths.all { (old, next) -> staged.files.singleOrNull { it.path.normalize() == next } != null &&
                staged.files.none { it.path.normalize() == old } },
            "c.compilationContextUnavailable: staged relocation does not preserve the exact moved file set")
        requireContext(staged.auxiliaryFiles.size == snapshot.auxiliaryFiles.size,
            "c.compilationContextUnavailable: unexpected auxiliary edits")
        requireContext(!CMakeManifestCapture.hasUncapturedCMakeAuthority(snapshot) &&
            movedPaths.keys.none { nestedManifestExists(root, it) } && movedPaths.values.none { nestedManifestExists(root, it) } &&
            componentDir?.let { !nestedManifestExists(root, it.resolve("_managed_cmake_probe.c")) } != false &&
            newDir?.let { !nestedManifestExists(root, it.resolve("_managed_cmake_probe.c")) } != false,
            "c.compilationContextUnavailable: nested CMake manifest is unmodeled")
        val databasePath = root.resolve("compile_commands.json")
        requireContext(Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(root.resolve("build/compile_commands.json"), LinkOption.NOFOLLOW_LINKS),
            "c.compilationContextUnavailable: unique regular root compilation database is required")
        val bytes = Files.readAllBytes(databasePath)
        requireContext(bytes.size in 1..MAX_DATABASE_BYTES,
            "c.compilationContextUnavailable: compilation database exceeds the bounded limit")
        val discovery = CCompilationDatabaseParser(
            CCompilationDatabasePolicy(requireArgumentsArray = true),
        ).parse(root, bytes)
        val database = (discovery as? CCompilationDatabaseDiscovery.Available)?.database
        val translationUnits = snapshot.files.filter { it.path.fileName.toString().endsWith(".c") }
        requireContext(database != null && database.units.isNotEmpty() &&
            translationUnits.all { f -> database.units.count { it.file == root.resolve(f.path.normalize()) } == 1 },
            "c.compilationCommandUnsupported: every captured C TU must have exactly one database entry")
        val units = database!!.units
        requireContext(units.size <= MAX_MANAGED_TUS,
            "c.compilationContextUnavailable: project exceeds managed diagnostics TU limit $MAX_MANAGED_TUS")
        val translationUnitPaths = translationUnits.map { root.resolve(it.path.normalize()) }.toSet()
        requireContext(units.map { it.file }.toSet() == translationUnitPaths,
            "c.compilationCommandUnsupported: database entries must exactly cover captured C translation units")
        requireContext(units.all { it.directory == root && it.output == null },
            "c.compilationCommandUnsupported: compilation directory, source or output is not literal")
        requireContext(units.all { it.target == null && it.sysroot == null && it.includeDirectories.isEmpty() },
            "c.compilationCommandUnsupported: target, sysroot and include flags are not admitted")
        val raw = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonArray
        val entries = raw?.filterIsInstance<JsonObject>().orEmpty()
        units.forEach { projectUnit ->
            val entry = entries.singleOrNull { obj ->
                (obj["file"] as? JsonPrimitive)?.contentOrNull?.let { f ->
                    root.resolve(f).normalize() == projectUnit.file
                } ?: false
            }
            val arguments = (entry?.get("arguments")) as? JsonArray
            requireContext(arguments != null && arguments.size == projectUnit.arguments.size &&
                arguments.all { (it as? JsonPrimitive)?.isString == true },
                "c.compilationCommandUnsupported: arguments must be complete literal strings")
        }
        val clangPath = toolchain.clangExecutable.toAbsolutePath().normalize()
        requireContext(toolchain.provenance.clangVersion == "22.1.8" &&
            Files.isRegularFile(clangPath, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(clangPath),
            "c.compilationContextUnavailable: reference Clang 22.1.8 executable is unavailable")
        val compilerIdentity = toolchain.provenance.evidence.singleOrNull { it.role == "clang-executable" }
        requireContext(compilerIdentity != null && compilerIdentity.path.toAbsolutePath().normalize() == clangPath &&
            compilerIdentity.size == Files.size(clangPath) && compilerIdentity.sha256 == hashFile(clangPath),
            "c.compilationContextUnavailable: hash-bound reference compiler identity is unavailable")
        val contexts = units.sortedBy { root.relativize(it.file).toString() }.associate { unit ->
            val source = root.relativize(unit.file).normalize()
            source to sanitize(unit.arguments, source, root, clangPath)
        }
        val beforeOwners = literalOwnership(snapshot, beforeBinding)
        val inverseMovedPaths = movedPaths.entries.associate { (old, next) -> next to old }
        val stagedBinding = if (plan.operation == "relocateComponent") {
            CMakeLiteralBindings.relocate(staged, inverseMovedPaths, requireNotNull(newDir), requireNotNull(componentDir))
        } else {
            val (old, next) = movedPaths.entries.single().let { it.key to it.value }
            CMakeLiteralBindings.move(staged, next, old)
        }
        requireContext(stagedBinding is CMakeBindingDecision.Bound,
            "c.cmakeTargetOwnershipUnavailable: staged literal target ownership cannot be captured")
        val stagedOwners = literalOwnership(staged, stagedBinding as CMakeBindingDecision.Bound)
        val movedOwnersBefore = movedPaths.keys.associateWith { old ->
            beforeBinding.sourceOwners[old].orEmpty().singleOrNull()
                ?: error("c.cmakeTargetOwnershipUnavailable: moved path '$old' has no unique literal target owner")
        }
        val movedOwnersAfter = movedPaths.values.associateWith { next ->
            stagedBinding.sourceOwners[next].orEmpty().singleOrNull()
                ?: error("c.cmakeTargetOwnershipUnavailable: staged path '$next' has no unique literal target owner")
        }
        requireContext(movedPaths.all { (old, next) -> movedOwnersBefore[old] == movedOwnersAfter[next] },
            "c.cmakeTargetOwnershipUnavailable: relocation changed a moved path's literal target owner")
        // Clean syntax with an omitted -I does not consume the manifest's flag origin.
        // Retain the no-include command policy for every owner in both models.
        requireContext(bound.includeRoots.isEmpty() && stagedBinding.includeRoots.isEmpty(),
            "c.cmakeFlagsUnconsumed: literal target include roots are not admitted by no-include command policy")
        requireContext(stagedOwners == beforeOwners.mapKeys { (source, _) -> movedPaths[source] ?: source },
            "c.cmakeTargetOwnershipUnavailable: staged target ownership changed beyond the moved path")
        fun movedOwnerModel(paths: Map<Path, CMakeLiteralTargetOwner>): JsonArray =
            JsonArray(paths.entries.sortedBy { it.key.toString() }.map { (path, owner) ->
                JsonArray(listOf(JsonPrimitive(path.toString()), JsonPrimitive(owner.target),
                    JsonPrimitive(owner.targetKind), owner.libraryType?.let(::JsonPrimitive) ?: JsonNull))
            })
        val ownershipHash = hash(JsonArray(listOf(ownershipModel(beforeOwners), ownershipModel(stagedOwners),
            movedOwnerModel(movedOwnersBefore), movedOwnerModel(movedOwnersAfter)))
            .toString().toByteArray(Charsets.UTF_8))
        return Captured(root, snapshot.hash, staged.hash, movedPaths, componentDir, newDir, databasePath,
            hash(bytes), clangPath, compilerIdentity!!.sha256, contexts, ownershipHash)
    }

    private fun literalOwnership(
        snapshot: ProjectSnapshot,
        binding: CMakeBindingDecision.Bound,
    ): Map<Path, CMakeLiteralTargetOwner> {
        val translationUnits = snapshot.files.filter { it.path.fileName.toString().endsWith(".c") }
        val owners = translationUnits.associate { file ->
            val source = file.path.normalize()
            val occurrences = binding.sourceOwners[source].orEmpty()
            requireContext(occurrences.size == 1,
                "c.cmakeTargetOwnershipUnavailable: TU '$source' must have exactly one literal source occurrence/owner")
            val owner = occurrences.single()
            requireContext(owner.targetKind == "add_executable" ||
                (owner.targetKind == "add_library" && owner.libraryType == "STATIC"),
                "c.cmakeTargetOwnershipUnavailable: owner '${owner.target}' of TU '$source' is not an executable or explicit STATIC library")
            source to owner
        }
        requireContext(binding.sourceOwners.keys.filter { it.fileName.toString().endsWith(".c") }.toSet() == owners.keys,
            "c.cmakeTargetOwnershipUnavailable: literal source coverage differs from captured translation units")
        return owners
    }

    private fun ownershipModel(owners: Map<Path, CMakeLiteralTargetOwner>): JsonArray =
        JsonArray(owners.entries.sortedBy { it.key.toString() }.map { (source, owner) ->
            JsonArray(listOf(JsonPrimitive(source.toString()), JsonPrimitive(owner.target),
                JsonPrimitive(owner.targetKind), owner.libraryType?.let(::JsonPrimitive) ?: JsonNull))
        })

    private fun sanitize(arguments: List<String>, old: Path, root: Path, clang: Path): List<String> {
        requireContext(arguments.isNotEmpty() && runCatching { Path.of(arguments[0]).toAbsolutePath().normalize() }
            .getOrNull() == clang, "c.compilationCommandUnsupported: compiler command differs from configured Clang")
        val flags = mutableListOf<String>()
        var dialect: String? = null
        var sourceCount = 0
        var compileCount = 0
        var i = 1
        val define = Regex("[A-Za-z_][A-Za-z_0-9]*(=([A-Za-z_][A-Za-z_0-9]*|[0-9]+))?")
        while (i < arguments.size) {
            val arg = arguments[i]
            when {
                arg == "-std=c11" || arg == "-std=c17" -> {
                    requireContext(dialect == null, "c.compilationCommandUnsupported: conflicting standards")
                    dialect = arg
                }
                arg == "-c" -> compileCount++
                arg == "-D" -> {
                    val value = arguments.getOrNull(++i)
                    requireContext(value != null && value.matches(define),
                        "c.compilationCommandUnsupported: unsupported separated macro definition")
                    flags += "-D$value"
                }
                arg.startsWith("-D") && arg.substring(2).matches(define) -> flags += arg
                arg == old.toString() || runCatching { Path.of(arg).isAbsolute &&
                    Path.of(arg).normalize() == root.resolve(old) }.getOrDefault(false) -> sourceCount++
                else -> error("c.compilationCommandUnsupported: unexpected compiler argument; project commands are data, not executables")
            }
            i++
        }
        requireContext(dialect != null && sourceCount == 1 && compileCount == 1 && flags.size <= 32,
            "c.compilationCommandUnsupported: exact C11/C17 source and flags are required")
        return listOf(dialect!!, "-pedantic-errors") + flags
    }

    private inner class Captured(
        val root: Path,
        val beforeHash: String,
        val stagedHash: String,
        val movedPaths: Map<Path, Path>,
        val componentDir: Path?,
        val newDir: Path?,
        val databasePath: Path,
        val databaseHash: String,
        val clangPath: Path,
        val clangHash: String,
        val contexts: Map<Path, List<String>>,
        val ownershipHash: String,
    ) {
        // Separate input-bound extensions; retain the historical source-policy core hash.
        val definePolicyHash = policyBindingHash(CONSUMER_DEFINE_POLICY)
        val pastePolicyHash = policyBindingHash(CONSUMER_PASTE_POLICY)
        val trigraphPolicyHash = policyBindingHash(CONSUMER_TRIGRAPH_POLICY)
        val embedPolicyHash = policyBindingHash(CONSUMER_EMBED_POLICY)
        val newlinePolicyHash = policyBindingHash(CONSUMER_NEWLINE_POLICY)
        val pathMacroPolicyHash = policyBindingHash(MOVED_PATH_MACRO_POLICY)
        val pathDefinePolicyHash = policyBindingHash(CONSUMER_PATH_DEFINE_POLICY)
        val directivePolicyHash = policyBindingHash(MOVED_DIRECTIVE_POLICY)
        val directiveTokenPolicyHash = policyBindingHash(MOVED_DIRECTIVE_TOKEN_POLICY)

        private fun policyBindingHash(policy: String): String =
            hash("$policy|$DIAGNOSTICS_SCOPE|${contexts.size}|$beforeHash|$stagedHash|$databaseHash|$clangHash|$ownershipHash"
                .toByteArray(Charsets.UTF_8))

        fun evaluate(candidate: ProjectSnapshot): List<Diagnostic> {
            requireContext(candidate.workspace.root.toAbsolutePath().normalize() == root &&
                candidate.hash in setOf(beforeHash, stagedHash),
                "c.compilationContextUnavailable: diagnostics candidate differs from approved snapshot")
            verifyCapturedInputs(candidate)
            val baseline = candidate.hash == beforeHash
            movedPaths.forEach { (old, next) ->
                val movedPath = if (baseline) old else next
                val moved = candidate.files.singleOrNull { it.path.normalize() == movedPath }
                requireContext(moved != null && !hasPathSensitiveInput(moved.content, candidate, movedPath),
                    "c.compilationContextUnavailable: moved source/header '$movedPath' missing or scope changed")
            }
            val evaluated = contexts.flatMap { (source, flags) ->
                val file = if (!baseline) movedPaths[source] ?: source else source
                requireContext(candidate.files.count { it.path.normalize() == file } == 1,
                    "c.compilationContextUnavailable: captured translation unit '$file' is missing or ambiguous")
                val result = diagnostics.analyze(candidate, file, flags)
                val available = result as? CDiagnosticsResult.Available ?: run {
                    val failure = (result as CDiagnosticsResult.Unavailable).diagnostic
                    error("c.compilationDiagnosticsUnavailable: TU '$file': ${failure.code}: ${failure.message}")
                }
                requireContext(available.processProvenance?.executableSha256 == clangHash,
                    "c.compilationDiagnosticsUnavailable: TU '$file': hash-bound compiler provenance is incomplete or changed")
                val errors = available.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
                requireContext(errors.isEmpty(),
                    "c.compilationDiagnosticsUnavailable: TU '$file': ${errors.joinToString("; ") { it.message }}")
                available.diagnostics
            }
            // Preserve exact compiler-error precedence, then refuse clean fallback
            // branches whose availability consumers are not modeled by this cell.
            if (!baseline) {
                candidate.files.sortedBy { it.path.toString() }.forEach { source ->
                    requireContext(!hasDirectAvailabilityProbe(source.content),
                        "c.compilationConsumerUnavailable: TU '${source.path}': direct header-availability probe is not admitted by managed move policy")
                }
                contexts.forEach { (source, flags) ->
                    val file = movedPaths[source] ?: source
                    requireContext(flags.none { flag -> flag.startsWith("-D") &&
                        hasDirectAvailabilityProbe(flag.substring(2)) },
                        "c.compilationConsumerUnavailable: TU '$file': captured compiler-define availability probe is not admitted by managed move policy")
                }
                candidate.files.sortedBy { it.path.toString() }.forEach { source ->
                    requireContext(!hasSourceTokenPaste(source.content),
                        "c.compilationConsumerUnavailable: TU '${source.path}': source token-paste expansion is not admitted by managed move policy")
                }
                candidate.files.sortedBy { it.path.toString() }.forEach { source ->
                    requireContext(!hasRawTrigraphInput(source.content),
                        "c.compilationConsumerUnavailable: TU '${source.path}': raw C11/C17 trigraph input is not admitted by managed move policy")
                }
                candidate.files.sortedBy { it.path.toString() }.forEach { source ->
                    requireContext(!hasResourceAvailabilityProbe(source.content),
                        "c.compilationConsumerUnavailable: TU '${source.path}': direct resource-availability probe is not admitted by managed move policy")
                }
                contexts.forEach { (source, flags) ->
                    val file = movedPaths[source] ?: source
                    requireContext(flags.none { flag -> flag.startsWith("-D") &&
                        hasResourceAvailabilityProbe(flag.substring(2)) },
                        "c.compilationConsumerUnavailable: TU '$file': captured compiler-define resource-availability probe is not admitted by managed move policy")
                }
                contexts.forEach { (source, flags) ->
                    val file = movedPaths[source] ?: source
                    requireContext(flags.none { flag -> flag.startsWith("-D") &&
                        hasPathMacroIdentifier(flag.substring(2)) },
                        "c.compilationConsumerUnavailable: TU '$file': captured compiler-define path macro is not admitted by managed move policy")
                }
            }
            // A longer project evaluation must not grant authority if inputs
            // changed while the sequential compiler checks were running.
            verifyCapturedInputs(candidate)
            return evaluated
        }

        private fun verifyCapturedInputs(candidate: ProjectSnapshot) {
            requireContext(Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(databasePath) <= MAX_DATABASE_BYTES && hashFile(databasePath) == databaseHash,
                "c.compilationDatabaseChanged: compilation database changed after preview")
            requireContext(Files.isRegularFile(clangPath, LinkOption.NOFOLLOW_LINKS) &&
                hashFile(clangPath) == clangHash,
                "c.compilerToolchainChanged: configured compiler changed after preview")
            requireContext(!Files.exists(root.resolve("build/compile_commands.json"), LinkOption.NOFOLLOW_LINKS) &&
                !CMakeManifestCapture.hasUncapturedCMakeAuthority(candidate) &&
                movedPaths.keys.none { nestedManifestExists(root, it) } &&
                movedPaths.values.none { nestedManifestExists(root, it) },
                "c.compilationContextUnavailable: another build configuration appeared after preview")
        }
    }

    private fun hasRawTrigraphInput(content: String): Boolean = (0 until content.length - 2).any { index ->
        // ISO C11/C17 phase one precedes splicing and lexing; trivia is not an exemption.
        content[index] == '?' && content[index + 1] == '?' && content[index + 2] in "=/'()!<>-"
    }

    private fun prepareSource(content: String): String {
        // Canonicalize configured-Clang newline boundaries only in analysis input;
        // source/snapshot bytes stay unchanged. Splicing precedes trivia lexing.
        val newlines = content.replace("\r\n", "\n").replace('\r', '\n')
        return newlines.replace("\\\n", "")
    }

    private fun sourceTokens(content: String): List<CToken> = CTokenizer().tokenize(prepareSource(content))

    private fun hasDirectAvailabilityProbe(content: String): Boolean = sourceTokens(content).any { token ->
        token.type == CTokenType.IDENTIFIER && (token.text == "__has_include" || token.text == "__has_include_next")
    }

    private fun hasResourceAvailabilityProbe(content: String): Boolean = sourceTokens(content).any { token ->
        token.type == CTokenType.IDENTIFIER && token.text == "__has_embed"
    }

    private fun hasSourceTokenPaste(content: String): Boolean {
        val tokens = sourceTokens(content)
        fun operator(index: Int, spelling: String): Boolean {
            val first = tokens[index]
            return spelling.indices.all { offset -> tokens.getOrNull(index + offset)?.let {
                it.type == CTokenType.PUNCTUATION && it.text == spelling[offset].toString() &&
                    it.line == first.line && it.column == first.column + offset
            } == true }
        }
        // Token adjacency is physical after splicing: separate null directives
        // or punctuation separated by whitespace/comments are not one operator.
        return tokens.indices.any { operator(it, "##") || operator(it, "%:%:") }
    }

    private fun hasPathMacroIdentifier(content: String): Boolean = sourceTokens(content).any { token ->
        token.type == CTokenType.IDENTIFIER &&
            token.text in setOf("__FILE__", "__FILE_NAME__", "__BASE_FILE__", "__builtin_FILE")
    }

    private fun hasUnparsedPathDirective(content: String, includes: List<CIncludeDirective>): Boolean {
        val tokens = sourceTokens(content)
        val parsedLines = includes.map { it.line }.toSet()
        return tokens.indices.any { index ->
            val first = tokens[index]
            // Only a line's first prepared token introduces a directive;
            // a macro's later stringization token is not an entry point.
            if (first.type != CTokenType.PUNCTUATION || tokens.getOrNull(index - 1)?.line == first.line) return@any false
            val keywordIndex = when {
                first.text == "#" -> index + 1
                first.text == "%" && tokens.getOrNull(index + 1)?.let { colon ->
                    colon.type == CTokenType.PUNCTUATION && colon.text == ":" &&
                        colon.line == first.line && colon.column == first.column + 1
                } == true -> index + 2
                else -> return@any false
            }
            val keyword = tokens.getOrNull(keywordIndex) ?: return@any false
            if (keyword.type != CTokenType.IDENTIFIER || keyword.line != first.line) return@any false
            when (keyword.text) {
                "include" -> first.line !in parsedLines
                "include_next", "import", "line" -> true
                else -> false
            }
        }
    }

    private fun hasPathSensitiveInput(content: String, snapshot: ProjectSnapshot, filePath: Path): Boolean {
        if (Regex("\\b(__FILE__|__FILE_NAME__|__BASE_FILE__|__builtin_FILE|__has_include)\\b").containsMatchIn(content)) return true
        // Keep the conservative raw guard, and contain identifiers reconstructed
        // by the existing newline/splice preparation without macro interpretation.
        if (hasPathMacroIdentifier(content)) return true
        val prepared = prepareSource(content)
        val specialDirective = Regex("(?m)^\\s*#\\s*(include_next|import|line)\\b")
        if (specialDirective.containsMatchIn(content) || specialDirective.containsMatchIn(prepared)) return true
        // Supplement, never relax, the raw directive guard using the same
        // preparation/parser. No new operand grammar or macro interpretation.
        val parser = CIncludeDirectiveParser()
        val preparedIncludes = parser.parse(prepared)
        if (hasUnparsedPathDirective(content, preparedIncludes)) return true
        val directives = parser.parse(content) + preparedIncludes
        val workspacePaths = snapshot.files.map { it.path.normalize() }.toSet()
        val fileDir = filePath.parent ?: Path.of("")
        return directives.any { directive ->
            when (directive.kind) {
                CIncludeKind.MACRO -> true
                CIncludeKind.QUOTED -> {
                    val resolved = fileDir.resolve(directive.target).normalize()
                    workspacePaths.contains(resolved)
                }
                CIncludeKind.ANGLED -> false
            }
        }
    }

    private fun nestedManifestExists(root: Path, file: Path): Boolean =
        generateSequence(file.parent) { it.parent }
            .filter { it.toString().isNotEmpty() && !it.isAbsolute && !it.startsWith("..") }
            .any { Files.exists(root.resolve(it).resolve("CMakeLists.txt"), LinkOption.NOFOLLOW_LINKS) }

    private fun hashFile(file: Path): String = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun requireContext(condition: Boolean, reason: String) {
        if (!condition) error(reason)
    }

    companion object {
        private const val GATE_ID = "clang-cmake-single-tu-v1"
        private const val DIAGNOSTICS_SCOPE = "captured-project-tus-v1"
        private const val OWNERSHIP_SCOPE = "literal-root-tu-ownership-v1"
        private const val FLAG_POLICY = "literal-root-no-include-flags-v1"
        private const val CONSUMER_PROBE_POLICY = "direct-availability-tokens-refused-v1"
        private const val CONSUMER_DEFINE_POLICY = "availability-define-tokens-refused-v1"
        private const val CONSUMER_PASTE_POLICY = "source-token-paste-refused-v1"
        private const val CONSUMER_TRIGRAPH_POLICY = "raw-c11-c17-trigraphs-refused-v1"
        private const val CONSUMER_EMBED_POLICY = "direct-source-define-embed-refused-v1"
        private const val CONSUMER_NEWLINE_POLICY = "lf-crlf-cr-before-splice-lex-v1"
        private const val MOVED_PATH_MACRO_POLICY = "prepared-moved-source-path-identifiers-refused-v1"
        private const val CONSUMER_PATH_DEFINE_POLICY = "captured-define-path-identifiers-refused-v1"
        private const val MOVED_DIRECTIVE_POLICY = "lf-crlf-cr-splice-moved-directives-v1"
        private const val MOVED_DIRECTIVE_TOKEN_POLICY = "unparsed-moved-directive-tokens-refused-v1"
        private const val MAX_MANAGED_TUS = 8
        private const val MAX_DATABASE_BYTES = 8 * 1024 * 1024
    }
}
