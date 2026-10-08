---
title: Drafting — prototype A implementation and source-grounded business corrections
status: implemented-acceptance-pending
labels: [implemented, verification-pending]
date: 2026-10-05
authority: User decisions, competition brief, standard NTT/SCT/SCC, Wang Li meeting and Comments
---

## Problem Statement

业务人员需要根据比赛标准 NTT、SCT、SCC 和项目沟通资料完成英文合约起草。目前真实系统仍使用旧八项输入／BASE与FILE重复编辑，资料未识别的必要问题可能消失，父子值与修改范围不一致，人工修改可能在刷新或识别后丢失，未知项被确认门禁拦住，生成稿未说明其对应的资料及取值版本。原型 A 已验证一个竖直可编辑问题清单更适合实际使用，但模拟值、假识别和假导出不能移入真实应用。

原文复核另发现比赛必要判断遗漏、编号重排及跨文档引用缺少闭环。“待核实”同时掩盖缺答案、缺关联、缺源文裁定，业务人员不知道如何完成。Comments 是对当时实现的反馈，不能代替完整比赛范围；同时不应把内部54组字面变量目录全部放到前端。

## Solution

采用原型 A 的三步流程：读取真实资料 → 一个竖直业务问题清单 → 生成后的真实英文预览与导出。静态、版本化业务目录决定必要问题，模型仅提供有原文依据的候选值；没有答案的必要项继续展示可编辑。主控制项在依赖项之前，少量条件细节在各组内展开，来源和受影响条款折叠查看。

使用每个业务事实的一份本次采用值，统一派生条件、条款采用、修改动作、编号和引用。人工编辑即采用此次值；新资料不覆盖人工值，只将实际受影响字段标为待复核。条件不适用时保留缓存但排除本次生成；未知保持未知。允许生成带未决事项的草稿，不增加审批门禁。

补足原文和会议确认的必要业务判断，归入既有组及少量新组，约21个主组作为布局安排；数量不构成完整性证据。Bill/Schedule用途、Section、提交要求和条款采用结果用共同记录复用。明确源文规则可执行；源文歧义、版次差异和外部依赖保留明确说明，并提供业务人员本次采用动作／精确改文的入口，不伪装成系统已经完成。

## User Stories

