# Gradle declarative build model

Status: partial, non-executable P2B provider (`gradle-declarative-v1`).

RefactorKit does not invoke Gradle, the Wrapper, settings scripts, build scripts,
tasks, plugins, or the Tooling API during default discovery. Gradle files are
bounded text inputs and only an explicitly documented literal subset is read.
Unknown executable semantics remain outside the model.

## Recognized metadata

- conventional `main` and `test` Java roots and outputs;
- Java toolchain/source compatibility literals from 8 through 25;
- literal `sourceSets.create("name")` / `maybeCreate` declarations;
- literal Kotlin/Groovy-style source directory declarations:
  `sourceSets["name"].java.srcDir("path")` and
  `sourceSets.name.java.srcDir("path")`;
- literal `configuration(project(":module"))` module dependencies;
- integration source-set classification when the name contains `integration`;
- generated classification for materialized roots beneath `build/generated`;
- conventional/local classpath evidence supplied by the scanner.

The provider returns `PARTIAL` even when all recognized metadata is clean because
Gradle is executable and the declarative subset cannot claim to be an effective
Gradle model. Main/test/integration/custom roots remain separate BuildSourceSets.
JDT consumes each set's roots, visibility, module edges, classpath, and source
level.

## Refusal boundary

Build files are limited to 2 MiB. Literal roots must remain syntactically and,
when materialized, canonically inside the workspace. An escape or an unreadable/
oversized descriptor returns `EXECUTION_REFUSED` with a redacted typed diagnostic.
RefactorKit never falls back to executing the file to answer the query.

Generated roots remain analysis-only. Provider discovery writes nothing and does
not grant refactoring authority by itself.

## Not yet modeled

- arbitrary variables, functions, loops, conditionals, convention plugins, or
  precompiled script plugins;
- version catalogs and resolved external dependency graphs;
- settings-driven project-directory remapping;
- Android variants;
- an opt-in executable/Tooling API provider.

Those capabilities require a separately reviewed execution, sandbox, provenance,
credential, timeout, and cancellation contract. They are not silently inferred
from `BuildCodeExecution.ALLOW_EXPLICIT`.
