package com.consense.service.drafting;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded BQ issue/preparation evidence; SOR issue and tender return are separate roles. */
final class DraftTenderMediaEvidence {
    private DraftTenderMediaEvidence() { }
    private static final String BQ="(?:BQ|bills?\\s+of\\s+quantities)";
    private static final String MODE="(?:L10Pro|hard[- ]?copy|paper|printed\\s+originals?)";
    private static final String MEDIA_DOCUMENT=MODE+"\\s+(?:(?:reference|convenience)\\s+cop(?:y|ies)|(?:tender\\s+)?documents?|cop(?:y|ies))";
    private static final String ACTOR="(?:(?:the|all|both)\\s+)?(?:"+BQ+"|SOR|schedules?\\s+of\\s+rates|tender(?:ing)?\\s+(?:documents?|returns?|preparation)|tenderers?|returns?|drawings?|addenda|correspondence|photocop(?:ies|y)|"+MODE+"\\s+(?:electronic\\s+)?issue|"+MEDIA_DOCUMENT+")\\b";
    private static final Pattern CLAUSES=Pattern.compile("(?i)(?:;|,?\\s+(?:and|whereas|while|but|with|including))\\s+(?="+ACTOR+")");
    private static final Pattern UNASSERTED=Pattern.compile("(?i)\\?|\\b(?:please\\s+(?:confirm|provide|advise)|whether|pending|unknown|unconfirmed|undetermined|outstanding|not\\s+(?:supplied|provided|confirmed|adopted|selected)|proposed|if|unless|subject\\s+to|may|might)\\b");
    private static final Pattern OTHER_PROJECT=Pattern.compile("(?i)\\b(?:another|other|previous|adjacent|neighbouring)\\s+(?:project|contract|tender)\\b");
    private static final Pattern RETURN=Pattern.compile("(?i)\\b(?:return(?:ed|ing)?|submit(?:ted|ting)?|submission|deliver(?:ed|y)?)\\b");
    private static final Pattern ISSUE=Pattern.compile("(?i)\\b(?:issue(?:d)?|issuance|preparation|pricing|price)\\b");
    private static final Pattern NARROW=Pattern.compile("(?i)\\b(?:SOR|schedules?\\s+of\\s+rates|tender\\s+drawings?|addenda|correspondence|photocop(?:ies|y)|reference\\s+(?:PDF|cop(?:y|ies)|sets?|documents?)|convenience\\s+cop(?:y|ies))\\b");
    private static final Pattern BQ_EXCLUSION=Pattern.compile("(?i)\\b(?:except|excluding|without|but\\s+not)\\s+(?:the\\s+)?"+BQ+"\\b");
    private static final Pattern BQ_ROLE=Pattern.compile("(?i)\\b"+BQ+"\\b[^.;|\\r\\n]{0,90}\\b(?:issue(?:d)?|issuance|format|preparation|pricing)\\b");
    private static final Pattern ISSUE_FORMAT_OBJECT=Pattern.compile("(?i)\\b(?:electronic\\s+)?issue\\s+format\\s+for\\s+(?:the\\s+)?"+BQ+"\\b");
    private static final Pattern BILLS_FORMAT_OBJECT=Pattern.compile("(?i)\\b(?:electronic\\s+)?issue\\s+format\\s+for\\s+(?:the\\s+)?Bills\\b");
    private static final Pattern BILLS_BQ_SCOPE=Pattern.compile("(?i)^(?:(?:the\\s+)?(?:detailed\\s+)?"+BQ+"(?:\\s+and\\s+(?:SOR|schedules?\\s+of\\s+rates))?\\s+pricing\\s+files\\s+(?:are|will\\s+be|shall\\s+be)\\s+(?:supplied|issued)\\b|(?:the\\s+)?Bills\\s+(?:include|comprise)\\s+(?:the\\s+)?"+BQ+"\\b)");
    private static final Pattern NONADOPTED_SCOPE=Pattern.compile("(?i)\\b(?:for\\s+reference|reference\\s+only|for\\s+convenience|unselected|samples?|examples?|specimens?|illustrative|drafts?)\\b");
    private static final Pattern BILLS_EXCLUDE_BQ=Pattern.compile("(?i)\\b(?:(?:these|the)\\s+)?(?:adopted\\s+)?Bills\\s+(?:comprise|include|contain|cover|are)\\s+(?:only\\s+(?:SOR|schedules?\\s+of\\s+rates)|(?:SOR|schedules?\\s+of\\s+rates)\\s+only)\\b");
    private static final Pattern WHOLE_ISSUE=Pattern.compile("(?i)\\b(?:all\\s+)?tender\\s+documents\\b[^.;|\\r\\n]{0,60}\\b(?:issue(?:d)?|issuance)\\b");
    private static final Pattern WORKFLOW=Pattern.compile("(?i)\\btendering\\s+preparation\\s+option\\b|\\bselect\\s+(?:the\\s+)?L10Pro\\s+option\\s+for\\s+the\\s+preparation\\s+and\\s+pricing\\s+workflow\\b");
    private static final Pattern SELECT_WORKFLOW=Pattern.compile("(?i)\\bselect\\s+(?:the\\s+)?L10Pro\\s+option\\s+for\\s+the\\s+preparation\\s+and\\s+pricing\\s+workflow\\b");
    private static final Pattern REVERSE_ISSUE=Pattern.compile("(?i)\\b"+MODE+"\\s+(?:electronic\\s+)?issue(?:\\s+and\\s+pricing)?\\b");
    private static final Pattern NEGATIVE=Pattern.compile("(?i)\\b(?:not|no|never)\\b");
    private static final Pattern RETURN_INDEPENDENCE=Pattern.compile("(?i)\\bdo(?:es)?\\s+not\\s+mean\\b[^.;\\r\\n]*\\btender\\s+return\\b");
    private static final Pattern HARDCOPY=Pattern.compile("(?i)\\b(?:hard[- ]?copy|paper|printed\\s+originals?)\\b");
    private static final Pattern ELECTRONIC=Pattern.compile("(?i)\\bL10Pro\\b");
    private static final Pattern NOT_MODE_BUT_MODE=Pattern.compile("(?i)\\bnot\\s+(?:in\\s+)?("+MODE+")\\s*,?\\s+but\\s+(?:in\\s+)?("+MODE+")\\b");
    private static final Pattern DEPENDENT_UNASSERTED=Pattern.compile("(?i)(?:^|[.;!?]\\s+)(?:(?:this|that)(?:\\s+(?:issue\\s+)?(?:format|decision))?|the\\s+(?:BQ\\s+)?(?:issue\\s+)?(?:format|decision))\\s+(?:is|remains)\\s+(?:pending|unknown|unconfirmed|undetermined|not\\s+(?:confirmed|adopted|selected))\\b");

