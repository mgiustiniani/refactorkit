# Java adapter

See AGENTS.md for the authoritative initial architecture and implementation rules.

## Compiler-backed analysis roadmap

ADR 0008 ([compiler-backed Java analysis strategy](adr/0008-compiler-backed-java-analysis-strategy.adoc)) selects Eclipse JDT as the primary `v0.3.0` prototype candidate for parsing, bindings, symbol identity, and reference precision.

The existing lexical/structural planners remain a safety fallback for areas not yet proven by JDT. Previews must keep fallback evidence visible and must warn or refuse ambiguous cases instead of claiming semantic certainty.

The JDT prototype must prove source-root/classpath discovery, binding-aware class and selected-member identity, reference disambiguation for same-name symbols, clear JDT-backed vs lexical-fallback reporting, and no regression of preview/apply/rollback safety. OpenRewrite remains a possible future recipe/transformation backend, not the first symbol identity source.
