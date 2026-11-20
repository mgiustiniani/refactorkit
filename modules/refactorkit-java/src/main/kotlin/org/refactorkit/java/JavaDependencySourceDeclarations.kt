package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceRange
import org.refactorkit.core.ImmutableEditorOverlay
import java.nio.file.Path

data class JdtJavaSourceDeclaration(
    val identity: JdtJavaJvmIdentity,
    val declarationRange: SourceRange,
    val nameRange: SourceRange,
    val bindingKey: String,
    val modifiers: Int,
)

enum class JdtJavaSourceDeclarationStatus { READY, AMBIGUOUS, UNAVAILABLE }

data class JdtJavaSourceDeclarations(val declarations: List<JdtJavaSourceDeclaration>, val completeOwners: Set<String>,
                                   val implicitIdentities: Set<JdtJavaJvmIdentity>,
                                   val status: JdtJavaSourceDeclarationStatus = JdtJavaSourceDeclarationStatus.READY)

data class JavaDependencySourceDeclarationResult(
    val source: JavaDependencySourceResult,
    val declaration: JdtJavaSourceDeclaration? = null,
    val ambiguousDeclarations: List<JdtJavaSourceDeclaration> = emptyList(),
)

enum class JavaEditorDependencySourceOperation { LOOKUP, DECLARATION, READ }

/** Semantic application query over one already-attested source; no second dependency model. */
class JavaDependencySourceDeclarations {
    fun readEditor(session: JavaEditorOverlaySession, operation: JavaEditorDependencySourceOperation,
                   request: JavaDependencySourceRequest, expectedOverlayHash: String,
                   scan: () -> ProjectSnapshot, overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay,
                   cancellation: SemanticCancellationToken): JavaEditorReadResult<JavaDependencySourceDeclarationResult> =
        session.read(request.context, request.path, expectedOverlayHash, scan, overlayFactory, cancellation) { context, overlay, staged ->
            val boundRequest = request.copy(editorOverlayHash = overlay.overlayHash)
            when (operation) {
                JavaEditorDependencySourceOperation.DECLARATION -> resolve(context, boundRequest, cancellation, staged.workspace.root)
                JavaEditorDependencySourceOperation.LOOKUP, JavaEditorDependencySourceOperation.READ ->
                    JavaDependencySourceDeclarationResult(JavaDependencySources().readInContext(context, boundRequest, cancellation))
            }
        }
    fun read(request: JavaDependencySourceRequest, scan: () -> ProjectSnapshot,
             cancellation: SemanticCancellationToken): JavaBoundReadResult<JavaDependencySourceDeclarationResult> =
        JavaBoundContext.read(request.context, scan, cancellation) { context ->
            try { resolve(context, request, cancellation) } catch (_: Exception) {
                JavaDependencySourceDeclarationResult(JavaDependencySourceResult.Refused(
                    "java.sources.declarationAnalysisUnavailable", "Source declaration bindings could not be resolved safely"))
            }
        }

    private fun resolve(context: JavaBoundContext, request: JavaDependencySourceRequest,
                        cancellation: SemanticCancellationToken, semanticSourceRoot: Path = context.snapshot.workspace.root): JavaDependencySourceDeclarationResult {
        val sources = JavaDependencySources()
        val initial = sources.readInContext(context, request, cancellation)
        if (initial !is JavaDependencySourceResult.Available) return JavaDependencySourceDeclarationResult(initial)
        val guarded = request.copy(expectedAssociationHash = initial.association.associationHash)
        val read = if (initial.content != null) initial else sources.readInContext(context, guarded, cancellation)
        if (read !is JavaDependencySourceResult.Available) return JavaDependencySourceDeclarationResult(read)
        val association = read.association
        if (!sources.binaryDeclaresIdentity(association)) return JavaDependencySourceDeclarationResult(
            JavaDependencySourceResult.Refused("java.sources.binaryIdentityMismatch", "Classfile has no exact bound JVM member descriptor; synthetic constructor forms are not inferred"))
        val environment = context.view.environments.getValue(JavaEffectiveProjectContextProvider.key(
            association.providerId, association.moduleId, association.sourceSetId))
        val owner = context.snapshot.owningJavaBuildSourceRoots(request.path).singleOrNull()
            ?.takeIf { it.providerId == association.providerId && it.module.id == association.moduleId && it.sourceSet.id == association.sourceSetId }
            ?: return JavaDependencySourceDeclarationResult(JavaDependencySourceResult.Refused(
                "java.sources.ownerAmbiguous", "Source declaration has no unique matching Java source-set owner"))
        val apiProfile = JavaSourceSetPlatformContext.profile(owner.sourceSet, context.authority.platforms)
        val platform = context.authority.platforms.getValue(apiProfile)
        val root = context.snapshot.workspace.root
        val declarations = JdtJavaSemanticAnalyzer().dependencySourceDeclarations(
            SourceFile(Path.of(association.sourceEntry), requireNotNull(read.content), "java"),
            environment.sourceRoots.map { semanticSourceRoot.resolve(it).normalize() },
            environment.compileClasspath.map { root.resolve(it.path).normalize() }, platform, cancellation)
        // Bracket every semantic outcome, including unavailable/ambiguous source evidence.
        val after = sources.readInContext(context, guarded, cancellation)
        if (after !is JavaDependencySourceResult.Available) return JavaDependencySourceDeclarationResult(after)
        val metadata = after.copy(content = null)
        if (declarations.status == JdtJavaSourceDeclarationStatus.AMBIGUOUS) return JavaDependencySourceDeclarationResult(
            JavaDependencySourceResult.Ambiguous("java.sources.declarationAmbiguous", "JDT reports duplicate/clashing source declarations; no range is qualified", emptyList()))
        if (declarations.status == JdtJavaSourceDeclarationStatus.UNAVAILABLE) return JavaDependencySourceDeclarationResult(
            JavaDependencySourceResult.Unavailable("java.sources.declarationBindingUnavailable", "Attested source grammar is unavailable for exact declaration navigation"))
        val matching = declarations.declarations.filter { it.identity == association.identity }
        if (matching.size > 128) return JavaDependencySourceDeclarationResult(JavaDependencySourceResult.Refused(
            "java.sources.declarationLimitExceeded", "Source declaration candidates exceed 128 entries"))
        if (matching.size > 1) return JavaDependencySourceDeclarationResult(
            JavaDependencySourceResult.Ambiguous("java.sources.declarationAmbiguous", "Multiple source declarations have the exact bound JVM identity", emptyList()),
            ambiguousDeclarations = matching)
        val exact = matching.singleOrNull()
        if (exact != null) {
            if (!sources.binaryDeclaresIdentity(association, exact.modifiers)) return JavaDependencySourceDeclarationResult(
                JavaDependencySourceResult.Refused("java.sources.declarationMismatch", "Source declaration modifiers differ from the exact binary member"))
            return JavaDependencySourceDeclarationResult(metadata, exact)
        }
        if (association.identity.ownerBinaryName !in declarations.completeOwners)
            return JavaDependencySourceDeclarationResult(JavaDependencySourceResult.Unavailable(
                "java.sources.declarationBindingUnavailable", "Owning source has no complete resolved declaration binding"))
        val missing = if (association.identity in declarations.implicitIdentities)
            JavaDependencySourceResult.Unavailable("java.sources.declarationUnavailable",
                "JDT identifies an implicit/generated member with no explicit source declaration range")
        else JavaDependencySourceResult.Refused("java.sources.declarationMismatch",
            "Attested source has no matching explicit or implicit JVM member; source and binary differ")
        return JavaDependencySourceDeclarationResult(missing)
    }
}
