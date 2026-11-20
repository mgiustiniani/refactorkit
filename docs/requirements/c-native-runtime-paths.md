# C native runtime path capture

Authority: the bounded operational
[Story](../../features/c-native-runtime-paths.feature), `REQ-C-NATIVE-PATH-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated acceptance;
the [0.8.0 plan](../releases/v0.8.0-plan.md) owns complete C02.

An explicitly selected native path is read as data. Its capture retains the
absolute requested spelling, every observed directory/leaf alias and its exact
target spelling, and the resolved artifact's role, path, byte size, SHA-256 and
ELF dependency declarations. Alias lists are immutable copies. Resolving the
path delegates object reading to the existing strict direct-artifact boundary;
the strict boundary still refuses indirect input when called directly.

Traversal reads each node without following links implicitly. It binds observed
directory identities, checks alias metadata before/after reading each target,
and repeats resolution after object capture. At most 32 aliases, 2048 visited
components and 4096 UTF-8 bytes per path are admitted. Cycles/repeated aliases,
unavailable nodes, non-directory ancestors, unsupported representations and
exceeded bounds cannot grant a capture. Native object reads retain the existing
bounded ELF64 little-endian x86-64 profile. A script behind an alias is data;
its unsupported object format is refused without execution.

Currency resolves the same requested path and reads the resolved object again.
Retargeting an alias invalidates the earlier capture even when both target files
have identical bytes. The equality includes requested spelling, ordered alias
records and resolved artifact facts. A forged or mutated record must equal a
fresh complete capture to retain currency. This is not a filesystem lock or a
guarantee against live replacement races.

Six native-data cases inspect the configured private 22.1.8 clangd, the actual
system interpreter through `/lib64`, the actual libstdc++ leaf alias, an owned
equal-byte retarget, a cycle and a script. Independent `readlink`, `readelf` and
`sha256sum` oracles attest paths and object facts. A pre-capture inventory proves
owned file/alias preservation; the expected intentional retarget is recorded
explicitly. Reference bytes remain intact and no native artifact is executed.
The new API passes its first valid implementation; no semantic RED is manufactured
from a missing API. The initial report is preserved before strengthening the
read-only inventory and requested-spelling handling.

The loader's [documented dependency search](https://man7.org/linux/man-pages/man8/ld.so.8.html)
can involve object search paths, the runtime cache, hardware capability directories
and default paths. This capture alone does not select those dependencies or
establish loader/library/sysroot closure, licence/provisioning authority, runtime
manifest identity, native execution admission or a preview lease. Those integrations,
additional path-grammar/budget/race cases and complete C02 remain `actionable`.
Every C01-C17 acceptance remains required. Evidence is in
`.refactorkit/runs/v080-native-runtime-paths-r001/`, not independent release acceptance.
