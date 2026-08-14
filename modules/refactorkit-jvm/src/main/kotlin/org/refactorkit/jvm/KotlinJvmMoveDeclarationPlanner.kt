package org.refactorkit.jvm

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.kotlin.KotlinCompilerDeclarationEvidence
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinDeclarationVisibility
import org.refactorkit.kotlin.KotlinLanguageAdapter
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** Bounded K5 whole-file type/function package move with fail-closed standalone companion refusal. */
class KotlinJvmMoveDeclarationPlanner(
    kotlin: KotlinLanguageAdapter,
    private val java: JdtJavaSemanticAnalyzer = JdtJavaSemanticAnalyzer(),
) {
    private val compilerSymbols = kotlin::compilerSymbols
    private val compilerDiagnostics = kotlin::compilerDiagnostics
    private val compilerDiagnosticsWithOutput = kotlin::compilerDiagnosticsWithOutput
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = when (val evidence = analyzeMixed(snapshot)) {
        is MixedEvidence.Available -> evidence.kotlin.diagnostics + javaDiagnostics(evidence.java)
        is MixedEvidence.Refused -> listOf(failure(evidence.code, evidence.message))
    }

    fun preview(
        snapshot: ProjectSnapshot,
        symbolId: SymbolId,
        targetPackage: String,
        acceptExternalConsumerRisk: Boolean = false,
    ): PatchPlan {
        if (!PACKAGE.matches(targetPackage) || targetPackage.split('.').any { it in KEYWORDS }) return refused(
            snapshot, "kotlin.moveTargetPackageInvalid", "Kotlin move target package is invalid",
        )
        if (!acceptExternalConsumerRisk) return refused(
            snapshot, "kotlin.moveExternalConsumerApprovalRequired",
            "Public Kotlin/JVM move requires explicit acceptance of unknown external-consumer risk",
        )
        val catalogue = when (val result = compilerSymbols(snapshot)) {
            is KotlinCompilerSymbolsResult.Available -> result
            is KotlinCompilerSymbolsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.moveEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerSymbolsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.moveEvidenceUnavailable", result.failure.message,
            )
        }
        val target = catalogue.index.symbols.singleOrNull { it.id == symbolId }
            ?: return refused(snapshot, "kotlin.moveTargetMissing", "Kotlin move target is absent from the compiler catalogue")
        val declaration = catalogue.declarations[target.id]
            ?: return refused(snapshot, "kotlin.moveIdentityUnavailable", "Kotlin move target lacks JVM identity evidence")
        val source = snapshot.file(target.location.path) ?: return refused(
            snapshot, "kotlin.moveDeclarationFileMissing", "Kotlin declaration file is absent from the snapshot",
        )
        val sourceFileName = source.path.fileName?.toString() ?: return refused(
            snapshot, "kotlin.moveDeclarationFileMissing", "Kotlin declaration path has no source filename",
        )
        if (target.kind == Symbol.Kind.OBJECT && declaration.isCompanion) return refused(
            snapshot, "kotlin.moveCompanionStandaloneUnsupported",
            "A companion object cannot be moved independently of its enclosing top-level declaration",
        )
        val isTopLevelType = target.kind in TYPE_KINDS &&
            declaration.visibility == KotlinDeclarationVisibility.PUBLIC &&
            declaration.jvmIdentity == declaration.jvmOwner && declaration.jvmDescriptor.isEmpty() &&
            '\$' !in declaration.jvmIdentity
        val isTopLevelFunctionIdentity = target.kind == Symbol.Kind.FUNCTION && declaration.isTopLevelFunction &&
            declaration.visibility == KotlinDeclarationVisibility.PUBLIC && declaration.jvmOwner.isNotBlank() &&
            declaration.jvmName == target.name && declaration.jvmDescriptor.startsWith("(") &&
            declaration.jvmIdentity == "${declaration.jvmOwner}#${declaration.jvmName}${declaration.jvmDescriptor}"
        if (isTopLevelFunctionIdentity && !declaration.isMovePlainFunction) return refused(
            snapshot, "kotlin.moveFunctionShapeUnsupported",
            "Top-level function move excludes extension, suspend, default, generic, context, annotation, and modifier-dependent shapes",
        )
        if (isTopLevelFunctionIdentity && declaration.containsCallableReference) return refused(
            snapshot, "kotlin.moveFunctionCallableReferenceUnsupported",
            "Top-level function move requires compiler-PSI proof that the moved declaration contains no callable reference",
        )
        val isTopLevelFunction = isTopLevelFunctionIdentity && declaration.isMovePlainFunction
        if (!isTopLevelType && !isTopLevelFunction) return refused(
            snapshot, "kotlin.moveDeclarationUnsupported",
            "Kotlin move supports qualified public top-level JVM types and one bounded top-level function shape",
        )
        val oldPackage = if (isTopLevelType) declaration.jvmIdentity.substringBeforeLast('.', "")
        else declaration.jvmOwner.substringBeforeLast('.', "")
        if (oldPackage.isEmpty()) return refused(
            snapshot, "kotlin.moveDefaultPackageUnsupported", "Initial Kotlin move does not support the default package",
        )
        if (oldPackage == targetPackage) return refused(
            snapshot, "kotlin.moveNoChange", "Kotlin move source and target packages are identical",
        )
        if (isTopLevelFunction && !boundedTopLevelFunction(source, target, declaration, oldPackage)) return refused(
            snapshot, "kotlin.moveFunctionShapeUnsupported",
            "Top-level function move requires one plain public file-facade function without extension, suspend, default, overload, or JVM-name adaptation",
        )
        if (isTopLevelFunction && catalogue.index.symbols.count { symbol ->
                val evidence = catalogue.declarations[symbol.id]
                symbol.kind == Symbol.Kind.FUNCTION && symbol.name == target.name &&
                    evidence != null && evidence.isTopLevelFunction &&
                    evidence.jvmOwner.substringBeforeLast('.', "") == oldPackage
            } != 1) return refused(
            snapshot, "kotlin.moveFunctionShapeUnsupported",
            "Top-level function move does not support same-package overload families across one or more files",
        )
        val fileDeclarations = supportedFileDeclarations(catalogue, source, target, declaration)
            ?: return refused(
                snapshot, "kotlin.moveFileShapeUnsupported",
                "Kotlin move requires one public target plus only compiler-proven private top-level helpers",
            )
        if (fileDeclarations.any { it.evidence.containsCallableReference }) return refused(
            snapshot,
            if (isTopLevelFunction) "kotlin.moveFunctionCallableReferenceUnsupported"
            else "kotlin.moveCallableReferenceUnsupported",
            "Kotlin move requires compiler-PSI proof that every moved top-level declaration contains no callable reference",
        )
        if (isTopLevelFunction && catalogue.index.symbols.any { symbol ->
                symbol.location.path.normalize() != source.path.normalize() &&
                    catalogue.declarations[symbol.id]?.containsCallableReference == true
            }) return refused(
            snapshot, "kotlin.moveFunctionCallableReferenceUnsupported",
            "Top-level function move refuses Kotlin callable-reference consumers outside the moved file",
        )
        if (isTopLevelFunction && fileDeclarations.count {
                it.symbol.kind == Symbol.Kind.FUNCTION && it.symbol.name == target.name
            } != 1) return refused(
            snapshot, "kotlin.moveFunctionShapeUnsupported",
            "Top-level function move does not support overload families, including private same-name siblings",
        )
        if (isTopLevelFunction && catalogue.index.symbols.any { symbol ->
                val evidence = catalogue.declarations[symbol.id]
                symbol.kind == Symbol.Kind.FUNCTION && symbol.name == target.name &&
                    evidence != null && evidence.isTopLevelFunction &&
                    evidence.jvmOwner.substringBeforeLast('.', "") == targetPackage
            }) return refused(
            snapshot, "kotlin.moveDestinationOverloadUnsupported",
            "Top-level function move would create a same-name overload family in the destination package",
        )
        val allFileDeclarations = catalogue.index.symbols
            .filter { it.location.path.normalize() == source.path.normalize() }
            .map { symbol -> symbol to (catalogue.declarations[symbol.id] ?: return refused(
                snapshot, "kotlin.moveIdentityUnavailable",
                "Kotlin move source file has a declaration without exact JVM identity evidence",
            )) }
        if (allFileDeclarations.isEmpty() ||
            allFileDeclarations.any { !it.second.jvmIdentity.startsWith("$oldPackage.") }) return refused(
            snapshot, "kotlin.moveFileShapeUnsupported",
            "Kotlin move requires package-qualified JVM identity for every declaration in the source file",
        )
        val movedTypeIdentities = allFileDeclarations.filter { it.first.kind in TYPE_KINDS }
            .associate { (_, evidence) ->
                evidence.jvmIdentity to "$targetPackage.${evidence.jvmIdentity.removePrefix("$oldPackage.")}"
            }
        val movedFileIdentities = allFileDeclarations.associate { (_, evidence) ->
            val oldIdentity = evidence.jvmIdentity
            val ownerRelocated = "$targetPackage.${oldIdentity.removePrefix("$oldPackage.")}"
            oldIdentity to movedTypeIdentities.entries.fold(ownerRelocated) { relocated, (oldType, newType) ->
                relocated.replace(
                    "L${oldType.replace('.', '/')};",
                    "L${newType.replace('.', '/')};",
                )
            }
        }
        val publicDeclarations = fileDeclarations.filter {
            it.evidence.visibility == KotlinDeclarationVisibility.PUBLIC
        }
        val movedIdentities = publicDeclarations.associate { declarationInFile ->
            val oldIdentity = declarationInFile.evidence.jvmIdentity
            oldIdentity to movedFileIdentities.getValue(oldIdentity)
        }
        val movedSourceIdentities = publicDeclarations.associate { declarationInFile ->
            declarationInFile.evidence.jvmIdentity to if (isTopLevelFunction) {
                "$targetPackage.${declarationInFile.symbol.name}"
            } else {
                movedIdentities.getValue(declarationInFile.evidence.jvmIdentity)
            }
        }
        if (dynamicRisk(snapshot, publicDeclarations.map { it.symbol.name }.toSet())) return refused(
            snapshot, "kotlin.moveDynamicOrFrameworkReference",
            "Quoted reflection, serialization or framework evidence prevents the bounded Kotlin move row",
        )
        val ownership = snapshot.owningBuildSourceRoots(source.path)
        val ownedRoots = ownership.map { it.root.normalize() }.distinct()
        if (ownership.isEmpty() || ownedRoots.size != 1 ||
            ownership.any { it.generated || it.modelStatus != BuildModelStatus.AVAILABLE }) return refused(
            snapshot, "kotlin.moveSourceOwnershipUnavailable",
            "Kotlin move requires one authoritative non-generated source-root path",
        )
        val destination = ownedRoots.single().resolve(targetPackage.replace('.', '/')).resolve(sourceFileName).normalize()
        val newIdentity = movedIdentities.getValue(declaration.jvmIdentity)
        if (destination == source.path.normalize() || snapshot.files.any { it.path.normalize() == destination } ||
            Files.exists(snapshot.workspace.root.resolve(destination)) ||
            catalogue.declarations.values.any {
                it.jvmIdentity in movedFileIdentities.values && it.jvmIdentity !in movedFileIdentities.keys
            }) return refused(
            snapshot, "kotlin.moveDestinationConflict", "Kotlin move destination already exists",
        )

        val sourcePackageEdit = packageEdit(source, oldPackage, targetPackage)
            ?: return refused(
                snapshot, "kotlin.movePackageDeclarationInvalid", "Kotlin package declaration is not exact",
            )
        val before = when (val evidence = analyzeMixed(snapshot)) {
            is MixedEvidence.Available -> evidence
            is MixedEvidence.Refused -> return refused(snapshot, evidence.code, evidence.message)
        }
        if (before.kotlin.symbolFailure != null || before.kotlin.diagnostics.any { it.severity == Diagnostic.Severity.ERROR } ||
            before.java.warnings.isNotEmpty()) return refused(
            snapshot, "kotlin.moveBaselineIncomplete", "Kotlin move requires complete clean K2/JDT evidence",
            before.kotlin.diagnostics + javaDiagnostics(before.java),
        )
        if (isTopLevelFunction) when (destinationFacadeEvidence(
            snapshot, "$targetPackage.${declaration.jvmOwner.substringAfterLast('.')}",
        )) {
            DestinationFacadeEvidence.ABSENT -> Unit
            DestinationFacadeEvidence.PRESENT -> return refused(
                snapshot, "kotlin.moveDestinationConflict",
                "Top-level function move would collide with a dependency JVM file facade",
            )
            DestinationFacadeEvidence.UNAVAILABLE -> return refused(
                snapshot, "kotlin.moveDestinationEvidenceUnavailable",
                "Top-level function move cannot prove dependency-facade absence in the destination package",
            )
        }
        if (isTopLevelFunction) when (destinationCallableEvidence(snapshot, source, targetPackage, target.name)) {
            DestinationCallableEvidence.ABSENT -> Unit
            DestinationCallableEvidence.PRESENT -> return refused(
                snapshot, "kotlin.moveDestinationOverloadUnsupported",
                "Top-level function move would collide with a dependency callable in the destination package",
            )
            DestinationCallableEvidence.UNAVAILABLE -> return refused(
                snapshot, "kotlin.moveDestinationEvidenceUnavailable",
                "Top-level function move cannot prove dependency-callable absence in the destination package",
            )
        }
        val expectedOutboundBindings = if (isTopLevelFunction) {
            outboundBindings(
                before.kotlin, source.path, movedFileIdentities,
                BindingProjection(source.content, sourcePackageEdit),
            )
                ?: return refused(
                    snapshot, "kotlin.moveOutboundEvidenceIncomplete",
                    "Top-level function move requires exact outbound K2 binding evidence for the moved file",
                )
        } else emptyMap()
        val publicById = publicDeclarations.associateBy { it.symbol.id }
        val exactJavaCallableBindingUses = before.java.bindingUses.filter { use ->
            use.jvmIdentity?.let { identity ->
                "${identity.ownerBinaryName}#${identity.memberName}${identity.descriptor}"
            } == declaration.jvmIdentity
        }
        val exactJavaCallableReferences = before.java.references.filter { reference ->
            reference.jvmIdentity?.let { identity ->
                "${identity.ownerBinaryName}#${identity.memberName}${identity.descriptor}"
            } == declaration.jvmIdentity
        }
        if (isTopLevelFunction && (exactJavaCallableBindingUses.isNotEmpty() || exactJavaCallableReferences.isNotEmpty())) {
            return refused(
                snapshot, "kotlin.moveFunctionJavaConsumerUnsupported",
                "The bounded top-level function move does not support Java consumers",
            )
        }
        val javaUses = if (isTopLevelFunction) emptyList() else before.java.bindingUses.filter {
            it.symbolQualifiedName in movedIdentities.keys
        }
        val consumerUses = catalogue.usages.filter {
            it.targetId in publicById.keys && it.location.path.normalize() != source.path.normalize()
        }.map { usage ->
            ConsumerUse(publicById.getValue(usage.targetId).evidence.jvmIdentity, usage.location)
        } + javaUses.map { usage ->
            ConsumerUse(usage.symbolQualifiedName!!, SourceLocation(usage.path, usage.sourceRange))
        }
        val consumerPaths = consumerUses.map { it.location.path.normalize() }.toSet()
        val usesByPath = consumerUses.groupBy { it.location.path.normalize() }
        if (consumerPaths.any { path -> snapshot.owningBuildSourceRoots(path).any { it.generated } }) return refused(
            snapshot, "kotlin.moveGeneratedReference", "Kotlin move consumer belongs to generated source",
        )
        if (isTopLevelFunction && consumerPaths.any { path ->
                val roots = snapshot.owningBuildSourceRoots(path)
                roots.isEmpty() || roots.map { it.root.normalize() }.distinct().size != 1 ||
                    roots.any { it.generated || it.modelStatus != BuildModelStatus.AVAILABLE }
            }) return refused(
            snapshot, "kotlin.moveFunctionConsumerOwnershipUnavailable",
            "Top-level function move requires one authoritative non-generated source root for every consumer",
        )

        val edits = mutableListOf<FileEdit>()
        edits += FileEdit.Modify(source.path, listOf(sourcePackageEdit))
        for (path in consumerPaths.sortedBy { it.toString() }) {
            val consumer = snapshot.file(path) ?: return refused(
                snapshot, "kotlin.moveReferenceFileMissing", "Kotlin move consumer is absent from the snapshot",
            )
            val pathUses = usesByPath[path].orEmpty()
            val consumerEdits = when {
                isTopLevelFunction -> topLevelFunctionConsumerEdits(
                    consumer, pathUses,
                    "$oldPackage.${target.name}", movedSourceIdentities.getValue(declaration.jvmIdentity), target.name,
                )
                publicDeclarations.size == 1 -> consumerEdits(
                    consumer, pathUses.map { it.location }, oldPackage,
                    declaration.jvmIdentity, newIdentity, target.name,
                )
                else -> publicSiblingConsumerEdits(
                    consumer, pathUses, movedIdentities, oldPackage,
                )
            } ?: return refused(
                snapshot,
                when {
                    isTopLevelFunction -> "kotlin.moveFunctionConsumerShapeUnsupported"
                    publicDeclarations.size == 1 -> "kotlin.moveImportShapeUnsupported"
                    else -> "kotlin.movePublicSiblingImportUnsupported"
                },
                when {
                    isTopLevelFunction -> "Top-level function move requires one exact explicit Kotlin callable import and compiler-proven uses"
                    publicDeclarations.size == 1 -> "Kotlin move requires an exact import, same-package use, or fully-qualified compiler-proven target"
                    else -> "Additional public file types require exact explicit/aliased, package-star, same-package, or fully-qualified consumers"
                },
            )
            edits += FileEdit.Modify(path, consumerEdits)
        }
        edits += FileEdit.Rename(source.path, destination)
        val workspaceEdit = WorkspaceEdit(edits)
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "kotlin.movePreviewInvalid", it.message ?: "Kotlin move preview is invalid")
        }
        val after = when (val evidence = analyzeMixed(staged)) {
            is MixedEvidence.Available -> evidence
            is MixedEvidence.Refused -> return refused(snapshot, evidence.code, evidence.message)
        }
        val introduced = introducedDiagnostics(
            before.kotlin.diagnostics + javaDiagnostics(before.java),
            after.kotlin.diagnostics + javaDiagnostics(after.java),
        )
        if (introduced.isNotEmpty()) return refused(
            snapshot, "kotlin.moveDiagnosticsRegression",
            "Kotlin move introduces ${introduced.size} compiler error(s)", introduced,
        )
        val stagedOutboundBindings = if (isTopLevelFunction) {
            outboundBindings(after.kotlin, destination)
                ?: return refused(
                    snapshot, "kotlin.moveOutboundEvidenceIncomplete",
                    "Staged top-level function move lacks exact outbound K2 binding evidence",
                )
        } else emptyMap()
        if (stagedOutboundBindings != expectedOutboundBindings) return refused(
            snapshot, "kotlin.moveOutboundBindingChanged",
            "Staged top-level function move changes an outbound semantic binding",
        )
        val stagedDeclaration = after.kotlin.declarations.entries.singleOrNull { it.value.jvmIdentity == newIdentity }
        val stagedPublicIds = after.kotlin.declarations.filterValues {
            it.jvmIdentity in movedIdentities.values
        }.keys
        val expectedMovedIdentities = movedFileIdentities.values.toSet()
        val stagedIdentities = after.kotlin.declarations.values.map { it.jvmIdentity }.toSet()
        val stagedKotlinUseCount = after.kotlin.usages.count {
            it.targetId in stagedPublicIds && it.location.path.normalize() != destination
        }
        val expectedKotlinUseCount = consumerUses.count { it.location.path.toString().endsWith(".kt") }
        val stagedJavaUseCount = after.java.bindingUses.count { it.symbolQualifiedName in movedIdentities.values }
        val stagedFunctionHasJavaConsumer = isTopLevelFunction && (
            after.java.bindingUses.any { use ->
                use.jvmIdentity?.let { identity ->
                    "${identity.ownerBinaryName}#${identity.memberName}${identity.descriptor}"
                } == newIdentity
            } || after.java.references.any { reference ->
                reference.jvmIdentity?.let { identity ->
                    "${identity.ownerBinaryName}#${identity.memberName}${identity.descriptor}"
                } == newIdentity
            }
        )
        val kotlinUsesComplete = if (isTopLevelFunction) {
            stagedKotlinUseCount == expectedKotlinUseCount
        } else {
            stagedKotlinUseCount >= expectedKotlinUseCount
        }
        if (stagedDeclaration == null || stagedPublicIds.size != publicDeclarations.size ||
            !stagedIdentities.containsAll(expectedMovedIdentities) || !kotlinUsesComplete ||
            stagedFunctionHasJavaConsumer || stagedJavaUseCount < javaUses.size) return refused(
            snapshot, "kotlin.movePostImageIdentityMissing",
            "Staged K2/JDT evidence does not resolve every moved JVM identity use",
        )
        return PatchPlan(
            operation = "moveDeclaration", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.91, requiresUserApproval = true,
            summary = if (isTopLevelFunction) {
                "Move public top-level Kotlin function '${target.name}' from '$oldPackage' to '$targetPackage' across ${consumerPaths.size} exact-import consumer file(s)."
            } else {
                "Move ${publicDeclarations.size} public Kotlin type(s) led by '${target.name}' from '$oldPackage' to '$targetPackage' across ${consumerPaths.size} compiler-proven consumer file(s)."
            },
            affectedFiles = workspaceEdit.affectedFiles(), workspaceEdit = workspaceEdit,
            diagnosticsBefore = before.kotlin.diagnostics + javaDiagnostics(before.java),
            diagnosticsAfterPreview = after.kotlin.diagnostics + javaDiagnostics(after.java),
            warnings = listOf("Public JVM package move: unknown external consumers were explicitly accepted for this preview."),
            riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun analyzeMixed(snapshot: ProjectSnapshot): MixedEvidence {
        var javaEvidence: JdtJavaSemanticAnalysisResult? = null
        val kotlinResult = compilerDiagnosticsWithOutput(snapshot) { output ->
            javaEvidence = java.analyze(snapshot, additionalClasspathEntries = listOf(output))
        }
        return when (kotlinResult) {
            is KotlinCompilerDiagnosticsResult.Available -> kotlinResult.symbolFailure?.let {
                MixedEvidence.Refused(it.code ?: "kotlin.moveKotlinEvidenceUnavailable", it.message)
            } ?: javaEvidence?.let { MixedEvidence.Available(kotlinResult, it) }
                ?: MixedEvidence.Refused("kotlin.moveBinaryEvidenceUnavailable", "Kotlin move lacks JVM binary evidence")
            is KotlinCompilerDiagnosticsResult.Refused -> MixedEvidence.Refused(
                kotlinResult.reason.code ?: "kotlin.moveEvidenceUnavailable", kotlinResult.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> MixedEvidence.Refused(
                kotlinResult.failure.code ?: "kotlin.moveEvidenceUnavailable", kotlinResult.failure.message,
            )
        }
    }

    private fun packageEdit(source: SourceFile, oldPackage: String, targetPackage: String): TextEdit? {
        // Kotlin package declarations may carry a trailing line comment (e.g. "// note") that
        // must be preserved byte for byte; only the package token is replaced. The Java form
        // keeps the existing semicolon-terminated exact match.
        val terminator = if (source.languageId == "kotlin") "(?:[ \\t]*;|[ \\t]*//.*$|[ \\t]*$)" else "[ \\t]*;[ \\t]*$"
        val match = Regex(
            "(?m)^[ \\t]*package[ \\t]+(${Regex.escape(oldPackage)})$terminator",
        ).findAll(source.content).toList().singleOrNull() ?: return null
        val range = match.groups[1]!!.range
        return offsetEdit(source.content, range.first, range.last + 1, targetPackage)
    }

    private fun publicSiblingConsumerEdits(
        source: SourceFile,
        uses: List<ConsumerUse>,
        movedIdentities: Map<String, String>,
        oldPackage: String,
    ): List<TextEdit>? {
        val oldIdentities = uses.map { it.oldIdentity }.distinct().sorted()
        val explicit = oldIdentities.mapNotNull { oldIdentity ->
            exactImportEdit(source, oldIdentity, movedIdentities.getValue(oldIdentity))
        }
        if (explicit.size == oldIdentities.size) return explicit
        if (explicit.isNotEmpty()) return null
        if (oldIdentities.all { it in source.content }) {
            val qualified = mutableListOf<TextEdit>()
            for (oldIdentity in oldIdentities) {
                qualified += qualifiedUseEdits(
                    source, uses.filter { it.oldIdentity == oldIdentity }.map { it.location },
                    oldIdentity, movedIdentities.getValue(oldIdentity), oldIdentity.substringAfterLast('.'),
                ) ?: return null
            }
            return qualified
        }
        if (oldIdentities.any { it in source.content }) return null
        val terminator = if (source.languageId == "java") "\\s*;" else ""
        val stars = Regex(
            "(?m)^[ \\t]*import\\s+${Regex.escape(oldPackage)}\\.\\*$terminator[ \\t]*$",
        ).findAll(source.content).toList()
        val samePackage = exactPackage(source) == oldPackage
        if ((!samePackage && stars.size != 1) || (samePackage && stars.isNotEmpty())) return null
        val anchor = if (samePackage) packageLine(source) ?: return null else stars.single()
        val newline = if ("\r\n" in source.content) "\r\n" else "\n"
        var insertionOffset = anchor.range.last + 1
        if (source.content.startsWith(newline, insertionOffset)) insertionOffset += newline.length
        val semicolon = if (source.languageId == "java") ";" else ""
        val imports = oldIdentities.map { movedIdentities.getValue(it) }.sorted()
            .joinToString(separator = newline, postfix = newline) { "import $it$semicolon" }
        return listOf(offsetEdit(source.content, insertionOffset, insertionOffset, imports))
    }

    private fun topLevelFunctionConsumerEdits(
        source: SourceFile,
        uses: List<ConsumerUse>,
        oldSourceIdentity: String,
        newSourceIdentity: String,
        simpleName: String,
    ): List<TextEdit>? {
        if (source.languageId != "kotlin" || uses.isEmpty()) return null
        if (Regex("::\\s*${Regex.escape(simpleName)}\\b").containsMatchIn(source.content)) return null
        val oldPackage = oldSourceIdentity.substringBeforeLast('.')
        if (Regex("(?m)^[ \\t]*import[ \\t]+${Regex.escape(oldPackage)}\\.\\*[ \\t]*;?[ \\t]*$")
                .containsMatchIn(source.content)) return null
        val imports = Regex(
            "(?m)^[ \\t]*import[ \\t]+(${Regex.escape(oldSourceIdentity)})[ \\t]*;?[ \\t]*$",
        ).findAll(source.content).toList()
        if (imports.size != 1) return null
        val importRange = imports.single().groups[1]!!.range
        if (occurrenceOffsets(source.content, oldSourceIdentity) != listOf(importRange.first)) return null
        val edit = offsetEdit(source.content, importRange.first, importRange.last + 1, newSourceIdentity)
        if (uses.any { use ->
                val start = runCatching { TextEdits.offsetOf(source.content, use.location.range.start) }.getOrNull()
                    ?: return@any true
                val end = runCatching { TextEdits.offsetOf(source.content, use.location.range.end) }.getOrNull()
                    ?: return@any true
                start !in 0..end || end > source.content.length ||
                    source.content.substring(start, end) != simpleName
            }) return null
        return listOf(edit)
    }

    private fun exactImportEdit(source: SourceFile, oldIdentity: String, newIdentity: String): TextEdit? {
        val terminator = if (source.languageId == "java") "\\s*;" else ""
        val explicit = Regex(
            "(?m)^[ \\t]*import\\s+(${Regex.escape(oldIdentity)})$terminator[ \\t]*$",
        ).findAll(source.content).toList()
        val aliased = if (source.languageId == "kotlin") Regex(
            "(?m)^[ \\t]*import\\s+(${Regex.escape(oldIdentity)})\\s+as\\s+" +
                "[A-Za-z_][A-Za-z0-9_]*[ \\t]*$",
        ).findAll(source.content).toList() else emptyList()
        val matches = explicit + aliased
        if (matches.size != 1) return null
        val range = matches.single().groups[1]!!.range
        if (occurrenceOffsets(source.content, oldIdentity) != listOf(range.first)) return null
        return offsetEdit(source.content, range.first, range.last + 1, newIdentity)
    }

    private fun consumerEdits(
        source: SourceFile,
        locations: List<SourceLocation>,
        oldPackage: String,
        oldIdentity: String,
        newIdentity: String,
        simpleName: String,
    ): List<TextEdit>? {
        val terminator = if (source.languageId == "java") "\\s*;" else ""
        val explicit = Regex(
            "(?m)^[ \\t]*import\\s+(${Regex.escape(oldIdentity)})$terminator[ \\t]*$",
        ).findAll(source.content).toList()
        if (explicit.size == 1) {
            val range = explicit.single().groups[1]!!.range
            if (occurrenceOffsets(source.content, oldIdentity) != listOf(range.first)) return null
            return listOf(offsetEdit(source.content, range.first, range.last + 1, newIdentity))
        }
        if (explicit.isNotEmpty()) return null
        val aliased = if (source.languageId == "kotlin") Regex(
            "(?m)^[ \\t]*import\\s+(${Regex.escape(oldIdentity)})\\s+as\\s+" +
                "[A-Za-z_][A-Za-z0-9_]*[ \\t]*$",
        ).findAll(source.content).toList() else emptyList()
        if (aliased.size == 1) {
            val range = aliased.single().groups[1]!!.range
            if (occurrenceOffsets(source.content, oldIdentity) != listOf(range.first)) return null
            return listOf(offsetEdit(source.content, range.first, range.last + 1, newIdentity))
        }
        if (aliased.isNotEmpty()) return null
        val star = Regex(
            "(?m)^[ \\t]*import\\s+${Regex.escape(oldPackage)}\\.\\*$terminator[ \\t]*$",
        ).findAll(source.content).toList()
        if (star.size == 1) {
            if (oldIdentity in source.content) return null
            val newline = if ("\r\n" in source.content) "\r\n" else "\n"
            var insertionOffset = star.single().range.last + 1
            if (source.content.startsWith(newline, insertionOffset)) insertionOffset += newline.length
            val semicolon = if (source.languageId == "java") ";" else ""
            return listOf(offsetEdit(
                source.content, insertionOffset, insertionOffset,
                "import $newIdentity$semicolon$newline",
            ))
        }
        if (star.isNotEmpty()) return null
        if (oldIdentity in source.content) return qualifiedUseEdits(
            source, locations, oldIdentity, newIdentity, simpleName,
        )
        if (exactPackage(source) != oldPackage) return null
        val unsafeImport = Regex(
            "(?m)^[ \\t]*import\\s+(?:static\\s+)?(?:${Regex.escape(oldPackage)}\\.\\*|" +
                "[A-Za-z_][A-Za-z0-9_.]*\\.${Regex.escape(simpleName)})(?:\\s+as\\s+\\w+)?[ \\t]*;?[ \\t]*$",
        )
        if (unsafeImport.containsMatchIn(source.content)) return null
        val packageMatch = packageLine(source) ?: return null
        val newline = if ("\r\n" in source.content) "\r\n" else "\n"
        var insertionOffset = packageMatch.range.last + 1
        if (source.content.startsWith(newline, insertionOffset)) insertionOffset += newline.length
        val semicolon = if (source.languageId == "java") ";" else ""
        return listOf(offsetEdit(
            source.content, insertionOffset, insertionOffset,
            "import $newIdentity$semicolon$newline",
        ))
    }

    private fun qualifiedUseEdits(
        source: SourceFile,
        locations: List<SourceLocation>,
        oldIdentity: String,
        newIdentity: String,
        simpleName: String,
    ): List<TextEdit>? {
        val prefixLength = oldIdentity.length - simpleName.length
        val ranges = locations.map { location ->
            val tokenStart = runCatching { TextEdits.offsetOf(source.content, location.range.start) }.getOrNull()
                ?: return null
            val tokenEnd = runCatching { TextEdits.offsetOf(source.content, location.range.end) }.getOrNull()
                ?: return null
            val start = tokenStart - prefixLength
            if (start < 0 || tokenEnd <= tokenStart ||
                source.content.substring(tokenStart, tokenEnd) != simpleName ||
                source.content.substring(start, tokenEnd) != oldIdentity) return null
            start until tokenEnd
        }.distinctBy { it.first to it.last }.sortedBy { it.first }
        if (ranges.isEmpty() || occurrenceOffsets(source.content, oldIdentity) != ranges.map { it.first }) return null
        return ranges.map { range ->
            offsetEdit(source.content, range.first, range.last + 1, newIdentity)
        }
    }

    private fun occurrenceOffsets(content: String, value: String): List<Int> {
        val offsets = mutableListOf<Int>()
        var next = content.indexOf(value)
        while (next >= 0) {
            val before = content.getOrNull(next - 1)
            val after = content.getOrNull(next + value.length)
            if (before?.isJvmIdentifierPart() != true && after?.isJvmIdentifierPart() != true) offsets += next
            next = content.indexOf(value, next + value.length)
        }
        return offsets
    }

    private fun Char.isJvmIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'

    private fun exactPackage(source: SourceFile): String? = packageLine(source)?.groups?.get(1)?.value

    private fun packageLine(source: SourceFile): MatchResult? {
        val terminator = if (source.languageId == "java") "[ \\t]*;" else "[ \\t]*;?"
        val matches = Regex(
            "(?m)^[ \\t]*package[ \\t]+([A-Za-z_][A-Za-z0-9_.]*)$terminator[ \\t]*$",
        ).findAll(source.content).toList()
        return matches.singleOrNull()
    }

    private fun destinationFacadeEvidence(
        snapshot: ProjectSnapshot,
        jvmOwner: String,
    ): DestinationFacadeEvidence = runCatching {
        val classEntry = "${jvmOwner.replace('.', '/')}.class"
        val model = snapshot.buildModels.singleOrNull { it.providerId == "kotlin-jvm-projection-v1" }
            ?: return DestinationFacadeEvidence.UNAVAILABLE
        val entries = model.modules.flatMap { it.sourceSets }.flatMap { it.classpathEntries }.distinct()
        for (configured in entries) {
            val path = if (configured.isAbsolute) configured.normalize()
                else snapshot.workspace.root.resolve(configured).normalize()
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) ||
                !path.fileName.toString().endsWith(".jar", ignoreCase = true)) {
                return DestinationFacadeEvidence.UNAVAILABLE
            }
            JarFile(path.toFile(), false).use { jar ->
                if (jar.getJarEntry(classEntry) != null || jar.entries().asSequence().any { entry ->
                        !entry.isDirectory && entry.name.startsWith("META-INF/versions/") &&
                            entry.name.endsWith("/$classEntry")
                    }) return DestinationFacadeEvidence.PRESENT
            }
        }
        DestinationFacadeEvidence.ABSENT
    }.getOrElse { DestinationFacadeEvidence.UNAVAILABLE }

    private fun destinationCallableEvidence(
        snapshot: ProjectSnapshot,
        source: SourceFile,
        targetPackage: String,
        callableName: String,
    ): DestinationCallableEvidence {
        val parent = source.path.parent ?: return DestinationCallableEvidence.UNAVAILABLE
        val occupied = snapshot.trackedFiles.map { it.path.normalize() }.toSet()
        val probePath = (0..16).asSequence()
            .map { suffix ->
                val marker = if (suffix == 0) "" else "_$suffix"
                parent.resolve("__RefactorKitDestinationProbe$marker.kt").normalize()
            }
            .firstOrNull { it !in occupied } ?: return DestinationCallableEvidence.UNAVAILABLE
        val probe = SourceFile(
            probePath,
            "package __refactorkit_destination_probe\nimport $targetPackage.$callableName\n",
            "kotlin",
        )
        val unresolvedNames = (targetPackage.split('.') + callableName).toSet()
        return when (val result = compilerDiagnostics(snapshot.copy(files = snapshot.files + probe))) {
            is KotlinCompilerDiagnosticsResult.Available -> {
                val errors = result.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
                val absent = errors.size == 1 && errors.single().let { diagnostic ->
                    diagnostic.location?.path?.normalize() == probePath && unresolvedNames.any { unresolved ->
                        diagnostic.message == "Unresolved reference '$unresolved'."
                    }
                }
                when {
                    errors.isEmpty() -> DestinationCallableEvidence.PRESENT
                    absent -> DestinationCallableEvidence.ABSENT
                    else -> DestinationCallableEvidence.UNAVAILABLE
                }
            }
            is KotlinCompilerDiagnosticsResult.Error,
            is KotlinCompilerDiagnosticsResult.Refused -> DestinationCallableEvidence.UNAVAILABLE
        }
    }

    private fun outboundBindings(
        result: KotlinCompilerDiagnosticsResult.Available,
        path: Path,
        relocation: Map<String, String> = emptyMap(),
        projection: BindingProjection? = null,
    ): Map<String, Int>? {
        val normalizedPath = path.normalize()
        val bindings = mutableListOf<String>()
        for (usage in result.usages.filter { it.location.path.normalize() == normalizedPath }) {
            val identity = result.declarations[usage.targetId]?.jvmIdentity ?: return null
            val location = bindingLocation(usage.location, projection) ?: return null
            bindings += "$location:source:${relocation[identity] ?: identity}"
        }
        result.externalTypeUsages.filter { it.location.path.normalize() == normalizedPath }.forEach { usage ->
            if (usage.jvmBinaryName.isBlank()) return null
            val location = bindingLocation(usage.location, projection) ?: return null
            bindings += "$location:external-type:${usage.jvmBinaryName}"
        }
        result.externalCallableUsages.filter { it.location.path.normalize() == normalizedPath }.forEach { usage ->
            if (usage.jvmOwner.isBlank() || usage.callableName.isBlank() || usage.jvmDescriptor.isBlank()) return null
            val location = bindingLocation(usage.location, projection) ?: return null
            bindings += "$location:external-callable:${usage.jvmOwner}#${usage.callableName}${usage.jvmDescriptor}"
        }
        return bindings.groupingBy { it }.eachCount()
    }

    private fun bindingLocation(location: SourceLocation, projection: BindingProjection?): String? {
        val range = if (projection == null) location.range else projection.project(location.range) ?: return null
        return with(range) { "${start.line}:${start.character}-${end.line}:${end.character}" }
    }

    private fun dynamicRisk(snapshot: ProjectSnapshot, simpleNames: Set<String>): Boolean {
        val quotedNames = simpleNames.map { simpleName ->
            Regex("[\\\"'][^\\\"'\\n]*\\b${Regex.escape(simpleName)}\\b[^\\\"'\\n]*[\\\"']")
        }
        val frameworkAnnotation = Regex("@(Entity|Table|JsonTypeName|JsonSubTypes|Component|Service|Repository|Controller)\\b")
        return snapshot.files.any { file ->
            file.languageId in setOf("java", "kotlin") &&
                (quotedNames.any { it.containsMatchIn(file.content) } || frameworkAnnotation.containsMatchIn(file.content))
        }
    }

    private fun boundedTopLevelFunction(
        source: SourceFile,
        target: Symbol,
        evidence: KotlinCompilerDeclarationEvidence,
        oldPackage: String,
    ): Boolean {
        val fileName = source.path.fileName?.toString() ?: return false
        if (source.languageId != "kotlin" || !fileName.endsWith(".kt")) return false
        if (evidence.jvmOwner.substringBeforeLast('.', "") != oldPackage ||
            evidence.jvmOwner.substringAfterLast('.').isBlank() || '$' in evidence.jvmOwner ||
            evidence.jvmName != target.name || evidence.jvmDescriptor.isBlank() ||
            !evidence.jvmDescriptor.startsWith("(")) return false
        return evidence.isTopLevelFunction && evidence.isMovePlainFunction
    }

    private fun supportedFileDeclarations(
        catalogue: KotlinCompilerSymbolsResult.Available,
        source: SourceFile,
        target: Symbol,
        targetEvidence: KotlinCompilerDeclarationEvidence,
    ): List<FileDeclaration>? {
        val semantic = catalogue.index.symbols.filter { it.location.path.normalize() == source.path.normalize() }
            .mapNotNull { symbol ->
                val evidence = catalogue.declarations[symbol.id] ?: return@mapNotNull null
                if (evidence.isTopLevelDeclaration) symbol to evidence else null
            }
        val targetIsFunction = target.kind == Symbol.Kind.FUNCTION
        if (targetEvidence.sourceTopLevelDeclarationCount <= 0 ||
            semantic.size != targetEvidence.sourceTopLevelDeclarationCount ||
            semantic.none { it.first.id == target.id } ||
            semantic.filterNot { it.first.id == target.id }.any { (symbol, evidence) ->
                evidence.visibility != KotlinDeclarationVisibility.PRIVATE &&
                    !(targetIsFunction.not() && evidence.visibility == KotlinDeclarationVisibility.PUBLIC &&
                        symbol.kind in TYPE_KINDS)
            }) return null
        return semantic.map { FileDeclaration(it.first, it.second) }
    }

    private fun offsetEdit(content: String, start: Int, end: Int, replacement: String) = TextEdit(
        SourceRange(TextEdits.positionForOffset(content, start), TextEdits.positionForOffset(content, end)), replacement,
    )

    private fun ProjectSnapshot.file(path: Path): SourceFile? = files.singleOrNull { it.path.normalize() == path.normalize() }

    private fun javaDiagnostics(result: JdtJavaSemanticAnalysisResult) = result.warnings.map { warning ->
        Diagnostic(
            message = warning.message, severity = Diagnostic.Severity.ERROR,
            location = SourceLocation(warning.path, warning.sourceRange), code = "java.jdt.${warning.problemId}",
            evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION,
        )
    }

    private fun introducedDiagnostics(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val baseline = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticKey).toSet()
        return after.filter { it.severity == Diagnostic.Severity.ERROR && diagnosticKey(it) !in baseline }
    }

    private fun diagnosticKey(diagnostic: Diagnostic) = listOf(
        diagnostic.code.orEmpty(), diagnostic.location?.path?.toString().orEmpty(),
        diagnostic.location?.range?.toString().orEmpty(), diagnostic.message,
    )

    private fun failure(code: String, message: String) = Diagnostic(
        message = message, severity = Diagnostic.Severity.ERROR, code = code,
        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
    )

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = "moveDeclaration", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = message, affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(), diagnosticsAfterPreview = diagnostics, warnings = listOf(message),
        riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = code,
    )

    private class BindingProjection(
        private val beforeContent: String,
        private val edit: TextEdit,
    ) {
        private val editStart = TextEdits.offsetOf(beforeContent, edit.range.start)
        private val editEnd = TextEdits.offsetOf(beforeContent, edit.range.end)
        private val delta = edit.newText.length - (editEnd - editStart)
        private val afterContent = TextEdits.apply(beforeContent, listOf(edit))

        fun project(range: SourceRange): SourceRange? = runCatching {
            val start = TextEdits.offsetOf(beforeContent, range.start)
            val end = TextEdits.offsetOf(beforeContent, range.end)
            val projected = when {
                end <= editStart -> start to end
                start >= editEnd -> start + delta to end + delta
                else -> return null
            }
            TextEdits.rangeForOffset(afterContent, projected.first, projected.second - projected.first)
        }.getOrNull()
    }

    private data class FileDeclaration(
        val symbol: Symbol,
        val evidence: KotlinCompilerDeclarationEvidence,
    )

    private data class ConsumerUse(
        val oldIdentity: String,
        val location: SourceLocation,
    )

    private enum class DestinationCallableEvidence { ABSENT, PRESENT, UNAVAILABLE }
    private enum class DestinationFacadeEvidence { ABSENT, PRESENT, UNAVAILABLE }

    private sealed interface MixedEvidence {
        data class Available(
            val kotlin: KotlinCompilerDiagnosticsResult.Available,
            val java: JdtJavaSemanticAnalysisResult,
        ) : MixedEvidence
        data class Refused(val code: String, val message: String) : MixedEvidence
    }

    companion object {
        private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*){0,127}")
        private val TYPE_KINDS = setOf(
            Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.OBJECT, Symbol.Kind.ENUM, Symbol.Kind.ANNOTATION,
        )
        private val KEYWORDS = setOf(
            "class", "object", "interface", "fun", "val", "var", "when", "is", "in", "as", "private",
            "public", "internal", "protected", "return", "package", "import", "typealias",
        )
    }
}
