# Change Signature MVP+

RefactorKit includes a conservative Java Change Signature implementation.

Implemented in:

```text
modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaChangeSignaturePlanner.kt
```

## Supported operations

### Rename parameter

Renames a parameter for a non-overloaded Java method:

```bash
refactorkit change-signature \
  --symbol com.example.UserService#findName \
  --old-name id \
  --new-name userId \
  --root .
```

Updates the declaration and method body while preserving comments and string
literals. Java call sites are not changed because parameter names are not part of
normal method-call syntax.

### Add parameter with default call-site expression

Adds one parameter to a non-overloaded Java method and inserts a caller-provided
default expression at in-scope call sites:

```bash
refactorkit change-signature \
  --operation add-parameter \
  --symbol com.example.UserService#findName \
  --type boolean \
  --name activeOnly \
  --default true \
  --root .
```

The default expression must be a single Java expression without semicolons,
newlines, or top-level commas.

### Reorder parameters

Reorders all parameters of a non-overloaded Java method and reorders arguments at
in-scope call sites:

```bash
refactorkit change-signature \
  --operation reorder-parameters \
  --symbol com.example.UserService#label \
  --order active,first,count \
  --root .
```

`--order` must list every existing parameter name exactly once.

### Remove unused parameter

Removes a parameter from a non-overloaded Java method and removes the
corresponding argument at in-scope call sites:

```bash
refactorkit change-signature \
  --operation remove-parameter \
  --symbol com.example.UserService#findName \
  --name unusedFlag \
  --root .
```

The planner refuses removal if the parameter is still used in the method body.

## Risk reductions added

Add-parameter and reorder-parameters now reduce risk by refusing:

```java
this::findName        // method references
UserService::label
"findName"           // string-literal method-name references
@Generated           // generated declaration files
```

Method references and string-literal references cannot be safely rewritten
lexically. Generated declaration files are refused to avoid modifying derived
source. Call-site updates are also filtered to likely target invocations instead
of every same-name invocation in a scoped file. Structural signature changes
(add/reorder/remove) now refuse `@Override` methods, interface methods, and
public/protected methods because hierarchy-wide implementer updates and external
callers require compiler-backed analysis. Framework-annotated declaration files
escalate previews to HIGH risk with explicit warnings about framework
configuration. Successful previews also include diagnostics computed on a virtual
post-edit snapshot.

## Conservative refusals

The planner refuses when:

- symbol is not `<FQN>#<method>`;
- owner type is missing;
- method is missing;
- method is overloaded/ambiguous;
- constructor change-signature is requested;
- structural signature changes target an `@Override` method, interface method, or public/protected method;
- parameter names are invalid;
- added parameter name already exists;
- added parameter type/default expression looks unsafe;
- add-parameter would append after an existing varargs parameter;
- reorder order is incomplete, duplicated, unknown, or unchanged;
- reorder would move a varargs parameter away from the last position;
- remove-parameter target is still used in the method body;
- method references to the target method are found;
- string literals containing the target method name are found;
- the declaration file appears to be generated;
- a call site cannot be matched to the expected argument count for reorder.

## Remaining limitations

Successful previews use `riskLevel = MEDIUM` by default and require user approval.
Framework findings may escalate risk to `HIGH`.

The implementation is still lexical. Reflection, generated code, external
callers, framework configuration, dependency injection, serialization frameworks,
and annotation processors may need manual review.

Call-site updates are limited to files that appear to reference the owner type
via imports, FQN usage, static imports, or the owner simple name.

## Adapter integration

`JavaLanguageAdapter.availableRefactorings` includes:

```text
changeSignature.renameParameter
changeSignature.addParameter
changeSignature.reorderParameters
changeSignature.removeParameter
```

`JavaLanguageAdapter.applyRefactoring` accepts:

```text
changeSignature.renameParameter
renameParameter
changeSignature.addParameter
addParameter
changeSignature.reorderParameters
reorderParameters
changeSignature.removeParameter
removeParameter
```

Add-parameter arguments:

```json
{
  "type": "boolean",
  "name": "activeOnly",
  "default": "true"
}
```

Reorder-parameters arguments:

```json
{
  "order": "active,first,count"
}
```

Remove-parameter arguments:

```json
{
  "name": "unusedFlag"
}
```

## Tests

`JavaChangeSignaturePlannerTest` covers:

- declaration and body parameter rename;
- string/comment preservation;
- overloaded method refusal;
- missing parameter refusal;
- invalid/same name refusal;
- generic parameter type support;
- add-parameter declaration and call-site updates;
- no-argument call-site updates;
- avoiding unrelated same-package methods when the owner is not referenced;
- unsafe default expression refusal;
- method-reference refusal for add/reorder/remove;
- reorder declaration and call-site updates;
- incomplete reorder refusal;
- remove-parameter declaration and call-site updates;
- remove-parameter body-usage refusal.
