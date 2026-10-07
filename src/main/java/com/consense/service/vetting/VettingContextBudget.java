package com.consense.service.vetting;

import com.consense.service.vetting.VettingContextBuilder.ReferenceTrace;
import com.consense.service.vetting.VettingContextBuilder.Selection;
import com.consense.service.vetting.VettingCorpus.Chunk;
import lombok.Data;
import java.util.*;
import java.util.stream.Collectors;

/** One bounded source-only expansion for located outgoing, incoming, or original-block ranges omitted by budget. */
public final class VettingContextBudget {
    private VettingContextBudget() { }

    @Data public static class Result {
        private Selection selection;
        private int initialBudgetChars, effectiveBudgetChars, expansionCeilingChars;
        private boolean expansionAttempted, expanded, initialChunksPreserved = true, initialTargetRequestsPreserved = true;
        private String decision = "initial_window";
        private Set<String> initialMissingTargetIds = new LinkedHashSet<>(), finalMissingTargetIds = new LinkedHashSet<>();
        private Set<String> expandedCandidateMissingTargetIds = new LinkedHashSet<>(),
                expandedCandidateInitialMissingTargetIds = new LinkedHashSet<>(), newlyDiscoveredMissingTargetIds = new LinkedHashSet<>();
    }

    public static Result build(VettingContextBuilder builder, String topic, List<Chunk> rankedTender,
                               Map<String,List<Chunk>> rankedReferences, int initialLimit, int expansionCeiling) {
        Result result = new Result();
        int initial = Math.max(0, initialLimit);
        result.setInitialBudgetChars(initial);
        result.setEffectiveBudgetChars(initial);
        result.setExpansionCeilingChars(Math.max(0, expansionCeiling));
        Selection first = builder.build(topic, rankedTender, rankedReferences, initial);
        result.setSelection(first);
        result.setInitialMissingTargetIds(missingLocatedTargets(first));
        result.setFinalMissingTargetIds(new LinkedHashSet<>(result.getInitialMissingTargetIds()));
        if (result.getInitialMissingTargetIds().isEmpty() || expansionCeiling <= initial) return result;
        result.setExpansionAttempted(true);
        Selection expanded = builder.expandPreservingInitial(topic, rankedTender, rankedReferences, expansionCeiling, first);
        return evaluateExpansion(result, first, expanded, expansionCeiling);
    }

    /** Evaluate the fixed initial observation set. Newly admitted origins retain separate unresolved traces. */
    static Result evaluateExpansion(Result result, Selection first, Selection expanded, int expansionCeiling) {
        Map<String,Chunk> candidate = new LinkedHashMap<>();
        boolean unique = true;
        for (Chunk chunk : expanded.getChunks()) {
            if (chunk == null || chunk.getId() == null || candidate.putIfAbsent(chunk.getId(),chunk) != null) unique = false;
        }
        boolean preserved = unique && first.getChunks().stream().allMatch(chunk -> chunk != null && chunk.equals(candidate.get(chunk.getId())));
        result.setInitialChunksPreserved(preserved);
        Set<String> remaining = missingLocatedTargets(expanded);
        result.setExpandedCandidateMissingTargetIds(remaining);
        Set<String> originalRemaining = new LinkedHashSet<>(result.getInitialMissingTargetIds());
        originalRemaining.removeAll(candidate.keySet());
        result.setExpandedCandidateInitialMissingTargetIds(originalRemaining);
        Set<String> newlyMissing = new LinkedHashSet<>(remaining);
        newlyMissing.removeAll(result.getInitialMissingTargetIds());
        result.setNewlyDiscoveredMissingTargetIds(newlyMissing);
        boolean requestsPreserved = initialRequestsPreserved(first, expanded);
        result.setInitialTargetRequestsPreserved(requestsPreserved);
        // New origins may expose additional unresolved targets. They do not erase improvement of initial targets.
        boolean improved = originalRemaining.size() < result.getInitialMissingTargetIds().size();
        long actualChars = expanded.getChunks().stream().filter(Objects::nonNull).mapToLong(c -> c.getContent()==null?0:c.getContent().length()).sum();
        boolean bounded = actualChars <= expansionCeiling && actualChars == expanded.getContentChars();
        if (!preserved || !requestsPreserved || !bounded || !improved) {
            result.setDecision(!preserved ? "expansion_would_remove_initial_chunks" : !requestsPreserved ? "expansion_would_change_initial_target_requests"
                    : !bounded ? "expansion_exceeds_or_misstates_budget" : "expansion_did_not_improve_reference_coverage");
            return result;
        }
        result.setSelection(expanded);
        result.setEffectiveBudgetChars(expansionCeiling);
        result.setExpanded(true);
        result.setFinalMissingTargetIds(remaining);
        Set<String> firstIds=first.getChunks().stream().map(Chunk::getId).collect(Collectors.toSet());
        result.setDecision(first.getBlockContinuations().stream().anyMatch(t->t.isInitiallySubmitted()&&t.isOriginSubmitted()
                    &&t.getRequiredChunkIds().stream().anyMatch(id->!firstIds.contains(id))) ? "located_source_context_added"
                : first.getIncomingReferences().stream().anyMatch(t->t.getRequiredChunkIds().stream().anyMatch(id->!firstIds.contains(id)))
                    ? "located_incoming_literal_context_added" : "located_reference_targets_added");
        return result;
    }

