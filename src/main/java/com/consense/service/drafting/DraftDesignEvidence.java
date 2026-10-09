package com.consense.service.drafting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded Contractor role evidence. A candidate tuple must already occur in one source record. */
final class DraftDesignEvidence {
    private static final Pattern COMPLETE=Pattern.compile("(?i)\\b(?:complete|full|entire|exhaustive)\\s+(?:(?:component|design|and|execution)\\s+){0,5}responsibilit(?:y|ies)\\s+(?:schedule|list|table|register)\\b");
    private static final Pattern UNSETTLED=Pattern.compile("(?i)\\b(?:pending|unknown|unconfirmed|undetermined|proposed|unselected|not (?:yet )?(?:adopted|approved|confirmed|supplied|provided|determined|selected)|do not (?:use|adopt|accept)|to be confirmed|please (?:confirm|provide|advise)|whether|subject to|if|unless|may|might|could)\\b|\\?");
    private static final Pattern TABLE_SUBJECT=Pattern.compile("(?i)\\b(?:responsibilit(?:y|ies)|schedule|matrix|table)\\b");
    private static final Pattern REQUEST_PREFIX=Pattern.compile("(?i)^(?:please\\b|do not\\b|proposed\\b|pending\\b|if\\b|unless\\b|subject to\\b)");
    private static final Pattern CONTRACTOR_OWNER=Pattern.compile("(?i)\\bcontractor(?:'s)?\\s+(?:(?:complete|full|entire|adopted|component|design|and|execution)\\s+){0,5}responsibilit(?:y|ies)\\b");
    private static final Pattern OTHER_OWNER=Pattern.compile("(?i)\\b(?:architect|engineer|employer)(?:'s)?\\s+(?:(?:complete|full|entire|adopted|component|design|and|execution)\\s+){0,5}responsibilit(?:y|ies)\\b");
    private static final Pattern OTHER_PROJECT=Pattern.compile("(?i)\\b(?:another|different|other|previous|former|neighbouring|neighboring|adjacent)\\s+(?:project|contract)\\b|\\b(?:not|never)\\s+(?:for|under|within|in)\\s+(?:this|the same)\\s+(?:project|contract)\\b");
    private static final Pattern TABLE_REFERENCE=Pattern.compile("(?i)^\\s*(?:(?:approval|adoption|confirmation|status|scope)\\s+note\\s*:\\s*)?(?:(?:however|but)[,\\s]+)?(?:(?:this|that|the above|these)\\s+(?:decision|schedule|table|responsibilit(?:y|ies)|entries|assignments)|(?:this|that|it)(?=\\s+(?:is|remains|will|has|does)\\b)|the\\s+(?:responsibility\\s+)?(?:schedule|table|decision))\\b");
    private static final Pattern TABLE_PREFACE=Pattern.compile("(?i)\\b(?:responsibilit(?:y|ies)|schedule|matrix|table|columns|entries|assignments)\\b|^(?:for|within|under|in)\\s+(?:(?:this|the|another|a different|the previous|the former)\\s+)?(?:project|contract)\\b|^(?:for\\s+(?:another|other|different|previous|former)\\s+(?:project|contract))\\b");
    private static final Pattern EXTERNAL_TABLE_REFERENCE=Pattern.compile("(?i)\\belsewhere\\b|\\bin\\s+(?:another|a different|a separate)\\s+(?:document|record|schedule|table)\\b");
    private static final Pattern STRUCTURAL_COMPONENT=Pattern.compile("(?i)\\b(?:piles?|piling|pile\\s*[- ]?\\s*caps?|footings?)\\b");
    private static final String ACTOR_CLAUSES="(?i);|\\s*(?:,\\s*)?(?:and|but|whereas|while)\\s+(?=(?:the\\s+)?(?:architect|engineer|employer|contractor)\\s+(?:(?:will|shall|must|does|is|has)\\b|designs?\\b|executes?\\b|constructs?\\b)|(?:the\\s+)?(?:piles?|piling|pile\\s*[- ]?\\s*caps?|footings?)\\s+(?:will|shall|must)\\s+be\\s+(?:designed|constructed|executed|built)\\b)";
    private static final Pattern OBJECT_EXCLUSION=Pattern.compile("(?i)\\s*(?:,\\s*)?(?:excluding|except(?:\\s+for)?|but\\s+not)\\b");
    private static final Pattern LABEL=Pattern.compile("(?i)^\\s*(component|scope|actual work scope|design|execution)\\s*[:=]\\s*(.*?)\\s*$");
    // Both verbs govern the same following object. Separate clauses cannot lend each other a role.
    private static final Pattern JOINT_PROSE=Pattern.compile("(?i)\\b(?:the\\s+)?(?:(?:appointed|selected)\\s+)?contractor\\s+(?:(?:will|shall|must)\\s+|is\\s+to\\s+)?design(?:s)?\\s+and\\s+(?:construct(?:s)?|execut(?:e|es)|build(?:s)?)\\s+(.+)");
    private static final Pattern NEGATIVE_PROSE=Pattern.compile("(?i)\\bcontractor\\s+(?:(?:will|shall|must|does)\\s+)?(?:not|never)\\s+(?:design|construct|execute|build)(?:\\s+(?:and|or)\\s+(?:design|construct|execute|build))?\\s+(.+)");
    private DraftDesignEvidence() { }

