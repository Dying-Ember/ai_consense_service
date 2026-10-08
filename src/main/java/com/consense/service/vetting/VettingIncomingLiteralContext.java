package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import lombok.Data;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Source-only incoming literal observations. A transported edge never proves applicability. */
public final class VettingIncomingLiteralContext {
    public static final String POLICY = "initial_ranked_source_incoming_literal_observed_blocks_v2";
    @Data public static class Trace {
        private String incomingSourceId, incomingSourceAnchor, incomingSourceIdentity, reference;
        private String targetClauseId, targetSourceIdentity, targetHeadingLocation;
        private String direction = "incoming_literal_to_initial_ranked_clause";
        private String resolutionMode = "unknown", status = "unknown", admissionStatus = "not_admitted";
        private String observationScope = "source_literal_and_observed_block_ranges_only", applicability = "unknown", qualifiersComplete = "unknown";
        private List<String> initialOriginIds = new ArrayList<>(), resolvedTargetFamilyIds = new ArrayList<>();
        private List<String> requiredChunkIds = new ArrayList<>(), submittedChunkIds = new ArrayList<>(), missingChunkIds = new ArrayList<>();
        private List<VettingBlockContinuation.Trace> blockObservations = new ArrayList<>();
        private int requestedIncrementalChars, budgetRemainingAtAdmission;
        private boolean initiallyConnected, incomingSourceSubmitted, observedRangesTransported;
        private boolean sourceBlockCompleteKnown = false, legalApplicabilityVerified = false;
    }
    static final class Resolved {
        final String mode, status, clause, source, heading;
        final List<Chunk> chunks;
        Resolved(String mode,String status,String clause,String source,String heading,List<Chunk> chunks) {
            this.mode=mode;this.status=status;this.clause=clause;this.source=source;this.heading=heading;
            this.chunks=Collections.unmodifiableList(new ArrayList<>(chunks));
        }
    }
    private static final class Edge {
        final Chunk source; final String literal; final int ordinal;
        Edge(Chunk source,String literal,int ordinal) {this.source=source;this.literal=literal;this.ordinal=ordinal;}
    }
    private final Map<String,List<Edge>> byLiteral = new LinkedHashMap<>();
    private final Map<Edge,Resolved> resolutions = new HashMap<>();
    private final Map<String,String> sourceStatus = new LinkedHashMap<>();
    private final Map<String,String> sourcePayloadIdentities = new LinkedHashMap<>();
    private final Map<String,Chunk> byId = new LinkedHashMap<>();
    private final VettingBlockContinuation continuations;
    private final Function<Edge,Resolved> resolver;

