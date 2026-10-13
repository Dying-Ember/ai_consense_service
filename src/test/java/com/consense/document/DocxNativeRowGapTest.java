package com.consense.document;

import java.io.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.consense.document.DocxTemplateEditor.*;

class DocxNativeRowGapTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private byte[] source(boolean exact,boolean explicit)throws Exception{
        try(XWPFDocument d=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            XWPFTable table=d.createTable(2,2);table.getCTTbl().addNewTblGrid().addNewGridCol().setW(java.math.BigInteger.valueOf(5000));table.getCTTbl().getTblGrid().addNewGridCol().setW(java.math.BigInteger.valueOf(1200));
            XWPFTableRow row=table.getRow(0);row.setHeight(1200);if(exact)row.setHeightRule(TableRowHeightRule.EXACT);
            for(int i=0;i<2;i++){row.getCell(i).setText(i==0?"Confirmed submission text.":"SCT reference");XWPFParagraph p=row.getCell(i).getParagraphs().get(0);if(explicit){p.setSpacingBefore(120);p.setSpacingAfter(120);}p.setSpacingBetween(1.25);}
            d.write(out);return out.toByteArray();
        }
    }
    private NativeLayoutPlan plan(TemplateIndex i){return new NativeLayoutPlan(i.getSourceSha256(),"test-row-gap",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Arrays.asList(i.anchor(i.mainParagraph(1).getRowId())));}
    @Test void removesOnlyMinimumHeightAndExplicitGapsWhilePreservingTextLineSpacingAndGrid()throws Exception{
        byte[] bytes=source(false,true);TemplateIndex index=editor.inspect(bytes);DocxEditResult result=editor.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,plan(index));
        assertEquals(index.mainParagraph(1).getText(),result.getIndex().mainParagraph(1).getText());assertEquals(index.mainParagraph(2).getText(),result.getIndex().mainParagraph(2).getText());assertTrue(result.getIndex().mainParagraph(1).hasCompactNativeRowGaps());assertEquals("compact_verified_row_gaps",result.getNativeLayoutLedger().get(0).getAction());
        try(XWPFDocument before=new XWPFDocument(new ByteArrayInputStream(bytes));XWPFDocument after=new XWPFDocument(new ByteArrayInputStream(result.getDocxBytes()))){
            assertEquals(before.getTables().get(0).getCTTbl().getTblGrid().xmlText(),after.getTables().get(0).getCTTbl().getTblGrid().xmlText());
            assertEquals(before.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0).getSpacingBetween(),after.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0).getSpacingBetween());
            assertEquals(0,after.getTables().get(0).getRow(0).getCtRow().getTrPr().sizeOfTrHeightArray());
        }
        DocxTemplateEditorTest.unchangedOpaqueParts(bytes,result.getDocxBytes());
    }
    @Test void exactHeightAndUnverifiedParagraphGapsAreRejected()throws Exception{
        for(boolean exact:Arrays.asList(true,false)){byte[] bytes=source(exact,exact);TemplateIndex index=editor.inspect(bytes);EditException denied=assertThrows(EditException.class,()->editor.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,plan(index)));assertEquals("LAYOUT_ROW_GAP_SCOPE_CHANGED",denied.getCode());}
    }
    @Test void sourceBoundNativeGapRepairCanFollowAnAuthorizedTextReplacement()throws Exception{
        byte[] bytes=source(false,true);TemplateIndex index=editor.inspect(bytes);Paragraph p=index.mainParagraph(1);DocxEditResult result=editor.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Arrays.asList(Edit.replaceSpan("approved-value",p,0,p.getText().length(),p.getText(),"Approved complete replacement."))),Collections.emptyMap(),false,plan(index));
        assertEquals("Approved complete replacement.",result.getIndex().mainParagraph(1).getText());assertTrue(result.getIndex().mainParagraph(1).hasCompactNativeRowGaps());assertEquals(1,result.getLedger().size());
    }
}
