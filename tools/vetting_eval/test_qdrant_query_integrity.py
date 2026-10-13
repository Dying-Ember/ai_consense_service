"""Fresh real Qdrant Local + authored float32 vectors; no neural or live state."""
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import uuid

import numpy as np
from qdrant_client import models
from qdrant_backend import connect
from index_integrity import inspect_points, vector_sha256
import test_source_page_index as source_tests


def vector():
    # This authored seed exposes float32 rounding between point and matrix norms.
    value=np.random.RandomState(14).normal(size=1024).astype(np.float32)
    return value/np.linalg.norm(value)


class LocalCosineQueryTests(unittest.TestCase):
    def test_public_local_queries_preserve_exact_stored_float32_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            db=connect(directory,{})
            try:
                db.create_collection('owned',vectors_config=models.VectorParams(size=1024,distance=models.Distance.COSINE))
                db.upsert('owned',points=[models.PointStruct(id=str(uuid.UUID(int=1)),vector=vector().tolist(),payload={'role':'authored'})])
                before=vector_sha256(db.scroll('owned',with_vectors=True)[0][0].vector,1024)
                for _ in range(3):db.query_points('owned',query=vector().tolist(),limit=1)
                after=vector_sha256(db.scroll('owned',with_vectors=True)[0][0].vector,1024)
                self.assertEqual(before,after,'Read-only cosine queries must not mutate sealed vector bytes')
            finally:db.close()

    def test_remote_backend_keeps_constructor_and_cosine_function_unchanged(self):
        from qdrant_client.local import distances
        original=distances.cosine_similarity
        with patch('qdrant_client.QdrantClient') as constructor:
            actual=connect('unused',{'CONSENSE_QDRANT_URL':'http://AUTHORED.invalid:6333','CONSENSE_QDRANT_API_KEY':'AUTHORED-only'})
            self.assertIs(actual,constructor.return_value)
            constructor.assert_called_once_with(url='http://AUTHORED.invalid:6333',api_key='AUTHORED-only',timeout=120)
        self.assertIs(original,distances.cosine_similarity)


class PublicIndexQueryReuseTests(source_tests.SourcePageIndexHttpTests):
    def test_real_local_index_retrieve_reuse_and_cold_reopen_keep_original_seal(self):
        dbpath=self.root/'owned-real-qdrant'
        actual=connect(dbpath,{})
        self.backend.database=lambda:actual
        self.backend.embedding_model=lambda:SimpleNamespace(get_sentence_embedding_dimension=lambda:1024)
        def encode(texts):
            self.encodes.append(list(texts))
            return np.asarray([vector() for _ in texts],dtype=np.float32)
        self.backend.encode_embeddings=encode
        try:
            response=self.index();self.assertEqual(200,response.status_code,response.text)
            path=self.backend.metadata_path('AUTHORED-pins-index');original=path.read_bytes();saved=json.loads(original)
            for _ in range(3):
                response=self.client.post('/retrieve',json={'projectId':'AUTHORED-pins-index','query':'Visible','candidates':4,'limit':2})
                self.assertEqual(200,response.status_code,response.text)
            observed=inspect_points(actual,saved['collection'],saved['windows'],saved['signature'],1024)
            self.assertEqual(saved['vectorIntegrity']['pointsSha256'],observed['pointsSha256'],'Public retrieval must keep the original normal-indexing warm seal')
            before=len(self.encodes)
            self.backend.embedding_model=lambda:(_ for _ in ()).throw(AssertionError('Reuse must not load embedding model'))
            self.backend.encode_embeddings=lambda texts:(_ for _ in ()).throw(AssertionError('Reuse must not encode'))
            response=self.index();self.assertEqual(200,response.status_code,response.text);self.assertTrue(response.json()['cached'])
            self.assertEqual(original,path.read_bytes());self.assertEqual(before,len(self.encodes))
            actual.close();actual=connect(dbpath,{})
            self.backend._projects={}
            response=self.index();self.assertEqual(200,response.status_code,response.text);self.assertTrue(response.json()['cached'])
            self.assertEqual(original,path.read_bytes());self.assertEqual(before,len(self.encodes))
            point=actual.scroll(saved['collection'],limit=1,with_payload=True,with_vectors=True)[0][0]
            actual.upsert(saved['collection'],points=[models.PointStruct(id=point.id,payload=point.payload,vector=[1.]+[0.]*1023)])
            response=self.index();self.assertEqual(422,response.status_code,response.text)
            self.assertEqual('INDEX_VECTOR_INTEGRITY',response.json()['detail']['kind'])
            self.assertEqual(original,path.read_bytes());self.assertEqual(before,len(self.encodes))
        finally:actual.close()


if __name__=='__main__':unittest.main()
