@functional-requirement
Feature: Java refactoring capability truth
  Requirement sources: docs/integration-contracts.md, docs/requirements/java-jdt-change-signature.md, P0 "Reconcile Java support discovery", bounded Java engine operations, and the P1 makeStatic operation.
  Integrated architecture status and qualification limits: docs/arc42/appendix-requirements.adoc#java-makestatic-saved-jdt-selection.
  This feature owns executable acceptance for individual behaviors; ARC42 owns integrated status, release qualification, and native IDE acceptance boundaries.
  Adapter descriptors, capability metadata, SDK-bound mutation discovery, and managed dispatch must agree about authority.
  RefactorKit change-signature operations are engine operations; standard LSP `textDocument/rename` and the custom LSP signature command are separate client/editor operations and do not prove daemon/MCP managed authority.

  @REQ-JAVA-CHANGE-SIGNATURE-REMOVE-001#an-unused-jdt-bound-parameter-is-removed-through-managed-java-surfaces
  Scenario: An unused JDT-bound parameter is removed through managed Java surfaces
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I remove the unused JDT-bound parameter through daemon and MCP
    Then adapter descriptors and SDK mutation discovery agree with registered Java mutation authority
    And daemon and MCP remove-parameter transactions update bound calls and restore exact source bytes
    And effectful removed arguments refuse before daemon or MCP transaction

  @REQ-JAVA-CAPABILITY-TRUTH-001#jdt-bound-java-parameter-rename-is-managed-through-daemon-and-mcp
  Scenario: JDT-bound Java parameter rename is managed through daemon and MCP
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I preview apply and roll back changeSignature.renameParameter through daemon and MCP
    Then changeSignature.renameParameter is advertised as stable managed Java mutation
    And daemon and MCP parameter-rename transactions pass diagnostics and restore exact source bytes

  @REQ-JAVA-CHANGE-SIGNATURE-TYPE-001#a-jdt-bound-parameter-type-changes-through-managed-daemon-and-mcp-surfaces
  Scenario Outline: A JDT-bound parameter type changes through managed daemon and MCP surfaces
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I preview, apply and roll back changing the "<shape>" parameter type through daemon and MCP
    Then changeSignature.changeParameterType is advertised as stable managed Java mutation
    And daemon and MCP "<shape>" type-change transactions replace only bound type tokens and restore exact source bytes

    Examples:
      | shape              |
      | private-method     |
      | cursor-primitive-boxing |
      | override-family    |
      | private-constructor|

  @REQ-JAVA-CHANGE-SIGNATURE-ADD-001#a-jdt-bound-private-method-gains-a-parameter-through-managed-daemon-and-mcp-surfaces
  Scenario: A JDT-bound private method gains a parameter through managed daemon and MCP surfaces
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I preview, apply and roll back adding a parameter through daemon and MCP
    Then changeSignature.addParameter is advertised as stable managed Java mutation
    And daemon and MCP add-parameter transactions update bound calls and restore exact source bytes

  @REQ-JAVA-MAKE-STATIC-001#a-private-instance-method-without-instance-dependencies-previews-and-rolls-back-as-static
  Scenario: A private instance method without instance dependencies previews and rolls back as static
    Given a Java class with an isolated private instance method
    When I preview makeStatic for the selected method
    Then the preview adds one static modifier with clean diagnostics
    And applying and rolling back the preview restores the exact workspace

  @REQ-JAVA-MAKE-STATIC-001#saved-jdt-method-selection-authorizes-makestatic-preview
  Scenario: Saved JDT method selection authorizes makeStatic preview
    Given a Maven Java class with an isolated private instance method and SDK context
    When I preview makeStatic with the exact saved JDT method selection
    Then the preview adds one static modifier with clean diagnostics
    And applying and rolling back the preview restores the exact workspace
    And the preview and applied transaction retain the selected method identity

  @REQ-JAVA-MAKE-STATIC-001#lsp-code-actions-expose-only-eligible-makestatic-proposals
  Scenario Outline: LSP code actions expose only eligible makeStatic proposals
    Given a Java class has makeStatic eligibility shape "<shape>" in an open LSP document
    When I request the makeStatic code action for its method
    Then the LSP makeStatic action is "<result>" and workspace bytes remain unchanged

    Examples:
      | shape              | result    |
      | isolated            | available |
      | instance-dependent  | disabled  |

  @REQ-JAVA-CHANGE-SIGNATURE-REORDER-001#jdt-bound-parameter-reorder-enforces-evaluation-order
  Scenario Outline: JDT-bound parameter reorder enforces evaluation order
    Given a JDT-bound Java method has "<call-shape>" caller values in an offline Maven project
    When I request parameter reordering through the daemon and MCP
    Then the reorder outcome is "<outcome>" with no workspace mutation
    And changeSignature.reorderParameters is advertised as "<authority>"

    Examples:
      | call-shape                 | authority | outcome                    |
      | local-values                | stable    | applied-and-rolled-back    |
      | side-effecting-call-values  | stable    | refused-before-transaction |

  @REQ-JAVA-CHANGE-SIGNATURE-REORDER-001#saved-cursor-reorder-is-managed-through-daemon-and-mcp
  Scenario: Saved cursor reordering applies and rolls back exact JDT-bound edits
    Given a JDT-bound Java method has "cursor-local-values" caller values in an offline Maven project
    When I request parameter reordering through the daemon and MCP
    Then the reorder outcome is "applied-and-rolled-back" with no workspace mutation
    And changeSignature.reorderParameters is advertised as "stable"

  @REQ-JAVA-CHANGE-SIGNATURE-REORDER-001#hierarchy-literal-reorder-is-managed-through-daemon-and-mcp
  Scenario: JDT-bound hierarchy reorder preserves string and boolean literal call arguments
    Given a JDT-bound Java method has "hierarchy-literal-values" caller values in an offline Maven project
    When I request parameter reordering through the daemon and MCP
    Then the reorder outcome is "applied-and-rolled-back" with no workspace mutation
    And changeSignature.reorderParameters is advertised as "stable"

  @REQ-JAVA-CHANGE-SIGNATURE-REORDER-001#public-method-reorder-refuses-without-external-consumer-and-hierarchy-evidence
  Scenario: Public method reorder refuses without external-consumer and hierarchy evidence
    Given a JDT-bound Java method has "cursor-public-method" caller values in an offline Maven project
    When I request parameter reordering through the daemon and MCP
    Then the reorder outcome is "refused-before-transaction" with no workspace mutation
    And changeSignature.reorderParameters is advertised as "stable"

  @REQ-JAVA-CHANGE-SIGNATURE-REMOVE-001#saved-cursor-removal-reports-the-observable-argument-effect
  Scenario Outline: Saved cursor removal reports the observable argument effect
    Given an SDK-bound Maven method whose unused parameter receives a "<argument>"
    When I preview removal at the saved cursor for the method
    Then Remove Parameter refuses with "<refusal>" and preserves the Maven project

    Examples:
      | argument          | refusal                                             |
      | method call       | java.changeSignature.removedArgumentMayHaveEffects |
      | field increment   | java.changeSignature.removedArgumentMayHaveEffects |
      | commented literal | none                                                |
