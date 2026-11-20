package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.Modifier
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
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Conservative bound encapsulation cell for one public final primitive-int
 * field. It updates only JDT-binding-matched source reads in the saved Java
 * workspace and proves the staged getter/call bindings before returning a plan.
 */
class JavaEncapsulateFieldPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, symbolId: SymbolId): PatchPlan {
        val analysis = runCatching { JdtJavaSemanticAnalyzer().analyze(snapshot) }.getOrElse {
            return refused(snapshot, "java.encapsulateField.analysisUnavailable", "Complete JDT project analysis is unavailable.")
        }
        if (analysis.warnings.isNotEmpty()) {
            return refused(snapshot, "java.encapsulateField.analysisIncomplete", "JDT reported unresolved or recovered Java source bindings.")
        }
        val target = analysis.symbols.singleOrNull {
            it.qualifiedName == symbolId.value && it.kind == JdtJavaSemanticSymbolKind.FIELD
        } ?: return refused(snapshot, "java.encapsulateField.fieldNotFound", "The requested field is absent or ambiguous in the bound Java workspace.")
        val bindingKey = target.bindingKey?.takeIf(String::isNotBlank)
            ?: return refused(snapshot, "java.encapsulateField.analysisIncomplete", "The field declaration has no complete JDT binding.")
        if (target.recovered || target.evidence != JdtJavaSemanticEvidence.JDT_BINDING) {
            return refused(snapshot, "java.encapsulateField.analysisIncomplete", "The field declaration is recovered or lacks JDT binding evidence.")
        }
        val ownerFile = snapshot.files.singleOrNull { it.path.normalize() == target.path.normalize() }
            ?: return refused(snapshot, "java.encapsulateField.fileUnavailable", "The declaring Java source is unavailable in the saved workspace snapshot.")
        if (ownerFile.languageId != "java") {
            return refused(snapshot, "java.encapsulateField.languageUnsupported", "encapsulateField currently supports Java source only.")
        }
        JavaGeneratedSourcePolicy.reason(ownerFile)?.let {
            return refused(snapshot, "java.encapsulateField.generatedSource", "Generated Java source cannot be rewritten: $it")
        }

