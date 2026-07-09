# AI workflow

See AGENTS.md for the authoritative initial architecture and implementation rules.

## MCP-assisted local agent workflow

1. Call `project_scan` for the workspace root.
2. Use `project_summary`, `project://dependencies`, and `diagnostics` to understand project state.
3. Use `symbol_search`, `symbol_definition`, `symbol://...`, and `symbol_references` to gather focused context.
4. Use `generate_context_bundle` for a compact symbol/snippet/diagnostic bundle.
5. Use `preview_refactoring` before any deterministic Java refactoring.
6. Inspect affected files, risk, confidence, and warnings.
7. Use `apply_refactoring` only after the preview is acceptable.
8. Use `rollback_refactoring` if diagnostics or behavior regress.

MCP file resources are scoped to the scanned workspace snapshot. Agents should not
request arbitrary files; they should use symbol and project resources first.
