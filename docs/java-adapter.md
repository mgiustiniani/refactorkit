# Java adapter

See AGENTS.md for the authoritative initial architecture and implementation rules.

## Compiler-backed analysis roadmap

ADR 0008 ([compiler-backed Java analysis strategy](adr/0008-compiler-backed-java-analysis-strategy.adoc)) selects Eclipse JDT as the primary `v0.3.0` prototype candidate for parsing, bindings, symbol identity, and reference precision.

The existing lexical/structural planners remain a safety fallback for areas not yet proven by JDT. Previews must keep fallback evidence visible and must warn or refuse ambiguous cases instead of claiming semantic certainty.

The JDT prototype must prove source-root/classpath discovery, binding-aware class and selected-member identity, reference disambiguation for same-name symbols, clear JDT-backed vs lexical-fallback reporting, and no regression of preview/apply/rollback safety. Representative Maven/Gradle sample tests now provide source-root evidence, and unresolved external type diagnostics now produce `JDT_PARSE` warning evidence, but broader classpath robustness remains unproven. OpenRewrite remains a possible future recipe/transformation backend, not the first symbol identity source.

## Current prototype status

The first `v0.3.0` P2 prototype adds Eclipse JDT parsing in `refactorkit-java`. `JdtJavaSemanticAnalyzer` parses Java source at Java 21 compliance, configures source roots from the scanned `ProjectSnapshot`, and currently collects class, interface, enum, method, field, and constructor declarations with qualified names, source path and line, optional binding keys, and evidence labels such as `JDT_BINDING`, `JDT_PARSE`, and `LEXICAL_FALLBACK`.

Member identities are now signed. Examples include `com.acme.User#displayName()`, `com.acme.Lookup#find(java.lang.String)`, and `com.acme.Lookup#<init>()`. `JdtJavaSemanticSymbol` exposes `ownerQualifiedName` and `memberSignature`; `JdtJavaSemanticReference` exposes `symbolSignature`. The analyzer visits `ClassInstanceCreation` so constructor references can resolve by JDT binding key, skips declaration-name `SimpleName`s instead of using line-based filtering, and emits `JdtJavaSemanticWarning` entries from JDT compilation errors with `JDT_PARSE` evidence for unresolved external type or classpath issues.

The analyzer returns `JdtJavaSemanticReference` entries for references whose JDT binding keys match collected symbols. Current tests show method/field identity and reference evidence, distinguish same-simple-name imports such as `com.acme.right.Service` versus `com.acme.left.Service`, cover interface and enum declarations, nested type/member ownership, overloaded method disambiguation, constructor identity/references, unresolved type warnings, and validate scanner-provided source roots on representative samples (`samples/java-maven-simple` expecting `com.example.UserManager` and `samples/java-gradle-simple` expecting `com.example.UserService`) with method-symbol discovery on both sample shapes. This is implementation evidence only: the existing lexical refactoring planners are not yet wired to the JDT analyzer.
