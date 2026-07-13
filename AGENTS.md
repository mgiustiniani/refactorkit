# AGENTS.md

> Operational instructions for local LLMs, coding agents, and IDE-integrated assistants working on **RefactorKit**.

This file is intentionally scoped to the **RefactorKit library/executable**.

## 1. Project Mission

RefactorKit is a deterministic refactoring and code-intelligence engine.

Its goal is to provide safe, previewable, rollbackable code transformations similar in spirit to IDE refactorings.

The first supported language is **Java**.

The Java adapter must be compatible with Java 8 projects while supporting Java language/source constructs and type forms through Java 25.

The architecture must allow future support for other languages through adapters.

RefactorKit must be usable as:

1. a library;
2. a CLI executable;
3. a long-running local daemon;
4. an LSP server;
5. an MCP server for local LLM agents.

The project must help AI-assisted development by replacing fragile text edits with deterministic semantic operations.

---

## 2. What Is In Scope

The following are in scope for this repository:

```text
RefactorKit core engine
Java language adapter
Patch preview/apply/rollback engine
Symbol index
Diagnostics model
CLI
Daemon JSON-RPC API
LSP server
MCP server
External Java class importer
Recipe engine
Test harness
Sample Java projects
Packaging as executable without requiring global Java installation
```

---

## 3. What Is Out of Scope

The following are not part of the primary RefactorKit implementation:

```text
Custom IDE implementation
Tauri frontend
Editor UI
Project tree UI
Diff viewer UI
AI chat panel UI
Tauri sidecar lifecycle code
VS Code extension
JetBrains plugin
Web dashboard
Cloud service
Collaborative editing
```

These may be documented as **integration consumers**, but they must not drive the internal design unless they expose clear requirements for the engine API.

For example, a Tauri IDE may later consume RefactorKit through JSON-RPC, LSP, or MCP, but RefactorKit should not contain Tauri-specific code.

RefactorKit may later be consumed by a Tauri IDE, another editor, a CLI workflow, an MCP client, or a CI pipeline, but those consumers are **out of scope for this repository** unless explicitly added as separate integration modules.

---

## 4. Core Philosophy

Do not let an LLM directly rewrite code when a deterministic refactoring operation exists.

The LLM should decide **what** the user wants.

RefactorKit should decide **how** to apply the change safely.

Example:

```text
User:
Rename UserManager to AccountManager.

Agent:
- scan the project;
- identify the symbol;
- ask RefactorKit for a rename preview;
- inspect the patch;
- run diagnostics;
- apply only if the plan is safe.
```

Do not implement Java refactorings as global search-and-replace.

Prefer semantic operations based on symbols, references, AST, package declarations, imports, build structure, and diagnostics.

---

## 5. Recommended Technology Stack

### Main implementation language

```text
Kotlin JVM targeting Java 8-compatible bytecode where possible
```

Reason:

- the first target is Java;
- RefactorKit must work with Java 8-era projects while understanding Java syntax and type constructs up to Java 25;
- the strongest Java analysis/refactoring libraries are JVM-native;
- Kotlin interoperates directly with Java libraries;
- Kotlin is concise for immutable models, sealed hierarchies, patch plans, result types, and adapters.

### Java backend candidates

Use one or more of:

```text
Eclipse JDT:
Java AST, bindings, semantic analysis, IDE-like rewrite capabilities.

OpenRewrite:
recipe-based Java transformations and large-scale refactoring.

Spoon:
optional Java AST analysis/transformation support.

LSP4J:
JVM implementation for exposing Language Server Protocol.

Tree-sitter:
future multi-language structural parsing.
```

### Packaging

The executable must not require the user to install Java globally.

Preferred MVP packaging:

```text
Kotlin JVM application + embedded Java runtime generated with jlink
```

Possible later packaging:

```text
GraalVM Native Image
```

Do not use Kotlin/Native for the core Java refactoring engine because it would lose direct access to JVM-based Java tooling.

---

## 6. Repository Layout

Use this structure unless there is a strong reason to change it.

