# Source-bound window retrieval

The window server preserves each original Java corpus parent, its `id`, content,
native table cells, extraction observations and source quality metadata. Window
points have separate IDs and original UTF-16 source spans. Returned hits contain
the complete original parent, never a child window payload.

The verified local recipe is CUDA, float32, embedding and rerank token cap 512,
64-token overlap, batch size 4, one thread of interop work, four CPU threads, TF32
disabled, and serial embedding/rerank GPU offload. Query tokens and special tokens
count toward the pair budget. An oversized query is rejected explicitly; silent
truncation and automatic precision/batch fallback are prohibited.

Dense window scores and rerank window scores currently aggregate by maximum per
parent. This favors long parents with more windows; it is a known limitation, not
a claim of semantic quality. The dense search expands its point prefix until it
has enough unique parents or reaches the available point count. Cardinality does
not prove approximate nearest-neighbor recall. BM25, RRF candidate order, actual
rerank input order, scores and final parent order are recorded separately.

## Explicit offline preparation and startup

Do not use an old parent-vector index. The source window signature is version 3
and binds complete parent payloads, source hashes, roles, model revisions and
bytes, tokenizer identities, preprocessing and window recipe. Projects use
separate collections. Adding/removing/changing sources invalidates the signature.

Prepare only (no model weight load, HTTP server, index, query or OCR):

```powershell
& .\Start-WindowRetrieval.ps1 -Mode prepare `
  -WorkspaceRoot <LOCAL_PATH_REDACTED>`
  -StateDirectory <LOCAL_PATH_REDACTED>`
  -EmbeddingSnapshot <LOCAL_PATH_REDACTED>`
  -RerankSnapshot <LOCAL_PATH_REDACTED>`
  -Receipt <LOCAL_PATH_REDACTED>
```

`-Mode serve` explicitly starts one loopback worker. Use a different, fresh
receipt filename for that action. The launcher sets variables in its Python
process only, explicitly resolves the work area and model snapshots, sets and
checks the frozen math behavior, and adds the same execution-math identity as the
actual benchmark. It does not restart an existing service or alter Java/UI
configuration. A caller must separately index the current project through the
new server before retrieval. An empty/new state is not populated by startup.

The repository now contains the separately frozen metadata-v2 core. Its shared
runtime version supplies both the persisted signature and `/health` value **3**.
Default health reads return configured retrieval settings without loading a
tokenizer or model. Detailed tokenizer identity is still checked when preparing
an actual index or explicit detailed recipe.

`workspace_root.py` accepts an existing `CONSENSE_WORKSPACE_ROOT` first. In the
repository layout it can resolve the work area from `tools/vetting_eval` and the
service's `pom.xml`. An isolated experiment tree or portable package requires the
explicit variable or launcher. Empty, missing or ambiguous roots fail before
opening state. The helper's bytes are bound by runtime algorithm identities and
the launcher receipt, so a changed helper invalidates old cache/index identities.

Earlier CJK-v2 actual benchmarks and portable ZIP v3 retain their original frozen
core, including the old health label and default-root limitation. Their receipts
remain historical evidence. The new metadata-v2 manifest is
`909e67644bbd116e6bd9c0fcfbf0a7f055e2ffab83d105f4b45b156a3a70df44`;
its 89 isolated guards and 171 repository integration guards passed. New fixed
cache/index builds must use this new identity rather than relabel old vectors.

## Fixed reference cache and changing project sources

NTT/SCT/SCC templates and GCT/GCC/SL may be explicitly packaged as fixed competition
sources. This storage classification does not change their business `role`:
for example, an NTT template does not automatically become a `standard` document.
All other sources must be parsed and indexed as the supplied project revision.

Cache reads require an explicit content-hash/source-role whitelist and a bound
actual fixed-vector bundle. Files are not classified as fixed by name, directory,
clause needle or parent length. Cache keys bind original source hash, role,
complete stable parent/Part/table/quality payload, window spans and model text,
model/tokenizer/preprocessing/math identities. Current project/document/parent IDs
are rebound; they are not reusable evidence identities from a different project.
A changed parse/payload/model/recipe produces a strict miss and fresh encoding.

To enable a verified fixed bundle, pass both `-FixedCacheBundle` and
`-FixedCacheBundleSha256` to the launcher. This validates every bound vector and
original actual float32 row before startup. A bundle's absolute references must
be explicitly rebound and re-verified when extracting a portable package.

## Reproducible actual benchmark and saved audits

`actual_window_retrieval.py prepare` binds the external corpus/quality/software
manifests, normalized full parents, unchanged generic query requests, complete
local model file identities and retrieval profiles. Preparing performs no neural
inference. `run` is a separate explicit action with a fresh reservation and state.
It records every actual forward start/completion, full features, final encoded
float32 matrices, input/window/parent ordinals, all scores, resource samples and
terminal failures. No review LLM, downloads, application database or live HTTP API
is part of that benchmark.

`fresh_client_readback.py` requires a completed, stopped producer. It makes a
byte-identical state copy, opens a new real local Qdrant client, scrolls every
window point and checks exact payloads and elementwise equality to original
encoded float32 vectors (including provenance of any reused fixed cache row).
The original state remains unchanged. `audit_window_persistence.py` independently
uses immutable SQLite and an allowlisted inert decoder; it does not load a model
or open a Qdrant client. For PDF sources, use the plan's exact normalized parent
payloads: Python normalizes top-level `P12` page values to integer `12`, while Java
keeps the original page label. That transformation is explicitly audited; Part
page labels and other original values must not be altered.

Mock tests and file-only context replays are separate from actual encoder/index/
retrieval calls. Successful transport, literal quote coverage and a legal empty
answer do not establish native precision, recall or contract correctness. Gold
observations belong only to evaluator inputs and must never enter query/model/
index recipes. Preserve old output directories and index identities for honest
comparison.