    private static boolean initialRequestsPreserved(Selection first, Selection expanded) {
        for (ReferenceTrace original : first.getReferences()) {
            if (!original.isInitiallySubmitted() || !original.isOriginSubmitted() || original.getRequiredTargetIds().isEmpty()) continue;
            boolean matched = expanded.getReferences().stream().anyMatch(candidate -> candidate.isInitiallySubmitted() && candidate.isOriginSubmitted()
                    && Objects.equals(original.getOriginId(),candidate.getOriginId()) && Objects.equals(original.getReference(),candidate.getReference())
                    && Objects.equals(original.getTargetClauseId(),candidate.getTargetClauseId()) && Objects.equals(original.getTargetSourceIdentity(),candidate.getTargetSourceIdentity())
                    && Objects.equals(original.getTargetHeadingLocation(),candidate.getTargetHeadingLocation()) && Objects.equals(original.getResolutionMode(),candidate.getResolutionMode())
                    && new LinkedHashSet<>(original.getRequiredTargetIds()).equals(new LinkedHashSet<>(candidate.getRequiredTargetIds())));
            if (!matched) return false;
        }
        for (VettingBlockContinuation.Trace original : first.getBlockContinuations()) {
            if (!original.isInitiallySubmitted() || !original.isOriginSubmitted() || original.getRequiredChunkIds().isEmpty()) continue;
            boolean matched = expanded.getBlockContinuations().stream().anyMatch(candidate -> candidate.isInitiallySubmitted() && candidate.isOriginSubmitted()
                    && Objects.equals(original.getOriginId(),candidate.getOriginId()) && Objects.equals(original.getSourceIdentity(),candidate.getSourceIdentity())
                    && Objects.equals(original.getBlockId(),candidate.getBlockId()) && Objects.equals(original.getSourceAnchor(),candidate.getSourceAnchor())
                    && original.getObservedStartOffset()==candidate.getObservedStartOffset() && original.getObservedEndOffset()==candidate.getObservedEndOffset()
                    && Objects.equals(original.getObservedRanges(),candidate.getObservedRanges())
                    && new LinkedHashSet<>(original.getRequiredChunkIds()).equals(new LinkedHashSet<>(candidate.getRequiredChunkIds())));
            if (!matched) return false;
        }
        // Preserve every declared incoming observation, including unknown/empty requests. A missing trace is not resolution.
        List<VettingIncomingLiteralContext.Trace> unmatched=new ArrayList<>(expanded.getIncomingReferences());
        for(VettingIncomingLiteralContext.Trace original:first.getIncomingReferences()) {
            int match=-1;
            for(int i=0;i<unmatched.size();i++)if(sameIncomingRequest(original,unmatched.get(i))) {match=i;break;}
            if(match<0)return false;
            unmatched.remove(match);
        }
        return true;
    }

