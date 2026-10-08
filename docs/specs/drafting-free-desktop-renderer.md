# Optional free Desktop Editors PDF renderer

2026-10-07. This is an additive renderer adapter for the existing exact-DOCX conversion, source/result preview, revision-bound binding and PDF export interface. LibreOffice remains the default. Deployment requires an explicit operator choice; neither discovery nor fallback selects another engine.

The scoped Windows probe used the official ONLYOFFICE Desktop Editors 9.4.0 portable release, `converter/x2t.exe` version **9.4.0.129**, without GUI launch, global installation, font installation, paid flags or license files. The official archive was 598,575,987 bytes, with release digest `22ae48a7813954e5079bff1ea211c6583aba67f34f2d0dbcb2431d4a20e94dfc`. This establishes the tested binary's identity and observed behavior; it does not establish a general supported headless server product or unrestricted commercial redistribution rights. Review the [release](httplocal verification artifact (not published)

## Explicit setup

Provide the complete extracted runtime tree and a separately prepared immutable font cache. Use the bundled converter's scoped font-cache command, with a new output directory and explicitly chosen font directory:

```powershell
& 'local verification artifact (not published)
```

Verify `AllFonts.js` and nonempty `font_selection.bin`. The cache's `__fonts_files` paths must be absolute and continue to identify the selected font files. The application parses the two documented/generated array assignments as data and does not execute the JavaScript. This command is setup, not an automatic application download/install step. The historical [x2t testing instructions](httplocal verification artifact (not published)

Use deployment-local properties or command-line/environment equivalents, without changing repository defaults:

```yaml
consense:
  drafting:
    renderer-kind: desktop-x2t
    renderer-executable: local verification artifact (not published)
    renderer-asset-root: local verification artifact (not published)
    renderer-font-cache: local verification artifact (not published)
    renderer-work-root: local verification artifact (not published)
    renderer-timeout-ms: 120000
```

The executable must lie inside the asset root. The private work root must lie outside the immutable asset root and font cache. Use a Java runtime with ProcessHandle support. The verified 9.4 binary identifies itself on a no-argument probe and returns native parameter-error exit **88**; this exact identifying output/exit is accepted only for the version probe. Actual conversion must exit zero. Unknown engine kinds or missing assets/cache fail explicitly; bindings become pending when the current profile cannot be assessed. Old LibreOffice PDF geometry is never reused for this engine.

## Identity, bounded conversion and export

`DraftPdfConverter.convert/currentProfileHash` retain their interface. The shared external-process boundary drains capped diagnostics, enforces the deadline, handles interruption and terminates only the owned process tree. Factory limits remain 64 KiB diagnostics and 64 MiB delivered PDF. XML/ZIP, asset inventory and PDF graph bounds are also explicit.

The profile hashes the command, reported version, locale, adapter/marker policy, complete runtime file tree (DLL/XML/SDK resources included), complete cache tree and every font file referenced by the cache. Symlinks and missing/oversized assets fail; drift detected after conversion rejects the result. Hashing is streamed under the same deadline. Preflight plus uncached frozen-template conversion took roughly 7–10 seconds in the recorded six-template run on this host, including identity checks. HTTP cache/binding queries still re-assess the current identity; this implementation makes no constant-time profile claim.

The input is cloned/frozen and saved unchanged in a private work directory. Native saved/exported DOCX bytes and the original uploaded template remain immutable. A separate conversion-only copy may wrap an existing safe first visible direct text run of a unique complete application-generated `CS[0-9a-f]{28}` bookmark in an inert custom URI containing a fresh per-conversion nonce. Whitespace-only runs are skipped. Existing run properties/text are retained. No field, existing hyperlink, drawing/control run or unsupported structure is rewritten. Empty/protected/absent targets remain unavailable. A preexisting reserved main-document relationship conservatively leaves marker geometry unavailable and is recorded in the manifest. Every existing reserved URI, including header/footer or HYPERLINK-field actions without relationships, lacks the fresh nonce and cannot impersonate the owned marker. The source still converts and all reserved actions are stripped. The converter receives PDF format 513, private temporary/cache directories and no paid/license setting.

PDFBox reads only actual renderer-produced URI annotations with the exact current nonce and corresponding linked CS ID. It validates finite in-crop geometry and page rotation, chooses the earliest physical page/visual first run, and creates a local named XYZ destination. Only generated IDs actually registered from this source have old raw destinations replaced; unrelated NameTree and legacy destinations, including a native `CSHistorical` cross-paragraph bookmark, are preserved. The existing binding adapter exposes a **point near the first existing visible direct text run**, with actual physical page/crop/rotation dimensions. It does not certify a complete paragraph rectangle, exact clause highlight, or a Word text location. Unrecognized IDs, foreign nonces, invalid rectangles and unsupported targets receive no manufactured geometry. The adapter strips every reachable `consense-binding:` URI action, including annotation, open, hover and chained actions, before returning any PDF; legitimate unrelated links are retained.

The final postprocessed PDF is hashed and cached under the exact saved DOCX hash plus current profile. Preview and export return those same final bytes. The manifest records original DOCX, conversion-copy, raw renderer PDF and delivered PDF hashes, runtime identity, private work directory, stripped/resolved/unavailable markers and unverified field/font status. A new sanctioned body save produces a new revision and pending geometry until that revision has its own PDF. Stale preview/body writes retain the existing revision conflict behavior. There is no new full-DOCX upload/native save callback route.

## Observed acceptance boundary

Actual frozen NTT source/draft, SCT source/draft and SCC source/draft convert to **11/11, 27/22, 81/78** pages. Public source upload, three generated results and a newly saved NTT body revision also exercise this adapter. Those public generation fixtures manually adopt only two known boolean inputs; they do not certify all correspondence values, business choices or Bill-purpose distribution. Preview/export identity, original/source immutability and current revision/hash/profile geometry are tested separately from layout quality.

The selected 9.4 outputs have no observed evaluation watermark or page truncation, but the NTT opening continuation and extensive SCC TOC blank space remain unaccepted. A smaller page count does not establish better pagination. PDF field refresh and per-run glyph/font selection remain `UNVERIFIED`; no all-pages Word comparison was available. Existing protected TOC hyperlink targets can legitimately have no point. Native rich editing, source-evidence overlays and a future reconciled native callback are separate work; this opt-in renderer does not establish a deployed Community editor host.

Review evidence and immutable RED/GREEN receipts live outside the repository under `output/drafting-a-implementation-20261005/hardening-workspace-20261007/hw07/`. The earlier free engine pilot, paid evaluation limits and source-pagination failures remain in `drafting-document-engine-decision.md` and the external reports. No whole-ticket or goal closure follows from this adapter slice.
