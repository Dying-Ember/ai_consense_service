package com.consense.service.sources;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Python-generated authored bytes; full wire integrity only, no model/original acceptance. */
class NativeCompleteCollectionContractTest {
    static class Wire {
        ObjectNode root;String rootRaw;Map<Integer,byte[]> chunks=new HashMap<>(),envelopes=new HashMap<>();List<Integer> envelopeReads=new ArrayList<>();
        Wire(String variant)throws Exception{
            rootRaw=text("authored-complete-"+variant+"-root.json");root=(ObjectNode)JsonUtils.parse(rootRaw);
            for(JsonNode row:root.path("batches")){int n=row.path("ordinal").asInt();chunks.put(n,NativeCanonicalFixture.bytes("authored-complete-"+variant+"-batch-"+n+".json"));}
            for(JsonNode row:root.path("envelopeBatches")){int n=row.path("ordinal").asInt();envelopes.put(n,NativeCanonicalFixture.bytes("authored-complete-"+variant+"-envelope-"+n+".json"));}
        }
        NativeDocumentCollectionEnvelopeContract.Document read(){return NativeDocumentCollectionContract.readDocument(rootRaw,root.path("projectionSha256").asText(),chunks::get,n->{envelopeReads.add(n);return envelopes.get(n);});}
        void reseal(){rootRaw=seal(root);}
        void replaceEnvelope(int ordinal,byte[] raw){envelopes.put(ordinal,raw);bind((ObjectNode)root.path("envelopeBatches").get(ordinal),raw);reseal();}
        void replaceChunk(int ordinal,byte[] raw){chunks.put(ordinal,raw);bind((ObjectNode)root.path("batches").get(ordinal),raw);reseal();}
    }
    static String text(String name)throws Exception{return new String(NativeCanonicalFixture.bytes(name),StandardCharsets.UTF_8);}
    static String seal(ObjectNode value){value.remove("projectionSha256");value.put("projectionSha256",CanonicalJson.hash(JsonUtils.write(value),null));return CanonicalJson.strictCanonical(JsonUtils.write(value));}
    static void bind(ObjectNode description,byte[] raw){String sha=CanonicalJson.sha(raw);ObjectNode descriptor=(ObjectNode)description.path("artifactDescriptor");
        descriptor.put("sha256",sha);descriptor.put("byteCount",raw.length);descriptor.put("relativePath","sha256/"+sha.substring(0,2)+"/"+sha+".blob");}
    @Test void completeBaseWireReconstructsExactHeaderPublicAndEveryGlobalField()throws Exception{
        Wire wire=new Wire("base");NativeDocumentCollectionEnvelopeContract.Document result=wire.read();
        assertEquals(text("authored-complete-base-header.json"),result.headerJson);assertEquals(text("authored-complete-base-public.json"),result.publicJson);
        assertEquals(NativeCanonicalFixture.snapshot(),result.publicProjection);assertEquals(wire.root,result.root);
        assertEquals(Collections.singletonList(0),wire.envelopeReads);assertFalse(result.root.has("chunks"));
        assertEquals(1,result.publicProjection.path("manifest").path("tableGroups").size());
        assertEquals(NativeCanonicalFixture.snapshot().path("manifest").path("relations"),result.publicProjection.path("manifest").path("relations"));
        assertEquals(NativeCanonicalFixture.snapshot().path("canonical").path("relations"),result.publicProjection.path("canonical").path("relations"));
        JsonNode table=result.publicProjection.path("chunks").get(1);assertEquals(2,table.path("tableGroup").path("nativeTableTopology").size());
        assertEquals(5,table.path("parts").size());assertEquals(3,table.path("parts").findValues("text").stream().filter(n->n.asText().isEmpty()).count());
        assertEquals(3,result.publicProjection.path("unknowns").size());assertFalse(result.publicProjection.path("humanConfirmed").asBoolean());
    }
    @Test void multipleEnvelopeBatchesPreserveScientificNegativeZeroUnicodeAndOpaqueBodyValues()throws Exception{
        Wire wire=new Wire("scalars");NativeDocumentCollectionEnvelopeContract.Document result=wire.read();
        assertEquals(text("authored-complete-scalars-public.json"),result.publicJson);assertEquals(text("authored-complete-scalars-header.json"),result.headerJson);
        assertTrue(result.headerJson.getBytes(StandardCharsets.UTF_8).length>60000);assertEquals(Arrays.asList(0,1,2,3),wire.envelopeReads);
        assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(result.publicProjection.path("authoredUnknownScalarMetadata").path("floats").get(3).asDouble()));
        JsonNode opaque=result.publicProjection.path("chunks").get(1).path("authoredOpaqueUnknown");
        assertEquals(BigInteger.ONE.shiftLeft(80),opaque.path("hugeInteger").bigIntegerValue());assertEquals("表组😀",opaque.path("text").asText());
        assertTrue(result.publicJson.contains("1e-05"));assertTrue(result.publicJson.contains("1e+23"));assertTrue(result.publicJson.contains("5e-324"));
    }
    @Test void missingNonfirstEnvelopeNeverReturnsACompleteDocument()throws Exception{
        for(int n=0;n<4;n++){Wire wire=new Wire("scalars");wire.envelopes.remove(n);assertThrows(CanonicalEvidenceException.class,wire::read);}
    }
    @Test void missingCorruptSwappedAndTruncatedBodyAreRejectedAfterCompleteHeader()throws Exception{
        for(int mode=0;mode<4;mode++){Wire wire=new Wire("base");byte[] raw=wire.chunks.get(1).clone();
            if(mode==0)wire.chunks.remove(1);else if(mode==1){raw[raw.length-1]^=1;wire.chunks.put(1,raw);}else if(mode==2)wire.chunks.put(1,wire.chunks.get(0));else wire.chunks.put(1,Arrays.copyOf(raw,raw.length-1));
            assertThrows(CanonicalEvidenceException.class,wire::read);assertEquals(Collections.singletonList(0),wire.envelopeReads);}
    }
    @Test void resealedEnvelopeSourceAndRangeDriftCannotReplaceBoundIdentity()throws Exception{
        for(Consumer<ObjectNode> mutation:Arrays.<Consumer<ObjectNode>>asList(n->n.put("documentId","foreign"),n->n.put("sourceSha256",String.join("",Collections.nCopies(64,"f"))),n->n.put("firstRecordOrdinal",1),n->n.put("headerSha256",String.join("",Collections.nCopies(64,"f"))))){
            Wire wire=new Wire("base");ObjectNode batch=(ObjectNode)JsonUtils.parse(new String(wire.envelopes.get(0),StandardCharsets.UTF_8));mutation.accept(batch);
            byte[] raw=seal(batch).getBytes(StandardCharsets.UTF_8);((ObjectNode)wire.root.path("envelopeBatches").get(0)).put("projectionSha256",batch.path("projectionSha256").asText());wire.replaceEnvelope(0,raw);
            assertThrows(CanonicalEvidenceException.class,wire::read);}
    }
    @Test void resealedMetadataRecordDeletionRepetitionAndSwapAreRejected()throws Exception{
        for(int mode=0;mode<3;mode++){Wire wire=new Wire("base");ObjectNode batch=(ObjectNode)JsonUtils.parse(new String(wire.envelopes.get(0),StandardCharsets.UTF_8));ArrayNode rows=(ArrayNode)batch.path("records");
            if(mode==0)rows.remove(1);else if(mode==1)rows.set(1,rows.get(0).deepCopy());else{JsonNode first=rows.get(1).deepCopy();rows.set(1,rows.get(2).deepCopy());rows.set(2,first);}
            byte[] raw=seal(batch).getBytes(StandardCharsets.UTF_8);((ObjectNode)wire.root.path("envelopeBatches").get(0)).put("projectionSha256",batch.path("projectionSha256").asText());wire.replaceEnvelope(0,raw);
            assertThrows(CanonicalEvidenceException.class,wire::read);}
    }
    @Test void resealedRootCannotOmitPresentEmptyArrayOrInventAndDuplicateSeams()throws Exception{
        for(int mode=0;mode<3;mode++){Wire wire=new Wire("base");ArrayNode rows=(ArrayNode)wire.root.path("completeEnvelope").path("arrays");
            if(mode==0){int index=-1;for(int n=0;n<rows.size();n++)if(rows.get(n).path("jsonPointer").asText().equals("/assetRefs"))index=n;assertTrue(index>=0);rows.remove(index);}
            else if(mode==1)((ObjectNode)rows.get(0)).put("jsonPointer","/invented/array");else rows.add(rows.get(0).deepCopy());
            wire.reseal();assertThrows(CanonicalEvidenceException.class,wire::read);}
    }
    @Test void resealedRangesCountsCapsAndCodeDescriptorRemainStrict()throws Exception{
        for(Consumer<ObjectNode> mutation:Arrays.<Consumer<ObjectNode>>asList(
            n->((ArrayNode)n.path("envelopeBatches")).remove(3),n->((ObjectNode)n.path("envelopeBatches").get(1)).put("ordinal",0),
            n->((ObjectNode)n.path("envelopeBatches").get(1)).put("firstRecordOrdinal",0),n->((ObjectNode)n.path("completeEnvelope")).put("recordCount",1),
            n->((ObjectNode)n.path("batchPolicy")).put("maximumBatchBytes",16777217),n->((ObjectNode)n.path("envelopeCodeRef")).put("identityScope","invented"),
            n->((ObjectNode)n.path("envelopeBatches").get(1).path("artifactDescriptor")).put("byteCount",60001))){Wire wire=new Wire("scalars");mutation.accept(wire.root);wire.reseal();assertThrows(CanonicalEvidenceException.class,wire::read);}
    }
    @Test void exactDescriptorRebindingCannotAdmitDuplicateKeysOrNoncanonicalBytes()throws Exception{
        Wire original=new Wire("base");String raw=new String(original.chunks.get(1),StandardCharsets.UTF_8);
        List<String> variants=Arrays.asList(" "+raw,raw+"\n",raw.substring(0,1)+"\"ordinal\":1,"+raw.substring(1),raw.replace("😀","\\ud83d\\ude00"),unsorted(raw));
        for(String changed:variants){assertNotEquals(raw,changed);Wire wire=new Wire("base");wire.replaceChunk(1,changed.getBytes(StandardCharsets.UTF_8));assertThrows(CanonicalEvidenceException.class,wire::read);}
    }
    private static String unsorted(String raw){Map<String,String> fields=CanonicalJson.rawFields(raw);List<String> names=new ArrayList<>(fields.keySet());Collections.reverse(names);
        List<String> pairs=new ArrayList<>();for(String name:names)pairs.add(CanonicalJson.scalar(name)+":"+fields.get(name));return "{"+String.join(",",pairs)+"}";}
    @Test void numericTokenSpellingDriftCannotPassWithReboundActualByteDescriptors()throws Exception{
        String original=new String(new Wire("scalars").chunks.get(1),StandardCharsets.UTF_8);
        for(String changed:Arrays.asList(original.replace("1e-05","1E-05"),original.replace("1e-05","1.0e-05"),original.replace("-0.0","-0e+00"))){
            assertNotEquals(original,changed);Wire wire=new Wire("scalars");wire.replaceChunk(1,changed.getBytes(StandardCharsets.UTF_8));assertThrows(CanonicalEvidenceException.class,wire::read);}
    }
    @Test void rootWhitespaceAndDuplicateKeysCannotBecomeReviewedCanonicalRoot()throws Exception{
        Wire wire=new Wire("base");String address=wire.root.path("projectionSha256").asText();
        assertThrows(CanonicalEvidenceException.class,()->NativeDocumentCollectionContract.validateRoot(" "+wire.rootRaw,address));
        assertThrows(CanonicalEvidenceException.class,()->NativeDocumentCollectionContract.validateRoot(wire.rootRaw.substring(0,1)+"\"schema\":\"consense.native-document.v1\","+wire.rootRaw.substring(1),address));
    }
    @Test void oversizeActualEnvelopeAndRootAreRejectedWithoutPartialReturn()throws Exception{
        Wire wire=new Wire("scalars");wire.envelopes.put(3,new byte[16777217]);assertThrows(CanonicalEvidenceException.class,wire::read);
        char[] padding=new char[16777217];Arrays.fill(padding,' ');assertThrows(CanonicalEvidenceException.class,()->NativeDocumentCollectionContract.validateRoot(new String(padding),wire.root.path("projectionSha256").asText()));
    }
    @Test void resealedRepeatedRootMetadataCannotDisagreeWithCompleteHeader()throws Exception{
        Wire wire=new Wire("base");((ObjectNode)wire.root.path("observerIdentity")).put("authoredUnknown","different only in root");wire.reseal();
        CanonicalEvidenceException failure=assertThrows(CanonicalEvidenceException.class,wire::read);assertEquals("CANONICAL_COLLECTION_PUBLIC_METADATA_INVALID",failure.getMessage());
    }
    @Test void pythonFloatTokensAndOldHashBehaviorAreIndependentlyRetained()throws Exception{
        String raw=text("authored-python-float-tokens.json");assertEquals(4106,CanonicalJson.rawElements(raw).size());assertEquals(raw,CanonicalJson.strictCanonical(raw));
        assertEquals(CanonicalJson.sha("{\"a\":1e-05,\"b\":-0.0}".getBytes(StandardCharsets.UTF_8)),CanonicalJson.hash("{ \"b\":-0.0, \"a\":1e-05 }",null));
        assertEquals("{\"中文\":\"😀\"}",CanonicalJson.strictCanonical("{\"中文\":\"😀\"}"));
        for(String invalid:Arrays.asList("{\"a\":1.0e-05}","{\"a\":1E-05}","{\"a\":-0e+00}","{\"a\":0,\"a\":0}"))assertThrows(CanonicalEvidenceException.class,()->CanonicalJson.requireCanonical(invalid));
    }
}
