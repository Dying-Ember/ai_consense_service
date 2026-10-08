package com.consense.document;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Source-bound editing of an existing DOCX package. This utility does not decide clause applicability. */
public final class DocxTemplateEditor {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MAIN="word/document.xml";
    private static final String M="http://schemas.openxmlformats.org/officeDocument/2006/math";
    // The same hard ceilings as DocumentParser; actual inflation is checked, not ZIP declared sizes.
    private static final long ENTRY_LIMIT=128L*1024*1024, XML_LIMIT=32L*1024*1024, TOTAL_LIMIT=256L*1024*1024;
    private static final int COUNT_LIMIT=5000;

    public TemplateIndex inspect(byte[] sourceDocx) { return load(sourceDocx).index; }
    public DocxEditResult apply(byte[] sourceDocx, SourceEditBatch batch) {
        return applyWithParagraphBookmarks(sourceDocx,batch,Collections.emptyMap());
    }
    /** Attach invisible persistent markers to the surviving original paragraph objects after edits.
     * Validation and compiler decisions still use the immutable original package/hash. */
    public DocxEditResult applyWithParagraphBookmarks(byte[] sourceDocx,SourceEditBatch batch,Map<String,String> bookmarks) {
        return applyWithParagraphBookmarks(sourceDocx,batch,bookmarks,false);
    }
    public DocxEditResult applyWithParagraphBookmarks(byte[] sourceDocx,SourceEditBatch batch,Map<String,String> bookmarks,boolean markAddedParagraphs) {
        return applyWithParagraphBookmarks(sourceDocx,batch,bookmarks,markAddedParagraphs,null);
    }
    /** Layout uses captured immutable source anchors, before business edits can shift native paths. */
    public DocxEditResult applyWithParagraphBookmarks(byte[] sourceDocx,SourceEditBatch batch,Map<String,String> bookmarks,boolean markAddedParagraphs,NativeLayoutPlan layout) {
        if(sourceDocx!=null)sourceDocx=sourceDocx.clone();
        PackageModel model=load(sourceDocx);
        if(batch==null||!model.index.sourceSha256.equals(batch.expectedSourceSha256))throw error("SOURCE_HASH_MISMATCH","batch");
        CapturedLayout captured=captureLayout(model,layout);
        if(batch.edits.isEmpty()&&bookmarks.isEmpty()&&layout==null)return new DocxEditResult(sourceDocx,model.index);
        Set<String> ids=new HashSet<>();
        for(Edit edit:batch.edits) {
            if(edit==null||edit.operationId==null||edit.operationId.trim().isEmpty()||!ids.add(edit.operationId))throw error("INVALID_OPERATION_ID","batch");
        }
        SourceEditBatch originalBatch=batch;batch=expand(batch);
        for(Edit edit:batch.edits) {
            Element target=model.nodes.get(edit.targetId);
            if(target==null)throw error("TARGET_NOT_FOUND",edit.operationId);
            if(!edit.targetId.startsWith(MAIN+"#"))throw error("STORY_EDIT_UNSUPPORTED",edit.operationId);
            if(!hash(xml(target)).equals(edit.targetSha256))throw error("TARGET_HASH_MISMATCH",edit.operationId);
            if(edit.type==EditType.REPLACE_SPAN) {
                validateSpan(target,edit);
                if(edit.donorId!=null) {Element donor=model.nodes.get(edit.donorId);if(!is(donor,"r")||!intrinsicHash(donor).equals(edit.donorSha256))throw error("STYLE_DONOR_MISMATCH",edit.operationId);}
            }
            else if(insertion(edit))validateInsertion(model,target,edit);
            else validateStructural(target,edit);
            for(Edit other:batch.edits) {
                if(other==edit)continue;Element otherTarget=model.nodes.get(other.targetId);
                if(otherTarget==null)continue;
                if(target==otherTarget) {
                    if(insertion(edit)||insertion(other)) {
                        if(destructive(edit)||destructive(other))throw error("OVERLAPPING_OPERATIONS",edit.operationId);
                    }else if(edit.type!=EditType.REPLACE_SPAN||other.type!=EditType.REPLACE_SPAN||other.start<edit.end&&other.end>edit.start)throw error("OVERLAPPING_OPERATIONS",edit.operationId);
                }else if(contains(target,otherTarget)||contains(otherTarget,target))throw error("OVERLAPPING_OPERATIONS",edit.operationId);
            }
        }
        List<Edit> ordered=new ArrayList<>(batch.edits);
        validateRemainingRows(model,batch);
        retainRequiredCellParagraphs(model,batch);
        validateFeatureScopes(model,batch);
        List<FeatureImpact> impacts=featureImpacts(model,batch);
        ordered.sort(Comparator.comparing((Edit e)->e.targetId).thenComparing((Edit e)->-e.start));
        for(Edit edit:ordered) {
            Element target=model.nodes.get(edit.targetId);
            if(edit.type==EditType.REPLACE_SPAN)replace(model,target,edit);
            else if(insertion(edit))insert(model,target,edit);
            else if(edit.type==EditType.CLEAR_EDITORIAL_CELL) {
                NodeList ps=target.getElementsByTagNameNS(W,"p");for(int i=0;i<ps.getLength();i++)clear((Element)ps.item(i));
            }else if(edit.type==EditType.CLEAR_PARAGRAPH)clear(target);
            else if(edit.type==EditType.CLEAR_PARAGRAPH_AND_NUMBERING) {clear(target);clearNumbering(target);}
            else if(edit.type==EditType.KEEP_ROW_TOGETHER)keepRowTogether(target);
            else if(edit.type==EditType.DELETE_PARAGRAPH) {
                if(model.retainedCellParagraphs.contains(target))clear(target);
                else target.getParentNode().removeChild(target);
            }else target.getParentNode().removeChild(target);
        }
        List<NativeLayoutChange> layoutChanges=applyLayout(model,captured);
        attachParagraphBookmarks(model,bookmarks,markAddedParagraphs);
        model.entries.put(MAIN,xml(model.xmlParts.get(MAIN)));
        byte[] result=write(model.entries);
        TemplateIndex updated=load(result).index;
        return new DocxEditResult(result,updated,model.index.sourceSha256,impacts,ledger(model,originalBatch,updated.sourceSha256),model.index.entryHashes,layout==null?null:layout.profile,layoutChanges);
    }

