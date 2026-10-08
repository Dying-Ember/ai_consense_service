package com.consense.service.drafting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Header-bound Bill identities; pricing classifications never come from another row or a heading. */
final class DraftBillSourceTables {
    private static final Pattern UNANSWERED=Pattern.compile("(?i)\\b(?:pending|unknown|unconfirmed|undetermined|not supplied|not provided|please confirm|please provide|whether|to be confirmed)\\b|\\?");
    private static final Pattern COMPLETE=Pattern.compile("(?i)\\bbill identity list\\b|\\bcomplete\\s+(?:(?:formal|parent|current|adopted|pricing|bill)\\s+){0,4}(?:list|schedule|register)\\b|\\bfollowing\\s+\\d+\\s+bill\\b|\\ball\\s+bill\\b");
    private static final Pattern UNASSERTED=Pattern.compile("(?i)\\b(?:pending|unknown|unconfirmed|undetermined|proposed|unselected|not (?:yet )?(?:adopted|approved|confirmed|provided|supplied)|please (?:confirm|provide)|whether|to be confirmed|do not (?:use|adopt|accept)|subject to|if|unless|may|might|could)\\b|\\?");
    private static final Pattern FOREIGN=Pattern.compile("(?i)\\b(?:another|different|other|previous|former|neighbouring|neighboring|adjacent)\\s+(?:project|contract)\\b|\\b(?:not|never)\\s+(?:for|under|within|in)\\s+(?:this|the same)\\s+(?:project|contract)\\b");
    private static final Pattern TABLE_PREFACE=Pattern.compile("(?i)\\b(?:bills?|pricing|register|schedule|table|columns|entries|adoption|approval|status)\\b");
    private static final Pattern TABLE_REFERENCE=Pattern.compile("(?i)^\\s*(?:(?:adoption|approval|status)\\s+note\\s*:\\s*)?(?:(?:however|but)[,\\s]+)?(?:(?:this|that|the above|these|the)\\s+(?:(?:parent|pricing|bill)\\s+){0,2}(?:register|schedule|table|decision|entries)|(?:this|that|it)(?=\\s+(?:is|remains|will|has|does)\\b))\\b");
    private DraftBillSourceTables() { }

    static final class Table {
        final int start,end;
        final String prefix;
        final List<Map<String,Object>> rows;
        final boolean declaredTypes,validRows,declaredComplete,asserted;
        Table(int start,int end,String prefix,List<Map<String,Object>> rows,boolean declaredTypes,boolean validRows,boolean suffixAsserted) {
            this.start=start;this.end=end;this.prefix=prefix;this.rows=rows;
            this.declaredTypes=declaredTypes;this.validRows=validRows;
            this.declaredComplete=COMPLETE.matcher(prefix).find();
            this.asserted=suffixAsserted&&!UNASSERTED.matcher(prefix).find()&&!FOREIGN.matcher(prefix).find();
        }
    }

    /** null preserves the separate prose-name path when this identity has no tabular anchor. */
    static Boolean identitySupported(Map<String,Object> candidate,String source) {
        boolean anchored=false;int matches=0;
        for(Table table:tables(source))for(Map<String,Object> row:table.rows) {
            if(!same(candidate.get("number"),row.get("number")))continue;
            anchored=true;
            if(!table.validRows||!table.asserted)continue;
            if(!same(candidate.get("description"),row.get("description")))continue;
            if(UNANSWERED.matcher(String.valueOf(row.get("description"))).find())continue;
            if(DraftBusinessRules.answered(candidate.get("type"))) {
                if(DraftBusinessRules.answered(row.get("type"))) {
                    if(!same(candidate.get("type"),row.get("type")))continue;
                } else if(table.declaredTypes)continue;
            }
            matches++;
        }
        if(!anchored)return unsupportedTableIdentity(candidate,source)?Boolean.FALSE:null;
        // Repeated identical tables are handled by recovery's unique-unit guard. Within one table,
        // an absent namespace must never select two same-number/same-name pricing identities.
        if(!DraftBusinessRules.answered(candidate.get("type")))for(Table table:tables(source)) {
            int local=0;
            for(Map<String,Object> row:table.rows)if(same(candidate.get("number"),row.get("number"))&&
                    same(candidate.get("description"),row.get("description")))local++;
            if(local>1)return false;
        }
        return matches>0;
    }