```text
refactorkit/
AGENTS.md
README.md
settings.gradle.kts
build.gradle.kts
docs/
architecture.md
refactoring-engine.md
patch-engine.md
java-adapter.md
ai-workflow.md
safety-model.md
external-class-importer.md
packaging.md
integration-contracts.md
modules/
refactorkit-core/
refactorkit-java/
refactorkit-cli/
refactorkit-daemon/
refactorkit-lsp/
refactorkit-mcp/
refactorkit-web-importer/
refactorkit-tree-sitter/
refactorkit-testkit/
samples/
java-maven-simple/
java-gradle-simple/
java-spring-simple/
java-jpa-simple/
java-multimodule/
recipes/
java/
testdata/
golden/
```

---

## 7. Module Responsibilities

### 7.1 refactorkit-core

Language-independent infrastructure.

Responsibilities:

```text
workspace model
project model
file scanner
source file abstraction
content hashing
project snapshot
build model/source-set provider contract
text edit model
file edit model
workspace edit model
patch plan
patch preview
atomic apply
rollback
transaction log
diagnostics model
symbol model
reference model
language adapter contract
configuration model
```

Core must not depend on Java-specific logic.

Roadmap boundary: before additional deep JVM adapters, the proven Maven
module/source-set concepts must be generalized into an internal language-neutral
Build Model SPI. Until that extraction is complete, Maven parsing/resolution
remains owned by `refactorkit-java`; core must not acquire Maven classes or
plugin-execution assumptions.

Important domain objects:

```text
ProjectSnapshot
Workspace
Module
BuildModel
BuildModule
BuildSourceSet
BuildModelProvider
LanguageAdapterRegistry
LanguageAdapterDescriptor
LanguageCapability
LanguageAdapterRuntime
LanguageCapabilityProtocol
ExternalSemanticProcessManager
SemanticProcessSpec
SemanticProcessProvenance
SemanticWorkspaceOverlay
ExternalWorkspaceEditProposal
ExternalWorkspaceEditNormalizer
SourceFile
TextEdit
FileEdit
WorkspaceEdit
PatchPlan
Transaction
Diagnostic
Symbol
Reference
LanguageAdapter
```

---

### 7.2 refactorkit-java

Java-specific intelligence.

Responsibilities:

```text
Java parsing for source levels 8 through 25
offline plugin-free Maven effective-reactor discovery
per-module Maven main/test classpath and source-level modeling
Gradle project discovery
source root detection
package detection
class/interface/enum/record detection
method detection
field detection
constructor detection
import detection
type resolution
symbol indexing
reference search
Java diagnostics
Java refactorings
Java formatter integration
organize imports
```

Initial Java refactorings:

```text
rename class
rename method
rename field
move class
move source root without changing package identity
move package
organize imports
safe delete
extract method, limited version
change signature, limited version
```

---

### 7.3 refactorkit-cli

Command-line interface for users, scripts, CI, and agents.

Required commands:

```bash
refactorkit scan .
refactorkit index .
refactorkit diagnostics .
refactorkit symbols .
refactorkit references --symbol com.example.UserService
refactorkit definition --symbol com.example.UserService
refactorkit rename --symbol com.example.UserService --to AccountService --preview
refactorkit move-class --symbol com.example.UserService --to-package com.example.account --preview
refactorkit java move-source-root --from module-a/src/main/java --to module-b/src/main/java --root .
refactorkit organize-imports src/main/java/com/example/UserService.java
refactorkit safe-delete --symbol com.example.LegacyUtil --preview
refactorkit recipe run recipe.yml --preview
refactorkit java import-class --stdin --target-package com.example.util --preview
refactorkit patch apply plan.json
refactorkit patch rollback transaction-id
```

---

### 7.4 refactorkit-daemon

Long-running local process for editors, IDEs, and agents.

Responsibilities:

```text
keep project index warm
receive JSON-RPC commands
stream indexing progress
stream diagnostics
generate patch previews
apply patch plans
rollback transactions
generate AI context bundles
```

Preferred MVP transport:

```text
JSON-RPC over stdio
```

Later optional transport:

```text
local HTTP/WebSocket JSON-RPC
```

The daemon must not assume any specific frontend technology.

---

### 7.5 refactorkit-lsp

Language Server Protocol integration.

Required LSP features:

