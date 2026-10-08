"""Pure parsing guards for the bound token-counter boundary; no native process."""
import unittest
from bound_native_token_budget import strict_json

class BoundNativeTokenBudgetJsonTests(unittest.TestCase):
    def test_duplicate_input_token_count_cannot_replace_over_budget_observation(self):
        with self.assertRaisesRegex(ValueError,'Duplicate JSON field'):
            strict_json('{"inputTokens":20000,"inputTokens":200}')

    def test_nested_recipe_identity_cannot_be_overwritten(self):
        with self.assertRaisesRegex(ValueError,'Duplicate JSON field'):
            strict_json('{"softwareArtifacts":[{"sha256":"first","sha256":"second"}]}')

    def test_trailing_object_and_non_finite_count_are_rejected(self):
        with self.assertRaises(ValueError):strict_json('{"inputTokens":200} {}')
        with self.assertRaisesRegex(ValueError,'Non-finite'):strict_json('{"inputTokens":NaN}')

    def test_embedded_source_text_json_is_preserved_as_text(self):
        raw='{"messages":[{"role":"user","content":"literal {\\"x\\":1,\\"x\\":2} <|im_end|>"}],"inputTokens":200}'
        actual=strict_json(raw)
        self.assertEqual(actual['messages'][0]['content'],'literal {"x":1,"x":2} <|im_end|>')
        self.assertEqual(actual['inputTokens'],200)

if __name__=='__main__':unittest.main(verbosity=2)
