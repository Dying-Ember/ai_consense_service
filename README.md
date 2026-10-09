# Service publication snapshot: configuration and verification

Source: 67ceb7081d42204aa17b12b149f52081e348da71  
Module: ai_consense_service  
This is a fresh source snapshot. Existing projects, uploaded files, database rows, actual model/runtime captures, old Git history, model caches, and secrets are absent.

The three runtime drafting registries and eleven Flyway migration files are retained. The skills seed is retained byte-for-byte as the generic definition. projects.json, vetting-files.json, and findings.json are empty arrays, so a new installation starts without demo projects, source rows, or findings. guidance-compiler-bindings.json differs only in its three originalSourceDocuments.*.sourcePath strings; all binding rules, source/text hashes, inventory hash, and other bytes are retained.

## Build and local checks

Use Maven and a reviewed JDK with ProcessHandle support (Java 9+ is required by formatted PDF process management; this snapshot was checked on JDK 17). The POM's compiler source/target remains Java 8.

Commands:

    mvn -Dtest=DraftBusinessRulesTest,DocxNativeLayoutGuardTest test
    mvn -DskipTests package

The selected invocation performed no real provider calls: DraftBusinessRulesTest reported 13 passing tests; DocxNativeLayoutGuardTest reported four skipped cases. The native guard suite is not claimed as passed. Eight tests that require excluded external fixtures, including the transitive VettingStrictHybridTest helper users, are preserved under tests/external-fixtures/java with their original package paths. See tests/external-fixtures/README.md. They are outside Maven's default test source root. Default full-suite acceptance has not been rerun for this publication snapshot; no old acceptance result is presented as new deployment acceptance.

Compiled output under target/ is ignored and must not be committed. Build/test receipts are stored outside the publication tree.

## Configuration

The application profile is selected through SPRING_PROFILES_ACTIVE. H2 is a development profile; supply a new empty database or a separate deployment database. Existing local .db files are excluded.

The export adds these environment placeholders:

| Name | Meaning |
| --- | --- |
| CONSENSE_STORAGE_ROOT | Writable upload/project/export storage directory; generic relative fallback only |
| CONSENSE_ALLOWED_ORIGINS | Allowed frontend origins; generic localhost example fallback |
| CONSENSE_LLM_BASE_URL | Generic LLM provider endpoint; empty default |
| CONSENSE_LLM_ENABLED | Optional provider enablement; source default retained |
| CONSENSE_MINIMAX_BASE_URL | MiniMax provider endpoint; empty default |
| CONSENSE_MINIMAX_ENABLED | Optional provider enablement; source default retained |
| CONSENSE_OCR_BASE_URL | OCR endpoint; empty default |
| CONSENSE_OCR_ENABLED | Optional OCR enablement; source default retained |
| CONSENSE_VECTOR_BASE_URL | Vector service endpoint; empty default |
| CONSENSE_VECTOR_API_KEY | Vector service credential; empty default |
| CONSENSE_RETRIEVAL_URL | Hybrid retrieval endpoint for the local vetting profile; empty default |
| CONSENSE_H2_JDBC_URL | Development H2 JDBC connection |
| CONSENSE_VETTING_LOCAL_JDBC_URL | Local vetting profile H2 JDBC connection |

Existing placeholders remain:

| Name | Meaning |
| --- | --- |
| MYSQL_HOST / MYSQL_PORT / MYSQL_DATABASE / MYSQL_USER | Deployment database connection |
| MYSQL_PASSWORD | Database credential, default empty |
| MiniMaxCN_Token_Plan_API_Key | MiniMax credential, default empty |
| SPRING_PROFILES_ACTIVE | Profile selection |

No actual credential belongs in a committed example file. Provision secret values through the deployment secret store or process environment. Configure provider, model IDs, timeouts, token budgets, retrieval contract fields, and vector collection through the matching consense.* Spring properties.

For a source-only local start without optional services, set CONSENSE_LLM_ENABLED=false, CONSENSE_MINIMAX_ENABLED=false and CONSENSE_OCR_ENABLED=false in that process. This does not make real model/OCR/retrieval functionality available; configure the required service endpoints before using those operations.

For PDF rendering, supply consense.drafting.renderer-kind, renderer-executable, renderer-asset-root, renderer-font-cache, renderer-work-root and renderer-timeout-ms. The x2t route requires read-only immutable assets, a pregenerated AllFonts.js and nonempty font_selection.bin with its referenced fonts present, and a separate writable render work directory. The LibreOffice route requires headless soffice, fonts and a separate writable work root. Report font configuration is consense.report.font-path. The rendering binaries/fonts are not bundled with this snapshot.

Original NTT/SCT/SCC documents and correspondence must be uploaded through the application; a source snapshot cannot recreate an existing project or previously verified layout environment.

## Optional Python OCR/retrieval tools

tools/vetting_eval contains the implementation and pinned requirements. Torch/CUDA and model weights/cache are provisioned separately. samples.json retains embedding/reranker settings but has empty source_directory, documents, ocr_cases and queries. The server imports this model-config structure; supply deployment model/runtime settings and authorized external sources when required.

The local case inventories, historical queries, probe text, model/runtime traces and outputs are excluded. Evaluation scripts that use those inputs require external data and are not claimed to run against the empty publication snapshot.

## Verification boundary

The manifest records every fixed-HEAD path as unchanged, sanitized, excluded, or preserved external-fixture test. No source Java implementation or POM changed. The source repository stayed at the reviewed pin with a clean worktree.

The export verification checks file hashes, three registry sourcePath replacements only, unchanged skills/input/native registries/migrations, empty demo seeds and samples inventories, excluded data/captures/secret documents, preserved external tests, and no credential-pattern matches in publication text or synthetic DOCX XML.

This verifies the source packaging boundary, compilation, 13 passing rule tests and four skipped native guard cases. It does not verify live deployment, provider capacity, real model output quality, OCR/retrieval quality, or final PDF layout.

## Optional Python tool paths

Some optional Python evaluation tools retain generic development-workspace, logging-module and source-manifest defaults. Configure the existing CONSENSE_WORKSPACE_ROOT and CLI workspace, log-module, log-root, tooling and source-manifest inputs for your deployment. Their referenced corpora, caches and historical runtime records are excluded from this repository. No Python evaluation logic or model settings were changed for publication.

## MiniMax relay testing and development

Use [the MiniMax relay handoff](docs/minimax-relay-handoff.md) to run this backend with `h2,minimax-relay`, a relay root URL and a privately supplied independent token. The frontend selects `minimax-cn`; no provider credential belongs in frontend code. The handoff also includes a non-streaming M3 code-review client and explains the single owner of HTTP 529 retries.

The [portable Drafting evaluation kit](tools/drafting_eval/README.md) contains 15 fictional round-five correspondence documents, a separate answer ledger, read-only completed-run capture and the unchanged strict scorer. Use MiniMax-M3 for real identification regression and code suggestions, then review patches and run local tests. Keep reference answers out of model inputs, and record actual run IDs and results rather than treating generated suggestions as executed tests.
