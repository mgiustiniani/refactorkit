# Daemon JSON-RPC protocol

The official daemon is `bin/refactorkit-daemon` (`.bat` on Windows) in the
self-contained distribution. It uses the bundled runtime and complete packaged
classpath; clients do not construct a classpath or install Java.

Transport is JSON-RPC 2.0 as newline-delimited UTF-8 JSON over stdio. Standard
output is reserved for one response per line. Startup and operational logging is
written only to standard error and must never contain imported source. Close
stdin to request orderly shutdown; the process finishes outstanding synchronous
work and exits at EOF. Abrupt termination is handled by the managed WAL recovery
run during the next `project.open`.

Typical lifecycle:

```json
{"jsonrpc":"2.0","id":1,"method":"server.capabilities"}
{"jsonrpc":"2.0","id":2,"method":"project.open","params":{"root":"/workspace"}}
{"jsonrpc":"2.0","id":3,"method":"java.importExternalClass","params":{"sourceKind":"clipboard","code":"public class Foo {}","targetDirectory":"module-a/src/main/java/com/example/util","licensePolicy":"warn"}}
{"jsonrpc":"2.0","id":4,"method":"refactor.apply","params":{"planId":"plan-..."}}
{"jsonrpc":"2.0","id":5,"method":"patch.rollback","params":{"transactionId":"transaction-..."}}
```

`java.importExternalClass` is preview-only. Its `targetDirectory` is an existing
workspace-relative filesystem directory. RefactorKit derives module, source root,
source set, and package. The legacy `targetPackage` is a logical Java package and
continues to work; if both are supplied they must agree.

Capability discovery exposes:

```json
{
  "name": "java.importExternalClass",
  "stability": "experimental",
  "requiresProject": true,
  "writesWorkspace": false,
  "features": {
    "targetDirectory": true,
    "preview": true,
    "apply": true,
    "rollback": true
  }
}
```

The preview reports the standard patch-plan fields plus structured diff, primary
file, resolved module/root/source set/package, package changes, provenance and
license, unresolved dependencies, conflicts/refusals, apply eligibility,
snapshot evidence, and provider version. Paths are workspace-relative. Apply
uses the exact retained plan and the central diagnostics/snapshot/WAL boundary;
rollback uses its transaction ID.

See [Integration contracts](integration-contracts.md),
[External class importer](external-class-importer.md), and
[Transactionality audit](transactionality-audit.md) for normative refusal and
managed-write semantics.
