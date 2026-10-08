import copy
from pathlib import Path
import tempfile
import unittest
from token_windows import *
from fixed_vector_cache import cache_identity, store, lookup


class CharacterTokenizer:
    is_fast = True
    model_max_length = 8192
    def num_special_tokens_to_add(self, pair=False): return 4 if pair else 2
    def __call__(self, text, pair=None, **kwargs):
        if isinstance(text, list):
            rows = [self(x, **kwargs) if isinstance(x, str) else self(x[0],x[1],**kwargs) for x in text]
            return {"input_ids":[r["input_ids"] for r in rows],"attention_mask":[r["attention_mask"] for r in rows]}
        offsets = [(i,i+1) for i,c in enumerate(text) if not c.isspace()]
        ids = list(range(len(offsets)))
        if pair is not None: ids += list(range(len([c for c in pair if not c.isspace()])))
        if kwargs.get("add_special_tokens",True):ids += [0]*self.num_special_tokens_to_add(pair is not None)
        return {"input_ids":ids,"offset_mapping":offsets,"attention_mask":[1]*len(ids)}


def parent(text="abcdefghijklmnop", id_="parent1", role="tender", source_hash="source"):
    return {"id":id_,"content":text,"role":role,"sourceHash":source_hash,"documentId":"doc", "cells":["unaltered"],
            "parts":[{"text":text,"blockId":"body:1:paragraph","startOffset":10,"endOffset":10+utf16_length(text),"anchor":"body/1"}]}


