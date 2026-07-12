# Java adapter

See [Java stable capability matrix](java-capabilities.md) for the normative v1
write-authority inventory.

## Compiler diagnostics

`JavaLanguageAdapter.diagnostics` reports JDT syntax and type-resolution errors
with stable codes (`java.jdt.syntax`, `java.jdt.typeResolution`), exact source
ranges, `COMPILER` evidence, and stable categories. Maven model/classpath/source-
level roots use `buildModel.unavailable`, `classpath.unavailable`, and
`sourceLevel.unavailable`; derivative unresolved-type cascades are suppressed per
affected module while genuine syntax errors remain. CLI `diagnostics --verbose`
and daemon `diagnostics {"verbose":true}` expose unsuppressed JDT details for
investigation. Structural package/file-name/
duplicate diagnostics use `STRUCTURAL` evidence and `PROJECT_STRUCTURE`.

For staged validation, Java sources are written to an isolated temporary overlay;
JDT resolves the exact post-image against that overlay and original external
classpath entries. No Maven/Gradle build script is executed. Explicit planner-declared post-preview
errors (for example forced safe delete) are treated as approved diagnostics;
`PatchEngine` still blocks every additional unapproved regression. Daemon responses and
LSP diagnostic `data` expose evidence/category fields.

## Evidence and apply authority

Every Java plan classifies itself as `JDT_BINDING`, `STRUCTURAL`, or
`LEXICAL_FALLBACK`. Exact type/member binding operations use `JDT_BINDING`;
syntax-local transforms use `STRUCTURAL`. An unclean/unresolved analysis may still
produce a `LEXICAL_FALLBACK` diff for review, but core apply refuses it as
`evidence.insufficient` before WAL creation. This prevents a visible fallback
warning from becoming accidental semantic write authority.

## Generated-source boundary

Generated Java remains analyzable for symbols/references but is never rewritten
by stable file-rewriter planners. A shared policy detects conventional generated
paths, `@Generated` variants, and generated/do-not-edit headers. Rename type,
move type, rename member, safe delete (including force), extract method, change
signature, and organize imports conservatively refuse before producing edits.

## Source compatibility

RefactorKit uses Eclipse JDT Core 3.44 with the JLS25 AST to parse Java source
levels 8 through 25 while retaining Java 8-compatible target bytecode for the
library contract. `JavaProjectScanner` builds Maven effective models with embedded Maven
ModelBuilder, resolving relative parents, inherited/interpolated properties,
compiler release/source, dependency management, imported BOMs, active-by-default
profiles, reactor coordinates/edges, local-repository classifier variants,
`test-jar` artifacts, explicit Maven `systemPath` files, effective
`sourceDirectory`/`testSourceDirectory`, and active-profile declarative
`build-helper-maven-plugin` add-source roots. Build-helper configuration is read
as metadata only; no goal or plugin executes. Inactive-profile roots are absent,
and source declarations outside the workspace or through external symlinks fail
closed without disclosing host paths. Dependency mediation
uses Maven variant identity (GA + type + classifier), so a normal JAR and tests
JAR are not incorrectly collapsed. System paths must resolve to existing absolute
regular files, are not treated as reactor source edges, and receive dedicated
hash-bound evidence without exposing full paths in model diagnostics. It never
executes project plugins and is offline by default; explicit network resolution
is anonymous Maven Central only and never reads Maven settings credentials.
Independent main/test source and class paths preserve Maven scope visibility,
including generated read-only roots. Gradle toolchain/source-compatibility
heuristics remain supported. Unconfigured projects default
to Java 8 semantics; direct synthetic snapshots without module metadata use the
latest supported level.

The normalized `java.sourceLevel` is stored in hash-bound `Module.languageSettings`.
Maven/Gradle descriptors are independently fingerprinted as declaration evidence,
so a compliance change after preview refuses apply as `snapshot.classpathChanged`.
JDT receives the detected compliance for each source module. Acceptance covers
representative Java 8, 11, 17, 21, and 25 syntax, including Java 25 module-import
declarations.


See AGENTS.md for the authoritative initial architecture and implementation rules.

## Compiler-backed analysis roadmap

ADR 0008 ([compiler-backed Java analysis strategy](adr/0008-compiler-backed-java-analysis-strategy.adoc)) selects Eclipse JDT as the primary `v0.3.0` prototype candidate for parsing, bindings, symbol identity, and reference precision.

The existing lexical/structural planners remain a safety fallback for areas not yet proven by JDT. Previews must keep fallback evidence visible and must warn or refuse ambiguous cases instead of claiming semantic certainty.

The JDT prototype must prove source-root/classpath discovery, binding-aware class and selected-member identity, reference disambiguation for same-name symbols, clear JDT-backed vs lexical-fallback reporting, and no regression of preview/apply/rollback safety. Representative Maven/Gradle sample tests provide source-root evidence, the Gradle multi-module sample proves cross-module sourcepath interface references and override relations, and the scanner supplies conventional compiled output directories (`target/classes`, `target/test-classes`, `build/classes/java/main`, and `build/classes/java/test`) plus project-local JARs under `lib`/`libs` to JDT. It also reads generated dependency lists from `.refactorkit/classpath`, `target/classpath.txt`, and `build/classpath.txt`; each non-comment line may contain one path or a platform-separated path list, relative entries resolve from the module root, and missing/non-JAR files are ignored. A test-generated Maven-style `target/classpath.txt` proves that an otherwise unresolved external type becomes clean binding evidence. The scanner records SHA-256 evidence for active entries, prospective conventional output directories, local JAR directories, generated classpath declarations, parent/effective POMs, imported BOMs, local artifacts, source-level inputs, and module edges; `PatchEngine` recomputes it under lock before journaling and refuses stale evidence. Maven plugins and arbitrary lifecycle code are never invoked. OpenRewrite remains a possible future recipe/transformation backend, not the first symbol identity source.

