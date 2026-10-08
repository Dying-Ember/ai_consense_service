package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Source-only comparison windows. Relations are retrieval hints, never findings. */
public final class VettingContextBuilder {
    public static final String STRATEGY = "paired-local-window-genuine-ranked-origin-v7";
    public static final String EXPANSION_STRATEGY = "preserved_initial_genuine_ranked_source_closure_v4";
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern EXPANSION = Pattern.compile("([A-Za-z][A-Za-z' -]{2,150})\\s*\\(([A-Z][A-Z0-9]{1,11})\\)");
    private static final Pattern QUALIFIER = Pattern.compile("(?i)\\b(?:except|unless|provided that|save as|subject to|according to|at least|at most|minimum|maximum|solely|attendance)\\b");
    private static final Pattern OPERATIVE = Pattern.compile("(?i)\\b(?:shall|must|required|means|total|minimum|maximum|at least|at most|nos?|days?|months?)\\b");
    private static final Pattern EDITION = Pattern.compile("(?i)(?:edition\\s*(\\d{4})|(\\d{4})\\s+edition)");
    private static final Pattern OBLIGATION_SENTENCE = Pattern.compile("(?i)\\b(?:shall|must)\\b|\\b(?:means|mean)\\s+(?:the|an?|that)\\b");
    private static final Pattern CONTENTS_MARKER = Pattern.compile("(?i)^(?:table\\s+of\\s+)?contents?(?:\\s*\\(.*\\))?$ ".trim());
    private final Map<String,Chunk> byId = new LinkedHashMap<>();
    private final Map<String,List<Chunk>> scopes = new LinkedHashMap<>(), clauses = new LinkedHashMap<>(), entities = new LinkedHashMap<>();
    private final Map<String,Set<String>> chunkEntities = new HashMap<>();
    private final Map<String,Set<String>> entityAliases = new HashMap<>();
    private final Map<String,Integer> entityScopeCounts = new HashMap<>();
    private final Map<String,Integer> order = new HashMap<>();
    private final Pattern reference;
    private final VettingBlockContinuation continuations;
    private final VettingIncomingLiteralContext incomingLiterals;

    @Data public static class GroupTrace {
        private String id, relation, relationStrength = "rank_unknown", status = "candidate", scopeCoverage = "local_window", qualifiersComplete = "unknown";
        private List<String> coreIds = new ArrayList<>(), contextIds = new ArrayList<>(), unresolvedReferences = new ArrayList<>();
        private int incrementalChars, submittedCoreCount;
        private boolean fullySubmitted;
    }
    /** A single ranked-source literal edge, never global contract coverage. */
    @Data public static class ReferenceTrace {
        private String originId, originAnchor, reference, targetClauseId, targetSourceIdentity, targetHeadingLocation;
        private String resolutionMode = "unknown", status = "unknown", admissionStatus = "not_admitted";
        private List<String> requiredTargetIds = new ArrayList<>(), submittedTargetIds = new ArrayList<>(), missingTargetIds = new ArrayList<>();
        private int requestedIncrementalChars, budgetRemainingAtAdmission;
        private boolean initiallySubmitted, originSubmitted, fullParentContextSelected, exactSubclauseVerified;
    }
    @Data public static class Selection {
        private List<Chunk> chunks = new ArrayList<>();
        private List<GroupTrace> groups = new ArrayList<>();
        @JsonDeserialize(as = LinkedHashSet.class)
        private Set<String> droppedIds = new LinkedHashSet<>();
        @JsonDeserialize(as = LinkedHashSet.class)
        private Set<String> unresolvedComparisonIds = new LinkedHashSet<>();
        private int contentChars;
        private List<ReferenceTrace> references = new ArrayList<>();
        private List<VettingBlockContinuation.Trace> blockContinuations = new ArrayList<>();
        private List<VettingIncomingLiteralContext.Trace> incomingReferences = new ArrayList<>();
        private String incomingReferencePolicy = VettingIncomingLiteralContext.POLICY;
        private String blockContinuationPolicy = VettingBlockContinuation.POLICY;
        private String referencePolicy = "single_hop_selected_ranked_literal_v1", coverage = "submitted_chunks_only";
    }
    private static final class Group {
        final LinkedHashSet<String> core = new LinkedHashSet<>(), context = new LinkedHashSet<>();
        final List<String> unresolved = new ArrayList<>();
        final int rank; final String relation, strength;
        int priority;
        Group(int rank,String relation,String strength) { this.rank=rank;this.relation=relation;this.strength=strength; }
        Set<String> all() { Set<String> ids=new LinkedHashSet<>(core);ids.addAll(context);return ids; }
    }
    private static final class Trie {
        final Map<String,Trie> children=new HashMap<>(); final Set<String> keys=new LinkedHashSet<>();
        void add(List<String> terms,String key) { Trie node=this;for(String term:terms) node=node.children.computeIfAbsent(term,k -> new Trie());node.keys.add(key); }
    }

    public VettingContextBuilder(List<Chunk> corpus) {
        LinkedHashSet<String> owners=new LinkedHashSet<>();
        for(Chunk c:corpus) {
            if(c==null||nvl(c.getId()).isEmpty()||byId.containsKey(c.getId())) throw new IllegalArgumentException("Context corpus requires unique nonempty chunk IDs");
            byId.put(c.getId(),c);order.put(c.getId(),order.size());
            if(!nvl(c.getFileKey()).isEmpty()&&!"OTHER".equals(c.getFileKey())) owners.add(c.getFileKey());
        }
        continuations=new VettingBlockContinuation(corpus);
        String keys=owners.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        reference=keys.isEmpty()?null:Pattern.compile("(?i)\\b("+keys+")\\s*(?:(clauses?)\\s+)?((?:\\.\\s*[A-Z]+)?\\s*\\.?\\s*\\d+(?:\\s*\\.\\s*\\d+)*(?:\\([A-Z0-9]+\\))*(?:\\s*\\.\\s*[A-Z](?=\\s|[,;:]|$))?)");
        for(Chunk c:corpus) {
            if(!usable(c)) continue;
            String scope=scope(c); if(scope!=null) scopes.computeIfAbsent(scope,k -> new ArrayList<>()).add(c);
            if(c.getClauseId()!=null&&Arrays.asList("tender","standard").contains(c.getRole()))
                clauses.computeIfAbsent(canonical(c.getClauseId()),k -> new ArrayList<>()).add(c);
        }
        indexEntities();
        incomingLiterals=new VettingIncomingLiteralContext(corpus,this::references,this::usable,(ref,source) -> {
            TargetResolution resolved=resolve(ref,source);
            return new VettingIncomingLiteralContext.Resolved(resolved.mode,resolved.status,resolved.clause,resolved.source,resolved.heading,resolved.chunks);
        });
    }

