package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSourceRequestPacks.*;
import lombok.Data;
import java.util.*;
import java.util.stream.Collectors;

/** Repack existing one-hop observations using complete bound token observations; no source graph expansion. */
public final class VettingTokenAwareRequestPacks {
    public static final String POLICY="related_whole_source_requests_direct_condition_bundles_complete_bound_input_tokens_v3";
    @FunctionalInterface public interface InputFactory { VettingInputBudget.Input build(Pack candidate); }
    @Data public static class Attempt {
        private String requestId, originalTransportStatus, outcome;
        private int candidatePackIndex;
        private List<String> requiredChunkIds=new ArrayList<>(), candidateChunkIds=new ArrayList<>();
        private VettingInputBudget.Result inputBudget;
        private boolean previewOnly=true, semanticScopeVerified=false;
    }
    @Data public static class Plan {
        private String policy=POLICY;
        private Result sourcePlan;
        private VettingInputBudget.Result globalInputBudget;
        private Map<Integer,VettingInputBudget.Result> packInputBudgets=new LinkedHashMap<>();
        private List<Attempt> attempts=new ArrayList<>();
        private Map<String,String> originalRequestTransportStatuses=new LinkedHashMap<>();
        private Map<String,String> originalRequestSourceUnknownReasons=new LinkedHashMap<>();
        private List<RelatedGroup> relatedGroups=new ArrayList<>();
        private List<ConditionBundle> conditionBundles=new ArrayList<>();
        private boolean legacyCharacterGateApplied=false;
        private int actualPreviewObserverCalls, previewCacheHits;
        private boolean previewIsFinalDispatchAuthority=false, semanticScopeVerified=false;
    }
    @Data public static class RelatedGroup {
        private List<String> requestIds=new ArrayList<>(), eligibleOriginIds=new ArrayList<>();
        private VettingInputBudget.Result completeGroupBudget;
        private String outcome;
        private boolean splitAfterObservedOverBudget=false, semanticScopeVerified=false;
    }
    /** Co-location ledger, distinct from an individual request's first published membership. */
    @Data public static class ConditionBundle {
        private String focalRequestId, outcome;
        private List<String> conditionRequestIds=new ArrayList<>(), requestIds=new ArrayList<>(), eligibleOriginIds=new ArrayList<>(), requiredChunkIds=new ArrayList<>();
        private List<VettingInputBudget.Result> candidateBudgets=new ArrayList<>();
        private int selectedPackIndex;
        private boolean createdAfterObservedComponentOverBudget=true, conditionCanBecomeOrigin=false, semanticScopeVerified=false;
    }
    private final Map<String,Chunk> canonical=new LinkedHashMap<>(),external=new LinkedHashMap<>();
    private final Map<String,String> bytes=new LinkedHashMap<>(),hashes=new LinkedHashMap<>();
    private static class PreviewMemo {
        final Map<String,VettingInputBudget.Result> budgets=new LinkedHashMap<>();
        final Map<String,String> inputs=new LinkedHashMap<>();
        String observationIdentity;
    }
    public VettingTokenAwareRequestPacks(List<Chunk> corpus) {
        if(corpus==null)throw new IllegalArgumentException("Canonical source corpus required");
        for(Chunk c:corpus) {
            if(c==null||c.getId()==null||c.getId().isEmpty()||bytes.containsKey(c.getId()))throw new IllegalArgumentException("Unique canonical source IDs required");
            String json=JsonUtils.write(c);bytes.put(c.getId(),json);hashes.put(c.getId(),VettingCorpus.hash(json));external.put(c.getId(),c);canonical.put(c.getId(),JsonUtils.read(json,Chunk.class));
        }
    }
    public Plan repack(Result original,VettingContextBuilder.Selection global,InputFactory factory,VettingInputBudget.Observer observer) {
        if(original==null||global==null||factory==null)throw new IllegalArgumentException("Bound source plan, global observation and complete Input factory required");
        validateMutableSources();String originalJson=JsonUtils.write(original),globalJson=JsonUtils.write(global);
        if(!VettingSourceRequestPacks.POLICY.equals(original.getPolicy())||!hashes.equals(original.getCanonicalPayloadHashes())||!VettingCorpus.hash(globalJson).equals(original.getGlobalSelectionHash())
                ||!JsonUtils.write(global.getChunks()).equals(JsonUtils.write(original.getGlobalChunks())))throw new IllegalArgumentException("Canonical source/global plan identity differs");
        Set<String> ranked=new LinkedHashSet<>(original.getOriginalRankedIds());if(ranked.size()!=original.getOriginalRankedIds().size())throw new IllegalArgumentException("Original ranked identities differ");
        for(String id:ranked)if(!canonical.containsKey(id)||!"tender".equals(canonical.get(id).getRole()))throw new IllegalArgumentException("Rank origin not canonical tender");
        Set<String> retrieved=new LinkedHashSet<>(original.getOriginalRetrievedIds());if(retrieved.size()!=original.getOriginalRetrievedIds().size())throw new IllegalArgumentException("Original retrieval seed identities differ");
        for(String id:retrieved)if(!canonical.containsKey(id))throw new IllegalArgumentException("Original retrieval seed is not canonical");
        for(Chunk c:global.getChunks())exact(c);
        Result out=JsonUtils.read(originalJson,Result.class);out.getExtraPacks().clear();out.setPolicy(POLICY);out.setBudgetScope("complete_gateway_messages_schema_chat_template_and_output_reserve_observed_per_candidate");
        out.setTokenEnvelopeMeasured(false);Plan plan=new Plan();plan.setSourcePlan(out);PreviewMemo memo=new PreviewMemo();
        Pack globalPack=new Pack();globalPack.setIndex(0);globalPack.setChunks(copies(global.getChunks().stream().map(Chunk::getId).collect(Collectors.toList())));globalPack.setContentChars(global.getContentChars());
        globalPack.setRequestIds(original.getRequests().stream().filter(r->"already_global".equals(r.getStatus())).map(Request::getId).collect(Collectors.toList()));
        plan.setGlobalInputBudget(observe(globalPack,factory,observer,plan,memo));
        boolean providerEstimate=plan.getGlobalInputBudget().getObservation()!=null&&plan.getGlobalInputBudget().getObservation().getProviderEstimate()!=null;
        if(providerEstimate)out.setBudgetScope("complete_provider_wire_input_estimate_with_explicit_configured_limit_and_absolute_margin_not_exact_template");
        Set<String> requestIds=new HashSet<>();List<Request> known=new ArrayList<>();
        for(Request r:out.getRequests()) {
            if(r.getId()==null||!requestIds.add(r.getId()))throw new IllegalArgumentException("Unique observed request IDs required");
            plan.getOriginalRequestTransportStatuses().put(r.getId(),r.getStatus());
            if(r.getUnknownReason()!=null)plan.getOriginalRequestSourceUnknownReasons().put(r.getId(),r.getUnknownReason());
            if(r.getUnknownReason()!=null||"unknown".equals(r.getStatus()))continue;
            if(r.getRequiredChunkIds().isEmpty()||new HashSet<>(r.getRequiredChunkIds()).size()!=r.getRequiredChunkIds().size()
                    ||r.getEligibleOriginIds().isEmpty()||!("retrieved_structural_unit".equals(r.getKind())?retrieved:ranked).containsAll(r.getEligibleOriginIds())||!r.getRequiredChunkIds().containsAll(r.getEligibleOriginIds()))throw new IllegalArgumentException("Whole request/ranked origin identity differs");
            long chars=0;for(String id:r.getRequiredChunkIds()){Chunk c=canonical.get(id);if(c==null)throw new IllegalArgumentException("Whole request canonical member missing");chars+=c.getContent().length();}
            if(chars!=r.getContentChars())throw new IllegalArgumentException("Whole request source cost differs");
            known.add(r);
        }
        // Related references of the same original ranked source are tried together before first-fit.
        // Shared targets do not become new origins and do not connect otherwise independent groups.
        for(List<Request> group:related(known)) {
            RelatedGroup trace=new RelatedGroup();trace.setRequestIds(group.stream().map(Request::getId).collect(Collectors.toList()));
            trace.setEligibleOriginIds(group.stream().flatMap(r->r.getEligibleOriginIds().stream()).distinct().collect(Collectors.toList()));plan.getRelatedGroups().add(trace);
            if(group.stream().allMatch(r->"already_global".equals(r.getStatus()))&&plan.getGlobalInputBudget().isExtraDispatchPermitted()) {
                for(Request r:group)r.setTokenEnvelopeMeasured(exactMeasurement(plan.getGlobalInputBudget()));trace.setOutcome("already_global");continue;
            }
            Pack whole=empty(out.getExtraPacks().size()+1);for(Request r:group)whole=union(whole,r);
            VettingInputBudget.Result wholeBudget=observe(whole,factory,observer,plan,memo);trace.setCompleteGroupBudget(copyBudget(wholeBudget));trace.setOutcome(wholeBudget.getStatus());
            for(Request r:group)attempt(plan,r,whole,wholeBudget);
            if(wholeBudget.isExtraDispatchPermitted()) {
                Pack chosen=whole;VettingInputBudget.Result chosenBudget=wholeBudget;
                for(Pack p:out.getExtraPacks()) {
                    Pack candidate=p;for(Request r:group)candidate=union(candidate,r);
                    VettingInputBudget.Result budget=observe(candidate,factory,observer,plan,memo);for(Request r:group)attempt(plan,r,candidate,budget);
                    if(budget.isExtraDispatchPermitted()){chosen=candidate;chosenBudget=budget;break;}
                }
                if(chosen.getIndex()>out.getExtraPackCap()) {
                    for(Request r:group)pending(r,"omitted_pack_cap",null);trace.setOutcome("omitted_pack_cap");continue;
                }
                publish(out,plan,chosen,chosenBudget,group);trace.setOutcome("transported_related_group");continue;
            }
            if("budget_unknown".equals(wholeBudget.getStatus())) {
                for(Request r:group)pending(r,"budget_unknown","complete_input_budget_observation_unknown");continue;
            }
            // A known-over component can be decomposed, but observed conditions remain with each
            // directly related request. Sibling references and condition targets are never new seeds.
            trace.setSplitAfterObservedOverBudget(group.size()>1);
            fallback(plan,out,group,factory,observer,memo);
        }
        // Preview caches cannot substitute for the independent final dispatch observation.
        out.setTokenEnvelopeMeasured(exactMeasurement(plan.getGlobalInputBudget())&&out.getRequests().stream().noneMatch(r->"budget_unknown".equals(r.getStatus())));
        if(!originalJson.equals(JsonUtils.write(original))||!globalJson.equals(JsonUtils.write(global)))throw new IllegalArgumentException("Source observation changed during token planning");
        validateMutableSources();return plan;
    }
    private void fallback(Plan plan,Result out,List<Request> component,InputFactory factory,VettingInputBudget.Observer observer,PreviewMemo memo) {
        List<List<Request>> bundles=new ArrayList<>();Set<String> attached=new HashSet<>();
        for(Request focal:component)if(!condition(focal)) {
            List<Request> members=new ArrayList<>();members.add(focal);Set<String> origins=new HashSet<>(focal.getEligibleOriginIds());
            for(Request candidate:out.getRequests())if(condition(candidate)&&!candidate.getEligibleOriginIds().isEmpty()&&origins.containsAll(candidate.getEligibleOriginIds())) {
                members.add(candidate);attached.add(candidate.getId());
            }
            bundles.add(members);
        }
        for(Request request:component)if(condition(request)&&!attached.contains(request.getId()))bundles.add(Collections.singletonList(request));
        for(List<Request> members:bundles) {
            Request focal=members.get(0);ConditionBundle trace=new ConditionBundle();trace.setFocalRequestId(focal.getId());trace.setEligibleOriginIds(new ArrayList<>(focal.getEligibleOriginIds()));
            trace.setRequestIds(members.stream().map(Request::getId).collect(Collectors.toList()));trace.setConditionRequestIds(members.stream().skip(1).map(Request::getId).collect(Collectors.toList()));
            trace.setRequiredChunkIds(members.stream().flatMap(r->r.getRequiredChunkIds().stream()).distinct().collect(Collectors.toList()));plan.getConditionBundles().add(trace);
            // An unresolved condition is visible and blocks this joint candidate, not silently omitted.
            if(members.stream().anyMatch(r->plan.getOriginalRequestSourceUnknownReasons().containsKey(r.getId())||"unknown".equals(plan.getOriginalRequestTransportStatuses().get(r.getId())))) {
                trace.setOutcome("unknown_source_condition");for(Request r:members)pendingUnlessPublished(out,r,"budget_unknown","attached_condition_source_request_unknown");continue;
            }
            Pack whole=empty(out.getExtraPacks().size()+1);for(Request r:members)whole=union(whole,r);
            VettingInputBudget.Result wholeBudget=observe(whole,factory,observer,plan,memo);trace.getCandidateBudgets().add(copyBudget(wholeBudget));for(Request r:members)attempt(plan,r,whole,wholeBudget);
            if(!wholeBudget.isExtraDispatchPermitted()) {
                trace.setOutcome(wholeBudget.getStatus());for(Request r:members)pendingUnlessPublished(out,r,wholeBudget.getStatus(),"budget_unknown".equals(wholeBudget.getStatus())?"complete_joint_condition_input_budget_unknown":null);continue;
            }
            Pack chosen=whole;VettingInputBudget.Result chosenBudget=wholeBudget;
            for(Pack p:out.getExtraPacks()) {
                Pack candidate=p;for(Request r:members)candidate=union(candidate,r);
                VettingInputBudget.Result budget=observe(candidate,factory,observer,plan,memo);trace.getCandidateBudgets().add(copyBudget(budget));for(Request r:members)attempt(plan,r,candidate,budget);
                if(budget.isExtraDispatchPermitted()){chosen=candidate;chosenBudget=budget;break;}
            }
            if(chosen.getIndex()>out.getExtraPackCap()) {
                trace.setOutcome("omitted_pack_cap");for(Request r:members)pendingUnlessPublished(out,r,"omitted_pack_cap",null);continue;
            }
            publish(out,plan,chosen,chosenBudget,members);trace.setOutcome("transported_direct_condition_bundle");trace.setSelectedPackIndex(chosen.getIndex());
        }
    }
    private static boolean condition(Request r){return "observed_entity_qualifier".equals(r.getKind());}
    private static boolean published(Result out,Request r) {
        return out.getExtraPacks().stream().anyMatch(p->p.getRequestIds().contains(r.getId())&&p.getChunks().stream().map(Chunk::getId).collect(Collectors.toSet()).containsAll(r.getRequiredChunkIds()));
    }
    private static void pendingUnlessPublished(Result out,Request r,String status,String reason){if(!published(out,r))pending(r,status,reason);}
    private static List<List<Request>> related(List<Request> requests) {
        List<List<Request>> groups=new ArrayList<>();
        for(Request request:requests) {
            List<Request> merged=new ArrayList<>();merged.add(request);Set<String> origins=new LinkedHashSet<>(request.getEligibleOriginIds());
            int insertion=groups.size();
            for(int i=0;i<groups.size();) {
                List<Request> group=groups.get(i);
                if(group.stream().flatMap(r->r.getEligibleOriginIds().stream()).anyMatch(origins::contains)) {
                    insertion=Math.min(insertion,i);merged.addAll(group);for(Request r:group)origins.addAll(r.getEligibleOriginIds());groups.remove(i);i=0;
                } else i++;
            }
            merged.sort(Comparator.comparingInt(Request::getOrdinal));groups.add(Math.min(insertion,groups.size()),merged);
        }
        return groups;
    }
    private static void pending(Request r,String status,String reason) {r.setStatus(status);r.setUnknownReason(reason);r.setPackIndex(0);r.getSubmittedChunkIds().clear();r.setMissingChunkIds(new ArrayList<>(r.getRequiredChunkIds()));}
    private static void publish(Result out,Plan plan,Pack pack,VettingInputBudget.Result budget,List<Request> group) {
        Map<String,Integer> firstPublished=new HashMap<>();
        for(Request r:group)for(Pack p:out.getExtraPacks())if(p.getRequestIds().contains(r.getId())&&p.getChunks().stream().map(Chunk::getId).collect(Collectors.toSet()).containsAll(r.getRequiredChunkIds())){firstPublished.put(r.getId(),p.getIndex());break;}
        pack.setTokenEnvelopeMeasured(exactMeasurement(budget));int position=pack.getIndex()-1;
        if(position==out.getExtraPacks().size())out.getExtraPacks().add(pack);else out.getExtraPacks().set(position,pack);
        plan.getPackInputBudgets().put(pack.getIndex(),copyBudget(budget));
        for(Request r:group){r.setStatus("transported_extra_pack");r.setUnknownReason(null);r.setPackIndex(firstPublished.getOrDefault(r.getId(),pack.getIndex()));r.setTokenEnvelopeMeasured(exactMeasurement(budget));r.setSubmittedChunkIds(new ArrayList<>(r.getRequiredChunkIds()));r.getMissingChunkIds().clear();}
    }
    private VettingInputBudget.Result observe(Pack candidate,InputFactory factory,VettingInputBudget.Observer observer,Plan plan,PreviewMemo memo) {
        Pack supplied=JsonUtils.read(JsonUtils.write(candidate),Pack.class);String before=JsonUtils.write(supplied);VettingInputBudget.Input input;
        try{input=factory.build(supplied);}catch(Exception failure){VettingInputBudget.Result out=new VettingInputBudget.Result();out.setReason("complete_input_factory_failed_"+failure.getClass().getSimpleName());return out;}
        if(input==null||!before.equals(JsonUtils.write(supplied))||!VettingCorpus.hash(JsonUtils.write(candidate.getChunks())).equals(input.getSourceSnapshotSha256()))throw new IllegalArgumentException("Complete Input/candidate source identity differs");
        String sha=input.getSerializedInputSha256(),inputBytes=JsonUtils.write(input);
        if(memo.budgets.containsKey(sha)) {
            if(!inputBytes.equals(memo.inputs.get(sha)))throw new IllegalArgumentException("Preview input SHA reused for a different complete Input");
            plan.setPreviewCacheHits(plan.getPreviewCacheHits()+1);return copyBudget(memo.budgets.get(sha));
        }
        VettingInputBudget.Result budget=VettingInputBudget.check(input,observer);if(observer!=null)plan.setActualPreviewObserverCalls(plan.getActualPreviewObserverCalls()+1);
        if(!"budget_unknown".equals(budget.getStatus())) {
            String identity=observationIdentity(input,budget.getObservation());
            if(memo.observationIdentity==null)memo.observationIdentity=identity;
            else if(!memo.observationIdentity.equals(identity)){budget.setStatus("budget_unknown");budget.setReason("token_plan_observer_identity_changed");budget.setExtraDispatchPermitted(false);}
        }
        memo.inputs.put(sha,inputBytes);memo.budgets.put(sha,copyBudget(budget));return budget;
    }
    private void attempt(Plan plan,Request request,Pack candidate,VettingInputBudget.Result budget) {
        Attempt row=new Attempt();row.setRequestId(request.getId());row.setOriginalTransportStatus(plan.getOriginalRequestTransportStatuses().get(request.getId()));row.setCandidatePackIndex(candidate.getIndex());row.setRequiredChunkIds(new ArrayList<>(request.getRequiredChunkIds()));row.setCandidateChunkIds(candidate.getChunks().stream().map(Chunk::getId).collect(Collectors.toList()));row.setInputBudget(copyBudget(budget));row.setOutcome(budget.getStatus());plan.getAttempts().add(row);
    }
    private Pack union(Pack pack,Request request) {
        Pack out=JsonUtils.read(JsonUtils.write(pack),Pack.class);Set<String> ids=out.getChunks().stream().map(Chunk::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        for(String id:request.getRequiredChunkIds())if(ids.add(id)){Chunk c=copy(id);out.getChunks().add(c);out.setContentChars(Math.addExact(out.getContentChars(),c.getContent().length()));out.getSourcePayloadHashes().put(id,hashes.get(id));}
        if(!out.getRequestIds().contains(request.getId()))out.getRequestIds().add(request.getId());for(String id:request.getEligibleOriginIds())if(!out.getEligibleOriginIds().contains(id))out.getEligibleOriginIds().add(id);return out;
    }
    private Pack empty(int index){Pack p=new Pack();p.setIndex(index);return p;}
    private Chunk copy(String id){return JsonUtils.read(bytes.get(id),Chunk.class);}
    private List<Chunk> copies(List<String> ids){return ids.stream().map(this::copy).collect(Collectors.toList());}
    private void exact(Chunk c){if(c==null||!Objects.equals(bytes.get(c.getId()),JsonUtils.write(c)))throw new IllegalArgumentException("Source full payload differs");}
    private void validateMutableSources(){for(Chunk c:external.values())exact(c);}
    private static VettingInputBudget.Result copyBudget(VettingInputBudget.Result r){return JsonUtils.read(JsonUtils.write(r),VettingInputBudget.Result.class);}
    private static String observationIdentity(VettingInputBudget.Input in,VettingInputBudget.Observation o) {
        Map<String,Object> identity=new LinkedHashMap<>();identity.put("provider",in.getProvider());identity.put("providerConfigurationSha256",in.getProviderConfigurationSha256());identity.put("model",o.getModel());identity.put("tokenizerIdentitySha256",o.getTokenizerIdentitySha256());identity.put("chatTemplateIdentitySha256",o.getChatTemplateIdentitySha256());identity.put("effectiveContextIdentitySha256",o.getEffectiveContextIdentitySha256());identity.put("effectiveContextTokens",o.getEffectiveContextTokens());identity.put("outputReserveTokens",o.getOutputReserveTokens());
        if(o.getProviderEstimate()!=null){VettingResponsesTransport.EstimateIdentity e=o.getProviderEstimate();identity.put("providerEstimateKind",e.getKind());identity.put("wireProtocol",e.getWireProtocol());identity.put("counterContract",e.getCounterContract());identity.put("profileSha256",e.getProfileSha256());identity.put("contextPolicySha256",e.getContextPolicySha256());identity.put("safetyMarginTokens",e.getSafetyMarginTokens());identity.put("modelIdentityScope",e.getModelIdentityScope());identity.put("responseModelUnavailable",e.isResponseModelUnavailable());}
        return JsonUtils.write(identity);
    }
    private static boolean exactMeasurement(VettingInputBudget.Result budget){return !"budget_unknown".equals(budget.getStatus())&&(budget.getObservation()==null||budget.getObservation().getProviderEstimate()==null);}
    public static boolean sameFinalObservation(VettingInputBudget.Result preview,VettingInputBudget.Result actual) {return preview!=null&&actual!=null&&JsonUtils.write(preview).equals(JsonUtils.write(actual));}
}
