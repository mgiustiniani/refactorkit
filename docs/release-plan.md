# RefactorKit release roadmap

Status: active. [ARC42](arc42/04-solution-strategy.adoc) owns the integrated
release strategy. This roadmap summarizes published releases and development plans.

RefactorKit uses the `0.x` series to build and validate a deep multi-language
refactoring platform without a deadline-driven stable freeze. The first stable
`v1.0.0` is intentionally deferred until the complete language roadmap and
IDE-grade acceptance gates are satisfied.

## Published releases

| Release | Result |
|---|---|
| `v0.1.0-alpha` | First CLI/library preview, patch engine and Java MVP |
| `v0.2.0-beta` | Broader Java operations, integration contracts, golden and rollback workflows |
| `v0.3.0` | JDT analysis foundation, explicit version/API metadata, capability discovery and compatibility policy |
| `v0.4.0` | Transaction hardening, exact Java diagnostics and managed integration parity |
| `v0.5.0` | Native runtime matrix, Build Model SPI and bounded multi-language kernel |
| `v0.6.2` | Managed TypeScript/JavaScript semantic foundation |
| `v0.7.0` | Bounded Java/TypeScript/Kotlin and recovery qualification on Linux x86-64 |

Published tags are immutable. Historical detailed plans remain under
`docs/releases/`.

## Published release lines

### `v0.6.2` — managed TypeScript/JavaScript semantic foundation

Detailed plan: [`docs/releases/v0.6.0-plan.md`](releases/v0.6.0-plan.md).

`v0.6.2` publishes the completed `v0.6.0` scope: explicit hash-bound TypeScript tooling, declarative project models,
compiler/LSP semantic reads, stable opaque symbol identity, exact no-emit compiler
diagnostics, managed identifier rename, native apply/WAL/rollback, semantic
process restart and packaged kill-during-write startup recovery. API `0.2` remains
the active beta compatibility baseline.

Acceptance and migration evidence are recorded in
[`docs/releases/v0.6.2-acceptance.md`](releases/v0.6.2-acceptance.md) and
[`docs/releases/v0.6.0-migration.md`](releases/v0.6.0-migration.md). Advanced
TypeScript move/import/signature/extraction/recipe operations are explicitly
post-`v0.6.0` scope.

### `v0.7.0` — Kotlin and JVM interoperability

The published Linux x86-64 release adds bounded compiler-backed Kotlin behavior
and Java/Kotlin interoperability. See the
[release acceptance](releases/v0.7.0-acceptance.md) and
[Kotlin development plan](releases/v0.7.0-plan.md) for the exact scope.

## Active development line

### `0.8.0` — C and shared Clang foundations

The [C release plan](releases/v0.8.0-plan.md) covers C11 and C17 on Linux x86-64.
The [progress register](releases/v0.8.0-progress.md) records completed slices and
the remaining qualification before publication. API `0.2` is the compatibility
baseline.

[ADR 0009](adr/0009-supreme-multilanguage-v1-roadmap.adoc) records the approved
decision to deliver C before Python and defer multi-host qualification to
`1.0.0`. Interim releases qualify the platform they ship.

## Long-range 0.x sequence

The [1.0 roadmap](releases/v1.0.0-plan.md) defines the language sequence after
`0.8.0`. Later minor versions remain unassigned. Java depth continues in every
band.

[ADR 0010](adr/0010-offline-build-models-and-source-ownership.adoc) records
offline effective build models, typed cascade suppression, source ownership
and the Build Model SPI gate after Java 21 Maven/Magrathea acceptance.

The order can change through an explicit roadmap decision. Equivalent quality
means idiomatic IDE-grade capability, not identical operations for unlike
languages.

## Stable `v1.0.0`

Normative roadmap: [`docs/releases/v1.0.0-plan.md`](releases/v1.0.0-plan.md).

The stable release has two simultaneous goals:

1. **depth** — Java is the reference and widest adapter, while JVM languages,
   C#, TypeScript, C/C++, Objective-C, Swift, Go, Rust, Python and Clojure receive serious idiomatic
   compiler/language-server-backed refactoring catalogues rather than shallow
   wrappers;
2. **breadth** — the shared core safely orchestrates mixed-language projects,
   external semantic engines, capability/evidence reporting, preview, staged
   diagnostics, managed apply, rollback, limits and protocol integrations.

Clojure is the final planned language integration before alpha/beta/RC
stabilization. Its arrival is necessary but not sufficient: every adapter and
cross-language gate must pass.

## Universal release rules

- Never apply a semantic transformation without preview and explicit authority.
- External compiler/LSP workspace edits are untrusted proposals and always pass
  core normalization, validation, diagnostics and `PatchEngine`.
- Stable capability is declared per language and operation as `STABLE`,
  `EXPERIMENTAL`, `STRUCTURAL`, `REFUSED`, or `NOT_APPLICABLE`.
- Hidden lexical fallback is never stable.
- Generated, unresolved, reflective, macro/configuration-dependent, external or
  framework-string effects require evidence, warning, explicit narrowing, or
  refusal.
- Stable releases require clean build, golden/integration/protocol/stress tests,
  self-contained or toolchain-qualified packaging, checksum, SBOM,
  provenance/attestation, migration documentation, and independently verified
  downloaded artifacts.
- Release tags are never moved or recreated.
