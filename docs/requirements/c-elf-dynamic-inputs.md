# C toolchain ELF dynamic inputs

Authority: pure Kotlin rules for `REQ-C-ELF-DYNAMIC-INPUTS-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated acceptance;
the [0.8.0 plan](../releases/v0.8.0-plan.md) owns full C02 closure.

Read bounded ELF64 little-endian x86-64 executable/shared-object bytes without
filesystem access or execution. Retain the exact input SHA-256, optional program
interpreter, explicit ET_EXEC/ET_DYN object type, ordered `DT_NEEDED` strings including repetitions, SONAME, RPATH,
RUNPATH and dynamic loader flags. Resolve string-table virtual addresses through
one file-backed load segment; an ambiguous or unavailable mapping is refused.
Static executables have an explicit empty dynamic-input result.

Reject unsupported class/endianness/architecture/object type or ABI profile,
malformed/truncated/overflowing headers, duplicate singleton metadata, unmapped
or unterminated strings and incomplete dynamic arrays. Bound source bytes,
program headers, dynamic entries, dependency count, string-table bytes and each
string. Additional loader configuration/auditing/filter/auxiliary directives
must refuse rather than silently omit dependencies. Returned dependency lists
are immutable snapshots.

The layouts and ordered dependency semantics follow the primary
[ELF header](https://refspecs.linuxfoundation.org/elf/gabi4+/ch4.eheader.html),
[program header](https://refspecs.linuxfoundation.org/elf/gabi4+/ch5.pheader.html)
and [dynamic section](https://gabi.xinuos.com/elf/08-dynamic.html) specifications.
GNU loader-extension constants are checked against the installed libc `elf.h`.
The reader extracts dependency declarations; it does not certify all executable
semantics or actual loader resolution.

Six approved Kotest specifications use own in-memory binary fixtures. The
[direct artifact Story](../../features/c-elf-artifact-input-capture.feature)
separately proves read-only native object capture and current-byte checks;
its direct-only boundary continues refusing indirect input.

The [native path Story](../../features/c-native-runtime-paths.feature),
`REQ-C-NATIVE-PATH-001`, adds an explicit read-only alias boundary around that
reader. It retains the requested spelling, every observed alias and exact
resolved object, checks traversal around reads, and rejects earlier authority
after equal-byte retargeting. Actual interpreter and libstdc++ aliases are
qualified as native data; cycles and scripts refuse without artifact execution.
[Its requirement](c-native-runtime-paths.md) records bounds and authority.

The [runtime manifest domain](c-runtime-manifest.md) validates supplied recursive
graphs, roles, indexed edges and immutable identities. It does not choose loader
resolution or certify native execution.

Actual dependency resolution, native loader/library/sysroot capture,
execution admission/lease binding, additional path/race cases, process/protocol
cells and complete C02 remain `actionable`. No C01-C17 scope is reduced.
