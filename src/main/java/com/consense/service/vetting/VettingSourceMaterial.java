package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingCorpus.TableRow;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Source-bound observations from selected chunks only. Never an inferred header, grid or adoption assertion. */
public final class VettingSourceMaterial {
    public static final String PROJECTION_VERSION="selected-native-source-material-form-observations-v6";
    private static final Pattern ROW_ANCHOR=Pattern.compile("body/(\\d+)/table-row/(\\d+)(?: @(\\d+))?");
    private VettingSourceMaterial() { }

    public static List<Map<String,Object>> project(List<Chunk> selected) {
        return project(selected,FixedVettingReferences.empty());
    }

    static List<Map<String,Object>> project(List<Chunk> selected,FixedVettingReferences fixedReferences) {
        Objects.requireNonNull(selected,"Selected source chunks are required");
        Set<String> ids=new HashSet<>();Map<List<String>,Integer> blocks=new HashMap<>(),positions=new HashMap<>();
        for(Chunk c:selected) {
            if(c==null||!present(c.getId())||!ids.add(c.getId()))throw new IllegalArgumentException("Selected source IDs must be present and unique");
            if(c.getParts()!=null)for(Part p:c.getParts())if(p!=null) {
                if(present(p.getBlockId()))blocks.merge(key(c,p.getBlockId()),1,Integer::sum);
                String position=position(c,p);
                if(position!=null)positions.merge(key(c,position),1,Integer::sum);
            }
        }
        VettingNativeCellSlices.Observation cellObservations=VettingNativeCellSlices.observe(selected);
        OerModelMaterial.Request modelMaterial=new OerModelMaterial.Request();
        Map<String,Map<String,Object>> fixedExcerpts=fixedReferences.modelExcerpts(selected);
        List<Map<String,Object>> excerpts=new ArrayList<>();
        for(Chunk c:selected) {
            if(FixedVettingReferences.isFixed(c)){excerpts.add(fixedExcerpts.get(c.getId()));continue;}
            if(c.getCanonicalPayload()!=null||c.getCanonicalEvidence()!=null){excerpts.add(canonical(c,modelMaterial));continue;}
            List<Map<String,Object>> rows=new ArrayList<>(),unknown=new ArrayList<>(),methods=new ArrayList<>();
            boolean bodyBound=bodyMatchesParts(c);int cursor=0;
            if(c.getParts()==null||c.getParts().isEmpty())unknown.add(map("reason","source_parts_unavailable"));
            else for(Part p:c.getParts()) {
                int length=p==null||p.getText()==null?0:p.getText().length();
                if(p!=null) {
                    Map<String,Object> method=map("partAnchor",p.getAnchor(),"blockId",p.getBlockId(),"startOffset",p.getStartOffset(),"endOffset",p.getEndOffset(),
                            "extractionSource",bodyBound&&present(p.getExtractionSource())?p.getExtractionSource():"unknown");
                    if(p.getPdfFormField()!=null) {
                        boolean bound=formMatchesPart(c,p,bodyBound,blocks);
                        method.put("formObservationStatus",bound?"observed_selected_original_widget":"unknown_part_binding");
                        method.put("pdfFormField",bound?formObservation(p):null);
                        method.put("pdfFormFieldSourceSha256",bound?formSourceSha256(JsonUtils.mapper().valueToTree(p.getPdfFormField())):null);
                        method.put("nativeVisibilityStatus",bound?p.getNativeVisibilityStatus():"unknown");
                        method.put("formInterpretation","Stored value, selected appearance, blank/nontext and conflict are observations only; blank meaning, raster visibility, parent-child selection and legal OR applicability remain unknown. Raw streams, glyph coordinates and audit fields remain in source storage identified by pdfFormFieldSourceSha256.");
                    }
                    methods.add(method);
                }
                if(p!=null&&looksLikeRow(p)) {
                    String reason=unknownReason(c,p,bodyBound,blocks,positions);
                    if(reason==null) {
                        TableRow t=p.getTable();
                        rows.add(map("partAnchor",p.getAnchor(),"blockId",p.getBlockId(),"tableLocation",t.getTableLocation(),
                                "rowIndex",t.getRowIndex(),"startOffset",p.getStartOffset(),"endOffset",p.getEndOffset(),
                                "chunkStartOffset",cursor,"chunkEndOffset",cursor+length,"cells",new ArrayList<>(t.getCells())));
                    } else unknown.add(map("partAnchor",p.getAnchor(),"blockId",p.getBlockId(),"reason",reason));
                }
                cursor+=length+1;
            }
            if(!bodyBound&&unknown.isEmpty())unknown.add(map("reason","source_parts_do_not_match_selected_body"));
            excerpts.add(map("id",c.getId(),"file",nvl(c.getFileKey())+" · "+nvl(c.getFileName()),"documentId",c.getDocumentId(),
                    "sourceHash",c.getSourceHash(),"role",c.getRole(),"metadataVersion",c.getMetadataVersion(),
                    "segmentationVersion",c.getSegmentationVersion(),"nativeTableMetadataVersion",c.getNativeTableMetadataVersion(),
                    "sourceQualityMetadataVersion",c.getSourceQualityMetadataVersion(),"sourceQualityHash",c.getSourceQualityHash(),"sourceQuality",VettingSourceQuality.project(c),
                    "selectedPartExtractionObservations",methods,
                    "partExtractionObservationScope","selected source part declarations only; text accuracy unverified",
                    "anchor",c.getAnchor(),"clauseId",c.getClauseId(),"clauseHeadingLocation",c.getClauseHeadingLocation(),"content",c.getContent(),
                    "projectionVersion",PROJECTION_VERSION,"nativeRows",rows,"unresolvedNativeRowParts",unknown,
                    "nativeCellSlices",cellObservations.slices(c.getId()),"reconstructedNativeRows",cellObservations.rows(c.getId()),
                    "unresolvedNativeCellSlices",cellObservations.unknown(c.getId()),
                    "nativeCellStructureScope","selected source UTF16 intersections only; complete cells and rows require proved union coverage; unselected headers and table grid unknown",
                    "nativeRowsStatus",rows.isEmpty()?"unknown":"observed_selected_rows_only",
                    "tableInterpretation", "selected complete row parts only; headers, merged grid, full table and contractual adoption unknown"));
        }
        return excerpts;
    }

