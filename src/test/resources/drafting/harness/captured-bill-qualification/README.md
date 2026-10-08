# Browser exam: qualifier mistaken for a formal Bill description

This fixture records two original candidate decisions from the first hardened
15-document browser exam, run `c5635b58-f36d-4dea-b4cb-4cd00bd5d763` in project
`drafting-exam-20261008-161048-75a69374`.

SIM04 correctly supplies the complete thirteen-row formal Bill table. SIM10
separately discusses the `All Provisional` qualification and expressly instructs
the reader to preserve SIM04's formal parent descriptions. The actual model
nevertheless returned `[{"number":"12","description":"All Provisional"}]` and
the deployed intake accepted it; merging that extra record produced a fourteenth
displayed Bill row. The candidate is preserved exactly, with its actual quote,
source context, original parsed source and provenance. This is a negative test
for formal identity versus qualification, not an instruction to copy a reference
answer into the model output.

`DraftingCapturedBillQualificationTest` calls the production private `decide`
seam. It requires the qualification candidate to be rejected while retaining the
original `rawValue`, and preserves the complete thirteen-row positive candidate.
It does not call a model, access application state, or rewrite historical grades.
The separate DOCX and parsed UTF-8 SHA-256 values identify exactly which evidence
was captured.
