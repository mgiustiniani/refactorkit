# Java Maven move-class authority fixture

This permanent acceptance fixture is an offline, plugin-free Maven reactor. The root POM is the sole aggregator and declares exactly 20 active child modules; every child is a non-aggregator JAR module. Dependency arrows below point from consumer to dependency.

## Authority topology

```text
catalog-acceptance:test -> catalog-storefront:main -> catalog-pricing:main -> catalog-model:main
```

`catalog-model:main` owns the only top-level `com.acme.catalog.legacy.Product`. The expected binding-proven observer closure is:

| Source set | Relationship to the owner | Required use forms |
| --- | --- | --- |
| `catalog-pricing:main` | direct | import, constructor, declared type |
| `catalog-storefront:main` | transitive | `instanceof`, class literal |
| `catalog-acceptance:test` | test-only | step-definition-shaped import and fully qualified use |

`catalog-decoy:main` owns the unrelated `com.acme.decoy.Product`. `reporting-unrelated:main` belongs to the separate reporting graph and contains only a lexical legacy-name candidate. The resource `catalog-acceptance/src/test/resources/features/product-lifecycle.feature` is the non-Java lexical candidate. None of these three candidates belongs to the binding-proven observer closure.

The fourteen bounded support modules form independent acyclic graphs. Representative chains are:

```text
catalog-batch -> catalog-admin -> catalog-inventory -> catalog-common
catalog-recommendations -> catalog-search -> catalog-common
reporting-unrelated -> reporting-core -> order-processing -> order-model -> customer-model
customer-notifications -> customer-model
payment-contract -> order-model
catalog-generated-support -> catalog-common
```

## Materialized inputs

`catalog-generated-support/target/generated-sources/catalog-metadata` is a committed, already materialized generated main source root. Discovery may inventory it but must never run a generator. No POM declares a plugin.

`catalog-pricing` uses the system-scoped local artifact `fixture-libs/catalog-price-contract-1.0.0.jar`; no repository or network access is needed. The artifact was authored for this fixture from `fixture-libs/provenance/src/com/acme/fixture/external/PriceAuthority.java`, compiled with `javac 25.0.3 --release 8 -g:none`, and packaged with ordered, uncompressed ZIP entries, mode `0644`, and the fixed timestamp `1980-01-01T00:00:00Z`.

```text
SHA-256  7f2e71601326da5129cb90435fb5442b958137f05fd28e4fec5192227268a3a2
```

The adjacent `catalog-price-contract-1.0.0.jar.refactorkit-evidence` manifest records that expected identity and the artifact's provided type. Isolated `OFFLINE_MISSING` acceptance variants remove only the JAR, leaving the hash-bound manifest so absence, expected content identity, and target-name non-concealment can be proven without executing Maven or accessing a network.

## REQ-JAVA-MAVEN-MOVE-AUTH-003 review-only guidance variants

The permanent baseline checks in two operation-specific expected-evidence manifests:

| Manifest | Snapshot-bound expectation |
| --- | --- |
| `catalog-acceptance/.refactorkit-expected-source-inventory.properties` | `catalog-acceptance:test` must inventory the readable `ProductLifecycleSteps.java` at its recorded content SHA-256. |
| `catalog-generated-support/.refactorkit-generated-root-inventory.properties` | `catalog-generated-support:main` owns the materialized `target/generated-sources/catalog-metadata` root at its recorded deterministic inventory SHA-256. |

`JavaProjectScanner` captures both manifests as auxiliary files in the `ProjectSnapshot`, so their exact bytes participate in the snapshot identity. They are snapshot-bound negative evidence: they may only demote an otherwise supported move to immutable, non-managed `REVIEW_ONLY_GUIDANCE` or cause fail-closed refusal when the evidence cannot be validated. They never grant, recover, or promote semantic authority.

REQ-JAVA-MAVEN-MOVE-AUTH-003 copies the fixture into four independent workspaces and introduces exactly one readable, safely contained, bounded, and enumerable preview-time defect per copy:

| Affected source set | Isolated variant | Stable blocker code |
| --- | --- | --- |
| `catalog-acceptance:test` | Its Maven test source root is redirected so `ProductLifecycleSteps.java` remains a readable regular file but is omitted from the freshly scanned source inventory. | `java.maven.moveClass.sourceInventory.missingEntry` |
| `catalog-pricing:main` | The readable system-path JAR differs from the fingerprint recorded by its adjacent classpath-evidence manifest. | `java.maven.moveClass.classpathFingerprint.mismatch` |
| `catalog-storefront:main` | The import is removed from readable `ProductTile.java`, so the target use has only a recovered binding. | `java.maven.moveClass.targetUse.recoveredBinding` |
| `catalog-generated-support:main` | The readable materialized generated source changes while its checked-in generated-root inventory remains unchanged. | `java.maven.moveClass.materializedGeneratedRootInventory.fingerprintMismatch` |

Each unchanged copy is freshly scanned and previewed twice; guidance is snapshot/evidence-bound and CLI apply is refused before managed writes. The harness mutates only the isolated static inputs: it does not run Maven lifecycle goals, plugins, annotation processors, generators, wrappers, or network requests.

