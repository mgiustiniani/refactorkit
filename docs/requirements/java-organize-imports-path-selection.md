# Java organize-imports path selection

Authority: [the executable feature](../../features/java-organize-imports-path-selection.feature)
owns `REQ-JAVA-IMPORT-PATH-001#organize-imports-resolves-selected-paths-or-refuses-the-entire-request`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated architecture and status.
This satellite records the IDE defect and bounded implementation evidence.

The planner resolves absolute and relative selections against the snapshot workspace,
normalizes dot segments, and deduplicates equivalent paths before generated-source
checks and import analysis. The resulting patch retains workspace-relative paths.
A missing snapshot file refuses the entire request with `java.sourceFileNotFound`;
an escaping path uses `java.pathOutsideWorkspace`. A selected non-Java file uses
`java.unsupportedSourceLanguage`. Refusals have no edits, affected files or approval.

Six Cucumber cases exercise the real planner and CLI. Five genuine RED cases
reproduced successful empty previews or partial acceptance of invalid selections;
all six cases pass after the correction. Fixture bytes and snapshot hashes remain
unchanged. Existing runner/glue roots are reused, with four new step definitions.
No dependency or handwritten JUnit behavior test is added.

Development receipts, effective-source inventory, preview/before copies and RED/GREEN
reports live under `.refactorkit/runs/ide-java-organize-imports-path-r001/`.
This change corrects saved-snapshot selection. A versioned IDE changed/unchanged/refused
envelope, Java overlays, cursor-to-mutation selection and full Maven/Gradle effective
project context remain `actionable`; this requirement does not qualify those contracts.
