package com.consense.service.drafting;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded field-specific checks; this does not certify general semantic entailment. */
final class DraftCandidateGrounding {
    private DraftCandidateGrounding() { }
    private static final String DATE_LITERAL="(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4}|\\d{1,2}(?:st|nd|rd|th)?\\s+[a-z]+\\s+\\d{4})";
    private static final String BILL_PASSAGES="\\R|\\||;|(?<=[.!?])\\s+(?=[A-Z])|:\\s*(?=(?i:is|are|does|do|please|whether)\\b)";

    static boolean con8Contradicted(Object value,String quote) {
        Pattern usage=Pattern.compile("(?i)\\b(?:volumetric precast(?: concrete)? components|CON8(?: components)?)\\s+((?:are|is)\\s+(?:not\\s+)?used|will\\s+(?:not\\s+)?be\\s+(?:not\\s+)?used)\\b");
        Boolean candidate=DraftBusinessRules.truth(value);
        boolean requested=false,answered=false;
        for(String passage:quote.split("\\R|\\||;|(?<=[.!?])\\s+(?=[A-Z])")) {
            if(!Pattern.compile("(?i)\\b(?:volumetric precast(?: concrete)? components|CON8)\\b").matcher(passage).find())continue;
            if(nonAnswer(passage)){requested=true;continue;}
            Matcher match=usage.matcher(passage);
            while(match.find()) {
                answered=true;
                if(candidate!=null&&candidate!=!Pattern.compile("(?i)\\bnot\\b").matcher(match.group(1)).find())return true;
            }
        }
        return requested&&!answered;
    }

    private static boolean nonAnswer(String passage) {
        return passage.contains("?")||Pattern.compile("(?i)\\b(?:please confirm|please provide|please advise|whether|to provide|to confirm|if any|pending|unknown|unconfirmed|undetermined|outstanding|not supplied|not provided)\\b").matcher(passage).find();
    }

    static boolean dateSupported(String key,String value,String quote) {
        for(String passage:canonical(quote).split("\\||;|(?<=[.!?])\\s+(?=[A-Z])"))
            if(!nonAnswer(passage)&&datePassageSupported(key,value,passage))return true;
        return false;
    }

    private static boolean datePassageSupported(String key,String value,String quote) {
        LocalDate candidate=date(value);
        if(candidate==null)return false;
        Matcher fullRange=Pattern.compile("(?i)("+DATE_LITERAL+")\\s+(?:and|to|until|[-–])\\s+("+DATE_LITERAL+")").matcher(quote);
        boolean ranged=false;
        while(fullRange.find()) {
            ranged=true;
            if(candidate.equals(date(fullRange.group("siteInspectionStartDate".equals(key)?1:2))))return true;
        }
        if(ranged)return false;
        // A range shares its month/year with its first endpoint in the actual SCT8 reply.
        Matcher range=Pattern.compile("(?i)\\b(?:between\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:and|to|[-–])\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]+)\\s+(\\d{4})\\b").matcher(quote);
        boolean sharedRange=false;
        while(range.find()) {
            sharedRange=true;
            String day="siteInspectionStartDate".equals(key)?range.group(1):range.group(2);
            if(candidate.equals(date(day+" "+range.group(3)+" "+range.group(4))))return true;
        }
        if(sharedRange)return false;
        Matcher labels=Pattern.compile("(?i)\\b(starts?(?:\\s+date)?|ends?(?:\\s+date)?)\\s*(?:[:=]|(?:on|is)\\b)?\\s*("+DATE_LITERAL+")").matcher(quote);
        boolean labelled=false;
        while(labels.find()) {
            labelled=true;
            if("siteInspectionStartDate".equals(key)==labels.group(1).toLowerCase(Locale.ROOT).startsWith("start")&&candidate.equals(date(labels.group(2))))return true;
        }
        if(labelled)return false;
        Matcher numeric=Pattern.compile("(?<!\\d)(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4})(?!\\d)").matcher(quote);
        while(numeric.find())if(candidate.equals(date(numeric.group())))return true;
        Matcher named=Pattern.compile("(?i)\\b\\d{1,2}(?:st|nd|rd|th)?\\s+[a-z]+\\s+\\d{4}\\b").matcher(quote);
        while(named.find())if(candidate.equals(date(named.group())))return true;
        return false;
    }

