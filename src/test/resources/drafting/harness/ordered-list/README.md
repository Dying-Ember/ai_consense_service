# Numbered source-list regression

`SIM11-source.txt` is the exact concatenation of source document 32's original core parts from the captured local medium4k trace, run `drafting-exam-20261008-101233-df46e254`. It retains the original simulated-data warnings, document qualifications, numbering and surrounding topics. The original core lengths are 2933 and 2240 UTF-16 code units. No expected-answer file or model output is used to create this source fixture.

SHA-256: `0fb63fffd67aeb42a8c60d8302e1dd6b31e1db31d65f7e406ea87be2158f9874`.

`DraftSourceContextOrderedListTest` checks dispatched original offsets, full logical-list coverage, context budget stops and boundaries between unrelated topics. Its captured-source test failed before the fix because the first core's request lacked the final numbered item. The tests run without model/API/database calls.

Malformed contiguous source blocks keep every entry and expose `Unit.validSequence=false` for gaps, repeated numbers or empty item bodies. They never become independent valid-looking sublists. The immediate field title and a nearby enclosing control title, such as a pending-alternatives heading, both remain in the original source scope.

Focused Maven invocation from this repository root: `mvn -B -ntp -DforkCount=0 -Dtest=DraftSourceContextOrderedListTest,DraftingContextRecallIntegrationTest test`.
