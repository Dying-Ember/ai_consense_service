package com.consense.service.vetting;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Validates recorded query artifacts against their independently accepted source index. No inference or database access. */
final class VettingInspectionRetrievalBinding {
    static final String RUN = "corrected-source42-current16";
    static final String DATASET = "corrected-source42-native-v3-full44";
    static final String PROTOCOL = "completed-corrected-source-query16-inspection-binding-v1";
    static final String SOURCE44_RUN = "corrected-source44-current16";
    static final String SOURCE44_DATASET = "corrected-source44-native-v3-full44";
    static final String SOURCE44_PROTOCOL = "completed-source44-structural-unit-query16-inspection-binding-v1";
    static final String SOURCE44_POLICY = "unique-source-structural-unit-seeds-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ROLES = Arrays.asList("tender", "standard", "project_fact", "package_manifest");

    interface Artifacts {
        Path descriptor(JsonNode value) throws IOException;
        JsonNode read(Path path) throws IOException;
    }

    static final class Bound {
        final Path result;
        final JsonNode binding, raw, plan;
        Bound(Path result, JsonNode binding, JsonNode raw, JsonNode plan) {
            this.result=result; this.binding=binding; this.raw=raw; this.plan=plan;
        }
    }

    static Bound validate(JsonNode binding, Path indexBindingPath, JsonNode indexBinding,
                          JsonNode index, JsonNode indexPlan, Artifacts files) throws IOException {
        require(PROTOCOL.equals(binding.path("protocol").asText()) && RUN.equals(binding.path("runId").asText())
                && DATASET.equals(binding.path("datasetId").asText()), "Corrected retrieval binding protocol/identity differs");
        require(files.descriptor(binding.path("indexBinding")).equals(indexBindingPath.toAbsolutePath().normalize()), "Query belongs to another index binding");
        require(files.read(indexBindingPath).equals(indexBinding), "Current source index binding differs");
        Path result=files.descriptor(binding.path("result")); JsonNode raw=files.read(result);
        require("offline-gpu-source-window-retrieval-result-v1".equals(raw.path("protocol").asText())
                && "completed".equals(raw.path("status").asText()) && yes(raw.path("queryOnly"))
                && count(raw,"actualRetrievalAttempts",16) && count(raw,"actualIndexAttempts",0)
                && count(raw,"actualGenerationCalls",0) && count(raw,"actualHTTP",0)
                && count(raw,"actualApplicationDB",0) && count(raw,"actualOCR",0) && count(raw,"actualDocumentParse",0)
                && yes(raw.path("qdrantClose").path("attempted")) && yes(raw.path("qdrantClose").path("completed"))
                && yes(raw.path("originalProducerStateBeforeAfterByteExact")), "Corrected retrieval is not a completed closed query-only run");
        require(same(raw,index,"projectId") && same(raw,index,"parentCount") && same(raw,index,"indexSignature")
                && raw.path("indexResult").isNull() && empty(raw.path("actualEncodedFloat32Groups"))
                && empty(raw.path("actualWindowUpsertGroups")), "Query/index scope differs or query recorded index writes");
        exact(binding.path("preparedPlan"),raw.path("plan"),files);
        exact(raw.path("previousActualIndex"),indexBinding.path("result"),files);
        JsonNode terminal=files.read(files.descriptor(binding.path("dispatchReceipt")));
        require("root-corrected42-query16-dispatch-v1".equals(terminal.path("protocol").asText())
                && "child_exited".equals(terminal.path("status").asText()) && count(terminal,"exitCode",0), "Query wrapper has not exited successfully");
        exact(terminal.path("actualResult"),binding.path("result"),files);
        JsonNode started=files.read(files.descriptor(terminal.path("started")));
        require("root-corrected42-query16-dispatch-v1".equals(started.path("protocol").asText())
                && "started".equals(started.path("status").asText()) && started.path("pid").isIntegralNumber()
                && started.path("pid").asLong()>0 && same(started,terminal,"pid"), "Query wrapper start/terminal identity differs");
        JsonNode preparation=files.read(files.descriptor(started.path("preparation")));
        exact(preparation.path("prepared"),binding.path("preparedPlan"),files);
        exact(preparation.path("driver"),raw.path("software"),files);
        JsonNode plan=files.read(files.descriptor(raw.path("plan")));
        require("corrected-source42-topic2-6-query-only-plan-v1".equals(plan.path("protocol").asText())
                && "prepared_no_inference".equals(plan.path("status").asText()) && same(plan,raw,"projectId")
                && same(plan,raw,"parentCount") && same(plan,raw,"indexSignature")
                && count(plan,"expectedMainIndexCalls",0) && count(plan,"expectedMainQueryCalls",16)
                && count(plan,"groupCount",4) && count(plan,"concurrency",1) && count(plan,"retries",0), "Query plan scope differs");
        exact(plan.path("previousActualIndex"),indexBinding.path("result"),files);
        exact(plan.path("previousIndexPlan"),index.path("plan"),files);
        exact(plan.path("publishedInspectionBinding"),binding.path("indexBinding"),files);
        for(String key:Arrays.asList("rawCorpus","corpusManifest","windowTooling"))
            exact(plan.path("inputs").path(key),indexPlan.path("inputs").path(key),files);
        exact(plan.path("normalizedParents"),indexPlan.path("normalizedParents"),files);
        exact(binding.path("canonicalCorpus"),indexPlan.path("inputs").path("rawCorpus"),files);
        exact(binding.path("normalizedParents"),plan.path("normalizedParents"),files);
        exact(binding.path("sourceDocuments"),indexBinding.path("sourceDocuments"),files);
        exact(plan.path("correctedSourceDocuments"),indexBinding.path("sourceDocuments"),files);
        digestEqual(raw.path("mainMetadata"),index.path("mainMetadata"),files);
        exact(plan.path("producerMetadata"),index.path("mainMetadata"),files);
        require(same(plan,indexPlan,"normalizedCorpusSha256") && plan.path("contract").asText().equals("source-bound-window-points-full-parent-results-v1"), "Query canonical corpus/contract differs");
        JsonNode copy=files.read(files.descriptor(raw.path("stateCopyProvenance")));
        require("closed-producer-state-query-copy-v1".equals(copy.path("protocol").asText())
                && yes(copy.path("allFilesByteExact")) && no(copy.path("indexUpsertAuthorized"))
                && copy.path("producerBefore").equals(plan.path("producerStateInventory"))
                && copy.path("producerBefore").equals(raw.path("originalProducerStateAfter")), "Original producer state provenance differs");
        require(copy.path("producerBefore").isArray() && copy.path("producerBefore").size()>0, "State inventory missing");
        for(JsonNode state:copy.path("producerBefore"))files.descriptor(state.path("file"));
        JsonNode old=files.read(files.descriptor(plan.path("historicalQueryTemplate")));
        JsonNode rows=plan.path("requests"), operations=raw.path("operations");
        require(rows.isArray() && rows.size()==16 && operations.isArray() && operations.size()==16, "Actual query count differs");
        Map<Integer,JsonNode> planned=new LinkedHashMap<>(); Set<String> groups=new HashSet<>();
        Map<String,Set<String>> groupRoles=new LinkedHashMap<>(); Map<String,String> groupQueries=new LinkedHashMap<>();
        for(int i=0;i<rows.size();i++) {
            JsonNode row=rows.get(i), request=row.path("request");
            require(count(row,"ordinal",i+1) && (count(row,"originalTopicOrdinal",2)||count(row,"originalTopicOrdinal",6))
                    && request.path("projectId").equals(raw.path("projectId")) && request.path("query").isTextual()
                    && !request.path("query").asText().isEmpty() && ROLES.contains(request.path("role").asText()), "Request scope differs");
            String profile=row.path("profileId").asText();
            require(("primary".equals(profile)&&count(request,"candidates",50)&&count(request,"limit",10))
                    ||("coverage100x20".equals(profile)&&count(request,"candidates",100)&&count(request,"limit",20)), "Request profile differs");
            String group=profile+"/"+row.path("originalTopicOrdinal").asInt(); groups.add(group);
            require(groupRoles.computeIfAbsent(group,k->new HashSet<>()).add(request.path("role").asText()), "Duplicate role in query group");
            String previous=groupQueries.putIfAbsent(group,request.path("query").asText());
            require(previous==null||previous.equals(request.path("query").asText()), "Query text conflicts within a group");
            int oldOrdinal=row.path("originalActualRequestOrdinal").asInt(-1);
            JsonNode oldRow=null;for(JsonNode candidate:old.path("requests"))if(count(candidate,"ordinal",oldOrdinal))oldRow=candidate;
            require(oldRow!=null, "Old actual request mapping missing");
            ObjectNode expected=oldRow.path("request").deepCopy();expected.set("projectId",raw.path("projectId"));
            require(expected.equals(request), "Request changed beyond the declared project rebind");
            for(String field:Arrays.asList("originalTopicOrdinal","profileId","historicalRequest","sourceScope","sourceProvenance","originalRawRequestArtifact","originalProbeMetadataArtifact"))
                require(row.path(field).equals(oldRow.path(field)), "Original request provenance differs");
            planned.put(i+1,row);
        }
        require(groups.size()==4, "Query group count differs");
        for(Set<String> roles:groupRoles.values())require(roles.equals(new HashSet<>(ROLES)), "Incomplete query roles");
        Map<String,JsonNode> returned=new LinkedHashMap<>();Set<Integer> completed=new HashSet<>();
        for(JsonNode operation:operations) {
            int ordinal=operation.path("ordinal").asInt(-1);JsonNode row=planned.get(ordinal);
            require("query".equals(operation.path("operation").asText()) && row!=null && completed.add(ordinal)
                    && operation.path("originalTopicOrdinal").equals(row.path("originalTopicOrdinal")), "Query operation mapping differs");
            JsonNode saved=files.read(files.descriptor(operation.path("artifact"))), response=saved.path("response");
            require(saved.path("request").equals(row) && yes(saved.path("allReturnedParentsExact"))
                    && response.path("projectId").equals(raw.path("projectId")) && response.path("indexSignature").equals(raw.path("indexSignature"))
                    && response.path("mode").equals(plan.path("contract")) && response.path("hits").isArray()
                    && response.path("hits").size()<=row.path("request").path("limit").asInt(), "Saved response/query identity differs");
            Set<String> seen=new HashSet<>();for(JsonNode hit:response.path("hits")) {
                String id=hit.path("id").asText();JsonNode payload=hit.path("payload");
                require(!id.isEmpty() && seen.add(id) && id.equals(payload.path("id").asText())
                        && payload.path("role").equals(row.path("request").path("role")), "Returned source identity differs");
                JsonNode prior=returned.putIfAbsent(id,payload);require(prior==null||prior.equals(payload), "Same parent has conflicting payloads");
            }
        }
        // Compare full normalized source trees, including table slices and quality. Never reconstruct from keywords or old hits.
        Set<String> canonicalIds=new HashSet<>();Path canonical=files.descriptor(plan.path("normalizedParents"));
        try(JsonParser parser=JSON.getFactory().createParser(canonical.toFile())) {
            require(parser.nextToken()==JsonToken.START_ARRAY, "Normalized parents are not an array");
            while(parser.nextToken()!=JsonToken.END_ARRAY) {
                require(parser.currentToken()==JsonToken.START_OBJECT, "Invalid normalized parent");
                JsonNode parent=JSON.readTree(parser);String id=parent.path("id").asText();
                require(!id.isEmpty()&&canonicalIds.add(id), "Duplicate canonical parent identity");
                JsonNode payload=returned.remove(id);require(payload==null||payload.equals(parent), "Returned parent is not its full current canonical payload");
            }
            require(parser.nextToken()==null, "Trailing canonical source data");
        }
        require(returned.isEmpty() && canonicalIds.size()==raw.path("parentCount").asInt(), "Returned parent outside current corpus");
        verifyRankingProbes(raw,planned,files);
        return new Bound(result,binding,raw,plan);
    }