    /** Bind a literal quoted table to its immediate native preface, never to another table. */
    static String quotedTableScope(String quote,String original) {
        if(quote==null||original==null||!quote.contains("|"))return null;
        String[] lines=original.split("\n",-1);int[] offsets=lineOffsets(lines);String found=null;
        for(int i=0;i<lines.length;i++) {
            if(!lines[i].contains("|")||header(cells(lines[i]))==null)continue;
            int first=i,last=tableEnd(lines,i);
            String prefix=tablePrefix(lines,first);
            int scopeStart=prefix.isEmpty()?offsets[first]:original.lastIndexOf(prefix,offsets[first]);
            int scopeEnd=offsets[last]+lines[last].length();
            String scope=original.substring(scopeStart,scopeEnd);
            int following=last+1;
            // Dependent decision qualifiers stay with their table, even when the model omitted them.
            while(following<lines.length) {
                while(following<lines.length&&lines[following].trim().isEmpty())following++;
                if(following==lines.length||!TABLE_REFERENCE.matcher(lines[following]).find())break;
                scopeEnd=offsets[following]+lines[following].length();
                scope=original.substring(scopeStart,scopeEnd);following++;
            }
            // The quote may already include a prefix. It must identify this same whole table unit.
            if(DraftEvidenceQuotes.present(scope,quote)&&DraftEvidenceQuotes.present(quote,lines[first])) {
                if(found!=null)return original;
                found=scope;
            }
            i=last;
        }
        return found;
    }

    /** Keep a contiguous preface chain; unrelated paragraphs cannot lend their status to a table. */
    private static String tablePrefix(String[] lines,int first) {
        int before=first-1;
        while(before>=0&&lines[before].trim().isEmpty())before--;
        if(before<0)return "";
        int start=before;
        if(tablePrefaceLine(lines[before])) {
            while(start>0) {
                int previous=start-1;
                while(previous>=0&&lines[previous].trim().isEmpty())previous--;
                if(previous<0||!tablePrefaceLine(lines[previous]))break;
                start=previous;
            }
        }
        return String.join("\n",Arrays.copyOfRange(lines,start,before+1));
    }

    private static boolean tablePrefaceLine(String line) {
        return TABLE_PREFACE.matcher(line.trim()).find()&&!EXTERNAL_TABLE_REFERENCE.matcher(line).find();
    }

    private static int tableEnd(String[] lines,int first) {
        int last=first;
        for(int next=first+1;next<lines.length;next++) {
            if(lines[next].trim().isEmpty())continue;
            if(!lines[next].contains("|")||header(cells(lines[next]))!=null)break;
            last=next;
        }
        return last;
    }
    private static int[] lineOffsets(String[] lines) {
        int[] result=new int[lines.length];for(int i=1;i<lines.length;i++)result[i]=result[i-1]+lines[i-1].length()+1;return result;
    }

    static boolean supported(Object value,String source) {
        if(!(value instanceof List)||source==null)return false;
        List<?> candidates=(List<?>)value;
        // The harness checks explicit absence evidence separately; do not reinterpret an empty list.
        if(candidates.isEmpty())return true;
        if(OTHER_PROJECT.matcher(source).find()||unsettledTableSuffix(source))return false;
        List<Record> records=records(source);Set<String> seen=new HashSet<>();
        for(Object raw:candidates) {
            if(!(raw instanceof Map))return false;
            Map<String,Object> row=DraftBusinessRules.asMap(raw);
            String component=component(row.get("component")),scope=identity(row.get("scope"));
            Boolean design=DraftBusinessRules.truth(row.get("design")),execution=DraftBusinessRules.truth(row.get("execution"));
            if(component==null||design==null||execution==null||!seen.add(component+"\u0000"+scope))return false;
            List<Record> matching=new ArrayList<>();Set<String> scopes=new HashSet<>();
            for(Record record:records)if(component.equals(record.component)&&(scope.isEmpty()||scope.equals(record.scope))) {
                matching.add(record);scopes.add(record.scope);
            }
            if(!matching.isEmpty()) {
                if(matching.size()>1)return false;
                if(scope.isEmpty()&&scopes.size()>1)return false;
                // A contradictory or unanswered record of this same identity stays unresolved.
                for(Record record:matching)if(!record.asserted||!Objects.equals(design,record.design)||!Objects.equals(execution,record.execution))return false;
            } else if(!proseSupported(component,scope,design,execution,source))return false;
        }
        if(COMPLETE.matcher(source).find())for(Record record:records) {
            if(!record.asserted)return false;
            boolean covered=false;
            for(Object raw:candidates) {
                Map<String,Object> row=DraftBusinessRules.asMap(raw);String scope=identity(row.get("scope"));
                if(Objects.equals(component(row.get("component")),record.component)&&record.component!=null&&
                        (scope.isEmpty()||scope.equals(record.scope))&&
                        Objects.equals(DraftBusinessRules.truth(row.get("design")),record.design)&&
                        Objects.equals(DraftBusinessRules.truth(row.get("execution")),record.execution))covered=true;
            }
            if(!covered)return false;
        }
        return true;
    }

