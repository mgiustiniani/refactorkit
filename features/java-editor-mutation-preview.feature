# Architecture narrative: docs/arc42/appendix-requirements.adoc#JavaEditorTransactionDelivery
# Authority: executable editor-preview/transaction/recovery requirements; protocol wire details live in docs/java-project-context-protocol.md.
@functional-requirement @REQ-JAVA-EDITOR-PREVIEW-001
Feature: Dirty Java mutation preview with versioned editor edits
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.

  @REQ-JAVA-EDITOR-PREVIEW-001#sdk-26-editor-preview-attests-the-acquired-context-schema
  Scenario Outline: SDK 26 editor preview attests the acquired context schema
    Given a verified SDK26 "<provider>" editor workspace selects "<schema>" context for release "<release>"
    # Legacy source grammar remains 8..25. A refused context must stop this
    # consumer workflow before mutation selection or editor preview is requested.
    When the IDE attempts its SDK26 editor rename with the acquired context
    Then the SDK26 editor workflow reports "<outcome>" with exact context authority
    And the SDK26 editor preview preserves saved inputs and SDK authority

    Examples:
      | provider | schema | release | outcome          |
      | Maven    | v2     | 17      | semantic preview |
      | Maven    | v3     | 26      | source26 refused |
      | Maven    | v7     | 26      | semantic preview |
      | Gradle   | v2     | 26      | source26 refused |
      | Gradle   | v3     | 26      | source26 refused |
      | Gradle   | v7     | 17      | semantic preview |

  @REQ-JAVA-EDITOR-PREVIEW-001#dirty-java-previews-bind-the-sdk-and-closed-buffer-versions-without-writing-sources
  Scenario Outline: Dirty Java previews bind the SDK and closed buffer versions without writing sources
    Given closed dirty Java mutation buffers with explicit SDK authority
    When editor preview requests "<operation>" after "<event>"
    Then editor preview reports "<outcome>" with exact versioned edit authority
    And editor preview preserves saved source bytes and refuses ordinary managed apply
    Examples:
      | operation    | event                     | outcome                     |
      | renameClass  | none                      | preview                     |
      | renameMember | none                      | preview                     |
      | formatFile   | none                      | preview                     |
      | renameClass  | missing affected document | java.editor.documentsMissing |
      | renameClass  | older document version    | java.overlay.versionStale    |
      | renameClass  | SDK drift                 | java.context.contextStale    |

  @REQ-JAVA-EDITOR-PREVIEW-001#dirty-cursors-select-exact-bindings-with-current-overlay-version-authority
  Scenario Outline: Dirty cursors select exact bindings with current overlay version authority
    Given closed dirty Java mutation buffers with explicit SDK authority
    When dirty cursor "<target>" passes its selector to preview after "<event>"
    Then dirty cursor preview reports "<outcome>" with current compiler and version identity
    And editor preview preserves saved source bytes and refuses ordinary managed apply
    Examples:
      | target       | event               | outcome                     |
      | class        | none                | preview                     |
      | dirty method | none                | preview                     |
      | dirty field  | none                | preview                     |
      | class        | altered binding     | java.selection.selectorStale |
      | class        | new document version | java.selection.selectorStale |
      | class        | same version changed | java.overlay.versionStale   |

  Scenario Outline: Editor owned transactions confirm exact edits and undo or refuse stale authority
    Given closed dirty Java mutation buffers with explicit SDK authority
    When the editor prepares "<operation>" and confirms its own edits after "<event>"
    Then the editor transaction reports "<outcome>" and preserves guarded buffer ownership
    And editor preview preserves saved source bytes and refuses ordinary managed apply

    Examples:
      | operation    | event              | outcome                       |
      | renameClass  | complete roundtrip | undone                        |
      | renameMember | complete roundtrip | undone                        |
      | formatFile   | altered approval   | java.editor.approvalMismatch  |
      | renameClass  | advanced buffer    | java.editor.planStale         |
      | renameClass  | SDK drift          | java.context.contextStale     |
      | renameMember | external post edit | recovery-required             |

  Scenario Outline: Journals recover editor transactions after restart with exact guards
    Given closed dirty Java mutation buffers with explicit SDK authority
    When the daemon restarts with an editor transaction after "<event>"
    Then durable editor recovery reports "<outcome>" without overwriting unknown changes
    And editor preview preserves saved source bytes and refuses ordinary managed apply

    Examples:
      | event                     | outcome                   |
      | prepared intact           | aborted                   |
      | prepared full application | undone                    |
      | confirmed application     | undone                    |
      | partial application       | undone                    |
      | unknown external content  | recovery-required         |
      | corrupt journal           | java.editor.journalCorrupt |

  Scenario Outline: Editor transactions refuse late authority drift and survive inverse confirmation restart
    Given closed dirty Java mutation buffers with explicit SDK authority
    When an editor transaction encounters "<event>" at its guarded boundary
    Then the guarded transaction reports "<outcome>" with "<code>" and never overwrites external state
    And editor preview preserves saved source bytes and refuses ordinary managed apply

    Examples:
      | event                        | outcome           | code                       |
      | SDK after edit               | recovery-required | java.context.contextStale  |
      | build inputs after edit      | recovery-required | java.context.snapshotStale |
      | classpath rebuild after edit | recovery-required | java.context.snapshotStale |
      | buffer before undo           | refused           | java.editor.undoStale      |
      | journal after prepare        | refused           | java.editor.journalCorrupt |
      | restart after inverse        | undone            | none                       |

  Scenario Outline: Qualified editor operations roundtrip with durable authority and refuse unqualified requests
    Given closed dirty Java mutation buffers with explicit SDK authority
    When the durable editor contract executes "<operation>" after "<event>"
    Then qualified editor operation reports "<outcome>" and restores its original dirty content
    And editor preview preserves saved source bytes and refuses ordinary managed apply

    Examples:
      | operation       | event              | outcome                          |
      | renameClass     | complete roundtrip | undone                           |
      | renameMember    | complete roundtrip | undone                           |
      | formatFile      | complete roundtrip | undone                           |
      | organizeImports | complete roundtrip | undone                           |
      | safeDelete      | unsupported        | java.editor.operationUnsupported |
      | renameMember    | external post edit | recovery-required                |

  Scenario Outline: Explicit durable editor admission keeps read only preview compatible
    Given closed dirty Java mutation buffers with explicit SDK authority
    When the editor requests "<operation>" in "<mode>" admission mode
    Then editor mutation admission reports "<outcome>" with the qualified operation catalogue
    And editor preview preserves saved source bytes and refuses ordinary managed apply

    Examples:
      | operation       | mode                 | outcome   |
      | renameClass     | durable-editor-owned | available |
      | renameMember    | durable-editor-owned | available |
      | formatFile      | durable-editor-owned | available |
      | organizeImports | durable-editor-owned | available |
      | renameClass     | default              | read-only |
      | renameClass     | unknown-mode         | invalid   |

  @REQ-JAVA-EDITOR-PREVIEW-001#restart-discovery-exposes-complete-original-document-lineage-without-source-text
  Scenario Outline: Restart discovery exposes complete original document lineage without source text
    Given closed dirty Java mutation buffers with explicit SDK authority
    When the IDE loses its captured inventory after "<operation>" reaches "<state>"
    Then discovery reconstructs all original documents including clean tabs and permits "<outcome>"
    And editor preview preserves saved source bytes and refuses ordinary managed apply

    Examples:
      | operation       | state     | outcome |
      | renameClass     | prepared  | aborted |
      | renameClass     | partial   | undone  |
      | renameClass     | applied   | undone  |
      | renameMember    | confirmed | undone  |
      | formatFile      | applied   | undone  |
      | organizeImports | applied   | undone  |