    public Selection build(String topic,List<Chunk> rankedTender,Map<String,List<Chunk>> rankedReferences,int limit) {
        return buildWindow(topic,rankedTender,rankedReferences,limit,null);
    }

    /** Package-local bounded expansion. Initial payload is canonical and reserved in its existing order. */
    Selection expandPreservingInitial(String topic,List<Chunk> rankedTender,Map<String,List<Chunk>> rankedReferences,int limit,Selection initial) {
        if(initial==null) throw new IllegalArgumentException("Initial context is required for preserved expansion");
        return buildWindow(topic,rankedTender,rankedReferences,limit,initial);
    }

    private Selection buildWindow(String topic,List<Chunk> rankedTender,Map<String,List<Chunk>> rankedReferences,int limit,Selection initial) {
        Selection result=new Selection(); List<Group> groups=new ArrayList<>(),expansions=new ArrayList<>(); Map<String,Integer> ranks=new HashMap<>();
        Map<String,Chunk> uniqueSeeds=new LinkedHashMap<>();
        rankedTender.stream().map(this::canonicalInput).filter(c -> "tender".equals(c.getRole())&&usable(c)).forEach(c -> uniqueSeeds.putIfAbsent(c.getId(),c));
        for(List<Chunk> refs:rankedReferences.values()) for(Chunk c:refs) canonicalInput(c);
        List<Chunk> seeds=new ArrayList<>(uniqueSeeds.values());
        for(int i=0;i<seeds.size();i++) ranks.put(seeds.get(i).getId(),i);
        Set<String> pairKeys=new HashSet<>(), pairedSeedIds=new HashSet<>();
        for(int rank=0;rank<seeds.size();rank++) {
            Chunk seed=seeds.get(rank); LinkedHashMap<Chunk,String> peers=new LinkedHashMap<>(),extraPeers=new LinkedHashMap<>();
            List<String> sourceEntities=new ArrayList<>(chunkEntities.getOrDefault(seed.getId(),Collections.emptySet()));
            sourceEntities.sort(Comparator.comparingInt(entity -> entityScopeCounts.getOrDefault(entity,Integer.MAX_VALUE)));
            for(String entity:sourceEntities) {
                List<Chunk> candidates=entities.getOrDefault(entity,Collections.emptyList()).stream()
                        .filter(c -> "tender".equals(c.getRole())&&compatibleRevision(seed,c)&&differentScope(seed,c)&&OPERATIVE.matcher(nvl(c.getContent())).find())
                        .sorted(Comparator.<Chunk>comparingInt(c -> ranks.containsKey(c.getId())?0:1)
                                .thenComparingInt(c -> localOverlap(seed,c,entity)>0?0:1)
                                .thenComparingInt(c -> ranks.getOrDefault(c.getId(),Integer.MAX_VALUE))
                                .thenComparing(Comparator.comparingInt((Chunk c) -> localOverlap(seed,c,entity)).reversed())
                                .thenComparing(Comparator.comparingInt((Chunk c) -> obligationSignals(c)).reversed())
                                .thenComparingInt(c -> order.get(c.getId()))).collect(Collectors.toList());
                if(!candidates.isEmpty()) {
                    Chunk peer=candidates.get(0);String strength=localOverlap(seed,peer,entity)>0?"local_terms_shared":"weak_relation";
                    if(ranks.containsKey(peer.getId())) peers.putIfAbsent(peer,strength);
                    else if(localTopicOverlap(seed,peer,entity,topic)>0) extraPeers.putIfAbsent(peer,strength);
                }
                if(peers.size()+extraPeers.size()>=2) break;
            }
            // Ranked comparison peers cannot consume the entire opportunity to observe
            // a different source scope's qualifier. These remain bounded weak hints.
            if(!peers.isEmpty()) for(String entity:sourceEntities) {
                if(extraPeers.size()>=2) break;
                List<Chunk> tails=entities.getOrDefault(entity,Collections.emptyList()).stream()
                        .filter(c -> "tender".equals(c.getRole())&&!ranks.containsKey(c.getId())
                                &&compatibleRevision(seed,c)&&differentScope(seed,c)
                                &&!nvl(c.getDocumentId()).isEmpty()&&!nvl(c.getSourceHash()).isEmpty()
                                &&QUALIFIER.matcher(nvl(c.getContent())).find()
                                &&OPERATIVE.matcher(nvl(c.getContent())).find()
                                &&localTopicOverlap(seed,c,entity,topic)>0)
                        .sorted(Comparator.<Chunk>comparingInt(c -> localOverlap(seed,c,entity)).reversed()
                                .thenComparing(Comparator.comparingInt((Chunk c) -> obligationSignals(c)).reversed())
                                .thenComparingInt(c -> order.get(c.getId()))).collect(Collectors.toList());
                for(Chunk tail:tails) {
                    if(extraPeers.containsKey(tail)||extraPeers.keySet().stream().anyMatch(c -> !differentScope(c,tail))) continue;
                    extraPeers.put(tail,"observed_entity_qualifier_terms");break;
                }
            }
            for(Map.Entry<Chunk,String> peer:peers.entrySet()) addPair(groups,pairKeys,pairedSeedIds,rank,"shared_entity",peer.getValue(),seed,peer.getKey());
            for(String ref:references(seed)) {
                List<Chunk> target=target(ref,seed);
                List<Chunk> rankedTargets=target.stream().filter(c -> ranks.containsKey(c.getId())&&!c.getId().equals(seed.getId())).collect(Collectors.toList());
                if(!rankedTargets.isEmpty()) addPair(groups,pairKeys,pairedSeedIds,rank,"explicit_reference","source_reference",seed,bestTarget(rankedTargets,seed));
            }
            for(Map.Entry<Chunk,String> peer:extraPeers.entrySet()) addPair(expansions,pairKeys,new HashSet<>(),rank,"shared_entity_extension",peer.getValue(),seed,peer.getKey());
        }
        for(int rank=0;rank<seeds.size();rank++) if(!pairedSeedIds.contains(seeds.get(rank).getId())) {
            Group singleton=new Group(rank,"single_seed","rank_unknown");singleton.core.add(seeds.get(rank).getId());groups.add(singleton);
        }
        for(Group group:groups) completeWindow(group,false);
        for(Group group:expansions) {group.priority="explicit_reference_extension".equals(group.relation)?1:2;completeWindow(group,true);}
        groups.sort(Comparator.comparingInt(g -> g.rank));
        expansions.sort(Comparator.<Group>comparingInt(g -> g.priority).thenComparingInt(g -> g.rank));
        LinkedHashMap<String,Chunk> selected=new LinkedHashMap<>();
        LinkedHashMap<String,Chunk> referenceOrigins=new LinkedHashMap<>();
        List<Chunk> reservedOrigins=new ArrayList<>();
        if(initial!=null) {
            for(Chunk chunk:initial.getChunks()) {
                Chunk canonical=canonicalInput(chunk);
                if(selected.putIfAbsent(canonical.getId(),canonical)!=null) throw new IllegalArgumentException("Initial context requires unique canonical chunk IDs");
            }
            validateDocumentRevisions(selected.values());
            long initialChars=selected.values().stream().mapToLong(c->nvl(c.getContent()).length()).sum();
            if(initialChars>Math.max(0,limit)||initialChars!=initial.getContentChars())
                throw new IllegalArgumentException("Initial context exceeds or misstates the expansion budget");
            result.setContentChars((int)initialChars);
            Set<String> eligible=new LinkedHashSet<>();
            for(GroupTrace trace:initial.getGroups()) if(Arrays.asList("single_seed","shared_entity","explicit_reference").contains(trace.getRelation())
                    &&Arrays.asList("selected","selected_partial_context").contains(trace.getStatus()))
                for(String id:trace.getCoreIds()) if(selected.containsKey(id))eligible.add(id);
            for(ReferenceTrace trace:initial.getReferences())if(trace.isInitiallySubmitted()&&trace.isOriginSubmitted())eligible.add(trace.getOriginId());
            for(VettingBlockContinuation.Trace trace:initial.getBlockContinuations())if(trace.isInitiallySubmitted()&&trace.isOriginSubmitted())eligible.add(trace.getOriginId());
            for(Chunk seed:seeds) if(eligible.contains(seed.getId())) {reservedOrigins.add(seed);referenceOrigins.put(seed.getId(),seed);}
            // Initial observed closure has priority over newly admitted ranked cores.
            closeRankedContinuations(reservedOrigins,new LinkedHashSet<>(referenceOrigins.keySet()),selected,result,limit);
            closeRankedReferences(reservedOrigins,new LinkedHashSet<>(referenceOrigins.keySet()),selected,result,limit);
            closeIncomingLiterals(reservedOrigins,selected,result,limit);
        }
        // Existing non-core source context and all initial one-hop derived targets stay context, even if ranked.
        Set<String> sourceDerivedBeforeCoreIds=new LinkedHashSet<>(selected.keySet());
        sourceDerivedBeforeCoreIds.removeAll(referenceOrigins.keySet());
        Map<Group,GroupTrace> traces=new LinkedHashMap<>();
        Set<String> admittedCoreIds=new LinkedHashSet<>(referenceOrigins.keySet());
        for(Group group:groups) {
            GroupTrace trace=admit(group,group.core,selected,result,limit);traces.put(group,trace);
            if("selected".equals(trace.getStatus()))for(String id:group.core)if(!sourceDerivedBeforeCoreIds.contains(id))admittedCoreIds.add(id);
        }
        // Only selected ranked origins can request one-hop explicit source context.
        for(Chunk seed:seeds) if(admittedCoreIds.contains(seed.getId())) referenceOrigins.put(seed.getId(),seed);
        Set<String> reservedOriginIds=reservedOrigins.stream().map(Chunk::getId).collect(Collectors.toSet());
        List<Chunk> remainingOrigins=seeds.stream().filter(seed->!reservedOriginIds.contains(seed.getId())).collect(Collectors.toList());
        closeRankedContinuations(remainingOrigins,new LinkedHashSet<>(referenceOrigins.keySet()),selected,result,limit);
        closeRankedReferences(remainingOrigins,new LinkedHashSet<>(referenceOrigins.keySet()),selected,result,limit);
        List<Chunk> newlyAdmittedOrigins=remainingOrigins.stream().filter(seed->referenceOrigins.containsKey(seed.getId())).collect(Collectors.toList());
        closeIncomingLiterals(newlyAdmittedOrigins,selected,result,limit);
        // Located qualifier terms in an existing source-entity comparison precede weak
        // neighbours, after ranked cores and their one-hop source closure. The package
        // stays atomic and never adds an outgoing origin or claims legal completeness.
        for(Group group:expansions) if("observed_entity_qualifier_terms".equals(group.strength))
            admit(group,group.all(),selected,result,limit);
        // Original ranked cores and their explicit closure precede weak neighbouring context.
        List<Group> localWindows=new ArrayList<>(groups);localWindows.sort(Comparator.<Group>comparingInt(g -> g.context.stream()
                .anyMatch(id -> QUALIFIER.matcher(byId.get(id).getContent()).find())?0:1).thenComparingInt(g -> g.rank));
        for(Group group:localWindows) {
            GroupTrace trace=traces.get(group);if(!"selected".equals(trace.getStatus()))continue;
            int cost=cost(group.context,selected);
            if(result.getContentChars()+cost<=Math.max(0,limit)) {
                for(String id:group.context)selected.putIfAbsent(id,byId.get(id));result.setContentChars(result.getContentChars()+cost);
                trace.setIncrementalChars(trace.getIncrementalChars()+cost);
            } else {
                trace.setStatus("selected_partial_context");result.getDroppedIds().addAll(group.context);
                result.getUnresolvedComparisonIds().addAll(group.core);
            }
        }
        for(Group group:expansions) if(!"observed_entity_qualifier_terms".equals(group.strength))
            admit(group,group.all(),selected,result,limit);
        // References are supporting context, not a fixed reservation ahead of an adopted pair.
        for(List<Chunk> refs:rankedReferences.values()) for(Chunk c:refs) {
            if(!usable(c)||selected.containsKey(c.getId())||!relatedToSelected(c,referenceOrigins.values())) continue;
            int cost=nvl(c.getContent()).length();if(result.getContentChars()+cost>limit) continue;
            selected.put(c.getId(),c);result.setContentChars(result.getContentChars()+cost);
        }
        refreshReferences(result,selected);
        refreshContinuations(result,selected);
        for(VettingIncomingLiteralContext.Trace incoming:result.getIncomingReferences())VettingIncomingLiteralContext.refresh(incoming,selected.keySet());
        for(GroupTrace trace:result.getGroups()) {
            for(VettingIncomingLiteralContext.Trace incoming:result.getIncomingReferences())if(!Collections.disjoint(trace.getCoreIds(),incoming.getInitialOriginIds())) {
                for(String id:incoming.getRequiredChunkIds())if(!trace.getCoreIds().contains(id)&&!trace.getContextIds().contains(id))trace.getContextIds().add(id);
                if(!incoming.isObservedRangesTransported()||"parent_fallback".equals(incoming.getResolutionMode()))result.getUnresolvedComparisonIds().addAll(incoming.getInitialOriginIds());
            }

            for(VettingBlockContinuation.Trace continuation:result.getBlockContinuations()) if(trace.getCoreIds().contains(continuation.getOriginId())) {
                for(String id:continuation.getRequiredChunkIds()) if(!trace.getCoreIds().contains(id)&&!trace.getContextIds().contains(id)) trace.getContextIds().add(id);
                if(continuation.isOriginSubmitted()&&(!continuation.isObservedRangesTransported()||continuation.getRequiredChunkIds().isEmpty()))
                    result.getUnresolvedComparisonIds().add(continuation.getOriginId());
            }
            for(ReferenceTrace ref:result.getReferences()) if(trace.getCoreIds().contains(ref.getOriginId())) {
                for(String id:ref.getRequiredTargetIds()) if(!trace.getCoreIds().contains(id)&&!trace.getContextIds().contains(id)) trace.getContextIds().add(id);
                if(ref.isOriginSubmitted()&&(!ref.getMissingTargetIds().isEmpty()||"unknown".equals(ref.getResolutionMode())||"parent_fallback".equals(ref.getResolutionMode()))) {
                    String unresolved=ref.getReference();
                    if("parent_fallback".equals(ref.getResolutionMode())&&ref.isFullParentContextSelected()) unresolved+=" (parent context submitted; exact subclause metadata unknown)";
                    if(!trace.getUnresolvedReferences().contains(unresolved)) trace.getUnresolvedReferences().add(unresolved);
                    result.getUnresolvedComparisonIds().add(ref.getOriginId());
                }
            }
            trace.setSubmittedCoreCount((int)trace.getCoreIds().stream().filter(selected::containsKey).count());
            trace.setFullySubmitted(trace.getSubmittedCoreCount()==trace.getCoreIds().size()&&trace.getContextIds().stream().allMatch(selected::containsKey));
        }
        result.setChunks(new ArrayList<>(selected.values()));
        if(initial!=null)validateDocumentRevisions(result.getChunks());
        result.getDroppedIds().removeAll(selected.keySet());return result;
    }

