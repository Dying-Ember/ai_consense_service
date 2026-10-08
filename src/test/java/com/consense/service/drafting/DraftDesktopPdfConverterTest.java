package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DraftDesktopPdfConverterTest {
    private static final String MARKER="CS0123456789abcdef0123456789ab";
    @TempDir Path temporary;
    DraftPdfRenderProfile fixture(String mode)throws Exception {
        Path cache=Files.createDirectories(temporary.resolve("cache")),font=temporary.resolve("font.ttf");Files.write(font,"boundary fixture font bytes".getBytes(StandardCharsets.UTF_8));
        Files.write(cache.resolve("AllFonts.js"),("window[\"__fonts_files\"] = ["+com.consense.common.JsonUtils.write(font.toAbsolutePath().toString())+"];\nwindow[\"__fonts_infos\"] = [[\"Times New Roman\"]];").getBytes(StandardCharsets.UTF_8));Files.write(cache.resolve("font_selection.bin"),new byte[]{1});
        Path javaHome=Paths.get(System.getProperty("java.home"));String javaExecutable=javaHome.resolve("bin").resolve(System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        return new DraftPdfRenderProfile(Arrays.asList(javaExecutable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),DraftDesktopPdfFixture.class.getName(),mode),temporary.resolve("work"),30000,2048,1024*1024,Collections.emptySet(),"external boundary fixture cache",DraftPdfRenderProfile.Engine.DESKTOP_X2T,javaHome,cache);
    }
    byte[] markedDocx()throws Exception {
        byte[] original;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){org.apache.poi.xwpf.usermodel.XWPFRun run=word.createParagraph().createRun();run.setFontFamily("Times New Roman");run.setText("A physical first run");word.write(out);original=out.toByteArray();}
        DocxTemplateEditor editor=new DocxTemplateEditor();DocxTemplateEditor.TemplateIndex index=editor.inspect(original);
        return editor.applyWithParagraphBookmarks(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.singletonMap(index.getMainParagraphs().get(0).getId(),MARKER)).getDocxBytes();
    }
    @Test void aRotatedCropUsesTheObservedFirstRunVisualCornerAndRemovesTemporaryLinks()throws Exception {
        DraftPdfConversionResult result=new DraftPdfConverter(fixture("rotate90")).convert(markedDocx());
        try(PDDocument pdf=PDDocument.load(result.getPdfBytes())) {
            PDPageXYZDestination point=(PDPageXYZDestination)pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination(MARKER));
            assertNotNull(point);assertEquals(120,point.getLeft());assertEquals(240,point.getTop(),"At rotation90 the visual top-left is the annotation's lower-left PDF corner");
            assertEquals(90,pdf.getPage(0).getRotation());assertEquals(100,pdf.getPage(0).getCropBox().getLowerLeftX());assertTrue(pdf.getPage(0).getAnnotations().isEmpty());
        }
    }
    @Test void hiddenOrUnknownBindingUriActionsCannotEscapeInTheDeliveredPdf()throws Exception {
        DraftPdfConversionResult result=new DraftPdfConverter(fixture("nested-actions")).convert(markedDocx());
        try(PDDocument pdf=PDDocument.load(result.getPdfBytes())) {
            assertNull(pdf.getDocumentCatalog().getOpenAction(),"A custom marker URI is never a document-open action");
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink ordinary=(org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink)pdf.getPage(0).getAnnotations().get(0);
            assertEquals("https://example.invalid/retained-source-link",((org.apache.pdfbox.pdmodel.interactive.action.PDActionURI)ordinary.getAction()).getURI());
            assertNull(new org.apache.pdfbox.pdmodel.interactive.action.PDAnnotationAdditionalActions((org.apache.pdfbox.cos.COSDictionary)ordinary.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.AA)).getE(),"Custom marker hover actions must also be stripped");
            assertNull(pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination("ForeignUnknown")));
        }
    }
    @Test void cachedFontBytesAndCacheMetadataArePartOfTheCurrentProfile()throws Exception {
        DraftPdfRenderProfile profile=fixture("success");DraftPdfConverter converter=new DraftPdfConverter(profile);String first=converter.currentProfileHash();
        Files.write(temporary.resolve("font.ttf"),"changed boundary font bytes".getBytes(StandardCharsets.UTF_8));String second=converter.currentProfileHash();assertNotEquals(first,second);
        Files.write(profile.getFontCache().resolve("font_selection.bin"),new byte[]{2});assertNotEquals(second,converter.currentProfileHash());
        Files.delete(temporary.resolve("font.ttf"));assertThrows(DraftPdfConversionException.class,converter::currentProfileHash);
    }
    @Test void unknownMarkersAndOutOfCropAnnotationsCannotBecomeObservedLocations()throws Exception {
        byte[] source=markedDocx();for(String mode:Arrays.asList("unknown-marker","outside-crop")){
            DraftPdfConversionResult result=new DraftPdfConverter(fixture(mode)).convert(source);
            try(PDDocument pdf=PDDocument.load(result.getPdfBytes())){assertNull(pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination(MARKER)));assertNull(pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination("CSNotInSource")));assertTrue(pdf.getPage(0).getAnnotations().isEmpty());}
        }
    }
    @Test void anExistingReservedSourceLinkCannotImpersonateTheConversionMarker()throws Exception {
        byte[] original;try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(markedDocx()));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun run=word.createParagraph().createHyperlinkRun("consense-binding:"+MARKER);run.setFontFamily("Times New Roman");run.setText("Existing source link elsewhere");word.write(out);original=out.toByteArray();
        }
        byte[] before=original.clone();DraftPdfConversionResult result=new DraftPdfConverter(fixture("success")).convert(original);assertArrayEquals(before,original);
        try(PDDocument pdf=PDDocument.load(result.getPdfBytes())){assertNull(pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination(MARKER)));assertTrue(pdf.getPage(0).getAnnotations().isEmpty());}
    }
    @ParameterizedTest @ValueSource(strings={"header","field","instruction"})
    void sourcePartsCannotImpersonateTheCurrentConversionMarker(String part)throws Exception {
        byte[] original;try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(markedDocx()));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if("header".equals(part)){
                org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun run=word.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createHyperlinkRun("consense-binding:"+MARKER);run.setFontFamily("Times New Roman");run.setText("Reserved header link");
            }else if("field".equals(part)){
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField field=word.createParagraph().getCTP().addNewFldSimple();field.setInstr(" HYPERLINK \"consense-binding:"+MARKER+"\" ");org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR run=field.addNewR();org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts fonts=run.addNewRPr().addNewRFonts();fonts.setAscii("Times New Roman");fonts.setHAnsi("Times New Roman");run.addNewT().setStringValue("Reserved field link");
            }else{
                org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph=word.createParagraph();paragraph.createRun().getCTR().addNewFldChar().setFldCharType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.BEGIN);paragraph.createRun().getCTR().addNewInstrText().setStringValue(" HYPERLINK \"consense-binding:"+MARKER+"\" ");paragraph.createRun().getCTR().addNewFldChar().setFldCharType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.SEPARATE);org.apache.poi.xwpf.usermodel.XWPFRun result=paragraph.createRun();result.setFontFamily("Times New Roman");result.setText("Reserved instruction field link");paragraph.createRun().getCTR().addNewFldChar().setFldCharType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.END);
            }
            word.write(out);original=out.toByteArray();
        }
        byte[] before=original.clone();DraftPdfConversionResult result=new DraftPdfConverter(fixture("source-link-collision")).convert(original);assertArrayEquals(before,original);
        assertFalse(new String(result.getPdfBytes(),StandardCharsets.ISO_8859_1).contains("consense-binding:"),"These fixture PDFs contain no namespace text; all conversion/source URI strings must be gone");
        try(PDDocument pdf=PDDocument.load(result.getPdfBytes())){PDPageXYZDestination point=(PDPageXYZDestination)pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination(MARKER));assertNotNull(point);assertEquals(120,point.getLeft(),"The earlier source-part URI must not become the owned body marker point");assertEquals(260,point.getTop());assertTrue(pdf.getPage(0).getAnnotations().isEmpty());}
    }
    @Test void unrelatedNativeDestinationsAndUnsupportedHistoricalBookmarkRemainAvailable()throws Exception {
        byte[] original;try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(markedDocx()));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark start=word.getParagraphs().get(0).getCTP().addNewBookmarkStart();start.setName("CSHistorical");start.setId(java.math.BigInteger.valueOf(7331));org.apache.poi.xwpf.usermodel.XWPFRun next=word.createParagraph().createRun();next.setFontFamily("Times New Roman");next.setText("Historical native range");word.getParagraphs().get(1).getCTP().addNewBookmarkEnd().setId(java.math.BigInteger.valueOf(7331));word.write(out);original=out.toByteArray();
        }
        byte[] before=original.clone();DraftPdfConversionResult result=new DraftPdfConverter(fixture("native-destinations")).convert(original);assertArrayEquals(before,original);
        try(PDDocument pdf=PDDocument.load(result.getPdfBytes())){assertNotNull(pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination("CSHistorical")),"An unregistered native bookmark must not be erased");assertNotNull(pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination("CSHistoryLegacy")),"Legacy native destinations are preserved too");assertNotNull(pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination("OtherNative")));}
    }
    @Test void rendererFailuresAndUnreadableOrMissingOutputsHaveExplicitCodes()throws Exception {
        byte[] bytes=markedDocx();String[] modes={"error","no-output","corrupt","changed-input"};DraftPdfConversionException.Code[] codes={DraftPdfConversionException.Code.CONVERSION_FAILED,DraftPdfConversionException.Code.OUTPUT_MISSING,DraftPdfConversionException.Code.INVALID_PDF,DraftPdfConversionException.Code.CONVERSION_FAILED};
        for(int i=0;i<modes.length;i++){DraftPdfConverter converter=new DraftPdfConverter(fixture(modes[i]));assertEquals(codes[i],assertThrows(DraftPdfConversionException.class,()->converter.convert(bytes)).getCode());}
        assertEquals(DraftPdfConversionException.Code.INVALID_DOCX,assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(fixture("success")).convert(new byte[]{1,2,3})).getCode());
    }
    @Test void diagnosticsAndDeliveredPdfHaveConfiguredByteBounds()throws Exception {
        byte[] bytes=markedDocx();DraftPdfRenderProfile profile=fixture("large-output");DraftPdfConversionResult result=new DraftPdfConverter(profile).convert(bytes);
        assertTrue(String.valueOf(result.getManifest().get("rendererOutput")).getBytes(StandardCharsets.UTF_8).length<=profile.getMaxOutputBytes());
        DraftPdfRenderProfile tiny=limits(profile,30000,10);assertEquals(DraftPdfConversionException.Code.OUTPUT_TOO_LARGE,assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(tiny).convert(bytes)).getCode());
    }
    @Test void deadlineStopsOnlyTheLaunchedDesktopTreeAndLeavesAnotherProcessAlive()throws Exception {
        DraftPdfRenderProfile profile=limits(fixture("timeout-child"),6000,1024*1024);String javaExecutable=profile.getCommand().get(0);Process unrelated=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),DraftPdfConverterFixture.class.getName(),"unrelated").start();long started=System.nanoTime();
        try {
            assertEquals(DraftPdfConversionException.Code.CONVERSION_TIMEOUT,assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(profile).convert(markedDocx())).getCode());assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<11000);assertTrue(unrelated.isAlive());
            Path pidFile;try(java.util.stream.Stream<Path> files=Files.walk(profile.getWorkRoot())){pidFile=files.filter(p->p.getFileName().toString().equals("owned-child.pid")).findFirst().orElseThrow(()->new AssertionError("External child launch must actually occur before deadline"));}
            long pid=Long.parseLong(new String(Files.readAllBytes(pidFile),StandardCharsets.UTF_8));Class<?> handles=Class.forName("java.lang.ProcessHandle");Optional<?> child=(Optional<?>)handles.getMethod("of",long.class).invoke(null,pid);assertTrue(!child.isPresent()||!(Boolean)handles.getMethod("isAlive").invoke(child.get()));
        }finally{unrelated.destroyForcibly();unrelated.waitFor(2,java.util.concurrent.TimeUnit.SECONDS);}
    }
    private static DraftPdfRenderProfile limits(DraftPdfRenderProfile p,long timeout,long maxPdf){return new DraftPdfRenderProfile(p.getCommand(),p.getWorkRoot(),timeout,p.getMaxOutputBytes(),maxPdf,p.getInstalledFonts(),p.getFontInventoryIdentity(),p.getEngine(),p.getAssetRoot(),p.getFontCache());}
}
