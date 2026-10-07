import copy
import json
from pathlib import Path
import struct
import tempfile
import unittest
import numpy as np
import actual_window_retrieval as d


class CacheBundleGuards(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.cache=self.root/'cache';self.cache.mkdir()
        self.whitelist=self.root/'whitelist.json';d.write(self.whitelist,{'protocol':'fixed-source-vector-cache-whitelist-v1','sources':[{'sourceHash':'a'*64,'role':'tender','rawSourceSha256':'b'*64,'storageTier':'fixed_competition_material'}]})
        identity={'sourceHash':'a'*64,'role':'tender','rawSourceSha256':'b'*64,'embeddingIdentity':{'vectorDimension':2}}
        key=d.digest(identity);directory=self.cache/key;directory.mkdir();vector=directory/'vector.f32le';vector.write_bytes(struct.pack('<ff',1,0))
        manifest=directory/'manifest.json';d.write(manifest,{'key':key,'identity':identity,'dtype':'float32-le','dimension':2,'vector':{'sha256':d.desc(vector)['sha256'],'bytes':8}})
        encoded=self.root/'encoded.npy'
        with encoded.open('wb')as f:np.save(f,np.asarray([[1,0]],dtype=np.float32),allow_pickle=False)
        self.bundle={'protocol':'actual-fixed-source-window-vector-cache-bundle-v1','whitelist':d.desc(self.whitelist),'root':str(self.cache),'uniqueCacheKeys':1,'windowCount':1,'embeddingIdentity':{'vectorDimension':2},
            'entries':[{'key':key,'windowId':'old-window','parentId':'old-project-parent','manifest':d.desc(manifest),'vector':d.desc(vector),'encodedSource':{'artifact':d.desc(encoded),'rowIndex':0}}]}
    def tearDown(self):self.tmp.cleanup()
    def check(self):
        path=self.root/'bundle.json';path.write_text(json.dumps(self.bundle));return d.validate_cache_bundle(d.desc(path))
    def test_exact_external_vector_and_original_encoded_row(self):self.assertEqual(self.check()['windowCount'],1)
    def test_vector_tamper_external_sha_rejected(self):
        Path(self.bundle['entries'][0]['vector']['path']).write_bytes(b'wrong')
        with self.assertRaises(ValueError):self.check()
    def test_bad_source_role_whitelist_rejected(self):
        self.bundle['entries'][0]['key']='c'*64
        with self.assertRaises(ValueError):self.check()
    def test_original_encoded_difference_not_hidden(self):
        path=Path(self.bundle['entries'][0]['encodedSource']['artifact']['path'])
        with path.open('wb')as f:np.save(f,np.asarray([[0,1]],dtype=np.float32),allow_pickle=False)
        self.bundle['entries'][0]['encodedSource']['artifact']=d.desc(path)
        with self.assertRaises(ValueError):self.check()
    def test_original_encoded_row_out_of_range_rejected(self):
        self.bundle['entries'][0]['encodedSource']['rowIndex']=1
        with self.assertRaises(ValueError):self.check()
    def test_path_escape_rejected(self):
        outside=self.root/'outside';outside.write_bytes(struct.pack('<ff',1,0));self.bundle['entries'][0]['vector']=d.desc(outside)
        with self.assertRaises(ValueError):self.check()
    def test_package_model_identity_difference_rejected(self):
        self.bundle['embeddingIdentity']['revision']='changed'
        with self.assertRaises(ValueError):self.check()


if __name__=='__main__':unittest.main()