    private static boolean sameIncomingRequest(VettingIncomingLiteralContext.Trace original,VettingIncomingLiteralContext.Trace candidate) {
        return Objects.equals(original.getDirection(),candidate.getDirection())
                &&Objects.equals(original.getIncomingSourceId(),candidate.getIncomingSourceId())
                &&Objects.equals(original.getIncomingSourceAnchor(),candidate.getIncomingSourceAnchor())
                &&Objects.equals(original.getIncomingSourceIdentity(),candidate.getIncomingSourceIdentity())
                &&Objects.equals(original.getReference(),candidate.getReference())
                &&Objects.equals(original.getTargetClauseId(),candidate.getTargetClauseId())
                &&Objects.equals(original.getTargetSourceIdentity(),candidate.getTargetSourceIdentity())
                &&Objects.equals(original.getTargetHeadingLocation(),candidate.getTargetHeadingLocation())
                &&Objects.equals(original.getResolutionMode(),candidate.getResolutionMode())
                &&Objects.equals(original.getInitialOriginIds(),candidate.getInitialOriginIds())
                &&Objects.equals(original.getResolvedTargetFamilyIds(),candidate.getResolvedTargetFamilyIds())
                &&Objects.equals(original.getRequiredChunkIds(),candidate.getRequiredChunkIds())
                &&original.isInitiallyConnected()==candidate.isInitiallyConnected()
                &&Objects.equals(original.getObservationScope(),candidate.getObservationScope())
                &&Objects.equals(original.getApplicability(),candidate.getApplicability())
                &&Objects.equals(original.getQualifiersComplete(),candidate.getQualifiersComplete())
                &&original.isSourceBlockCompleteKnown()==candidate.isSourceBlockCompleteKnown()
                &&original.isLegalApplicabilityVerified()==candidate.isLegalApplicabilityVerified()
                &&sameIncomingBlocks(original.getBlockObservations(),candidate.getBlockObservations());
    }
    private static boolean sameIncomingBlocks(List<VettingBlockContinuation.Trace> original,List<VettingBlockContinuation.Trace> candidate) {
        if(original.size()!=candidate.size())return false;
        for(int i=0;i<original.size();i++) {
            VettingBlockContinuation.Trace a=original.get(i),b=candidate.get(i);
            if(!Objects.equals(a.getOriginId(),b.getOriginId())||!Objects.equals(a.getSourceIdentity(),b.getSourceIdentity())
                    ||!Objects.equals(a.getBlockId(),b.getBlockId())||!Objects.equals(a.getSourceAnchor(),b.getSourceAnchor())
                    ||a.getObservedStartOffset()!=b.getObservedStartOffset()||a.getObservedEndOffset()!=b.getObservedEndOffset()
                    ||!Objects.equals(a.getObservedRanges(),b.getObservedRanges())||!Objects.equals(a.getRequiredChunkIds(),b.getRequiredChunkIds())
                    ||!Objects.equals(a.getCoverage(),b.getCoverage())||a.isSourceBlockCompleteKnown()!=b.isSourceBlockCompleteKnown())return false;
        }
        return true;
    }

    private static Set<String> missingLocatedTargets(Selection selection) {
        Set<String> missing = new LinkedHashSet<>();
        Set<String> submitted = selection.getChunks().stream().filter(Objects::nonNull).map(Chunk::getId).collect(Collectors.toSet());
        for (ReferenceTrace trace : selection.getReferences()) {
            if (trace.isInitiallySubmitted() && trace.isOriginSubmitted() && !trace.getRequiredTargetIds().isEmpty())
                trace.getRequiredTargetIds().stream().filter(id -> !submitted.contains(id)).forEach(missing::add);
        }
        for (VettingBlockContinuation.Trace trace : selection.getBlockContinuations()) {
            if(trace.isInitiallySubmitted()&&trace.isOriginSubmitted()&&!trace.getRequiredChunkIds().isEmpty())trace.getRequiredChunkIds().stream().filter(id -> !submitted.contains(id)).forEach(missing::add);
        }
        // Required IDs are the source-bound request. Summary booleans/lists cannot hide absent submitted bodies.
        for(VettingIncomingLiteralContext.Trace trace:selection.getIncomingReferences())
            trace.getRequiredChunkIds().stream().filter(id->!submitted.contains(id)).forEach(missing::add);
        return missing;
    }
}
