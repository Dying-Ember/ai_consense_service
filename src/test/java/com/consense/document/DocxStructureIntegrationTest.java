package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocxStructureIntegrationTest {
    private static final String TICK = "<w:r><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></w:r>";

    @Test void tableCellsAdvanceNumberingExactlyOnceAndPreserveCellAnchors() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            BigInteger num = numbering(doc);
            XWPFTable table = doc.createTable(1, 2);
            for (int i=0; i<2; i++) {
                XWPFParagraph p=table.getRow(0).getCell(i).getParagraphs().get(0);
                p.setNumID(num); p.createRun().setText("item " + i);
            }
            bytes=save(doc);
        }
        DocumentBlock row=parse(bytes).getBlocks().get(0);
        assertEquals(Arrays.asList("(e) item 0", "(f) item 1"), row.getCells());
        assertEquals("body/0/table-row/0", row.getLocation());
        assertEquals(2, row.getWordNumbering().size());
        assertTrue(row.getWordNumbering().get(1).getLocation().contains("/cell/1/"));
        assertNull(row.getPageNo());
    }

    @Test void fullyDeletedOrStruckNumberedItemsDoNotBecomeEffectiveClauses() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc=new XWPFDocument()) {
            BigInteger num=numbering(doc);
            for (String text : Arrays.asList("delete me", "strike me", "active")) {
                XWPFParagraph p=doc.createParagraph(); p.setNumID(num);
                XWPFRun run=p.createRun(); run.setText(text);
                if (text.equals("strike me")) run.setStrikeThrough(true);
            }
            bytes=save(doc);
        }
        bytes=edit(bytes, "<w:r><w:t>delete me</w:t></w:r>",
                "<w:del w:id=\"1\" w:author=\"Fixture\"><w:r><w:delText>delete me</w:delText></w:r></w:del>");
        DocumentParser.ParsedDocument parsed=parse(bytes);
        assertEquals(3, parsed.getBlocks().size());
        assertEquals("", parsed.getBlocks().get(0).getText());
        assertEquals("(e) delete me", parsed.getBlocks().get(0).getDeletedText());
        assertEquals("", parsed.getBlocks().get(1).getText());
        assertEquals("(f) strike me", parsed.getBlocks().get(1).getStrikeText());
        assertEquals("(g) active", parsed.getText());
        assertFalse(parsed.getBlocks().get(0).getWordNumbering().get(0).isAppliedToEffectiveText());
    }

    @Test void symbolsKeepIndependentOriginalDeletedAndStrikeChannels() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc=new XWPFDocument()) { doc.createParagraph(); bytes=save(doc); }
        String paragraph="<w:p>"+TICK+"<w:del w:id=\"2\" w:author=\"Fixture\">"+TICK
                +"</w:del><w:r><w:rPr><w:strike/></w:rPr><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></w:r></w:p>";
        DocumentBlock b=parse(edit(bytes,"</w:body>",paragraph+"</w:body>")).getBlocks().get(0);
        assertEquals("✓",b.getText()); assertEquals("✓✓✓",b.getOriginalText());
        assertEquals("✓",b.getDeletedText()); assertEquals("✓",b.getStrikeText());
        assertEquals(3,b.getWordSymbols().size());
        assertTrue(b.getWordSymbols().get(1).isDeleted());
        assertTrue(b.getWordSymbols().get(2).isStruck());
    }

    @Test void unsupportedSymbolAndNumberingAreVisiblePartialCoverage() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            XWPFParagraph p=doc.createParagraph(); p.setNumID(BigInteger.valueOf(999)); p.createRun().setText("body");
            bytes=save(doc);
        }
        bytes=edit(bytes,"</w:body>","<w:p><w:r><w:sym w:font=\"UnknownFont\" w:char=\"F0FC\"/></w:r></w:p></w:body>");
        DocumentParser.ParsedDocument parsed=parse(bytes);
        assertEquals("PARTIAL",parsed.getParseStatus());
        assertTrue(parsed.getText().contains("[unresolved Word numbering] body"));
        assertTrue(parsed.getText().contains("[unresolved Word symbol]"));
        assertFalse(parsed.getText().contains("✓"));
        assertEquals("999",parsed.getBlocks().get(0).getWordNumbering().get(0).getNumId());
        assertEquals("UnknownFont",parsed.getBlocks().get(1).getWordSymbols().get(0).getFont());
    }

    @Test void savedProvenanceRoundTripsAndLegacyBlocksRemainCompatible() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            XWPFParagraph p=doc.createParagraph(); p.setNumID(numbering(doc)); p.createRun().setText("body"); bytes=save(doc);
        }
        bytes=edit(bytes,"</w:body>","<w:p>"+TICK+"</w:p></w:body>");
        List<DocumentBlock> blocks=parse(bytes).getBlocks();
        List<DocumentBlock> saved=JsonUtils.readList(JsonUtils.write(blocks),DocumentBlock.class);
        assertEquals(blocks,saved);
        DocumentBlock legacy=JsonUtils.read("{\"text\":\"old\",\"location\":\"body/1/paragraph\"}",DocumentBlock.class);
        assertTrue(legacy.getWordSymbols().isEmpty()); assertTrue(legacy.getWordNumbering().isEmpty());
        assertNull(legacy.getWordStructureVersion());
        assertFalse(JsonUtils.write(legacy).contains("wordSymbols"));
    }

    @Test void textboxNumberingDoesNotAdvanceBodyList() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            XWPFParagraph p=doc.createParagraph(); p.setNumID(numbering(doc)); p.createRun().setText("first"); bytes=save(doc);
        }
        String numbered="<w:p><w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>";
        String textbox="<w:p><w:r><w:pict><v:shape xmlns:v=\"urn:schemas-microsoft-com:vml\"><v:textbox><w:txbxContent>"
                + numbered + "textbox</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>";
        bytes=edit(bytes,"</w:body>",textbox+numbered+"last</w:t></w:r></w:p></w:body>");
        List<DocumentBlock> blocks=parse(bytes).getBlocks();
        assertEquals("(e) first",blocks.get(0).getText());
        assertEquals("(e) textbox",blocks.get(1).getText());
        assertEquals("(f) last",blocks.get(2).getText());
    }

    @Test void alternateContentDoesNotAdvanceBodyAndIsExplicitlyPartial() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            BigInteger num=numbering(doc);
            XWPFParagraph p=doc.createParagraph();p.setNumID(num);p.createRun().setText("first");
            doc.createParagraph().createRun().setText("holder");
            p=doc.createParagraph();p.setNumID(num);p.createRun().setText("last");bytes=save(doc);
        }
        String alternative="<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" xmlns:v=\"urn:schemas-microsoft-com:vml\">"
                +"<mc:Choice Requires=\"v\"><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></mc:Choice>"
                +"<mc:Fallback><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></mc:Fallback></mc:AlternateContent>";
        DocumentParser.ParsedDocument parsed=parse(edit(bytes,"<w:t>holder</w:t>",alternative));
        assertEquals("PARTIAL",parsed.getParseStatus());
        assertEquals("(f) last",parsed.getBlocks().get(2).getText());
        assertEquals(1,parsed.getBlocks().get(1).getWordWarnings().size());
        assertTrue(parsed.getBlocks().get(1).getText().startsWith("[unresolved Word AlternateContent]"));
    }

    @Test void noteNumberingRetainsDefinitionsButDoesNotAssertUnverifiedOrder() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            BigInteger num=numbering(doc);doc.createParagraph().createRun().setText("body");
            XWPFFootnote note=doc.createFootnote();XWPFParagraph p=note.createParagraph();p.setNumID(num);p.createRun().setText("note");bytes=save(doc);
        }
        DocumentParser.ParsedDocument parsed=parse(bytes);
        DocumentBlock note=parsed.getBlocks().stream().filter(b->"footnote".equals(b.getKind())).findFirst().get();
        assertEquals("PARTIAL",parsed.getParseStatus());
        assertTrue(note.getText().contains("[unresolved Word numbering] note"));
        assertEquals("note_reference_order_not_verified",note.getWordNumbering().get(0).getReason());
        assertEquals("(e)",note.getWordNumbering().get(0).getLabel());
    }

    private BigInteger numbering(XWPFDocument doc) {
        XWPFNumbering n=doc.createNumbering();
        CTAbstractNum a=CTAbstractNum.Factory.newInstance(); a.setAbstractNumId(BigInteger.ZERO);
        CTLvl l=a.addNewLvl(); l.setIlvl(BigInteger.ZERO); l.addNewStart().setVal(BigInteger.valueOf(5));
        l.addNewNumFmt().setVal(STNumberFormat.LOWER_LETTER); l.addNewLvlText().setVal("(%1)");
        BigInteger abstractId=n.addAbstractNum(new XWPFAbstractNum(a));
        n.addNum(abstractId, BigInteger.ONE);
        return BigInteger.ONE;
    }
    private DocumentParser.ParsedDocument parse(byte[] bytes) {
        OcrClient ocr=mock(OcrClient.class);
        DocumentParser.ParsedDocument result=new DocumentParser(new ConsenseProperties(),ocr).parse("fixture.docx",bytes);
        verifyNoInteractions(ocr); return result;
    }
    private byte[] save(XWPFDocument doc) throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream(); doc.write(out); return out.toByteArray(); }
    private byte[] edit(byte[] bytes,String from,String to) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes)); ZipOutputStream zip=new ZipOutputStream(out)) {
            ZipEntry entry;
            while((entry=in.getNextEntry())!=null) {
                ByteArrayOutputStream data=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int len;
                while((len=in.read(buffer))!=-1) data.write(buffer,0,len);
                byte[] content=data.toByteArray();
                if(entry.getName().equals("word/document.xml")) {
                    String xml=new String(content,StandardCharsets.UTF_8); assertTrue(xml.contains(from));
                    content=xml.replace(from,to).getBytes(StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new ZipEntry(entry.getName())); zip.write(content); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
