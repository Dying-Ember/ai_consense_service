"""Offline parameter/provenance regressions: no HTTP, model, GPU or real index."""
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

import eval as pilot
import ocr_runtime
import server
from experiment_log import events


CONFIG = {'embedding': {'name': 'example/embedding', 'revision': 'embed-revision'},
          'reranker': {'name': 'example/reranker', 'revision': 'rerank-revision'}}
CHUNKS = [{'id': 'fixed-source', 'content': 'Unchanged source text 45 days.', 'role': 'tender'}]


class FakeSampler:
    instances = []

    def __init__(self, output, note):
        self.output, self.note, self.started, self.stopped = output, note, False, False
        self.instances.append(self)

    def set_phase(self, phase):
        self.phase = phase

    def start(self):
        self.started = True

    def stop(self):
        self.stopped = True
        value = {'samples': 0, 'sampledGpuMemoryPeaks': [], 'mocked': True}
        pilot.save(self.output/'resource_summary.json', value)
        return value


class ModelParametersTest(unittest.TestCase):
    def ocr_library_fixture(self, root):
        library, models = root/'library', root/'models'
        library.mkdir()
        models.mkdir()
        (library/'config.yaml').write_text('full default config bytes\n', encoding='utf-8')
        (library/'default_models.yaml').write_text('full routing config bytes\n', encoding='utf-8')
        (library/'engine.py').write_text('original engine source\n', encoding='utf-8')
        config = {'Global': {'use_det': True, 'use_cls': True, 'use_rec': True},
                  'EngineConfig': {'onnxruntime': {'use_cuda': False}}}
        routing = {'onnxruntime': {'test-version': {}}}
        for name in ('Det', 'Cls', 'Rec'):
            task = name.lower()
            config[name] = {'engine_type': 'onnxruntime', 'ocr_version': 'test-version',
                            'task_type': task, 'lang_type': 'ch', 'model_type': 'small'}
            routing['onnxruntime']['test-version'][task] = {
                'ch_test_small': {'model_dir': 'https://example.invalid/'+task+'.onnx',
                                 'SHA256': pilot.hashlib.sha256(('fake '+task+' weights').encode()).hexdigest()}}
            (models/(task+'.onnx')).write_bytes(('fake '+task+' weights').encode())
        return library, models, config, routing

    def signed_ocr_fixture(self, root, recipe):
        source = root/'source.pdf'
        source.write_bytes(b'fixed source PDF fixture, no renderer')
        args = pilot.parse_args(['--out', str(root/'out'), '--source-root', str(root), '--offline', 'ocr'])
        cache = args.out/'ocr/FT_2.json'
        data = {'ocrCacheSchemaVersion': 2, 'ocrCacheRecipe': recipe,
                'ocrCacheSignature': ocr_runtime.identity_hash(recipe),
                'source_hash': pilot.file_identity(source)['sha256'], 'key': 'FT', 'page': 2,
                'dpi': 200, 'image_size': [1654, 2339], 'seconds': 1.2, 'lines': [],
                'text': '原文 180 days\nfrom and including the fixed or extended closing date.'}
        pilot.save(cache, data)
        config = {'documents': [{'key': 'FT', 'path': 'source.pdf'}],
                  'ocr_cases': [{'key': 'FT', 'page': 2, 'needles': ['180 days']}]}
        return args, config, cache, data

    def test_ocr_cli_knobs_are_independent_and_validate_before_inference(self):
        with patch.object(ocr_runtime.os, 'cpu_count', return_value=8):
            default = pilot.parse_args(['ocr'])
            self.assertEqual({'max_side_len': 2000, 'text_score': .5, 'box_thresh': .5,
                              'intra_threads': 4, 'inter_threads': 1}, pilot.ocr_settings(default))
            changed = pilot.parse_args(['--ocr-max-side-len', '3000', '--ocr-text-score', '.4',
                '--ocr-box-thresh', '.6', '--ocr-intra-threads', '2', '--ocr-inter-threads', '3', 'ocr'])
            self.assertEqual((3000, .4, .6, 2, 3), tuple(pilot.ocr_settings(changed).values()))
            self.assertEqual((200, 512, 512, 4, 4), (changed.dpi, changed.embed_max_tokens,
                             changed.rerank_max_tokens, changed.batch_size, changed.rerank_batch))
            different_dpi = copy.copy(default)
            different_dpi.dpi = 250
            with patch.object(ocr_runtime, 'ocr_cache_recipe', side_effect=lambda directory, **settings: {'parameters': settings}):
                first, second = pilot.requested_ocr_recipe(default), pilot.requested_ocr_recipe(different_dpi)
                self.assertEqual(first['parameters'], second['parameters'])
                self.assertNotEqual(ocr_runtime.identity_hash(first), ocr_runtime.identity_hash(second))
            for name, value in (('max-side-len', '31'), ('max-side-len', '1.5'),
                ('text-score', 'nan'), ('text-score', '1.1'), ('box-thresh', '-.1'),
                ('box-thresh', 'inf'), ('intra-threads', '0'), ('intra-threads', '9'),
                ('inter-threads', '-1')):
                with self.subTest(name=name, value=value), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    pilot.parse_args(['--ocr-'+name, value, 'ocr'])

    def test_ocr_factory_forwards_cpu_parameters_without_real_engine(self):
        fake_rapidocr = types.ModuleType('rapidocr')
        fake_rapidocr.RapidOCR = Mock(return_value=object())
        with patch.dict(sys.modules, {'torch': types.ModuleType('torch'),
            'onnxruntime': types.ModuleType('onnxruntime'), 'rapidocr': fake_rapidocr}), \
            patch.dict(ocr_runtime.os.environ, {'CONSENSE_VC_RUNTIME_DIR': '/nonexistent-offline-test-dll-directory'}), \
            patch.object(ocr_runtime.os, 'cpu_count', return_value=8):
            ocr_runtime.create_ocr_engine('frozen-model-root')
            params = fake_rapidocr.RapidOCR.call_args.kwargs['params']
            self.assertEqual(2000, params['Global.max_side_len'])
            self.assertEqual((4, 1), (params['EngineConfig.onnxruntime.intra_op_num_threads'],
                                    params['EngineConfig.onnxruntime.inter_op_num_threads']))
            ocr_runtime.create_ocr_engine('frozen-model-root', max_side_len=3000,
                text_score=.3, box_thresh=.7, intra_threads=2, inter_threads=3)
            params = fake_rapidocr.RapidOCR.call_args.kwargs['params']
            self.assertEqual((3000, .3, .7, 2, 3), tuple(params[key] for key in (
                'Global.max_side_len', 'Global.text_score', 'Det.box_thresh',
                'EngineConfig.onnxruntime.intra_op_num_threads', 'EngineConfig.onnxruntime.inter_op_num_threads')))
            self.assertEqual('frozen-model-root', params['Global.model_root_dir'])
            with self.assertRaises(ValueError):
                ocr_runtime.create_ocr_engine(intra_threads=9)
            self.assertEqual(2, fake_rapidocr.RapidOCR.call_count)

    def test_ocr_recipe_binds_config_weight_threads_and_package_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            library, models, config, routing = self.ocr_library_fixture(root)
            package = {'installed': True, 'version': 'frozen', 'recordSha256': 'record'}
            with patch.object(ocr_runtime, 'rapidocr_configuration', return_value=(library, config, routing)), \
                 patch.object(ocr_runtime, 'package_identity', side_effect=lambda name: copy.deepcopy(package)):
                baseline = ocr_runtime.ocr_cache_recipe(models)
                self.assertEqual(baseline, ocr_runtime.ocr_cache_recipe(models))
                self.assertNotEqual(ocr_runtime.identity_hash(baseline),
                    ocr_runtime.identity_hash(ocr_runtime.ocr_cache_recipe(models, intra_threads=1)))
                weight = models/'det.onnx'
                before = weight.read_bytes()
                weight.write_bytes(b'changed model only')
                self.assertNotEqual(baseline, ocr_runtime.ocr_cache_recipe(models))
                weight.write_bytes(before)
                file = library/'config.yaml'
                before = file.read_bytes()
                file.write_bytes(b'changed configuration only')
                self.assertNotEqual(baseline, ocr_runtime.ocr_cache_recipe(models))
                file.write_bytes(before)
                package['version'] = 'updated runtime only'
                self.assertNotEqual(baseline, ocr_runtime.ocr_cache_recipe(models))
                package['version'] = 'frozen'
                relocated = root/'same-weights-new-output'
                relocated.mkdir()
                for path in models.iterdir(): (relocated/path.name).write_bytes(path.read_bytes())
                self.assertEqual(baseline, ocr_runtime.ocr_cache_recipe(relocated))
                weight.unlink()
                with self.assertRaises(FileNotFoundError): ocr_runtime.ocr_cache_recipe(models)

    def test_pinned_v6_multilingual_and_legacy_orientation_routes_select_actual_filenames(self):
        routing = {'onnxruntime': {'PP-OCRv6': {}, 'PP-OCRv4': {'cls': {
            'ch_ppocr_mobile_v2.0_cls_mobile': {'model_dir': 'https://example.invalid/ch_ppocr_mobile_v2.0_cls_mobile.onnx'}}}}}
        for task in ('det', 'rec'):
            key = 'multi_PP-OCRv6_'+task+'_small'
            routing['onnxruntime']['PP-OCRv6'][task] = {key: {'model_dir': 'https://example.invalid/PP-OCRv6_'+task+'_small.onnx'}}
            selected, info = ocr_runtime.configured_model_info({'engine_type': 'onnxruntime',
                'ocr_version': 'PP-OCRv6', 'task_type': task, 'model_type': 'small', 'lang_type': 'ch'}, routing)
            self.assertEqual(key, selected)
            self.assertEqual('PP-OCRv6_'+task+'_small.onnx', Path(info['model_dir']).name)
        selected, _ = ocr_runtime.configured_model_info({'engine_type': 'onnxruntime',
            'ocr_version': 'PP-OCRv4', 'task_type': 'cls', 'model_type': 'mobile', 'lang_type': 'ch'}, routing)
        self.assertEqual('ch_ppocr_mobile_v2.0_cls_mobile', selected)

    def test_ocr_cache_rejects_unsigned_or_changed_source_page_dpi_or_recipe(self):
        with tempfile.TemporaryDirectory() as directory:
            recipe = {'parameters': {'threads': 4}, 'modelWeights': {'det': 'fixed-sha'}}
            args, _, cache, data = self.signed_ocr_fixture(Path(directory), recipe)
            expected = dict(recipe=recipe, source_hash=data['source_hash'], key='FT', page=2, dpi=200, cache=cache)
            original = copy.deepcopy(data)
            ocr_runtime.validate_ocr_cache(data, **expected)
            for field, value in (('ocrCacheSchemaVersion', None), ('ocrCacheSignature', None),
                ('source_hash', 'changed'), ('page', 3), ('key', 'AA'), ('dpi', 250),
                ('ocrCacheRecipe', {'parameters': {'threads': 1}})):
                changed = copy.deepcopy(data)
                changed[field] = value
                with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, 'refresh'):
                    ocr_runtime.validate_ocr_cache(changed, **expected)
            changed_expected = {**expected, 'recipe': {**recipe, 'runtime': 'new'}}
            with self.assertRaises(RuntimeError): ocr_runtime.validate_ocr_cache(data, **changed_expected)
            self.assertEqual(original, data)

    def test_same_recipe_ocr_cache_reuse_does_not_initialize_engine_or_render(self):
        with tempfile.TemporaryDirectory() as directory:
            recipe = {'parameters': {'threads': 4}, 'modelWeights': {'det': 'fixed-sha'}}
            args, config, cache, data = self.signed_ocr_fixture(Path(directory), recipe)
            with patch.object(pilot, 'requested_ocr_recipe', return_value=recipe), \
                 patch.object(ocr_runtime, 'create_ocr_engine', side_effect=AssertionError('No engine on cache hit')), \
                 contextlib.redirect_stdout(io.StringIO()):
                pilot.ocr(args, config)
            self.assertEqual(data['text'], pilot.read(cache)['text'])
            self.assertEqual(data['ocrCacheSignature'], pilot.read(cache)['ocrCacheSignature'])
            self.assertEqual(1, pilot.read(args.out/'ocr_report.json')['anchor_checks_passed'])
            unsigned = copy.deepcopy(data)
            unsigned.pop('ocrCacheSignature')
            unsigned.pop('ocrCacheSchemaVersion')
            pilot.save(cache, unsigned)
            before = cache.read_bytes()
            with patch.object(pilot, 'requested_ocr_recipe', side_effect=AssertionError('No signing legacy bytes')), \
                 patch.object(ocr_runtime, 'create_ocr_engine', side_effect=AssertionError('No engine')), \
                 self.assertRaisesRegex(RuntimeError, 'unsigned'):
                pilot.ocr(args, config)
            self.assertEqual(before, cache.read_bytes())

    def test_parse_validates_ocr_recipe_and_refuses_legacy_without_model_loading(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            recipe = {'modelWeights': {'det': 'fixed-sha'}}
            args, _, cache, data = self.signed_ocr_fixture(root, recipe)
            pages = [types.SimpleNamespace(extract_text=lambda: 'A'*80),
                     types.SimpleNamespace(extract_text=lambda: '')]
            fake_pdf = types.ModuleType('pypdf')
            fake_pdf.PdfReader = Mock(return_value=types.SimpleNamespace(pages=pages))
            with patch.dict(sys.modules, {'pypdf': fake_pdf}):
                resolver = Mock(return_value=recipe)
                blocks, pending = pilot.pdf_blocks(root/'source.pdf', 'FT', data['source_hash'], args.out,
                                                   ocr_recipe=resolver, dpi=200)
                self.assertEqual([], pending)
                self.assertEqual(data['text'], blocks[1]['text'])
                self.assertEqual(data['ocrCacheSignature'], blocks[1]['ocrCacheSignature'])
                resolver.assert_called_once_with()
                with self.assertRaisesRegex(RuntimeError, 'refresh'):
                    pilot.pdf_blocks(root/'source.pdf', 'FT', data['source_hash'], args.out,
                                     ocr_recipe={'different': 'thread/config/weight'}, dpi=200)
                data.pop('ocrCacheSignature')
                data.pop('ocrCacheSchemaVersion')
                pilot.save(cache, data)
                with self.assertRaisesRegex(RuntimeError, 'unsigned'):
                    pilot.pdf_blocks(root/'source.pdf', 'FT', data['source_hash'], args.out,
                                     ocr_recipe=recipe, dpi=200)
            # Digital PDF / missing selected OCR page needs no local models.
            fake_pdf.PdfReader = Mock(return_value=types.SimpleNamespace(pages=pages[:1]))
            with patch.dict(sys.modules, {'pypdf': fake_pdf}):
                resolver = Mock(side_effect=AssertionError('No OCR recipe for a digital page'))
                pilot.pdf_blocks(root/'source.pdf', 'FT', 'digest', args.out, ocr_recipe=resolver)
                resolver.assert_not_called()

    def test_fresh_ocr_refuses_recipe_changed_by_engine_initialization_before_inference(self):
        with tempfile.TemporaryDirectory() as directory:
            recipe = {'modelWeights': {'det': 'pre-initialization-sha'}}
            args, config, _, _ = self.signed_ocr_fixture(Path(directory), recipe)
            args.refresh = True
            engine = Mock(side_effect=AssertionError('No OCR call after a repaired weight'))
            with patch.object(pilot, 'requested_ocr_recipe', side_effect=[recipe,
                    {'modelWeights': {'det': 'repaired-weight-sha'}}]), \
                 patch.object(ocr_runtime, 'local_model_paths', return_value={'Det': 'fixture', 'Cls': 'fixture', 'Rec': 'fixture'}), \
                 patch.object(ocr_runtime, 'create_ocr_engine', return_value=engine), \
                 self.assertRaisesRegex(RuntimeError, 'changed during engine initialization'):
                pilot.ocr(args, config)
            engine.assert_not_called()

    def test_offline_corrupt_default_weight_is_rejected_before_factory_or_download(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            library, models, config, routing = self.ocr_library_fixture(root)
            args, stage_config, _, _ = self.signed_ocr_fixture(root, {'irrelevant': 'old-cache'})
            args.refresh = True
            target = args.out/'ocr_models'
            target.mkdir()
            for path in models.iterdir(): (target/path.name).write_bytes(path.read_bytes())
            (target/'det.onnx').write_bytes(b'corrupt but present')
            with patch.object(ocr_runtime, 'rapidocr_configuration', return_value=(library, config, routing)), \
                 patch.object(ocr_runtime, 'package_identity', return_value={'version': 'fixture'}), \
                 patch.object(ocr_runtime, 'create_ocr_engine') as factory, \
                 self.assertRaisesRegex(RuntimeError, 'corrupt default weight before initialization'):
                pilot.ocr(args, stage_config)
            factory.assert_not_called()

    def test_offline_factory_uses_explicit_local_paths_and_custom_weight_needs_local_dictionary(self):
        with tempfile.TemporaryDirectory() as directory:
            library, models, config, routing = self.ocr_library_fixture(Path(directory))
            rec = models/'rec.onnx'
            custom = rec.with_name('custom.onnx')
            custom.write_bytes(b'custom model bytes, not the official route')
            config['Rec']['model_path'] = str(custom)
            dictionary = models/'dictionary.txt'
            with patch.object(ocr_runtime, 'rapidocr_configuration', return_value=(library, config, routing)), \
                 patch.object(ocr_runtime, 'package_identity', return_value={'version': 'fixture'}):
                # Preserve custom models online; do not impose the default registry hash.
                online = ocr_runtime.ocr_cache_recipe(models)
                self.assertEqual('explicit_local_path', online['modelWeights']['Rec']['modelKey'])
                self.assertNotIn('registrySha256', online['modelWeights']['Rec'])
                with self.assertRaisesRegex(RuntimeError, 'local Rec.rec_keys_path'):
                    ocr_runtime.ocr_cache_recipe(models, require_verified_defaults=True)
                dictionary.write_text('a\nb\n', encoding='utf-8')
                config['Rec']['rec_keys_path'] = str(dictionary)
                offline = ocr_runtime.ocr_cache_recipe(models, require_verified_defaults=True)
                self.assertEqual(pilot.file_identity(dictionary)['sha256'], offline['modelWeights']['Rec']['dictionary']['sha256'])
                local = ocr_runtime.local_model_paths(models)
            fake = types.ModuleType('rapidocr')
            fake.RapidOCR = Mock(return_value=object())
            with patch.dict(sys.modules, {'torch': types.ModuleType('torch'),
                    'onnxruntime': types.ModuleType('onnxruntime'), 'rapidocr': fake}), \
                 patch.dict(ocr_runtime.os.environ, {'CONSENSE_VC_RUNTIME_DIR': '/no-test-dll-directory'}):
                ocr_runtime.create_ocr_engine(models, local_paths=local)
            params = fake.RapidOCR.call_args.kwargs['params']
            self.assertEqual(set(local.values()), {params[name+'.model_path'] for name in ('Det', 'Cls', 'Rec')})

    def test_invalid_cli_parameters_log_zero_call_rejection_and_safe_path_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)/'workspace'
            workspace.mkdir()
            registry = workspace/'tmp/registry'
            fallback = workspace/'tmp/fallback-registry'
            outside = Path(directory)/'outside-must-not-create'
            with patch.object(pilot, 'WORKSPACE', workspace), patch.object(pilot, 'DEFAULT_LOG_ROOT', fallback), \
                 patch.object(pilot, 'ocr', side_effect=AssertionError('No OCR after invalid parameters')), \
                 patch('resource_sample.ResourceSampler', side_effect=AssertionError('No sampling')), \
                 contextlib.redirect_stderr(io.StringIO()):
                for log_root in (registry, outside):
                    argv = ['--log-root', str(log_root), '--out', str(outside), '--ocr-intra-threads', '0', 'ocr']
                    with self.assertRaises(SystemExit) as error: pilot.main(argv)
                    self.assertEqual(2, error.exception.code)
                before = len(events(fallback))
                with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as help_exit:
                    pilot.main(['--help'])
                self.assertEqual(0, help_exit.exception.code)
                self.assertEqual(before, len(events(fallback)))
            self.assertFalse(outside.exists())
            for log_root in (registry, fallback):
                history = events(log_root)
                self.assertEqual(['started', 'failed'], [value['eventType'] for _, value in history])
                first, final = [value['record'] for _, value in history]
                self.assertEqual(first['inputFingerprints'], final['inputFingerprints'])
                self.assertEqual((2, 0, 0, 0), tuple(final['measurements'][name] for name in
                    ('exitCode', 'actualModelCalls', 'actualOcrCalls', 'actualIndexesCreated')))
                self.assertIsNone(final['measurements']['wholeMachineSampledResources'])
                self.assertIsNone(final['evaluation']['qualityAccepted'])
                self.assertEqual('driver_preflight', final['scope'].split(':')[0])
                self.assertIn('--ocr-intra-threads', final['parameters']['rawArgv'])


    def settings(self):
        return server.model_settings({'CONSENSE_EMBED_MAX_TOKENS': '768', 'CONSENSE_EMBED_BATCH_SIZE': '2',
                                      'CONSENSE_RERANK_MAX_TOKENS': '1024', 'CONSENSE_RERANK_BATCH_SIZE': '7'})

    def test_server_defaults_and_independent_environment_overrides(self):
        self.assertEqual({'embedding': {'maxTokens': 512, 'batchSize': 4},
                          'reranker': {'maxTokens': 512, 'batchSize': 4}}, server.model_settings({}))
        only_ranker = server.model_settings({'CONSENSE_RERANK_MAX_TOKENS': '1024'})
        self.assertEqual(512, only_ranker['embedding']['maxTokens'])
        self.assertEqual(1024, only_ranker['reranker']['maxTokens'])
        self.assertEqual(4, only_ranker['reranker']['batchSize'])

    def test_invalid_environment_values_fail_before_model_loading(self):
        for name in ('CONSENSE_EMBED_MAX_TOKENS', 'CONSENSE_RERANK_MAX_TOKENS',
                     'CONSENSE_EMBED_BATCH_SIZE', 'CONSENSE_RERANK_BATCH_SIZE'):
            for value in ('0', '-1', '1.5', 'oops', ''):
                with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                    server.model_settings({name: value})
        for name in ('CONSENSE_EMBED_MAX_TOKENS', 'CONSENSE_RERANK_MAX_TOKENS'):
            with self.assertRaises(ValueError):
                server.model_settings({name: '8193'})

    def test_selected_device_is_resolved_once_and_signature_matches_loaded_precision(self):
        from window_runtime import WindowRuntime
        runtime = WindowRuntime(server)
        runtime.identity = lambda kind: {'fixtureIdentity': kind}
        fake_torch = types.ModuleType('torch')
        available = Mock(return_value=True)
        fake_torch.cuda = types.SimpleNamespace(is_available=available)
        fake_torch.set_num_threads = Mock()
        with patch.dict(sys.modules, {'torch': fake_torch}), \
             patch.dict(server.os.environ, {'CONSENSE_MODEL_DEVICE': 'auto'}), \
             patch.object(server, '_selected_device', None), \
             patch.object(server, '_windows_runtime', runtime), patch.object(server, 'MODEL_DTYPE', 'float32'):
            self.assertEqual('cuda', server.device())
            available.return_value = False
            self.assertEqual('cuda', server.device())
            current = server.signature(CHUNKS)
            self.assertEqual(('cuda', 'float32'), (current['runtime']['device'], current['runtime']['dtype']))
            self.assertEqual(3, current['signatureVersion'])
        fake_torch.set_num_threads.assert_called_once_with(4)
        with patch.dict(sys.modules, {'torch': fake_torch}), \
             patch.dict(server.os.environ, {'CONSENSE_MODEL_DEVICE': 'cuda'}), \
             patch.object(server, '_selected_device', None), self.assertRaises(RuntimeError):
            server.device()

    def test_server_loaders_use_separate_token_limits_without_loading_real_models(self):
        from test_token_windows import CharacterTokenizer
        from window_runtime import WindowRuntime
        fake_sentence = types.ModuleType('sentence_transformers')
        parameter = types.SimpleNamespace(dtype='torch.float32', device=types.SimpleNamespace(type='cpu'))
        transformer = types.SimpleNamespace(do_lower_case=False, tokenizer=CharacterTokenizer())
        class FakeEmbedding:
            default_prompt_name = None
            def __getitem__(self, index):
                self.assert_index = index
                return transformer
            def parameters(self):
                return iter([parameter])
        embed = FakeEmbedding()
        ranker = types.SimpleNamespace(tokenizer=CharacterTokenizer(), parameters=lambda: iter([parameter]))
        runtime = WindowRuntime(server)
        fake_sentence.SentenceTransformer = Mock(return_value=embed)
        fake_sentence.CrossEncoder = Mock(return_value=ranker)
        with patch.dict(sys.modules, {'sentence_transformers': fake_sentence}), \
             patch.object(server, 'MODEL_SETTINGS', self.settings()), \
             patch.object(server, '_embedding', None), patch.object(server, '_reranker', None), \
             patch.object(server, 'model_options', return_value=('cpu', {'torch_dtype': 'float32'})), \
             patch.object(server, 'model_path', return_value='/frozen/local-snapshot'), \
             patch.object(server, 'device', return_value='cpu'), patch.object(server, 'MODEL_DTYPE', 'float32'), \
             patch.object(server, '_windows_runtime', runtime):
            self.assertIs(embed, server.embedding_model())
            server.rerank_model()
            self.assertEqual(768, embed.max_seq_length)
            self.assertEqual(1024, fake_sentence.CrossEncoder.call_args.kwargs['max_length'])
            self.assertEqual('cpu', fake_sentence.SentenceTransformer.call_args.kwargs['device'])

    def test_server_encode_and_pair_rerank_preserve_sources_and_independent_batches(self):
        import numpy as np
        from test_token_windows import CharacterTokenizer, parent
        from window_runtime import WindowRuntime
        embed, ranker = Mock(), Mock()
        chunks = [parent(CHUNKS[0]['content'], id_='source-parent', source_hash='a' * 64)]
        original = copy.deepcopy(chunks)
        ranker.predict.return_value = np.asarray([0.5], dtype=np.float32)
        runtime = WindowRuntime(server)
        runtime.tokenizer = lambda kind: CharacterTokenizer()
        runtime.identity = lambda kind: {'fixtureIdentity': kind}
        with patch.object(server, 'MODEL_SETTINGS', self.settings()), \
             patch.object(server, 'embedding_model', return_value=embed), \
             patch.object(server, 'rerank_model', return_value=ranker), \
             patch.object(server, '_windows_runtime', runtime):
            server.encode_embeddings([chunks[0]['content']])
            result = server.rerank_candidates('fixed query', chunks)
        self.assertEqual(2, embed.encode.call_args.kwargs['batch_size'])
        self.assertTrue(embed.encode.call_args.kwargs['normalize_embeddings'])
        self.assertEqual(7, ranker.predict.call_args.kwargs['batch_size'])
        self.assertEqual([('fixed query', chunks[0]['content'])], ranker.predict.call_args.args[0])
        self.assertEqual([0.5], result)
        self.assertEqual(original, chunks)

    def test_embedding_signature_binds_embedding_identity_and_excludes_reranker_changes(self):
        from window_runtime import WindowRuntime
        runtime = WindowRuntime(server)
        runtime.identity = lambda kind: {'fixtureIdentity': kind}
        settings = server.model_settings({})
        with patch.object(server, 'MODEL_SETTINGS', settings), patch.object(server, 'device', return_value='cpu'), \
             patch.object(server, '_windows_runtime', runtime), patch.object(server, 'MODEL_DTYPE', 'float32'):
            initial = server.signature(CHUNKS)
            self.assertEqual('float32', initial['runtime']['dtype'])
            self.assertEqual(3, initial['signatureVersion'])
            settings['reranker'].update(maxTokens=1024, batchSize=2)
            self.assertEqual(initial, server.signature(CHUNKS))
            for key, value in (('maxTokens', 1024), ('batchSize', 2)):
                altered = copy.deepcopy(settings)
                altered['embedding'][key] = value
                with patch.object(server, 'MODEL_SETTINGS', altered):
                    self.assertNotEqual(initial, server.signature(CHUNKS))
            with patch.object(server, 'device', return_value='cuda'):
                current = server.signature(CHUNKS)
                self.assertNotEqual(initial, current)
                self.assertEqual('float32', current['runtime']['dtype'])
            with patch.object(server, 'MODEL_DTYPE', 'float16'):
                self.assertNotEqual(initial, server.signature(CHUNKS))

    def test_old_disk_and_cached_indexes_return_409_before_any_database_access(self):
        from window_runtime import WindowRuntime
        runtime = WindowRuntime(server)
        runtime.identity = lambda kind: {'fixtureIdentity': kind, 'modelWeightLoads': 0}
        old = {'projectId': 'project', 'chunks': CHUNKS, 'collection': 'old',
               'signature': {'embedding': server.MODELS['embedding'], 'maxTokens': 512,
                             'normalized': True, 'corpus': server.digest(CHUNKS)}}
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(server, 'STATE', Path(directory)), patch.object(server, '_projects', {}), \
             patch.object(server, 'MODEL_SETTINGS', server.model_settings({})), \
             patch.object(server, 'device', return_value='cpu'), \
             patch.object(server, 'database', side_effect=AssertionError('No database may be opened')), \
             patch.object(server, '_windows_runtime', runtime):
            target = server.metadata_path('project')
            target.parent.mkdir(parents=True)
            target.write_text(json.dumps(old), encoding='utf-8')
            for cached in (False, True):
                with self.subTest(cached=cached):
                    if cached:
                        server._projects['project'] = old
                    with self.assertRaises(server.HTTPException) as caught:
                        server.read_project('project')
                    self.assertEqual(409, caught.exception.status_code)
                    self.assertIn('re-index', caught.exception.detail)

    def test_health_discloses_actual_settings_revisions_and_library_defaults_without_gpu(self):
        from window_runtime import WindowRuntime
        runtime = WindowRuntime(server)
        runtime.identity = lambda kind: {'fixtureIdentity': kind, 'modelWeightLoads': 0}
        fake_torch = types.ModuleType('torch')
        fake_torch.cuda = types.SimpleNamespace(is_available=lambda: False)
        with patch.dict(sys.modules, {'torch': fake_torch}), \
             patch.object(server, 'device', return_value='cpu'), \
             patch.object(server, 'MODEL_SETTINGS', self.settings()), \
             patch.object(server, '_windows_runtime', runtime):
            info = server.health()
        self.assertEqual(768, info['runtime']['embedding']['maxTokens'])
        self.assertEqual(768, info['maxTokens'])
        self.assertEqual(1024, info['runtime']['reranker']['maxTokens'])
        self.assertEqual(7, info['runtime']['reranker']['batchSize'])
        self.assertEqual('float32', info['runtime']['embedding']['dtype'])
        self.assertEqual(server.MODELS['reranker']['revision'], info['runtime']['reranker']['revision'])
        self.assertEqual({'candidates': 50, 'limit': 10}, info['retrieval']['requestDefaults'])
        self.assertEqual(60, info['retrieval']['rrfConstant'])
        self.assertEqual((1.5, .75, .25), tuple(info['retrieval']['bm25'][k] for k in ('k1', 'b', 'epsilon')))

    def test_cli_default_legacy_fallback_and_split_limits(self):
        default = pilot.parse_args(['index'])
        self.assertEqual((512, 512, 4, 4), (default.embed_max_tokens, default.rerank_max_tokens,
                                           default.batch_size, default.rerank_batch))
        legacy = pilot.parse_args(['--max-tokens', '768', 'evaluate'])
        self.assertEqual((768, 768), (legacy.embed_max_tokens, legacy.rerank_max_tokens))
        split = pilot.parse_args(['--max-tokens', '768', '--embed-max-tokens', '1024',
                                  '--embed-batch-size', '2', '--rerank-batch-size', '7', 'evaluate'])
        self.assertEqual((1024, 768, 2, 7), (split.embed_max_tokens, split.rerank_max_tokens,
                                           split.batch_size, split.rerank_batch))

    def test_cli_invalid_limits_and_batches_rejected_before_stage_execution(self):
        for option, value in (('--max-tokens', '0'), ('--embed-max-tokens', '0'),
                              ('--rerank-max-tokens', '8193'), ('--embed-batch-size', '-1'),
                              ('--rerank-batch-size', '0'), ('--top-k', '51')):
            with self.subTest(option=option), contextlib.redirect_stderr(io.StringIO()), \
                 self.assertRaises(SystemExit) as caught:
                pilot.parse_args([option, value, 'evaluate'])
            self.assertEqual(2, caught.exception.code)

    def test_cli_loader_and_runtime_use_independent_limits_without_real_inference(self):
        args = pilot.parse_args(['--device', 'cpu', '--embed-max-tokens', '768',
                                 '--rerank-max-tokens', '1024', '--rerank-batch', '2', 'evaluate'])
        fake_torch = types.ModuleType('torch')
        fake_torch.float16, fake_torch.float32 = 'float16', 'float32'
        fake_sentence = types.ModuleType('sentence_transformers')
        embed = types.SimpleNamespace()
        fake_sentence.SentenceTransformer = Mock(return_value=embed)
        fake_sentence.CrossEncoder = Mock(return_value=object())
        with patch.dict(sys.modules, {'torch': fake_torch, 'sentence_transformers': fake_sentence}), \
             patch.object(pilot, 'model_path', return_value='/frozen/local-snapshot'):
            pilot.embed_model(args, CONFIG)
            pilot.rerank_model(args, CONFIG)
        self.assertEqual(768, embed.max_seq_length)
        self.assertEqual(1024, fake_sentence.CrossEncoder.call_args.kwargs['max_length'])
        self.assertEqual(2, pilot.model_runtime(args, CONFIG)['reranker']['batchSize'])

    def test_cli_signature_requires_current_embedding_recipe_but_not_reranker(self):
        args = pilot.parse_args(['--device', 'cpu', 'evaluate'])
        original = pilot.embedding_signature(args, CONFIG, CHUNKS)
        args.rerank_max_tokens, args.rerank_batch = 1024, 2
        pilot.require_index_signature(original, pilot.embedding_signature(args, CONFIG, CHUNKS))
        for field, value in (('embed_max_tokens', 1024), ('batch_size', 2), ('device', 'cuda')):
            changed = copy.copy(args)
            setattr(changed, field, value)
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                pilot.require_index_signature(original, pilot.embedding_signature(changed, CONFIG, CHUNKS))
        old = {k: v for k, v in original.items() if k not in ('signature_version', 'device', 'batch_size')}
        with self.assertRaises(RuntimeError):
            pilot.require_index_signature(old, original)

    def cli_fixture(self, root, command):
        config = root/'config.json'
        config.write_text(json.dumps({**CONFIG, 'documents': [], 'ocr_cases': []}), encoding='utf-8')
        return ['--config', str(config), '--out', str(root/'cache'), '--log-root', str(root/'registry'),
                '--baseline-id', 'fixed-control', '--device', 'cpu', command]

    def test_all_cli_stages_append_started_finished_with_explicit_pilot_scope(self):
        for command in ('probe', 'ocr', 'parse', 'index', 'evaluate'):
            with self.subTest(command=command), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                argv = self.cli_fixture(root, command)
                def stage(args, config):
                    pilot.save(args.out/pilot.REPORT_FILES[command][0], {'metric': 'observed', 'semanticQuality': 'unknown'})
                    print('offline stub '+command)
                FakeSampler.instances = []
                with patch.object(pilot, command, side_effect=stage), \
                     patch('resource_sample.ResourceSampler', FakeSampler), contextlib.redirect_stdout(io.StringIO()):
                    result = pilot.main(argv)
                history = events(root/'registry')
                self.assertEqual(['started', 'finished'], [event['eventType'] for _,event in history])
                self.assertEqual('completed', result['status'])
                self.assertEqual('fixed-control', result['baselineId'])
                self.assertIn('not Java full44', result['scope'])
                self.assertIsNone(result['evaluation']['qualityAccepted'])
                self.assertEqual(history[0][1]['record']['inputFingerprints'], history[1][1]['record']['inputFingerprints'])
                self.assertEqual(argv, result['measurements']['originalArgv'])
                self.assertEqual(command != 'probe', bool(FakeSampler.instances))
                if command != 'probe': self.assertTrue(FakeSampler.instances[0].stopped)
                else: self.assertIsNone(result['measurements']['wholeMachineSampledResources'])

    def test_reused_output_keeps_immutable_pre_post_bytes_and_no_database_directory_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            argv = self.cli_fixture(root, 'parse')
            cache = root/'cache'
            cache.mkdir()
            (cache/'qdrant').mkdir()
            (cache/'qdrant/private-db.bin').write_bytes(b'do not copy database')
            original = b'{"id":"first"}\r\n'
            (cache/'chunks.jsonl').write_bytes(original)
            generated = [b'{"id":"one"}\r\n', b'{"id":"two"}\r\n']
            def stage(args, config):
                (args.out/'chunks.jsonl').write_bytes(generated.pop(0))
                (args.out/'parse_report.json').write_bytes(b'{ "characters": 123 }\r\n')
            with patch.object(pilot, 'parse', side_effect=stage), patch('resource_sample.ResourceSampler', FakeSampler):
                first = pilot.main(argv)
                first_id = first['experimentId']
                second = pilot.main(argv)
            runs = cache/'experiment_runs'
            self.assertEqual(original, (runs/first_id/'before/chunks.jsonl').read_bytes())
            self.assertEqual(b'{"id":"one"}\r\n', (runs/first_id/'after/chunks.jsonl').read_bytes())
            self.assertEqual(b'{"id":"one"}\r\n', (runs/second['experimentId']/'before/chunks.jsonl').read_bytes())
            self.assertEqual(b'{"id":"two"}\r\n', (runs/second['experimentId']/'after/chunks.jsonl').read_bytes())
            self.assertEqual(b'{ "characters": 123 }\r\n', (runs/first_id/'after/parse_report.json').read_bytes())
            self.assertFalse(any(path.name == 'private-db.bin' for path in runs.rglob('*')))
            self.assertEqual(4, len(events(root/'registry')))

    def test_failed_cli_stage_keeps_partial_output_error_and_stdio(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            argv = self.cli_fixture(root, 'ocr')
            def stage(args, config):
                print('partial diagnostic output')
                pilot.save(args.out/'ocr_report.json', {'partial': True})
                raise RuntimeError('simulated OCR timeout without an OCR call')
            with patch.object(pilot, 'ocr', side_effect=stage), patch('resource_sample.ResourceSampler', FakeSampler), \
                 contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
                pilot.main(argv)
            history = events(root/'registry')
            self.assertEqual(['started', 'failed'], [event['eventType'] for _,event in history])
            final = history[-1][1]['record']
            self.assertIn('simulated OCR timeout', final['measurements']['error'])
            self.assertEqual({'partial': True}, final['measurements']['postRunReports']['ocr_report.json'])
            run = root/'cache/experiment_runs'/final['experimentId']
            self.assertIn('partial diagnostic output', (run/'stdout.txt').read_text(encoding='utf-8'))
            self.assertIn('RuntimeError', (run/'stderr.txt').read_text(encoding='utf-8'))
            self.assertEqual(history[0][1]['record']['inputFingerprints'], final['inputFingerprints'])

    def test_ordered_pair_identity_preserves_unicode_bytes_order_and_source_text(self):
        pairs = [('query\r\n中', 'raw A 45 days.\n'), ('same query', 'raw B')]
        original = copy.deepcopy(pairs)
        identity = pilot.ordered_pair_identity(pairs, ['source-a', 'source-b'])
        self.assertEqual(identity, pilot.ordered_pair_identity(pairs, ['source-a', 'source-b']))
        self.assertNotEqual(identity['orderedInputSha256'],
                            pilot.ordered_pair_identity(pairs[::-1], ['source-b', 'source-a'])['orderedInputSha256'])
        altered = [(pairs[0][0], pairs[0][1].rstrip()), pairs[1]]
        self.assertNotEqual(identity['orderedInputSha256'], pilot.ordered_pair_identity(altered, ['source-a', 'source-b'])['orderedInputSha256'])
        self.assertEqual(original, pairs)
        with self.assertRaises(ValueError):
            pilot.ordered_pair_identity(pairs, ['missing-one-source'])

    def test_pair_diagnostic_mirrors_predict_batches_and_effective_tokenizer_limit(self):
        class Tensor:
            dtype = 'torch.int64'
            def __init__(self, values): self.values = values
            def tolist(self): return self.values
        class Tokenizer:
            model_max_length, truncation_side, padding_side = 4, 'right', 'right'
            def __init__(self): self.calls = []
            def __call__(self, first, second=None, **kwargs):
                self.calls.append((copy.deepcopy(first), copy.deepcopy(second), kwargs))
                pairs = list(zip(first, second)) if second is not None else first
                rows = [[101]+[ord(char) for char in query+content]+[102] for query, content in pairs]
                if not kwargs['truncation']: return {'input_ids': rows}
                rows = [row[:self.model_max_length] for row in rows]
                padded = max(map(len, rows))
                return {'input_ids': Tensor([row+[0]*(padded-len(row)) for row in rows]),
                        'attention_mask': Tensor([[1]*len(row)+[0]*(padded-len(row)) for row in rows])}
        tokenizer = Tokenizer()
        ranker = types.SimpleNamespace(tokenizer=tokenizer)
        pairs = [('a', 'bcde'), ('x', ''), ('y', 'zzzz')]
        original = copy.deepcopy(pairs)
        first = pilot.pair_token_observations(ranker, pairs, 1024, batch_size=2)
        self.assertEqual((1024, 4), (first['requestedMaxTokens'], first['effectiveMaxTokens']))
        self.assertEqual([7, 3, 7], first['untruncatedTokens'])
        self.assertEqual([4, 3, 4], first['retainedTokens'])
        self.assertEqual(2, first['pairsActuallyTruncated'])
        self.assertEqual(pairs[:2], tokenizer.calls[1][0])
        self.assertIsNone(tokenizer.calls[1][1])
        self.assertEqual({'padding': True, 'truncation': True, 'return_tensors': 'pt'}, tokenizer.calls[1][2])
        self.assertEqual(pairs[2:], tokenizer.calls[2][0])
        tokenizer.model_max_length = 8
        second = pilot.pair_token_observations(ranker, pairs, 8, batch_size=2)
        self.assertEqual(first['untruncatedTokens'], second['untruncatedTokens'])
        self.assertEqual(0, second['pairsActuallyTruncated'])
        self.assertNotEqual(first['predictInputSha256'], second['predictInputSha256'])
        self.assertEqual(first['retainedInputSha256'][1], second['retainedInputSha256'][1])
        self.assertEqual(original, pairs)

    def test_bad_config_and_bad_output_still_leave_started_failed_and_original_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            argv = self.cli_fixture(root, 'parse')
            bad_config = b'{"unterminated":\r\n'
            (root/'config.json').write_bytes(bad_config)
            with patch('resource_sample.ResourceSampler', FakeSampler), self.assertRaises(ValueError):
                pilot.main(argv)
            final = events(root/'registry')[-1][1]['record']
            self.assertEqual('failed', final['status'])
            run = root/'cache/experiment_runs'/final['experimentId']
            self.assertEqual(bad_config, (run/'before/config.json').read_bytes())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            argv = self.cli_fixture(root, 'probe')
            def invalid_output(args, config):
                (args.out/'environment.json').write_bytes(b'{broken report bytes')
            with patch.object(pilot, 'probe', side_effect=invalid_output), self.assertRaises(RuntimeError):
                pilot.main(argv)
            final = events(root/'registry')[-1][1]['record']
            self.assertEqual('failed', final['status'])
            self.assertIn('Output capture failed', final['measurements']['error'])
            self.assertEqual(b'{broken report bytes', (root/'cache/experiment_runs'/final['experimentId']/'after/environment.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