## Current prototype status

The first `v0.3.0` P2 prototype adds Eclipse JDT parsing in `refactorkit-java`. `JdtJavaSemanticAnalyzer` parses Java source at Java 21 compliance, configures source roots and discovered conventional classpath entries from the scanned `ProjectSnapshot`, and currently collects class, interface, enum, record, annotation-type, method, field, and constructor declarations with qualified names, source path and line, optional binding keys, and evidence labels such as `JDT_BINDING`, `JDT_PARSE`, and `LEXICAL_FALLBACK`.

Member identities are now signed. Examples include `com.acme.User#displayName()`, `com.acme.Lookup#find(java.lang.String)`, `com.acme.Route#path()` for an annotation element, and `com.acme.Lookup#<init>()`. `JdtJavaSemanticSymbol` exposes `ownerQualifiedName`, `memberSignature`, and `sourceRange`; `JdtJavaSemanticReference` exposes `symbolSignature` and `sourceRange`. Source ranges use JDT evidence for exact declaration/reference text edits, with JDT's zero-based columns mapped directly to `SourceRange` characters. The analyzer visits `ClassInstanceCreation` so constructor references can resolve by JDT binding key, skips declaration-name `SimpleName`s instead of using line-based filtering, and emits `JdtJavaSemanticWarning` entries from JDT compilation errors with `JDT_PARSE` evidence for unresolved external type or classpath issues.

The analyzer returns `JdtJavaSemanticReference` entries for references whose JDT binding keys match collected symbols. It also reports `JdtJavaSemanticOverrideRelation` entries for source-visible method override/inheritance evidence, combining JDT `IMethodBinding.overrides`/subsignature checks with scanner-visible extends/interface inheritance records as a conservative fallback. Current tests show method/field identity and reference evidence, distinguish same-simple-name imports such as `com.acme.right.Service` versus `com.acme.left.Service`, resolve static method import and invocation ranges, cover interface, enum, record, and annotation-type declarations, nested type/member ownership, overloaded method disambiguation, constructor identity/references, a child method overriding its base method, interface implementation override evidence where `DefaultLookup#find(java.lang.String)` implements/overrides `LookupApi#find(java.lang.String)`, unresolved type warnings, and validate scanner-provided source roots on representative samples (`samples/java-maven-simple` expecting `com.example.UserManager`, `samples/java-gradle-simple` expecting `com.example.UserService`, and `samples/java-multimodule` resolving `UserService` to `UserApi` across module source roots).

`renameMember` previews now use exact JDT binding evidence for unambiguous field selectors, signed method selectors, and source-visible override families. For selectors such as `com.example.Lookup#find(java.lang.String)`, the planner resolves exactly one JDT method candidate, requires a binding key, computes the transitive connected override family, and emits edits from every selected declaration/reference `sourceRange`; unrelated overloads such as `find(int)` remain unchanged. Class inheritance and interface implementation tests verify that base/interface-typed and implementation-typed call sites change together, while unrelated same-signature methods remain unchanged. Static method imports are included as binding-matched reference ranges, so a proven signed rename updates the declaration, static import, and invocation together. Static method hiding is excluded from override relations. The signed flow refuses when JDT reports parse/classpath warnings, when the selector is not an exact single-candidate match, when binding evidence is missing, or when the override family contains an external declaration outside the scanned source workspace, such as `Object#toString()`. Unambiguous fields use exact declaration/reference ranges when analysis is clean, preserving shadowing locals and same-name fields in unrelated owners; existing target fields are refused. Other unsigned member rename remains a lexical fallback and can still rename all method overloads with warnings.

Read-only Java adapter lookup is also starting to consume JDT evidence for exact signed member IDs and source positions. Existing unsigned symbol lookup remains lexical for compatibility, but `searchSymbols`, `findSymbol`, `findReferences`, and `resolveSymbol` can now resolve selectors such as `com.example.Lookup#find(java.lang.String)` using the JDT binding key when the analysis is clean. Daemon `symbol.search`/`symbol.definition`/`symbol.references`, MCP `symbol_search` plus symbol definition/resource paths, CLI `definition`/`references`, and LSP definition/reference/rename-position resolution therefore support exact signed member selectors without broadening mutating planner authority beyond proven slices. Class rename now uses exact JDT ranges for the selected type declaration, constructor declarations, and binding-matched references when analysis is clean, preventing unrelated same-simple-name types from changing; existing targets are refused and unclean analysis produces an explicit lexical fallback warning. Move class uses the selected type binding to scope package/import/FQN changes to actual referencing files, preventing imports from being inserted into unrelated old-package files and avoiding same-simple-name confusion; invalid packages, existing targets, and overlapping direct-import/FQN edits are handled safely. Safe delete similarly uses exact JDT type-binding references when clean. Organize imports consumes JDT binding-use evidence for exact imports in clean files and normalizes parameterized type and generic method bindings to their declarations before deciding usage; wildcard/unresolved imports are preserved, and unclean files continue lexical sorting, deduplication, and same-package removal only. Limited change-signature accepts signed method selectors for single-method cases by normalizing the selector back to the method name, while overloaded methods remain refused. Annotation elements are represented as zero-parameter signed methods; exact lookup/reference queries and signed rename update the declaration plus binding-matched named annotation usages. Implicit single-element annotation values require no name edit.
