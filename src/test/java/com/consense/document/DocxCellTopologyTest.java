package com.consense.document;

import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DocxCellTopologyTest {
    @Test void colspanVerticalMergeNestedTablesAndStrikeChannelsKeepDistinctNativeAnchors() throws Exception {
        String body="<w:tbl><w:tblGrid><w:gridCol w:w=\"1000\"/><w:gridCol w:w=\"1000\"/><w:gridCol w:w=\"1000\"/></w:tblGrid>"
                +"<w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:vMerge w:val=\"restart\"/></w:tcPr>"
                +p("active")+"<w:p><w:r><w:rPr><w:strike/></w:rPr><w:t>struck option</w:t></w:r><w:del w:id=\"1\"><w:r><w:delText>removed option</w:delText></w:r></w:del></w:p>"
                +"<w:tbl><w:tblGrid><w:gridCol w:w=\"800\"/></w:tblGrid><w:tr><w:tc>"+p("nested value")+"</w:tc></w:tr></w:tbl><w:p/></w:tc><w:tc>"+p("other column")+"</w:tc></w:tr>"
                +"<w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:vMerge/></w:tcPr><w:p/></w:tc><w:tc>"+p("other row")+"</w:tc></w:tr></w:tbl>";
        DocumentParser.ParsedDocument parsed=parse(body); assertEquals(2,parsed.getBlocks().size()); DocumentBlock first=parsed.getBlocks().get(0);
        DocumentBlock.WordCell merged=first.getWordCells().get(0), nested=first.getWordCells().get(1), other=first.getWordCells().get(2);
        assertEquals(Integer.valueOf(2),merged.getColumnSpan()); assertEquals(Integer.valueOf(2),merged.getRowSpan()); assertEquals(Integer.valueOf(0),merged.getGridColumn());
        assertEquals(Integer.valueOf(2),other.getGridColumn()); assertNotEquals(merged.getTableLocation(),nested.getTableLocation());
        assertTrue(merged.getText().contains("active")); assertTrue(merged.getText().contains("nested value")); assertFalse(merged.getText().contains("struck"));
        assertEquals("struck option",merged.getStrikeText()); assertEquals("removed option",merged.getDeletedText());
        DocumentBlock.WordCell continuation=parsed.getBlocks().get(1).getWordCells().get(0);
        assertEquals("continue",continuation.getVerticalMerge()); assertNull(continuation.getRowSpan()); assertNull(first.getPageNo());
        assertEquals("native_ooxml_unrendered",merged.getTopologyStatus()); assertNotNull(parsed.getSourceSha256());
    }
    @Test void invalidSpanIsUnknownAndCannotSilentlyMoveFollowingCells() throws Exception {
        DocumentParser.ParsedDocument parsed=parse("<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"0\"/></w:tcPr>"+p("bad span")+"</w:tc><w:tc>"+p("following")+"</w:tc></w:tr></w:tbl>");
        List<DocumentBlock.WordCell> cells=parsed.getBlocks().get(0).getWordCells(); assertNull(cells.get(0).getColumnSpan()); assertEquals("0",cells.get(0).getGridSpanRaw());
        assertNull(cells.get(1).getGridColumn()); assertEquals("native_ooxml_topology_unresolved",cells.get(1).getTopologyStatus()); assertFalse(parsed.getCoverage().isComplete());
    }
    @Test void badGridBeforeAndLegacyHorizontalMergeArePreservedAsUnresolved() throws Exception {
        DocumentParser.ParsedDocument parsed=parse("<w:tbl><w:tr><w:trPr><w:gridBefore w:val=\"invalid\"/></w:trPr><w:tc><w:tcPr><w:hMerge w:val=\"restart\"/></w:tcPr>"+p("legacy")+"</w:tc></w:tr></w:tbl>");
        DocumentBlock.WordCell cell=parsed.getBlocks().get(0).getWordCells().get(0); assertNull(cell.getGridColumn()); assertEquals("invalid",cell.getGridBeforeRaw());
        assertEquals("restart",cell.getHorizontalMerge()); assertFalse(cell.getLimitations().isEmpty()); assertEquals("native_ooxml_topology_unresolved",cell.getTopologyStatus());
    }
    @Test void blankNativeCellsStillRetainTheirTableTopology() throws Exception {
        DocumentParser.ParsedDocument parsed=parse("<w:tbl><w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>");
        assertEquals(1,parsed.getBlocks().size()); assertEquals(2,parsed.getBlocks().get(0).getWordCells().size());
        assertEquals(Integer.valueOf(1),parsed.getBlocks().get(0).getWordCells().get(1).getGridColumn()); assertNull(parsed.getBlocks().get(0).getPageNo());
    }
    private static String p(String text) {return "<w:p><w:r><w:t>"+text+"</w:t></w:r></w:p>";}
    private DocumentParser.ParsedDocument parse(String body) throws Exception {
        ByteArrayOutputStream initial=new ByteArrayOutputStream(); try(XWPFDocument doc=new XWPFDocument()){doc.createParagraph();doc.write(initial);}
        ByteArrayOutputStream modified=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(initial.toByteArray())); ZipOutputStream out=new ZipOutputStream(modified)){
            ZipEntry entry; while((entry=in.getNextEntry())!=null){ByteArrayOutputStream part=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                while((count=in.read(buffer))!=-1)part.write(buffer,0,count); byte[] bytes=part.toByteArray();
                if("word/document.xml".equals(entry.getName()))bytes=new String(bytes,StandardCharsets.UTF_8).replaceAll("(?s)<w:body>.*?</w:body>","<w:body>"+body+"</w:body>").getBytes(StandardCharsets.UTF_8);
                out.putNextEntry(new ZipEntry(entry.getName()));out.write(bytes);out.closeEntry();
            }
        }
        return new DocumentParser(new ConsenseProperties(),mock(OcrClient.class)).parse("anonymous.docx",modified.toByteArray());
    }
}
