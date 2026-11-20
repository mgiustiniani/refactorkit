@operational-requirement @partial @REQ-C-SOURCE-CAPTURE-001
Feature: Owned source capture ancestor identity
  Ordinary sibling activity outside the workspace does not change the identity
  of an already verified ancestor directory. Capture still owns no-follow secure
  descriptors, rejects substituted ancestor inodes or links, and refuses real
  workspace membership changes. Deterministic interleavings use real NIO handles
  and real file attributes, without production test hooks or a weaker path guard.

  @REQ-C-SOURCE-CAPTURE-001#owned-source-capture-separates-ancestor-membership-from-input-identity
  Scenario Outline: Owned source capture separates ancestor membership from input identity
    Given an owned source capture boundary has "<condition>"
    When the owned NIO capture crosses the controlled directory open boundary
    Then capture reports "<outcome>" with exact input authority and no partial result
    And capture preserves declared inputs and the external canary

    Examples:
      | condition                       | outcome       |
      | flat inventory ancestor churn   | captured      |
      | selected roots ancestor churn   | captured      |
      | opaque input ancestor churn     | captured      |
      | ancestor inode substitution     | INPUT_CHANGED |
      | ancestor link substitution      | IO_UNAVAILABLE|
      | workspace membership change     | INPUT_CHANGED |
