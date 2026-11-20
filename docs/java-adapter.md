# Java adapter

The effective Gradle provider preserves exported compile/runtime closure order and
source-set ownership. For JDT source filename lookup, visible own-main roots precede
test roots only when main outputs occur in that compile closure. Secondary test
declarations in a same-named file must not hide the actual main declaration. This
bounded adapter document links to [ARC42 requirements](arc42/appendix-requirements.adoc);
the [Java context protocol](java-project-context-protocol.md) owns wire authority.

See [Java stable capability matrix](java-capabilities.md) for the normative v1
write-authority inventory.

## Public effective project context

The daemon's read-only `project.context` route exposes snapshot-bound provider,
module, source-set, root, classpath and selected-source evidence. The
[project-context feature](../features/java-effective-project-context.feature)
now passes its six-case source-association Story on current source. It proves
that `pkg.Runner` and secondary top-level `pkg.Peer` bind to
`src/main/java/pkg/Runner.java`, while `pkg.Unrelated` binds to the same relative
path under `src/test/java`; a materialized generated root keeps its generated
owner. An unselected source and a stale build snapshot return typed refusals with
no `selectedSource`. The positive response carries the exact source root, module,
source set and JDT binding key.

The Java 8 fixture has no module descriptor. JPMS is now `NOT_DECLARED` for
explicit Java 1–8 targets without `module-info.java`, even when unrelated compiler
option facts are incomplete; Java 9+ still requires complete options before the
resolver declares JPMS absent. The focused Kotest Spec covers both boundaries.
This closes the bounded RefactorKit source-association cell only. Broader
provider/context completeness and native IDE payload-consumption acceptance stay
open; [ARC42](arc42/appendix-requirements.adoc) remains the integrated status
authority. JDI, build/run and editor markers stay with the IDE/JDK consumer.

## Build output discovery

Project discovery excludes undeclared project copies below `build` and `target` directories
at the workspace root and inside nested modules. Their sources and Maven/Gradle
descriptors do not contribute modules, captured build evidence, or snapshot
identity. Materialized generated sources are still collected through the real
module's recognized generated-source roots.

An explicit module declaration in an effective Maven model admits that module
even when its path contains `build` or `target`. Discovery follows declarations
recursively, including selected profiles, without scanning unrelated descendants
of those directories. Inactive profiles grant no exception. Admitted descriptors
are captured as snapshot inputs; declarations outside the workspace, through
symlink paths, or into `.git`, `.gradle`, or `.refactorkit` grant no exception.

The [declared-module Story](../features/java-scan-declared-output-module.feature)
has six locally passing cases for direct/nested modules and selected/inactive
profiles. This preserves a Maven reactor whose destination module is `target`,
including the daemon/MCP source-root move contract.

The bounded [scanner Story](../features/java-scan-output-directory-exclusion.feature)
has four locally passing cases, including generated Maven sources and unchanged
snapshot/build evidence after copy edits. Its status remains `@partial`; this is
source-built regression evidence, not release qualification. The
[ARC42 requirements appendix](arc42/appendix-requirements.adoc) owns integrated
architecture and requirement status.

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

This is an implementation-informed baseline, not completion of the active
product-critical Java/Maven diagnostics-authority epic. Module-level availability,
source compliance, a successful Maven build, or diagnostics from the running JVM
must not be presented as the complete source-set/release authority defined by
[`requirements/java-maven-authoritative-diagnostics.md`](requirements/java-maven-authoritative-diagnostics.md)
and ADR 0012.

## Evidence and apply authority

