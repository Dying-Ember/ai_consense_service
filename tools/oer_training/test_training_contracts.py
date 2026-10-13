"""Real failure cases for source isolation, target closure and pool fairness."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

try:
    from .dataset import SCHEMA, official_path, validate_manifest
    from .evaluate import literal_score, pool_digest, retrieval_score
except ImportError:
    from dataset import SCHEMA, official_path, validate_manifest
    from evaluate import literal_score, pool_digest, retrieval_score


class TrainingDataBoundary(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        def file(name, data):
            (self.root / name).write_bytes(data)
            return {"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        self.source = {"sourceId": "gct", "sourceFamily": "gct-family", "editionFamily": "gct-edition",
                       "nearDuplicateGroup": "gct-near-duplicate", "partition": "train",
                       "exposure": "development_historically_exposed", "route": "competition_in_scope",
                       "original": file("source.pdf", b"actual original test bytes")}
        self.record = {"sampleId": "condition5", "sourceId": "gct", "task": "text",
                       "originalPhysicalPageNo": 2, "coverage": "complete_task_unit", "unknownRanges": [],
                       "image": file("crop.png", b"test image closure"),
                       "target": file("target.txt", b"5. Unless otherwise provided, the tender shall be in Hong Kong dollars."),
                       "imageTransform": {"kind": "original_crop", "originalNormalizedBbox": [0.1, 0.1, 0.9, 0.2]},
                       "sourceReview": {"method": "source_only", "actor": "Codex", "humanConfirmed": False,
                                        "artifact": file("review.json", b'{"sourceOnly":true}')}}
        self.manifest = {"schema": SCHEMA, "purpose": "compatibility_only",
                         "sources": [self.source], "samples": [self.record]}

    def tearDown(self):
        self.tmp.cleanup()

    def validate(self, value=None):
        path = self.root / "manifest.json"
        path.write_text(json.dumps(value or self.manifest), encoding="utf-8")
        return validate_manifest(path)

    def test_compatibility_retains_honest_scope_and_full_target(self):
        _, resolved, inventory = self.validate()
        self.assertFalse(inventory["qualityImprovementClaim"])
        self.assertEqual(inventory["blindTestSourceCount"], 0)
        self.assertEqual(inventory["humanConfirmedSampleCount"], 0)
        self.assertEqual(resolved[0]["targetText"], (self.root / "target.txt").read_text())

    def test_official_windows_paths_quarantined_on_any_os(self):
        self.assertTrue(official_path(r"D:\Ref\2e_questions and answers\answer.pdf"))
        self.assertTrue(official_path("/data/2d_Tender doc vetting comments/a.pdf"))
        self.assertFalse(official_path("/data/2b_Tender documents/gct.pdf"))
        self.source["route"] = "official_evaluation_only"
        with self.assertRaisesRegex(ValueError, "Official 2d/2e"):
            self.validate()

    def test_renamed_same_original_may_not_cross_partitions(self):
        duplicate = copy.deepcopy(self.source)
        duplicate.update(sourceId="renamed", partition="test", sourceFamily="new-family",
                         editionFamily="new-edition", nearDuplicateGroup="new-near")
        self.manifest["sources"].append(duplicate)
        with self.assertRaisesRegex(ValueError, "crosses partitions"):
            self.validate()

    def test_near_duplicate_independent_bytes_may_not_cross_partitions(self):
        duplicate = copy.deepcopy(self.source)
        data = b"different bytes, same near-duplicate"
        (self.root / "other.pdf").write_bytes(data)
        duplicate.update(sourceId="other", partition="dev", sourceFamily="other-family", editionFamily="other-edition",
                         original={"path": "other.pdf", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        self.manifest["sources"].append(duplicate)
        with self.assertRaisesRegex(ValueError, "crosses partitions"):
            self.validate()

    def test_partial_unknown_target_cannot_become_complete(self):
        self.record["unknownRanges"] = [{"start": 2, "end": 3}]
        with self.assertRaisesRegex(ValueError, "Incomplete/unknown"):
            self.validate()

    def test_modified_target_fails_closure(self):
        (self.root / "target.txt").write_bytes(b"altered target")
        with self.assertRaisesRegex(ValueError, "Artifact missing/size changed"):
            self.validate()

    def test_candidate_derived_target_rejected(self):
        self.record["sourceReview"]["method"] = "candidate_consensus"
        with self.assertRaisesRegex(ValueError, "source-first"):
            self.validate()

    def test_no_same_source_dev_masquerading_as_quality_training(self):
        self.manifest["purpose"] = "quality_training"
        with self.assertRaisesRegex(ValueError, "independent-family train and dev"):
            self.validate()

    def test_unknown_human_confirmation_is_not_true(self):
        self.record["sourceReview"]["humanConfirmed"] = "Codex reviewed"
        with self.assertRaisesRegex(ValueError, "literal true/false"):
            self.validate()

    def test_image_transform_cannot_hide_invalid_source_crop(self):
        for bbox in ([0, 0, 2, 1], [0.5, 0, 0.4, 1], [0, float("nan"), 1, 1], [False, 0, 1, 1]):
            self.record["imageTransform"]["originalNormalizedBbox"] = bbox
            with self.subTest(bbox=bbox), self.assertRaisesRegex(ValueError, "finite normalized"):
                self.validate()


class StageEvaluation(unittest.TestCase):
    def row(self):
        pool = ["a", "b", "c"]
        return {"queryId": "q1", "candidatePool": pool, "candidatePoolSha256": pool_digest(pool),
                "embeddingOrder": ["b", "c", "a"], "rerankOrder": ["a", "b", "c"],
                "sourceOnlyRelevanceGrades": {"a": 3, "b": 0, "c": 0},
                "independentlyAnnotatedRelevantCorpusCount": 2,
                "split": "dev", "exposure": "development_historically_exposed"}

    def test_pool_recall_and_rerank_gain_are_separate(self):
        score = retrieval_score([self.row()], k=1)[0]
        self.assertEqual(score["candidatePoolRelevantRecall"], 0.5)
        self.assertEqual(score["embedding"]["recallAtK"], 0)
        self.assertEqual(score["rerank"]["recallAtK"], 0.5)
        self.assertEqual(score["rerank"]["samePoolNdcgAtK"], 1)

    def test_rerank_cannot_add_gold_candidate(self):
        row = self.row()
        row["rerankOrder"].append("new_answer")
        with self.assertRaisesRegex(ValueError, "same unique complete candidate pool"):
            retrieval_score([row], k=1)

    def test_changed_frozen_pool_rejected(self):
        row = self.row()
        row["candidatePoolSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "identity changed"):
            retrieval_score([row], k=1)

    def test_missing_relevance_grade_rejected(self):
        row = self.row()
        del row["sourceOnlyRelevanceGrades"]["c"]
        with self.assertRaisesRegex(ValueError, "Every same-pool candidate"):
            retrieval_score([row], k=1)

    def test_literal_spelling_numbers_and_whitespace_remain_distinct(self):
        score = literal_score("not less than 20\nHK$M", "not less than 25 HKM")
        self.assertFalse(score["rawExact"])
        self.assertFalse(score["whitespaceOnlyExact"])
        self.assertFalse(score["digitSequencesExact"])
        self.assertGreater(score["characterErrorRate"], 0)


if __name__ == "__main__":
    unittest.main()
