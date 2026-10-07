package com.consense.service.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Saved-artifact validation only; never opens Qdrant or performs an index/query/model call. */
final class VettingInspectionIndexBinding {
    interface Artifacts { Path descriptor(JsonNode value)throws IOException; JsonNode read(Path path)throws IOException; }
    static final class Bound {
        final Path result, vectors, sourceDocuments, originalSourceDocuments;
        final JsonNode rawResult, plan;
        final List<JsonNode> revisionStages;
        final int revisionCount;
        Bound(Path result,Path vectors,Path sourceDocuments,Path originalSourceDocuments,JsonNode rawResult,JsonNode plan,List<JsonNode> stages,int count){
            this.result=result;this.vectors=vectors;this.sourceDocuments=sourceDocuments;this.originalSourceDocuments=originalSourceDocuments;this.rawResult=rawResult;this.plan=plan;this.revisionStages=Collections.unmodifiableList(new ArrayList<>(stages));this.revisionCount=count;
        }
    }
    static Bound validate(JsonNode receipt,String expectedDataset,int expectedRevisions,int expectedStages,Artifacts artifacts)throws IOException {
        if(!"completed-corrected-source-index-inspection-binding-v1".equals(receipt.path("protocol").asText())||!expectedDataset.equals(receipt.path("datasetId").asText()))throw new IOException("Corrected source inspection binding differs");
        Path result=artifacts.descriptor(receipt.path("result"));JsonNode raw=artifacts.read(result);
        if(!"completed".equals(raw.path("status").asText())||raw.path("actualIndexAttempts").asInt(-1)!=1||raw.path("actualRetrievalAttempts").asInt(-1)!=0||raw.path("actualGenerationCalls").asInt(-1)!=0||!raw.path("qdrantClose").path("completed").asBoolean())throw new IOException("Corrected source index has not completed and closed");
        JsonNode dispatch=artifacts.read(artifacts.descriptor(receipt.path("dispatchReceipt")));
        if(dispatch.path("exitCode").asInt(-1)!=0||!dispatch.path("oldStateAndBundleByteExact").asBoolean()||!dispatch.path("result").equals(receipt.path("result")))throw new IOException("Corrected index dispatch identity differs");
        JsonNode plan=artifacts.read(artifacts.descriptor(raw.path("plan"))), corpusManifest=artifacts.read(artifacts.descriptor(plan.path("inputs").path("corpusManifest")));
        JsonNode sourceDescriptor=corpusManifest.path("sourceDocuments");
        require(artifacts,receipt.path("productionPlan"),raw.path("plan"));require(artifacts,receipt.path("canonicalCorpus"),plan.path("inputs").path("rawCorpus"));require(artifacts,receipt.path("sourceDocuments"),sourceDescriptor);
        require(artifacts,corpusManifest.path("corpus"),plan.path("inputs").path("rawCorpus"));
        if(!plan.path("indexOnly").asBoolean()||!plan.path("requests").isArray()||plan.path("requests").size()!=0||!plan.path("projectId").equals(raw.path("projectId"))||raw.path("parentCount").asLong(-1)!=corpusManifest.path("chunkCount").asLong(-2)||!raw.path("indexResult").path("projectId").equals(raw.path("projectId"))||!raw.path("indexResult").path("signature").equals(raw.path("indexSignature")))throw new IOException("Corrected source plan/corpus/index identity differs");
        artifacts.descriptor(raw.path("mainMetadata"));
        JsonNode audit=artifacts.read(artifacts.descriptor(receipt.path("freshClientAudit")));
        if(!"independent-corrected-source-all-window-fresh-client-audit-v1".equals(audit.path("protocol").asText())||!"completed".equals(audit.path("status").asText())||!audit.path("indexSignature").equals(raw.path("indexSignature"))||audit.path("parentCount").asLong(-1)!=raw.path("parentCount").asLong(-2)||audit.path("windowCount").asLong(-1)!=raw.path("indexResult").path("vectorPoints").asLong(-2)||audit.path("observedPoints").asLong(-1)!=audit.path("windowCount").asLong(-2)||audit.path("vectorElementwiseMismatchCount").asLong(-1)!=0||!audit.path("allParentRangesZeroGap").asBoolean()||!audit.path("allProducerStateBytesUnchanged").asBoolean()||!audit.path("failures").isArray()||audit.path("failures").size()!=0||audit.path("freshClientCalls").path("constructor").asLong()<1||audit.path("freshClientCalls").path("scroll").asLong()<1||audit.path("freshClientCalls").path("close").asLong()!=audit.path("freshClientCalls").path("constructor").asLong()||audit.path("producerScope").path("indexAttempts").asInt(-1)!=1||audit.path("producerScope").path("queries").asInt(-1)!=0)throw new IOException("Corrected source fresh-client audit not terminal or complete");
        Map<String,JsonNode> auditedInputs=new LinkedHashMap<>();auditedInputs.put("producerResult",receipt.path("result"));auditedInputs.put("productionPlan",raw.path("plan"));auditedInputs.put("mainMetadata",raw.path("mainMetadata"));auditedInputs.put("rawCorpus",plan.path("inputs").path("rawCorpus"));auditedInputs.put("normalizedParents",plan.path("normalizedParents"));auditedInputs.put("windowTooling",plan.path("inputs").path("windowTooling"));
        for(Map.Entry<String,JsonNode> entry:auditedInputs.entrySet()){
            require(artifacts,audit.path("inputs").path(entry.getKey()),entry.getValue());boolean listed=false;for(JsonNode a:audit.path("boundInputArtifacts"))if(a.equals(entry.getValue()))listed=true;if(!listed)throw new IOException("Fresh-client audit omits bound input artifact");
        }
        Path vectors=artifacts.descriptor(receipt.path("vectorSnapshot"));JsonNode snapshot=artifacts.read(vectors);
        if(!"inspection-qdrant-snapshot-v1".equals(snapshot.path("protocol").asText())||!"completed".equals(snapshot.path("status").asText())||!snapshot.path("producerStateBeforeAfterUnchanged").asBoolean()||snapshot.path("pointCount").asInt(-1)!=raw.path("indexResult").path("vectorPoints").asInt(-2)||snapshot.path("parentCount").asInt(-1)!=raw.path("parentCount").asInt(-2))throw new IOException("Corrected index independent point snapshot differs");
        JsonNode identity=snapshot.path("sourceStoreIdentity");
        if(!raw.path("projectId").equals(identity.path("projectId"))||!raw.path("indexSignature").equals(identity.path("signature")))throw new IOException("Corrected point snapshot source identity differs");
        require(artifacts,snapshot.path("actualPointReadback"),receipt.path("freshClientAudit"));require(artifacts,identity.path("producerResult"),receipt.path("result"));require(artifacts,identity.path("productionPlan"),raw.path("plan"));require(artifacts,identity.path("canonicalCorpus"),plan.path("inputs").path("rawCorpus"));artifacts.descriptor(snapshot.path("parentPoints"));
        if(!identity.path("collection").equals(audit.path("collection"))||!snapshot.path("producerStateBefore").equals(audit.path("producerStateBefore"))||!snapshot.path("producerStateAfter").equals(audit.path("producerStateAfter"))||!audit.path("producerStateBefore").isArray()||!audit.path("producerStateBefore").equals(audit.path("producerStateAfter")))throw new IOException("Corrected snapshot belongs to another readback state");
        List<JsonNode> revisionDescriptors=new ArrayList<>();
        if(receipt.has("sourceRevisionChain")) {
            if(!receipt.path("sourceRevisionChain").isArray())throw new IOException("Source revision chain must be an array");
            receipt.path("sourceRevisionChain").forEach(revisionDescriptors::add);
            if(revisionDescriptors.isEmpty()||!revisionDescriptors.get(revisionDescriptors.size()-1).equals(receipt.path("sourceRevision")))throw new IOException("Final revision descriptor differs from the chain");
        } else revisionDescriptors.add(receipt.path("sourceRevision"));
        if(revisionDescriptors.size()!=expectedStages)throw new IOException("Source revision chain does not have the declared depth");
        List<JsonNode> revisions=new ArrayList<>();JsonNode previous=null;int changes=0;Path original=null;
        for(JsonNode revisionDescriptor:revisionDescriptors) {
            JsonNode revision=artifacts.read(artifacts.descriptor(revisionDescriptor));
            if(!"source-text-whole-block-override-result-v1".equals(revision.path("protocol").asText())||!revision.path("wholeBlockChanges").isArray()||!revision.path("documentRevisions").isArray())throw new IOException("Source revision protocol/records differ");
            Path old=artifacts.descriptor(revision.path("oldSourceSnapshot"));artifacts.descriptor(revision.path("newSourceSnapshot"));
            if(previous!=null)require(artifacts,revision.path("oldSourceSnapshot"),previous);
            else original=old;
            Set<String> candidates=new HashSet<>();
            for(JsonNode change:revision.path("wholeBlockChanges"))if(change.path("candidateId").asText().isEmpty()||!candidates.add(change.path("candidateId").asText()))throw new IOException("Ambiguous revision candidate identity");
            int size=revision.path("wholeBlockChanges").size();
            if(revision.has("updatedBlockCount")&&revision.path("updatedBlockCount").asInt(-1)!=size)throw new IOException("Revision count differs from actual records");
            changes=Math.addExact(changes,size);previous=revision.path("newSourceSnapshot");revisions.add(revision);
        }
        require(artifacts,previous,sourceDescriptor);
        // Legacy source42 bindings predate an explicit cumulative count; retain their historical contract.
        if(expectedStages>1&&(changes!=expectedRevisions||!receipt.path("cumulativeRevisionCount").isIntegralNumber()||receipt.path("cumulativeRevisionCount").asInt(-1)!=changes))throw new IOException("Cumulative source revision count differs");
        return new Bound(result,vectors,artifacts.descriptor(sourceDescriptor),original,raw,plan,revisions,changes);
    }
    private static void require(Artifacts artifacts,JsonNode actual,JsonNode expected)throws IOException {
        if(!actual.isObject()||!actual.equals(expected))throw new IOException("Bound artifact identity differs");artifacts.descriptor(actual);
    }
}