    /** null means classification is absent, permitting the existing metadata-only safe downgrade. */
    static Boolean typeSupport(Map<String,Object> candidate,String source) {
        boolean declared=false,supported=false;
        for(Table table:tables(source))for(Map<String,Object> row:table.rows) {
            if(!table.asserted||!table.validRows)continue;
            if(!same(candidate.get("number"),row.get("number"))||!same(candidate.get("description"),row.get("description")))continue;
            if(table.declaredTypes||DraftBusinessRules.answered(row.get("type"))) {
                declared=true;
                if(same(candidate.get("type"),row.get("type")))supported=true;
            }
        }
        if(!declared)return null;
        // Another same-number/same-name row in a different explicit namespace is another identity.
        return supported;
    }

    /** Optional metadata belongs to the one valid native unit covered by the actual quote. */
    static String metadataSource(Map<String,Object> candidate,String source,String quote) {
        if(source==null||quote==null||!DraftEvidenceQuotes.present(source,quote))return "";
        Table selected=null;
        for(Table table:tables(source)) {
            if(!table.asserted||!table.validRows)continue;
            String scope=source.substring(table.start,table.end);
            if(!DraftEvidenceQuotes.present(scope,quote)&&!DraftEvidenceQuotes.present(quote,scope))continue;
            if(selected!=null)return "";
            selected=table;
        }
        if(selected!=null) {
            for(Map<String,Object> row:selected.rows)if(same(candidate.get("number"),row.get("number"))&&same(candidate.get("description"),row.get("description")))
                return source.substring(selected.start,selected.end);
        }
        // A mixed quotation can also contain a distinct literal Bill statement after a table.
        // Bind that candidate's whole line, preserving formal titles containing semicolons.
        String[] lines=source.split("\n",-1);int offset=0;String prose=null;
        List<Table> allTables=tables(source);
        for(int index=0;index<lines.length;index++) {
            String line=lines[index];boolean nativeRow=false;
            for(Table table:allTables)if(offset>=table.start&&offset<table.end){nativeRow=true;break;}
            offset+=line.length()+1;
            if(nativeRow||line.trim().isEmpty()||UNASSERTED.matcher(line).find()||FOREIGN.matcher(line).find())continue;
            if(!DraftEvidenceQuotes.present(quote,line)&&!DraftEvidenceQuotes.present(line,quote))continue;
            if(!DraftCandidateGrounding.billIdentitiesSupported(java.util.Collections.singletonList(candidate),line))continue;
            boolean asserted=true;
            for(int previous=index-1;previous>=0;previous--) {
                String prefix=lines[previous].trim();if(prefix.isEmpty())continue;
                if(prefix.contains("|")||prefix.matches("(?i)^bill\\s*(?:no\\.?\\s*)?\\d+\\b.*")||
                        !TABLE_PREFACE.matcher(prefix).find()&&!FOREIGN.matcher(prefix).find())break;
                if(UNASSERTED.matcher(prefix).find()||FOREIGN.matcher(prefix).find()){asserted=false;break;}
            }
            if(!asserted)continue;
            if(prose!=null)return "";
            prose=line;
        }
        return prose==null?"":prose;
    }

    static boolean complete(Object value,Table table) {
        List<Object> candidates=DraftBusinessRules.list(value);
        if(!table.validRows||candidates.size()!=table.rows.size())return false;
        Set<Integer> matched=new HashSet<>();
        for(Object raw:candidates) {
            Map<String,Object> candidate=DraftBusinessRules.asMap(raw);int match=-1;
            for(int i=0;i<table.rows.size();i++) {
                Map<String,Object> row=table.rows.get(i);
                if(!same(candidate.get("number"),row.get("number"))||!same(candidate.get("description"),row.get("description")))continue;
                if(UNANSWERED.matcher(String.valueOf(row.get("description"))).find())continue;
                if(DraftBusinessRules.answered(candidate.get("type"))&&
                        (DraftBusinessRules.answered(row.get("type"))||table.declaredTypes)&&!same(candidate.get("type"),row.get("type")))continue;
                if(match>=0)return false;
                match=i;
            }
            if(match<0||!matched.add(match))return false;
        }
        return matched.size()==table.rows.size();
    }