    static boolean supported(Object value,String quote) {
        return supported(value,quote,false);
    }

    /** Resolves a bare Bills object only within its quoted original paragraph and visible adjacent scope sentence. */
    static boolean supported(Object value,String quote,String supplied,String original) {
        if(supported(value,quote))return true;
        String scope=billsScope(quote,supplied,original);
        return scope!=null&&supported(value,scope,true);
    }

    private static boolean supported(Object value,String quote,boolean resolvedBills) {
        if(value==null||quote==null)return false;
        String candidate=String.valueOf(value);
        if(!"Hardcopy".equals(candidate)&&!"L10Pro".equals(candidate))return false;
        if(DEPENDENT_UNASSERTED.matcher(quote).find())return false;
        Set<String> positive=new HashSet<>(),negative=new HashSet<>();
        // Preserve table rows and independent assertions. Do not pool media across their subjects.
        for(String sentence:quote.split("\\R|(?<=[.!?])\\s+")) {
            for(String fragment:CLAUSES.split(sentence)) {
                String clause=canonical(fragment);
                if(UNASSERTED.matcher(clause).find()||OTHER_PROJECT.matcher(clause).find()||BQ_EXCLUSION.matcher(clause).find())continue;
                java.util.regex.Matcher bqRole=BQ_ROLE.matcher(clause);
                boolean bq=bqRole.find();
                if(!bq) {bqRole=ISSUE_FORMAT_OBJECT.matcher(clause);bq=bqRole.find();}
                if(bq) {
                    String beforeBq=clause.substring(0,bqRole.start());
                    // A reference to BQ inside a statement about a different document is not its issue assertion.
                    boolean jointSubject=beforeBq.matches("(?is).*\\band\\s+(?:the\\s+)?$")&&!ISSUE.matcher(beforeBq).find();
                    if(NARROW.matcher(beforeBq).find()&&!jointSubject)bq=false;
                }
                boolean narrower=NARROW.matcher(clause).find();
                boolean role=bq||!narrower&&(WHOLE_ISSUE.matcher(clause).find()||WORKFLOW.matcher(clause).find()||REVERSE_ISSUE.matcher(clause).find()||resolvedBills&&BILLS_FORMAT_OBJECT.matcher(clause).find());
                if(!role)continue;
                // A BQ return label does not assert issue; the retained legacy clarification explicitly asserts issue independence.
                String assertion=RETURN_INDEPENDENCE.matcher(clause).replaceAll("");
                java.util.regex.Matcher selection=SELECT_WORKFLOW.matcher(assertion);
                if(selection.find())assertion=assertion.substring(selection.start());
                if(RETURN.matcher(assertion).find()&&!ISSUE.matcher(assertion).find())continue;
                // 'Not A but B' negates only A and explicitly adopts B for this already-bound issue role.
                java.util.regex.Matcher alternative=NOT_MODE_BUT_MODE.matcher(assertion);
                while(alternative.find()) {
                    negative.add(media(alternative.group(1)));positive.add(media(alternative.group(2)));
                }
                assertion=alternative.replaceAll("");
                Set<String> modes=new HashSet<>();
                if(HARDCOPY.matcher(assertion).find())modes.add("Hardcopy");
                if(ELECTRONIC.matcher(assertion).find())modes.add("L10Pro");
                (NEGATIVE.matcher(assertion).find()?negative:positive).addAll(modes);
            }
        }
        return positive.size()==1&&positive.contains(candidate)&&!negative.contains(candidate);
    }

