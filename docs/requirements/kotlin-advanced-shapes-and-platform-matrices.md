# Kotlin advanced-shape evidence and unsupported-platform matrices

Status: candidate requirement; this document authorizes read-only modeling and stable refusal only, not additional mutation authority.

## REQ-KOTLIN-SHAPES-001 — Compiler-backed JVM shape facts

After a successful bounded K2 compilation, declaration evidence records additive immutable facts for:

- extension receivers on supported function declarations;
- suspend functions;
- companion objects;
- data, sealed and value classes;
- an exact JVM-name effect when compiler source name and generated JVM method name differ; and
- the already-proven JVM owner/name/descriptor, visibility and exact PSI ranges.

Value-class primary constructors are not fabricated as ordinary JVM constructors; the value type and field-backed constructor property remain modeled from their actual class-file evidence. These shape facts are `EXPERIMENTAL`, compiler-backed, read-only capabilities with `MutationAuthority.NONE`. They do not promote rename, move, change-signature, extract, inline, formatting, or any other operation for the shape.

Delegated properties are detected by compiler PSI and return stable `kotlin.symbolDelegatedPropertyUnsupported` instead of disappearing from an apparently complete catalogue. No delegated-property mutation is authorized.

## REQ-KOTLIN-MATRIX-001 — Separate fail-closed capability cases

The descriptor and build projection publish distinct cases for:

| Case | Qualified status | Stable boundary |
|---|---|---|
| Kotlin/JVM saved `.kt` | bounded compiler evidence | operation-specific requirements only |
| `expect`/`actual` and Multiplatform/common/native/JS | refused | `kotlin.platformUnsupported` / `expectActual` + `multiplatform` capabilities |
| Android Kotlin | refused | Android platform facet plus `kotlin.platformUnsupported` |
| compiler plugins, kapt/KSP semantics | refused | `kotlin.compilerPluginsUnsupported` / `compilerPluginSemantics` |
| generated-code mutation | refused | `generatedCodeMutation` |
| framework-aware Spring/JPA/Jackson semantics | refused | `frameworkAwareSemantics` |
| scripts | refused separately | `scriptSemantics` on `.kts` only |

A JVM source merely containing an annotation or framework-like name does not acquire framework authority. Android, Multiplatform, plugin and framework rows never inherit JVM mutation support. Detection/refusal is a supported safety outcome, not successful transformation.

## Non-claims

No general Kotlin Analysis API session, IDE formatter, coroutine/control-flow reasoning, extension/suspend mutation, delegated-property transformation, arbitrary JVM annotation semantics, `expect`/`actual` linking, Android variant resolution, Multiplatform source-set linking, compiler-plugin execution, generated-code ownership, or framework-aware string/configuration update is claimed.
