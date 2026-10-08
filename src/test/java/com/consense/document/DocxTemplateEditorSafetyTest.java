package com.consense.document;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** Adversarial OOXML behavior through the same public package interface; not a layout acceptance. */
class DocxTemplateEditorSafetyTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private void rejected(String code,byte[] original,DocxTemplateEditor.TemplateIndex index,DocxTemplateEditor.Edit edit) {
        DocxTemplateEditor.EditException failure=assertThrows(DocxTemplateEditor.EditException.class,
                ()->editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(edit))));
        assertEquals(code,failure.getCode());
    }
    static byte[] document(String body) throws IOException {
        String doc="<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body>"+body+"<w:sectPr/></w:body></w:document>";
        Map<String,byte[]> entries=new LinkedHashMap<>();entries.put("[Content_Types].xml",("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));
        entries.put("word/document.xml",doc.getBytes(StandardCharsets.UTF_8));return zip(entries);
    }
    static byte[] zip(Map<String,byte[]> entries) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        return out.toByteArray();
    }
    @Test void rejectsTextSpansThatCrossOpaqueMathAndDrawingContent() throws Exception {
        for(String feature:Arrays.asList("<m:oMath><m:r><m:t>x</m:t></m:r></m:oMath>","<w:r><w:drawing/></w:r>","<w:bookmarkStart w:id=\"1\" w:name=\"middle\"/><w:bookmarkEnd w:id=\"1\"/>")) {
            byte[] original=document("<w:p><w:r><w:t>Left</w:t></w:r>"+feature+"<w:r><w:t>Right</w:t></w:r></w:p>");
            DocxTemplateEditor.TemplateIndex index=editor.inspect(original);DocxTemplateEditor.Paragraph p=index.mainParagraph(1);
            DocxTemplateEditor.Edit edit=DocxTemplateEditor.Edit.replaceSpan("opaque-span",p,0,p.getText().length(),"LeftRight","Replacement");
            DocxTemplateEditor.EditException failure=assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(edit))));
            assertEquals("UNSUPPORTED_INLINE_FEATURE",failure.getCode());
            assertArrayEquals(original,editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.emptyList())).getDocxBytes());
        }
    }
    @Test void deletesOnlyUnmergedRowsWhilePreservingAnUnrelatedVerticalMergeChain() throws Exception {
        byte[] original=document("<w:tbl><w:tblGrid><w:gridCol w:w=\"2000\"/></w:tblGrid><w:tr><w:tc><w:p><w:r><w:t>Guide</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:tcPr><w:vMerge w:val=\"restart\"/></w:tcPr><w:p><w:r><w:t>Start</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p><w:r><w:t>Continue</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>Discard</w:t></w:r></w:p></w:tc></w:tr></w:tbl>");
        DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
        for(int ordinal:new int[]{2,3})rejected("MERGED_ROW_DELETION_UNSUPPORTED",original,index,DocxTemplateEditor.Edit.deleteRow("merge-"+ordinal,index.anchor(index.mainParagraph(ordinal).getRowId())));
        byte[] emitted=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Arrays.asList(DocxTemplateEditor.Edit.deleteRow("guide",index.anchor(index.mainParagraph(1).getRowId())),DocxTemplateEditor.Edit.deleteRow("discard",index.anchor(index.mainParagraph(4).getRowId()))))).getDocxBytes();
        org.w3c.dom.Document a=DocxTemplateEditorTest.mainXml(original),b=DocxTemplateEditorTest.mainXml(emitted);String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        org.w3c.dom.NodeList ar=a.getElementsByTagNameNS(w,"tr"),br=b.getElementsByTagNameNS(w,"tr");assertEquals(2,br.getLength());assertTrue(ar.item(1).isEqualNode(br.item(0)));assertTrue(ar.item(2).isEqualNode(br.item(1)));
    }
    @Test void rowCohesionRejectsExplicitPaginationControlsAndOverlappingContentEditsAtomically() throws Exception {
        for(String content:Arrays.asList("<w:p><w:r><w:t>Formula</w:t><w:br w:type=\"page\"/></w:r></w:p>","<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>Formula</w:t></w:r></w:p>","<w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>Formula</w:t></w:r></w:p>")) {
            byte[] original=document("<w:tbl><w:tr><w:tc>"+content+"</w:tc></w:tr></w:tbl>");DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
            rejected("ROW_COHESION_CONTROL_UNSUPPORTED",original,index,DocxTemplateEditor.Edit.keepRowTogether("unsafe",index.anchor(index.mainParagraph(1).getRowId())));
            assertArrayEquals(original,editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.emptyList())).getDocxBytes());
        }
        byte[] original=document("<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Formula</w:t></w:r></w:p></w:tc></w:tr></w:tbl>");DocxTemplateEditor.TemplateIndex index=editor.inspect(original);DocxTemplateEditor.Paragraph paragraph=index.mainParagraph(1);
        rejected("TARGET_KIND_MISMATCH",original,index,DocxTemplateEditor.Edit.keepRowTogether("wrong-kind",index.anchor(paragraph.getId())));
        DocxTemplateEditor.EditException overlap=assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Arrays.asList(DocxTemplateEditor.Edit.keepRowTogether("row",index.anchor(paragraph.getRowId())),DocxTemplateEditor.Edit.replaceSpan("text",paragraph,0,7,"Formula","Changed")))));
        assertEquals("OVERLAPPING_OPERATIONS",overlap.getCode());assertArrayEquals(original,editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.emptyList())).getDocxBytes());
    }
    @Test void aSelectedRunStyleAppliesOnlyToReplacementAndKeepsPrefixSuffixAndTabs() throws Exception {
        byte[] original=document("<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/></w:tabs></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>prefix TOKEN suffix</w:t><w:tab/><w:t>tail</w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\"> donor</w:t></w:r></w:p>");
        DocxTemplateEditor.TemplateIndex index=editor.inspect(original);DocxTemplateEditor.Paragraph p=index.mainParagraph(1);
        assertEquals("prefix TOKEN suffix\ttail donor",p.getText(),"Paragraph tab settings are not text");
        DocxTemplateEditor.Edit edit=DocxTemplateEditor.Edit.replaceSpan("styled-token",p,7,12,"TOKEN","VALUE").withStyleDonor(p.getRuns().get(1));
        byte[] edited=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(edit))).getDocxBytes();
        assertEquals("prefix VALUE suffix\ttail donor",editor.inspect(edited).mainParagraph(1).getText());
        org.w3c.dom.Element para=DocxTemplateEditorTest.p(DocxTemplateEditorTest.mainXml(edited),1);
        org.w3c.dom.NodeList runs=para.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","r");
        boolean replacementFound=false,prefixFound=false,suffixFound=false;
        for(int i=0;i<runs.getLength();i++) {
            org.w3c.dom.Element run=(org.w3c.dom.Element)runs.item(i);String text=DocxTemplateEditorTest.text(run);
            if(text.equals("VALUE")){replacementFound=true;assertEquals(1,run.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","i").getLength());}
            if(text.equals("prefix ")){prefixFound=true;assertEquals(1,run.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","b").getLength());}
            if(text.equals(" suffixtail")){suffixFound=true;assertEquals(1,run.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","b").getLength());}
        }
        assertTrue(replacementFound&&prefixFound&&suffixFound);assertEquals(2,para.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","tab").getLength());
    }
    @Test void protectsSectionsPartialBookmarksFieldsAndLastCellParagraphs() throws Exception {
        byte[] original=document("<w:p><w:bookmarkStart w:id=\"1\" w:name=\"split\"/><w:r><w:t>First</w:t></w:r></w:p><w:p><w:r><w:t>Second</w:t></w:r><w:bookmarkEnd w:id=\"1\"/></w:p>");
        DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
        rejected("PARTIAL_BOOKMARK_SCOPE",original,index,DocxTemplateEditor.Edit.deleteParagraph("partial",index.mainParagraph(1)));
        byte[] section=document("<w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>Section</w:t></w:r></w:p>");
        DocxTemplateEditor.TemplateIndex sectionIndex=editor.inspect(section);
        rejected("SECTION_DELETION_UNSUPPORTED",section,sectionIndex,DocxTemplateEditor.Edit.deleteParagraph("section",sectionIndex.mainParagraph(1)));
        byte[] cell=document("<w:tbl><w:tr><w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/></w:tcPr><w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr><w:r><w:t>Only paragraph</w:t></w:r></w:p></w:tc></w:tr></w:tbl>");
        DocxTemplateEditor.TemplateIndex ci=editor.inspect(cell);
        byte[] cleared=editor.apply(cell,new DocxTemplateEditor.SourceEditBatch(ci.getSourceSha256(),Collections.singletonList(DocxTemplateEditor.Edit.deleteParagraph("last-cell",ci.mainParagraph(1))))).getDocxBytes();
        assertEquals(1,editor.inspect(cleared).getMainParagraphs().size());assertEquals("",editor.inspect(cleared).mainParagraph(1).getText());
        assertEquals(DocxTemplateEditorTest.properties(DocxTemplateEditorTest.mainXml(cell).getDocumentElement(),"tcPr"),DocxTemplateEditorTest.properties(DocxTemplateEditorTest.mainXml(cleared).getDocumentElement(),"tcPr"));
        byte[] field=document("<w:p><w:r><w:t>before</w:t></w:r><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>");
        DocxTemplateEditor.TemplateIndex fi=editor.inspect(field);DocxTemplateEditor.Paragraph fp=fi.mainParagraph(1);
        rejected("FIELD_TEXT_EDIT_UNSUPPORTED",field,fi,DocxTemplateEditor.Edit.replaceSpan("field",fp,0,6,"before","after"));
    }
    @Test void leavesXmlWhitespaceAndNontextControlsOutsideAdoptedSpanUntouched() throws Exception {
        byte[] original=document("<w:p><w:r><w:t xml:space=\"preserve\"> TOKEN </w:t><w:tab/><w:t>suffix</w:t><w:br/></w:r></w:p>");
        DocxTemplateEditor.TemplateIndex index=editor.inspect(original);DocxTemplateEditor.Paragraph p=index.mainParagraph(1);
        byte[] result=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(DocxTemplateEditor.Edit.replaceSpan("space",p,1,6,"TOKEN"," VALUE ")))).getDocxBytes();
        assertEquals("  VALUE  \tsuffix\n",editor.inspect(result).mainParagraph(1).getText());
        org.w3c.dom.Document doc=DocxTemplateEditorTest.mainXml(result);
        assertEquals(1,doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","tab").getLength());
        assertEquals(1,doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","br").getLength());
        assertEquals("preserve",((org.w3c.dom.Element)doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","t").item(0)).getAttributeNS("http://www.w3.org/XML/1998/namespace","space"));
    }
    @Test void rejectsUnsupportedReplacementCharactersAndSplitSurrogates() throws Exception {
        byte[] original=document("<w:p><w:r><w:t>A😀B</w:t></w:r></w:p>");DocxTemplateEditor.TemplateIndex index=editor.inspect(original);DocxTemplateEditor.Paragraph p=index.mainParagraph(1);
        rejected("INVALID_TEXT_BOUNDARY",original,index,DocxTemplateEditor.Edit.replaceSpan("half-character",p,1,2,p.getText().substring(1,2),"X"));
        rejected("UNSUPPORTED_REPLACEMENT_CHARACTER",original,index,DocxTemplateEditor.Edit.replaceSpan("control",p,0,1,"A","\u0001"));
    }
    @Test void mapsCatalogOffsetsWithoutConfusingTabsAndBreaksWithTextOffsets() throws Exception {
        byte[] original=document("<w:p><w:r><w:t>label</w:t><w:tab/><w:t>TO</w:t></w:r><w:r><w:t>KEN</w:t><w:br/><w:t>tail</w:t></w:r></w:p>");
        DocxTemplateEditor.TemplateIndex index=editor.inspect(original);DocxTemplateEditor.Paragraph p=index.mainParagraph(1);
        assertEquals("labelTOKENtail",p.getCatalogText());assertEquals("label\tTOKEN\ntail",p.getText());
        DocxTemplateEditor.Edit edit=DocxTemplateEditor.Edit.replaceCatalogSpan("catalog-value",p,5,10,"TOKEN","VALUE");
        DocxTemplateEditor.DocxEditResult result=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(edit)));
        assertEquals("label\tVALUE\ntail",result.getIndex().mainParagraph(1).getText());
        assertEquals(6,result.getLedger().get(0).getStart());assertEquals(11,result.getLedger().get(0).getEnd());
        DocxTemplateEditor.EditException crossing=assertThrows(DocxTemplateEditor.EditException.class,
                ()->editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(
                        DocxTemplateEditor.Edit.replaceCatalogSpan("cross-tab",p,0,10,"labelTOKEN","Combined")))));
        assertEquals("UNSUPPORTED_INLINE_FEATURE",crossing.getCode());
    }
    @Test void guidanceClearsTextOnlyAndKeepsRunsBookmarkBoundariesBreaksAndMath() throws Exception {
        byte[] original=document("<w:tbl><w:tblGrid><w:gridCol w:w=\"2000\"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/></w:tcPr><w:p><w:bookmarkStart w:id=\"1\" w:name=\"guide\"/><w:r><w:rPr><w:i/></w:rPr><w:t>Editorial</w:t><w:tab/><w:br w:type=\"page\"/></w:r><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></w:p><w:p><w:r><w:t>prompt</w:t></w:r><w:bookmarkEnd w:id=\"1\"/></w:p></w:tc><w:tc><w:p><w:r><w:t>Contract body</w:t></w:r></w:p></w:tc></w:tr></w:tbl>");
        DocxTemplateEditor.TemplateIndex index=editor.inspect(original);DocxTemplateEditor.NodeAnchor cell=index.anchor(index.mainParagraph(1).getCellId());
        DocxTemplateEditor.DocxEditResult result=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(DocxTemplateEditor.Edit.clearEditorialCell("guide",cell))));
        org.w3c.dom.Document before=DocxTemplateEditorTest.mainXml(original),after=DocxTemplateEditorTest.mainXml(result.getDocxBytes());
        assertEquals("\t\n",result.getIndex().mainParagraph(1).getText());assertEquals("Contract body",result.getIndex().mainParagraph(3).getText());
        for(String local:Arrays.asList("rPr","bookmarkStart","bookmarkEnd","tab","br","tblGrid","tcPr"))
            assertEquals(DocxTemplateEditorTest.properties(before.getDocumentElement(),local),DocxTemplateEditorTest.properties(after.getDocumentElement(),local),local);
        assertEquals(1,after.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","oMath").getLength());
        assertTrue(result.getAffectedFeatures().stream().filter(f->"BOOKMARK".equals(f.getFeature().getKind())).allMatch(f->"TEXT_CLEARED_ANCHOR_PRESERVED".equals(f.getStatus())));
        byte[] field=document("<w:p><w:fldSimple w:instr=\"PAGE\"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p>");
        DocxTemplateEditor.TemplateIndex fi=editor.inspect(field);
        rejected("FIELD_TEXT_CLEAR_UNSUPPORTED",field,fi,DocxTemplateEditor.Edit.clearParagraph("field-clear",fi.mainParagraph(1)));
    }
    @Test void rejectsUnrecognizedSourceCodecsAndUnsafeXml() throws Exception {
        byte[] original=document("<w:p><w:r><w:t>text</w:t></w:r></w:p>");Map<String,byte[]> parts=DocxTemplateEditorTest.entries(original);
        Map<String,byte[]> badRoot=new LinkedHashMap<>(parts);badRoot.put("word/document.xml","<notWord/>".getBytes(StandardCharsets.UTF_8));
        assertEquals("UNSUPPORTED_DOCX_CODEC",assertThrows(DocxTemplateEditor.EditException.class,()->editor.inspect(zip(badRoot))).getCode());
        Map<String,byte[]> macro=new LinkedHashMap<>(parts);macro.put("[Content_Types].xml",new String(parts.get("[Content_Types].xml"),StandardCharsets.UTF_8).replace("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml","application/vnd.ms-word.document.macroEnabled.main+xml").getBytes(StandardCharsets.UTF_8));
        assertEquals("UNSUPPORTED_DOCX_CODEC",assertThrows(DocxTemplateEditor.EditException.class,()->editor.inspect(zip(macro))).getCode());
        Map<String,byte[]> unsafe=new LinkedHashMap<>(parts);unsafe.put("word/document.xml","<!DOCTYPE w:document [<!ENTITY x SYSTEM 'file:///never-read-this'>]><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>".getBytes(StandardCharsets.UTF_8));
        assertEquals("INVALID_DOCX_PACKAGE",assertThrows(DocxTemplateEditor.EditException.class,()->editor.inspect(zip(unsafe))).getCode());
        Map<String,byte[]> traversal=new LinkedHashMap<>(parts);traversal.put("../outside",new byte[]{1});
        assertEquals("UNSAFE_ZIP_ENTRY",assertThrows(DocxTemplateEditor.EditException.class,()->editor.inspect(zip(traversal))).getCode());
    }
    @Test void featureManifestReportsOnlyTextActuallyInsideBookmarkRanges() throws Exception {
        byte[] bounded=document("<w:p><w:r><w:t>Outside</w:t></w:r><w:bookmarkStart w:id=\"1\" w:name=\"inside\"/><w:r><w:t>Inside</w:t></w:r><w:bookmarkEnd w:id=\"1\"/></w:p>");
        DocxTemplateEditor.TemplateIndex bi=editor.inspect(bounded);
        DocxTemplateEditor.DocxEditResult outside=editor.apply(bounded,new DocxTemplateEditor.SourceEditBatch(bi.getSourceSha256(),Collections.singletonList(DocxTemplateEditor.Edit.replaceSpan("outside",bi.mainParagraph(1),0,7,"Outside","Changed"))));
        assertTrue(outside.getAffectedFeatures().stream().noneMatch(f->"BOOKMARK".equals(f.getFeature().getKind())),"A marker in the same paragraph is not enough to mark a bookmark affected");
        byte[] spanning=document("<w:p><w:bookmarkStart w:id=\"1\" w:name=\"three-paragraphs\"/><w:r><w:t>First</w:t></w:r></w:p><w:p><w:r><w:t>Middle</w:t></w:r></w:p><w:p><w:r><w:t>Last</w:t></w:r><w:bookmarkEnd w:id=\"1\"/></w:p>");
        DocxTemplateEditor.TemplateIndex si=editor.inspect(spanning);
        DocxTemplateEditor.DocxEditResult middle=editor.apply(spanning,new DocxTemplateEditor.SourceEditBatch(si.getSourceSha256(),Collections.singletonList(DocxTemplateEditor.Edit.replaceSpan("middle",si.mainParagraph(2),0,6,"Middle","Changed"))));
        assertEquals(1,middle.getAffectedFeatures().size());assertEquals("three-paragraphs",middle.getAffectedFeatures().get(0).getFeature().getName());
        assertEquals(Collections.singletonList("middle"),middle.getAffectedFeatures().get(0).getOperationIds());
    }
}
