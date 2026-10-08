package com.consense.service.drafting;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded formal Section-table evidence; ordinary building locations are not Section identities. */
final class DraftSectionEvidence {
    private static final Pattern UNSETTLED=Pattern.compile("(?i)\\b(?:pending|unknown|unconfirmed|undetermined|proposed|partial|incomplete|not (?:yet )?(?:adopted|approved|confirmed|supplied|provided|determined|selected)|do not (?:use|adopt|accept)|to be confirmed|please (?:confirm|provide|advise)|whether|subject to|if|unless|may|might|could)\\b|\\?");
    private static final Pattern SECTION_SCOPE=Pattern.compile("(?i)\\b(?:sections?|decision|table|schedule)\\b");
    private static final Pattern COMPLETE=Pattern.compile("(?i)\\b(?:complete|full|entire|exhaustive|all)\\b|\\badopt (?:these|the following)\\b");
    private static final Pattern FOREIGN=Pattern.compile("(?i)\\b(?:another|different|other|previous|former|neighbouring|neighboring|adjacent)\\s+(?:project|contract)\\b|\\b(?:not|never)\\s+(?:for|under|within|in)\\s+(?:this|the same)\\s+(?:project|contract)\\b");
    private static final Pattern TABLE_PREFACE=Pattern.compile("(?i)\\b(?:sections?|schedule|table|register|columns|entries|adoption|approval|status)\\b");
    private static final Pattern TABLE_REFERENCE=Pattern.compile("(?i)^\\s*(?:(?:adoption|approval|status)\\s+note\\s*:\\s*)?(?:(?:however|but)[,\\s]+)?(?:(?:this|that|the above|these|the)\\s+(?:section\\s+)?(?:schedule|table|register|decision|entries)|(?:this|that|it)(?=\\s+(?:is|remains|will|has|does)\\b))\\b");
    private DraftSectionEvidence() { }

    /** The whole literal native table keeps its own preface and dependent decision qualifiers. */
    static String quotedTableScope(String quote,String original) {
        if(quote==null||original==null||!quote.contains("|"))return null;
        String[] lines=original.split("\n",-1);int[] offsets=new int[lines.length];String found=null;
        for(int i=1;i<lines.length;i++)offsets[i]=offsets[i-1]+lines[i-1].length()+1;
        for(int first=0;first<lines.length;first++) {
            if(!lines[first].contains("|")||header(cells(lines[first]),previous(lines,first))==null)continue;
            int last=first;
            for(int next=first+1;next<lines.length;next++) {
                if(lines[next].trim().isEmpty())continue;
                if(!lines[next].contains("|")||header(cells(lines[next]),"")!=null)break;
                last=next;
            }
            String prefix=prefix(lines,first);
            int scopeStart=prefix.isEmpty()?offsets[first]:original.lastIndexOf(prefix,offsets[first]);
            String scope=original.substring(scopeStart,offsets[last]+lines[last].length());
            for(int next=last+1;next<lines.length;next++) {
                if(lines[next].trim().isEmpty())continue;
                if(!TABLE_REFERENCE.matcher(lines[next]).find())break;
                scope=original.substring(scopeStart,offsets[next]+lines[next].length());
            }
            if(DraftEvidenceQuotes.present(scope,quote)&&DraftEvidenceQuotes.present(quote,lines[first])) {
                if(found!=null)return original;
                found=scope;
            }
            first=last;
        }
        return found;
    }

    static boolean supported(Object value,String quote) {
        if(!(value instanceof List)||quote==null)return false;
        List<?> candidates=(List<?>)value;
        // Explicit absence is checked by the public harness against the original source context.
        if(candidates.isEmpty())return true;
        List<Row> actual=new ArrayList<>();Set<String> identities=new HashSet<>();
        for(Object raw:candidates) {
            if(!(raw instanceof Map))return false;
            Map<String,Object> candidate=DraftBusinessRules.asMap(raw);
            String designation=identity(candidate.get("designation")),location=identity(candidate.get("location"));
            Set<String> types=candidateTypes(candidate.get("workTypes"));
            if(designation.isEmpty()||location.isEmpty()||types==null||!identities.add(designation))return false;
            actual.add(new Row(designation,types,location));
        }
        boolean found=false;
        for(Table table:tables(quote)) {
            boolean matches=table.asserted&&matches(actual,table.rows);
            // One complete table cannot lend rows or roles to another complete table in the quotation.
            if(table.complete&&!matches)return false;
            if(matches){if(found)return false;found=true;}
        }
        return found;
    }

    /** Designation identifies a Section; reordering already supplied tuples does not change their meaning. */
    private static boolean matches(List<Row> candidates,List<Row> source) {
        if(candidates.size()!=source.size())return false;
        Map<String,Row> records=new HashMap<>();
        for(Row row:source)if(records.put(row.designation,row)!=null)return false;
        for(Row candidate:candidates) {
            Row record=records.get(candidate.designation);if(record==null||!candidate.same(record))return false;
        }
        return true;
    }

