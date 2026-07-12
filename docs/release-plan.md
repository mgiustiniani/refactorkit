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

Published tags are immutable. Historical detailed plans remain under
`docs/releases/`.

## Current release

### `v0.4.0` — hardened Java and transaction foundation

Detailed plan: [`docs/releases/v0.4.0-plan.md`](releases/v0.4.0-plan.md).

Main reports `0.4.0`. API `0.2` remains the active beta compatibility
baseline until an explicit pre-1.0 migration is planned and tested.

The release publishes the already completed transactionality, Java 8–25 JDT,
structured evidence, generated-source refusal, module/classpath boundary, and
exact staged diagnostics work. Remaining gates cover contract/protocol
hardening, bounded resources, supply chain, packaging, migration, documentation,
and release acceptance.

`v0.4.0` is not an API `1.0` freeze and does not claim final multi-language or
IDE-grade catalogue completion.

## Long-range 0.x sequence

Planning bands are directional rather than deadlines. A language may span more
than one minor release and advances only when its semantic and safety evidence is
credible. Java depth continues in every band.

| Band | Primary focus |
|---|---|
| `0.5.x` | Multi-language adapter kernel, external semantic-process manager, real structural parsing, generalized evidence |
| `0.6.x` | TypeScript and JavaScript deep adapter |
| `0.7.x` | Kotlin and Java/Kotlin interoperability |
| `0.8.x` | Python deep adapter |
| `0.9.x` | Go deep adapter |
| `0.10.x` | Scala 2/3 and JVM interoperability |
| `0.11.x` | C/C++ Clang platform and component relocation |
| `0.12.x` | Groovy, Gradle Groovy DSL and Spock |
| `0.13.x` | C# Roslyn solution/refactoring platform |
| `0.14.x` | Rust and Cargo/rust-analyzer platform |
| `1.0` development | Clojure and global all-language stabilization |

The order can change through an explicit roadmap decision. Equivalent quality
means idiomatic IDE-grade capability, not identical operations for unlike
languages.

## Stable `v1.0.0`

Normative roadmap: [`docs/releases/v1.0.0-plan.md`](releases/v1.0.0-plan.md).

The stable release has two simultaneous goals:

1. **depth** — Java is the reference and widest adapter, while JVM languages,
   C#, TypeScript, C/C++, Go, Rust, Python and Clojure receive serious idiomatic
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
