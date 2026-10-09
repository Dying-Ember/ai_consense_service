# Captured alternate-values replay

`fixture.json` freezes the real frontend exam with run ID
`1917802c-34e0-4adc-a532-587787fffb3f`. It includes the original source
text, exact request contexts, 28 extraction answers and two joint-review answers.
The source documents are wholly fictional correspondence. Their complete original
source text is reconstructed from the recorded contiguous core parts and checked
against every attempt's exact source offsets and against recorded source hashes.

`expected.json` is the independently authored 79-input oracle. It is used only
for assertions after extraction. It is never returned by the replay adapter or
inserted into an extraction prompt.

`DraftingCapturedAlternateWorkflowTest` freezes dispatch scheduling to replay all
recorded extraction answers, including replies to repairs and recalls which a
fixed harness might no longer request. Production decoding, intake, current-run
applicability, joint-review validation, candidate merging, persistence and plan
generation still run. Joint answers are returned unchanged if production asks
for the corresponding captured pair; a fixed harness may avoid that request.
Any other model request fails the test. This verifies reception of already
observed answers, not new model quality or changed-prompt scheduling.

The test creates a fresh isolated H2 database and leaves existing local projects
and the original captured run untouched. It writes `variables.json`, `trace.json`,
`plan.json`, `inputs.json`, `catalog.json` and a replay receipt under
`target/captured-workflow-replay/captured-alternate-values`. Those exports can be scored by the unchanged
offline fixture evaluator using the original document byte manifest. Literal
source quotes and oracle semantic diagnostics are not widened by this replay.
