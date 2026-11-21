package org.refactorkit.java

import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.LanguageAdapterResourceLimits
import org.refactorkit.core.LanguageAdapterRuntime
import org.refactorkit.core.LanguageAdapterDescriptor
import org.refactorkit.core.LanguageCapability
import org.refactorkit.core.MutationAuthority
import org.refactorkit.core.RefactoringDescriptor
import org.refactorkit.core.RegisteredLanguageAdapter
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SemanticEvidenceKind

/** Reference registration consumed by the multi-language kernel. */
object JavaAdapterRegistration {
    private val jdtReadRuntime = LanguageAdapterRuntime(
        limits = LanguageAdapterResourceLimits(
            maxInputBytes = JdtJavaAnalysisCache.MAX_JAVA_SOURCE_BYTES,
        ),
    )

    val capabilities: List<LanguageCapability> = listOf(
        semanticRead("definition"),
        semanticRead("hover"),
        semanticRead("references"),
        semanticQuery("typeDefinition"),
        semanticQuery("typeHierarchy"),
        semanticQuery("callHierarchy"),
        stable("renameClass", SemanticEvidenceKind.COMPILER),
        stable("renameMember", SemanticEvidenceKind.COMPILER),
        stable("moveClass", SemanticEvidenceKind.COMPILER),
        stable("movePackage", SemanticEvidenceKind.STRUCTURAL_PARSE),
        stable("moveSourceRoot", SemanticEvidenceKind.STRUCTURAL_PARSE),
        stable("organizeImports", SemanticEvidenceKind.STRUCTURAL_PARSE),
        stable("batchCleanup", SemanticEvidenceKind.STRUCTURAL_PARSE),
        stable("formatFile", SemanticEvidenceKind.STRUCTURAL_PARSE),
        stable("safeDelete", SemanticEvidenceKind.COMPILER),
        stable("makeStatic", SemanticEvidenceKind.COMPILER),
        stable("changeVisibility", SemanticEvidenceKind.COMPILER),
        stable("changeType", SemanticEvidenceKind.COMPILER),
        stable("inlineVariable", SemanticEvidenceKind.COMPILER),
        stable("inlineConstant", SemanticEvidenceKind.COMPILER),
        stable("inlineMethod", SemanticEvidenceKind.COMPILER),
        stable("extractVariable", SemanticEvidenceKind.COMPILER),
        stable("extractConstant", SemanticEvidenceKind.COMPILER),
        stable("extractField", SemanticEvidenceKind.COMPILER),
        stable("encapsulateField", SemanticEvidenceKind.COMPILER),
        stable("extractParameter", SemanticEvidenceKind.COMPILER),
        stable("extractInterface", SemanticEvidenceKind.COMPILER),
        stable("extractSuperclass", SemanticEvidenceKind.COMPILER),
        stable("pullUpMember", SemanticEvidenceKind.COMPILER),
        stable("pushDownMember", SemanticEvidenceKind.COMPILER),
        stable("moveMember", SemanticEvidenceKind.COMPILER),
        stable("extractMethod", SemanticEvidenceKind.COMPILER),
        stable("changeSignature.renameParameter", SemanticEvidenceKind.COMPILER),
        stable("changeSignature.changeParameterType", SemanticEvidenceKind.COMPILER),
        stable("changeSignature.addParameter", SemanticEvidenceKind.COMPILER),
        stable("changeSignature.reorderParameters", SemanticEvidenceKind.COMPILER),
        stable("changeSignature.removeParameter", SemanticEvidenceKind.COMPILER),
    )

    private val capabilitiesByOperation = capabilities.associateBy(LanguageCapability::operation)

    fun capability(operation: String): LanguageCapability? = capabilitiesByOperation[canonicalOperation(operation)]

    fun mutationCapabilities(): List<LanguageCapability> = capabilities.filter {
        it.mutationAuthority != MutationAuthority.NONE
    }

    fun sdkMutationOperations(): List<String> = mutationCapabilities().map(LanguageCapability::operation).sorted()

    fun supportsSdkMutationOperation(operation: String): Boolean = operation in capabilitiesByOperation &&
        capabilitiesByOperation.getValue(operation).mutationAuthority != MutationAuthority.NONE

    fun canonicalOperation(operation: String): String = LEGACY_OPERATION_ALIASES[operation] ?: operation

    fun descriptors(): List<RefactoringDescriptor> = mutationCapabilities().map { capability ->
        val (label, risk) = DESCRIPTOR_LABELS.getValue(capability.operation)
        RefactoringDescriptor(capability.operation, label, risk, capability)
    }

    fun create(adapter: JavaLanguageAdapter = JavaLanguageAdapter()) = RegisteredLanguageAdapter(
        descriptor = LanguageAdapterDescriptor(
            languageId = "java",
            extensions = setOf("java"),
            backend = "eclipse-jdt-3.44",
            capabilities = capabilities,
        ),
        adapter = adapter,
    )

