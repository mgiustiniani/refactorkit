# RefactorKit release roadmap

Status: active.

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

Published tags are immutable. Historical detailed plans remain under
`docs/releases/`.

## Latest release line

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

## Long-range 0.x sequence

Planning bands are directional rather than deadlines. A language may span more
than one minor release and advances only when its semantic and safety evidence is
credible. Java depth continues in every band.

| Band | Primary focus |
|---|---|
| `0.5.x` | Native Windows/macOS self-contained runtime matrix; completed Java 21 Maven reactor/source-ownership reference slice; Build Model SPI and Maven/Gradle productionization; multi-language adapter kernel, external semantic-process manager, real structural parsing and generalized evidence |
| `0.6.x` | TypeScript and JavaScript deep adapter |
| `0.7.x` | Kotlin and Java/Kotlin interoperability |
| `0.8.x` | Python deep adapter |
| `0.9.x` | Go deep adapter |
| `0.10.x` | Scala 2/3 and JVM interoperability |
| `0.11.x` | C/C++/Objective-C Clang platform, component relocation and Apple runtime boundaries |
| `0.12.x` | Swift, SwiftPM/Xcode model and Objective-C interoperability |
| `0.13.x` | Groovy, Gradle Groovy DSL and Spock |
| `0.14.x` | C# Roslyn solution/refactoring platform |
| `0.15.x` | Rust and Cargo/rust-analyzer platform |
| `1.0` development | Clojure and global all-language stabilization |

Active plan: [`docs/releases/v0.6.0-plan.md`](releases/v0.6.0-plan.md).
ADR 0010 records offline effective build models, typed cascade suppression,
source-identity/ownership separation, and the Build Model SPI gate added after the
Java 21 Maven/Magrathea acceptance slice.

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