    private static Map<String,Object> canonical(Chunk c,OerModelMaterial.Request modelMaterial){
        String rejected=OerBusinessEvidence.canonicalViewStatus(c);if(rejected!=null)throw new IllegalArgumentException(rejected);
        com.fasterxml.jackson.databind.JsonNode raw=c.getCanonicalPayload();
        com.fasterxml.jackson.databind.node.ObjectNode structure=modelMaterial.project(c.getCanonicalEvidence(),raw,c.getRole());
        structure.set("canonicalScope",raw.path("canonicalScope").deepCopy());
        return map("id",c.getId(),"file",nvl(c.getFileKey())+" · "+nvl(c.getFileName()),"documentId",c.getDocumentId(),"sourceHash",c.getSourceHash(),
                "role",c.getRole(),"metadataVersion",c.getMetadataVersion(),"segmentationVersion",c.getSegmentationVersion(),
                "nativeTableMetadataVersion",c.getNativeTableMetadataVersion(),"sourceQualityMetadataVersion",c.getSourceQualityMetadataVersion(),
                "sourceQualityHash",c.getSourceQualityHash(),"sourceQuality",raw.path("sourceQuality").deepCopy(),
                "anchor",c.getAnchor(),"clauseId",c.getClauseId(),"clauseHeadingLocation",c.getClauseHeadingLocation(),"content",c.getContent(),
                "projectionVersion",OerModelMaterial.VERSION,"canonicalStructure",structure,
                "nativeRows",Collections.emptyList(),"unresolvedNativeRowParts",Collections.emptyList(),"nativeCellSlices",Collections.emptyList(),
                "reconstructedNativeRows",Collections.emptyList(),"unresolvedNativeCellSlices",Collections.emptyList(),
                "nativeRowsStatus","not_applicable_to_canonical_structure","tableInterpretation","adopted whole-unit observations; edit state, blank meaning, applicability and semantic coverage remain declared unknown");
    }

