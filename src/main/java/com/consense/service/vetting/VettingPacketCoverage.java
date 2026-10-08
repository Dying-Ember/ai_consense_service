package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.*;
import java.util.*;
import java.util.stream.Collectors;

/** Call/decode and observed-request transport accounting, never correctness of model declarations. */
final class VettingPacketCoverage {
    private VettingPacketCoverage() { }
    static SemanticPacketVO packet(int topicIndex,int index,String kind,List<Chunk> chunks,List<String> requestIds) {
        SemanticPacketVO p=new SemanticPacketVO();p.setPacketIndex(index);p.setKind(kind);p.setSourceSnapshotSha256(VettingCorpus.hash(JsonUtils.write(chunks)));
        p.setPacketId("topic-"+topicIndex+"-packet-"+index+"-"+p.getSourceSnapshotSha256());p.setSourceObservationPacketId(p.getPacketId());p.setStatus("not_submitted");
        p.setSubmittedChunkIds(chunks.stream().map(Chunk::getId).collect(Collectors.toList()));p.setSubmittedChars(chunks.stream().mapToInt(c->c.getContent().length()).sum());p.setRequestIds(new ArrayList<>(requestIds));return p;
    }
    static void callResult(SemanticTopicVO call,SemanticPacketVO p) {
        p.setStatus(call.getStatus());p.setFailureKind(call.getFailureKind());p.setError(call.getError());p.setModelResponseMetadata(call.getModelResponseMetadata());p.setRawResponseSha256(call.getRawResponseSha256());
        p.setReturnedAssessments(call.getReturnedAssessments());p.setIssueAssessments(call.getIssueAssessments());p.setConsistentAssessments(call.getConsistentAssessments());p.setInsufficientContextAssessments(call.getInsufficientContextAssessments());p.setAcceptedFindings(call.getAcceptedFindings());p.setRejectedRecords(call.getRejectedRecords());
    }
    static void finish(SemanticTopicVO audit,VettingSourceRequestPacks.Result plan) {
        audit.setGlobalCallStatus(audit.getStatus());audit.setExtraPacketCount(Math.max(0,audit.getPacketAudits().size()-1));
        audit.setFailedPacketCount((int)audit.getPacketAudits().stream().filter(p->"failed".equals(p.getStatus())).count());
        audit.setNotSubmittedPacketCount((int)audit.getPacketAudits().stream().filter(p->p.getStatus().startsWith("not_submitted")).count());
        Map<Integer,SemanticPacketVO> packets=new LinkedHashMap<>();audit.getPacketAudits().forEach(p->packets.put(p.getPacketIndex(),p));
        if(plan!=null) {
            audit.setRequestPacketPolicy(plan.getPolicy());
            for(VettingSourceRequestPacks.Request r:plan.getRequests()) {
                SemanticSourceRequestVO row=new SemanticSourceRequestVO();row.setRequestId(r.getId());row.setKind(r.getKind());row.setObservationId(r.getObservationId());row.setTransportStatus(r.getStatus());row.setUnknownReason(r.getUnknownReason());row.setPackIndex(r.getPackIndex());row.setContentChars(r.getContentChars());
                row.setEligibleOriginIds(new ArrayList<>(r.getEligibleOriginIds()));row.setRequiredChunkIds(new ArrayList<>(r.getRequiredChunkIds()));row.setMissingChunkIds(new ArrayList<>(r.getMissingChunkIds()));row.setSourceTrace(JsonUtils.parse(JsonUtils.write(r.getSourceTrace())));row.setOriginObservation(JsonUtils.parse(JsonUtils.write(r.getOriginObservation())));
                SemanticPacketVO p="already_global".equals(r.getStatus())?packets.get(0):"transported_extra_pack".equals(r.getStatus())?packets.get(r.getPackIndex()):null;
                if(p==null)row.setReviewExecutionStatus("not_submitted_"+r.getStatus());
                else {row.setPacketId(p.getPacketId());if(!new HashSet<>(p.getSubmittedChunkIds()).containsAll(r.getRequiredChunkIds()))throw new IllegalStateException("Observed request not co-located in bound packet");row.setReviewExecutionStatus(execution(p));}
                audit.getSourceRequests().add(row);
            }
        }
        audit.setSourceRequestCount(audit.getSourceRequests().size());audit.setPendingSourceRequestCount((int)audit.getSourceRequests().stream().filter(r->!r.getReviewExecutionStatus().startsWith("decoded")).count());
        if("failed".equals(audit.getGlobalCallStatus()))audit.setAggregateReviewStatus("failed");
        else if(audit.getPacketAudits().stream().noneMatch(SemanticPacketVO::isActualGatewayCallStarted))audit.setAggregateReviewStatus("not_submitted");
        else if(audit.getPendingSourceRequestCount()>0||audit.getFailedPacketCount()>0||audit.getNotSubmittedPacketCount()>0||audit.getPacketAudits().stream().anyMatch(p->!"observed_tokens".equals(p.getInputBudgetStatus())))audit.setAggregateReviewStatus("partial");
        else audit.setAggregateReviewStatus("observed_requests_decoded_scope_unknown");
    }
    private static String execution(SemanticPacketVO p) {
        if("failed".equals(p.getStatus()))return "failed";
        if("provider_estimated_fit".equals(p.getInputBudgetStatus())) {
            if("completed_with_rejections".equals(p.getStatus()))return "decoded_provider_estimate_with_rejections";
            return Arrays.asList("completed","completed_empty").contains(p.getStatus())?"decoded_provider_estimate":"not_submitted";
        }
        if(!"observed_tokens".equals(p.getInputBudgetStatus()))return "over_budget".equals(p.getInputBudgetStatus())?"over_budget":"budget_unknown";
        if("completed_with_rejections".equals(p.getStatus()))return "decoded_with_rejections";
        if(Arrays.asList("completed","completed_empty").contains(p.getStatus()))return "decoded";
        return "not_submitted";
    }
}
