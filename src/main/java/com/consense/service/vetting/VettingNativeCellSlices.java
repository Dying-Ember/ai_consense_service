package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** Native cell positions derive from the declared ordered cells, never from splitting flattened pipes. */
public final class VettingNativeCellSlices {
    public static final String VERSION = "selected-native-cell-slices-v2";
    private static final Pattern POSITION = Pattern.compile("body/(0|[1-9]\\d*)/table-row/(0|[1-9]\\d*) @(0|[1-9]\\d*)");
    private VettingNativeCellSlices() { }

    static NativeTableSlice slice(Chunk c, Part p, TableRow row) {
        if(row.getCells()==null||row.getCells().isEmpty()||row.getCells().stream().anyMatch(Objects::isNull))return null;
        String whole=String.join(" | ",row.getCells());
        if(p.getStartOffset()<0||p.getEndOffset()>whole.length()||p.getEndOffset()<=p.getStartOffset()
                ||!whole.substring(p.getStartOffset(),p.getEndOffset()).equals(p.getText()))return null;
        NativeTableSlice t=new NativeTableSlice();t.setVersion(VERSION);t.setTableLocation(row.getTableLocation());
        t.setRowIndex(row.getRowIndex());t.setRowUtf16Length(whole.length());t.setCellCount(row.getCells().size());
        t.setRowTextSha256(VettingCorpus.hash(whole));List<NativeCellLayout> layout=new ArrayList<>();int offset=0;
        for(int i=0;i<row.getCells().size();i++) {
            String cell=row.getCells().get(i);NativeCellLayout d=new NativeCellLayout();d.setColumnIndex(i);
            d.setRowStartOffset(offset);d.setRowEndOffset(offset+cell.length());d.setCellTextSha256(VettingCorpus.hash(cell));layout.add(d);
            offset+=cell.length()+(i+1<row.getCells().size()?3:0);
        }
        t.setCellLayout(layout);t.setCellLayoutSha256(VettingCorpus.hash(JsonUtils.write(layout)));
        t.setSourceIdentityHash(identity(c,p,t));t.setCellSlices(expectedSlices(p,t));return t;
    }

    public static final class Observation {
        private final Map<String,List<Map<String,Object>>> slices=new HashMap<>(), rows=new HashMap<>(), unknown=new HashMap<>();
        public List<Map<String,Object>> slices(String id){return slices.getOrDefault(id,Collections.emptyList());}
        public List<Map<String,Object>> rows(String id){return rows.getOrDefault(id,Collections.emptyList());}
        public List<Map<String,Object>> unknown(String id){return unknown.getOrDefault(id,Collections.emptyList());}
    }
    private static final class Member {final Chunk c;final Part p;Member(Chunk c,Part p){this.c=c;this.p=p;}}