class WindowTests(unittest.TestCase):
    def setUp(self): self.t=CharacterTokenizer();self.r=WindowRecipe(10,2);self.p=parent()
    def win(self,p=None,query=None): return windows(p or self.p,self.t,self.r,{"sha":"t"},query=query,embedding_strip=query is None)
    def test_complete_source_coverage(self): self.assertTrue(verify_window_coverage(self.p,self.win(),10)["zeroGapCoverage"])
    def test_special_tokens_in_budget(self): self.assertTrue(all(w["fullModelTokens"]<=10 and w["specialTokens"]==2 for w in self.win()))
    def test_full_query_pair_budget(self): self.assertTrue(all(w["fullModelTokens"]<=10 and w["queryTokens"]==3 and w["specialTokens"]==4 for w in self.win(query="abc")))
    def test_long_query_explicit_failure(self):
        with self.assertRaises(WindowError)as e:self.win(query="abcdefghijklmnop")
        self.assertEqual(e.exception.kind,"QUERY_TOKEN_BUDGET_EXHAUSTED")
    def test_astral_utf16(self):
        p=parent("汉字😀ab🙂cdefgh中文")
        rows=self.win(p);self.assertTrue(any(w["parentEndUtf16"]!=w["parentEndCodepoint"]for w in rows));verify_window_coverage(p,rows,10)
    def test_tables_multiline(self):
        p=parent("表格 | ✓ | English\nrow2 | 中文😀")
        verify_window_coverage(p,self.win(p),10)
    def test_overlapping_windows(self):
        rows=self.win();self.assertLess(rows[1]["parentStartCodepoint"],rows[0]["parentEndCodepoint"])
    def test_no_overlap_still_zero_gap(self):
        rows=windows(parent("abc   def   ghi   jkl"),self.t,WindowRecipe(7,0),{})
        verify_window_coverage(parent("abc   def   ghi   jkl"),rows,7)
    def test_whitespace_edges_accounted(self):
        p=parent("  abcdefghijklmnop \n ");verify_window_coverage(p,self.win(p),10)
    def test_empty_parent_rejected(self):
        with self.assertRaises(WindowError):self.win(parent("   "))
    def test_missing_parts_rejected(self):
        p=self.p.copy();p.pop("parts")
        with self.assertRaises(WindowError):self.win(p)
    def test_unsupported_content_envelope_rejected(self):
        p=copy.deepcopy(self.p);p["content"]="invented header "+p["content"]
        with self.assertRaises(WindowError):self.win(p)
    def test_java_trim_source_mapping(self):
        p=parent("  abcdefghijklmnop  ");p["content"]=p["content"].strip();rows=self.win(p)
        self.assertEqual(rows[0]["sourceSpans"][0]["sourceStartUtf16"],12)
    def test_wrong_part_utf16_rejected(self):
        p=parent("😀abc");p["parts"][0]["endOffset"]-=1
        with self.assertRaises(WindowError):self.win(p)
    def test_model_different_tokenizer_offsets_rejected(self):
        self.t.is_fast=False
        with self.assertRaises(WindowError):self.win()
    def test_window_count_guard_is_failure_not_omitted_tail(self):
        with self.assertRaises(WindowError):windows(self.p,self.t,WindowRecipe(10,2,1),{})
    def test_gap_tamper_rejected(self):
        rows=self.win();rows[1]["parentStartCodepoint"]=rows[0]["parentEndCodepoint"]+1
        with self.assertRaises(WindowError):verify_window_coverage(self.p,rows,10)
    def test_text_tamper_rejected(self):
        rows=self.win();rows[0]["content"]="bad"
        with self.assertRaises(WindowError):verify_window_coverage(self.p,rows,10)
    def test_utf16_tamper_rejected(self):
        rows=self.win();rows[0]["parentEndUtf16"]+=1
        with self.assertRaises(WindowError):verify_window_coverage(self.p,rows,10)
    def test_source_span_tamper_rejected(self):
        rows=self.win();rows[0]['sourceSpans'][0]['sourceStartUtf16']+=1
        with self.assertRaises(WindowError):verify_window_coverage(self.p,rows,10)
    def test_source_role_tamper_rejected(self):
        rows=self.win();rows[0]['role']='standard'
        with self.assertRaises(WindowError):verify_window_coverage(self.p,rows,10)
    def test_native_cells_payload_change_invalidates_window(self):
        rows=self.win();changed=copy.deepcopy(self.p);changed['cells']=['changed']
        with self.assertRaises(WindowError):verify_window_coverage(changed,rows,10)
    def test_document_parent_id_rebinding_preserves_stable_payload(self):
        changed=copy.deepcopy(self.p);changed['id']='new';changed['documentId']=123
        self.assertEqual(stable_parent_payload_sha(self.p),stable_parent_payload_sha(changed))
    def test_changed_source_or_role_changes_id(self):
        ids=lambda p:[w["id"]for w in self.win(p)]
        self.assertNotEqual(ids(self.p),ids(parent(source_hash="changed")))
        self.assertNotEqual(ids(self.p),ids(parent(role="standard")))
        # Recipe identity explicitly includes role through tokenizer/source tier
        # signatures; source content alone is never a cache identity.
        self.assertNotEqual(cache_identity_stub(self.win()[0]),cache_identity_stub(self.win(parent(role="standard"))[0]))
    def test_aggregation_returns_original_parent_cells_and_id(self):
        rows=self.win();lookup_={w["id"]:w for w in rows};result=aggregate_max([(rows[0]["id"],.2),(rows[-1]["id"],.8)],lookup_,{self.p["id"]:self.p})
        self.assertIs(result[0]["payload"],self.p);self.assertEqual(result[0]["id"],"parent1");self.assertEqual(result[0]["score"],.8)
    def test_unknown_window_rejected(self):
        with self.assertRaises(WindowError):aggregate_max([("unknown",1)],{}, {})
    def test_score_nonfinite_rejected(self):
        row=self.win()[0]
        with self.assertRaises(WindowError):aggregate_max([(row["id"],float("nan"))],{row["id"]:row},{self.p["id"]:self.p})
    def test_dense_expands_until_unique_parent_threshold(self):
        p2=parent(id_="parent2");a=self.win();b=self.win(p2);all_={w["id"]:w for w in a+b};points=[(a[0]["id"],.9),(a[1]["id"],.8),(b[0]["id"],.7)]
        result,receipt=dense_expand(lambda n:points[:n],all_,{"parent1":self.p,"parent2":p2},parent_limit=2,eligible_window_count=3,initial_limit=2)
        self.assertEqual(len(receipt["fetches"]),2);self.assertEqual(len(result),2);self.assertTrue(receipt["completeRequestedParentCount"])
    def test_dense_exhaustion_does_not_claim_requested_parent_count(self):
        a=self.win();result,receipt=dense_expand(lambda n:[(a[0]["id"],.9)],{a[0]["id"]:a[0]},{"parent1":self.p},parent_limit=2,eligible_window_count=1)
        self.assertTrue(receipt["exhausted"]);self.assertFalse(receipt["completeRequestedParentCount"])
    def test_real_boundary_disables_truncation(self):
        guard=FullInputTokenizerGuard(self.t,10);result=guard(["abc"],truncation=True,max_length=1);self.assertEqual(sum(result["attention_mask"][0]),5)
    def test_real_boundary_overflow_fails_before_forward(self):
        guard=FullInputTokenizerGuard(self.t,10)
        with self.assertRaises(WindowError)as e:guard(["abcdefghijkl"],truncation=True,max_length=10)
        self.assertEqual(e.exception.kind,"INFERENCE_TOKEN_BUDGET_EXCEEDED")


