package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingCorpus.TableRow;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Observations from selected source records only, never an absence or completeness judgment. */
public final class VettingSourceInventory {
    private static final String LABEL="[\\p{L}\\p{N}]+(?:[./-][\\p{L}\\p{N}]+)*";
    private static final Pattern STANDALONE_LABEL=Pattern.compile(
            "(?:APPENDIX|ANNEX|SCHEDULE)\\s+"+LABEL+"(?:\\s+TO\\s+(?:APPENDIX|ANNEX|SCHEDULE)\\s+"+LABEL+")?",
            Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Pattern TABLE_TEXT_POSITION=Pattern.compile("(?:^|\\s)body/(\\d+)/table-row/(\\d+)(?:\\s+@(\\d+))?$");
    private VettingSourceInventory() { }

    /** Compact prompt view; the full describe method remains available for source probes. */
    public static Map<String,Object> compact(List<Chunk> selected) {
        List<Map<String,Object>> excerpts=VettingSourceMaterial.project(selected),records=new ArrayList<>();
        for(int i=0;i<selected.size();i++) {
            Chunk c=selected.get(i);Map<String,Object> excerpt=excerpts.get(i);
            String label=c.getContent()==null?null:c.getContent().trim();
            if(label==null||label.length()>160||!STANDALONE_LABEL.matcher(label).matches())label=null;
            int count=((List<?>)excerpt.get("nativeRows")).size();
            Map<String,Object> quality=VettingSourceQuality.project(c);
            records.add(map("chunkId",c.getId(),"standaloneLabel",label,"verifiedNativeRowPartsCount",count==0?"unknown":count,
                    "sourceQualityHash",c.getSourceQualityHash(),"parseStatus",quality.get("parseStatus"),"ocrQualityStatus",quality.get("ocrQualityStatus"),"textAccuracy","unverified",
                    "unresolvedNativeRowPartsCount",((List<?>)excerpt.get("unresolvedNativeRowParts")).size()));
        }
        return map("inventoryVersion","selected-source-compact-v1","selectedOnly",true,"records",records,
                "coverage","selected_chunks_only; zero verified rows never establishes absence",
                "fullSourceCoverage","unknown","contractApplicability","unknown");
    }

    public static Map<String,Object> describe(List<Chunk> selected) {
        Objects.requireNonNull(selected,"Selected chunks are required");
        Set<String> ids=new LinkedHashSet<>();
        Map<List<String>,Set<String>> tableChunks=new HashMap<>(),headingChunks=new HashMap<>();
        for(Chunk c:selected) {
            if(c==null||!present(c.getId())||!ids.add(c.getId()))throw new IllegalArgumentException("Selected chunk IDs must be present and unique");
            if(sourceKnown(c)&&present(c.getClauseHeadingLocation()))add(headingChunks,key(c,c.getClauseHeadingLocation()),c.getId());
            if(sourceKnown(c)&&c.getParts()!=null)for(Part p:c.getParts())
                if(p!=null&&p.getTable()!=null&&present(p.getTable().getTableLocation()))add(tableChunks,key(c,p.getTable().getTableLocation()),c.getId());
        }
        List<Map<String,Object>> records=new ArrayList<>();
        for(Chunk c:selected) {
            String content=c.getContent(),label=content==null?null:content.trim();
            if(label==null||!STANDALONE_LABEL.matcher(label).matches())label=null;
            List<String> nativeRowAnchors=new ArrayList<>(),nativeRowBlockIds=new ArrayList<>();
            List<Map<String,Object>> rowTextPositions=new ArrayList<>();
            Set<String> nativeTables=new LinkedHashSet<>(),rowIdentities=new HashSet<>();
            String unknown=null;
            if(!sourceKnown(c)||!present(c.getAnchor()))unknown="source_identity_or_anchor_unknown";
            if(c.getParts()==null||c.getParts().isEmpty())unknown="source_parts_unavailable";
            else for(Part p:c.getParts()) {
                if(p==null){unknown="source_part_unavailable";continue;}
                Map<String,Object> textPosition=tableTextPosition(c,p);
                if(textPosition!=null)rowTextPositions.add(textPosition);
                if(p.getTable()==null) {
                    if(present(p.getAnchor())&&p.getAnchor().contains("/table-row/"))unknown="table_row_metadata_unavailable";
                    continue;
                }
                TableRow row=p.getTable();
                if(!nativeRow(c,p)){unknown="table_row_metadata_or_slice_unknown";continue;}
                nativeRowAnchors.add(p.getAnchor());nativeRowBlockIds.add(p.getBlockId());nativeTables.add(row.getTableLocation());
                if(!rowIdentities.add(row.getTableLocation()+"/"+row.getRowIndex()))unknown="repeated_table_row_fragment";
                if(sourceKnown(c)&&tableChunks.get(key(c,row.getTableLocation())).size()>1)unknown="table_spans_multiple_selected_chunks";
            }
            if(nativeRowAnchors.isEmpty()&&unknown==null)unknown="no_native_table_row_metadata_observed";
            if(sourceKnown(c)&&present(c.getClauseHeadingLocation())&&headingChunks.get(key(c,c.getClauseHeadingLocation())).size()>1
                    &&!nativeRowAnchors.isEmpty())unknown="source_heading_spans_multiple_selected_chunks";
            Map<String,Object> record=map("chunkId",c.getId(),"documentId",c.getDocumentId(),"sourceHash",c.getSourceHash(),"role",c.getRole(),
                    "anchor",c.getAnchor(),"firstLine",content==null?null:content.split("\\R",2)[0],"firstLineMeaning","source_text_only",
                    "standaloneLabel",label,"standaloneLabelMeaning","source_label_only; section relationship and layout role unknown",
                    "observedNativeTableRowPartsCount",unknown==null?nativeRowAnchors.size():"unknown",
                    "tableCountScope","only observed native row parts within this selected chunk; never full-table row count",
                    "tableCountUnknownReason",unknown,"observedNativeRowPartAnchors",nativeRowAnchors,
                    "observedNativeRowPartBlockIds",nativeRowBlockIds,"observedNativeTableLocations",nativeTables,
                    "observedTableTextPartsCount",rowTextPositions.isEmpty()?"unknown":rowTextPositions.size(),"rowTextPositions",rowTextPositions,
                    "tableTextCountScope","positioned text parts in this selected chunk only; cells, complete rows and complete table remain unknown",
                    "fullTableCompleteness","unknown");
            records.add(record);
        }
        return map("inventoryVersion","selected-source-observations-v1","selectedOnly",true,"presentChunkIds",new ArrayList<>(ids),"records",records,
                "coverage","selected_chunks_only; unselected sections, table continuations and absent native metadata remain unknown",
                "fullSourceCoverage","unknown","contractApplicability","unknown",
                "interpretation","Presence observations require inspection of the supplied text; unknown table metadata never establishes absence.");
    }

    private static boolean nativeRow(Chunk c,Part p) {
        TableRow row=p.getTable();
        return row!=null&&present(row.getTableLocation())&&row.getRowIndex()>=0&&row.getCells()!=null&&!row.getCells().isEmpty()
                &&row.getCells().stream().noneMatch(Objects::isNull)&&present(p.getAnchor())&&present(p.getBlockId())&&p.getText()!=null
                &&p.getAnchor().endsWith(row.getTableLocation()+"/table-row/"+row.getRowIndex())
                &&p.getStartOffset()==0&&p.getEndOffset()==p.getText().length()
                &&String.join(" | ",row.getCells()).equals(p.getText())&&c.getContent()!=null&&c.getContent().contains(p.getText());
    }
    private static Map<String,Object> tableTextPosition(Chunk c,Part p) {
        if(!sourceKnown(c)||!present(c.getAnchor())||!present(p.getAnchor())||!present(p.getBlockId())||!present(p.getText())
                ||c.getContent()==null||!c.getContent().contains(p.getText())||p.getStartOffset()<0
                ||p.getEndOffset()-p.getStartOffset()!=p.getText().length())return null;
        Matcher position=TABLE_TEXT_POSITION.matcher(p.getAnchor());
        if(!position.find()||!p.getBlockId().equals("body:"+position.group(1)+":table-row:"+position.group(2)))return null;
        if(position.group(3)==null?p.getStartOffset()!=0:!position.group(3).equals(Integer.toString(p.getStartOffset())))return null;
        return map("anchor",p.getAnchor(),"blockId",p.getBlockId(),"tableLocation","body/"+position.group(1),
                "rowIndexText",position.group(2),"startOffset",p.getStartOffset(),"endOffset",p.getEndOffset(),"textSliceOnly",true);
    }
    private static boolean sourceKnown(Chunk c){return present(c.getDocumentId())&&present(c.getSourceHash())&&present(c.getRole());}
    private static boolean present(String value){return value!=null&&!value.trim().isEmpty();}
    private static List<String> key(Chunk c,String location){return Arrays.asList(c.getDocumentId(),c.getSourceHash(),c.getRole(),location);}
    private static void add(Map<List<String>,Set<String>> map,List<String> key,String id){map.computeIfAbsent(key,k->new HashSet<>()).add(id);}
    private static Map<String,Object> map(Object... entries){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;}
}