    private static void validateDocumentRevisions(Collection<Chunk> chunks) {
        Map<String,String> identities=new LinkedHashMap<>();
        for(Chunk chunk:chunks) {
            String doc=nvl(chunk.getDocumentId());
            if(doc.isEmpty())throw new IllegalArgumentException("Preserved context requires source document identity");
            String revision=frame(chunk.getSourceHash())+frame(chunk.getRole())+frame(chunk.getMetadataVersion())+frame(chunk.getSegmentationVersion())
                    +frame(chunk.getNativeTableMetadataVersion())+frame(chunk.getSourceQualityMetadataVersion())+frame(chunk.getSourceQualityHash());
            String previous=identities.putIfAbsent(doc,revision);
            if(previous!=null&&!previous.equals(revision))throw new IllegalArgumentException("Preserved context cannot mix source document revisions or roles");
        }
    }

    private Chunk canonicalInput(Chunk c) {
        if(c==null||!byId.containsKey(c.getId())||!byId.get(c.getId()).equals(c))
            throw new IllegalArgumentException("Ranked context chunk does not match the current source corpus");
        return byId.get(c.getId());
    }
    private void closeRankedContinuations(List<Chunk> seeds,Set<String> eligible,Map<String,Chunk> selected,Selection result,int limit) {
        for(Chunk seed:seeds) for(VettingBlockContinuation.Trace trace:continuations.resolve(seed)) {
            trace.setInitiallySubmitted(eligible.contains(seed.getId()));trace.setOriginSubmitted(selected.containsKey(seed.getId()));
            trace.setBudgetRemainingAtAdmission(Math.max(0,limit-result.getContentChars()));
            trace.setRequestedIncrementalChars(cost(trace.getRequiredChunkIds(),selected));result.getBlockContinuations().add(trace);
            if(!trace.isInitiallySubmitted()) {trace.setStatus("origin_not_eligible_at_closure_start");continue;}
            if(trace.getRequiredChunkIds().isEmpty())continue;
            if(trace.getRequestedIncrementalChars()<=trace.getBudgetRemainingAtAdmission()) {
                for(String id:trace.getRequiredChunkIds())selected.putIfAbsent(id,byId.get(id));
                result.setContentChars(result.getContentChars()+trace.getRequestedIncrementalChars());trace.setAdmissionStatus("admitted_observed_ranges");
            } else {trace.setAdmissionStatus("blocked_budget");result.getDroppedIds().addAll(trace.getRequiredChunkIds());}
        }
    }
    private void refreshContinuations(Selection result,Map<String,Chunk> selected) {
        for(VettingBlockContinuation.Trace trace:result.getBlockContinuations()) {
            trace.setOriginSubmitted(selected.containsKey(trace.getOriginId()));
            trace.setSubmittedChunkIds(trace.getRequiredChunkIds().stream().filter(selected::containsKey).collect(Collectors.toList()));
            trace.setMissingChunkIds(trace.getRequiredChunkIds().stream().filter(id->!selected.containsKey(id)).collect(Collectors.toList()));
            if(!trace.isInitiallySubmitted()||trace.getRequiredChunkIds().isEmpty())continue;
            boolean covered=trace.getMissingChunkIds().isEmpty();trace.setObservedRangesTransported(covered);
            trace.setStatus(covered?"observed_ranges_submitted":"partial_observed_ranges_submitted");
        }
    }