1. As a QS, I want the same read/edit/preview flow as prototype A, so that the production workflow is familiar.
2. As a QS, I want one vertical list of business questions, so that I do not answer the same fact for three documents.
3. As a QS, I want all necessary questions to exist before extraction, so that absent evidence does not hide required inputs.
4. As a QS, I want unidentified values to remain editable, so that I can supply knowledge the model cannot find.
5. As a QS, I want upstream controls first, so that dependent choices are understandable.
6. As a QS, I want inapplicable details hidden with saved values retained, so that switching branches does not destroy my work.
7. As a QS, I want unknown prerequisites distinguished from No, so that the system does not silently select a negative branch.
8. As a QS, I want explicit None distinguished from unanswered lists, so that the draft uses my actual decision.
9. As a QS, I want Simplified Chinese, Traditional Chinese and English UI, so that the existing working environment is retained.
10. As a QS, I want English contract output independently of UI language, so that the final working documents use English terms.
11. As a QS, I want formal Bill descriptions and free text preserved, so that display translation does not rewrite evidence.
12. As a QS, I want real upload, parsing, extraction, saving and export, so that a prototype simulation cannot appear successful.
13. As a QS, I want model suggestions beside their actual source passages, so that I can assess the proposed value.
14. As a QS, I want human edits to prevail over extraction, so that new suggestions do not undo my decision.
15. As a QS, I want candidates and adopted values stored separately, so that I can inspect differing evidence.
16. As a QS, I want changed evidence to flag affected fields, so that I review the relevant decisions.
17. As a QS, I want editing inspection restrictions to leave date review intact, so that unrelated edits do not certify dates.
18. As a QS, I want saving one field to retain other unsaved edits, so that normal editing is reliable.
19. As a QS, I want project switches and late requests isolated, so that data cannot be written into another project.
20. As a QS, I want foundation inclusion AND an inclusive 39-month threshold to determine G1/G1a, so that the accepted rule is applied exactly.
21. As a QS, I want optional months to calculate the threshold without duplicating it, so that there is only one adopted duration fact.
22. As a QS, I want NSC to exclude BSSSC trade and replacement inputs, so that the interface gives correct guidance.
23. As a QS, I want tender procedure separated from BQ issue medium, so that a custom procedure is not inferred from L10Pro or hardcopy.
24. As a QS, I want domestic/CON8 and the two facade decisions kept distinct, so that payment and submission rules use their complete prerequisites.
25. As a QS, I want one excision decision to affect NTT and SCC, so that both documents stay consistent.
26. As a QS, I want site formation and its Specification prerequisites assessed explicitly, so that foundations are not used as a substitute.
27. As a QS, I want RSE structural-submission requirements assessed explicitly, so that SCT6(3)(d) uses the correct treatment.
28. As a QS, I want design, execution, component scope and tender-stage timing expressed separately, so that different SCC responsibilities are not collapsed into one Boolean.
29. As a QS, I want railway EOT options derived from actual SCC8.304 adoption, so that there is no duplicate or guessed answer.
30. As a QS, I want BQ/SOR arrangement and provisional quantities separated from issue media, so that SCC11.301/11.302 are mutually consistent.
31. As a QS, I want stable Bill/Schedule records with number, exact description, type, purpose and trade links, so that safety pricing references select the actual item.
32. As a QS, I want non-consecutive Bill numbers preserved, so that the system cannot invent a continuous range.
33. As a QS, I want Bill body/Collection/Summary placement shown as a derived arrangement, so that a shared Bill is edited once.
34. As a QS, I want project-specific BQ/SOR media overrides, so that the standard layout is not falsely universalized.
35. As a QS, I want common submission requirements to retain multiple sources and appear once, so that identical PSE/general text is not duplicated.
36. As a QS, I want similar but different requirements retained, so that approximate deduplication does not remove a genuine obligation.
37. As a QS, I want adopted SCT5 rows to produce final letters and cross-references, so that deleted rows do not leave old references.
38. As a QS, I want inactive SCT10 to exclude its internal reference, so that there is no orphan instruction.
39. As a QS, I want actual Project Architect salutation and separate inspection addresses, so that correct text reaches the separate blanks.
40. As a QS, I want date order and non-negative photocopy rates checked, so that invalid amounts and dates are marked before use.
41. As a QS, I want tree identity checked before counting, so that blank or duplicate records cannot become multiple trees.
42. As a QS, I want single-tree and multiple-tree wording handled differently, so that several numbers are not inserted into a single-tree blank.
43. As a QS, I want excavation permit, SSF and show/exhibition-flat requirements explicitly represented, so that residential or foundation scope cannot substitute for them.
44. As a QS, I want warranty scope and Tin Shui Wai facts shared, so that all warranty locations use the same adopted decision.
45. As a QS, I want separate Section records and site separation facts, so that demolition alone cannot incorrectly trigger SCC8.303.
46. As a QS, I want special-clause adoption and consultation guidance without an approval gate, so that the business decision remains mine.
47. As a QS, I want partnership and both JV forms distinguished, so that SCC22.308/22.309 do not share an incorrect generic JV result.
48. As a QS, I want adopted definition clauses to update SCC3.302 references, so that removed definitions are not still cited.
49. As a QS, I want unresolved items classified by missing value, linkage, source or rule, so that I can resolve the correct problem.
50. As a QS, I want a target-level action and exact-text override, so that a source ambiguity has an actionable human path.
51. As a QS, I want edition and external-document warnings retained beside overrides, so that a one-draft decision is not promoted into a general rule.
52. As a QS, I want three drafts generated from one saved effective-input snapshot, so that edits are applied synchronously.
53. As a QS, I want a generated snapshot to remain frozen, so that later edits cannot change what I previously generated.
54. As a QS, I want stale drafts clearly marked but viewable, so that old content is not presented as current.
55. As a QS, I want full source text and unrelated wording preserved, so that long-template tail clauses are not lost.
56. As a QS, I want real Word/PDF downloads and content validation, so that a successful transport response is not mistaken for an actual document.
57. As a QS, I want NTT/SCT/SCC to retain the uploaded template's fonts, paragraph formatting, tables, numbering, headers, footers and page settings, so that generated contracts follow the required working format.
58. As a QS, I want adopted values filled at their actual template locations and only the corresponding guidance and irrelevant content removed, so that generation edits the template instead of rebuilding its prose.
59. As a QS, I want PDF preview and export to derive from the same generated Word artifact, so that all displayed and downloaded formats represent the same contract.
60. As a QS, I want generated template bytes, applied edits and artifact identity bound to the frozen input/source snapshot, so that later source replacement cannot silently change a previous draft.
61. As a QS, I want an unsafe source-format mapping or unavailable converter reported explicitly, so that a plain-text reconstruction is not presented as a template-faithful output.
62. As a QS, I want supported generated-content edits to retain the template formatting, so that changing words does not erase the document design.
63. As a QS, I want affected table-of-content and page-reference fields handled explicitly, so that deleted clauses do not leave falsely certified references.
64. As a QS, I want actual template and generated page images compared, so that exported format fidelity is verified visually as well as structurally.