    /** Separate actual cached-index + query episode. The old42 query-only acceptance remains unchanged. */
    static Bound validateSource44(JsonNode binding,Path indexBindingPath,JsonNode indexBinding,
                                  JsonNode index,JsonNode indexPlan,Artifacts files)throws IOException {
        require(SOURCE44_PROTOCOL.equals(binding.path("protocol").asText())
                && SOURCE44_RUN.equals(binding.path("runId").asText()) && SOURCE44_DATASET.equals(binding.path("datasetId").asText())
                && SOURCE44_POLICY.equals(binding.path("retrievalPolicy").asText()),"Source44 retrieval binding protocol/policy differs");
        require(files.descriptor(binding.path("indexBinding")).equals(indexBindingPath.toAbsolutePath().normalize())
                && files.read(indexBindingPath).equals(indexBinding),"Source44 query belongs to another accepted index binding");
        Path result=files.descriptor(binding.path("result"));JsonNode raw=files.read(result);
        require("offline-gpu-source-window-retrieval-result-v1".equals(raw.path("protocol").asText()) && "completed".equals(raw.path("status").asText())
                && no(raw.path("queryOnly")) && "one_cached_index_branch_plus_16_fresh_queries_no_index_build".equals(raw.path("scope").asText())
                && count(raw,"actualIndexAttempts",1) && count(raw,"newIndexBuilds",0) && count(raw,"actualRetrievalAttempts",16)
                && count(raw,"actualGenerationCalls",0) && count(raw,"actualHTTP",0) && count(raw,"actualApplicationDB",0)
                && count(raw,"actualOCR",0) && count(raw,"actualDocumentParse",0) && count(raw,"retries",0) && count(raw,"concurrency",1)
                && yes(raw.path("qdrantClose").path("attempted")) && yes(raw.path("qdrantClose").path("completed"))
                && yes(raw.path("originalProducerStateBeforeAfterByteExact")),"Source44 retrieval has not completed its cached-only and query scope");
        require(same(raw,index,"projectId") && same(raw,index,"parentCount") && same(raw,index,"indexSignature")
                && raw.path("indexResult").equals(raw.path("cachedIndexResult")) && yes(raw.path("cachedIndexResult").path("cached"))
                && empty(raw.path("actualEncodedFloat32Groups")) && empty(raw.path("actualWindowUpsertGroups")),"Source44 result changed index identity or recorded index writes");
        for(String key:Arrays.asList("model","encode","rerank","prepare_windows"))
            require(count(raw.path("cachedIndexGuardCounts"),key,0),"Cached index entered a forbidden computation path");
        exact(binding.path("preparedPlan"),raw.path("plan"),files);exact(raw.path("previousActualIndex"),indexBinding.path("result"),files);
        exact(binding.path("softwareFreeze"),raw.path("softwareFreeze"),files);exact(binding.path("compatibilityReceipt"),raw.path("compatibilityReceipt"),files);
        JsonNode terminal=files.read(files.descriptor(binding.path("dispatchReceipt")));
        require("source44-query16-owned-child-terminal-v1".equals(terminal.path("protocol").asText()) && count(terminal,"exitCode",0)
                && no(terminal.path("timedOut")) && count(terminal,"retries",0),"Source44 owned worker has not exited successfully");
        exact(terminal.path("plan"),binding.path("preparedPlan"),files);
        JsonNode worker=files.read(files.descriptor(terminal.path("workerStarted")));
        require(worker.path("pid").isIntegralNumber() && worker.path("pid").asLong()>0 && worker.path("parentPid").equals(terminal.path("launcherChildPid")),"Source44 actual worker identity differs");
        exact(worker.path("plan"),binding.path("preparedPlan"),files);
        JsonNode receipt=files.read(files.descriptor(binding.path("finalReceipt")));
        require("source44-structural-unit-query16-final-receipt-v1".equals(receipt.path("protocol").asText()) && "completed".equals(receipt.path("status").asText()),"Source44 final receipt missing");
        exact(receipt.path("actualResult"),binding.path("result"),files);exact(receipt.path("ownedTerminal"),binding.path("dispatchReceipt"),files);
        exact(receipt.path("actualWorker"),terminal.path("workerStarted"),files);exact(receipt.path("plan"),binding.path("preparedPlan"),files);
        exact(receipt.path("actualProbeManifest"),raw.path("pipelineProbeManifest"),files);
        require(count(receipt.path("counts"),"actualCachedIndexMethodCalls",1) && count(receipt.path("counts"),"newIndexBuilds",0)
                && count(receipt.path("counts"),"actualQueries",16) && count(receipt.path("counts"),"reviewGeneration",0),"Source44 final call boundary differs");
        JsonNode plan=files.read(files.descriptor(raw.path("plan")));
        require("source44-structural-unit-cached-index-plus-query16-plan-v1".equals(plan.path("protocol").asText())
                && "prepared_no_inference".equals(plan.path("status").asText()) && same(plan,raw,"projectId") && same(plan,raw,"parentCount")
                && same(plan,raw,"indexSignature") && count(plan,"expectedMainCachedIndexCalls",1) && count(plan,"expectedNewIndexBuilds",0)
                && count(plan,"expectedMainQueryCalls",16) && count(plan,"retries",0) && count(plan,"concurrency",1),"Source44 prepared plan scope differs");
        exact(plan.path("previousActualIndex"),indexBinding.path("result"),files);exact(plan.path("previousIndexPlan"),index.path("plan"),files);
        exact(plan.path("finalSoftware"),binding.path("softwareFreeze"),files);exact(plan.path("compatibilityReceipt"),binding.path("compatibilityReceipt"),files);
        for(String key:Arrays.asList("rawCorpus","corpusManifest","windowTooling"))exact(plan.path("inputs").path(key),indexPlan.path("inputs").path(key),files);
        exact(plan.path("normalizedParents"),indexPlan.path("normalizedParents"),files);exact(binding.path("normalizedParents"),plan.path("normalizedParents"),files);
        exact(binding.path("canonicalCorpus"),indexPlan.path("inputs").path("rawCorpus"),files);exact(binding.path("sourceDocuments"),indexBinding.path("sourceDocuments"),files);
        digestEqual(raw.path("mainMetadata"),index.path("mainMetadata"),files);exact(plan.path("producerMetadata"),index.path("mainMetadata"),files);
        require(same(plan,indexPlan,"normalizedCorpusSha256") && "source-bound-window-points-full-parent-results-v1".equals(plan.path("contract").asText())
                && count(raw.path("cachedIndexResult"),"indexed",raw.path("parentCount").asLong())
                && raw.path("cachedIndexResult").path("vectorPoints").equals(plan.path("vectorPoints")),"Source44 cached index/canonical contract differs");
        JsonNode copy=files.read(files.descriptor(raw.path("stateCopyProvenance")));
        require("closed-source44-state-copy-v1".equals(copy.path("protocol").asText()) && yes(copy.path("allFilesByteExact"))
                && no(copy.path("newIndexBuildAuthorized")) && copy.path("producerBefore").equals(plan.path("producerStateInventory"))
                && copy.path("producerBefore").equals(raw.path("originalProducerStateAfter")),"Source44 original producer state provenance differs");
        require(copy.path("producerBefore").isArray() && copy.path("producerBefore").size()>0,"Source44 original state inventory missing");
        for(JsonNode state:copy.path("producerBefore"))files.descriptor(state.path("file"));
        JsonNode old=files.read(files.descriptor(plan.path("historicalSource42Plan"))),rows=plan.path("requests"),operations=raw.path("operations");
        require(rows.isArray() && rows.size()==16 && old.path("requests").isArray() && old.path("requests").size()==16
                && operations.isArray() && operations.size()==17,"Source44 query matrix differs");
        Map<Integer,JsonNode> planned=new LinkedHashMap<>();Map<String,Set<String>> roles=new LinkedHashMap<>();Map<String,String> queries=new LinkedHashMap<>();
        for(int i=0;i<16;i++) {
            JsonNode row=rows.get(i),request=row.path("request"),prior=old.path("requests").get(i);
            require(count(row,"ordinal",i+1) && count(row,"source42RequestOrdinal",i+1)
                    && (count(row,"originalTopicOrdinal",2)||count(row,"originalTopicOrdinal",6)) && ROLES.contains(request.path("role").asText())
                    && row.path("source42Request").equals(prior.path("request")),"Source44 original sixteen request mapping differs");
            ObjectNode expected=prior.path("request").deepCopy();expected.set("projectId",raw.path("projectId"));require(expected.equals(request),"Source44 request changed beyond declared project rebind");
            for(String key:Arrays.asList("originalTopicOrdinal","profileId","historicalRequest","sourceScope","sourceProvenance","originalRawRequestArtifact","originalProbeMetadataArtifact"))
                require(row.path(key).equals(prior.path(key)),"Source44 original query provenance differs");
            String profile=row.path("profileId").asText();require(("primary".equals(profile)&&count(request,"candidates",50)&&count(request,"limit",10))
                    ||("coverage100x20".equals(profile)&&count(request,"candidates",100)&&count(request,"limit",20)),"Source44 query profile differs");
            String group=profile+"/"+row.path("originalTopicOrdinal").asInt();require(roles.computeIfAbsent(group,k->new HashSet<>()).add(request.path("role").asText()),"Source44 duplicate role");
            String prev=queries.putIfAbsent(group,request.path("query").asText());require(request.path("query").isTextual()&&!request.path("query").asText().isEmpty()
                    &&(prev==null||prev.equals(request.path("query").asText())),"Source44 grouped query text differs");planned.put(i+1,row);
        }
        require(roles.size()==4,"Source44 query group count differs");for(Set<String> group:roles.values())require(group.equals(new HashSet<>(ROLES)),"Source44 query role missing");
        Map<Integer,JsonNode> finalRankings=source44FinalRankings(raw,planned,files);
        Map<String,JsonNode> returned=new LinkedHashMap<>();Set<Integer> completed=new HashSet<>();int cached=0;
        for(JsonNode operation:operations) {
            if("cached_index".equals(operation.path("operation").asText())) {cached++;require(operation.path("result").equals(raw.path("cachedIndexResult")),"Cached operation result differs");continue;}
            int ordinal=operation.path("ordinal").asInt(-1);JsonNode row=planned.get(ordinal);
            require("query".equals(operation.path("operation").asText()) && row!=null && completed.add(ordinal)
                    && operation.path("originalTopicOrdinal").equals(row.path("originalTopicOrdinal")),"Source44 actual query operation differs");
            JsonNode saved=files.read(files.descriptor(operation.path("artifact"))),response=saved.path("response");
            require(saved.path("request").equals(row) && yes(saved.path("allReturnedParentsExact")) && same(response,raw,"projectId") && same(response,raw,"indexSignature")
                    && response.path("mode").equals(plan.path("contract")) && response.path("hits").isArray() && response.path("hits").size()<=row.path("request").path("limit").asInt(),"Source44 actual response identity differs");
            verifySource44Episode(response,row,finalRankings.get(ordinal),returned);
        }
        require(cached==1 && completed.size()==16,"Source44 cached/query operation count differs");
        verifyCanonicalPayloads(returned,plan.path("normalizedParents"),raw.path("parentCount").asInt(),files);
        verifyRankingProbes(raw,planned,files);
        return new Bound(result,binding,raw,plan);
    }

