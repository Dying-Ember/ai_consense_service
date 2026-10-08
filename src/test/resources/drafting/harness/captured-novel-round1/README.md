# Captured first novel round public workflow replay

This freezes wholly fictional round 05's real frontend run
`12d06aec-a541-4e21-a400-0632b80d0d3e` on harness .22: 15 native sources,
16 core parts, 27 extraction dispatches, 110 original decisions and 29 unchanged
raw responses including two joint replies. Every source is reconstructed from
unchanged primary core parts in partIndex order, matched to its exact sourceHash,
and checked against all recorded context offsets. The original failed variables
remain frozen. The oracle is separately pinned and is used for assertions only.

`DraftingCapturedBaselineWorkflowTest` also runs this fixture via the public
service workflow and public harness intake, with H2 and a mocked external
LlmClient. Scheduling is frozen; no captured answer is rewritten. Disposable
empty follow-ups cannot repair a failed captured item and do not enter the
exported original trace. Joint answers are returned unchanged only if production
requests their captured key, and unused joint replies remain counted.

Assertions cover all 79 displayed business states (68 known and 11 blank),
the complete design preface and table, the nine waterproofing areas including
continuations, BQ issue format, formal Sections, block identifiers and rejection
of unsupported scope candidates. Suggestions stay unadopted, effectiveValues
stay empty, and an unrelated adopted project stays unchanged.

This is offline regression of captured replies, not a new model exam or browser
acceptance. Run exports are under target/captured-workflow-replay/captured-novel-round1.