    /** This projection consults selected text only. Complete cells/rows require exact selected union coverage and digests. */
    public static Observation observe(List<Chunk> selected) {
        Observation out=new Observation();Map<List<String>,List<Member>> groups=new LinkedHashMap<>();
        for(Chunk c:selected)if(c.getParts()!=null)for(Part p:c.getParts())if(p!=null) {
            if(p.getTableSlice()!=null)out.slices.computeIfAbsent(c.getId(),k->new ArrayList<>());
            if(p.getTableSlice()!=null||isRow(p))groups.computeIfAbsent(groupKey(c,p),k->new ArrayList<>()).add(new Member(c,p));
        }
        for(List<Member> members:groups.values()) {
            if(members.stream().noneMatch(m->m.p.getTableSlice()!=null))continue;
            String failure=null;NativeTableSlice t=null;
            for(Member m:members) {
                String reason=validate(m.c,m.p);
                if(reason!=null){failure=reason;break;}
                if(t==null)t=m.p.getTableSlice();
                else if(!sameRow(t,m.p.getTableSlice())){failure="conflicting_native_row_descriptors";break;}
            }
            if(failure!=null){unknown(out,members,failure);continue;}
            // A bounded row-length allocation, derived from the submitted declared layout. No unselected values are read.
            TreeMap<Integer,Character> text=new TreeMap<>();boolean conflict=false;
            for(Member m:members)for(int i=0;i<m.p.getText().length();i++) {
                int at=m.p.getStartOffset()+i;Character prior=text.putIfAbsent(at,m.p.getText().charAt(i));
                if(prior!=null&&prior!=m.p.getText().charAt(i)){conflict=true;break;}
            }
            if(conflict){unknown(out,members,"conflicting_selected_utf16_overlap");continue;}
            boolean full=covered(text,0,t.getRowUtf16Length());String rowText=full?read(text,0,t.getRowUtf16Length()):null;
            if(full&&!VettingCorpus.hash(rowText).equals(t.getRowTextSha256())){unknown(out,members,"selected_row_digest_mismatch");continue;}
            List<Map<String,Object>> cells=new ArrayList<>();List<String> completeValues=new ArrayList<>();boolean allCells=true;
            for(NativeCellLayout d:t.getCellLayout()) {
                boolean complete=d.getRowStartOffset()==d.getRowEndOffset()?emptyBoundaryCovered(text,t,d):covered(text,d.getRowStartOffset(),d.getRowEndOffset());
                String value=complete?read(text,d.getRowStartOffset(),d.getRowEndOffset()):null;
                if(complete&&!VettingCorpus.hash(value).equals(d.getCellTextSha256())){failure="selected_cell_digest_mismatch";break;}
                allCells&=complete;if(complete)completeValues.add(value);
                List<Map<String,Object>> selectedSlices=new ArrayList<>();
                for(Member m:members)for(NativeCellSlice s:m.p.getTableSlice().getCellSlices())if(s.getColumnIndex()==d.getColumnIndex())
                    selectedSlices.add(map("chunkId",m.c.getId(),"partAnchor",m.p.getAnchor(),"rowStartOffset",s.getRowStartOffset(),"rowEndOffset",s.getRowEndOffset(),"text",s.getText()));
                Map<String,Object> cell=map("columnIndex",d.getColumnIndex(),"rowStartOffset",d.getRowStartOffset(),"rowEndOffset",d.getRowEndOffset(),
                        "status",complete?"complete_selected_cell":"partial_selected_cell","selectedSlices",selectedSlices,
                        "missingUtf16Ranges",missing(text,d.getRowStartOffset(),d.getRowEndOffset()));
                if(complete)cell.put("text",value);cells.add(cell);
            }
            if(failure!=null){unknown(out,members,failure);continue;}
            if(full&&(!allCells||!String.join(" | ",completeValues).equals(rowText))){unknown(out,members,"selected_native_join_mismatch");continue;}
            List<Map<String,Object>> contributors=new ArrayList<>();Set<String> contributorIds=new LinkedHashSet<>();
            for(Member m:members) {
                contributorIds.add(m.c.getId());contributors.add(map("chunkId",m.c.getId(),"partAnchor",m.p.getAnchor(),"blockId",m.p.getBlockId(),"startOffset",m.p.getStartOffset(),"endOffset",m.p.getEndOffset()));
                out.slices.computeIfAbsent(m.c.getId(),k->new ArrayList<>()).add(map("partAnchor",m.p.getAnchor(),"blockId",m.p.getBlockId(),
                        "startOffset",m.p.getStartOffset(),"endOffset",m.p.getEndOffset(),"tableSlice",m.p.getTableSlice()));
            }
            Map<String,Object> row=map("viewScope","cross-selected-chunk source row; not a single chunk citation or full table",
                    "sourceIdentityHash",t.getSourceIdentityHash(),"documentId",members.get(0).c.getDocumentId(),"sourceHash",members.get(0).c.getSourceHash(),
                    "role",members.get(0).c.getRole(),"blockId",members.get(0).p.getBlockId(),"tableLocation",t.getTableLocation(),"rowIndex",t.getRowIndex(),
                    "rowUtf16Length",t.getRowUtf16Length(),"rowTextSha256",t.getRowTextSha256(),"cellLayoutSha256",t.getCellLayoutSha256(),
                    "status",full?"complete_selected_row":"partial_selected_row","contributorChunkIds",new ArrayList<>(contributorIds),"contributors",contributors,
                    "cells",cells,"missingUtf16Ranges",missing(text,0,t.getRowUtf16Length()),
                    "interpretation","native selected cell relationships only; header, merged grid, complete table, text accuracy and legal applicability unknown");
            if(full)row.put("text",rowText);
            out.rows.computeIfAbsent(members.get(0).c.getId(),k->new ArrayList<>()).add(row);
        }
        return out;
    }

