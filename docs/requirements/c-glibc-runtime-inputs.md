# C glibc runtime data input capture

Authority: [the feature](../../features/c-glibc-runtime-inputs.feature) is the
single source of truth for `REQ-C-GLIBC-INPUTS-001`, one six-case Story.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated architecture and
acceptance; [the cache domain](c-glibc-loader-cache.md) owns pure byte/lookup rules.

Read a selected direct cache file and prove selected preload-file absence.
Default selectors are `/etc/ld.so.cache` and `/etc/ld.so.preload`; explicit
selectors support inspection, without certifying that alternative files are the
actual host inputs. Do not execute tools or regenerate the cache. Bind selected
absolute normalized paths, complete cache bytes, size and immutable parsed facts.
Read no more than 8 MiB. Require direct regular cache files and direct ancestors,
observed file/directory keys and stable attributes around reading; reject aliases,
missing/unreadable/malformed data and unstable paths. Preload must be absent via
no-follow attribute inspection: broken links, files and directories count as
presence; errors never become absence. Check absence around cache reading.

A later currency check rereads the original selectors and compares the cache
evidence/facts and preload absence. Byte changes and newly present preload
invalidate earlier captures; new captures never renew old evidence implicitly.
Return structured safety diagnostics. No writes, locking or race closure is
claimed. Cache acquisition does not make a candidate an inspected library.

Six cases inspect actual current host data, an owned unchanged copy, owned byte
mutation, indirect/missing cache and preload appearing after capture. Independent
read-only `ldconfig -p -C`, `sha256sum` and attribute/path oracles validate metadata,
hashes and preservation. The library API executes no tool; oracle utilities run
only in qualification glue. Original host data/source stays intact; owned fixture
mutations are explicit. The installed analyzer inventories effective sources
before edits; the existing runner and scenario-scoped glue are retained.

Actual recursive ELF/alias search, cache hardware/profile selection, remaining
host/provisioning/licence/sysroot inputs, execution admission, preview leases,
post-commit and additional budget/race/error cells remain `actionable`. Every
C01-C17 acceptance stays required; this API closes none of those full rows.
