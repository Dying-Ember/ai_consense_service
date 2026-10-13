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
        p.setVerifiedConsistentAssessments(call.getVerifiedConsistentAssessments());p.setVerifiedInsufficientContextAssessments(call.getVerifiedInsufficientContextAssessments());
        p.setVerifiedNotApplicableAssessments(call.getVerifiedNotApplicableAssessments());
        p.setCheckState(checkState(p));call.setCheckState(p.getCheckState());
    }
    static void restore(SemanticPacketVO saved,SemanticTopicVO call,SemanticPacketVO packet) {
        packet.setAttempts(saved.getAttempts());
        call.setStatus(saved.getStatus());call.setFailureKind(saved.getFailureKind());call.setError(saved.getError());call.setModelResponseMetadata(saved.getModelResponseMetadata());call.setRawResponseSha256(saved.getRawResponseSha256());
        call.setReturnedAssessments(saved.getReturnedAssessments());call.setIssueAssessments(saved.getIssueAssessments());call.setConsistentAssessments(saved.getConsistentAssessments());call.setInsufficientContextAssessments(saved.getInsufficientContextAssessments());call.setAcceptedFindings(saved.getAcceptedFindings());call.setRejectedRecords(saved.getRejectedRecords());
        call.setVerifiedConsistentAssessments(saved.getVerifiedConsistentAssessments());call.setVerifiedInsufficientContextAssessments(saved.getVerifiedInsufficientContextAssessments());call.setVerifiedNotApplicableAssessments(saved.getVerifiedNotApplicableAssessments());packet.setVerifiedAssessments(saved.getVerifiedAssessments());packet.setActualGatewayCallStarted(saved.isActualGatewayCallStarted());callResult(call,packet);
    }
    private static String checkState(SemanticPacketVO p) {
        if(!Arrays.asList("completed","completed_empty","completed_with_rejections").contains(p.getStatus()))return "not_completed";
        if(p.getAcceptedFindings()>0)return "issue";
        if(p.getVerifiedInsufficientContextAssessments()>0)return "insufficient_evidence";
        if(p.getVerifiedNotApplicableAssessments()>0&&p.getVerifiedNotApplicableAssessments()==p.getReturnedAssessments()&&p.getRejectedRecords()==0)return "not_applicable";
        if(p.getVerifiedConsistentAssessments()>0&&p.getRejectedRecords()==0)return "checked_pass";
        return "not_completed";
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
        if(audit.getPacketAudits().stream().anyMatch(p->"issue".equals(p.getCheckState())))audit.setCheckState("issue");
        else if(audit.getPacketAudits().stream().anyMatch(p->"insufficient_evidence".equals(p.getCheckState())))audit.setCheckState("insufficient_evidence");
        else if(!audit.getPacketAudits().isEmpty()&&audit.getPacketAudits().stream().allMatch(p->"checked_pass".equals(p.getCheckState()))&&audit.getPendingSourceRequestCount()==0)audit.setCheckState("checked_pass");
        else if(!audit.getPacketAudits().isEmpty()&&audit.getPacketAudits().stream().allMatch(p->"not_applicable".equals(p.getCheckState()))&&audit.getPendingSourceRequestCount()==0)audit.setCheckState("not_applicable");
        else audit.setCheckState("not_completed");
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
