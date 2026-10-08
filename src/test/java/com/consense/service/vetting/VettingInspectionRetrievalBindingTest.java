package com.consense.service.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Only controlled file fixtures. These tests perform no retrieval, inference, HTTP, service start or database access. */
class VettingInspectionRetrievalBindingTest {
    @TempDir Path workspace;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> ROLES=Arrays.asList("tender","standard","project_fact","package_manifest");
    private ObjectNode object(Object... fields){ObjectNode n=JSON.createObjectNode();for(int i=0;i<fields.length;i+=2)n.set((String)fields[i],JSON.valueToTree(fields[i+1]));return n;}
    private ObjectNode artifact(String name,JsonNode node)throws Exception {
        Path path=workspace.resolve(name);Files.createDirectories(path.getParent());byte[] bytes=JSON.writeValueAsBytes(node);Files.write(path,bytes);
        StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format("%02x",b&255));
        return object("path",path.toAbsolutePath().toString(),"bytes",bytes.length,"sha256",hex.toString());
    }
    private final class Fixture {
        ObjectNode binding,indexBinding,index,indexPlan,plan,raw,terminal,preparation,started,source44Final,worker;
        ArrayNode canonical,rows,operations;
        List<ObjectNode> saved=new ArrayList<>();Path indexBindingPath;
        void updateResult()throws Exception {
            raw.set("operations",operations);
            ObjectNode result=artifact("query/result.json",raw);binding.set("result",result);terminal.set("actualResult",result);
            binding.set("dispatchReceipt",artifact("query/terminal.json",terminal));
        }
        void updatePlan()throws Exception {
            plan.set("requests",rows);
            ObjectNode descriptor=artifact("query/plan.json",plan);binding.set("preparedPlan",descriptor);raw.set("plan",descriptor);
            preparation.set("prepared",descriptor);started.set("preparation",artifact("query/preparation.json",preparation));
            terminal.set("started",artifact("query/started.json",started));updateResult();
        }
        void updateQuery(int i)throws Exception {((ObjectNode)operations.get(i)).set("artifact",artifact("query/query-"+(i+1)+".json",saved.get(i)));updateResult();}
        void updateSource44Result()throws Exception {
            raw.set("operations",operations);binding.set("result",artifact("query/result.json",raw));
            terminal.set("plan",binding.path("preparedPlan"));worker.set("plan",binding.path("preparedPlan"));terminal.set("workerStarted",artifact("query/worker.json",worker));
            binding.set("dispatchReceipt",artifact("query/source44-terminal.json",terminal));
            source44Final.set("actualResult",binding.path("result"));source44Final.set("ownedTerminal",binding.path("dispatchReceipt"));source44Final.set("actualWorker",terminal.path("workerStarted"));
            source44Final.set("plan",binding.path("preparedPlan"));source44Final.set("actualProbeManifest",raw.path("pipelineProbeManifest"));
            binding.set("finalReceipt",artifact("query/source44-final.json",source44Final));
        }
        void updateSource44Plan()throws Exception {plan.set("requests",rows);binding.set("preparedPlan",artifact("query/plan.json",plan));raw.set("plan",binding.path("preparedPlan"));updateSource44Result();}
        void updateSource44Query(int i)throws Exception {((ObjectNode)operations.get(i+1)).set("artifact",artifact("query/query-"+(i+1)+".json",saved.get(i)));updateSource44Result();}
        VettingInspectionRetrievalBinding.Bound validateSource44()throws Exception {
            return VettingInspectionRetrievalBinding.validateSource44(binding,indexBindingPath,indexBinding,index,indexPlan,new VettingInspectionRetrievalBinding.Artifacts(){
                public Path descriptor(JsonNode d)throws IOException {
                    try{Path p=Paths.get(d.path("path").asText()).toAbsolutePath().normalize();
                        if(!p.startsWith(workspace)||!Files.isRegularFile(p)||!d.path("bytes").isIntegralNumber()||Files.size(p)!=d.path("bytes").asLong())throw new IOException("File scope/size differs");
                        byte[] bytes=Files.readAllBytes(p);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));
                        if(!sha.toString().equals(d.path("sha256").asText()))throw new IOException("File hash differs");return p;
                    }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
                }
                public JsonNode read(Path path)throws IOException{return JSON.readTree(path.toFile());}
            });
        }
        VettingInspectionRetrievalBinding.Bound validate()throws Exception {
            return VettingInspectionRetrievalBinding.validate(binding,indexBindingPath,indexBinding,index,indexPlan,new VettingInspectionRetrievalBinding.Artifacts(){
                public Path descriptor(JsonNode d)throws IOException {
                    try{
                        Path p=Paths.get(d.path("path").asText()).toAbsolutePath().normalize();
                        if(!p.startsWith(workspace)||!Files.isRegularFile(p)||!d.path("bytes").isIntegralNumber()||Files.size(p)!=d.path("bytes").asLong())throw new IOException("File scope/size differs");
                        byte[] bytes=Files.readAllBytes(p);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));
                        if(!sha.toString().equals(d.path("sha256").asText()))throw new IOException("File hash differs");return p;
                    }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
                }
                public JsonNode read(Path path)throws IOException{return JSON.readTree(path.toFile());}
            });
        }
    }
    private Fixture fixture()throws Exception {
        Fixture f=new Fixture();ObjectNode sources=artifact("index/sources.json",object("source","current-source-revision"));
        ObjectNode corpus=artifact("index/corpus.jsonl",object("sourceHash","current-source-hash"));
        ObjectNode manifest=artifact("index/corpus-manifest.json",object("sourceDocuments",sources,"corpus",corpus));
        ObjectNode tooling=artifact("index/tooling.json",object("algorithm","fixture-source-bound-windows"));
        f.canonical=JSON.createArrayNode();
        String text="whole selected text | 🧭\nempty cell is retained";
        for(String role:ROLES){
            ObjectNode slice=object("cellSlices",Arrays.asList(object("columnIndex",0,"text","whole selected text | 🧭"),object("columnIndex",1,"text","")));
            ObjectNode part=object("blockId","row-"+role,"startOffset",0,"endOffset",text.length(),"text",text,"tableSlice",slice);
            f.canonical.add(object("id","parent-"+role,"role",role,"documentId",17,"sourceHash","current-source-hash-"+role,
                    "content",text,"pageNo",12,"nativeTableMetadataVersion","native-cell-slices-portable-v3","sourceQualityHash","current-quality","parts",Collections.singletonList(part)));
        }
        ObjectNode normalized=artifact("index/normalized.json",f.canonical),metadata=artifact("index/metadata.json",object("signature","current-signature"));
        f.indexPlan=object("projectId","current-project","indexOnly",true,"normalizedCorpusSha256","current-canonical-signature","normalizedParents",normalized,
                "inputs",object("rawCorpus",corpus,"corpusManifest",manifest,"windowTooling",tooling),"requests",Collections.emptyList());
        ObjectNode indexPlan=artifact("index/plan.json",f.indexPlan);
        f.index=object("status","completed","projectId","current-project","indexSignature","current-signature","parentCount",4,"plan",indexPlan,"mainMetadata",metadata);
        ObjectNode indexResult=artifact("index/result.json",f.index);
        f.indexBinding=object("protocol","completed-corrected-source-index-inspection-binding-v1","datasetId",VettingInspectionRetrievalBinding.DATASET,"result",indexResult,"sourceDocuments",sources);
        ObjectNode indexBinding=artifact("index/binding.json",f.indexBinding);f.indexBindingPath=Paths.get(indexBinding.path("path").asText());
        ArrayNode state=JSON.createArrayNode().add(object("relativePath","metadata.json","file",metadata));
        ObjectNode copy=artifact("query/copy.json",object("protocol","closed-producer-state-query-copy-v1","producerBefore",state,"copiedBefore",state,"allFilesByteExact",true,"indexUpsertAuthorized",false));
        f.rows=JSON.createArrayNode();ArrayNode oldRows=JSON.createArrayNode();int[] oldStarts={5,21,69,85};
        for(int group=0;group<4;group++)for(int role=0;role<4;role++) {
            int ordinal=group*4+role+1, topic=(group%2==0?2:6);String profile=(group<2?"primary":"coverage100x20");
            ObjectNode oldRequest=object("projectId","old-project","query","generic task "+topic,"role",ROLES.get(role),"candidates",group<2?50:100,"limit",group<2?10:20);
            ObjectNode old=object("ordinal",oldStarts[group]+role,"originalTopicOrdinal",topic,"request",oldRequest,"profileId",profile,
                    "historicalRequest",oldRequest,"sourceScope","original-scope-"+topic,"sourceProvenance",object("run","original-run","call","original-run/"+topic+"/"+ROLES.get(role)));
            oldRows.add(old);ObjectNode row=old.deepCopy();row.put("ordinal",ordinal);row.put("originalActualRequestOrdinal",oldStarts[group]+role);
            ((ObjectNode)row.path("request")).put("projectId","current-project");f.rows.add(row);
        }
        ObjectNode oldPlan=artifact("old/plan.json",object("requests",oldRows));
        f.plan=object("protocol","corrected-source42-topic2-6-query-only-plan-v1","status","prepared_no_inference","projectId","current-project",
                "parentCount",4,"indexSignature","current-signature","expectedMainIndexCalls",0,"expectedMainQueryCalls",16,"groupCount",4,"concurrency",1,"retries",0,
                "contract","source-bound-window-points-full-parent-results-v1","previousActualIndex",indexResult,"previousIndexPlan",indexPlan,"publishedInspectionBinding",indexBinding,
                "normalizedParents",normalized,"normalizedCorpusSha256","current-canonical-signature","correctedSourceDocuments",sources,
                "producerMetadata",metadata,"producerStateInventory",state,"historicalQueryTemplate",oldPlan,"inputs",f.indexPlan.path("inputs"),"requests",f.rows);
        ObjectNode prepared=artifact("query/plan.json",f.plan),software=artifact("query/software.json",object("script","synthetic-query-tool"));
        f.operations=JSON.createArrayNode();
        for(int i=0;i<16;i++) {
            ObjectNode response=object("projectId","current-project","indexSignature","current-signature","mode","source-bound-window-points-full-parent-results-v1",
                    "hits",Arrays.asList(object("id","parent-"+ROLES.get(i%4),"score",.6,"payload",f.canonical.get(i%4))));
            ObjectNode saved=object("request",f.rows.get(i),"response",response,"allReturnedParentsExact",true);f.saved.add(saved);
            f.operations.add(object("operation","query","ordinal",i+1,"originalTopicOrdinal",f.rows.get(i).path("originalTopicOrdinal"),"artifact",artifact("query/query-"+(i+1)+".json",saved)));
        }
        f.raw=object("protocol","offline-gpu-source-window-retrieval-result-v1","status","completed","queryOnly",true,"projectId","current-project","indexSignature","current-signature",
                "parentCount",4,"actualRetrievalAttempts",16,"actualIndexAttempts",0,"actualGenerationCalls",0,"actualHTTP",0,"actualApplicationDB",0,"actualOCR",0,"actualDocumentParse",0,
                "qdrantClose",object("attempted",true,"completed",true),"originalProducerStateBeforeAfterByteExact",true,"indexResult",null,
                "actualEncodedFloat32Groups",Collections.emptyList(),"actualWindowUpsertGroups",Collections.emptyList(),"plan",prepared,"previousActualIndex",indexResult,
                "mainMetadata",metadata,"stateCopyProvenance",copy,"originalProducerStateAfter",state,"software",software,"operations",f.operations,
                "pipelineProbeManifest",artifact("query/probes.json",object("events",Collections.emptyList())));
        ArrayNode probeEvents=JSON.createArrayNode();
        for(int i=0;i<16;i++)for(String stage:Arrays.asList("dense-parent-candidates","parent-bm25-candidates","parent-rrf-candidates","reranker-output")) {
            ObjectNode payload=object("orderedParents",Collections.emptyList(),"allScopeScores",Collections.emptyList(),"orderedCandidateIds",Collections.emptyList(),"allFusedInOrder",Collections.emptyList(),"candidateIdentitiesInInputOrder",Collections.emptyList(),"scoresInCandidateOrder",Collections.emptyList());
            String name="probe-"+(i+1)+"-"+stage+".json";
            ObjectNode d=artifact("query/"+name,object("context",object("operation","query","scope","main","queryOrdinal",i+1,"projectId","current-project","profileId",f.rows.get(i).path("profileId"),"originalTopicOrdinal",f.rows.get(i).path("originalTopicOrdinal")),"payload",payload));
            ObjectNode relative=d.deepCopy();relative.remove("path");relative.put("relativePath",name);probeEvents.add(object("stage",stage,"artifact",relative));
        }
        f.raw.set("pipelineProbeManifest",artifact("query/probes.json",object("events",probeEvents)));
        f.preparation=object("prepared",prepared,"driver",software);
        f.started=object("protocol","root-corrected42-query16-dispatch-v1","status","started","pid",1234,"preparation",artifact("query/preparation.json",f.preparation));
        f.terminal=object("protocol","root-corrected42-query16-dispatch-v1","status","child_exited","exitCode",0,"pid",1234,"started",artifact("query/started.json",f.started));
        f.binding=object("protocol",VettingInspectionRetrievalBinding.PROTOCOL,"runId",VettingInspectionRetrievalBinding.RUN,"datasetId",VettingInspectionRetrievalBinding.DATASET,
                "indexBinding",indexBinding,"preparedPlan",prepared,"canonicalCorpus",corpus,"normalizedParents",normalized,"sourceDocuments",sources);
        f.updateResult();return f;
    }

    @Test void exactSixteenQueriesAndFullCurrentNativePayloadBindWithoutAnIndexOrGenerationCall()throws Exception {
        Fixture f=fixture();VettingInspectionRetrievalBinding.Bound bound=f.validate();assertEquals(16,bound.raw.path("operations").size());
        assertEquals(f.indexPlan.path("inputs").path("rawCorpus"),bound.plan.path("inputs").path("rawCorpus"));assertEquals(0,bound.raw.path("actualGenerationCalls").asInt());
    }
    @Test void oldProjectCannotMasqueradeAsTheCorrectedQueryRun()throws Exception {Fixture f=fixture();f.raw.put("projectId","old-project");f.updateResult();assertThrows(IOException.class,f::validate);}
    @Test void oldSourceSnapshotCannotMasqueradeAsCurrentSourceDocuments()throws Exception {Fixture f=fixture();f.plan.set("correctedSourceDocuments",artifact("old/source.json",object("old","source")));f.updatePlan();assertThrows(IOException.class,f::validate);}
    @Test void partialResultCannotCreateAnInspectionRun()throws Exception {Fixture f=fixture();f.raw.put("status","running");f.updateResult();assertThrows(IOException.class,f::validate);}
    @Test void resultPresenceWithoutTheSuccessfulTerminalIsRejected()throws Exception {Fixture f=fixture();f.terminal.put("exitCode",1);f.updateResult();assertThrows(IOException.class,f::validate);}
    @Test void missingWrapperTerminalIsRejected()throws Exception {Fixture f=fixture();Files.delete(Paths.get(f.binding.path("dispatchReceipt").path("path").asText()));assertThrows(IOException.class,f::validate);}
    @Test void unclosedQueryClientIsRejected()throws Exception {Fixture f=fixture();f.raw.with("qdrantClose").put("completed",false);f.updateResult();assertThrows(IOException.class,f::validate);}
    @Test void fifteenReportedQueriesCannotMasqueradeAsSixteen()throws Exception {Fixture f=fixture();f.raw.put("actualRetrievalAttempts",15);f.updateResult();assertThrows(IOException.class,f::validate);}
    @Test void duplicateQueryOperationCannotHideAMissingRole()throws Exception {Fixture f=fixture();((ObjectNode)f.operations.get(15)).put("ordinal",15);f.updateResult();assertThrows(IOException.class,f::validate);}
    @Test void priorIndexSignatureCannotBeBorrowed()throws Exception {Fixture f=fixture();f.saved.get(0).with("response").put("indexSignature","old-signature");f.updateQuery(0);assertThrows(IOException.class,f::validate);}
    @Test void alteredQueryOrFilterIsNotAProjectOnlyRebind()throws Exception {Fixture f=fixture();((ObjectNode)f.rows.get(0).path("request")).put("query","different query");f.updatePlan();assertThrows(IOException.class,f::validate);}
    @Test void unchangedIdDoesNotAllowModifiedNativeCellsOrBody()throws Exception {Fixture f=fixture();((ObjectNode)f.saved.get(0).path("response").path("hits").get(0).path("payload")).put("content","invented body");f.updateQuery(0);assertThrows(IOException.class,f::validate);}
    @Test void unchangedIdDoesNotAllowModifiedQualityOrSourceHash()throws Exception {Fixture f=fixture();((ObjectNode)f.saved.get(0).path("response").path("hits").get(0).path("payload")).put("sourceHash","old-source-hash");f.updateQuery(0);assertThrows(IOException.class,f::validate);}
    @Test void foreignParentCannotBePresentedAsCurrent()throws Exception {Fixture f=fixture();ObjectNode hit=(ObjectNode)f.saved.get(0).path("response").path("hits").get(0);hit.put("id","foreign");((ObjectNode)hit.path("payload")).put("id","foreign");f.updateQuery(0);assertThrows(IOException.class,f::validate);}
    @Test void fakeGenerationOrUpsertIsRejectedEvenWithValidQueryFiles()throws Exception {Fixture f=fixture();f.raw.put("actualGenerationCalls",1);f.updateResult();assertThrows(IOException.class,f::validate);}
    @Test void emptyOrMixedCanonicalSourceDoesNotPassFullPayloadBinding()throws Exception {Fixture f=fixture();f.canonical.remove(0);f.plan.set("normalizedParents",artifact("query/wrong-canonical.json",f.canonical));f.updatePlan();assertThrows(IOException.class,f::validate);}
    @Test void missingOneRankingStageCannotBePresentedAsAnInspectableCompleteRun()throws Exception {Fixture f=fixture();JsonNode probes=JSON.readTree(Paths.get(f.raw.path("pipelineProbeManifest").path("path").asText()).toFile());((ArrayNode)probes.path("events")).remove(0);f.raw.set("pipelineProbeManifest",artifact("query/probes.json",probes));f.updateResult();assertThrows(IOException.class,f::validate);}
    @Test void rankingProbeCannotBorrowAStageFromAnotherQueryProject()throws Exception {Fixture f=fixture();JsonNode probes=JSON.readTree(Paths.get(f.raw.path("pipelineProbeManifest").path("path").asText()).toFile());ObjectNode event=(ObjectNode)probes.path("events").get(0);ObjectNode probe=JSON.readTree(workspace.resolve("query").resolve(event.path("artifact").path("relativePath").asText()).toFile()).deepCopy();probe.with("context").put("projectId","old-project");ObjectNode d=artifact("query/foreign-probe.json",probe);d.remove("path");d.put("relativePath","foreign-probe.json");event.set("artifact",d);f.raw.set("pipelineProbeManifest",artifact("query/probes.json",probes));f.updateResult();assertThrows(IOException.class,f::validate);}
    @Test void savedTaskSeparatesCandidatesNotRerankedFromCandidatesDroppedAfterReranking()throws Exception {
        Fixture f=fixture();ObjectNode manifest=JSON.readTree(Paths.get(f.raw.path("pipelineProbeManifest").path("path").asText()).toFile()).deepCopy();
        for(JsonNode item:manifest.path("events")){
            ObjectNode event=(ObjectNode)item;Path path=workspace.resolve("query").resolve(event.path("artifact").path("relativePath").asText());ObjectNode probe=JSON.readTree(path.toFile()).deepCopy();
            if(probe.path("context").path("queryOrdinal").asInt()!=1)continue;
            ObjectNode payload=(ObjectNode)probe.path("payload");String stage=event.path("stage").asText();
            if("dense-parent-candidates".equals(stage))payload.set("orderedParents",JSON.valueToTree(Arrays.asList(object("id","parent-tender"),object("id","before-rerank"),object("id","after-rerank"))));
            else if("reranker-output".equals(stage)){payload.set("candidateIdentitiesInInputOrder",JSON.valueToTree(Arrays.asList(object("id","parent-tender"),object("id","after-rerank"))));payload.set("scoresInCandidateOrder",JSON.valueToTree(Arrays.asList(.9,.3)));}
            ObjectNode descriptor=artifact("query/"+path.getFileName(),probe);descriptor.remove("path");descriptor.put("relativePath",path.getFileName().toString());event.set("artifact",descriptor);
        }
        f.raw.set("pipelineProbeManifest",artifact("query/probes.json",manifest));f.updateResult();
        VettingInspectionService service=new VettingInspectionService(workspace);
        java.lang.reflect.Field field=VettingInspectionService.class.getDeclaredField("datasets");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,VettingInspectionService.Dataset> datasets=(Map<String,VettingInspectionService.Dataset>)field.get(service);
        datasets.put("metadata-v2-full44",new VettingInspectionService.Dataset("metadata-v2-full44","controlled saved diagnostic fixture","query/result.json",f.binding.path("result").path("sha256").asText()));
        JsonNode page=JSON.valueToTree(service.task("metadata-v2-primary64","1","dense",1,100));
        assertEquals("",page.path("items").get(0).path("dropReason").asText());assertTrue(page.path("items").get(0).path("inRerankerInput").asBoolean());
        assertEquals("not_in_reranker_input",page.path("items").get(1).path("dropReason").asText());assertFalse(page.path("items").get(1).path("inRerankerInput").asBoolean());
        assertEquals("outside_final_retrieval_limit",page.path("items").get(2).path("dropReason").asText());assertTrue(page.path("items").get(2).path("inRerankerInput").asBoolean());
        assertEquals(50,page.path("items").get(1).path("requestedCandidates").asInt());
        JsonNode emptyBm25=JSON.valueToTree(service.task("metadata-v2-primary64","1","bm25",1,100));assertEquals("available",emptyBm25.path("status").asText());assertEquals(0,emptyBm25.path("total").asInt(),"A recorded empty candidate array is distinct from a missing probe");
        JsonNode sent=JSON.valueToTree(service.task("metadata-v2-primary64","1","sent",1,100));assertEquals("unavailable",sent.path("status").asText());assertEquals(0,sent.path("total").asInt());
    }

    private Fixture source44Fixture()throws Exception {
        Fixture f=fixture();
        for(JsonNode node:f.canonical){ObjectNode parent=(ObjectNode)node;parent.put("fileName","controlled native fixture.docx");parent.put("metadataVersion","fixture-owner-v2");parent.put("segmentationVersion","fixture-boundaries-v1");parent.put("sourceQualityMetadataVersion","fixture-quality-v1");}
        ObjectNode tail=f.canonical.get(0).deepCopy();tail.put("id","parent-tender-tail");tail.put("content","unranked complete native member 🧭 | cell");f.canonical.add(tail);
        ObjectNode normalized=artifact("index/normalized.json",f.canonical);f.indexPlan.set("normalizedParents",normalized);ObjectNode indexPlan=artifact("index/plan.json",f.indexPlan);
        f.index.put("parentCount",5);f.index.set("plan",indexPlan);ObjectNode indexResult=artifact("index/result.json",f.index);
        f.indexBinding.put("datasetId",VettingInspectionRetrievalBinding.SOURCE44_DATASET);f.indexBinding.set("result",indexResult);
        ObjectNode indexBinding=artifact("index/binding.json",f.indexBinding);f.indexBindingPath=Paths.get(indexBinding.path("path").asText());
        ArrayNode priorRows=f.rows.deepCopy();for(JsonNode row:priorRows)((ObjectNode)row.path("request")).put("projectId","source42-project");
        ObjectNode prior=artifact("old/source42-plan.json",object("requests",priorRows));
        f.plan.put("protocol","source44-structural-unit-cached-index-plus-query16-plan-v1");f.plan.put("parentCount",5);f.plan.put("vectorPoints",6);
        f.plan.put("expectedMainCachedIndexCalls",1);f.plan.put("expectedNewIndexBuilds",0);f.plan.put("expectedMainQueryCalls",16);
        f.plan.set("previousActualIndex",indexResult);f.plan.set("previousIndexPlan",indexPlan);f.plan.set("normalizedParents",normalized);f.plan.set("historicalSource42Plan",prior);
        ObjectNode software=artifact("query/final-software.json",object("software","controlled immutable source fixture")),compat=artifact("query/compatibility.json",object("receipt","controlled explicit identity fixture"));
        f.plan.set("finalSoftware",software);f.plan.set("compatibilityReceipt",compat);
        for(int i=0;i<16;i++){ObjectNode row=(ObjectNode)f.rows.get(i);row.put("source42RequestOrdinal",i+1);row.set("source42Request",priorRows.get(i).path("request").deepCopy());}
        ArrayNode state=(ArrayNode)f.plan.path("producerStateInventory");f.raw.set("stateCopyProvenance",artifact("query/copy.json",object("protocol","closed-source44-state-copy-v1","producerBefore",state,"copiedBefore",state,"allFilesByteExact",true,"newIndexBuildAuthorized",false)));
        ObjectNode cached=object("projectId","current-project","indexed",5,"vectorPoints",6,"signature","current-signature","cached",true,"contract","source-bound-window-points-full-parent-results-v1");
        f.raw.put("queryOnly",false);f.raw.put("scope","one_cached_index_branch_plus_16_fresh_queries_no_index_build");f.raw.put("parentCount",5);f.raw.put("actualIndexAttempts",1);f.raw.put("newIndexBuilds",0);f.raw.put("retries",0);f.raw.put("concurrency",1);
        f.raw.set("indexResult",cached);f.raw.set("cachedIndexResult",cached);f.raw.set("cachedIndexGuardCounts",object("model",0,"encode",0,"rerank",0,"prepare_windows",0));
        f.raw.set("previousActualIndex",indexResult);f.raw.set("softwareFreeze",software);f.raw.set("compatibilityReceipt",compat);
        ArrayNode operations=JSON.createArrayNode().add(object("operation","cached_index","ordinal",1,"result",cached));for(JsonNode operation:f.operations)operations.add(operation);f.operations=operations;
        ArrayNode probeEvents=JSON.createArrayNode();int eventOrdinal=0;
        for(int i=0;i<16;i++) {
            JsonNode parent=f.canonical.get(i%4);String id=parent.path("id").asText();ObjectNode saved=f.saved.get(i),hit=(ObjectNode)saved.path("response").path("hits").get(0);hit.set("payload",parent.deepCopy());saved.set("request",f.rows.get(i));
            List<JsonNode> members=new ArrayList<>();members.add(parent.deepCopy());if(i%4==0)members.add(tail.deepCopy());List<String> memberIds=new ArrayList<>();for(JsonNode member:members)memberIds.add(member.path("id").asText());
            ObjectNode identity=JSON.createObjectNode();for(String key:Arrays.asList("documentId","sourceHash","role","metadataVersion","segmentationVersion","nativeTableMetadataVersion","sourceQualityMetadataVersion","sourceQualityHash"))identity.put(key,parent.path(key).asText());
            ObjectNode unit=object("unitId","fixture-unit-"+i,"originIds",Collections.singletonList(id),"originalRerankOrdinal",1,"seedScore",.6,"derivedMembersCanBecomeOrigins",false,"memberScores",null,
                    "status","complete_observed_unit","requiredMemberIds",memberIds,"members",members,"sourceIdentity",identity,"qualifiersComplete","unknown","semanticScopeVerified",false);
            saved.with("response").set("sourceUnits",object("policy",VettingInspectionRetrievalBinding.SOURCE44_POLICY,"scope","canonical_observed_structure_only","originalHitIds",Collections.singletonList(id),
                    "rawParentTopKIds",Collections.singletonList(id),"units",Collections.singletonList(unit),"derivedMembersCanBecomeOrigins",false,"qualifiersComplete","unknown","semanticScopeVerified",false));
            ((ObjectNode)f.operations.get(i+1)).set("artifact",artifact("query/query-"+(i+1)+".json",saved));
            for(String stage:Arrays.asList("dense-parent-candidates","parent-bm25-candidates","parent-rrf-candidates","reranker-output","parent-final-ranking")) {
                ObjectNode payload=object("orderedParents",Collections.emptyList(),"allScopeScores",Collections.emptyList(),"orderedCandidateIds",Collections.emptyList(),"allFusedInOrder",Collections.emptyList(),
                        "candidateIdentitiesInInputOrder",Collections.singletonList(object("id",id)),"scoresInCandidateOrder",Collections.singletonList(.6),"allRerankedInTrueScoreOrder",Collections.singletonList(object("id",id,"score",.6)),
                        "returnedIds",Collections.singletonList(id),"rawParentTopKIds",Collections.singletonList(id),"selectionPolicy",VettingInspectionRetrievalBinding.SOURCE44_POLICY);
                String name="source44-probe-"+(i+1)+"-"+stage+".json";eventOrdinal++;
                ObjectNode descriptor=artifact("query/"+name,object("eventOrdinal",eventOrdinal,"stage",stage,"context",object("operation","query","scope","main","queryOrdinal",i+1,"projectId","current-project",
                        "profileId",f.rows.get(i).path("profileId"),"originalTopicOrdinal",f.rows.get(i).path("originalTopicOrdinal")),"payload",payload));
                descriptor.remove("path");descriptor.put("relativePath",name);probeEvents.add(object("ordinal",eventOrdinal,"stage",stage,"artifact",descriptor));
            }
        }
        f.raw.set("pipelineProbeManifest",artifact("query/source44-probes.json",object("events",probeEvents)));
        f.terminal=object("protocol","source44-query16-owned-child-terminal-v1","exitCode",0,"timedOut",false,"launcherChildPid",1234,"retries",0);
        f.worker=object("pid",2345,"parentPid",1234);f.source44Final=object("protocol","source44-structural-unit-query16-final-receipt-v1","status","completed",
                "counts",object("actualCachedIndexMethodCalls",1,"newIndexBuilds",0,"actualQueries",16,"reviewGeneration",0));
        f.binding=object("protocol",VettingInspectionRetrievalBinding.SOURCE44_PROTOCOL,"runId",VettingInspectionRetrievalBinding.SOURCE44_RUN,"datasetId",VettingInspectionRetrievalBinding.SOURCE44_DATASET,
                "retrievalPolicy",VettingInspectionRetrievalBinding.SOURCE44_POLICY,"indexBinding",indexBinding,"canonicalCorpus",f.indexPlan.path("inputs").path("rawCorpus"),"normalizedParents",normalized,
                "sourceDocuments",f.indexBinding.path("sourceDocuments"),"softwareFreeze",software,"compatibilityReceipt",compat);
        f.updateSource44Plan();return f;
    }
    @Test void source44CachedIndexAndSixteenActualScoredEpisodesBindAsASeparateProtocol()throws Exception {
        Fixture f=source44Fixture();assertEquals(17,f.validateSource44().raw.path("operations").size());assertThrows(IOException.class,f::validate);
    }
    @Test void source44CannotAcceptAnOld42BindingOrAnIndexRebuild()throws Exception {
        Fixture f=source44Fixture();f.binding.put("protocol",VettingInspectionRetrievalBinding.PROTOCOL);assertThrows(IOException.class,f::validateSource44);
        f=source44Fixture();f.raw.put("newIndexBuilds",1);f.updateSource44Result();assertThrows(IOException.class,f::validateSource44);
    }
    @Test void source44CachedResultMustBeCachedWithoutAHiddenEncoderCall()throws Exception {
        Fixture f=source44Fixture();f.raw.with("cachedIndexGuardCounts").put("encode",1);f.updateSource44Result();assertThrows(IOException.class,f::validateSource44);
    }
    @Test void source44CannotBorrowAStaleSourceSnapshotOrSuccessfulTerminal()throws Exception {
        Fixture f=source44Fixture();f.binding.set("sourceDocuments",artifact("old/stale-sources.json",object("old",true)));assertThrows(IOException.class,f::validateSource44);
        f=source44Fixture();f.terminal.put("exitCode",1);f.updateSource44Result();assertThrows(IOException.class,f::validateSource44);
    }
    @Test void source44MemberKeepsFullNativeCellTreeAndCurrentSourceHash()throws Exception {
        Fixture f=source44Fixture();ObjectNode member=(ObjectNode)f.saved.get(0).path("response").path("sourceUnits").path("units").get(0).path("members").get(1);
        member.put("content","invented hidden native cell");f.updateSource44Query(0);assertThrows(IOException.class,f::validateSource44);
    }
    @Test void source44UnitCannotInventASeedScoreOrPromoteDerivedMembersIntoOrigins()throws Exception {
        Fixture f=source44Fixture();((ObjectNode)f.saved.get(0).path("response").path("sourceUnits").path("units").get(0)).put("seedScore",.8);f.updateSource44Query(0);assertThrows(IOException.class,f::validateSource44);
        f=source44Fixture();((ObjectNode)f.saved.get(0).path("response").path("sourceUnits").path("units").get(0)).put("derivedMembersCanBecomeOrigins",true);f.updateSource44Query(0);assertThrows(IOException.class,f::validateSource44);
    }
    @Test void source44UnknownUnitHasNoGuessedMembers()throws Exception {
        Fixture f=source44Fixture();ObjectNode unit=(ObjectNode)f.saved.get(0).path("response").path("sourceUnits").path("units").get(0);
        unit.put("status","unknown");unit.set("members",JSON.createArrayNode());unit.set("requiredMemberIds",JSON.createArrayNode());f.updateSource44Query(0);assertNotNull(f.validateSource44());
        unit.set("members",JSON.createArrayNode().add(f.canonical.get(0)));f.updateSource44Query(0);assertThrows(IOException.class,f::validateSource44);
    }
    @Test void sourceUnitPublicStageHasUnrankedMemberRowsAndNoSentClaim()throws Exception {
        Fixture f=source44Fixture();f.validateSource44();VettingInspectionService service=new VettingInspectionService(workspace);
        java.lang.reflect.Field field=VettingInspectionService.class.getDeclaredField("datasets");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,VettingInspectionService.Dataset> datasets=(Map<String,VettingInspectionService.Dataset>)field.get(service);
        datasets.put("metadata-v2-full44",new VettingInspectionService.Dataset("metadata-v2-full44","controlled saved source44 file fixture","query/result.json",f.binding.path("result").path("sha256").asText()));
        JsonNode output=JSON.valueToTree(service.task("metadata-v2-primary64","1","source_units",1,100)),row=output.path("items").get(0);
        assertEquals("available",output.path("status").asText());assertTrue(row.path("score").isNull());assertTrue(row.path("rank").isNull());assertEquals(1,row.path("transportOrdinal").asInt());
        assertEquals(f.saved.get(0).path("response").path("sourceUnits").path("units").get(0),row.path("sourceUnit"));assertEquals(f.canonical.get(0),row.path("chunk"));
        assertEquals(2,row.path("sourceUnit").path("members").size());assertFalse(output.path("generationDispatchRecorded").asBoolean());
        assertEquals("unavailable",JSON.valueToTree(service.task("metadata-v2-primary64","1","sent",1,100)).path("status").asText());
    }
}
