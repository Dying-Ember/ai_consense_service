package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import lombok.Data;
import java.util.*;
import java.util.stream.Collectors;

/** Independent, bounded transport of observed source requests; never legal or token-window completeness. */
public final class VettingSourceRequestPacks {
    public static final String POLICY = "ranked_source_request_atomic_extra_packets_with_observed_conditions_v2";
    public static final int DEFAULT_EXTRA_PACKS = 8, DEFAULT_PER_PACK_CHARS = 20000;
    private final Map<String,String> payloads = new LinkedHashMap<>();
    private final Map<String,Chunk> snapshot = new LinkedHashMap<>(), external = new LinkedHashMap<>();
    private final Map<String,Set<String>> documentIdentities = new LinkedHashMap<>();
    private final VettingBlockContinuation continuation;
    private final VettingContextBuilder observer;

    @Data public static class Request {
        private String id, observationId, kind, reference, status = "candidate", unknownReason;
        private int ordinal, rankOrdinal, contentChars, packIndex;
        private List<String> eligibleOriginIds = new ArrayList<>(), requiredChunkIds = new ArrayList<>();
        private List<String> globallySubmittedIds = new ArrayList<>(), submittedChunkIds = new ArrayList<>(), missingChunkIds = new ArrayList<>();
        private Map<String,Object> sourceTrace = new LinkedHashMap<>();
        private Map<String,Object> originObservation = new LinkedHashMap<>();
        private String applicability = "unknown", qualifiersComplete = "unknown", observationScope = "source_request_and_observed_native_block_ranges_only";
        private boolean semanticScopeVerified = false, tokenEnvelopeMeasured = false;
    }
    @Data public static class Pack {
        private int index, contentChars;
        private List<Chunk> chunks = new ArrayList<>();
        private List<String> requestIds = new ArrayList<>(), eligibleOriginIds = new ArrayList<>();
        private Map<String,String> sourcePayloadHashes = new LinkedHashMap<>();
        private String scope = "co_located_complete_observed_request_groups_only", qualifiersComplete = "unknown";
        private boolean tokenEnvelopeMeasured = false, semanticScopeVerified = false;
    }
    @Data public static class Result {
        private String policy = POLICY, topic, globalSelectionHash;
        private List<Chunk> globalChunks = new ArrayList<>();
        private List<Pack> extraPacks = new ArrayList<>();
        private List<Request> requests = new ArrayList<>();
        private List<String> originalRankedIds = new ArrayList<>(), globalEligibleOriginIds = new ArrayList<>();
        /** Original attached retrieval seeds only; derived unit members never enter this list. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private List<String> originalRetrievedIds = new ArrayList<>();
        private int perPackChars, extraPackCap, independentSeedObservations, conditionCandidateObservations;
        private Map<String,String> canonicalPayloadHashes = new LinkedHashMap<>();
        private String budgetScope = "canonical_content_utf16_chars_only_not_serialized_input_or_model_tokens", qualifiersComplete = "unknown";
        private boolean tokenEnvelopeMeasured = false, semanticScopeVerified = false;
        private int additionalRetrievalCalls = 0, additionalModelCalls = 0;
    }

    public VettingSourceRequestPacks(List<Chunk> corpus) {
        if(corpus==null)throw new IllegalArgumentException("Canonical corpus required");
        for(Chunk c:corpus) {
            if(c==null||nvl(c.getId()).isEmpty()||payloads.containsKey(c.getId()))throw new IllegalArgumentException("Canonical corpus requires unique nonempty IDs");
            String json=JsonUtils.write(c);payloads.put(c.getId(),json);external.put(c.getId(),c);
            Chunk copy=JsonUtils.read(json,Chunk.class);snapshot.put(c.getId(),copy);
            documentIdentities.computeIfAbsent(nvl(copy.getDocumentId()),key->new LinkedHashSet<>()).add(source(copy));
        }
        List<Chunk> copies=new ArrayList<>(snapshot.values());
        continuation=new VettingBlockContinuation(copies);observer=new VettingContextBuilder(copies);
    }

    public Result plan(String topic,List<Chunk> rankedTender,VettingContextBuilder.Selection global) {
        return plan(topic,rankedTender,global,DEFAULT_PER_PACK_CHARS,DEFAULT_EXTRA_PACKS);
    }

    /** Observe full source requests; complete-input token packing is the dispatch authority. */
    public Result planForCompleteInputBudget(String topic,List<Chunk> rankedTender,VettingContextBuilder.Selection global) {
        return plan(topic,rankedTender,global,Integer.MAX_VALUE,DEFAULT_EXTRA_PACKS);
    }
    Result planForCompleteInputBudget(String topic,List<Chunk> rankedTender,VettingContextBuilder.Selection global,List<VettingRetrievalSourceUnits.Unit> units) {
        return plan(topic,rankedTender,global,Integer.MAX_VALUE,DEFAULT_EXTRA_PACKS,units);
    }

