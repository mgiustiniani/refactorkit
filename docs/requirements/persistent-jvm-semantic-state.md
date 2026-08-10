# Persistent bounded JDT and K2 semantic state

Status: implemented candidate; promotion requires focused regression, four-platform execution, and independent review.

## Requirement

`REQ-PERSISTENT-JVM-SEMANTIC-STATE-001` extends the workspace intelligence foundation with session-owned, bounded provider state.

1. JDT state shall be keyed by a normalized Java semantic-input hash, not the whole mixed-language snapshot hash.
2. An unrelated Kotlin/TypeScript source change may reuse the exact immutable JDT semantic payload while result and cache attestations are rebound to the caller's current snapshot hash.
3. Any Java source, relevant build model, classpath, auxiliary build descriptor, module, or platform-evidence change shall miss the JDT state.
4. K2 diagnostics and symbol reads for one exact snapshot shall share one normalized compiler result within a bounded session.
5. K2 snapshot changes shall miss; stale or mismatched provider attestations shall never enter the session.
6. Both providers shall use small access-order LRU bounds, explicit clear operations, deterministic status counters, no global mutable state, and no compiler-internal handle in API `0.2`.
7. Ephemeral additional-classpath/output operations remain outside the ordinary K2 cache because their authority and lifetime differ.

## Clarification

This row qualifies persistent normalized provider state and exact session integration. It does not claim serialization of compiler objects, reuse across process restart, unbounded daemon lifetime, K2 compiler-plugin execution, general incremental compilation, or I1 crash/restart/stress coverage.

## Executable evidence

- `JdtJavaAnalysisCacheTest.reusesNormalizedJdtStateAcrossUnrelatedLanguageSnapshotChanges`
- existing JDT LRU/limit/cancellation tests
- `KotlinCompilerAnalysisSessionTest`
- Kotlin adapter regressions proving diagnostics/symbol routing through the session
