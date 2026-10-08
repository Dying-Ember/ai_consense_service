package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.domain.DraftVariable;
import com.consense.domain.SourceDocument;
import com.consense.web.dto.DraftingDtos.CandidateVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Persistence identity helpers. They never decide business applicability. */
final class DraftAdoption {
    private DraftAdoption() { }
    static Object decode(DraftBlueprint.InputSpec spec,String value) {
        if(spec!=null&&Arrays.asList("text","date").contains(spec.kind))return JsonUtils.isBlankText(value)?null:value;
        return decode(value);
    }
    static Object decode(String value) {
        if (JsonUtils.isBlankText(value)) return null;
        String text=value.trim();
        if (text.startsWith("[") || text.startsWith("{") || "true".equals(text) || "false".equals(text)
                || "null".equals(text) || text.matches("-?\\d+(\\.\\d+)?")) {
            try { return JsonUtils.read(text,Object.class); } catch (RuntimeException ignored) { }
        }
        return value;
    }
    static List<CandidateVO> candidates(DraftVariable variable) {
        try { return JsonUtils.readList(variable.getCandidatesJson(),CandidateVO.class); }
        catch (RuntimeException invalidLegacy) { return Collections.emptyList(); }
    }
    static List<CandidateVO> adoptedSources(DraftVariable variable) {
        try { return JsonUtils.readList(variable.getAdoptedSourcesJson(),CandidateVO.class); }
        catch (RuntimeException invalidLegacy) { return Collections.emptyList(); }
    }
    static String candidateIdentity(List<CandidateVO> candidates) {
        List<Object> identities=new ArrayList<>();
        for (CandidateVO candidate:candidates) identities.add(Arrays.asList(candidate.getValue(),
                candidate.getSourceDocumentId(),candidate.getSourceHash(),candidate.getSourceQuote()));
        return JsonUtils.write(identities);
    }
    static String hash(String content) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest((content==null?"":content).getBytes(StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder();
            for (byte value:bytes) result.append(String.format(Locale.ROOT,"%02x",value & 0xff));
            return result.toString();
        } catch (Exception unavailable) { throw new IllegalStateException("SHA-256 unavailable",unavailable); }
    }
    static String sourceHash(SourceDocument source) {
        return hash(source.getCategory()+"\n"+source.getFileKey()+"\n"+source.getParseStatus()+"\n"+source.getTextContent());
    }
    static Map<String,Object> sourceIdentity(SourceDocument source) {
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("id",source.getId()); item.put("category",source.getCategory()); item.put("fileKey",source.getFileKey());
        item.put("fileName",source.getFileName()); item.put("hash",sourceHash(source)); return item;
    }
}
