package com.consense.document;

import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Portable guards for conditional source-bound padding and native heading cohesion. */
class DocxClearedNativeLayoutGuardTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    static byte[] fixture(String extraBody)throws Exception {
        String xml="<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            +"<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>SCC8.303 Original title</w:t></w:r></w:p>"
            +"<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"120\"/></w:pPr><w:bookmarkStart w:id=\"20\" w:name=\"sourceBody\"/><w:r><w:rPr><w:i/></w:rPr><w:t>Body to be cleared</w:t><w:br w:type=\"page\"/>"+extraBody+"</w:r><w:bookmarkEnd w:id=\"20\"/></w:p>"
            +"<w:p><w:r><w:t>Outside paragraph</w:t><w:br w:type=\"page\"/></w:r></w:p>"
            +"<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>SCC5.308</w:t></w:r></w:p>"
            +"<w:p><w:r><w:t>Preparer guidance</w:t></w:r></w:p>"
            +"<w:p><w:r><w:t>Retained body</w:t></w:r></w:p>"
            +"</w:body></w:document>";
        try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out)){
            zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(xml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/media/opaque.bin"));zip.write(new byte[]{1,2,3,4});zip.closeEntry();zip.finish();return out.toByteArray();
        }
    }
    static Map<String,byte[]> parts(byte[] bytes)throws Exception{
        Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry item;while((item=zip.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=zip.read(buf))!=-1)out.write(buf,0,n);result.put(item.getName(),out.toByteArray());}}return result;
    }
    byte[] alteredFixture(String from,String to)throws Exception{Map<String,byte[]> entries=parts(fixture(""));entries.put("word/document.xml",new String(entries.get("word/document.xml"),StandardCharsets.UTF_8).replace(from,to).getBytes(StandardCharsets.UTF_8));try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}zip.finish();return out.toByteArray();}}
    NativeLayoutPlan plan(TemplateIndex index,boolean removeBreak,List<NodeAnchor> keep){return new NativeLayoutPlan(index.getSourceSha256(),"test-scc-exact-source",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.singletonList(new NativeLayoutClearedParagraph(index.anchor(index.mainParagraph(2).getId()),index.anchor(index.mainParagraph(1).getId()),"SCC8.303Not used",removeBreak)),keep);}
    Edit negative(TemplateIndex index){Paragraph p=index.mainParagraph(1);return Edit.replaceSpan("adopt-negative",p,0,p.getText().length(),p.getText(),"SCC8.303 Not used");}
    DocxEditResult apply(byte[] bytes,List<Edit> edits,NativeLayoutPlan plan){return editor.applyWithParagraphBookmarks(bytes,new SourceEditBatch(editor.inspect(bytes).getSourceSha256(),edits),Collections.emptyMap(),false,plan);}
    @Test void onlyVerifiedClearedBodyCanLoseItsWhitelistedBreakAndSpacing()throws Exception{
        byte[] bytes=fixture("");TemplateIndex index=editor.inspect(bytes);DocxEditResult result=apply(bytes,Arrays.asList(negative(index),Edit.clearParagraph("clear-body",index.mainParagraph(2))),plan(index,true,Collections.emptyList()));
        assertEquals("APPLIED",result.getNativeLayoutLedger().get(0).getStatus());assertTrue(result.getIndex().mainParagraph(2).hasCompactClearedLayoutSpacing());
        assertEquals(index.mainParagraph(3).getSha256(),result.getIndex().mainParagraph(3).getSha256());assertEquals(index.mainParagraph(4).getSha256(),result.getIndex().mainParagraph(4).getSha256());
        String xml=new String(parts(result.getDocxBytes()).get("word/document.xml"),StandardCharsets.UTF_8);assertEquals(1,xml.split("type=\"page\"",-1).length-1);assertTrue(xml.contains("sourceBody"));assertTrue(xml.contains("<w:i"));assertArrayEquals(parts(bytes).get("word/media/opaque.bin"),parts(result.getDocxBytes()).get("word/media/opaque.bin"));
    }
    @Test void retainedGuardOrNonemptyBodySkipsLayoutWithoutLosingBreakOrParagraphProperties()throws Exception{
        byte[] bytes=fixture("");TemplateIndex index=editor.inspect(bytes);
        for(List<Edit> edits:Arrays.asList(Collections.<Edit>emptyList(),Collections.singletonList(negative(index)),Collections.singletonList(Edit.clearParagraph("clear-body",index.mainParagraph(2))))){
            DocxEditResult business=editor.apply(bytes,new SourceEditBatch(index.getSourceSha256(),edits)),layout=apply(bytes,edits,plan(index,true,Collections.emptyList()));assertEquals("SKIPPED_NOT_CLEARED",layout.getNativeLayoutLedger().get(0).getStatus());assertNull(layout.getNativeLayoutLedger().get(0).getLayoutId());assertEquals(business.getIndex().mainParagraph(2).getSha256(),layout.getIndex().mainParagraph(2).getSha256());assertFalse(layout.getIndex().mainParagraph(2).hasCompactClearedLayoutSpacing());
        }
    }
    @Test void removedTargetSkipsAndNonWhitelistedNativeBreakIsRejected()throws Exception{
        byte[] bytes=fixture("");TemplateIndex index=editor.inspect(bytes);DocxEditResult removed=apply(bytes,Arrays.asList(Edit.deleteParagraph("remove-body",index.mainParagraph(2))),plan(index,true,Collections.emptyList()));assertEquals("SKIPPED_REMOVED",removed.getNativeLayoutLedger().get(0).getStatus());
        EditException bad=assertThrows(EditException.class,()->apply(bytes,Collections.emptyList(),plan(index,false,Collections.emptyList())));assertEquals("LAYOUT_CLEARED_INLINE_UNSUPPORTED",bad.getCode());
    }
    @Test void rejectsWrongSourceHashAndParagraphAnchor()throws Exception{
        byte[] bytes=fixture("");TemplateIndex index=editor.inspect(bytes);NativeLayoutPlan wrong=new NativeLayoutPlan("wrong-source","test",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList());assertEquals("LAYOUT_SOURCE_HASH_MISMATCH",assertThrows(EditException.class,()->apply(bytes,Collections.emptyList(),wrong)).getCode());
        TemplateIndex altered=editor.inspect(fixture("<w:t>Other</w:t>"));NativeLayoutPlan stale=new NativeLayoutPlan(index.getSourceSha256(),"test",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.singletonList(new NativeLayoutClearedParagraph(altered.anchor(altered.mainParagraph(2).getId()),index.anchor(index.mainParagraph(1).getId()),"SCC8.303Not used",true)),Collections.emptyList());assertEquals("LAYOUT_ANCHOR_MISMATCH",assertThrows(EditException.class,()->apply(bytes,Collections.emptyList(),stale)).getCode());
    }
    @Test void rejectsFieldDrawingAndUnknownInlineInsteadOfCompactingProtectedControls()throws Exception{
        for(String control:Arrays.asList("<w:fldChar w:fldCharType=\"begin\"/>","<w:drawing/>","<w:footnoteReference w:id=\"1\"/>","<w:br w:type=\"textWrapping\"/>","<w:attr w:name=\"outside-semantic-wrapper\"/>")){
            byte[] bytes=fixture(control);TemplateIndex index=editor.inspect(bytes);assertEquals("LAYOUT_CLEARED_INLINE_UNSUPPORTED",assertThrows(EditException.class,()->apply(bytes,Collections.emptyList(),plan(index,true,Collections.emptyList()))).getCode());
        }
    }
    @Test void preservesLegacySemanticTextWrappersAndRenderedBreakMetadataWithoutRemovingNativeControls()throws Exception{
        String wrappers="<w:lastRenderedPageBreak/></w:r><w:smartTag w:uri=\"urn:test\" w:element=\"date\"><w:smartTagPr><w:attr w:name=\"year\" w:val=\"2026\"/></w:smartTagPr><w:r><w:t>Legacy date</w:t></w:r></w:smartTag><w:r>";
        byte[] bytes=fixture(wrappers);TemplateIndex index=editor.inspect(bytes);DocxEditResult result=apply(bytes,Arrays.asList(negative(index),Edit.clearParagraph("clear-body",index.mainParagraph(2))),plan(index,true,Collections.emptyList()));assertTrue(result.getIndex().mainParagraph(2).hasCompactClearedLayoutSpacing());String xml=new String(parts(result.getDocxBytes()).get("word/document.xml"),StandardCharsets.UTF_8);assertTrue(xml.contains("lastRenderedPageBreak"));assertTrue(xml.contains("w:uri=\"urn:test\""));assertTrue(xml.contains("w:val=\"2026\""));assertEquals("",result.getIndex().mainParagraph(2).getText());
    }
    @Test void keepsExactHeadingAndClearedGuidanceWithNextAndRejectsChangedNonemptyHeading()throws Exception{
        byte[] bytes=fixture("");TemplateIndex index=editor.inspect(bytes);List<NodeAnchor> keep=Arrays.asList(index.anchor(index.mainParagraph(4).getId()),index.anchor(index.mainParagraph(5).getId()));NativeLayoutPlan p=plan(index,true,keep);
        DocxEditResult result=apply(bytes,Collections.singletonList(Edit.clearParagraph("guidance",index.mainParagraph(5))),p);assertTrue(result.getIndex().mainParagraph(4).hasNativeKeepNext());assertTrue(result.getIndex().mainParagraph(5).hasNativeKeepNext());assertEquals(index.mainParagraph(4).getText(),result.getIndex().mainParagraph(4).getText());assertEquals(index.mainParagraph(6).getSha256(),result.getIndex().mainParagraph(6).getSha256());
        Paragraph heading=index.mainParagraph(4);assertEquals("LAYOUT_KEEP_NEXT_SCOPE_CHANGED",assertThrows(EditException.class,()->apply(bytes,Collections.singletonList(Edit.replaceSpan("changed-heading",heading,0,heading.getText().length(),heading.getText(),"Different heading")),p)).getCode());
        assertArrayEquals(parts(bytes).get("word/media/opaque.bin"),parts(result.getDocxBytes()).get("word/media/opaque.bin"));
    }
    @Test void onlyExactClearedGuidanceMayLoseItsNativeBreakWithinTheRegisteredHeadingChain()throws Exception{
        byte[] source=alteredFixture("<w:t>Preparer guidance</w:t>","<w:t>Preparer guidance</w:t><w:br w:type=\"page\"/>");TemplateIndex index=editor.inspect(source);Paragraph heading=index.mainParagraph(4),guide=index.mainParagraph(5);NativeLayoutPlan layout=new NativeLayoutPlan(index.getSourceSha256(),"exact-guide-break",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Arrays.asList(index.anchor(heading.getId()),index.anchor(guide.getId())),Collections.singletonList(new NativeLayoutClearedKeepNextBreak(index.anchor(guide.getId()),index.anchor(heading.getId()),heading.getCatalogText())));
        DocxEditResult retained=apply(source,Collections.emptyList(),layout);assertEquals("SKIPPED_NOT_CLEARED",retained.getNativeLayoutLedger().get(1).getStatus());assertEquals(guide.getSha256(),retained.getIndex().mainParagraph(5).getSha256());
        DocxEditResult cleared=apply(source,Collections.singletonList(Edit.clearParagraph("business-guide-clear",guide)),layout);assertEquals("keep_verified_cleared_guidance_with_next",cleared.getNativeLayoutLedger().get(1).getAction());assertTrue(cleared.getIndex().mainParagraph(5).hasClearedNativeKeepNext());assertEquals(index.mainParagraph(3).getSha256(),cleared.getIndex().mainParagraph(3).getSha256());assertEquals(index.mainParagraph(6).getSha256(),cleared.getIndex().mainParagraph(6).getSha256());
        Edit changed=Edit.replaceSpan("changed-heading",heading,0,heading.getText().length(),heading.getText(),"Changed retained heading");assertEquals("LAYOUT_KEEP_NEXT_SCOPE_CHANGED",assertThrows(EditException.class,()->apply(source,Arrays.asList(changed,Edit.clearParagraph("business-guide-clear",guide)),layout)).getCode());
    }
    @Test void rejectsNativeSectionAndPageBreakBeforeProperties()throws Exception{
        for(String control:Arrays.asList("<w:sectPr/>","<w:pageBreakBefore/>")){byte[] source=alteredFixture("<w:spacing w:before=\"120\" w:after=\"120\"/>","<w:spacing w:before=\"120\" w:after=\"120\"/>"+control);TemplateIndex index=editor.inspect(source);assertEquals("LAYOUT_CLEARED_SCOPE_CHANGED",assertThrows(EditException.class,()->apply(source,Collections.emptyList(),plan(index,true,Collections.emptyList()))).getCode());}
    }
    @Test void headingPageBoundaryRequiresEveryGapClearedAndBothSourceGuardsUnchanged()throws Exception{
        byte[] source=alteredFixture("<w:p><w:r><w:t>Retained body</w:t>","<w:p><w:r><w:t>Second guidance</w:t></w:r></w:p><w:p><w:r><w:t>Retained body</w:t>");TemplateIndex index=editor.inspect(source);Paragraph heading=index.mainParagraph(4),gap=index.mainParagraph(5),second=index.mainParagraph(6),body=index.mainParagraph(7);NativeLayoutPlan layout=new NativeLayoutPlan(index.getSourceSha256(),"exact-heading-break",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.singletonList(new NativeLayoutParagraphBoundary(index.anchor(heading.getId()),index.anchor(body.getId()),Arrays.asList(index.anchor(gap.getId()),index.anchor(second.getId())))));
        Edit clear=Edit.clearParagraph("clear-first-gap",gap),clearSecond=Edit.clearParagraph("clear-second-gap",second);
        for(List<Edit> edits:Arrays.asList(Collections.<Edit>emptyList(),Collections.singletonList(clear),Arrays.asList(clear,clearSecond,Edit.replaceSpan("change-heading",heading,0,heading.getText().length(),heading.getText(),"Changed heading")),Arrays.asList(clear,clearSecond,Edit.replaceSpan("change-first-body",body,0,body.getText().length(),body.getText(),"Changed body")),Arrays.asList(clear,clearSecond,Edit.deleteParagraph("remove-first-body",body)),Arrays.asList(clear,clearSecond,Edit.insertParagraphs("insert-new-body-before-gaps",heading,true,Collections.singletonList("New authorized body"),body)))){DocxEditResult result=apply(source,edits,layout);assertEquals("SKIPPED_NOT_CLEARED",result.getNativeLayoutLedger().get(0).getStatus());assertFalse(result.getIndex().mainParagraph(4).hasNativeHeadingPageBreak());}
        DocxEditResult result=apply(source,Arrays.asList(clear,clearSecond),layout);assertEquals("APPLIED",result.getNativeLayoutLedger().get(0).getStatus());assertTrue(result.getIndex().mainParagraph(4).hasNativeHeadingPageBreak());assertEquals(heading.getText(),result.getIndex().mainParagraph(4).getText());assertEquals(body.getSha256(),result.getIndex().mainParagraph(7).getSha256());assertEquals(index.mainParagraph(3).getSha256(),result.getIndex().mainParagraph(3).getSha256());assertArrayEquals(parts(source).get("word/media/opaque.bin"),parts(result.getDocxBytes()).get("word/media/opaque.bin"));
    }
    @Test void headingBoundaryRejectsWrongSourceIdentityAndNonconsecutiveSourceChains()throws Exception{
        byte[] source=fixture("");TemplateIndex index=editor.inspect(source);NativeLayoutPlan wrong=new NativeLayoutPlan("wrong-source","exact-heading-break",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.singletonList(new NativeLayoutParagraphBoundary(index.anchor(index.mainParagraph(4).getId()),index.anchor(index.mainParagraph(6).getId()),Collections.singletonList(index.anchor(index.mainParagraph(5).getId())))));assertEquals("LAYOUT_SOURCE_HASH_MISMATCH",assertThrows(EditException.class,()->apply(source,Collections.emptyList(),wrong)).getCode());NativeLayoutPlan nonconsecutive=new NativeLayoutPlan(index.getSourceSha256(),"exact-heading-break",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.singletonList(new NativeLayoutParagraphBoundary(index.anchor(index.mainParagraph(4).getId()),index.anchor(index.mainParagraph(6).getId()),Collections.singletonList(index.anchor(index.mainParagraph(2).getId())))));assertThrows(EditException.class,()->apply(source,Collections.emptyList(),nonconsecutive));
    }
}
