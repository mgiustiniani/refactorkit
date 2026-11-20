# C toolchain native runtime discovery

Authority: [the feature](../../features/c-toolchain-runtime-discovery.feature)
is the single source of truth for `REQ-C-CLANG-RUNTIME-DISCOVERY-001`, one
six-case Story. [ARC42](../arc42/appendix-requirements.adoc) owns integrated
acceptance; [runtime identities](clang-toolchain-identity.md) and
[native resolution](c-glibc-runtime-resolution.md) own their bounded contracts.

After explicit executable/path policy checks, capture all three runtime graphs
and compose the immutable bundle before any version or resource-dir probe.
Unknown/unavailable runtime refuses. Require the retained executable selectors
to match the selected compiler/server/formatter and actual default system cache/
preload selectors. Alternate inspection-cache captures cannot authorize host
execution. Retain the bundle through discovery; check its currency before and
after every probe and after resource/evidence acquisition. A newly captured
bundle must not renew the original inputs implicitly. Any change stops later
probes and refuses. Bind the bundle identity in v3 provenance and retain the
actual runtime capture in the returned toolchain.

The IO provider captures via the existing bounded native resolver and delegates
composition/identity to pure rules. Currency checks cache/preload around one
bounded traversal of retained selected objects and unsuccessful search inputs.
Reuse identical shared captures within a check; conflicting snapshots refuse.
Require exact no-follow absence and direct bounded ancestor inspection; errors
never become absence. Keep the two-GiB graph traversal and 512 MiB object bounds.
This API neither locks files nor closes all filesystem replacement races.
An explicit IO port supports application tests; simulated legacy ports are
structural regression only, never native qualification or a production fallback.

The current native reference control runs actual constant version/resource
probes on the qualified Clang 22.1.8 archive and binds all three runtime graphs.
It records seven runtime objects and sixteen dependency bindings per tool. The
known archive contains `libclang-cpp.so.22.1`, but not `libLLVM.so.22.1`; its
Clang, clangd, and clang-format hashes are pinned by the qualification receipt.
A non-ELF canary refuses before execution. For mutation cases, the fixture
copies the native executables/resources, adds a private `libffi.so.8` dependency
to the copies, and mutates only that copied library or the copied not-yet-probed
executable. Both changes refuse before the next version probe. Alternate cache
authority and mismatched inventory refuse before any probe. Independent hashes
verify source/reference preservation, and bounded probe processes complete and
close before returning.

The exact six-case Story passes offline against that archive. Its effective
shared runner has no ambiguity, and the installed analyzer classifies all four
steps as exact reuse; no new step definitions or dependencies were added. The
receipt and current-source evidence are in
`.refactorkit/runs/c-toolchain-runtime-discovery-clang22-inputs-r001/`. The
initial red run exposed stale fixture paths/hashes, not a production behavior
regression.

Version compatibility remains the existing metadata policy; no new major-version
parity or full semantic capability is inferred. Complete admission/currency at
compiler/formatter/clangd beyond the bounded qualified cells, runtime leases and
post-commit checks, package/licence/host/CPU/sysroot identity and remaining
process/fault/budget/alias/profile cells remain `actionable`. Every C01-C17
acceptance remains required; discovery alone closes no complete roadmap row.

## C02 clangd consumer runtime currency

`REQ-C-CLANGD-RUNTIME-CURRENCY-001` requires a current retained native runtime
before clangd launch and before/after each document or semantic exchange. The
runtime identity and all three executable selectors must match the discovered
compiler, clangd and formatter, including retained executable evidence and actual
system cache/preload inputs. Missing capture refuses with
`c.clangdRuntimeUnavailable`; changed identity, selector or captured native input
refuses with `c.clangdRuntimeChanged`. A runtime change during a live session
closes clangd and its private launch context before returning a semantic result.

The separate [six-case clangd Story](../../features/c-compiler-runtime-currency.feature)
uses the shared CLI runner and the real private Clang 22.1.8 toolchain. It covers
the current control, missing capture, changed identity, substituted executable
selector, changed copied `libffi` after launch, and an executable replaced by an
exit-zero canary. The control resolves the C definition; stale/canary cases refuse
before launch, and the copied-library case revokes the running session. The
scenario has a hash-bound receipt and pre/post installed-analyzer inventories in
`.refactorkit/runs/v080-clangd-runtime-currency-r001/`.

This qualifies only runtime currency at clangd launch and semantic exchange. It
does not establish complete compilation-database/source/header/configuration
authority, close filesystem replacement races, create an execution lease, or
close C02. Those inputs, preview/post-commit currency and the rest of C01-C17
remain `actionable`.
