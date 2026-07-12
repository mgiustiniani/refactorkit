# Java stable capability matrix

This matrix defines Java write authority for the proposed v1 contract. A preview
may be displayed regardless of evidence, but only `JDT_BINDING` and documented
`STRUCTURAL` plans can pass core apply validation.

| Operation | Stable apply evidence | Conservative boundary |
|---|---|---|
| Rename type | `JDT_BINDING` | Unclean/unresolved analysis produces review-only `LEXICAL_FALLBACK`; strings, reflection, generated code, and external binaries are not rewritten. |
| Move type | `JDT_BINDING` | Same review-only fallback; existing target and invalid package refuse. |
| Rename signed method/annotation element | `JDT_BINDING` | Exact overload and complete source-visible override family required; external family declaration refuses. |
| Rename exact field | `JDT_BINDING` | Exact owner binding and collision checks required. |
| Unsigned member rename | `LEXICAL_FALLBACK` | Preview only; stable apply requires a signed selector or exact field binding. |
| Safe delete type | `JDT_BINDING` | Unclean analysis is review-only; generated declarations always refuse. |
| Organize imports | `JDT_BINDING` or `STRUCTURAL` | JDT removes proven unused exact imports; structural mode only sorts/deduplicates/removes same-package imports and preserves unresolved/wildcard imports. |
| Extract method, limited | `STRUCTURAL` | Only documented straight-line/control-flow-safe selections; ambiguity refuses. |
| Change signature, limited | `STRUCTURAL` | Supported parameter operations only; overload/override/generated/reflection-risk boundaries refuse. |
| Package move recipe | `STRUCTURAL` | Owns and stages the complete old-package source set as one transaction. |
| External Java import | `STRUCTURAL` | Provenance, license policy, conflicts, package/path rules, and diagnostics remain mandatory. |

## Language and module compatibility

- JDT Core 3.44/JLS25 parses per-module Java compliance from 8 through 25.
- Maven and Gradle source levels and project-module dependencies are hash-bound.
- JDT source/class paths contain only the owning module and its transitive declared
  module dependencies; unrelated sibling modules are intentionally invisible.
- JDK bootstrap types, declared/generated dependency classpaths, compiled outputs,
  and local JARs are deterministic scanner inputs.
- Generated Java is visible for analysis but never receives stable rewrite
  authority.
