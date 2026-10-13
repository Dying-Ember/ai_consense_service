package com.consense.service.vetting;

import com.consense.web.dto.VettingDtos.FindingVO;
import com.consense.web.dto.VettingDtos.ReviewUpdateRequest;

/** Existing current-screen fixtures explicitly carry the same public binding as the frontend. */
public final class VettingHumanReviewFixture {
    private VettingHumanReviewFixture() { }
    public static FindingVO updateStatus(VettingService service,String project,String code,String status){
        FindingVO loaded=current(service,project,code);
        return service.updateStatus(project,code,status,loaded==null?null:loaded.getRunId(),loaded==null?null:loaded.getGeneratedRevisionSha256(),loaded==null?null:loaded.getSourceSnapshotSha256());
    }
    public static FindingVO updateReview(VettingService service,String project,String code,ReviewUpdateRequest request){
        FindingVO loaded=current(service,project,code);
        if(request!=null&&loaded!=null){request.setExpectedRunId(loaded.getRunId());request.setExpectedGeneratedRevisionSha256(loaded.getGeneratedRevisionSha256());request.setExpectedSourceSnapshotSha256(loaded.getSourceSnapshotSha256());}
        return service.updateReview(project,code,request);
    }
    private static FindingVO current(VettingService service,String project,String code){return service.listFindings(project,null,null,null,null,null).stream().filter(f->code.equals(f.getCode())).findFirst().orElse(null);}
}