```text
textDocument/definition
textDocument/references
textDocument/rename
textDocument/prepareRename
textDocument/codeAction
textDocument/documentSymbol
textDocument/semanticTokens
textDocument/publishDiagnostics
workspace/executeCommand
workspace/applyEdit
```

Use LSP for editor-native operations.

Do not overload LSP with advanced AI workflows. Use JSON-RPC or MCP for those.

---

### 7.6 refactorkit-mcp

MCP server for local LLMs and coding agents.

Expose deterministic tools, not arbitrary filesystem access.

Initial MCP tools:

```text
project_scan
project_summary
symbol_search
symbol_definition
symbol_references
diagnostics
available_refactorings
preview_refactoring
apply_refactoring
rollback_refactoring
import_external_java_class
generate_context_bundle
```

Resources:

```text
project://summary
project://symbols
project://dependencies
file://...
symbol://...
diagnostics://latest
```

Prompts:

```text
refactor_safely
import_external_class_safely
explain_patch
generate_tests_for_refactor
```

---

### 7.7 refactorkit-web-importer

External code import module.

This is not pure refactoring.

Treat it as:

```text
External Code Assimilation
```

First implementation:

```text
ExternalJavaClassImporter
```

Responsibilities:

```text
accept snippet, clipboard text, file content, or URL content
strip markdown fences
detect package declaration
detect top-level classes
detect public classes
split multiple public classes into separate files
rewrite package declaration
compute target file path
organize imports
detect unresolved imports
detect naming conflicts
record provenance
warn about license risk
generate patch preview
apply only after confirmation
```

---

## 8. Language Adapter Contract

Every language must eventually implement this conceptual contract.

```kotlin
interface LanguageAdapter {
    fun languageId(): String
    fun parse(file: SourceFile): ParseResult
    fun buildSymbols(project: ProjectSnapshot): SymbolIndex
    fun resolveSymbol(location: SourceLocation): SymbolResolution
    fun findReferences(symbolId: SymbolId): List<Reference>
    fun diagnostics(project: ProjectSnapshot): List<Diagnostic>
    fun availableRefactorings(selection: CodeSelection): List<RefactoringDescriptor>
    fun applyRefactoring(request: RefactoringRequest): RefactoringPlan
    fun formatEdits(edits: List<TextEdit>): List<TextEdit>
}
```

The core must depend on this contract, not on language-specific classes.

---

## 9. Patch and Transaction Model

Every code modification must be represented as a previewable patch plan.

Never directly modify files without a plan.

### 9.1 PatchPlan

A patch plan must contain:

```json
{
  "id": "plan-uuid",
  "operation": "renameSymbol",
  "status": "preview",
  "snapshotHash": "hash-before-change",
  "confidence": 0.98,
  "requiresUserApproval": true,
  "summary": "Rename UserManager to AccountManager and update 34 references.",
  "affectedFiles": [],
  "workspaceEdit": {},
  "diagnosticsBefore": [],
  "diagnosticsAfterPreview": [],
  "warnings": []
}
```

### 9.2 WorkspaceEdit

A workspace edit may contain:

```text
text edits
file creation
file deletion
file rename
directory creation
directory rename
```

Example:

```json
{
  "edits": [
    {
      "type": "modifyFile",
      "path": "src/main/java/com/example/UserService.java",
      "textEdits": [
        {
          "range": {
            "start": {"line": 12, "character": 10},
            "end": {"line": 12, "character": 20}
          },
          "newText": "AccountService"
        }
      ]
    }
  ],
  "createdFiles": [],
  "deletedFiles": [],
  "renamedFiles": []
}
```

### 9.3 Transaction Rules

Before apply:

```text
verify current snapshot hash
reject if files changed since preview
reject overlapping edits
reject edits outside workspace root
write transaction log
apply atomically where possible
run diagnostics
save rollback metadata
```

If the project changed between preview and apply:

```text
Abort.
Regenerate the plan.
```

---

## 10. Safety Model

Every modification must be:

```text
previewable
atomic
rollbackable
validatable
traceable
reproducible
```

### 10.1 Risk Levels

