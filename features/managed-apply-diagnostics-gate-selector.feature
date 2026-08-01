# language: en
@non-functional-requirement @implemented-and-validated
Feature: Preserve managed-apply diagnostics-gate selection across daemon and MCP
  Daemon and MCP need one shared routing decision without changing diagnostics or apply authority.
  The selector returns one lazy gate from exact pending-plan metadata and the adapters current for that call.
  CLI, managed LSP, recipes, testkit, and direct library apply remain outside this bounded extraction.

  @REQ-MANAGED-APPLY-DIAGNOSTICS-SELECTOR-001
  Scenario Outline: Selection case "<case>" follows the first matching ordered route
    Given the selector owns only this precedence-ordered routing table:
      | order | exact predicate                                                                                                     | gate ID rule                | lazy provider or result                                                                  |
      | 1     | languageId == "java" and operation == "java.moveAcrossMavenModules"                                                | java-maven-ownership       | JavaMoveAcrossMavenModulesPlanner(currentJavaAdapter)::diagnostics                        |
      | 2     | languageId == "java" after row 1 fails                                                                            | java-jdt                   | currentJavaAdapter::diagnostics                                                           |
      | 3     | languageId == "kotlin" and javaAffected and operation == "moveDeclaration"                                       | kotlin-k2-java-jdt         | KotlinJvmMoveDeclarationPlanner(currentKotlinAdapter).diagnostics(candidate)              |
      | 4     | languageId == "kotlin" and javaAffected and operation != "moveDeclaration" and evidence == "JDT_BINDING"         | kotlin-k2-java-jdt         | JavaKotlinPublicTypeRenamePlanner(currentKotlinAdapter).diagnostics(candidate)             |
      | 5     | languageId == "kotlin" and javaAffected after rows 3 and 4 fail                                                    | kotlin-k2-java-jdt         | KotlinJavaPublicTypeRenamePlanner(currentKotlinAdapter).diagnostics(candidate)             |
      | 6     | languageId == "kotlin" and not javaAffected                                                                        | kotlin-k2                  | currentKotlinAdapter.compilerDiagnostics(candidate).diagnostics                           |
      | 7     | every other exact languageId                                                                                         | resolver gate ID unchanged  | externalGateResolver(languageId) return value unchanged                                   |
    And javaAffected means exactly that an original plan.affectedFiles final segment satisfies `fileName.toString().endsWith(".java")`
    And the pending plan has exact language ID "<language ID>", operation "<operation>", evidence "<evidence>", and original affected files "<affected files>"
    And for an external route the surface resolver returns an exact lazy gate with ID "<resolver gate ID>"
    When daemon or MCP asks the stateless selector to choose the managed-apply diagnostics gate
    Then precedence row "<row>" is the first match
    And the returned result has exact gate ID "<gate ID>" and is "<provider or identity>"
    And the selector does not normalize language IDs or paths, infer languages, recompute affected files, or narrow unusual metadata combinations

    Examples:
      | case                                      | language ID | operation                    | evidence         | affected files                                                     | resolver gate ID                  | row | gate ID                          | provider or identity                                                                   |
      | exact Java Maven ownership                | java        | java.moveAcrossMavenModules  | STRUCTURAL       | module-a/src/main/java/com/acme/Foo.java                            | unused                            | 1   | java-maven-ownership             | lazy JavaMoveAcrossMavenModulesPlanner provider using the current Java adapter          |
      | ordinary Java                             | java        | renameClass                  | JDT_BINDING      | src/main/java/com/acme/Foo.java                                     | unused                            | 2   | java-jdt                         | lazy diagnostics provider of the current Java adapter                                   |
      | Java-affected Kotlin move beats evidence  | kotlin      | moveDeclaration              | JDT_BINDING      | src/main/kotlin/Foo.kt, src/main/java/com/acme/FooUser.java         | unused                            | 3   | kotlin-k2-java-jdt               | lazy KotlinJvmMoveDeclarationPlanner provider using the current Kotlin adapter           |
      | Java-affected non-move JDT binding        | kotlin      | renameClass                  | JDT_BINDING      | src/main/java/com/acme/FooUser.java, src/main/kotlin/Foo.kt         | unused                            | 4   | kotlin-k2-java-jdt               | lazy JavaKotlinPublicTypeRenamePlanner provider using the current Kotlin adapter          |
      | Java-affected broad fallback              | kotlin      | deliberately-unusual         | NATIVE_AST       | src/main/kotlin/Foo.kt, src/main/java/com/acme/FooUser.java         | unused                            | 5   | kotlin-k2-java-jdt               | lazy KotlinJavaPublicTypeRenamePlanner provider using the current Kotlin adapter          |
      | Kotlin-only before operation and evidence | kotlin      | moveDeclaration              | JDT_BINDING      | src/main/kotlin/Foo.kt                                              | unused                            | 6   | kotlin-k2                        | lazy compilerDiagnostics result provider of the current Kotlin adapter                   |
      | uppercase JAVA is not Java-affected       | kotlin      | moveDeclaration              | JDT_BINDING      | src/main/java/com/acme/Foo.JAVA                                    | unused                            | 6   | kotlin-k2                        | lazy compilerDiagnostics result provider of the current Kotlin adapter                   |
      | case-changed Java ID is external          | Java        | java.moveAcrossMavenModules  | JDT_BINDING      | src/main/java/com/acme/Foo.java                                     | external-case-sensitive-java-v1  | 7   | external-case-sensitive-java-v1 | the exact resolver-returned gate object unchanged                                        |
      | case-changed Kotlin ID is external        | Kotlin      | moveDeclaration              | JDT_BINDING      | src/main/java/com/acme/FooUser.java                                 | external-case-sensitive-kotlin-v1 | 7   | external-case-sensitive-kotlin-v1 | the exact resolver-returned gate object unchanged                                        |
      | ordinary external ID is opaque            | typescript  | rename                       | LANGUAGE_SERVER  | src/Foo.ts                                                          | typescript-compiler-exact-v1      | 7   | typescript-compiler-exact-v1     | the exact resolver-returned gate object unchanged                                        |

  @REQ-MANAGED-APPLY-DIAGNOSTICS-SELECTOR-002
  Scenario: Selection remains lazy, per-call, and bounded to the duplicate daemon and MCP decision
    Given each managed-apply attempt supplies the selector with its adapter fields current for that call:
      | attempt | surface                 | current Java adapter | current Kotlin adapter |
      | first   | daemon refactor.apply   | java-current-1       | kotlin-current-1       |
      | second  | MCP apply_refactoring   | java-current-2       | kotlin-current-2       |
    And every built-in diagnostics provider, external gate provider, and external adapter lookup is independently observable
    When all six built-in routes and these isolated external outcomes are selected without invoking PatchEngine:
      | selection        | exact language ID | surface resolver behavior       | resolver calls during selection |
      | external success | typescript        | return exact lazy gate G        | exactly 1                       |
      | external failure | Java              | throw exact exception E_external | exactly 1                       |
    Then every built-in route performs zero external adapter lookups and zero diagnostics-provider invocations during selection
    And selection performs none of this built-in provider work:
      | deferred provider work                                                        |
      | Java adapter diagnostics                                                      |
      | Maven ownership materialization, offline scanning, rebuilding, or diagnostics |
      | Kotlin compiler or K2 diagnostics                                             |
      | mixed-JVM JDT analysis or ephemeral Java compilation                          |
    And each built-in gate uses the exact Java or Kotlin adapter argument supplied on its own call, without retaining a startup or previous adapter or creating a replacement
    And each external selection performs exactly one synchronous adapter lookup during selection, with no lookup for built-in IDs and no lookup deferred into a provider
    And successful external selection returns the exact gate G unchanged while G's provider remains unexecuted
    And failed external selection propagates the same E_external object with its exact type, code, message, and cause, without catching, wrapping, or translating it
    And the selector performs none of this apply or surface-owned work:
      | excluded work                                                                                 |
      | invoke PatchEngine or acquire the workspace lock                                               |
      | run baseline, staged, post-image, or restored-baseline diagnostics                             |
      | perform recovery, regression comparison, WAL, mutation, automatic rollback, or explicit rollback |
      | validate or clean pending plans, Kotlin leases, or index generations                           |
      | start, stop, restart, reset, or close adapters, toolchains, or sessions                         |
      | render responses or errors, refresh state, bound diagnostics, or run daemon post-success work  |
    And selector adoption is limited to these duplicate managed-apply call sites:
      | surface | adopting call site                |
      | daemon  | DaemonSession.refactorApply       |
      | MCP     | McpSession.toolApplyRefactoring   |
    But these excluded surfaces preserve their existing behavior:
      | excluded surface           | behavior preserved                                                              |
      | CLI                        | Java-only Maven-or-java-jdt selection and its fresh-adapter behavior              |
      | managed LSP                | java-jdt selection, including Maven ownership moves                               |
      | recipes                    | existing java-jdt or injected diagnostics path and module boundary                |
      | testkit and direct library | caller-owned diagnostics-gate selection and direct-apply behavior                 |