## Implementation Decisions

- Retain the existing authenticated/project-scoped drafting API surface and binary-client checks. Add versioned catalog and clause-plan endpoints; preserve current upload, replace, delete, trace, document-edit, PDF and DOCX capabilities.
- The rule catalog is the canonical source for groups, labels, field types, options, conditions, optionality and source targets. The model does not create or remove required business questions. No production dependency may fall back to prototype seed data.
- Retain existing eight storage keys and their adopted values through an explicit compatibility mapping. Preserve legacy records as history; exclude Tender A/B from this competition workflow. Do not retain two independent current values for a shared fact.
- The input catalog groups the original agreed questions plus four compact supplemental areas: works/special requirements; warranties; Sections/special time clauses; entity form. Enrich existing design, Bills and submission groups instead of adding another screen for each clause.
- Values use canonical Boolean/choice values and structured JSON records. Dates, explicit empty lists, unknowns and false remain distinct. UI translations apply to labels/options only.
- Preserve one adopted value, manual origin, source-linked candidates, review status and evidence revision per input. Newly extracted values do not overwrite manual edits. New evidence with no known relation is reported as unassessed rather than blindly invalidating every field.
- Condition evaluation and validation must feed both UI status and the effective generation snapshot. Hidden values are cached, excluded from the current branch and restored on switching back. Invalid values and unadopted model suggestions remain unresolved.
- Persisted edits invalidate the current-generation identity without deleting its content. Persist snapshot hash, rule version, template/source identities and unresolved items. A draft is current only for its recorded snapshot.
- Use a single server rule/plan layer for derived decisions and actions. Action vocabulary distinguishes retain, amend/fill, delete specified text, Not used with numbering retained, not adopted, and pending. Every action records its source location and driving inputs.
- `targetOverrides` is one shared collection of action ID, adopted action and exact text. Bind each adopted target to its actual template source revision, retain that binding on untouched entries, and review affected targets individually after replacement. Re-adopting one target must not review the others. Validate target IDs, preserve human text and perform a source-anchor check before application. The override resolves this draft's action, not the general source rule or missing document warning.
- Bill/Schedule rows have stable IDs, exact number/description, actual pricing type, purposes, trade/scope links and issue placement. Four SCC safety references derive from selected purpose/type records, not duplicate number inputs or name guessing. Media and pricing scheme are independent.
- General/PSE requirements retain raw records and sources. Exact adopted text is output once; similar text remains separate. Cached inactive CON8/PSE requirements are excluded.
- SCT5 final letters derive from the adopted row set and custom procedure. Update SCT10 and technical-submission references from the verified final structure; when structure is unknown, emit a specific unresolved target. SCT10 Not used excludes its internal reference.
- SCC definitions update SCC3.302(1)(b) from actual adopted definitions. Railway A/B derives from the actual SCC8.304 decision; preserve complete design/execution/component/tender conditions.
- SCC11.301 and11.302 are mutually exclusive under the pricing scheme. 11.302 A/B uses its own provisional-quantity condition. Do not blindly adopt1.304+11.301 for every provisional-quantity answer.
- SCC8.303 requires relevant building+demolition work, respective Sections and separate/detached sites. SCC11.304 has its own building/other-work Sections conditions. Adopted8.301 drives8.308 numbering; do not derive police training solely from demolition.
- Warranty scope distinguishes existence, required warranty and Specification-backed Other. SCC7.307's play-equipment/IASM condition stays an AND; one-only cases require a specific business treatment. SCC22.308 and22.309 use different entity-form sets.
- Validate dates, rates, tree identities and standard Bill labels conservatively. Keep invalid manual entries available for correction; never silently repair business facts or reinterpret unknown as No.
- Template identity/anchor validation precedes source-position edits. Audited P numbers do not authorize editing an arbitrary uploaded revision at that numeric position. Full templates must be processed without prefix truncation; unrecognized source structures remain explicitly unresolved.
- Keep the working English source text and English output independent of UI locale. Generate all three files from one immutable effective snapshot. Apply verified deterministic edits and adopted target overrides directly to full source text; do not ask an LLM to echo or rewrite already resolved contract wording. Use the LLM for evidence-linked candidates and optional suggestions requiring adoption. Preserve full unrelated content; empty or summary content cannot count as a generated contract.
- Unresolved descriptions belong to a separate review manifest, not internal field names or implementation instructions inserted into formal contract wording. Business template blanks may remain unresolved in a draft.
- Implementation occurs in new isolated checkouts based on snapshots of the current workspace; existing drafting, vetting, source data and running review instances are preserved.
- The uploaded editable DOCX is the format authority. Edit a copied package through verified paragraph/run/cell locations and source-bound operations. Preserve untouched styles, numbering, section geometry, tables, relationships, bookmarks, fields, headers/footers and opaque package parts. Fill values within existing formatted runs; clear guidance cells without changing unrelated table grids. Remove inapplicable rows/paragraphs only within the verified adopted scope. Do not derive formatted edits by blindly replacing the final flattened body.
- Freeze the generated DOCX artifact and its digest/source identity with the shared generation snapshot. PDF preview/export converts those exact generated bytes through an explicitly available document-layout engine with isolated temporary/profile state. No added cover, generic font/style preset, contract metadata lines without a verified original slot, or plain-text PDF/Word fallback is allowed. Existing contract identity remains project/snapshot metadata when the source has no verified insertion slot.
- The content-edit capability must use the same source-preserving artifact path. Unsupported structural changes or ambiguous locations return a specific error without losing the previous artifact; they do not silently rebuild the document. Rule/version changes invalidate older rebuilt artifacts and require regeneration.
- Preserve field instructions and bookmarks unless an adopted edit changes their scope. Assess affected TOC/page references after deletions; use a verified refresh path or record the specific unresolved/deferred field state. Do not claim caches refreshed merely because a converter returned bytes. Unknown business values still do not create an approval gate or invented answer.

