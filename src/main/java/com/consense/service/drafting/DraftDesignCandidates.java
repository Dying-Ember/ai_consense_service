package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.CandidateVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An expressly complete candidate may dominate compatible fragments; no new list is synthesized. */
final class DraftDesignCandidates {
    private static final Pattern COMPLETE=Pattern.compile("(?i)\\b(?:complete|full|entire|exhaustive)\\s+(?:(?:component|design|and|execution)\\s+){0,5}responsibilit(?:y|ies)\\s+(?:schedule|list|table)\\b");
    private static final Pattern UNSETTLED=Pattern.compile("(?i)\\b(?:not\\s+(?:(?:a|the)\\s+)?(?:complete|full|entire|exhaustive)|incomplete|partial|pending|proposed|unconfirmed|unknown|unselected|alternatives?|options?|subject to|whether|if|unless|not adopted|not approved|not confirmed|not supplied|not provided|not available|not applicable|to be confirmed|please confirm|do not (?:use|adopt)|does not apply)\\b|\\?");
    private static final Pattern PROJECT_SCOPE=Pattern.compile("(?i)\\b(?:within|under|for|in|of)\\s+(?:the\\s+)?(?:same|this|entire|whole)\\s+(?:contract|project)\\b|\\b(?:all|entire|whole)\\s+(?:project|contract)(?:\\s+works)?\\b");
    private static final Pattern OTHER_PROJECT=Pattern.compile("(?i)\\b(?:another|different|other|previous|former)\\s+(?:project|contract)\\b|\\b(?:not|never)\\s+(?:for|under|within|in)\\s+(?:this|the same)\\s+(?:project|contract)\\b");
    private static final Pattern LOCAL_SCOPE=Pattern.compile("(?i)\\b((?:north|south|east|west|northern|southern|eastern|western)(?:\\s+[a-z0-9-]+){0,2}\\s+(?:annex|wing|tower|block|section|phase|zone|plot|site|building|hall|library|facility))\\b|\\b(?:at|within|for|in|of|on)\\s+(?:the\\s+)?((?:[a-z0-9-]+\\s+){0,3}(?:annex|wing|tower|block|section|phase|zone|plot|site|building|hall|library|facility))\\b");
    private static final Pattern NAMED_SCOPE=Pattern.compile("\\b((?i:annex|wing|tower|block|section|phase|zone|plot|site|building)\\s+(?:[A-Z][A-Za-z0-9_-]*|[a-z](?![A-Za-z0-9_-])|[0-9]+))\\b");
    private static final Pattern RESTRICTED_NAMED_SCOPE=Pattern.compile("(?i)\\b(?:limited|restricted)\\s+to\\s+(?:the\\s+)?((?:annex|wing|tower|block|section|phase|zone|plot|site|building)\\s+[a-z0-9][a-z0-9_-]*)\\b");
    private static final Pattern GENERIC_LOCATION=Pattern.compile("(?i)^(?:(?:the|this|same|all|each|every|entire|whole|project|contract|works|specified)\\s+)*(?:annex|wing|tower|block|section|phase|zone|plot|site|building|hall|library|facility)$");
    private static final Pattern GENERIC_FOUNDATION_SCOPE=Pattern.compile("(?i)^(?:(?:for|of|within|under)\\s+)?(?:(?:the|this|all|same|whole|entire|project|contract)\\s+)*(?:buildings?|building\\s+works|(?:project|contract)\\s+works)$");
    private DraftDesignCandidates() { }