```text
LOW:
local rename with all references resolved.

MEDIUM:
public API rename, cross-module move, change signature.

HIGH:
reflection possible.
string-based framework references.
Spring/JPA/Jackson annotations.
generated code.
annotation processors.
unknown external code.
unknown license.
```

### 10.2 Hard Rules

Agents must obey these rules:

```text
1. Do not modify code directly if RefactorKit has an operation for the task.
2. Always generate preview before apply.
3. Always inspect affected files before apply.
4. Always run diagnostics after generating a plan.
5. Always use rollback if apply causes unexpected diagnostics.
6. Never overwrite existing user code without explicit plan and confirmation.
7. Never import unknown external code without provenance and license warning.
8. Never add Maven/Gradle dependencies without preview and explicit note.
9. Never assume package paths; compute them.
10. Never perform global textual search-and-replace for Java symbol refactoring.
11. Never execute Maven project plugins or lifecycle code during project discovery.
12. Maven dependency network access is disabled unless the caller explicitly opts in; never load or report Maven settings credentials.
```

---

## 11. Java Refactoring MVP

### 11.1 Rename Class

Command:

```bash
refactorkit rename \
  --symbol com.example.UserManager \
  --to AccountManager \
  --preview
```

Must update:

```text
class declaration
constructors
file name
imports
fully qualified references
same-package references
tests
related build references only if confidently detected
```

Should warn about:

```text
reflection
string references
Spring bean names
serialization names
JPA entity names
Jackson annotations
```

---

### 11.2 Move Class

Command:

```bash
refactorkit move-class \
  --symbol com.example.UserService \
  --to-package com.example.account \
  --preview
```

Must update:

```text
file path
package declaration
imports across project
fully qualified references
diagnostics
```

---

### 11.3 Organize Imports

Command:

```bash
refactorkit organize-imports \
  src/main/java/com/example/UserService.java
```

Must:

```text
remove unused imports
sort imports
remove same-package imports
handle static imports
preserve comments where possible
detect unresolved conflicts
```

---

### 11.4 Safe Delete

Command:

```bash
refactorkit safe-delete \
  --symbol com.example.LegacyUtil \
  --preview
```

Must:

```text
find references
reject deletion if references exist
show all references
allow forced mode only with explicit override
```

---

### 11.5 Extract Method, Limited MVP

Input:

```json
{
  "file": "src/main/java/com/example/UserService.java",
  "range": {
    "startLine": 40,
    "endLine": 55
  },
  "methodName": "validateUser"
}
```

Must analyze:

```text
variables read
variables written
return value
exceptions
control flow
visibility
static context
```

If ambiguous:

```text
Do not guess.
Return a refused plan with explanation.
```

---

### 11.6 Change Signature, Limited MVP

Initially support:

```text
rename parameter
add parameter with default expression
reorder parameters
update call sites
```

---

## 12. External Java Class Importer

This feature is useful, but it is not pure refactoring.

Treat it as a separate capability:

```text
ExternalJavaClassImporter
```

or:

```text
External Code Assimilator
```

### 12.1 Purpose

Given Java code copied from the web, clipboard, a local file, or an LLM response, insert it into the project with:

```text
correct file path
correct package
correct namespace
organized imports
conflict detection
provenance metadata
license warning
diagnostics
```

### 12.2 Input

```json
{
  "sourceKind": "clipboard|url|file|llm",
  "sourceUrl": "optional",
  "code": "public class Foo { ... }",
  "targetModule": "app",
  "targetPackage": "com.example.util",
  "mode": "import|adapt|translate",
  "allowRename": true,
  "licensePolicy": "warn|block-unknown|allow"
}
```

### 12.3 Output

```json
{
  "status": "preview",
  "detectedClasses": ["Foo"],
  "targetFiles": [
    "src/main/java/com/example/util/Foo.java"
  ],
  "workspaceEdit": {},
  "warnings": [
    "No license detected. User approval required."
  ]
}
```

### 12.4 Pipeline

```text
1. Acquire source.
2. Strip markdown fences.
3. Detect license/provenance.
4. Parse Java code.
5. Detect package declaration.
6. Detect top-level classes.
7. Detect public classes.
8. Split into files if needed.
9. Infer or apply target package.
10. Rewrite package declaration.
11. Resolve imports.
12. Detect naming conflicts.
13. Apply formatter.
14. Generate patch preview.
15. Run diagnostics.
16. Apply only after confirmation.
```

