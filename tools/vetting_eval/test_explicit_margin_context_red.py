"""Generic authored source relation expectations; no neural, labels or HTTP."""
import copy
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "copied-tools"))
from retrieval_source_units import SourceUnits
from structural_retrieval import StructuralRecipe, _catalog, compare
from token_windows import WindowRecipe, utf16_length


class CharacterTokenizer:
    is_fast = True
    model_max_length = 8192
    def num_special_tokens_to_add(self, pair=False): return 4 if pair else 2
    def __call__(self, text, pair=None, **options):
        if isinstance(text, list):
            rows = [self(x, **options) if isinstance(x, str) else self(x[0], x[1], **options) for x in text]
            return {"input_ids": [r["input_ids"] for r in rows], "attention_mask": [r["attention_mask"] for r in rows]}
        offsets = [(i, i + 1) for i, c in enumerate(text) if not c.isspace()]
        ids = list(range(len(offsets)))
        if pair is not None: ids += list(range(len([c for c in pair if not c.isspace()])))
        if options.get("add_special_tokens", True): ids += [0] * self.num_special_tokens_to_add(pair is not None)
        return {"input_ids": ids, "offset_mapping": offsets, "attention_mask": [1] * len(ids)}


def node(identity, text, block):
    return {"id": identity, "unitId": "unit:" + identity, "content": text, "documentId": "authored-source", "role": "tender", "sourceHash": "a" * 64, "metadataVersion": "authored-whole-source-v1", "segmentationVersion": "authored-boundaries-v1", "nativeTableMetadataVersion": "authored-pointers-v1", "sourceQualityMetadataVersion": "authored-unknown-v1", "sourceQualityHash": "b" * 64, "sourceQuality": {"parseStatus": "PARSED", "semanticQuality": "unknown", "qualifiersComplete": "unknown"}, "originalSourceSha256": "c" * 64, "sourceRevisionSha256": "c" * 64, "sourceSha256": "c" * 64, "projectionRevisionSha256": "d" * 64, "canonicalScope": "region", "adoption": {"unitId": "unit:" + identity, "referenceSha256": "e" * 64, "reviewStatus": "explicit_source_reviewed_selection", "humanConfirmed": False}, "relations": [], "assetRefs": [{"authoredImagePointer": identity}], "humanConfirmed": False, "parts": [{"text": text, "blockId": block, "anchor": block, "startOffset": 0, "endOffset": utf16_length(text), "authoredTablePointer": {"preserveUnknown": True}}]}


def corpus(kind="margin_note"):
    note_a = node("note-a", "See conditions", "page/1/sidebar-a")
    body_a = node("body-a", "Provide a drawing set. Only sealed copies are admissible.", "page/1/body-a")
    note_b = node("note-b", "See conditions", "page/1/sidebar-b")
    body_b = node("body-b", "Provide a cost ledger. The ledger shall include every item.", "page/1/body-b")
    for note, body in [(note_a, body_a), (note_b, body_b)]:
        relation = {"type": kind, "fromUnitId": note["unitId"], "toUnitId": body["unitId"], "fromBlockId": note["parts"][0]["blockId"], "toBlockId": body["parts"][0]["blockId"], "status": "explicit_source_reviewed", "referenceSha256": "e" * 64}
        note["relations"].append(copy.deepcopy(relation))
        body["relations"].append(copy.deepcopy(relation))
    return [note_a, body_a, note_b, body_b]


def context_for(parents, identity):
    units, bindings = _catalog(parents)
    binding = bindings[identity]
    return units[binding.get("contextId", binding["unitId"])]


class ExplicitMarginContextTests(unittest.TestCase):
    def test_note_seed_context_contains_bound_full_body_not_other_same_text_notes(self):
        for kind in ["margin_note", "sidebar"]:
            p = corpus(kind)
            original = copy.deepcopy(p)
            context = context_for(p, "note-a")
            with self.subTest(relationType=kind):
                self.assertIn(p[1]["content"], context["content"])
                self.assertNotIn(p[3]["content"], context["content"])
                occurrences = context["structuralUnit"]["originalPartOccurrences"]
                self.assertTrue(any(o["part"] == p[1]["parts"][0] for o in occurrences))
                self.assertEqual(p, original)

    def test_body_seed_context_contains_only_its_explicit_bound_note(self):
        p = corpus()
        context = context_for(p, "body-a")
        self.assertEqual(set(context["structuralUnit"]["requiredMemberIds"]), {"note-a", "body-a"})
        self.assertIn(p[0]["content"], context["content"])
        self.assertNotIn("note-b", context["structuralUnit"]["requiredMemberIds"])

    def test_same_pool_compare_distinguishes_notes_using_declared_source_context(self):
        p = corpus()
        captured = []
        def authored_adapter(pairs):
            captured.append(copy.deepcopy(pairs))
            return [0.1] * len(pairs)  # Test input wiring only; no asserted quality scores.
        result = compare("Which drawings are required?", [p[0], p[2]], p, CharacterTokenizer(), {"tokenizer": "authored-character"}, WindowRecipe(128, 8), StructuralRecipe("dual-structure-v1", 512, 512), authored_adapter)
        self.assertEqual([r["id"] for r in result["candidatePool"]], ["note-a", "note-b"])
        self.assertEqual([r["id"] for r in result["results"]], ["note-a", "note-b"])
        structured_passages = [passage for _, passage in captured[1]]
        self.assertTrue(any(p[1]["content"] in text for text in structured_passages))
        self.assertTrue(any(p[3]["content"] in text for text in structured_passages))
        self.assertGreater(len(set(structured_passages)), 1)
        self.assertFalse(result["qualityImprovementEstablished"])

    def test_no_declaration_does_not_follow_clause_words_or_another_unit_text(self):
        p = corpus()
        for row in p: row["relations"] = []
        p[0]["content"] = p[0]["parts"][0]["text"] = "See condition body-a"
        p[0]["parts"][0]["endOffset"] = utf16_length(p[0]["content"])
        context = context_for(p, "note-a")
        self.assertEqual(context["structuralUnit"]["requiredMemberIds"], ["note-a"])
        self.assertEqual(context["content"], p[0]["content"])

    def test_old_exact_unit_identity_still_does_not_certify_legal_qualifiers(self):
        p = corpus()
        original = copy.deepcopy(p)
        catalog = SourceUnits(p)
        observed = catalog._resolve_observed(p[0])
        resolved = catalog.resolve(p[0])
        self.assertEqual(observed["requiredMemberIds"], ["note-a"])
        self.assertEqual(observed["members"], [p[0]])
        self.assertEqual(observed["unitId"], resolved["unitId"])
        self.assertEqual(resolved["qualifiersComplete"], "unknown")
        self.assertFalse(resolved["semanticScopeVerified"])
        self.assertEqual(p, original)


if __name__ == "__main__": unittest.main(verbosity=2)
