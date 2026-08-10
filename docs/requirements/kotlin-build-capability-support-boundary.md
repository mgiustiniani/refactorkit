# Kotlin build-model, capability, support, license, and SBOM boundary

Status: implemented candidate; promotion requires focused regression, four-platform execution, and independent review.

## Requirements

### REQ-KOTLIN-BUILD-FACET-001

The language-neutral Build Model SPI shall carry typed `BuildLanguageFacet` values rather than requiring Kotlin to copy Java- or Maven-specific fields into core. Facets bind language, platform, compiler, source/target/runtime versions, compiler-plugin IDs, and declared/derived/partial/unsupported evidence. They are validated, deeply detached, and included in `ProjectSnapshot.hash`.

Maven, Gradle, TypeScript, and Kotlin projections shall use the same additive core type. Existing API `0.2` attributes remain compatibility evidence, not the new semantic contract.

### REQ-KOTLIN-CAPABILITY-BOUNDARY-001

Kotlin/JVM, Multiplatform, Android, generated code, compiler plugins, `expect`/`actual`, and scripts shall remain separate capability/evidence cases.

- bounded Kotlin/JVM rows may be operation-specific `EXPERIMENTAL`;
- Multiplatform, Android, compiler-plugin/kapt/KSP semantics, generated-code mutation, `expect`/`actual`, and `.kts` semantics remain stable typed refusals;
- declarative Gradle evidence remains `PARTIAL` and never executes build code;
- unsupported facets make the Kotlin projection `EXECUTION_REFUSED` rather than silently falling back to JVM.

### REQ-KOTLIN-SUPPORT-PROJECTION-001

Before any Kotlin managed capability can be advertised beyond its separately qualified row, the repository shall publish and fail-closed verify exact Kotlin/JDK/Maven/Gradle support rows plus dependency-license and SBOM boundaries.

The normative machine-readable projection is [`v0.7.0-kotlin-support.json`](../releases/v0.7.0-kotlin-support.json). It intentionally distinguishes dependency-license declarations from the final release SPDX SBOM and keeps final SBOM assets, attestations, signing, publication, and independent downloaded-asset verification in I1.

## Executable evidence

- `BuildModelsTest.languageNeutralSourceSetFacetsValidateMultipleEcosystemsWithoutJavaOrMavenFields`
- `BuildModelsTest.buildModelChangesAreSnapshotHashBound`
- `KotlinJvmBuildModelTest` including Maven, Gradle, Android, Multiplatform, generated, plugin, and script cases
- `KotlinLanguageAdapterTest.descriptorPromotesBoundedCompilerReadsAndPrivateTypeRenameProposalOnly`
- `scripts/test-verify-v070-kotlin-support.py`
- `scripts/verify-v070-kotlin-support.py`
