# Selected-SDK Java platform source navigation

Date: 2026-10-04. Assessment status: **historical proposal; subsequent implementation qualified separately below**.
This is a bounded research and protocol proposal requested by the IDE consumer.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated architecture and status;
this document owns the observations and proposed authority boundary. The production
[API catalogue](../api-0.2-contract.json) remains the authority for advertised methods.
The original assessment did not advertise or qualify runtime methods. The implementation update below records the subsequent separate qualification.
Evidence is in `.refactorkit/runs/ide-java-platform-source-assessment-r001`.

## Existing support and concrete gaps

The engine already binds JDT to the explicitly configured SDK and the owning source
set's effective release. `JavaReleasePlatformAuthority` attests SDK release metadata,
`ct.sym`, release/platform version and, for the current release, the SDK's system
module image. Its `identitySha256` participates in C0. `JavaContextAuthority` and
`JavaBoundContext` revalidate that authority around semantic queries. The existing
`JavaReleasePlatformClasspath` substitutes ECJ's historical-release signature view
when the source release differs from the SDK version; it never substitutes the daemon
host JDK. This existing semantic support does not provide platform source resources.

The shared JDT binding-use projection carries exact JVM owner/member/descriptor, but
does not carry a qualified SDK module/binary-origin/source association. The dependency
source service searches the owning source set's dependency classpath for a normal JAR,
then requires provider attachment provenance and a classfile `SourceFile` attribute.
It does not search the SDK image or `src.zip`. JDK bindings therefore do not qualify
as dependency-JAR associations. The source-declaration parser and guarded source-byte
workflow are reusable foundations, but platform module context and exact binary
provenance still need their own implementation and qualification.

Read-only packaged-daemon observations use Maven fixtures, configured SDK21/25, and
effective releases 17 and the respective current release. A `java.lang.String` cursor
has an available SDK-bound project context, while existing dependency declaration
navigation reports `unavailable` / `java.sources.binaryUnavailable`, without a range.
The transcript validates the actual production schema and unchanged workspace image.
These are observations of current limitations, not qualification of a new feature.

The installed SDK21 has no `lib/src.zip`. The selected GraalVM SDK25 has a local
51,193,464-byte `src.zip`: 17,220 entries with 219,025,660 total declared uncompressed
bytes. Its exact `java.base/java/lang/String.java` entry is 198,085 bytes. Those exceed
the current dependency API's 32 MiB archive / 64 MiB total inflation / 128 KiB selected
source limits. Increasing those limits for dependency archives would not establish
platform provenance. SDK25's `ct.sym` includes multiple historical String signatures;
current SDK25 source bytes are not thereby attested as historical-release source.

## Separate proposed contract

Reserve experimental methods `java.platformSource.lookup`,
`java.platformSource.declaration` and `java.platformSource.read`, with separate
`java-platform-source-v1` and `java-platform-source-declaration-v1` schemas. All would
be read-only, require a project and advertise selected-SDK platform authority. Do not
add them to capabilities or the production catalogue before Story/native qualification.
They must never reuse a dependency association or `refactorkit-java-source://` URI.

Every request contains the existing S0/C0/SDK/path/UTF-16 cursor fields and
`expectedPlatformIdentitySha256`. That identity comes from the provider's
`project.context.sdk[*].identitySha256` for the selected source set's effective release;
the provider resolves the unique owner and verifies the selection, rather than trusting
a caller-chosen release/module. The caller supplies no class name, source filename,
module-image path, archive path or alternative SDK. Authority is explicit:

- Saved navigation supplies `sourceAuthority:{"kind":"saved-snapshot"}`.
- Editor navigation supplies the existing `immutable-editor-overlay` document frame
  and mandatory `expectedOverlayHash` returned by the selected definition query.
  It shares the application session's observed version history and private staging.
  There is no saved fallback when editor authority fails.

Lookup observes the association and returns metadata; declaration also returns an exact
bound declaration/name range when qualified. Declaration optionally accepts
`expectedAssociationHash`; read requires it and repeats the same authority frame.
An editor frame binds every supplied path/version/content, including other drafts.
The consumer is responsible for supplying its complete Java selection inventory;
the provider can reject changed inventories, but cannot discover omitted unsent drafts.

An available association must contain:

