# Java stable capability matrix

This matrix defines Java write authority for the proposed v1 contract. A preview
may be displayed regardless of evidence, but only `JDT_BINDING` and documented
`STRUCTURAL` plans can pass core apply validation.

Authority boundary: this page reports the current Java capability limits; the
integrated architecture remains [ARC42](arc42/README.adoc). The implementation's
`JavaAdapterRegistration` is authoritative for runtime mutation authority.

| Operation | Stable apply evidence | Conservative boundary |
|---|---|---|
| Rename type | `JDT_BINDING` | Unclean/unresolved analysis produces review-only `LEXICAL_FALLBACK`; strings, reflection, generated code, and external binaries are not rewritten. |
| Move type | `JDT_BINDING` | First-party `moveClass` projects broad unclean compatibility evidence as edit-free `LEXICAL_FALLBACK_REVIEW`; bounded candidate/lookup/closure/diagnostic uncertainty becomes typed non-managed guidance; missing active reactor structure refuses; leased non-managed source drift refuses under lock before WAL; existing target and invalid package refuse. |
| Move source root | `STRUCTURAL` | Rename-only whole-root relocation preserves bytes/FQCNs; generated, overlapping, unrecognized, colliding, package-inconsistent, duplicate-type, symlink, or diagnostic-regressing plans refuse. |
| Rename signed method/annotation element | `JDT_BINDING` | Exact overload and complete source-visible override family required; external family declaration refuses. |
| Rename exact field | `JDT_BINDING` | Exact owner binding and collision checks required. |
| Unsigned member rename | `LEXICAL_FALLBACK` | Preview only; stable apply requires a signed selector or exact field binding. |
| Safe delete type | `JDT_BINDING` | Unclean analysis is review-only; generated declarations always refuse. |
| Organize imports | `JDT_BINDING` or `STRUCTURAL` | JDT removes proven unused exact imports; structural mode only sorts/deduplicates/removes same-package imports and preserves unresolved/wildcard imports. |
| Format Java file | `STRUCTURAL` | Eclipse JDT formats one non-generated, syntactically valid compilation unit using hash-bound project preferences or versioned defaults; import organization remains separate. |
| Extract method, bounded | `JDT_BINDING` | Managed extraction accepts contiguous complete method-body statements with resolved method-parameter inputs and read-only preceding direct local inputs, exactly one local live-out, checked-exception propagation, and staged JDT validation. Writes to enclosing locals/parameters, nested-scope inputs, unresolved or non-denotable bindings, multiple outputs, and escaping control flow refuse. Lexical compatibility previews remain review-only. |
| Extract variable, bounded | `JDT_BINDING` | `extractVariable` can extract a complete JDT-bound subexpression from a direct method return when its type is denotable and moving evaluation before the return preserves order. The managed Story qualifies extracting `6 * 7` from `return 6 * 7 + 0;` into an explicit `int` local, with staged binding/diagnostic checks and daemon/MCP Apply plus exact rollback. Effectful prefixes, conditional or short-circuit evaluation, null/poly expressions, unresolved bindings, and partial selections refuse. Other statement contexts and sibling extraction operations remain unqualified. The custom `refactorkit.extractVariable` LSP command has a separate experimental, client-managed preview for this same bounded shape, including a dirty editor buffer; it has no server-managed Apply or rollback authority. |
| Inline variable, bounded | `JDT_BINDING` | `inlineVariable` supports one direct local declaration in an ordinary method body, initialized by a resolved primitive-`int` numeric literal. It substitutes only reads with the exact `IVariableBinding`, then validates staged JDT bindings and diagnostics; the Story verifies managed PatchEngine Apply and exact normal rollback. Writes, unused locals, multiple fragments, nonliteral or non-`int` initializers, unsupported scopes, ambiguous/recovered bindings, and declaration lines whose comments could be lost refuse. This does not establish general Java inlining, inline-method behavior, dirty-buffer/LSP support, or daemon/MCP surface qualification. |
| Inline constant, bounded | `JDT_BINDING` | `inlineConstant` supports one private static final primitive-`int` field initialized by a direct numeric literal in a top-level ordinary class. It replaces only reads matched to the exact `IVariableBinding` in the declaring compilation unit, validates staged bindings and diagnostics, and is managed through daemon/MCP with exact rollback. Mutable/public fields, computed initializers, uncertain bindings, unused fields, generated source, and unsupported layouts refuse. The HIGH-risk preview warns that reflection or external consumers could observe field removal. This does not qualify cross-file reads, dirty-buffer/LSP support, or packaged/native IDE consumers. |
| Extract constant, bounded | `JDT_BINDING` | `extractConstant` supports one exact, complete resolved primitive-`int` compile-time constant expression inside an ordinary method of a top-level ordinary class. It inserts a non-conflicting `private static final int`, stages JDT bindings and diagnostics, and is stable/managed-stable through daemon/MCP with exact rollback; nonconstant selections and unsupported shapes refuse. Its experimental LSP command adds a dirty-buffer client-managed preview with two versioned edits and no server Apply/rollback. |
| Extract field, bounded | `JDT_BINDING` | `extractField` supports one exact, complete JDT-bound primitive-`int` compile-time expression inside a non-static method of a top-level ordinary class. It inserts a private final instance field, replaces the selection with `this.<field>`, validates staged bindings and diagnostics, and is stable/managed-stable through daemon/MCP with exact rollback. Its experimental LSP command adds a dirty-buffer client-managed preview with two versioned edits and no server Apply/rollback. Other field types, placements, and packaged/native IDE acceptance remain open. |
| Extract interface, bounded | `JDT_BINDING` | `extractInterface` moves one exact JDT-bound public concrete instance method with primitive parameters/return type into a same-package interface, then proves the staged implementation binding and diagnostics. Unsupported signatures, class hierarchies, generated sources, and collisions refuse. Daemon/MCP preview, Apply, exact rollback, and typed refusal are accepted; LSP and packaged/native IDE consumers remain unqualified. |
| Extract superclass, bounded | `JDT_BINDING` | `extractSuperclass` moves one public `int` method with a single return expression using only `int` parameters, numeric literals, parentheses, and primitive arithmetic into a same-package superclass. Staged JDT bindings and diagnostics must prove that the method is inherited by the original class. Stateful/computed bodies, unsupported signatures/hierarchies, generated sources, and collisions refuse. Daemon/MCP preview, Apply, exact rollback, and typed refusal are accepted; the HIGH-risk preview flags the public hierarchy change. |
| Change visibility, bounded | `JDT_BINDING` | `changeVisibility` promotes one concrete, annotation-free method declared directly on a top-level ordinary class to `public`; the class has no declared superclass, interfaces, or type parameters. Instance methods must be final (or their owner class final); static methods are admitted directly. Staged analysis must preserve the exact method binding and introduce no diagnostics. Non-final instance methods with an open override path, public/no-op targets, generated sources, and incomplete bindings refuse. Daemon/MCP preview, managed Apply, exact rollback, and typed refusal are accepted. The HIGH-risk preview warns about external and reflective API expansion. |
| Change signature, limited | `STRUCTURAL` | Supported parameter operations only; overload/override/generated/reflection-risk boundaries refuse. |
| Package move recipe | `STRUCTURAL` | Owns and stages the complete old-package source set as one transaction. |
| External Java import | `STRUCTURAL` | Provenance, license policy, conflicts, package/path rules, and diagnostics remain mandatory. |

## Language and module compatibility

- JDT Core 3.47.0/JLS26 parses per-module Java compliance from 8 through 26;
  provider-context support is qualified separately, and write authority still
  follows each operation's acceptance row.
- Maven effective parent/BOM/property models, local artifacts, source levels, and reactor edges are hash-bound; Gradle declarations remain hash-bound.
- Maven analysis is plugin-free and offline by default, with distinct main/test environments and concise root diagnostics when local inputs are missing.
- JDT source/class paths contain only the owning module and its transitive declared
  module dependencies; unrelated sibling modules are intentionally invisible.
- JDK bootstrap types, declared/generated dependency classpaths, compiled outputs,
  and local JARs are deterministic scanner inputs.
- Generated Java is visible for analysis but never receives stable rewrite
  authority.
