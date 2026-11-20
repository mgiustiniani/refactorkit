package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.PrimitiveType
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/** Pure support policy for the first deliberately narrow Java field-type repair cell. */
internal object JavaTypeRepairPolicy {
    fun supportsPrimitiveFieldChange(oldType: String, newType: String): Boolean =
        oldType == "int" && newType == "long"
}

/** JDT-bound type repair for one private mutable primitive-int field in saved Java source. */
class JavaTypeRepairPlanner(private val adapter: JavaLanguageAdapter) {
    fun previewSelected(
        snapshot: ProjectSnapshot,
        path: Path,
        position: SourcePosition,
        sdkHome: Path,
        newType: String,
        scan: () -> ProjectSnapshot,
    ): PatchPlan {
        val context = when (val acquired = JavaBoundContext.acquire(
            JavaProjectContextRequest(snapshot.hash, sdkHome), scan,
        )) {
            is JavaBoundContextResolution.Available -> acquired.context
            is JavaBoundContextResolution.Refused -> return refused(snapshot, acquired.code, acquired.message)
        }
        val selection = try {
            JavaMutationSelectionService.select(context, path, position)
        } catch (failure: JavaContextAuthorityException) {
            return refused(snapshot, failure.code, failure.message.orEmpty())
        }
        if (selection.declaration.path.normalize() != path.normalize()) {
            return refused(snapshot, "java.typeRepair.declarationSelectionRequired",
                "Select the field declaration in its saved source document.")
        }
        try {
            JavaMutationSelectionService.validate(
                context, selection, "changeType", selection.symbolId,
                mapOf("file" to path.toString(), "newType" to newType),
            )
        } catch (failure: JavaContextAuthorityException) {
            return refused(snapshot, failure.code, failure.message.orEmpty())
        }
        context.verifyAfterQuery(scan)?.let { return refused(snapshot, it.code, it.message) }
        // Keep the authority attached to the snapshot through the JDT planner. The selected
        // binding and the subsequent rewrite must be evaluated under the same captured
        // source-set/JDK context.
        val plan = preview(context.snapshot, selection.symbolId, selection.declaration.path, selection.bindingKey, newType)
        context.verifyAfterQuery(scan)?.let { return refused(snapshot, it.code, it.message) }
        return plan
    }