    static String merge(DraftBlueprint.InputSpec spec,List<CandidateVO> candidates) {
        if(candidates.isEmpty())return "";
        List<List<JsonNode>> rows=new ArrayList<>();
        for(CandidateVO candidate:candidates) {
            List<JsonNode> parsed=rows(candidate.getValue());if(parsed==null)return null;
            rows.add(parsed);
        }
        String first=candidates.get(0).getValue();
        if(candidates.stream().allMatch(candidate->first.equals(candidate.getValue())))return DraftInputRules.normalizeSuggestion(spec,first);
        int selected=-1;
        for(int i=0;i<candidates.size();i++) {
            String quote=candidates.get(i).getSourceQuote();
            if(quote==null||!COMPLETE.matcher(quote).find()||UNSETTLED.matcher(quote).find()||OTHER_PROJECT.matcher(quote).find())continue;
            if(selected>=0&&(!contains(rows.get(selected),rows.get(i))||!contains(rows.get(i),rows.get(selected))))return null;
            if(selected<0)selected=i;
        }
        if(selected<0||rows.get(selected).isEmpty())return null;
        for(int i=0;i<rows.size();i++) {
            List<JsonNode> partial=rows.get(i);
            if(partial.isEmpty()||!contains(rows.get(selected),partial))return null;
            if(i!=selected&&!missingScopesCovered(rows.get(selected),partial,candidates.get(i).getSourceQuote(),candidates.get(selected).getSourceQuote()))return null;
        }
        return DraftInputRules.normalizeSuggestion(spec,candidates.get(selected).getValue());
    }

    private static List<JsonNode> rows(String value) {
        JsonNode records=JsonUtils.parse(value);if(!records.isArray())return null;
        List<JsonNode> result=new ArrayList<>();Set<String> identities=new HashSet<>();
        Map<String,Integer> counts=new LinkedHashMap<>();Set<String> missingScopes=new HashSet<>();
        for(JsonNode record:records) {
            if(!record.isObject()||!record.path("component").isTextual()||!record.path("design").isBoolean()||!record.path("execution").isBoolean())return null;
            String component=record.path("component").asText();
            if(!Arrays.asList("piling","pilecaps","footings","other").contains(component))return null;
            if(record.hasNonNull("scope")&&!record.path("scope").isTextual())return null;
            String scope=scope(record);
            if(!identities.add(component+"\u0000"+scope))return null;
            counts.put(component,counts.getOrDefault(component,0)+1);
            if(scope.isEmpty())missingScopes.add(component);
            result.add(record);
        }
        for(String component:missingScopes)if(counts.get(component)>1)return null;
        return result;
    }

    private static JsonNode target(List<JsonNode> complete,JsonNode row) {
        JsonNode found=null;
        for(JsonNode candidate:complete)if(row.path("component").equals(candidate.path("component"))&&
                (scope(row).isEmpty()||genericFoundationScope(row)||scope(row).equals(scope(candidate)))) {
            if(found!=null)return null;
            found=candidate;
        }
        return found;
    }

    private static boolean contains(List<JsonNode> complete,List<JsonNode> fragment) {
        for(JsonNode row:fragment) {
            JsonNode target=target(complete,row);if(target==null)return false;
            Iterator<Map.Entry<String,JsonNode>> fields=row.fields();
            while(fields.hasNext()) {
                Map.Entry<String,JsonNode> field=fields.next();JsonNode actual=field.getValue();
                if(actual==null||actual.isNull()||actual.isTextual()&&actual.asText().trim().isEmpty())continue;
                if("scope".equals(field.getKey())&&genericFoundationScope(row))continue;
                JsonNode existing=target.get(field.getKey());if(existing==null||existing.isNull())return false;
                if(actual.isTextual()&&existing.isTextual()) {
                    if(!DraftEvidenceQuotes.textIdentity(actual.asText()).equals(DraftEvidenceQuotes.textIdentity(existing.asText())))return false;
                }else if(!actual.equals(existing))return false;
            }
        }
        return true;
    }

