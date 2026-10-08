package com.consense.service.vetting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** File-bound inspection fixtures exercise saved-artifact guards, not OCR quality. */
class VettingInspectionSourceRevisionTest {
    @TempDir Path workspace;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String DATASET="corrected-source42-native-v3-full44";
    private static final String BINDING="tmp/vetting_foundation_hardening/inspection-corrected-source42-20261004/completed_index_binding.json";
    private Map<String,Object> map(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private Map<String,Object> artifact(String name,Object value)throws Exception {
        Path path=workspace.resolve(name);Files.createDirectories(path.getParent());
        byte[] bytes=JSON.writeValueAsBytes(value);Files.write(path,bytes);
        StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));
        return map("path",path.toAbsolutePath().toString(),"bytes",bytes.length,"sha256",sha.toString());
    }
    private Map<String,Object> fixture(String status,String currentText,String revisedText,String snapshotProject)throws Exception {
        List<Double> bbox=Arrays.asList(.1,.2,.3,.4);
        Map<String,Object> block=map("id","b1","pageNo","P1","text",currentText,"bbox",bbox,"source","ocr");
        Map<String,Object> source=map("id",1,"fileName","fixture.pdf","pageCount",1,"ocrUsed",true,"parseStatus","PARTIAL","structuredContentJson",JSON.writeValueAsString(Arrays.asList(block)),"parseCoverageJson","{\"textAccuracy\":\"unverified\"}");
        Map<String,Object> sourceFile=artifact("fixtures/sources.json",Arrays.asList(source));
        String newHash=VettingCorpus.hash("\n"+source.get("structuredContentJson"));Map<String,Object> oldSource=new LinkedHashMap<>(source);oldSource.put("structuredContentJson",JSON.writeValueAsString(Arrays.asList(map("id","b1","pageNo","P1","text","raw OCR text","bbox",bbox,"source","ocr"))));String oldHash=VettingCorpus.hash("\n"+oldSource.get("structuredContentJson"));Map<String,Object> oldSourceFile=artifact("fixtures/old-sources.json",Arrays.asList(oldSource));
        Map<String,Object> corpusFile=artifact("fixtures/corpus.jsonl",map("id","p1","documentId",1,"fileName","fixture.pdf","role","standard","sourceHash",newHash,"content",currentText,"parts",Arrays.asList(map("blockId","b1","text",currentText,"startOffset",0,"endOffset",currentText.length()))));
        Map<String,Object> corpusManifest=artifact("fixtures/corpus-manifest.json",map("sourceDocuments",sourceFile,"corpus",corpusFile,"chunkCount",1));
        Map<String,Object> normalized=artifact("fixtures/normalized.json",map("fixture","normalized")),tooling=artifact("fixtures/tooling.json",map("fixture","software_identity"));
        Map<String,Object> plan=artifact("fixtures/plan.json",map("projectId","fixture-project","indexOnly",true,"normalizedParents",normalized,"inputs",map("rawCorpus",corpusFile,"corpusManifest",corpusManifest,"windowTooling",tooling),"requests",Collections.emptyList()));
        Map<String,Object> metadata=artifact("fixtures/metadata.json",map("windows",Arrays.asList(map("id","w1","parentId","p1","content",currentText))));
        Map<String,Object> result=artifact("fixtures/result.json",map("status",status,"actualIndexAttempts",1,"actualRetrievalAttempts",0,"actualGenerationCalls",0,"qdrantClose",map("completed",true),"plan",plan,"mainMetadata",metadata,"projectId","fixture-project","indexSignature","fixture-signature","parentCount",1,"indexResult",map("projectId","fixture-project","signature","fixture-signature","vectorPoints",1)));
        Map<String,Object> dispatch=artifact("fixtures/dispatch.json",map("exitCode",0,"oldStateAndBundleByteExact",true,"result",result));
        List<Object> state=Arrays.asList(map("relativePath","metadata.json","file",metadata));
        Map<String,Object> audit=artifact("fixtures/audit.json",map("protocol","independent-corrected-source-all-window-fresh-client-audit-v1","status","completed","scope","controlled_file_binding_fixture","inputs",map("producerResult",result,"productionPlan",plan,"mainMetadata",metadata,"rawCorpus",corpusFile,"normalizedParents",normalized,"windowTooling",tooling),"boundInputArtifacts",Arrays.asList(result,plan,metadata,corpusFile,normalized,tooling),"indexSignature","fixture-signature","parentCount",1,"windowCount",1,"observedPoints",1,"vectorElementwiseMismatchCount",0,"allParentRangesZeroGap",true,"allProducerStateBytesUnchanged",true,"failures",Collections.emptyList(),"freshClientCalls",map("constructor",1,"scroll",1,"close",1),"producerScope",map("indexAttempts",1,"queries",0),"collection","fixture-collection","producerStateBefore",state,"producerStateAfter",state));
        Map<String,Object> points=artifact("fixtures/points.jsonl",map("parentId","p1","points",Arrays.asList(map("id","w1","collection","fixture-collection","payload",map("content",currentText),"vectorPreview",Arrays.asList(.1,.2)))));
        Map<String,Object> snapshot=artifact("fixtures/snapshot.json",map("protocol","inspection-qdrant-snapshot-v1","status","completed","producerStateBeforeAfterUnchanged",true,"pointCount",1,"parentCount",1,"actualPointReadback",audit,"producerStateBefore",state,"producerStateAfter",state,"sourceStoreIdentity",map("projectId",snapshotProject,"signature","fixture-signature","collection","fixture-collection","producerResult",result,"productionPlan",plan,"canonicalCorpus",corpusFile),"parentPoints",points));
        Map<String,Object> revision=artifact("fixtures/revision.json",map("protocol","source-text-whole-block-override-result-v1","newSourceSnapshot",sourceFile,"oldSourceSnapshot",oldSourceFile,"documentRevisions",Arrays.asList(map("documentId",1,"oldSourceHash",oldHash,"newSourceHash",newHash)),"wholeBlockChanges",Arrays.asList(map("candidateId","fixture-correction","sourceBlockBinding",map("documentId",1,"physicalPage",1,"blockId","b1","oldBlockText","raw OCR text","oldBlockBbox",bbox),"newText",revisedText,"confidenceRefersToHistoricalOcr",true))));
        Map<String,Object> binding=map("protocol","completed-corrected-source-index-inspection-binding-v1","datasetId",DATASET,"result",result,"dispatchReceipt",dispatch,"freshClientAudit",audit,"vectorSnapshot",snapshot,"sourceRevision",revision,"productionPlan",plan,"canonicalCorpus",corpusFile,"sourceDocuments",sourceFile);
        artifact(BINDING,binding);return binding;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> correctedCatalog(VettingInspectionService service){
        return ((List<Map<String,Object>>)service.catalog().get("datasets")).stream().filter(d->DATASET.equals(d.get("id"))).findFirst().orElseThrow(()->new AssertionError("Missing corrected dataset"));
    }
    @Test void onlyClosedCompletedIndexAndItsOwnSnapshotExposeRevisedBlockWithoutPromotingQuality()throws Exception {
        fixture("completed","Revised text 🧭","Revised text 🧭","fixture-project");
        VettingInspectionService service=new VettingInspectionService(workspace);
        assertEquals("available",correctedCatalog(service).get("status"));
        Map<String,Object> chunk=service.chunk(DATASET,"p1");assertEquals("available",chunk.get("vectorSnapshotStatus"));
        assertEquals("Revised text 🧭",JSON.valueToTree(chunk).path("chunk").path("content").asText());
        Map<String,Object> page=service.page(DATASET,"1",1);
        assertEquals("unverified",JSON.valueToTree(page).path("quality").path("textAccuracy").asText());
        assertEquals("raw OCR text",JSON.valueToTree(page).path("textRevisions").get(0).path("oldText").asText());
        assertEquals("Revised text 🧭",JSON.valueToTree(page).path("textRevisions").get(0).path("newText").asText());
        assertFalse(JSON.valueToTree(page).path("textRevisions").get(0).path("humanConfirmed").asBoolean());
        assertEquals(0,service.runs().size(),"A persisted index must not create a retrieval run");
    }
    @Test void failedIndexIsUnavailableEvenWithAnOtherwiseBoundPointFile()throws Exception {
        fixture("failed","body","body","fixture-project");VettingInspectionService service=new VettingInspectionService(workspace);
        assertEquals("unavailable",correctedCatalog(service).get("status"));assertThrows(IOException.class,()->service.chunk(DATASET,"p1"));
    }
    @Test void snapshotFromAnotherProjectCannotMasqueradeAsTheCorrectedIndex()throws Exception {
        fixture("completed","body","body","another-project");VettingInspectionService service=new VettingInspectionService(workspace);
        assertEquals("unavailable",correctedCatalog(service).get("status"));assertThrows(IOException.class,()->service.page(DATASET,"1",1));
    }
    @Test void correctionMustMatchTheActuallyIndexedCompletePageBlock()throws Exception {
        fixture("completed","actual revised body","different claimed correction","fixture-project");VettingInspectionService service=new VettingInspectionService(workspace);
        assertThrows(IOException.class,()->service.page(DATASET,"1",1));
    }
    @Test void changedBoundResultIsNotHiddenByTheCatalogCache()throws Exception {
        Map<String,Object> binding=fixture("completed","body","body","fixture-project");VettingInspectionService service=new VettingInspectionService(workspace);
        assertEquals("available",correctedCatalog(service).get("status"));
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)binding.get("result");
        Files.write(Paths.get((String)result.get("path")),"{}".getBytes(StandardCharsets.UTF_8));
        assertEquals("unavailable",correctedCatalog(service).get("status"));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> readArtifact(Map<String,Object> binding,String key)throws Exception {return JSON.readValue(Paths.get((String)((Map<String,Object>)binding.get(key)).get("path")).toFile(),Map.class);}
    private void replaceBinding(Map<String,Object> binding,String key,Object value)throws Exception {binding.put(key,artifact("fixtures/replaced-"+key+".json",value));artifact(BINDING,binding);}
    @Test void failedFreshClientAuditCannotBeUsedAsCompletedIndexProof()throws Exception {
        Map<String,Object> b=fixture("completed","body","body","fixture-project"),audit=readArtifact(b,"freshClientAudit");audit.put("status","failed");replaceBinding(b,"freshClientAudit",audit);assertEquals("unavailable",correctedCatalog(new VettingInspectionService(workspace)).get("status"));
    }
    @Test void freshClientAuditFromAnotherProducerCannotBeBorrowed()throws Exception {
        Map<String,Object> b=fixture("completed","body","body","fixture-project"),audit=readArtifact(b,"freshClientAudit");audit.put("inputs",map("producerResult",artifact("fixtures/foreign-result.json",map("status","completed"))));replaceBinding(b,"freshClientAudit",audit);assertEquals("unavailable",correctedCatalog(new VettingInspectionService(workspace)).get("status"));
    }
    @SuppressWarnings("unchecked") @Test void missingVectorSidecarCannotLeaveCatalogAvailable()throws Exception {
        Map<String,Object> b=fixture("completed","body","body","fixture-project"),snapshot=readArtifact(b,"vectorSnapshot");Files.delete(Paths.get((String)((Map<String,Object>)snapshot.get("parentPoints")).get("path")));assertEquals("unavailable",correctedCatalog(new VettingInspectionService(workspace)).get("status"));
    }
    @SuppressWarnings("unchecked") @Test void changedNestedSourceSnapshotIsRejectedAfterCatalogWasCached()throws Exception {
        Map<String,Object> b=fixture("completed","body","body","fixture-project");VettingInspectionService service=new VettingInspectionService(workspace);assertEquals("available",correctedCatalog(service).get("status"));Map<String,Object> revision=readArtifact(b,"sourceRevision"),source=(Map<String,Object>)revision.get("newSourceSnapshot");Files.write(Paths.get((String)source.get("path")),"[]".getBytes(StandardCharsets.UTF_8));assertEquals("unavailable",correctedCatalog(service).get("status"));
        long count=((List<Map<String,Object>>)service.catalog().get("datasets")).stream().filter(d->DATASET.equals(d.get("id"))).count();assertEquals(1,count,"One unavailable row must replace the old available identity");
    }
    @SuppressWarnings("unchecked") @Test void displayedBeforeTextMustMatchTheBoundHistoricalBlock()throws Exception {
        Map<String,Object> b=fixture("completed","body","body","fixture-project"),revision=readArtifact(b,"sourceRevision");List<Map<String,Object>> changes=(List<Map<String,Object>>)revision.get("wholeBlockChanges");((Map<String,Object>)changes.get(0).get("sourceBlockBinding")).put("oldBlockText","fabricated old transcription");replaceBinding(b,"sourceRevision",revision);assertThrows(IOException.class,()->new VettingInspectionService(workspace).page(DATASET,"1",1));
    }
}
