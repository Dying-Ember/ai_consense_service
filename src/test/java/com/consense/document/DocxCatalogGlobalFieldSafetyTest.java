package com.consense.document;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Full-story field context at apply; the ordinary span seam keeps its existing contract. */
class DocxCatalogGlobalFieldSafetyTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private static final String BEGIN="<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>";
    private static final String SEP="<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>";
    private static final String END="<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    private static final String LABEL="<w:hyperlink w:anchor=\"entry\"><w:r><w:t>La</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>bel</w:t></w:r></w:hyperlink>";
    private static String instruction(String value){return "<w:r><w:instrText xml:space=\"preserve\"> "+value+" </w:instrText></w:r>";}
    private static String cross(String instruction){return "<w:p>"+BEGIN+instruction(instruction)+SEP+"</w:p><w:p>"+LABEL+"</w:p><w:p>"+END+"</w:p>";}
    private DocxTemplateEditor.DocxEditResult apply(byte[] source,boolean toc) {
        DocxTemplateEditor.TemplateIndex index=editor.inspect(source);DocxTemplateEditor.Paragraph p=index.mainParagraph(2);
        DocxTemplateEditor.Edit edit=toc?DocxTemplateEditor.Edit.replaceCatalogTocLabel("label",p,0,5,"Label","Title"):DocxTemplateEditor.Edit.replaceCatalogLabelOutsideFields("label",p,0,5,"Label","Title");
        return editor.apply(source,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(edit)));
    }
    @Test void ordinaryLabelRejectsCachesWhenComplexFieldsCrossParagraphs() throws Exception {
        for(String field:Arrays.asList("REF entry","TOC \\h"))assertEquals("FIELD_TEXT_EDIT_UNSUPPORTED",assertThrows(DocxTemplateEditor.EditException.class,()->apply(DocxTemplateEditorSafetyTest.document(cross(field)),false)).getCode());
    }
    @Test void explicitTocLabelAllowsOnlyItsCompleteOuterTocAndKeepsNativeProperties() throws Exception {
        byte[] source=DocxTemplateEditorSafetyTest.document(cross("TOC \\h"));DocxTemplateEditor.DocxEditResult actual=apply(source,true);
        assertEquals("Title",actual.getIndex().mainParagraph(2).getText());
        org.w3c.dom.Document before=DocxTemplateEditorTest.mainXml(source),after=DocxTemplateEditorTest.mainXml(actual.getDocxBytes());
        for(String local:Arrays.asList("fldChar","instrText","rPr"))assertEquals(DocxTemplateEditorTest.properties(before.getDocumentElement(),local),DocxTemplateEditorTest.properties(after.getDocumentElement(),local),local);
        String ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        assertEquals(1,after.getElementsByTagNameNS(ns,"hyperlink").getLength());
        assertEquals("entry",((org.w3c.dom.Element)after.getElementsByTagNameNS(ns,"hyperlink").item(0)).getAttributeNS(ns,"anchor"));
    }
    @Test void explicitTocRejectsOtherFieldInstructionsAndIncompleteNativeScope() throws Exception {
        for(String body:Arrays.asList(cross("REF entry"),cross("TOCX \\h"),cross("TOC \\h").replace(END,""),cross("TOC \\h").replace(SEP,""),cross("TOC \\h").replace(SEP,SEP+SEP)))
            assertThrows(DocxTemplateEditor.EditException.class,()->apply(DocxTemplateEditorSafetyTest.document(body),true));
    }
    @Test void nestedReferenceCachesStayProtectedInsideOuterToc() throws Exception {
        String body="<w:p>"+BEGIN+instruction("TOC \\h")+SEP+BEGIN+instruction("REF entry")+SEP+"</w:p><w:p>"+LABEL+"</w:p><w:p>"+END+END+"</w:p>";
        assertEquals("FIELD_TEXT_EDIT_UNSUPPORTED",assertThrows(DocxTemplateEditor.EditException.class,()->apply(DocxTemplateEditorSafetyTest.document(body),true)).getCode());
    }
    @Test void tocModeRequiresAnActualNativeHyperlinkAndAnOuterToc() throws Exception {
        String plain=cross("TOC \\h").replace(LABEL,"<w:r><w:t>Label</w:t></w:r>");
        assertEquals("TOC_LABEL_HYPERLINK_REQUIRED",assertThrows(DocxTemplateEditor.EditException.class,()->apply(DocxTemplateEditorSafetyTest.document(plain),true)).getCode());
        String outside="<w:p/><w:p>"+LABEL+"</w:p><w:p/>";
        assertEquals("FIELD_TEXT_EDIT_UNSUPPORTED",assertThrows(DocxTemplateEditor.EditException.class,()->apply(DocxTemplateEditorSafetyTest.document(outside),true)).getCode());
        assertEquals("Title",apply(DocxTemplateEditorSafetyTest.document(outside),false).getIndex().mainParagraph(2).getText());
    }
    @Test void localPagerefResultIsRejectedBeforeApplyEvenInTocMode() throws Exception {
        byte[] source=DocxTemplateEditorSafetyTest.document("<w:p>"+BEGIN+instruction("PAGEREF entry")+SEP+LABEL+END+"</w:p>");
        DocxTemplateEditor.Paragraph p=editor.inspect(source).mainParagraph(1);
        assertEquals("FIELD_TEXT_EDIT_UNSUPPORTED",assertThrows(DocxTemplateEditor.EditException.class,()->DocxTemplateEditor.Edit.replaceCatalogTocLabel("cache",p,0,5,"Label","Title")).getCode());
    }
}