    /** Keeps the existing project-fact interpretation only when its row and header text are both selected. */
    public static VettingProjectFactTable.Result projectFactRows(List<Chunk> selected,List<String> references) {
        List<Map<String,Object>> excerpts=project(selected);
        Map<List<String>,Part> validatedParts=new HashMap<>();
        for(int i=0;i<selected.size();i++) {
            Chunk c=selected.get(i);Set<String> validIds=new HashSet<>();
            for(Object row:(List<?>)excerpts.get(i).get("nativeRows"))validIds.add((String)((Map<?,?>)row).get("blockId"));
            if(c.getParts()!=null)for(Part p:c.getParts())if(p!=null&&validIds.contains(p.getBlockId()))validatedParts.put(key(c,p.getBlockId()),p);
        }
        List<Chunk> bounded=new ArrayList<>();
        for(Chunk original:selected) {
            Chunk c=JsonUtils.read(JsonUtils.write(original),Chunk.class);
            if(c.getParts()!=null)for(Part p:c.getParts())if(p!=null&&p.getTable()!=null) {
                if(!validatedParts.containsKey(key(c,p.getBlockId()))) {p.setTable(null);continue;}
                TableRow t=p.getTable();Part header=validatedParts.get(key(c,t.getHeaderBlockId()));
                boolean selectedHeader=header!=null&&header.getTable()!=null&&header.getTable().getRowIndex()==0
                        &&Objects.equals(header.getTable().getTableLocation(),t.getTableLocation())
                        &&Objects.equals(t.getHeaderLocation(),header.getTable().getTableLocation()+"/table-row/0")
                        &&Objects.equals(t.getHeaders(),header.getTable().getCells())
                        &&"project_fact_first_native_row_heuristic_compatibility".equals(t.getHeaderBasis());
                if(!selectedHeader) {t.setHeaders(null);t.setHeaderBlockId(null);t.setHeaderLocation(null);t.setHeaderBasis("unknown");}
            }
            bounded.add(c);
        }
        return VettingProjectFactTable.rows(bounded,references);
    }