## Testing Decisions

The principal seam is the real project-scoped API through persistence, rules, generation snapshot and binary export, followed by the in-app browser workflow. Model doubles prove workflow determinism and cannot prove live extraction quality. Tests check externally visible values, actions, content and identities instead of mirroring private methods.

- Extend the existing H2/independent-storage/MockMvc workflow tests and binary MIME/content checks. Run focused rule tests for source truth tables, then full project workflow tests.
- New-project GET returns editable empty required catalog inputs without model extraction; old stored values migrate without overwrite or duplicate current facts.
- PUT→GET, refresh, re-extraction, source changes and switching projects preserve manual edits, candidates and field-level review. Editing restrictions does not review dates; editing one date does not review the other.
- Parent Yes/No/unknown, hidden-cache restoration, explicit None and effective-value exclusion are checked across saving and generation.
- Cover foundation AND39 with false+unknown and inclusive39; NSC/BSSSC; media×procedure; domestic×CON8; separate facade decisions; negative rates/zero; reverse dates; tree0/1/multiple/blank/duplicate.
- Cover excision cross-document reuse, site formation and RSE treatments, design/execution/component/time differences, actual8.304→railway A/B, BQ/SOR×provisional mutually consistent alternatives, purpose-linked safety references, non-consecutive Bills and exact requirement deduplication.
- Verify final SCT row-letter references and inactive-parent exclusion; actual definition collection; Section/weather/8.301 numbering; warranty-region/scope; different partnership/JV decisions.
- Verify a valid target override actually changes the located source, an invalid/unlocatable target cannot silently succeed, and rule/source warnings remain accurate.
- Full-tail/structural coverage, unrelated text, English output, one shared snapshot, stale-after-edit, actual POI/PDFBox-readable export bytes and no internal guidance in formal content are required.
- Browser verification uses real controls and real API requests for upload, missing-value editing, branch changes, save, candidate inspection, generation and download. Three UI languages preserve canonical values and raw text; the edit page has no embedded document preview.
- Preserve all failed observations and their corrected rechecks. Compilation and fixtures alone do not demonstrate business semantic quality, live provider quality or exported visual layout.
- Template-format acceptance uses the actual three competition DOCX files at the public generation/export seam. Compare preserve-only ZIP parts and paragraph/run properties, table grids, numbering, header/footer and section geometry; test split-run fills, target deletion, guidance cells, full-clause replacement and source mismatch. Render the original and final documents with the same available layout engine, inspect every final page and all distinct original page patterns, and distinguish intended pagination changes from format drift. Verify PDF preview/export and Word bytes share the recorded artifact identity, including supported body edits and stale-source rejection. Missing converters and unsafe structured edits must fail explicitly without a plain-text substitute.