Native SDK25/26 diagnostics retain explicit source-set ownership. A test compiler
may implicitly load a main-source dependency and report its error with a different
range. RefactorKit publishes that source's error from its explicit owning
compilation group, preserving the original range. Independent errors with equal
code and message remain distinct; this is not message-based deduplication.
The provenance hash binds the explicit-source-set projection policy. Compiler
input validation, full acquisition and main/test classpath separation remain in
force. Six native release/source-target cases pass with independent javac oracles,
repairs and test-only visibility controls; broader qualification remains bounded.
The executable authority is
`REQ-JAVA-PROJECT-CONTEXT-001#native-compiler-diagnostics-retain-explicit-source-set-ownership`;
the [ARC42 appendix](arc42/appendix-requirements.adoc#native-diagnostic-source-ownership-2026-11-18)
owns integrated status.

Every Java compatibility plan classifies itself as `JDT_BINDING`, `STRUCTURAL`,
or `LEXICAL_FALLBACK`. Exact type/member binding operations use `JDT_BINDING`;
syntax-local transforms use `STRUCTURAL`. The low-level planner may still retain
an internal `LEXICAL_FALLBACK` diff for compatibility review, but core apply
refuses it as `evidence.insufficient` before lock or WAL creation. First-party
Java `moveClass` admission uses `JavaMoveClassOperationDispatcher` to project that
internal artifact as immutable edit-free `LEXICAL_FALLBACK_REVIEW` before generic
rendering, pending-plan registration, LSP edit conversion, or recipe aggregation.
This prevents a visible fallback warning from becoming accidental semantic write
authority.

Target-scoped Maven move authority also binds non-managed candidate-source files
through language-neutral `OperationAuthorityFileEvidence`. `PatchEngine` captures
the engine-owned source/auxiliary scope once under the workspace lock and compares
expected/observed file evidence before WAL. Drift returns immutable structured
`DiagnosticDetails` with the observed snapshot identity. Separately, a missing
root-declared active module POM produces result-owned
`JavaMoveClassStructuralRefusal`; symlink or non-regular descriptor paths are not
misclassified as ordinary missing files.

`moveClass` resolves an existing target package against declared, non-generated
Java source roots. For a new package, callers may explicitly select
`arguments.targetModuleId`; `server.capabilities` publishes this contract under
`javaContextRefactoring.operationCapabilities[operation=moveClass]`:

```json
{
  "schemaVersion": "java-move-class-target-module-v1",
  "argument": "targetModuleId",
  "identity": "module-id-within-source-provider",
  "requiresAvailableContext": true,
  "sourceSetKind": "main",
  "requiresUniqueNonGeneratedRoot": true
}
```

The daemon requires an available explicit Java project context whenever
`targetModuleId` is supplied. It resolves that ID only within the source module's
available build-model provider and accepts exactly one declared, non-generated
`main` source root. If the package already exists, its owner must match the
selected module. The selection never creates or edits a build descriptor. Without
an explicit module selection, an absent package keeps the legacy source-root
destination.

A cross-module move is admitted only when the destination has one exact owner in
the same available supported build model, both roots are main source sets, the
source and staged target resolve through JDT, and staged Java diagnostics
introduce no errors. Staged binding retention compares non-import references per
source file: JDT also reports imports as references, and a move may add imports
while preserving every existing use. The preview reports before/after diagnostics
and remains read-only. Ambiguous, mismatched, generated-only, unowned, or
unavailable cross-module destinations are refused. The selected acceptance Story
covers Maven only; other build providers and external consumers are not
qualified.

The IDE Product migration acceptance runs through daemon `project.open`,
`project.context`, and `refactor.preview` on a six-module Maven fixture with
compiled outputs and a generated source root. It moves
`pkg.MigrateToLibrary` from `maven/app` into the existing `fixtures.library`
package in `maven/library`. The regression confirms that the two original use
references remain bound after staging even though JDT reports two additional
import references.

## Java refactoring preview command boundary

The Java adapter now owns a sealed `JavaRefactoringPreviewCommand` hierarchy with
exactly five plan-only variants: `RenameClass`, `RenameMember`, `MoveSourceRoot`,
single-file `OrganizeImports`, and `SafeDelete`. The
`JavaRefactoringPreviewDispatcher` has no retained fields. Its public `preview`
call supplies an `ExistingJavaRefactoringPreviewPlannerInvoker` for that call; an
internal overload accepts the `JavaRefactoringPreviewPlannerInvoker` seam and
maps each variant to the existing planner without changing the supplied snapshot,
current adapter, typed values, returned `PatchPlan`, or escaping exception.

`JavaLanguageAdapter.applyRefactoring(RefactoringRequest)` remains the generic
compatibility decoder. Its five operation-specific decoders preserve existing
string/path/boolean conversion, the absent `safeDelete.force=false` default,
missing-argument refused plans, and unknown-operation behavior, then construct a
complete command and converge on the dispatcher. There is no parallel direct-
planner route for those five operations. Daemon and MCP use the same dispatcher
only after their own parsing and required-field validation.

The boundary is stateless and ends at plan return. It owns no `moveClass`
`Plan`/`Guidance`/`LexicalReview`, other-language/mixed/experimental/Maven route,
CLI/LSP or multi-file-import adoption, pending-plan policy, diagnostics,
filesystem write, `PatchEngine`, lock/WAL/transaction, apply/rollback, lifecycle,
protocol rendering, or persistence. `ManagedApplyInvoker` remains rejected and
absent. No external protocol schema or capability catalogue changed.

A missing-API compile RED has SHA-256
`3221bc7bc6aaef1a21117032ce6f22ea7c28ea8efb05a2797e2fec1beb3b5af8`.
After the initial REQ-003 source-only review block, corrected split runners passed
all four definitions/eight cases/94 steps on OpenJDK 21.0.11 (log SHA-256
`10c805b67306026cb4ab4a79de9b7258c712e28aaf0bb0070279edbbc44914f5`;
JSON SHA-256
`2059c28d57fde8f72b2e3f0de9fe6d95062c8de6cad40aeaeee3e4175a378ef6`
and
`999ee1a13d8577113113236e7b2241a5fac881e97327705eb1e4d1858ed83baa`).
The Java module regression reported 253 discovered, 252 executed, one tag-
filtered, and zero failures/errors within combined regression log SHA-256
`9a1cb587e7e377682a09353f470140a76e4dae1e5cb42a727f8ed346e2a0e9b1`.
Focused evidence remains source-built/in-process. Final dated mutable
whole-working-tree source-built verification on 2026-08-01 used OpenJDK 21.0.11
and
`./gradlew --no-daemon --rerun-tasks check goldenTest`: `BUILD SUCCESSFUL` in
6m 54s; 1045 tests were discovered, 817 executed, 228 skipped/tag-filtered,
zero failures/errors, 140 XML files were produced, and 79 tasks executed; log
SHA-256
`fddde07b954c36898252e510ad07758408f2c9b4e35b9102a5e71d7dd518d535`.
The targeted affected-module static command passed (log SHA-256
`8f29ec9da3ff6a96d0044423e8ac1f415b2651ce0870fab9b2b86d6f5f007c33`). Its
classification is deliberately non-zero: the full targeted log contains no
finding owned by `JavaRefactoringPreviewCommand`,
`JavaRefactoringPreviewDispatcher`,
`ExistingJavaRefactoringPreviewPlannerInvoker`, daemon `refactorPreview`, MCP
`toolPreviewRefactoring`, or the five changed generic decoder helpers. Exactly
48 reviewed/non-blocking slice-local glue findings remain: Java-module command
glue 24 (15 NP, 6 RCN, 1 Dm, 2 BC) and CLI real-surface glue 24 (13 NP, 7 RCN,
4 BC). Dispatcher-method references arise from generated Kotlin nullability
analysis at glue call sites, not production methods. Wider configured
SpotBugs/PMD baselines remain non-blocking; aggregate static cleanliness and
numerical coverage are not claimed. This dated source-built aggregate does not
qualify packaged, native, cross-platform, concurrent, crash-restart,
general-orchestration, or numerical-coverage behavior.

## Generated-source boundary

Generated Java remains analyzable for symbols/references but is never rewritten
by stable file-rewriter planners. A shared policy detects conventional generated
paths, `@Generated` variants, and generated/do-not-edit headers. Rename type,
move type, rename member, safe delete (including force), extract method, change
signature, and organize imports conservatively refuse before producing edits.

## Release-aware Maven authority

Status: active, product-critical. Release 8/current-release packaged diagnostics
and post-model module filtering are implemented; the full epic remains open.

For managed Java semantic operations, RefactorKit is the definitive authority and
Maven compile/test is supplementary evidence only. The declared full reactor root
remains workspace/model authority; module targeting filters after the active
reactor, source-set dependency closure, and affected dependent-impact closure are
constructed. No child request triggers implicit upward or outward discovery.

Effective Maven `--release 8..26` requires immutable, licensed, SBOM-covered,
hash-attested Java platform signatures. JDT grammar compliance and release flags
alone are insufficient, and the host or reduced jlink runtime is never a silent
substitute. Source-only/no-release configurations require an explicit qualified
JDK platform or typed unavailability. Main and test availability is independent;
only safely declared and already materialized generated sources are visible.
Named JPMS/module-path reactors return typed unsupported until a dedicated
complete slice is accepted. Discovery continues to prohibit implicit Maven,
wrapper, lifecycle, plugin, annotation-processor, credential, or build-code
execution.

Default staged policy rejects introduced errors; strict mode requires zero staged
errors. An unavailable affected source-set/impact environment always blocks, even
at baseline, and all authoritative rows bind snapshot/overlay mode, model and
classpath evidence, platform identity, source-set closure, and provider identity.

## Source compatibility

RefactorKit uses Eclipse JDT Core 3.47.0 with the JLS26 AST to parse Java source
levels 8 through 26 while retaining Java 8-compatible target bytecode for the
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
executes project plugins and is offline by default. Explicit provider-scoped
active/inactive profile IDs are bounded, validated, passed to ModelBuilder, and
hash-bound. Explicit network resolution is anonymous HTTPS Maven Central only,
disables redirects, applies bounded time/size limits, and requires a valid
bounded `.sha256` sidecar before atomically publishing every downloaded POM/JAR.
It never reads Maven settings credentials. Pre-existing local artifacts are
content-hash evidence for drift protection, not a claim of repository
provenance/authenticity.
Independent main/test source and class paths preserve Maven scope visibility,
including generated read-only roots. Gradle toolchain/source-compatibility
heuristics remain supported. The current compatibility scanner defaults
unconfigured projects to Java 8 grammar semantics; direct synthetic snapshots without module metadata
use the latest supported grammar level. Neither default is exact Java SE API
platform authority. Under the active ADR 0012 contract, source-only/no-release
semantic authority instead requires an explicitly qualified JDK platform or a
typed unavailable result.

The normalized `java.sourceLevel` is stored in hash-bound `Module.languageSettings`.
Maven/Gradle descriptors are independently fingerprinted as declaration evidence,
so a compliance change after preview refuses apply as `snapshot.classpathChanged`.
JDT receives the detected compliance for each source module. Acceptance covers
representative Java 8, 11, 17, 21, and 25 syntax, including Java 25 module-import
declarations; separate provider-context acceptance exercises Java 26 syntax and
binding for Maven and Gradle. Provider context v6/v7 separately exposes compiler
and selected-JDK platform metadata through GA Java 26/27. Java 27 metadata does
not qualify its parser or semantic operations: v7 may report owner-only source
selection, while symbol analysis remains `java.context.sourceAnalysisUnqualified`.
An unsupported parser level also remains separate from the actual classpath and
reactor dependency graph, so metadata capture does not discard otherwise
available Maven module edges. See [ARC42 Java context boundaries](arc42/08-crosscutting-concepts.adoc#java-analysis-and-refactoring)
and the [ARC42 requirements appendix](arc42/appendix-requirements.adoc).


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