    /** Index source literals once. Resolution is memoized by immutable source edge across queries. */
    VettingIncomingLiteralContext(List<Chunk> corpus,Function<Chunk,Set<String>> literalReferences,
                                 Function<Chunk,Boolean> usable,java.util.function.BiFunction<String,Chunk,Resolved> resolve) {
        Map<String,Set<String>> documentIdentities=new LinkedHashMap<>();
        for(Chunk c:corpus) {
            byId.put(c.getId(),c);
            sourcePayloadIdentities.put(c.getId(),payloadIdentity(c));
            documentIdentities.computeIfAbsent(nvl(c.getDocumentId()),k->new LinkedHashSet<>()).add(source(c));
        }
        continuations=new VettingBlockContinuation(corpus);
        resolver=edge->resolve.apply(edge.literal,edge.source);
        int ordinal=0;
        for(Chunk c:corpus) {
            String status=canonicalSourceStatus(c);
            if(documentIdentities.getOrDefault(nvl(c.getDocumentId()),Collections.emptySet()).size()!=1)status="ambiguous_incoming_document_identity";
            sourceStatus.put(c.getId(),status);
            if(!Boolean.TRUE.equals(usable.apply(c))||VettingSubstantiveEvidence.standaloneLabel(c.getContent()))continue;
            for(String ref:literalReferences.apply(c)) {
                Edge edge=new Edge(c,ref,ordinal++);
                byLiteral.computeIfAbsent(ref,k->new ArrayList<>()).add(edge);
                int sub=ref.indexOf('(');
                if(sub>0)byLiteral.computeIfAbsent(ref.substring(0,sub),k->new ArrayList<>()).add(edge);
            }
        }
    }
    /** Only the fixed initial origins can connect an incoming edge; returned notes never become origins. */
    List<Trace> observe(List<Chunk> initialOrigins) {
        LinkedHashMap<String,Chunk> eligible=new LinkedHashMap<>();
        for(Chunk origin:initialOrigins)if("source_bound".equals(currentSourceStatus(origin))
                &&origin.getClauseId()!=null&&!nvl(origin.getClauseHeadingLocation()).isEmpty())eligible.put(origin.getId(),origin);
        LinkedHashSet<Edge> candidates=new LinkedHashSet<>();
        for(Chunk origin:eligible.values()) {
            String clause=canonical(origin.getClauseId());
            Set<String> keys=new LinkedHashSet<>();keys.add(clause);keys.add(clause.replaceFirst("\\.[A-Z]$",""));
            int sub=clause.indexOf('(');if(sub>0)keys.add(clause.substring(0,sub));
            for(String key:keys)candidates.addAll(byLiteral.getOrDefault(key,Collections.emptyList()));
        }
        List<Edge> ordered=new ArrayList<>(candidates);ordered.sort(Comparator.comparingInt(e->e.ordinal));
        List<Trace> out=new ArrayList<>();
        for(Edge edge:ordered) {
            Trace trace=new Trace();trace.setIncomingSourceId(edge.source.getId());trace.setIncomingSourceAnchor(edge.source.getAnchor());
            trace.setIncomingSourceIdentity(source(edge.source));trace.setReference(edge.literal);out.add(trace);
            String sourceState=currentSourceStatus(edge.source);
            if(!"source_bound".equals(sourceState)) {trace.setStatus(sourceState);continue;}
            Resolved resolved=resolutions.computeIfAbsent(edge,resolver);
            trace.setResolutionMode(resolved.mode);trace.setStatus(resolved.status);trace.setTargetClauseId(resolved.clause);
            trace.setTargetSourceIdentity(resolved.source);trace.setTargetHeadingLocation(resolved.heading);
            trace.setResolvedTargetFamilyIds(resolved.chunks.stream().map(Chunk::getId).collect(Collectors.toList()));
            if(resolved.chunks.stream().anyMatch(c->!"source_bound".equals(currentSourceStatus(c)))) {
                trace.setStatus("unknown_target_family_source_identity");continue;
            }
            List<String> connected=resolved.chunks.stream().filter(c->eligible.containsKey(c.getId())&&!c.getId().equals(edge.source.getId()))
                    .map(Chunk::getId).collect(Collectors.toList());trace.setInitialOriginIds(connected);
            if(connected.isEmpty()) {if(!resolved.chunks.isEmpty())trace.setStatus("resolved_family_has_no_initial_origin");continue;}
            trace.setInitiallyConnected(true);
            LinkedHashSet<String> required=new LinkedHashSet<>();required.add(edge.source.getId());
            List<VettingBlockContinuation.Trace> blocks=continuations.resolve(edge.source);trace.setBlockObservations(blocks);
            boolean valid=true;
            for(VettingBlockContinuation.Trace block:blocks) {
                if(block.getRequiredChunkIds().isEmpty()) {valid=false;trace.setStatus("incoming_block_boundary_unknown");break;}
                for(String id:block.getRequiredChunkIds()) {
                    Chunk member=byId.get(id);
                    if(member==null||!"source_bound".equals(currentSourceStatus(member))||!source(member).equals(source(edge.source))) {valid=false;trace.setStatus("incoming_block_source_identity_unknown");break;}
                    required.add(id);
                }
                if(!valid)break;
            }
            if(valid) {trace.setRequiredChunkIds(new ArrayList<>(required));trace.setStatus("incoming_observed_context_located");}
        }
        return out;
    }
    static void refresh(Trace trace,Set<String> selectedIds) {
        trace.setIncomingSourceSubmitted(selectedIds.contains(trace.getIncomingSourceId()));
        trace.setSubmittedChunkIds(trace.getRequiredChunkIds().stream().filter(selectedIds::contains).collect(Collectors.toList()));
        trace.setMissingChunkIds(trace.getRequiredChunkIds().stream().filter(id->!selectedIds.contains(id)).collect(Collectors.toList()));
        if(trace.isInitiallyConnected()&&!trace.getRequiredChunkIds().isEmpty()) {
            boolean complete=trace.getMissingChunkIds().isEmpty();trace.setObservedRangesTransported(complete);
            trace.setStatus(complete?"incoming_observed_context_submitted":"incoming_observed_context_missing");
        }
        for(VettingBlockContinuation.Trace block:trace.getBlockObservations()) {
            block.setOriginSubmitted(selectedIds.contains(block.getOriginId()));
            block.setSubmittedChunkIds(block.getRequiredChunkIds().stream().filter(selectedIds::contains).collect(Collectors.toList()));
            block.setMissingChunkIds(block.getRequiredChunkIds().stream().filter(id->!selectedIds.contains(id)).collect(Collectors.toList()));
            block.setObservedRangesTransported(!block.getRequiredChunkIds().isEmpty()&&block.getMissingChunkIds().isEmpty());
        }
    }
    private String currentSourceStatus(Chunk c) {
        if(!Objects.equals(sourcePayloadIdentities.get(c.getId()),payloadIdentity(c)))return "incoming_source_changed_since_snapshot";
        return sourceStatus.get(c.getId());
    }
    private static String payloadIdentity(Chunk c) {return VettingCorpus.hash(com.consense.common.JsonUtils.write(c));}
    private static String canonicalSourceStatus(Chunk c) {
        if(nvl(c.getDocumentId()).isEmpty()||!nvl(c.getSourceHash()).matches("[a-f0-9]{64}")
                ||!Arrays.asList("tender","standard","project_fact","package_manifest").contains(c.getRole()))return "unknown_incoming_source_identity";
        if(!VettingCorpus.METADATA_VERSION.equals(c.getMetadataVersion())||!VettingCorpus.SEGMENTATION_VERSION.equals(c.getSegmentationVersion())
                ||!VettingCorpus.NATIVE_TABLE_METADATA_VERSION.equals(c.getNativeTableMetadataVersion())
                ||!VettingSourceQuality.VERSION.equals(c.getSourceQualityMetadataVersion())||c.getSourceQuality()==null
                ||!Objects.equals(c.getSourceQualityHash(),VettingSourceQuality.hash(c.getSourceQuality()))
                ||!VettingSourceQuality.project(c).containsKey("observationScope"))return "unknown_incoming_quality_or_version_identity";
        if(c.getParts()==null||c.getParts().isEmpty()||nvl(c.getAnchor()).isEmpty())return "unknown_incoming_part_mapping";
        StringBuilder joined=new StringBuilder();Set<String> slices=new HashSet<>();
        for(Part p:c.getParts()) {
            if(p==null||p.getText()==null||p.getText().isEmpty()||nvl(p.getBlockId()).isEmpty()||nvl(p.getAnchor()).isEmpty()
                    ||p.getStartOffset()<0||p.getEndOffset()-p.getStartOffset()!=p.getText().length()
                    ||Character.isLowSurrogate(p.getText().charAt(0))||Character.isHighSurrogate(p.getText().charAt(p.getText().length()-1))
                    ||!slices.add(p.getBlockId()+"|"+p.getStartOffset()+"|"+p.getEndOffset()))return "unknown_incoming_part_mapping";
            if(p.getStartOffset()>0&&!p.getAnchor().endsWith(" @"+p.getStartOffset()))return "unknown_incoming_part_mapping";
            if(joined.length()>0)joined.append('\n');joined.append(p.getText());
        }
        return Objects.equals(c.getContent(),joined.toString())?"source_bound":"unknown_incoming_part_mapping";
    }
    private static String source(Chunk c) {return nvl(c.getDocumentId())+"|"+nvl(c.getSourceHash())+"|"+nvl(c.getRole())+"|"
            +nvl(c.getMetadataVersion())+"|"+nvl(c.getSegmentationVersion())+"|"+nvl(c.getNativeTableMetadataVersion())+"|"
            +nvl(c.getSourceQualityMetadataVersion())+"|"+nvl(c.getSourceQualityHash());}
    private static String canonical(String s) {return nvl(s).replaceAll("\\s+","").toUpperCase(Locale.ROOT);}
    private static String nvl(String s) {return s==null?"":s;}
}
