"""Full persisted point verification for sealed index reuse; no model loads.

The seal is produced only by normal indexing. It is an independent integrity
ledger, not protection against a coordinated rewrite of database and metadata.
"""
import hashlib
import math
import struct

from token_windows import digest, fail


PROTOCOL='consense.persisted-vector-index-integrity.v1'


def vector_sha256(vector, dimension):
    fail(isinstance(vector,list) and len(vector)==dimension,
         'INDEX_VECTOR_DIMENSION','Each persisted vector requires the sealed exact dimension')
    fail(all(type(v) in (int,float) and math.isfinite(v) for v in vector),
         'INDEX_VECTOR_NONFINITE','Persisted vector values must be finite real numbers')
    fail(abs(sum(v*v for v in vector)-1.0)<=.005,
         'INDEX_VECTOR_UNNORMALIZED','Persisted embedding vectors must remain normalized')
    try:raw=struct.pack('<'+'f'*dimension,*vector)
    except (OverflowError,struct.error) as error:
        fail(False,'INDEX_VECTOR_ENCODING','Persisted vectors require finite float32 bytes')
    fail(all(math.isfinite(v) for v in struct.unpack('<'+'f'*dimension,raw)),
         'INDEX_VECTOR_ENCODING','Persisted vectors require finite float32 bytes')
    return hashlib.sha256(raw).hexdigest()


def inspect_points(db, collection, windows, signature, dimension):
    """Read every actual point, including payload and vectors, with bounded pages."""
    fail(type(dimension) is int and dimension>0,'INDEX_VECTOR_DIMENSION','Positive sealed vector dimension required')
    expected={str(w['id']):{**w,'indexSignature':digest(signature)} for w in windows}
    fail(len(expected)==len(windows),'INDEX_POINT_IDS','Expected point identities must be unique')
    fail(bool(expected),'INDEX_POINT_SCOPE_EMPTY','Nonempty source indexes require actual vector points')
    fail(db.collection_exists(collection),'INDEX_COLLECTION_MISSING','Sealed index collection is absent; explicitly rebuild')
    vectors=db.get_collection(collection).config.params.vectors
    fail(not isinstance(vectors,dict) and getattr(vectors,'size',None)==dimension
         and str(getattr(vectors,'distance','')).lower()=='cosine'
         and getattr(vectors,'multivector_config',None) is None,
         'INDEX_VECTOR_SCHEMA','Collection vector schema must match the sealed unnamed cosine dimension')
    def exact_count():
        count=db.count(collection,exact=True).count
        fail(type(count) is int and count==len(expected),'INDEX_POINT_COUNT','Actual exact point count differs from the complete source window plan')
    exact_count()
    seen={};offset=None;cursors=set();page_size=256
    for _ in range((len(expected)+page_size-1)//page_size+1):
        records,next_offset=db.scroll(collection,limit=page_size,offset=offset,with_payload=True,with_vectors=True)
        fail(isinstance(records,list) and len(records)<=page_size,
             'INDEX_POINT_READ','Bounded complete point page required')
        for point in records:
            point_id=str(point.id)
            fail(point_id in expected and point_id not in seen,'INDEX_POINT_IDS','Persisted points contain absent, extra or duplicate identities')
            fail(point.payload==expected[point_id],'INDEX_POINT_PAYLOAD','Every complete persisted payload must equal its exact source/window/signature plan')
            seen[point_id]=vector_sha256(point.vector,dimension)
        if next_offset is None:break
        marker=(type(next_offset).__name__,str(next_offset))
        fail(bool(records) and marker not in cursors,'INDEX_POINT_READ','Point paging must progress without repeated cursor')
        cursors.add(marker);offset=next_offset
    else:fail(False,'INDEX_POINT_READ','Complete point paging exceeded its exact-count bound')
    fail(set(seen)==set(expected),'INDEX_POINT_IDS','Actual persisted point membership differs from the complete source window plan')
    exact_count()
    return {'schema':PROTOCOL,'dimension':dimension,'dtype':'float32-le','pointCount':len(seen),
            'embeddingSignatureSha256':digest(signature),
            'pointsSha256':digest([{'id':key,'vectorSha256':seen[key]} for key in sorted(seen)])}


def verify_reuse(db, metadata, *, key, collection, chunks, signature):
    fail(metadata.get('projectId')==key and metadata.get('collection')==collection
         and metadata.get('chunks')==chunks and type(metadata.get('parentCount')) is int
         and metadata['parentCount']==len(chunks) and type(metadata.get('vectorPoints')) is int,
         'INDEX_REUSE_IDENTITY','Saved index project/collection/complete parents differ from the current request')
    seal=metadata.get('vectorIntegrity')
    fail(isinstance(seal,dict) and set(seal)=={'schema','dimension','dtype','pointCount','embeddingSignatureSha256','pointsSha256','persistedPointsSha256','sealSha256'}
         and seal.get('schema')==PROTOCOL,
         'INDEX_VECTOR_SEAL_MISSING','Index has no normal-path vector integrity seal; explicitly rebuild without reparsing originals')
    fail(seal['sealSha256']==digest({k:v for k,v in seal.items() if k!='sealSha256'}),
         'INDEX_VECTOR_SEAL_CHANGED','Normal indexing vector seal fields changed')
    fail(type(seal['pointCount']) is int and type(seal['dimension']) is int,
         'INDEX_VECTOR_SEAL_CHANGED','Seal count and dimension require exact integers')
    if not chunks:
        actual={'schema':PROTOCOL,'dimension':0,'dtype':'float32-le','pointCount':0,
                'embeddingSignatureSha256':digest(signature),'pointsSha256':digest([])}
        fail(metadata.get('windows')==[] and metadata.get('vectorPoints')==0,
             'INDEX_POINT_SCOPE_EMPTY','An empty source index must contain no windows')
    else:actual=inspect_points(db,collection,metadata['windows'],signature,seal.get('dimension'))
    # Qdrant Local stores the submitted float32 values, while the warm cosine
    # collection exposes its normalized copy. Cold load exposes stored values.
    # Both exact representations are recorded during normal indexing, not
    # inferred or accepted through an epsilon comparison at recovery time.
    expected={k:v for k,v in seal.items() if k not in ('persistedPointsSha256','sealSha256')}
    fail(actual['pointsSha256'] in (seal['pointsSha256'],seal['persistedPointsSha256']),
         'INDEX_VECTOR_INTEGRITY','Actual vector bytes differ from the seal recorded during normal indexing')
    expected['pointsSha256']=actual['pointsSha256']
    fail(expected==actual,'INDEX_VECTOR_INTEGRITY','Actual vector identity differs from its normal indexing seal')
    return actual
