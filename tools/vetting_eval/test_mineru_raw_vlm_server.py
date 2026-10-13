"""Authored CUDA device/tensor doubles only: no actual model, CUDA allocation or network."""
import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch, Mock

from PIL import Image


class Device:
    def __init__(self, name): self.name, self.type = name, name.split(':')[0]
    def __str__(self): return self.name


class Tensor:
    def __init__(self, dtype='torch.float32', shape=(1, 3), device='cpu'):
        self.dtype, self.shape, self.device = dtype, shape, Device(device)
    def to(self, device):
        self.device = Device('cuda:3' if device == 'cuda' else device)
        return self


class Inputs(dict):
    def __getattr__(self, name): return self[name]
    def to(self, device):
        for value in self.values(): value.to(device)
        return self


class RawVlmCudaTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        path = Path(__file__).with_name('mineru_raw_vlm_server.py')
        spec = importlib.util.spec_from_file_location('authored_raw_vlm_cuda_'+self._testMethodName, path)
        self.server = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.server)
        self.server._config.update(snapshot='AUTHORED offline pinned snapshot', evidence=str(self.root), threads=4)
        self.torch = SimpleNamespace(float32='torch.float32',
            cuda=SimpleNamespace(is_available=lambda: True), set_num_threads=Mock(),
            is_tensor=lambda value: isinstance(value, Tensor), inference_mode=contextlib.nullcontext)
        self.modules = patch.dict(sys.modules, {'torch': self.torch})
        self.env = patch.dict(os.environ, {'CONSENSE_MODEL_DEVICE': 'cuda', 'CONSENSE_MODEL_DTYPE': 'float32'})
        self.modules.start(); self.env.start()
    def tearDown(self):
        self.env.stop(); self.modules.stop(); self.temp.cleanup()
    def body(self, **extra):
        image = Image.new('RGB', (8, 6), 'white'); output = io.BytesIO(); image.save(output, format='PNG')
        return {'model': self.server.MODEL, 'messages': [{'role': 'user', 'content': [
            {'type': 'text', 'text': 'AUTHORED layout request'},
            {'type': 'image_url', 'image_url': {'url': 'data:image/png;base64,'+base64.b64encode(output.getvalue()).decode()}}]}],
            'max_completion_tokens': 10, **extra}
    def processor(self, count=3):
        outer = self
        class Processor:
            tokenizer = SimpleNamespace(decode=lambda ids, **kwargs: '<layout>AUTHORED</layout>')
            def apply_chat_template(self, messages, **kwargs): return 'AUTHORED complete prompt'
            def __call__(self, **kwargs):
                outer.inputs = Inputs(input_ids=Tensor('torch.int64', (1, count)),
                    attention_mask=Tensor('torch.int64', (1, count)), pixel_values=Tensor(),
                    image_grid_thw=Tensor('torch.int64'))
                return outer.inputs
        return Processor()
    def model(self, dtype='torch.float32', final_device='cuda:3', count=2):
        outer = self
        class Model:
            generation_config = SimpleNamespace(eos_token_id=2)
            def __init__(self):
                self.params = [Tensor(dtype, device=final_device) for _ in range(count)]
                self.moves = []
            def parameters(self): return iter(self.params)
            def eval(self): return self
            def to(self, target): self.moves.append(target); return self
            def generate(self, **kwargs):
                outer.generation = kwargs
                return Generated()
        class Generated:
            def __getitem__(self, key): return self
            def tolist(self): return [101, 102, 2]
        return Model()
    def transformers(self, processor, model):
        return SimpleNamespace(AutoProcessor=SimpleNamespace(from_pretrained=Mock(return_value=processor)),
            Qwen2VLForConditionalGeneration=SimpleNamespace(from_pretrained=Mock(return_value=model)),
            LogitsProcessor=object, LogitsProcessorList=list)
    def test_startup_cuda_unavailable_fails_before_receipt_or_listener(self):
        self.torch.cuda.is_available = lambda: False
        with patch.object(sys, 'argv', ['raw', '--model-receipt', 'ABSENT-must-not-be-read.json', '--evidence', str(self.root)]):
            with self.assertRaisesRegex(RuntimeError, 'CUDA.*required'): self.server.main()
        self.assertIsNone(self.server._model); self.assertIsNone(self.server._processor)
    def test_startup_probe_error_is_explicit_and_cannot_publish_a_model(self):
        self.torch.cuda.is_available = Mock(side_effect=RuntimeError('AUTHORED CUDA driver error'))
        with self.assertRaisesRegex(RuntimeError, 'CUDA driver'): self.server._load()
        self.assertIsNone(self.server._model); self.assertIsNone(self.server._processor)
    def test_inherited_cpu_auto_or_non_fp32_is_rejected(self):
        for value in ('cpu', 'auto'):
            with self.subTest(device=value), patch.dict(os.environ, {'CONSENSE_MODEL_DEVICE': value}):
                with self.assertRaisesRegex(RuntimeError, 'requires.*cuda'): self.server._require_cuda()
        with patch.dict(os.environ, {'CONSENSE_MODEL_DTYPE': 'float16'}):
            with self.assertRaisesRegex(RuntimeError, 'float32'): self.server._require_cuda()
    def test_load_keeps_offline_pins_fp32_and_moves_model_to_cuda(self):
        model = self.model(); processor = self.processor(); transformers = self.transformers(processor, model)
        with patch.dict(sys.modules, {'transformers': transformers}): self.server._load()
        self.assertEqual(['cuda'], model.moves); self.assertIs(self.server._model, model)
        self.assertIs(self.server._processor, processor)
        kwargs = transformers.Qwen2VLForConditionalGeneration.from_pretrained.call_args.kwargs
        self.assertTrue(kwargs['local_files_only']); self.assertEqual('torch.float32', kwargs['torch_dtype'])
        self.assertEqual('eager', kwargs['attn_implementation'])
        self.assertEqual('cuda:3', self.server.health()['device']); self.assertEqual('float32', self.server.health()['dtype'])
    def test_wrong_device_precision_empty_or_mixed_parameters_are_never_published(self):
        models = [self.model(final_device='cpu'), self.model(dtype='torch.float16'), self.model(count=0), self.model()]
        models[-1].params[-1].device = Device('cpu')
        for model in models:
            with self.subTest(parameters=len(model.params)):
                self.server._model = self.server._processor = None
                transformers = self.transformers(self.processor(), model)
                with patch.dict(sys.modules, {'transformers': transformers}):
                    with self.assertRaisesRegex(RuntimeError, 'CUDA FP32 runtime'): self.server._load()
                self.assertIsNone(self.server._model); self.assertIsNone(self.server._processor)
    def test_all_processor_tensor_fields_move_to_cuda_without_changing_integer_dtype(self):
        values = Inputs(input_ids=Tensor('torch.int64'), attention_mask=Tensor('torch.int64'),
                        pixel_values=Tensor(), image_grid_thw=Tensor('torch.int64'))
        result = self.server._cuda_inputs(values)
        self.assertIs(values, result)
        self.assertTrue(all(value.device.type == 'cuda' for value in values.values()))
        self.assertEqual('torch.int64', result['input_ids'].dtype); self.assertEqual('torch.float32', result['pixel_values'].dtype)
        class RefusedInputs(Inputs):
            def to(self, device): return self
        with self.assertRaisesRegex(RuntimeError, 'did not move to CUDA'):
            self.server._cuda_inputs(RefusedInputs(input_ids=Tensor()))
    def test_health_and_runtime_failure_are_controlled_without_cpu_fallback(self):
        self.assertEqual('cuda', self.server.health()['device']); self.assertFalse(self.server.health()['modelLoaded'])
        self.torch.cuda.is_available = lambda: False
        with self.assertRaises(self.server.HTTPException) as error: self.server.health()
        self.assertEqual(503, error.exception.status_code); self.assertIn('CUDA', error.exception.detail)
        with self.assertRaises(self.server.HTTPException) as error: self.server.completions(self.body())
        self.assertEqual(503, error.exception.status_code); self.assertIn('CUDA', error.exception.detail)
        self.assertEqual([], list(self.root.glob('*.json')))
    def test_completion_moves_tensors_and_journals_actual_runtime_preserving_layout_tokens(self):
        self.server._processor, self.server._model = self.processor(), self.model()
        transformers = self.transformers(self.server._processor, self.server._model)
        with patch.dict(sys.modules, {'transformers': transformers}): result = self.server.completions(self.body())
        self.assertTrue(all(value.device.type == 'cuda' for value in self.inputs.values()))
        self.assertEqual('<layout>AUTHORED</layout>', result['choices'][0]['message']['content'])
        self.assertEqual('stop', result['choices'][0]['finish_reason'])
        evidence = json.loads(next(self.root.glob('*.json')).read_text(encoding='utf-8'))
        self.assertEqual('cuda:3', evidence['device']); self.assertEqual('float32', evidence['dtype'])
        self.assertEqual(self.server.MODEL, evidence['model']); self.assertEqual(self.server.REVISION, evidence['revision'])
        self.assertEqual(10, evidence['generation']['max_new_tokens'])
        self.assertEqual([101, 102, 2], evidence['outputTokenIds'])
        self.assertEqual(8, evidence['inputImages'][0]['width'])
    def test_full_context_and_output_caps_are_not_loosened_for_cuda(self):
        self.server._processor, self.server._model = self.processor(8192), self.model()
        with self.assertRaises(self.server.HTTPException) as error: self.server.completions(self.body())
        self.assertEqual(422, error.exception.status_code); self.assertIn('Complete image', error.exception.detail)
        self.server._processor = self.processor()
        for cap in (-1, 8193):
            with self.assertRaises(self.server.HTTPException) as error: self.server.completions(self.body(max_completion_tokens=cap))
            self.assertEqual(422, error.exception.status_code)
        self.assertEqual([], list(self.root.glob('*.json')))


if __name__ == '__main__': unittest.main()
