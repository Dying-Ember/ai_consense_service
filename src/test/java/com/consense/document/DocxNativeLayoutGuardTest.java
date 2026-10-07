package com.consense.document;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual source through the public editor batch/layout interface, with no renderer needed. */
class DocxNativeLayoutGuardTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private DocxTemplateEditor.NativeLayoutPlan plan(DocxTemplateEditor.TemplateIndex index) {
        DocxTemplateEditor.Paragraph heading=index.mainParagraph(114);
        return new DocxTemplateEditor.NativeLayoutPlan(index.getSourceSha256(),"desktop-native-continuations-v1",Collections.singletonList(
            new DocxTemplateEditor.NativeLayoutBoundary(index.anchor(heading.getTableId()),index.anchor(heading.getId()),index.anchor(index.mainParagraph(111).getId()))),Collections.emptyList());
    }
    @Test void refusesAClearedOrReplacedRegisteredHeadingEvenWhenItsOriginalTableSurvives()throws Exception {
        byte[] original=DocxTemplateEditorTest.source(0);DocxTemplateEditor.TemplateIndex index=editor.inspect(original);DocxTemplateEditor.Paragraph heading=index.mainParagraph(114);
        for(DocxTemplateEditor.Edit edit:Arrays.asList(DocxTemplateEditor.Edit.clearParagraph("clear-heading",heading),
                DocxTemplateEditor.Edit.replaceSpan("replace-heading",heading,0,heading.getText().length(),heading.getText(),"TEST ONLY different heading"))) {
            DocxTemplateEditor.EditException failure=assertThrows(DocxTemplateEditor.EditException.class,()->editor.applyWithParagraphBookmarks(original,
                new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(edit)),Collections.emptyMap(),false,plan(index)));
            assertEquals("LAYOUT_BOUNDARY_SCOPE_CHANGED",failure.getCode());assertEquals(index.getSourceSha256(),editor.inspect(original).getSourceSha256());
        }
    }
    @Test void keepsTheRegisteredHeadingBoundaryWhenAnotherParagraphInItsTableChanges()throws Exception {
        byte[] original=DocxTemplateEditorTest.source(0);DocxTemplateEditor.TemplateIndex index=editor.inspect(original);DocxTemplateEditor.Paragraph heading=index.mainParagraph(114),body=index.mainParagraph(122);
        assertEquals(heading.getTableId(),body.getTableId());assertFalse(body.getText().isEmpty());String replacement="TEST ONLY changed neighboring body";
        DocxTemplateEditor.DocxEditResult result=editor.applyWithParagraphBookmarks(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(
            DocxTemplateEditor.Edit.replaceSpan("change-body",body,0,body.getText().length(),body.getText(),replacement))),Collections.emptyMap(),false,plan(index));
        assertEquals(1,result.getNativeLayoutLedger().size());assertEquals("APPLIED",result.getNativeLayoutLedger().get(0).getStatus());
        assertEquals(heading.getText(),result.getIndex().mainParagraph(115).getText());assertEquals(replacement,result.getIndex().mainParagraph(123).getText());
        DocxTemplateEditorTest.unchangedOpaqueParts(original,result.getDocxBytes());assertEquals(index.getSourceSha256(),editor.inspect(original).getSourceSha256());
    }
    private DocxTemplateEditor.NativeLayoutPlan spacingPlan(DocxTemplateEditor.TemplateIndex index,int ordinal){return new DocxTemplateEditor.NativeLayoutPlan(index.getSourceSha256(),"desktop-native-continuations-v2",Collections.emptyList(),Collections.emptyList(),Collections.singletonList(new DocxTemplateEditor.NativeLayoutSpacing(index.anchor(index.mainParagraph(ordinal).getId()),index.anchor(index.mainParagraph(211).getId()))));}
    @Test void refusesNonemptySpacingTargetsAndChangedManualFooters()throws Exception {
        byte[] source=DocxTemplateEditorTest.source(1);DocxTemplateEditor.TemplateIndex index=editor.inspect(source);DocxTemplateEditor.Paragraph footer=index.mainParagraph(211);
        DocxTemplateEditor.EditException nonempty=assertThrows(DocxTemplateEditor.EditException.class,()->editor.applyWithParagraphBookmarks(source,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,spacingPlan(index,197)));assertEquals("LAYOUT_EMPTY_SCOPE_CHANGED",nonempty.getCode());
        DocxTemplateEditor.EditException changed=assertThrows(DocxTemplateEditor.EditException.class,()->editor.applyWithParagraphBookmarks(source,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(DocxTemplateEditor.Edit.replaceCatalogSpan("change-footer",footer,0,10,"March 2020","TEST ONLY changed date"))),Collections.emptyMap(),false,spacingPlan(index,198)));assertEquals("LAYOUT_EMPTY_SCOPE_CHANGED",changed.getCode());assertEquals(index.getSourceSha256(),editor.inspect(source).getSourceSha256());
    }
    @Test void skipsRemovedOriginalEmptyAndDoesNotCompactNewEmptyParagraphs()throws Exception {
        byte[] source=DocxTemplateEditorTest.source(1);DocxTemplateEditor.TemplateIndex index=editor.inspect(source);DocxTemplateEditor.Paragraph empty=index.mainParagraph(198);DocxTemplateEditor.NativeLayoutPlan plan=spacingPlan(index,198);
        DocxTemplateEditor.DocxEditResult removed=editor.applyWithParagraphBookmarks(source,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(DocxTemplateEditor.Edit.deleteParagraph("remove-empty",empty))),Collections.emptyMap(),false,plan);assertEquals("SKIPPED_REMOVED",removed.getNativeLayoutLedger().get(0).getStatus());assertNull(removed.getNativeLayoutLedger().get(0).getLayoutId());
        DocxTemplateEditor.DocxEditResult inserted=editor.applyWithParagraphBookmarks(source,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.singletonList(DocxTemplateEditor.Edit.insertParagraphs("insert-new-empty",empty,true,Arrays.asList("","TEST ONLY new paragraph"),empty))),Collections.emptyMap(),false,plan);assertEquals(1,inserted.getNativeLayoutLedger().size());assertTrue(inserted.getIndex().paragraph(inserted.getNativeLayoutLedger().get(0).getGeneratedId()).hasCompactEmptyLayoutSpacing());assertEquals("",inserted.getIndex().mainParagraph(199).getText());assertFalse(inserted.getIndex().mainParagraph(199).hasCompactEmptyLayoutSpacing());DocxTemplateEditorTest.unchangedOpaqueParts(source,inserted.getDocxBytes());
    }
}