### 12.5 Java File Rules

```text
public class Foo      -> Foo.java
public interface Foo  -> Foo.java
public enum Foo       -> Foo.java
public record Foo     -> Foo.java

Multiple public top-level types:
split into multiple files.

Multiple non-public top-level types:
allow same file only if Java-valid.
```

### 12.6 Conflict Policy

If this exists:

```text
com.example.util.Foo
```

Default behavior:

```text
abort with preview warning
```

Optional choices:

```text
overwrite
rename imported class
manual merge
```

Never overwrite by default.

### 12.7 License and Provenance

For every imported external class, record:

```json
{
  "sourceUrl": "...",
  "retrievedAt": "2026-06-12T00:00:00Z",
  "licenseDetected": "MIT|Apache-2.0|GPL|unknown",
  "licenseRisk": "low|medium|high|unknown",
  "originalHash": "sha256..."
}
```

If license is unknown:

```text
Generate preview only.
Warn the user.
Do not silently apply.
```

---

## 13. JSON-RPC API

The daemon exposes a frontend-agnostic API.

No API method may assume a specific IDE or UI framework.

### 13.1 project.open

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "method": "project.open",
  "params": {
    "root": "/path/to/project"
  }
}
```

### 13.2 project.summary

```json
{
  "jsonrpc": "2.0",
  "id": "2",
  "method": "project.summary",
  "params": {}
}
```

### 13.3 symbol.search

```json
{
  "jsonrpc": "2.0",
  "id": "3",
  "method": "symbol.search",
  "params": {
    "query": "UserService"
  }
}
```

### 13.4 symbol.references

```json
{
  "jsonrpc": "2.0",
  "id": "4",
  "method": "symbol.references",
  "params": {
    "symbol": "com.example.UserService"
  }
}
```

### 13.5 refactor.preview

```json
{
  "jsonrpc": "2.0",
  "id": "5",
  "method": "refactor.preview",
  "params": {
    "operation": "renameClass",
    "symbol": "com.example.UserManager",
    "arguments": {
      "newName": "AccountManager"
    }
  }
}
```

### 13.6 refactor.apply

```json
{
  "jsonrpc": "2.0",
  "id": "6",
  "method": "refactor.apply",
  "params": {
    "planId": "plan-uuid"
  }
}
```

### 13.7 patch.rollback

```json
{
  "jsonrpc": "2.0",
  "id": "7",
  "method": "patch.rollback",
  "params": {
    "transactionId": "transaction-uuid"
  }
}
```

### 13.8 java.importExternalClass

```json
{
  "jsonrpc": "2.0",
  "id": "8",
  "method": "java.importExternalClass",
  "params": {
    "sourceKind": "clipboard",
    "code": "public class Foo {}",
    "targetPackage": "com.example.util",
    "licensePolicy": "warn"
  }
}
```

---

## 14. Agent Workflow Rules

Every coding agent must follow this workflow.

### 14.1 Standard Refactoring Workflow

```text
1. Open or scan project.
2. Identify target symbol.
3. Read symbol definition.
4. Find references.
5. Ask for refactoring preview.
6. Inspect patch summary and affected files.
7. Run diagnostics.
8. Apply only if safe.
9. Run final diagnostics.
10. Report modified files and warnings.
```

### 14.2 Direct Editing Workflow

Use direct editing only when:

```text
no deterministic refactoring operation exists
the edit is small and localized
the agent understands the surrounding code
diagnostics will be run afterward
```

Even then:

```text
Prefer patch preview.
Prefer minimal edits.
Do not rewrite entire files unnecessarily.
```

### 14.3 External Code Import Workflow

```text
1. Treat as external code import, not refactoring.
2. Record provenance.
3. Detect or warn about license.
4. Parse code.
5. Detect classes.
6. Determine target package.
7. Determine target file path.
8. Rewrite package.
9. Organize imports.
10. Detect conflicts.
11. Preview patch.
12. Apply only after approval.
```

### 14.4 AI Context Workflow

The agent must avoid loading the entire repository into context.

Use structured queries:

```text
project_summary
symbol_search
symbol_definition
symbol_references
diagnostics
generate_context_bundle
```

The context bundle should include:

```text
relevant symbols
method signatures
dependency graph excerpt
affected files
diagnostics
minimal code snippets
patch preview
```

---

## 15. Local LLM Prompt

Use this as the system/developer prompt for a local coding agent.

```text
You are a coding agent working on a local repository using RefactorKit.

