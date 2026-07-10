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

```bash
./gradlew :modules:refactorkit-cli:run --args="--help"
./gradlew :modules:refactorkit-cli:run --args="scan samples/java-maven-simple"
./gradlew :modules:refactorkit-cli:run --args="java symbols samples/java-maven-simple"
./gradlew :modules:refactorkit-cli:run --args="recipe run recipes/java/rename-package.yml --root samples/java-maven-simple --param.oldPackage com.example --param.newPackage com.acme"
```

## Self-contained CLI package

Build an unpacked package with an embedded Java runtime:

```bash
./gradlew packageCliRuntime
modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit --help
```

Build a zip distribution:

```bash
./gradlew distCliRuntimeZip
```

See `docs/packaging.md` for details.