    private fun stable(operation: String, evidence: SemanticEvidenceKind) = LanguageCapability(
        operation,
        CapabilityStability.STABLE,
        evidence,
        MutationAuthority.MANAGED_STABLE,
    )

    private fun experimental(
        operation: String,
        evidence: SemanticEvidenceKind = SemanticEvidenceKind.COMPILER,
    ) = LanguageCapability(
        operation,
        CapabilityStability.EXPERIMENTAL,
        evidence,
        MutationAuthority.PROPOSAL_ONLY,
    )

    private fun semanticQuery(operation: String) = LanguageCapability(
        operation,
        CapabilityStability.EXPERIMENTAL,
        SemanticEvidenceKind.COMPILER,
        MutationAuthority.NONE,
        runtime = jdtReadRuntime,
    )

    private fun semanticRead(operation: String) = LanguageCapability(
        operation,
        CapabilityStability.EXPERIMENTAL,
        SemanticEvidenceKind.COMPILER,
        MutationAuthority.NONE,
        runtime = jdtReadRuntime,
    )

    private val LEGACY_OPERATION_ALIASES = mapOf(
        "renameParameter" to "changeSignature.renameParameter",
        "changeParameterType" to "changeSignature.changeParameterType",
        "addParameter" to "changeSignature.addParameter",
        "reorderParameters" to "changeSignature.reorderParameters",
        "removeParameter" to "changeSignature.removeParameter",
    )

    private val DESCRIPTOR_LABELS = mapOf(
        "renameClass" to ("Rename Java class" to RiskLevel.LOW),
        "renameMember" to ("Rename Java method or field" to RiskLevel.LOW),
        "extractMethod" to ("Extract Java method (limited MVP)" to RiskLevel.MEDIUM),
        "encapsulateField" to ("Encapsulate Java field with bound accessors" to RiskLevel.HIGH),
        "changeSignature.renameParameter" to ("Rename Java parameter (limited change signature)" to RiskLevel.HIGH),
        "changeSignature.changeParameterType" to ("Change one JDT-bound Java parameter type" to RiskLevel.MEDIUM),
        "changeSignature.addParameter" to ("Add Java parameter with default call-site expression" to RiskLevel.MEDIUM),
        "changeSignature.reorderParameters" to ("Reorder Java parameters and call-site arguments" to RiskLevel.MEDIUM),
        "changeSignature.removeParameter" to ("Remove unused Java parameter and call-site argument" to RiskLevel.MEDIUM),
        "moveClass" to ("Move Java class" to RiskLevel.MEDIUM),
        "movePackage" to ("Move exact-package Java compilation units" to RiskLevel.MEDIUM),
        "moveSourceRoot" to ("Move Java source root without changing FQCNs" to RiskLevel.MEDIUM),
        "organizeImports" to ("Organize imports" to RiskLevel.LOW),
        "batchCleanup" to ("Batch low-risk Java cleanup" to RiskLevel.LOW),
        "formatFile" to ("Format Java compilation unit" to RiskLevel.LOW),
        "safeDelete" to ("Safe delete" to RiskLevel.MEDIUM),
        "makeStatic" to ("Make Java method static (limited semantic subset)" to RiskLevel.HIGH),
        "changeVisibility" to ("Widen visibility of one bound Java method" to RiskLevel.HIGH),
        "changeType" to ("Change one JDT-bound private Java field type (int to long)" to RiskLevel.HIGH),
        "inlineVariable" to ("Inline Java local primitive-int literal reads" to RiskLevel.LOW),
        "inlineConstant" to ("Inline bound reads of a private static final Java int literal" to RiskLevel.HIGH),
        "inlineMethod" to ("Inline one bound call to a private static Java integer helper" to RiskLevel.HIGH),
        "extractVariable" to ("Extract bound Java return expression into a local variable" to RiskLevel.MEDIUM),
        "extractConstant" to ("Extract bound Java compile-time integer expression into a constant" to RiskLevel.MEDIUM),
        "extractField" to ("Extract bound Java compile-time integer expression into an instance field" to RiskLevel.MEDIUM),
        "extractParameter" to ("Promote a bound Java return expression into a method parameter" to RiskLevel.MEDIUM),
        "extractInterface" to ("Extract one bound Java method into a source interface" to RiskLevel.MEDIUM),
        "extractSuperclass" to ("Move one bound pure Java method into a new superclass" to RiskLevel.HIGH),
        "pullUpMember" to ("Move one bound final Java method into its direct superclass (limited subset)" to RiskLevel.HIGH),
        "pushDownMember" to ("Move one bound final Java method into its only direct child (limited subset)" to RiskLevel.HIGH),
        "moveMember" to ("Move one binding-closed Java member between existing types (limited subset)" to RiskLevel.HIGH),
    )
}
