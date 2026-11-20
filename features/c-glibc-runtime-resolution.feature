@REQ-C-GLIBC-RESOLUTION-001
Feature: Captured C glibc runtime resolution
  Recursive resolution uses each object's RUNPATH and the selected cache.
  Captures must retain selected objects and unsuccessful search inputs.

  @REQ-C-GLIBC-RESOLUTION-001#c-glibc-runtime-resolution-captures-reference-graphs-or-refuses-unavailable-inputs
  Scenario Outline: C glibc runtime resolution captures reference graphs or refuses unavailable inputs
    Given a C glibc runtime resolution input with "<condition>"
    When the C glibc runtime graph is resolved and checked
    Then the C glibc runtime resolution outcome is "<outcome>"
    And glibc resolution preserves source and executes no selected artifact

    Examples:
      | condition              | outcome     |
      | compiler               | current     |
      | server                 | current     |
      | formatter              | current     |
      | changed cache          | not-current |
      | changed library        | not-current |
      | absent cache candidate | refused     |
