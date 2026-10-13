package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftNativeLayoutStateTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private DocxEditResult saved()throws Exception{
        byte[] bytes;try(XWPFDocument d=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){d.createTable(2,1).getRow(0).getCell(0).setText("Confirmed packaging instructions.");d.write(out);bytes=out.toByteArray();}
        TemplateIndex index=editor.inspect(bytes);NativeLayoutPlan plan=new NativeLayoutPlan(index.getSourceSha256(),"test-source-row",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Arrays.asList(index.anchor(index.mainParagraph(1).getRowId())));
        return editor.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,plan);
    }
    private Map<String,Object> state(DocxEditResult saved,DocxEditResult updated){
        Map<String,Object> original=DraftNativeLayout.info("SCT","libreoffice",saved);
        return DraftBusinessRules.asMap(DraftNativeLayout.bodyLedger(JsonUtils.write(Arrays.asList(original)),updated).stream().filter(v->"native_layout".equals(DraftBusinessRules.asMap(v).get("kind"))).findFirst().get());
    }
    @Test void laterAuthorizedBodyTextEditKeepsTheNativeRowControlAndResolvesItsMarker()throws Exception{
        DocxEditResult saved=saved();Paragraph p=saved.getIndex().mainParagraph(1);DocxEditResult changed=editor.apply(saved.getDocxBytes(),new SourceEditBatch(saved.getDocxSha256(),Arrays.asList(Edit.replaceSpan("review-value",p,0,p.getText().length(),p.getText(),"User approved packaging instructions."))));
        Map<String,Object> state=state(saved,changed);assertEquals("applied",state.get("status"));Map<String,Object> action=DraftBusinessRules.asMap(DraftBusinessRules.list(state.get("actions")).get(0));assertEquals("APPLIED",action.get("status"));assertTrue(changed.getIndex().paragraph(String.valueOf(action.get("generatedId"))).hasNativeRowCohesion());assertEquals(saved.getDocxSha256(),state.get("parentDocxSha256"));
    }
    @Test void removingTheControlledRowCannotLeaveAnAppliedLayoutClaim()throws Exception{
        DocxEditResult saved=saved();TemplateIndex index=saved.getIndex();DocxEditResult changed=editor.apply(saved.getDocxBytes(),new SourceEditBatch(saved.getDocxSha256(),Arrays.asList(Edit.deleteRow("user-remove",index.anchor(index.mainParagraph(1).getRowId())))));
        Map<String,Object> state=state(saved,changed);assertEquals("unavailable",state.get("status"));Map<String,Object> action=DraftBusinessRules.asMap(DraftBusinessRules.list(state.get("actions")).get(0));assertEquals("MISSING_LAYOUT_CONTROL",action.get("status"));assertNull(action.get("generatedId"));
    }
}
