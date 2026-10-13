"""Pinned migration trust boundaries with actual in-memory point ledgers."""
import copy
import json
import importlib.util
from pathlib import Path
import unittest
import sys
from unittest.mock import patch

from index_integrity import inspect_points
from migrated_qdrant_index import MigratedIndex, ENV, configured
from token_windows import digest, WindowError
from verify_retrieval_offline import descriptor
import test_window_server as fixtures

s = fixtures.s


class MigratedIndexTests(unittest.TestCase):
    def setUp(self):
        self.base = fixtures.ServerTests(); self.base.setUp()
        self.root = Path(self.base.tmp.name); self.runtime = self.base.rt
        original = self.root/'original.pdf'; original.write_bytes(b'authored PDF identity')
        source = descriptor(original)
        self.base.p.update(originalSourceSha256=source['sha256'], sourcePath=str(original))
        self.identities = {kind:{'model':s.MODELS[kind], 'files':[{'relativePath':'authored.bin','bytes':1,'sha256':'a'*64}],
                                'backendSha256':'b'*64, 'tokenizerClass':'CharacterTokenizer', 'fast':True, 'modelMaxLength':512}
                           for kind in ('embedding','reranker')}
        self.runtime.identity = lambda kind:copy.deepcopy(self.identities[kind])
        self.base.index()
        meta = copy.deepcopy(s._projects['one']); collection = meta['collection']
        # The synthetic build profile deliberately differs from the current CPU query profile.
        meta['signature']['runtime'].update(device='cuda', dtype='float16')
        for point in self.base.db.collections[collection].values():
            point.payload['indexSignature'] = digest(meta['signature'])
        actual = inspect_points(self.base.db, collection, meta['windows'], meta['signature'], 2)
        seal = {**actual, 'persistedPointsSha256':actual['pointsSha256']}; seal['sealSha256'] = digest(seal)
        meta['vectorIntegrity'] = seal
        publications = {}
        for kind in ('embedding','reranker'):
            path = self.root/(kind+'-publication.json'); path.write_text('authored publication '+kind)
            publications[kind] = descriptor(path)
        self.runtime.model_publications = {kind:object() for kind in publications}
        self.publication_hashes = {kind:value['sha256'] for kind,value in publications.items()}
        self.runtime.publication_identity = lambda kind:{'manifestSha256':self.publication_hashes[kind]}
        frozen = {'schema':'consense.frozen-fixed-development-baseline.v1', 'indexSignature':digest(meta['signature']),
                  'collection':collection, 'vectorIntegrity':seal, 'coverage':{'totalParents':1,'embeddingWindows':len(meta['windows'])},
                  'persistedVectorCount':len(meta['windows']), 'originalSources':{'source':source}, 'modelPublications':publications}
        self.freeze_file = self.root/'frozen.json'; self.freeze_file.write_text(json.dumps(frozen))
        candidate = 'oer_server_authored'
        self.base.db.collections[candidate] = copy.deepcopy(self.base.db.collections[collection])
        self.base.db.vector_params[candidate] = self.base.db.vector_params[collection]
        self.metadata = {**meta, 'collection':candidate}
        self.metadata_file = self.root/'candidate.json'; self.metadata_file.write_text(json.dumps(self.metadata))
        self.receipt = {'schema':'consense.qdrant-server-migration.v1', 'status':'completed_full_storage_readback_candidate_not_adopted',
                        'target':{'url':'http://127.0.0.1:16333','collection':candidate}, 'targetAdopted':False,
                        'activeConfigurationChanged':False, 'allOriginalPersistentStateBytesUnchanged':True,
                        'frozenManifest':descriptor(self.freeze_file), 'candidateMetadata':descriptor(self.metadata_file),
                        'indexSignature':frozen['indexSignature'], 'parentCount':1, 'vectorWindowCount':len(meta['windows']),
                        'targetVectorIntegrity':actual, 'sourceVectorIntegrity':copy.deepcopy(actual)}
        self.receipt_file = self.root/'migration.json'; self.receipt_file.write_text(json.dumps(self.receipt))
        self.environ = {ENV:str(self.receipt_file), ENV+'_SHA256':descriptor(self.receipt_file)['sha256'],
                        'CONSENSE_QDRANT_URL':self.receipt['target']['url']}
        self.database_calls = 0

    def tearDown(self):
        s._migrated_index = None
        self.base.tearDown()

    def database(self):
        self.database_calls += 1
        return self.base.db

    def read(self, reader=None):
        return (reader or MigratedIndex(self.environ)).read('one', self.runtime, self.database)

    def test_default_local_mode_is_unconfigured(self):
        self.assertFalse(configured({}))
        self.assertTrue(configured({ENV+'_SHA256':'a'*64}))

    def test_original_signature_and_separate_query_identity_are_preserved(self):
        reader = MigratedIndex(self.environ); result = self.read(reader)
        self.assertEqual(result, self.metadata)
        self.assertEqual(reader.last_observation['indexSignature'], self.receipt['indexSignature'])
        self.assertNotEqual(reader.last_observation['queryRuntimeSignature'], self.receipt['indexSignature'])
        self.assertFalse(reader.last_observation['runtimeCompatibilityVerified'])
        self.assertEqual(self.database_calls, 1)

    def test_missing_url_wrong_url_and_changed_receipt_rejected_before_database(self):
        for key,value in (('CONSENSE_QDRANT_URL',''), ('CONSENSE_QDRANT_URL','http://127.0.0.1:9999'), (ENV+'_SHA256','0'*64)):
            changed = {**self.environ,key:value}
            with self.assertRaises(WindowError):MigratedIndex(changed)
        self.assertEqual(self.database_calls,0)

    def test_re_pinned_wrong_source_or_target_full_vector_identity_refused_before_database(self):
        for kind in ('sourceVectorIntegrity','targetVectorIntegrity'):
            changed=copy.deepcopy(self.receipt); changed[kind]['pointsSha256']='0'*64
            self.receipt_file.write_text(json.dumps(changed))
            self.environ[ENV+'_SHA256']=descriptor(self.receipt_file)['sha256']
            with self.assertRaises(WindowError):MigratedIndex(self.environ)
        self.assertEqual(self.database_calls,0)

    def test_changed_metadata_and_freeze_refused_even_after_cached_read(self):
        for path in (self.metadata_file,self.freeze_file,self.receipt_file):
            with self.subTest(path=path.name):
                raw=path.read_bytes(); reader=MigratedIndex(self.environ); self.read(reader)
                path.write_bytes(raw+b' '); calls=self.database_calls
                with self.assertRaises(WindowError):self.read(reader)
                self.assertEqual(self.database_calls,calls); path.write_bytes(raw)

    def test_wrong_project_and_configuration_switch_refused_before_database(self):
        reader=MigratedIndex(self.environ)
        with self.assertRaises(WindowError):reader.read('other',self.runtime,self.database)
        self.environ.clear()
        with self.assertRaises(WindowError):self.read(reader)
        self.assertEqual(self.database_calls,0)

    def test_same_count_vector_or_payload_change_refused_after_cached_read(self):
        reader=MigratedIndex(self.environ); self.read(reader)
        points=self.base.db.collections[self.metadata['collection']]
        point=next(iter(points.values())); vector=point.vector
        point.vector=[0.,1.]
        with self.assertRaises(WindowError):self.read(reader)
        point.vector=vector; point.payload['content']='different'
        with self.assertRaises(WindowError):self.read(reader)
        self.assertEqual(self.base.db.count(self.metadata['collection']).count,len(self.metadata['windows']))

    def test_query_model_tokenizer_window_and_publication_drift_refused(self):
        reader=MigratedIndex(self.environ)
        for field,value in (('backendSha256','c'*64),('files',[]),('modelMaxLength',513)):
            old=self.identities['embedding'][field]; self.identities['embedding'][field]=value
            with self.assertRaises(WindowError):self.read(reader)
            self.identities['embedding'][field]=old
        self.runtime.overlap+=1
        with self.assertRaises(WindowError):self.read(reader)
        self.runtime.overlap-=1; self.publication_hashes['reranker']='d'*64
        with self.assertRaises(WindowError):self.read(reader)
        self.assertEqual(self.database_calls,0)

    def test_index_route_is_read_only_without_runtime_or_database_calls(self):
        with patch.dict(s.os.environ,self.environ,clear=True), patch.object(s,'window_runtime') as runtime, patch.object(s,'database') as db:
            with self.assertRaises(s.HTTPException) as error:s.index(s.IndexRequest(projectId='one',chunks=[]))
            self.assertEqual(error.exception.status_code,409)
            runtime.assert_not_called(); db.assert_not_called()

    def test_invalid_migration_cannot_fall_back_to_local(self):
        with patch.dict(s.os.environ,{ENV:str(self.receipt_file)},clear=True), patch.object(s,'database') as db:
            with self.assertRaises(s.HTTPException) as error:s.read_project('one')
            self.assertEqual(error.exception.status_code,409); db.assert_not_called()


