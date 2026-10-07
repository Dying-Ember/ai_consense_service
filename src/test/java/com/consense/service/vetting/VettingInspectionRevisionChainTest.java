package com.consense.service.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic saved-artifact chains; no parser, OCR, vector client, model or supplier call. */
class VettingInspectionRevisionChainTest {
    @TempDir Path workspace;
    private static final ObjectMapper J=new ObjectMapper();
    private static final String ID="corrected-source44-native-v3-full44", BINDING="tmp/vetting_foundation_hardening/inspection-corrected-source44-20261004/completed_index_binding.json";
    private Map<String,Object> map(Object...pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private String hash(String s){return VettingCorpus.hash(s);}
    private Map<String,Object> artifact(String name,Object data)throws Exception {
        Path p=workspace.resolve(name);Files.createDirectories(p.getParent());byte[] b=J.writeValueAsBytes(data);Files.write(p,b);
        StringBuilder sha=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(b))sha.append(String.format("%02x",v&255));return map("path",p.toString(),"bytes",b.length,"sha256",sha.toString());
    }
    private Map<String,Object> block(int n,String text){return map("id","b"+n,"pageNo","P1","text",text,"bbox",Arrays.asList(.1,.2,.3,.4),"source","ocr");}
    private Map<String,Object> source(List<Object> blocks)throws Exception {return map("id",1,"fileName","unknown-new-material.pdf","reviewRole","standard","projectId","source-project","fileKey","OTHER","pageCount",1,"ocrUsed",true,"parseStatus","PARTIAL","structuredContentJson",J.writeValueAsString(blocks),"parseCoverageJson","{\"textAccuracy\":\"unverified\",\"ocrQualityStatus\":\"needs_review\",\"ocrQualityPageScopeUnknown\":true}");}
    private String sourceHash(Map<String,Object> d){return hash("\n"+d.get("structuredContentJson"));}
    private Map<String,Object> change(int n,String old,String next){return map("candidateId","correction-"+n+"-"+next,"sourceBlockBinding",map("documentId",1,"physicalPage",1,"blockId","b"+n,"oldBlockText",old,"oldBlockBbox",Arrays.asList(.1,.2,.3,.4)),"newText",next,"confidenceRefersToHistoricalOcr",true);}
    private Map<String,Object> revision(String name,Map<String,Object> oldFile,Map<String,Object> newFile,Map<String,Object> old,Map<String,Object> next,List<Object> changes)throws Exception {
        return artifact(name,map("protocol","source-text-whole-block-override-result-v1","oldSourceSnapshot",oldFile,"newSourceSnapshot",newFile,"updatedBlockCount",changes.size(),"documentRevisions",Arrays.asList(map("documentId",1,"oldSourceHash",sourceHash(old),"newSourceHash",sourceHash(next))),"wholeBlockChanges",changes));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> fixture()throws Exception {
        List<Object> a=new ArrayList<>(),b=new ArrayList<>(),c=new ArrayList<>(),first=new ArrayList<>(),second=new ArrayList<>();
        for(int n=1;n<=43;n++) {String old="old "+n,mid=n<=42?"middle "+n:old,last=n==1?"final 🧭":n==43?"final 43":mid;a.add(block(n,old));b.add(block(n,mid));c.add(block(n,last));if(n<=42)first.add(change(n,old,mid));if(n==1||n==43)second.add(change(n,mid,last));}
        Map<String,Object> old=source(a),middle=source(b),current=source(c);
        Map<String,Object> oldFile=artifact("f/original.json",Arrays.asList(old)),middleFile=artifact("f/source42.json",Arrays.asList(middle)),currentFile=artifact("f/source44.json",Arrays.asList(current));
        Map<String,Object> r1=revision("f/revision42.json",oldFile,middleFile,old,middle,first),r2=revision("f/revision44.json",middleFile,currentFile,middle,current,second);
        Map<String,Object> corpus=artifact("f/corpus.jsonl",map("id","p1","documentId",1,"fileName","unknown-new-material.pdf","role","standard","sourceHash",sourceHash(current),"content","final 🧭","parts",Arrays.asList(map("blockId","b1","text","final 🧭","startOffset",0,"endOffset",8))));
        Map<String,Object> manifest=artifact("f/corpus-manifest.json",map("sourceDocuments",currentFile,"corpus",corpus,"chunkCount",1));
        Map<String,Object> normalized=artifact("f/normalized.json",map("fixture","normalized")),tooling=artifact("f/tooling.json",map("fixture","bound_software"));
        Map<String,Object> plan=artifact("f/plan.json",map("projectId","fresh44-project","indexOnly",true,"normalizedParents",normalized,"inputs",map("rawCorpus",corpus,"corpusManifest",manifest,"windowTooling",tooling),"requests",Collections.emptyList()));
        Map<String,Object> metadata=artifact("f/metadata.json",map("windows",Arrays.asList(map("id","w1","parentId","p1","content","final 🧭"))));
        Map<String,Object> result=artifact("f/result.json",map("status","completed","actualIndexAttempts",1,"actualRetrievalAttempts",0,"actualGenerationCalls",0,"qdrantClose",map("completed",true),"plan",plan,"mainMetadata",metadata,"projectId","fresh44-project","indexSignature","fresh44-signature","parentCount",1,"indexResult",map("projectId","fresh44-project","signature","fresh44-signature","vectorPoints",1)));
        Map<String,Object> dispatch=artifact("f/dispatch.json",map("exitCode",0,"oldStateAndBundleByteExact",true,"result",result));List<Object> state=Arrays.asList(map("relativePath","metadata.json","file",metadata));
        Map<String,Object> audit=artifact("f/audit.json",map("protocol","independent-corrected-source-all-window-fresh-client-audit-v1","status","completed","inputs",map("producerResult",result,"productionPlan",plan,"mainMetadata",metadata,"rawCorpus",corpus,"normalizedParents",normalized,"windowTooling",tooling),"boundInputArtifacts",Arrays.asList(result,plan,metadata,corpus,normalized,tooling),"indexSignature","fresh44-signature","parentCount",1,"windowCount",1,"observedPoints",1,"vectorElementwiseMismatchCount",0,"allParentRangesZeroGap",true,"allProducerStateBytesUnchanged",true,"failures",Collections.emptyList(),"freshClientCalls",map("constructor",1,"scroll",1,"close",1),"producerScope",map("indexAttempts",1,"queries",0),"collection","fresh44-collection","producerStateBefore",state,"producerStateAfter",state));
        Map<String,Object> points=artifact("f/points.jsonl",map("parentId","p1","points",Arrays.asList(map("id","w1","collection","fresh44-collection","payload",map("content","final 🧭"),"vectorPreview",Arrays.asList(.1,.2)))));
        Map<String,Object> snapshot=artifact("f/snapshot.json",map("protocol","inspection-qdrant-snapshot-v1","status","completed","producerStateBeforeAfterUnchanged",true,"pointCount",1,"parentCount",1,"actualPointReadback",audit,"producerStateBefore",state,"producerStateAfter",state,"sourceStoreIdentity",map("projectId","fresh44-project","signature","fresh44-signature","collection","fresh44-collection","producerResult",result,"productionPlan",plan,"canonicalCorpus",corpus),"parentPoints",points));
        Map<String,Object> binding=map("protocol","completed-corrected-source-index-inspection-binding-v1","datasetId",ID,"result",result,"dispatchReceipt",dispatch,"freshClientAudit",audit,"vectorSnapshot",snapshot,"sourceRevision",r2,"sourceRevisionChain",Arrays.asList(r1,r2),"cumulativeRevisionCount",44,"productionPlan",plan,"canonicalCorpus",corpus,"sourceDocuments",currentFile);artifact(BINDING,binding);return binding;
    }
    private Map<String,Object> readArtifact(Map<String,Object> d)throws Exception{return J.readValue(Paths.get((String)d.get("path")).toFile(),Map.class);}
    @SuppressWarnings("unchecked") private Map<String,Object> nested(Map<String,Object> b,String key)throws Exception{return readArtifact((Map<String,Object>)b.get(key));}
    private void bind(Map<String,Object> binding)throws Exception {artifact(BINDING,binding);}
    @SuppressWarnings("unchecked") private String status(VettingInspectionService service){return ((List<Map<String,Object>>)service.catalog().get("datasets")).stream().filter(d->ID.equals(d.get("id"))).findFirst().get().get("status").toString();}
    @Test void cumulativeChainKeeps42HistoricChangesThen2NewAndPreservesUnknownQualityWithoutBorrowingRetrieval()throws Exception {
        fixture();VettingInspectionService s=new VettingInspectionService(workspace);assertEquals("available",status(s));JsonNode page=J.valueToTree(s.page(ID,"1",1));
        assertEquals(44,page.path("textRevisions").size());assertEquals("old 1",page.path("textRevisions").get(0).path("oldText").asText());assertEquals("middle 1",page.path("textRevisions").get(0).path("newText").asText());assertEquals("middle 1",page.path("textRevisions").get(42).path("oldText").asText());assertEquals("final 🧭",page.path("textRevisions").get(42).path("newText").asText());assertEquals(2,page.path("textRevisions").get(42).path("revisionStep").asInt());assertEquals(44,page.path("textRevisions").get(0).path("cumulativeRevisionCount").asInt());assertEquals("needs_review",page.path("quality").path("ocrQualityStatus").asText());assertFalse(page.path("textRevisions").get(0).path("humanConfirmed").asBoolean());assertTrue(s.runs().isEmpty());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->s.tasks("corrected-source42-current16",1,10));
    }
    @SuppressWarnings("unchecked") @Test void chainCannotSkip42AndJoinAnUnrelatedOriginalSnapshot()throws Exception {
        Map<String,Object> b=fixture();List<Map<String,Object>> chain=(List<Map<String,Object>>)b.get("sourceRevisionChain");Map<String,Object> first=readArtifact(chain.get(0)),last=readArtifact(chain.get(1));last.put("oldSourceSnapshot",first.get("oldSourceSnapshot"));Map<String,Object> replacement=artifact("f/wrong-link.json",last);chain.set(1,replacement);b.put("sourceRevision",replacement);bind(b);assertEquals("unavailable",status(new VettingInspectionService(workspace)));
    }
    @Test void source42DatasetIdentityCannotStandInForFresh44()throws Exception {
        Map<String,Object> b=fixture();b.put("datasetId","corrected-source42-native-v3-full44");bind(b);assertEquals("unavailable",status(new VettingInspectionService(workspace)));
    }
    @Test void incompleteIndexCannotBeRegistered()throws Exception {
        Map<String,Object> b=fixture(),r=nested(b,"result");r.put("status","running");b.put("result",artifact("f/running.json",r));bind(b);assertEquals("unavailable",status(new VettingInspectionService(workspace)));
    }
    @Test void missingReadbackPointOrChangedProjectCannotMasqueradeAsFresh44()throws Exception {
        Map<String,Object> b=fixture(),a=nested(b,"freshClientAudit");a.put("observedPoints",0);b.put("freshClientAudit",artifact("f/partial-audit.json",a));bind(b);assertEquals("unavailable",status(new VettingInspectionService(workspace)));
    }
    @Test void finalRevisionCannotPointBackToSource42Snapshot()throws Exception {
        Map<String,Object> b=fixture();@SuppressWarnings("unchecked") List<Map<String,Object>> chain=(List<Map<String,Object>>)b.get("sourceRevisionChain");b.put("sourceRevision",chain.get(0));bind(b);assertEquals("unavailable",status(new VettingInspectionService(workspace)));
    }
    @Test void cumulativeCountMustMatchActualStageRecords()throws Exception {
        Map<String,Object> b=fixture();b.put("cumulativeRevisionCount",2);bind(b);assertEquals("unavailable",status(new VettingInspectionService(workspace)));
    }
    @SuppressWarnings("unchecked") @Test void historicBeforeTextStillMustMatchBoundIntermediateSnapshot()throws Exception {
        Map<String,Object> b=fixture();List<Map<String,Object>> chain=(List<Map<String,Object>>)b.get("sourceRevisionChain");Map<String,Object> first=readArtifact(chain.get(0));List<Map<String,Object>> changes=(List<Map<String,Object>>)first.get("wholeBlockChanges");((Map<String,Object>)changes.get(0).get("sourceBlockBinding")).put("oldBlockText","invented before");chain.set(0,artifact("f/false-history.json",first));bind(b);assertThrows(IOException.class,()->new VettingInspectionService(workspace).page(ID,"1",1));
    }
    @Test void immutableDatasetCannotChangeReceiptAfterRegistration()throws Exception {
        Map<String,Object> b=fixture();VettingInspectionService s=new VettingInspectionService(workspace);assertEquals("available",status(s));b.put("scope","changed same ID");bind(b);assertEquals("unavailable",status(s));
    }
}
