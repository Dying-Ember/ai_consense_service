package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.CandidateVO;
import java.util.*;
import java.util.regex.Pattern;

/** Only grounded, expressly partial trade facts may add options to another suggestion. */
final class DraftTradeCandidates {
    private static final Pattern PARTIAL=Pattern.compile("(?i)\\b(?:partial\\s+(?:selected\\s+)?(?:trade|subcontract)\\s+(?:confirmation|list|selection)|additional\\s+(?:selected\\s+)?(?:trade|subcontract)|selected\\s+trades\\s+also\\s+include)\\b");
    private static final String LIST_SCOPE="(?:complete|entire|full|exhaustive)\\s+(?:(?:selected|specialist|subcontract|trade)\\s+)*list";
    private static final Pattern NOT_COMPLETE=Pattern.compile("(?i)\\bnot\\s+(?:(?:a|the)\\s+)?"+LIST_SCOPE+"\\b");
    private static final Pattern COMPLETE=Pattern.compile("(?i)\\b(?:"+LIST_SCOPE+"|no\\s+other\\s+(?:(?:specialist|subcontract)\\s+)?trades?)\\b");

    private DraftTradeCandidates() { }

    /** Null keeps competing candidates visible instead of prefilling a silently enlarged set. */
    static String merge(DraftBlueprint.InputSpec spec,List<CandidateVO> candidates) {
        if(candidates.isEmpty())return "";
        String first=candidates.get(0).getValue();
        if(candidates.stream().allMatch(candidate->first.equals(candidate.getValue())))return first;
        Set<String> trades=new LinkedHashSet<>();
        Set<String> complete=null;
        for(CandidateVO candidate:candidates) {
            Set<String> selected=new LinkedHashSet<>();
            for(com.fasterxml.jackson.databind.JsonNode value:JsonUtils.parse(candidate.getValue()))selected.add(value.asText());
            String quote=candidate.getSourceQuote();
            // A full-list assertion takes precedence over incidental discussion of possible additions.
            boolean partial=PARTIAL.matcher(quote).find()&&!COMPLETE.matcher(NOT_COMPLETE.matcher(quote).replaceAll("")).find();
            if(selected.isEmpty()||!partial) {
                if(complete!=null&&!complete.equals(selected))return null;
                complete=selected;
            }
            trades.addAll(selected);
        }
        if(complete!=null&&!complete.containsAll(trades))return null;
        return DraftInputRules.normalizeSuggestion(spec,JsonUtils.write(trades));
    }
}