    /** The supplied global selection is trusted production observation, but every transported payload is re-bound. */
    public Result plan(String topic,List<Chunk> rankedTender,VettingContextBuilder.Selection global,int perPackChars,int extraPackCap) {
        return plan(topic,rankedTender,global,perPackChars,extraPackCap,Collections.emptyList());
    }
    private Result plan(String topic,List<Chunk> rankedTender,VettingContextBuilder.Selection global,int perPackChars,int extraPackCap,List<VettingRetrievalSourceUnits.Unit> units) {
        if(topic==null||rankedTender==null||global==null||perPackChars<0||extraPackCap<0)throw new IllegalArgumentException("Topic, original ranking, global observation and nonnegative limits required");
        validateSnapshot();
        if(!VettingIncomingLiteralContext.POLICY.equals(global.getIncomingReferencePolicy())
                ||!VettingBlockContinuation.POLICY.equals(global.getBlockContinuationPolicy())
                ||!"single_hop_selected_ranked_literal_v1".equals(global.getReferencePolicy()))throw new IllegalArgumentException("Global observation software policy differs");
        String globalJson=JsonUtils.write(global);
        LinkedHashMap<String,Chunk> ranked=new LinkedHashMap<>(),selected=new LinkedHashMap<>();
        for(Chunk c:rankedTender) {Chunk canonical=canonical(c);if(!"tender".equals(canonical.getRole()))throw new IllegalArgumentException("Original topic ranking requires tender role");ranked.putIfAbsent(c.getId(),canonical);}
        long globalChars=0;
        for(Chunk c:global.getChunks()) {Chunk canonical=canonical(c);if(selected.putIfAbsent(c.getId(),canonical)!=null)throw new IllegalArgumentException("Duplicate global selected ID");globalChars+=nvl(c.getContent()).length();}
        if(globalChars!=global.getContentChars())throw new IllegalArgumentException("Global selected content cost differs");
        Result out=new Result();out.setTopic(topic);out.setGlobalSelectionHash(VettingCorpus.hash(globalJson));out.setPerPackChars(perPackChars);out.setExtraPackCap(extraPackCap);
        out.setOriginalRankedIds(new ArrayList<>(ranked.keySet()));
        // Source-derived extension IDs cannot confer initial core eligibility, even when also ranked.
        Set<String> genuineGroups=new LinkedHashSet<>(),typedOrigins=new LinkedHashSet<>(),eligible=new LinkedHashSet<>();
        for(VettingContextBuilder.GroupTrace group:global.getGroups()) if(genuine(group.getRelation())&&Arrays.asList("selected","selected_partial_context").contains(group.getStatus()))
            for(String id:group.getCoreIds())if(ranked.containsKey(id)&&selected.containsKey(id))genuineGroups.add(id);
        for(VettingContextBuilder.ReferenceTrace t:global.getReferences())if(t.isInitiallySubmitted()&&t.isOriginSubmitted())typedOrigins.add(t.getOriginId());
        for(VettingBlockContinuation.Trace t:global.getBlockContinuations())if(t.isInitiallySubmitted()&&t.isOriginSubmitted())typedOrigins.add(t.getOriginId());
        for(VettingIncomingLiteralContext.Trace t:global.getIncomingReferences())if(t.isInitiallyConnected())typedOrigins.addAll(t.getInitialOriginIds());
        for(String id:ranked.keySet())if(selected.containsKey(id)&&genuineGroups.contains(id)&&typedOrigins.contains(id))eligible.add(id);
        out.setGlobalEligibleOriginIds(new ArrayList<>(eligible));
        Map<String,Integer> ranks=new LinkedHashMap<>();int rank=0;for(String id:ranked.keySet())ranks.put(id,++rank);
        collect(out,"global",global,eligible,ranks,selected.keySet());
        collectConditions(out,topic,ranked,global,ranks,selected.keySet());
        for(Chunk seed:ranked.values())if(!eligible.contains(seed.getId())) {
            String observation="independent_ranked_request_episode_"+ranks.get(seed.getId());
            String valid=sourceStatus(seed);
            if(valid!=null) {unknownSeed(out,observation,seed,ranks,valid);continue;}
            VettingContextBuilder.Selection single=observer.build(topic,Collections.singletonList(seed),Collections.emptyMap(),perPackChars);
            out.setIndependentSeedObservations(out.getIndependentSeedObservations()+1);
            boolean admitted=single.getGroups().stream().anyMatch(g->genuine(g.getRelation())&&Arrays.asList("selected","selected_partial_context").contains(g.getStatus())&&g.getCoreIds().contains(seed.getId()))
                    &&single.getChunks().stream().anyMatch(c->seed.getId().equals(c.getId()));
            Request sourceRequest=newRequest(out,observation,"ranked_source_observation",Collections.singletonList(seed.getId()),null,ranks);
            sourceRequest.setOriginObservation(observationIdentity(single,seed.getId(),admitted));
            if(!admitted) {sourceRequest.setUnknownReason("independent_ranked_seed_not_admitted");sourceRequest.setStatus(seed.getContent().length()>perPackChars?"oversized":"unknown");sourceRequest.getRequiredChunkIds().add(seed.getId());sourceRequest.setContentChars(seed.getContent().length());finishMissing(sourceRequest,selected.keySet());continue;}
            required(sourceRequest,Collections.emptyList());
            if(sourceRequest.getUnknownReason()==null&&selected.keySet().containsAll(sourceRequest.getRequiredChunkIds()))sourceRequest.setStatus("already_global");
            collect(out,observation,single,Collections.singleton(seed.getId()),ranks,selected.keySet());
        }
        // This is a source transport request only. No collect()/Builder expansion
        // on members: their references and other blocks never become new origins.
        LinkedHashSet<String> retrieved=new LinkedHashSet<>();
        for(VettingRetrievalSourceUnits.Unit unit:units) {
            Chunk seed=snapshot.get(unit.originId);if(seed==null)throw new IllegalArgumentException("Retrieved structural seed is not canonical");
            retrieved.add(unit.originId);
            Request r=newRequest(out,"original_retrieval_structural_unit","retrieved_structural_unit",Collections.singletonList(unit.originId),null,ranks);
            r.setSourceTrace(new LinkedHashMap<>(unit.trace));
            r.setOriginObservation(VettingReviewProbe.map("originalRetrievalSeedId",unit.originId,"structuralMembersCanBecomeLiteralOrigins",false,"selectionPolicy",VettingRetrievalSourceUnits.POLICY));
            r.setUnknownReason("complete_observed_unit".equals(unit.status)?sourceStatus(seed):"structural_unit_unknown_"+unit.reason);
            for(String id:unit.ids){Chunk member=snapshot.get(id);if(member==null||!source(seed).equals(source(member)))throw new IllegalArgumentException("Retrieved structural unit canonical source differs");String state=sourceStatus(member);if(state!=null)r.setUnknownReason(state);}
            required(r,unit.ids);finishMissing(r,selected.keySet());
        }
        out.setOriginalRetrievedIds(new ArrayList<>(retrieved));
        for(Request request:out.getRequests()) {
            if(request.getUnknownReason()!=null||"unknown".equals(request.getStatus())||"oversized".equals(request.getStatus())) {if("candidate".equals(request.getStatus()))request.setStatus("unknown");finishMissing(request,selected.keySet());continue;}
            if(selected.keySet().containsAll(request.getRequiredChunkIds())) {request.setStatus("already_global");request.setSubmittedChunkIds(new ArrayList<>(request.getRequiredChunkIds()));request.getMissingChunkIds().clear();continue;}
            if(request.getContentChars()>perPackChars) {request.setStatus("oversized");finishMissing(request,selected.keySet());continue;}
            Pack chosen=null;
            for(Pack pack:out.getExtraPacks())if((long)pack.getContentChars()+incremental(request,pack)<=perPackChars){chosen=pack;break;}
            if(chosen==null&&out.getExtraPacks().size()<extraPackCap) {chosen=new Pack();chosen.setIndex(out.getExtraPacks().size()+1);out.getExtraPacks().add(chosen);}
            if(chosen==null) {request.setStatus("omitted_pack_cap");finishMissing(request,selected.keySet());continue;}
            LinkedHashSet<String> present=chosen.getChunks().stream().map(Chunk::getId).collect(Collectors.toCollection(LinkedHashSet::new));
            for(String id:request.getRequiredChunkIds())if(present.add(id)) {Chunk c=copy(id);chosen.getChunks().add(c);chosen.setContentChars(chosen.getContentChars()+c.getContent().length());chosen.getSourcePayloadHashes().put(id,VettingCorpus.hash(payloads.get(id)));}
            chosen.getRequestIds().add(request.getId());for(String id:request.getEligibleOriginIds())if(!chosen.getEligibleOriginIds().contains(id))chosen.getEligibleOriginIds().add(id);
            request.setStatus("transported_extra_pack");request.setPackIndex(chosen.getIndex());request.setSubmittedChunkIds(new ArrayList<>(request.getRequiredChunkIds()));request.getMissingChunkIds().clear();
        }
        out.setGlobalChunks(selected.keySet().stream().map(this::copy).collect(Collectors.toList()));
        for(String id:payloads.keySet())out.getCanonicalPayloadHashes().put(id,VettingCorpus.hash(payloads.get(id)));
        if(!globalJson.equals(JsonUtils.write(global)))throw new IllegalArgumentException("Global observation changed during planning");
        validateSnapshot();return out;
    }

