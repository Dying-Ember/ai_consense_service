package com.consense.service.drafting;

import com.consense.web.dto.DraftingDtos.ExtractionContextVO;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Original-source windows. Core chunks remain stable; context is never synthesized. */
final class DraftSourceContext {
    static final int WINDOW_BUDGET=6000;
    private static final int EDGE_BUDGET=1500;
    private DraftSourceContext() { }

    static ExtractionContextVO primary(String source,int start,int end) {
        int left=start,right=end;boolean blocked=false;
        int paragraphStart=source.lastIndexOf('\n',Math.max(0,start-1))+1;
        int next=source.indexOf('\n',end),paragraphEnd=next<0?source.length():next+1;
        if(start>0&&source.charAt(start-1)!='\n') {
            if(end-paragraphStart<=WINDOW_BUDGET)left=paragraphStart;else blocked=true;
        }
        if(end<source.length()&&source.charAt(end-1)!='\n') {
            if(paragraphEnd-left<=WINDOW_BUDGET)right=paragraphEnd;else blocked=true;
        }
        // Complete the paragraph cut by the core boundary, then preserve its nearby heading/instruction.
        for(int i=0,nonblank=0;i<8&&nonblank<3&&left>0;i++) {
            int candidate=source.lastIndexOf('\n',Math.max(0,left-2))+1;
            if(start-candidate>EDGE_BUDGET||right-candidate>WINDOW_BUDGET)break;
            String previous=source.substring(candidate,left).trim();left=candidate;
            if(!previous.isEmpty()){nonblank++;if(heading(previous))break;}
        }
        for(int i=0,nonblank=0;i<6&&nonblank<2&&right<source.length();i++) {
            int newline=source.indexOf('\n',right);int candidate=newline<0?source.length():newline+1;
            String following=source.substring(right,candidate).trim();
            if(heading(following)&&nonblank>0)break;
            if(candidate-end>EDGE_BUDGET||candidate-left>WINDOW_BUDGET){blocked|=nonblank==0;break;}
            right=candidate;if(!following.isEmpty())nonblank++;
        }
        // A parsed table is a contiguous run of header/rows; all rows travel with the header if the unit fits.
        int tableStart=-1,tableEnd=-1,offset=0;
        for(String line:source.split("\\n",-1)) {
            int lineEnd=Math.min(source.length(),offset+line.length()+1);
            if(line.contains("|")) {if(tableStart<0)tableStart=offset;tableEnd=lineEnd;}
            else if(!line.trim().isEmpty()||lineEnd==source.length()) {
                if(tableStart>=0&&tableStart<end&&tableEnd>start) {
                    if(Math.max(right,tableEnd)-Math.min(left,tableStart)<=WINDOW_BUDGET){left=Math.min(left,tableStart);right=Math.max(right,tableEnd);}
                    else blocked=true;
                }
                tableStart=-1;tableEnd=-1;
            }
            offset=lineEnd;
        }
        if(tableStart>=0&&tableStart<end&&tableEnd>start) {
            if(Math.max(right,tableEnd)-Math.min(left,tableStart)<=WINDOW_BUDGET){left=Math.min(left,tableStart);right=Math.max(right,tableEnd);}else blocked=true;
        }
        boolean incomplete=blocked||(left>0&&source.charAt(left-1)!='\n')||(right<source.length()&&source.charAt(right-1)!='\n');
        return new ExtractionContextVO("original_neighbors",Collections.emptyList(),left,right,source.substring(left,right),incomplete?"context_insufficient":null);
    }

    static List<Integer> cues(String text,DraftBlueprint.InputSpec spec) {
        List<Integer> result=new ArrayList<>();
        List<String> words=new ArrayList<>();
        for(String token:spec.labelEn.split("[^A-Za-z0-9]+")) {
            String word=token.toLowerCase(Locale.ROOT);
            // Short domain acronyms in the catalogue meaning are routing terms, never answer evidence.
            boolean acronym=token.matches("[A-Z][A-Z0-9]{1,}");
            if((word.length()>3||acronym)&&!Arrays.asList("project","contract","works","this","under","with","adopted","current","includes","included").contains(word)&&!words.contains(word))words.add(word);
        }
        int offset=0;
        for(String line:text.split("\\n",-1)) {
            String lower=line.toLowerCase(Locale.ROOT);int matches=0;
            for(String word:words)if(Pattern.compile("\\b"+Pattern.quote(word)+"\\b").matcher(lower).find())matches++;
            if(matches>=2||lower.contains(spec.key.toLowerCase(Locale.ROOT)))result.add(offset);
            offset+=line.length()+1;
        }
        return result;
    }

