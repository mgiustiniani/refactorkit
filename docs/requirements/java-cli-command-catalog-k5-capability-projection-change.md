# Approved Java CLI catalogue compatibility-oracle update for K5

Status: approved non-weakening test-oracle update.

The Java CLI command-catalogue v1/v2 schemas, command members, parser reachability, Java usage lines, mutation boundaries and exclusions are unchanged. Their BDD scenarios also invoke the independent `refactorkit capabilities` and top-level help surfaces as byte-exact compatibility tripwires. Kotlin K5 adds truthful Kotlin help lines and additive language-capability rows without adding any Java command-catalogue entry.

Therefore the compatibility side inputs are repinned to the exact current source-built bytes:

- capabilities: `modules/refactorkit-cli/src/test/resources/org/refactorkit/cli/reqjavaclicatalog001/capabilities-k5-candidate-a8cc8f03c496.json`, SHA-256 `a8cc8f03c496d7b48edb307e52e83358fbc34aa53205a68687b17e3cbb9325e3`;
- pre-slice help compatibility baseline with only the additive K5 lines: `modules/refactorkit-cli/src/test/resources/org/refactorkit/cli/reqjavaclicatalog001/help-k5-candidate-56d503763b94.txt`, SHA-256 `56d503763b94b513cdfe370a117a48786a7b669312bb30f911e3308591fe5a54`. The Java catalogue-owned help lines remain layered and tested separately.

This update does not qualify Kotlin K5, change either command-catalogue oracle, authorize another Java command, relax any closed validator, or weaken workspace/write tripwires. The capability projection remains distinct from the Java command catalogue, and K5 promotion still requires its own native evidence and review.