    /** One source-only candidate snapshot per plan, never one new Builder observation per pair. */
    private void collectConditions(Result out,String topic,LinkedHashMap<String,Chunk> ranked,VettingContextBuilder.Selection global,Map<String,Integer> ranks,Set<String> globalSelected) {
        List<VettingContextBuilder.GroupTrace> observed=global.getGroups().stream().filter(VettingSourceRequestPacks::conditionGroup).collect(Collectors.toList());
        if(observed.isEmpty())return;
        VettingContextBuilder.Selection candidates=observer.build(topic,new ArrayList<>(ranked.values()),Collections.emptyMap(),0);
        out.setConditionCandidateObservations(1);
        Set<String> confirmed=candidates.getGroups().stream().filter(VettingSourceRequestPacks::conditionGroup).map(g->JsonUtils.write(g.getCoreIds())).collect(Collectors.toCollection(LinkedHashSet::new));
        int ordinal=0;
        for(VettingContextBuilder.GroupTrace trace:observed) {
            ordinal++;
            List<String> pair=trace.getCoreIds();
            // addPair's first member is the actual original ranked observation source.
            List<String> origins=pair.size()==2&&ranked.containsKey(pair.get(0))?Collections.singletonList(pair.get(0)):Collections.emptyList();
            String episode="independent_ranked_condition_observation_"+ordinal;
            Request r=newRequest(out,episode,"observed_entity_qualifier",origins,null,ranks);
            r.setSourceTrace(JsonUtils.readMap(JsonUtils.write(trace)));
            Map<String,Object> identity=new LinkedHashMap<>();identity.put("observationKind","original_ranked_source_entity_condition_candidate");identity.put("originalRankedObservationSourceIds",new ArrayList<>(origins));identity.put("pairCoreIds",new ArrayList<>(pair));identity.put("candidateSnapshotSha256",VettingCorpus.hash(JsonUtils.write(candidates.getGroups())));identity.put("sourceRelationAlgorithm",VettingContextBuilder.STRATEGY);identity.put("candidateBuilderLimit",0);identity.put("candidateObservationCountPerPlan",1);identity.put("globalLiteralEligibilityConferred",false);identity.put("conditionCanBecomeLiteralOrigin",false);identity.put("weakContextIdsIncluded",false);r.setOriginObservation(identity);
            if(pair.size()!=2||origins.isEmpty()||pair.get(0).equals(pair.get(1))||!confirmed.contains(JsonUtils.write(pair)))r.setUnknownReason("condition_pair_not_observed_by_current_builder");
            LinkedHashSet<String> requiredPair=new LinkedHashSet<>(pair);
            if(r.getUnknownReason()==null)for(String id:pair) {
                Chunk c=snapshot.get(id);String state=c==null?"missing_canonical_condition_member":sourceStatus(c);
                if(state!=null){r.setUnknownReason(state);break;}
                if(!"tender".equals(c.getRole())){r.setUnknownReason("condition_pair_role_differs");break;}
                for(VettingBlockContinuation.Trace block:continuation.resolve(c)) {
                    if(block.getRequiredChunkIds().isEmpty()){r.setUnknownReason("condition_observed_block_unknown_"+block.getStatus());break;}
                    for(String member:block.getRequiredChunkIds()) {
                        Chunk target=snapshot.get(member);
                        if(target==null||!source(c).equals(source(target))){r.setUnknownReason("condition_observed_block_source_differs");break;}
                        requiredPair.add(member);
                    }
                }
            }
            // No collect() on the condition or its continuation: these are transported evidence only.
            required(r,requiredPair);finishMissing(r,globalSelected);
        }
    }
    private static boolean conditionGroup(VettingContextBuilder.GroupTrace g) {
        return "shared_entity_extension".equals(g.getRelation())&&"observed_entity_qualifier_terms".equals(g.getRelationStrength());
    }