    /** Structural completeness check, using original offsets only; no semantic truth classifier. */
    static boolean completeQuote(String original,ExtractionContextVO context,String quote,String kind) {
        if(original==null||quote==null||!Arrays.asList("text","list","multiselect").contains(kind))return true;
        String supplied=context.getSourceText();StringBuilder canonical=new StringBuilder();List<Integer> offsets=new ArrayList<>();boolean space=false;
        for(int i=0;i<supplied.length();i++) {
            char character=supplied.charAt(i);
            if(Character.isWhitespace(character)||character=='\u00a0'){space=canonical.length()>0;continue;}
            if(space){canonical.append(' ');offsets.add(i);space=false;}
            canonical.append(typography(character));offsets.add(i);
        }
        String normalized=quote.replace('\u201c','"').replace('\u201d','"').replace('\u2018','\'').replace('\u2019','\'').replace('\u00a0',' ').trim().replaceAll("\\s+"," ");
        int at=canonical.indexOf(normalized);if(at<0||normalized.isEmpty())return true; // Existing quote intake handles absent quotes.
        int quoteStart=context.getSourceStart()+offsets.get(at),quoteEnd=context.getSourceStart()+offsets.get(at+normalized.length()-1)+1;
        int paragraphStart=original.lastIndexOf('\n',Math.max(0,quoteStart-1))+1;
        int next=original.indexOf('\n',quoteEnd),paragraphEnd=next<0?original.length():next;
        if(paragraphStart<context.getSourceStart()||paragraphEnd>context.getSourceEnd())return false;
        int tableStart=-1,tableEnd=-1,offset=0;
        for(String line:original.split("\\n",-1)) {
            int lineEnd=Math.min(original.length(),offset+line.length()+1);
            if(line.contains("|")){if(tableStart<0)tableStart=offset;tableEnd=lineEnd;}
            else if(!line.trim().isEmpty()||lineEnd==original.length()) {
                if(tableStart>=0&&tableStart<quoteEnd&&tableEnd>quoteStart&&(tableStart<context.getSourceStart()||tableEnd>context.getSourceEnd()))return false;
                tableStart=-1;tableEnd=-1;
            }
            offset=lineEnd;
        }
        if(tableStart>=0&&tableStart<quoteEnd&&tableEnd>quoteStart&&(tableStart<context.getSourceStart()||tableEnd>context.getSourceEnd()))return false;
        return true;
    }

    private static char typography(char value) {
        return value=='\u201c'||value=='\u201d'?'"':value=='\u2018'||value=='\u2019'?'\'':value;
    }

    static ExtractionContextVO recall(String source,int cue,List<String> keys,String trigger) {
        int start=source.lastIndexOf('\n',Math.max(0,cue-1))+1;
        int newline=source.indexOf('\n',cue),end=newline<0?source.length():newline+1;
        int lines=0;boolean table=source.substring(start,end).contains("|");boolean insufficient=end-start>WINDOW_BUDGET;
        while(end<source.length()) {
            int next=source.indexOf('\n',end),candidate=next<0?source.length():next+1;
            String line=source.substring(end,candidate).trim();
            if(lines>0&&heading(line)&&!table)break;
            if(table&&!line.isEmpty()&&!line.contains("|")&&lines>0)break;
            if(candidate-start>WINDOW_BUDGET){insufficient=true;break;}
            end=candidate;if(!line.isEmpty())lines++;
        }
        // Context may carry a preceding header but never truncates the selected logical unit to do so.
        int left=start;
        for(int i=0;i<2&&left>0;i++) {
            int previous=source.lastIndexOf('\n',Math.max(0,left-2))+1;
            String line=source.substring(previous,left).trim();
            if(end-previous>WINDOW_BUDGET)break;
            left=previous;if(heading(line))break;
        }
        return new ExtractionContextVO(trigger,keys,left,end,source.substring(left,end),insufficient?"context_insufficient":null);
    }

    private static boolean heading(String line) {
        return line.length()<160&&line.split("\\s+").length>=3&&!line.endsWith(".")&&!line.endsWith(";")&&!line.contains("|")&&
                !line.matches("(?i)^(?:use|retain|remove|delete|the|for|please|only)\\b.*");
    }
}