    private int cost(Collection<String> ids,Map<String,Chunk> selected) {
        return ids.stream().filter(id -> !selected.containsKey(id)).mapToInt(id -> nvl(byId.get(id).getContent()).length()).sum();
    }
    private void closeIncomingLiterals(List<Chunk> initialOrigins,Map<String,Chunk> selected,Selection result,int limit) {
        List<Chunk> ownedOrigins=initialOrigins.stream().filter(VettingContextBuilder::ownedClause).collect(Collectors.toList());
        for(VettingIncomingLiteralContext.Trace trace:incomingLiterals.observe(ownedOrigins)) {
            trace.setBudgetRemainingAtAdmission(Math.max(0,limit-result.getContentChars()));
            trace.setRequestedIncrementalChars(cost(trace.getRequiredChunkIds(),selected));result.getIncomingReferences().add(trace);
            if(!trace.isInitiallyConnected()||trace.getRequiredChunkIds().isEmpty())continue;
            if(trace.getRequestedIncrementalChars()<=trace.getBudgetRemainingAtAdmission()) {
                for(String id:trace.getRequiredChunkIds())selected.putIfAbsent(id,byId.get(id));
                result.setContentChars(result.getContentChars()+trace.getRequestedIncrementalChars());trace.setAdmissionStatus("admitted_complete_observed_incoming_context");
            } else {trace.setAdmissionStatus("blocked_budget");result.getDroppedIds().addAll(trace.getRequiredChunkIds());}
        }
    }
    private GroupTrace admit(Group group,Set<String> requested,Map<String,Chunk> selected,Selection result,int limit) {
        GroupTrace trace=new GroupTrace();trace.setId(group.core.stream().sorted().collect(Collectors.joining("+")));
        trace.setRelation(group.relation);trace.setRelationStrength(group.strength);trace.setCoreIds(new ArrayList<>(group.core));trace.setContextIds(new ArrayList<>(group.context));
        trace.setUnresolvedReferences(new ArrayList<>(group.unresolved));int cost=cost(requested,selected);trace.setIncrementalChars(cost);
        if(result.getContentChars()+cost<=Math.max(0,limit)) {
            trace.setStatus("selected");for(String id:requested)selected.putIfAbsent(id,byId.get(id));result.setContentChars(result.getContentChars()+cost);
        } else {trace.setStatus("dropped_budget");result.getDroppedIds().addAll(group.all());}
        result.getGroups().add(trace);return trace;
    }

