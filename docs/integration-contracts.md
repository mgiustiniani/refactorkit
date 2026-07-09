# Integration contracts

See AGENTS.md for the authoritative initial architecture and implementation rules.

## LSP server MVP

`modules/refactorkit-lsp` exposes a JSON-RPC/LSP server over stdio using standard
`Content-Length` framing.

Current capabilities:

- `textDocument/definition`
- `textDocument/references`
- `textDocument/prepareRename`
- `textDocument/rename`
- `textDocument/codeAction`
- `textDocument/documentSymbol`
- `textDocument/semanticTokens/full`
- `textDocument/diagnostic`
- `textDocument/publishDiagnostics` notifications after workspace refresh
- `workspace/executeCommand` for RefactorKit preview/apply-oriented commands

Workspace edits include both legacy `changes` entries for text edits and
`documentChanges` entries for file create, delete, and rename operations.

Advertised `workspace/executeCommand` commands:

- `refactorkit.renameClass`
- `refactorkit.renameMember`
- `refactorkit.extractMethod`
- `refactorkit.changeSignature.renameParameter`
- `refactorkit.moveClass`
- `refactorkit.organizeImports`
- `refactorkit.safeDelete`
- `refactorkit.applyPlan`
- `refactorkit.rollback`

## Daemon JSON-RPC MVP

`refactor.preview` supports:

- `renameClass`
- `renameMember`
- `extractMethod`
- `changeSignature.renameParameter` / `renameParameter`
- `moveClass`
- `organizeImports`
- `safeDelete`

`extractMethod` arguments:

```json
{
  "file": "src/main/java/com/example/App.java",
  "startLine": "12",
  "endLine": "14",
  "methodName": "extractedLogic"
}
```

`changeSignature.renameParameter` arguments:

```json
{
  "oldName": "id",
  "newName": "userId"
}
```

## MCP server MVP

`modules/refactorkit-mcp` exposes JSON-RPC 2.0 over newline-delimited stdio for
local LLM/MCP clients.

Current tools:

- `project_scan`
- `project_summary`
- `symbol_search`
- `symbol_definition`
- `symbol_references`
- `diagnostics`
- `available_refactorings`
- `preview_refactoring`
- `apply_refactoring`
- `rollback_refactoring`
- `import_external_java_class`
- `generate_context_bundle`

Current resources:

- `project://summary`
- `project://symbols`
- `project://dependencies`
- `diagnostics://latest`
- `symbol://{fullyQualifiedName}`
- `file://...` for files inside the currently scanned workspace snapshot only

`preview_refactoring` supports:

- `renameClass`
- `renameMember`
- `extractMethod`
- `changeSignature.renameParameter` / `renameParameter`
- `moveClass`
- `organizeImports`
- `safeDelete`

The MCP server intentionally does not expose arbitrary filesystem access. File
resources are refused unless the requested file is inside the workspace root and
was part of the last scanned snapshot.

Current prompts:

- `refactor_safely`
- `import_external_class_safely`
- `explain_patch`
- `generate_tests_for_refactor`