    private static boolean unsettledTableSuffix(String source) {
        String[] lines=source.split("\\R",-1);
        for(int i=0;i<lines.length;i++) {
            if(!lines[i].contains("|")||header(cells(lines[i]))==null)continue;
            i=tableEnd(lines,i);
            int next=i+1;
            while(next<lines.length) {
                while(next<lines.length&&lines[next].trim().isEmpty())next++;
                if(next==lines.length||!TABLE_REFERENCE.matcher(lines[next]).find())break;
                if(UNSETTLED.matcher(lines[next++]).find())return true;
            }
        }
        return false;
    }

    private static final class Record {
        final String component,scope;final Boolean design,execution;final boolean asserted;
        Record(String component,String scope,Boolean design,Boolean execution,boolean asserted) {
            this.component=component;this.scope=scope;this.design=design;this.execution=execution;this.asserted=asserted;
        }
    }
    private static final class Header {
        int component=-1,scope=-1,design=-1,execution=-1,columns;boolean ambiguous,contractorDesign,contractorExecution;
    }

    private static List<Record> records(String source) {
        List<Record> result=new ArrayList<>();Header header=null;boolean asserted=true;String previous="";Boolean labelledAsserted=null;
        String[] lines=source.split("\\R",-1);
        for(int index=0;index<lines.length;index++) {
            String line=lines[index];
            if(line.contains("|")) {
                List<String> cells=cells(line);Header next=header(cells);
                if(next!=null) {
                    String prefix=tablePrefix(lines,index);
                    header=next;asserted=prefixAsserted(prefix)&&
                            (header.contractorDesign&&header.contractorExecution||CONTRACTOR_OWNER.matcher(prefix).find());
                    labelledAsserted=null;
                } else if(header!=null&&!separator(cells)) {
                    String component=component(cell(cells,header.component));
                    result.add(new Record(component,identity(cell(cells,header.scope)),
                            truth(cell(cells,header.design)),truth(cell(cells,header.execution)),
                            asserted&&!header.ambiguous&&cells.size()==header.columns&&!UNSETTLED.matcher(line).find()));
                }
            } else if(!line.trim().isEmpty()) {
                header=null;Record labelled=labelled(line);
                if(labelled!=null) {
                    if(labelledAsserted==null)labelledAsserted=prefixAsserted(previous)&&!OTHER_OWNER.matcher(previous).find();
                    result.add(new Record(labelled.component,labelled.scope,labelled.design,labelled.execution,labelled.asserted&&labelledAsserted));
                } else {labelledAsserted=null;previous=line.trim();}
            }
        }
        return result;
    }

    private static Header header(List<String> cells) {
        Header header=new Header();header.columns=cells.size();
        for(int i=0;i<cells.size();i++) {
            String name=identity(cells.get(i));
            if(name.matches("(?:(?:foundation|works) )?component(?: class)?")) {
                if(header.component>=0)header.ambiguous=true;header.component=i;
            } else if(name.matches("(?:actual )?(?:work )?scope|scope of work|(?:actual )?component or scope")) {
                if(header.scope>=0)header.ambiguous=true;header.scope=i;
            } else if(name.matches("(?:contractor(?:'s)? )?(?:designs?|design responsibility)")) {
                if(header.design>=0)header.ambiguous=true;header.design=i;header.contractorDesign=name.startsWith("contractor");
            } else if(name.matches("(?:contractor(?:'s)? )?(?:executes?|execution(?: responsibility)?|constructs?|construction responsibility)")) {
                if(header.execution>=0)header.ambiguous=true;header.execution=i;header.contractorExecution=name.startsWith("contractor");
            }
        }
        return header.component<0?null:header;
    }