Your goals:
- perform safe deterministic code transformations;
- minimize direct text editing;
- use symbol-aware refactoring whenever available;
- generate patch previews before applying changes;
- keep changes small, atomic, and rollbackable;
- avoid loading unnecessary files into context.

Mandatory workflow:
1. Scan or open the project.
2. Identify relevant language, modules, and build system.
3. Search for relevant symbols.
4. Read definitions and references.
5. Generate a refactoring plan.
6. Inspect patch preview.
7. Run diagnostics.
8. Apply only if the plan is safe.
9. Verify after apply.
10. Report modified files, diagnostics, and risks.

Rules:
- Do not use global search-and-replace for symbol refactoring.
- Do not modify files directly if RefactorKit offers a semantic operation.
- Do not import external code without provenance and license warning.
- Do not overwrite existing files silently.
- Do not add dependencies without a preview.
- Do not guess package paths; compute them from project structure.
- Prefer smaller refactorings over large ambiguous operations.
```

---

## 16. MVP Roadmap

### Milestone 0: Repository Skeleton

Deliverables:

```text
AGENTS.md
README.md
Gradle Kotlin multi-module project
sample Java projects
basic test harness
```

Done when:

```bash
refactorkit --help
refactorkit scan samples/java-maven-simple
```

---

### Milestone 1: Patch Engine

Deliverables:

```text
TextEdit
FileEdit
WorkspaceEdit
PatchPlan
preview diff
apply
rollback
snapshot hash
transaction log
```

Done when:

```text
A JSON patch plan can safely modify, create, rename, and delete files.
```

---

### Milestone 2: Java Project Scanner

Deliverables:

```text
Maven detection
Gradle detection
source root detection
package scan
class scan
method scan
field scan
basic symbol index
```

Done when:

```bash
refactorkit java symbols samples/java-maven-simple
```

---

### Milestone 3: Rename Class

Deliverables:

```text
rename class
rename constructor
rename file
update imports
update references
preview/apply/rollback
```

Done when:

```bash
refactorkit rename --symbol com.example.UserManager --to AccountManager --preview
```

---

### Milestone 4: Move Class

Deliverables:

```text
move file
rewrite package declaration
update imports
update fully qualified references
run diagnostics
```

---

### Milestone 5: Organize Imports

Deliverables:

```text
remove unused imports
sort imports
remove same-package imports
preserve comments where possible
detect conflicts
```

---

### Milestone 6: Daemon JSON-RPC

Deliverables:

```text
project.open
project.summary
symbol.search
symbol.references
diagnostics
refactor.preview
refactor.apply
patch.rollback
```

---

### Milestone 7: LSP Server

Deliverables:

```text
definition
references
prepareRename
rename
codeAction
documentSymbol
diagnostics
semanticTokens
```

---

### Milestone 8: MCP Server

Deliverables:

```text
project_scan
project_summary
symbol_search
symbol_definition
symbol_references
preview_refactoring
apply_refactoring
rollback_refactoring
generate_context_bundle
```

---

### Milestone 9: External Java Class Importer

Deliverables:

```text
paste snippet
strip markdown fences
detect class/package/imports
target package selection
file path computation
package rewrite
organize imports
license/provenance warning
conflict detection
preview/apply
```

---

### Milestone 10: Recipe Engine

Deliverables:

```text
YAML recipes
parameters
multi-step operations
validation hooks
dry-run mode
CI-friendly output
```

---

### Milestone 11: Multi-Language Foundation

Deliverables:

```text
Tree-sitter adapter
generic outline
generic structural search
generic local rename
external LSP adapter abstraction
```

---

## 17. Integration Consumers

RefactorKit should expose stable APIs for external consumers.

Known possible consumers:

```text
Tauri IDE
VS Code extension
JetBrains plugin
CLI scripts
CI pipeline
local LLM agent
MCP client
custom editor
```

These consumers must integrate through:

```text
CLI
JSON-RPC daemon
LSP
MCP
library API
```

Do not put consumer-specific implementation details into the core engine.

For example:

```text
Correct:
expose refactor.preview through JSON-RPC.

