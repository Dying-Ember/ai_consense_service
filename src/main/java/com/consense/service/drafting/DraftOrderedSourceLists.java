package com.consense.service.drafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative original-offset boundaries for numbered source blocks, including malformed sequences. */
final class DraftOrderedSourceLists {
    private static final Pattern MARKER=Pattern.compile("(?m)(?:^[\\t ]*|(?<=[;:])[\\t ]*)([0-9]{1,4})[.)](?:[\\t ]+(?=\\S)|[\\t ]*(?=[^\\s\\d]|\\r?$))");
    private static final int NEIGHBOR_BUDGET=800;
    private static final Pattern LIST_REFERENCE=Pattern.compile("(?i)\\b(?:this|that|these|those|the|above|below|preceding|foregoing|listed|specified|numbered|full|complete)(?:\\s+[a-z0-9]+){0,3}\\s+(?:list|schedule|scope|areas?|locations?|items?|entries|requirements?|alternatives?|options?)\\b|\\b(?:it|they|them)\\b");
    private static final Pattern STANDALONE_CONTROL=Pattern.compile("(?i)^(?:(?:approval|selection|confirmation|adoption|applicability)\\s+(?:is|remains|has|requires|awaits)|pending\\b|unconfirmed\\b|not(?:\\s+yet)?\\s+(?:adopted|selected|confirmed|applicable|required)\\b)");
    private static final Pattern INDEPENDENT_DECISIONS=Pattern.compile("(?i)^(?:this|these|those|the(?:\\s+(?:above|following|separate))?)\\s+decisions?\\s+(?:do|does|will)\\s+not\\s+(?:amend|withdraw|change|affect)(?:\\s+(?:or|and)\\s+(?:amend|withdraw|change|affect))*\\s+");
    private static final Pattern EXPLICIT_RESTRICTION=Pattern.compile("(?i)\\b(?:pending|unknown|unconfirmed|undetermined|whether|proposed|unselected|alternatives?|options?|if|unless|subject to|excluded|not (?:adopted|selected|confirmed|applicable|required|supplied|provided)|to be confirmed|please (?:confirm|provide)|do not (?:use|adopt|apply)|does not apply)\\b|\\b(?:no|none of|neither)\\b[^.;]*\\b(?:required|adopted|included|specified|selected|applicable|apply|applies)\\b|\\bnot\\s+(?:included|specified)\\b|\\?");
    private static final Pattern INDEPENDENT_CLAUSES=Pattern.compile("(?i),?\\s+(?:and|but|whereas|while)\\s+(?=(?:the|this|these|those|a|an|no|none|neither)\\s+(?:[a-z0-9'-]+\\s+){0,7}(?:is|are|was|were|will|shall|must|do|does|has|have|remains?|requires?)\\b)");
    private static final Pattern OTHER_PROJECT=Pattern.compile("(?i)\\b(?:another|different|other|previous|former|adjacent|neighbouring|neighboring)\\s+(?:project|contract)\\b|\\bnot\\s+(?:for|under|within|in)\\s+(?:this|the same)\\s+(?:project|contract)\\b");
    private static final Pattern NUMBER_WORD=Pattern.compile("(?i)zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty");
    private static final Set<String> GENERIC_WORDS=new HashSet<>(Arrays.asList("other","specified","specification","following","preceding","complete","numbered","continuation","lines","belongs","selected","required","includes","included","contract","project","works","scope","areas","items","entries","these","those","their","there","shall","would","could","should","please","pending","approval","confirmation","adopted","warranty","warranties","installation","installations","schedule","locations","requirements","alternatives","options","above","below","listed","foregoing"));
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
        /** Only clauses governing this list, not negative clauses about an adjacent installation. */
        final String governingText;
        final boolean foreignProject;
        final List<Entry> entries;
        Unit(int start,int end,List<Entry> entries,String governingText) {
            this.start=start;this.end=end;this.entries=Collections.unmodifiableList(new ArrayList<>(entries));
            this.listStart=entries.get(0).start;this.listEnd=entries.get(entries.size()-1).end;
            this.governingText=governingText;this.foreignProject=OTHER_PROJECT.matcher(governingText).find();
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
            String attached=source.substring(start,first).trim();
            if(!attached.isEmpty()&&sentenceEnd(line)&&!governsList(line,attached))break;
            start=candidate;
            if(!line.isEmpty())nonblank++;
        }
        int end=last,newline=source.indexOf('\n',last);
        if(newline>=0)end=newline+1;
        // A named restriction can follow a sibling paragraph within the same bounded neighborhood.
        // Keep original contiguous offsets for evidence, but do not import the sibling's negation.
        String introduction=source.substring(start,first);StringBuilder governing=new StringBuilder(introduction);
        int cursor=end;boolean adjacent=true;
        for(int count=0;count<6&&cursor<source.length();count++) {
            int next=source.indexOf('\n',cursor),candidate=next<0?source.length():next+1;
            String line=source.substring(cursor,candidate).trim();
            if(candidate-last>NEIGHBOR_BUDGET)break;
            if(line.isEmpty()){cursor=candidate;continue;}
            if(!(sentenceEnd(line)||STANDALONE_CONTROL.matcher(line).find())||line.contains("|")||MARKER.matcher(line).find())break;
            List<String> controls=governingStatements(line,introduction,adjacent);
            if(controls.isEmpty()) {
                if(!independentAssurance(line))adjacent=false;
                cursor=candidate;continue;
            }
            for(String statement:controls)governing.append('\n').append(statement);
            List<String> allStatements=statements(line);
            adjacent=independentAssurance(allStatements.get(allStatements.size()-1))||
                    !governingStatements(allStatements.get(allStatements.size()-1),introduction,true).isEmpty();
            end=candidate;cursor=candidate;
        }
        units.add(new Unit(start,end,run,governing.toString()));
    }

    private static boolean independentAssurance(String paragraph) {
        for(String statement:statements(paragraph))
            if(INDEPENDENT_DECISIONS.matcher(statement).find()&&!EXPLICIT_RESTRICTION.matcher(statement).find())return true;
        return false;
    }

    private static boolean governsList(String sentence,String introduction) {
        return !governingStatements(sentence,introduction,true).isEmpty();
    }

    private static List<String> governingStatements(String sentence,String introduction,boolean adjacent) {
        // Match a subject stated in the introduction, never a word from an individual item.
        // Shared generic words such as "warranty" do not identify the same subject.
        Set<String> subjects=new HashSet<>();
        for(String word:introduction.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            if(word.length()>4&&!GENERIC_WORDS.contains(word)&&!NUMBER_WORD.matcher(word).matches())subjects.add(word);
        List<String> result=new ArrayList<>();
        for(String statement:statements(sentence)) {
            // An assurance that sibling decisions do not change this list is an incidental reference,
            // not a qualification importing those decisions' negative installation/warranty facts.
            // Actual pending/negative restrictions in the same statement remain governing evidence.
            if(INDEPENDENT_DECISIONS.matcher(statement).find()&&!EXPLICIT_RESTRICTION.matcher(statement).find())continue;
            boolean relevant=referencesOwnList(statement,subjects,adjacent)||adjacent&&STANDALONE_CONTROL.matcher(statement).find();
            for(String word:subjects)if(Pattern.compile("(?i)\\b"+Pattern.quote(word)+"\\b").matcher(statement).find())relevant=true;
            if(relevant)result.add(statement);
        }
        return result;
    }

    private static boolean referencesOwnList(String statement,Set<String> subjects,boolean adjacent) {
        Matcher reference=LIST_REFERENCE.matcher(statement);
        while(reference.find()) {
            String text=reference.group().toLowerCase(Locale.ROOT);
            if(text.matches("it|they|them")){if(adjacent)return true;continue;}
            boolean named=false,shared=false;
            for(String word:text.split("[^a-z0-9]+"))if(word.length()>4&&!GENERIC_WORDS.contains(word)&&!NUMBER_WORD.matcher(word).matches()) {
                named=true;if(subjects.contains(word))shared=true;
            }
            if(!named||shared)return true;
        }
        return false;
    }

    private static List<String> statements(String paragraph) {
        List<String> result=new ArrayList<>();
        for(String sentence:paragraph.split("(?<=[.!?;])\\s+(?=[A-Z])"))
            for(String clause:INDEPENDENT_CLAUSES.split(sentence))if(!clause.trim().isEmpty())result.add(clause.trim());
        return result;
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
        return text.endsWith(".")||text.endsWith(";")||text.endsWith("; and")||text.endsWith("?")||text.endsWith("!");
    }
}