    private static String unknownReason(Chunk c,Part p,boolean bodyBound,Map<List<String>,Integer> blocks,Map<List<String>,Integer> positions) {
        if(!present(c.getDocumentId())||!present(c.getSourceHash())||!Arrays.asList("tender","standard","project_fact","package_manifest").contains(c.getRole())||!present(c.getAnchor()))return "source_identity_unknown";
        if(!VettingCorpus.NATIVE_TABLE_METADATA_VERSION.equals(c.getNativeTableMetadataVersion()))return "native_metadata_version_unknown";
        if(!VettingCorpus.METADATA_VERSION.equals(c.getMetadataVersion())||!VettingCorpus.SEGMENTATION_VERSION.equals(c.getSegmentationVersion()))return "chunk_metadata_version_unknown";
        if(!bodyBound)return "source_parts_do_not_match_selected_body";
        String position=position(c,p);if(position==null)return "part_anchor_or_block_identity_unknown";
        if(!Integer.valueOf(1).equals(blocks.get(key(c,p.getBlockId())))||!Integer.valueOf(1).equals(positions.get(key(c,position))))return "ambiguous_selected_row_identity";
        TableRow t=p.getTable();if(t==null)return "native_cells_unavailable";
        if(p.getText()==null||p.getStartOffset()!=0||p.getEndOffset()!=p.getText().length()||p.getAnchor().matches(".* @\\d+$"))return "partial_source_row";
        if(t.getRowIndex()<0||!position.equals(nvl(t.getTableLocation())+"/table-row/"+t.getRowIndex()))return "table_position_does_not_match_part";
        if(t.getCells()==null||t.getCells().isEmpty()||t.getCells().stream().anyMatch(Objects::isNull)
                ||!String.join(" | ",t.getCells()).equals(p.getText()))return "native_cells_do_not_match_part_text";
        return null;
    }
    private static boolean bodyMatchesParts(Chunk c) {
        return c.getContent()!=null&&c.getParts()!=null&&!c.getParts().isEmpty()
                &&c.getParts().stream().allMatch(p->p!=null&&p.getText()!=null)
                &&c.getContent().equals(c.getParts().stream().map(Part::getText).collect(Collectors.joining("\n")));
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode formObservation(Part p) {
        com.fasterxml.jackson.databind.node.ObjectNode source=JsonUtils.mapper().valueToTree(p.getPdfFormField()),out=JsonUtils.mapper().createObjectNode();
        for(String name:Arrays.asList("id","fieldName","fieldType","fieldValue","appearanceValue","appearanceState","visibilityStatus","valueStatus","pageNo","needAppearances","appearancePresent","conflict","appearanceStreamObservationStatus","appearanceGlyphObservationStatus","limitations"))
            if(source.has(name))out.set(name,source.get(name).deepCopy());
        return out;
    }
    /** Hash-only binary64 values avoid Java/Python decimal spelling differences without changing source fields. */
    static String formSourceSha256(com.fasterxml.jackson.databind.JsonNode source) {
        return VettingCorpus.hash(JsonUtils.write(formSourceHashValue(source)));
    }
    private static com.fasterxml.jackson.databind.JsonNode formSourceHashValue(com.fasterxml.jackson.databind.JsonNode source) {
        if(source.isFloatingPointNumber()) {
            double value=source.doubleValue();
            if(!Double.isFinite(value))throw new IllegalArgumentException("Non-finite form source number");
            return JsonUtils.mapper().createObjectNode().put("$binary64",String.format(Locale.ROOT,"%016x",Double.doubleToRawLongBits(value)));
        }
        if(source.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode out=JsonUtils.mapper().createObjectNode();TreeMap<String,com.fasterxml.jackson.databind.JsonNode> fields=new TreeMap<>();
            source.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));fields.forEach((key,value)->out.set(key,formSourceHashValue(value)));return out;
        }
        if(source.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out=JsonUtils.mapper().createArrayNode();source.forEach(value->out.add(formSourceHashValue(value)));return out;
        }
        return source;
    }
    private static boolean formMatchesPart(Chunk c,Part p,boolean bodyBound,Map<List<String>,Integer> blocks) {
        com.consense.document.PdfPageEvidence.PdfFormField f=p.getPdfFormField();
        String prefix=present(c.getClauseId())?c.getClauseId()+" · ":"";
        return bodyBound&&present(c.getDocumentId())&&present(c.getSourceHash())
                &&VettingCorpus.METADATA_VERSION.equals(c.getMetadataVersion())&&VettingCorpus.SEGMENTATION_VERSION.equals(c.getSegmentationVersion())
                &&Arrays.asList("tender","standard","project_fact","package_manifest").contains(c.getRole())
                &&"native_form".equals(p.getExtractionSource())&&present(f.getId())
                &&p.getStartOffset()==0&&p.getEndOffset()==p.getText().length()
                &&f.getId().replace('/',':').equals(p.getBlockId())&&Objects.equals(prefix+f.getId(),p.getAnchor())
                &&Objects.equals(f.getPageNo()==null?null:"P"+f.getPageNo(),p.getPageNo())
                &&Objects.equals(f.getBbox()==null?null:Arrays.stream(f.getBbox()).boxed().collect(Collectors.toList()),p.getBbox())
                &&Objects.equals(f.getVisibilityStatus(),p.getNativeVisibilityStatus())
                &&(p.getText().isEmpty()||!f.isConflict()&&"appearance_text_unverified".equals(f.getValueStatus())
                    &&"operator_visible_unverified".equals(f.getVisibilityStatus())&&Objects.equals(p.getText(),f.getAppearanceValue()))
                &&Integer.valueOf(1).equals(blocks.get(key(c,p.getBlockId())));
    }
    private static String position(Chunk c,Part p) {
        if(!present(p.getAnchor())||!present(p.getBlockId()))return null;
        String prefix=present(c.getClauseId())?c.getClauseId()+" · ":"";
        if(!p.getAnchor().startsWith(prefix))return null;
        Matcher m=ROW_ANCHOR.matcher(p.getAnchor().substring(prefix.length()));
        if(!m.matches()||!p.getBlockId().equals("body:"+m.group(1)+":table-row:"+m.group(2)))return null;
        try {
            int body=Integer.parseInt(m.group(1)),row=Integer.parseInt(m.group(2));
            if(!Integer.toString(body).equals(m.group(1))||!Integer.toString(row).equals(m.group(2)))return null;
            if(m.group(3)==null?p.getStartOffset()!=0:!m.group(3).equals(Integer.toString(p.getStartOffset())))return null;
            return "body/"+body+"/table-row/"+row;
        } catch(NumberFormatException unavailablePosition) {return null;}
    }
    private static boolean looksLikeRow(Part p){return p.getTable()!=null||nvl(p.getAnchor()).contains("/table-row/")||nvl(p.getBlockId()).contains(":table-row:");}
    private static List<String> key(Chunk c,String location){return Arrays.asList(c.getDocumentId(),c.getSourceHash(),c.getRole(),c.getMetadataVersion(),c.getSegmentationVersion(),c.getNativeTableMetadataVersion(),c.getSourceQualityMetadataVersion(),c.getSourceQualityHash(),location);}
    private static boolean present(String s){return s!=null&&!s.trim().isEmpty();}
    private static String nvl(String s){return s==null?"":s;}
    private static Map<String,Object> map(Object... a){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<a.length;i+=2)m.put((String)a[i],a[i+1]);return m;}
}
