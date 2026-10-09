package com.consense.service.drafting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project-contact projection only; raw evidence and manually stored text are unchanged. */
final class DraftArchitectContactEvidence {
    private DraftArchitectContactEvidence() { }
    private static final String TITLES="(?:Mr|Mrs|Ms|Miss|Dr|Ir|Ar|Prof|Professor)";
    private static final Pattern PREFIX=Pattern.compile("(?i)^\\s*("+TITLES+")\\.?\\s+(.+)$");
    private static final Pattern TITLE=Pattern.compile("(?i)^"+TITLES+"\\.?$");
    private static final Pattern LEGAL=Pattern.compile("(?i)\\b(?:registered|legal)\\s+name\\b");
    private static final Pattern OTHER_PERSON=Pattern.compile("(?i)\\b(?:contractor|employer|engineer|subcontractor|quantity surveyor|another person)\\b");
    private static final String PASSAGES="\\R|;|(?<!Dr\\.)(?<!Mr\\.)(?<!Ms\\.)(?<!Ir\\.)(?<!Ar\\.)(?<!Prof\\.)(?<=[.!?])\\s+(?=[A-Z])";
    private static final Pattern FOREIGN=Pattern.compile("(?i)\\b(?:another|other|different|previous|former|neighbouring|neighboring)\\s+(?:project|contract)\\b");
    private static final Pattern UNSETTLED=Pattern.compile("(?i)\\b(?:please (?:confirm|provide|advise)|whether|unconfirmed|pending|proposed|suggested|tentative|subject to|to be confirmed|not (?:yet )?(?:confirmed|appointed|adopted|approved)|do not (?:use|adopt|accept))\\b|\\?");
    private static final Pattern DEPENDENT=Pattern.compile("(?i)^\\s*(?:this|that|the above)(?:\\s+(?:appointment|contact|name|title|salutation|decision|instruction))?\\s+(?:is|remains|has|will)\\b");
    private static final Pattern CURRENT=Pattern.compile("(?i)(?:for\\s+)?(?:this|current|our)\\s+(?:project|contract)\\s*:");

    static String suggestion(String key,String value,String quote) {
        return suggestion(key,value,quote,quote,quote);
    }
    static String suggestion(String key,String value,String quote,String supplied,String original) {
        if(value==null||quote==null)return value;
        String scope=scope(quote,supplied,original);if(scope==null)return value;
        if("projectArchitectName".equals(key)) {
            Matcher prefix=PREFIX.matcher(value);if(!prefix.matches())return value;
            Pattern contact=Pattern.compile("(?i)\\b(?:the\\s+)?Project\\s+Architect(?:'s)?(?:\\s+name)?\\s*(?::|=|is|will\\s+be)\\s*"+Pattern.quote(value)+"(?=$|[\\s,.;])");
            Matcher role=contact.matcher(quote);
            if(!role.find())return value;
            // Qualifications in the same source paragraph still govern a short contact quote.
            if(legalNameScope(scope)||!supported(key,quote,supplied,original))return value;
            return prefix.group(2);
        }
        if("projectArchitectOtherTitle".equals(key)&&TITLE.matcher(value).matches()&&value.endsWith(".")) {
            if(!supported(key,quote,supplied,original))return value;
            Pattern explicit=Pattern.compile("(?i)\\b(?:title|salutation)(?:\\s+to\\s+print(?:\\s+in\\s+its\\s+detail)?)?\\s*(?::|=|is)\\s*"+Pattern.quote(value)+"(?=$|[\\s,.;])");
            if(explicit.matcher(quote).find())return value.substring(0,value.length()-1);
        }
        return value;
    }