    private void addPair(List<Group> groups,Set<String> seen,Set<String> paired,int rank,String relation,String strength,Chunk seed,Chunk peer) {
        String key=Arrays.asList(seed.getId(),peer.getId()).stream().sorted().collect(Collectors.joining("|"));
        if(!seen.add(key)) return; Group group=new Group(rank,relation,strength);group.core.add(seed.getId());group.core.add(peer.getId());groups.add(group);
        paired.add(seed.getId());paired.add(peer.getId());
    }
    private void completeWindow(Group group,boolean expandTargets) {
        List<Chunk> neighbours=new ArrayList<>();
        for(String id:group.core) {
            Chunk core=byId.get(id);List<Chunk> same=scopes.getOrDefault(scope(core),Collections.emptyList());int position=same.indexOf(core);
            if(position>0) neighbours.add(same.get(position-1));if(position>=0&&position+1<same.size()) neighbours.add(same.get(position+1));
        }
        neighbours.stream().distinct().filter(c -> !group.core.contains(c.getId()))
                .sorted(Comparator.<Chunk>comparingInt(c -> QUALIFIER.matcher(c.getContent()).find()?0:1).thenComparingInt(c -> order.get(c.getId())))
                .limit(2).forEach(c -> group.context.add(c.getId()));
        // References are resolved separately from the fixed ranked origins, not neighbours or extensions.
    }
    private Set<String> references(Chunk seed) {
        LinkedHashSet<String> refs=new LinkedHashSet<>();if(reference==null) return refs;
        Matcher matcher=reference.matcher(nvl(seed.getContent()));
        while(matcher.find()) {
            String number=canonical(matcher.group(3));
            String ref=canonical(matcher.group(1)+matcher.group(3));
            if(matcher.group(2)==null&&!number.contains(".")&&!number.contains("(")
                    &&clauses.keySet().stream().noneMatch(clause -> matchesReference(ref,clause))) continue; // e.g. an unrecognized library edition code
            if(!ref.equals(canonical(seed.getClauseId()))) refs.add(ref);
        }
        return refs;
    }
    private static final class TargetResolution {
        final List<Chunk> chunks=new ArrayList<>();
        String mode="unknown", status="missing_target", clause, source, heading;
    }
    private TargetResolution resolve(String ref,Chunk origin) {
        TargetResolution out=new TargetResolution(); List<Chunk> found=new ArrayList<>();
        for(Map.Entry<String,List<Chunk>> entry:clauses.entrySet()) if(matchesReference(ref,entry.getKey())
                ||(ref.contains("(")&&ref.startsWith(entry.getKey().replaceFirst("\\.[A-Z]$","")+"(")))
            entry.getValue().stream().filter(VettingContextBuilder::ownedClause).forEach(found::add);
        if(found.isEmpty()) return out;
        Set<String> years=referenceEditions(ref,origin);
        if(years.size()>1) {out.status="ambiguous_source_or_edition";return out;}
        if(years.size()==1) {
            String year=years.iterator().next();Set<String> matching=found.stream().filter(c -> nvl(c.getFileName()).contains(year)||editions(c.getContent()).contains(year))
                    .map(VettingContextBuilder::sourceIdentity).collect(Collectors.toSet());
            found.removeIf(c -> !matching.contains(sourceIdentity(c)));
            if(found.isEmpty()) {out.status="edition_target_missing";return out;}
        } else if(found.stream().anyMatch(c -> "tender".equals(c.getRole()))) found.removeIf(c -> !"tender".equals(c.getRole()));
        if(found.stream().anyMatch(c -> nvl(c.getDocumentId()).isEmpty()||nvl(c.getSourceHash()).isEmpty())) {out.status="unknown_source_identity";return out;}
        Set<String> sources=found.stream().map(VettingContextBuilder::sourceIdentity).collect(Collectors.toCollection(LinkedHashSet::new));
        if(sources.size()!=1) {out.status="ambiguous_source_or_edition";return out;}
        boolean exact=found.stream().anyMatch(c -> canonical(c.getClauseId()).equals(ref));
        if(exact) found.removeIf(c -> !canonical(c.getClauseId()).equals(ref));
        if(found.stream().anyMatch(c -> nvl(c.getClauseHeadingLocation()).isEmpty())) {out.status="unknown_target_heading";return out;}
        Set<String> headings=found.stream().map(Chunk::getClauseHeadingLocation).collect(Collectors.toSet());
        if(headings.size()!=1) {out.status="ambiguous_target_scope";return out;}
        Set<String> families=found.stream().map(c -> canonical(c.getClauseId()).replaceFirst("\\.[A-Z]$","")).collect(Collectors.toSet());
        if(families.size()!=1) {out.status="ambiguous_parent_scope";return out;}
        String source=sources.iterator().next();out.heading=headings.iterator().next();
        out.source=source+"|"+out.heading;out.clause=families.iterator().next();
        out.mode=ref.contains("(")&&!exact?"parent_fallback":ref.contains("(")?"exact_subclause":"exact_clause";
        // The denominator is the entire source-bound clause family, not only usable/ranked hits.
        String family=out.clause;
        found=byId.values().stream().filter(c -> sourceIdentity(c).equals(source)&&ownedClause(c))
                .filter(c -> {String clause=canonical(c.getClauseId()).replaceFirst("\\.[A-Z]$","");
                    return clause.equals(family)||clause.startsWith(family+"(");}).collect(Collectors.toList());
        // Descendant clause IDs may inherit a unique parent family. A repeated ID with
        // different headings or a missing heading has no unambiguous ancestry metadata.
        Map<String,Set<String>> clauseHeadings=new LinkedHashMap<>();
        for(Chunk chunk:found) {
            if(nvl(chunk.getClauseHeadingLocation()).isEmpty()) {out.mode="unknown";out.status="unknown_target_heading";return out;}
            String clause=canonical(chunk.getClauseId()).replaceFirst("\\.[A-Z]$","");
            clauseHeadings.computeIfAbsent(clause,k -> new LinkedHashSet<>()).add(chunk.getClauseHeadingLocation());
        }
        if(clauseHeadings.values().stream().anyMatch(h -> h.size()!=1)
                ||!clauseHeadings.getOrDefault(family,Collections.emptySet()).contains(out.heading)) {
            out.mode="unknown";out.status="ambiguous_target_scope";return out;
        }
        out.status="target_known";found.sort(Comparator.comparingInt(c -> order.get(c.getId())));out.chunks.addAll(found);return out;
    }
    private List<Chunk> target(String ref,Chunk seed) {return resolve(ref,seed).chunks;}
    private void closeRankedReferences(List<Chunk> seeds,Set<String> eligibleOrigins,Map<String,Chunk> selected,Selection result,int limit) {
        for(Chunk seed:seeds) for(String ref:references(seed)) {
            TargetResolution target=resolve(ref,seed);ReferenceTrace trace=new ReferenceTrace();
            trace.setOriginId(seed.getId());trace.setOriginAnchor(seed.getAnchor());trace.setReference(ref);
            trace.setInitiallySubmitted(eligibleOrigins.contains(seed.getId()));trace.setOriginSubmitted(selected.containsKey(seed.getId()));trace.setResolutionMode(target.mode);
            trace.setTargetClauseId(target.clause);trace.setTargetSourceIdentity(target.source);trace.setStatus(target.status);
            trace.setTargetHeadingLocation(target.heading);
            trace.setRequiredTargetIds(target.chunks.stream().map(Chunk::getId).collect(Collectors.toList()));
            trace.setBudgetRemainingAtAdmission(Math.max(0,limit-result.getContentChars()));
            trace.setRequestedIncrementalChars(cost(trace.getRequiredTargetIds(),selected));result.getReferences().add(trace);
            if(!trace.isInitiallySubmitted()) {trace.setStatus("origin_not_eligible_at_closure_start");continue;}
            if(target.chunks.isEmpty()) continue;
            if(trace.getRequestedIncrementalChars()<=trace.getBudgetRemainingAtAdmission()) {
                for(Chunk chunk:target.chunks) selected.putIfAbsent(chunk.getId(),chunk);
                result.setContentChars(result.getContentChars()+trace.getRequestedIncrementalChars());trace.setAdmissionStatus("admitted_complete_target");
            } else {trace.setAdmissionStatus("blocked_budget");result.getDroppedIds().addAll(trace.getRequiredTargetIds());}
        }
    }
    private void refreshReferences(Selection result,Map<String,Chunk> selected) {
        for(ReferenceTrace trace:result.getReferences()) {
            trace.setOriginSubmitted(selected.containsKey(trace.getOriginId()));
            trace.setSubmittedTargetIds(trace.getRequiredTargetIds().stream().filter(selected::containsKey).collect(Collectors.toList()));
            trace.setMissingTargetIds(trace.getRequiredTargetIds().stream().filter(id -> !selected.containsKey(id)).collect(Collectors.toList()));
            if(!trace.isInitiallySubmitted()||"unknown".equals(trace.getResolutionMode())) continue;
            boolean full=!trace.getRequiredTargetIds().isEmpty()&&trace.getMissingTargetIds().isEmpty();
            trace.setFullParentContextSelected(full&&"parent_fallback".equals(trace.getResolutionMode()));
            trace.setExactSubclauseVerified(full&&"exact_subclause".equals(trace.getResolutionMode()));
            trace.setStatus(full?("parent_fallback".equals(trace.getResolutionMode())?"full_parent_context_selected":trace.isExactSubclauseVerified()?"exact_subclause_verified":"exact_clause_context_selected"):
                    trace.getSubmittedTargetIds().isEmpty()?"blocked_budget":"partial_target_context_selected");
        }
    }
    private static String sourceIdentity(Chunk c) {return nvl(c.getDocumentId())+"|"+nvl(c.getSourceHash())+"|"+nvl(c.getRole());}
    private static boolean ownedClause(Chunk c) {
        String owner=canonical(c.getFileKey());return !owner.isEmpty()&&!"OTHER".equals(owner)
                &&canonical(c.getClauseId()).matches(Pattern.quote(owner)+"(?:\\.[A-Z]+)?\\.?\\d.*");
    }
    private static Set<String> editions(String text) {
        Set<String> years=new LinkedHashSet<>();Matcher m=EDITION.matcher(nvl(text));while(m.find()) years.add(m.group(1)!=null?m.group(1):m.group(2));return years;
    }
    private Set<String> referenceEditions(String ref,Chunk origin) {
        Set<String> years=new LinkedHashSet<>();if(reference==null)return years;
        for(String sentence:nvl(origin.getContent()).split("\\r?\\n|(?<=[.!?;])\\s+(?=[A-Z])")) {
            Matcher m=reference.matcher(sentence);while(m.find()) if(ref.equals(canonical(m.group(1)+m.group(3))))years.addAll(editions(sentence));
        }return years;
    }
    private Chunk bestTarget(List<Chunk> candidates,Chunk seed) {
        Set<String> terms=words(seed.getContent()).stream().filter(t -> t.length()>3).collect(Collectors.toSet());
        return candidates.stream().max(Comparator.<Chunk>comparingInt(c -> {
            int score=0;for(String entity:chunkEntities.getOrDefault(seed.getId(),Collections.emptySet())) if(chunkEntities.getOrDefault(c.getId(),Collections.emptySet()).contains(entity)) score+=20;
            for(String term:words(c.getContent())) if(terms.contains(term)) score++;return score;
        }).thenComparingInt(c -> -order.get(c.getId()))).get();
    }
    private boolean relatedToSelected(Chunk reference,Collection<Chunk> selected) {
        for(Chunk c:selected) {
            if(!"tender".equals(c.getRole())) continue;
            if(Arrays.asList("project_fact","package_manifest").contains(reference.getRole())
                    &&references(reference).stream().anyMatch(ref -> matchesReference(ref,canonical(c.getClauseId())))) return true;
            Set<String> refs=references(c);
            List<String> matchingRefs=refs.stream().filter(ref -> matchesReference(ref,canonical(reference.getClauseId()))).collect(Collectors.toList());
            if(!matchingRefs.isEmpty()) {
                if(matchingRefs.stream().anyMatch(ref -> target(ref,c).stream().anyMatch(t -> t.getId().equals(reference.getId())))) return true;
                continue; // An ambiguous edition must not return through the entity filler.
            }
            if(!Collections.disjoint(chunkEntities.getOrDefault(reference.getId(),Collections.emptySet()),chunkEntities.getOrDefault(c.getId(),Collections.emptySet()))) return true;
        }
        return false;
    }
    private void indexEntities() {
        Map<String,Set<String>> meanings=new HashMap<>();Trie phrases=new Trie();
        for(Chunk c:byId.values()) if(usable(c)&&Arrays.asList("tender","standard").contains(c.getRole())) {
            Matcher matcher=EXPANSION.matcher(c.getContent());
            while(matcher.find()) {
                String acronym=matcher.group(2),name=expansion(matcher.group(1),acronym);if(name==null) continue;
                meanings.computeIfAbsent(acronym,k -> new LinkedHashSet<>()).add(name);phrases.add(words(name),name);
            }
        }
        for(Map.Entry<String,Set<String>> entry:meanings.entrySet()) if(entry.getValue().size()==1)
            entityAliases.computeIfAbsent(entry.getValue().iterator().next(),k -> new LinkedHashSet<>()).add(entry.getKey().toLowerCase(Locale.ROOT));
        for(Chunk c:byId.values()) if(usable(c)) {
            LinkedHashSet<String> found=new LinkedHashSet<>();List<String> tokens=words(c.getContent());
            for(int i=0;i<tokens.size();i++) {
                Trie node=phrases;for(int j=i;j<tokens.size()&&node!=null;j++) { node=node.children.get(tokens.get(j));if(node!=null) found.addAll(node.keys); }
            }
            Matcher upper=Pattern.compile("\\b[A-Z][A-Z0-9]{1,11}\\b").matcher(c.getContent());
            while(upper.find()) { Set<String> names=meanings.get(upper.group());if(names!=null&&names.size()==1) found.add(names.iterator().next()); }
            chunkEntities.put(c.getId(),found);for(String key:found) entities.computeIfAbsent(key,k -> new ArrayList<>()).add(c);
        }
        for(Map.Entry<String,List<Chunk>> entry:entities.entrySet()) entityScopeCounts.put(entry.getKey(),(int)entry.getValue().stream()
                .filter(c -> "tender".equals(c.getRole())).map(c -> scope(c)==null?c.getDocumentId():scope(c)).distinct().count());
    }
    private int localOverlap(Chunk left,Chunk right,String entity) {
        Set<String> terms=localTerms(left,entity);terms.retainAll(localTerms(right,entity));return terms.size();
    }
    private int localTopicOverlap(Chunk left,Chunk right,String entity,String topic) {
        Set<String> terms=localTerms(left,entity);terms.retainAll(localTerms(right,entity));terms.retainAll(new HashSet<>(words(topic)));return terms.size();
    }
    private Set<String> localTerms(Chunk chunk,String entity) {
        List<String> tokens=words(chunk.getContent()),phrase=words(entity);Set<String> terms=new HashSet<>();
        Set<String> aliases=entityAliases.getOrDefault(entity,Collections.emptySet());
        for(int i=0;i<tokens.size();i++) {
            int length=aliases.contains(tokens.get(i))?1:0;
            if(i+phrase.size()<=tokens.size()&&tokens.subList(i,i+phrase.size()).equals(phrase)) length=phrase.size();
            if(length==0) continue;
            for(int j=Math.max(0,i-8);j<Math.min(tokens.size(),i+length+8);j++) terms.add(tokens.get(j));
        }
        terms.removeAll(phrase);terms.removeAll(aliases);
        terms.removeAll(Arrays.asList("a","an","the","of","and","for","to","in","on","at","is","be","with","by","shall","must","will","as","or","not"));
        terms.removeIf(t -> t.length()<3||t.matches("\\d+"));return terms;
    }
    private static String expansion(String text,String acronym) {
        List<String> terms=words(text);
        for(int start=Math.max(0,terms.size()-12);start<terms.size()-1;start++) {
            StringBuilder initials=new StringBuilder();
            for(int i=start;i<terms.size();i++) if(!Arrays.asList("of","the","and","for","a","an").contains(terms.get(i))) initials.append(terms.get(i).charAt(0));
            if(initials.toString().equalsIgnoreCase(acronym)) return String.join(" ",terms.subList(start,terms.size()));
        }
        return null;
    }
    private static List<String> words(String text) { List<String> out=new ArrayList<>();Matcher matcher=WORD.matcher(nvl(text));while(matcher.find()) out.add(matcher.group().toLowerCase(Locale.ROOT));return out; }
    private static int obligationSignals(Chunk c) { Matcher matcher=OPERATIVE.matcher(nvl(c.getContent()));int count=0;while(matcher.find()) count++;return count; }
    private static boolean differentScope(Chunk a,Chunk b) {
        if(a.getId().equals(b.getId())) return false;String left=scope(a),right=scope(b);
        return left!=null&&right!=null?!left.equals(right):!Objects.equals(a.getDocumentId(),b.getDocumentId());
    }
    private static boolean compatibleRevision(Chunk a,Chunk b) {return !Objects.equals(a.getDocumentId(),b.getDocumentId())||Objects.equals(a.getSourceHash(),b.getSourceHash())
            &&Objects.equals(a.getSourceQualityMetadataVersion(),b.getSourceQualityMetadataVersion())&&Objects.equals(a.getSourceQualityHash(),b.getSourceQualityHash());}
    private static String scope(Chunk c) { return c.getClauseHeadingLocation()==null?null:frame(c.getDocumentId())+frame(c.getSourceHash())+frame(c.getRole())
            +frame(c.getMetadataVersion())+frame(c.getSegmentationVersion())+frame(c.getNativeTableMetadataVersion())
            +frame(c.getSourceQualityMetadataVersion())+frame(c.getSourceQualityHash())+frame(c.getClauseHeadingLocation()); }
    private static String frame(String s) {String value=nvl(s);return value.length()+":"+value;}
    private boolean usable(Chunk c) {
        String text=nvl(c.getContent());if(text.trim().isEmpty()) return false;
        if(c.getParts()!=null&&!c.getParts().isEmpty()&&c.getParts().stream().allMatch(p -> nvl(p.getAnchor()).matches("(?:header|footer)/.*"))) return false;
        long lines=text.split("\\r?\\n").length;
        long leaders=Arrays.stream(text.split("\\r?\\n")).filter(l -> l.matches(".*\\.{3,}\\s*\\d*\\s*$")).count();
        if(leaders>0&&leaders*2>=lines)return false;
        if(OBLIGATION_SENTENCE.matcher(text).find())return true;
        List<String> rows=Arrays.stream(text.split("\\r?\\n")).map(String::trim).filter(l -> !l.isEmpty()).collect(Collectors.toList());
        Set<String> distinctClauses=new HashSet<>();int clausePageRows=0,numericRows=0;boolean contents=false;
        for(String row:rows) {
            contents|=CONTENTS_MARKER.matcher(row).matches();if(row.matches("\\.?\\d+(?:\\.\\d+)*"))numericRows++;
            if(reference==null)continue;Matcher label=reference.matcher(row);
            if(label.lookingAt()&&row.substring(label.end()).trim().matches("[A-Za-z].*\\s+\\d+")) {
                clausePageRows++;distinctClauses.add(canonical(label.group(1)+label.group(3)));
            }
        }
        boolean numberedContents=distinctClauses.size()>=3&&clausePageRows*2>=rows.size();
        boolean splitContents=contents&&rows.size()>=6&&numericRows>=3&&numericRows*4>=rows.size();
        return !numberedContents&&!splitContents;
    }
    private static String canonical(String value) { return nvl(value).replaceAll("\\s+","").toUpperCase(Locale.ROOT); }
    private static boolean matchesReference(String ref,String clause) {
        return ref.equals(clause)||clause.matches(Pattern.quote(ref)+"\\.[A-Z]")||ref.startsWith(clause+"(");
    }
    private static String nvl(String value) { return value==null?"":value; }
}