    private static String billsScope(String quote,String supplied,String original) {
        if(quote==null||supplied==null||original==null||!BILLS_FORMAT_OBJECT.matcher(quote).find())return null;
        String anchor=canonical(quote),scope=null;
        for(String paragraph:original.split("\\R[\\t ]*\\R")) {
            String normalized=canonical(paragraph);
            int position=normalized.indexOf(anchor);
            if(position<0)continue;
            // Duplicate anchors cannot borrow one convenient scope from several possible source locations.
            if(scope!=null||normalized.indexOf(anchor,position+anchor.length())>=0)return null;
            scope=paragraph;
        }
        if(scope==null||!DraftEvidenceQuotes.present(supplied,scope)||BQ_EXCLUSION.matcher(scope).find()||BILLS_EXCLUDE_BQ.matcher(scope).find())return null;
        for(String line:scope.split("\\R")) {
            String[] sentences=line.split("(?<=[.!?])[\\t ]+");
            for(int index=0;index+1<sentences.length;index++) {
                String statement=canonical(sentences[index]),next=canonical(sentences[index+1]);
                if(!BILLS_FORMAT_OBJECT.matcher(statement).find()||!anchor.contains(statement))continue;
                if(!BILLS_BQ_SCOPE.matcher(next).find()||UNASSERTED.matcher(next).find()||NONADOPTED_SCOPE.matcher(next).find()||
                        OTHER_PROJECT.matcher(next).find()||RETURN.matcher(next).find()||NEGATIVE.matcher(next).find())continue;
                return scope;
            }
        }
        return null;
    }

    private static String media(String literal) {return ELECTRONIC.matcher(literal).find()?"L10Pro":"Hardcopy";}

    private static String canonical(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFKC).replace('\u00a0',' ').trim().replaceAll("\\s+"," ");
    }
}
