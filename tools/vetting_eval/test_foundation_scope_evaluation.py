import tempfile
import unittest
from pathlib import Path

from foundation_scope_evaluation import (
    _canonical_checks, canonical_digest, checked, coverage, descriptor, gaps, merged,
    parent_coverage, sha_bytes, utf16len, utf16slice, validate_target, window_coverage,
)


def target(text="a😀bc",start=0,block="body:1:paragraph"):
    return {"id":"native-observation","evaluationOnly":True,"documentId":17,
            "businessRole":"tender","currentDerivedSourceHash":"current-revision",
            "requiredParts":[{"blockId":block,"startOffsetUtf16":start,
                              "endOffsetUtf16":start+utf16len(text),"exactText":text,
                              "textUtf8Sha256":sha_bytes(text.encode("utf-8"))}]}


def parent(text="a😀bc",start=0,parent_id="parent"):
    return {"id":parent_id,"documentId":"17","role":"tender","sourceHash":"current-revision",
            "content":text,"parts":[{"blockId":"body:1:paragraph","startOffset":start,
                                     "endOffset":start+utf16len(text),"text":text}]}


class NativeRangeGuards(unittest.TestCase):
    def test_signature_structured_object_canonical_hash(self):
        self.assertEqual(canonical_digest({"version":3,"runtime":{"dtype":"float32"}}),canonical_digest({"runtime":{"dtype":"float32"},"version":3}))
        self.assertNotEqual(canonical_digest({"version":3}),canonical_digest({"version":2}))

    def test_utf16_surrogate_boundaries(self):
        self.assertEqual(utf16len("a😀bc"),5)
        self.assertEqual(utf16slice("a😀bc",1,3),"😀")
        with self.assertRaises(UnicodeDecodeError):utf16slice("a😀bc",1,2)

    def test_utf16_bounds_and_booleans(self):
        for start,end in [(-1,2),(0,7),(True,2),(1,False),(3,2)]:
            with self.assertRaises(ValueError):utf16slice("abc",start,end)

    def test_union_overlapping_windows_not_double_counted(self):
        self.assertEqual(merged([(0,2),(1,3),(3,5),(0,2)]),[[0,5]])
        self.assertEqual(gaps(0,8,[(0,3),(2,5),(7,9)]),[[5,7]])

    def test_boundary_split_exact_union(self):
        t=target();a=parent("a😀",0,"first");b=parent("bc",3,"second")
        parts={p["id"]:parent_coverage(t,p) for p in [a,b]}
        self.assertTrue(coverage(t,["first","second"],parts)["allRequiredRangesCovered"])
        self.assertEqual(coverage(t,["first"],parts)["ranges"][0]["missingUtf16"],[[3,5]])

    def test_equal_quote_wrong_source_revision_has_no_credit(self):
        t=target();p=parent();p["sourceHash"]="older-revision"
        self.assertEqual(parent_coverage(t,p),[[]])

    def test_equal_quote_wrong_source_document_has_no_credit(self):
        t=target();p=parent();p["documentId"]="18"
        self.assertEqual(parent_coverage(t,p),[[]])

    def test_equal_quote_wrong_role_has_no_credit(self):
        t=target();p=parent();p["role"]="standard"
        self.assertEqual(parent_coverage(t,p),[[]])

    def test_equal_quote_wrong_block_has_no_credit(self):
        t=target();p=parent();p["parts"][0]["blockId"]="body:2:paragraph"
        self.assertEqual(parent_coverage(t,p),[[]])

    def test_wrong_text_at_source_bound_offset_rejected(self):
        with self.assertRaises(ValueError):parent_coverage(target("abc"),parent("xbc"))

    def test_bad_native_part_offsets_rejected(self):
        p=parent();p["parts"][0]["endOffset"]=4
        with self.assertRaises(ValueError):parent_coverage(target(),p)

    def test_fixture_requires_sha_utf16_and_evaluation_boundary(self):
        for key,value in [("evaluationOnly",False),("currentDerivedSourceHash",None)]:
            t=target();t[key]=value
            with self.assertRaises(ValueError):validate_target(t)
        for key,value in [("textUtf8Sha256","0"*64),("endOffsetUtf16",4)]:
            t=target();t["requiredParts"][0][key]=value
            with self.assertRaises(ValueError):validate_target(t)

    def test_duplicate_fixture_ranges_rejected(self):
        t=target();t["requiredParts"]*=2
        with self.assertRaises(ValueError):validate_target(t)

    def test_duplicate_ranked_ids_rejected(self):
        with self.assertRaises(ValueError):coverage(target(),["a","a"],{})

    def test_missing_source_and_other_query_role_are_explicit(self):
        self.assertEqual(coverage(target(),[],{},corpus_source_present=False)["status"],"source_revision_not_in_corpus")
        self.assertEqual(coverage(target(),[],{},role="standard")["status"],"different_query_role_no_recall_expectation")

    def test_native_window_span_parent_and_sha_lineage(self):
        t=target();p=parent();w={"id":"window","parentId":"parent","documentId":"17",
             "role":"tender","sourceHash":"current-revision","sourceSpans":[{
             "blockId":"body:1:paragraph","sourceStartUtf16":0,"sourceEndUtf16":5,
             "textSha256":sha_bytes("a😀bc".encode("utf-8"))}]}
        self.assertTrue(window_coverage(t,[w],{"parent":p})["allRequiredRangesCovered"])
        w["sourceSpans"][0]["textSha256"]="wrong"
        with self.assertRaises(ValueError):window_coverage(t,[w],{"parent":p})

    def test_native_window_missing_parent_rejected(self):
        w={"id":"window","parentId":"unknown","documentId":"17","role":"tender","sourceHash":"current-revision"}
        with self.assertRaises(ValueError):window_coverage(target(),[w],{})

    def test_material_body_and_source_part_observations_must_match(self):
        p=parent();row=dict(p)
        row["selectedPartExtractionObservations"]=[{"partAnchor":None,"blockId":"body:1:paragraph","startOffset":0,"endOffset":5,"extractionSource":None}]
        _canonical_checks(row,p,material=True)
        row["content"]="a😀bc\nextra"
        with self.assertRaises(ValueError):_canonical_checks(row,p,material=True)

    def test_material_cannot_silently_drop_tail_part_observation(self):
        p=parent();row=dict(p);row["selectedPartExtractionObservations"]=[]
        with self.assertRaises(ValueError):_canonical_checks(row,p,material=True)

    def test_material_quality_adds_only_declared_unverified_scope(self):
        p=parent();p["sourceQuality"]={"textAccuracy":"unverified","needsReviewPages":[]}
        row={**p,"sourceQuality":{**p["sourceQuality"],"observationScope":"stored_parser_declarations_only; textAccuracy_unverified; legalCoverage_unknown"},
             "selectedPartExtractionObservations":[{"partAnchor":None,"blockId":"body:1:paragraph","startOffset":0,"endOffset":5,"extractionSource":None}]}
        _canonical_checks(row,p,material=True)
        row["sourceQuality"]["textAccuracy"]="verified"
        with self.assertRaises(ValueError):_canonical_checks(row,p,material=True)

    def test_nonnull_native_table_cells_remain_strict(self):
        p=parent();p["parts"][0]["table"]={"cells":["a","b"],"headerBlockId":None}
        row={**p,"parts":[{**p["parts"][0],"table":{"cells":["a","b"]}}]}
        _canonical_checks(row,p)
        row["parts"][0]["table"]["cells"]=["a"]
        with self.assertRaises(ValueError):_canonical_checks(row,p)

    def test_external_sha_and_size_rejected_before_json_consume(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/"sample.json";path.write_text('{"a":1}',encoding="utf-8")
            binding=descriptor(path);self.assertEqual(checked(binding),{"a":1})
            with self.assertRaises(ValueError):checked({**binding,"bytes":1})
            with self.assertRaises(ValueError):checked({**binding,"sha256":"0"*64})


if __name__=="__main__":unittest.main()
