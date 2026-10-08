# Local vetting validation and runtime

These tools provide an RTX 4060 8 GB baseline for parsing, OCR, retrieval and reranking,
plus an HTTP model service used by the Java vetting API. An integration runner checks
original-file uploads, persisted jobs, evidence and reports. Drafting keeps its existing
workflow. Successful execution is not a contractual accuracy score.

For the verified local parameters, controlled experiment order, per-stage metrics
and comparison-log contract, see the Chinese [local tuning plan](../../docs/vetting-local-tuning-plan.md).
Its proposed experiments are labelled planned; they do not replace the preserved
actual execution and independent source-semantic audits below.

## Components

| Component | Pilot implementation | Address or data |
| --- | --- | --- |
| OCR | RapidOCR 3.9.2, CPU ONNX Runtime | `POST htt<LOCAL_PATH_REDACTED>` |
| Embedding | BGE-M3, CUDA FP16, 1024 dimensions | Python model service |
| Hybrid retrieval | Qdrant Python local mode, BM25 and RRF | Workspace `tmp/vetting_server/` |
| Reranking | BGE reranker v2 M3, CUDA FP16 | `POST htt<LOCAL_PATH_REDACTED>` |
| Review model | Ollama `qwen2.5:3b` | `htt<LOCAL_PATH_REDACTED>` |
| Java API | Profiles `h2,vetting-local` | `htt<LOCAL_PATH_REDACTED>` |
| Web UI | Vue/Vite with API proxy | `htt<LOCAL_PATH_REDACTED>` |

The CLI stages load embedding and reranking in separate processes. The HTTP model
service loads lazily and keeps models resident; it serializes model calls and Qdrant
local writes. Run one worker. `consense.vector.provider=memory` in Java's local profile
is the older advice adapter setting; vetting uses the Python persistent Qdrant store.

## Independent embedding and reranker controls (source revision)

The source now accepts independent token and inference batch settings. Defaults
remain 512 tokens / batch four for both models. **The HTTP model service has
not been restarted or exercised with these changes.** Offline tests do not claim
GPU or quality results. A separate actual CLI reranker 512/1024 pilot and new v2
index are recorded below; they do not validate the HTTP path or full44 quality.

| Startup environment variable | Default | Controls |
| --- | ---: | --- |
| `CONSENSE_EMBED_MAX_TOKENS` | 512 | Document and query embedding token cap |
| `CONSENSE_EMBED_BATCH_SIZE` | 4 | Embedding inference batch |
| `CONSENSE_RERANK_MAX_TOKENS` | 512 | Combined query/candidate reranker token cap |
| `CONSENSE_RERANK_BATCH_SIZE` | 4 | Reranker inference batch |

Values must be positive integers; token caps must not exceed 8192, the pinned
models' configured input limit. Values are selected at process startup and require
a controlled restart to change. `CONSENSE_MODEL_DEVICE=auto|cpu|cuda` resolves once
per process. `/health.runtime.embedding` and `.reranker` disclose the resolved
device/dtype, independent maxTokens/batchSize, model name and revision. The old
top-level maxTokens/batchSize fields remain compatible and describe embedding only.
`/health.retrieval` describes request defaults (50 candidates / 10 hits), equal-weight
RRF constant 60, role filtering, storage and the actual installed BM25 defaults;
request-specific candidate/limit values can differ.

The v2 index signature binds embedding revision, effective device/dtype, token cap,
batch size, normalization and corpus. Reading legacy metadata or a different
embedding identity returns **409: re-index current documents**. Changing only
reranker token/batch parameters leaves the embedding index identity unchanged.
Use a fresh state directory for experiments and preserve historical state/proofs;
do not silently reuse an index across embedding configurations.

CLI `eval.py` now accepts `--embed-max-tokens` and `--rerank-max-tokens`. The existing
`--max-tokens` remains the fallback for each model that has no explicit override.
`--batch-size` / `--embed-batch-size` are aliases; `--rerank-batch` /
`--rerank-batch-size` are aliases. For example, the following is a **planned**
configuration, not a run completed here:

```powershell
# First establish the 512-token / embedding-batch-two baseline in a new --out.
# Only after that comparison, change the embedding cap to 1024; reranker stays 512.
$taskPython = '<LOCAL_PATH_REDACTED>
$taskEval = '<LOCAL_PATH_REDACTED>
& $taskPython $taskEval --offline --embed-max-tokens 1024 --embed-batch-size 2 --rerank-max-tokens 512 --rerank-batch-size 4 --out '<LOCAL_PATH_REDACTED>
```

CLI index metadata and retrieval reports include independent runtime identities.
The CLI checks the complete embedding signature before evaluation; an old or
changed embedding index raises a stale-index error. A reranker-only experiment can
reuse the unchanged embedding vectors while freezing the same raw candidate set.
The pilot corpus still differs from Java's production corpus, so this command alone
is not a production retrieval or contractual-quality acceptance test.

The standalone offline regression command is:

```powershell
& $taskPython -m unittest discover -s tools/vetting_eval -p test_model_parameters.py -v
```

Its model/Torch/database dependencies are replaced with test doubles for inference
paths; it starts no HTTP server and creates no real index or GPU tensor. Actual test
results and source hashes are recorded separately under the workspace `tmp/` tree.
The 2026-10-02 source revision passes 12 standalone tests, zero failures/errors/skips,
exit 0. The preserved log and manifest are
`tmp/local_model_parameter_tests/20261002T041643Z/unittest.log` and
`test_manifest.json` (SHA256
`823d1b0cbe77d458215039ae83e537e8d6d3c42d258b9cafd2a3ffb09c4fee6a`).
`artifact_binding.json` binds source copies matching that exact test revision;
later documentation of these results is distinct from the frozen tested files.
These twelve tool tests are separate from the Java full155/focused Writer checks
and do not constitute a combined full-suite run or a model-quality score.

## Automatic pilot CLI experiment logs (source revision)

`eval.py` now logs every `probe`, `ocr`, `parse`, `index` and `evaluate` execution
through `experiment_log.append_record`: a `started` event followed by `finished`
or `failed`, with a new experiment ID each time. `--log-root` selects the registry
(default workspace `tmp/vetting_experiment_logs`); `--baseline-id` binds an explicit
existing control. Parsed command options, original argv, pinned model config,
installed package versions and config/tool source hashes are recorded. Source
files are hashed for OCR/parse. Runtime source and RapidOCR config/registry YAML
are preserved where applicable; weights are not copied or loaded for logging.

Legacy `--out` may be reused for caches. Before execution, its known reports,
`chunks.jsonl`, index metadata and selected OCR caches are hashed and their original
bytes copied into a **fresh** `out/experiment_runs/<id>/before/` directory. The stage's
post-run reports and selected OCR artifacts go into `after/`; stdout/stderr, error,
resource samples/summary and started/finished record snapshots remain beside them.
Vector arrays are hashed, while Qdrant database and HF weight directories are never
recursively copied. Cached or unchanged post-run reports are observations, not proof
of new inference. Later runs cannot overwrite the earlier snapshot directories.

Non-probe stages use the observational `ResourceSampler`. `probe` records resources
as unknown rather than sampling a model it does not run. Sampling errors retain
unknown resources; whole-machine/GPU peaks include other activity and can miss
short spikes. A failed stage still appends a failed record with partial report
bytes, error/stdio and hashes. Malformed config/output failures are also preserved.
`semanticAcceptance` remains `not_evaluated` and `qualityAccepted` remains null;
existing retrieval-span evaluation metrics are preserved without inventing a
contractual accuracy score. These logs are explicitly **Python pilot** observations,
not the Java full44 corpus or review result.

An offline regression initially exposed a Path/string input interface error; the
failed 16-test / eight-subscenario-error proof remains at
`tmp/local_model_parameter_tests/20261002T042757765694Z/test_manifest.json`
(SHA256 `0400dea972547d57bafeeffd448e5560e5a5537340a3e5eab400627691a2faff`).
After normalizing `read()` inputs and freezing runtime sources, the latest revision
passes **16 tests, zero failures/errors/skips**, exit 0, and `py_compile` passes.
The new immutable proof is
`tmp/local_model_parameter_tests/20261002T042903376420Z/test_manifest.json`
(SHA256 `9a5d43bac6f492bc0ce8581affebe49c88b86b53ae709f9ac84fd0c90ff5830e`).
The four added tests exercise all five CLI lifecycle paths with stage/sampler doubles,
reuse of a cache directory while preserving raw CRLF report/chunk bytes, partial
failure output and malformed config/output. Registries are temporary test folders;
no real OCR, model, index, HTTP, resource sampler or service restart runs here. The
earlier twelve-test proof remains distinct and unchanged.

## Actual controlled reranker 512 / 1024 pilot — 2026-10-02

One fresh v2 embedding index and two serial evaluations completed in
`tmp/rerank_token_experiments/rerank512-1024-20261002T050558263488Z-f914dc11`.
The preregistration, frozen source, exact input bytes, automatic CLI events,
stdout/error/resource observations and per-run report copies are preserved there.
The fixed corpus is the existing **2,739 pilot chunks**; `samples.json` supplies
**eight queries and eleven contains-needle labels**. No parse/OCR, production
full44 indexing/review, LLM, upload or application database mutation ran.

Both arms use pinned BGE-M3 and reranker revisions, CUDA FP16, embedding512/batch4,
reranker batch4, 50 candidates, top10, RRF60 and unchanged BM25 defaults. Only the
reranker's effective `tokenizer.model_max_length` changes from **512 to 1024**.
The legacy v1 `.npy`/metadata was not copied or hand-migrated; the new 2,739-point
index was actually generated once. Native Ollama and retrieval services were
absent during both arms, verified by process and TCP LISTEN inventory. The PS
response is unknown, not an observed `[]`; these memory numbers cannot be directly
compared with historical resident-service experiments.

SchemaVersion2 retrieval reports now preserve all ordered candidate IDs,
query/content SHA-256, query-vector identity, complete ranked scores and actual
tokenizer input fingerprints. The CPU diagnostic mirrors pinned ST5.2 `predict`
batch tokenization with `padding=True`, `truncation=True`, `return_tensors='pt'`;
it reads the tokenizer's actual limit and has no additional model forward.
Its time is separate from score time. Two added offline regressions bring this
tool revision to **18 tests, zero failures/errors/skips**, exit0, plus `py_compile`.
The immutable proof is
`tmp/local_model_parameter_tests/20261002T045705258953Z/test_manifest.json`
(SHA256 `35ac025123fa5734e9db1e441d10baf6701f1657e12b8c3a2b1ad693c63562ae`).
Previous twelve/sixteen-test and failed proof versions remain distinct.

| Observation | 512 baseline | 1024 candidate |
| --- | ---: | ---: |
| Actually truncated pairs / 400 | 2 | 0 |
| Top10 labelled needle hits / 11 | 11 | 11 |
| All-label queries / 8 | 8 | 8 |
| Reranker score time, diagnostic excluded | 5.616462s | 5.898354s |
| CPU pair diagnostic time | 0.473356s | 0.198199s |
| Whole evaluation wall time | 19.998017s | 18.253100s |
| Whole-machine sampled GPU peak / 8188MiB | 2529MiB, 20 samples | 2579MiB, 18 samples |

The once-only index wall time is 62.437361s, 62 samples, peak 2563MiB. No OOM or
sampler error occurred in this bounded run. Fresh CLI processes load local snapshots;
filesystem cache state is unknown. Single observations do not establish speed,
statistical significance, p50/p95, isolated model memory or worst-case feasibility.

All query vectors, 400 ordered candidate/pair content identities, untruncated lengths,
embedding/index identity and fixed runtime settings match exactly. The limit therefore
triggered an actual input change: two BSI-query pairs retain 526/736 tokens instead
of 512. These are PRE.B8 bilingual Daily Record Summary/disposal table chunks,
ranking 39→37 and 20→19; neither enters top10 or matches that query's two definition
needles. Other seven full rankings stay unchanged. No labelled-needle recall gain
was observed, and no comprehensive qualifier/relevance/conflict gold was evaluated.
Keep the 512 baseline; 1024 is a bounded executable candidate, not a quality-approved
configuration. `qualityAccepted=null`, `semanticAcceptance=not_evaluated`.

