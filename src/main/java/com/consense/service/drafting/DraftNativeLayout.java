package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.util.*;

/** Explicit Desktop native layout edition; never discovers targets from rendered page positions. */
final class DraftNativeLayout {
    private static final Map<String,Object> REGISTRY=registry();
    private static Map<String,Object> registry(){try(InputStream in=DraftNativeLayout.class.getResourceAsStream("/drafting/desktop-native-layout.json")){if(in==null)throw new IOException("Missing native layout registry");return JsonUtils.mapper().readValue(in,Map.class);}catch(IOException failure){throw new IllegalStateException("Native layout registry unavailable",failure);}}
    static NativeLayoutPlan plan(byte[] source,String key,String rendererKind) {
        if(!"desktop-x2t".equals(rendererKind))return null;String sourceHash=DraftPdfConverter.sha256(source);
        for(Object raw:DraftBusinessRules.list(REGISTRY.get("sources"))) {
            Map<String,Object> entry=DraftBusinessRules.asMap(raw);if(!key.equals(entry.get("key"))||!sourceHash.equals(entry.get("sourceSha256")))continue;
            TemplateIndex index=new DocxTemplateEditor().inspect(source);List<NativeLayoutBoundary> boundaries=new ArrayList<>();List<NodeAnchor> empty=new ArrayList<>();List<NativeLayoutSpacing> spacing=new ArrayList<>();
            for(Object value:DraftBusinessRules.list(entry.get("breaks"))){Map<String,Object> boundary=DraftBusinessRules.asMap(value);boundaries.add(new NativeLayoutBoundary(anchor(index,boundary,"table"),anchor(index,boundary,"heading"),anchor(index,boundary,"footer")));}
            for(Object value:DraftBusinessRules.list(entry.get("emptyExclusions")))empty.add(anchor(index,DraftBusinessRules.asMap(value),"paragraph"));
            for(Object value:DraftBusinessRules.list(entry.get("emptySpacing"))){Map<String,Object> row=DraftBusinessRules.asMap(value);spacing.add(new NativeLayoutSpacing(anchor(index,row,"paragraph"),anchor(index,row,"footer")));}
            return new NativeLayoutPlan(sourceHash,String.valueOf(REGISTRY.get("version")),boundaries,empty,spacing);
        }
        return null;
    }
    private static NodeAnchor anchor(TemplateIndex index,Map<String,Object> entry,String prefix){NodeAnchor node=index.anchor(String.valueOf(entry.get(prefix+"Id")));if(!node.getSha256().equals(entry.get(prefix+"Sha256")))throw new IllegalStateException("Native layout registry anchor mismatch: "+node.getId());return node;}
    static Map<String,Object> info(String key,String rendererKind,DocxEditResult result) {
        if(!"desktop-x2t".equals(rendererKind)||!("NTT".equals(key)||"SCT".equals(key)))return null;
        List<Object> actions=new ArrayList<>();for(NativeLayoutChange change:result.getNativeLayoutLedger())actions.add(DraftBusinessRules.map("action",change.getAction(),"status",change.getStatus(),"sourceId",change.getSource().getId(),"sourceNodeSha256",change.getSource().getSha256(),"generatedId",change.getGeneratedId(),"layoutId",change.getLayoutId()));
        return DraftBusinessRules.map("kind","native_layout","profile",REGISTRY.get("version"),"status",result.getNativeLayoutProfile()==null?"skipped_unverified_source":"applied","sourceSha256",result.getOriginalSourceSha256(),"docxSha256",result.getDocxSha256(),"compiledDocxSha256",result.getDocxSha256(),"actions",actions,"message",result.getNativeLayoutProfile()==null?com.consense.common.LocalizedText.of("此模板版本未应用已核验的续页布局；原生内容与控件已保留。","此範本版本未套用已核驗的續頁佈局；原生內容與控制項已保留。","Verified continuation layout was skipped for this source edition; native content and controls are preserved."):null);
    }
    static List<Object> ledger(DocxEditResult result,Map<String,Object> layout){List<Object> rows=new ArrayList<>(result.getLedger());if(layout!=null)rows.add(layout);return rows;}
    static Map<String,Object> savedInfo(String json,String key,String rendererKind,String sourceHash,String docxHash) {
        for(Object raw:JsonUtils.readList(json,Object.class)){Map<String,Object> row=DraftBusinessRules.asMap(raw);if("native_layout".equals(row.get("kind")))return new LinkedHashMap<>(row);}
        if(!"desktop-x2t".equals(rendererKind)||!("NTT".equals(key)||"SCT".equals(key)))return null;
        return DraftBusinessRules.map("kind","native_layout","profile",REGISTRY.get("version"),"status","not_applied_to_saved_revision","sourceSha256",sourceHash,"docxSha256",docxHash,"actions",Collections.emptyList(),"message",com.consense.common.LocalizedText.of("此已保存版本没有已记录的续页布局修正；现有正文和版本保持不变。","此已儲存版本沒有已記錄的續頁佈局修正；現有正文和版本保持不變。","This saved revision has no recorded continuation layout repair; its current body and revision remain unchanged."));
    }
    static List<Object> bodyLedger(String previousJson,DocxEditResult result) {
        Map<String,Object> previous=null;for(Object raw:JsonUtils.readList(previousJson,Object.class)){Map<String,Object> row=DraftBusinessRules.asMap(raw);if("native_layout".equals(row.get("kind")))previous=row;}
        if(previous==null)return ledger(result,null);Map<String,Object> current=new LinkedHashMap<>(previous);current.put("parentDocxSha256",previous.get("docxSha256"));current.put("docxSha256",result.getDocxSha256());List<Object> actions=new ArrayList<>();
        Map<String,List<Feature>> markers=new HashMap<>();for(Feature feature:result.getIndex().getFeatures())if("BOOKMARK".equals(feature.getKind())&&"word/document.xml".equals(feature.getPart()))markers.computeIfAbsent(feature.getName(),id->new ArrayList<>()).add(feature);
        for(Object raw:DraftBusinessRules.list(previous.get("actions"))){Map<String,Object> action=new LinkedHashMap<>(DraftBusinessRules.asMap(raw));if(action.get("layoutId")!=null){List<Feature> matches=markers.getOrDefault(String.valueOf(action.get("layoutId")),Collections.emptyList());if(matches.size()!=1||!matches.get(0).isComplete()){action.put("status","MISSING_LAYOUT_CONTROL");action.put("generatedId",null);current.put("status","unavailable");}else{String start=matches.get(0).getStartId(),paragraph=start.substring(0,start.lastIndexOf("/w:bookmarkStart"));action.put("generatedId",paragraph);if("compact_verified_empty_spacing".equals(action.get("action"))&&!result.getIndex().paragraph(paragraph).hasCompactEmptyLayoutSpacing()){action.put("status","CHANGED_LAYOUT_CONTROL");current.put("status","unavailable");}}}actions.add(action);}current.put("actions",actions);return ledger(result,current);
    }
}
