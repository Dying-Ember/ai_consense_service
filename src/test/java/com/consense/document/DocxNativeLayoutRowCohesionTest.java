package com.consense.document;

import java.io.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.consense.document.DocxTemplateEditor.*;

class DocxNativeLayoutRowCohesionTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private byte[] source()throws Exception{try(XWPFDocument d=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){d.createParagraph().createRun().setText("Previous clause");d.createTable(2,1).getRow(0).getCell(0).setText("Complete packaging instructions must remain together.");d.createParagraph().createRun().setText("Following clause");d.write(out);return out.toByteArray();}}
    private NativeLayoutPlan plan(TemplateIndex index){return new NativeLayoutPlan(index.getSourceSha256(),"test-row-layout",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Arrays.asList(index.anchor(index.mainParagraph(2).getRowId())));}
    @Test void preservesTheActualRowTextAndRecordsItsSourceAndNativeControl()throws Exception{
        byte[] source=source();TemplateIndex index=editor.inspect(source);DocxEditResult result=editor.applyWithParagraphBookmarks(source,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,plan(index));
        assertEquals(index.mainParagraph(2).getText(),result.getIndex().mainParagraph(2).getText());assertTrue(result.getIndex().mainParagraph(2).hasNativeRowCohesion());
        assertEquals("keep_verified_row_together",result.getNativeLayoutLedger().get(0).getAction());assertEquals(index.mainParagraph(2).getRowId(),result.getNativeLayoutLedger().get(0).getSource().getId());
        assertEquals(index.getSourceSha256(),editor.inspect(source).getSourceSha256());DocxTemplateEditorTest.unchangedOpaqueParts(source,result.getDocxBytes());
    }
    @Test void anAuthorizedBusinessEditInsideTheSameRowCanStillReceiveNativeCohesion()throws Exception{
        byte[] source=source();TemplateIndex index=editor.inspect(source);Paragraph p=index.mainParagraph(2);
        DocxEditResult result=editor.applyWithParagraphBookmarks(source,new SourceEditBatch(index.getSourceSha256(),Arrays.asList(Edit.replaceSpan("adopted-value",p,0,p.getText().length(),p.getText(),"Adopted exact instructions."))),Collections.emptyMap(),false,plan(index));
        assertEquals("Adopted exact instructions.",result.getIndex().mainParagraph(2).getText());assertTrue(result.getIndex().mainParagraph(2).hasNativeRowCohesion());assertEquals(1,result.getLedger().size());
    }
    @Test void refusesAnUnverifiedSourceAndSkipsAnActuallyRemovedRow()throws Exception{
        byte[] source=source();TemplateIndex index=editor.inspect(source);NodeAnchor row=index.anchor(index.mainParagraph(2).getRowId());
        NativeLayoutPlan wrong=new NativeLayoutPlan("other-edition","test-row-layout",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Arrays.asList(row));
        EditException denied=assertThrows(EditException.class,()->editor.applyWithParagraphBookmarks(source,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,wrong));assertEquals("LAYOUT_SOURCE_HASH_MISMATCH",denied.getCode());
        DocxEditResult result=editor.applyWithParagraphBookmarks(source,new SourceEditBatch(index.getSourceSha256(),Arrays.asList(Edit.deleteRow("remove-optional-row",row))),Collections.emptyMap(),false,plan(index));
        assertEquals("SKIPPED_REMOVED",result.getNativeLayoutLedger().get(0).getStatus());assertNull(result.getNativeLayoutLedger().get(0).getLayoutId());
    }
    @Test void refusesAChangedRowAnchorAndAnExplicitPageBreak()throws Exception{
        byte[] bytes=source();TemplateIndex index=editor.inspect(bytes);Paragraph p=index.mainParagraph(2);
        TemplateIndex changed=editor.apply(bytes,new SourceEditBatch(index.getSourceSha256(),Arrays.asList(Edit.replaceSpan("new-value",p,0,p.getText().length(),p.getText(),"Different source row.")))).getIndex();
        NativeLayoutPlan wrong=new NativeLayoutPlan(index.getSourceSha256(),"test-row-layout",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Arrays.asList(changed.anchor(changed.mainParagraph(2).getRowId())));
        EditException mismatch=assertThrows(EditException.class,()->editor.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,wrong));assertEquals("LAYOUT_ANCHOR_MISMATCH",mismatch.getCode());
        byte[] withBreak;try(XWPFDocument d=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){d.createParagraph();d.createTable(2,1).getRow(0).getCell(0).getParagraphs().get(0).createRun().addBreak(BreakType.PAGE);d.write(out);withBreak=out.toByteArray();}
        TemplateIndex breakIndex=editor.inspect(withBreak);EditException denied=assertThrows(EditException.class,()->editor.applyWithParagraphBookmarks(withBreak,new SourceEditBatch(breakIndex.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,plan(breakIndex)));assertEquals("ROW_COHESION_CONTROL_UNSUPPORTED",denied.getCode());
    }
}
