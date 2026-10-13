"""Real authored Java/PDF capture with full child CAS; no models/network calls."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _hash
from competition_scope import CompetitionScopePolicy
from native_pdf_canonical_projection import produce_native_pdf
from native_pdf_projection_transport import publish_native_pdf_transport, MAX_SNAPSHOT_BYTES
from native_pdf_source_family import (SCHEMA, SPEC_SCHEMA, PRODUCER, STORAGE_SHAPE,
    compose_native_pdf_source_family, validate_native_pdf_source_family, source_family_candidate)
from oer_source_registry import SourceRegistryError, _serialize
from retrieval_source_units import SourceUnits
from test_native_pdf_canonical_projection import fixture
from unified_source_registry import UnifiedCanonicalSourceRegistry


class NativePdfSourceFamilyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.base = Path(cls.tmp.name)
        cls.store = ArtifactStore(cls.base/'cas')
        cls.policy = CompetitionScopePolicy('a'*64,'b'*64,'c'*64,2,'AUTHORED',((2,2),),(),((1,1),))
        cls.registry = UnifiedCanonicalSourceRegistry(cls.base/'registry', cls.store,
            family_policy=cls.policy, family_target_module='vetting')
        cls.native, cls.dual, cls.original_manifest = fixture(cls.store)
        cls.children = [cls.child(page) for page in (1,2)]
        cls.original_spec = {'schema':SPEC_SCHEMA,'documentId':'9001',
            'originalSourceSha256':cls.children[0]['source']['sourceSha256'],
            'scopePolicySha256':'a'*64,'components':[cls.pin(c) for c in cls.children]}
        cls.family = compose_native_pdf_source_family(cls.original_spec,registry=cls.registry,
            artifact_store=cls.store,policy=cls.policy)
        cls.registry.publish(cls.family)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    @classmethod
    def child(cls, page, *, unit_id=None, clause_id=None, document_id=None, purpose=None):
        manifest=copy.deepcopy(cls.original_manifest)
        rid='r'+str(page)
        manifest['documentId']=document_id or 'AUTHORED-child-'+str(page)
        manifest['scope']['requiredPhysicalPages']=[page]
        if purpose:
            manifest['sourcePurpose']=purpose
            if purpose=='standard_template':
                manifest['module']='drafting';manifest['eligibleModules']=['drafting']
        manifest['regionReview']['regions']=[r for r in manifest['regionReview']['regions'] if r['regionId']==rid]
        for row, native_page in zip(manifest['regionReview']['unadoptedGlyphs'],cls.native['observation']['pageObservations']):
            if row['physicalPageNo']!=page:row['glyphIds']=[g['id'] for g in native_page['nativePdfEvidence']['glyphs']]
        reference=__import__('json').loads(cls.store.read_asset(manifest['reference']['artifactDescriptor']))
        reference['relations']=[]
        for region in reference['regions']:
            region['clauseId']=clause_id or 'AUTHORED-clause-'+str(region['sourceAnchor']['physicalPageNo'])
        ref=cls.store.put_bytes(_bytes(reference))
        manifest['reference']['artifactDescriptor']=ref;manifest['reference']['evidenceSha256']=ref['sha256']
        manifest['regionReview']['referenceSha256']=ref['sha256'];manifest['sourceRelationIndices']=[]
        manifest['units']=[{'unitId':unit_id or 'AUTHORED-unit-'+str(page), 'scope':'whole_text_unit','regionIds':[rid],
            'partSeparator':{'kind':'reviewed_region_boundary','text':'\n'},
            'clause':{'clauseId':clause_id or 'AUTHORED-clause-'+str(page),'headingRegionId':rid}}]
        internal=produce_native_pdf(cls.native,cls.dual,manifest,artifact_store=cls.store)
        child=publish_native_pdf_transport(internal,artifact_store=cls.store)
        cls.registry.publish(child)
        return child

    @classmethod
    def pin(cls, child):
        raw=cls.registry._path(child['projectionSha256']).read_bytes()
        return {'projectionSha256':child['projectionSha256'], 'publicBytesSha256':hashlib.sha256(raw).hexdigest(),
                'publicByteCount':len(raw)}

    def setUp(self):
        self.spec=copy.deepcopy(self.original_spec)
        self.root=copy.deepcopy(self.family)

    def compose(self, spec=None, **kwargs):
        return compose_native_pdf_source_family(spec or self.spec,registry=self.registry,
            artifact_store=self.store,policy=self.policy,**kwargs)

    def validate(self, root=None, **kwargs):
        return validate_native_pdf_source_family(root or self.root,registry=self.registry,
            artifact_store=self.store,policy=self.policy,**kwargs)

    def resealed_reject(self, mutate, code='source_family_derived_payload_mismatch'):
        mutate(self.root)
        self.root['projectionSha256']=_hash({k:v for k,v in self.root.items() if k!='projectionSha256'})
        with self.assertRaisesRegex(CanonicalProjectionError,code):self.validate()

    def test_complete_children_normal_cold_read_and_detached_results(self):
        self.assertEqual(self.root,self.compose())
        self.assertEqual(self.root,self.validate())
        self.assertEqual(self.root,self.registry.load(self.root['projectionSha256']))
        loaded=self.validate();loaded['chunks'].clear()
        self.assertEqual(2,len(self.validate()['chunks']))
        self.assertEqual(2,len(self.root['sourceFamilyComponents']))
        self.assertEqual([1,2],[c['parts'][0]['physicalPageNo'] for c in self.root['chunks']])

    def test_real_literal_native_refs_and_full_child_unknown_proofs_are_preserved(self):
        for ordinal,(child,parent) in enumerate(zip(self.children,self.root['chunks'])):
            self.assertEqual(child['chunks'][0]['content'],parent['content'])
            self.assertEqual(child['chunks'][0]['parts'],parent['parts'])
            self.assertEqual(child['chunks'][0]['sourceSpans'],parent['sourceSpans'])
            origin=parent['sourceFamilyOriginRef']
            self.assertEqual(child['chunks'][0]['id'],origin['childChunkId'])
            self.assertEqual(_hash(child['chunks'][0]),origin['fullChildChunkSha256'])
            component=self.root['sourceFamilyComponents'][ordinal]
            for field in ['unadoptedGlyphsRef','unadoptedGlyphSummary','captureCompleteness',
                          'unobservedOriginalPhysicalPages','wholeOriginalSourceCoverage','limitations']:
                self.assertEqual(child[field],component[field])
            self.assertEqual(child['chunks'][0]['sourceQuality'],component['sourceQuality'])
            self.assertNotEqual(child['chunks'][0]['documentId'],parent['documentId'])
        self.assertEqual('unknown',self.root['wholeOriginalSourceCoverage'])
        self.assertFalse(self.root['humanConfirmed']);self.assertFalse(self.root['semanticScopeVerified'])
        self.assertEqual('derived_same_original_component_set',self.root['source']['sourceRevisionKind'])

    def test_complete_unit_selection_with_one_external_identity(self):
        units=SourceUnits(self.root['chunks'])
        for parent in self.root['chunks']:
            self.assertEqual('9001',parent['documentId'])
            self.assertEqual(self.root['sourceHash'],parent['sourceHash'])
            self.assertEqual('complete_observed_unit',units.resolve(parent)['status'])
        self.assertEqual(1,self.registry.validate_native_chunks(self.root['chunks']))

    def test_ordinary_ambiguous_document_guard_is_unchanged(self):
        parents=copy.deepcopy([c['chunks'][0] for c in self.children])
        for parent in parents:parent['documentId']='one-ordinary-document'
        for parent in parents:
            unit=SourceUnits(parents).resolve(parent)
            self.assertEqual('unknown',unit['status'])
            self.assertEqual('ambiguous_document_source_identity',unit['reason'])

    def test_wrong_original_scope_missing_duplicate_and_forged_byte_pins(self):
        mutations=[lambda s:s.update(originalSourceSha256='f'*64),
            lambda s:s.update(scopePolicySha256='f'*64),lambda s:s['components'].pop(),
            lambda s:s['components'].__setitem__(1,copy.deepcopy(s['components'][0])),
            lambda s:s['components'][0].update(publicBytesSha256='f'*64),
            lambda s:s['components'][0].update(publicByteCount=True)]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                spec=copy.deepcopy(self.original_spec);mutate(spec)
                with self.assertRaises(CanonicalProjectionError):self.compose(spec)

    def test_fixed_cap_rejects_without_family_capture_or_dropping_units(self):
        original=set(self.store.root.rglob('*.blob'))
        for limit in (0,True,MAX_SNAPSHOT_BYTES+1,len(_bytes(self.root))-1):
            with self.subTest(limit=limit),self.assertRaises(CanonicalProjectionError):
                self.compose(max_snapshot_bytes=limit)
        self.assertEqual(original,set(self.store.root.rglob('*.blob')))

    def test_tampered_saved_child_after_prior_green_read_fails(self):
        self.validate()
        path=self.registry._path(self.children[1]['projectionSha256']);raw=path.read_bytes()
        try:
            path.write_bytes(raw.replace(b'Second page',b'Tamper page'))
            with self.assertRaises(CanonicalProjectionError):self.validate()
        finally:path.write_bytes(raw)

    def test_missing_actual_child_proof_fails_even_with_registered_public_root(self):
        descriptor=self.children[1]['internalProjectionRef']['artifactDescriptor']
        path=self.store.root/descriptor['relativePath'];raw=path.read_bytes()
        try:
            path.unlink()
            with self.assertRaises(CanonicalProjectionError):self.validate()
        finally:path.write_bytes(raw)

    def test_text_utf16_native_ref_and_origin_tampering_are_rejected(self):
        mutations=[lambda r:r['chunks'][0].update(content='forged'),
            lambda r:r['chunks'][0]['parts'][0].update(endOffset=1),
            lambda r:r['chunks'][0]['parts'][0]['nativePdfRegionRef'].update(payloadSha256='f'*64),
            lambda r:r['chunks'][0]['sourceFamilyOriginRef'].update(childChunkOrdinal=1),
            lambda r:r['sourceFamilyComponents'][1]['unadoptedGlyphSummary'].clear()]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.root=copy.deepcopy(self.family);self.resealed_reject(mutate)

    def test_parent_drop_duplicate_reorder_and_quality_upgrade_are_rejected(self):
        mutations=[lambda r:r['chunks'].pop(),lambda r:r['chunks'].append(copy.deepcopy(r['chunks'][0])),
            lambda r:r['chunks'].reverse(),lambda r:r.update(humanConfirmed=True),
            lambda r:r.update(wholeOriginalSourceCoverage='accepted'),
            lambda r:r['manifest']['quality'].update(legalQualityAccepted=True)]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.root=copy.deepcopy(self.family);self.resealed_reject(mutate)

    def test_invented_cross_component_relation_is_rejected(self):
        self.resealed_reject(lambda r:r['canonical']['relations'].append({
            'type':'parent','fromUnitId':r['chunks'][0]['unitId'],'toUnitId':r['chunks'][1]['unitId'],
            'status':'source_reviewed_declared','scope':'between_whole_units'}))

    def test_declared_within_component_parent_maps_exactly_without_cross_component_context(self):
        manifest=copy.deepcopy(self.original_manifest)
        manifest['documentId']='AUTHORED-hierarchy';manifest['scope']['requiredPhysicalPages']=[1]
        reference=__import__('json').loads(self.store.read_asset(manifest['reference']['artifactDescriptor']))
        old_region=next(r for r in reference['regions'] if r['regionId']=='r1')
        pair=[];review=[];units=[]
        original_row=copy.deepcopy(manifest['regionReview']['regions'][0]);ids=original_row['capturedGlyphIds']
        for index,(rid,glyphs) in enumerate([('r1a',ids[:7]),('r1b',ids[7:])]):
            region=copy.deepcopy(old_region);region.update(regionId=rid,ordinal=index,clauseId='hierarchy-'+rid)
            pair.append(region);row=copy.deepcopy(original_row)
            row.update(regionId=rid,capturedGlyphIds=glyphs,glyphIdsInReadingOrder=glyphs);review.append(row)
            units.append({'unitId':'hierarchy-'+rid,'scope':'whole_text_unit','regionIds':[rid],
                'partSeparator':{'kind':'reviewed_region_boundary','text':'\n'},
                'clause':{'clauseId':'hierarchy-'+rid,'headingRegionId':rid}})
        reference['regions']=[*pair,*[r for r in reference['regions'] if r['regionId']!='r1']]
        reference['pages'][0]['regionIdsInReadingOrder']=['r1a','r1b']
        reference['relations']=[{'type':'parent','fromRegionId':'r1b','toRegionId':'r1a',
            'sourceReview':'authored_original_source_relation','basis':'explicit_authored_hierarchy',
            'humanConfirmed':False}]
        ref=self.store.put_bytes(_bytes(reference));manifest['reference']['artifactDescriptor']=ref
        manifest['reference']['evidenceSha256']=ref['sha256'];manifest['regionReview']['referenceSha256']=ref['sha256']
        manifest['regionReview']['regions']=review;manifest['units']=units;manifest['sourceRelationIndices']=[0]
        manifest['regionReview']['unadoptedGlyphs'][1]['glyphIds']=[
            g['id'] for g in self.native['observation']['pageObservations'][1]['nativePdfEvidence']['glyphs']]
        internal=produce_native_pdf(self.native,self.dual,manifest,artifact_store=self.store)
        child=publish_native_pdf_transport(internal,artifact_store=self.store);self.registry.publish(child)
        spec=copy.deepcopy(self.original_spec);spec['components'][0]=self.pin(child)
        family=self.compose(spec);self.assertEqual(3,len(family['chunks']))
        relation=family['canonical']['relations'][0];expected=copy.deepcopy(child['canonical']['relations'][0])
        expected['fromUnitId']=family['chunks'][1]['unitId'];expected['toUnitId']=family['chunks'][0]['unitId']
        self.assertEqual(expected,relation)
        sourceunits=SourceUnits(family['chunks']);resolved=sourceunits.resolve(family['chunks'][0])
        self.assertEqual('complete_observed_hierarchy_context',resolved['hierarchyContext']['status'])
        self.assertEqual(set(c['id'] for c in family['chunks'][:2]),set(resolved['hierarchyContext']['requiredMemberIds']))
        self.assertNotIn(family['chunks'][2]['id'],resolved['hierarchyContext']['requiredMemberIds'])

    def test_duplicate_clause_and_overlap_are_not_resolved_by_arbitrary_precedence(self):
        for page,clause in [(2,'AUTHORED-clause-1'),(1,'another-clause')]:
            child=self.child(page,unit_id='distinct-unit-'+str(page),clause_id=clause,document_id='AUTHORED-alternate')
            spec=copy.deepcopy(self.original_spec);spec['components'][1]=self.pin(child)
            with self.subTest(page=page),self.assertRaises(CanonicalProjectionError):self.compose(spec)

    def test_standard_template_and_wrong_target_module_are_rejected(self):
        child=self.child(2,unit_id='template-unit',document_id='template',purpose='standard_template')
        spec=copy.deepcopy(self.original_spec);spec['components'][1]=self.pin(child)
        with self.assertRaises(CanonicalProjectionError):self.compose(spec)
        with self.assertRaises(CanonicalProjectionError):self.compose(target_module='drafting')

    def test_family_source_gate_requires_registered_exact_complete_parents(self):
        for chunks in (self.root['chunks'][:1],self.root['chunks'][::-1],
                       [self.root['chunks'][0],self.root['chunks'][0]]):
            with self.subTest(count=len(chunks)),self.assertRaises(SourceRegistryError):
                self.registry.validate_native_chunks(chunks)
        bad=copy.deepcopy(self.root['chunks']);bad[0]['content']='arbitrary /index content'
        with self.assertRaises(SourceRegistryError):self.registry.validate_native_chunks(bad)

    def test_independent_markers_cannot_fall_through_to_child_pdf_docx_or_scan_gates(self):
        for field in ('schema','producerKind','storageShape'):
            root=copy.deepcopy(self.root);root.pop(field)
            self.assertTrue(source_family_candidate(root))
            with self.subTest(field=field),self.assertRaises(ValueError):self.registry.publish(root)
        parent=copy.deepcopy(self.root['chunks'][0]);parent.pop('producerKind');parent.pop('storageShape')
        self.assertTrue(source_family_candidate(parent))

    def test_successful_exit_checks_child_root_after_full_cas_rehash(self):
        path=self.registry._path(self.children[1]['projectionSha256']);raw=path.read_bytes()
        original=self.store.validation_session
        from contextlib import contextmanager
        @contextmanager
        def changed_on_exit():
            with original():yield
            path.write_bytes(raw+b' ')
        try:
            with patch.object(self.store,'validation_session',changed_on_exit),self.assertRaises(CanonicalProjectionError):
                self.validate()
        finally:path.write_bytes(raw)

    def test_restart_and_request_frame_revalidate_child_root_not_only_cas_copy(self):
        new=UnifiedCanonicalSourceRegistry(self.base/'registry',self.store,
            family_policy=self.policy,family_target_module='vetting')
        self.assertEqual(self.root,new.load(self.root['projectionSha256']))
        guard=lambda:{'authored':'immutable'}
        with new.validation_frame(software_guard=guard):new.load(self.root['projectionSha256'])
        path=new._path(self.children[1]['projectionSha256']);raw=path.read_bytes()
        try:
            path.write_bytes(raw+b' ')
            with self.assertRaises(ValueError):
                with new.validation_frame(software_guard=guard):new.load(self.root['projectionSha256'])
        finally:path.write_bytes(raw)


if __name__=='__main__':unittest.main()
