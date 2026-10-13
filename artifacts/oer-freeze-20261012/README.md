# Frozen OER models and fixed05 vector database

The actual weights and static database are distributed as seven gzip parts in the [public service fork Release](https://github.com/Dying-Ember/ai_consense_service/releases/tag/oer-models-fixed05-20261013). Code changes are submitted to the colleague's separate service and frontend repositories through PRs. The current GitHub account cannot create a Release in the colleague's upstream repository.

Trusted manifest SHA256: `5d13e569422e87a7428792dd7be5795ce1a110c699e1bd82fdee67637ce6b3c6`.

The package contains 82 files, 10,376,931,695 unpacked bytes and 7,227,642,598 compressed bytes. All 68 original model files and four static fixed05 database/provenance files retain their frozen hashes. Ten separate model-card/license notices are added. See [distribution notices](notices/DISTRIBUTION-NOTICES.md) and the manifest's pinned official model-card URLs. The Release is a development baseline; final source, business and H800 acceptance remains open.

The database is the fixed GCC/GCT/Specification Library standard-source copy: 19,371 parent records, 20,700 windows and 20,700 stored vector points. Metadata contains full source text and historical Windows paths. It is not an anonymized vector-only database. Active H2/business data, user activity, provider keys and service logs are excluded. Original PDFs, parsed descriptor closure and corpus/runtime plans are not in this public subset; source-file retrieval needs those authorized inputs separately.

## Download, verify and restore

From the independent service repository root, using Python 3.11 or newer:

```powershell
gh release download oer-models-fixed05-20261013 --repo Dying-Ember/ai_consense_service --dir D:\ConSense-public-download --pattern "manifest.json" --pattern "*.part*"
python tools/restore_frozen_artifacts.py --manifest D:\ConSense-public-download\manifest.json --manifest-sha256 5d13e569422e87a7428792dd7be5795ce1a110c699e1bd82fdee67637ce6b3c6 --parts-dir D:\ConSense-public-download
python tools/restore_frozen_artifacts.py --manifest D:\ConSense-public-download\manifest.json --manifest-sha256 5d13e569422e87a7428792dd7be5795ce1a110c699e1bd82fdee67637ce6b3c6 --parts-dir D:\ConSense-public-download --destination D:\ConSense-public-fixed05
```

The destination must be a new absolute path that does not exist. The tool validates every part, the assembled archive and every declared file before creating the destination. It rejects unsafe paths, links and unexpected members; it never deletes an existing directory. A failed write can leave a partial new destination, and only a successful restore writes `RESTORED.json`. Reserve space for 7.23 GB of downloaded parts, another 7.23 GB temporary assembled archive, and 10.38 GB unpacked destination. These may be on different volumes.

Restoration preserves the frozen bytes. It does not rewrite recorded source/model paths, load models, open databases or start services. `path-map.json` is empty for this minimal public subset; resolve recorded paths explicitly and publish a new runtime identity for another machine. The existing author-specific fixed-baseline preparation guard is retained in the code and is not a portable import command. Full original-source retrieval and browser/business deployment require separately provisioned inputs and validation.
