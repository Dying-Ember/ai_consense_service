package com.consense.service.vetting;

import com.consense.common.BizException;
import com.consense.domain.VettingRun;
import com.consense.domain.VettingChecklistReview;
import com.consense.repository.VettingChecklistReviewRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.ProjectService;
import com.consense.web.dto.VettingChecklistDtos.ChecklistVO;
import com.consense.web.dto.VettingChecklistDtos.ItemVO;
import com.consense.web.dto.VettingChecklistDtos.UpdateRequest;
import com.consense.web.dto.VettingChecklistDtos.UpdateItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.time.Instant;
import java.util.stream.Collectors;

/** Appendix I is a human countercheck record, independent of finding or run completion status. */
@Service
@RequiredArgsConstructor
public class VettingChecklistService {
    private final ProjectService projectService;
    private final VettingRunRepository runRepository;
    private final VettingChecklistReviewRepository reviewRepository;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy
    private VettingService vetting;

    public ChecklistVO get(String projectId,String runId) {
        VettingRun run=requireRun(projectId,runId);
        String binding=snapshot(run);
        Map<String,VettingChecklistReview> reviews=reviewRepository.findByProjectIdAndRunId(projectId,runId).stream()
                .filter(review->binding.equals(review.getRunSnapshotSha256()))
                .collect(Collectors.toMap(VettingChecklistReview::getItemId,review->review));
        List<ItemVO> items=VettingChecklistCatalog.items().stream().map(item->{
            VettingChecklistReview review=reviews.get(item.getId());
            return new ItemVO(item.getId(),item.getSection(),item.getClause(),item.getDescription(),item.getTemplatePage(),
                    item.getReviewScope(),item.getScopeNote(),item.getScopeNoteText(),
                    review==null?"unchecked":review.getStatus(),review==null?"":review.getRemarks(),review==null?null:review.getUpdatedAt());
        }).collect(Collectors.toList());
        return new ChecklistVO(projectId,runId,binding,vetting.currentReviewRun(run),items,run.getSourceSnapshotSha256());
    }

    @Transactional
    public ChecklistVO update(String projectId,String runId,UpdateRequest request) {
        projectService.require(projectId);
        if(request==null||!runId.equals(request.getExpectedRunId()))throw new BizException(4104,"Checklist run binding is missing or changed; reload before saving");
        VettingRun run=vetting.requireCurrentReviewRun(projectId,runId,request.getExpectedSourceSnapshotSha256());
        String binding=snapshot(run);
        if(request==null||!binding.equals(request.getRunSnapshotSha256()))
            throw new BizException(4123,"Review run or checklist requirements changed; reload before saving");
        validateItems(request.getItems());
        Map<String,VettingChecklistReview> current=reviewRepository.findByProjectIdAndRunId(projectId,runId).stream()
                .collect(Collectors.toMap(VettingChecklistReview::getItemId,review->review));
        List<VettingChecklistReview> changes=new ArrayList<>();
        for(UpdateItem item:request.getItems()) {
            VettingChecklistReview review=current.get(item.getId());
            if(review==null) {review=new VettingChecklistReview();review.setId(UUID.randomUUID().toString());}
            review.setProjectId(projectId);review.setRunId(runId);review.setItemId(item.getId());
            review.setRunSnapshotSha256(binding);review.setStatus(item.getStatus());
            review.setRemarks(item.getRemarks()==null?"":item.getRemarks());review.setUpdatedAt(Instant.now());changes.add(review);
        }
        reviewRepository.saveAllAndFlush(changes);
        return get(projectId,runId);
    }

    public List<VettingChecklistCatalog.Result> resultsForRun(String projectId,String runId) {
        return get(projectId,runId).getItems().stream().map(item->new VettingChecklistCatalog.Result(
                item.getId(),item.getStatus(),item.getRemarks())).collect(Collectors.toList());
    }

    private VettingRun requireRun(String projectId,String runId) {
        projectService.require(projectId);
        return runRepository.findById(runId).filter(run->projectId.equals(run.getProjectId()))
                .orElseThrow(()->new BizException(4102,"Review run is unavailable for this project"));
    }

    private static String snapshot(VettingRun run) {
        return VettingCorpus.hash("vetting-checklist-v1\n"+run.getId()+"\n"+
                (run.getCoverageJson()==null?"":run.getCoverageJson())+"\n"+
                VettingChecklistCatalog.items().stream().map(item->item.getId()+"\n"+item.getSection()+"\n"+
                        item.getClause()+"\n"+item.getDescription()+"\n"+item.getReviewScope()+"\n"+item.getScopeNote()).collect(Collectors.joining("\n")));
    }

    private static void validateItems(List<UpdateItem> items) {
        if(items==null||items.isEmpty()||items.size()>VettingChecklistCatalog.items().size())
            throw new BizException(4121,"Provide one or more distinct checklist requirements");
        Set<String> ids=VettingChecklistCatalog.items().stream().map(VettingChecklistCatalog.Item::getId).collect(Collectors.toSet());
        Set<String> seen=new HashSet<>();
        for(UpdateItem item:items) {
            if(item==null||!ids.contains(item.getId())||!seen.add(item.getId()))
                throw new BizException(4121,"Unknown or repeated checklist requirement");
            if(!"unchecked".equals(item.getStatus())&&!"fulfilled".equals(item.getStatus())&&!"not_applicable".equals(item.getStatus()))
                throw new BizException(4121,"Checklist state must be unchecked, fulfilled or not_applicable");
            if(item.getRemarks()!=null&&item.getRemarks().length()>4000)
                throw new BizException(4121,"Checklist remarks must not exceed 4000 characters");
        }
    }
}