    static boolean phoneSupported(String value,String quote) {
        String digits=value.replaceAll("[^0-9]","");
        if(digits.length()<8||!value.matches("[+0-9()\\s.\\-/]+"))return false;
        for(String passage:canonical(quote).split("\\||;|(?<=[.!?])\\s+(?=[A-Z])")) {
            if(nonAnswer(passage))continue;
            Matcher numbers=Pattern.compile("(?<![a-zA-Z0-9])\\+?\\d[0-9()\\s.\\-/]*\\d(?!\\d)").matcher(passage);
            while(numbers.find())if(digits.equals(numbers.group().replaceAll("[^0-9]","")))return true;
        }
        return false;
    }

    static boolean directLiteralSupported(DraftBlueprint.InputSpec spec,Object value,String quote) {
        return directLiteralSupported(spec,value,quote,quote,quote);
    }

    static boolean directLiteralSupported(DraftBlueprint.InputSpec spec,Object value,String quote,String supplied,String originalSource) {
        if("specificationInspectionBlock".equals(spec.key)||"drawingsInspectionBlock".equals(spec.key))
            return DraftInspectionLocationEvidence.supported(spec.key,value,quote,supplied,originalSource);
        if("text".equals(spec.kind))return literalInAnswer(quote,value);
        if("billNos".equals(spec.key))return billIdentitiesSupported(value,quote);
        if("designResponsibilities".equals(spec.key))return DraftDesignEvidence.supported(value,quote);
        if("electronicTendering".equals(spec.key))return DraftTenderMediaEvidence.supported(value,quote,supplied,originalSource);
        if("footingsServeBuildingsOrMajorExternalStructures".equals(spec.key))return DraftScopeEvidence.footingsSupported(value,quote,supplied,originalSource);
        if("domesticBlocks".equals(spec.key))return DraftBooleanEvidence.domesticSupported(value,quote,supplied,originalSource);
        if("buildingDemolitionSitesSeparated".equals(spec.key))return DraftScopeEvidence.buildingDemolitionSeparationSupported(value,quote,supplied,originalSource);
        if("projectInTinShuiWai".equals(spec.key))return DraftScopeEvidence.tinShuiWaiSupported(value,quote,supplied,originalSource);
        if("sections".equals(spec.key))return DraftSectionEvidence.supported(value,quote);
        if("contract".equals(spec.kind)) {
            for(String key:Arrays.asList("number","title"))if(!literalInAnswer(quote,DraftBusinessRules.asMap(value).get(key)))return false;
        }
        if("number".equals(spec.kind))return DraftNumberEvidence.supported(spec.key,value,quote);
        return true;
    }

    private static boolean literalInAnswer(String quote,Object value) {
        return literalInAnswer(quote,value,"\\R|\\||;|(?<=[.!?])\\s+(?=[A-Z])");
    }

    private static boolean literalInAnswer(String quote,Object value,String separator) {
        if(value==null||String.valueOf(value).trim().isEmpty())return true;
        if(!literalPresent(quote,value))return false;
        // The whole literal must already occur contiguously; assess roles without joining source snippets.
        for(String fragment:String.valueOf(value).split(separator)) {
            if(fragment.trim().isEmpty())continue;
            boolean answered=false;
            for(String passage:quote.split(separator))if(!nonAnswer(passage)&&literalPresent(passage,fragment)){answered=true;break;}
            if(!answered)return false;
        }
        return true;
    }

    static boolean billIdentitiesSupported(Object value,String source) {
        for(Object item:DraftBusinessRules.list(value)) {
            Map<String,Object> row=DraftBusinessRules.asMap(item);
            Boolean tabular=DraftBillSourceTables.identitySupported(row,source);
            if(Boolean.FALSE.equals(tabular))return false;
            if(Boolean.TRUE.equals(tabular))continue;
            Object number=row.get("number"),description=row.get("description");
            if(!DraftBusinessRules.answered(number)) {
                if(!literalInAnswer(source,description,BILL_PASSAGES))return false;
                continue;
            }
            String literalNumber=Pattern.quote(canonical(String.valueOf(number)));
            Pattern identity=Pattern.compile("(?i)(?:^\\s*"+literalNumber+"(?:\\s*[|:]|\\s+)|\\bBills?[^:;\\r\\n]{0,60}:\\s*"+literalNumber+"\\s+|\\bBill\\s*(?:No\\.?\\s*)?"+literalNumber+"(?![\\p{L}\\p{N}]))");
            boolean found=false;
            // A following Bill starts a new entry, even when the parser puts both on one line.
            String separator="(?i)(?=\\bBill\\s*(?:No\\.?\\s*)?\\d+\\b)";
            if(Pattern.compile("(?i)\\bBills?\\s+(?:are\\s+)?(?:listed(?:\\s+below)?|list|schedule)\\s*[:=]").matcher(source).find())
                separator+="|;(?=\\s*\\d+\\s+)";
            for(String rawLine:source.split("\\R")) {
                String line=canonical(rawLine);int cursor=0;
                for(String entry:line.split(separator)) {
                    int position=line.indexOf(entry,cursor);cursor=position+entry.length();Matcher anchor=identity.matcher(entry);
                    if(anchor.find()&&answerAt(line,position+anchor.start())&&billDescriptionSlot(entry.substring(anchor.end()),description)&&literalInAnswer(entry,description,BILL_PASSAGES)) {
                        found=true;break;
                    }
                }
                if(found)break;
            }
            if(!found)return false;
        }
        return true;
    }

