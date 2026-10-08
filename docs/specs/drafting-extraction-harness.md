# Drafting extraction harness repair

Date: 2026-10-06. User instruction: “先把harness部分修好”. Integration branch remains `feature/drafting-a-business-rules-20261005` in both isolated repositories. This extends the existing Drafting spec; it does not replace the business catalog, source-edit rules or outstanding A09/A06 download acceptance.

## Problem and evidence

The actual eight-source 7B run retained in `local verification artifact (not published)` completed decoding but was not semantically accepted. Its effective system prompt is 22,211 characters, includes both a plain catalogue and structured catalogue, and repeats the full input contract. The user prompt repeats that contract again. It asks for JSON serialized inside strings despite A10 already accepting native values.

179 returned items became 17 candidates in seven fields. Invalid shapes and quotes are silently discarded, so a recognized-but-rejected contract title looks like absent source information. L10Pro/Hardcopy disagreement is a model candidate conflict, not a demonstrated conflict between original documents. A request is treated as an answer, pending foundation classification becomes true, and a Bill reference causes invented rows to be merged with the real 13 rows. A successful transport or decoder is not an accuracy verdict.

## Scope and agreed acceptance seams

The already agreed seams remain real project-scoped upload/extraction/persistence/trace APIs and the actual Drafting frontend controls. Use real DOCX parsing, H2, AiGateway and service logic; double only the external LlmClient when replaying captured responses. Frontend verification observes actual rendered controls, not private component state. Tests at these existing seams do not require another approval.

The current 21-group business catalog and its 79 editable fields remain complete. Missing and conditional inputs remain editable. Keep all correspondence parts, manual/adopted values, evidence revisions, source hashes, generation snapshots and existing DOCX/PDF artifacts. Keep the current model/provider/context budget and vetting/advice unchanged. No simulated answer may become a runtime default, and no special case may recognize these SIM filenames or their known answers.

## Service requirements (DRAFT-A-11)

1. Introduce a dedicated Drafting extraction harness rather than modifying shared gateway/provider behavior. It owns request construction, per-part attempt bookkeeping, candidate intake checks and diagnostics. Existing business normalization, source revisions and manual adoption remain authoritative.
2. Default prompts contain one compact canonical schema catalogue and one authoritative extraction protocol. Use native JSON values (object, list, Boolean, number, string or null), with concrete shape examples for contract identity and list records. Legacy serialized values remain compatible. Keep customized prompt text through the existing PromptService mechanism; never silently erase user customization. The full source part is clearly delimited and not interleaved with the schema/instructions. Do not ask the model to emit empty copies of the whole catalogue or guess absent values.
3. Every dispatched attempt records its exact system/user prompts and raw response, source ID/name/hash, part ID/index, attempt kind and failure state. Retain all parts and attempts, including a failed last part or repair. An error must not publish the prior successful trace as if it described the current run.
4. Every returned item receives an intake decision: accepted, rejected or unanswered. Retain raw value, normalized value where available, key, quote, reason, confidence and source/attempt/item identity. Stable codes distinguish unknown/hidden keys, missing/out-of-range/low confidence, missing/non-contiguous quote, invalid value shape, unknown value and unsupported literal identity. Empty/null/unknown remain distinct from false and an explicitly justified empty list.
5. Contract number/title and Bill number/description are direct source identifiers: a supplied nonblank identifier must occur in the same actual source part after typographic/whitespace normalization. This is a necessary grounding check, not a semantic certification. Do not require every derived classification/enum literal to appear verbatim; BSSSC, for example, may be described by its full name. Do not implement a generic Boolean truth/negation judge using keyword guesses. Do not invent missing contract siblings or Bill descriptions, or certify guessed Bill metadata.
6. Permit at most one targeted model repair per part for rejected known, nonblank values with shape, quote or literal-grounding failures. The primary call has at most the existing one syntax retry; the repair is one explicitly tracked dispatch with no recursive/implicit repair retry: total maximum three dispatches per part. Supply the original source and precise rejection reasons, allow only the failed keys, and retain the original decisions. Never repair empty/unknown/low-confidence output or overwrite already accepted values. A failed repair leaves those suggestions rejected with visible reasons. Primary malformed output still fails the extraction atomically, preserving previous candidates and all adopted values.
7. Final completed and failed runs are persisted as append-only project records, not overwritten. The existing latest `/variables/extract-trace` contract retains its five legacy fields and gains additive diagnostics. Provide project-scoped bounded run-list and individual-run reads. Reading history is read-only and must not change values, mark adoption or invalidate generated artifacts. Source changes make a report visibly stale; they do not rewrite its source identity.
8. Field diagnostics distinguish suggested, candidate_conflict, rejected, model_unanswered, no_candidate and not_assessed. A model not returning a field does not prove the source lacks its answer. Candidate disagreement does not prove original documents conflict. Keep extraction state separate from adopted/manual state and current applicability.
9. Extraction remains atomic against malformed primary responses and source changes; manual edits made during a model call survive. Failed-run reporting must survive the same failure without committing partial candidate changes. Persisting a successful report and its candidate update must not leave a misleading success/failure pair.

