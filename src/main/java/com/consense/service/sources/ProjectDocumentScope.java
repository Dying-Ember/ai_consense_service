package com.consense.service.sources;

import com.consense.common.BizException;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus;
import java.util.*;
import java.util.stream.Collectors;

/** Competition file boundary shared by review and advice; role and document scope are independent. */
public final class ProjectDocumentScope {
    private ProjectDocumentScope() {}
    private static final List<String> ORDER=Arrays.asList("NTT","SCT","SCC","GCT","FT","AA","GCC","SL","PRE","SP","BQ","GS");
    private static final Set<String> TENDER_KEYS=new HashSet<>(Arrays.asList("NTT","SCT","GCT","FT"));
    public static String normalizeScope(String scope) {
        String value=nvl(scope).trim().toLowerCase(Locale.ROOT);
        if(value.isEmpty())return "fullset";
        if(Arrays.asList("fullset","tender","contract").contains(value))return value;
        throw new BizException(4202,"Advice scope must be fullset, tender or contract.");
    }
    public static String normalizeDocumentScope(String scope) {
        String value=nvl(scope).trim().toLowerCase(Locale.ROOT);
        if(value.isEmpty()||"auto".equals(value))return null;
        if(Arrays.asList("package","works_pss","pricing","example","drawing").contains(value))return value;
        throw new BizException(4101,"Document scope must be auto, package, works_pss, pricing, example or drawing.");
    }
    /** Full catalog, retaining exclusions and failed/newer uploads so callers cannot silently use an older parse. */
    public static List<SourceDocument> currentCatalog(String projectId,List<SourceDocument> documents) {
        Map<String,SourceDocument> latest=new LinkedHashMap<>();
        documents.stream().filter(d->Objects.equals(projectId,d.getProjectId())).filter(ProjectDocumentScope::reviewCategory)
                .sorted(Comparator.comparing(d->d.getId()==null?0L:d.getId()))
                .forEach(d->latest.put(nvl(d.getFileName()).trim().toLowerCase(Locale.ROOT),d));
        List<SourceDocument> result=new ArrayList<>(latest.values());
        result.sort(Comparator.comparingInt(d->{int at=ORDER.indexOf(key(d));return at<0?ORDER.size():at;}));
        return result;
    }
    public static List<SourceDocument> selected(String projectId,List<SourceDocument> documents,String scope) {
        return selected(projectId,documents,scope,"vetting");
    }
    public static List<SourceDocument> selected(String projectId,List<SourceDocument> documents,String scope,String module) {
        String normalized=normalizeScope(scope);
        return currentCatalog(projectId,documents).stream().filter(d->exclusionReason(d,normalized,module)==null).collect(Collectors.toList());
    }
    public static boolean reviewCategory(SourceDocument document) {
        return SourceDocument.CATEGORY_VETTING_PACKAGE.equals(document.getCategory())||SourceDocument.CATEGORY_VETTING_SUPPLEMENT.equals(document.getCategory());
    }
    public static String exclusionReason(SourceDocument d,String scope) {
        return exclusionReason(d,scope,"vetting");
    }
    public static String exclusionReason(SourceDocument d,String scope,String module) {
        if(!reviewCategory(d))return "not_review_package";
        if("drawing".equals(d.getDocumentScope())||"DRAWING".equals(key(d)))return "drawing_outside_scope";
        String moduleExclusion=OerSourceMetadata.moduleExclusion(d,module);
        if(moduleExclusion!=null)return moduleExclusion;
        String filename=nvl(d.getFileName()).toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ');
        String explicit=nvl(d.getDocumentScope()).toLowerCase(Locale.ROOT);
        // Recognisable answer keys remain held out even when accidentally labelled as package provisions.
        if("example".equals(explicit)||filename.contains("vetting comment")||filename.contains("checklist")||filename.contains("q&a")
                ||filename.contains("q & a")||filename.contains("contractual advice")||filename.contains("answer sheet")
                ||filename.contains("questions and answers")||filename.matches(".*\\bappendix\\s+flowchart\\b.*"))return "example_answer";
        if("pricing".equals(explicit))return "pricing_document";
        if("works_pss".equals(explicit))return "project_works_specification";
        boolean technicalStandard="standard".equals(VettingCorpus.sourceRole(d))&&("GS".equals(key(d))||filename.contains("specification library")||filename.contains("general specification"));
        if(Arrays.asList("BQ","SOR").contains(key(d))||("GS".equals(key(d))&&!technicalStandard)||filename.contains("bill of quantities")||filename.contains("bills of quantities")
                ||filename.contains("schedule of rates")||filename.contains("general summary"))return "pricing_document";
        // Package labels cannot override fixed competition exclusions. Standard SL/GS, project PRE and composition remain available.
        boolean projectPss=filename.contains("pss")||filename.contains("project specific specification")||filename.contains("project specification");
        if(projectPss&&!"PRE".equals(key(d))&&!filename.contains("preliminar")&&!filename.matches(".*\\bpre\\b.*")
                &&!"standard".equals(VettingCorpus.sourceRole(d))&&!"SL".equals(key(d)))return "project_works_specification";
        String normalized=normalizeScope(scope);
        if("fullset".equals(normalized))return null;
        String role=VettingCorpus.sourceRole(d);
        if("project_fact".equals(role)||"package_manifest".equals(role))return null;
        boolean tender=TENDER_KEYS.contains(key(d))||filename.contains("marking scheme")||filename.contains("tenderer")||filename.contains("restriction to award tender");
        if("tender".equals(normalized)&&!tender)return "outside_tender_scope";
        if("contract".equals(normalized)&&tender)return "outside_contract_scope";
        return null;
    }
    public static boolean parseUsable(SourceDocument document) {
        return Arrays.asList("PARSED","PARTIAL").contains(document.getParseStatus())&&!nvl(document.getTextContent()).trim().isEmpty();
    }
    private static String key(SourceDocument d){return nvl(d.getFileKey()).toUpperCase(Locale.ROOT);}
    private static String nvl(String value){return value==null?"":value;}
}
