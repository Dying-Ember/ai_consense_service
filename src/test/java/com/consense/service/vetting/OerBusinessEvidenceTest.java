package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalSourcePageService;
import com.consense.service.sources.NativeCanonicalFixture;
import com.consense.service.sources.NativeCanonicalContract;
import com.consense.service.sources.NativeDocumentCollectionContract;
import com.consense.service.sources.NativeDocumentCollectionFixture;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Source-reviewed authored wire; mocked registry/client, no neural or online acceptance claim. */
class OerBusinessEvidenceTest {
    static final class Wire {
        final CanonicalEvidenceService registry=mock(CanonicalEvidenceService.class);
        final OerCanonicalRetrievalClient client=mock(OerCanonicalRetrievalClient.class);
        final CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();
        final SourceDocument document=new SourceDocument();
        final CanonicalSourceBinding binding=new CanonicalSourceBinding();
        final ObjectNode snapshot,response;
        final OerBusinessEvidence service;
        Wire() throws Exception {this(false);}
        Wire(boolean nativeFixture) throws Exception {
            JsonNode wire=VettingDualStructureHealthTest.resource("authored_canonical_wire.json");
            snapshot=nativeFixture?NativeCanonicalFixture.snapshot():wire.path("projection").deepCopy();response=wire.path("retrieve").deepCopy();
            config.setBaseUrl("http://127.0.0.1:8868");
            document.setId(42L);document.setProjectId("project");document.setFileKey("PRE");
            binding.setProjectId("project");binding.setDocumentId(42L);binding.setProjectionSha256(snapshot.path("projectionSha256").asText());
            binding.setOriginalSourceSha256(snapshot.path("source").path("sourceSha256").asText());
            binding.setJavaParsedSourceHash(VettingCorpus.hash("authored native revision"));binding.setRoutingSha256(VettingCorpus.hash("authored routing"));
            binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());
            binding.setSourceRevisionSha256(snapshot.path("source").path("sourceRevisionSha256").asText());
            binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());store();
            when(registry.currentBindings("project","vetting",Collections.singletonList(42L))).thenReturn(Collections.singletonList(binding));
            when(registry.pointers(eq("project"),eq(42L),anyString(),eq("vetting"))).thenAnswer(call->pointers());
            when(client.retrieve(anyString(),eq("vetting"),anyString(),nullable(String.class),anyList())).thenAnswer(call->response.deepCopy());
            when(client.sourceUnitPolicy(anyString(),anyString(),anyList())).thenReturn(VettingRetrievalSourceUnits.POLICY);
            service=new OerBusinessEvidence(registry,client,config);
        }
        void store(){binding.setSnapshotJson(JsonUtils.write(snapshot));binding.setSnapshotBytesSha256(VettingCorpus.hash(binding.getSnapshotJson()));}
        OerBusinessEvidence.Session load(){return service.load("project","vetting",Collections.singletonList(document));}
        List<EvidencePointer> pointers(){List<EvidencePointer> result=new ArrayList<>();for(JsonNode c:snapshot.path("chunks")){
            if(NativeCanonicalContract.isNative(snapshot)){result.add(NativeCanonicalFixture.pointer(snapshot,c,binding,"vetting"));continue;}
            EvidencePointer p=new EvidencePointer();p.setProjectId("project");p.setDocumentId(42L);p.setModule("vetting");
            p.setUnitId(c.path("unitId").asText());p.setCanonicalChunkId(c.path("id").asText());
            p.setOriginalSourceSha256(binding.getOriginalSourceSha256());p.setJavaParsedSourceHash(binding.getJavaParsedSourceHash());
            p.setCanonicalSourceHash(binding.getCanonicalSourceHash());p.setSourceRevisionSha256(binding.getSourceRevisionSha256());
            p.setProjectionSha256(binding.getProjectionSha256());p.setProjectionRevisionSha256(binding.getProjectionRevisionSha256());
            p.setSourceFilename(snapshot.path("source").path("sourceFilename").deepCopy());p.setSourceOrder(c.path("sourceOrder").deepCopy());
            List<Integer> pages=new ArrayList<>();c.path("physicalPages").forEach(n->pages.add(n.asInt()));p.setPhysicalPages(pages);
            p.setEligibleModules(c.path("eligibleModules").deepCopy());p.setParts(c.path("parts").deepCopy());p.setSourceSpans(c.path("sourceSpans").deepCopy());
            p.setTableGroup(c.get("tableGroup"));p.setAssetRefs(c.path("assetRefs").deepCopy());p.setRelations(c.path("relations").deepCopy());
            p.setAdoption(c.path("adoption").deepCopy());p.setSourceQuality(c.path("sourceQuality").deepCopy());p.setUnknowns(snapshot.path("unknowns").deepCopy());result.add(p);
        }return result;}
    }

    @Test void collectionRootLoadsAllActualBatchParentsAndKeepsRootAddressThroughBusinessSession() throws Exception {
        Wire w=new Wire(true);JsonNode publicSnapshot=w.snapshot.deepCopy();w.snapshot.removeAll();w.snapshot.setAll(NativeDocumentCollectionFixture.root());
        w.binding.setProjectionSha256(w.snapshot.path("projectionSha256").asText());w.store();
        JsonNode parents=NativeDocumentCollectionContract.readChunks(w.snapshot,ordinal->{try{return NativeDocumentCollectionFixture.batch(ordinal);}catch(Exception failure){throw new RuntimeException(failure);}});
        when(w.registry.chunks(eq(w.binding),eq("vetting"))).thenReturn(parents);
        List<EvidencePointer> pointers=new ArrayList<>();for(JsonNode payload:parents){
            EvidencePointer pointer=NativeCanonicalFixture.pointer(publicSnapshot,payload,w.binding,"vetting");
            JsonNode batch=NativeDocumentCollectionContract.unitBatch(w.snapshot,payload.path("unitId").asText(),ordinal->{try{return NativeDocumentCollectionFixture.batch(ordinal);}catch(Exception failure){throw new RuntimeException(failure);}});
            pointer.setUnknowns(NativeDocumentCollectionContract.unitUnknowns(batch,payload.path("unitId").asText()));pointers.add(pointer);
        }
        when(w.registry.pointers(eq("project"),eq(42L),eq(w.binding.getProjectionSha256()),eq("vetting"))).thenReturn(pointers);
        OerBusinessEvidence.Session session=w.load();assertEquals(3,session.chunkCount());assertFalse(JsonUtils.parse(w.binding.getSnapshotJson()).has("chunks"));
        for(int i=0;i<parents.size();i++){
            Chunk chunk=session.chunks().get(i);assertEquals(parents.get(i),chunk.getCanonicalPayload());assertNull(OerBusinessEvidence.canonicalViewStatus(chunk));
            assertEquals(w.binding.getProjectionSha256(),chunk.getCanonicalEvidence().getProjectionSha256());assertEquals(1,chunk.getCanonicalEvidence().getUnknowns().size());assertNull(chunk.getPageNo());
        }
        assertEquals(2,session.chunks().get(1).getCanonicalPayload().path("tableGroup").path("nativeTableTopology").size());
        verify(w.registry).chunks(w.binding,"vetting");w.binding.setProjectionSha256(VettingCorpus.hash("new root revision"));
        assertThrows(IllegalStateException.class,()->w.service.index(session));verifyNoInteractions(w.client);
    }

    @Test void nativeViewKeepsExactChannelsBlankPartsAndTopologyWithoutLegacyPdfGeometry() throws Exception {
        Wire w=new Wire(true);OerBusinessEvidence.Session session=w.load();List<Chunk> chunks=session.chunks();assertEquals(3,chunks.size());
        for(Chunk c:chunks){assertNull(c.getPageNo());assertNull(c.getBbox());assertNull(OerBusinessEvidence.canonicalViewStatus(c));
            for(VettingCorpus.Part p:c.getParts()){assertNull(p.getPageNo());assertNull(p.getBbox());}}
        Chunk table=chunks.get(1);assertEquals(5,table.getParts().size());assertEquals(3,table.getParts().stream().filter(p->p.getText().isEmpty()).count());
        assertEquals(w.snapshot.path("chunks").get(1),table.getCanonicalPayload());
        assertEquals(2,table.getCanonicalPayload().path("tableGroup").path("nativeTableTopology").size());
        assertDoesNotThrow(()->w.service.ruleSources(session));
        w.service.ruleSources(session).forEach(source->source.getBlocks().forEach(block->assertNull(block.getPageNo())));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));
        for(int i=0;i<chunks.size();i++)OerModelMaterialCandidateTest.assertLiteralParts(chunks.get(i).getCanonicalPayload().path("parts"),material.get(i).path("canonicalStructure").path("parts"));assertTrue(material.toString().contains("nativeTableTopology"));
        assertTrue(material.toString().contains(NativeCanonicalContract.VISUAL));
        List<VectorStore.SearchHit> context=w.service.sourceHits(session,Collections.singletonList(table.getId()));
        assertEquals(1,context.size());assertEquals("",context.get(0).pageNo());assertEquals("unranked_context",context.get(0).getPayload().get("scoreKind"));
        assertTrue(context.get(0).getPayload().get("canonicalEvidence") instanceof List);assertEquals("unknown",((JsonNode)context.get(0).getPayload().get("canonicalSourceUnit")).path("qualifiersComplete").asText());
    }
    @Test void nativePointerOrTransportCannotInventPageOrDiscardPartAndStillReachBusinessPrompt() throws Exception {
        for(String mutation:Arrays.asList("partPage","chunkPage","pointerFrame","version","channel","topology")){
            Wire w=new Wire(true);OerBusinessEvidence.Session session=w.load();Chunk table=session.chunks().get(1);
            if("partPage".equals(mutation))table.getParts().get(0).setPageNo("P0");
            if("chunkPage".equals(mutation))table.setPageNo("P1");
            if("pointerFrame".equals(mutation))table.getCanonicalEvidence().setBboxConvention("normalized_xyxy_original_page");
            if("version".equals(mutation))((ObjectNode)table.getCanonicalPayload()).put("projectionVersion","future");
            if("channel".equals(mutation))((ObjectNode)table.getCanonicalPayload().path("parts").get(0).path("textTransform")).put("textChannel","observedRawXXX");
            if("topology".equals(mutation))((ObjectNode)table.getCanonicalPayload().path("tableGroup")).remove("nativeTableTopology");
            assertNotNull(OerBusinessEvidence.canonicalViewStatus(table),mutation);
            assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(Collections.singletonList(table)),mutation);
        }
    }

    @Test void loadPreservesFullPayloadAndUsesFileBatchAddresses() throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();assertEquals(2,session.chunkCount());
        assertTrue(OerBusinessEvidence.canonicalCorpus(session.chunks()));assertSame(session,OerBusinessEvidence.session(session.chunks()));
        assertThrows(UnsupportedOperationException.class,()->session.chunks().remove(0));
        for(int i=0;i<session.chunkCount();i++){
            Chunk c=session.chunks().get(i);assertEquals(w.snapshot.path("chunks").get(i),c.getCanonicalPayload());
            assertNull(OerBusinessEvidence.canonicalViewStatus(c));assertEquals("unknown",c.getCanonicalEvidence().getSourceQuality().path("semanticQuality").asText());
            assertFalse(c.getCanonicalEvidence().isHumanConfirmed());
            assertEquals(w.binding.getJavaParsedSourceHash(),VettingCorpus.evidence(c,"source",c.getContent()).getSourceHash());
            assertEquals(w.binding.getCanonicalSourceHash(),c.getSourceHash());
        }
        JsonNode bbox=w.snapshot.path("chunks").get(0).path("parts").get(0).path("bbox");
        assertEquals(bbox.get(2).asDouble()-bbox.get(0).asDouble(),session.chunks().get(0).getParts().get(0).getBbox().get(2));
        verify(w.registry).pointers("project",42L,w.binding.getProjectionSha256(),"vetting");
        verify(w.registry,never()).pointer(anyString(),anyLong(),anyString(),anyString(),anyString());
    }

    @Test void ruleQuoteRetainsCanonicalOriginalIdentityOnlyForItsParsedRevision() throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();Chunk chunk=session.chunks().get(0);VettingCorpus.Part part=chunk.getParts().get(0);
        com.consense.web.dto.VettingDtos.FindingEvidence quote=new com.consense.web.dto.VettingDtos.FindingEvidence("source",chunk.getDocumentId(),chunk.getFileKey(),chunk.getFileName(),part.getPageNo(),part.getAnchor(),part.getText(),true,w.binding.getJavaParsedSourceHash(),null);
        w.service.bindRuleEvidence(session,Collections.singletonList(quote));assertNotNull(quote.getCanonicalEvidence());assertEquals(w.binding.getOriginalSourceSha256(),quote.getOriginalSourceSha256());
        com.consense.web.dto.VettingDtos.FindingEvidence stale=new com.consense.web.dto.VettingDtos.FindingEvidence("source",chunk.getDocumentId(),chunk.getFileKey(),chunk.getFileName(),part.getPageNo(),part.getAnchor(),part.getText(),true,VettingCorpus.hash("another parsed revision"),null);
        w.service.bindRuleEvidence(session,Collections.singletonList(stale));assertNull(stale.getCanonicalEvidence());assertNull(stale.getOriginalSourceSha256());assertFalse(stale.isLocated());
    }

    @Test void reviewedSourceRenderConfigurationRequiresTheTypedValidator() throws Exception {
        Wire w=new Wire();w.config.getSourcePageRenderEvidenceIdentities().put(w.binding.getProjectionSha256(),VettingCorpus.hash("authored composite"));
        assertTrue(assertThrows(IllegalStateException.class,w::load).getMessage().contains("source page validator required"));
        verifyNoInteractions(w.client);
    }
    @Test void validatedRenderIdentityFlowsToCitationAndChangesIndexSessionIdentity() throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session unpinned=w.load();String identity=VettingCorpus.hash("authored reviewed composite");
        w.config.getSourcePageRenderEvidenceIdentities().put(w.binding.getProjectionSha256(),identity);
        CanonicalSourcePageService pages=mock(CanonicalSourcePageService.class);
        when(pages.enrich(any(EvidencePointer.class))).thenAnswer(call->{EvidencePointer p=call.getArgument(0);p.setSourceRenderEvidenceIdentitySha256(identity);return p;});
        ReflectionTestUtils.setField(w.service,"sourcePages",pages);
        OerBusinessEvidence.Session pinned=w.load();assertNotEquals(unpinned.indexKey,pinned.indexKey);assertNotEquals(unpinned.identitySha256(),pinned.identitySha256());
        assertEquals(pinned.identitySha256(),w.service.currentIdentity("project","vetting",Collections.singletonList(w.document)));
        List<VectorStore.SearchHit> hits=w.service.sourceHits(pinned,Collections.singletonList(pinned.chunks().get(0).getId()));
        List<?> pointers=(List<?>)hits.get(0).getPayload().get("canonicalEvidence");
        assertEquals(identity,((EvidencePointer)pointers.get(0)).getSourceRenderEvidenceIdentitySha256());
        assertEquals(pinned.chunks().get(0).getCanonicalEvidence().getUnknowns(),((EvidencePointer)pointers.get(0)).getUnknowns());
        w.config.getSourcePageRenderEvidenceIdentities().put(w.binding.getProjectionSha256(),VettingCorpus.hash("new reviewed composite"));
        assertThrows(IllegalStateException.class,()->w.service.assertCurrent(pinned));verify(w.client).sourceUnitPolicy(anyString(),anyString(),anyList());verifyNoMoreInteractions(w.client);
    }

    @Test void disabledWrongProjectDuplicateOrMissingSelectedBindingRejects() throws Exception {
        Wire w=new Wire();w.config.setBaseUrl(null);assertFalse(w.service.enabled());assertThrows(IllegalStateException.class,w::load);
        w.config.setBaseUrl("http://127.0.0.1:8868");w.document.setProjectId("other");assertThrows(IllegalStateException.class,w::load);
        w.document.setProjectId("project");assertThrows(IllegalStateException.class,()->w.service.load("project","vetting",Arrays.asList(w.document,w.document)));
        when(w.registry.currentBindings(anyString(),anyString(),anyList())).thenReturn(Collections.emptyList());assertThrows(IllegalStateException.class,w::load);
        verifyNoInteractions(w.client);
    }

    @Test void bindingChangesCannotIndexLoadedOldViewsOrRestoreInvalidatedSession() throws Exception {
        for(Consumer<CanonicalSourceBinding> mutate:Arrays.<Consumer<CanonicalSourceBinding>>asList(
                b->b.setProjectionSha256(VettingCorpus.hash("new projection")),b->b.setRoutingSha256(VettingCorpus.hash("new routing")),
                b->b.setJavaParsedSourceHash(VettingCorpus.hash("new native")),b->b.setSnapshotJson(b.getSnapshotJson()+" "))){
            Wire w=new Wire();OerBusinessEvidence.Session session=w.load();String original=JsonUtils.write(w.binding);mutate.accept(w.binding);
            assertThrows(IllegalStateException.class,()->w.service.index(session));verifyNoInteractions(w.client);
            CanonicalSourceBinding restored=JsonUtils.read(original,CanonicalSourceBinding.class);
            when(w.registry.currentBindings(anyString(),anyString(),anyList())).thenReturn(Collections.singletonList(restored));
            assertThrows(IllegalStateException.class,()->w.service.index(session));
        }
    }

    @Test void synchronizedPayloadViewOrAssetMutationCannotReachInference() throws Exception {
        for(String mutation:Arrays.asList("rawAndView","assets","unknowns","quality","bbox","metadata")){
            Wire w=new Wire();OerBusinessEvidence.Session session=w.load();Chunk c=session.chunks().get("assets".equals(mutation)?1:0);
            if("rawAndView".equals(mutation)){
                String text=c.getContent()+" forged";c.setContent(text);((ObjectNode)c.getCanonicalPayload()).put("content",text);
                c.getParts().get(0).setText(text);c.getParts().get(0).setEndOffset(text.length());
                ((ObjectNode)c.getCanonicalPayload().path("parts").get(0)).put("text",text).put("endOffset",text.length());
                c.getCanonicalEvidence().setParts(c.getCanonicalPayload().path("parts").deepCopy());
            }
            if("assets".equals(mutation))((com.fasterxml.jackson.databind.node.ArrayNode)c.getCanonicalEvidence().getAssetRefs())
                    .add(JsonUtils.mapper().createObjectNode().put("sha256",VettingCorpus.hash("unbound image")));
            if("unknowns".equals(mutation))c.getCanonicalEvidence().setUnknowns(JsonUtils.mapper().createArrayNode().add("unbound"));
            if("quality".equals(mutation))c.getSourceQuality().setTextAccuracy("verified");
            if("bbox".equals(mutation))c.getParts().get(0).setBbox(Arrays.asList(0d,0d,1d,1d));
            if("metadata".equals(mutation))c.setMetadataVersion(VettingCorpus.METADATA_VERSION);
            assertThrows(IllegalStateException.class,()->w.service.response(session,"query",null),mutation);verifyNoInteractions(w.client);
        }
    }

    @Test void changesDuringIndexOrRetrieveRejectBeforeBusinessConsumption() throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();
        doAnswer(call->{w.binding.setRoutingSha256(VettingCorpus.hash("changed during index"));return null;}).when(w.client).index(anyString(),anyString(),anyList());
        assertThrows(IllegalStateException.class,()->w.service.index(session));
        Wire q=new Wire();OerBusinessEvidence.Session active=q.load();
        when(q.client.retrieve(anyString(),anyString(),anyString(),nullable(String.class),anyList())).thenAnswer(call->{
            q.binding.setProjectionSha256(VettingCorpus.hash("changed during query"));return q.response.deepCopy();});
        assertThrows(IllegalStateException.class,()->q.service.response(active,"query",null));
    }

    @Test void rankedSeedsKeepFullPayloadAndObservedClosureWithoutNewOrigins() throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();List<Chunk> hits=w.service.retrieve(session,"query","tender");
        assertEquals(1,hits.size());assertEquals(w.response.path("hits").get(0).path("payload"),hits.get(0).getCanonicalPayload());
        assertTrue(hits instanceof VettingRetrievalSourceUnits.RankedChunks);
        Chunk seed=hits.get(0);assertNotSame(session.chunks().get(0),seed);seed.setContent("caller changed copy");w.service.index(session);
    }

    @Test void adviceReturnsWholeUnitInSourceOrderWithSingleSeedScoreAndAllPointers() throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();List<VectorStore.SearchHit> hits=w.service.adviceHits(session,"query");
        assertEquals(1,hits.size());VectorStore.SearchHit hit=hits.get(0);Map<String,Object> p=hit.getPayload();
        assertEquals(session.chunks().stream().map(Chunk::getContent).collect(Collectors.joining("\n")),p.get("content"));
        assertEquals(2,((List<?>)p.get("canonicalEvidence")).size());assertEquals(2,((List<?>)p.get("canonicalMembers")).size());
        assertEquals(w.binding.getJavaParsedSourceHash(),p.get("sourceHash"));assertNull(p.get("applicabilityContextComplete"));
        assertEquals("P3, P4",p.get("pageNo"));assertEquals("ranked_seed_only",p.get("canonicalPrimaryAnchorScope"));
        assertEquals(w.response.path("sourceUnits").path("units").get(0),p.get("canonicalSourceUnit"));
        assertEquals(w.response.path("hits").get(0).path("score").asDouble(),hit.getScore());assertFalse(p.containsKey("memberScores"));
    }

    @Test void unknownClosureDoesNotTurnAttachedMembersIntoCompleteUnit() throws Exception {
        Wire w=new Wire();((ObjectNode)w.response.path("sourceUnits").path("units").get(0)).put("status","unknown").put("reason","authored boundary unknown");
        OerBusinessEvidence.Session session=w.load();Map<String,Object> p=w.service.adviceHits(session,"query").get(0).getPayload();
        assertEquals(1,((List<?>)p.get("canonicalEvidence")).size());assertEquals(session.chunks().get(0).getContent(),p.get("content"));
        assertEquals("unknown",((JsonNode)p.get("canonicalSourceUnit")).path("status").asText());assertNull(p.get("applicabilityContextComplete"));
    }
}