- Its own `associationHash`, `readOnly:true`, `resourceKind:"java-platform-source"`
  and opaque `refactorkit-java-platform-source://` URI.
- Owning workspace provider/module/source-set, original saved S0/C0, selected SDK
  real path, effective release, platform release and platform identity SHA-256.
- Exact SDK module name and binary origin `CURRENT_JRT_IMAGE`, attested system-image
  identity, selected class entry and raw class SHA-256, plus exact JVM identity.
- Standard configured-SDK `lib/src.zip` path, resolved target identity and raw archive
  SHA-256, exact selected source entry/raw source SHA-256, and the actual classfile
  `SourceFile` attribute used to select that entry.
- For editor mode, provider snapshot and closed-overlay proof; document versions enter
  the association even when the draft text is identical.

Keep the full association in one provider domain service. Transport only extracts,
delegates and renders. Return original S0/C0/SDK separately from the editor provider
snapshot, as in the qualified editor dependency extension. Hash domains are distinct:
platform authority, raw class/archive/source hashes and association hashes cannot be
compared with opaque project classpath fingerprints.

## Required selection and validation

1. Acquire the existing bound Java context and unique source-set owner, with the exact
   saved/editor frame. Select a non-recovered JDT binding at the cursor.
2. Require authoritative SDK binary-origin/module evidence from the same bound JDT
   resolver. This is a missing internal projection to implement, not evidence currently
   exposed by owner/name/descriptor. Never classify a type as platform by `java.*`, by
   finding a same-named entry, or by inspecting the daemon host SDK.
3. Initially qualify only modern current-platform source contexts where
   `effectiveRelease == platformRelease`. Read the exact selected class from the
   attested SDK module image through an isolated, closeable provider reader. Retain
   the actual module/class origin; ambiguous or unavailable evidence cannot select one.
4. Read only the configured SDK's standard `lib/src.zip`. Resolve and attest a distro
   symlink if present, including its resulting path identity; revalidate the resolution
   on read. Do not search other SDKs, download sources, execute builds, or accept an
   arbitrary caller archive. Select `<actual module>/<actual package>/<SourceFile>`
   from the resolved classfile attribute, never from the Java binary's simple name.
5. Bound the archive independently: proposed 128 MiB compressed bytes, 32768 entries,
   512 MiB total declared inflation, 512 KiB selected UTF-8 source and 2 MiB selected
   class. Check duplicate/unsafe entries, unsupported layouts, malformed UTF-8 and
   streamed selected-entry limits. Read only the selected entries in memory; extract
   nothing. These are proposed limits requiring native qualification, not active limits.
6. Resolve the attested source with the existing JDT backend under the same selected
   SDK, effective release and actual platform module context. Module-aware source
   parsing is a qualification requirement; the dependency parser alone is insufficient
   evidence. Require exact JVM owner/member/descriptor and checked modifiers against
   the selected real classfile. Preserve the existing explicit/synthetic distinction,
   duplicate-declaration ambiguity, invalid syntax and binding completeness refusals.
7. Revalidate source/SDK/frame evidence around analysis and again on guarded read.
   A hash-bound source archive is additional authority even when S0/C0 is unchanged.
   All outcomes require honest typed status; no unsupported result invents a range.

Exact ranges would use the existing zero-based UTF-16/exclusive-end convention and
`evidence:"JDT_SOURCE_BINDING"`, into the exact bytes returned by the guarded read.
Native/abstract explicit declarations may qualify; generated/default/synthetic members
without an explicit declaration do not. This proves declaration identity, not that SDK
source and executable implementations are byte-for-byte equivalent.

## Refusals and initial unsupported cases

Use existing context/overlay stale and invalid-parameter behavior before platform
selection. Proposed additional reasons are namespaced `java.platformSources.*`:

