package com.consense.service.drafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative original-offset boundaries for numbered source blocks, including malformed sequences. */
final class DraftOrderedSourceLists {
    private static final Pattern MARKER=Pattern.compile("(?m)(?:^[\\t ]*|(?<=[;:])[\\t ]*)([0-9]{1,4})[.)](?:[\\t ]+(?=\\S)|[\\t ]*(?=[^\\s\\d]|\\r?$))");
    private static final int NEIGHBOR_BUDGET=800;
    private DraftOrderedSourceLists() { }

    static final class Entry {
        final int number,start,markerEnd,end;
        final String text;
        Entry(int number,int start,int markerEnd,int end,String text) {
            this.number=number;this.start=start;this.markerEnd=markerEnd;this.end=end;this.text=text;
        }
    }

    static final class Unit {
        /** start/end also carry a nearby original title or a following source declaration. */
        final int start,end,listStart,listEnd;
        /** Missing bodies, duplicate numbers and gaps invalidate the original block; never split them away. */
        final boolean validSequence;
        final List<Entry> entries;
        Unit(int start,int end,List<Entry> entries) {
            this.start=start;this.end=end;this.entries=Collections.unmodifiableList(new ArrayList<>(entries));
            this.listStart=entries.get(0).start;this.listEnd=entries.get(entries.size()-1).end;
            boolean valid=true;
            for(int i=0;i<entries.size();i++) {
                Entry entry=entries.get(i);
                valid&=!entry.text.isEmpty()&&(i==0||entry.number==entries.get(i-1).number+1);
            }
            this.validSequence=valid;
        }
    }

    static List<Unit> units(String source) {
        if(source==null||source.isEmpty())return Collections.emptyList();
        List<Entry> markers=new ArrayList<>();Matcher matcher=MARKER.matcher(source);
        while(matcher.find())markers.add(new Entry(Integer.parseInt(matcher.group(1)),matcher.start(1),matcher.end(),0,""));
        List<Unit> units=new ArrayList<>();List<Entry> run=new ArrayList<>();
        for(int i=0;i<markers.size();i++) {
            Entry marker=markers.get(i);int limit=i+1<markers.size()?markers.get(i+1).start:source.length();
            int end=itemEnd(source,marker.markerEnd,limit);
            Entry entry=new Entry(marker.number,marker.start,marker.markerEnd,end,source.substring(marker.markerEnd,end).trim());
            if(!run.isEmpty()) {
                Entry previous=run.get(run.size()-1);
                if(labelledMarker(source,entry.start)||!source.substring(previous.end,entry.start).trim().isEmpty()) {
                    addUnit(source,run,units);run.clear();
                }
            }
            run.add(entry);
        }
        addUnit(source,run,units);return Collections.unmodifiableList(units);
    }

    private static boolean labelledMarker(String source,int start) {
        int previous=start-1;
        while(previous>=0&&(source.charAt(previous)==' '||source.charAt(previous)=='\t'))previous--;
        return previous>=0&&source.charAt(previous)==':';
    }

    private static void addUnit(String source,List<Entry> run,List<Unit> units) {
        if(run.size()<2)return;
        int first=run.get(0).start,last=run.get(run.size()-1).end;
        int start=source.lastIndexOf('\n',Math.max(0,first-1))+1;
        // A same-line field label is already included; add its nearby preceding source title/instruction.
        for(int count=0,nonblank=0;count<5&&nonblank<2&&start>0;count++) {
            int candidate=source.lastIndexOf('\n',Math.max(0,start-2))+1;
            String line=source.substring(candidate,start).trim();
            if(first-candidate>NEIGHBOR_BUDGET||line.contains("|")||MARKER.matcher(line).find())break;
            if(!line.isEmpty()&&line.length()>240)break;
            start=candidate;
            if(!line.isEmpty())nonblank++;
        }
        int end=last,newline=source.indexOf('\n',last);
        if(newline>=0)end=newline+1;
        // Do not absorb the next topic. A short, immediately following sentence is retained as source context.
        int cursor=end;
        for(int count=0;count<3&&cursor<source.length();count++) {
            int next=source.indexOf('\n',cursor),candidate=next<0?source.length():next+1;
            String line=source.substring(cursor,candidate).trim();
            if(candidate-last>NEIGHBOR_BUDGET)break;
            if(line.isEmpty()){cursor=candidate;continue;}
            if(sentenceEnd(line)&&!line.contains("|")&&!MARKER.matcher(line).find())end=candidate;
            break;
        }
        units.add(new Unit(start,end,run));
    }

    /** Continuation lines belong to an item; a separate unindented paragraph never bridges two lists. */
    private static int itemEnd(String source,int bodyStart,int limit) {
        int newline=source.indexOf('\n',bodyStart);
        int end=newline<0||newline>=limit?limit:newline+1;
        String previous=source.substring(bodyStart,end).trim();boolean blank=false;
        while(end<limit) {
            int next=source.indexOf('\n',end),candidate=next<0||next>=limit?limit:next+1;
            String raw=source.substring(end,candidate),line=raw.trim();
            if(line.isEmpty()){blank=true;end=candidate;continue;}
            boolean indented=raw.charAt(0)==' '||raw.charAt(0)=='\t';
            if(!indented&&(blank||sentenceEnd(previous)))break;
            previous=line;blank=false;end=candidate;
        }
        while(end>bodyStart&&Character.isWhitespace(source.charAt(end-1)))end--;
        return end;
    }

    private static boolean sentenceEnd(String text) {
        return text.endsWith(".")||text.endsWith(";")||text.endsWith("; and");
    }
}