`comparison_sidecar.json` binds the actual reports and immutable stage records
(SHA256 `ea720ec70da77c8b8959bc7e412f3657ea85fa5f5815860bccd217477c2b7785`).
The separate `source_activation_followup.json` retains the changed source IDs,
locations, token counts, rank and needle-match checks
(SHA256 `3a6621f3a976a806969256ccdc60823b796c7494a8d3eb4ecfc46f8dbb9828d8`).
Baseline ID is `pilot-evaluate-20261002T050711414202Z-6e06971b`; candidate ID is
`pilot-evaluate-20261002T050732385616Z-b9288bfb`, explicitly linked to that baseline.
Two earlier driver preflight failures (connection refusal and an active TCP probe
timeout) are separately registered with zero model/index stages; their original
errors were preserved before the final group was independently preregistered.

A fresh two-source-only audit reconstructs the actual retained token IDs with the
pinned tokenizer JSON and matches both 512/1024 SHA values, without importing Torch
or running a model. Original DOCX OOXML paragraphs/rows are read independently.
Zero-based candidate18 loses only blank form separators; candidate27 loses load
fraction/vehicle/CHIT-DDF form fields and the PRE.B8/III caption. These tails contain
no target BSI/Site Staff definition or its authority conditions. The original
comparison is unchanged; `source_context_truncation_audit.json` is a separate
supplement (SHA256 `ab214a0a9b04dc40922ba8077ca8f7668749305eb1c908e62dc192067a73a829`).
An `independent_audit` event links all three sidecars to the candidate's unchanged
execution record with quality unknown. This is not a 400-pair semantic gold set.

## Prepared embedding 512 / 1024 pilot — zero calls

The sole pending group is
`tmp/embedding_token_experiments/embedding512-1024-20261002T080828206680Z-c545b7f8`,
preregistration SHA256
`ced9ef45b28cfe57ea2cc721e61a351d183859935ec010b3d8c96a040a1fc8e8`.
The earlier `080535949274Z-6eb46562` preparation remains unchanged and is superseded:
the revision adds an execution-time check of the actual installed ST/Transformer/
CrossEncoder/BM25 sources, Python executable and package versions, in addition to
the frozen helper copies and local model files. Both new arms share the same
revised driver and observer. No production/eval code or historical proof is changed.

Only embedding `max_seq_length` is requested to change **512 → 1024**. Embedding
batch4, requested CUDA FP16, reranker512/batch4, candidates50, top10, RRF60 and
rank-bm25 0.2.2 defaults (`k1=1.5`, `b=.75`, `epsilon=.25`) remain fixed. Each arm
has a fresh independent output with identical 2,739 original chunks and no index,
vectors or stage cache. The eight original queries/eleven contains-needle labels
are frozen unchanged. Both indices must be generated independently under v2;
neither legacy nor historical v2 vectors may be copied or hand-signed. Pinned
BGE-M3/reranker revisions and every cached snapshot file are bound by hash without
copying the weights. HF_HOME is `tmp/vetting_models`, offline is explicit, and
the eventual child blocks socket connections.

The existing eighteen-test proof remains historical. Its ten retrieval functions
have identical AST hashes in the current accepted29 source; the newer OCR edits
and logger14 identity are separately bound. **Twelve new offline tests pass with
zero failures/errors/skips**, exit0, no heavy inference imports and zero actual
models/index/OCR/HTTP/nativePS/samplers. They use fake encoders/tokenizers plus a
real temporary registry to check actual arguments, return/exception transparency,
sorted versus original input identity, failed diagnostics, stale signatures,
runtime/source rejection, planned→started and the explicit execute flag. These
are software checks, not a second real embedding evaluation.

The new observer wraps the real ST5.2 first-module tokenizer invocation. It
records the explicit `max_length=self.max_seq_length` that actually applies to
embedding, separately from `tokenizer.model_max_length`; original returned model
input tensors and attention masks are hashed unchanged. Original source order
and the actual normalized/sorted tokenizer batches have separate identities.
An extra untruncated tokenizer call on those same received inputs measures actual
retention rather than estimating tokens from characters. Its CPU/hash time stays
inside encode/CLI time and is separately reported, not subtracted. A diagnostic
failure preserves the original model result and stops the driver without retry.

Future execution order is index512→evaluate512→index1024→evaluate1024, once each,
zero retries. Every stage requires a read-only actual native `models=[]` response
and no listener on8868/8870; it never unloads another model. **Preparation makes
no PS/HTTP call**, so actual state, tensors, truncations and resources remain
unknown. Embedding changes can legitimately alter query vectors, candidate sets
and ordered rerank pairs: do not demand the same400 pairs as the earlier reranker
experiment. Compare all per-query candidates/content/pair identities, scores,
top10 and the bounded eleven-needle metrics alongside index/query/rerank/diagnostic
time and sampled resources. Those labels do not establish semantic precision,
recall, qualifier completeness or contractual quality. The future driver has only
a planned registry event and no execution manifest; the separate file-preparation
and software-validation record is completed.

The twelve-test proof SHA256 is
`fce02bdb571e047eddf4bf6658045603fbf4e21b34d9aee997cbe97f133834c0`;
the final file-only audit is `final_static_preflight_revision1.json`, SHA256
`d0f6fda8f61e4c1f41e87db8dd1bc4f7f9467d5869a251b2eff897cc1f6dbd86`.
The independent source-only preflight is
`tmp/independent_embedding_preflight_audits/embedding512-1024-20261002T081559058046Z/independent_embedding_preflight.json`
(SHA256 `1f0a5bbfcdec71a07f5de34ad4e2bf510bb3384875e85fac0012ac00cc156821`).
An initial post-prepare CPU audit helper failed on a registry-descriptor interface,
before any registry cancellation/audit append. Its partial file proof, captured
console and zero-call software failure are preserved under `post_prepare_cpu_audit_attempt1`;
failure event SHA256 `420e402bf85af7319df978fc40c5600c9663c2a13a9b91ab7c32eeacff2af9aa`.
Only that file-reading helper was corrected. Revision1 is appended, and the older
preparation receives a separate cancelled/superseded event without modifying its
original bytes or performing any model retry.

The 4060 models are mechanism/regression baselines; they do not determine the H800
competition main model. The user excludes4B/7B as that main model. At least27/32B
dense or largerMoE candidates and a fair large-model plain-prompt versus minimal
runtime comparison remain competition plans, with no H800 measurement here.

## Actual controlled OCR DPI200 /250 /300 pilot — 2026-10-02

The separately preregistered group is
`tmp/ocr_dpi_experiments/dpi200-250-300-20261002T053456227500Z-b7c70e67`.
It completed three serial fresh CPU OCR arms at 05:38:38–05:39:40Z, five original
pages per arm (FT physical2/7, AA physical1/2, APL physical3), total15 page calls.
Three ONNX weights and RapidOCR3.9.2/config/source hashes are fixed; each fresh
output contains verified local weights and an initially empty OCR cache. The
old cache/report is unchanged. Each arm observed native `models=[]` before
starting; no native generation/unload, new index, application mutation or model
download was performed. Every actual session selected `CPUExecutionProvider`
with intra4/inter1 threads; child network attempts are zero.

| Observation | DPI200 | DPI250 | DPI300 |
|---|---:|---:|---:|
| Actual rendered W×H | 1654×2339 | 2067×2924 | 2481×3508 |
| Actual internal W×H | 1408×1984 | 1408×1984 | 1408×1984 |
| Actual detector tensor | [1,3,1984,1408] | [1,3,1984,1408] | [1,3,1984,1408] |
| Existing anchor smoke | 21/22 | 21/22 | 21/22 |
|31 selected literal units in14 visual-source regions | 30/31 | 30/31 | 30/31 |
| Five-page OCR duration sum | 13.303055s | 13.431753s | 14.824862s |
| Engine load | 2.277107s | 2.254597s | 2.147377s |
| Whole CLI wall | 18.130961s | 18.773093s | 21.727142s |
| Sampled whole-machine GPU peak/8188MiB | 1589MiB/18 samples | 1633MiB/19 samples | 1575MiB/22 samples |

All15 detector input shapes are identical because the unchanged global2000-pixel
maximum and32-multiple rounding resize every arm. Rendered pixels/interpolation
change; equal shapes do not mean equal pixel tensors. Classifier/recognizer
crop counts and widths can differ. A temporary read-only observer forwards the
original inputs/returns/exceptions and records preprocessing plus every session
shape/config/provider. Three fake-object transparency tests passed offline;
the observed metadata overhead (about0.0021–0.0022s per arm) remains included in
OCR timing, not subtracted. The production parser/engine algorithm is unchanged.

All five source images and all15 produced box overlays were visually inspected.
The selected tender-validity180-day condition, completion extension/payment
clauses, partner/address/witness footnotes and both AA2 OR levels remain present
in every arm. Word presence is not relation understanding. Blank subcontractor
lists contain only numbered slots, whose Roman numerals are misread/merged as
`目目`, `日目日`, `313`, `三三三`, or missed (DPI250 Lift(v)/(vi) have no boxes).
These are not filled names or counts. Every output contains footer `APL /2`;
the frozen `APL/2` needle fails on internal spacing. Keep the old21/22 score,
without post-hoc matcher changes. The14 labels are evaluation-only and never sent
to a model. No whole-page CER, deletion interpretation or automatic table/relation
accuracy is established.

Keep DPI200 for this pipeline;250/300 did not improve these selected literal
observations. `qualityAccepted=null`, `semanticAcceptance=not_evaluated`. Timings
are single observations with OS-cache/load/background effects. Whole-machine
GPU usage is not CPU OCR model memory and is not comparable directly to the
absent-service environment of the rerank experiment. Higher internal resolution
requires a new, separately controlled `max_side_len` experiment; it was not tested
here. This is not Java full44 parsing/review or H800 verification.

`comparison_sidecar.json` binds source/config/model bytes, all raw outputs and
resource/session observations (SHA256
`4b2cf5a0f5e20406eb8b366afe51674cdb00b031bae451aa7288e6050caca909`).
The three actual IDs are `pilot-ocr-20261002T053838557779Z-66251ec6`,
`pilot-ocr-20261002T053857774937Z-2ff81410`, and
`pilot-ocr-20261002T053917673682Z-6f7e0dfa`; both candidate arms link to the200
baseline. CLI started/finished and resize-diagnostic events are retained,
followed by independent source-audit events that preserve original observations.
All three samplers stopped without errors. Reusing this completed group is
rejected: prepare a fresh group/output for another experiment.

## OCR parameter controls and signed cache — source update, 2026-10-02

The CLI now exposes independent OCR controls. This source update has **not**
performed another OCR call, restarted a service, or tested `max_side_len=3000`.
The completed DPI experiment above keeps its original frozen source/cache/results.
The Java parser and HTTP OCR endpoint have no new parameter API in this change.

| CLI option | Default | Validation / meaning |
|---|---:|---|
| `--dpi` | 200 | Positive integer; PDF rendering scale remains DPI/72 |
| `--ocr-max-side-len` | 2000 | Integer ≥32; RapidOCR global preprocessing limit |
| `--ocr-text-score` | 0.5 | Finite [0,1]; final recognized-line score threshold |
| `--ocr-box-thresh` | 0.5 | Finite [0,1]; detector box score threshold |
| `--ocr-intra-threads` | 4 | Integer 1…actual CPU count; ONNX intra-op threads |
| `--ocr-inter-threads` | 1 | Integer 1…actual CPU count; ONNX inter-op threads |

Other library defaults remain unchanged, including detector `thresh=0.3`,
`limit_side_len=736`, classifier/recognizer batch=6 and CPU provider settings.
Thread counts outside the CPU count fail before engine initialization, because
ONNXRuntime otherwise silently ignores them. Embedding/rerank controls remain
independent, with their existing 512-token/batch4 defaults.

OCR JSON now requires `ocrCacheSchemaVersion=2`, `ocrCacheRecipe` and
`ocrCacheSignature`. The signature binds all actual RapidOCR Python/config
resource bytes, selected local ONNX/dictionary bytes, effective OCR parameters,
render DPI/scale, Python/OS/CPU identity, tool/runtime source bytes, selected VC
runtime DLL hashes and installed dependency version/METADATA/RECORD identities.
Wheel manifests describe package identity; this is not an exhaustive checksum
of every external OS DLL. Output/model-root location alone is excluded from the
recipe. Original PDF hash, document key and physical page are also checked.
If engine initialization replaces/repairs a weight, execution stops before
inference rather than signing output with the previous bytes.

