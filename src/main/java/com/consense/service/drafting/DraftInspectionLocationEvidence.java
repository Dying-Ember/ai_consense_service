package com.consense.service.drafting;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inspection places bind to their document and complete identifier, not another document's address. */
final class DraftInspectionLocationEvidence {
    private static final String PASSAGES="\\R|;|(?<=[.!?])\\s+(?=[A-Z])";
    private static final Pattern INSPECTION=Pattern.compile("(?i)\\b(?:inspect(?:ed|ion|ing)?|view(?:ed|ing)?)\\b");
    private static final Pattern AVAILABLE=Pattern.compile("(?i)\\b(?:specifications?|drawings?)\\s+(?:are|is|will\\s+be)\\s+(?:made\\s+)?available\\s+(?:at|in|on|within)\\b");
    private static final Pattern FIELD=Pattern.compile("(?i)\\b(?:specifications?|drawings?)\\b");
    private static final Pattern UNSETTLED=Pattern.compile("(?i)\\b(?:please (?:confirm|provide|advise)|whether|pending|unknown|unconfirmed|proposed|unselected|awaiting|to be confirmed|if|unless|subject to|not (?:yet )?(?:confirmed|adopted|approved|selected)|do not (?:use|adopt|apply))\\b|\\?");
    private static final Pattern OTHER_PROJECT=Pattern.compile("(?i)\\b(?:another|other|different|previous|former|neighbouring|neighboring)\\s+(?:project|contract)\\b");
    private static final Pattern PREFIX_CONTROL=Pattern.compile("(?i)^\\s*(?:please\\b|if\\b|unless\\b|proposed\\b|pending\\b|subject to\\b|do not\\b|for (?:another|other|different|previous|former) (?:project|contract)\\b)");
    private static final Pattern DEPENDENT=Pattern.compile("(?i)^\\s*(?:this|that|the above)(?:\\s+(?:inspection\\s+)?(?:location|block|decision|arrangement))?\\s+(?:is|remains|has|will)\\b");
    private static final Pattern NEGATIVE=Pattern.compile("(?i)\\b(?:not|never|no longer)\\s+(?:be\\s+)?(?:inspect(?:ed|ion)?|available|located|kept|used|at|in)\\b|\\b(?:inspection (?:location|block)|block)\\s+(?:is|will be)\\s+not\\b|\\bno\\s+(?:specifications?|drawings?)\\s+inspection\\b");
    private static final Pattern NEW_SUBJECT=Pattern.compile("(?i)\\s*(?:,\\s*)?(?:and|but|whereas|while)\\s+(?=(?:the\\s+)?(?:specifications?|drawings?|roofing|acrylic|warranty|contractor|architect|employer|engineer|office)\\b)");
    private DraftInspectionLocationEvidence() { }

    static boolean supported(String key,Object value,String quote,String supplied,String original) {
        if(!(value instanceof String))return false;
        String identifier=DraftInputRules.blockIdentifier((String)value);
        if(identifier==null||identifier.isEmpty())return false;
        String scope=anchoredScope(key,quote,supplied,original);
        return scope!=null&&claimSupported(key,identifier,quote,scope)&&claimSupported(key,identifier,scope,scope);
    }