    private static Map<Integer,JsonNode> source44FinalRankings(JsonNode raw,Map<Integer,JsonNode> planned,Artifacts files)throws IOException {
        Path path=files.descriptor(raw.path("pipelineProbeManifest"));JsonNode manifest=files.read(path);
        Map<Integer,JsonNode> out=new LinkedHashMap<>(),rerank=new LinkedHashMap<>();
        for(JsonNode record:manifest.path("events")) {
            String stage=record.path("stage").asText();if(!"parent-final-ranking".equals(stage)&&!"reranker-output".equals(stage))continue;
            JsonNode relative=record.path("artifact");Path target=path.getParent().resolve(relative.path("relativePath").asText()).normalize();
            require(target.startsWith(path.getParent())&&!relative.path("relativePath").asText().isEmpty(),"Source44 ranking path escapes run");
            ObjectNode d=JSON.createObjectNode();d.put("path",target.toString());d.set("bytes",relative.path("bytes"));d.set("sha256",relative.path("sha256"));
            JsonNode event=files.read(files.descriptor(d)),context=event.path("context");int ordinal=context.path("queryOrdinal").asInt(-1);JsonNode row=planned.get(ordinal);
            require(row!=null && stage.equals(event.path("stage").asText()) && event.path("eventOrdinal").equals(record.path("ordinal"))
                    && "query".equals(context.path("operation").asText()) && "main".equals(context.path("scope").asText()) && same(context,raw,"projectId")
                    && context.path("profileId").equals(row.path("profileId")) && context.path("originalTopicOrdinal").equals(row.path("originalTopicOrdinal")),"Source44 ranking query identity differs");
            Map<Integer,JsonNode> selected="parent-final-ranking".equals(stage)?out:rerank;
            require(selected.put(ordinal,event.path("payload"))==null,"Source44 duplicate ranking probe");
        }
        require(out.size()==16&&rerank.size()==16,"Source44 actual ranking probes missing");
        for(Map.Entry<Integer,JsonNode> entry:out.entrySet()) {
            JsonNode payload=rerank.get(entry.getKey()),ids=payload.path("candidateIdentitiesInInputOrder"),scores=payload.path("scoresInCandidateOrder"),ranked=entry.getValue().path("allRerankedInTrueScoreOrder");
            require(ids.isArray()&&scores.isArray()&&ranked.isArray()&&ids.size()==scores.size()&&ranked.size()==ids.size(),"Source44 rerank/final score cardinality differs");
            Map<String,JsonNode> values=new HashMap<>();for(int i=0;i<ids.size();i++)require(!ids.get(i).path("id").asText().isEmpty()&&scores.get(i).isNumber()
                    &&Double.isFinite(scores.get(i).asDouble())&&values.put(ids.get(i).path("id").asText(),scores.get(i))==null,"Source44 duplicate/non-finite rerank score");
            for(JsonNode hit:ranked){JsonNode score=values.remove(hit.path("id").asText());require(score!=null&&score.equals(hit.path("score")),"Source44 final ranking does not preserve actual rerank scores");}
            require(values.isEmpty(),"Source44 final ranking lost actual rerank candidates");
        }
        return out;
    }

