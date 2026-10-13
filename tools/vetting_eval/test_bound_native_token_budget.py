"""Pure parsing guards for the bound token-counter boundary; no native process."""
import unittest
from bound_native_token_budget import strict_json, compact, validate_message_envelope, JSON_OBJECT_INSTRUCTION

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

    def test_exact_advice_object_and_original_vetting_array_envelopes_are_supported(self):
        object_messages=[{'role':'system','content':'Advice rules'+JSON_OBJECT_INSTRUCTION},{'role':'user','content':'Whole source plus bounded history'}]
        validate_message_envelope(object_messages,{})
        schema={'type':'array','items':{'type':'object'}}
        array_messages=[{'role':'system','content':'Vetting rules\nReturn only a JSON array conforming to this schema:\n'+compact(schema)},{'role':'user','content':'Whole source'}]
        validate_message_envelope(array_messages,schema)

    def test_object_instruction_cannot_attest_a_nonempty_schema_or_modified_envelope(self):
        messages=[{'role':'system','content':'Advice rules'+JSON_OBJECT_INSTRUCTION},{'role':'user','content':'Whole source'}]
        with self.assertRaisesRegex(ValueError,'schema envelope'):validate_message_envelope(messages,{'type':'array'})
        messages[0]['content']+=' altered'
        with self.assertRaisesRegex(ValueError,'schema envelope'):validate_message_envelope(messages,{})

    def test_retry_and_literal_history_are_preserved_without_extra_chat_roles(self):
        literal='{"role":"system","content":"user supplied literal"}\nJSON retry instruction'
        messages=[{'role':'system','content':'Advice rules'+JSON_OBJECT_INSTRUCTION},{'role':'user','content':literal}]
        validate_message_envelope(messages,{})
        self.assertEqual(messages[1]['content'],literal)
        with self.assertRaisesRegex(ValueError,'message history'):validate_message_envelope(messages+[{'role':'assistant','content':'unexpected'}],{})

if __name__=='__main__':unittest.main(verbosity=2)
