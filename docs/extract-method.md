# Extract Method

Authority boundary: this page documents the bounded JDT-backed managed shape and the separate
lexical compatibility preview. The integrated architecture narrative remains
[ARC42](arc42/README.adoc).

RefactorKit includes a conservative Java Extract Method preview planner:

```text
modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaExtractMethodPlanner.kt
```

## CLI

```bash
refactorkit extract-method \
  --file src/main/java/com/example/App.java \
  --start-line 12 \
  --end-line 14 \
  --method-name extractedLogic \
  --root .
```

Line numbers are **1-based and inclusive**.

The multi-line command above may produce a lexical compatibility preview. Adding `--apply`
does not promote that evidence: `PatchEngine` refuses lexical fallback plans with
`evidence.insufficient`. Apply is available only for a qualified binding-backed plan.

## Success conditions

A successful unbound compatibility preview is limited to simple Java statements and remains
review-only. The managed Extract Method shape requires complete JDT bindings and a recognized
Java build source set. It is available when:

- the file is in the scanned workspace;
- line numbers are 1-based, inclusive, and select a contiguous range of complete direct statements;
- the method name is a valid Java identifier and does not already exist;
- exactly one local declared in the selection is used after the selected range;
- the output has one denotable type and a complete initializer binding;
- every captured input is a resolved enclosing-method parameter or a preceding direct local
  declaration in the same method body, with a denotable type;
- assignments stay within selected locals or fields; writes to enclosing locals and parameters refuse;
- invoked checked exceptions have complete bindings and can be declared by the helper;
- staged bindings preserve the helper call, output, parameters, and exceptions;
- staged Java diagnostics introduce no new errors.

## Supported MVP shape

The lexical compatibility path used by an unbound or unsupported workspace snapshot can preview
complete selected lines as a no-argument `private void` method:

```java
private void methodName() {
    ...selected statements...
}
```

It replaces the original selected lines with:

```java
methodName();
```

The method is inserted before the final class closing brace. Its plan carries
`LEXICAL_FALLBACK` evidence, so managed Apply refuses it before writing files or creating a
transaction.

The JDT-bound managed shape accepts multiple contiguous statements, including a conditional
branch, when all required bindings are complete. It passes resolved enclosing-method parameters
and read-only locals declared directly before the selection in the same method body to the helper,
returns exactly one selected local that remains live after the range, and adds resolved checked
exceptions to the helper signature. The captured local must have one exact JDT binding and a
denotable type; writes to it, ambiguous names, nested-scope declarations, and unsupported types
remain refused. For example, the selected body may declare an intermediate local, update the
output local in an `if` branch, and return that output to the original method. The helper preserves
the enclosing method's static context. Staged binding checks and diagnostics run before the plan
is accepted.

## Conservative refusals

The lexical compatibility path refuses extraction when it detects:

- invalid method name
- invalid/blank range
- method name already exists in the file
- `return`, `throw`, `break`, `continue`, or `yield`
- type declarations in the selected range
- apparent method/control block declarations in the selected range
- unbalanced braces
- selected code uses local variables or parameters declared before the range
- selected code declares local variables used after the range

The lexical compatibility refusals avoid guessing parameters, return values, exceptions, or control
flow. The managed JDT path also refuses missing or recovered bindings,
multiple live-out locals, unsupported output declarations, writes to enclosing local values,
`return`, `throw`, `break`, `continue`, `yield`, lambdas, method references, anonymous classes,
non-denotable types, and JPMS module-path inputs. It does not guess data flow across those shapes.

## Risk model and warnings

Successful unbound compatibility previews use:

```text
riskLevel = MEDIUM
confidence = 0.70
requiresUserApproval = true
```

Lexical compatibility previews use confidence `0.70` and warn that they support only
no-argument `private void` helpers. Binding-aware previews use confidence `0.86` and warn that
they require complete JDT bindings, enclosing-parameter or preceding direct-local inputs, one
live-out local, and checked exception propagation. A refused plan must not be replaced with manual
cut-and-paste extraction unless a human accepts the risk and reviews the resulting patch.

## Rollback expectations

Applied extract-method changes are transaction-backed and can be rolled back with
`refactorkit patch rollback <transaction-id> --root <root>`. Rollback restores
the file edits recorded by the transaction, but it does not undo manual edits,
formatter runs, or behavior changes outside the workspace. Run diagnostics and
relevant tests after apply and rollback.

## Adapter integration

`JavaLanguageAdapter.availableRefactorings` includes:

```text
extractMethod
```

`JavaLanguageAdapter.applyRefactoring` accepts:

```json
{
  "operation": "extractMethod",
  "arguments": {
    "file": "src/main/java/com/example/App.java",
    "startLine": "12",
    "endLine": "14",
    "methodName": "extractedLogic"
  }
}
```

## Capability and IDE Apply boundary

The binding-aware shape is available to an SDK-bound snapshot or through a resolved build
source set: contiguous complete statements directly in a resolved method body, with one selected
local used afterward. Resolved enclosing-method parameters and read-only direct method locals
declared before the selection become helper parameters; the selected local's denotable type
becomes the helper result; checked exceptions
resolved from invoked methods and constructors are declared on the helper. Conditional branches
are covered by the current Story. Static context is preserved. Multiple outputs, writes to
enclosing locals, nested-scope prior locals, unresolved or recovered bindings,
non-denotable types, lambdas, method references, anonymous classes, JPMS module-path inputs, and
escaping control flow are refused. Staged JDT bindings and diagnostics validate the generated
call and helper.

`extractMethod` is `STABLE`/`MANAGED_STABLE` for this binding-backed shape. The existing
C-EXTRACT story and J1 row cover daemon and MCP preview, staged diagnostics, Apply, and exact
rollback without introducing a duplicate requirement. The lexical compatibility preview stays
available for review but is rejected by managed Apply. The custom LSP command
`refactorkit.extractMethod` remains experimental and returns a client-managed `WorkspaceEdit`;
it has no RefactorKit transaction or rollback authority. The IDE consumer now forwards a
multi-statement selection with a preceding-local input; native provider-backed preview, Apply,
and rollback acceptance remains separate. The operation's engine capability does not promote
either editor surface. Additional input/output and escaping-control-flow shapes remain open work
under the same C-EXTRACT requirement.

The CLI's explicit `--apply` route is a separate user-invoked command path, but it does not
promote lexical fallback evidence or change capability metadata, and it does not authorize
an IDE Apply action.

An isolated smoke check of the compatibility path selected simple statements inside a
`static` method and observed its non-static helper proposal. The binding-aware acceptance
now verifies a static-compatible helper for the supported single-local shape. The older
line-based path remains unqualified for static-context extraction and should not be used
to infer broader support.

## Tests

`JavaExtractMethodPlannerTest` covers the existing planner refusal boundaries. The requirement-first
`java-extract-method-characterization.feature` covers preceding-local capture with compile/result
preservation, refusal when extraction writes to an enclosing local, the legacy preview contract,
the managed binding-backed Apply/rollback shape, and refusal of lexical fallback Apply.

The former unbound multi-line JUnit case asserted direct mutation from a lexical preview.
That contradicted the managed-write contract and duplicated the Story's preview coverage;
it was removed so the test suite does not claim lexical edits are applicable.

Focused refusal cases include:

- refusal for return statements
- refusal for selected code using method parameters
- refusal for unsupported prior local-variable dependencies
- refusal for selected local variable used after the range
- refusal for existing method names
- refusal for invalid method names
