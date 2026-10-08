"""Pure lexical/BM25 tests. No neural model, tokenizer, index, HTTP or DB calls."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import sys
import unittest
from types import SimpleNamespace
from rank_bm25 import BM25Okapi

path = Path(__file__).with_name('server.py')
spec = importlib.util.spec_from_file_location('cjk_lexical_server_test', path)
s = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = s
spec.loader.exec_module(s)
OLD_PATTERN = r'[a-z0-9]+(?:[./()-][a-z0-9]+)*'

class CjkLexicalTests(unittest.TestCase):
    def rank_scores(self, query, rows):
        return BM25Okapi([s.tokens(row) for row in rows]).get_scores(s.tokens(query))

    def test_chinese_query_ranks_related_text_over_latin_noise(self):
        query = '防水封堵'
        self.assertTrue(s.tokens(query))
        scores = self.rank_scores(query, ['场地外围的备用电缆必须防水封堵', 'pump electrical table', 'unrelated schedule drawing', 'generic payment ledger'])
        self.assertGreater(scores[0], max(scores[1:]))

    def test_unseen_new_file_sentence_ranks_its_related_row(self):
        query = '临时围栏损坏后须即时更换'
        scores = self.rank_scores(query, ['仓库外围临时围栏损坏后须即时更换', 'lighting supply specification', 'contract title number', 'tree planting material'])
        self.assertGreater(scores[0], max(scores[1:]))

    def test_repetition_is_preserved_without_cross_punctuation_bigram(self):
        self.assertEqual(s.tokens('防水防水，防'), ['防', '防水', '水', '水防', '防', '防水', '水', '防'])
        self.assertEqual(s.tokens('防水防水，防'), s.tokens('防水防水，防'))

    def test_mixed_latin_clause_keeps_source_order(self):
        self.assertEqual(s.tokens('SCC 6/8 合約 GCT-12防水'), ['scc', '6/8', '合', '合約', '約', 'gct-12', '防', '防水', '水'])

    def test_all_historical_english_queries_match_original_tokens(self):
        artifact = Path(__file__).with_name('fixtures') / 'historical_english_queries_v1.json'
        raw = artifact.read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), '3c13b3aef9071d200d37103cc96cd01ac4d5b24b429da6c3bfefd1739e421642')
        calls = json.loads(raw)['calls']
        self.assertEqual(len(calls), 64)
        for row in calls:
            text = row['request']['query']
            with self.subTest(ordinal=row['ordinal']):
                self.assertEqual(s.tokens(text), re.findall(OLD_PATTERN, text.casefold()))
        for text in ['Clause PRE.B6/IV (12)-4.1 A/C', 'CAFÉ Straße İ TEST', 'F0FC e-mail 4b/7b English-only 123.45']:
            self.assertEqual(s.tokens(text), re.findall(OLD_PATTERN, text.casefold()))

    def test_single_character_query_is_preserved(self):
        self.assertEqual(s.tokens('水'), ['水'])
        self.assertEqual(s.tokens('，水！'), ['水'])

    def test_unicode_astral_han_kana_hangul_and_separator(self):
        first, second = chr(0x20000), chr(0x31350)
        self.assertEqual(s.tokens(first + second), [first, first + second, second])
        self.assertEqual(s.tokens(first + '😀' + second), [first, second])
        self.assertEqual(s.tokens('かな한글'), ['か', 'かな', 'な', 'な한', '한', '한글', '글'])

    def test_version_and_source_hash_invalidate_old_algorithm_identity(self):
        old_runtime = s._windows_runtime
        try:
            s._windows_runtime = SimpleNamespace(retrieval_recipe=lambda: {'pureTest': True})
            settings = s.retrieval_settings()
            self.assertEqual(settings['tokenizerAlgorithm'], 'casefold-latin-v1-cjk-unigram-bigram-source-order-v1')
            self.assertEqual(settings['latinTokenPattern'], OLD_PATTERN)
            self.assertNotEqual(settings['tokenPattern'], OLD_PATTERN)
            from window_runtime import WindowRuntime
            algorithms = WindowRuntime(s).algorithms()
            self.assertEqual(algorithms['server.py'], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertNotEqual(algorithms['server.py'], '06b30fe4cefc3482d7d4b54809da557db7592ecfe2da10fe429d273fec976ab4')
        finally:
            s._windows_runtime = old_runtime

if __name__ == '__main__':
    unittest.main(verbosity=2)