        val shape = when (val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, ownerFile.path) { unit, _ ->
            inspectField(unit, ownerFile, bindingKey)
        }) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> return refused(
                snapshot, "java.encapsulateField.analysisUnavailable", "The declaring source has no current SDK-bound JDT compilation unit.",
            )
        }
        if (shape is ShapeResult.Refused) return refused(snapshot, shape.code, shape.message)
        shape as ShapeResult.Valid

        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.encapsulateField.diagnosticsUnavailable", "Java diagnostics are unavailable for the saved snapshot.")
        }
        val references = analysis.references.filter { it.bindingKey == bindingKey }
        if (references.any { it.recovered || it.evidence != JdtJavaSemanticEvidence.JDT_BINDING || it.simpleName != shape.fieldName }) {
            return refused(snapshot, "java.encapsulateField.analysisIncomplete", "One or more field references are recovered or do not match the declaration.")
        }
        val referencesByPath = references.groupBy { it.path.normalize() }
        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        val expectedRewrittenReads = linkedMapOf<Path, Int>()
        var expectedUnchangedReads = 0
        for ((path, fileReferences) in referencesByPath) {
            val source = snapshot.files.singleOrNull { it.path.normalize() == path }
                ?: return refused(snapshot, "java.encapsulateField.analysisIncomplete", "A bound field reference is outside the saved workspace snapshot.")
            if (source.languageId != "java") {
                return refused(snapshot, "java.encapsulateField.languageUnsupported", "A bound field reference is not in Java source.")
            }
            JavaGeneratedSourcePolicy.reason(source)?.let {
                return refused(snapshot, "java.encapsulateField.generatedSource", "A bound reference is in generated source: $it")
            }
            val group = when (val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, source.path) { unit, _ ->
                resolveReferences(unit, source, fileReferences, bindingKey, shape.ownerBindingKey, shape.fieldName, shape.getterName)
            }) {
                is JdtBoundCompilationUnitResolution.Available -> resolution.value
                is JdtBoundCompilationUnitResolution.Refused -> return refused(
                    snapshot, "java.encapsulateField.analysisIncomplete", "A source file containing a bound field read cannot be rebound.",
                )
            }
            if (group is ReferenceResult.Refused) return refused(snapshot, group.code, group.message)
            group as ReferenceResult.Resolved
            expectedUnchangedReads += group.retainedCount
            if (group.edits.isNotEmpty()) {
                editsByPath.getOrPut(source.path) { mutableListOf() }.addAll(group.edits)
                expectedRewrittenReads[source.path.normalize()] = group.edits.size
            }
        }

        val ownerEdits = editsByPath.getOrPut(ownerFile.path) { mutableListOf() }
        ownerEdits += TextEdit(
            TextEdits.rangeForOffset(ownerFile.content, shape.publicModifierOffset, shape.publicModifierLength),
            "private",
        )
        ownerEdits += TextEdit(TextEdits.rangeForOffset(ownerFile.content, shape.getterInsertionOffset, 0), shape.getterText)
        val workspaceEdits = editsByPath.map { (path, fileEdits) ->
            val source = snapshot.files.single { it.path.normalize() == path.normalize() }
            val normalizedEdits = fileEdits.sortedByDescending { TextEdits.offsetOf(source.content, it.range.start) }
            FileEdit.Modify(source.path, normalizedEdits)
        }
        val stagedFiles = snapshot.files.map { source ->
            val edits = editsByPath.entries.singleOrNull { it.key.normalize() == source.path.normalize() }?.value
            if (edits == null) source else source.copy(content = runCatching {
                TextEdits.apply(source.content, edits)
            }.getOrElse {
                return refused(snapshot, "java.encapsulateField.editConstructionFailed", "The bound field changes could not be normalized safely.")
            })
        }
        val staged = snapshot.copy(files = stagedFiles)
        val stagedFailure = runCatching {
            adapter.withDiagnosticsOverlay(staged) { overlay ->
                val stagedAnalysis = JdtJavaSemanticAnalyzer().analyze(overlay)
                if (stagedAnalysis.warnings.isNotEmpty()) {
                    val detail = stagedAnalysis.warnings.take(3).joinToString("; ") { warning ->
                        "${warning.path}:${warning.line + 1}: ${warning.message}"
                    }
                    return@withDiagnosticsOverlay "Staged JDT analysis reported unresolved or recovered bindings: $detail"
                }
                val stagedField = stagedAnalysis.symbols.singleOrNull {
                    it.qualifiedName == symbolId.value && it.kind == JdtJavaSemanticSymbolKind.FIELD
                }
                if (stagedField == null || stagedField.bindingKey != bindingKey || stagedField.recovered) {
                    return@withDiagnosticsOverlay "Staged JDT analysis did not preserve the field declaration binding."
                }
                val remainingReferences = stagedAnalysis.references.count { it.bindingKey == bindingKey }
                val expectedStagedReferences = expectedUnchangedReads + 1 // generated getter returns the now-private field
                if (remainingReferences != expectedStagedReferences) {
                    return@withDiagnosticsOverlay "Staged JDT analysis found $remainingReferences field reference(s); expected $expectedStagedReferences inside the declaring top-level class and generated getter."
                }
                proveStagedBindings(overlay, ownerFile.path, shape, expectedRewrittenReads)
            }
        }.getOrElse {
            return refused(snapshot, "java.encapsulateField.stagedValidationFailed", "The staged workspace cannot be fully rebound by JDT.")
        }
        if (stagedFailure != null) {
            return refused(snapshot, "java.encapsulateField.stagedValidationFailed", stagedFailure)
        }
        val after = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.encapsulateField.diagnosticsUnavailable", "Staged Java diagnostics are unavailable.")
        }
        if (introducedErrors(before, after).isNotEmpty()) {
            return refused(snapshot, "java.encapsulateField.diagnosticsFailed", "Encapsulation introduces Java compiler diagnostic errors.")
        }

        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.97,
            requiresUserApproval = true,
            summary = "Encapsulate public final ${shape.fieldTypeSource} field '${shape.fieldName}' and update ${expectedRewrittenReads.values.sum()} bound workspace read(s).",
            affectedFiles = WorkspaceEdit(workspaceEdits).affectedFiles(),
            workspaceEdit = WorkspaceEdit(workspaceEdits),
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf("Unknown external, reflection, serialization, and framework-string consumers are not rewritten."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun inspectField(unit: CompilationUnit, source: SourceFile, bindingKey: String): ShapeResult {
        val fragments = mutableListOf<Pair<FieldDeclaration, VariableDeclarationFragment>>()
        unit.accept(object : ASTVisitor() {
            override fun visit(node: FieldDeclaration): Boolean {
                node.fragments().filterIsInstance<VariableDeclarationFragment>().forEach { fragment ->
                    if (fragment.resolveBinding()?.variableDeclaration?.key == bindingKey) fragments += node to fragment
                }
                return true
            }
        })
        val (field, fragment) = fragments.singleOrNull()
            ?: return ShapeResult.Refused("java.encapsulateField.fieldNotFound", "The JDT-bound field declaration is absent or ambiguous in its source.")
        if (field.fragments().size != 1) {
            return ShapeResult.Refused("java.encapsulateField.declarationShapeUnsupported", "A declaration containing multiple fields is not supported by this bounded operation.")
        }
        val owner = field.parent as? TypeDeclaration
            ?: return ShapeResult.Refused("java.encapsulateField.ownerUnsupported", "The field must be declared directly in an ordinary Java class.")
        val ownerBinding = owner.resolveBinding()
        if (owner.isInterface || owner.parent !== unit || ownerBinding == null || ownerBinding.isRecovered || ownerBinding.key.isNullOrBlank()) {
            return ShapeResult.Refused("java.encapsulateField.ownerUnsupported", "The field must belong to a fully bound top-level ordinary Java class.")
        }
        if (owner.superInterfaceTypes().isNotEmpty() || owner.superclassType?.resolveBinding()?.qualifiedName?.let { it != "java.lang.Object" } == true) {
            return ShapeResult.Refused("java.encapsulateField.ownerUnsupported", "Classes with custom inheritance are outside this bounded field-encapsulation cell.")
        }
        if (!Modifier.isPublic(field.modifiers) || !Modifier.isFinal(field.modifiers)) {
            return ShapeResult.Refused("java.encapsulateField.mutableFieldUnsupported", "Only a public final field can be encapsulated by this bounded operation.")
        }
        if (Modifier.isStatic(field.modifiers)) {
            return ShapeResult.Refused("java.encapsulateField.staticFieldUnsupported", "Static fields are not supported by this instance-field encapsulation operation.")
        }
        if (field.modifiers().any { it is org.eclipse.jdt.core.dom.Annotation }) {
            return ShapeResult.Refused("java.encapsulateField.annotatedFieldUnsupported", "Field declaration annotations are not supported because their framework and processor effects cannot be preserved safely.")
        }
        if (fragment.extraDimensions().isNotEmpty()) {
            return ShapeResult.Refused("java.encapsulateField.fieldDimensionsUnsupported", "Array dimensions written after the field name are not supported by this bounded operation.")
        }
        val fieldBinding = fragment.resolveBinding()
        val fieldType = fieldBinding?.type
        if (fieldBinding == null || fieldBinding.isRecovered || fieldType == null || fieldType.isRecovered || fieldType.key.isNullOrBlank()) {
            return ShapeResult.Refused("java.encapsulateField.typeUnsupported", "The field type must have a complete JDT binding and source representation.")
        }
        val typeStart = field.type.startPosition
        val typeEnd = typeStart + field.type.length
        val fieldTypeSource = source.content.takeIf { typeStart >= 0 && typeEnd <= it.length && typeEnd > typeStart }
            ?.substring(typeStart, typeEnd)
            ?.takeIf(String::isNotBlank)
            ?: return ShapeResult.Refused("java.encapsulateField.typeUnsupported", "The source type range is not stable in the saved declaration.")
        val fieldName = fragment.name.identifier
        val getterName = "get" + fieldName.replaceFirstChar { it.uppercase() }
        if (owner.methods.any { it.name.identifier == getterName }) {
            return ShapeResult.Refused("java.encapsulateField.getterConflict", "The declaring class already has a method named '$getterName'.")
        }
        val publicModifier = field.modifiers().filterIsInstance<Modifier>().singleOrNull { it.isPublic }
            ?: return ShapeResult.Refused("java.encapsulateField.declarationShapeUnsupported", "The public field modifier is not represented unambiguously in the source tree.")
        val close = owner.startPosition + owner.length - 1
        if (close !in source.content.indices || source.content[close] != '}') {
            return ShapeResult.Refused("java.encapsulateField.insertionPointUnsupported", "The class closing brace is not a stable source insertion point.")
        }
        val insertionLineStart = source.content.lastIndexOf('\n', close).let { it + 1 }
        if (source.content.substring(insertionLineStart, close).any { it != ' ' && it != '\t' }) {
            return ShapeResult.Refused("java.encapsulateField.insertionPointUnsupported", "The class closing brace shares a line with source code or comments.")
        }
        val firstMember = owner.bodyDeclarations().firstOrNull() as? ASTNode
        val indent = firstMember?.let { member ->
            val memberLineStart = source.content.lastIndexOf('\n', (member.startPosition - 1).coerceAtLeast(0)) + 1
            source.content.substring(memberLineStart, member.startPosition).takeIf { prefix -> prefix.all { it == ' ' || it == '\t' } }
        } ?: if (firstMember == null) {
            val classLineStart = source.content.lastIndexOf('\n', (owner.startPosition - 1).coerceAtLeast(0)) + 1
            source.content.substring(classLineStart, owner.startPosition).takeWhile { it == ' ' || it == '\t' } + "    "
        } else {
            return ShapeResult.Refused("java.encapsulateField.insertionPointUnsupported", "The class member indentation cannot be determined safely.")
        }
        val lineEnding = if (source.content.contains("\r\n")) "\r\n" else "\n"
        return ShapeResult.Valid(
            fieldName = fieldName,
            getterName = getterName,
            ownerQualifiedName = ownerBinding.typeDeclaration.qualifiedName,
            ownerBindingKey = ownerBinding.typeDeclaration.key,
            fieldBindingKey = fieldBinding.variableDeclaration.key,
            fieldTypeSource = fieldTypeSource,
            fieldTypeBindingKey = fieldType.key,
            publicModifierOffset = publicModifier.startPosition,
            publicModifierLength = publicModifier.length,
            getterInsertionOffset = insertionLineStart,
            getterText = "${lineEnding}${indent}public $fieldTypeSource $getterName() { return this.$fieldName; }$lineEnding",
        )
    }

    private fun resolveReferences(
        unit: CompilationUnit,
        source: SourceFile,
        references: List<JdtJavaSemanticReference>,
        bindingKey: String,
        ownerBindingKey: String,
        fieldName: String,
        getterName: String,
    ): ReferenceResult {
        val edits = mutableListOf<TextEdit>()
        for (reference in references) {
            val start = try {
                TextEdits.offsetOf(source.content, reference.sourceRange.start)
            } catch (_: IllegalArgumentException) {
                return ReferenceResult.Refused("java.encapsulateField.analysisIncomplete", "A bound field reference range is outside its source file.")
            }
            val end = try {
                TextEdits.offsetOf(source.content, reference.sourceRange.end)
            } catch (_: IllegalArgumentException) {
                return ReferenceResult.Refused("java.encapsulateField.analysisIncomplete", "A bound field reference range is outside its source file.")
            }
            if (end <= start || source.content.substring(start, end) != fieldName) {
                return ReferenceResult.Refused("java.encapsulateField.analysisIncomplete", "A bound reference range does not select exactly the field name.")
            }
            val matches = mutableListOf<SimpleName>()
            unit.accept(object : ASTVisitor() {
                override fun visit(node: SimpleName): Boolean {
                    if (node.startPosition == start && node.length == end - start &&
                        (node.resolveBinding() as? IVariableBinding)?.variableDeclaration?.key == bindingKey) {
                        matches += node
                    }
                    return true
                }
            })
            val name = matches.singleOrNull()
                ?: return ReferenceResult.Refused("java.encapsulateField.analysisIncomplete", "A field reference cannot be uniquely rebound to its JDT source range.")
            var current: ASTNode? = name
            var topLevel: TypeDeclaration? = null
            while (current != null) {
                if (current is TypeDeclaration && current.parent is CompilationUnit) topLevel = current
                current = current.parent
            }
            if (topLevel?.resolveBinding()?.typeDeclaration?.key == ownerBindingKey) continue
            edits += TextEdit(TextEdits.rangeForOffset(source.content, start, end - start), "$getterName()")
        }
        return ReferenceResult.Resolved(edits, references.size - edits.size)
    }

    private fun proveStagedBindings(
        staged: ProjectSnapshot,
        ownerPath: Path,
        shape: ShapeResult.Valid,
        expectedRewrittenReads: Map<Path, Int>,
    ): String? {
        val ownerSource = staged.files.single { it.path.normalize() == ownerPath.normalize() }
        val ownerProof = withUnit(staged, ownerSource.path) { unit ->
            val owner = topLevelOwner(unit, shape.ownerBindingKey) ?: return@withUnit false
            val field = owner.bodyDeclarations().filterIsInstance<FieldDeclaration>().singleOrNull { declaration ->
                declaration.fragments().filterIsInstance<VariableDeclarationFragment>().any { fragment ->
                    fragment.name.identifier == shape.fieldName && fragment.resolveBinding()?.variableDeclaration?.key == shape.fieldBindingKey
                }
            } ?: return@withUnit false
            val fragment = field.fragments().filterIsInstance<VariableDeclarationFragment>().singleOrNull() ?: return@withUnit false
            val getter = owner.methods.singleOrNull { it.name.identifier == shape.getterName && it.parameters().isEmpty() }
                ?: return@withUnit false
            val getterBinding = getter.resolveBinding() ?: return@withUnit false
            val getterReturn = getter.resolveBinding()?.returnType
            val returnStatement = getter.body?.statements()?.singleOrNull() as? org.eclipse.jdt.core.dom.ReturnStatement
                ?: return@withUnit false
            val returned = returnStatement.expression as? org.eclipse.jdt.core.dom.FieldAccess ?: return@withUnit false
            val returnedBinding = returned.resolveFieldBinding()
            Modifier.isPrivate(field.modifiers) && Modifier.isFinal(field.modifiers) && !Modifier.isPublic(field.modifiers) &&
                getterBinding.declaringClass?.typeDeclaration?.key == shape.ownerBindingKey &&
                Modifier.isPublic(getter.modifiers) && getterReturn?.let { !it.isRecovered && it.key == shape.fieldTypeBindingKey } == true &&
                returned.name.identifier == shape.fieldName && returnedBinding?.variableDeclaration?.key == shape.fieldBindingKey &&
                fragment.resolveBinding()?.variableDeclaration?.key == shape.fieldBindingKey
        }
        if (ownerProof != true) return "Staged JDT bindings do not prove a private final field and a public getter returning that field."

        val expectedMethodKey = withUnit(staged, ownerSource.path) { unit ->
            val owner = topLevelOwner(unit, shape.ownerBindingKey) ?: return@withUnit null
            owner.methods.singleOrNull { it.name.identifier == shape.getterName && it.parameters().isEmpty() }
                ?.resolveBinding()?.methodDeclaration?.key
        } ?: return "The generated getter has no complete staged JDT method binding."
        for ((path, expectedCount) in expectedRewrittenReads) {
            val source = staged.files.singleOrNull { it.path.normalize() == path }
                ?: return "A rewritten Java source disappeared from the staged workspace."
            val count = withUnit(staged, source.path) { unit ->
                var resolved = 0
                var invalidTarget = false
                unit.accept(object : ASTVisitor() {
                    override fun visit(node: MethodInvocation): Boolean {
                        if (node.name.identifier != shape.getterName) return true
                        val binding = node.resolveMethodBinding() ?: return true
                        if (binding.methodDeclaration.key == expectedMethodKey) resolved++
                        if (binding.name == shape.getterName && binding.declaringClass?.typeDeclaration?.key == shape.ownerBindingKey &&
                            (binding.methodDeclaration.key != expectedMethodKey || binding.parameterTypes.isNotEmpty() ||
                                binding.returnType.isRecovered || binding.returnType.key != shape.fieldTypeBindingKey)) {
                            invalidTarget = true
                        }
                        return true
                    }
                })
                if (invalidTarget) -1 else resolved
            } ?: return "A rewritten Java client file cannot be rebound after staging."
            if (count != expectedCount) return "Staged JDT analysis does not bind every rewritten read to the generated getter exactly once."
        }
        return null
    }

    private fun <T> withUnit(snapshot: ProjectSnapshot, path: Path, action: (CompilationUnit) -> T): T? =
        when (val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, path) { unit, _ -> action(unit) }) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> null
        }

    private fun topLevelOwner(unit: CompilationUnit, key: String): TypeDeclaration? =
        unit.types().filterIsInstance<TypeDeclaration>().singleOrNull { type ->
            type.resolveBinding()?.typeDeclaration?.key == key
        }

    private fun introducedErrors(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val baseline = before.filter { it.severity == Diagnostic.Severity.ERROR }
            .groupingBy(::diagnosticIdentity).eachCount().toMutableMap()
        return after.filter { it.severity == Diagnostic.Severity.ERROR }.filter { diagnostic ->
            val identity = diagnosticIdentity(diagnostic)
            val remaining = baseline.getOrDefault(identity, 0)
            if (remaining == 0) true else {
                baseline[identity] = remaining - 1
                false
            }
        }
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic) =
        "${diagnostic.severity}|${diagnostic.code}|${diagnostic.message}|${diagnostic.location}"

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.JDT_BINDING,
        refusalCode = code,
    )

    private sealed interface ShapeResult {
        data class Valid(
            val fieldName: String,
            val getterName: String,
            val ownerQualifiedName: String,
            val ownerBindingKey: String,
            val fieldBindingKey: String,
            val fieldTypeSource: String,
            val fieldTypeBindingKey: String,
            val publicModifierOffset: Int,
            val publicModifierLength: Int,
            val getterInsertionOffset: Int,
            val getterText: String,
        ) : ShapeResult

        data class Refused(val code: String, val message: String) : ShapeResult
    }

    private sealed interface ReferenceResult {
        data class Resolved(val edits: List<TextEdit>, val retainedCount: Int) : ReferenceResult
        data class Refused(val code: String, val message: String) : ReferenceResult
    }

    companion object {
        const val OPERATION = "encapsulateField"
    }
}
