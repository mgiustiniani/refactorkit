# REQ-JAVA-INLINE-VARIABLE-LITERAL-001

Status: `@implemented-and-validated` for the source-built Java adapter and the
bounded Story acceptance below. This requirement extends the historical J1
refusal-only baseline without changing its approved snapshot. The integrated
architecture authority is [ARC42](../arc42/README.adoc); this satellite records
the operation-specific acceptance boundary.

The Java adapter supports one narrow `inlineVariable` shape: a single local
declaration fragment that is a direct statement in an ordinary method body,
initialized by a JDT-resolved primitive-`int` numeric literal. It replaces only
read references whose `IVariableBinding` matches that declaration, deletes the
declaration line only when doing so cannot discard comments, validates the
staged binding structure and Java diagnostics, and returns a previewable patch
plan. The capability is registered as `STABLE` / `MANAGED_STABLE`; the Story
executes it through `PatchEngine` Apply and normal rollback and verifies exact
source restoration.

The planner refuses writes or increments, locals with no reads, multiple
declarators, nonliteral or non-`int` initializers, nested or unsupported scopes,
ambiguous/recovered bindings, generated source, unavailable source-set
authority, and declaration-line layouts where deleting the line could remove a
comment. This does not qualify general Java inline-variable support, expression
initializers, fields or parameters, `inlineMethod`, daemon/MCP protocol flows,
native IDE consumer flows, packaged distribution, or other platforms.

The generic request uses `operation = "inlineVariable"` with `arguments.file`
set to the saved Java file path and `arguments.name` set to the unique local
declaration name in that compilation unit. Capability metadata is recorded in
[`api-0.2-contract.json`](../api-0.2-contract.json); this does not define a new
transport method or claim cursor-selection support.

The executable Story is
[`java-inline-variable-literal-preview.feature`](../../features/java-inline-variable-literal-preview.feature),
tagged with the exact identity
`REQ-JAVA-INLINE-VARIABLE-LITERAL-001#inline-all-binding-matched-reads-of-a-local-primitive-int-literal`.
Its one scenario has four steps. The pre-edit receipt and effective-glue
inventory are in
`.refactorkit/runs/java-inline-variable-literal-r001/`; the installed analyzer
found four new required steps, zero shared-reuse candidates, zero duplicate
expressions, and zero runner ambiguities. The RED record captures the previous
typed-refusal route failing the expected preview. The fresh offline GREEN record
proves exact preview output, stable capability metadata, managed Apply, clean
post-Apply Java diagnostics, normal rollback, and snapshot restoration.

Implementation is in
[`JavaInlineVariablePlanner.kt`](../../modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaInlineVariablePlanner.kt),
dispatched by `JavaLanguageAdapter` and registered by
`JavaAdapterRegistration`. Acceptance reuses the existing Java preview-command
Cucumber runner and glue package; it adds no dependency or runner.

The user explicitly approved a ten-file maximum for this BDD slice. The override
was needed to keep the planner, exact-tag Story, existing runner/glue wiring,
requirement and capability records, and ARC42 mapping reviewable together. The
slice stays within that limit. Independent requirements-quality review and
packaged or cross-platform qualification remain open.
