# Cross-platform runtime portability requirements

Status: path/watcher correction accepted by GitHub Actions run `29418213082`;
diagnostics reconciliation-race precedence awaits native qualification.

## Scope

Correct platform-dependent path assertions and saved-workspace watcher timing
without weakening protocol-path safety, read-only behavior, or semantic snapshot
authority.

## Requirements

### RPK-PORT-001 — Native internal paths

Core models shall accept safe workspace-relative `Path` values created by the host
filesystem provider. A valid relative path created from slash-separated source on
Windows must not be rejected merely because `Path.toString()` renders native
backslashes. Normalization must still reject absolute, blank and parent-traversing
paths.

### RPK-PORT-002 — Protocol path boundary

JSON/protocol paths shall remain canonical forward-slash strings. The transport
parser must reject backslashes, drive-prefixed values, absolute paths and parent
traversal before creating an internal `Path`. Protocol serialization shall use
forward slashes on every host platform.

### RPK-PORT-003 — Portable preview evidence

Patch-preview tests shall compare canonical protocol paths rather than host-native
`Path.toString()` output. Rename-followed-by-modify rendering must continue to use
the staged post-rename bytes and preserve the modified content.

### RPK-PORT-004 — Eventual saved-file observation

Recursive saved-workspace watcher acceptance shall allow the bounded latency of
native JDK watch-service implementations, including polling implementations on
macOS. Tests shall wait up to 15 seconds using a monotonic deadline and must still
prove that a real saved-file change marks the watcher dirty, refreshes the next
read, and creates no `.refactorkit` metadata.

### RPK-PORT-005 — Reconciliation race precedence

When an external saved-file change races watcher reconciliation, diagnostics may
return `diagnostics.savedSnapshotStale` before reconciliation or
`diagnostics.snapshotStale` after the session adopts the new snapshot. A missing
semantic adapter after reconciliation must not mask the stale request as
`diagnostics.semanticSessionNotReady`; snapshot correlation is checked first.

### RPK-PORT-006 — Qualification

The correction is complete only when the full JVM suite and golden tests pass
locally, packaged Kotlin acceptance remains green, and the Linux, Windows x86-64,
macOS x86-64 and macOS arm64 GitHub Actions jobs pass from one immutable commit.