| Status | Reason | Meaning |
| --- | --- | --- |
| refused | `platformStale` | Selected platform identity differs from the context's actual release authority. |
| refused | `associationStale` | SDK image/class, source archive/entry/path or editor frame changed since selection. |
| unavailable | `sourceArchiveUnavailable` | Selected SDK has no locally materialized standard source archive. |
| unavailable | `releaseSourceUnqualified` | Effective historical-release sources are not attested by this current SDK source archive. |
| unavailable | `binaryOriginUnavailable` | Binding lacks exact selected-SDK module/class origin evidence. |
| unavailable | `sourceIdentityUnavailable` | No safe SourceFile/module entry or complete source binding can be established. |
| ambiguous | `binaryOriginAmbiguous` / `declarationAmbiguous` | Competing origins/declarations cannot be selected. |
| refused | `declarationMismatch` | Bound source descriptor or checked modifiers do not match the actual binary. |
| refused | `limitExceeded` / `archiveUnsafe` | Provider archive/class/source limits or integrity rules fail. |

Historical-release exact source navigation is **unsupported initially**, even if a
current SDK source contains a same-named or matching-signature member. `ct.sym` is API
signature authority, not a historical source attachment. A later provider would need
separately qualified matching-release source provenance and actual source identity;
this proposal does not permit a consumer guess. SDK8 `rt.jar`, patched/replaced modules,
nonstandard source layouts, ambiguous module identity, absent SourceFile, unqualified
synthetic descriptors and additional hosts stay explicitly unsupported until tested.
SDK21 on this machine must honestly report missing sources, never fall back to SDK25.

## Qualification before delivery

Implement observable behavior as bounded Cucumber Stories with fresh single-scenario
receipts and effective-glue inventories. Keep each slice within six expanded cases,
four new steps and eight changed files, reusing the existing runner/glue. No new
dependency or hand-written JUnit behavior tests are required by the proposal.

Separate slices must cover exact current-SDK class/overload/constructor/field ranges,
missing/historical sources, stale platform/archive/association and unsafe/ambiguous
source evidence. Another slice must prove cross-document editor selection and observed
version/text/inventory guards. Native qualification must use the separately packaged
daemon, actual production schema, selected SDK21/25 and Maven/Gradle contexts, including
SDK changes, original workspace preservation and immutable read-only resources. Use
owned SDK fixtures for destructive evidence-drift probes; do not change installed SDKs.
If a valid local SDK21 source archive is unavailable, record that limitation and qualify
its honest missing-source behavior without fabricating a positive SDK21 result.

Status of the feature and executable schemas: `actionable`. Consumer acceptance:
`unknown`. Historical source attestation and wider hosts/layouts: `out-of-scope` for the
initial increment. The assessment and proposed authority boundary are complete; runtime
implementation and delivery remain distinct work. All prior packages/manifests remain
immutable, including the qualified dependency overlay package.


## Subsequent implementation and qualification

The IDE requested implementation of this bounded proposal after the assessment.
The production catalogue now advertises separate experimental
`java.platformSource.lookup/declaration/read`; the [protocol guide](../java-project-context-protocol.md)
owns their final wire contract and [ARC42](../arc42/appendix-requirements.adoc)
owns integrated status. The preceding proposal and the sealed assessment-result
remain historical evidence; they must not be read as runtime qualification.

The implementation supplies the previously missing actual JDT JRT origin and
module-aware source parser. It checks selected SDK/release/module, SourceFile,
source owner/grammar, exact JVM identity, classfile evidence, captured ZIP evidence
and guarded saved/editor-frame association. Type-kind projection uses JDT semantic
interface/annotation/enum predicates because `ITypeBinding.getModifiers()` alone
omits the corresponding classfile type flags. The pinned JDT 3.44 module bridge
fails closed when it cannot establish the actual module. No SDK download, installed
SDK alteration, another-SDK fallback or dependency-budget expansion was introduced.

Five bounded Stories pass 30 new cases; final selected regressions pass 514 and
packaged-daemon native checks pass 263. Positive source navigation is qualified on
the real selected SDK25 for Maven/Gradle. SDK21 has no installed standard src.zip,
so only explicit missing-source/refusal behavior is qualified there. Historical
release source, implicit/synthetic ranges, code-equivalence attestation and other
hosts/layouts remain outside this delivery. Btrfs ENOSPC forced final verification
and the immutable delivery onto owned /tmp paths; earlier failed attempts remain
retained and are not counted as qualification. Evidence and the manifest are at
`/tmp/refactorkit-sdk-source-delivery-r001`; original bounded plans/red-green evidence
remain under `.refactorkit/runs/ide-java-platform-*`. SDK consumer acceptance is
independent and pending. Four earlier packages and sealed documentation copies
remain unchanged.
