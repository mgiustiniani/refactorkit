# Move Java source root

Status: beta contract for `v0.5.0`.

`moveSourceRoot` transfers every Java compilation unit from one recognized source
root to another while preserving package identity, FQCNs, imports, and UTF-8
bytes. It is distinct from [`moveClass`](move-class.md), which changes a type's
package identity and rewrites references.

```bash
refactorkit java move-source-root \
  --from domain/src/main/java \
  --to domain-relocated/src/main/java \
  --root /workspace

# Apply only the reviewed plan.
refactorkit java move-source-root ... --apply
```

Library/daemon/MCP operation name: `moveSourceRoot`; stable arguments are `from`
and `to`, both workspace-relative protocol paths. The current recipe schema does
not expose this operation because it cannot yet bind whole-root ownership safely.

## Contract

A successful preview:

- emits only ordered `FileEdit.Rename` operations, including `package-info.java`
  and `module-info.java`;
- creates no destination directory during preview;
- preserves each source file's content exactly;
- preserves main/test source-set kind and package/FQCN identity;
- validates the exact post-image with the effective per-module Maven/JDT model;
- applies as one PatchEngine/WAL transaction and rolls every file back together.

The destination may be an existing declared source root or the conventional
prospective `src/main/java` / `src/test/java` root of a Maven reactor module that
already has an effective POM in the worktree.

## Typed refusals

- `sourceRoot.missing`
- `sourceRoot.generated`
- `sourceRoot.overlap`
- `sourceRoot.destinationCollision` (including case-folded collisions)
- `sourceRoot.packageMismatch`
- `sourceRoot.duplicateType`
- `sourceRoot.destinationUnrecognized`
- `sourceRoot.symlinkEscape`
- `buildModel.unavailable`
- `classpath.unavailable`
- `sourceRoot.diagnosticsRegression`

Refusal is final for that plan; do not replace it with raw filesystem moves.
POM changes needed to define module ownership/dependencies must exist before the
preview so they are included in snapshot and classpath evidence. A future explicit
`java.moveAcrossMavenModules` capability is tracked in
`docs/releases/v0.7.0-plan.md`; `moveSourceRoot` must not infer or edit POMs.