    private static void verifySource44Episode(JsonNode response,JsonNode row,JsonNode ranking,Map<String,JsonNode> returned)throws IOException {
        JsonNode hits=response.path("hits"),episode=response.path("sourceUnits"),units=episode.path("units"),actual=ranking.path("allRerankedInTrueScoreOrder");
        require(SOURCE44_POLICY.equals(episode.path("policy").asText()) && SOURCE44_POLICY.equals(ranking.path("selectionPolicy").asText())
                && "canonical_observed_structure_only".equals(episode.path("scope").asText()) && no(episode.path("derivedMembersCanBecomeOrigins"))
                && no(episode.path("semanticScopeVerified")) && "unknown".equals(episode.path("qualifiersComplete").asText())
                && units.isArray() && units.size()==hits.size() && actual.isArray(),"Source44 source unit episode policy/scope differs");
        List<String> ids=new ArrayList<>();Map<String,JsonNode> scores=new LinkedHashMap<>();Map<String,Integer> ranks=new LinkedHashMap<>();double previous=Double.POSITIVE_INFINITY;
        for(int i=0;i<actual.size();i++){JsonNode score=actual.get(i).path("score");String id=actual.get(i).path("id").asText();
            require(!id.isEmpty() && score.isNumber() && Double.isFinite(score.asDouble()) && score.asDouble()<=previous && scores.put(id,score)==null,"Source44 actual ranking score/order differs");
            previous=score.asDouble();ranks.put(id,i+1);}
        ArrayList<String> rawIds=new ArrayList<>();for(int i=0;i<Math.min(actual.size(),row.path("request").path("limit").asInt());i++)rawIds.add(actual.get(i).path("id").asText());
        require(JSON.valueToTree(rawIds).equals(ranking.path("rawParentTopKIds")) && ranking.path("rawParentTopKIds").equals(episode.path("rawParentTopKIds")),"Source44 recorded raw policy differs");
        Set<String> unitIds=new HashSet<>();
        for(int i=0;i<hits.size();i++) {
            JsonNode hit=hits.get(i),seed=hit.path("payload"),unit=units.get(i);String id=hit.path("id").asText();ids.add(id);
            require(id.equals(seed.path("id").asText()) && seed.path("role").equals(row.path("request").path("role")) && scores.containsKey(id)
                    && scores.get(id).equals(hit.path("score")) && scores.get(id).equals(unit.path("seedScore"))
                    && count(unit,"originalRerankOrdinal",ranks.get(id)) && JSON.valueToTree(Collections.singletonList(id)).equals(unit.path("originIds"))
                    && no(unit.path("derivedMembersCanBecomeOrigins")) && unit.path("memberScores").isNull(),"Source44 scored seed/member boundary differs");
            sourcePayload(returned,seed);
            if("complete_observed_unit".equals(unit.path("status").asText())) {
                require(!unit.path("unitId").asText().isEmpty() && unitIds.add(unit.path("unitId").asText()) && unit.path("members").isArray()
                        && unit.path("requiredMemberIds").isArray() && unit.path("members").size()>0 && unit.path("members").size()==unit.path("requiredMemberIds").size(),"Source44 complete unit member identity missing");
                List<String> memberIds=new ArrayList<>();for(JsonNode member:unit.path("members")) {
                    require(memberIds.add(member.path("id").asText()) && !member.path("id").asText().isEmpty(),"Source44 empty member identity");
                    for(String key:Arrays.asList("documentId","sourceHash","role","metadataVersion","segmentationVersion","nativeTableMetadataVersion","sourceQualityMetadataVersion","sourceQualityHash"))
                        require(!seed.path(key).isMissingNode() && !seed.path(key).isNull() && member.path(key).equals(seed.path(key))
                                && unit.path("sourceIdentity").path(key).asText().equals(seed.path(key).asText()),"Source44 member crossed source/quality/version");
                    sourcePayload(returned,member);
                }
                require(new HashSet<>(memberIds).size()==memberIds.size() && memberIds.contains(id) && JSON.valueToTree(memberIds).equals(unit.path("requiredMemberIds")),"Source44 complete unit member order differs");
            } else require("unknown".equals(unit.path("status").asText()) && empty(unit.path("members")) && empty(unit.path("requiredMemberIds")),"Unknown Source44 unit guessed members");
        }
        require(new HashSet<>(ids).size()==ids.size() && JSON.valueToTree(ids).equals(episode.path("originalHitIds"))
                && ranking.path("returnedIds").equals(episode.path("originalHitIds")),"Source44 episode seed order differs");
    }
    private static void sourcePayload(Map<String,JsonNode> returned,JsonNode payload)throws IOException {
        String id=payload.path("id").asText();require(!id.isEmpty(),"Source44 payload identity missing");JsonNode before=returned.putIfAbsent(id,payload);
        require(before==null||before.equals(payload),"Source44 same parent has conflicting payloads");
    }
    private static void verifyCanonicalPayloads(Map<String,JsonNode> returned,JsonNode descriptor,int expected,Artifacts files)throws IOException {
        Set<String> ids=new HashSet<>();Path canonical=files.descriptor(descriptor);
        try(JsonParser parser=JSON.getFactory().createParser(canonical.toFile())) {
            require(parser.nextToken()==JsonToken.START_ARRAY,"Source44 canonical parents not an array");
            while(parser.nextToken()!=JsonToken.END_ARRAY){require(parser.currentToken()==JsonToken.START_OBJECT,"Source44 invalid canonical parent");JsonNode p=JSON.readTree(parser);String id=p.path("id").asText();
                require(!id.isEmpty()&&ids.add(id),"Source44 duplicate canonical parent");JsonNode payload=returned.remove(id);require(payload==null||payload.equals(p),"Source44 source unit member differs from complete canonical tree");}
            require(parser.nextToken()==null,"Source44 trailing canonical data");
        }
        require(returned.isEmpty()&&ids.size()==expected,"Source44 returned member outside current corpus");
    }