Incorrect:
implement a Tauri diff viewer inside RefactorKit.
```

---

## 18. Testing Strategy

### 18.1 Unit Tests

Test:

```text
text edit ordering
overlapping edit rejection
file rename logic
snapshot hash
rollback
package-to-path conversion
path-to-package conversion
import sorting
class detection
```

### 18.2 Golden File Tests

Each refactoring should have:

```text
before/
after/
request.json
expected-plan.json
```

Command:

```bash
refactorkit test-golden rename-class-user-manager
```

### 18.3 Integration Tests

Use sample projects:

```text
java-maven-simple
java-gradle-simple
java-spring-simple
java-jpa-simple
java-multimodule
```

For each refactoring:

```text
build before
apply preview
apply patch
build after
run tests if configured
rollback
verify hash restored
```

### 18.4 Agent Simulation Tests

Simulate local LLM tool usage.

Scenarios:

```text
rename class
move service to new package
organize imports
safe delete refused because references exist
import external class with unknown license
import external class with naming conflict
```

---

## 19. Coding Standards

### 19.1 Kotlin

Use:

```text
sealed interfaces for operation types
data classes for immutable models
Result/Either-style returns for recoverable failures
explicit error types
no silent nulls
no global mutable state
```

Avoid:

```text
stringly typed operation names in core internals
unbounded filesystem access
direct file writes outside PatchEngine
large God classes
```

### 19.2 JSON-RPC/API

Use:

```text
typed request/response models
stable method names
explicit error codes
trace IDs
structured diagnostics
versioned API where needed
```

Avoid:

```text
returning plain strings for structured errors
leaking internal exceptions
assuming a frontend exists
```

---

## 20. Configuration

Project config file:

```yaml
# refactorkit.yml
project:
  root: .
  defaultLanguage: java

java:
  sourceRoots:
    - src/main/java
    - src/test/java
  buildSystem: auto
  formatter: project
  organizeImports: true

safety:
  requirePreview: true
  requireCleanGit: false
  blockUnknownLicenseImports: false
  runDiagnosticsBeforeApply: true
  runTestsBeforeApply: false

ai:
  contextBudgetTokens: 12000
  exposeMcp: true
  exposeHttp: true

daemon:
  transport: stdio
  logLevel: info
```

---

## 21. Recipe System

Recipes are reusable refactoring workflows.

Example:

```yaml
id: java.rename-package
name: Rename Java package
language: java
parameters:
  oldPackage: string
  newPackage: string
steps:
  - type: movePackage
    from: "{{ oldPackage }}"
    to: "{{ newPackage }}"
  - type: organizeImports
  - type: runDiagnostics
  - type: summarizePatch
```

Command:

```bash
refactorkit recipe run rename-package.yml \
  --param oldPackage=com.old \
  --param newPackage=com.new \
  --preview
