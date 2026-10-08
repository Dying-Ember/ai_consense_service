package com.consense.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.regex.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Acceptance at the public editor and emitted original-package seam, using the actual standards. */
class DocxTemplateEditorTest {
    private static final String[] NAMES={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
    private static final int[] PARAGRAPHS={665,1238,2074};
    private static final String[] HASHES={"60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"};
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    static byte[] source(int document) throws IOException {
        String configured=System.getProperty("consense.acceptance.sourceDir");
        assumeTrue(configured!=null,"Set consense.acceptance.sourceDir to the actual 2a standards.");
        return Files.readAllBytes(Paths.get(configured).resolve(NAMES[document]));
    }
    static Map<String,byte[]> entries(byte[] bytes) throws IOException {
        Map<String,byte[]> result=new LinkedHashMap<>();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=in.getNextEntry())!=null) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();int n;
                while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                assertNull(result.put(entry.getName(),out.toByteArray()),"Duplicate ZIP entry");
            }
        }
        return result;
    }
    static void preserve(String name,byte[] bytes) throws IOException {
        String configured=System.getProperty("consense.acceptance.outputDir");
        if(configured==null)return;
        Path dir=Paths.get(configured);Files.createDirectories(dir);
        String hash=new DocxTemplateEditor().inspect(bytes).getSourceSha256();
        Path target=dir.resolve(name.substring(0,name.length()-5)+"-"+hash+".docx");
        if(Files.exists(target))assertArrayEquals(Files.readAllBytes(target),bytes,"Evidence must not be overwritten");
        else Files.write(target,bytes,StandardOpenOption.CREATE_NEW);
    }
    @Test void indexesEveryActualParagraphAndReturnsNoOpPackagesByteForByte() throws Exception {
        for(int i=0;i<NAMES.length;i++) {
            byte[] original=source(i);
            DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
            assertEquals(HASHES[i],index.getSourceSha256());
            assertEquals(PARAGRAPHS[i],index.getMainParagraphs().size(),NAMES[i]);
            byte[] result=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.emptyList())).getDocxBytes();
            assertArrayEquals(original,result,"No-op must return the exact original package");
            Map<String,byte[]> originalParts=entries(original),resultParts=entries(result);
            assertEquals(originalParts.keySet(),resultParts.keySet());
            for(String part:originalParts.keySet())assertArrayEquals(originalParts.get(part),resultParts.get(part),part);
            try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(result))) { assertFalse(word.getBodyElements().isEmpty()); }
            preserve("noop-"+NAMES[i],result);
        }
    }
    static Document mainXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(entries(bytes).get("word/document.xml")));
    }
    static Element p(Document doc,int ordinal) { return (Element)doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","p").item(ordinal-1); }
    static String text(Element p) {
        StringBuilder out=new StringBuilder();NodeList ts=p.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","t");
        for(int i=0;i<ts.getLength();i++)out.append(ts.item(i).getTextContent());return out.toString();
    }
    static String xml(Node n) throws Exception {
        Transformer t=TransformerFactory.newInstance().newTransformer();t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");
        StringWriter out=new StringWriter();t.transform(new DOMSource(n),new StreamResult(out));return out.toString();
    }
    static List<String> properties(Element p,String local) throws Exception {
        List<String> out=new ArrayList<>();NodeList nodes=p.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main",local);
        for(int i=0;i<nodes.getLength();i++)out.add(xml(nodes.item(i)));return out;
    }
    static void unchangedOpaqueParts(byte[] source,byte[] result) throws Exception {
        Map<String,byte[]> before=entries(source),after=entries(result);assertEquals(before.keySet(),after.keySet());
        for(String key:before.keySet())if(!key.equals("word/document.xml"))assertArrayEquals(before.get(key),after.get(key),key);
    }
    @Test void sourceFractionRowCohesionKeepsEveryExistingNativeNodeAndOpaquePackagePartExact() throws Exception {
        byte[] original=source(1);DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
        DocxTemplateEditor.NodeAnchor row=index.anchor(index.mainParagraph(796).getRowId());assertEquals(23,row.getParagraphIds().size());
        DocxTemplateEditor.DocxEditResult result=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(DocxTemplateEditor.Edit.keepRowTogether("fraction",row))));
        assertEquals(DocxTemplateEditor.EditType.KEEP_ROW_TOGETHER,result.getLedger().get(0).getType());unchangedOpaqueParts(original,result.getDocxBytes());
        Document before=mainXml(original),after=mainXml(result.getDocxBytes());Element sourceRow=(Element)p(before,796).getParentNode().getParentNode(),outputRow=(Element)p(after,796).getParentNode().getParentNode();
        assertEquals("tr",sourceRow.getLocalName());assertEquals("tr",outputRow.getLocalName());assertEquals(0,sourceRow.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","cantSplit").getLength());
        Element properties=(Element)outputRow.getFirstChild();assertEquals("trPr",properties.getLocalName());assertEquals(1,properties.getChildNodes().getLength());assertEquals("cantSplit",properties.getFirstChild().getLocalName());assertEquals("1",((Element)properties.getFirstChild()).getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","val"));
        outputRow.removeChild(properties);assertTrue(before.isEqualNode(after),"The emitted complete main XML is source-identical except the one containing-row pagination property");
        assertTrue(result.getAffectedFeatures().stream().noneMatch(f->f.getStatus().startsWith("TEXT_")),"Pagination cohesion does not claim a field/bookmark text change or refresh");preserve("fraction-cohesion-SCT.docx",result.getDocxBytes());
    }
    static DocxTemplateEditor.Edit span(String id,DocxTemplateEditor.Paragraph p,String old,String replacement) {
        int start=p.getText().indexOf(old);assertTrue(start>=0,"Actual token not found: "+old);
        return DocxTemplateEditor.Edit.replaceSpan(id,p,start,start+old.length(),old,replacement);
    }
    @Test void fillsActualSplitRunBondRatesAndContactWithoutReplacingTheirFormatting() throws Exception {
        byte[] ntt=source(0);DocxTemplateEditor.TemplateIndex ni=editor.inspect(ntt);
        DocxTemplateEditor.Paragraph bond=ni.mainParagraph(484);
        byte[] edited=editor.apply(ntt,new DocxTemplateEditor.SourceEditBatch(ni.getSourceSha256(),Collections.singletonList(span("bond",bond,"*G1/*G1a","G1a")))).getDocxBytes();
        assertEquals(text(p(mainXml(ntt),484)).replace("*G1/*G1a","G1a"),text(p(mainXml(edited),484)));
        assertEquals(properties(p(mainXml(ntt),484),"pPr"),properties(p(mainXml(edited),484),"pPr"));
        assertEquals(properties(p(mainXml(ntt),484),"rPr"),properties(p(mainXml(edited),484),"rPr"));
        assertEquals(xml(p(mainXml(ntt),546)),xml(p(mainXml(edited),546)),"Unrelated body paragraph");
        unchangedOpaqueParts(ntt,edited);preserve("filled-bond-NTT.docx",edited);
        byte[] sct=source(1);DocxTemplateEditor.TemplateIndex si=editor.inspect(sct);
        DocxTemplateEditor.Paragraph rate=si.mainParagraph(751),contact=si.mainParagraph(923);
        Matcher officer=Pattern.compile("A/\\s*\\(\\*Mr/Ms\\s*\\)").matcher(contact.getText());assertTrue(officer.find());
        Matcher phone=Pattern.compile("telephone\\s*\\.").matcher(contact.getText());assertTrue(phone.find());
        List<DocxTemplateEditor.Edit> edits=Arrays.asList(span("small-rate",rate,"#$8.7","$0"),span("large-rate",rate,"#$45","$2"),
                span("architect",contact,officer.group(),"Architect (Ms Test Person)"),span("telephone",contact,phone.group(),"telephone 12345678."));
        byte[] filled=editor.apply(sct,new DocxTemplateEditor.SourceEditBatch(si.getSourceSha256(),edits)).getDocxBytes();
        assertTrue(text(p(mainXml(filled),751)).contains("rate of $0 per page"));assertTrue(text(p(mainXml(filled),751)).contains("rate of $2 per page"));
        assertTrue(text(p(mainXml(filled),923)).contains("Architect (Ms Test Person), telephone 12345678."));
        for(int ordinal:new int[]{751,923}) {
            assertEquals(properties(p(mainXml(sct),ordinal),"pPr"),properties(p(mainXml(filled),ordinal),"pPr"));
            assertEquals(properties(p(mainXml(sct),ordinal),"rPr"),properties(p(mainXml(filled),ordinal),"rPr"));
        }
        unchangedOpaqueParts(sct,filled);preserve("filled-rates-contact-SCT.docx",filled);
    }
    static Element ancestor(Element p,String local){Node n=p;while(n instanceof Element){if(local.equals(n.getLocalName()))return (Element)n;n=n.getParentNode();}return null;}
    @Test void clearsOnlyExplicitEditorialCellsAndDeletesOnlyTheSelectedParagraphOrRow() throws Exception {
        byte[] original=source(0);DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
        DocxTemplateEditor.Paragraph guidance=index.mainParagraph(486),facade=index.mainParagraph(75);
        byte[] result=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Arrays.asList(
                DocxTemplateEditor.Edit.clearEditorialCell("bond-guidance",index.anchor(guidance.getCellId())),
                DocxTemplateEditor.Edit.deleteParagraph("facade",facade)))).getDocxBytes();
        Document before=mainXml(original),after=mainXml(result);
        assertFalse(text(after.getDocumentElement()).contains("The tender shall be based on the use of precast concrete facades"));
        assertTrue(text(after.getDocumentElement()).contains("The tender shall be “Lump Sum”"));
        assertTrue(text(after.getDocumentElement()).contains("(Delete if precast façade is not applicable)"),"Unselected guidance is not heuristically removed");
        assertEquals(xml(p(before,484)),xml(p(after,483)),"Clearing editorial cell must preserve the neighboring body cell");
        Element beforeCell=ancestor(p(before,486),"tc"),afterCell=ancestor(p(after,485),"tc");
        assertEquals("",text(afterCell));assertEquals(properties(beforeCell,"tcPr"),properties(afterCell,"tcPr"));
        assertEquals(properties(beforeCell,"pPr"),properties(afterCell,"pPr"));
        assertEquals(properties(before.getDocumentElement(),"tblGrid"),properties(after.getDocumentElement(),"tblGrid"));
        unchangedOpaqueParts(original,result);preserve("guidance-and-facade-NTT.docx",result);
        byte[] sct=source(1);DocxTemplateEditor.TemplateIndex si=editor.inspect(sct);
        byte[] removed=editor.apply(sct,new DocxTemplateEditor.SourceEditBatch(si.getSourceSha256(),Collections.singletonList(
                DocxTemplateEditor.Edit.deleteRow("foundation-row",si.anchor(si.mainParagraph(627).getRowId()))))).getDocxBytes();
        assertFalse(text(mainXml(removed).getDocumentElement()).contains("#(e)Assessment of Sub-surface Conditions"));
        assertTrue(text(mainXml(removed).getDocumentElement()).contains("Special Terms of Payment"));
        assertEquals(mainXml(sct).getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","tr").getLength()-1,
                mainXml(removed).getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","tr").getLength());
        assertEquals(properties(mainXml(sct).getDocumentElement(),"tblGrid"),properties(mainXml(removed).getDocumentElement(),"tblGrid"));
        unchangedOpaqueParts(sct,removed);preserve("foundation-row-removed-SCT.docx",removed);
    }
    @Test void preservesActualSccSectionsFieldsBookmarksAndOpaqueFormattingAndReportsFieldReview() throws Exception {
        byte[] original=source(2);DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
        assertEquals(415,index.getFeatures().stream().filter(f->"BOOKMARK".equals(f.getKind())).count());
        assertEquals(2,index.getFeatures().stream().filter(f->"SECTION".equals(f.getKind())).count());
        assertTrue(index.getFeatures().stream().filter(f->"FIELD".equals(f.getKind())).count()>90);
        DocxTemplateEditor.DocxEditResult result=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),
                Arrays.asList(span("warranty",index.mainParagraph(570),"ten/twenty*","twenty"),
                        DocxTemplateEditor.Edit.clearParagraph("guidance-P100",index.mainParagraph(100)),
                        DocxTemplateEditor.Edit.insertParagraphs("scc-body-amendment",index.mainParagraph(570),true,Collections.singletonList("Adopted test body paragraph"),index.mainParagraph(570)))));
        byte[] edited=result.getDocxBytes();unchangedOpaqueParts(original,edited);
        Document before=mainXml(original),after=mainXml(edited);
        for(String local:Arrays.asList("sectPr","bookmarkStart","bookmarkEnd","fldChar","instrText","numPr","tblGrid"))
            assertEquals(properties(before.getDocumentElement(),local),properties(after.getDocumentElement(),local),local);
        assertTrue(text(p(after,570)).contains("warranty period of twenty years"));
        assertEquals("",text(p(after,100)));assertEquals("Adopted test body paragraph",text(p(after,571)));
        assertEquals(properties(p(before,100),"pPr"),properties(p(after,100),"pPr"));
        assertEquals(properties(p(before,100),"rPr"),properties(p(after,100),"rPr"));
        assertEquals(properties(p(before,570),"pPr"),properties(p(after,571),"pPr"));
        assertEquals(properties(p(before,570),"rPr").get(0),properties(p(after,571),"rPr").get(0));
        NodeList beforeMath=before.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","oMath"),afterMath=after.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","oMath");
        assertEquals(4,beforeMath.getLength());assertEquals(beforeMath.getLength(),afterMath.getLength());
        for(int i=0;i<beforeMath.getLength();i++)assertEquals(xml(beforeMath.item(i)),xml(afterMath.item(i)),"Native equation "+i);
        assertFalse(result.getAffectedFeatures().isEmpty());
        assertTrue(result.getAffectedFeatures().stream().anyMatch(f->"PAGINATION_REVIEW_REQUIRED".equals(f.getStatus())));
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(edited))) { assertFalse(word.getHeaderList().isEmpty());assertNotNull(word.getNumbering()); }
        preserve("warranty-field-review-SCC.docx",edited);
        for(int document=0;document<3;document++) {
            byte[] bytes=source(document);Document dom=mainXml(bytes);
            int math=dom.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","oMath").getLength()
                    +dom.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","oMathPara").getLength();
            assertEquals(math,editor.inspect(bytes).getFeatures().stream().filter(f->"OMML".equals(f.getKind())).count());
        }
    }
    @Test void insertsBodyParagraphsFromSourceStylesAndRemovesAnExplicitGuidanceRange() throws Exception {
        byte[] original=source(0);DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
        DocxTemplateEditor.Paragraph body=index.mainParagraph(546);
        List<DocxTemplateEditor.NodeAnchor> range=Arrays.asList(index.anchor(index.mainParagraph(107).getId()),index.anchor(index.mainParagraph(108).getId()),index.anchor(index.mainParagraph(109).getId()));
        DocxTemplateEditor.DocxEditResult result=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Arrays.asList(
                DocxTemplateEditor.Edit.insertParagraphs("body-amendment",body,true,Arrays.asList("First adopted test paragraph","Second adopted test paragraph"),body),
                DocxTemplateEditor.Edit.removeNodes("selected-guidance-range",range))));
        assertEquals(2,result.getLedger().size());assertEquals(index.getSourceSha256(),result.getOriginalSourceSha256());
        assertEquals(Arrays.asList("First adopted test paragraph","Second adopted test paragraph"),result.getLedger().get(0).getInsertedLines());
        assertNotNull(result.getIndex().anchor(result.getLedger().get(0).getTargets().get(0).getGeneratedId()));
        assertNotEquals(index.getSourceSha256(),result.getDocxSha256());
        Document dom=mainXml(result.getDocxBytes()),before=mainXml(original);
        DocxTemplateEditor.TemplateIndex updated=result.getIndex();
        DocxTemplateEditor.Paragraph first=updated.getMainParagraphs().stream().filter(p->p.getText().equals("First adopted test paragraph")).findFirst().orElseThrow(AssertionError::new);
        DocxTemplateEditor.Paragraph second=updated.getMainParagraphs().stream().filter(p->p.getText().equals("Second adopted test paragraph")).findFirst().orElseThrow(AssertionError::new);
        assertEquals(first.getCellId(),second.getCellId());assertEquals(body.getCellId(),first.getCellId());
        assertEquals(properties(p(before,546),"pPr"),properties(p(dom,first.getOrdinal()),"pPr"));
        assertEquals(properties(p(before,546),"rPr").get(0),properties(p(dom,first.getOrdinal()),"rPr").get(0));
        assertEquals(1,properties(ancestor(p(dom,first.getOrdinal()),"tc"),"tcPr").size(),"Only one source cell property node");
        assertTrue(text(dom.getDocumentElement()).contains("(Alternative (a) - for tenders"),"Other source-bound repeat remains");
        assertEquals(properties(before.getDocumentElement(),"tblGrid"),properties(dom.getDocumentElement(),"tblGrid"));
        unchangedOpaqueParts(original,result.getDocxBytes());preserve("body-and-guidance-range-NTT.docx",result.getDocxBytes());
        byte[] secondRevision=editor.apply(result.getDocxBytes(),new DocxTemplateEditor.SourceEditBatch(updated.getSourceSha256(),
                Collections.singletonList(span("body-wording",first,"First adopted test paragraph","Edited body paragraph")))).getDocxBytes();
        assertTrue(text(mainXml(secondRevision).getDocumentElement()).contains("Edited body paragraph"));
        preserve("second-body-revision-NTT.docx",secondRevision);
    }
    @Test void rejectsSourceTargetExpectationAndOverlapFailuresAtomically() throws Exception {
        byte[] original=source(0),unchanged=original.clone();DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
        DocxTemplateEditor.Paragraph bond=index.mainParagraph(484);DocxTemplateEditor.Edit good=span("bond",bond,"*G1/*G1a","G1a");
        DocxTemplateEditor.EditException badHash=assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,new DocxTemplateEditor.SourceEditBatch("wrong-source",Collections.singletonList(good))));
        assertEquals("SOURCE_HASH_MISMATCH",badHash.getCode());
        DocxTemplateEditor.EditException badId=assertThrows(DocxTemplateEditor.EditException.class,()->index.anchor("word/document.xml#/missing"));assertEquals("TARGET_NOT_FOUND",badId.getCode());
        DocxTemplateEditor.Edit badText=DocxTemplateEditor.Edit.replaceSpan("wrong-text",index.mainParagraph(546),0,3,"BAD","good");
        DocxTemplateEditor.EditException expectation=assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Arrays.asList(good,badText))));
        assertEquals("TEXT_EXPECTATION_MISMATCH",expectation.getCode());
        DocxTemplateEditor.EditException overlap=assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Arrays.asList(good,span("conflict",bond,"*G1/*G1a","G1")))));
        assertEquals("OVERLAPPING_OPERATIONS",overlap.getCode());
        byte[] different=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(good))).getDocxBytes();
        DocxTemplateEditor.Paragraph otherRevision=editor.inspect(different).mainParagraph(484);
        DocxTemplateEditor.EditException wrongTarget=assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(span("other-revision",otherRevision,"G1a","G1")))));
        assertEquals("TARGET_HASH_MISMATCH",wrongTarget.getCode());
        DocxTemplateEditor.EditException scoped=assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Arrays.asList(good,DocxTemplateEditor.Edit.clearEditorialCell("shared-cell",index.anchor(bond.getCellId()))))));
        assertEquals("OVERLAPPING_OPERATIONS",scoped.getCode());
        assertArrayEquals(unchanged,original);assertArrayEquals(original,editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.emptyList())).getDocxBytes());
    }
    @Test void clearsNoncontiguousActualGuidanceWithoutErasingInterleavedFormalText() throws Exception {
        int[][] selected={{614,617,620},{60,65,66}},formal={{619},{61,63}};
        for(int doc=0;doc<2;doc++) {
            byte[] original=source(doc);DocxTemplateEditor.TemplateIndex index=editor.inspect(original);List<DocxTemplateEditor.Edit> edits=new ArrayList<>();
            for(int ordinal:selected[doc])edits.add(DocxTemplateEditor.Edit.clearParagraph("guide-P"+ordinal,index.mainParagraph(ordinal)));
            byte[] result=editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),edits)).getDocxBytes();
            Document before=mainXml(original),after=mainXml(result);
            for(int ordinal:selected[doc])assertEquals("",text(p(after,ordinal)));
            for(int ordinal:formal[doc])assertEquals(xml(p(before,ordinal)),xml(p(after,ordinal)),"Interleaved formal P"+ordinal);
            for(String property:Arrays.asList("pPr","rPr","tblGrid","tcPr","sectPr","bookmarkStart","bookmarkEnd","fldChar","tab","br"))
                assertEquals(properties(before.getDocumentElement(),property),properties(after.getDocumentElement(),property),property);
            unchangedOpaqueParts(original,result);preserve("noncontiguous-guidance-"+(doc==0?"NTT":"SCT")+".docx",result);
        }
    }
}
