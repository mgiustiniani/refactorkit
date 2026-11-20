# Clang resource directory inputs

Authority: pure Kotlin rules for `REQ-C-CLANG-RESOURCE-FACTS-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated acceptance;
the [0.8.0 plan](../releases/v0.8.0-plan.md) owns full C02 closure.

A resource snapshot binds one absolute normalized native root, every regular
file's relative path, byte size and SHA-256, and every non-root directory,
including empty directories. Files are binary data; no decoding or execution
is permitted. Relative paths must be normalized, nonempty, inside the root and
round-trip through the native path representation. Their ancestors must appear
in the directory set. Duplicate entries, file/directory collisions, invalid
sizes or hashes, missing ancestors and invalid roots are refused.

Independent budgets bound total entries (8192), depth (16), path bytes (4096),
one file (32 MiB) and total bytes (128 MiB); callers may lower those limits.
The per-file ceiling admits the 27,987,190-byte `libflang_rt.runtime.a` shipped
inside the official LLVM 22.1.8 Clang resource tree while keeping both per-file
and complete-tree work bounded. Zero-byte files and an explicitly empty root
are valid. Facts are immutable copies. Permutation does not affect the identity;
root, paths, node kinds, empty directories, file sizes and hashes do. Content
hashing uses exact bytes.

The identity hashes framed UTF-8 strings `refactorkit.clang.resources.v1` and the
root, then a four-byte big-endian entry count. Entries are sorted by relative
path. Each has a one-byte kind (file `01`, directory `02`) and framed path; files
also have an eight-byte big-endian size and framed hash. Strings use a four-byte
big-endian UTF-8 byte length followed by those bytes. This is an internal domain
encoding, not a wire protocol or a filesystem lock.

Six approved Kotest specifications use own binary fixtures and an independent
golden digest. Native read-only capture, currency checks and compiler-fact guard
integration have separately bounded Stories below. Runtime loader and sysroot
closure, replacement-race guarantees and full C02 remain `actionable`.
Every C01-C17 acceptance remains required.

`REQ-C-CLANG-RESOURCE-CAPTURE-001` is the separately bounded native library
[Story](../../features/c-clang-resource-directory-capture.feature). Its capture
reads direct regular files/directories only, checks bounded inventories and file
attributes before/after reading, and delegates immutable facts to the pure rules.
Its currency check rescans the entire tree and all file bytes. Parent/leaf links,
unsupported file kinds, read failures or exceeded budgets cannot grant a snapshot.
The six native cases use all 413 configured resources, independent `sha256sum`
oracles, byte/addition/deletion faults, an indirect script and a reduced entry
budget. SDK contents are preserved and no resource is executed. Capture is not a
filesystem lock or a guarantee against live replacement races. ARC42 owns
integrated acceptance.

The [compiler-resource Story](../../features/c-compiler-resource-context.feature)
owns `REQ-C-COMPILER-RESOURCES-001`: C function/scalar/helper fact evaluation
requires a retained complete resource snapshot, validates it around native
analysis and pins `-resource-dir` to its root. Omitted/unavailable or changed
inputs refuse. Metadata discovery alone grants no compiler-fact authority.

The [preview Story](../../features/c-resource-bound-preview-authority.feature)
owns `REQ-C-RESOURCE-PREVIEW-001`: scalar/helper/reorder/insertion approval leases
retain `c.resourceDirectoryIdentity`. Recapturing changed resources cannot renew
an earlier preview; managed apply refuses before journaling, including when the
caller supplies a disabled diagnostics gate. Unchanged controls apply and roll
back with exact source and project identities.

The [post-commit Story](../../features/c-native-resource-post-commit.feature)
owns `REQ-C-RESOURCE-POST-COMMIT-001`. Resource revocation after the committed
source image triggers automatic source rollback and a refusal that explicitly
reports unavailable post-apply and rollback diagnostics. Concurrent resource
bytes remain changed; the source journal retains both exact images and explicit
approval. Two unchanged controls complete apply and manual rollback. All six
native cases reuse existing scenario-scoped glue with no new step expressions.

The [syntax Story](../../features/c-syntax-resource-context.feature) owns
`REQ-C-SYNTAX-RESOURCES-001`. Syntax diagnostics require the retained complete
resource snapshot, validate its declared root and tree before materialization,
immediately before launch and after execution, and pin `-resource-dir`. Explicit
resource-directory override flags refuse. Missing or changed resources produce
`clang.compilerResourcesUnavailable`, never clean diagnostics. Six native cases
include an actual resource-provided `size_t` and independent argument digest,
byte/addition/deletion faults, missing capture and a caller override.

The [warm server Story](../../features/c-clangd-resource-context.feature) owns
`REQ-C-CLANGD-RESOURCES-001`. The server pins `--resource-dir` to the retained
root and validates the entire resource snapshot around document opening and
definition/reference/rename exchanges. Changed resources close the session and
report `c.clangdResourcesChanged`; reference failure remains unavailable. Six
actual 22.1.8 cases prove an exact resource-only header definition, exact
declaration/use references, and byte/addition/deletion/link revocation with
process/private-material cleanup and preservation of project and SDK bytes.

The [startup Story](../../features/c-clangd-resource-admission.feature) owns
`REQ-C-CLANGD-RESOURCE-ADMISSION-001`. Before private configuration or process
launch, startup requires a captured snapshot whose declared root and complete
tree remain current. One actual 22.1.8 case admits that context and resolves the
resource-only header. Five explicit fail-on-launch port witnesses prove missing
or unavailable capture, a mismatched declared root, changed bytes and a removed
root produce `c.clangdResourcesUnavailable` before executing the witness. These
five port cases are preflight evidence, not native negative qualification.
Project bytes, original SDK, recorded resource faults and private-directory
inventory remain intact. The existing guard passes the first valid run; no
production change or semantic RED is claimed by this startup Story.

Formatter and remaining native inputs, faults during startup/exchange, complete
TU/configuration/source/header authority, actual loader/library/sysroot closure
and complete C02 remain `actionable`. These Stories do not establish an execution
lease, replacement-race guarantees or release qualification. The earlier generic
server-resource documentation gap is reconciled by these bounded contracts.
Every C01-C17 acceptance remains required.
