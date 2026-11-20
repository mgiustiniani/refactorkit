# C04 structural index addition

Requirement: `REQ-C-STRUCTURAL-INDEX-001`.
Route: Kotlin pure-domain Spec BDD with the explicitly approved Kotest 5.9.1.

This satellite owns the value-level contract of `CSymbolIndex.add`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated architecture status;
the [0.8.0 catalogue](../releases/v0.8.0-plan.md) owns release acceptance.
This structural index does not establish compiler bindings, complete references
or mutation authority; the compiler-backed declaration index remains separate.

Adding a translation-unit index returns a new `CSymbolIndex` containing the
existing units followed by the supplied unit. The original index stays unchanged.
Queries on the returned index include both existing and new symbols, retaining
their supplied lookup-space identities without merging equal spellings.
Successive additions retain earlier units. Adding the 4,096th unit succeeds;
adding a duplicate file or a 4,097th unit fails before producing a new index.

`CSymbolIndexAdditionSpec` exercises these five cases. This contract changes no
transport capability and does not qualify semantic C04 or the full release.
