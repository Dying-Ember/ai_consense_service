# Numbered source-list regression

`SIM11-source.txt` is the exact concatenation of source document 32's original core parts from the captured local medium4k trace, run `drafting-exam-20261008-101233-df46e254`. It retains the original simulated-data warnings, document qualifications, numbering and surrounding topics. The original core lengths are 2933 and 2240 UTF-16 code units. No expected-answer file or model output is used to create this source fixture.

SHA-256: `0fb63fffd67aeb42a8c60d8302e1dd6b31e1db31d65f7e406ea87be2158f9874`.

`DraftSourceContextOrderedListTest` checks dispatched original offsets, full logical-list coverage, context budget stops and boundaries between unrelated topics. Its captured-source test failed before the fix because the first core's request lacked the final numbered item. The tests run without model/API/database calls.

Malformed contiguous source blocks keep every entry and expose `Unit.validSequence=false` for gaps, repeated numbers or empty item bodies. They never become independent valid-looking sublists. The immediate field title and a nearby enclosing control title, such as a pending-alternatives heading, both remain in the original source scope.

Focused Maven invocation from this repository root: `mvn -B -ntp -DforkCount=0 -Dtest=DraftSourceContextOrderedListTest,DraftingContextRecallIntegrationTest test`.

`SIM11-alternate-source.txt` is the immutable original SIM11 text from run
`1917802c-34e0-4adc-a532-587787fffb3f` (`SHA-256:
900294239bd66baf6391850b1c06bc6991997a192c5052e532d977d7dc4a7f47`).
`SIM11-alternate-answers.json` preserves the two original model items and their
actual dispatched context offsets. Neither file contains an expected-answer key.
The nine-item list contains indented continuations at items 5 and 8, followed by
an independent paragraph denying warranties for other installations.

`DraftOrderedListBoundaryRegressionTest` replays those original answers through
the actual intake seam. It also checks that unrelated negative paragraphs do
not govern this list, while a pending/negated list qualification still does;
missing/changed items and cut source windows remain rejected. This fixes the
failure where an unrelated following negative warranty was absorbed as the
list's source qualification. Adjacent source declarations and parent status
headings remain attached using original offsets only.

Focused alternate-source and refusal-boundary invocation:
`mvn -B -ntp -DforkCount=0 -Dtest=DraftOrderedListBoundaryRegressionTest,DraftSourceContextOrderedListTest,DraftingEvidenceRecoveryBoundaryTest test`.

`alternate-design-candidates.json` preserves the same run's two accepted
`designResponsibilities` candidates, including their citations and source
hashes. `DraftDesignCandidatesTest` checks that the expressly complete four-row
source candidate dominates compatible earlier component facts, regardless of
candidate order. The merge returns that existing answer only; it never joins
facts into a new list, mutates candidates, or adopts an answer for the user.
Boolean, populated-scope, component and competing-complete-source disagreements
remain conflicts. Component and explicit work scope jointly identify a row;
several `other` rows with different stated scopes remain valid.
`legacy-design-candidates.json` freezes the original three scoped `other` rows
from the legacy run, without changing their accepted answer or source binding.
Duplicate scope identities and unscoped ambiguity do not permit dominance.
An unscoped fragment maps only to one complete row of the same component, and
requires a quote contained in the complete quote or an explicit current/whole
contract or project scope. Local directional, Annex/Block/Section/Phase scope
qualifiers must agree with the target row; another project and negated current
project scope remain conflicts. Missing values and unsettled full-list claims
also do not permit dominance.

`SIM11-novel-round1-source.docx` and `SIM11-novel-round1-source.txt` preserve
the wholly fictional first novel round 05 native source and its extracted
text. Their SHA-256 values are respectively
`14e368c7baf9e7d5de61ee9ac37513961d861aa54d45ea94df941228d1758afb`
and `e4b49a8566d3c81068f7c46007f353afe54c9786d1ce96b081cbc855a583cae7`.
`SIM11-novel-round1-answers.json` retains the original model items and
dispatched source offsets. The DOCX fixture checks paragraph and
continuation handling through native parsing; it is not an official
competition document. See `../captured-novel-round1/README.md` for the
captured workflow provenance and the distinction from a fresh model exam.
