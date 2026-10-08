import copy
import tempfile
import unittest
from pathlib import Path
from foundation_fixed_sources import build, fingerprint, ManifestError

class FixedSourceManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.fixed = self.root / "approved-template.docx"; self.fixed.write_bytes(b"fixed bytes")
        self.variable = self.root / "NTT.docx"; self.variable.write_bytes(b"different current project")
        self.spec = [{"referenceKey":"approved", "datasetRelativePath":self.fixed.name,
            "expectedRawSha256":fingerprint(self.fixed)["sha256"], "historicalDocumentId":"5"}]
        self.inventory = {"sources":[{"documentId":"5", "role":"standard", "fileKey":"NTT", "original":fingerprint(self.fixed)},
            {"documentId":"6", "role":"tender", "fileKey":"NTT", "original":fingerprint(self.variable)}]}

    def test_explicit_sha_allowlist_retains_roles_and_does_not_classify_by_filename(self):
        result = build(self.root, self.spec, self.inventory)
        self.assertEqual("standard", result["fixedSources"][0]["businessRole"])
        self.assertEqual("6", result["variableBaselineSources"][0]["historicalDocumentId"])
        self.assertEqual("tender", result["variableBaselineSources"][0]["businessRole"])
        self.assertIsNone(result["fixedSources"][0]["historicalParse"]["fidelityAccepted"])
        self.assertFalse(result["portableVectorBundleComplete"])

    def test_changed_bytes_invalidate_the_manifest(self):
        self.fixed.write_bytes(b"changed")
        with self.assertRaisesRegex(ManifestError, "approved spec"): build(self.root,self.spec,self.inventory)

    def test_wrong_historical_binding_is_rejected(self):
        spec = copy.deepcopy(self.spec);spec[0]["historicalDocumentId"]="6"
        with self.assertRaisesRegex(ManifestError,"binding differs"):build(self.root,spec,self.inventory)

    def test_duplicate_alias_or_document_is_rejected(self):
        duplicate = copy.deepcopy(self.spec);duplicate.append(copy.deepcopy(duplicate[0]));duplicate[1]["referenceKey"]="alias"
        with self.assertRaisesRegex(ManifestError,"Duplicate fixed"):build(self.root,duplicate,self.inventory)

    def test_path_escape_and_absolute_path_are_rejected(self):
        outside=self.root.parent/(self.root.name+"-outside.bin");outside.write_bytes(b"outside");self.addCleanup(outside.unlink)
        for path in (str(outside), "../"+outside.name):
            spec=copy.deepcopy(self.spec);spec[0]["datasetRelativePath"]=path
            with self.assertRaises(ManifestError):build(self.root,spec,self.inventory)

    def test_undefined_business_role_is_rejected_instead_of_reclassified(self):
        inv=copy.deepcopy(self.inventory);inv["sources"][0]["role"]="drafting-output"
        with self.assertRaisesRegex(ManifestError,"Unknown business role"):build(self.root,self.spec,inv)

if __name__=="__main__":unittest.main()