class BackendConfigurationTests(unittest.TestCase):
    def test_cached_database_rejects_url_and_key_switch_without_reconnect_or_close(self):
        spec=importlib.util.spec_from_file_location('migration_backend_config_test',Path(__file__).with_name('server.py'))
        module=importlib.util.module_from_spec(spec);sys.modules[spec.name]=module;spec.loader.exec_module(module)
        db=object()
        with patch.dict(module.os.environ,{'CONSENSE_QDRANT_URL':'http://127.0.0.1:16333'},clear=True), patch('qdrant_backend.connect',return_value=db) as connect:
            self.assertIs(module.database(),db)
            module.os.environ['CONSENSE_QDRANT_URL']='http://127.0.0.1:16334'
            with self.assertRaises(WindowError):module.database()
            module.os.environ['CONSENSE_QDRANT_URL']='http://127.0.0.1:16333'
            module.os.environ['CONSENSE_QDRANT_API_KEY']='new-key'
            with self.assertRaises(WindowError):module.database()
            self.assertEqual(connect.call_count,1)
            self.assertIs(module._database,db)

    def test_unknown_cached_backend_cannot_claim_a_migration_url(self):
        spec=importlib.util.spec_from_file_location('migration_unknown_backend_test',Path(__file__).with_name('server.py'))
        module=importlib.util.module_from_spec(spec);sys.modules[spec.name]=module;spec.loader.exec_module(module)
        module._database=object()
        with patch.dict(module.os.environ,{ENV:'explicit-pin','CONSENSE_QDRANT_URL':'http://127.0.0.1:16333'},clear=True), patch('qdrant_backend.connect') as connect:
            with self.assertRaises(WindowError):module.database()
            connect.assert_not_called()


if __name__ == '__main__':
    unittest.main()