    private static Record labelled(String line) {
        String component=null,scope="";Boolean design=null,execution=null;Set<String> seen=new HashSet<>();boolean asserted=!UNSETTLED.matcher(line).find();
        for(String field:line.split(";",-1)) {
            Matcher match=LABEL.matcher(field);if(!match.matches())return null;
            String key=identity(match.group(1)),text=match.group(2);
            if("actual work scope".equals(key))key="scope";
            if(!seen.add(key))asserted=false;
            if("component".equals(key))component=component(text);
            else if("scope".equals(key))scope=identity(text);
            else if("design".equals(key))design=truth(text);
            else if("execution".equals(key))execution=truth(text);
        }
        return component==null?null:new Record(component,scope,design,execution,asserted);
    }

    private static boolean proseSupported(String component,String scope,boolean design,boolean execution,String source) {
        if(!design||!execution)return false;
        if("other".equals(component)&&STRUCTURAL_COMPONENT.matcher(scope).find())return false;
        String[] passages=source.split("\\R|(?<=[.!?])\\s+(?=[A-Z])");boolean supported=false;
        for(int p=0;p<passages.length;p++) {
            String passage=passages[p];
            if(passage.contains("|"))continue;
            if(p>0&&!prefixAsserted(passages[p-1].trim()))continue;
            String[] clauses=passage.split(ACTOR_CLAUSES,-1);
            for(int i=0;i<clauses.length;i++) {
                String clause=clauses[i];Matcher negative=NEGATIVE_PROSE.matcher(clause);
                if(negative.find()&&objectMatches(component,scope,positiveObject(negative.group(1))))return false;
                if(UNSETTLED.matcher(clause).find())continue;
                // A dependent qualifier retains its previous claim's status; a new subject has its own role.
                if(i+1<clauses.length&&clauses[i+1].trim().matches("(?is)^(?:this|that|it|the above)\\b.*")&&UNSETTLED.matcher(clauses[i+1]).find())continue;
                if(p+1<passages.length&&passages[p+1].trim().matches("(?is)^(?:this|that|it|the above)\\b.*")&&UNSETTLED.matcher(passages[p+1]).find())continue;
                Matcher claim=JOINT_PROSE.matcher(clause);if(!claim.find())continue;
                String object=positiveObject(claim.group(1));
                if(Pattern.compile("(?i)\\b(?:not|never|no)\\b").matcher(clause.substring(0,claim.start(1))).find()||object.trim().matches("(?is)^(?:the\\s+)?no\\b.*"))continue;
                if(objectMatches(component,scope,object))supported=true;
            }
        }
        return supported;
    }

    private static String positiveObject(String object) {
        Matcher exclusion=OBJECT_EXCLUSION.matcher(object);return exclusion.find()?object.substring(0,exclusion.start()):object;
    }
    private static boolean objectMatches(String component,String scope,String object) {
        if(!scope.isEmpty()&&!identity(object).contains(scope))return false;
        String alias="piling".equals(component)?"piles?|piling":"pilecaps".equals(component)?"pile\\s*[- ]?\\s*caps?":"footings".equals(component)?"footings?":null;
        // 'other' has no safe generic literal identity; its explicit scope must be the shared object.
        return alias==null?!scope.isEmpty():Pattern.compile("(?i)\\b(?:"+alias+")\\b").matcher(object).find();
    }

    private static boolean prefixAsserted(String prefix) {
        return !UNSETTLED.matcher(prefix).find()||!TABLE_SUBJECT.matcher(prefix).find()&&!REQUEST_PREFIX.matcher(prefix).find();
    }

    private static String component(Object value) {
        String text=identity(value);
        if(text.matches("piling|piles?"))return "piling";
        if(text.matches("pile\\s*[- ]?\\s*caps?"))return "pilecaps";
        if(text.matches("(?:shallow )?footings?"))return "footings";
        return "other".equals(text)?"other":null;
    }
    private static Boolean truth(String text) {
        String answer=identity(text).replaceFirst("[.!]$","").trim();
        if(Arrays.asList("yes","true","required").contains(answer))return true;
        if(Arrays.asList("no","false","not required").contains(answer))return false;
        return null;
    }
    private static String identity(Object text) {return text==null?"":DraftEvidenceQuotes.textIdentity(String.valueOf(text)).toLowerCase(Locale.ROOT);}
    private static String cell(List<String> cells,int index) {return index<0||index>=cells.size()?"":cells.get(index);}
    private static List<String> cells(String line) {
        String[] raw=line.trim().split("\\|",-1);List<String> cells=new ArrayList<>();
        int first=raw[0].trim().isEmpty()?1:0,last=raw.length;
        if(last>first&&raw[last-1].trim().isEmpty())last--;
        for(int i=first;i<last;i++)cells.add(raw[i].trim());return cells;
    }
    private static boolean separator(List<String> cells) {
        if(cells.isEmpty())return false;
        for(String cell:cells)if(!cell.matches(":?-{2,}:?"))return false;
        return true;
    }
}
