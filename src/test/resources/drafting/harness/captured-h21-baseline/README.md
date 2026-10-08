# Captured .21 baseline public workflow replay

The resource freezes real frontend baseline run
`bca1b3bc-b75b-4fb6-a8cc-7862d3b94166`: 16 parts, 26 extraction dispatches,
113 original intake decisions and 27 unchanged raw responses, including one
joint review. The 15 source documents are wholly fictional 04 correspondence.
Their unchanged source texts are matched against the earlier curated fictional
resource by filename and source hash, and checked against every original
dispatch's exact source offsets. No real project or official template body is
published in this resource.

The original trace, variables and runtime file hashes are recorded in
`provenance`. Original raw strings, parts, contexts, decisions and the original
wrong persisted variables remain frozen. Runtime curation retains the actual
model and budget settings and artifact/source fingerprints; machine paths, PID
receipts and operational ports are omitted and explicitly identified. The
complete original runtime file remains local and its hash is retained.

The test reads the existing independently authored 04 expected resource for
assertions only. It remains unchanged and its hash is pinned. The expected
business outcome is 69 complete nonempty suggestions and 10 blank inputs;
expected answers are never used as model output or inserted into a prompt.

`DraftingCapturedBaselineWorkflowTest` uses the public Drafting service with H2,
AiGateway and the external LlmClient boundary double. It reuses
`DraftHarnessTestIntake` to send each exact captured extraction response through
the public harness's actual decoder and grounding checks. Recorded dispatch
scheduling is frozen; the disposable intake trace can receive test-only empty
repair/coverage replies. Those replies are not captured answers, cannot rescue
the primary item and are excluded from the exported captured trace. No fake
empty response is added to this resource or claimed as a real model reply.

The original joint answer is returned unchanged only if production asks for
its captured key. A fixed harness may avoid that request; unused captured joint
responses remain preserved in this fixture and are explicitly counted in the
replay receipt. Any other service-level model request fails the test.

The test verifies the source-wrong SOR-only BQ media candidate is rejected,
retains the correct BQ source, shows the complete four-row design suggestion
with its complete source/note, leaves all suggestions unadopted, keeps plan
effectiveValues empty and preserves an unrelated adopted project. Exports are
under `target/captured-workflow-replay/captured-h21-baseline`. This is offline
regression of recorded answers, not a new live model score, prompt-scheduling
evaluation or browser acceptance.