```

Rules:

```text
Recipes must support dry run.
Recipes must produce patch plans.
Recipes must be rollbackable.
Recipes must fail safely on unresolved symbols.
```

---

## 22. Framework Awareness

After the Java MVP, add framework-aware analysis.

### 22.1 Spring

Detect:

```text
@Component
@Service
@Repository
@Controller
@RestController
@Autowired
@Qualifier
@RequestMapping
@ConfigurationProperties
@Bean
```

Refactoring support:

```text
bean references
@Qualifier strings
configuration properties
endpoint paths
test references
```

### 22.2 JPA

Detect:

```text
@Entity
@Table
@Column
@OneToMany
@ManyToOne
```

Warnings:

```text
entity names
table names
column names
JPQL strings
criteria API
migration scripts
```

### 22.3 Jackson

Detect:

```text
@JsonProperty
@JsonTypeName
@JsonSubTypes
```

Warnings:

```text
serialized field names
polymorphic type names
external API contracts
```

---

## 23. Multi-Language Expansion

### 23.1 Level 1: Structural

Use Tree-sitter for:

```text
outline
syntax tree
structural search
fold ranges
basic local rename
selection expansion
```

### 23.2 Level 2: LSP-backed

Use existing language servers for:

```text
TypeScript: tsserver / typescript-language-server
Python: Pyright
Rust: rust-analyzer
Go: gopls
C#: Roslyn
Kotlin: Kotlin language server or compiler-based tooling
```

RefactorKit acts as orchestrator:

```text
RefactorKit Core
-> Language Adapter
-> External LSP
```

### 23.3 Level 3: Native Adapter

Implement native semantic adapters only for languages where deeper refactoring is required.

Recommended order:

```text
1. Java
2. TypeScript
3. Python
4. Kotlin
5. C#
6. Go
7. Rust
```

---

## 24. Non-Goals for the MVP

Do not attempt these in the MVP:

```text
full IntelliJ clone
custom IDE
Tauri application
VS Code extension
JetBrains plugin
complete Java type system from scratch
perfect extract method for all edge cases
full framework-aware refactoring
full multi-language semantic support
cloud synchronization
remote development
collaborative editing
AI autonomous mass rewrites
```

MVP goal:

```text
safe deterministic Java refactoring with preview, diagnostics, rollback, CLI, daemon API, LSP/MCP integration points.
```

---

## 25. Definition of Done

A feature is done only if:

```text
it has tests
it supports preview
it supports rollback if it writes files
it reports affected files
it reports diagnostics
it has CLI or JSON-RPC access
it handles failure safely
it avoids silent data loss
it has sample project coverage
it is documented
```

---

## 26. First Implementation Tasks

Start with this order:

```text
1. Create repository skeleton.
2. Create Gradle Kotlin multi-module build.
3. Create refactorkit-core models.
4. Implement TextEdit and WorkspaceEdit.
5. Implement PatchPlan preview.
6. Implement PatchPlan apply.
7. Implement transaction rollback.
8. Add CLI skeleton.
9. Add Java project scanner.
10. Add basic Java symbol index.
11. Implement rename class preview.
12. Implement rename class apply.
13. Add sample Java projects.
14. Add golden file tests.
15. Create daemon JSON-RPC.
16. Add LSP server skeleton.
17. Add MCP server skeleton.
18. Add ExternalJavaClassImporter preview.
```

---

## 27. Agent Response Style

When working on this repository, agents should report:

```text
what was changed
why it was changed
which files were affected
which tests or diagnostics were run
what risks remain
whether rollback is available
```

Avoid vague summaries such as:

```text
Made improvements.
```

Prefer:

```text
Implemented PatchPlan overlap validation in refactorkit-core.
Added unit tests for overlapping TextEdit rejection.
No production files are written outside PatchEngine.
Tests run: ./gradlew :modules:refactorkit-core:test.
```

---

## 28. Critical Rules for Agents

These are mandatory.

```text
1. Never apply refactoring without preview.
2. Never perform semantic Java refactoring with plain text replace.
3. Never write outside the workspace root.
4. Never silently overwrite existing files.
5. Never import external code without provenance handling.
6. Never ignore diagnostics after a patch.
7. Never make huge unrelated rewrites.
8. Never couple the core engine to a specific IDE.
9. Never put UI assumptions into the refactoring engine.
10. Never require the user to install Java globally.
```

---

## 29. Final Target

The final RefactorKit system should allow this workflow:

```text
A user, IDE, CLI, or agent opens a project.
RefactorKit indexes the Java project.
The user or AI requests a refactoring.
RefactorKit generates a semantic patch preview.
The caller reviews the diff and diagnostics.
The caller approves.
RefactorKit applies atomically.
Rollback remains available.
```

For AI:

```text
Local LLM asks RefactorKit for project context.
RefactorKit returns only relevant symbols and snippets.
LLM chooses operation.
RefactorKit previews deterministic patch.
LLM reviews diagnostics.
RefactorKit applies safely.
```

The project succeeds if it makes AI-assisted development faster, safer, and less dependent on fragile text edits.