    /** Locate a logical identity independently of whether its source unit is adopted. */
    static boolean identityRowsSupported(Object value,Table table) {
        for(Object raw:DraftBusinessRules.list(value)) {
            Map<String,Object> candidate=DraftBusinessRules.asMap(raw);int matches=0;
            for(Map<String,Object> row:table.rows) {
                if(!same(candidate.get("number"),row.get("number"))||!same(candidate.get("description"),row.get("description")))continue;
                if(UNANSWERED.matcher(String.valueOf(row.get("description"))).find())continue;
                if(DraftBusinessRules.answered(candidate.get("type"))&&
                        (DraftBusinessRules.answered(row.get("type"))||table.declaredTypes)&&!same(candidate.get("type"),row.get("type")))continue;
                matches++;
            }
            if(matches==0||!DraftBusinessRules.answered(candidate.get("type"))&&matches>1)return false;
        }
        return !DraftBusinessRules.list(value).isEmpty();
    }

    static List<Table> tables(String source) {
        List<Table> result=new ArrayList<>();if(source==null||source.isEmpty())return result;
        int start=-1,end=-1,offset=0;Header header=null;boolean validRows=true;
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String line:source.split("\n",-1)) {
            int lineEnd=Math.min(source.length(),offset+line.length()+1);
            Header next=line.contains("|")?header(line):null;
            if(next!=null) {
                add(source,start,end,header,validRows,rows,result);
                start=offset;end=lineEnd;header=next;validRows=true;rows=new ArrayList<>();
            } else if(header!=null&&line.contains("|")) {
                end=lineEnd;
                List<String> cells=cells(line,header.outer);
                if(!separator(cells)) {
                    if(cells.size()!=header.columns||
                            !cells.get(header.number).matches("[A-Za-z]?[0-9]+(?:[A-Za-z]|\\.[0-9]+)?")||cells.get(header.description).isEmpty())validRows=false;
                    else {
                        Map<String,Object> row=new LinkedHashMap<>();
                        row.put("number",cells.get(header.number));row.put("description",cells.get(header.description));
                        if(header.type>=0&&cells.size()>header.type) {
                            String type=pricingType(cells.get(header.type));if(type!=null)row.put("type",type);
                        }
                        rows.add(row);
                    }
                }
            } else if(header!=null&&(!line.trim().isEmpty()||lineEnd==source.length())) {
                add(source,start,end,header,validRows,rows,result);
                start=-1;end=-1;header=null;rows=new ArrayList<>();
            }
            offset=lineEnd;
        }
        add(source,start,end,header,validRows,rows,result);
        return result;
    }

    private static final class Header {
        final int number,description,type,columns;final boolean declaredTypes,outer;
        Header(int number,int description,int type,int columns,boolean declaredTypes,boolean outer) {
            this.number=number;this.description=description;this.type=type;this.columns=columns;this.declaredTypes=declaredTypes;this.outer=outer;
        }
    }

    private static Header header(String line) {
        boolean outer=line.trim().startsWith("|");List<String> cells=cells(line,outer);
        int number=-1,description=-1,type=-1;boolean declaredTypes=false;
        for(int i=0;i<cells.size();i++) {
            String cell=identity(cells.get(i)).toLowerCase(Locale.ROOT);
            if(cell.matches("(?:bill(?:\\s*(?:no\\.?|number|nos\\.?))?|number)")) {
                if(number>=0)return null;number=i;
            } else if(cell.matches("(?:(?:formal|exact|issued)\\s+)?(?:bill\\s+)?(?:description|name|title)")) {
                if(description>=0)return null;description=i;
            } else if(cell.matches("(?:pricing\\s+|document\\s+)?type|pricing classification|pricing document")) {
                if(type>=0)return null;type=i;declaredTypes=true;
            } else if("document treatment".equals(cell)) {
                if(type>=0)return null;type=i;
            }
        }
        return number<0||description<0?null:new Header(number,description,type,cells.size(),declaredTypes,outer);
    }

    private static List<String> cells(String line,boolean outer) {
        String[] raw=line.split("\\|",-1);List<String> cells=new ArrayList<>();
        int start=outer&&raw.length>0&&raw[0].trim().isEmpty()?1:0;
        int end=outer&&raw.length>start&&raw[raw.length-1].trim().isEmpty()?raw.length-1:raw.length;
        for(int i=start;i<end;i++)cells.add(raw[i].trim());
        return cells;
    }

    private static boolean separator(List<String> cells) {
        if(cells.isEmpty())return false;
        for(String cell:cells)if(!cell.matches(":?-{2,}:?"))return false;
        return true;
    }

    /** An explicit but incomplete/ambiguous Bill header cannot fall back to numbered prose. */
    private static boolean unsupportedTableIdentity(Map<String,Object> candidate,String source) {
        boolean unresolved=false;
        for(String line:source.split("\\R",-1)) {
            if(!line.contains("|")){if(!line.trim().isEmpty())unresolved=false;continue;}
            if(header(line)!=null){unresolved=false;continue;}
            List<String> row=cells(line,line.trim().startsWith("|"));
            boolean headerLike=false;
            for(String cell:row)if(identity(cell).toLowerCase(Locale.ROOT).matches("(?:bill(?:\\s*(?:no\\.?|number|nos\\.?))?|number)|(?:(?:formal|exact|issued)\\s+)?bill\\s+(?:description|name|title)|pricing (?:type|classification|document)"))headerLike=true;
            if(headerLike){unresolved=true;continue;}
            if(unresolved)for(String cell:row)if(same(candidate.get("number"),cell)||!DraftBusinessRules.answered(candidate.get("number"))&&same(candidate.get("description"),cell))return true;
        }
        return false;
    }

    private static String pricingType(String cell) {
        String text=identity(cell).toLowerCase(Locale.ROOT).replaceFirst("[.!]$","").trim();
        if(text.matches("bq|bills? of quantities(?: \\(bq\\))?"))return "BQ";
        if(text.matches("sor|schedules? of rates(?: \\(sor\\))?"))return "SOR";
        return null;
    }

    private static void add(String source,int start,int end,Header header,boolean validRows,List<Map<String,Object>> rows,List<Table> result) {
        if(header==null||start<0||end<start||rows.isEmpty())return;
        int left=start;boolean nearest=true;
        while(left>0) {
            int previous=source.lastIndexOf('\n',left-2)+1;
            String line=source.substring(previous,left).trim();
            if(line.contains("|")||!line.isEmpty()&&!nearest&&!TABLE_PREFACE.matcher(line).find()&&!FOREIGN.matcher(line).find())break;
            left=previous;if(!line.isEmpty()){nearest=false;if(!TABLE_PREFACE.matcher(line).find()&&!FOREIGN.matcher(line).find())break;}
        }
        int suffixEnd=end;boolean suffixAsserted=true;
        for(int cursor=end;cursor<source.length();) {
            int next=source.indexOf('\n',cursor);next=next<0?source.length():next+1;
            String line=source.substring(cursor,next).trim();cursor=next;
            if(line.isEmpty())continue;
            if(!TABLE_REFERENCE.matcher(line).find())break;
            suffixEnd=next;
            if(UNASSERTED.matcher(line).find()||FOREIGN.matcher(line).find())suffixAsserted=false;
        }
        result.add(new Table(left,suffixEnd,source.substring(left,start),new ArrayList<>(rows),header.declaredTypes,validRows,suffixAsserted));
    }

    private static boolean same(Object left,Object right) {
        return DraftBusinessRules.answered(left)&&DraftBusinessRules.answered(right)&&identity(String.valueOf(left)).equals(identity(String.valueOf(right)));
    }
    private static String identity(String text) {return DraftEvidenceQuotes.textIdentity(text);}
}
