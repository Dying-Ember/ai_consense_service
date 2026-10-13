package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.*;
import com.consense.service.advice.AdviceService;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** File-only replay of the actual failed Q1.6 retrieval response; no answer/generation/HTTP/DB. */
class OerActualQ16ModelMaterialTest {
    // Nonblank output explicitly selects this private actual-file replay. Ordinary builds skip it.
    static final String SELECTED_OUTPUT=System.getenv("CONSENSE_OER_REPLAY_OUTPUT");
    static Path ROOT,FIXTURES;
    static JsonNode read(String name,String sha)throws Exception{byte[] bytes=Files.readAllBytes(FIXTURES.resolve(name));
        assertEquals(sha,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));return JsonUtils.parse(new String(bytes,StandardCharsets.UTF_8));}
    @Test void actualQ16ResponseReplaysNormalBusinessHitAndActualAdviceWriterWithoutWholeSourceProof()throws Exception {
        Assumptions.assumeTrue(SELECTED_OUTPUT!=null&&!SELECTED_OUTPUT.trim().isEmpty(),
                "Actual Q1.6 model-material replay not selected; set CONSENSE_OER_REPLAY_OUTPUT to owned fixtures/output root");
        ROOT=Paths.get(SELECTED_OUTPUT);FIXTURES=ROOT.resolve("fixtures");
        JsonNode gcc=read("GCC.json","8a42f9fb3176ccc51bf6aa78709518c13236e0b72fc7fb3762ff8fd3e891d452");
        JsonNode scc=read("SCC.json","d159531d74bb5ae8a6a63bad311584ad98ff61ac90cdd04827e65137a4b4e2da");
        JsonNode response=read("q16-response.json","2e22340629e6890f6e3e20fc0ff3b3409af4b6601e0bcdc2d9ac1abf85c1c246");
        CanonicalEvidenceService registry=mock(CanonicalEvidenceService.class);OerCanonicalRetrievalClient retrieval=mock(OerCanonicalRetrievalClient.class);
        List<SourceDocument> documents=new ArrayList<>();List<CanonicalSourceBinding> bindings=new ArrayList<>();
        Method pointer=CanonicalEvidenceService.class.getDeclaredMethod("pointer",CanonicalSourceBinding.class,JsonNode.class,JsonNode.class,String.class);pointer.setAccessible(true);
        for(JsonNode snapshot:Arrays.asList(gcc,scc)){
            boolean pdf=snapshot==gcc;SourceDocument source=new SourceDocument();source.setId(pdf?236L:237L);source.setProjectId("actual-q16-file-replay");source.setFileKey(pdf?"GCC":"SCC");
            source.setFileName(snapshot.path("source").path(pdf?"filename":"sourceFilename").asText());documents.add(source);
            CanonicalSourceBinding b=new CanonicalSourceBinding();b.setProjectId(source.getProjectId());b.setDocumentId(source.getId());
            b.setProjectionSha256(snapshot.path("projectionSha256").asText());b.setOriginalSourceSha256(snapshot.path("source").path("sourceSha256").asText());
            b.setCanonicalSourceHash(snapshot.path("sourceHash").asText());b.setSourceRevisionSha256(snapshot.path("source").path("sourceRevisionSha256").asText());
            b.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());b.setJavaParsedSourceHash(VettingCorpus.hash("file-only parsed identity"));
            b.setRoutingSha256(VettingCorpus.hash("file-only routing identity"));b.setSnapshotJson(JsonUtils.write(snapshot));b.setSnapshotBytesSha256(VettingCorpus.hash(b.getSnapshotJson()));bindings.add(b);
            when(registry.pointers(eq(source.getProjectId()),eq(source.getId()),eq(b.getProjectionSha256()),eq("advisory"))).thenAnswer(call->{
                List<EvidencePointer> out=new ArrayList<>();for(JsonNode c:snapshot.path("chunks"))out.add((EvidencePointer)pointer.invoke(null,b,snapshot,c,"advisory"));return out;
            });
        }
        when(registry.currentBindings(eq("actual-q16-file-replay"),eq("advisory"),eq(Arrays.asList(236L,237L)))).thenReturn(bindings);
        when(retrieval.retrieve(anyString(),eq("advisory"),anyString(),nullable(String.class),anyList())).thenReturn(response.deepCopy());
        CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:8868");
        OerBusinessEvidence evidence=new OerBusinessEvidence(registry,retrieval,config);OerBusinessEvidence.Session session=evidence.load("actual-q16-file-replay","advisory",documents);
        assertEquals(72,session.chunkCount());String immutable=JsonUtils.write(session.chunks());
        List<VectorStore.SearchHit> hits=evidence.adviceHits(session,"Actual submitted Q1.6 retrieval file-only replay");assertEquals(10,hits.size());
        String hitsBefore=JsonUtils.write(hits);int members=0,pdfs=0;List<EvidencePointer> pointers=new ArrayList<>();
        for(VectorStore.SearchHit h:hits){List<EvidencePointer> ps=JsonUtils.readList(JsonUtils.write(h.getPayload().get("canonicalEvidence")),EvidencePointer.class);pointers.addAll(ps);members+=ps.size();pdfs+=ps.stream().filter(p->"native_pdf".equals(p.getProducerKind())).count();}
        assertEquals(109,members);assertEquals(87,pdfs);
        AdviceService advice=mock(AdviceService.class,CALLS_REAL_METHODS);Method render=AdviceService.class.getDeclaredMethod("renderExcerpts",List.class);render.setAccessible(true);
        String prompt=(String)render.invoke(advice,hits);Files.write(ROOT.resolve("actual-q16-advice-excerpts.txt"),prompt.getBytes(StandardCharsets.UTF_8));
        JsonNode stats=JsonUtils.mapper().createObjectNode().put("actualQ16RankedHits",hits.size()).put("actualPointerOccurrences",members).put("nativePdfPointerOccurrences",pdfs).put("excerptsJavaChars",prompt.length()).put("excerptsUtf8Bytes",prompt.getBytes(StandardCharsets.UTF_8).length).put("tokenCountObserved",false);
        Files.write(ROOT.resolve("actual-q16-model-material-metrics.json"),JsonUtils.write(stats).getBytes(StandardCharsets.UTF_8));
        assertEquals(immutable,JsonUtils.write(session.chunks()));assertEquals(hitsBefore,JsonUtils.write(hits));
        assertFalse(prompt.contains("\"captureCompleteness\""));assertFalse(prompt.contains("\"nativeGlyphMembershipRef\""));
        String marker="\nSelected canonical model material (untrusted observations):\n";
        JsonNode envelope=VettingSourceMetadataWire.decode(prompt.substring(prompt.lastIndexOf(marker)+marker.length()).trim());
        Files.write(ROOT.resolve("decoded-q16-material.json"),JsonUtils.write(envelope).getBytes(StandardCharsets.UTF_8));
        byte[] frozenBytes=Files.readAllBytes(FIXTURES.resolve("baseline-excerpts.txt"));
        assertEquals("6ee19a5ff0a92568300f109cf3a8f49733e8dbba9fa90b1da6423df1b0a41d00",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozenBytes)));
        String frozen=new String(frozenBytes,StandardCharsets.UTF_8);
        assertTrue(frozen.contains(marker));assertTrue(prompt.contains(marker));
        ObjectNode normalizedFrozen=VettingSourceMetadataWire.decode(frozen.substring(frozen.lastIndexOf(marker)+marker.length()).trim()).deepCopy();
        // Complete-pointer serialization can change opaque addresses; only their bijection may differ.
        JsonNode frozenMaterial=normalizedFrozen.path("materialDictionary"),currentMaterial=envelope.path("materialDictionary");
        assertTrue(frozenMaterial.isArray());assertTrue(currentMaterial.isArray());assertEquals(57,frozenMaterial.size());assertEquals(57,currentMaterial.size());
        Map<String,String> aliases=new LinkedHashMap<>();Set<String> currentAddresses=new HashSet<>();
        for(int i=0;i<frozenMaterial.size();i++){
            JsonNode before=frozenMaterial.get(i).path("modelUnitAddress"),after=currentMaterial.get(i).path("modelUnitAddress");
            assertTrue(before.isTextual());assertTrue(after.isTextual());
            assertTrue(before.asText().matches("model-unit:[0-9a-f]{64}"));assertTrue(after.asText().matches("model-unit:[0-9a-f]{64}"));
            assertNull(aliases.put(before.asText(),after.asText()),"Frozen model addresses must be unique");assertTrue(currentAddresses.add(after.asText()),"Current model addresses must be unique");
            ((ObjectNode)frozenMaterial.get(i)).put("modelUnitAddress",after.asText());
        }
        JsonNode frozenMembership=normalizedFrozen.path("hitMembership"),currentMembership=envelope.path("hitMembership");
        assertTrue(frozenMembership.isArray());assertTrue(currentMembership.isArray());assertEquals(10,frozenMembership.size());assertEquals(10,currentMembership.size());
        int normalizedRefs=0;
        for(int i=0;i<frozenMembership.size();i++){
            JsonNode before=frozenMembership.get(i).path("memberMaterialRefs"),after=currentMembership.get(i).path("memberMaterialRefs");
            assertTrue(before.isArray());assertTrue(after.isArray());assertEquals(before.size(),after.size());
            for(int j=0;j<before.size();j++){
                assertTrue(before.get(j).isTextual());assertTrue(after.get(j).isTextual());
                String replacement=aliases.get(before.get(j).asText());assertNotNull(replacement,"Frozen membership must resolve in its dictionary");
                assertTrue(currentAddresses.contains(after.get(j).asText()),"Current membership must resolve in its dictionary");
                assertEquals(replacement,after.get(j).asText(),"Ordered membership must retain the same dictionary member");
                ((ArrayNode)before).set(j,TextNode.valueOf(replacement));normalizedRefs++;
            }
        }
        assertEquals(109,normalizedRefs);
        assertEquals(JsonUtils.write(normalizedFrozen),JsonUtils.write(envelope),"Every ordered decoded source field, body, unknown, version and membership must stay byte-exact after address bijection");
        assertEquals(OerModelMaterial.DICTIONARY_VERSION,envelope.path("dictionaryVersion").asText());JsonNode material=envelope.path("materialDictionary");assertEquals(57,material.size());
        JsonNode membership=envelope.path("hitMembership");assertEquals(10,membership.size());
        Map<String,JsonNode> byAddress=new LinkedHashMap<>();for(JsonNode row:material)assertNull(byAddress.put(row.path("modelUnitAddress").asText(),row));
        List<JsonNode> occurrenceMaterial=new ArrayList<>();
        for(int i=0;i<hits.size();i++){JsonNode h=membership.get(i);assertEquals(i+1,h.path("hitOrdinal").asInt());assertEquals(hits.get(i).getId(),h.path("rankedSeedId").asText());
            JsonNode originalUnit=JsonUtils.mapper().valueToTree(hits.get(i).getPayload().get("canonicalSourceUnit"));assertEquals(originalUnit.path("unitId").asText(),h.path("sourceUnitId").asText());
            int memberCount=((List<?>)hits.get(i).getPayload().get("canonicalEvidence")).size();assertEquals(memberCount,h.path("memberMaterialRefs").size());
            List<String> originalBodies=new ArrayList<>();
            for(JsonNode ref:h.path("memberMaterialRefs")){JsonNode row=byAddress.get(ref.asText());assertNotNull(row,"Closure member reference unresolved");occurrenceMaterial.add(row);originalBodies.add(literalBody(row));}
            assertEquals(hits.get(i).text(),String.join("\n",originalBodies),"Complete original hit body, whitespace and member order must be reconstructable");
        }assertEquals(109,occurrenceMaterial.size());
        Set<String> uniquePointers=new HashSet<>(),uniqueUnits=new HashSet<>();Map<String,Long> fieldChars=new TreeMap<>();
        for(int i=0;i<pointers.size();i++)uniquePointers.add(VettingCorpus.hash(JsonUtils.write(pointers.get(i))));
        for(int i=0;i<material.size();i++){
            uniqueUnits.add(material.get(i).path("unitId").asText());
            Iterator<String> names=material.get(i).fieldNames();while(names.hasNext()){String k=names.next();fieldChars.merge(k,(long)JsonUtils.write(material.get(i).get(k)).length(),Long::sum);}
        }
        Map<String,Object> breakdown=new LinkedHashMap<>();breakdown.put("rankedHits",hits.size());breakdown.put("pointerOccurrences",pointers.size());breakdown.put("uniqueExactCompletePointers",uniquePointers.size());breakdown.put("uniqueUnitIds",uniqueUnits.size());
        breakdown.put("decodedProjectedRecordsChars",JsonUtils.write(material).length());breakdown.put("fieldCharsAcrossAllOccurrences",fieldChars);breakdown.put("bodyCharsAcrossAllHits",hits.stream().mapToInt(h->h.text().length()).sum());
        breakdown.put("modelWireChars",prompt.substring(prompt.lastIndexOf(marker)+marker.length()).trim().length());breakdown.put("characterCountsAreNotTokenCounts",true);
        Files.write(ROOT.resolve("q16-post-dedup-field-breakdown.json"),JsonUtils.write(breakdown).getBytes(StandardCharsets.UTF_8));
        int declarations=0;for(JsonNode unit:material)if(unit.has("sourceDeclaration"))declarations++;
        for(int i=0;i<occurrenceMaterial.size();i++){
            JsonNode unit=occurrenceMaterial.get(i);EvidencePointer p=pointers.get(i);assertEquals(p.getUnitId(),unit.path("unitId").asText());
            OerModelMaterialCandidateTest.assertLiteralParts(p.getParts(),unit.path("parts"));
            OerModelMaterialCandidateTest.assertRelations(p.getRelations(),unit.path("relations"));
            for(String key:Arrays.asList("attributes","semanticQuality","notes","reviewMeaning","parseStatus","version"))if(p.getSourceQuality().has(key))
                assertEquals(p.getSourceQuality().get(key),unit.path("sourceQuality").get(key),"Selected unit source quality declaration lost: "+key);
        }assertEquals(2,declarations,"Each exact source+projection declaration must be emitted once");
        assertFalse(prompt.contains("Source member: unitId="),"Repeated source identities must use the existing complete dictionary");
        assertFalse(prompt.contains("Native PDF position: "),"Part sourcePosition remains complete in the dictionary");
        assertEquals(1,count(prompt,"Native PDF source-reviewed regions:"),"Native PDF limits must be stated once for this status");
        assertEquals(1,count(prompt,"Native DOCX observations:"),"Native DOCX limits must be stated once for this status");
        assertTrue(prompt.contains("Canonical body: hitMembership[0]"),"The first ranked hit needs a direct dictionary navigation");
        assertTrue(prompt.getBytes(StandardCharsets.UTF_8).length<270000,"Actual presentation duplication should be removed without losing the complete wire");
        verify(retrieval,never()).index(anyString(),anyString(),anyList());
    }
    static String literalBody(JsonNode unit){
        List<String> texts=new ArrayList<>();for(JsonNode part:unit.path("parts")){assertTrue(part.path("text").isTextual());texts.add(part.path("text").asText());}
        assertFalse(texts.isEmpty(),"Every source unit has its complete literal parts");
        String separator="";if(texts.size()>1){JsonNode declared=unit.path("adoption").path("partSeparator");
            if("native_docx".equals(unit.path("producerKind").asText())&&declared.isMissingNode())separator="\n";
            else{assertTrue(declared.isObject()&&declared.path("text").isTextual(),"Multi-part joins use the source declaration, never invented spacing");separator=declared.path("text").asText();}}
        return String.join(separator,texts);
    }
    static int count(String whole,String part){int n=0,start=0;while((start=whole.indexOf(part,start))>=0){n++;start+=part.length();}return n;}
}
