# Captured second novel round public workflow replay

This freezes wholly fictional round 06's real frontend run
`24ec77b5-5695-41fb-934f-bbb2717f8567` on harness .23: 15 native sources,
15 core parts, 27 extraction dispatches, 108 original decisions and 27 unchanged
raw responses. No joint-review response was dispatched. The exact sourceHash,
every dispatch context offset and raw-response hash are verified. Original
failed displayed values and the incorrect accepted source-role decision remain
frozen. The separately sealed oracle is used only for assertions.

`DraftingCapturedBaselineWorkflowTest` replays every captured answer through the
public service workflow and harness intake with H2 and a mocked external model.
Scheduling is frozen; no captured answer is synthesized or rewritten. Test-only
empty follow-up replies are excluded from the exported original trace.

Assertions cover all 79 business states: 62 known values and 17 blank inactive
or withheld details. They also check domestic-block predicates, same-paragraph
footings pronoun scope, architect title/name projection, and rejection of a
standalone demolition-building quote as evidence for separated whole-project
sites. Raw replies/quotes stay intact, suggestions stay unadopted, effectiveValues
stay empty, and an unrelated adopted project stays unchanged.

This is offline regression of captured replies, not a fresh model examination or
browser acceptance. Exports are under
`target/captured-workflow-replay/captured-novel-round2`.
