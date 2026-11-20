# language: en
# Authority: bounded acquired formatting source context; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Format only the acquired current C source
  A preview cannot compare formatter output from a different live file with an earlier source image.

  @REQ-C-FORMAT-SOURCE-001 @c-format-source-context
  Scenario Outline: Formatting retains its acquired C source or refuses before process execution
    Given an acquired C formatting snapshot has "<condition>" source evidence
    When the managed C format preview is requested
    Then formatting source disposition is "<disposition>" without an unauthorized proposal
    And formatting source checks preserve the recorded workspace and reap owned processes

    Examples:
      | condition             | disposition       |
      | relative capture      | formatting preview |
      | absolute capture      | formatting preview |
      | captured bytes change | source refused     |
      | captured file removed | source refused     |
      | source link inserted  | source refused     |
      | source not captured   | source refused     |

  @REQ-C-FORMAT-RANGE-001#a-bounded-c-formatting-range-preserves-unselected-content
  Scenario Outline: A bounded C formatting range preserves unselected content
    Given a scanned C formatting workspace with "<range_state>" style evidence
    When the managed C format preview is requested
    Then the C formatter style decision is "preview"
    And the bounded C ranges preview changes only selected regions and remains idempotent
    And the formatter style check preserves workspace inputs and leaves no transaction journal

    Examples:
      | range_state                                        |
      | captured project style and a bounded range        |
      | captured project style and two discontiguous ranges |
      | captured project style and 32 discontiguous ranges  |

  @REQ-C-FORMAT-RANGE-001#invalid-c-format-ranges-refuse-before-formatter-launch
  Scenario Outline: Invalid C format ranges refuse before formatter launch
    Given a scanned C formatting workspace with "<range_state>" style evidence
    When the managed C format preview is requested
    Then the C formatter style decision is "refused"
    And the formatter style check preserves workspace inputs and leaves no transaction journal

    Examples:
      | range_state                                    |
      | captured project style and a partial range    |
      | captured project style and a malformed range  |
      | captured project style and a reversed range   |
      | captured project style and an out-of-file range |
      | captured project style and a malformed range list |
      | captured project style and overlapping ranges  |

  @REQ-C-FORMAT-RANGE-001#invalid-multi-ranges-refuse-when-out-of-order-or-out-of-file
  Scenario Outline: Invalid multi-ranges refuse when out of order or out of file
    Given a scanned C formatting workspace with "<range_state>" style evidence
    When the managed C format preview is requested
    Then the C formatter style decision is "refused"
    And the formatter style check preserves workspace inputs and leaves no transaction journal

    Examples:
      | range_state                                               |
      | captured project style and an out-of-order range list     |
      | captured project style and an out-of-file range list      |

  @REQ-C-FORMAT-RANGE-001#c-format-range-lists-over-the-32-range-limit-refuse-before-formatter-launch
  Scenario Outline: C format range lists over the 32-range limit refuse before formatter launch
    Given a scanned C formatting workspace with "<range_state>" style evidence
    When the managed C format preview is requested
    Then the C formatter style decision is "refused"
    And the formatter style check preserves workspace inputs and leaves no transaction journal

    Examples:
      | range_state                                                     |
      | captured project style and a range list above 32 entries        |

  @REQ-C-FORMAT-SOURCE-001#managed-c-formatting-surfaces-capture-the-nearest-project-style
  Scenario Outline: Managed C formatting surfaces capture the nearest project style
    Given a native C formatting project with "<style>" project style
    When "<surface>" performs the managed C formatting lifecycle
    Then the C formatting surface reports "<outcome>"
    And all captured C formatting files and transaction states match the outcome

    Examples:
      | surface | style       | outcome                |
      | CLI     | nearest     | applied and restored   |
      | daemon  | nearest     | applied and restored   |
      | MCP     | nearest     | applied and restored   |
      | daemon  | changed     | stale style refused    |
      | CLI     | conflicting | ambiguous style refused |

  @REQ-C-FORMAT-RANGE-001#cli-formatting-honors-explicit-line-range-lists
  Scenario Outline: CLI formatting honors explicit line range lists
    Given a local C file with three separate unformatted functions
    When the C CLI previews formatting with "<selection>"
    Then the native C formatting preview gives "<outcome>"
    And the CLI range preview preserves source bytes and creates no transaction

    Examples:
      | selection           | outcome                 |
      | disjoint           | only the selected lines |
      | single range       | only the first line     |
      | overlapping        | invalid range refused   |
      | both forms         | invalid range refused   |
      | missing list value | malformed option refused|