    private void collect(Result out,String observation,VettingContextBuilder.Selection s,Set<String> eligible,Map<String,Integer> ranks,Set<String> globalSelected) {
        for(VettingContextBuilder.ReferenceTrace trace:s.getReferences())if(eligible.contains(trace.getOriginId())&&trace.isInitiallySubmitted()) {
            Request r=newRequest(out,observation,"outgoing_literal",Collections.singletonList(trace.getOriginId()),trace.getReference(),ranks);r.setSourceTrace(JsonUtils.readMap(JsonUtils.write(trace)));
            if("unknown".equals(trace.getResolutionMode())||trace.getRequiredTargetIds().isEmpty())r.setUnknownReason("unresolved_literal_target_"+trace.getStatus());
            else validateTarget(r,trace.getRequiredTargetIds(),trace.getTargetSourceIdentity(),trace.getTargetClauseId(),trace.getTargetHeadingLocation());
            required(r,trace.getRequiredTargetIds());
            finishMissing(r,globalSelected);
        }
        for(VettingBlockContinuation.Trace trace:s.getBlockContinuations())if(eligible.contains(trace.getOriginId())&&trace.isInitiallySubmitted()) {
            Request r=newRequest(out,observation,"origin_observed_block",Collections.singletonList(trace.getOriginId()),null,ranks);r.setSourceTrace(JsonUtils.readMap(JsonUtils.write(trace)));
            if(trace.getRequiredChunkIds().isEmpty())r.setUnknownReason("unknown_observed_block_"+trace.getStatus());required(r,trace.getRequiredChunkIds());finishMissing(r,globalSelected);
        }
        for(VettingIncomingLiteralContext.Trace trace:s.getIncomingReferences()) {
            List<String> origins=trace.getInitialOriginIds().stream().filter(eligible::contains).collect(Collectors.toList());
            if(origins.isEmpty()) {
                if(!trace.isInitiallyConnected()&&!eligible.isEmpty()) {Request unknown=newRequest(out,observation,"incoming_literal",Collections.emptyList(),trace.getReference(),ranks);unknown.setSourceTrace(JsonUtils.readMap(JsonUtils.write(trace)));unknown.setStatus("unknown");unknown.setUnknownReason("unresolved_initial_origin_relation_"+trace.getStatus());}
                continue;
            }
            Request r=newRequest(out,observation,"incoming_literal",origins,trace.getReference(),ranks);r.setSourceTrace(JsonUtils.readMap(JsonUtils.write(trace)));
            if(!trace.isInitiallyConnected()||trace.getRequiredChunkIds().isEmpty())r.setUnknownReason("unresolved_incoming_"+trace.getStatus());
            else {validateTarget(r,trace.getResolvedTargetFamilyIds(),trace.getTargetSourceIdentity(),trace.getTargetClauseId(),trace.getTargetHeadingLocation());validateIncoming(r,trace);}
            required(r,trace.getRequiredChunkIds());
            finishMissing(r,globalSelected);
        }
    }
    private Request newRequest(Result out,String observation,String kind,List<String> origins,String reference,Map<String,Integer> ranks) {
        Request r=new Request();r.setOrdinal(out.getRequests().size()+1);r.setObservationId(observation);r.setKind(kind);r.setReference(reference);r.setEligibleOriginIds(new ArrayList<>(origins));
        r.setRankOrdinal(origins.stream().mapToInt(id->ranks.getOrDefault(id,Integer.MAX_VALUE)).min().orElse(Integer.MAX_VALUE));
        r.setId(VettingCorpus.hash(POLICY+"|"+observation+"|"+kind+"|"+r.getOrdinal()+"|"+JsonUtils.write(origins)+"|"+nvl(reference)));out.getRequests().add(r);return r;
    }
    private void unknownSeed(Result out,String observation,Chunk seed,Map<String,Integer> ranks,String reason) {
        Request r=newRequest(out,observation,"ranked_source_observation",Collections.singletonList(seed.getId()),null,ranks);r.setStatus("unknown");r.setUnknownReason(reason);r.getRequiredChunkIds().add(seed.getId());r.setContentChars(seed.getContent().length());
    }
    private void required(Request r,Collection<String> targetIds) {
        LinkedHashSet<String> ids=new LinkedHashSet<>(r.getEligibleOriginIds());
        for(String origin:r.getEligibleOriginIds()) {
            Chunk c=snapshot.get(origin);String state=c==null?"missing_canonical_origin":sourceStatus(c);if(state!=null){r.setUnknownReason(state);break;}
            for(VettingBlockContinuation.Trace t:continuation.resolve(c)) {
                if(t.getRequiredChunkIds().isEmpty()){r.setUnknownReason("origin_observed_block_unknown_"+t.getStatus());break;}
                for(String id:t.getRequiredChunkIds()) {Chunk member=snapshot.get(id);if(member==null||!source(c).equals(source(member))){r.setUnknownReason("origin_observed_block_source_differs");break;}ids.add(id);}
            }
        }
        ids.addAll(targetIds);
        for(String id:ids) {Chunk c=snapshot.get(id);String state=c==null?"missing_canonical_request_member":sourceStatus(c);if(state!=null){r.setUnknownReason(state);break;}}
        r.setRequiredChunkIds(new ArrayList<>(ids));long cost=0;for(String id:ids)if(snapshot.containsKey(id))cost+=snapshot.get(id).getContent().length();
        if(cost>Integer.MAX_VALUE){r.setUnknownReason("request_cost_overflow");r.setContentChars(Integer.MAX_VALUE);}else r.setContentChars((int)cost);
    }
    private void validateTarget(Request r,List<String> ids,String identity,String clause,String heading) {
        if(ids.isEmpty()||nvl(identity).isEmpty()||nvl(clause).isEmpty()||nvl(heading).isEmpty()){r.setUnknownReason("target_identity_unknown");return;}
        String sourceIdentity=null;Map<String,Set<String>> headings=new LinkedHashMap<>();
        for(String id:ids) {Chunk c=snapshot.get(id);if(c==null){r.setUnknownReason("missing_canonical_target");return;}
            String source=nvl(c.getDocumentId())+"|"+nvl(c.getSourceHash())+"|"+nvl(c.getRole());
            if(sourceIdentity==null)sourceIdentity=source;if(!source.equals(sourceIdentity)||!identity.equals(source+"|"+heading)){r.setUnknownReason("target_source_binding_differs");return;}
            String family=canonical(c.getClauseId()).replaceFirst("\\.[A-Z]$","");
            if(!family.equals(clause)&&!family.startsWith(clause+"(")){r.setUnknownReason("target_family_binding_differs");return;}
            if(nvl(c.getClauseHeadingLocation()).isEmpty()){r.setUnknownReason("target_heading_unknown");return;}
            headings.computeIfAbsent(family,k->new LinkedHashSet<>()).add(c.getClauseHeadingLocation());
        }
        if(headings.values().stream().anyMatch(h->h.size()!=1)||!headings.getOrDefault(clause,Collections.emptySet()).contains(heading))r.setUnknownReason("target_heading_binding_differs");
        Set<String> expected=new LinkedHashSet<>();
        for(Chunk c:snapshot.values())if((nvl(c.getDocumentId())+"|"+nvl(c.getSourceHash())+"|"+nvl(c.getRole())).equals(sourceIdentity)&&ownedClause(c)) {
            String family=canonical(c.getClauseId()).replaceFirst("\\.[A-Z]$","");if(family.equals(clause)||family.startsWith(clause+"("))expected.add(c.getId());
        }
        if(!expected.equals(new LinkedHashSet<>(ids)))r.setUnknownReason("incomplete_canonical_target_family");
    }
    private void validateIncoming(Request r,VettingIncomingLiteralContext.Trace trace) {
        Chunk incoming=snapshot.get(trace.getIncomingSourceId());
        if(incoming==null){r.setUnknownReason("missing_canonical_incoming_source");return;}
        String identity=String.join("|",Arrays.asList(nvl(incoming.getDocumentId()),nvl(incoming.getSourceHash()),nvl(incoming.getRole()),nvl(incoming.getMetadataVersion()),nvl(incoming.getSegmentationVersion()),nvl(incoming.getNativeTableMetadataVersion()),nvl(incoming.getSourceQualityMetadataVersion()),nvl(incoming.getSourceQualityHash())));
        if(!identity.equals(trace.getIncomingSourceIdentity())||!Objects.equals(incoming.getAnchor(),trace.getIncomingSourceAnchor())){r.setUnknownReason("incoming_source_binding_differs");return;}
        Set<String> expected=new LinkedHashSet<>();expected.add(incoming.getId());
        for(VettingBlockContinuation.Trace block:continuation.resolve(incoming)) {
            if(block.getRequiredChunkIds().isEmpty()){r.setUnknownReason("incoming_block_boundary_unknown");return;}expected.addAll(block.getRequiredChunkIds());
        }
        for(String id:trace.getRequiredChunkIds())if(!snapshot.containsKey(id)||!source(incoming).equals(source(snapshot.get(id)))){r.setUnknownReason("incoming_observed_source_differs");return;}
        if(!expected.equals(new LinkedHashSet<>(trace.getRequiredChunkIds())))r.setUnknownReason("incomplete_canonical_incoming_block");
    }
    private String sourceStatus(Chunk c) {
        if(nvl(c.getDocumentId()).isEmpty()||!nvl(c.getSourceHash()).matches("[a-f0-9]{64}")||!Arrays.asList("tender","standard","project_fact","package_manifest").contains(c.getRole()))return "unknown_source_identity";
        if(documentIdentities.getOrDefault(c.getDocumentId(),Collections.emptySet()).size()!=1)return "ambiguous_document_source_identity";
        if(!VettingCorpus.METADATA_VERSION.equals(c.getMetadataVersion())||!VettingCorpus.SEGMENTATION_VERSION.equals(c.getSegmentationVersion())||!VettingCorpus.NATIVE_TABLE_METADATA_VERSION.equals(c.getNativeTableMetadataVersion())
                ||!VettingSourceQuality.VERSION.equals(c.getSourceQualityMetadataVersion())||c.getSourceQuality()==null||!Objects.equals(c.getSourceQualityHash(),VettingSourceQuality.hash(c.getSourceQuality()))||!VettingSourceQuality.project(c).containsKey("observationScope"))return "unknown_source_quality_or_version";
        if(c.getParts()==null||c.getParts().isEmpty()||nvl(c.getAnchor()).isEmpty())return "unknown_source_part_mapping";
        StringBuilder joined=new StringBuilder();Set<String> slices=new HashSet<>();
        for(Part p:c.getParts()) {
            if(p==null||p.getText()==null||p.getText().isEmpty()||nvl(p.getBlockId()).isEmpty()||nvl(p.getAnchor()).isEmpty()||p.getStartOffset()<0||p.getEndOffset()-p.getStartOffset()!=p.getText().length()
                    ||Character.isLowSurrogate(p.getText().charAt(0))||Character.isHighSurrogate(p.getText().charAt(p.getText().length()-1))||!slices.add(p.getBlockId()+"|"+p.getStartOffset()+"|"+p.getEndOffset()))return "unknown_source_part_mapping";
            if(p.getStartOffset()>0&&!p.getAnchor().endsWith(" @"+p.getStartOffset()))return "unknown_source_part_mapping";
            if(joined.length()>0)joined.append('\n');joined.append(p.getText());
        }
        return c.getContent().equals(joined.toString())?null:"unknown_source_part_mapping";
    }
    private Chunk canonical(Chunk supplied) {
        if(supplied==null||!Objects.equals(payloads.get(supplied.getId()),JsonUtils.write(supplied)))throw new IllegalArgumentException("Full canonical source payload differs");return snapshot.get(supplied.getId());
    }
    private void validateSnapshot() {
        for(String id:payloads.keySet())if(!payloads.get(id).equals(JsonUtils.write(external.get(id)))||!payloads.get(id).equals(JsonUtils.write(snapshot.get(id))))throw new IllegalArgumentException("Canonical corpus changed since snapshot");
    }
    private Chunk copy(String id) {return JsonUtils.read(payloads.get(id),Chunk.class);}
    private static void finishMissing(Request r,Set<String> global) {r.setGloballySubmittedIds(r.getRequiredChunkIds().stream().filter(global::contains).collect(Collectors.toList()));r.setMissingChunkIds(r.getRequiredChunkIds().stream().filter(id->!global.contains(id)).collect(Collectors.toList()));}
    private static int incremental(Request r,Pack pack) {return r.getContentChars()-pack.getChunks().stream().filter(c->r.getRequiredChunkIds().contains(c.getId())).mapToInt(c->c.getContent().length()).sum();}
    private static Map<String,Object> observationIdentity(VettingContextBuilder.Selection s,String seed,boolean admitted) {Map<String,Object> out=new LinkedHashMap<>();out.put("originalRankedSeedId",seed);out.put("onlyEligibleLiteralOriginId",seed);out.put("seedAdmitted",admitted);out.put("selectionHash",VettingCorpus.hash(JsonUtils.write(s)));out.put("observedSelectedIds",s.getChunks().stream().map(Chunk::getId).collect(Collectors.toList()));out.put("derivedOriginsAllowed",false);return out;}
    private static boolean genuine(String relation) {return Arrays.asList("single_seed","shared_entity","explicit_reference").contains(relation);}
    private static boolean ownedClause(Chunk c) {String owner=canonical(c.getFileKey());return !owner.isEmpty()&&!"OTHER".equals(owner)&&canonical(c.getClauseId()).matches(java.util.regex.Pattern.quote(owner)+"(?:\\.[A-Z]+)?\\.?\\d.*");}
    private static String source(Chunk c) {return JsonUtils.write(Arrays.asList(c.getDocumentId(),c.getSourceHash(),c.getRole(),c.getMetadataVersion(),c.getSegmentationVersion(),c.getNativeTableMetadataVersion(),c.getSourceQualityMetadataVersion(),c.getSourceQualityHash()));}
    private static String canonical(String s) {return nvl(s).replaceAll("\\s+","").toUpperCase(Locale.ROOT);}
    private static String nvl(String s) {return s==null?"":s;}
}