## Out of Scope

- Rebuilding vetting/advice or changing their accepted behavior.
- Importing the full54-group internal catalog, prototype layout switcher, mock API, simulated candidates or fake exports into production.
- Drafting the excluded SCT/SCC appendix bodies or reintroducing Tender A/B.
- Inventing business values from document names, arbitrary thresholds, incomplete clauses or model confidence.
- Declaring unverified edition mappings, NSC template substitutions, appendix letters or external OVT dependencies universally correct.
- Adding new approval gates or treating one-draft adoption as permanent factual certification.
- Publishing code, deploying to competition hardware or overwriting other workspace work without separately authorized scope.
- Adding a new document design, cover page or generic formatting instead of preserving the supplied templates.

## Further Notes

Authority: user-confirmed decisions govern; Wang Li meeting and Comments carry business authority unless contradicted by written facts. The competition brief requires necessary standard-document questions even when emails do not answer them. The 2026-10-05 source audit matrices and coverage-gaps list define the source positions for this work.

Open source-rule questions are implemented as explicit target-level review paths. They are limited to: SCT6(1)(c)'s negative subitem layout and actual PRE.B9.260(4) applicability; SCT6(2)(b)'s independent negative treatment when domestic blocks are included but facade permission is not; one-only play/IASM; simultaneous fire-services categories needing distinct scope evidence; generic SCC non-adoption layout; verified edition/NSC/appendix mappings and external OVT coordination. Special SOR or custom issue placement and non-standard Preliminaries/Preambles numbering also need exact project-source treatment. They are not silently classified as completed automatic rules.

These open branches do not include already verified ordinary behavior: the complete negative SCT5 site-formation row; SCT6(2) Not used when domestic blocks are absent; SCT4/10 choices; SCT6(3)(c)'s specified payment phrase deletion; ordinary BSSSC name reuse; standard Bill1/2 body/Collection/Summary distribution; non-consecutive ordinary BQ numbers; and correctly typed safety Schedule references. Unanswered inputs, unadopted candidates, changed-source review and failed source anchors remain distinct runtime states, rather than new business questions. The scope stays at21 main groups and79 visible input fields.

Final acceptance includes explicit red/green regressions for valid same-number BQ/SOR records and stale-draft API permissions. Earlier implementation used both test-first cycles and implementation-followed-by-acceptance; it is not represented as universal TDD. A stale draft stays readable, with its previous content retained, but must be regenerated before content changes, PDF preview or export as the current draft.

The configured tracker is the local task graph `internal implementation record (not published)`, with closure evidence under `docs/specs/verification/` and workflow policy in `docs/agents/issue-tracker.md`. Remote publication is not presumed. The to-spec format is retained locally. Implementation is recorded; native IAB download acceptance is explicitly pending before final tracker closure.

On 2026-10-06 the user explicitly made template-format preservation a hard requirement for generated NTT/SCT/SCC. This extends the previous text/content acceptance; the earlier rebuilt Word/PDF output does not meet it. Stories57–64 and their structural/render gates must pass before final delivery. The business catalog and previously agreed decision rules are unchanged by this extension.
