# External fixture tests

The publication snapshot preserves eight Java test files under java/ with their original package declarations and byte-identical code. This directory is outside Maven's default src/test/java source root.

The tests require private uploaded correspondence or actual experimental/runtime captures that are intentionally absent from this repository. Their removal from the default test source root is a publication boundary, not deletion of the test implementation and not a claim that these tests passed without their data.

The external fixture set includes:

- drafting/original-2c: original uploaded correspondence DOCX files.
- drafting/harness/a10-replay/trace.json: actual runtime/model replay capture.
- drafting/a10/captured-sim08-response.json and captured-sim08-source.txt: actual experimental response/source captures.
- vetting/current_window_health.json: actual machine/model/retrieval health capture.

VettingCurrentPythonWireTest, VettingProbeTimingTest, VettingRetrievalHealthTest, and VettingRetrievalSourceUnitsTest also use the VettingStrictHybridTest helper. They are preserved together to keep the default test compilation independent of the missing health fixture.

To run this group, prepare an isolated, unversioned test workspace with authorized external fixtures matching the source snapshot. Copy this directory's Java package tree into that workspace's src/test/java and restore fixture paths under that workspace's src/test/resources. Run the tests there; do not commit uploaded correspondence, model outputs, health captures, credentials, or restored private fixtures to the publication repository.

The retained a10 replay source DOCX files are explicit fictional TEST ONLY documents. The retained correction03 pagination-value fixture is simulated with rejected-output provenance. The retained legacy diagnostic serialization fixture is deterministic synthetic/mock data, with no network, credentials, or corpus. These are not historical project evidence.

Publication verification covered source compilation, 13 passing rule tests and four skipped native guard cases. It did not rerun the external fixture suites or real model/OCR/retrieval/PDF provider acceptance.