    private static boolean claimSupported(String key,String identifier,String text,String scope) {
        Pattern document=Pattern.compile("(?i)\\b"+("specificationInspectionBlock".equals(key)?"specifications?":"drawings?")+"\\b");
        String literal="(?<![\\p{L}\\p{N}._-])"+Pattern.quote(identifier)+"(?![\\p{L}\\p{N}_-]|\\.[\\p{L}\\p{N}])";
        Pattern location=Pattern.compile("(?i)(?:\\bblock\\s+(?:(?:is|[:=])\\s*)?|\\bblock\\s*[:=]\\s*|\\b(?:at|in|within)\\s+(?:the\\s+)?(?:block\\s+)?)"+literal);
        boolean supported=false;
        for(String passage:text.split(PASSAGES))for(String clause:clauses(passage)) {
            if(!FIELD.matcher(clause).find()) {
                if(PREFIX_CONTROL.matcher(clause).find()||DEPENDENT.matcher(clause).find()&&UNSETTLED.matcher(clause).find())return false;
                continue;
            }
            if(!document.matcher(clause).find()||OTHER_PROJECT.matcher(clause).find())continue;
            if(!INSPECTION.matcher(clause).find()&&!availabilityHasInspectionRole(clause,scope))continue;
            if(UNSETTLED.matcher(clause).find()||NEGATIVE.matcher(clause).find())return false;
            if(location.matcher(clause).find())supported=true;
            else if(Pattern.compile("(?i)\\bblock\\b").matcher(clause).find())return false;
        }
        return supported;
    }

    /** A new explicit subject owns its clause; a joint document subject keeps one shared location. */
    private static List<String> clauses(String passage) {
        List<String> result=new ArrayList<>();Matcher split=NEW_SUBJECT.matcher(passage);int start=0;
        while(split.find()) {
            String preceding=passage.substring(start,split.start());
            if(!accessAsserted(preceding))continue;
            result.add(preceding);start=split.end();
        }
        result.add(passage.substring(start));return result;
    }

    private static boolean accessAsserted(String clause) {
        return INSPECTION.matcher(clause).find()||AVAILABLE.matcher(clause).find();
    }

    /** Availability inherits inspection access only through the same explicit office antecedent. */
    private static boolean availabilityHasInspectionRole(String clause,String scope) {
        if(!AVAILABLE.matcher(clause).find()||!Pattern.compile("(?i)\\b(?:that|the same)\\s+(?:inspection\\s+)?office\\b").matcher(clause).find()||
                Pattern.compile("(?i)\\b(?:shop|fabrication|production|construction)\\s+drawings?\\b|\\bfor\\s+(?:fabrication|production|construction)\\b").matcher(clause).find())return false;
        String previous=null,anchor=DraftEvidenceQuotes.textIdentity(clause);
        for(String passage:scope.split(PASSAGES))for(String item:clauses(passage)) {
            if(DraftEvidenceQuotes.textIdentity(item).equals(anchor))
                return previous!=null&&INSPECTION.matcher(previous).find()&&FIELD.matcher(previous).find()&&
                        Pattern.compile("(?i)\\boffice\\b").matcher(previous).find()&&!UNSETTLED.matcher(previous).find()&&
                        !NEGATIVE.matcher(previous).find()&&!OTHER_PROJECT.matcher(previous).find();
            if(!item.trim().isEmpty())previous=item;
        }
        return false;
    }

    /** Do not let a citation discard an unseen request prefix or a dependent location qualifier. */
    private static String anchoredScope(String key,String quote,String supplied,String original) {
        if(quote==null||supplied==null||original==null||quote.trim().isEmpty())return null;
        if(DraftScopeEvidence.assertedScope(quote,supplied,original,"specificationInspectionBlock".equals(key)?"specifications?":"drawings?")==null)return null;
        String anchor=DraftEvidenceQuotes.textIdentity(quote),scope=null;
        String[] paragraphs=original.split("\\R[\\t ]*\\R");
        for(int index=0;index<paragraphs.length;index++) {
            String paragraph=paragraphs[index],normalized=DraftEvidenceQuotes.textIdentity(paragraph);int position=normalized.indexOf(anchor);
            if(position<0)continue;
            if(scope!=null||normalized.indexOf(anchor,position+anchor.length())>=0)return null;
            if(!DraftEvidenceQuotes.present(supplied,paragraph))return null;
            scope=paragraph;
            for(int following=index+1;following<paragraphs.length&&DEPENDENT.matcher(paragraphs[following]).find();following++) {
                scope+="\n\n"+paragraphs[following];
                if(!DraftEvidenceQuotes.present(supplied,scope))return null;
            }
        }
        return scope;
    }
}
