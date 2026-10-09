# Captured legacy-values replay

This freezes the previous successful `.20` frontend exam (run
`f41b5531-f0d0-4565-9191-ee2466df802c`) of the 15-document
`03_missing_evidence_test` scenario. The complete source text, 35 recorded
extraction responses and their exact request contexts remain unchanged. There
were no joint-review calls in this run. Its 79-input oracle is separate and is
used only after replay for assertions.

The shared `DraftingCapturedAlternateWorkflowTest` runs both old and new scenarios
through production intake and persistence in isolated H2. It preserves original
source IDs and raw responses, makes no fresh model calls, and exports each case
under `target/captured-workflow-replay/<fixture-name>`. The existing evaluator can
score those files against the matching original DOCX fixture; semantic quotation
diagnostics are not relaxed.
