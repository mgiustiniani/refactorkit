package org.refactorkit.java

import org.eclipse.jdt.core.dom.Modifier
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Generates a [PatchPlan] that renames a Java method, field, or constructor and
 * updates all call sites / reference sites across the project.
 *
 * Reference search is scoped to files that are likely to reference the declaring
 * type (via import, star-import, same-package, or FQN usage), eliminating false
 * positives from same-named members in unrelated classes.
 *
 * Limitations (MVP):
 * - Reflection, Spring @EventListener parameter types, Jackson annotations,
 *   and annotation-processor-generated code are NOT updated.
 * - Unsigned overloaded methods: all overloads with the same simple name are
 *   renamed and a warning is emitted.
 * - Signed method selectors use JDT binding evidence for the currently proven
 *   exact-overload slice and refuse when semantic evidence is not clean.
 * - Unambiguous field selectors use exact JDT declaration/reference ranges when
 *   evidence is clean; otherwise the documented lexical fallback remains visible.
 * - Constructor rename follows class rename (not a standalone member rename).
 */
class JavaRenameMemberPlanner(private val adapter: JavaLanguageAdapter) {

    fun preview(
        snapshot: ProjectSnapshot,
        symbolFqnWithMember: String,
        newMemberName: String,
    ): PatchPlan {
        // ── parse symbolId ───────────────────────────────────────────────────
        val hashIdx = symbolFqnWithMember.indexOf('#')
        if (hashIdx < 0) {
            return refused(snapshot, "renameMember", "Symbol must be in the form <FQN>#<member> — got: $symbolFqnWithMember")
        }
        val ownerFqn = symbolFqnWithMember.substring(0, hashIdx)
        val memberSelector = symbolFqnWithMember.substring(hashIdx + 1)
        val oldMemberName = memberSelector.substringBefore('(')

        if (oldMemberName == "<init>") {
            return refused(snapshot, "renameMember", "Constructor rename follows class rename. Use renameClass instead.")
        }

        if (!isValidJavaIdentifier(newMemberName)) {
            return refused(snapshot, "renameMember", "Invalid Java identifier: $newMemberName")
        }
        if (oldMemberName == newMemberName) {
            return refused(snapshot, "renameMember", "Old and new names are the same: $oldMemberName")
        }

        // ── resolve owner type ───────────────────────────────────────────────
        val index = adapter.buildSymbols(snapshot)
        val ownerSymbol = index.symbols.find { it.id.value == ownerFqn && it.kind in TYPE_KINDS }
            ?: return refused(snapshot, "renameMember", "Owner type not found: $ownerFqn")
        val ownerFile = snapshot.files.find { it.path == ownerSymbol.location.path }
            ?: return refused(snapshot, "renameMember", "Owner declaration file not found: ${ownerSymbol.location.path}")
        JavaGeneratedSourcePolicy.reason(ownerFile)?.let { reason ->
            return refused(snapshot, "renameMember", "Generated source cannot be rewritten: ${ownerFile.path} ($reason)")
        }

        val hasKotlinSources = snapshot.files.any { it.languageId == "kotlin" || it.path.toString().endsWith(".kt") }

        // An exact signed selector is resolved by JDT, including declarations
        // which a line-oriented member prescan cannot represent.
        if (memberSelector.contains('(')) {
            return previewSignedJdtRename(snapshot, ownerFqn, memberSelector, oldMemberName, newMemberName, hasKotlinSources)
        }
        if (hasKotlinSources) return unprovedKotlinConsumers(snapshot)

        if (ownerSymbol.kind == Symbol.Kind.RECORD) {
            previewJdtRecordComponentRename(snapshot, ownerFqn, oldMemberName, newMemberName)?.let { return it }
        }

        // ── find member(s) ───────────────────────────────────────────────────
        val members = index.symbols.filter { sym ->
            sym.name == oldMemberName &&
                sym.location.path == ownerSymbol.location.path &&
                sym.kind in MEMBER_KINDS
        }
        if (members.isEmpty()) {
            return refused(snapshot, "renameMember", "Member '$oldMemberName' not found in $ownerFqn")
        }

        val frameworkReferenceEvidence = if (members.singleOrNull()?.kind == Symbol.Kind.FIELD) {
            JavaFrameworkReferenceAnalyzer().analyzeEntityPropertyRename(
                snapshot,
                ownerFqn,
                ownerSymbol.location.path,
                oldMemberName,
                newMemberName,
            )
        } else null

        if (members.singleOrNull()?.kind == Symbol.Kind.FIELD) {
            if (index.symbols.any { symbol ->
                    symbol.id.value.substringBeforeLast('#', "") == ownerFqn &&
                        symbol.kind == Symbol.Kind.FIELD &&
                        symbol.name == newMemberName
                }
            ) {
                return refused(snapshot, "renameMember", "Field target already exists: $ownerFqn#$newMemberName")
            }
            previewJdtFieldRename(
                snapshot, ownerFqn, oldMemberName, newMemberName, frameworkReferenceEvidence,
            )?.let { return it }
        }

        val warnings = mutableListOf<String>()
        if (members.size > 1) {
            warnings += "Multiple overloads of '$oldMemberName' detected in $ownerFqn. All overloads will be renamed."
        }
        warnings += jdtEvidenceWarnings(snapshot, ownerFqn, oldMemberName)

        // ── build edits ──────────────────────────────────────────────────────
        val ownerPkg = JavaPackageUtil.packageOf(ownerFqn)
        val ownerSimple = JavaPackageUtil.simpleName(ownerFqn)
        val ownerPkgStar = "import ${ownerPkg}.*;"

        val edits = mutableListOf<FileEdit>()
        val affectedPaths: MutableSet<Path> = mutableSetOf()

        for (file in snapshot.files.filter { it.languageId == "java" }) {
            val filePkg = JavaPackageUtil.extractPackage(file.content)
            val inScope = file.path == ownerSymbol.location.path ||
                file.content.contains("import $ownerFqn;") ||
                (ownerPkg.isNotEmpty() && file.content.contains(ownerPkgStar)) ||
                (ownerPkg.isNotEmpty() && filePkg == ownerPkg) ||
                file.content.contains(ownerFqn) ||
                file.content.contains(ownerSimple) ||
                frameworkReferenceEvidence?.editsByPath?.containsKey(file.path) == true

            if (!inScope) continue

            val textEdits = frameworkReferenceEvidence?.editsByPath?.get(file.path).orEmpty().toMutableList()
            val covered = mutableSetOf<Int>()

            // Replace member name occurrences, excluding the class name itself
            for (range in JavaLexer.findOccurrences(file.content, oldMemberName)) {
                if (range.first in covered) continue
                textEdits += TextEdit(
                    range = TextEdits.rangeForOffset(file.content, range.first, oldMemberName.length),
                    newText = newMemberName,
                )
                for (off in range) covered += off
            }

            if (textEdits.isNotEmpty()) {
                val sorted = textEdits.sortedWith(
                    compareBy({ it.range.start.line }, { it.range.start.character }),
                )
                edits += FileEdit.Modify(file.path, sorted)
                affectedPaths.add(file.path)
            }
        }

        if (edits.isEmpty()) {
            return refused(snapshot, "renameMember", "No occurrences of '$oldMemberName' found in scope of $ownerFqn")
        }

        val memberKind = members.first().kind.name.lowercase()
        warnings += "Reflection, Spring event/listener names, Jackson property names, and annotation-processor output are NOT updated. Review manually."
        frameworkReferenceEvidence?.referenceAnalysis?.warnings?.let(warnings::addAll)

        return PatchPlan(
            operation = "renameMember",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.88,
            requiresUserApproval = true,
            summary = "Rename $memberKind '$oldMemberName' \u2192 '$newMemberName' in $ownerFqn. ${affectedPaths.size} file(s) affected.",
            affectedFiles = affectedPaths,
            workspaceEdit = WorkspaceEdit(edits),
            warnings = warnings,
            riskLevel = when {
                frameworkReferenceEvidence != null -> RiskLevel.HIGH
                members.size > 1 -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            },
            evidence = RefactoringEvidence.LEXICAL_FALLBACK,
            referenceAnalysis = frameworkReferenceEvidence?.referenceAnalysis,
            managedWriteEligible = frameworkReferenceEvidence == null,
        )
    }

