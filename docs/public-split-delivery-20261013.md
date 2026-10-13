# OER / Vetting / Advisory split-repository delivery

This standalone service integrates the reviewed implementation from source snapshot `c971f873fef793c8ee2ec5a67294ece11ba01a4e`. The matching frontend is [ai_consense_web PR #6](https://github.com/s1765029698/ai_consense_web/pull/6). Each repository has its own project root; no private monorepo history is imported.

The service adds identity-bound fixed/project evidence, canonical source-page support, source routing with drawings excluded, Advisory sessions and per-answer citations, and Vetting progress, checklist/manual review guards, recovery/checkpoints and report snapshots. Existing public endpoint/environment placeholders and empty demo seeds are retained. SQL migrations V1-V18 are present; applying them to a real database is a separate deployment operation. Drafting remains subject to the existing freeze and review requirements.

## Frozen model and vector database artifacts

Actual model weights and the static fixed05 database are distributed through the [service fork release](https://github.com/Dying-Ember/ai_consense_service/releases/tag/oer-models-fixed05-20261013). The current account can read the colleague's upstream repositories but cannot push branches or create Releases there, so code is submitted through fork PRs and the public Release is hosted in the service fork. The backend [artifact manifest and recovery instructions](../artifacts/oer-freeze-20261012/README.md) pin the bytes. They must accompany the complete Release before it is marked ready.

The immutable subset contains 68 model files (BGE-M3 and BGE-reranker-v2-m3 publications plus historical PaddleOCR/MinerU snapshots), 4 static fixed05 database/provenance files, and separate license/model-card notices. BGE-M3 is declared MIT; the other five pinned model repositories are declared Apache-2.0. These are model licenses, not a license grant over GCC/GCT/Specification Library text or the entire service project.

The database has 19,371 standard parent records and 20,700 windows/points, sourced only from GCC/GCT/Specification Library. Metadata includes full source text and historical source paths; it is not a vector-only anonymized export. The restore tool verifies every byte and writes only into a new absolute destination. It does not load a model, rewrite frozen identity or start a database/service. Original PDFs, parsed source-descriptor closure, corpus/runtime plans and active H2/business data are outside this public package. Source-file retrieval requires supplying the authorized source closure separately and creating an explicit new runtime identity/path mapping. The existing author-specific fixed-baseline runtime guard is retained and is not advertised as a portable recovery command.

## Checks on the independent service checkout

- `mvn -DskipTests package`: passed, including compilation of the default test source set; tests are skipped by this invocation.
- Focused Java integration checks: 46 tests, zero failures/errors/skips, covering Advisory sessions, checklist workflow, fixed-source manual write refusal, fixed-baseline client and Python loopback wire. Four additional authored fixture-consumer suites passed 107 tests. These nine focused suites total 153 tests with zero failures/errors/skips.
- Python workspace/metadata checks: 13 passed, including the standalone Git-root resolver case.
- Actual source/template tests that need excluded external inputs remain under `tests/external-fixtures/java`; they are not fabricated or counted as passed.

All checks above use declared local/fake-provider boundaries. No complete new Java/Python suite, real model inference, browser business flow, H800 or production deployment acceptance is claimed. Frozen model/database bytes and recovery verification are reported separately in the artifact receipt. Optional renderer binaries/fonts and external original documents still need deployment provisioning.