    fun preview(snapshot: ProjectSnapshot, symbolId: String, path: Path, bindingKey: String, newType: String): PatchPlan {
        val operation = "changeType"
        val file = snapshot.files.singleOrNull { it.path.normalize() == path.normalize() }
            ?: return refused(snapshot, "java.typeRepair.selectionUnavailable", "The selected Java declaration is absent or ambiguous in the saved snapshot.")
        if (file.languageId != "java") return refused(snapshot, "java.typeRepair.languageUnsupported", "Type repair currently supports saved Java source only.")
        JavaGeneratedSourcePolicy.reason(file)?.let {
            return refused(snapshot, "java.typeRepair.generatedSource", "Generated source cannot be repaired: $it")
        }
        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.typeRepair.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected source set.")
        }
        val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, file.path) { unit, _ ->
            val declarations = mutableListOf<VariableDeclarationFragment>()
            unit.accept(object : ASTVisitor() {
                override fun visit(node: VariableDeclarationFragment): Boolean {
                    val binding = node.resolveBinding()
                    if (node.name.identifier == symbolId.substringAfterLast('#') && node.parent is FieldDeclaration &&
                        binding?.isField == true && !binding.isRecovered &&
                        "${binding.declaringClass?.qualifiedName}#${node.name.identifier}" == symbolId) declarations += node
                    return true
                }
            })
            val selected = declarations.singleOrNull()
                ?: return@withBoundCompilationUnit refused(snapshot, "java.typeRepair.selectionAmbiguous", "The selected JDT field must have exactly one saved source declaration.")
            val selectedBinding = selected.resolveBinding()
            if (selectedBinding?.variableDeclaration?.key != bindingKey) {
                return@withBoundCompilationUnit refused(snapshot, "java.typeRepair.bindingMismatch", "The selected field binding differs from the exact IDE selector.")
            }
            previewBound(snapshot, file.path, selected, newType, before)
        }
        return when (resolution) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> refused(
                snapshot,
                "java.typeRepair.sourceSetUnavailable",
                "Type repair requires one available, non-generated effective Java source set.",
            )
        }
    }

    private fun previewBound(
        snapshot: ProjectSnapshot,
        path: Path,
        fragment: VariableDeclarationFragment?,
        requestedType: String,
        before: List<Diagnostic>,
    ): PatchPlan {
        val file = snapshot.files.single { it.path.normalize() == path.normalize() }
        val field = fragment?.parent as? FieldDeclaration
            ?: return refused(snapshot, "java.typeRepair.fieldUnavailable", "The selected declaration is not one exact JDT field.")
        val owner = field.parent as? TypeDeclaration
            ?: return refused(snapshot, "java.typeRepair.ownerUnsupported", "Only a field on an ordinary top-level class is supported.")
        val binding = fragment.resolveBinding()
            ?: return refused(snapshot, "java.typeRepair.bindingIncomplete", "The selected field has no complete JDT variable binding.")
        if (owner.parent !== field.root || owner.isInterface || field.fragments().size != 1 ||
            field.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 ||
            owner.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 ||
            binding.isRecovered || binding.variableDeclaration.key.isNullOrBlank() || !binding.isField) {
            return refused(snapshot, "java.typeRepair.bindingIncomplete", "The selected field or its owner has incomplete JDT structure or binding.")
        }
        if (owner.superclassType != null || owner.superInterfaceTypes().isNotEmpty() || owner.typeParameters().isNotEmpty()) {
            return refused(snapshot, "java.typeRepair.ownerUnsupported", "The selected field owner must be a top-level class without a declared hierarchy or type parameters.")
        }
        if (!Modifier.isPrivate(binding.modifiers)) {
            return refused(snapshot, "java.typeRepair.fieldNotPrivate", "Only a private field is supported by this bounded type-repair operation.")
        }
        val primitive = field.type as? PrimitiveType
            ?: return refused(snapshot, "java.typeRepair.fieldShapeUnsupported", "Only a primitive-int field is supported.")
        if (Modifier.isStatic(binding.modifiers) || Modifier.isFinal(binding.modifiers) ||
            field.modifiers().any { it is org.eclipse.jdt.core.dom.Annotation } || primitive.annotations().isNotEmpty()) {
            return refused(snapshot, "java.typeRepair.fieldShapeUnsupported", "Static, final, and annotated fields are outside the bounded repair shape.")
        }
        val oldType = primitive.primitiveTypeCode.toString()
        if (!JavaTypeRepairPolicy.supportsPrimitiveFieldChange(oldType, requestedType.trim())) {
            return refused(snapshot, "java.typeRepair.typePairUnsupported", "The supported primitive field type change is int to long.")
        }

        val declarationKey = binding.variableDeclaration.key
        var incompleteSameNameBinding = false
        unitReferences(field.root as org.eclipse.jdt.core.dom.CompilationUnit, fragment) { reference ->
            if (reference.identifier != fragment.name.identifier) return@unitReferences
            val resolved = reference.resolveBinding()
            if (resolved == null || resolved.isRecovered || resolved.key.isNullOrBlank()) incompleteSameNameBinding = true
            else if (resolved is org.eclipse.jdt.core.dom.IVariableBinding &&
                resolved.variableDeclaration.key == declarationKey && resolved.type.isRecovered) incompleteSameNameBinding = true
        }
        if (incompleteSameNameBinding) {
            return refused(snapshot, "java.typeRepair.bindingIncomplete", "A same-named source reference has incomplete JDT binding evidence.")
        }

        val typeRange = TextEdits.rangeForOffset(file.content, primitive.startPosition, primitive.length)
        val edit = FileEdit.Modify(path, listOf(TextEdit(typeRange, requestedType.trim())))
        val staged = snapshot.copy(files = snapshot.files.map { source ->
            if (source.path.normalize() == path.normalize()) source.copy(content = TextEdits.apply(source.content, edit.textEdits)) else source
        })
        val stagedBinding = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(staged, path) { unit, _ ->
            val candidates = mutableListOf<VariableDeclarationFragment>()
            unit.accept(object : ASTVisitor() {
                override fun visit(node: VariableDeclarationFragment): Boolean {
                    val candidate = node.resolveBinding()
                    if (node.name.identifier == fragment.name.identifier && node.parent is FieldDeclaration &&
                        candidate?.isField == true && !candidate.isRecovered &&
                        "${candidate.declaringClass?.qualifiedName}#${node.name.identifier}" ==
                        "${binding.declaringClass?.qualifiedName}#${fragment.name.identifier}") candidates += node
                    return true
                }
            })
            val selected = candidates.singleOrNull() ?: return@withBoundCompilationUnit false
            val selectedField = selected.parent as? FieldDeclaration ?: return@withBoundCompilationUnit false
            val selectedBinding = selected.resolveBinding() ?: return@withBoundCompilationUnit false
            !selectedBinding.isRecovered && selectedBinding.variableDeclaration.key?.isNotBlank() == true &&
                (selectedField.type as? PrimitiveType)?.primitiveTypeCode == PrimitiveType.LONG
        }
        if (stagedBinding !is JdtBoundCompilationUnitResolution.Available || stagedBinding.value != true) {
            return refused(snapshot, "java.typeRepair.stagedBindingFailed", "Staged JDT bindings did not preserve the selected field identity and requested type.")
        }
        val after = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.typeRepair.stagedDiagnosticsUnavailable", "Staged Java diagnostics could not be computed.")
        }
        val baselineErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.groupingBy(::diagnosticIdentity).eachCount()
        val stagedErrors = after.filter { it.severity == Diagnostic.Severity.ERROR }.groupingBy(::diagnosticIdentity).eachCount()
        val introducedErrors = stagedErrors.entries.sumOf { (identity, count) -> (count - baselineErrors.getOrDefault(identity, 0)).coerceAtLeast(0) }
        if (introducedErrors > 0) {
            return refused(snapshot, "java.typeRepair.diagnosticsFailed", "The requested type introduces $introducedErrors Java diagnostic error(s).")
        }
        return PatchPlan(
            operation = "changeType",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.96,
            requiresUserApproval = true,
            summary = "Change JDT-bound private field '${fragment.name.identifier}' from int to long.",
            affectedFiles = setOf(path),
            workspaceEdit = WorkspaceEdit(listOf(edit)),
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf("This bounded operation changes one private field declaration; callers or reflective consumers outside the analyzed source cannot be inferred."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun unitReferences(unit: org.eclipse.jdt.core.dom.CompilationUnit, selected: VariableDeclarationFragment, visit: (SimpleName) -> Unit) {
        unit.accept(object : ASTVisitor() {
            override fun visit(node: SimpleName): Boolean {
                if (node !== selected.name && node.parent !is org.eclipse.jdt.core.dom.Javadoc) visit(node)
                return true
            }
        })
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String =
        "${diagnostic.severity}|${diagnostic.code}|${diagnostic.message}|${diagnostic.location?.path?.normalize()}"

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = "changeType",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        refusalCode = code,
    )
}
