package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Color;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DocumentParseProbeTest {
    @TempDir Path temp;
    ConsenseProperties observed(){ConsenseProperties p=new ConsenseProperties();p.getDocument().setProbeDirectory(temp.toString());return p;}
    static JsonNode read(Path file)throws IOException{return JsonUtils.parse(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));}
    List<JsonNode> events(String job)throws IOException{
        try(Stream<Path> files=Files.list(temp.resolve(job))){return files.filter(p->p.getFileName().toString().matches("[0-9]{5}-.*\\.json"))
            .sorted().map(p->{try{return read(p);}catch(IOException e){throw new UncheckedIOException(e);}}).collect(Collectors.toList());}
    }
    static byte[] pdf(boolean includeNativeAndBlank)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(PDDocument doc=new PDDocument()){
            if(includeNativeAndBlank){
                PDPage nativePage=new PDPage(new PDRectangle(72,72));doc.addPage(nativePage);
                try(PDPageContentStream stream=new PDPageContentStream(doc,nativePage)){
                    stream.beginText();stream.setFont(PDType1Font.HELVETICA,2);stream.newLineAtOffset(2,40);
                    stream.showText("The contractor shall submit the explicit source details before the work starts.");stream.endText();
                }
                doc.addPage(new PDPage(new PDRectangle(72,72)));
            }
            PDPage scan=new PDPage(new PDRectangle(72,72));doc.addPage(scan);
            try(PDPageContentStream stream=new PDPageContentStream(doc,scan)){stream.setNonStrokingColor(Color.BLACK);stream.addRect(20,20,5,5);stream.fill();}
            doc.save(out);
        }
        return out.toByteArray();
    }
    @Test void sourceIdentitySafeJobAndFreshDirectoryAreRequiredBeforeParsing()throws Exception{
        ConsenseProperties p=observed();OcrClient ocr=mock(OcrClient.class);DocumentParser parser=new DocumentParser(p,ocr);byte[] source="Literal source".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,()->parser.parse("source.txt",source,null,null,null));
        assertThrows(IllegalArgumentException.class,()->parser.parse("source.txt",source,"../escape","12",DocumentParseProbe.sha256(source)));
        assertThrows(IllegalArgumentException.class,()->parser.parse("source.txt",source,"wrong-sha","12",String.join("",Collections.nCopies(64,"a"))));
        assertFalse(Files.exists(temp.resolve("wrong-sha")));
        parser.parse("source.txt",source,"fresh","12",DocumentParseProbe.sha256(source));
        byte[] initial=Files.readAllBytes(temp.resolve("fresh/00001-parse_started.json"));
        assertThrows(DocumentParseProbe.Failure.class,()->parser.parse("source.txt",source,"fresh","12",DocumentParseProbe.sha256(source)));
        assertArrayEquals(initial,Files.readAllBytes(temp.resolve("fresh/00001-parse_started.json")));
        verifyNoInteractions(ocr);
    }
    @Test void legacyCallerRemainsUnobservedWithConfiguredRootAndOriginalOutputAndOcrCallCount()throws Exception{
        OcrClient ocr=mock(OcrClient.class);when(ocr.available()).thenReturn(true);
        OcrClient.OcrResult result=new OcrClient.OcrResult("Original scanned requirement.",.8,Collections.emptyList());when(ocr.recognize(any())).thenReturn(result);
        byte[] original=pdf(true);DocumentParser.ParsedDocument expected=new DocumentParser(new ConsenseProperties(),ocr).parse("legacy.pdf",original);
        reset(ocr);when(ocr.available()).thenReturn(true);when(ocr.recognize(any())).thenReturn(result);
        DocumentParser.ParsedDocument observed=new DocumentParser(observed(),ocr).parse("legacy.pdf",original);
        assertEquals(JsonUtils.write(expected),JsonUtils.write(observed));verify(ocr,times(1)).available();verify(ocr,times(1)).recognize(any());
        reset(ocr);ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(XWPFDocument word=new XWPFDocument()){word.createParagraph().createRun().setText("Original legacy body with its unmodified condition.");word.write(out);}
        byte[] docx=out.toByteArray();
        assertEquals(JsonUtils.write(new DocumentParser(new ConsenseProperties(),ocr).parse("legacy.docx",docx)),JsonUtils.write(new DocumentParser(observed(),ocr).parse("legacy.docx",docx)));
        verifyNoInteractions(ocr);try(Stream<Path> files=Files.list(temp)){assertEquals(0,files.count(),"Legacy/drafting callers do not write configured vetting probe artifacts");}
    }
    @Test void originalParseFailureWinsIfItsFinalObservationFlushAlsoFails()throws Exception{
        byte[] source={1};DocumentParseProbe probe=DocumentParseProbe.start(observed(),"final-failure","12",DocumentParseProbe.sha256(source),"fixture.bin",source);
        byte[] sentinel="Previous immutable summary".getBytes(StandardCharsets.UTF_8);
        Files.write(temp.resolve("final-failure/probe_overhead_manifest.json"),sentinel,StandardOpenOption.CREATE_NEW);
        IllegalStateException original=new IllegalStateException("Original parser failure",new IOException("Original cause"));
        probe.finish("aborted",original);assertEquals(1,original.getSuppressed().length);assertTrue(original.getSuppressed()[0] instanceof DocumentParseProbe.Failure);
        assertEquals("Original cause",original.getCause().getMessage());assertArrayEquals(sentinel,Files.readAllBytes(temp.resolve("final-failure/probe_overhead_manifest.json")));
    }
    @Test void mixedPhysicalPdfObservedOutputEqualsDefaultAndActualOcrInputIsFrozen()throws Exception{
        byte[] source=pdf(true);OcrClient ocr=mock(OcrClient.class);when(ocr.available()).thenReturn(true);
        OcrClient.OcrResult raw=new OcrClient.OcrResult("Explicit scanned line.",.91,Collections.singletonList(new OcrClient.OcrLine("Explicit scanned line.",.91,new double[]{10,12,50,8})));
        when(ocr.recognize(any())).thenReturn(raw);
        DocumentParser.ParsedDocument expected=new DocumentParser(new ConsenseProperties(),ocr).parse("mixed.pdf",source);
        reset(ocr);when(ocr.available()).thenReturn(true);when(ocr.recognize(any())).thenReturn(raw);
        DocumentParser.ParsedDocument actual=new DocumentParser(observed(),ocr).parse("mixed.pdf",source,"mixed","12",DocumentParseProbe.sha256(source));
        assertEquals(JsonUtils.write(expected),JsonUtils.write(actual));
        assertEquals(Arrays.asList("native","blank","ocr"),actual.getPages().stream().map(DocumentParser.PageText::getStatus).collect(Collectors.toList()));
        org.mockito.ArgumentCaptor<byte[]> input=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(ocr,times(1)).recognize(input.capture());verify(ocr,times(1)).available();
        List<JsonNode> trace=events("mixed");JsonNode event=trace.stream().filter(e->"ocr_actual_input".equals(e.path("phase").asText())).findFirst().get();
        assertEquals(3,event.path("physicalPage").asInt());
        assertArrayEquals(input.getValue(),Files.readAllBytes(temp.resolve("mixed").resolve(event.path("artifact").path("relativePath").asText())));
        assertEquals(DocumentParseProbe.sha256(input.getValue()),event.path("artifact").path("sha256").asText());
        assertEquals(2,trace.stream().filter(e->"pdf_render_image".equals(e.path("phase").asText())).count());
        assertFalse(trace.stream().anyMatch(e->"pdf_render_image".equals(e.path("phase").asText())&&e.path("physicalPage").asInt()==1));
        assertTrue(trace.stream().filter(e->"pdf_render_image".equals(e.path("phase").asText())).allMatch(e->e.path("observations").path("actualDpi").asInt()==200));
        JsonNode result=trace.stream().filter(e->"ocr_actual_result".equals(e.path("phase").asText())).findFirst().get().path("observations").path("result");
        assertEquals(.91,result.path("lines").get(0).path("confidence").asDouble());assertEquals(4,result.path("lines").get(0).path("bbox").size());
        assertTrue(trace.stream().allMatch(e->DocumentParseProbe.sha256(source).equals(e.path("sourceSha256").asText())&&"12".equals(e.path("sourceId").asText())));
    }
    @Test void ocrDecoratorPassesSameInputReturnsSameObjectAndRethrowsSameException()throws Exception{
        byte[] source={1,2,3};DocumentParseProbe probe=DocumentParseProbe.start(observed(),"delegate","12",DocumentParseProbe.sha256(source),"fixture.bin",source);
        OcrClient delegate=mock(OcrClient.class);ObservedOcrClient observed=new ObservedOcrClient(delegate,probe,7);
        OcrClient.OcrResult original=new OcrClient.OcrResult("Raw returned text",.8,Collections.emptyList());
        when(delegate.recognize(same(source))).thenReturn(original);assertSame(original,observed.recognize(source));verify(delegate).recognize(same(source));
        IllegalStateException failure=new IllegalStateException("Actual fixture failure",new IOException("Original cause"));when(delegate.recognize(same(source))).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,()->observed.recognize(source)));
        probe.finish("fixture_finished");
        JsonNode error=events("delegate").stream().filter(e->"ocr_actual_failure".equals(e.path("phase").asText())).findFirst().get();
        assertEquals("Actual fixture failure",error.path("observations").path("failureChain").get(0).path("message").asText());
        assertEquals("Original cause",error.path("observations").path("failureChain").get(1).path("message").asText());
    }
    @Test void originalOcrFailureWinsIfItsObservationWriteAlsoFails()throws Exception{
        byte[] source={1};DocumentParseProbe probe=DocumentParseProbe.start(observed(),"failure","12",DocumentParseProbe.sha256(source),"fixture.bin",source);
        OcrClient delegate=mock(OcrClient.class);IllegalStateException original=new IllegalStateException("Original OCR failure");when(delegate.recognize(any())).thenThrow(original);
        Files.write(temp.resolve("failure/00003-ocr_actual_failure.json"),"existing evidence".getBytes(StandardCharsets.UTF_8));
        assertSame(original,assertThrows(IllegalStateException.class,()->new ObservedOcrClient(delegate,probe,1).recognize(source)));
        assertEquals(1,original.getSuppressed().length);assertTrue(original.getSuppressed()[0] instanceof DocumentParseProbe.Failure);
        assertEquals("existing evidence",new String(Files.readAllBytes(temp.resolve("failure/00003-ocr_actual_failure.json")),StandardCharsets.UTF_8));
    }
    @Test void failedOcrPageKeepsDefaultCoverageWhileRecordingOriginalFailureAndElapsed()throws Exception{
        byte[] source=pdf(false);OcrClient delegate=mock(OcrClient.class);when(delegate.available()).thenReturn(true);when(delegate.recognize(any())).thenThrow(new IllegalStateException("Observed synthetic downstream failure"));
        DocumentParser.ParsedDocument expected=new DocumentParser(new ConsenseProperties(),delegate).parse("scan.pdf",source);
        DocumentParser.ParsedDocument actual=new DocumentParser(observed(),delegate).parse("scan.pdf",source,"ocr-failure","12",DocumentParseProbe.sha256(source));
        assertEquals(JsonUtils.write(expected),JsonUtils.write(actual));assertEquals("FAILED",actual.getParseStatus());
        List<JsonNode> trace=events("ocr-failure");assertTrue(trace.stream().anyMatch(e->"ocr_actual_failure".equals(e.path("phase").asText())&&e.path("observations").path("wallNanos").asLong()>=0));
        assertTrue(trace.stream().anyMatch(e->"pdf_physical_page_output".equals(e.path("phase").asText())&&"ocr_failed".equals(e.path("observations").path("status").asText())));
        assertEquals("returned_normally",read(temp.resolve("ocr-failure/probe_overhead_manifest.json")).path("status").asText());
    }
    @Test void docxInactiveTextNativeCellsAndUnpaginatedLocationsKeepOriginalOutput()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(XWPFDocument doc=new XWPFDocument()){
            XWPFParagraph paragraph=doc.createParagraph();paragraph.createRun().setText("Visible obligation ");
            XWPFRun strike=paragraph.createRun();strike.setText("Inactive option");strike.setStrikeThrough(true);
            paragraph.getCTP().addNewDel().addNewR().addNewDelText().setStringValue("Deleted alternative");
            XWPFTable table=doc.createTable(2,3);table.getRow(0).getCell(0).setText("Clause");table.getRow(0).getCell(1).setText("Required input");table.getRow(0).getCell(2).setText("Reply");
            table.getRow(1).getCell(0).setText("XYZ1");table.getRow(1).getCell(1).setText("Provide a local value");table.getRow(1).getCell(2).setText("Native value");doc.write(out);
        }
        byte[] source=out.toByteArray();OcrClient ocr=mock(OcrClient.class);
        DocumentParser.ParsedDocument expected=new DocumentParser(new ConsenseProperties(),ocr).parse("source.docx",source);
        DocumentParser.ParsedDocument actual=new DocumentParser(observed(),ocr).parse("source.docx",source,"docx","12",DocumentParseProbe.sha256(source));
        assertEquals(JsonUtils.write(expected),JsonUtils.write(actual));assertEquals(0,actual.getPageCount());
        List<JsonNode> blocks=events("docx").stream().filter(e->"docx_source_block".equals(e.path("phase").asText())).map(e->e.path("observations").path("block")).collect(Collectors.toList());
        assertTrue(blocks.stream().anyMatch(b->"Inactive option".equals(b.path("strikeText").asText())&&"Deleted alternative".equals(b.path("deletedText").asText())));
        assertTrue(blocks.stream().anyMatch(b->b.path("cells").size()==3&&"Native value".equals(b.path("cells").get(2).asText())));
        assertTrue(blocks.stream().allMatch(b->b.path("pageNo").isNull()));verifyNoInteractions(ocr);
    }
    @Test void malformedPdfKeepsFailureChainAndSeparateObserverOverhead()throws Exception{
        byte[] source="not a pdf".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,()->new DocumentParser(observed(),mock(OcrClient.class)).parse("bad.pdf",source,"bad-pdf","12",DocumentParseProbe.sha256(source)));
        assertTrue(events("bad-pdf").stream().anyMatch(e->"parse_failure".equals(e.path("phase").asText())&&!e.path("observations").path("failureChain").isEmpty()));
        JsonNode overhead=read(temp.resolve("bad-pdf/probe_overhead_manifest.json"));assertEquals("aborted",overhead.path("status").asText());
        assertTrue(overhead.path("serializationAndHashWallNanos").asLong()>0);assertTrue(overhead.path("artifactWriteWallNanos").asLong()>0);assertFalse(overhead.path("summarySelfFlushIncluded").asBoolean());
    }
}