    /** Contact evidence must retain its original governing unit, even when the quote is short. */
    static boolean supported(String key,String quote,String supplied,String original) {
        if(!key.startsWith("projectArchitect"))return true;
        String scope=scope(quote,supplied,original);if(scope==null)return false;
        String subject="projectArchitectPhone".equals(key)?"(?:telephone|phone|contact|appointment)":
                "projectArchitectPost".equals(key)?"(?:post|position|appointment|contact)":
                "projectArchitectName".equals(key)?"(?:name|appointment|contact|Project Architect)":"(?:title|salutation|appointment|contact)";
        Pattern field=Pattern.compile("(?i)\\b"+subject+"\\b");
        String anchor=DraftEvidenceQuotes.textIdentity(quote);
        for(String passage:scope.split(PASSAGES)) {
            String normalized=DraftEvidenceQuotes.textIdentity(passage);
            boolean governs=passage.trim().endsWith(":")||normalized.contains(anchor)||
                    Pattern.compile("(?i)^\\s*for\\s+(?:another|other|different|previous|former)\\s+(?:project|contract)\\b").matcher(passage).find();
            if(FOREIGN.matcher(passage).find()&&governs)return false;
            boolean unrelatedContact="projectArchitectName".equals(key)&&
                    Pattern.compile("(?i)\\b(?:telephone|phone|salutation|title|post|position)\\b").matcher(passage).find()&&
                    !Pattern.compile("(?i)\\b(?:name|appointment)\\b").matcher(passage).find();
            if(UNSETTLED.matcher(passage).find()&&!unrelatedContact&&(field.matcher(passage).find()||DEPENDENT.matcher(passage).find()||passage.trim().endsWith(":")))return false;
        }
        return true;
    }
    private static boolean legalNameScope(String scope) {
        for(String passage:scope.split(PASSAGES))if(LEGAL.matcher(passage).find()&&!OTHER_PERSON.matcher(passage).find())return true;
        return false;
    }
    private static String scope(String quote,String supplied,String original) {
        if(quote==null||supplied==null||original==null||quote.trim().isEmpty())return null;
        String anchor=DraftEvidenceQuotes.textIdentity(quote),found=null;
        String[] paragraphs=original.split("\\R[\\t ]*\\R");
        for(int i=0;i<paragraphs.length;i++) {
            String paragraph=paragraphs[i],normalized=DraftEvidenceQuotes.textIdentity(paragraph);
            int position=normalized.indexOf(anchor);if(position<0)continue;
            if(found!=null||normalized.indexOf(anchor,position+anchor.length())>=0)return null;
            if(!DraftEvidenceQuotes.present(supplied,paragraph))return null;
            String unit=paragraph;
            for(int before=i-1;before>=0&&(paragraphs[before].trim().endsWith(":")||
                    legalNameScope(paragraphs[before])&&Pattern.compile("(?i)^\\s*(?:use|retain|preserve|print)\\b").matcher(paragraphs[before]).find());before--) {
                unit=paragraphs[before]+"\n\n"+unit;
            }
            for(int after=i+1;after<paragraphs.length&&DEPENDENT.matcher(paragraphs[after]).find();after++)unit+="\n\n"+paragraphs[after];
            if(!DraftEvidenceQuotes.present(supplied,unit))return null;
            // A current-project heading explicitly starts a new source role within this unit.
            Matcher reset=CURRENT.matcher(unit);int start=0;
            while(reset.find())if(DraftEvidenceQuotes.present(unit.substring(reset.end()),quote)&&
                    !UNSETTLED.matcher(unit.substring(0,reset.start())).find()&&
                    !Pattern.compile("(?i)\\b(?:registered|legal)\\s+name\\b").matcher(unit.substring(0,reset.start())).find())start=reset.end();
            found=unit.substring(start);
        }
        return found;
    }

    /** Omit an already printed matching honorific, rather than rewriting the adopted name. */
    static String renderedName(String title,String name) {
        if(title==null||name==null)return String.valueOf(title)+" "+String.valueOf(name);
        Matcher prefix=PREFIX.matcher(name);
        if(TITLE.matcher(title).matches()&&prefix.matches()&&canonical(title).equals(canonical(prefix.group(1))))return name;
        return title+" "+name;
    }
    private static String canonical(String title){return title.replaceFirst("\\.$", "").toLowerCase(java.util.Locale.ROOT);}
}
