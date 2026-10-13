package com.consense.document;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact TOC label edits must preserve native hyperlinks, controls and field results. */
class DocxCatalogLabelFieldSafetyTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private static final String LABEL="Definitions (conditional)";
    private static final String FIELD="<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> PAGEREF _TocExample \\h </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>12</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    private static byte[] source() throws Exception {return DocxTemplateEditorSafetyTest.document("<w:p><w:hyperlink w:anchor=\"_TocExample\"><w:r><w:t>SCC1.304</w:t><w:tab/></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>"+LABEL+"</w:t></w:r><w:r><w:tab/></w:r>"+FIELD+"</w:hyperlink></w:p>");}
    @Test void updatesOnlyTheTocLabelOutsideItsNativePageField() throws Exception {
        byte[] source=source();DocxTemplateEditor.TemplateIndex index=editor.inspect(source);DocxTemplateEditor.Paragraph p=index.mainParagraph(1);
        DocxTemplateEditor.Edit edit=assertDoesNotThrow(()->DocxTemplateEditor.Edit.replaceCatalogLabelOutsideFields("toc-label",p,8,8+LABEL.length(),LABEL,"Not used"));
        byte[] actual=editor.apply(source,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(edit))).getDocxBytes();
        assertEquals("SCC1.304\tNot used\t12",editor.inspect(actual).mainParagraph(1).getText());
        org.w3c.dom.Document xml=DocxTemplateEditorTest.mainXml(actual);String ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        assertEquals(2,xml.getElementsByTagNameNS(ns,"tab").getLength());assertEquals(3,xml.getElementsByTagNameNS(ns,"fldChar").getLength());
        assertEquals(" PAGEREF _TocExample \\h ",xml.getElementsByTagNameNS(ns,"instrText").item(0).getTextContent());
        assertEquals("_TocExample",((org.w3c.dom.Element)xml.getElementsByTagNameNS(ns,"hyperlink").item(0)).getAttributeNS(ns,"anchor"));
        assertEquals(DocxTemplateEditorTest.properties(DocxTemplateEditorTest.mainXml(source).getDocumentElement(),"rPr"),DocxTemplateEditorTest.properties(xml.getDocumentElement(),"rPr"));
    }
    @Test void ordinaryEditingStillRejectsNativeFieldsAndHyperlinkLabels() throws Exception {
        DocxTemplateEditor.Paragraph p=editor.inspect(source()).mainParagraph(1);
        assertThrows(DocxTemplateEditor.EditException.class,()->DocxTemplateEditor.Edit.replaceCatalogSpan("generic",p,8,8+LABEL.length(),LABEL,"Not used"));
    }
    @Test void fieldCacheCrossingControlsAndSimpleFieldTextRemainProtected() throws Exception {
        DocxTemplateEditor.Paragraph p=editor.inspect(source()).mainParagraph(1);int cached=p.getCatalogText().length()-2;
        assertEquals("FIELD_TEXT_EDIT_UNSUPPORTED",assertThrows(DocxTemplateEditor.EditException.class,()->DocxTemplateEditor.Edit.replaceCatalogLabelOutsideFields("page-cache",p,cached,cached+2,"12","99")).getCode());
        assertThrows(DocxTemplateEditor.EditException.class,()->DocxTemplateEditor.Edit.replaceCatalogLabelOutsideFields("cross-field",p,8,p.getCatalogText().length(),LABEL+"12","Changed"));
        byte[] simple=DocxTemplateEditorSafetyTest.document("<w:p><w:r><w:t>Label </w:t></w:r><w:fldSimple w:instr=\"PAGE\"><w:r><w:t>12</w:t></w:r></w:fldSimple></w:p>");
        DocxTemplateEditor.Paragraph s=editor.inspect(simple).mainParagraph(1);
        assertThrows(DocxTemplateEditor.EditException.class,()->DocxTemplateEditor.Edit.replaceCatalogLabelOutsideFields("simple-cache",s,6,8,"12","99"));
    }
    @Test void incompleteFieldAndCrossHyperlinkLabelsAreRejected() throws Exception {
        byte[] broken=DocxTemplateEditorSafetyTest.document("<w:p><w:r><w:t>Label</w:t></w:r><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r></w:p>");
        DocxTemplateEditor.Paragraph p=editor.inspect(broken).mainParagraph(1);
        assertEquals("INCOMPLETE_FIELD_SCOPE",assertThrows(DocxTemplateEditor.EditException.class,()->DocxTemplateEditor.Edit.replaceCatalogLabelOutsideFields("broken",p,0,5,"Label","Title")).getCode());
        byte[] split=DocxTemplateEditorSafetyTest.document("<w:p><w:hyperlink w:anchor=\"a\"><w:r><w:t>Left</w:t></w:r></w:hyperlink><w:hyperlink w:anchor=\"b\"><w:r><w:t>Right</w:t></w:r></w:hyperlink></w:p>");
        DocxTemplateEditor.Paragraph s=editor.inspect(split).mainParagraph(1);
        assertThrows(DocxTemplateEditor.EditException.class,()->DocxTemplateEditor.Edit.replaceCatalogLabelOutsideFields("split",s,0,9,"LeftRight","Title"));
    }
}
