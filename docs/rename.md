# Rename class and member

Status: implementation-informed for `v0.3.0-SNAPSHOT`. Rename operations are
previewable Java transformations backed by `PatchPlan`. Proven class and signed
method slices use JDT binding evidence; unsupported or unclean semantic cases
remain explicit lexical fallbacks or refusals.

## Commands

```bash
# Rename a Java type.
refactorkit rename --symbol com.example.UserManager --to AccountManager <root>

# Rename a method or field in an owner type.
refactorkit rename-member --symbol 'com.example.UserManager#displayName' --to labelFor <root>
```

Add `--apply` only after reviewing the preview, warnings, diagnostics, and
affected files.

## Class rename success conditions

A `renameClass` preview is considered safe enough to review when:

- the target symbol is a discovered Java class, interface, enum, record, or
  annotation type;
- the new simple name is a valid Java identifier;
- the declaration source file exists in the snapshot;
- the preview updates the declaration file, constructor/simple-name references in
  applicable source files, fully qualified references, direct imports, and the
  source filename;
- clean JDT analysis uses exact type/constructor declaration and reference ranges,
  including annotation declarations/usages while preserving unrelated
  same-simple-name annotation types;
- parse/classpath warnings produce an explicit lexical fallback warning;
- a type or source file with the requested target name does not already exist;
- warnings about fallback limits and framework findings have been reviewed.

## Member rename success conditions

A `renameMember` preview is considered safe enough to review when:

- the symbol uses `<fully.qualified.Owner#member>` form;
- the owner type and target method, field, or zero-parameter annotation element
  are found;
- the new member name is a valid Java identifier and differs from the old name;
- likely in-scope Java files are updated based on owner package/import/FQN/simple
  name visibility;
- overload warnings, if present, have been reviewed;
- unambiguous field selectors use exact JDT declaration/reference ranges when
  evidence is clean, preserving shadowing locals and unrelated same-name fields;
  an existing target field causes refusal;
- signed annotation-element selectors such as `com.acme.Route#path()` update the
  declaration and binding-matched named usages; implicit single-element values do
  not contain a member name and require no edit.

## Refusal conditions

Class rename refuses when the target name is invalid or unchanged, the symbol is
missing or is not a renameable type, the declaration file is missing, or the
target type/source filename already exists.

Member rename refuses when:

- the symbol is not in `<FQN#member>` form;
- a constructor rename is requested (`<init>`); use class rename instead;
- the new name is invalid or unchanged;
- the owner type or member cannot be found;
- no occurrences of the old member name are found in the planner's scope.

A refused plan is final for that preview. Agents must not replace it with global
search-and-replace. Gather more context, change the request, or ask for manual
review.

## Warnings and manual-review areas

Rename previews use exact JDT binding ranges for proven clean class, field, and
signed method slices. Lexical fallback remains available where documented and is always
reported in preview warnings. Review carefully when any of these apply:

- comments and string literals are not rewritten for class rename;
- reflection, annotation-processor output, generated code, `ServiceLoader`, and
  native-image/resource configuration can contain hidden names;
- Spring, JPA/Jakarta Persistence, and Jackson annotations may imply bean names,
  entity names, serialized names, query strings, XML/YAML/properties references,
  migrations, or external API contracts; see [framework-aware Java
  refactoring](framework-awareness.md);
- member rename does not understand all overload, inheritance, dynamic dispatch,
  framework listener/property, serialization, or external caller semantics;
- public API renames can break downstream projects outside the current workspace;
- large affected-file counts increase review risk.

## Rollback expectations

Applied renames are transaction-backed and can be rolled back with
`refactorkit patch rollback <transaction-id> --root <root>`. Rollback restores
files changed by the patch, including file renames when transaction metadata is
available. It does not restore downstream projects, generated artifacts, build
caches, or manual edits made after apply. Run diagnostics and relevant tests
after apply and rollback.