    private static String validate(Chunk c,Part p) {
        NativeTableSlice t=p.getTableSlice();if(t==null)return "selected_row_fragment_has_no_cell_layout";
        if(!VERSION.equals(t.getVersion())||!VettingCorpus.NATIVE_TABLE_METADATA_VERSION.equals(c.getNativeTableMetadataVersion())
                ||!VettingCorpus.METADATA_VERSION.equals(c.getMetadataVersion())||!VettingCorpus.SEGMENTATION_VERSION.equals(c.getSegmentationVersion()))return "cell_slice_metadata_version_unknown";
        if(!present(c.getDocumentId())||!present(c.getSourceHash())||!Arrays.asList("tender","standard","project_fact","package_manifest").contains(c.getRole())
                ||!VettingSourceQuality.project(c).containsKey("observationScope"))return "cell_slice_source_identity_unknown";
        if(c.getContent()==null||c.getParts()==null||c.getParts().stream().anyMatch(x->x==null||x.getText()==null)
                ||!c.getContent().equals(c.getParts().stream().map(Part::getText).collect(Collectors.joining("\n"))))return "cell_slice_body_unbound";
        String prefix=present(c.getClauseId())?c.getClauseId()+" · ":"";
        if(p.getAnchor()==null||!p.getAnchor().startsWith(prefix))return "cell_slice_anchor_unknown";
        Matcher m=POSITION.matcher(p.getAnchor().substring(prefix.length()));
        if(!m.matches()||!Objects.equals(p.getBlockId(),"body:"+m.group(1)+":table-row:"+m.group(2))
                ||!Objects.equals(t.getTableLocation(),"body/"+m.group(1))||!m.group(2).equals(Integer.toString(t.getRowIndex()))
                ||!m.group(3).equals(Integer.toString(p.getStartOffset())))return "cell_slice_anchor_unknown";
        if(p.getText()==null||p.getStartOffset()<0||p.getEndOffset()<=p.getStartOffset()||p.getEndOffset()>t.getRowUtf16Length()
                ||p.getEndOffset()-p.getStartOffset()!=p.getText().length()||!validUtf16(p.getText()))return "cell_slice_range_invalid";
        if(t.getRowUtf16Length()<=0||t.getCellLayout()==null||t.getCellLayout().isEmpty()||t.getCellCount()!=t.getCellLayout().size())return "cell_layout_invalid";
        long cursor=0;
        for(int i=0;i<t.getCellCount();i++) {
            NativeCellLayout d=t.getCellLayout().get(i);
            if(d==null||d.getColumnIndex()!=i||d.getRowStartOffset()!=cursor||d.getRowEndOffset()<cursor||d.getRowEndOffset()>t.getRowUtf16Length()||!digest(d.getCellTextSha256()))return "cell_layout_invalid";
            cursor=d.getRowEndOffset()+(i+1<t.getCellCount()?3:0);
        }
        if(cursor!=t.getRowUtf16Length()||!digest(t.getRowTextSha256())||!VettingCorpus.hash(JsonUtils.write(t.getCellLayout())).equals(t.getCellLayoutSha256())
                ||!identity(c,p,t).equals(t.getSourceIdentityHash()))return "cell_layout_identity_mismatch";
        if(t.getCellSlices()==null||!expectedSlices(p,t).equals(t.getCellSlices()))return "cell_slices_do_not_match_selected_text";
        // Every observed separator must agree with the native join, including partial separator observations.
        for(int i=0;i+1<t.getCellCount();i++) {
            int start=t.getCellLayout().get(i).getRowEndOffset();
            for(int at=Math.max(start,p.getStartOffset());at<Math.min(start+3,p.getEndOffset());at++)
                if(p.getText().charAt(at-p.getStartOffset())!=" | ".charAt(at-start))return "cell_separator_mismatch";
        }
        return null;
    }