## REQ-JAVA-MAVEN-MOVE-AUTH-009 opt-in variant

The profile `req-java-maven-move-auth-009` has no activation and is therefore inactive by default. When the acceptance harness activates it and explicitly supplies `fixture-repository` as RefactorKit's local Maven repository, `catalog-pricing` gains the compile dependency `com.acme.fixture.external:field-scope-parent:1.0.0`. That artifact's POM explicitly declares all six field-shaped children as `test`, while the reactor's dependency management repeats the same JAR coordinates at `1.0.0-refactorkit-fixture`: reload4j, Log4j Core Test, and Lombok omit managed scope and default to `compile`; Log4j API Test, Log4j Core, and Spring Context Support declare managed scope `compile`. No coordinate has a classifier.

The six child POMs are materialized in Maven repository layout, but their six JARs are intentionally absent. This proves that an explicit transitive `test` declaration remains authoritative instead of being replaced by a matching managed default or `compile` scope. The same opt-in profile gives `reporting-unrelated` the ordinary dependency `com.acme.fixture.missing:outside-product-closure:1.0.0`; its POM is present and its JAR is intentionally absent so global `OFFLINE_MISSING` evidence stays outside the Product authority closure. These ordinary missing artifacts have no provided-type sidecars.

The present `field-scope-parent-1.0.0.jar` is a fixture-authored empty marker with no compiled code and no external source. It contains one ordered, uncompressed `META-INF/MANIFEST.MF` entry with the manifest bytes used by the existing fixture JAR, Unix mode `100644`, no ZIP extra fields or comments, and timestamp `1980-01-01T00:00:00Z`. Recreating that single entry with those fixed values yields:

```text
SHA-256  f54a2d563edf27ccd13bba1d26fcb845580cb2420e6aa3b1a4ed730bc8d038ed
```

No POM declares a repository; repository selection remains an explicit, offline harness input.

## REQ-JAVA-MAVEN-MOVE-AUTH-010 opt-in descriptor-pruning variant

The `catalog-pricing` profiles `req-java-maven-move-auth-010` and `req-java-maven-move-auth-010-alternate-path` have no activation and are inactive by default. The first profile adds the compile-visible target-closure dependency `com.acme.fixture.external:descriptor-pruning-parent:1.0.0`. Its dependency path carries the exact exclusion `org.springframework:spring-context-support`; the selected parent POM itself declares exactly the six fixed `1.0.0-refactorkit-fixture` children from the REQ-JAVA-MAVEN-MOVE-AUTH-010 table. Their transitive paths are pruned by `test` scope, `provided` scope, `optional=true`, or that exact inherited exclusion.

The fixture uses only supported deterministic Maven variants:

| Declared type | Classifier | Normalized JAR path suffix |
| --- | --- | --- |
| `jar` | omitted | `artifactId-1.0.0-refactorkit-fixture.jar` |
| `test-jar` | `tests` | `artifactId-1.0.0-refactorkit-fixture-tests.jar` |

The permanent fixture retains the child POMs needed by REQ-JAVA-MAVEN-MOVE-AUTH-009; its child JARs remain intentionally absent. REQ-JAVA-MAVEN-MOVE-AUTH-010 glue operates only on an isolated copy and removes the six child POM paths plus the six normalized variant JAR paths before traversal. This base-tree sharing does not change the REQ-JAVA-MAVEN-MOVE-AUTH-009 parent, profiles, dependency-management entries, or outside-closure artifact.

For the negative phase, activating `req-java-maven-move-auth-010-alternate-path` together with the main `010` profile directly selects `org.springframework:spring-context-support:1.0.0-refactorkit-fixture` at compile scope. That direct path has neither `optional` nor an exclusion, so the same coordinate's absent POM in the isolated copy becomes a required descriptor instead of a pruned non-node.

The present `descriptor-pruning-parent-1.0.0.jar` is a fixture-authored empty marker with the same reproducible one-entry ZIP recipe documented above: ordered and stored `META-INF/MANIFEST.MF`, Unix mode `100644`, no extra fields or comments, and timestamp `1980-01-01T00:00:00Z`. Its reproducible identity is:

```text
SHA-256  f54a2d563edf27ccd13bba1d26fcb845580cb2420e6aa3b1a4ed730bc8d038ed
```

## REQ-JAVA-MAVEN-MOVE-AUTH-011 opt-in sidecar-free ordinary missing-leaf variant

The `catalog-pricing` profile `req-java-maven-move-auth-011` has no activation and is inactive by default. When the acceptance harness activates it and explicitly supplies `fixture-repository` as a local-only Maven-2-layout repository, the profile adds the compile dependency `com.acme.fixture.missing:ordinary-missing-leaf:1.0.0` with type `jar` and no classifier. The version is fixed and non-snapshot.

