# RefactorKit

[![CI](https://github.com/mgiustiniani/refactorkit/actions/workflows/ci.yml/badge.svg)](https://github.com/mgiustiniani/refactorkit/actions/workflows/ci.yml)

RefactorKit is a deterministic refactoring and code-intelligence engine for safe, previewable, rollbackable code transformations.

The first supported language is **Java**. RefactorKit must work with Java 8-era projects while understanding Java language/source constructs and type forms through Java 25. The architecture is adapter-based so future languages can be added without coupling language-specific logic into the core engine.

## Usage modes

RefactorKit is designed to be used as:

- a Kotlin/JVM library;
- a CLI executable;
- a long-running local daemon with JSON-RPC;
- an LSP server;
- an MCP server for local LLM agents.

## MVP focus

The MVP focuses on safe deterministic Java refactoring with patch preview, diagnostics, rollback, a CLI, and integration points for daemon/LSP/MCP consumers.

## Architecture documentation

- ARC42 index: [`docs/arc42/README.adoc`](docs/arc42/README.adoc)
- C4 model / System Context: [`docs/c4/workspace.dsl`](docs/c4/workspace.dsl)
- Active `v0.7.x` Kotlin/JVM interoperability plan: [`docs/releases/v0.7.0-plan.md`](docs/releases/v0.7.0-plan.md)
- Kotlin adapter boundary: [`docs/kotlin-adapter.md`](docs/kotlin-adapter.md)
- Kotlin toolchain boundary: [`docs/kotlin-toolchain.md`](docs/kotlin-toolchain.md)
- Supreme multi-language `v1.0.0` roadmap: [`docs/releases/v1.0.0-plan.md`](docs/releases/v1.0.0-plan.md)
- Deterministic formatting contract: [`docs/formatting.md`](docs/formatting.md)

Latest release is `v0.6.2`; main develops `0.7.0-SNAPSHOT`. API `0.2` remains
the beta compatibility baseline. The `v0.6.2` publication patch delivers the
`v0.6.0` managed TypeScript/JavaScript semantic foundation on the
natively built self-contained runtime matrix; platforms are marked supported only
after native managed apply/recovery/rollback acceptance. Stable `v1.0.0` is deliberately deferred until deep IDE-grade language
adapters through Clojure and global all-language acceptance are complete. Java is
the reference and widest catalogue, while other mature ecosystems target
equivalent semantic safety and idiomatic depth.

## Self-contained runtime platforms

| Platform | Architecture | Published support | Planned asset |
|---|---|---|---|
| Linux | x86_64 | `v0.6.2` supported | `refactorkit-runtime-<version>-linux-x86_64.zip` |
| Windows | x86_64 | `v0.6.2` supported | `refactorkit-runtime-<version>-windows-x86_64.zip` |
| macOS | Intel x86_64 | `v0.6.2` supported | `refactorkit-runtime-<version>-macos-x86_64.zip` |
| macOS | Apple Silicon arm64 | `v0.6.2` supported | `refactorkit-runtime-<version>-macos-aarch64.zip` |

Each package embeds its native Java runtime. A runtime is built on its target OS,
receives an independent checksum/SBOM/attestation, and must pass packaged
version, semantic lookup, format/apply/rollback, recovery and filesystem safety
checks. The IDE does not require a globally installed Java runtime.

## Install a v0.6.2 self-contained runtime

Select `linux-x86_64`, `windows-x86_64`, `macos-x86_64`, or `macos-aarch64`.
Every package includes its native launcher and embedded Java runtime, so users do
not need a globally installed Java runtime.

Release page: <https://github.com/mgiustiniani/refactorkit/releases/tag/v0.6.2>

The immutable release commit and final asset hashes are recorded in
[`docs/releases/v0.6.2-acceptance.md`](docs/releases/v0.6.2-acceptance.md); use
each platform asset's adjacent checksum as authoritative.

Linux x86_64 example:

```bash
curl -LO https://github.com/mgiustiniani/refactorkit/releases/download/v0.6.2/refactorkit-runtime-0.6.2-linux-x86_64.zip
curl -LO https://github.com/mgiustiniani/refactorkit/releases/download/v0.6.2/refactorkit-runtime-0.6.2-linux-x86_64.zip.sha256
sha256sum -c refactorkit-runtime-0.6.2-linux-x86_64.zip.sha256
unzip refactorkit-runtime-0.6.2-linux-x86_64.zip -d /tmp/refactorkit-v0.6.2
```

Run smoke checks with `JAVA_HOME` unset to prove the embedded runtime is used:

```bash
RK=/tmp/refactorkit-v0.6.2/refactorkit/bin/refactorkit

env -u JAVA_HOME "$RK" --help
env -u JAVA_HOME "$RK" scan samples/java-maven-simple
env -u JAVA_HOME "$RK" scan samples/java-gradle-simple
env -u JAVA_HOME "$RK" scan samples/java-spring-simple
env -u JAVA_HOME "$RK" scan samples/java-jpa-simple
env -u JAVA_HOME "$RK" scan samples/java-multimodule
```

For a source build, run the packaged-runtime signed-selector smoke test. It
checks that the embedded image contains `java.compiler`, executes JDT-backed
`definition` and `references` using only that image, and verifies its temporary
fixture remains unchanged:

```bash
env -u JAVA_HOME ./gradlew :modules:refactorkit-cli:smokePackagedCli
```

Optionally add the extracted launcher to `PATH`:

```bash
export PATH=/tmp/refactorkit-v0.6.2/refactorkit/bin:$PATH
refactorkit --help
```

Run the sample scan commands from a RefactorKit source checkout that contains the
`samples/` directory.

### Windows and macOS `v0.6.2` artifact usage

Windows verification uses PowerShell:

```powershell
Get-FileHash .\refactorkit-runtime-<version>-windows-x86_64.zip -Algorithm SHA256
Expand-Archive .\refactorkit-runtime-<version>-windows-x86_64.zip
.\refactorkit\bin\refactorkit.bat --version
```

macOS Intel users select `macos-x86_64`; Apple Silicon users select
`macos-aarch64`:

```bash
shasum -a 256 refactorkit-runtime-<version>-macos-aarch64.zip
unzip refactorkit-runtime-<version>-macos-aarch64.zip
./refactorkit/bin/refactorkit --version
```

Unsigned/notarization or SmartScreen limitations must remain visible in each
release until platform signing is implemented. Do not substitute a runtime built
for another OS or architecture.

### TypeScript/JavaScript semantic toolchain

The Java runtime is self-contained; TypeScript semantics use an explicit external
Node/toolchain. The `v0.6.0` managed row is Node 22.18.0,
`typescript-language-server` 5.1.3 and TypeScript 5.9.3. RefactorKit never installs
or runs npm implicitly. See the
[`v0.6.0` support matrix](docs/releases/v0.6.0-support-matrix.md) and
[migration guide](docs/releases/v0.6.0-migration.md).

```bash
refactorkit typescript search /workspace --query UserService \
  --node /tools/node \
  --language-server-package /tools/node_modules/typescript-language-server \
  --typescript-package /tools/node_modules/typescript
```

## Build

```bash
./gradlew build
```

If no Gradle wrapper has been generated yet, use a local Gradle install to run:

```bash
gradle wrapper
./gradlew build
```

## CLI examples

The examples below use the packaged binary name `refactorkit`. During development, run the same command through Gradle:

```bash
./gradlew :modules:refactorkit-cli:run --args="<command and options>"
```

For example:

```bash
./gradlew :modules:refactorkit-cli:run --args="scan samples/java-maven-simple"
```

### Project inspection

```bash
# Show global CLI help.
refactorkit --help

# Scan a project and print the workspace summary and snapshot hash.
refactorkit scan samples/java-maven-simple

# `index` is an alias for scan.
refactorkit index samples/java-maven-simple

# List Java symbols discovered by the default Java scanner.
refactorkit symbols samples/java-maven-simple

# Java namespace aliases for inspection commands.
refactorkit java scan samples/java-maven-simple
refactorkit java symbols samples/java-maven-simple
refactorkit java diagnostics samples/java-maven-simple

# Report Java diagnostics such as duplicate symbols or package/path mismatches.
refactorkit diagnostics samples/java-maven-simple

# Locate a symbol definition and references.
refactorkit definition --symbol com.example.UserManager samples/java-maven-simple
refactorkit references --symbol com.example.UserManager samples/java-maven-simple
refactorkit java definition --symbol com.example.UserManager samples/java-maven-simple
refactorkit java references --symbol com.example.UserManager samples/java-maven-simple
```

### Java refactoring previews

Maven scans build effective reactor models offline without executing project
plugins. Parent properties, BOMs, reactor edges, and locally cached dependencies
feed independent main/test JDT environments. `--resolve-dependencies` is an
explicit anonymous Maven Central opt-in; Maven settings credentials are never
loaded or reported.

Mutating Java commands are preview-first. Omit `--apply` to inspect the patch plan and warnings without writing files.

```bash
# Rename a Java type and update same-project references.
refactorkit rename \
  --symbol com.example.UserManager \
  --to AccountManager \
  samples/java-maven-simple

# Rename a method or field by owner/member symbol.
refactorkit rename-member \
  --symbol 'com.example.UserManager#displayName' \
  --to labelFor \
  samples/java-maven-simple

# Move a class to another package and update package/import/FQN references.
refactorkit move-class \
  --symbol com.example.UserManager \
  --to-package com.example.account \
  samples/java-maven-simple

# Move a complete source root between Maven modules without changing bytes/FQCNs.
refactorkit java move-source-root \
  --from domain/src/main/java \
  --to domain-relocated/src/main/java \
  --root samples/java-maven-reactor-21

# Sort/deduplicate imports for one or more files.
refactorkit organize-imports \
  src/main/java/com/example/UserManager.java \
  --root samples/java-maven-simple

# Format one Java compilation unit using project JDT preferences or deterministic defaults.
refactorkit format-file \
  src/main/java/com/example/UserManager.java \
  --root samples/java-maven-simple

# Refuse deletion when source references are found; use --force only after manual review.
refactorkit safe-delete \
  --symbol com.example.UserManager \
  samples/java-maven-simple
```

### Limited transformations

These commands are intentionally conservative in the MVP and may return a refused plan when the selection or signature is unsafe.

```bash
# Extract a selected line range to a private no-argument void method.
# Adjust the file and 1-based line numbers to a real, supported selection.
refactorkit extract-method \
  --file src/main/java/com/example/UserManager.java \
  --start-line 10 \
  --end-line 14 \
  --method-name validateUser \
  --root samples/java-maven-simple

# Rename a method parameter.
refactorkit change-signature \
  --symbol 'com.example.UserManager#displayName' \
  --old-name username \
  --new-name userName \
  samples/java-maven-simple

# Add a parameter with a default expression at call sites.
refactorkit change-signature \
  --operation add-parameter \
  --symbol 'com.example.UserManager#displayName' \
  --type String \
  --name prefix \
  --default '"User"' \
  samples/java-maven-simple

# Reorder or remove parameters when the planner can prove the change is safe.
refactorkit change-signature \
  --operation reorder-parameters \
  --symbol 'com.example.UserManager#displayName' \
  --order userName,prefix \
  samples/java-maven-simple
refactorkit change-signature \
  --operation remove-parameter \
  --symbol 'com.example.UserManager#displayName' \
  --name prefix \
  samples/java-maven-simple
```

### Apply and rollback workflow

```bash
# 1. Preview first and inspect the rendered patch, diagnostics, warnings, and risk level.
refactorkit rename --symbol com.example.UserManager --to AccountManager samples/java-maven-simple

# 2. Apply only after review. The CLI prints a transaction ID on success.
refactorkit rename --symbol com.example.UserManager --to AccountManager --apply samples/java-maven-simple

# 3. Verify after apply.
refactorkit diagnostics samples/java-maven-simple

# 4. Roll back by transaction ID if verification fails or the change is no longer wanted.
# Refuses if affected files changed after apply
refactorkit patch rollback <transaction-id> --root samples/java-maven-simple

# Explicit destructive override after reviewing the conflict
refactorkit patch rollback <transaction-id> --force --root samples/java-maven-simple
```

### External Java class import

External imports are treated as code assimilation, not plain refactoring. Keep provenance and license risk visible in the preview.

```bash
# Preview an import from stdin. Unknown licenses warn by default.
printf 'public class Slugifier { public String slug(String s) { return s.toLowerCase(); } }\n' | \
  refactorkit java import-class \
    --target-package com.example.util \
    --source-url https://example.invalid/snippets/Slugifier.java \
    --stdin \
    samples/java-maven-simple

# Refuse unknown-license imports instead of warning.
refactorkit java import-class \
  --target-package com.example.util \
  --file /path/to/ExternalUtil.java \
  --license-policy block-unknown \
  samples/java-maven-simple
```

### Recipes

```bash
# Preview a parameterized recipe.
refactorkit recipe run recipes/java/rename-package.yml \
  --root samples/java-maven-simple \
  --param.oldPackage com.example \
  --param.newPackage com.acme

# Apply the same recipe only after reviewing the preview.
refactorkit recipe run recipes/java/rename-package.yml \
  --root samples/java-maven-simple \
  --param.oldPackage com.example \
  --param.newPackage com.acme \
  --apply
```

### Structural tools

Structural tools are lightweight, language-agnostic helpers. They are useful for inspection and local edits, but they are not a substitute for semantic Java refactorings.

```bash
# Show a file outline using the generic/tree-sitter adapter path.
refactorkit outline samples/java-maven-simple/src/main/java/com/example/UserManager.java --language java

# Search a single file structurally/textually with whole-word matching.
refactorkit search samples/java-maven-simple/src/main/java/com/example/UserManager.java \
  --pattern UserManager \
  --language java \
  --whole-word

# Preview a file-local identifier rename.
refactorkit local-rename samples/java-maven-simple/src/main/java/com/example/UserManager.java \
  --from username \
  --to userName \
  --root samples/java-maven-simple
```

### Golden tests

```bash
# Run all golden test cases.
refactorkit test-golden

# Run one golden test case.
refactorkit test-golden rename-class-user-manager

# Use a non-default golden test directory.
refactorkit test-golden --golden-dir testdata/golden
```

## Preview, apply, rollback safety model

The beta workflow is: scan the project, generate a preview, inspect affected files/diagnostics/warnings, apply only with `--apply`, verify with diagnostics or tests, and roll back by transaction ID if the result is not acceptable. Patch application validates the snapshot hash and records rollback metadata under the workspace.

MVP limitations remain visible: Java analysis is still mostly lexical, `organize-imports` does not fully remove unused imports, `safe-delete` does not inspect every non-source reference, external importer license detection is heuristic, and extract-method/change-signature support is conservative. See [`docs/release-plan.md`](docs/release-plan.md) and ARC42 risks in [`docs/arc42/11-risks-and-technical-debt.adoc`](docs/arc42/11-risks-and-technical-debt.adoc).

## Self-contained CLI and daemon package

Build an unpacked package with an embedded Java runtime:

```bash
./gradlew packageCliRuntime
modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit --help
# Official NDJSON JSON-RPC stdio launcher; close stdin to shut down.
modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit-daemon
```

Build a zip distribution:

```bash
./gradlew distCliRuntimeZip
```

See `docs/packaging.md` for details.
