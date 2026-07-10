# Extract Method MVP

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

# Apply after preview review
refactorkit extract-method \
  --file src/main/java/com/example/App.java \
  --start-line 12 \
  --end-line 14 \
  --method-name extractedLogic \
  --root . \
  --apply
```

Line numbers are **1-based and inclusive**.

## Success conditions

A successful preview is limited to a simple Java statement extraction. It is safe
enough to review when:

- the file is in the scanned workspace;
- line numbers are 1-based, inclusive, and select complete statements;
- the method name is a valid Java identifier and does not already exist;
- the selected code can become a no-argument `private void` method;
- the preview warnings have been reviewed.

## Supported MVP shape

The planner extracts complete selected lines into:

```java
private void methodName() {
    ...selected statements...
}
```

It replaces the original selected lines with:

```java
methodName();
```

The method is inserted before the final class closing brace.

## Conservative refusals

The MVP refuses extraction when it detects:

- invalid method name
- invalid/blank range
- method name already exists in the file
- `return`, `throw`, `break`, `continue`, or `yield`
- type declarations in the selected range
- apparent method/control block declarations in the selected range
- unbalanced braces
- selected code uses local variables or parameters declared before the range
- selected code declares local variables used after the range

These refusals avoid guessing parameters, return values, exceptions, or control flow.

## Risk model and warnings

Successful extract-method previews use:

```text
riskLevel = MEDIUM
confidence = 0.70
requiresUserApproval = true
```

Warnings explicitly state that this is a no-argument `private void` MVP. The
operation does not infer parameters, return values, checked exceptions, complex
control flow, visibility, or static/instance context beyond the supported shape.
A refused plan must not be replaced with manual cut-and-paste extraction unless a
human accepts the risk and reviews the resulting patch.

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

## Tests

`JavaExtractMethodPlannerTest` covers:

- successful extraction and patch application
- refusal for return statements
- refusal for selected code using method parameters
- refusal for prior local-variable dependencies
- refusal for selected local variable used after the range
- refusal for existing method names
- refusal for invalid method names