The leaf's complete, self-contained POM is present at `fixture-repository/com/acme/fixture/missing/ordinary-missing-leaf/1.0.0/ordinary-missing-leaf-1.0.0.pom`. It declares JAR packaging and no parent, relocation, dependency management, BOM import, or dependency, so the selected external subtree is intentionally a dependency-free leaf.

Only that POM is materialized in the coordinate directory. `ordinary-missing-leaf-1.0.0.jar` and its adjacent `.refactorkit-evidence` sidecar are deliberately absent, and no expected JAR content hash, JAR-hash manifest, or provided-type manifest or inventory is supplied. This preserves an ordinary, sidecar-free missing-binary input instead of synthetic caller-attested binary or type evidence.

No fixture POM contains a repository, plugin, mirror, proxy, server, credential-helper, wrapper, snapshot, or network setting. The harness must select `fixture-repository` explicitly and remain offline.

## REQ-JAVA-MAVEN-MOVE-AUTH-012 static selected-descriptor variants

REQ-JAVA-MAVEN-MOVE-AUTH-012 uses only static inputs from this permanent fixture. An acceptance harness may copy one template into an isolated workspace and introduce one descriptor mutation there; the permanent baseline files are never mutated by a variant.

The fixture repository contains these non-reactor model inputs in Maven-2 layout:

| Coordinate | Purpose |
| --- | --- |
| `com.acme.fixture.authority:ordinary-missing-parent:1.0.0` | Dependency-free parent required by the parent-backed selected-leaf template. |
| `com.acme.fixture.authority:ordinary-missing-bom:1.0.0` | Dependency-free BOM imported by the imported-BOM selected-leaf template. |
| `com.acme.fixture.authority:ordinary-missing-management-bom:1.0.0` | BOM whose only managed row supplies `com.acme.fixture.missing:ordinary-missing-leaf:1.0.0` as an ordinary JAR. |

All three descriptors have `pom` packaging and no ordinary dependencies. The management BOM contains only its dependency-management declaration; it does not add a selected outgoing dependency.

The `catalog-pricing` profile `req-java-maven-move-auth-012-management` has no activation and is inactive by default. When explicitly selected in an isolated copy, it imports `ordinary-missing-management-bom:1.0.0` with type `pom` and scope `import`, then declares `com.acme.fixture.missing:ordinary-missing-leaf` without a version, with type `jar` and compile scope. The baseline BOM therefore mediates version `1.0.0`. The separate REQ-JAVA-MAVEN-MOVE-AUTH-011 profile retains its explicit fixed version.

The directory `fixture-variants/req-java-maven-move-auth-012` contains these non-active `.pom` templates:

| Template | Isolated-copy use |
| --- | --- |
| `ordinary-missing-leaf-parent-backed.pom` | Replace the selected leaf descriptor before independently removing its required fixture parent. |
| `ordinary-missing-leaf-imported-bom.pom` | Replace the selected leaf descriptor so its effective model requires the fixture-owned imported BOM. |
| `ordinary-missing-leaf-malformed.pom` | Replace the selected leaf descriptor with intentionally unclosed XML that cannot produce a raw or effective model. |
| `ordinary-missing-bom-drifted.pom` | Replace the hash-bound imported BOM with a well-formed descriptor of the same coordinate and changed content. |
| `ordinary-missing-management-bom-drifted.pom` | Replace the hash-bound management BOM with a well-formed descriptor of the same coordinate that instead mediates version `2.0.0`. |

The templates are deliberately named `.pom`, never `pom.xml`, so they cannot become active modules or additional project descriptors. Parent and BOM variants preserve the selected coordinate, direct dependency path, ordinary unclassified JAR identity, and dependency-free selected subtree. The selected leaf JAR, adjacent `.refactorkit-evidence` sidecar, expected JAR hash, and provided-type inventory remain absent in the baseline and every descriptor-only variant.

## Safety boundary and pending variants

Discovery and semantic analysis must not execute Maven lifecycle goals, Maven plugins, annotation processors, credential helpers, generators, wrappers, or network requests. The fixture contains no Gradle build and no Cucumber runner or runtime dependency; executable acceptance glue belongs to the RefactorKit Story BDD harness.

With no profile activation, this directory remains the deterministic positive baseline. The REQ-JAVA-MAVEN-MOVE-AUTH-012 files are static scaffolding and do not by themselves change the requirement's declared status. The four bounded, readable/enumerable REQ-JAVA-MAVEN-MOVE-AUTH-003 variants above are implemented; additional source-inventory, generated-root, classpath, and recovered-binding shapes are not implied. Analysis or residual-enumeration truncation; lost, unreadable, unsafe, or unbounded required structural input; and post-preview snapshot/evidence drift remain open under REQ-JAVA-MAVEN-MOVE-AUTH-008. Force, recipe, daemon, LSP, and MCP parity remains open under REQ-JAVA-MAVEN-MOVE-AUTH-004. Local-artifact shapes beyond the explicit variants, Java platform/provider inputs, child-root discovery, destination collisions, cross-module moves, source-root changes, nested or multiple top-level declarations, and JPMS/module-path moves also remain outside this fixture's supported move shape.
