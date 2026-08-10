# Shared Java/Kotlin JVM declaration and reference identity

Status: implemented candidate; promotion requires focused regression, four-platform execution, and independent review.

## Requirement

`REQ-SHARED-JVM-IDENTITY-001` establishes one descriptor-exact normalized identity for supported JVM-visible declarations and cross-language references.

1. JDT shall project type, callable, constructor, and field identities from exact bindings as binary owner, JVM member name, and descriptor.
2. K2 shall project the same tuple from compiler/FIR, PSI, and generated class-file evidence.
3. `refactorkit-jvm` shall join those projections without retaining or exposing JDT, FIR, PSI, AST, binding-key, or class-loader handles.
4. Java references to Kotlin declarations and Kotlin references to Java declarations shall resolve only when the exact tuple names one source declaration.
5. Overloads and constructors require the complete JVM descriptor; owner/name matching alone is insufficient.
6. Stale K2 snapshot evidence, duplicate source claims, recovered JDT bindings, invalid paths, resource overflow, or absent exact evidence shall refuse or remain excluded rather than degrade to lexical identity.
7. Output ordering and `shared-jvm-v1:<sha256>` IDs shall be deterministic and independent of source offsets and process identity.

## Bounded scope

The first row covers compiler-visible JVM types, methods, constructors, and fields represented by the existing JDT/K2 catalogues. Kotlin property accessors, synthetic/default bridges, inline/value-class mangling, callable references, dynamic reflection, generated declarations, and unsupported build/toolchain shapes remain separately gated.

## Executable evidence

- `JdtJavaSemanticAnalyzerTest.projectsExactJvmTypeCallableFieldAndReferenceIdentitiesWithoutJdtHandles`
- `KotlinJavaPublicTypeRenamePlannerTest.ephemeralJavaClassesLetK2ProveKotlinUsesOfPublicJavaType`
- `SharedJvmIdentityIndexTest.joinsDescriptorExactJavaAndKotlinDeclarationsAndReferencesInBothDirections`
- `SharedJvmIdentityIndexTest.refusesStaleK2EvidenceBeforePublishingAnySharedIdentity`
