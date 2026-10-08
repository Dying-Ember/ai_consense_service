# Captured Bill and waterproofing harness regression

This fixture is a compact extraction of the real local Drafting runs of the user's
15-document missing-evidence exam. It contains only two uploaded correspondence
sources (SIM04 and SIM11), six original model candidate items, their actual request
offsets, and run/part/attempt/item provenance. It does not contain the examiner's
reference-answer files, catalogue answers, templates, or a replacement prompt.

The source `originalSource` strings are the concatenation, in original part order,
of the saved extraction report's core `sourceText`. UTF-8 source SHA-256 and the
uploaded DOCX byte SHA-256 are recorded separately. Their meanings differ: the
application's `sourceHash` identifies parsed evidence, and is not the DOCX byte
hash. Context `sourceText` is deliberately reconstructed from the captured
`sourceStart`/`sourceEnd` offsets to avoid redundant fixture copies.

Captured runs:

- Baseline project `drafting-exam-20261008-095654-9ecc884f`, run
  `e93fc9a3-cebc-4557-b2a9-8c7d777bde8f`: the complete Bill candidate cited its
  introduction instead of the actual table.
- Medium project `drafting-exam-20261008-101233-df46e254`: the successful Bill
  repair and four failed waterproofing candidates. Every candidate's exact run
  identity is in `fixture.json`.

The private original trace snapshots and original DOCX sources remain in the
separate ConSense workspace. They are not included in this service repository or
required for running the checked-in tests. The `docxRelativePath` fixture fields
are provenance labels from that workspace, not files to resolve here.

`DraftingCapturedOutputRegressionTest` calls the production `decide` intake seam
and the production `DraftSourceContext.primary` planner. Reflection is limited to
the existing private intake method. The tests do not implement a second
grounding algorithm and do not dispatch AI calls, use HTTP, or connect to any
database.

The expected complete waterproofing value is sliced from its actual contiguous
source unit; a shorter three-item derivative checks that completeness is driven
by source structure rather than hardcoding the exam's 18 items. No expected
answer is placed into a model request. The synthetic positive candidate with a
missing `7.` marker is derived by applying the exact observed typography error to
the complete source list. Unlike the actual model's 1–16 candidate, it retains
all source items.

Acceptance matrix:

| Case | Required observable result |
|---|---|
| Captured complete Bill, introduction quote | Accepted with an exact quote reanchored to the unique source table; original raw value retained |
| Captured complete Bill, exact table quote | Accepted without rewriting its evidence |
| Bill altered description, omitted row, unrelated quote | Rejected; no answer invention |
| Bill two identical tables in different logical scopes | Rejected; no arbitrary evidence selection |
| Captured waterproofing 1–16 or 12–18 | Rejected both in its old truncated context and in a complete context; no silent filling of missing items |
| Captured waterproofing array shape | Rejected for a field requiring text |
| Primary windows at the actual core boundary | Both carry the full original numbered unit if it fits the context budget |
| Complete waterproofing value and exact quote | Accepted with all original words, punctuation, and item order |
| Complete waterproofing value with the observed missing marker punctuation | Only original-source typography restored; raw model value retained |
| Full quote but incomplete/altered value | Rejected |
| Ambiguous unit, question citation, low confidence | Rejected |
| Complete three-item derivative | Accepted, establishing source-driven list length |

Run from this service repository root with Java 17 and Maven 3.9:

```shell
mvn -B -ntp -DforkCount=0 -Dtest=DraftingCapturedOutputRegressionTest,DraftingCapturedBillQualificationTest,DraftBillDescriptionSlotTest,DraftingBillCandidateMergeTest,DraftingEvidenceRecoveryBoundaryTest,DraftSourceContextOrderedListTest,DraftEvidenceQuotesTest test
```

This is deterministic intake/context regression, not a measurement of live-model
exam accuracy. The complete exam runner, offline grading scripts, 15 original
DOCX uploads and three 2a templates stay external in the ConSense workspace. The
real-model exam creates a new labelled project, never reuses/adopts user
candidates, and excludes reference-answer files from model inputs.