def cache_identity_stub(window):return(window["sourceHash"],window["role"],window["windowContentSha256"])


class FixedCacheTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.p=parent();self.w=windows(self.p,CharacterTokenizer(),WindowRecipe(10,2),{})[0]
        self.whitelist={"protocol":"fixed-source-vector-cache-whitelist-v1","sources":[{"sourceHash":"source","role":"tender","storageTier":"fixed_competition_material","rawSourceSha256":"a"*64}]}
        self.identity={"model":{"revision":"r"},"tokenizer":{"sha":"t"},"windowRecipe":{"cap":10},"preprocessing":{"strip":True},"runtimeDtype":"float32","vectorDimension":2}
    def tearDown(self):self.tmp.cleanup()
    def test_write_read_fixed_vector_parent_id_rebinding(self):
        store(self.root,self.w,[1,0],self.whitelist,self.identity);new=dict(self.w,parentId="new-project-parent",id="new-window")
        v,r=lookup(self.root,new,self.whitelist,self.identity);self.assertEqual(v,[1,0]);self.assertEqual(r["newProjectParentId"],"new-project-parent")
    def test_role_not_whitelisted_rejected(self):
        with self.assertRaises(WindowError):lookup(self.root,dict(self.w,role="standard"),self.whitelist,self.identity)
    def test_variable_material_not_whitelisted_rejected(self):
        with self.assertRaises(WindowError):lookup(self.root,self.w,{"protocol":"fixed-source-vector-cache-whitelist-v1","sources":[]},self.identity)
    def test_changed_text_source_recipe_are_different_cache_keys(self):
        old=digest(cache_identity(self.w,self.whitelist,self.identity))
        for name in ["tokenizer","windowRecipe","preprocessing","runtimeDtype"]:
            changed=self.identity|{name:"changed"};self.assertNotEqual(old,digest(cache_identity(self.w,self.whitelist,changed)))
    def test_cache_tamper_rejected(self):
        m=store(self.root,self.w,[1,0],self.whitelist,self.identity);(self.root/m["key"]/'vector.f32le').write_bytes(b'bad')
        with self.assertRaises(WindowError):lookup(self.root,self.w,self.whitelist,self.identity)
    def test_rerank_query_dependent_windows_cannot_use_cache(self):
        with self.assertRaises(WindowError):lookup(self.root,dict(self.w,operation="rerank_pair",querySha256="x"),self.whitelist,self.identity)


if __name__=="__main__":unittest.main()
