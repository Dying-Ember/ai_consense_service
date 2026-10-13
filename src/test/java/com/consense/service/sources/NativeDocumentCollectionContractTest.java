package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Exact cross-language bytes plus hostile authored variants; no whole-source semantic acceptance. */
class NativeDocumentCollectionContractTest {
    private byte[] batch(int ordinal){try{return NativeDocumentCollectionFixture.batch(ordinal);}catch(Exception missing){throw new RuntimeException(missing);}}
    private static String seal(ObjectNode root){root.remove("projectionSha256");root.put("projectionSha256",CanonicalJson.hash(JsonUtils.write(root),null));return CanonicalJson.strictCanonical(JsonUtils.write(root));}
    private static JsonNode validate(ObjectNode root){String raw=seal(root);return NativeDocumentCollectionContract.validateRoot(raw,root.path("projectionSha256").asText());}
    @Test void exactPythonRootAndThreeCompleteBatchesRetainSingleSourceAndOldPublicIdentity() throws Exception{
        String raw=NativeDocumentCollectionFixture.rootRaw();JsonNode root=NativeDocumentCollectionFixture.root();
        assertEquals(root,NativeDocumentCollectionContract.validateRoot(raw,root.path("projectionSha256").asText()));
        assertNotEquals(root.path("publicProjectionSha256"),root.path("projectionSha256"));assertFalse(root.has("chunks"));
        assertEquals(NativeCanonicalFixture.snapshot().path("chunks"),NativeDocumentCollectionContract.readChunks(root,this::batch));
        JsonNode table=NativeDocumentCollectionContract.unitBatch(root,"table",this::batch).path("chunks").get(0);
        assertEquals(2,table.path("tableGroup").path("nativeTableTopology").size());assertEquals(5,table.path("parts").size());
        assertEquals(3,table.path("parts").findValues("text").stream().filter(t->t.isTextual()&&t.asText().isEmpty()).count());
        assertEquals(NativeCanonicalFixture.snapshot().path("unknowns").get(1),NativeDocumentCollectionContract.unitUnknowns(NativeDocumentCollectionContract.unitBatch(root,"table",this::batch),"table").get(0));
    }
    @Test void unitReadUsesOnlyItsDeclaredWholeBatchAndNoArbitraryCasPath() throws Exception{
        JsonNode root=NativeDocumentCollectionFixture.root();AtomicInteger reads=new AtomicInteger();
        JsonNode found=NativeDocumentCollectionContract.readUnitBatch(root,"table",ordinal->{assertEquals(1,ordinal);reads.incrementAndGet();return batch(ordinal);});
        assertEquals(1,reads.get());assertEquals("table",found.get(0).path("unitId").asText());
        assertThrows(CanonicalEvidenceException.class,()->NativeDocumentCollectionContract.readUnitBatch(root,"missing",ordinal->{fail("Must not read a batch for an unknown unit");return null;}));
    }
    @Test void descriptorByteChangeSwapMissingAndTruncationRejectBeforeReturningParents() throws Exception{
        JsonNode root=NativeDocumentCollectionFixture.root();
        for(int failure=0;failure<4;failure++){
            final int mode=failure;
            assertThrows(CanonicalEvidenceException.class,()->NativeDocumentCollectionContract.readChunks(root,ordinal->{
                byte[] bytes=batch(ordinal);if(ordinal!=1)return bytes;
                if(mode==0){bytes[bytes.length-1]^=1;return bytes;}if(mode==1)return batch(0);if(mode==2)return null;return Arrays.copyOf(bytes,bytes.length-1);
            }));
        }
    }
    @Test void resealedRootCannotDropReorderRepeatOrSplitDeclaredUnits() throws Exception{
        for(Consumer<ObjectNode> mutate:Arrays.<Consumer<ObjectNode>>asList(
                n->((ArrayNode)n.path("batches")).remove(2),
                n->((ObjectNode)n.path("batches").get(1)).put("ordinal",0),
                n->((ObjectNode)n.path("batches").get(1)).put("firstUnitOrdinal",0),
                n->((ArrayNode)n.path("batches").get(1).path("unitIds")).set(0,JsonUtils.mapper().getNodeFactory().textNode("heading")),
                n->((ObjectNode)n.path("canonicalClosure")).put("unitCount",2),
                n->((ObjectNode)n.path("batchPolicy")).put("maximumBatchBytes",NativeDocumentCollectionContract.MAX_BYTES+1),
                n->((ObjectNode)n.path("batchPolicy")).put("unitBoundary","split-table-cell"),
                n->n.putArray("chunks"))) {
            ObjectNode changed=NativeDocumentCollectionFixture.root();mutate.accept(changed);assertThrows(CanonicalEvidenceException.class,()->validate(changed));
        }
    }
    @Test void byteResealedForeignBatchAndUnitPayloadCannotReplaceSameDocumentObservation() throws Exception{
        for(Consumer<ObjectNode> mutate:Arrays.<Consumer<ObjectNode>>asList(
                n->n.put("documentId","999"),
                n->((ObjectNode)n.path("chunks").get(0)).put("sourceHash",String.join("",Collections.nCopies(64,"f"))),
                n->((ObjectNode)n.path("chunks").get(0).path("parts").get(0)).putNull("physicalPageNo"),
                n->((ObjectNode)n.path("unknowns").get(0).path("attributes")).put("semanticQuality","verified"),
                n->((ArrayNode)n.path("unknowns")).remove(0))) {
            ObjectNode root=NativeDocumentCollectionFixture.root();ObjectNode changed=(ObjectNode)JsonUtils.parse(new String(batch(1),StandardCharsets.UTF_8));mutate.accept(changed);
            byte[] raw=seal(changed).getBytes(StandardCharsets.UTF_8);ObjectNode description=(ObjectNode)root.path("batches").get(1);
            description.put("projectionSha256",changed.path("projectionSha256").asText());ObjectNode ref=(ObjectNode)description.path("artifactDescriptor");String sha=CanonicalJson.sha(raw);
            ref.put("sha256",sha);ref.put("byteCount",raw.length);ref.put("relativePath","sha256/"+sha.substring(0,2)+"/"+sha+".blob");
            JsonNode validated=validate(root);assertThrows(CanonicalEvidenceException.class,()->NativeDocumentCollectionContract.readChunks(validated,ordinal->ordinal==1?raw:batch(ordinal)));
        }
    }
    @Test void manifestAndEnvelopeUnknownCannotBePromotedByNewRootSeal() throws Exception{
        for(Consumer<ObjectNode> mutate:Arrays.<Consumer<ObjectNode>>asList(
                n->n.put("semanticScopeVerified",true),n->n.put("humanConfirmed",true),
                n->((ObjectNode)n.path("manifestMetadata").path("quality")).put("semanticQuality","verified"),
                n->((ObjectNode)n.path("envelopeRefs").path("canonical")).put("sourceHash",String.join("",Collections.nCopies(64,"f"))),
                n->((ObjectNode)n.path("manifestMetadata")).putArray("units"),
                n->((ObjectNode)n.path("manifestMetadata")).put("documentId","99"))) {
            ObjectNode changed=NativeDocumentCollectionFixture.root();mutate.accept(changed);assertThrows(CanonicalEvidenceException.class,()->validate(changed));
        }
    }
}
