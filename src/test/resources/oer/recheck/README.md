# Authored source recheck fixtures

These fixtures support `CanonicalRecheckAdviceRealSeamTest` with real Spring,
JPA, source-byte verification and a local HTTP test server. The registry,
retrieval and LLM responses are explicit test doubles.

The PDF and OOXML sources are authored synthetic documents. The PDF contains
visibility and crop test strings such as `ZeroAlpha`, `Hidden`, `Visible fi
cafe`, `OutCrop` and `Second page`. The DOCX contains authored text, a nested
table, an emoji, deleted/struck text and a header. No competition originals,
official questions, answers, API keys or private runtime responses are included.

The public projections were produced from these authored observations by the
frozen Python producers. The complete CAS objects and byte-count/SHA ledgers
are retained so the test can detect actual source and CAS byte changes,
including changes that preserve file length and modification time. Projection
JSON was copied without resealing. `fixture-provenance.json` records every
original file hash and the preparation/provenance identities.

Document IDs 42 and 43 belong only to the isolated in-memory test database.
They are not registration instructions or facts about the application database.
Semantic quality remains `unknown`; these tests make no OCR, retrieval-model,
legal-content, original-quality or browser business acceptance claim.