    private static boolean missingScopesCovered(List<JsonNode> complete,List<JsonNode> fragment,String quote,String fullQuote) {
        if(quote==null)return false;
        if(OTHER_PROJECT.matcher(quote).find()||OTHER_PROJECT.matcher(fullQuote).find())return false;
        boolean included=DraftEvidenceQuotes.present(fullQuote,quote);
        List<String> initialQualifiers=localQualifiers(quote);
        // "Complete" describes the declared work unit; a local unit cannot absorb whole-project evidence.
        for(String qualifier:localQualifiers(tablePreface(fullQuote))) {
            if(GENERIC_LOCATION.matcher(qualifier).matches())continue;
            String identity=DraftEvidenceQuotes.textIdentity(qualifier).toLowerCase(Locale.ROOT);
            if(initialQualifiers.stream().noneMatch(initial->DraftEvidenceQuotes.textIdentity(initial).toLowerCase(Locale.ROOT).equals(identity)))return false;
        }
        for(JsonNode row:fragment)if((scope(row).isEmpty()||genericFoundationScope(row))&&!scope(target(complete,row)).isEmpty()) {
            if(genericFoundationScope(row)&&!DraftEvidenceQuotes.present(quote,scope(row)))return false;
            if(!included&&!PROJECT_SCOPE.matcher(quote).find()&&!refinesInitialRows(fragment,quote,fullQuote))return false;
            String fullScope=scope(target(complete,row)).toLowerCase(Locale.ROOT);
            for(String qualifier:initialQualifiers)
                if(!GENERIC_LOCATION.matcher(qualifier).matches()&&!fullScope.contains(DraftEvidenceQuotes.textIdentity(qualifier).toLowerCase(Locale.ROOT)))return false;
        }
        return true;
    }

    private static List<String> localQualifiers(String quote) {
        List<String> result=new ArrayList<>();Matcher matcher=LOCAL_SCOPE.matcher(quote);
        while(matcher.find())result.add(matcher.group(1)==null?matcher.group(2):matcher.group(1));
        matcher=NAMED_SCOPE.matcher(quote);while(matcher.find())result.add(matcher.group(1));
        matcher=RESTRICTED_NAMED_SCOPE.matcher(quote);while(matcher.find())result.add(matcher.group(1));
        return result;
    }

    private static String tablePreface(String quote) {
        int first=quote.indexOf('|');
        if(first<0)return quote;
        int header=quote.lastIndexOf('\n',first);
        return header<0?"":quote.substring(0,header);
    }

    /** Explicit source reference to earlier initial entries plus unchanged assignments, not date precedence. */
    private static boolean refinesInitialRows(List<JsonNode> fragment,String quote,String fullQuote) {
        if(fragment.stream().anyMatch(row->!scope(row).isEmpty()))return false;
        Matcher initial=Pattern.compile("(?i)\\b(?:the\\s+)?([a-z-]+|\\d+)\\s+initial\\s+entries\\s+are\\s*:").matcher(quote);
        Matcher reference=Pattern.compile("(?i)\\bsupplies\\s+the\\s+work\\s+limits\\s+for\\s+the\\s+earlier\\s+([a-z-]+|\\d+)\\s+entries\\b").matcher(fullQuote);
        if(!initial.find()||!reference.find()||!Pattern.compile("(?i)\\bdoes\\s+not\\s+change\\s+their\\s+design\\s+or\\s+execution\\s+assignments\\b").matcher(fullQuote).find())return false;
        java.math.BigDecimal count=DraftNumberEvidence.literal(initial.group(1)),referenced=DraftNumberEvidence.literal(reference.group(1));
        return count!=null&&count.equals(referenced)&&count.compareTo(java.math.BigDecimal.valueOf(fragment.size()))==0;
    }

    private static boolean genericFoundationScope(JsonNode row) {
        return Arrays.asList("piling","pilecaps","footings").contains(row.path("component").asText())&&
                GENERIC_FOUNDATION_SCOPE.matcher(scope(row)).matches();
    }

    private static String scope(JsonNode row) {
        return row==null?"":DraftEvidenceQuotes.textIdentity(row.path("scope").asText(""));
    }
}