    private static void verifyRankingProbes(JsonNode raw,Map<Integer,JsonNode> planned,Artifacts files)throws IOException {
        Path manifestPath=files.descriptor(raw.path("pipelineProbeManifest"));JsonNode manifest=files.read(manifestPath);
        List<String> stages=Arrays.asList("dense-parent-candidates","parent-bm25-candidates","parent-rrf-candidates","reranker-output");
        Set<String> observed=new HashSet<>();require(manifest.path("events").isArray(),"Ranking probe events missing");
        for(JsonNode event:manifest.path("events")) {
            String stage=event.path("stage").asText();if(!stages.contains(stage))continue;
            JsonNode relative=event.path("artifact");Path path=manifestPath.getParent().resolve(relative.path("relativePath").asText()).normalize();
            require(path.startsWith(manifestPath.getParent())&&!relative.path("relativePath").asText().isEmpty(),"Ranking probe path outside run");
            ObjectNode descriptor=JSON.createObjectNode();descriptor.put("path",path.toString());descriptor.set("bytes",relative.path("bytes"));descriptor.set("sha256",relative.path("sha256"));
            JsonNode probe=files.read(files.descriptor(descriptor)),context=probe.path("context");int ordinal=context.path("queryOrdinal").asInt(-1);JsonNode row=planned.get(ordinal);
            require(row!=null&&"query".equals(context.path("operation").asText())&&"main".equals(context.path("scope").asText())
                    &&context.path("projectId").equals(raw.path("projectId"))&&context.path("profileId").equals(row.path("profileId"))
                    && context.path("originalTopicOrdinal").equals(row.path("originalTopicOrdinal"))&&observed.add(stage+"/"+ordinal),"Ranking probe query identity differs or duplicates");
            JsonNode payload=probe.path("payload");
            if("dense-parent-candidates".equals(stage))require(payload.path("orderedParents").isArray(),"Dense candidates missing");
            else if("parent-bm25-candidates".equals(stage))require(payload.path("allScopeScores").isArray()&&payload.path("orderedCandidateIds").isArray(),"BM25 candidates missing");
            else if("parent-rrf-candidates".equals(stage))require(payload.path("allFusedInOrder").isArray(),"RRF candidates missing");
            else require(payload.path("candidateIdentitiesInInputOrder").isArray()&&payload.path("scoresInCandidateOrder").isArray()
                    &&payload.path("candidateIdentitiesInInputOrder").size()==payload.path("scoresInCandidateOrder").size(),"Rerank identity/score shape differs");
        }
        require(observed.size()==planned.size()*stages.size(),"Ranking probes are incomplete for the query matrix");
    }

    private static void exact(JsonNode a,JsonNode b,Artifacts files)throws IOException {
        require(a.isObject()&&a.equals(b), "Bound artifact identity differs");files.descriptor(a);
    }
    private static void digestEqual(JsonNode a,JsonNode b,Artifacts files)throws IOException {
        require(a.isObject()&&b.isObject()&&a.path("bytes").equals(b.path("bytes"))&&a.path("sha256").equals(b.path("sha256")), "Copied metadata identity differs");
        files.descriptor(a);files.descriptor(b);
    }
    private static boolean count(JsonNode n,String key,long value){return n.path(key).isIntegralNumber()&&n.path(key).asLong()==value;}
    private static boolean same(JsonNode a,JsonNode b,String key){return !a.path(key).isMissingNode()&&a.path(key).equals(b.path(key));}
    private static boolean yes(JsonNode n){return n.isBoolean()&&n.booleanValue();}
    private static boolean no(JsonNode n){return n.isBoolean()&&!n.booleanValue();}
    private static boolean empty(JsonNode n){return n.isArray()&&n.size()==0;}
    private static void require(boolean valid,String reason)throws IOException {if(!valid)throw new IOException(reason);}
}
