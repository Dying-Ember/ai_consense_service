package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.domain.VettingFinding;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import com.consense.web.dto.VettingDtos.FindingVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Compatibility entry point. All report exports use the same competition report presentation. */
@Deprecated
@Component
public class VettingPdfWriter {
    private final VettingReportWriter reports;
    public VettingPdfWriter() { this(new VettingReportWriter()); }
    @Autowired public VettingPdfWriter(VettingReportWriter reports) { this.reports=reports; }
    public byte[] write(Project project,List<VettingFinding> findings,String lang) {
        List<FindingVO> rows=new ArrayList<>();
        if(findings!=null)for(VettingFinding f:findings)if(f!=null) {
            FindingVO row=new FindingVO();row.setCode(f.getCode());row.setFileKey(f.getFileKey());
            row.setRefs(f.getRefs());row.setPageNo(f.getPageNo());row.setLocation(f.getLocation());
            row.setExpected(f.getExpected());row.setStatus(f.getStatus());row.setScope(f.getScope());
            row.setSeverity(f.getSeverity());row.setSource(f.getSource());row.setVerification(f.getVerification());
            row.setTypes(f.getTypes()==null?Collections.emptyList():Arrays.asList(f.getTypes().split(",")));
            row.setTitle(LocalizedText.of(f.getTitleZhHans(),f.getTitleZhHant(),f.getTitleEn()));
            row.setBody(LocalizedText.of(f.getBodyZhHans(),f.getBodyZhHant(),f.getBodyEn()));
            row.setImpact(LocalizedText.of(f.getImpactZhHans(),f.getImpactZhHant(),f.getImpactEn()));
            row.setSuggestion(LocalizedText.of(f.getSuggestionZhHans(),f.getSuggestionZhHant(),f.getSuggestionEn()));
            row.setReviewRemarks(f.getReviewRemarks());row.setActionTaken(f.getActionTaken());
            row.setAddendumRequired(f.getAddendumRequired());row.setReviewUpdatedAt(f.getReviewUpdatedAt());
            row.setEvidence(JsonUtils.readList(f.getEvidenceJson(),FindingEvidence.class));rows.add(row);
        }
        // A legacy call has no run/snapshot or manual checklist decisions: those remain unknown.
        return reports.pdf(project,rows,null,lang);
    }
}