## Additive trace contract shared with frontend

Existing `model`, `finishedAt`, `systemPrompt`, `userPrompt`, `rawResponses` remain. Add:

- `runId`, `harnessVersion`, `evidenceRevision`, `status` (`completed` or `failed`), `startedAt`, `failureCode`, `failureMessage`, `stale`.
- `parts[]`: `partId`, `sourceDocumentId`, `fileName`, `sourceHash`, `partIndex`, `sourceText`, `attempts[]`.
- `attempts[]`: `attemptIndex`, `kind` (`primary` or `repair`), `systemPrompt`, `userPrompt`, `rawResponse`, `status`, `errorCode`.
- `decisions[]`: `partId`, `attemptIndex`, `itemIndex`, `key`, `rawValue`, `normalizedValue`, `sourceQuote`, `reason`, `confidence`, `status` (`accepted`, `rejected`, `unanswered`), `codes[]`.
- `fields[]`: `key`, `status` (`suggested`, `candidate_conflict`, `rejected`, `model_unanswered`, `no_candidate`, `not_assessed`), `candidateCount`, `rejectionCount`, `unansweredCount`, `decisionRefs[]` (indices in decisions).

History URLs: `GET /api/drafting/{projectId}/variables/extract-traces?limit=20` returns bounded `ExtractRunSummary[]` with runId/harnessVersion/model/status/startedAt/finishedAt/evidenceRevision/failureCode/failureMessage/stale (default10, maximum20). `GET /api/drafting/{projectId}/variables/extract-traces/{runId}` returns the full additive trace and enforces project ownership. The existing singular latest `/variables/extract-trace` remains compatible.

Reason codes: `unknown_key`, `hidden_key`, `confidence_missing`, `confidence_out_of_range`, `confidence_below_threshold`, `quote_missing`, `quote_not_in_part`, `invalid_value_shape`, `value_unanswered`, `lexical_support_missing`, `repair_key_not_allowed`. Additional codes require a corresponding explanatory UI translation. Accepted means intake checks passed, not that business facts were certified. Historical traces without these fields remain renderable.

## Frontend requirements (DRAFT-A-12)

10. On the existing vertical input list, show latest-run diagnostics without changing the existing adoption/applicability calculation, edit controls, group order, hidden-value cache or manual-save behavior. A rejected candidate must not look like proof that the source contains no answer. Use “no usable suggestion returned” rather than claiming “no source evidence”.
11. Explain candidate disagreement as “candidate values disagree”; show each quote and raw value. Do not label an unverified model conflict as an established evidence conflict. Explain stale and failed reports; previous adopted values remain independent.
12. Extend the existing extraction-process panel with run outcome, per-part attempts and the accepted/rejected/unanswered counts and reasons. Raw output remains inspectable. A rejected output has no adoption button; manual input remains available. Accepted intake is explicitly not semantic validation.
13. Provide Simplified Chinese, Traditional Chinese and English labels/reason descriptions. Preserve English source values. Legacy trace payloads, empty history, failed runs and project switches must render safely. Historical report inspection must not replace current form values or adoption state.

## Replay and verification requirements (DRAFT-A-13)

14. Keep immutable before/after prompts, full source coverage, all raw attempts and field decisions. Replay the actual A10 captured output through the public API using the external model boundary; assert real rejection visibility, partial valid candidates, unknown/false/[] distinctions, conflict provenance, history isolation and failed-run atomicity. Build each new behavior test red before its minimal implementation.
15. Provide an offline, read-only evaluation command taking saved trace + saved variable responses and an independently written source-grounded expected-case manifest. Its bounded cases include contract number/title, domestic blocks, the 39-month threshold, exact accepted period unknown, foundation classification unknown, BSSSC/trades, two-envelope, L10Pro and the actual 13 Bill numbers/descriptions. Measure supported answer recall, wrong/unsupported fills and structural compliance separately. Identify unevaluated catalogue fields; do not treat absent expected coverage as incorrect or claim full semantic acceptance.
16. Run the replay/evaluator on the saved A10 baseline and on one actual current 7B run over the same eight uploaded sources. Show any remaining errors honestly. No stronger-model swap or new paid provider comparison is required for this harness repair.
17. Independent merger and both standards/spec review axes precede deployment. Relevant service regressions, frontend typecheck/build and observable three-language diagnostics must pass. Show the current in-app browser page, preserve previous six export bytes and source files, close only the new local tickets whose acceptance is complete, and remove only clean merged temporary implementer worktrees.

## Task graph

- A11: backend harness/persistence/prompt/repair and public-seam red/green evidence. Ready from A10.
- A12: frontend diagnostic rendering at the above stable additive contract. Ready in parallel; live acceptance depends on A11.
- A13: integration, offline source-case evaluation, actual 7B/browser run, both review axes and scoped deployment. Depends on A11/A12; closes this harness repair. Does not close A09/A06 downloads.
