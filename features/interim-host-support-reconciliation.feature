# language: en
# Authority: bounded C17 support-reconciler policy; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Reconcile historical support without demanding deferred hosts
  The historical native ledger retains its four accepted rows. Current interim
  workflow selection proves only Linux and must retain source identity and
  fail-closed native verification without widening support claims.

  @REQ-C-HOST-RECONCILE-001 @interim-host-support-reconciliation
  Scenario Outline: Interim support reconciliation retains historical evidence and current guards
    Given the support reconciler observes "<condition>" in an interim workflow
    When support claims are reconciled against that workflow
    Then the reconciliation is "<disposition>" without relabelling historical host evidence
    And reconciliation preserves the original repository and historical records

    Examples:
      | condition               | disposition |
      | intact Linux scope      | accepted    |
      | missing source identity | refused     |
      | missing finalizer       | refused     |
      | foreign interim host    | refused     |
      | bypassed prerequisite   | refused     |
      | widened support claim   | refused     |