    private static List<NativeCellSlice> expectedSlices(Part p,NativeTableSlice t) {
        List<NativeCellSlice> result=new ArrayList<>();
        for(NativeCellLayout d:t.getCellLayout()) {
            int start=Math.max(d.getRowStartOffset(),p.getStartOffset()),end=Math.min(d.getRowEndOffset(),p.getEndOffset());
            boolean empty=d.getRowStartOffset()==d.getRowEndOffset();
            if(empty) {
                int left=d.getColumnIndex()>0?d.getRowStartOffset()-3:d.getRowStartOffset();
                int right=d.getColumnIndex()+1<t.getCellCount()?d.getRowEndOffset()+3:d.getRowEndOffset();
                if(p.getStartOffset()>left||p.getEndOffset()<right)continue;start=d.getRowStartOffset();end=start;
            } else if(end<=start)continue;
            NativeCellSlice s=new NativeCellSlice();s.setColumnIndex(d.getColumnIndex());s.setRowStartOffset(start);s.setRowEndOffset(end);
            s.setText(p.getText().substring(start-p.getStartOffset(),end-p.getStartOffset()));s.setTextSha256(VettingCorpus.hash(s.getText()));result.add(s);
        }
        return result;
    }
    private static boolean emptyBoundaryCovered(SortedMap<Integer,Character> text,NativeTableSlice t,NativeCellLayout d) {
        int left=d.getColumnIndex()>0?d.getRowStartOffset()-3:d.getRowStartOffset();
        int right=d.getColumnIndex()+1<t.getCellCount()?d.getRowEndOffset()+3:d.getRowEndOffset();
        return right>left&&covered(text,left,right);
    }
    private static String identity(Chunk c,Part p,NativeTableSlice t) {
        // Portable content/layout identity excludes deployment database IDs. Observation groups below still include the actual document ID.
        return VettingCorpus.hash(JsonUtils.write(Arrays.asList(c.getSourceHash(),c.getRole(),c.getMetadataVersion(),c.getSegmentationVersion(),
                c.getNativeTableMetadataVersion(),c.getSourceQualityMetadataVersion(),c.getSourceQualityHash(),p.getBlockId(),t.getTableLocation(),t.getRowIndex(),
                t.getRowUtf16Length(),t.getRowTextSha256(),t.getCellLayoutSha256())));
    }
    private static List<String> groupKey(Chunk c,Part p) {return Arrays.asList(c.getDocumentId(),c.getSourceHash(),c.getRole(),c.getMetadataVersion(),c.getSegmentationVersion(),
            c.getNativeTableMetadataVersion(),c.getSourceQualityMetadataVersion(),c.getSourceQualityHash(),p.getBlockId());}
    private static boolean sameRow(NativeTableSlice a,NativeTableSlice b){return Objects.equals(a.getSourceIdentityHash(),b.getSourceIdentityHash())&&Objects.equals(a.getCellLayout(),b.getCellLayout());}
    private static boolean isRow(Part p){return p.getAnchor()!=null&&p.getAnchor().contains("/table-row/");}
    private static boolean covered(SortedMap<Integer,Character> text,int start,int end){return text.subMap(start,end).size()==end-start;}
    private static String read(SortedMap<Integer,Character> text,int start,int end){StringBuilder b=new StringBuilder();for(int i=start;i<end;i++)b.append(text.get(i));return b.toString();}
    private static List<List<Integer>> missing(SortedMap<Integer,Character> text,int start,int end) {
        List<List<Integer>> result=new ArrayList<>();int cursor=start;
        for(int at:text.subMap(start,end).keySet()){if(at>cursor)result.add(Arrays.asList(cursor,at));cursor=at+1;}
        if(cursor<end)result.add(Arrays.asList(cursor,end));return result;
    }
    private static boolean validUtf16(String s){for(int i=0;i<s.length();i++)if(Character.isHighSurrogate(s.charAt(i))){if(i+1==s.length()||!Character.isLowSurrogate(s.charAt(++i)))return false;}else if(Character.isLowSurrogate(s.charAt(i)))return false;return true;}
    private static boolean digest(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    private static boolean present(String s){return s!=null&&!s.trim().isEmpty();}
    private static void unknown(Observation out,List<Member> members,String reason){for(Member m:members)out.unknown.computeIfAbsent(m.c.getId(),k->new ArrayList<>()).add(map("partAnchor",m.p.getAnchor(),"blockId",m.p.getBlockId(),"reason",reason));}
    private static Map<String,Object> map(Object... a){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<a.length;i+=2)m.put((String)a[i],a[i+1]);return m;}
}