    private static List<Table> tables(String source) {
        List<Table> result=new ArrayList<>();String[] lines=source.split("\\R",-1);
        for(int line=0;line<lines.length;line++) {
            if(!lines[line].contains("|"))continue;
            String prefix=prefix(lines,line);Header header=header(cells(lines[line]),prefix);
            if(header==null)continue;
            Table table=new Table();table.asserted=!header.ambiguous&&asserted(prefix);
            table.complete=SECTION_SCOPE.matcher(prefix).find()&&COMPLETE.matcher(prefix).find();
            int next=line+1;
            for(;next<lines.length;next++) {
                // Native Word extraction leaves blank paragraphs between header and data rows.
                // Only a following pipe row belongs to this table; prose still ends the unit.
                if(lines[next].trim().isEmpty())continue;
                if(!lines[next].contains("|"))break;
                List<String> cells=cells(lines[next]);
                if(separator(cells))continue;
                // A second header starts a distinct source table, never a data row.
                if(header(cells,"")!=null)break;
                Set<String> types=sourceTypes(cell(cells,header.types));
                String designation=identity(cell(cells,header.designation)),location=identity(cell(cells,header.location));
                if(cells.size()!=header.columns||designation.isEmpty()||location.isEmpty()||types==null||UNSETTLED.matcher(lines[next]).find())table.asserted=false;
                table.rows.add(new Row(designation,types,location));
            }
            // Every continuous dependent decision belongs to this same table. Blank native
            // paragraphs do not break the chain; an independent subject ends it.
            for(int suffix=next;suffix<lines.length;suffix++) {
                if(lines[suffix].trim().isEmpty())continue;
                if(!TABLE_REFERENCE.matcher(lines[suffix]).find())break;
                if(UNSETTLED.matcher(lines[suffix]).find()||FOREIGN.matcher(lines[suffix]).find())table.asserted=false;
            }
            if(table.rows.isEmpty())table.asserted=false;
            result.add(table);line=next-1;
        }
        return result;
    }

    private static Header header(List<String> cells,String prefix) {
        Header h=new Header();h.columns=cells.size();
        for(int i=0;i<cells.size();i++) {
            String name=identity(cells.get(i));
            if(name.matches("section(?: designation| name)?")||"designation".equals(name)&&Pattern.compile("(?i)\\bsections?\\b").matcher(prefix).find()) {
                if(h.designation>=0)h.ambiguous=true;h.designation=i;
            } else if(name.matches("works? types?|types? of works?")) {
                if(h.types>=0)h.ambiguous=true;h.types=i;
            } else if(name.matches("(?:site or |site )?location|site")) {
                if(h.location>=0)h.ambiguous=true;h.location=i;
            }
        }
        return h.designation<0||h.types<0||h.location<0?null:h;
    }

    private static Set<String> candidateTypes(Object raw) {
        if(!(raw instanceof List))return null;
        Set<String> result=new LinkedHashSet<>();
        for(Object item:(List<?>)raw) {
            String type=identity(item);if(!Arrays.asList("building","foundation","demolition","other").contains(type)||!result.add(type))return null;
        }
        return result.isEmpty()?null:result;
    }
    private static Set<String> sourceTypes(String source) {
        Set<String> result=new LinkedHashSet<>();
        for(String item:identity(source).split("\\s*(?:,|;|/|\\band\\b|&)\\s*",-1)) {
            String type=item.trim().replaceFirst("\\s+works?$","");
            if(!Arrays.asList("building","foundation","demolition","other").contains(type)||!result.add(type))return null;
        }
        return result.isEmpty()?null:result;
    }
    private static boolean asserted(String passage) {
        if(FOREIGN.matcher(passage).find())return false;
        return !UNSETTLED.matcher(passage).find()||!SECTION_SCOPE.matcher(passage).find()&&!passage.trim().matches("(?i)^(?:please|proposed|pending|if|unless|subject to|do not)\\b.*");
    }
    private static String previous(String[] lines,int line) {
        for(int i=line-1;i>=0;i--)if(!lines[i].trim().isEmpty())return lines[i];return "";
    }
    private static String prefix(String[] lines,int first) {
        int before=first-1;while(before>=0&&lines[before].trim().isEmpty())before--;
        if(before<0)return "";int start=before;
        if(TABLE_PREFACE.matcher(lines[before]).find()||FOREIGN.matcher(lines[before]).find()) {
            while(start>0) {
                int previous=start-1;while(previous>=0&&lines[previous].trim().isEmpty())previous--;
                if(previous<0||!TABLE_PREFACE.matcher(lines[previous]).find()&&!FOREIGN.matcher(lines[previous]).find())break;
                start=previous;
            }
        }
        return String.join("\n",Arrays.copyOfRange(lines,start,before+1));
    }
    private static String identity(Object value) {return value==null?"":DraftEvidenceQuotes.textIdentity(String.valueOf(value)).toLowerCase(Locale.ROOT);}
    private static String cell(List<String> cells,int index) {return index>=0&&index<cells.size()?cells.get(index):"";}
    private static List<String> cells(String line) {
        String[] raw=line.trim().split("\\|",-1);List<String> result=new ArrayList<>();int first=raw[0].trim().isEmpty()?1:0,last=raw.length;
        if(last>first&&raw[last-1].trim().isEmpty())last--;
        for(int i=first;i<last;i++)result.add(raw[i].trim());return result;
    }
    private static boolean separator(List<String> cells) {
        if(cells.isEmpty())return false;for(String cell:cells)if(!cell.matches(":?-{2,}:?"))return false;return true;
    }
    private static final class Header {int designation=-1,types=-1,location=-1,columns;boolean ambiguous;}
    private static final class Table {final List<Row> rows=new ArrayList<>();boolean asserted,complete;}
    private static final class Row {
        final String designation,location;final Set<String> types;
        Row(String designation,Set<String> types,String location) {this.designation=designation;this.types=types;this.location=location;}
        boolean same(Row other) {return designation.equals(other.designation)&&location.equals(other.location)&&Objects.equals(types,other.types);}
    }
}
