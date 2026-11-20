package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.SourceLocation

/** Pure domain input. The adapter must supply an attested native JVM TYPE identity. */
internal data class JavaTypeCompilationScope(val providerId: String, val moduleId: String, val sourceSetId: String)
internal data class JavaNativeTypeDeclaration(val scope: JavaTypeCompilationScope, val binaryName: String,
                                            val location: SourceLocation)

/** Complements per-compilation-unit compiler errors with cross-source declaration closure. */
internal object JavaCrossSourceTypeCollisions {
    fun diagnostics(declarations: List<JavaNativeTypeDeclaration>): List<Diagnostic> = declarations.distinct()
        .groupBy { it.scope to it.binaryName }.values
        .filter { family -> family.map { it.location.path }.distinct().size > 1 }
        .flatMap { family -> family.map { declaration ->
            Diagnostic("Duplicate Java type '${declaration.binaryName}' in source set '${declaration.scope.moduleId}:${declaration.scope.sourceSetId}'",
                Diagnostic.Severity.ERROR, location = declaration.location, code = "java.jdt.typeResolution",
                evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION)
        } }
        .sortedWith(compareBy<Diagnostic> { ProtocolPath.serialize(requireNotNull(it.location).path) }
            .thenBy { it.location!!.range.start }.thenBy { it.message })
}