    public static final class NativeLayoutBoundary {
        private final NodeAnchor table,heading,footer;
        public NativeLayoutBoundary(NodeAnchor table,NodeAnchor heading,NodeAnchor footer){this.table=Objects.requireNonNull(table);this.heading=Objects.requireNonNull(heading);this.footer=Objects.requireNonNull(footer);}
    }
    public static final class NativeLayoutPlan {
        private final String sourceSha256,profile;
        private final List<NativeLayoutBoundary> boundaries;
        private final List<NodeAnchor> emptyExclusions;
        private final List<NativeLayoutSpacing> emptySpacing;
        public NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions){this(sourceSha256,profile,boundaries,emptyExclusions,Collections.emptyList());}
        public NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing){this.sourceSha256=Objects.requireNonNull(sourceSha256);this.profile=Objects.requireNonNull(profile);this.boundaries=Collections.unmodifiableList(new ArrayList<>(boundaries));this.emptyExclusions=Collections.unmodifiableList(new ArrayList<>(emptyExclusions));this.emptySpacing=Collections.unmodifiableList(new ArrayList<>(emptySpacing));}
    }
    /** An unchanged original empty paragraph associated with an exact manual footer. */
    public static final class NativeLayoutSpacing {
        private final NodeAnchor paragraph,footer;
        public NativeLayoutSpacing(NodeAnchor paragraph,NodeAnchor footer){this.paragraph=Objects.requireNonNull(paragraph);this.footer=Objects.requireNonNull(footer);}
    }
    public static final class NativeLayoutChange {
        private final String action,status,generatedId,layoutId;
        private final NodeAnchor source;
        private NativeLayoutChange(String action,String status,NodeAnchor source,String generatedId){this(action,status,source,generatedId,null);}
        private NativeLayoutChange(String action,String status,NodeAnchor source,String generatedId,String layoutId){this.action=action;this.status=status;this.source=source;this.generatedId=generatedId;this.layoutId=layoutId;}
        public String getAction(){return action;} public String getStatus(){return status;} public NodeAnchor getSource(){return source;} public String getGeneratedId(){return generatedId;} public String getLayoutId(){return layoutId;}
    }
    private static final class CapturedLayout {
        final NativeLayoutPlan plan;
        final List<Element[]> boundaries=new ArrayList<>(),spacing=new ArrayList<>();final List<Element> empty=new ArrayList<>();
        CapturedLayout(NativeLayoutPlan plan){this.plan=plan;}
    }
    private static CapturedLayout captureLayout(PackageModel model,NativeLayoutPlan plan) {
        CapturedLayout result=new CapturedLayout(plan);if(plan==null)return result;
        if(!model.index.sourceSha256.equals(plan.sourceSha256))throw error("LAYOUT_SOURCE_HASH_MISMATCH",plan.profile);
        Set<String> selected=new HashSet<>();
        for(NativeLayoutBoundary boundary:plan.boundaries) {
            Element table=layoutAnchor(model,boundary.table),heading=layoutAnchor(model,boundary.heading),footer=layoutAnchor(model,boundary.footer);
            if(!selected.add(boundary.table.id)||!is(table,"tbl")||!is(table.getParentNode(),"body")||!firstRowHeading(table,heading)||(footer.compareDocumentPosition(table)&Node.DOCUMENT_POSITION_FOLLOWING)==0)throw error("LAYOUT_BOUNDARY_SCOPE_CHANGED",boundary.table.id);
            result.boundaries.add(new Element[]{table,heading});
        }
        for(NodeAnchor anchor:plan.emptyExclusions){Element paragraph=layoutAnchor(model,anchor);if(!selected.add(anchor.id)||!is(paragraph.getParentNode(),"body")||!layoutEmpty(paragraph))throw error("LAYOUT_EMPTY_SCOPE_CHANGED",anchor.id);result.empty.add(paragraph);}
        for(NativeLayoutSpacing spacing:plan.emptySpacing){Element paragraph=layoutAnchor(model,spacing.paragraph),footer=layoutAnchor(model,spacing.footer);if(!selected.add(spacing.paragraph.id)||!layoutEmpty(paragraph)||!is(footer.getParentNode(),"body")||!is(footer,"p"))throw error("LAYOUT_EMPTY_SCOPE_CHANGED",spacing.paragraph.id);result.spacing.add(new Element[]{paragraph,footer});}
        return result;
    }
    private static Element layoutAnchor(PackageModel model,NodeAnchor anchor){Element node=model.nodes.get(anchor.id);if(!MAIN.equals(anchor.part)||node==null||!hash(xml(node)).equals(anchor.sha256))throw error("LAYOUT_ANCHOR_MISMATCH",anchor.id);return node;}
    private static boolean firstRowHeading(Element table,Element heading){List<Element> rows=direct(table,"tr");if(rows.isEmpty()||!contains(rows.get(0),heading))return false;for(Node parent=heading.getParentNode();parent!=null;parent=parent.getParentNode())if(is(parent,"tbl"))return parent==table;return false;}
    private static boolean layoutEmpty(Element paragraph){if(!is(paragraph,"p"))return false;for(Node child=paragraph.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"pPr"))return false;return paragraph.getElementsByTagNameNS(W,"sectPr").getLength()==0&&paragraph.getElementsByTagNameNS(W,"pageBreakBefore").getLength()==0;}
    private static List<NativeLayoutChange> applyLayout(PackageModel model,CapturedLayout captured) {
        List<NativeLayoutChange> changes=new ArrayList<>();if(captured.plan==null)return changes;Document document=model.xmlParts.get(MAIN);Element root=document.getDocumentElement();
        long nextMarker=0;Set<String> markerNames=new HashSet<>();NodeList markers=document.getElementsByTagNameNS(W,"bookmarkStart");for(int i=0;i<markers.getLength();i++){Element marker=(Element)markers.item(i);markerNames.add(marker.getAttributeNS(W,"name"));try{nextMarker=Math.max(nextMarker,Long.parseLong(marker.getAttributeNS(W,"id"))+1);}catch(NumberFormatException ignored){}}
        for(int i=0;i<captured.empty.size();i++){Element paragraph=captured.empty.get(i);NodeAnchor anchor=captured.plan.emptyExclusions.get(i);if(!contains(root,paragraph)){changes.add(new NativeLayoutChange("remove_verified_empty","SKIPPED_REMOVED",anchor,null));continue;}if(!layoutEmpty(paragraph)||!hash(xml(paragraph)).equals(anchor.sha256))throw error("LAYOUT_EMPTY_SCOPE_CHANGED",anchor.id);paragraph.getParentNode().removeChild(paragraph);changes.add(new NativeLayoutChange("remove_verified_empty","APPLIED",anchor,null));}
        for(int i=0;i<captured.boundaries.size();i++) {
            Element table=captured.boundaries.get(i)[0],heading=captured.boundaries.get(i)[1];NodeAnchor anchor=captured.plan.boundaries.get(i).table;
            if(!contains(root,table)||!contains(root,heading)){changes.add(new NativeLayoutChange("page_break_before","SKIPPED_REMOVED",anchor,null));continue;}
            if(!is(table.getParentNode(),"body")||!firstRowHeading(table,heading)||!hash(xml(heading)).equals(captured.plan.boundaries.get(i).heading.sha256))throw error("LAYOUT_BOUNDARY_SCOPE_CHANGED",anchor.id);
            Element paragraph=document.createElementNS(W,"w:p"),props=document.createElementNS(W,"w:pPr");
            props.appendChild(document.createElementNS(W,"w:pageBreakBefore"));compactSpacing(document,props);paragraph.appendChild(props);table.getParentNode().insertBefore(paragraph,table);
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange("page_break_before","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        for(int i=0;i<captured.spacing.size();i++){
            Element paragraph=captured.spacing.get(i)[0],footer=captured.spacing.get(i)[1];NativeLayoutSpacing spacing=captured.plan.emptySpacing.get(i);NodeAnchor anchor=spacing.paragraph;
            if(!contains(root,paragraph)||!contains(root,footer)){changes.add(new NativeLayoutChange("compact_verified_empty_spacing","SKIPPED_REMOVED",anchor,null));continue;}
            if(!layoutEmpty(paragraph)||!hash(xml(paragraph)).equals(anchor.sha256)||!hash(xml(footer)).equals(spacing.footer.sha256))throw error("LAYOUT_EMPTY_SCOPE_CHANGED",anchor.id);
            Element props=direct(paragraph,"pPr").isEmpty()?document.createElementNS(W,"w:pPr"):direct(paragraph,"pPr").get(0);if(props.getParentNode()==null)paragraph.insertBefore(props,paragraph.getFirstChild());compactSpacing(document,props);
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange("compact_verified_empty_spacing","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        // Later insertions may shift earlier BODY paths. Resolve IDs only after the complete layout.
        List<NativeLayoutChange> finalChanges=new ArrayList<>();int boundaryIndex=0;
        for(NativeLayoutChange change:changes){if("page_break_before".equals(change.action)){Element table=captured.boundaries.get(boundaryIndex++)[0];String id="APPLIED".equals(change.status)?MAIN+"#"+path((Element)table.getPreviousSibling()):null;finalChanges.add(new NativeLayoutChange(change.action,change.status,change.source,id,change.layoutId));}else finalChanges.add(change);}
        return finalChanges;
    }

    /** CT_PPr is ordered: new spacing precedes ind/jc/rPr and preserves all existing siblings. */
    private static void compactSpacing(Document document,Element props){
        Element value=direct(props,"spacing").isEmpty()?document.createElementNS(W,"w:spacing"):direct(props,"spacing").get(0);if(value.getParentNode()==null){Node later=null;Set<String> afterSpacing=Set.of("ind","contextualSpacing","mirrorIndents","suppressOverlap","jc","textDirection","textAlignment","textboxTightWrap","outlineLvl","divId","cnfStyle","rPr","sectPr","pPrChange");for(Node child=props.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&afterSpacing.contains(child.getLocalName())){later=child;break;}props.insertBefore(value,later);}
            value.setAttributeNS(W,"w:before","0");value.setAttributeNS(W,"w:after","0");value.setAttributeNS(W,"w:line","1");value.setAttributeNS(W,"w:lineRule","exact");
    }

    private static void attachParagraphBookmarks(PackageModel model,Map<String,String> bookmarks,boolean markAddedParagraphs) {
        Document document=model.xmlParts.get(MAIN);Element root=document.getDocumentElement();
        Set<String> names=new HashSet<>();long nextId=0;
        NodeList starts=document.getElementsByTagNameNS(W,"bookmarkStart");
        for(int i=0;i<starts.getLength();i++) {
            Element start=(Element)starts.item(i);names.add(start.getAttributeNS(W,"name"));
            try {nextId=Math.max(nextId,Long.parseLong(start.getAttributeNS(W,"id"))+1);}catch(NumberFormatException ignored) { }
        }
        for(Map.Entry<String,String> entry:bookmarks.entrySet()) {
            Element paragraph=model.nodes.get(entry.getKey());String name=entry.getValue();
            if(paragraph==null||!is(paragraph,"p"))throw error("BINDING_PARAGRAPH_INVALID",entry.getKey());
            if(!contains(root,paragraph))continue; // Removed source targets remain explicit in the sidecar.
            if(name==null||!name.matches("CS[A-Za-z0-9]{1,38}")||!names.add(name))throw error("BINDING_BOOKMARK_CONFLICT",entry.getKey());
            attachBookmark(document,paragraph,name,nextId++);
        }
        if(markAddedParagraphs) {
            Set<Element> original=Collections.newSetFromMap(new IdentityHashMap<Element,Boolean>());original.addAll(model.nodes.values());
            NodeList paragraphs=document.getElementsByTagNameNS(W,"p");
            for(int i=0;i<paragraphs.getLength();i++) {Element paragraph=(Element)paragraphs.item(i);if(original.contains(paragraph)||new Paragraph(MAIN,path(paragraph),paragraph,i+1).getCatalogText().trim().isEmpty())continue;
                String name="CS"+hash((model.index.getSourceSha256()+"|added|"+path(paragraph)).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!names.add(name))throw error("BINDING_BOOKMARK_CONFLICT",path(paragraph));attachBookmark(document,paragraph,name,nextId++);
            }
        }
    }
    private static void attachBookmark(Document document,Element paragraph,String name,long numericId) {
        String id=String.valueOf(numericId);Element start=document.createElementNS(W,"w:bookmarkStart"),end=document.createElementNS(W,"w:bookmarkEnd");
        start.setAttributeNS(W,"w:id",id);start.setAttributeNS(W,"w:name",name);end.setAttributeNS(W,"w:id",id);
        Node first=paragraph.getFirstChild();if(is(first,"pPr"))first=first.getNextSibling();paragraph.insertBefore(start,first);paragraph.appendChild(end);
    }

    public static final class TemplateIndex {
        private final String sourceSha256;
        private final List<Paragraph> mainParagraphs;
        private final Map<String,String> entryHashes;
        private final Map<String,NodeAnchor> anchors;
        private final List<Feature> features;
        private final List<Paragraph> allParagraphs;
        private TemplateIndex(String hash,List<Paragraph> paragraphs,Map<String,String> hashes,Map<String,NodeAnchor> anchors,List<Feature> features,List<Paragraph> all) {
            sourceSha256=hash;mainParagraphs=Collections.unmodifiableList(new ArrayList<>(paragraphs));
            entryHashes=Collections.unmodifiableMap(new LinkedHashMap<>(hashes));
            this.anchors=Collections.unmodifiableMap(new LinkedHashMap<>(anchors));
            this.features=Collections.unmodifiableList(new ArrayList<>(features));allParagraphs=Collections.unmodifiableList(new ArrayList<>(all));
        }
        public String getSourceSha256() { return sourceSha256; }
        public List<Paragraph> getMainParagraphs() { return mainParagraphs; }
        public Map<String,String> getEntryHashes() { return entryHashes; }
        /** One-based descendant w:p ordinal in the main document, matching the verified source catalog. */
        public Paragraph mainParagraph(int ordinal) { return mainParagraphs.get(ordinal-1); }
        public NodeAnchor anchor(String id){NodeAnchor anchor=anchors.get(id);if(anchor==null)throw error("TARGET_NOT_FOUND",id);return anchor;}
        public Collection<NodeAnchor> getAnchors(){return anchors.values();}
        public List<Feature> getFeatures(){return features;}
        public List<Paragraph> getParagraphs(){return allParagraphs;}
        public Paragraph paragraph(String id){for(Paragraph p:allParagraphs)if(p.id.equals(id))return p;throw error("TARGET_NOT_FOUND",id);}
    }
    public static final class Paragraph {
        private final String id,part,path,sha256,text,catalogText,cellId,rowId,tableId,sectionId;
        private final int ordinal;
        private final byte[] snapshotXml;
        private Paragraph(String part,String path,Element node,int ordinal) {
            this.part=part;this.path=path;id=part+"#"+path;this.ordinal=ordinal;
            snapshotXml=xml(node);sha256=hash(snapshotXml);catalogText=descendantText(node,"t");text=editableText(node);
            cellId=ancestorId(part,node,"tc");rowId=ancestorId(part,node,"tr");tableId=ancestorId(part,node,"tbl");
            String section=null;NodeList sections=node.getOwnerDocument().getElementsByTagNameNS(W,"sectPr");
            for(int i=0;i<sections.getLength();i++)if((node.compareDocumentPosition(sections.item(i))&Node.DOCUMENT_POSITION_FOLLOWING)!=0) {section=part+"#"+path((Element)sections.item(i));break;}
            sectionId=section;
        }
        public String getId(){return id;} public String getPart(){return part;} public String getPath(){return path;}
        public String getSha256(){return sha256;} public String getText(){return text;} public String getCatalogText(){return catalogText;}
        public int getOrdinal(){return ordinal;}
        public String getCellId(){return cellId;} public String getRowId(){return rowId;} public String getTableId(){return tableId;}
        public String getSectionId(){return sectionId;}
        /** Verify the native empty control itself, rather than trusting its previous ledger. */
        public boolean hasCompactEmptyLayoutSpacing(){
            try{Element paragraph=parse(snapshotXml).getDocumentElement();for(Node child=paragraph.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"pPr")&&!is(child,"bookmarkStart")&&!is(child,"bookmarkEnd"))return false;
                if(paragraph.getElementsByTagNameNS(W,"pageBreakBefore").getLength()!=0||paragraph.getElementsByTagNameNS(W,"sectPr").getLength()!=0)return false;List<Element> props=direct(paragraph,"pPr");if(props.size()!=1)return false;List<Element> spacing=direct(props.get(0),"spacing");if(spacing.size()!=1)return false;Element value=spacing.get(0);NamedNodeMap attributes=value.getAttributes();int count=0;for(int i=0;i<attributes.getLength();i++){Node attribute=attributes.item(i);if(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()))continue;if(!W.equals(attribute.getNamespaceURI())||!Set.of("before","after","line","lineRule").contains(attribute.getLocalName()))return false;count++;}if(count!=4)return false;return "0".equals(value.getAttributeNS(W,"before"))&&"0".equals(value.getAttributeNS(W,"after"))&&"1".equals(value.getAttributeNS(W,"line"))&&"exact".equals(value.getAttributeNS(W,"lineRule"));
            }catch(Exception failure){return false;}
        }
        /** Blank plain text may complete an explicitly selected row; native controls and opaque inline nodes may not. */
        public boolean isPlainEmpty() {
            if(!text.trim().isEmpty())return false;
            try {
                for(Segment segment:segments(parse(snapshotXml).getDocumentElement()))if(!segment.safe)return false;
                return true;
            }catch(Exception e){throw error("PARAGRAPH_STRUCTURE_UNAVAILABLE",id);}
        }
        public List<RunInfo> getRuns() {
            try {
                Element p=parse(snapshotXml).getDocumentElement();List<RunInfo> runs=new ArrayList<>();int offset=0,ordinal=0;
                for(Node child=p.getFirstChild();child!=null;child=child.getNextSibling()) {
                    String value=editableText(child);
                    if(is(child,"r"))runs.add(new RunInfo(id+"/"+child.getNodeName()+"["+(++ordinal)+"]",(Element)child,offset,offset+value.length(),value));
                    offset+=value.length();
                }
                return Collections.unmodifiableList(runs);
            }catch(Exception e){throw error("RUN_INDEX_UNAVAILABLE",id);}
        }
    }
    public static final class RunInfo {
        private final String id,sha256,text,propertiesXml,propertiesSha256;
        private final int start,end;
        private RunInfo(String id,Element run,int start,int end,String text) {
            this.id=id;sha256=intrinsicHash(run);this.start=start;this.end=end;this.text=text;
            List<Element> properties=direct(run,"rPr");propertiesXml=properties.isEmpty()?"":new String(xml(properties.get(0)),StandardCharsets.UTF_8);
            propertiesSha256=properties.isEmpty()?hash(new byte[0]):intrinsicHash(properties.get(0));
        }
        public String getId(){return id;} public String getSha256(){return sha256;} public String getText(){return text;}
        public String getPropertiesXml(){return propertiesXml;} public String getPropertiesSha256(){return propertiesSha256;}
        public int getStart(){return start;} public int getEnd(){return end;}
    }
    public static final class NodeAnchor {
        private final String id,part,path,sha256,kind;
        private final List<String> paragraphIds;
        private NodeAnchor(String part,Element node) {
            this.part=part;path=path(node);id=part+"#"+path;sha256=hash(xml(node));kind=node.getLocalName();
            List<String> ids=new ArrayList<>();NodeList ps=node.getElementsByTagNameNS(W,"p");
            if(is(node,"p"))ids.add(id);
            for(int i=0;i<ps.getLength();i++)ids.add(part+"#"+path((Element)ps.item(i)));
            paragraphIds=Collections.unmodifiableList(ids);
        }
        public String getId(){return id;} public String getPart(){return part;} public String getPath(){return path;}
        public String getSha256(){return sha256;} public String getKind(){return kind;} public List<String> getParagraphIds(){return paragraphIds;}
    }
    public static final class SourceEditBatch {
        private final String expectedSourceSha256;
        private final List<Edit> edits;
        public SourceEditBatch(String expectedSourceSha256, List<Edit> edits) {
            this.expectedSourceSha256=expectedSourceSha256;
            this.edits=Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(edits,"edits")));
        }
    }
    public enum EditType { REPLACE_SPAN, CLEAR_EDITORIAL_CELL, CLEAR_PARAGRAPH, CLEAR_PARAGRAPH_AND_NUMBERING, DELETE_PARAGRAPH, DELETE_ROW, DELETE_TABLE, REMOVE_NODES, KEEP_ROW_TOGETHER, INSERT_BEFORE, INSERT_AFTER }
    public static final class Edit {
        private final String operationId,targetId,targetSha256,expectedText,replacement,donorId,donorSha256;
        private final int start,end;
        private final EditType type;
        private final List<String> lines;
        private final List<NodeAnchor> targets;
        private Edit(String id,Paragraph p,int start,int end,String expected,String replacement) {
            operationId=id;targetId=p.id;targetSha256=p.sha256;this.start=start;this.end=end;
            expectedText=expected;this.replacement=replacement;
            type=EditType.REPLACE_SPAN;
            donorId=null;donorSha256=null;lines=Collections.emptyList();targets=Collections.emptyList();
        }
        private Edit(String id,String target,String sha,EditType type) {
            operationId=id;targetId=target;targetSha256=sha;this.type=type;start=0;end=0;expectedText=null;replacement=null;
            donorId=null;donorSha256=null;lines=Collections.emptyList();targets=Collections.emptyList();
        }
        private Edit(String id,Paragraph anchor,boolean after,List<String> lines,Paragraph donor) {
            operationId=id;targetId=anchor.id;targetSha256=anchor.sha256;type=after?EditType.INSERT_AFTER:EditType.INSERT_BEFORE;
            start=0;end=0;expectedText=null;replacement=null;donorId=donor.id;donorSha256=donor.sha256;
            this.lines=Collections.unmodifiableList(new ArrayList<>(lines));targets=Collections.emptyList();
        }
        private Edit(String id,List<NodeAnchor> targets) {
            operationId=id;targetId=null;targetSha256=null;type=EditType.REMOVE_NODES;start=0;end=0;expectedText=null;replacement=null;
            donorId=null;donorSha256=null;lines=Collections.emptyList();this.targets=Collections.unmodifiableList(new ArrayList<>(targets));
        }
        public static Edit replaceSpan(String id,Paragraph p,int start,int end,String expected,String replacement) {
            return new Edit(id,p,start,end,expected,replacement);
        }
        /** Catalog offsets count only descendant w:t. Mapping across controls or opaque features is rejected. */
        public static Edit replaceCatalogSpan(String id,Paragraph p,int start,int end,String expected,String replacement) {
            if(start<0||end<start||end>p.catalogText.length()||!p.catalogText.substring(start,end).equals(expected))throw error("TEXT_EXPECTATION_MISMATCH",id);
            if(start==end)throw error("EMPTY_SPAN_UNSUPPORTED",id);
            try {
                int catalog=0,actualStart=-1,actualEnd=-1;
                for(Segment segment:segments(parse(p.snapshotXml).getDocumentElement())) {
                    if(!is(segment.node,"t"))continue;
                    int length=segment.node.getTextContent().length();
                    if(actualStart<0&&start>=catalog&&start<catalog+length)actualStart=segment.start+start-catalog;
                    if(end>catalog&&end<=catalog+length)actualEnd=segment.start+end-catalog;
                    catalog+=length;
                }
                if(actualStart<0||actualEnd<actualStart)throw error("CATALOG_MAPPING_UNSUPPORTED",id);
                Edit edit=new Edit(id,p,actualStart,actualEnd,p.text.substring(actualStart,actualEnd),replacement);
                validateSpan(parse(p.snapshotXml).getDocumentElement(),edit);
                return edit;
            }catch(EditException e){throw e;}catch(Exception e){throw error("CATALOG_MAPPING_UNSUPPORTED",id);}
        }
        /** The caller supplies an explicitly adopted editorial cell; no text/style heuristic selects it. */
        public static Edit clearEditorialCell(String id,NodeAnchor cell){return new Edit(id,cell.id,cell.sha256,EditType.CLEAR_EDITORIAL_CELL);}
        public static Edit clearParagraph(String id,Paragraph p){return new Edit(id,p.id,p.sha256,EditType.CLEAR_PARAGRAPH);}
        /** Explicit adopted removal of paragraph text and its direct automatic label; other properties remain. */
        public static Edit clearParagraphAndNumbering(String id,Paragraph p){return new Edit(id,p.id,p.sha256,EditType.CLEAR_PARAGRAPH_AND_NUMBERING);}
        public static Edit deleteParagraph(String id,Paragraph p){return new Edit(id,p.id,p.sha256,EditType.DELETE_PARAGRAPH);}
        public static Edit deleteRow(String id,NodeAnchor row){return new Edit(id,row.id,row.sha256,EditType.DELETE_ROW);}
        /** Prevents this source-bound row from splitting without changing its content or design. */
        public static Edit keepRowTogether(String id,NodeAnchor row){return new Edit(id,row.id,row.sha256,EditType.KEEP_ROW_TOGETHER);}
        public static Edit insertParagraphs(String id,Paragraph anchor,boolean after,List<String> lines,Paragraph donor){return new Edit(id,anchor,after,lines,donor);}
        /** Each selected node has its own exact source hash. No numeric range is inferred. */
        public static Edit removeNodes(String id,List<NodeAnchor> nodes){return new Edit(id,nodes);}
        public String getOperationId(){return operationId;} public EditType getType(){return type;}
        public Edit withStyleDonor(RunInfo donor){if(type!=EditType.REPLACE_SPAN)throw error("RUN_STYLE_DONOR_OPERATION_UNSUPPORTED",operationId);return new Edit(this,donor);}
        private Edit(Edit original,RunInfo donor) {
            operationId=original.operationId;targetId=original.targetId;targetSha256=original.targetSha256;expectedText=original.expectedText;replacement=original.replacement;
            start=original.start;end=original.end;type=original.type;lines=original.lines;targets=original.targets;donorId=donor.id;donorSha256=donor.sha256;
        }
    }
    public static final class DocxEditResult {
        private final byte[] docxBytes;
        private final TemplateIndex index;
        private final String originalSourceSha256;
        private final List<FeatureImpact> affectedFeatures;
        private final List<AppliedEdit> ledger;
        private final Map<String,String> preservedEntryHashes;
        private final String nativeLayoutProfile;
        private final List<NativeLayoutChange> nativeLayoutLedger;
        private DocxEditResult(byte[] bytes,TemplateIndex index){this(bytes,index,index.sourceSha256,Collections.emptyList(),Collections.emptyList(),index.entryHashes);}
        private DocxEditResult(byte[] bytes,TemplateIndex index,String source,List<FeatureImpact> impacts,List<AppliedEdit> ledger,Map<String,String> originalHashes) {
            this(bytes,index,source,impacts,ledger,originalHashes,null,Collections.emptyList());
        }
        private DocxEditResult(byte[] bytes,TemplateIndex index,String source,List<FeatureImpact> impacts,List<AppliedEdit> ledger,Map<String,String> originalHashes,String layoutProfile,List<NativeLayoutChange> layoutLedger) {
            docxBytes=bytes.clone();this.index=index;originalSourceSha256=source;affectedFeatures=Collections.unmodifiableList(new ArrayList<>(impacts));
            nativeLayoutProfile=layoutProfile;nativeLayoutLedger=Collections.unmodifiableList(new ArrayList<>(layoutLedger));
            this.ledger=Collections.unmodifiableList(new ArrayList<>(ledger));Map<String,String> preserved=new LinkedHashMap<>();
            for(Map.Entry<String,String> entry:originalHashes.entrySet())if(entry.getValue().equals(index.entryHashes.get(entry.getKey())))preserved.put(entry.getKey(),entry.getValue());
            preservedEntryHashes=Collections.unmodifiableMap(preserved);
        }
        public byte[] getDocxBytes() { return docxBytes.clone(); }
        public TemplateIndex getIndex(){return index;}
        public String getDocxSha256(){return index.getSourceSha256();}
        public List<FeatureImpact> getAffectedFeatures(){return affectedFeatures;}
        public String getOriginalSourceSha256(){return originalSourceSha256;}
        public List<AppliedEdit> getLedger(){return ledger;} public Map<String,String> getPreservedEntryHashes(){return preservedEntryHashes;}
        public String getNativeLayoutProfile(){return nativeLayoutProfile;} public List<NativeLayoutChange> getNativeLayoutLedger(){return nativeLayoutLedger;}
    }
    public static final class AppliedEdit {
        private final String operationId,sourceSha256,generatedSha256,donorId,donorSha256,expectedText,replacement;
        private final EditType type;private final int start,end;private final List<TargetChange> targets;private final List<String> insertedLines,insertedParagraphIds;
        private AppliedEdit(Edit edit,String source,String generated,List<TargetChange> targets,RunInfo donor,List<String> insertedParagraphIds) {
            operationId=edit.operationId;sourceSha256=source;generatedSha256=generated;type=edit.type;
            donorId=edit.donorId!=null?edit.donorId:donor==null?null:donor.id;donorSha256=edit.donorSha256!=null?edit.donorSha256:donor==null?null:donor.sha256;
            expectedText=edit.expectedText;replacement=edit.replacement;start=edit.start;end=edit.end;
            this.targets=Collections.unmodifiableList(new ArrayList<>(targets));insertedLines=edit.lines;this.insertedParagraphIds=Collections.unmodifiableList(new ArrayList<>(insertedParagraphIds));
        }
        public String getOperationId(){return operationId;} public String getSourceSha256(){return sourceSha256;} public String getGeneratedSha256(){return generatedSha256;}
        public EditType getType(){return type;} public List<TargetChange> getTargets(){return targets;} public String getDonorId(){return donorId;}
        public String getDonorSha256(){return donorSha256;} public String getExpectedText(){return expectedText;} public String getReplacement(){return replacement;}
        public int getStart(){return start;} public int getEnd(){return end;}
        public List<String> getInsertedLines(){return insertedLines;}
        public List<String> getInsertedParagraphIds(){return insertedParagraphIds;}
    }
    public static final class TargetChange {
        private final NodeAnchor source;private final String afterSha256,status,generatedId;
        private TargetChange(NodeAnchor source,Element after,boolean retainedCellParagraph) {
            this.source=source;afterSha256=after.getParentNode()==null?null:hash(xml(after));
            generatedId=afterSha256==null?null:source.part+"#"+path(after);
            status=afterSha256==null?"REMOVED":retainedCellParagraph?"CLEARED_LAST_CELL_PARAGRAPH":"APPLIED";
        }
        public NodeAnchor getSource(){return source;} public String getAfterSha256(){return afterSha256;} public String getStatus(){return status;}
        public String getGeneratedId(){return generatedId;}
    }
    public static final class Feature {
        private final String kind,id,part,path,name,instruction,startId,endId;
        private final boolean complete;
        private Feature(String kind,String part,Element start,Element end,String name,String instruction) {
            this.kind=kind;this.part=part;path=path(start);id=part+"#"+path;this.name=name;this.instruction=instruction;
            startId=id;endId=end==null?null:part+"#"+path(end);complete=end!=null;
        }
        public String getKind(){return kind;} public String getId(){return id;} public String getPart(){return part;}
        public String getPath(){return path;} public String getName(){return name;} public String getInstruction(){return instruction;}
        public boolean isComplete(){return complete;} public String getStartId(){return startId;} public String getEndId(){return endId;}
    }
    public static final class FeatureImpact {
        private final Feature feature;private final String status;private final List<String> operationIds;
        private FeatureImpact(Feature feature,String status,List<String> ids){this.feature=feature;this.status=status;operationIds=Collections.unmodifiableList(new ArrayList<>(ids));}
        public Feature getFeature(){return feature;} public String getStatus(){return status;} public List<String> getOperationIds(){return operationIds;}
    }
    public static final class EditException extends IllegalArgumentException {
        private final String code,operationId;
        private EditException(String code,String operationId){super(code+": "+operationId);this.code=code;this.operationId=operationId;}
        public String getCode(){return code;} public String getOperationId(){return operationId;}
    }
    private static EditException error(String code,String id){return new EditException(code,id);}
    private static final class PackageModel {
        final LinkedHashMap<String,byte[]> entries=new LinkedHashMap<>();
        final Map<String,Document> xmlParts=new LinkedHashMap<>();
        final Map<String,Element> nodes=new LinkedHashMap<>();
        final Set<Element> retainedCellParagraphs=Collections.newSetFromMap(new IdentityHashMap<Element,Boolean>());
        final Map<String,List<Element>> insertedParagraphs=new LinkedHashMap<>();
        TemplateIndex index;
    }
    private static PackageModel load(byte[] bytes) {
        if(bytes==null||bytes.length<4||bytes[0]!='P'||bytes[1]!='K')throw error("INVALID_DOCX_PACKAGE","source");
        PackageModel model=new PackageModel();long total=0;byte[] buffer=new byte[8192];
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while((entry=in.getNextEntry())!=null) {
                String name=entry.getName();
                if(model.entries.size()>=COUNT_LIMIT||name.startsWith("/")||name.contains("\\")||name.contains("../")||name.contains(":"))throw error("UNSAFE_ZIP_ENTRY",name);
                if(model.entries.containsKey(name))throw error("DUPLICATE_ZIP_ENTRY",name);
                ByteArrayOutputStream out=new ByteArrayOutputStream();long expanded=0;int n;
                boolean xml=name.endsWith(".xml")||name.endsWith(".rels");
                while((n=in.read(buffer))!=-1) {
                    total+=n;expanded+=n;
                    if(expanded>ENTRY_LIMIT||(xml&&expanded>XML_LIMIT)||total>TOTAL_LIMIT)throw error("DOCX_INFLATION_LIMIT",name);
                    out.write(buffer,0,n);
                }
                model.entries.put(name,out.toByteArray());
            }
            if(!model.entries.containsKey(MAIN)||!model.entries.containsKey("[Content_Types].xml"))throw error("INVALID_DOCX_PACKAGE","source");
            validateContentType(model.entries.get("[Content_Types].xml"));
            List<Paragraph> paragraphs=new ArrayList<>(),all=new ArrayList<>();Map<String,NodeAnchor> anchors=new LinkedHashMap<>();List<Feature> features=new ArrayList<>();
            for(Map.Entry<String,byte[]> part:model.entries.entrySet()) {
                if(!part.getKey().matches("word/(?:document|header[0-9]+|footer[0-9]+|footnotes|endnotes)\\.xml"))continue;
                Document document=parse(part.getValue());
                if(!W.equals(document.getDocumentElement().getNamespaceURI()))throw error("UNSUPPORTED_DOCX_CODEC",part.getKey());
                if(MAIN.equals(part.getKey())&&(!is(document.getDocumentElement(),"document")||direct(document.getDocumentElement(),"body").size()!=1))throw error("UNSUPPORTED_DOCX_CODEC",part.getKey());
                model.xmlParts.put(part.getKey(),document);
                NodeList nodes=document.getElementsByTagNameNS(W,"p");
                for(int i=0;i<nodes.getLength();i++) {
                    Element p=(Element)nodes.item(i);Paragraph paragraph=new Paragraph(part.getKey(),path(p),p,i+1);
                    all.add(paragraph);if(MAIN.equals(part.getKey()))paragraphs.add(paragraph);model.nodes.put(paragraph.id,p);
                }
                for(String local:Arrays.asList("tc","tr","tbl","sectPr","p")) {
                    NodeList structural=document.getElementsByTagNameNS(W,local);
                    for(int i=0;i<structural.getLength();i++) {
                        Element node=(Element)structural.item(i);NodeAnchor anchor=new NodeAnchor(part.getKey(),node);
                        anchors.put(anchor.id,anchor);model.nodes.put(anchor.id,node);
                    }
                }
                features.addAll(features(part.getKey(),document,model.nodes));
            }
            Map<String,String> hashes=new LinkedHashMap<>();for(Map.Entry<String,byte[]> e:model.entries.entrySet())hashes.put(e.getKey(),hash(e.getValue()));
            model.index=new TemplateIndex(hash(bytes),paragraphs,hashes,anchors,features,all);return model;
        } catch(EditException e){throw e;} catch(Exception e){throw error("INVALID_DOCX_PACKAGE","source");}
    }
    private static void validateContentType(byte[] bytes) throws Exception {
        Document document=parse(bytes);String namespace="http://schemas.openxmlformats.org/package/2006/content-types";
        if(!namespace.equals(document.getDocumentElement().getNamespaceURI())||!"Types".equals(document.getDocumentElement().getLocalName()))throw error("UNSUPPORTED_DOCX_CODEC","[Content_Types].xml");
        NodeList overrides=document.getElementsByTagNameNS(namespace,"Override");int matches=0;
        for(int i=0;i<overrides.getLength();i++) {
            Element type=(Element)overrides.item(i);
            if("/word/document.xml".equals(type.getAttribute("PartName"))) {
                matches++;
                if(!"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml".equals(type.getAttribute("ContentType")))throw error("UNSUPPORTED_DOCX_CODEC","word/document.xml");
            }
        }
        if(matches!=1)throw error("UNSUPPORTED_DOCX_CODEC","word/document.xml");
    }
    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
    private static byte[] xml(Node node) {
        try {
            TransformerFactory factory=TransformerFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
            Transformer transformer=factory.newTransformer();transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,node instanceof Document?"no":"yes");
            transformer.setOutputProperty(OutputKeys.INDENT,"no");ByteArrayOutputStream out=new ByteArrayOutputStream();
            transformer.transform(new DOMSource(node),new StreamResult(out));return out.toByteArray();
        }catch(Exception e){throw error("XML_CODEC_UNAVAILABLE","source");}
    }
    private static String path(Element element) {
        List<String> parts=new ArrayList<>();Node current=element;
        while(current instanceof Element) {
            int n=1;for(Node prev=current.getPreviousSibling();prev!=null;prev=prev.getPreviousSibling())
                if(prev instanceof Element&&Objects.equals(prev.getNamespaceURI(),current.getNamespaceURI())&&Objects.equals(prev.getLocalName(),current.getLocalName()))n++;
            parts.add(current.getNodeName()+"["+n+"]");current=current.getParentNode();
        }
        Collections.reverse(parts);return "/"+String.join("/",parts);
    }
    private static String descendantText(Element node,String local) {
        StringBuilder text=new StringBuilder();NodeList list=node.getElementsByTagNameNS(W,local);
        for(int i=0;i<list.getLength();i++)text.append(list.item(i).getTextContent());return text.toString();
    }
    private static String editableText(Node node) {
        if(node instanceof Element&&W.equals(node.getNamespaceURI())) {
            if("pPr".equals(node.getLocalName())||"rPr".equals(node.getLocalName()))return "";
            if("t".equals(node.getLocalName()))return node.getTextContent();
            if("tab".equals(node.getLocalName()))return "\t";
            if("br".equals(node.getLocalName())||"cr".equals(node.getLocalName()))return "\n";
        }
        StringBuilder out=new StringBuilder();for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())out.append(editableText(child));
        return out.toString();
    }
    private static String hash(byte[] bytes) {
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();
            for(byte b:digest)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static final class Segment {
        final Element node;final int start,end;final boolean safe;
        Segment(Element node,int start,int end,boolean safe){this.node=node;this.start=start;this.end=end;this.safe=safe;}
    }
    private static List<Segment> segments(Element p) {
        List<Segment> result=new ArrayList<>();collectSegments(p,p,result,new int[]{0});return result;
    }
    private static void collectSegments(Node node,Element p,List<Segment> out,int[] offset) {
        if(node instanceof Element&&W.equals(node.getNamespaceURI())) {
            String local=node.getLocalName();
            if("pPr".equals(local)||"rPr".equals(local))return;
            // Source spelling/grammar markers carry no contractual anchor or visible control.
            // Keep them in place while permitting a text span across split proofing runs.
            if("proofErr".equals(local))return;
            if("t".equals(local)||"tab".equals(local)||"br".equals(local)||"cr".equals(local)) {
                int length="t".equals(local)?node.getTextContent().length():1;
                boolean safe="t".equals(local)&&is(node.getParentNode(),"r")&&node.getParentNode().getParentNode()==p;
                out.add(new Segment((Element)node,offset[0],offset[0]+length,safe));offset[0]+=length;return;
            }
            if(!"p".equals(local)&&!"r".equals(local))out.add(new Segment((Element)node,offset[0],offset[0],false));
        }else if(node instanceof Element) {
            out.add(new Segment((Element)node,offset[0],offset[0],false));
        }
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())collectSegments(child,p,out,offset);
    }
    private static boolean is(Node node,String local){return node instanceof Element&&W.equals(node.getNamespaceURI())&&local.equals(node.getLocalName());}
    private static boolean has(Element node,String local){return node.getElementsByTagNameNS(W,local).getLength()>0;}
    private static String ancestorId(String part,Element node,String local) {
        for(Node parent=node.getParentNode();parent instanceof Element;parent=parent.getParentNode())if(is(parent,local))return part+"#"+path((Element)parent);
        return null;
    }
    private static List<Element> direct(Node node,String local) {
        List<Element> out=new ArrayList<>();for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(is(child,local))out.add((Element)child);return out;
    }
    private static boolean contains(Node ancestor,Node child){for(Node n=child.getParentNode();n!=null;n=n.getParentNode())if(n==ancestor)return true;return false;}
    private static void validateSpan(Element target,Edit edit) {
        if(!is(target,"p"))throw error("TARGET_KIND_MISMATCH",edit.operationId);
        String original=editableText(target);
        if(edit.start<0||edit.end<edit.start||edit.end>original.length()||!original.substring(edit.start,edit.end).equals(edit.expectedText))throw error("TEXT_EXPECTATION_MISMATCH",edit.operationId);
        if(edit.start==edit.end)throw error("EMPTY_SPAN_UNSUPPORTED",edit.operationId);
        for(int offset:new int[]{edit.start,edit.end})if(offset>0&&offset<original.length()&&Character.isHighSurrogate(original.charAt(offset-1))&&Character.isLowSurrogate(original.charAt(offset)))throw error("INVALID_TEXT_BOUNDARY",edit.operationId);
        if(edit.replacement==null)throw error("INVALID_REPLACEMENT",edit.operationId);
        for(String fragment:edit.replacement.split("[\n\t]",-1))validText(fragment,edit.operationId);
        for(Segment segment:segments(target))if(!segment.safe&&(segment.start<edit.end&&segment.end>edit.start||segment.start==segment.end&&edit.start<segment.start&&segment.start<edit.end))throw error("UNSUPPORTED_INLINE_FEATURE",edit.operationId);
        if(has(target,"fldChar")||has(target,"instrText")||has(target,"fldSimple"))throw error("FIELD_TEXT_EDIT_UNSUPPORTED",edit.operationId);
    }
    private static void validateStructural(Element target,Edit edit) {
        if(edit.type==EditType.CLEAR_EDITORIAL_CELL) {
            if(!is(target,"tc"))throw error("TARGET_KIND_MISMATCH",edit.operationId);
            if(has(target,"tbl"))throw error("NESTED_EDITORIAL_CELL_UNSUPPORTED",edit.operationId);
        }else if(edit.type==EditType.DELETE_ROW) {
            if(!is(target,"tr"))throw error("TARGET_KIND_MISMATCH",edit.operationId);
            if(direct(target.getParentNode(),"tr").size()==1)throw error("LAST_TABLE_ROW_UNSUPPORTED",edit.operationId);
            // An unrelated merge elsewhere in the table does not bind this row.
            // Rows participating in a merge, or immediately before a continuation, remain guarded.
            if(has(target,"vMerge"))throw error("MERGED_ROW_DELETION_UNSUPPORTED",edit.operationId);
            Node next=target.getNextSibling();while(next!=null&&!is(next,"tr"))next=next.getNextSibling();
            if(next!=null)for(Element cell:direct(next,"tc"))for(Element properties:direct(cell,"tcPr"))for(Element merge:direct(properties,"vMerge"))if(!"restart".equals(merge.getAttributeNS(W,"val")))throw error("MERGED_ROW_DELETION_UNSUPPORTED",edit.operationId);
        }else if(edit.type==EditType.KEEP_ROW_TOGETHER) {
            if(!is(target,"tr")||!is(target.getParentNode(),"tbl"))throw error("TARGET_KIND_MISMATCH",edit.operationId);
            if(has(target,"sectPr")||has(target,"pageBreakBefore"))throw error("ROW_COHESION_CONTROL_UNSUPPORTED",edit.operationId);
            NodeList breaks=target.getElementsByTagNameNS(W,"br");
            for(int i=0;i<breaks.getLength();i++)if("page".equals(((Element)breaks.item(i)).getAttributeNS(W,"type")))throw error("ROW_COHESION_CONTROL_UNSUPPORTED",edit.operationId);
        }else if(edit.type==EditType.DELETE_TABLE) {
            if(!is(target,"tbl")||!is(target.getParentNode(),"body")&&!is(target.getParentNode(),"tc"))throw error("TABLE_LOCATION_UNSUPPORTED",edit.operationId);
        }else {
            if(!is(target,"p")||!is(target.getParentNode(),"tc")&&!is(target.getParentNode(),"body"))throw error("PARAGRAPH_LOCATION_UNSUPPORTED",edit.operationId);
        }
        if(edit.type==EditType.CLEAR_EDITORIAL_CELL||edit.type==EditType.CLEAR_PARAGRAPH||edit.type==EditType.CLEAR_PARAGRAPH_AND_NUMBERING)validateClear(target,edit.operationId);
        else if(has(target,"sectPr"))throw error("SECTION_DELETION_UNSUPPORTED",edit.operationId);
    }
    private static void validateClear(Element target,String operationId) {
        if(has(target,"fldChar")||has(target,"instrText")||has(target,"fldSimple"))throw error("FIELD_TEXT_CLEAR_UNSUPPORTED",operationId);
    }
    private static void clear(Element p) {
        NodeList texts=p.getElementsByTagNameNS(W,"t");for(int i=0;i<texts.getLength();i++)texts.item(i).setTextContent("");
    }
    private static void clearNumbering(Element p) {
        for(Element properties:direct(p,"pPr"))for(Element numbering:direct(properties,"numPr"))properties.removeChild(numbering);
    }
    private static void keepRowTogether(Element row) {
        List<Element> properties=direct(row,"trPr");
        Element trPr=properties.isEmpty()?row.getOwnerDocument().createElementNS(W,"w:trPr"):properties.get(0);
        if(properties.isEmpty())row.insertBefore(trPr,row.getFirstChild());
        List<Element> existing=direct(trPr,"cantSplit");
        if(!existing.isEmpty()){existing.get(0).setAttributeNS(W,"w:val","1");return;}
        Element property=row.getOwnerDocument().createElementNS(W,"w:cantSplit");property.setAttributeNS(W,"w:val","1");
        Node before=null;List<String> following=Arrays.asList("trHeight","tblHeader","tblCellSpacing","jc","hidden","ins","del","trPrChange");
        for(Node child=trPr.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&following.contains(child.getLocalName())){before=child;break;}
        trPr.insertBefore(property,before);
    }
    private static void validText(String text,String id) {
        if(text==null)throw error("INVALID_REPLACEMENT",id);
        for(int i=0;i<text.length();) {
            int cp=text.codePointAt(i);
            if(cp<0x20||cp==0xfffe||cp==0xffff||(cp>=0xd800&&cp<=0xdfff))throw error("UNSUPPORTED_REPLACEMENT_CHARACTER",id);
            i+=Character.charCount(cp);
        }
    }
    private static void setText(Element text,String value) {
        text.setTextContent(value);
        if(!value.isEmpty()&&(Character.isWhitespace(value.charAt(0))||Character.isWhitespace(value.charAt(value.length()-1))))
            text.setAttributeNS(XMLConstants.XML_NS_URI,"xml:space","preserve");
    }
    /** New body line breaks/tabs stay inside the selected run and preserve its native properties. */
    private static void setReplacementText(Element text,String value) {
        Node parent=text.getParentNode(),before=text.getNextSibling();Element current=text;int start=0;
        for(int i=0;i<value.length();i++)if(value.charAt(i)=='\n'||value.charAt(i)=='\t') {
            setText(current,value.substring(start,i));
            parent.insertBefore(text.getOwnerDocument().createElementNS(W,value.charAt(i)=='\t'?"w:tab":"w:br"),before);
            current=(Element)text.cloneNode(false);parent.insertBefore(current,before);start=i+1;
        }
        setText(current,value.substring(start));
    }
    private static void replace(PackageModel model,Element p,Edit edit) {
        List<Segment> segments=segments(p);Segment firstSegment=null;
        for(Segment segment:segments)if(segment.safe&&segment.start<edit.end&&segment.end>edit.start){firstSegment=segment;break;}
        Element firstRun=(Element)firstSegment.node.getParentNode(),donor=edit.donorId==null?firstRun:model.nodes.get(edit.donorId);
        List<Element> firstProperties=direct(firstRun,"rPr"),donorProperties=direct(donor,"rPr");
        String originalStyle=firstProperties.isEmpty()?"":intrinsicHash(firstProperties.get(0)),donorStyle=donorProperties.isEmpty()?"":intrinsicHash(donorProperties.get(0));
        boolean split=!originalStyle.equals(donorStyle),first=true;
        for(Segment segment:segments) {
            if(segment.start>=edit.end||segment.end<=edit.start)continue;
            String old=segment.node.getTextContent();
            int from=Math.max(0,edit.start-segment.start),to=Math.min(old.length(),edit.end-segment.start);
            String replacement=old.substring(0,from)+(first&&!split?edit.replacement:"")+old.substring(to);
            setReplacementText(segment.node,replacement);first=false;
        }
        if(split) {
            Element text=firstSegment.node;int from=edit.start-firstSegment.start;String remaining=text.getTextContent();
            Element right=(Element)firstRun.cloneNode(false);if(!firstProperties.isEmpty())right.appendChild(firstProperties.get(0).cloneNode(true));
            Element suffix=(Element)text.cloneNode(true);setText(suffix,remaining.substring(from));right.appendChild(suffix);setText(text,remaining.substring(0,from));
            while(text.getNextSibling()!=null)right.appendChild(text.getNextSibling());
            Element replacement=p.getOwnerDocument().createElementNS(W,"w:r");if(!donorProperties.isEmpty())replacement.appendChild(donorProperties.get(0).cloneNode(true));
            Element inserted=p.getOwnerDocument().createElementNS(W,"w:t");replacement.appendChild(inserted);setReplacementText(inserted,edit.replacement);
            p.insertBefore(replacement,firstRun.getNextSibling());p.insertBefore(right,replacement.getNextSibling());
        }
    }
    private static byte[] write(LinkedHashMap<String,byte[]> entries) {
        try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out)) {
            for(Map.Entry<String,byte[]> entry:entries.entrySet()) {
                ZipEntry target=new ZipEntry(entry.getKey());target.setTime(0);zip.putNextEntry(target);zip.write(entry.getValue());zip.closeEntry();
            }
            zip.finish();return out.toByteArray();
        }catch(IOException e){throw error("DOCX_WRITE_FAILED","batch");}
    }
    private static final class FieldBuilder {
        final Element start;final StringBuilder instruction=new StringBuilder();
        FieldBuilder(Element start){this.start=start;}
    }
    private static List<Feature> features(String part,Document doc,Map<String,Element> nodes) {
        List<Feature> result=new ArrayList<>();Map<String,Element> bookmarkEnds=new HashMap<>();
        NodeList ends=doc.getElementsByTagNameNS(W,"bookmarkEnd");for(int i=0;i<ends.getLength();i++) {Element end=(Element)ends.item(i);bookmarkEnds.put(end.getAttributeNS(W,"id"),end);}
        Deque<FieldBuilder> fields=new ArrayDeque<>();NodeList all=doc.getElementsByTagName("*");
        for(int i=0;i<all.getLength();i++) {
            Element node=(Element)all.item(i);nodes.put(part+"#"+path(node),node);String local=node.getLocalName();
            if(W.equals(node.getNamespaceURI())) {
                if("bookmarkStart".equals(local))result.add(new Feature("BOOKMARK",part,node,bookmarkEnds.get(node.getAttributeNS(W,"id")),node.getAttributeNS(W,"name"),null));
                else if("sectPr".equals(local))result.add(new Feature("SECTION",part,node,node,null,null));
                else if("fldSimple".equals(local))result.add(new Feature("FIELD",part,node,node,null,node.getAttributeNS(W,"instr")));
                else if("fldChar".equals(local)) {
                    String type=node.getAttributeNS(W,"fldCharType");
                    if("begin".equals(type))fields.push(new FieldBuilder(node));
                    else if("end".equals(type)&&!fields.isEmpty()) {FieldBuilder field=fields.pop();result.add(new Feature("FIELD",part,field.start,node,null,field.instruction.toString()));}
                }else if("instrText".equals(local)&&!fields.isEmpty())fields.peek().instruction.append(node.getTextContent());
            }else if(M.equals(node.getNamespaceURI())&&("oMath".equals(local)||"oMathPara".equals(local)))result.add(new Feature("OMML",part,node,node,null,null));
        }
        for(FieldBuilder field:fields)result.add(new Feature("FIELD",part,field.start,null,null,field.instruction.toString()));
        return result;
    }
    private static boolean inScope(Element scope,Element feature){return feature!=null&&(scope==feature||contains(scope,feature));}
    private static boolean insertion(Edit edit){return edit.type==EditType.INSERT_BEFORE||edit.type==EditType.INSERT_AFTER;}
    private static boolean destructive(Edit edit){return edit.type!=EditType.REPLACE_SPAN&&edit.type!=EditType.KEEP_ROW_TOGETHER&&!insertion(edit);}
    private static boolean removesNodes(PackageModel model,Edit edit) {
        return (edit.type==EditType.DELETE_PARAGRAPH||edit.type==EditType.DELETE_ROW||edit.type==EditType.DELETE_TABLE)&&!model.retainedCellParagraphs.contains(model.nodes.get(edit.targetId));
    }
    private static void validateFeatureScopes(PackageModel model,SourceEditBatch batch) {
        for(Feature feature:model.index.features) {
            if(!"BOOKMARK".equals(feature.kind)&&!"FIELD".equals(feature.kind))continue;
            boolean start=false,end=false;String operation="batch";
            for(Edit edit:batch.edits)if(removesNodes(model,edit)) {
                Element scope=model.nodes.get(edit.targetId);
                if(inScope(scope,model.nodes.get(feature.startId))){start=true;operation=edit.operationId;}
                if(inScope(scope,model.nodes.get(feature.endId)))end=true;
            }
            if(start!=end)throw error("PARTIAL_"+feature.kind+"_SCOPE",operation);
        }
    }
    private static List<FeatureImpact> featureImpacts(PackageModel model,SourceEditBatch batch) {
        List<FeatureImpact> impacts=new ArrayList<>();Set<String> removedBookmarks=new HashSet<>();List<String> allIds=new ArrayList<>();
        for(Edit edit:batch.edits)allIds.add(edit.operationId);
        for(Feature feature:model.index.features)if("BOOKMARK".equals(feature.kind))for(Edit edit:batch.edits)
            if(removesNodes(model,edit)&&inScope(model.nodes.get(edit.targetId),model.nodes.get(feature.startId)))removedBookmarks.add(feature.name);
        for(Feature feature:model.index.features) {
            List<String> touching=new ArrayList<>();boolean removed=false,cleared=false;
            for(Edit edit:batch.edits)if(affectsFeature(model,edit,feature)) {
                touching.add(edit.operationId);
                removed|=removesNodes(model,edit)&&inScope(model.nodes.get(edit.targetId),model.nodes.get(feature.startId));
                cleared|=destructive(edit)&&!removesNodes(model,edit);
            }
            if(!touching.isEmpty())impacts.add(new FeatureImpact(feature,removed?"REMOVED_WITH_ADOPTED_SCOPE":cleared?"TEXT_CLEARED_ANCHOR_PRESERVED":"TEXT_CHANGED_ANCHOR_PRESERVED",touching));
            else if("FIELD".equals(feature.kind)&&feature.instruction!=null) {
                String status=null;for(String name:removedBookmarks)if(name!=null&&!name.isEmpty()&&feature.instruction.contains(name)){status="REFERENCE_TARGET_REMOVED_UNREFRESHED";break;}
                if(status==null&&feature.instruction.trim().matches("(?is)^(?:TOC|PAGE|PAGEREF|NUMPAGES)\\b.*"))status="PAGINATION_REVIEW_REQUIRED";
                if(status!=null)impacts.add(new FeatureImpact(feature,status,allIds));
            }
        }
        return impacts;
    }
    private static boolean affectsFeature(PackageModel model,Edit edit,Feature feature) {
        if(edit.type==EditType.KEEP_ROW_TOGETHER)return false;
        Element scope=model.nodes.get(edit.targetId),start=model.nodes.get(feature.startId),end=model.nodes.get(feature.endId);
        if(removesNodes(model,edit)&&(inScope(scope,start)||inScope(scope,end)))return true;
        if(!"BOOKMARK".equals(feature.kind)&&!"FIELD".equals(feature.kind))return false;
        if(start==null||end==null||!scope.getOwnerDocument().equals(start.getOwnerDocument()))return false;
        if(insertion(edit)) {
            boolean startsBefore=(start.compareDocumentPosition(scope)&Node.DOCUMENT_POSITION_FOLLOWING)!=0;
            boolean endsAfter=(end.compareDocumentPosition(scope)&Node.DOCUMENT_POSITION_PRECEDING)!=0;
            if(edit.type==EditType.INSERT_AFTER)return (startsBefore||inScope(scope,start))&&endsAfter&&!inScope(scope,end);
            return startsBefore&&!inScope(scope,start)&&(endsAfter||inScope(scope,end));
        }
        if(edit.type==EditType.REPLACE_SPAN) {
            for(Segment segment:segments(scope))if(segment.safe&&segment.start<edit.end&&segment.end>edit.start&&insideFeature(segment.node,start,end))return true;
        }else {
            NodeList texts=scope.getElementsByTagNameNS(W,"t");
            for(int i=0;i<texts.getLength();i++)if(!texts.item(i).getTextContent().isEmpty()&&insideFeature(texts.item(i),start,end))return true;
        }
        return false;
    }
    private static boolean insideFeature(Node node,Element start,Element end) {
        if(start==end)return inScope(start,(Element)node);
        return (start.compareDocumentPosition(node)&Node.DOCUMENT_POSITION_FOLLOWING)!=0&&(end.compareDocumentPosition(node)&Node.DOCUMENT_POSITION_PRECEDING)!=0;
    }
    private static SourceEditBatch expand(SourceEditBatch batch) {
        List<Edit> expanded=new ArrayList<>();
        for(Edit edit:batch.edits) {
            if(edit.type!=EditType.REMOVE_NODES){expanded.add(edit);continue;}
            if(edit.targets.isEmpty())throw error("EMPTY_NODE_SCOPE",edit.operationId);
            for(NodeAnchor node:edit.targets) {
                EditType type="p".equals(node.kind)?EditType.DELETE_PARAGRAPH:"tr".equals(node.kind)?EditType.DELETE_ROW:"tbl".equals(node.kind)?EditType.DELETE_TABLE:null;
                if(type==null)throw error("TARGET_KIND_MISMATCH",edit.operationId);
                expanded.add(new Edit(edit.operationId,node.id,node.sha256,type));
            }
        }
        return new SourceEditBatch(batch.expectedSourceSha256,expanded);
    }
    private static void validateRemainingRows(PackageModel model,SourceEditBatch batch) {
        Map<Node,Integer> deleted=new IdentityHashMap<>();
        for(Edit edit:batch.edits)if(edit.type==EditType.DELETE_ROW) {Node table=model.nodes.get(edit.targetId).getParentNode();deleted.put(table,deleted.getOrDefault(table,0)+1);}
        for(Map.Entry<Node,Integer> e:deleted.entrySet())if(e.getValue()>=direct(e.getKey(),"tr").size())throw error("LAST_TABLE_ROW_UNSUPPORTED","batch");
    }
    private static void retainRequiredCellParagraphs(PackageModel model,SourceEditBatch batch) {
        Map<Node,List<Element>> deleted=new IdentityHashMap<>();Map<Element,String> operationIds=new IdentityHashMap<>();
        for(Edit edit:batch.edits)if(edit.type==EditType.DELETE_PARAGRAPH) {
            Element p=model.nodes.get(edit.targetId);Node parent=p.getParentNode();
            if(is(parent,"tc")){deleted.computeIfAbsent(parent,key->new ArrayList<>()).add(p);operationIds.put(p,edit.operationId);}
        }
        for(Map.Entry<Node,List<Element>> entry:deleted.entrySet()) {
            List<Element> all=direct(entry.getKey(),"p");
            if(entry.getValue().size()==all.size()) {
                Element retained=all.get(all.size()-1);validateClear(retained,operationIds.get(retained));model.retainedCellParagraphs.add(retained);
            }
        }
    }
    private static void validateInsertion(PackageModel model,Element target,Edit edit) {
        if(!is(target,"p")||!is(target.getParentNode(),"tc")&&!is(target.getParentNode(),"body"))throw error("PARAGRAPH_LOCATION_UNSUPPORTED",edit.operationId);
        Element donor=model.nodes.get(edit.donorId);
        if(donor==null||!is(donor,"p")||!hash(xml(donor)).equals(edit.donorSha256))throw error("STYLE_DONOR_MISMATCH",edit.operationId);
        if(has(donor,"sectPr"))throw error("SECTION_STYLE_DONOR_UNSUPPORTED",edit.operationId);
        if(edit.lines.isEmpty())throw error("EMPTY_INSERTION",edit.operationId);
        for(String line:edit.lines)validText(line==null?null:line.replace("\t",""),edit.operationId);
    }
    private static void insert(PackageModel model,Element anchor,Edit edit) {
        Element donor=model.nodes.get(edit.donorId);Document doc=anchor.getOwnerDocument();
        Node before=edit.type==EditType.INSERT_AFTER?anchor.getNextSibling():anchor;
        for(String line:edit.lines) {
            Element p=doc.createElementNS(W,"w:p");List<Element> props=direct(donor,"pPr");if(!props.isEmpty())p.appendChild(props.get(0).cloneNode(true));
            Element r=doc.createElementNS(W,"w:r");List<Element> runs=direct(donor,"r");
            if(!runs.isEmpty()){List<Element> rProps=direct(runs.get(0),"rPr");if(!rProps.isEmpty())r.appendChild(rProps.get(0).cloneNode(true));}
            String[] fragments=line.split("\t",-1);for(int i=0;i<fragments.length;i++) {
                if(i>0)r.appendChild(doc.createElementNS(W,"w:tab"));Element t=doc.createElementNS(W,"w:t");setText(t,fragments[i]);r.appendChild(t);
            }
            p.appendChild(r);anchor.getParentNode().insertBefore(p,before);model.insertedParagraphs.computeIfAbsent(edit.operationId,id->new ArrayList<>()).add(p);
        }
    }
    private static List<AppliedEdit> ledger(PackageModel model,SourceEditBatch original,String generatedHash) {
        List<AppliedEdit> ledger=new ArrayList<>();
        for(Edit edit:original.edits) {
            List<TargetChange> targets=new ArrayList<>();
            if(edit.type==EditType.REMOVE_NODES)for(NodeAnchor node:edit.targets)targets.add(new TargetChange(node,model.nodes.get(node.id),model.retainedCellParagraphs.contains(model.nodes.get(node.id))));
            else targets.add(new TargetChange(model.index.anchor(edit.targetId),model.nodes.get(edit.targetId),model.retainedCellParagraphs.contains(model.nodes.get(edit.targetId))));
            RunInfo donor=null;
            if(edit.type==EditType.REPLACE_SPAN&&edit.donorId==null)for(RunInfo run:model.index.paragraph(edit.targetId).getRuns())if(run.start<edit.end&&run.end>edit.start){donor=run;break;}
            List<String> added=new ArrayList<>();for(Element paragraph:model.insertedParagraphs.getOrDefault(edit.operationId,Collections.emptyList()))if(paragraph.getParentNode()!=null)added.add(MAIN+"#"+path(paragraph));
            ledger.add(new AppliedEdit(edit,model.index.sourceSha256,generatedHash,targets,donor,added));
        }
        return ledger;
    }
    /** Namespace-aware node fingerprint ignores inherited xmlns declarations but includes all content/properties. */
    private static String intrinsicHash(Node node) {StringBuilder out=new StringBuilder();fingerprint(node,out);return hash(out.toString().getBytes(StandardCharsets.UTF_8));}
    private static void fingerprint(Node node,StringBuilder out) {
        out.append(node.getNodeType()).append(':');
        if(node instanceof Element) {
            out.append('{').append(node.getNamespaceURI()).append('}').append(node.getLocalName());List<String> attributes=new ArrayList<>();NamedNodeMap map=node.getAttributes();
            for(int i=0;i<map.getLength();i++){Node attr=map.item(i);if(!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI()))attributes.add("{"+attr.getNamespaceURI()+"}"+attr.getLocalName()+"="+attr.getNodeValue());}
            Collections.sort(attributes);for(String attr:attributes)out.append('|').append(attr.length()).append(':').append(attr);
            out.append('[');for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())fingerprint(child,out);out.append(']');
        }else {String value=node.getNodeValue();out.append(value==null?0:value.length()).append(':').append(value);}
    }
}