    private fun refused(snapshot: ProjectSnapshot, operation: String, reason: String) = PatchPlan(
        operation = operation,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
    )

    private fun unprovedKotlinConsumers(snapshot: ProjectSnapshot): PatchPlan = refused(
        snapshot,
        "renameMember",
        "Java member rename requires compiler-proven closure of Kotlin consumers in this workspace",
    ).copy(
        refusalCode = "java.renameMember.kotlinConsumerClosureUnavailable",
        managedWriteEligible = false,
    )

    private fun isValidJavaIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!name[0].isLetter() && name[0] != '_' && name[0] != '$') return false
        return name.all { JavaLexer.isIdentChar(it) }
    }

    private fun previewJdtFieldRename(
        snapshot: ProjectSnapshot,
        ownerFqn: String,
        oldMemberName: String,
        newMemberName: String,
        frameworkReferenceEvidence: JavaFrameworkFieldRenameEvidence?,
    ): PatchPlan? {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        if (analysis.warnings.isNotEmpty()) return null
        val candidate = analysis.symbols.singleOrNull { symbol ->
            symbol.ownerQualifiedName == ownerFqn &&
                symbol.simpleName == oldMemberName &&
                symbol.kind == JdtJavaSemanticSymbolKind.FIELD
        } ?: return null
        if (analysis.symbols.any { symbol ->
                symbol.ownerQualifiedName == ownerFqn &&
                    symbol.simpleName == newMemberName &&
                    symbol.kind == JdtJavaSemanticSymbolKind.FIELD
            }
        ) {
            return refused(snapshot, "renameMember", "Field target already exists: $ownerFqn#$newMemberName")
        }
        val bindingKey = candidate.bindingKey ?: return null
        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        fun addEdit(path: Path, range: org.refactorkit.core.SourceRange) {
            editsByPath.getOrPut(path) { mutableListOf() } += TextEdit(range, newMemberName)
        }
        addEdit(candidate.path, candidate.sourceRange)
        analysis.references
            .filter { reference ->
                reference.bindingKey == bindingKey && reference.symbolKind == JdtJavaSemanticSymbolKind.FIELD
            }
            .forEach { reference -> addEdit(reference.path, reference.sourceRange) }
        frameworkReferenceEvidence?.editsByPath?.forEach { (path, textEdits) ->
            textEdits.forEach { edit -> editsByPath.getOrPut(path) { mutableListOf() } += edit }
        }
        val edits = editsByPath.map { (path, textEdits) ->
            FileEdit.Modify(
                path,
                textEdits.distinctBy { "${it.range.start.line}:${it.range.start.character}:${it.range.end.line}:${it.range.end.character}" }
                    .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character })),
            )
        }
        val workspaceEdit = WorkspaceEdit(edits)
        val managedFramework = frameworkReferenceEvidence?.let { framework ->
            JavaManagedJpqlRename.prove(
                snapshot, candidate.path, ownerFqn, oldMemberName, newMemberName, workspaceEdit, framework.referenceAnalysis,
            )
        }
        val referenceAnalysis = managedFramework?.references ?: frameworkReferenceEvidence?.referenceAnalysis
        val warnings = buildList {
            add("JDT binding selected exact field $ownerFqn#$oldMemberName; edits were generated from JDT declaration/reference ranges.")
            if (managedFramework != null) {
                add("Review consumers outside the captured Maven workspace before applying this field rename.")
            } else {
                add("Reflection, serialization names, framework strings, and annotation-processor output are NOT updated. Review manually.")
            }
            referenceAnalysis?.warnings?.let(::addAll)
        }
        return PatchPlan(
            operation = "renameMember",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.95,
            requiresUserApproval = true,
            summary = "Rename field '$oldMemberName' → '$newMemberName' in $ownerFqn using JDT binding evidence. ${editsByPath.size} file(s) affected.",
            affectedFiles = editsByPath.keys,
            workspaceEdit = workspaceEdit,
            warnings = warnings,
            riskLevel = if (frameworkReferenceEvidence == null) RiskLevel.LOW else RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
            referenceAnalysis = referenceAnalysis,
            managedWriteEligible = frameworkReferenceEvidence == null || managedFramework != null,
            authorityLease = managedFramework?.lease,
        )
    }

    private fun previewJdtRecordComponentRename(
        snapshot: ProjectSnapshot,
        ownerFqn: String,
        oldMemberName: String,
        newMemberName: String,
    ): PatchPlan? {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        if (analysis.warnings.isNotEmpty()) {
            return refused(
                snapshot,
                "renameMember",
                "Record member rename for $ownerFqn requires clean JDT semantic evidence; ${analysis.warnings.size} parse/classpath warning(s) were reported.",
            )
        }
        val candidates = analysis.symbols.filter { symbol ->
            symbol.ownerQualifiedName == ownerFqn &&
                symbol.simpleName == oldMemberName &&
                symbol.kind == JdtJavaSemanticSymbolKind.RECORD_COMPONENT
        }
        if (candidates.isEmpty()) return null
        val component = candidates.singleOrNull() ?: return refused(
            snapshot,
            "renameMember",
            "Record component selector $ownerFqn#$oldMemberName did not resolve to exactly one JDT component; found ${candidates.size}.",
        )
        val componentBindingKey = component.bindingKey?.takeUnless(String::isBlank)
            ?: return refused(snapshot, "renameMember", "Record component $ownerFqn#$oldMemberName has no JDT binding key.")
        val componentIndex = component.recordComponentIndex
            ?: return refused(snapshot, "renameMember", "Record component $ownerFqn#$oldMemberName has no source-order identity.")
        if (component.recovered || component.evidence != JdtJavaSemanticEvidence.JDT_BINDING) {
            return refused(snapshot, "renameMember", "Record component $ownerFqn#$oldMemberName does not have an unrecovered JDT binding.")
        }
        val declarationFile = snapshot.files.singleOrNull { it.path == component.path }
            ?: return refused(snapshot, "renameMember", "Record component declaration file is missing or ambiguous: ${component.path}")
        JavaGeneratedSourcePolicy.reason(declarationFile)?.let { reason ->
            return refused(snapshot, "renameMember", "Generated source cannot be rewritten: ${declarationFile.path} ($reason)")
        }
        val targetConflict = analysis.symbols.any { symbol ->
            symbol.ownerQualifiedName == ownerFqn && symbol.simpleName == newMemberName && when (symbol.kind) {
                JdtJavaSemanticSymbolKind.RECORD_COMPONENT,
                JdtJavaSemanticSymbolKind.FIELD -> true
                JdtJavaSemanticSymbolKind.METHOD -> symbol.memberSignature == "$newMemberName()"
                else -> false
            }
        }
        if (targetConflict) {
            return refused(snapshot, "renameMember", "Record component target already exists: $ownerFqn#$newMemberName")
        }

        val accessors = analysis.symbols.filter { symbol ->
            symbol.ownerQualifiedName == ownerFqn &&
                symbol.simpleName == oldMemberName &&
                symbol.kind == JdtJavaSemanticSymbolKind.METHOD &&
                symbol.memberSignature == "$oldMemberName()"
        }
        val accessor = accessors.singleOrNull() ?: return refused(
            snapshot,
            "renameMember",
            "Record component accessor $ownerFqn#$oldMemberName() did not resolve to exactly one JDT method; found ${accessors.size}.",
        )
        val accessorBindingKey = accessor.bindingKey?.takeUnless(String::isBlank)
            ?: return refused(snapshot, "renameMember", "Record component accessor $ownerFqn#$oldMemberName() has no JDT binding key.")
        if (accessor.recovered) {
            return refused(snapshot, "renameMember", "Record component accessor $ownerFqn#$oldMemberName() has a recovered JDT binding.")
        }

        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        fun addEdit(path: Path, range: org.refactorkit.core.SourceRange) {
            editsByPath.getOrPut(path) { mutableListOf() } += TextEdit(range, newMemberName)
        }
        addEdit(component.path, component.sourceRange)
        analysis.references.filter { reference ->
            reference.bindingKey == componentBindingKey && reference.symbolKind == JdtJavaSemanticSymbolKind.RECORD_COMPONENT
        }.forEach { reference -> addEdit(reference.path, reference.sourceRange) }
        addEdit(accessor.path, accessor.sourceRange)
        analysis.references.filter { reference ->
            reference.bindingKey == accessorBindingKey && reference.symbolKind == JdtJavaSemanticSymbolKind.METHOD
        }.forEach { reference -> addEdit(reference.path, reference.sourceRange) }

        val canonicalConstructors = analysis.methods.filter { method ->
            method.constructor && method.canonicalConstructor &&
                method.qualifiedName.startsWith("$ownerFqn#<init>(") && method.path == component.path
        }
        val canonicalKeys = canonicalConstructors.mapTo(mutableSetOf()) { it.bindingKey }
        val componentParameters = analysis.parameters.filter { parameter ->
            parameter.methodBindingKey in canonicalKeys && parameter.index == componentIndex
        }
        if (canonicalConstructors.isNotEmpty() && componentParameters.size != canonicalConstructors.size) {
            return refused(
                snapshot,
                "renameMember",
                "Record component $ownerFqn#$oldMemberName is missing a canonical-constructor parameter binding.",
            )
        }
        componentParameters.forEach { parameter ->
            addEdit(parameter.path, parameter.sourceRange)
            analysis.bindingUses.filter { use -> use.bindingKey == parameter.parameterBindingKey && use.path == parameter.path }
                .forEach { use -> addEdit(use.path, use.sourceRange) }
        }

        val edits = editsByPath.map { (path, textEdits) ->
            FileEdit.Modify(
                path,
                textEdits.distinctBy { "${it.range.start.line}:${it.range.start.character}:${it.range.end.line}:${it.range.end.character}" }
                    .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character })),
            )
        }
        return PatchPlan(
            operation = "renameMember",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.95,
            requiresUserApproval = true,
            summary = "Rename record component '$oldMemberName' → '$newMemberName' in $ownerFqn, including its canonical constructor and accessor. ${editsByPath.size} file(s) affected.",
            affectedFiles = editsByPath.keys,
            workspaceEdit = WorkspaceEdit(edits),
            warnings = listOf(
                "JDT binding selected exact record component $ownerFqn#$oldMemberName and coordinated its declaration, canonical-constructor parameter, accessor, and binding-matched references.",
                "Record component rename changes the public accessor and canonical constructor API. Reflection, serialization names, framework strings, and annotation-processor output are NOT updated; review manually.",
            ),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun previewSignedJdtRename(
        snapshot: ProjectSnapshot,
        ownerFqn: String,
        memberSelector: String,
        oldMemberName: String,
        newMemberName: String,
        hasKotlinSources: Boolean,
    ): PatchPlan {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        if (analysis.warnings.isNotEmpty()) {
            return refused(
                snapshot,
                "renameMember",
                "Signed member rename for $ownerFqn#$memberSelector requires clean JDT semantic evidence; ${analysis.warnings.size} parse/classpath warning(s) were reported.",
            )
        }
        val candidates = analysis.symbols.filter { symbol ->
            symbol.ownerQualifiedName == ownerFqn &&
                symbol.memberSignature == memberSelector &&
                symbol.kind == JdtJavaSemanticSymbolKind.METHOD
        }
        val candidate = candidates.singleOrNull() ?: return refused(
            snapshot,
            "renameMember",
            "Signed member selector $ownerFqn#$memberSelector did not resolve to exactly one JDT method candidate; found ${candidates.size}.",
        )
        val bindingKey = candidate.bindingKey ?: return refused(
            snapshot,
            "renameMember",
            "Signed member selector $ownerFqn#$memberSelector has no JDT binding key; exact overload rename refused.",
        )
        val familyBindingKeys = linkedSetOf(bindingKey)
        var expanded: Boolean
        do {
            expanded = false
            analysis.overrideRelations.forEach { relation ->
                when {
                    relation.overridingBindingKey in familyBindingKeys ->
                        expanded = familyBindingKeys.add(relation.overriddenBindingKey) || expanded
                    relation.overriddenBindingKey in familyBindingKeys ->
                        expanded = familyBindingKeys.add(relation.overridingBindingKey) || expanded
                }
            }
        } while (expanded)
        val familySymbols = analysis.symbols.filter { symbol ->
            symbol.kind == JdtJavaSemanticSymbolKind.METHOD && symbol.bindingKey in familyBindingKeys
        }
        if (familySymbols.mapNotNull { it.bindingKey }.toSet() != familyBindingKeys) {
            return refused(
                snapshot,
                "renameMember",
                "Signed member selector $ownerFqn#$memberSelector belongs to an override family containing declarations outside the scanned source workspace; source-only propagation is unsafe.",
            )
        }

        if (hasKotlinSources) {
            // Java access rules close the exact private method within its declaring Java source.
            val method = analysis.methods.singleOrNull { it.bindingKey == bindingKey }
            if (familyBindingKeys.size != 1 || candidate.recovered || method == null ||
                method.evidence != JdtJavaSemanticEvidence.JDT_BINDING || !Modifier.isPrivate(method.modifiers)
            ) {
                return unprovedKotlinConsumers(snapshot)
            }
        }

        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        fun addEdit(path: Path, range: org.refactorkit.core.SourceRange) {
            editsByPath.getOrPut(path) { mutableListOf() } += TextEdit(range, newMemberName)
        }
        familySymbols.forEach { symbol -> addEdit(symbol.path, symbol.sourceRange) }
        analysis.references
            .filter { it.bindingKey in familyBindingKeys && it.symbolKind == JdtJavaSemanticSymbolKind.METHOD }
            .forEach { reference -> addEdit(reference.path, reference.sourceRange) }

        val edits = editsByPath.map { (path, textEdits) ->
            FileEdit.Modify(
                path,
                textEdits.distinctBy { "${it.range.start.line}:${it.range.start.character}:${it.range.end.line}:${it.range.end.character}" }
                    .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character })),
            )
        }
        val warnings = buildList {
            add("JDT binding selected exact member signature $ownerFqn#$memberSelector; edits were generated from JDT declaration/reference ranges.")
            if (familySymbols.size > 1) {
                add("Override-aware propagation selected ${familySymbols.size} source declarations and their binding-matched call sites across the inheritance hierarchy.")
            }
            add("Reflection, Spring event/listener names, Jackson property names, and annotation-processor output are NOT updated. Review manually.")
        }
        return PatchPlan(
            operation = "renameMember",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = if (familySymbols.size > 1) 0.91 else 0.93,
            requiresUserApproval = true,
            summary = if (familySymbols.size > 1) {
                "Rename override family '$memberSelector' \u2192 '$newMemberName' from $ownerFqn using JDT binding evidence. ${familySymbols.size} declaration(s), ${editsByPath.size} file(s) affected."
            } else {
                "Rename method '$memberSelector' \u2192 '$newMemberName' in $ownerFqn using JDT binding evidence. ${editsByPath.size} file(s) affected."
            },
            affectedFiles = editsByPath.keys,
            workspaceEdit = WorkspaceEdit(edits),
            warnings = warnings,
            riskLevel = if (familySymbols.size > 1) RiskLevel.MEDIUM else RiskLevel.LOW,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun jdtEvidenceWarnings(
        snapshot: ProjectSnapshot,
        ownerFqn: String,
        oldMemberName: String,
    ): List<String> {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val candidates = analysis.symbols.filter { symbol ->
            symbol.ownerQualifiedName == ownerFqn &&
                symbol.simpleName == oldMemberName &&
                symbol.kind in JDT_MEMBER_KINDS
        }
        val warnings = mutableListOf<String>()
        if (candidates.isNotEmpty()) {
            val candidateKeys = candidates.mapNotNull { it.bindingKey }.toSet()
            val matchedReferences = analysis.references.count { it.bindingKey != null && it.bindingKey in candidateKeys }
            val signatures = candidates.mapNotNull { it.memberSignature }.distinct().sorted()
            warnings += "Experimental JDT evidence matched ${candidates.size} member candidate(s) and $matchedReferences reference(s) for $ownerFqn#$oldMemberName; lexical planner still determines preview edits."
            if (signatures.size > 1) {
                warnings += "Experimental JDT evidence distinguished overload signatures for $ownerFqn#$oldMemberName: ${signatures.joinToString(", ")}."
            }
        }
        if (analysis.warnings.isNotEmpty()) {
            warnings += "Experimental JDT semantic analysis reported ${analysis.warnings.size} parse/classpath warning(s); review preview carefully before apply."
        }
        return warnings
    }

    companion object {
        private val TYPE_KINDS = setOf(Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.ENUM, Symbol.Kind.RECORD, Symbol.Kind.ANNOTATION)
        private val MEMBER_KINDS = setOf(Symbol.Kind.METHOD, Symbol.Kind.FIELD, Symbol.Kind.RECORD_COMPONENT, Symbol.Kind.CONSTRUCTOR)
        private val JDT_MEMBER_KINDS = setOf(
            JdtJavaSemanticSymbolKind.METHOD,
            JdtJavaSemanticSymbolKind.FIELD,
            JdtJavaSemanticSymbolKind.RECORD_COMPONENT,
            JdtJavaSemanticSymbolKind.CONSTRUCTOR,
        )
    }
}
