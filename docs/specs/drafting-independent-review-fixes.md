# Drafting independent-review fixes

Date: 2026-10-06. User instruction: "按照独立审查报告修好".
Integration branch: feature/drafting-a-business-rules-20261005.
Baseline service: ded0b86b60c18fca1b966779936ebfdf1b5fca15.
Baseline web: ca332aa96a160bcc1ec24f6ab920c73adb2f8b7d.

Authoritative defect evidence:
local verification artifact (not published)
and its findings.json / primary-manifest.json / pure-reproduction-results.json.
Primary authority remains the competition brief, Comments, actual 2a templates and 2c correspondence.
This is a bounded correction of five reviewed findings, not a replacement catalog.

## Requirements

1. F1: At the public extraction seam, reject a nonblank candidate when its quotation explicitly contradicts its value or only requests the missing answer. In particular, original 2c CON8 "not used" cannot support true; a request for inspection dates cannot support an invented date; phone 2761 6161 cannot support 9999 9999. Check direct literal values against the quotation with legitimate typographic/date/phone normalization. For structured Bill identities bind number and description to the same source entry, rather than accepting individually occurring but swapped identities. Positive, sourced values remain editable suggestions. Do not infer unrelated classifications by generic keyword guessing, promise universal semantic validation, auto-adopt, or overwrite manual values. Reuse existing rejection/repair/diagnostic contracts where sufficient. Preserve raw failed attempts and bounded targeted repair.
2. F2: Explicitly conflicting complete subcontractor/trade sets, including [] versus nonempty, must remain competing candidates and be reported candidate_conflict / conflict at public project APIs. Do not silently union two complete alternatives. Preserve documented additive partial-source combinations where no complete-set contradiction exists, semantic option identity, manual adoption protection, and source provenance. The user chooses this draft's adopted value; do not assume newest wins.
3. F3: Explicit target delete and not_used for SCC20.303/.304 must work against the actual supplied SCC DOCX containing native OMML. Use verified full-clause structural scope, including contained paragraphs/table/formula nodes, rather than flattening or replacing formula runs. Delete and numbered Not used remain distinct. Unaffected clause text, formula bytes, package parts and formatting must remain intact; cross references follow adopted surviving clauses. Unsafe or unsupported mappings still report unresolved.
4. F4: SCC14.305(1) must recognize the original repeated singular Clause list at P1216 and replace that exact reference collection from the effective adopted specialist-clause set; remove the applicable instruction mark. Retain GCC20.2 and surrounding payment wording. Existing SCC20.301 plural-reference behavior remains valid. Verify this independently of negative formula-removal branches.
5. F5: Direct NTT-9-WTO not_used target decisions must use the same verified full-clause mapping as ordinary wtoGpaApplies=false, preserving "9. Not used". Both delete and not_used must follow their explicitly chosen semantics; do not reinterpret arbitrary range labels as whole clauses. Preserve source-version rejection and exact-wording target controls.
6. Regression: No change to the 21 groups/79 visible inputs, model options/providers, prompt scope, local project data, tri-language values, hidden-input cache, adoption lifecycle, frozen artifact/source identity, or unrelated Vetting/Advice. New rejected candidates stay manually editable, with explained existing diagnostics.
7. Verification must distinguish controlled-model HTTP tests, real provider extraction, DOCX structural/body validation and PDF visual checks. Preserve prior failed reports; do not rerun into or overwrite the user's existing project. Use a dedicated visibly test-only acceptance project on the same isolated runtime.

## Previously agreed acceptance seams

The existing drafting-a spec/tracker records project-scoped upload/extraction/variables/history/plan/generation/binary export, original DOCX through the public parser/source-rule compiler, and frontend observable controls. This user-authorized correction uses those same seams. Persistent regression tests must use these public interfaces, not reflection on private helpers from the disposable review probe. External model doubles are acceptable at the actual LlmClient boundary; in-memory test DB and original template fixtures are isolated.

Each implementer performs vertical RED -> minimal GREEN slices and saves immutable logs in its assigned output directory. Write source-based positive counterexamples alongside each defect regression. Run bounded relevant regressions before integration. Root performs integrated package/API/real-provider/source checks; independent standards and spec reviewers assess the pinned baseline diff before closure.

## Scope boundaries

No remote publication, no competition hardware deployment, no global configuration changes.
Existing unresolved business decisions remain unresolved; do not invent a generic non-adoption policy.
Do not claim the previous A09 native browser-download acceptance or universal Word visual quality from this correction.
