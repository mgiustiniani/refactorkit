package org.refactorkit.jvm

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.java.JdtJavaJvmIdentity
import org.refactorkit.java.JdtJavaJvmIdentityKind
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticEvidence
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import java.security.MessageDigest

/** Language-neutral-within-JVM declaration kind; no JDT, FIR, PSI, or compiler handle is retained. */
enum class SharedJvmIdentityKind { TYPE, CALLABLE, FIELD }

data class SharedJvmIdentity(
    val kind: SharedJvmIdentityKind,
    val ownerBinaryName: String,
    val memberName: String,
    val descriptor: String,
) {
    init {
        require(BINARY_NAME.matches(ownerBinaryName)) { "shared JVM owner is invalid" }
        when (kind) {
            SharedJvmIdentityKind.TYPE -> require(memberName.isEmpty() && TYPE_DESCRIPTOR.matches(descriptor)) {
                "shared JVM type identity is invalid"
            }
            SharedJvmIdentityKind.CALLABLE -> require(
                (JVM_NAME.matches(memberName) || memberName == "<init>") && METHOD_DESCRIPTOR.matches(descriptor),
            ) { "shared JVM callable identity is invalid" }
            SharedJvmIdentityKind.FIELD -> require(JVM_NAME.matches(memberName) && FIELD_DESCRIPTOR.matches(descriptor)) {
                "shared JVM field identity is invalid"
            }
        }
    }

    val id: SymbolId = SymbolId("shared-jvm-v1:${sha256(canonical())}")

    fun canonical(): String = listOf(kind.name, ownerBinaryName, memberName, descriptor).joinToString("\u0000")

    companion object {
        private val BINARY_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:[.$][A-Za-z_][A-Za-z0-9_]*)*")
        private val JVM_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val FIELD_DESCRIPTOR = Regex("\\[*(?:[BCDFIJSZ]|L[A-Za-z0-9_$/]+;)")
        private val TYPE_DESCRIPTOR = FIELD_DESCRIPTOR
        private val METHOD_DESCRIPTOR = Regex(
            "\\((?:\\[*(?:[BCDFIJSZ]|L[A-Za-z0-9_$/]+;))*\\)(?:V|\\[*(?:[BCDFIJSZ]|L[A-Za-z0-9_$/]+;))",
        )

        internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

data class SharedJvmDeclaration(
    val identity: SharedJvmIdentity,
    val languageId: String,
    val sourceSymbolId: SymbolId,
    val sourceKind: Symbol.Kind,
    val location: SourceLocation,
)

data class SharedJvmReference(
    val identity: SharedJvmIdentity,
    val sourceLanguageId: String,
    val targetLanguageId: String,
    val location: SourceLocation,
)

class SharedJvmIdentityIndex(
    val snapshotHash: String,
    declarations: List<SharedJvmDeclaration>,
    crossLanguageReferences: List<SharedJvmReference>,
    val complete: Boolean,
    val truncated: Boolean,
    val provenanceSha256: String,
) {
    private val declarationValues = declarations.toList()
    private val referenceValues = crossLanguageReferences.toList()
    val declarations: List<SharedJvmDeclaration> get() = declarationValues.toList()
    val crossLanguageReferences: List<SharedJvmReference> get() = referenceValues.toList()

    fun declaration(id: SymbolId): SharedJvmDeclaration? = declarationValues.singleOrNull { it.identity.id == id }
    fun references(id: SymbolId): List<SharedJvmReference> = referenceValues.filter { it.identity.id == id }

    override fun equals(other: Any?): Boolean = other is SharedJvmIdentityIndex &&
        snapshotHash == other.snapshotHash && declarationValues == other.declarationValues &&
        referenceValues == other.referenceValues && complete == other.complete &&
        truncated == other.truncated && provenanceSha256 == other.provenanceSha256

    override fun hashCode(): Int = listOf(
        snapshotHash, declarationValues, referenceValues, complete, truncated, provenanceSha256,
    ).hashCode()

    override fun toString(): String = "SharedJvmIdentityIndex(" +
        "snapshotHash=$snapshotHash, declarations=$declarationValues, " +
        "crossLanguageReferences=$referenceValues, complete=$complete, truncated=$truncated, " +
        "provenanceSha256=$provenanceSha256)"
}

sealed interface SharedJvmIdentityProjection {
    data class Available(val index: SharedJvmIdentityIndex) : SharedJvmIdentityProjection
    data class Refused(val code: String, val message: String) : SharedJvmIdentityProjection
}

/**
 * Joins exact JDT and K2 projections through JVM binary identity.
 *
 * The projector performs no parsing, compilation, filesystem access, or fallback.
 * Missing/recovered provider evidence is excluded rather than guessed.
 */
object SharedJvmIdentityProjector {
    private const val MAX_DECLARATIONS = 50_000
    private const val MAX_REFERENCES = 200_000

    fun project(
        snapshot: ProjectSnapshot,
        java: JdtJavaSemanticAnalysisResult,
        kotlin: KotlinCompilerSymbolsResult.Available,
    ): SharedJvmIdentityProjection {
        if (kotlin.attestation.snapshotHash != snapshot.hash) return SharedJvmIdentityProjection.Refused(
            "jvm.identitySnapshotMismatch",
            "K2 declaration evidence belongs to another project snapshot",
        )
        if (java.snapshotHash != snapshot.hash) return SharedJvmIdentityProjection.Refused(
            "jvm.identityJavaSnapshotMismatch",
            "JDT declaration evidence belongs to another project snapshot",
        )
        val sourcePaths = snapshot.files.associateBy { it.path.normalize() }
        val declarations = mutableListOf<SharedJvmDeclaration>()

        java.symbols.asSequence()
            .filter { !it.recovered && it.evidence == JdtJavaSemanticEvidence.JDT_BINDING }
            .mapNotNull { symbol -> symbol.jvmIdentity?.toShared()?.let { identity ->
                val sourceKind = symbol.kind.toCoreKind()
                SharedJvmDeclaration(
                    identity,
                    "java",
                    SymbolId("java-jvm-v1:${SharedJvmIdentity.sha256(identity.canonical())}"),
                    sourceKind,
                    SourceLocation(symbol.path.normalize(), symbol.sourceRange),
                )
            } }
            .forEach(declarations::add)

        val kotlinSymbols = kotlin.index.symbols.associateBy(Symbol::id)
        kotlin.declarations.values.forEach { evidence ->
            val symbol = kotlinSymbols[evidence.id] ?: return SharedJvmIdentityProjection.Refused(
                "jvm.identityKotlinDeclarationMissing",
                "K2 declaration evidence has no normalized source symbol",
            )
            evidence.toShared(symbol.kind)?.let { identity ->
                declarations += SharedJvmDeclaration(
                    identity,
                    "kotlin",
                    symbol.id,
                    symbol.kind,
                    symbol.location,
                )
            }
        }

        if (declarations.size > MAX_DECLARATIONS) return SharedJvmIdentityProjection.Refused(
            "jvm.identityDeclarationLimit",
            "Shared JVM declaration evidence exceeds $MAX_DECLARATIONS entries",
        )
        val unsafeDeclaration = declarations.firstOrNull { declaration ->
            val source = sourcePaths[declaration.location.path.normalize()]
            source == null || source.languageId != declaration.languageId
        }
        if (unsafeDeclaration != null) return SharedJvmIdentityProjection.Refused(
            "jvm.identityDeclarationPathInvalid",
            "Shared JVM declaration is outside its attested language source inventory",
        )
        val collisions = declarations.groupBy(SharedJvmDeclaration::identity).filterValues { it.size != 1 }
        if (collisions.isNotEmpty()) return SharedJvmIdentityProjection.Refused(
            "jvm.identityDeclarationCollision",
            "More than one source declaration claims the same exact JVM identity",
        )
        val declarationByIdentity = declarations.associateBy(SharedJvmDeclaration::identity)
        val references = mutableListOf<SharedJvmReference>()

        java.bindingUses.asSequence()
            .filter { !it.recovered && it.evidence == JdtJavaSemanticEvidence.JDT_BINDING }
            .mapNotNull { use -> use.jvmIdentity?.toShared()?.let { it to use } }
            .forEach { (identity, use) ->
                val target = declarationByIdentity[identity] ?: return@forEach
                if (target.languageId != "java") references += SharedJvmReference(
                    identity,
                    "java",
                    target.languageId,
                    SourceLocation(use.path.normalize(), use.sourceRange),
                )
            }

        kotlin.externalTypeUsages.forEach { use ->
            val identity = SharedJvmIdentity(
                SharedJvmIdentityKind.TYPE,
                use.jvmBinaryName,
                "",
                "L${use.jvmBinaryName.replace('.', '/')};",
            )
            val target = declarationByIdentity[identity] ?: return@forEach
            if (target.languageId != "kotlin") references += SharedJvmReference(
                identity, "kotlin", target.languageId, use.location,
            )
        }
        kotlin.externalCallableUsages.forEach { use ->
            if (use.jvmDescriptor.isEmpty()) return@forEach
            val identity = SharedJvmIdentity(
                SharedJvmIdentityKind.CALLABLE,
                use.jvmOwner,
                use.callableName,
                use.jvmDescriptor,
            )
            val target = declarationByIdentity[identity] ?: return@forEach
            if (target.languageId != "kotlin") references += SharedJvmReference(
                identity, "kotlin", target.languageId, use.location,
            )
        }

        if (references.size > MAX_REFERENCES) return SharedJvmIdentityProjection.Refused(
            "jvm.identityReferenceLimit",
            "Shared JVM reference evidence exceeds $MAX_REFERENCES entries",
        )
        val unsafeReference = references.firstOrNull { reference ->
            val source = sourcePaths[reference.location.path.normalize()]
            source == null || source.languageId != reference.sourceLanguageId
        }
        if (unsafeReference != null) return SharedJvmIdentityProjection.Refused(
            "jvm.identityReferencePathInvalid",
            "Shared JVM reference is outside its attested language source inventory",
        )
        val orderedDeclarations = declarations.sortedWith(compareBy(
            { it.identity.canonical() }, SharedJvmDeclaration::languageId,
            { it.location.path.toString() }, { it.location.range.start.line }, { it.location.range.start.character },
        ))
        val orderedReferences = references.distinct().sortedWith(compareBy(
            { it.identity.canonical() }, SharedJvmReference::sourceLanguageId,
            { it.location.path.toString() }, { it.location.range.start.line }, { it.location.range.start.character },
        ))
        val provenance = buildString {
            append(snapshot.hash).append('\n')
            append(kotlin.attestation.backend).append('\n')
            append(kotlin.attestation.toolchainProjectionHash).append('\n')
            orderedDeclarations.forEach { declaration ->
                append("D\u0000").append(declaration.identity.canonical()).append('\u0000')
                    .append(declaration.languageId).append('\u0000').append(declaration.location).append('\n')
            }
            orderedReferences.forEach { reference ->
                append("R\u0000").append(reference.identity.canonical()).append('\u0000')
                    .append(reference.sourceLanguageId).append('\u0000').append(reference.targetLanguageId)
                    .append('\u0000').append(reference.location).append('\n')
            }
        }
        return SharedJvmIdentityProjection.Available(SharedJvmIdentityIndex(
            snapshotHash = snapshot.hash,
            declarations = orderedDeclarations,
            crossLanguageReferences = orderedReferences,
            complete = true,
            truncated = false,
            provenanceSha256 = SharedJvmIdentity.sha256(provenance),
        ))
    }

    private fun JdtJavaJvmIdentity.toShared(): SharedJvmIdentity = SharedJvmIdentity(
        kind = when (kind) {
            JdtJavaJvmIdentityKind.TYPE -> SharedJvmIdentityKind.TYPE
            JdtJavaJvmIdentityKind.CALLABLE -> SharedJvmIdentityKind.CALLABLE
            JdtJavaJvmIdentityKind.FIELD -> SharedJvmIdentityKind.FIELD
        },
        ownerBinaryName = ownerBinaryName,
        memberName = memberName,
        descriptor = descriptor,
    )

    private fun org.refactorkit.kotlin.KotlinCompilerDeclarationEvidence.toShared(
        sourceKind: Symbol.Kind,
    ): SharedJvmIdentity? = when (sourceKind) {
        Symbol.Kind.CLASS,
        Symbol.Kind.OBJECT,
        Symbol.Kind.INTERFACE,
        Symbol.Kind.ENUM,
        Symbol.Kind.ANNOTATION -> SharedJvmIdentity(
            SharedJvmIdentityKind.TYPE,
            jvmOwner,
            "",
            "L${jvmOwner.replace('.', '/')};",
        )
        Symbol.Kind.FUNCTION,
        Symbol.Kind.CONSTRUCTOR -> SharedJvmIdentity(
            SharedJvmIdentityKind.CALLABLE,
            jvmOwner,
            jvmName,
            jvmDescriptor,
        )
        Symbol.Kind.PROPERTY -> SharedJvmIdentity(
            SharedJvmIdentityKind.FIELD,
            jvmOwner,
            jvmName,
            jvmDescriptor,
        )
        else -> null
    }

    private fun org.refactorkit.java.JdtJavaSemanticSymbolKind.toCoreKind(): Symbol.Kind = when (this) {
        org.refactorkit.java.JdtJavaSemanticSymbolKind.CLASS -> Symbol.Kind.CLASS
        org.refactorkit.java.JdtJavaSemanticSymbolKind.INTERFACE -> Symbol.Kind.INTERFACE
        org.refactorkit.java.JdtJavaSemanticSymbolKind.ENUM -> Symbol.Kind.ENUM
        org.refactorkit.java.JdtJavaSemanticSymbolKind.RECORD -> Symbol.Kind.RECORD
        org.refactorkit.java.JdtJavaSemanticSymbolKind.ANNOTATION -> Symbol.Kind.ANNOTATION
        org.refactorkit.java.JdtJavaSemanticSymbolKind.METHOD -> Symbol.Kind.METHOD
        org.refactorkit.java.JdtJavaSemanticSymbolKind.FIELD -> Symbol.Kind.FIELD
        org.refactorkit.java.JdtJavaSemanticSymbolKind.CONSTRUCTOR -> Symbol.Kind.CONSTRUCTOR
    }
}
