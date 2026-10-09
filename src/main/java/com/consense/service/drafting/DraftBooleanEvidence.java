package com.consense.service.drafting;

import java.util.*;
import java.util.regex.*;

/** Direct, field-scoped statements. A formal Bill title does not assert a quantity classification. */
final class DraftBooleanEvidence {
    private DraftBooleanEvidence() { }
    private static final String RESIDENTIAL="(?:domestic|residential)\\s+(?:(?:domestic|residential|caretaker|staff|accommodation|housing)\\s+){0,4}(?:blocks?|buildings?)";
    private static final String PENDING="(?i)\\b(?:whether|please confirm|if|would|could|might|option|pending|unconfirmed|template|adjacent|neighbouring|neighboring|existing|proposed|do not (?:use|adopt|accept)|(?:another|other|different|previous|former)\\s+(?:project|contract))\\b";

    static boolean domesticSupported(Object value,String quote,String supplied,String original) {
        Boolean requested=DraftBusinessRules.truth(value);
        String scope=DraftScopeEvidence.assertedScope(quote,supplied,original,"(?:domestic|residential|housing|accommodation|blocks?)");
        return requested!=null&&scope!=null&&requested.equals(domesticScope(quote))&&requested.equals(domesticScope(scope));
    }

    static Boolean domesticScope(String quote) {
        Boolean supported=null;
        if(quote==null)return null;
        for(String passage:DraftScopeEvidence.sourcePassages(quote)) {
            String clause=passage.toLowerCase(Locale.ROOT);
            Matcher subject=Pattern.compile("\\b"+RESIDENTIAL+"\\b").matcher(clause);
            if(!subject.find()||clause.contains("?")||Pattern.compile(PENDING).matcher(clause).find())continue;
            String before=clause.substring(0,subject.start()),after=clause.substring(subject.end());
            boolean no=before.matches("(?s).*\\b(?:no|without|exclude(?:s|d)?|not\\s+(?:include|construct|comprise))\\s+(?:(?:the|a|an|any|one|new|attached)\\s+)*$")||
                    after.matches("(?s)^\\s*(?:[:|=]\\s*)?(?:(?:construction|works|scope)\\s+)?(?:(?:is|are|will be)\\s+)?(?:no|not\\s+(?:included|constructed|part of|within)|excluded|outside)\\b.*");
            boolean type=before.matches("(?s).*\\b(?:the\\s+)?(?:[a-z0-9-]+\\s+){0,4}(?:housing|accommodation|block|building)(?:\\s+[a-z0-9-]+)?\\s+(?:is|will\\s+be)\\s+(?:a|an)\\s+$");
            boolean construction=after.matches("(?s)^\\s+(?:uses?|will\\s+use)\\s+(?:(?!(?:not|no|never|without)\\b)[a-z-]+\\s+){0,6}construction\\s*(?:[.!]|$).*");
            boolean yes=clause.matches("(?s).*\\b(?:describes|includes?|comprises?|comprise|consists of|construction of|constructs?)\\b.*")||type||construction||
                    after.matches("(?s)^\\s+(?:construction\\s*)?[:|=]\\s*yes\\b.*");
            if(!no&&!yes)continue;
            boolean answer=!no;if(supported!=null&&supported!=answer)return null;supported=answer;
        }
        return supported;
    }

    static Boolean allBqQuantities(String quote) {
        Boolean supported=null;
        String subject="(?:all\\s+(?:the\\s+)?(?:BQ\\s+|Bill\\s+)?quantities(?:\\s+in\\s+(?:the\\s+)?(?:BQ\\s+Bills?|Bills?\\s+of\\s+Quantities|BQ))?|all\\s+(?:the\\s+)?BQ\\s+(?:Bill\\s+)?quantities|(?:the\\s+)?BQ\\s+(?:Bill\\s+)?quantities)";
        for(String clause:DraftScopeEvidence.sourcePassages(quote)) {
            if(clause.contains("?")||Pattern.compile("(?i)\\b(?:whether|please confirm|if|unless|except|other than|apart from|subject to|pending|unconfirmed|proposed|might|could|would|title|description|named|labelled|labeled|do not (?:use|adopt|accept)|(?:another|other|different|previous|former)\\s+(?:project|contract))\\b").matcher(clause).find())continue;
            Matcher statement=Pattern.compile("(?i)\\b(?:(not)\\s+)?("+subject+")\\s+(?:are|will\\s+be|shall\\s+be|:)\\s+(?:(not(?:\\s+all)?|all)\\s+)?(?:provisional|firm|fixed|final)\\b").matcher(clause);
            while(statement.find()) {
                boolean provisional=statement.group().toLowerCase(Locale.ROOT).endsWith("provisional");
                boolean scopedDenial=clause.substring(0,statement.start()).matches("(?is).*\\bit\\s+is\\s+not\\s+the\\s+case\\s+that\\s*$");
                // Mixed or nested negation needs a separate assertion; do not simplify it into an absence.
                if(scopedDenial&&(statement.group(1)!=null||statement.group(3)!=null&&statement.group(3).startsWith("not")))continue;
                boolean negative=statement.group(1)!=null||statement.group(3)!=null&&statement.group(3).startsWith("not")||scopedDenial;
                if(!provisional&&negative)continue;
                boolean answer=provisional&&!negative;
                if(supported!=null&&supported!=answer)return null;supported=answer;
            }
        }
        return supported;
    }

    static boolean allBqSupported(Object value,String quote,String supplied,String original) {
        Boolean requested=DraftBusinessRules.truth(value);
        String scope=DraftScopeEvidence.assertedScope(quote,supplied,original,"(?:BQ\\s+(?:Bill\\s+)?quantities|all\\s+(?:the\\s+)?quantities|quantities\\s+in\\s+(?:the\\s+)?(?:BQ|Bills?\\s+of\\s+Quantities)|BQ\\s+quantity\\s+(?:status|classification|decision))");
        return requested!=null&&scope!=null&&requested.equals(allBqQuantities(quote))&&requested.equals(allBqQuantities(scope));
    }
}
