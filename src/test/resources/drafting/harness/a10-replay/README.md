# Frozen A10 public HTTP replay inputs — TEST ONLY

These eight DOCX files are byte-for-byte copies of the simulated correspondence in `<LOCAL_PATH_REDACTED>`. They are fictional test material, not historical project communications. `trace.json` is an unchanged copy of the saved actual A10 7B response at `a10-json-value-20261006/live-7b-20261006-080757/trace.json`.

`provenance.json` records the original locations, binary source SHA256/size, trace SHA256, and all nine original source-part/raw-response SHA256 hashes and item counts. The public HTTP replay validates those hashes before upload. Seven sources have one part; the eighth has two. All 179 original returned items are preserved without rewriting values, quotes, confidence or answers.

The fixture-local `.gitattributes` disables Git text normalization for the frozen trace and DOCX copies, so a checkout on another platform preserves their exact original bytes and SHA256 hashes.

Only the external `LlmClient` is doubled. A primary reply is selected from its actual effective user prompt filename and zero-based part index; the test also compares the complete real DOCX-parsed part with the original captured part. Every targeted repair explicitly returns `[]`, records that attempt, and supplies no additional answer. A subsequent malformed-primary fault injection is deliberately synthetic and checks failed-run atomicity against the same uploaded documents and saved completed run.

This is post-implementation acceptance coverage added to close the independent Spec review gap. Initial confidence representation and Mockito restubbing setup failures are retained outside the repository; they are not claimed as production RED observations. The replay verifies observable intake/provenance/persistence behavior, not model semantic correctness. Native false and explicitly justified empty-list acceptance remain covered by the other public-seam harness cases; this captured trace preserves legacy string values including `false`, blank unknowns and `[]` without conflating them.

These resources are under `src/test/resources` only. They must not become runtime defaults or introduce SIM filename/answer special cases into production.


Publication snapshot note: the eight sources are synthetic test documents. The captured runtime trace/provenance is intentionally excluded. Dependent harness tests are preserved under tests/external-fixtures/java and require external fixtures before running.
