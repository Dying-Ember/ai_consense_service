package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import lombok.Data;
import java.util.*;

/** Bounded parser declarations. These observations never certify OCR text, native text or legal coverage. */
public final class VettingSourceQuality {
    public static final String VERSION="source-quality-observations-v1";
    private static final ObjectMapper COVERAGE=new ObjectMapper(new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> STATUSES=new HashSet<>(Arrays.asList("PENDING","PARSING","PARSED","PARTIAL","FAILED"));
    private VettingSourceQuality() { }

    @Data public static class Info {
        private String parseStatus="unknown", ocrQualityStatus="unknown", textAccuracy="unverified";
        /** This is only the stored parser coverage declaration, never source/legal completeness. */
        private Boolean extractionCoverageComplete, ocrDeclared;
        private Integer totalPages, parsedPages, ocrPages;
        private List<Integer> needsReviewPages=new ArrayList<>();
        private boolean qualityPageScopeUnknown=true;
        private List<String> limitations=new ArrayList<>();
        /** Hash of supported normalized coverage fields; unrelated timestamps are excluded. */
        private String coverageMetadataSha256;
    }

    public static Info from(SourceDocument source,List<DocumentBlock> blocks) {
        Info out=new Info();TreeSet<String> reasons=new TreeSet<>();
        String status=source.getParseStatus()==null?"":source.getParseStatus().trim().toUpperCase(Locale.ROOT);
        if(STATUSES.contains(status))out.setParseStatus(status);else reasons.add("parse_status_unknown");
        if("PARTIAL".equals(out.getParseStatus()))reasons.add("parser_partial_extraction");
        if("FAILED".equals(out.getParseStatus()))reasons.add("parser_failed_extraction");
        out.setOcrDeclared(source.getOcrUsed());
        JsonNode coverage=null;
        if(source.getParseCoverageJson()==null||source.getParseCoverageJson().trim().isEmpty())reasons.add("stored_coverage_unavailable");
        else try {
            JsonNode parsed=COVERAGE.readTree(source.getParseCoverageJson());
            if(parsed==null||!parsed.isObject())reasons.add("stored_coverage_not_object");else coverage=parsed;
        } catch(Exception invalidCoverage) {reasons.add("stored_coverage_invalid_json");}
        Integer sourcePages=source.getPageCount()!=null&&source.getPageCount()>0?source.getPageCount():null;
        boolean invalidPageIdentity=false, newQualityFields=false, invalidNewQualityFields=false, declaredScopeUnknown=false, limitationPresent=false;
        TreeSet<Integer> reviewPages=new TreeSet<>();String declaredQuality=null;
        if(coverage!=null) {
            out.setTotalPages(integer(coverage,"totalPages",reasons));out.setParsedPages(integer(coverage,"parsedPages",reasons));out.setOcrPages(integer(coverage,"ocrPages",reasons));
            out.setExtractionCoverageComplete(bool(coverage,"complete",reasons));
            boolean mandatoryQualityKeysPresent=coverage.has("ocrQualityStatus")&&coverage.has("needsReviewPages")&&coverage.has("ocrQualityPageScopeUnknown");
            if(coverage.has("ocrQualityStatus")&&!coverage.get("ocrQualityStatus").isNull()) {
                JsonNode q=coverage.get("ocrQualityStatus");
                if(q.isTextual()&&"needs_review".equals(q.asText()))declaredQuality="needs_review";
                else reasons.add("ocr_quality_declaration_unsupported");
            }
            Boolean scope=bool(coverage,"ocrQualityPageScopeUnknown",reasons);declaredScopeUnknown=Boolean.TRUE.equals(scope);
            if(coverage.has("limitations")) {
                JsonNode limits=coverage.get("limitations");
                if(limits.isArray())limitationPresent=limits.size()>0;else reasons.add("coverage_limitations_shape_unknown");
            }
            Integer physical=out.getTotalPages()!=null&&out.getTotalPages()>0?out.getTotalPages():sourcePages;
            if(out.getTotalPages()!=null&&out.getTotalPages()>0&&sourcePages!=null&&!out.getTotalPages().equals(sourcePages)) {
                invalidPageIdentity=true;reasons.add("physical_page_count_conflict");
            }
            boolean recognizedReviewPages=false;
            if(coverage.has("needsReviewPages")) {
                JsonNode pages=coverage.get("needsReviewPages");
                if(!pages.isArray()){invalidPageIdentity=true;reasons.add("review_page_shape_unknown");}
                else { recognizedReviewPages=true;for(JsonNode page:pages) {
                    if(!page.isIntegralNumber()||!page.canConvertToInt()||page.asInt()<=0||physical!=null&&page.asInt()>physical) {
                        recognizedReviewPages=false;invalidPageIdentity=true;reasons.add("review_page_identity_unknown");
                    } else reviewPages.add(page.asInt());
                } }
            }
            newQualityFields=mandatoryQualityKeysPresent&&"needs_review".equals(declaredQuality)&&scope!=null&&recognizedReviewPages;
            invalidNewQualityFields=mandatoryQualityKeysPresent&&!newQualityFields;
            if(out.getTotalPages()==null&&sourcePages!=null)out.setTotalPages(sourcePages);
        } else if(sourcePages!=null)out.setTotalPages(sourcePages);
        boolean blockOcr=false;Set<Integer> observedOcrPages=new HashSet<>();
        if(blocks!=null)for(DocumentBlock block:blocks)if(block!=null&&"ocr".equals(block.getSource())) {
            blockOcr=true;
            if(block.getPageNo()==null||block.getPageNo()<=0||out.getTotalPages()!=null&&out.getTotalPages()>0&&block.getPageNo()>out.getTotalPages())invalidPageIdentity=true;
            else observedOcrPages.add(block.getPageNo());
        }
        boolean ocrObserved=Boolean.TRUE.equals(out.getOcrDeclared())||out.getOcrPages()!=null&&out.getOcrPages()>0
                ||blockOcr||declaredQuality!=null||!reviewPages.isEmpty();
        boolean conflictingOcr=Boolean.FALSE.equals(out.getOcrDeclared())&&(blockOcr||out.getOcrPages()!=null&&out.getOcrPages()>0);
        if(conflictingOcr)reasons.add("ocr_declaration_conflicts_with_extraction");
        if(ocrObserved) {
            out.setOcrQualityStatus("needs_review");reasons.add("ocr_text_requires_original_page_review");
            if(invalidNewQualityFields)reasons.add("ocr_quality_declaration_fields_unknown");
            boolean countIncomplete=out.getOcrPages()==null||out.getOcrPages()!=reviewPages.size();
            boolean extractionPageConflict=newQualityFields&&!reviewPages.containsAll(observedOcrPages);
            if(extractionPageConflict)reasons.add("ocr_extraction_page_not_in_review_declaration");
            boolean noPhysicalScope=out.getTotalPages()==null||out.getTotalPages()<=0;
            out.setQualityPageScopeUnknown(!newQualityFields||declaredScopeUnknown||reviewPages.isEmpty()||countIncomplete||invalidPageIdentity||noPhysicalScope||conflictingOcr||extractionPageConflict);
            if(!newQualityFields)reasons.add("legacy_ocr_quality_page_scope_unknown");
            if(out.isQualityPageScopeUnknown())reasons.add("ocr_review_page_scope_unknown");
        } else {
            boolean declaredNoOcr=Boolean.FALSE.equals(out.getOcrDeclared())&&Integer.valueOf(0).equals(out.getOcrPages());
            out.setOcrQualityStatus(declaredNoOcr?"not_observed":"unknown");
            out.setQualityPageScopeUnknown(!declaredNoOcr||invalidPageIdentity);
        }
        if(limitationPresent)reasons.add("parser_reported_limitations_present");
        if(out.getParsedPages()!=null&&out.getTotalPages()!=null&&out.getParsedPages()>out.getTotalPages()) {out.setQualityPageScopeUnknown(true);reasons.add("parsed_page_count_inconsistent");}
        if(out.getOcrPages()!=null&&out.getTotalPages()!=null&&out.getOcrPages()>out.getTotalPages()) {
            out.setQualityPageScopeUnknown(true);reasons.add("ocr_page_count_inconsistent");
        }
        out.setNeedsReviewPages(new ArrayList<>(reviewPages));out.setLimitations(new ArrayList<>(reasons));
        Map<String,Object> normalized=new LinkedHashMap<>();normalized.put("complete",out.getExtractionCoverageComplete());normalized.put("totalPages",out.getTotalPages());
        normalized.put("parsedPages",out.getParsedPages());normalized.put("ocrPages",out.getOcrPages());normalized.put("ocrQualityStatus",out.getOcrQualityStatus());
        normalized.put("needsReviewPages",out.getNeedsReviewPages());normalized.put("qualityPageScopeUnknown",out.isQualityPageScopeUnknown());normalized.put("limitations",out.getLimitations());
        out.setCoverageMetadataSha256(VettingCorpus.hash(JsonUtils.write(normalized)));return out;
    }

    public static String hash(Info info) {return VettingCorpus.hash(JsonUtils.write(info));}
    public static Map<String,Object> project(Chunk chunk) {
        Info info=chunk.getSourceQuality();
        if(!VERSION.equals(chunk.getSourceQualityMetadataVersion())||info==null||!Objects.equals(chunk.getSourceQualityHash(),hash(info))||!validInfo(info))
            return unknown("quality_metadata_unavailable_or_identity_mismatch");
        Map<String,Object> out=JsonUtils.read(JsonUtils.write(info),LinkedHashMap.class);
        out.put("observationScope","stored_parser_declarations_only; textAccuracy_unverified; legalCoverage_unknown");return out;
    }
    private static boolean validInfo(Info info) {
        if(!"unverified".equals(info.getTextAccuracy())||!STATUSES.contains(info.getParseStatus())&&!"unknown".equals(info.getParseStatus())
                ||!Arrays.asList("unknown","not_observed","needs_review").contains(info.getOcrQualityStatus())||info.getNeedsReviewPages()==null||info.getLimitations()==null)return false;
        for(Integer count:Arrays.asList(info.getTotalPages(),info.getParsedPages(),info.getOcrPages()))if(count!=null&&count<0)return false;
        int previous=0;for(Integer page:info.getNeedsReviewPages()) {
            if(page==null||page<=previous||info.getTotalPages()!=null&&info.getTotalPages()>0&&page>info.getTotalPages())return false;previous=page;
        }
        for(String reason:info.getLimitations())if(reason==null||!reason.matches("(?:parse_status_unknown|parser_partial_extraction|parser_failed_extraction|stored_coverage_unavailable|stored_coverage_not_object|stored_coverage_invalid_json|ocr_quality_declaration_unsupported|ocr_quality_declaration_fields_unknown|ocr_extraction_page_not_in_review_declaration|coverage_limitations_shape_unknown|physical_page_count_conflict|review_page_shape_unknown|review_page_identity_unknown|ocr_declaration_conflicts_with_extraction|ocr_text_requires_original_page_review|legacy_ocr_quality_page_scope_unknown|ocr_review_page_scope_unknown|parser_reported_limitations_present|parsed_page_count_inconsistent|ocr_page_count_inconsistent|invalid_coverage_(?:totalPages|parsedPages|ocrPages|complete|ocrQualityPageScopeUnknown))"))return false;
        return info.getCoverageMetadataSha256()!=null&&info.getCoverageMetadataSha256().matches("[a-f0-9]{64}");
    }
    private static Map<String,Object> unknown(String reason) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("parseStatus","unknown");m.put("ocrQualityStatus","unknown");m.put("textAccuracy","unverified");m.put("extractionCoverageComplete",null);
        m.put("needsReviewPages",Collections.emptyList());m.put("qualityPageScopeUnknown",true);m.put("limitations",Collections.singletonList(reason));return m;
    }
    private static Integer integer(JsonNode root,String name,Set<String> reasons) {
        if(!root.has(name))return null;JsonNode value=root.get(name);
        if(value.isIntegralNumber()&&value.canConvertToInt()&&value.asInt()>=0)return value.asInt();
        reasons.add("invalid_coverage_"+name);return null;
    }
    private static Boolean bool(JsonNode root,String name,Set<String> reasons) {
        if(!root.has(name))return null;JsonNode value=root.get(name);
        if(value.isBoolean())return value.asBoolean();reasons.add("invalid_coverage_"+name);return null;
    }
}