    /** A description occupies the name slot; a later qualification is not a new formal identity. */
    private static boolean billDescriptionSlot(String tail,Object description) {
        if(!DraftBusinessRules.answered(description))return true;
        String name=canonical(String.valueOf(description));
        String slot=canonical(tail).replaceFirst("^[|:=\\-]\\s*","");
        // Explicit naming statements are also valid, but 'being labelled/classified' alone is not a title.
        slot=slot.replaceFirst("(?i)^(?:(?:is\\s+)?(?:formally\\s+)?(?:named|titled|called)|(?:formal\\s+)?(?:description|title|name)\\s*(?:is|[:=]))\\s+","");
        if(slot.startsWith("\"")) {
            int end=slot.indexOf('"',1);
            return end>0&&slot.substring(1,end).equals(name);
        }
        if(slot.startsWith("'")) {
            int end=slot.indexOf('\'',1);
            return end>0&&slot.substring(1,end).equals(name);
        }
        int pipe=slot.indexOf('|');
        if(pipe>=0) {
            String cell=slot.substring(0,pipe).trim();
            return cell.equals(name)||trimNameEnd(cell).equals(name);
        }
        if(!slot.startsWith(name))return false;
        String remaining=slot.substring(name.length()).trim();
        return remaining.isEmpty()||remaining.matches("[.!]")
                ||remaining.matches("(?is)^(?:is|are|has|will)\\b.*")
                ||remaining.matches("(?is)^[:=]\\s*(?:type|purpose|trade|issue placement)\\s*[:=].*")
                ||remaining.matches("(?is)^:\\s*(?:is|are|does|do)\\b.*\\b(?:BQ|SOR|type|pricing|classification)\\b.*\\?");
    }

    private static String trimNameEnd(String text) {
        return text.trim().replaceFirst("[.!]$","").trim();
    }

    /** Evaluate the anchored occurrence in its original passage, preserving request prefixes before Bill markers. */
    private static boolean answerAt(String line,int position) {
        Matcher separators=Pattern.compile(BILL_PASSAGES).matcher(line);int start=0;
        while(separators.find()) {
            if(position<separators.start())return !nonAnswer(line.substring(start,separators.start()));
            start=separators.end();
        }
        return !nonAnswer(line.substring(start));
    }

    static boolean literalPresent(String source,Object value) {
        if(value==null||String.valueOf(value).trim().isEmpty())return true;
        String literal=canonical(String.valueOf(value));
        return Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(literal)+"(?![\\p{L}\\p{N}])").matcher(canonical(source)).find();
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replace('‘','\'').replace('’','\'').replace('“','"').replace('”','"')
                .replace('–','-').replace('—','-').replace('\u00a0',' ').trim().replaceAll("\\s+"," ");
    }

    private static LocalDate date(String text) {
        String normalized=text.trim().replaceAll("(?i)(\\d)(st|nd|rd|th)\\b","$1");
        for(String pattern:Arrays.asList("uuuu-M-d","uuuu/M/d","d/M/uuuu","d-M-uuuu","d.M.uuuu","d MMMM uuuu","d MMM uuuu")) {
            try{return LocalDate.parse(normalized,DateTimeFormatter.ofPattern(pattern,Locale.ENGLISH).withResolverStyle(java.time.format.ResolverStyle.STRICT));}
            catch(DateTimeParseException invalid){/* Try another documented literal representation. */}
        }
        return null;
    }
}
