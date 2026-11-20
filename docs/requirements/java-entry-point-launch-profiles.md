# Java entry-point launcher profiles: implementation and qualification

Requirement: existing `REQ-JAVA-PROJECT-CONTEXT-001` / M2.
Authority: this is a planning satellite of
[ARC42](../arc42/appendix-requirements.adoc#java-entry-point-java25-upgrade-contract-2026-11-15).
The existing [feature](../../features/java-effective-project-context.feature)
owns executable behavior. Each implementation Story needs its own fresh receipt
and effective-glue inventory. Bounded source qualifications below do not imply
completion of all matrices, packaged integration or the release.

The IDE requests source/cursor Run selection for inherited entry points and
Java 25 launch forms, without computing launchability locally. Current
`classic-declared-v1` accepts only declared public static `void main(String[])`
methods. Its schema, IDs, capability flags and refusal behavior remain unchanged
until a separately qualified successor is available.

## Profiles and wire compatibility

Reuse the existing planned profile identities:

- `inherited-v1`: classic public static `main(String[])` selected for the actual
  launch type through native inheritance facts, including a separate declaring
  owner. Each supported launcher/source/target combination needs evidence.
- `java25-v1`: the Java 25 launcher rules, including inherited methods,
  static/instance invocation, zero/String-array parameters, precedence,
  construction eligibility and GA compact source units. Initial launcher
  qualification targets the explicitly selected JDK 25; other launchers remain
  unqualified until separately measured.

Profiles select method shapes from an independently qualified source inventory.
In the SDK25/source25/target25 non-JPMS cell, `inherited-v1` also accepts native
compact owners with public static `main(String[])`. Compact instance/no-argument
methods fall outside this profile and produce no candidate, without suppressing
classic candidates elsewhere in a mixed scope. Their constructors do not create
construction exclusions for this profile. A compact source still requires GA25
source/target qualification; incomplete inventories, diagnostics and stale or
unqualified authority retain their refusals. Skipping compiler analysis of a
nonmatching source form is not permitted.

A proposed `java-entry-points-v2` response preserves the closed v1 branch. It
retains request/snapshot/index/context/schema/scope/platform correlations,
bounded deterministic output and explicit coverage. It must additionally make
the following facts unambiguous:

| Fact | Required authority |
|---|---|
| `binaryName` | Native launch-type binary owner; never inferred from a filename. |
| `declaringBinaryName` | Native owner of the selected main, separately identified for inheritance. |
| `descriptor` and `invocation` | Exact `()V` or `([Ljava/lang/String;)V`, with static/instance mode. |
| Launch/declaration locations | Provider-owned launch source/range and separately attributed declaration origin; unavailable external source ranges stay unavailable. |
| Instance creation | Native constructor/access/type facts proving static eligibility; runtime initializer/constructor success is not guaranteed. |
| Launcher | Selected SDK/home/platform relationship, release and executable identity, separately attested instead of inferred from the engine JVM. |
| Coverage | Qualified forms/profile, exact scope completeness, truncation and eligible counts; incomplete coverage cannot establish zero entry points. |

The final v2 field layout requires a closed schema and matching runtime producer
before advertisement. No new method alias, fallback to workspace-symbol results
or local IDE inference is introduced by this plan. The current v1
`compact-preview-v1` placeholder does not turn GA Java 25 compact units into a
preview feature; actual preview modes retain separate qualification.

## Selection and qualification

The normative launcher rules are
[JLS 25 §12.1.4](https://docs.oracle.com/javase/specs/jls/se25/html/jls-12.html#jls-12.1.4).
Candidate accessibility, inheritance and method selection must come from native
bindings. String-array forms take precedence over no-argument forms. Instance
invocation needs eligible construction; discovery must not execute application
code to test it. Compact ownership must come from the native implicit type.

The existing JDT 3.47.0 dependency contains `ImplicitTypeDeclaration`, but current
entry-point projection does not inventory that type or inherited main members.
API presence does not qualify bindings or discovery. Native source/target and
launcher rules remain distinct; Java 26/27 metadata does not qualify them.

Admit separate bounded slices, preserving the complete final requirement:

1. Pure launcher selection over complete detached native facts: declared and
   inherited shapes, parameter precedence, access and construction eligibility.
2. Native compiler hierarchy/implicit-type extraction: exact owners/ranges, incomplete
   bindings, hiding/overrides, interface defaults and ambiguous ownership.
3. Public v2 response/schema/capabilities: source/cursor selection, exact SDK and
   source-set context, stale authority, limits, cancellation and read-only proof.
4. Independently exercised packaged candidate and IDE handoff with source,
   schema/profile, SDK and archive identities.

Each Story selects at most six expanded cases by default. Invalid constructors,
private/unsupported methods, absent or recovered hierarchy facts, incompatible
launchers and incomplete required coverage must produce explicit outcomes.
Preserve classic profile and existing refusal/cancellation regressions. No
partial slice closes the inherited/Java25/compact requirement.

## Current evidence

The first implementation subject is a pure Kotlin launcher-selection policy,
specified with the already approved Kotest 5.9.1 framework. Its input is a
complete, non-truncated inventory of effective native main members, their
declared parameter shapes, exact descriptors/owners/access/invocation, selected
source/target, construction evidence and matched compiler/launcher SDK identity.
It must never reconstruct inheritance or declared types from names or erasure.
Missing/inconsistent authority returns a typed refusal, distinct from an
authoritative absence of a candidate.

Initial policy release cells are classic inheritance on launchers 21/25 and the
Java25 profile on launcher25. Compact source requires source/target25. The
String-array candidate takes precedence; static invocation needs no constructor.
Instance invocation requires native evidence for the launch type's eligible
zero-argument constructor. Other construction forms remain explicitly
unqualified. Ambiguous eligible members and incomplete native inventories
refuse. Detached member collections remain immutable. Passing these domain
specifications alone cannot advertise a native provider profile.
Compact binary owners remain opaque native names bounded in UTF-8; do not apply
the `TypeIdentifier` restrictions of explicit class declarations. The IDE reports
nine separate production-builder/javac/launcher name cases, including contextual
keywords and Unicode. That is consumer-reported launcher evidence, not JDT
discovery qualification; the provider still needs native ownership proof.
The later consumer report also retains U+200C in compact binary filenames.
Keep identifier-ignorable characters in the native owner verbatim; neither
normalization nor a reconstructed explicit-type identifier may replace it.

`.refactorkit/runs/ide-java25-entrypoint-upgrade-plan-r001/native-launcher-research.json`
records first-party source compilation and seven real JDK 25 launcher probes:
inherited classic, instance/String-array, instance/no-arg, static/no-arg,
parameter precedence and compact sources launch; an instance main without
eligible no-argument construction does not execute. SDK artifacts remain
unchanged. These probes concern the launcher only. Provider profiles,
capabilities and a new candidate remain `actionable`.

## Selected SDK compiler acquisition

The first native acquisition Story retains all six cases: compact `Compact`,
contextual-keyword `record`, `Or\u200Cder` with the exact U+200C owner, protected
instance/String-array main, inherited static main with distinct launch/declaration
owners, and an explicit argument-only constructor. Its original JDT baseline is
preserved: JDT 3.47.0 reports a false TypeIdentifier error for valid compact
`record.java`. Diagnostics are neither discarded nor reclassified to admit it.

The opt-in acquisition now uses the selected SDK25's compiler through a bounded
managed process. It invokes `JavacTask.parse/analyze`, disables annotation
processing and implicit compilation, installs an output-denying file manager and
never calls `generate` or `CompilationTask.call`. The first-party worker itself
is source-launched in memory; application classes are not loaded or executed.
`Elements.getBinaryName`, `getAllMembers`, declared constructors and hierarchy
bindings supply owners, effective methods, descriptors, declared parameter shape
and construction facts. No inheritance or binary-owner reconstruction is used.

Java25's public tree/element APIs have no implicit-class predicate. The worker
reads the selected compiler's native `IMPLICIT_CLASS` flag through two explicit
module exports and refuses if that ACL is unavailable. Explicit type ranges come
from native source positions. An implicit type has no native end position, so its
location is the owning compilation-unit span; its binary owner is still the
compiler value. Collections are detached and immutable.

The current acquisition slice requires an explicit bounded non-JPMS source25 /
target25 scope and caller-supplied compile classpath. It binds SDK metadata,
platform signatures/modules and both launcher/compiler executable hashes, checks
the worker's actual `java.home`, preserves UTF-8 filenames through an explicit
locale, revalidates source/classpath/SDK inputs, owns process termination and
removes its private overlay. Compiler errors, missing/inconsistent facts,
cancellation, time/output/input limits and unavailable acquisition return typed
refusals; a partial inventory cannot establish absence.

Evidence: `.refactorkit/runs/ide-java25-compiler-launch-facts-r001/` retains the
fresh analyzer receipt (three reused steps, one new, zero ambiguity), six native
RED cases and the subsequent six-case / 24-step GREEN. SDK/source hashes,
external execution marker, class-file absence, process exit and overlay removal
are checked. This proves detached acquisition for these cells only. Hiding,
overrides, interface defaults, ambiguous/missing bindings, active cancellation,
additional source/target/launcher and JPMS cells, public v2 production/schema,
capabilities and independently packaged IDE handoff remain `actionable`.

A separate native hierarchy Story exercises six additional exact compiler
cells: an overriding instance main and a hidden static main both select the child
member; an inherited interface default retains its interface declaring owner;
a private ancestor main is present in declared hierarchy facts but absent from
effective members; an abstract inherited main retains its abstract flags; and
`T[]` with a `T extends String` bound retains declared shape `OTHER` even though
its JVM descriptor erases to String-array. These are native acquisition facts,
not proof that every such member is launchable. Existing classic analysis and
all read-only controls remain checked in every case. Evidence:
`.refactorkit/runs/ide-java25-native-hierarchy-facts-r001/`. No new public profile
or completeness claim is introduced by this characterization.

Its first native run found an actual static-hiding defect: `getAllMembers`
retains both the child and hidden ancestor declarations. The acquisition now
uses native `Elements.hides` / `Elements.overrides` to exclude superseded
members, preserving distinct overloads and the complete declared hierarchy.
This is native compiler selection, not a descriptor/name-based hierarchy rule.
The one failing case and five passing controls are retained before the fix.

Six separate refusal controls require exact typed outcomes with no available
partial inventory: pre-launch cancellation, incompatible SDK release, stale SDK
identity, an actual native compiler source error, unqualified source release and
a missing compile-classpath entry. Each case retains classic analysis, original
source/SDK hashes, no application execution or emission and no surviving child
process. Evidence: `.refactorkit/runs/ide-java25-native-launch-refusals-r001/`.
This does not qualify in-flight cancellation, timeout or output-exhaustion cells.

## Native facts to selection

The pure Kotlin `JavaNativeLaunchSelector` now connects a captured source/type inventory to
`JavaMainLaunchPolicy`. Its approved-Kotest specifications require an exact
capture snapshot/SDK identity, source ownership, complete native effective-member
inventory, closed hierarchy and consistent declared method facts. Missing or
inconsistent acquisition must remain a typed refusal, including when an empty
list would otherwise look like an authoritative absence of main methods.

Construction is derived only from the launch class's own native kind, nesting,
abstract/access flags and declared constructors. It must never borrow an ancestor
constructor. Non-private zero-argument construction qualifies ordinary classes,
static member classes and zero-component records; private/argument-only
constructors, abstract classes, non-static members and enum/interface instances
are ineligible. Local/anonymous construction remains unproved rather than guessed.
Static invocation does not require construction. Native declared parameter shape
is preserved even when an erased descriptor resembles a launch signature.
This domain bridge alone does not activate a public profile or qualify a new
SDK/source/target cell.

Six domain specifications pass after a six-failure RED. Acquisition now carries
its exact snapshot, source/target/launcher releases and worker resource hash;
native effective-member completeness is explicit and defaults to unavailable.
Snapshot and worker identities are captured before the subprocess and checked
again afterwards. Constructor descriptors retain declared language-model
parameters; they do not invent synthetic outer-instance/capture parameters.
Native nesting/kind flags therefore remain mandatory for construction proof.
The bridge validates graph closure, ownership, declared/effective agreement and
descriptor syntax/arity without recreating inheritance or declared types. It
does not accept a lost root main as an empty complete inventory.
Evidence: `.refactorkit/runs/ide-java25-native-selector-spec-r001/`.

A separate six-case Story feeds actual SDK25 capture into this bridge for the
three compact names, protected instance main, inherited static main and rejected
argument-only construction. Before any launcher control, the engine path must
pass the unchanged-source/SDK, no-emission, no-execution and lifecycle checks.
The test then compiles identical fixture bytes in a separate private directory
and invokes the explicitly selected javac/java. Five selected owners must execute
the fixture main; the constructor refusal must match actual launch failure with
no main execution. The deliberate test-only execution does not run inside the
discovery engine. Evidence:
`.refactorkit/runs/ide-java25-native-selector-launcher-r001/`.

A second native comparison Story covers private constructors, abstract launch
classes, non-static members, empty records, static member classes and parameter
precedence. Exact native class/member/constructor facts must agree with the real
SDK25 launcher. The precedence fixture prints distinct `ARRAY` and `ZERO` markers,
so executing an incorrect overload cannot pass merely because the class ran.
Engine read-only checks precede deliberate test-only execution; original sources
and SDKs remain unchanged. Evidence:
`.refactorkit/runs/ide-java25-native-construction-launcher-r001/`.

## Declaration location and binary provenance

The native origin Story binds one exact main declaration to either its owning
source/range or an attested binary input. `Trees.getPath` and native source
positions provide the whole method declaration span, including modifiers/body.
The workspace mapping must resolve to an actual captured source; inherited
declarations retain their distinct declaring owner and source span. Compact
owners and Unicode paths remain native values.

Without a source tree, SDK25 `Elements.getFileObjectOf` supplies the actual
compiler URI. Binary facts have no fabricated source range. The ACL accepts
only captured classpath directory/JAR inputs or the selected SDK's attested
signature/module image. It retains native URI escaping and the input SHA256;
`ct.sym` origins stay signature origins rather than being relabelled as jrt.
Unattributed locations remain explicitly unavailable. No URI is opened and no
path is reconstructed from the binary owner. The closed private worker protocol
advances its magic to prevent interpreting older payloads as origin-bearing facts.

Six cases cover a U+200C compact owner, inherited source main, interface default,
separately compiled dependency directory/JAR with spaces in their paths, and
`com.sun.tools.javac.Main` inherited from the selected SDK's signature archive.
Dependency byte images remain unchanged; the engine neither compiles nor executes
application classes. First-party dependency compilation happens only in fixture
setup. Evidence: `.refactorkit/runs/ide-java25-native-origins-r001/`.
Provider compile-closure and snapshot/context correlation, further matrices,
active lifecycle and public v2/schema/capability/candidate gates remain actionable.

## Provider compilation scope

The application now acquires the provider-qualified module/source set through
`JavaBoundContext.read`, retaining its global snapshot and effective context hash
and the existing fresh-scan checks after a query. The native compiler receives a
separately identified snapshot containing the provider environment's exact source
closure. Test compilation includes visible main sources; reactor compilation
includes declared compile dependencies. Only sources owned by the requested set
are candidates. Unrelated modules and test files cannot enter by workspace-wide
scanning or by filtering candidates only after a broader compilation.
The reactor control includes an unrelated release21 module, proving that native
release25 compilation does not accidentally require every workspace module to
use the selected closure's source level.

The initial native cell requires a homogeneous non-JPMS release25 closure and
SDK25. Every included source has unique provider ownership and complete compatible
compiler facts. Mixed source cells and unqualified compiler arguments refuse;
other matrices remain required. A missing classpath entry is never silently
ignored. Only an exact declared project output whose complete source roots are
already included can be represented by those sources. The engine does not build
the project to materialize an output.

Six actual Maven provider cases cover main/test selection, an app-to-library
reactor with an unrelated module, mixed-release refusal, a missing source set and
a changed global snapshot. Global context and scoped compiler hashes remain
distinct, native inherited selection uses the scoped hash, and source/SDK/process
state stays unchanged. Evidence:
`.refactorkit/runs/ide-java25-native-provider-context-r001/`.
This application link does not yet activate public v2 or qualify every provider,
source/target/launcher or JPMS combination.

## Opt-in public response v2

`intelligence.query` retains `kind=entryPoints` and the existing profile names.
Set `entryPointSchemaVersion` to `java-entry-points-v2` to request the native
response for `java25-v1` or `inherited-v1`. Omitting the selector retains the
complete v1 response/profile/refusal behavior. Unknown schema selectors reject;
there is no response-version fallback or new method alias.

The closed v2 response binds the global snapshot/index/context and exact scope,
the distinct `analysis.snapshotHash`, native worker and ordered classpath hashes,
and the selected SDK/launcher/compiler executable identities. Its qualified cell
is non-JPMS source25/target25/platform25/launcher25 with preview disabled. The
coverage names the requested profile only; other source/target/SDK/provider and
preview cells still need independent qualification.

Each candidate separates `binaryName` from `declaringBinaryName`, gives the exact
descriptor and static/instance invocation, and distinguishes the launch type's
`source` range/form from the main's `declaration` source or binary origin.
Instance selection includes its proven zero-argument `construction`; static
selection omits construction. Unsupported selection or declaration facts produce
typed refusals rather than a complete empty response. Full scope analysis precedes
path/cursor selection and output limiting. Truncation makes `complete=false` while
retaining the exact eligible count. A complete no-main result requires complete
qualified native inputs. Deterministic IDs/provenance exclude temporary overlay
paths, PIDs, timestamps and request IDs.

Six public-protocol source cases pass native facts, full Draft2020 schema checks,
closed-response negative checks, stable repeat identities, retained v1 behavior
and stale snapshot/SDK-context guards. They invoke the protocol used by the daemon
in-process; stdio and a packaged artifact remain separate gates. Evidence:
`.refactorkit/runs/ide-java25-entrypoints-v2-r001/`.
All pre-existing schema definitions remain unchanged; v2 adds separate branches.
The engine version/default installation and original 0.8.0 scope remain unchanged.

A separate six-case cursor Story checks inherited selection in the child,
innermost nested and enclosing outer types, a complete zero for an inner type
without main despite an outer candidate, an out-of-document position and a split
UTF-16 surrogate pair. Native type ranges select the launch owner; no fallback to
an enclosing type's main is allowed. Selection correlations, full closed schema,
repeat IDs, v1 and stale/input guards remain checked. Evidence:
`.refactorkit/runs/ide-java25-entrypoints-v2-cursor-r001/`.

Scope and multi-type-file enumeration retain other runnable types when a class
has a proven construction failure. `ineligibleTypes` reports that native owner,
source span and typed reason; `ineligibleCount` and `ineligibleTypesTruncated`
retain exact totals and output bounds. `complete` requires both candidate and
exclusion details to be untruncated. An explicit cursor or single-type-source
request still receives its construction refusal. Unknown/unproved selection
remains a refusal and cannot manufacture an authoritative zero.

Six cases exercise ordinary inheritance, abstract-base/concrete-child selection,
argument-only construction alongside a runnable type, non-static inner exclusion,
static main bypass of construction and a complete scope without main methods.
Evidence: `.refactorkit/runs/ide-java25-entrypoints-v2-enumeration-r001/`.

Versioned capability flags are separate from retained v1 flags:
`javaEntryPointsV2`, `javaEntryPointsV2Inherited`, `javaEntryPointsV2Java25` and
`javaEntryPointsV2SavedSnapshot`. The source daemon advertises these without
opening a workspace or selecting an SDK; each actual query still proves its exact
qualified cell/context. Legacy inherited/Java25 flags remain false for v1.
The single capability Story exercises the actual daemon catalogue, repeat
identity, unchanged no-project behavior and process cleanup. Evidence:
`.refactorkit/runs/ide-java25-entrypoints-v2-capabilities-r001/`.