Both `ocr` and `parse` enforce this identity. Same-recipe OCR cache hits reuse
text without initializing an OCR engine or rendering a page. Digital PDF pages
without an OCR cache do not require local OCR weights. An old unsigned cache,
changed config/model/threads/DPI, or mismatched source/page is rejected; regenerate
with `ocr --refresh` and parse with the same OCR options. **Do not add signatures
to old JSON manually.** `--offline` checks each default weight's official registry
SHA before initialization and passes explicit Det/Cls/Rec `model_path` values,
bypassing the library's automatic download/repair branch. Missing or corrupt
default weights are rejected before the factory. Explicit custom model paths
are content-bound without imposing the default registry SHA. Offline recognition
also requires a local `Rec.rec_keys_path`, unless its exact weight is the already
observed PP-OCRv6 small weight with embedded character metadata; other custom
embedded dictionaries are not inspected in a session by this preflight.
The legacy online first-load download route remains available
without `--offline`; downloaded weights are identified before inference. Logs
hash local weights without copying them into every experiment archive.

For a future separately preregistered DPI200/max-side2000→3000 comparison, seed
each fresh output's `ocr_models` with the same verified three ONNX files, hold
all other settings/source pages/labels fixed and run serially. This example is
**planned**, not a recorded execution:

```powershell
# $TaskOut must be a fresh, preregistered, locally seeded output directory.
& ..\tmp\vetting_eval_env\Scripts\python.exe tools\vetting_eval\eval.py --out $TaskOut --offline --dpi 200 --ocr-max-side-len 3000 --ocr-text-score 0.5 --ocr-box-thresh 0.5 --ocr-intra-threads 4 --ocr-inter-threads 1 ocr
& ..\tmp\vetting_eval_env\Scripts\python.exe tools\vetting_eval\eval.py --out $TaskOut --offline --dpi 200 --ocr-max-side-len 3000 --ocr-text-score 0.5 --ocr-box-thresh 0.5 --ocr-intra-threads 4 --ocr-inter-threads 1 parse
```

Invalid CLI invocation is recorded separately as `driver_preflight`, with
started/failed events, raw argv/error/exit code/tool hashes, zero stage/model
calls and resource data unknown. Rejected raw `--out`/config paths are never
opened. A rejected invocation honors `--log-root` only inside the workspace's
`tmp`; unsafe/file paths fall back to the trusted default registry. Normal
`--help` exit0 does not create a failure record. This is not an executed model
experiment or a semantic-quality measurement.

Latest source proof: `tmp/local_model_parameter_tests/20261002T062150260246Z/test_manifest.json`,
SHA256 `eb97cbf23ab2750b26fae320046a5f65f06718ca9abbe56e6ddc1839f1d2670a`:
29 offline regressions, zero failures/errors/skips, `py_compile` passed, no real
OCR/model/index/API/sampler runs or heavy inference imports. Tests cover isolated
parameter forwarding, recipe invalidation on config/weight/thread/package
changes, unchanged-recipe reuse, unsigned-cache refusal, parsing consumer
validation, initialization-time weight replacement, corrupt-weight refusal
before the factory, explicit local/custom routes and invalid-argv logging. These are software
regressions, not OCR quality measurements. The initial failed test-mock attempt
and prior 12/16/18/24/26 proofs remain separate. An independent review preserved
the old 26-source corrupt-weight/invalid-argument counterexamples in
`tmp/independent_ocr_cache_reviews/20261002T061435220956Z/review_counterexamples.json`
(SHA256 `192a3cbd5d38f89593ee189ddf1d5148fc1134898d946c8e83634cce4cc497ce`);
the new mock regressions close those software paths without real network/OCR.

A metadata/weight-only installed-route audit in
`tmp/local_model_parameter_tests/installed-ocr-recipe-20261002T062249279271Z/installed_recipe_audit.json`
(SHA256 `4018b06acf4d8494fbd9c61b26d40d56e1840766b377696a11e02267b5eba4b4`)
resolves the actual PP-OCRv6 multilingual Det/Rec small routes and PP-OCRv4
orientation route to the same three previously verified local weight hashes,
with all three equal to their registry SHA values.
It loaded no inference modules and created no session; provider/session claims
remain those of the completed DPI experiment, not this metadata audit.

The first max-side zero-call preparation was frozen separately in
`tmp/ocr_max_side_experiments/maxside2000-3000-dpi200-20261002T064137935506Z-e5e82c0d`.
Its two fresh outputs contain the same three verified ONNX files and use the
same accepted 29-test tool source/approved observer, three original PDFs/five
physical pages, 22 original needles and 14 unchanged independent visual labels.
The full recipes differ only at `/parameters/Global.max_side_len` (2000→3000),
with DPI200/score0.5/box0.5/CPU4+1 fixed. Preregistration SHA256 is
`01b6fec54091d9891fcc123359c852659d068f3a3b734dc03692f1b60d10bf1a`;
driver-preregistration SHA256 is
`9eb224ce0f17e70b12bbac4256499d69ceec534ac7d2b7f25ca9e0f81a9901a6`.
Six offline observer/recipe/source/command-gate tests plus AST checks passed
(proof SHA256 `15dfba621575d0879f623d756252fa529581028adcc129e14bf13932d9ce7af3`).
Preparation made zero OCR/native/API/index calls and imported no heavy inference
modules. Its subsequently authorized driver attempt failed at stage 0 before
source verification, native PS, child creation or sampling: the old logger
rejected `planned → started`. Driver exit 1 and the captured tool output are
preserved in `execution_manifest.json` (SHA256
`12d000e87fe05ee0faca0308f3ed1499d57ecd2c00d692b021e4989a8cdf51da`).
The same original parameters/input fingerprints have a **failed** registry event
(`b4b2718709b1354607de01cea5d818b6991373cb866438386997a1dba76e8d85`).
There were zero child/OCR/native/API/index attempts and no sampler; the old group
will not be rerun. The console proof is a tool-captured merged output, rather than
independently captured OS stdout/stderr byte streams.

A new group was preregistered in
`tmp/ocr_max_side_experiments/maxside2000-3000-dpi200-v2-20261002T070656398717Z-59c14349`.
Its preregistration SHA256 is
`242086feb062adbdccc3dab29a1e35080674c45aad0a154f43455e09c367deca`,
and driver-preregistration SHA256 is
`7718d6dc3633ef0807a7a32538c7e55c13d3cf1bd6c5072ee001c60ba7f2d445`.
It retains the accepted 29-test eval/OCR sources and the identical three PDFs,
five pages, six local weight copies, 22 needles and 14 evaluation-only visual
labels. Both arms use the fixed logger revision `d0fff2f4…` whose 14 real registry
tests pass, plus a new transparent observer that records an attempt before each
full OCR/preprocess call, a completion only on successful return, and the original
exception on failure. Preprocess success does not count as full OCR success.
Eleven preparation tests pass (proof SHA256
`a7607ceebb0528597afe75edcaaaba44a190b5d5ee8ee853bf8e9ac22778d9a9`),
including actual temporary-registry `planned → started` IO, original-event byte
preservation and fake preprocessing/engine exception forwarding; AST checks pass.
Its original planned registry event (`d1b8d53b…`) remains immutable. After both
LLM profile samplers stopped, the authorized driver ran once (session20457,
exit0) and finished at `2026-10-02T07:18:19.369524Z`. The two sequential arms each
attempted and completed exactly five full OCR calls and five preprocess calls;
both samplers stopped without errors. Both before-arm native PS responses were
`models=[]`, with zero generation/unload/index/application mutation calls.
Three actual ONNX sessions used `CPUExecutionProvider` with4/1 threads; the child
network blocker recorded zero attempts. Actual experiment IDs are
`pilot-ocr-20261002T071737004087Z-a2248354` and
`pilot-ocr-20261002T071757489856Z-3c500ced` (second bound to first baseline).

| Max side | Same raster W×H | Actual preprocess W×H | Actual det tensor NCHW | Original needle smoke | 14-region literal units | Five-page OCR s | CLI wall s |
| --- | --- | --- | --- | --- | --- | ---: | ---: |
| 2000 | 1654×2339 | 1408×1984 | [1,3,1984,1408] | 21/22 | 30/31 | 13.668669 | 18.993472 |
| 3000 | 1654×2339 | 1654×2339 | [1,3,2336,1664] | 21/22 | 29/31 | 15.820364 | 20.609542 |

The factor actually changed detector resolution. However, at3000 FT2's source
`days from and including the Tender Closing Date` becomes OCR
`days frdom and including the Tenderer`, followed by `Closing Date` on the next
line. Keep the altered raw text and the original failed literal unit rather than
silently repairing it. Other selected AA1 extension/payment, FT7 footnotes and
AA2 OR literal units remain, which does not establish their logical relationships.
APL Fire(ii)-(iv) become separate correct strings at3000 while Electrical/Lift
(ii)-(iii) merge into erroneous strings; at2000 Electrical(iii)-(iv) and
Fire(ii)-(iv) were merged. Blank slots are not actual names/counts. Both still
output `APL /2`, so the fixed `APL/2` needle remains failed at21/22.

All five original references and ten new box overlays were visually inspected
for the unchanged fourteen labels. The final comparison is
`comparison_sidecar_revision1.json`, SHA256
`573b7503a86313b89f56aa935cfd61df85ae4ed505600098c869f6c5e70fd98d`,
with all12 mechanical provenance/config/shape/provider/count/network/sampler
checks passing and raw reports bound. The first sidecar (`24b72d15…`) correctly
counts29/31 but its Markdown overstates condition preservation; that original
and its registry events remain, with the correction appended in revision1.

There are19/21 one-second resource samples, whole-GPU peaks1503/1506MiB and
whole-machine RAM used peaks19,103,236,096/19,308,421,120 bytes. Engine load is
3.201597/2.080555s; about0.002050/0.002155s observer metadata overhead remains
included, not subtracted. These are single observations with background-inclusive
resources, not dedicated OCR GPU allocation or stable performance estimates.
Only these two new arms form the max-side-only comparison: logger/observer changes
prevent cross-group single-factor attribution to the old DPI results. Keep2000
default; quality remains null and semantic acceptance unevaluated. No full-page
CER, table/alternative relationship accuracy, contract precision/recall, full44
application deployment or H800 result is established.

## Environment and caches

Commands below run from `<LOCAL_PATH_REDACTED>`. Use Python 3.12 and
a compatible NVIDIA driver. The tested host has RTX 4060 Laptop GPU 8188 MiB VRAM,
32 GB RAM and 24 logical CPU threads. Allow several tens of GB for the environment,
downloads and caches, and keep them outside Git.

```powershell
python -m venv ..\tmp\vetting_eval_env
$taskPython = '<LOCAL_PATH_REDACTED>
& $taskPython -m pip install torch==2.7.1 --index-url http<LOCAL_PATH_REDACTED>
& $taskPython -m pip install -r tools/vetting_eval/requirements.txt
$env:HF_HOME = '<LOCAL_PATH_REDACTED>
$env:HF_HUB_DISABLE_XET = '1'
$env:HF_HUB_DOWNLOAD_TIMEOUT = '120'
$env:PYTHONIOENCODING = 'utf-8'
```

Download the revisions pinned in `samples.json` before selecting offline mode. The
following reads that configuration; serial downloads avoid Windows cache races.

```powershell
@'
import json
from pathlib import Path
from huggingface_hub import snapshot_download
config = json.loads(Path('tools/vetting_eval/samples.json').read_text(encoding='utf-8'))
for key in ('embedding', 'reranker'):
    model = config[key]
    snapshot_download(model['name'], revision=model['revision'], max_workers=1)
'@ | & $taskPython -
```

Windows OCR needs the official Microsoft VC++ x64 runtime. The workspace pilot uses
Microsoft-signed DLLs bundled with the Codex Poppler runtime without changing system
files. An equivalent trusted directory may be set on another Windows host:

```powershell
$env:CONSENSE_VC_RUNTIME_DIR = '<LOCAL_PATH_REDACTED>
```

Alternatively use the [official runtime required by ONNX Runtime](http<LOCAL_PATH_REDACTED>
Linux does not use this Windows override.

## Independent benchmark

The source folder defaults to the organiser's reference directory under the workspace.
`samples.json` explicitly allows NTT, SCT, SCC, PRE, GCC, FT, AA and APL. Official vetting
comments, checklist and questions/answers are excluded. `vetting_cases.json` contains
evaluation labels and is never uploaded as a contract source.

```powershell
$taskEval = '<LOCAL_PATH_REDACTED>
& $taskPython $taskEval probe
& $taskPython $taskEval ocr
& $taskPython $taskEval parse
& $taskPython $taskEval --offline index
& $taskPython $taskEval --offline evaluate
```

Global options precede the stage name:

```powershell
& $taskPython $taskEval --source-root '<LOCAL_PATH_REDACTED>
```

`ocr` processes five selected scan pages. `parse` combines native text and those cached
pages, and records every pending OCR page. It does not represent the remaining scans
as parsed. DOCX extraction preserves deletion/strike metadata and excludes inactive
wording from effective source text. DOCX body/clause anchors are not rendered Word
pages. Positive-ID footnotes/endnotes are included; separator notes are excluded. PDF
positions use physical pages. RapidOCR does not infer table-cell semantics,
deletion lines or clause precedence; the 512-token model cap may truncate long chunks.

Outputs default to workspace `tmp/vetting_eval/`: blocks/chunks JSONL, parse/OCR/index/
retrieval JSON reports, `embeddings.npy`, local `qdrant/` and OCR images/coordinate
overlays. Source/model/index signatures prevent incompatible cache reuse. Run memory
benchmarks without another model process occupying the same GPU for comparable figures.

The verified pilot snapshot on 2026-10-02 contained 2739 chunks. Five OCR pages matched
21/22 selected literal anchors; `APL/2` appeared as `APL /2`. Eight selected queries
had 11 expected spans: dense top 10 found 11/11, hybrid top 10 found 10/11, and reranked
top 10 found 11/11. Separate query-embedding/reranking stages peaked at 1096/1137.2 MiB
of PyTorch allocated memory. Those figures are allocator measurements for these stages,
not total GPU/process peaks or the cost of OCR and review together. The small selected
benchmark establishes feasibility, not OCR character accuracy or detection precision/recall.

## Start the model service

In a dedicated terminal with the Python/cache variables above:

```powershell
$env:CONSENSE_MODEL_DEVICE = 'cuda'
$env:CONSENSE_MODEL_OFFLINE = '1'
$env:HF_HUB_OFFLINE = '1'
$env:CONSENSE_RETRIEVAL_DATA = '<LOCAL_PATH_REDACTED>
& $taskPython tools/vetting_eval/server.py --host 127.0.0.1 --port 8868
```

`GET /health` reports device, pinned/loaded models and limitations. `/index` replaces
one project's source snapshot. `/retrieve` applies project/role filtering then hybrid
retrieval and reranking. `/ocr` takes raster image multipart field `file`, returning
lines with pixel bounding boxes; Java renders scan pages and maps those boxes to evidence.
The service rejects evaluation-material paths and unsupported roles.
Chunk physical page metadata accepts a positive integer or Java's `P12` representation
and normalizes it to an integer. Clause/Word anchors cannot be used as physical pages.

Hugging Face offline flags prevent embedding/reranker downloads and fail on missing
pinned snapshots. RapidOCR has a separate ONNX cache/download path: prewarm both CLI
and service OCR paths before disconnecting. Prove full offline operation with a real
OCR/index/retrieve/review/export check while network access is disabled. HF flags alone
do not establish that condition.

## Start Ollama Java and the UI

Use another terminal for installed or workspace portable Ollama. The pilot uses Ollama
0.35.0 with `qwen2.5:3b`. Pull and test this exact tag before disconnecting. The tested
`qwen3:4b` runner did not reliably return the required short JSON because of its thinking
behavior, so it is not the local default.

```powershell
$env:OLLAMA_HOST = '127.0.0.1:11434'
$env:OLLAMA_MODELS = '<LOCAL_PATH_REDACTED>
$env:OLLAMA_NO_CLOUD = '1'
$env:OLLAMA_NUM_PARALLEL = '1'
$env:OLLAMA_KEEP_ALIVE = '0'
& '<LOCAL_PATH_REDACTED>
```

In another terminal run `ollama pull qwen2.5:3b` with the same host/model path.
`KEEP_ALIVE=0` releases the review model between calls. Java's local profile sets context
8192, temperature 0.2 and timeout 180000 ms with no model retry. These are pilot settings.

Build from the service repo with Maven/JDK on the process PATH. Java 8 source compatibility
remains configured; the workspace uses portable JDK 17/Maven under `tmp/java_tools/`.
Package tests run before the executable jar is produced:

```powershell
mvn --no-transfer-progress package
$taskRuntime = '<LOCAL_PATH_REDACTED>
New-Item -ItemType Directory -Force -Path $taskRuntime | Out-Null
$taskJar = Join-Path $taskRuntime ('consense-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.jar')
Copy-Item -LiteralPath 'target\consense-service-1.0.0.jar' -Destination $taskJar
java -Dfile.encoding=UTF-8 -jar $taskJar --spring.profiles.active=h2,vetting-local
```

Run Java from the service directory. Local H2 writes `data/vetting-local*.db` and uploads
use `data/vetting-local-uploads/`; both are ignored by Git. This development profile uses
Hibernate schema updates; production MySQL uses Flyway. A unique runtime jar lets later
Maven recompilation leave the running app untouched. Do not run a live app against
`target/classes` while another process rebuilds it.

From the web repository, use a separate terminal:

```powershell
pnpm install --frozen-lockfile
$env:CONSENSE_API_TARGET = 'htt<LOCAL_PATH_REDACTED>
pnpm dev
```

Create/select a project, choose upload purpose, upload originals and start vetting.
Auto inference handles known standards/email extensions; explicitly choose `tender`,
`standard`, `project_fact` or `package_manifest` for an ambiguous batch. Upload different
purposes separately. Read coverage warnings and inspect every evidence side before
setting `Handled`/`Assigned`. Export Word/PDF/JSON only after the latest task completes,
including completed tasks with zero findings. See [the API/report contract](../../docs/vetting.md).

## Human review records in the current source

The finding detail view now supports project-team remarks, action taken and an
explicit addendum decision, with English, Simplified Chinese and Traditional
Chinese labels. The existing status control remains independent: `Handled` is
not an instruction to include a change in an addendum.

The API endpoint is
`PUT /api/vetting/{projectId}/findings/{code}/review`. Supply a non-null JSON
object with `reviewRemarks`, `actionTaken` and `addendumRequired`; all three
values are replaced together. `addendumRequired` is nullable: `null` means
undecided, `true` required and `false` not required. Omitted/null fields clear the
saved value, so `{}` clears the three values. `reviewUpdatedAt` is written by the
server and returned with the finding; clients cannot set it. Each text field is
limited to 4000 Java/JavaScript string units. Over-limit input is rejected
atomically rather than trimmed or truncated. Stored and JSON-exported text keeps
its spaces, line breaks and Unicode exactly as entered.

V5 adds four nullable database columns for these fields. Legacy records default
to null; MySQL/H2 schemas and the existing status endpoint remain compatible.
Project scoping, locking and transactions protect the save. A rerun retains the
records and save timestamp when the same finding identity recurs. Human text is
not added to the retrieval corpus, prompts or generated conclusion.

Unsaved browser drafts survive finding switches, status changes and save failures
within the current project view. They are session-local, are cleared on a project
change, and do not count as saved API data. Late responses from another project
cannot replace the current view. Save or discard every pending draft before a
new run or export; both actions are disabled while edits or save requests remain.
Word/PDF show the saved project-team response, action, save time and translated
`Required`/`Not required`/`Undecided` decision. JSON includes the four raw fields.
Empty text is shown as `Not recorded` in the readable report. User text and original
source quotations are not translated by the report writer.

## Original-file integration and report checks

The runner creates a unique project, uploads original allowlisted files, polls the
persisted task, independently checks located quotes, downloads all report formats and
exercises a human status update. Optional rerun verifies stable identity/manual status.

```powershell
& $taskPython tools/vetting_eval/integration.py --base-url htt<LOCAL_PATH_REDACTED>
& $taskPython tools/vetting_eval/integration.py --base-url htt<LOCAL_PATH_REDACTED>
& $taskPython tools/vetting_eval/integration.py --base-url htt<LOCAL_PATH_REDACTED>
```

`--documents-manifest <json>` broadens the explicit source list; `--source-root` locates
originals. `case_sources.json` explicitly adds SP and the competition index to the eight
sources. Every file in an explicit manifest must preserve its declared `sourceRole` in
the backend response. Groups are uploaded through vetting's own `sourceRole` parameter.

`--reuse-project --project-id <id>` uploads/reviews an explicitly named existing test
project. Choose a separate `--out` directory to preserve an earlier run's artifacts.
`--resume-completed --project-id <id>` performs only verification, status and export
steps against the recorded completed run; the original source hashes must match and
that run must remain the latest run. Reports are exported after human status updates;
JSON must preserve the actual current finding codes/statuses. See `--help` for options.

Large original packages can separate upload/parsing from review:

```powershell
& $taskPython tools/vetting_eval/integration.py --upload-only --documents-manifest tools/vetting_eval/full_case_sources.json --project-id <new-project-id> --out ../tmp/vetting_full_case
& $taskPython tools/vetting_eval/integration.py --review-existing --project-id <same-project-id> --out ../tmp/vetting_full_case
```

`--upload-only` stops after saving the exact original SHA256/byte manifest, backend file
responses and `source_audit.json`. Its summary status is `uploaded`, with
`reviewStarted: false`; it creates no review job, evaluation matrix or reports. The
per-file audit includes declared/actual roles, parse failures and warnings, original/API
physical PDF pages, parsed/OCR pages and confirmed blank pages. Usable partial text can
be recorded as ready for review while its missing pages remain visible; this does not
establish complete OCR coverage or text accuracy.

`--review-existing` requires an explicit project and output directory from that upload.
It verifies the recorded allowlist against the current original SHA256/byte contents,
then checks each backend role and physical/parsed page counters before starting a new
review. It performs no upload and preserves the same independent quotation, human
status and export checks as the combined runner. Supply the same `--source-root` when
originals are stored outside the default reference directory. `--resume-completed`
remains a verification/export operation for its recorded latest completed job and
rejects `--rerun`; it cannot be used to start a new review.
For a substantive review project, pass `--skip-status-check` so the runner keeps the
actual human finding statuses when it exports reports. The default status mutation is
only an integration exercise. Skipping it is recorded explicitly in the summary;
successful checks and generated reports do not imply professional approval. It cannot
be combined with the synthetic human-status `--rerun` check.

`full_case_sources.json` records 44 original sources: 35 tender documents, five standards,
three project facts and one package inventory. The 14 PDFs contain 4632 physical pages;
seven image-only scan candidates contain 132 pages. Its other 30 sources are DOCX with
unknown physical pagination. Source-family keys may repeat; identity uses unique full
filenames/paths and roles. Evaluation comments and Q&A are excluded. This manifest's
inventory is not a claim that all 44 sources have been parsed or reviewed successfully.

`upload_timings.json` and summary `uploadParseTimings` record each role's synchronous
upload/parse duration, separately from the persisted job's `indexReviewSeconds`.
Add `--upload-per-file` to send one original at a time within each declared role and
record actual per-file durations; `upload_role_timings.json` then retains each role's
total duration as well. Source identity/roles and the final audit remain the same.
`--sample-resources --resource-note "<warm/cold state and conditions>" optionally records
one-second `nvidia-smi`/existing `psutil` observations in `resource_samples.jsonl` and
`resource_summary.json`. Label the actual model warm state and whether sources are being
parsed for the first time. These are sampled whole-GPU/system usage and process RSS peaks,
including other concurrent work; shorter spikes can be missed. The sampler makes no
model requests and does not install dependencies.

When an uploaded project is reviewed or a completed report is resumed, sampling writes
to a new `resources/<mode>-<timestamp>-<id>/` directory. `summary.json` records its
`resourceOutput` and previous `resourceHistory`, preserving the earlier upload/parse
resource files instead of overwriting them with review/verification measurements.

Outputs are in
`tmp/vetting_end_to_end/<projectId>/`: source/parse results, jobs, findings, independent
quote checks, reports, evaluation matrix and `summary.json`. A passed runner establishes
its API/evidence/export checks. Semantic case accuracy is not automatically scored;
the matrix is a manual review queue, and absence of a finding does not pass a negative.

Independent quotation records now include `verificationScope`, the actual
`textExtractionMethod`, `sourceEffectiveness` and `effectivenessVerified: false`.
`source_verified` establishes a literal quotation and source location. PDF native text
and OCR do not resolve visual strike-through, deletion marks or annotations, so those
clauses retain unknown effective status even when the quote is located. DOCX extraction
excludes explicit run strike/dstrike and tracked deletions; inherited style and contractual
effect still require review. The summary records PDF quotes needing visual effectiveness
review and independently rerun OCR quote counts.

DOCX structure and PDF text checks do not establish layout. Render every final Word file
and inspect every final PDF page. Workspace `tmp/libreoffice_tools/portable/` contains
official LibreOffice 26.2.5.2 extracted with MSI administrative mode, verified by official
SHA256 and Authenticode, without global installation. A renderer child process can
temporarily prepend its `program/` and bundled Poppler to PATH. Synthetic report fixtures
are layout stress tests; they are not competition detection results.

## H800 preparation

Confirmed resources: 27 vCPUs, 250 GB RAM, one H800 80 GB GPU, 650 GB high-performance
and 1250 GB general-purpose storage. Competition OS/driver and permission to preload
weights or indexes still need confirmation. No H800 throughput/accuracy is claimed.

Preserve source hashing, roles, evidence schema, exact quotes and human review/export
when upgrading models. Use fast storage for active weights/indexes and current OCR/raster
caches; use the larger volume for originals, archived reports and evaluation outputs.
Bundle pinned revisions, dependencies, source hashes and index signatures for offline use.

Reproduce this baseline first. Then evaluate layout OCR and a stronger local review model
against held-out contractual, negative and conditional cases. A replacement needs an
adapter producing existing OCR/evidence contracts; a model-name edit alone is insufficient.
Measure cold/warm parsing, every scan page, corpus/index size, retrieval recall, review
precision/recall, peak GPU memory and end-to-end duration on the competition corpus.

Current Qdrant local mode supports one process and proves persistence, not server scaling.
Qdrant server with named dense/sparse vectors requires a separate adapter/deployment;
changing the older Java advice URL does not switch vetting. Tune concurrency after adapter
and memory checks. Embedding model/source/token-length changes require reindexing;
batch-size changes alone do not. Reuse standard-file OCR/indexes only if competition rules
permit, and perform a disconnected acceptance run before the event.

## Topic and model diagnosis

`diagnose.py` reads the chosen project's persisted production index and Java topic/prompt
source. Its Python excerpt assembly retains the older round-robin/whole-chunk budget
strategy; it does not reproduce the current Java comparison-window selector or native
schema decoding. Saved excerpts and model responses are diagnostic probes, not captures
of current production submissions. Prompt source hashes bind a probe to the source it
read; they do not prove that a previously packaged JAR uses that code. Select a few topics
rather than repeating the complete review:

```powershell
& $taskPython tools/vetting_eval/diagnose.py --project-id <test-project-id> --topic 1 --topic 2 --topic 3 --topic 13
```

`--retrieve-only` skips the model call. `--query` compares a generic natural-language
question for exactly one selected topic. Use a separate `--out` directory to preserve the
original production-query capture. These commands do not read evaluation gold or write
findings to Java. Inspect saved inputs to distinguish a missing source side, a context
budget omission, malformed output, an invalid quotation and an actual empty result.
`--topic-snapshot <json>` is restricted to retrieval-only diagnosis. It accepts reflected
`TOPICS`/metadata/version from a hash-verified immutable JAR when source code is changing;
its saved legacy excerpt-budget reconstruction is not an actual model submission. Every
retrieved payload must equal the persisted chunk, with the requested role and matching
project/index signature.

`replay.py` sends captured system/user prompts unchanged to another cached local model.
It performs no new retrieval, which isolates the model comparison from query or context
changes. Ollama API/CLI `ps` and GPU memory are observed while inference is active:

```powershell
& $taskPython tools/vetting_eval/replay.py --inputs <captured-topic-directory> --model <cached-model-tag> --topic 1 --topic 3 --topic 13 --out <comparison-directory>
```

Each response is checked for supplied chunk IDs, verbatim source quotations and two
distinct provisions for conflict claims. This establishes evidence integrity; judgment
still needs independent assessment. Replays preserve input file hashes and do not change
the application's default model. A correctly quoted result, a successful API run, or an
empty array from every topic is not an accuracy result.

An explicit `--system-prompt-file` labels a generic prompt experiment while preserving
the captured user input. `--temperature` and `--num-predict` allow a labelled decoding
experiment; do not attribute a combined prompt/decoding change solely to the prompt.
`review_prompt_probe.txt` contains generic constraint/scope and quotation rules. It has
no fixture clause numbers, project names or evaluation-answer examples and is not used
by the production Java service.

The initial 2026-10-02 ten-source run `1c72f814-0753-4ace-9942-a15028cec6dc` in project
`vetting-e2e-20261001T204016-f00d84b3` completed actual
OCR of every FT/AA/APL page and passed source-role, five-quotation, status and report
checks. GCC retained a visible partial parse for page 2 with no OCR text. It produced two
rule findings for a retained deleted-clause reference and inconsistent stated AQCC
numbers, with no model findings. This established the integration, not completion of
the fifteen-case semantic acceptance set. All sixteen production-topic diagnostic
replays from the 3B model returned `[]`; payment and inspector-definition source sides
were present in submitted contexts, showing a model-judgment limitation as well as
retrieval/context limitations in other topics.

For a model control, the official
[qwen2.5:7b-instruct-q4_K_M tag](http<LOCAL_PATH_REDACTED>
was cached separately. Three unchanged production inputs took 23.98, 21.66 and 17.50
seconds. Ollama reported 100% GPU placement at context 8192 with BGE/reranking still
resident; observed total GPU memory reached 7919/8188 MiB in the payment probe. Responses
were nonempty, but included one-sided/paraphrased evidence, unsupported ambiguity and a
minimum-bound comparison reported as a conflict. Evidence-valid records were not counted
as correct findings. A generic stricter prompt at temperature 0/output budget 1500 returned
`[]` for all three inputs, reducing noise without establishing improved positive recall.
The default model was not changed. Captures and placement records are under workspace
`tmp/vetting_topic_diagnostics/`, `tmp/vetting_topic_natural/` and
`tmp/vetting_model_replay/`.

## Full original-package parse/index baseline

Fourth immutable JAR SHA256
`7b3b9260f1ef46e54198238554e2c782c98968a497fb0397f459141d04a62956` was used for
new project `vetting-e2e-20261001T214513-b066e710`. All 44 original sources matched
the manifest SHA256/bytes, persisted their declared roles and were `PARSED`. All 4632
physical PDF pages were covered, with zero unparsed pages and one confirmed blank page.
Actual OCR processed 147 pages: the seven pure scan candidates' 132 pages, GCC cover
one page, 12 SL pages routed by PDFBox and two APU pages. The other 30 DOCX files retain
unknown physical pagination. Full-page processing does not measure OCR text/diagram
accuracy.

Models were already warm in the offline server; sources were parsed for the first time
in the new project. Upload/parse took 423.703 seconds, including 47.078 seconds for the
4300-page SL, 195.25 seconds for 61-page APB and 100.437 seconds for 41-page APC. The
one-second sampler observed whole-GPU memory up to 3164/8188 MiB, Java RSS up to
4,621,168,640 bytes and model-server Python RSS up to 2,615,844,864 bytes. These sampled
peaks include other system work and can miss shorter spikes.

SELECT-only H2 extraction then used the exact packaged `sourceSet`/`reviewable`/
`VettingCorpus.chunks` implementation, without reparsing or OCR. Stored source bytes,
API roles/status/pages and every chunk Part's original UTF-16 block range were checked.
The resulting 13,270-chunk request was 78,296,178 bytes, below the 50,000-chunk limit.
Pinned CPU tokenizer counts found 138 chunks above the 512-token embedding limit
(1.040% overall; 4.456% of tender chunks), with a maximum of 861 tokens. Larger model
input limits require a separate memory/retrieval check and a new index signature.

The independent fresh-index baseline preserved 512 tokens/batch four. Service indexing
took 220.325 seconds (client 222.719 seconds), returned all 13,270 chunks with
`cached: false` and no OOM/errors. Sampled GPU memory reached 3249/8188 MiB. An identical
request then returned `cached: true` in 2.953 seconds with the same count/signature.
The measured cold-index time exceeded the older 180-second Java request timeout;
current source gives index requests their own `retrieval-index-timeout-ms: 1800000`
(30 minutes), while retrieval queries retain 180 seconds. This new setting requires the
updated JAR; the fourth-JAR baseline used independent long-timeout prewarming.

Four frozen production topics (3, 13, 14, 16) then made 16 retrieval-only role requests
in 11.734 client seconds. All 132 hit observations matched the stored payloads, roles,
source IDs/hashes/pages and index signature. Five observed hits exceeded the dense input
cap; six query/chunk pairs exceeded the rerank cap. Exact omitted tail quotes are saved,
and full evidence payloads remain intact. This retrieval-only stage verifies
provenance/truncation exposure, not relevance, complete case recall or semantic judgment.

Evidence is under workspace `tmp/vetting_full_case/<project>/`: `summary.json`,
`upload_manifest.json`, `source_audit.json`, per-file/role timings and raw resource samples,
`corpus_snapshot/`, `index-baseline-fourth/` and `retrieval-baseline-fourth/`. The uploaded
original uploaded summary is frozen under `upload-only-frozen/`, with
`status: uploaded` and `reviewStarted: false`.

The subsequent actual review used final immutable JAR SHA256
`3b1a3a18f0bcc6decc442c3ca978d6f7c4fe5ce1186c8b7e479b8607fe1ebf7b` and run
`a89dd1df-2cc4-4a7d-a3e4-76ad0ecd981a`, without uploading, production parsing or
status mutation. The job completed in 368.516 seconds with 11 rule findings, all `Open`.
The strict integration remains **failed**: six APB page-21 AL quotations failed the
unchanged short-quote/literal-source checks. An independent audit verified 18 of 24
quoted locations and exported the actual 11-finding API JSON/DOCX/PDF for review;
these artifact structure checks do not pass the failed source gates. Three AL findings
mistake fragmented OCR drawing titles for acronym expansions and require correction.

There were zero valid LLM findings. Persisted warnings show six schema-rejected topics,
five topics whose records failed evidence checks, one read timeout and four successful
empty responses inferred from control flow; raw model responses were not captured.
Successful schema calls accounted for only 80 of 13,270 chunk IDs (79 tender and one
standard), including empty responses and rejected records. This submission accounting
does not establish semantic accuracy or complete scope. Facts and package inventory had
zero successfully submitted chunk IDs. See `semantic_topic_audit.json` for all 16 topics.

`review_resource_phases.json` separates the 366 review-only one-second observations
from independent quotation verification/export work. Review GPU memory reached a sampled
5659/8188 MiB; Java RSS 4,252,508,160 bytes, model-server Python RSS 2,865,643,520 bytes
and whole-machine RAM used 21,326,290,944 bytes. Source validation and later independent
OCR are separate phases; the supplemental audit/export was not resource sampled. The
actual production index HTTP cache flag was discarded by Java and was not observed.
Persisted metadata retained the fourth baseline signature and all 13,270 chunks; this is
reported in `index_observation.json`, without inferring `cached: true` from speed.

The failed baseline's complete source audit, 15-case manual evaluation matrix and actual
reports are in `failed-run-a89dd1df-audit/`. One APB page-21 independent 200-DPI OCR
request was made after review sampling ended; its response, same-process cache evidence,
page/anchor/bbox and unknown visual-deletion effectiveness are in
`independent_page_audit.json`. OCR parsing of FT and other scans does not establish the
effective status of visually struck text; FT was not among this run's quoted evidence.

## Full original-package owner/ledger revision

The same 44-source project was reviewed again without upload, production parsing, OCR or
human-status mutation, using immutable JAR SHA256
`235382395d7514819d2069cb0ab2f8e9585b54d723a6bfe9468925f8f6af758e` (104 tests passed)
and run `21ecc408-ceb7-4f53-bec8-b517745bda5d`. Its independent output root is
`tmp/vetting_full_case_revision_20261001T233330-f43358e7/`; the earlier failed baseline
and 60 recorded evidence files retained their exact SHA256 values. Only the frozen upload
identity artifacts seeded the new output. `baseline_provenance.json` binds the actual
runtime/process, original manifest, old run and frozen model log.

The technical integration passed in 198.632 seconds. Three rule findings remain `Open`:
the deleted-payment-provision reference, a personnel-number coordination risk, and an
inclusive range that contains clauses marked Not used. Eight original Word quotations
passed independent extraction with explicit OOXML strike/deletions excluded. Actual API
JSON/DOCX/PDF exports passed their structure, finding identity and quotation checks.
PDF visual deletion, inherited Word formatting and operative contract effect are still
unknown; no PDF or FT quotation was used in this report. Professional approval is not
implied. Full-page layout/source review is a separate check.

Compared by stable finding fingerprints, the old 11 became three retained findings with
eight removed and zero added. Three fragmented OCR drawing-title AL expansions, one MiMEP spelling
variant and four different-scope abbreviation candidates were removed. This observed
change does not measure recall or semantic accuracy; see `baseline_comparison.json`.

The actual production INFO log captured all 16 system/user requests and raw returns.
Every logged submitted excerpt exactly matches the frozen original corpus, and request
IDs/UTF-16 character counts, response SHA256, assessment counts and evidence-gate totals
agree with the persisted per-topic ledger. All 16 returns conform to their native schema.
Fourteen topics returned 18 `insufficient_context` assessments; topics 12 and 16 returned
actual `[]`. There were zero `issue` or `consistent` assessments and zero accepted LLM
findings. Eight of the nonissue records failed literal evidence gates: 32 of 40 quoted
sides were located and eight were not. No schema failure or model timeout occurred.
Well-formed nonissues and empty arrays do not establish positive recall or absence of
contractual defects.

Actual submitted union is 142 of 13,270 chunks: 136/2648 tender and 6/10,607 standard;
project facts and package inventory remain zero. Reviewed character accounting is
128,858/2,315,672 tender and 5943/11,420,308 standard. Across topics the ledger records
153 budget-dropped group occurrences and 45 partial-context occurrences. These repeated
counts are not distinct missing provisions, and submitted local windows do not establish
complete operative scope. `actual_model_audit/` preserves actual prompt/return files and
all assessment/quote checks; `semantic_topic_audit.json` reports the ledger and limits.

The review-only resource phase contains 198 one-second samples, with sampled whole-GPU
peak 5668/8188 MiB, Java RSS 3,561,017,344 bytes, model-server Python RSS 3,005,046,784
bytes and whole-machine RAM used 20,914,487,296 bytes. Source identity checking and
independent verification/exports are separate phases. Models were resident; exact Ollama
CPU/GPU placement was not captured for this run. No rendering, extra GPU probes or
builds ran during sampling. These are sampled peaks rather than absolute maxima.

Stored index metadata retained the fourth baseline's signature, creation time and all
13,270 chunks. The actual Java index HTTP cache flag remains unobserved because the
client discards its body; the earlier independent cached POST is separate evidence.
`index_observation.json` records this without inferring cache success from review speed.
The 15-case manual evaluation matrix remains unscored, and this revision establishes the
original/quotation/status/export and model-response audit chain, not full semantic
acceptance.

## Independent source-linked pack probes

A separate source-linked pack builder was frozen with 117 passing CPU tests, including
edition priority, source-known parent/child targets, actual submitted unions and per-origin
unresolved references. It is a prototype and was not deployed in place of the 104-test
runtime. Five actual `qwen2.5:3b` calls used the default first pack for topics 1, 3, 13, 14
and 16, with unchanged 104 system/native-schema rules, temperature 0.2, context 8192 and
output limit 2048. Every original chunk/Part/hash and the model excerpt text was checked.
The first offline preparation detected a helper encoding error in the file-label
separator; that failed preparation was retained, the helper was regenerated with explicit
UTF-8, and no model call was made against failed inputs. No second pack was substituted.

All five actual responses conform to their schemas and stopped normally. They contain
eight `insufficient_context` records, zero issues and zero gated issue candidates. Six
records fail exact quoted-source checks: only seven of fifteen quoted sides are located.
The two located nonissue records still need substantive source review. Some nonissue
comments say responsibilities are absent even where the source lists duties; some records
misdescribe active amendment headings as Not used. Schema success and fewer issued
findings do not establish understanding or improved recall.

Actual prompt/generated token counts are 4486/742, 2534/465, 4301/433, 4220/455 and
6293/563 respectively. Their reported sums are below 8192, but native counters alone do
not prove absence of input truncation. Client-observed duration totals 45.784 seconds,
including observation/polling overhead; native service durations total 42.218 seconds.
Ollama reports model `size` and `size_vram` both 2,403,178,905 bytes with context 8192,
indicating full model placement in GPU while host runtime overhead remains separate.

The independent 46 one-second samples observed whole-GPU memory up to 5643/8188 MiB,
Java RSS 2,706,026,496 bytes, model-server Python RSS 2,416,189,440 bytes and whole-machine
RAM used 20,115,763,200 bytes, with no sampling errors. Builds, rendering and other model
work were idle. These are sampled peaks, not absolute maxima. The complete inputs,
responses, raw hashes, per-field gate reasons, unresolved scope, native token/latency
counters, Ollama placement observations and resource rows are under
`tmp/review_pack_probes/source-linked-first-five-20261002T004800-d3e60fbb/`, including
`probe_evidence_audit.json`. This is a local comprehension experiment, not another full
44-source application review, and it made no index/OCR/upload/status changes.

## Independent single-chunk proposition extraction

A different diagnostic prompt requested explicit propositions from five fixed complete
source chunks, using `qwen2.5:3b`, temperature 0, context 8192, output limit 2048 and an
array capped at eight records. Inputs contain one original chunk with its complete
Part/hash metadata. This experiment is separate from the pack selector and 104 system
prompt, and is not a direct selector comparison. Its six required fields are `kind`,
`subjectQuote`, `predicateQuote`, nullable `valueQuote`, nullable `conditionQuote` and
`evidenceQuote`; all quotations must be exact same-source substrings.

Five actual responses stopped normally and passed the native schema. Their record counts
are 1/2/8/8/1, and all twenty records passed literal same-chunk quotation checks. This does
not establish twenty valid facts. The separate `evidence_frame_binding_audit.json`
requires every nonnull quoted field to occur within its own evidence quotation: twelve
records pass and eight fail. All eight failures incorrectly bind the subsequent PM/QCM
qualification predicate to roles in the preceding BF8a continuation list. The input has
no exception-list introduction, and that missing context was not supplied to the model.
The twelve frame-bound records still require classification and relation review. B4.040
records misclassify completion/submission and commencement permissions; explicit time
values were left null; some visible propositions were omitted. The two eight-record
responses reached the item cap. No accuracy, recall or substantive-fact acceptance is
claimed, and the original results remain frozen.

Prompt/generated native token counts are 947/167, 1464/268, 1744/736, 2112/1262 and
1687/395. The 44 one-second resource samples observed whole-GPU memory up to 5681/8188
MiB, Java RSS 2,706,092,032 bytes, resident model-server Python RSS 2,416,189,440 bytes
and machine RAM used 20,131,971,072 bytes, with no sampler errors. These are sampled
peaks. Original inputs, native responses, exact quotations, frame-gate failures, model
placement, timing and resources are under
`tmp/fact_probe_results/single-chunk-first-five-3b-20261002T010549-5532536f/`.

## Independent same-pack 4B model-recipe comparison

Five actual `qwen3.5:4b` calls reused the same five default first-pack inputs from the
earlier 3B pack experiment. Complete chunks/Parts/source hashes, metadata, unknown scope,
system/user prompts, schema and explicit options are unchanged. Only the request model,
`think: false` and `keep_alive: 0` differ. No thinking response field was returned.
Model architecture/tokenizer/template/default sampling parameters also differ; the 4B
recipe inherits stored top_k 20, top_p 0.95 and presence_penalty 1.5, while its stored
temperature is overridden by explicit 0.2. The cached 3B manifest has no parameter layer;
its effective inherited engine defaults were not observed. This is not a weights-only
causal comparison. The actual 104 runtime and every frozen 3B primary artifact retain
their hashes, and no review/index/retrieval/OCR/upload/status mutation was performed.

All five responses conform to their native schema and stopped normally. Topic 3 returns
`[]`; the other four topics each return one `issue`. Seven quoted sides contain six
located exact quotations and one failed quotation. Topic 1's short `(d) Not used.` is
located but does not support the missing-target claim; its second quote introduces an
ellipsis and fails. Topic 13 passes literal quotation checks but fails the known
incomplete-comparison-context gate. Topics 14 and 16 yield two gate-passed candidates.
Independent source review establishes no supported confirmed defect from these four
outputs: explicit more-stringent minima are misclassified as a conflict, an explicit GCC
amendment target is denied, and local missing documentation is overstated as a high
missing obligation despite unknown applicability/exception context. Empty output and
passed gates do not establish substantive understanding, accuracy, recall or improvement.

Actual prompt/generated token counts are 4495/284, 2544/2, 4309/422, 4221/296 and
6289/263. Native counters do not prove absence of input truncation. Client-observed
duration totals 44.733 seconds, native service duration 42.827 seconds (15.531 load,
7.825 prompt evaluation and 19.373 generation); immediate unloading reloads each model.
Ollama reports `size` and `size_vram` both 3,341,958,511 bytes at context 8192, with the
expected digest `2a654d98e6fba55d452b7043684e9b57a947e393bbffa62485a7aac05ee4eefd`.
The 45 one-second resource samples observed whole-GPU memory up to 7496/8188 MiB,
Java RSS 2,706,296,832 bytes, resident model-server Python RSS 2,382,770,176 bytes and
machine RAM used 21,030,334,464 bytes, with no sampling errors. These are sampled peaks,
with build/render/other model work idle, and do not predict H800 performance.

The fixed preparation manifest and seven offline source/body invariants are in
`tmp/review_pack_model_comparisons/prepared-qwen35-same-first-five-20261002T012200/`.
Actual native responses, raw hashes, resource/placement samples and the automatically
counted `model_recipe_comparison_audit.json` are in
`tmp/review_pack_model_comparisons/actual-qwen35-first-five-20261002T012236-a0fd7944/`.
The linked independent `actual_4b_same_first_five_semantic_audit.json` remains evaluation
data and must not be used as production retrieval/prompt input. No default model switch
or new full-package semantic acceptance follows from this diagnostic experiment.

## Offline replay of the conservative reference comparison policy

The updated source applies the existing conflict comparison requirements to model
`issue/reference` records too: at least two distinct `documentId|located anchor` pairs
must be quoted, and none of the quoted chunks may be in the captured selection's known
unresolved comparison IDs. Nonissue reference records and deterministic rule findings
retain their existing behavior. This is a conservative evidence gate; it can omit valid
single-passage reference or formatting issues and does not establish semantic truth.

The independent `reference_comparison_policy_replay.json` under the actual 4B batch above
replays all five frozen responses and their four records against that source policy.
It verifies schema, raw/native record binding, complete submitted Chunk/Part objects
against the original 13,270-chunk corpus, and actual located Part anchors. The old result
still contains two gate candidates. In the separate replay, the new gate accepts zero:
topic 14 has two distinct located anchors but both quoted chunks have known unresolved
context; topic 16 has only one located anchor and its quoted chunk also has known
unresolved context. Topic 1 retains its failed quotation and lacks a second located
anchor; topic 13 retains the existing incomplete-context rejection. Seven quoted sides
still contain six located quotations and one failure. All 27 pre-existing batch files
retain their before/after SHA-256 values.

This replay performs zero HTTP/model/index/OCR/application-review/status calls. It is a
logical offline replay of current Java source, not an execution of the production Java
application. The immutable 104-test baseline JAR predates this policy. Frozen original responses and
their original gate audit remain unchanged. Zero candidates proves this gate behavior
on these records; it does not prove that the contract is defect-free or that recall,
accuracy or professional acceptance improved.

## Original SCT table-heading and scope replay

Clause metadata `owner-clause-v2` restores explicit file-owner titles on the first
line of a Word table cell, including titles followed by body paragraphs in that cell.
Empty layout cells and explicit same-number continued titles retain the source scope.
A title with another populated applicability cell ends the previous scope but keeps
unknown ownership. Independent uppercase Appendix/Annex/Schedule labels also end
the previous scope. Bare table numbers, reference lists, foreign-owner references,
contents rows and obligation prose do not establish a new owned title.

The final offline replay uses three original SCT DOCX files and the three original
project-fact DOCX messages. Raw SHA-256 and parsed source revisions match the frozen
v1 source set. All 863 generated Part block IDs, UTF-16 offsets and source substrings
pass preservation checks. The standard SCT changes from 37 unlabelled chunks to 45
chunks, 35 labelled; the tender main SCT changes from 65 unlabelled chunks to 79
chunks, 44 labelled. SCT.A changes from 38 to 35 chunks and retains 15 labelled
chunks. These are structural counts, not reviewed or legally correct clauses.
The independent `independent_source_coverage_audit.json` also constructs per-block
UTF-16 interval unions: all 127395 effective source characters in 863 nonempty
original blocks are covered, with zero missing ranges, incorrect offsets or altered
Part text. Its six-source scope is recorded explicitly.

The actual source checks confirm SCT17/18 scope, no SCT24 inheritance into the six
Appendix B-G sections, and unknown scope for the standard SCT14 multicell heading
instead of false SCT13 inheritance. The production selector's fixed SCT6/7/8
diagnostic now contains all three project facts, 11 chunks and 9865 content characters;
the v1 diagnostic contained one fact. No live retrieval or model was called. Parent
SCT7 scope hits do not prove exact SCT7(1)/(2)/(3) subparagraph location.

Final replay evidence is under workspace
`tmp/sct_table_structure_audits/final-owned-table-boundaries-20261002T095410/`.
The final fresh full CPU snapshot is
`tmp/test_snapshots/all-cpu-final-vetting-20261002T095430/`: 14 suites, 126 tests,
zero failures/errors/skips, with frozen source/class identities and unchanged original
data and 104-test baseline JAR. No package, deployment, OCR, index or full application
review was performed in this revision. All v2 chunk IDs change; freeze a new corpus
and rebuild its retrieval signature before a new application/model run. Existing
v1 rankings, experiment inputs and exported reports remain separate historical evidence.

## Actual full44 v2 cold-index application baseline

The 126-test immutable application JAR was subsequently packaged and deployed for one
actual full44 run, `a2ebfeb7-a4fd-45fe-8d7e-390e27882808`, on the same original project.
Its SHA-256 is `bf3def545e435d41250e4a7f2300be2096155d2ed195f3d88f69f5785e272ed1`.
The evaluation database was cold-copied into an isolated directory; the original
evaluation database and all 165 protected historical artifacts retain their hashes.
The original uploads remain the sources. This run performed no upload, production
parsing, OCR, finding-status mutation or professional approval. The original demo
database was not opened in this round. A new isolated Python retrieval state reused
cached weights, with embedding/reranker/OCR/LLM initially unloaded.

The exact packaged v2 corpus contains 13,293 chunks from all 44 persisted sources.
An independent per-source UTF-16 interval-union audit proves coverage of all 207,746
nonempty source blocks and 13,266,431 effective characters through 207,800 Parts:
zero missing ranges, altered source text or incorrect offsets. All 177 packaged class
identities, original file-byte hashes and persisted source revisions are bound in the
snapshot. The audit compares source revisions with the preserved baseline; it does
not independently recompute revisions from raw `textContent`/`structuredContentJson`
strings, which were not exported. Source revisions and original file-byte SHA-256
remain separate identities.

A transparent recording proxy captures the actual application's sole cold `/index`
and all 64 `/retrieve` calls without changing or retrying their bodies. The actual
index request exactly matches the frozen 78,310,692-byte corpus request. Its native
response reports 13,293 indexed chunks, `cached: false`, 225.753 service seconds and
signature `944e1f76640ae513326085ba0aba6e84516d0c40d850a9fc411fadaf99a5861a`;
proxy-observed elapsed time is 228.400 seconds. Every role is queried 16 times. Ordered
hits, current source payloads, roles and index signatures pass independent transport
reconciliation. No independent prewarm or cached index POST preceded this run.

Actual index plus review takes 428.230 seconds. The 64 retrieval intervals total
46.118 seconds and the 16 logged model-gateway waits total 120.390 seconds. The
remaining 33.321 seconds cover uninstrumented stage work and gaps; they are not a
measurement of pure CPU time. The exact review window has 425 one-second samples:
whole-GPU memory peaks at 6135/8188 MiB, Java RSS at 4,573,474,816 bytes, model-server
Python RSS at 5,202,702,336 bytes and machine RAM used at 22,897,672,192 bytes. Cold
index, retrieval and model-wait GPU sampled peaks are respectively 2672, 3795 and
6135 MiB. The complete runner has 433 samples; its Java peak including exports is
4,611,010,560 bytes. These are sampled maxima, not absolute peaks or H800 predictions.
Ollama's 87 nonempty placement observations report the expected `qwen2.5:3b` digest,
context 8192 and `size == size_vram == 2,403,178,905` bytes. Native token counts and
`done_reason` are not captured by the application INFO log. Prior104 used warm
index/model state, so its 198.632-second time is not an isolated speed comparison.

All 16 actual inputs and raw responses reconcile with the persisted topic ledger,
including ordered submitted IDs, raw-response hashes and assessment/rejection counts.
Sixteen native-schema checks pass. Topics 7, 9 and 16 return `[]`; the other topics
return 19 records: 17 `insufficient_context` and two `issue/reference`. Forty-two
quoted sides contain 25 literal matches and 17 failures; 12 records fail evidence
gates. Both issue candidates are rejected and zero model findings enter the report.
The seven gate-passing records are all insufficient-context assessments. The final
source-first review of every raw record identifies 11 absence claims directly
contradicted by the supplied source, one unsupported SCT7 repetition/scope inference,
two unsupported issues and five bounded/bad-citation/unsupported gap observations.
SCT7 itself names SL2022; GS2020 is explicit in other supplied SP/PRE clauses, so a
demand to repeat it in SCT7 is unsupported rather than a directly contradicted absence
inside SCT7. These outputs do not pass semantic-quality acceptance. No accuracy,
precision, recall or fifteen-case benchmark score is inferred from these counts.

The actual retrieved-to-submitted gap remains material. There are 308 unique returned
chunks, but only 144 unique submitted chunks across 23 of 44 files: 138 tender, five
standard and one project-fact chunk. All three fact sources (IDs 102/103/104) and the
package manifest are returned in every topic's role-specific retrieval. Only fact104
is submitted, once in topic9, whose response is empty; facts102/103 and the manifest
are never submitted. Topic4 includes two owned SCT7 chunks without the related facts;
no owned SCT6 or SCT8 chunk is submitted. The ledger records 164 budget-dropped group,
45 partial-context group and 82 unresolved submitted-segment occurrences. Correct
source preservation and heading metadata therefore do not establish complete contract
comparisons. The next investigation is how retrieved clauses, exact amendment targets,
related facts and qualifiers are assembled into comparison material; another unchanged
whole-topic run does not resolve this observed gap.

The independent actual scope spot-check further separates the clause side: tender
SCT6 and SCT8 appear in none of the 64 final ranked-hit responses. SCT7 has one core
hit at topic4 rank8; adjacent completion supplies the two submitted SCT7 chunks.
The facts and manifest are excluded during packing, but the missing SCT6/SCT8 clause
side also requires nomination from original project/source references. Final ranked
hits do not expose pre-rerank candidate recall. Actual Appendix B-G boundaries retain
unknown ownership without inherited SCT24; the qualified standard SCT14 multicell
row and all 72 Annex-PDF chunks also keep unknown clause ownership. These scoped
checks are frozen in `independent_actual_v2_scope_spotchecks.json` and do not establish
applicability or completed clause/fact comparisons.

The three final deterministic findings remain Open and differ from the prior104
findings only in `runId`; fingerprints, bodies and evidence are unchanged. Eight
report quotations pass independent original-DOCX extraction/location checks excluding
explicit tracked deletion/strike. Inherited style strike, unseen qualifiers,
applicability and operative contract effect remain unknown. Actual JSON/DOCX/PDF are
exported, and the parent report QA separately verifies all rendered pages. Browser
export responses were observed, but browser-managed report-download events timed out;
this is distinct from the successful API exports. Completion, literal matches and
layout checks do not constitute professional or semantic acceptance.

The complete round is under `tmp/vetting_full_case_v2_20261002T020023/`, with the
project subdirectory `vetting-e2e-20261001T214513-b066e710/`. Important evidence includes
`corpus_snapshot_complete/independent_all44_source_coverage_audit.json`, actual raw
`rag_http_capture/` at the round root, `actual_model_audit_v2/`,
`independent_actual_rag_capture_audit.json`, `independent_actual19_source_semantic_audit_final.json`,
`actual_resource_phase_audit.json`, `actual_duration_accounting.json`,
`actual_104_to_126_baseline_diff.json`, `actual_rule_findings_baseline_diff.json`,
`old_baseline_preservation_final_audit.json`, `actual_run_provenance.json` and the three
actual `report.*` exports. These remain separate from preserved104 and model-probe
evidence; no additional model/index/application-review call follows from this audit.

## Integrated project-reference comparison candidate

The current Java source appends explicit project-reference comparisons to the
ordinary retrieval-topic review. `VettingSemanticReview` calls
`VettingReviewPackBuilder.buildFactComparisons(...)`; the older ranked
`build(...)` comparison-pack experiment is still unused by the application.
This is source integration with offline validation. It has not yet completed a
deployed full44 application run and does not revise the actual 126-test run,
reports or findings described above.

Original `project_fact` references nominate owned tender scopes regardless of
retrieval rank. Each admitted comparison keeps whole original tender chunks and
the referring project-message chunks, then adds the single corresponding standard
scope when available and within budget. Project facts remain contextual evidence,
not competing contractual obligations. Ambiguous or unlocated tender targets,
unlocated requested subparagraphs and omitted support are reported rather than
treated as proof of missing or inapplicable clauses. A comparison whose complete
tender scope plus project context cannot fit is not partially substituted with a
shorter invented clause; it is omitted with a warning.

The latest source has a project-reference-only generic system extension. It
requires separation of request/reply cells, blank replies versus explicit
answers, standard placeholders versus adopted tender values, and checking every
supplied continuation before asserting absence. It contains no SCT-specific
rules or evaluation answers. The ordinary sixteen topic prompts stay unchanged.
The shared candidate gate now additionally checks comparison basis from quoted
roles and explicit targets: two distinct located tender provisions; for reference
records, tender evidence with project-fact support; or a quoted tender passage
explicitly naming the differently owned standard provision. Same-number
template/tender text alone does not establish that template values govern the
adopted contract. These are necessary qualification checks, not proof of adoption,
field relationships, applicability or legal correctness.

Both property defaults and `application-vetting-local.yml` use
`consense.vetting.project-reference-context-chars=12000` and
`consense.vetting.project-reference-comparisons=16`. These settings govern source
characters per comparison and the maximum additional comparisons, respectively.
Ordinary-topic limits remain 10000 characters and 16 topics. Character budgets
exclude prompt framing and are not tokenizer limits. Semantic review must be
enabled, the LLM available and the ordinary-topic count nonzero for these
additional comparisons to be planned.

Progress uses the actual plan: ordinary-topic count plus admitted comparison
count, with two additional job units for rules and saving. The queued estimate is
updated after comparisons are selected; the maximum comparison count is not an
unconditional number of calls. `SemanticTopicVO` and frontend types expose
`reviewKind`, `selectionStrategy` and `referenceIds`. The frontend call-ledger
table labels `project_reference` rows with a translated badge and the nominated
references, while retaining the server-provided dynamic progress denominator.
Neither a row nor a completed call implies that its scope is complete or that a
model finding is valid.

The completed offline evidence is under
`tmp/review_pack_replays/integrated-fact-candidate-20261002T024647/`. Inspect
`offline_completion_manifest.json`, `freeze_manifest.json`,
`source_offset_prompt_and_accounting_audit.json` and
`packs/fact_comparisons/production_prompt_*/` together. The manifest binds the
frozen source/classes, selected original chunks, prompt envelopes, schema and
prompt provenance. It contains three additional comparisons:

| Reference | Source-content characters | Tender / project-fact / standard chunks | Original project-message document IDs |
| --- | ---: | --- | --- |
| SCT6 | 10784 | 3 / 3 / 4 | 102, 103, 104 |
| SCT7(1), SCT7(2), SCT7(3) | 6021 | 2 / 3 / 3 | 102, 103, 104 |
| SCT8 | 2703 | 1 / 3 / 1 | 102, 103, 104 |

Selected packs record zero omitted support IDs and zero known unresolved
comparison IDs. This is accounting for the selected material, not recursive
cross-reference closure. `qualifiersComplete` remains `unknown`; GCT, SCT1, CON8
and APE2 references still require source review. The independent audit passes
source preservation, offsets and accounting for 2,371 Part occurrences and all
19 production prompt constructions. The original sixteen ordinary-topic
submissions and prompts remain equal to the frozen actual126 baseline; three
source-nominated comparisons are added. The offline replay executes zero model,
HTTP, database, index, OCR or application-review calls, and observes no tokenizer
or generation counters. No latency, GPU feasibility, semantic correctness or
professional acceptance is inferred from it.

Earlier source evidence was the complete 15-suite full133 pass, the affected
25-test warning-repair pass and a targeted nine-test workflow pass. The then-current
134-test inventory did not represent a full134 rerun. The workflow tests include a
role-aware upload/asynchronous-worker regression with controlled empty model
responses. It pauses the extra comparison and verifies persisted progress at
19 total units and 17 completed, followed by 19 completed at finish. Restored
and exported ledger entries preserve `reviewKind: project_reference` and
`referenceIds`; tender and fact coverage are retained, and the empty comparison
does not create a finding. These controlled workflow checks make no native model
calls.

The earlier generic project-only prompt and comparison-basis source revision
passes a single complete Maven run: 15 suites, 138 tests, zero failures, errors or
skips, exit 0, at 11:13:21–11:13:29 local time on 2026-10-02. The 138-test inventory
is the earlier 133 plus one workflow and four semantic regressions; this pass is
not assembled from targeted runs. The exact proof is
`tmp/test_snapshots/source-fact-basis-138-20261002T1113/test_manifest.json`, SHA256
`ff3faef97fdc755a41a33d4b08f5ca1df5db920722ba03cd98db8ec83e9c92b8`, binding 326
frozen files including XML and source/class identities. The frontend build and
`node scripts/test-vetting-view.mjs` passed earlier. No new deployed comparison
badge/UI check is claimed from these Java tests. The earlier offline selection
replay retains its original source/class revision before the later warning and
project-only prompt changes; it is not re-labelled as a replay of the full138
revision. The application remains the deployed immutable126 baseline.

## Three actual native project-reference diagnostic rounds

Each round calls the SCT6/7/8 production prompts directly and sequentially, for
nine native calls and thirteen raw records overall. These are fixed diagnostic
calls, with no upload, parse/OCR, index/retrieval, database/status mutation,
application review, deployment or report-generation call.

| Round | Assessments | Quote sides: located / failed | Historical evidence records: pass / reject | Historical issue candidates |
| --- | --- | --- | --- | ---: |
| Original system, 3B (`025513`) | 6 insufficient-context | 7 / 4 | 1 / 5 | 0 |
| Generic project-only system, 3B (`030129`) | 1 issue, 3 insufficient-context | 7 / 3 | 2 / 2 | 0 |
| Same generic system, 4B (`030454`) | 1 issue, 1 insufficient-context, 1 consistent | 6 / 1 | 2 / 1 | 1 |

All nine responses pass native schema checks and end with `done_reason: stop`.
Native input/generation counters are retained, but no independent full-template
token-retention trace proves that every source token reaches generation. The
original-to-generic 3B comparison changes only the system instruction; the generic
3B-to-4B requests retain the same source chunks, system, user, schema and explicit
options, changing the model and adding `think:false`. Cached models inherit
different sampling recipes, so this is not a weights-only causal comparison.

Independent source review finds five directly contradicted absence claims and
one unsupported template-actor substitution in the first six records. The next
four contain an unsupported table issue and three false absence claims despite
supplied addresses and dates. The final three contain an unsupported blank-reply/
subparagraph inference, an unsupported old-template-as-adopted-edition issue and
one bounded officer-context observation. That bounded observation does not verify
all dates, applicability or the full clause. None of the rounds passes semantic
acceptance; zero issue candidates in the 3B rounds is not successful vetting, and
the historical 4B gate-passing issue must not be adopted as a supported finding.

The evidence locations, relative to the workspace root, are:

| Round | Technical manifest | Independent source-semantic audit |
| --- | --- | --- |
| Original 3B | `tmp/review_pack_probes/production-facts-latest-3b-20261002T025513/native_execution/technical_completion_manifest.json` | `tmp/independent_fact_response_audits/native-production-facts-latest-3b-20261002T025513/independent_native_six_record_source_semantic_audit.json` |
| Generic 3B | `tmp/review_pack_probes/production-facts-latest-3b-20261002T030129/native_execution/technical_completion_manifest.json` | `tmp/independent_fact_response_audits/native-production-facts-latest-3b-20261002T030129/independent_native_four_record_source_semantic_audit.json` |
| Generic 4B | `tmp/review_pack_model_comparisons/project-facts-generic-4b-20261002T030454/native_execution/technical_model_recipe_comparison_manifest.json` | `tmp/independent_fact_response_audits/native-project-facts-generic-4b-20261002T030454/independent_native_three_record_source_semantic_audit.json` |

The later CPU sidecar
`tmp/review_pack_probes/production-facts-latest-3b-20261002T031608/current_role_basis_audit.json`
replays the thirteen unchanged raw records with the current comparison-basis
guard. The historical candidate count drops from one to zero; the actual 4B SCT7
candidate has `hasComparisonBasis=false`. This adds no model call, rewrites no
historical response or old-gate result, and proves only the new gate's exclusion.
It does not establish reliable reading, adoption, field correctness or recall.

Whole-machine one-second sampling is separate from semantic judgment. Original
3B uses 30 samples over 29.812 seconds, with sampled GPU peak 6129/8188 MiB and
RAM used peak 20,166,000,640 bytes. Generic 3B samples 6144 MiB; generic 4B uses
33 samples over 32.750 seconds and reaches 7776/8188 MiB. BGE/reranker/OCR remain
warm and resident, and Ollama loads for the sequential diagnostic calls. These
peaks include existing services, may miss short spikes and do not predict cold
full44 or H800 behavior. Immutable126 reports, native histories and default 3B
configuration stay unchanged. No new full44 application run has been performed
with the full138 source revision.

## Latest human-review implementation verification

The later complete backend run finishes with exit 0 and 17 XML suites / 155 tests /
0 failures / 0 errors / 0 skips. XML timestamps are 2026-10-02
11:45:25–11:45:33 Asia/Shanghai; the workspace log is
`tmp/human-review-full-tests-20261002.log`. This is a complete new run rather than
a total assembled from focused runs. The preserved full138 snapshot above remains
proof of its earlier source revision and is not overwritten or re-labelled.
The fresh independent proof is
`tmp/test_snapshots/human-review-155-20261002T034717590368Z/test_summary.json`,
SHA256 `4b5f07efc8fb6b4e10ce5dd97643a52a57f5c39b136aa24e5d770a11c5dc6502`.
It binds 413 source/class/migration/configuration/log and frontend source/dist/
package-lock files, including 184 production and 17 test classes. It independently
totals the 17 XML suites; frontend command success is recorded from the root
agent's actual observations, not a second execution by the freeze script.

The current tests exercise real H2 review-field persistence and V5 compatibility,
full replacement and null/false semantics, raw text and server timestamp round
trips, atomic length rejection, project isolation and status independence. The
existing upload/asynchronous-worker regression also detects the same finding on
a later run, verifies retention of saved records and checks that the actual
assembled model prompts contain no human-review text. The Word/PDF regression
checks all three addendum decisions independently of status and includes saved
response/action/time alongside unchanged original evidence.

The frontend `vue-tsc` plus Vite production build and
`node scripts/test-vetting-view.mjs` finish with exit 0. Four added controlled UI
regressions check raw saving and false versus null, per-finding drafts across status
changes, stale saves after a project change, and failed-save draft retention.
These are source/build/regression checks; no new deployed review UI interaction,
full44 application run or all-page export QA is claimed from them. The running
application remains immutable126, and its reports and historical counts stay intact.

## Actual source-grounded cell diagnostics

Two later fixed three-call rounds retain a separate experimental source/class
identity: an earlier 55-test focused pass binds the native cell inputs/classes,
before the human-review fields were added. Native schema, exact quote checks and
current candidate gates are technical stages, not a semantic acceptance test.

| Round | Raw assessments | Located / total quote sides | Current evidence records: pass / reject | Issue candidates |
| --- | --- | ---: | --- | ---: |
| Cell-annotated 3B (`033230`) | 5 insufficient-context | 9 / 14 | 2 / 3 | 0 |
| Same-cell 7B (`033850`) | 3 issue records; SCT6 returns `[]` | 6 / 8 | 1 / 2 | 1 |

Independent source review rejects all five 3B records. Supplied table continuations,
addresses, dates and subjects are still misbound or denied; an explicit `Not used`
entry is not reliably interpreted. The historical and current 7B gates admit one
SCT8 candidate, but its two sides supply the same date and do not establish the
claimed inconsistency. That candidate cannot be adopted as a supported finding.
The completed independent 7B source-semantic audit supports none of its three
issue records. Its 34 item-level checks comprise three source-contradicted claims,
three unsupported object associations and 28 unassessed items. All twelve SCT6
items remain unassessed after `[]`; absence of an output does not establish correct
review of that clause. These counts are not accuracy, recall or comprehensive
quality scores. Neither model round has passed contractual-quality acceptance.
A larger model, a valid schema,
located text or a gate-passing record alone cannot establish correct meaning.

Evidence paths are relative to the workspace root:

| Evidence | Path |
| --- | --- |
| 3B cell technical completion | `tmp/review_pack_probes/production-facts-latest-3b-20261002T033230/native_execution/native_cell_annotation_completion.json` |
| Independent 3B five-record source audit | `tmp/independent_fact_response_audits/native-cell3b-20261002T034121460962Z/independent_native_cell3b_five_record_source_semantic_audit.json` |
| 7B same-cell technical comparison | `tmp/review_pack_model_comparisons/native-cell-7b-20261002T033850/native_execution/technical_native_cell_7b_comparison_manifest.json` |
| Independent 7B three-issue source audit | `tmp/independent_fact_response_audits/native-cell7b-20261002T034705308429Z/independent_native_cell7b_three_issue_source_semantic_audit.json` |

The independent 7B audit SHA256 is
`850b1a7760be963a0d12f6584714b1bf7574c9e1ce1fc2c9e20792a9fa7d734e`.

The 7B calls change only the native request's model field; the manifest binds the
same system/user/schema/options/cell inputs. Whole-machine sampling has 54 samples
over 54.109 seconds, with sampled GPU peak 7891/8188 MiB and machine RAM used about
20.711 GB. Placement observations report the 7B model fully in VRAM, approximately
4.987 GB at context 8192. These are sampled feasibility observations in this warm
diagnostic setup, not H800 measurements, absolute resource peaks or full44 coverage.
Both rounds are complete with sampling stopped. They perform no new application
review, deployment, upload, OCR, index, database/status change or default-model
switch. Human-review verification and model-quality judgment remain separate.
