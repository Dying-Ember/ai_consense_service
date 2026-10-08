package com.consense.service.drafting;

import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import java.util.concurrent.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DraftPdfConverterTest {
    @TempDir Path temporary;
    @Test void anUnavailableExplicitExecutableHasNoTextPdfFallback() {
        DraftPdfRenderProfile profile=new DraftPdfRenderProfile(Collections.singletonList(temporary.resolve("not-installed.exe").toString()),
                temporary.resolve("work"),1000,1024,1024,Collections.singleton("Times New Roman"),"fixture fonts");
        DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(profile).convert(new byte[]{1,2}));
        assertEquals(DraftPdfConversionException.Code.NOT_CONFIGURED,failure.getCode());
        assertFalse(Files.exists(temporary.resolve("work")),"Unavailable capability must not create a misleading output.");
    }
    DraftPdfRenderProfile fixture(String mode,long timeout) {
        String java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        return new DraftPdfRenderProfile(Arrays.asList(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                DraftPdfConverterFixture.class.getName(),mode),temporary.resolve("work"),timeout,1024,1024*1024,
                Collections.singleton("Times New Roman"),"fixture inventory");
    }
    byte[] docx(String text) throws IOException {
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            org.apache.poi.xwpf.usermodel.XWPFRun run=word.createParagraph().createRun();run.setFontFamily("Times New Roman");run.setText(text);
            word.write(out);return out.toByteArray();
        }
    }
    @Test void aHungRendererTimesOutWithoutWaitingForItsWholeDelay() throws Exception {
        long started=System.nanoTime();
        DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(fixture("timeout",900)).convert(docx("Bounded conversion")));
        assertEquals(DraftPdfConversionException.Code.CONVERSION_TIMEOUT,failure.getCode());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<6000,"Renderer timeout must be bounded.");
    }
    @Test void pdfProvenanceIdentifiesTheExactInputBytesAndRendererWithoutChangingTheCallerArtifact() throws Exception {
        byte[] source=docx("Exact frozen source bytes"),before=source.clone();
        DraftPdfConversionResult result=new DraftPdfConverter(fixture("success",10000)).convert(source);
        assertArrayEquals(before,source);
        try(PDDocument pdf=PDDocument.load(result.getPdfBytes())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains(result.getDocxSha256()));assertEquals(1,pdf.getNumberOfPages());
        }
        assertEquals(sha(source),result.getDocxSha256());assertEquals(sha(result.getPdfBytes()),result.getPdfSha256());
        assertEquals("FixtureRenderer 1.0",result.getRendererVersion());assertEquals(64,result.getRenderProfileHash().length());
        assertFalse(result.getPrivateProfileId().isEmpty());assertEquals("UNVERIFIED",result.getFieldStatus());
        byte[] returned=result.getPdfBytes();returned[0]=0;assertEquals('%',result.getPdfBytes()[0]);
    }
    static String sha(byte[] bytes) throws Exception {
        StringBuilder hash=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",value&255));return hash.toString();
    }
    @Test void anUnavailableFontUsedByActualTextIsAnExplicitCapabilityFailure() throws Exception {
        byte[] source;
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            org.apache.poi.xwpf.usermodel.XWPFRun run=word.createParagraph().createRun();run.setFontFamily("Deliberately Missing Typeface");run.setText("Active source font");word.write(out);source=out.toByteArray();
        }
        byte[] finalSource=source;
        DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(fixture("success",10000)).convert(finalSource));
        assertEquals(DraftPdfConversionException.Code.FONT_CAPABILITY_MISSING,failure.getCode());
        assertTrue(failure.getMessage().contains("Deliberately Missing Typeface"));
    }
    @Test void converterExitFailureCannotBecomeAReplacementPdf() throws Exception {
        DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(fixture("error",10000)).convert(docx("Error path")));
        assertEquals(DraftPdfConversionException.Code.CONVERSION_FAILED,failure.getCode());assertTrue(failure.getMessage().contains("17"));
        assertTrue(failure.getMessage().contains("Deliberate renderer failure"));
    }
    @Test void aZeroExitWithoutOutputIsAnExplicitMissingArtifact() throws Exception {
        DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(fixture("no-output",10000)).convert(docx("Missing PDF")));
        assertEquals(DraftPdfConversionException.Code.OUTPUT_MISSING,failure.getCode());
    }
    @Test void corruptAndZeroByteOutputsAreNotReadablePdfArtifacts() throws Exception {
        for(String mode:Arrays.asList("zero","corrupt")) {
            DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(fixture(mode,10000)).convert(docx("Bad output")));
            assertEquals(DraftPdfConversionException.Code.INVALID_PDF,failure.getCode());
        }
    }
    @Test void processOutputCaptureAndPdfSizeHaveConfiguredBounds() throws Exception {
        DraftPdfConversionResult result=new DraftPdfConverter(fixture("large-output",10000)).convert(docx("Bounded diagnostic"));
        assertTrue(String.valueOf(result.getManifest().get("rendererOutput")).getBytes(StandardCharsets.UTF_8).length<=1024);
        DraftPdfRenderProfile original=fixture("success",10000);
        DraftPdfRenderProfile tiny=new DraftPdfRenderProfile(original.getCommand(),original.getWorkRoot(),10000,1024,10,original.getInstalledFonts(),original.getFontInventoryIdentity());
        DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(tiny).convert(docx("Too large")));
        assertEquals(DraftPdfConversionException.Code.OUTPUT_TOO_LARGE,failure.getCode());
    }
    @Test void simultaneousRequestsUsePrivateProfilesAndCannotReturnTheOtherArtifact() throws Exception {
        DraftPdfConverter converter=new DraftPdfConverter(fixture("success",15000));byte[] first=docx("First contract"),second=docx("Second contract");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<DraftPdfConversionResult> a=pool.submit(()->converter.convert(first)),b=pool.submit(()->converter.convert(second));
            DraftPdfConversionResult one=a.get(20,TimeUnit.SECONDS),two=b.get(20,TimeUnit.SECONDS);
            assertNotEquals(one.getPrivateProfileId(),two.getPrivateProfileId());assertEquals(one.getRenderProfileHash(),two.getRenderProfileHash());
            assertEquals(sha(first),one.getDocxSha256());assertEquals(sha(second),two.getDocxSha256());
            try(PDDocument pdf=PDDocument.load(one.getPdfBytes())) {String text=new PDFTextStripper().getText(pdf);assertTrue(text.contains(sha(first)));assertFalse(text.contains(sha(second)));}
            try(PDDocument pdf=PDDocument.load(two.getPdfBytes())) {String text=new PDFTextStripper().getText(pdf);assertTrue(text.contains(sha(second)));assertFalse(text.contains(sha(first)));}
        }finally{pool.shutdownNow();}
    }
    @SuppressWarnings("unchecked")
    @Test void unusedThemeFontDeclarationsDoNotBlockTextWithAnAvailableExplicitFont() throws Exception {
        byte[] source=docx("Available Latin text");ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(source));ZipOutputStream result=new ZipOutputStream(out)) {
            ZipEntry entry;byte[] buffer=new byte[4096];int count;while((entry=zip.getNextEntry())!=null){result.putNextEntry(new ZipEntry(entry.getName()));while((count=zip.read(buffer))!=-1)result.write(buffer,0,count);result.closeEntry();}
            result.putNextEntry(new ZipEntry("word/theme/theme1.xml"));
            result.write(("<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:themeElements><a:fontScheme><a:majorFont><a:latin typeface=\"Missing unused theme font\"/></a:majorFont></a:fontScheme></a:themeElements></a:theme>").getBytes(StandardCharsets.UTF_8));result.closeEntry();
        }
        DraftPdfConversionResult converted=new DraftPdfConverter(fixture("success",10000)).convert(out.toByteArray());
        Map<String,Object> fonts=(Map<String,Object>)converted.getManifest().get("fontCapability");
        assertEquals(Collections.singleton("Times New Roman"),fonts.get("activeFamilies"));assertTrue(((Set<String>)fonts.get("themeDeclarations")).contains("Missing unused theme font"));
    }
    @Test void nonDocxBytesCannotBePassedToTheLayoutEngineAsAnEditableSource() {
        DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(fixture("success",10000)).convert(new byte[]{1,2,3}));
        assertEquals(DraftPdfConversionException.Code.INVALID_DOCX,failure.getCode());
    }
    @Test void timeoutStopsOnlyItsOwnedProcessTreeAndLeavesAnUnrelatedRendererAlive() throws Exception {
        DraftPdfRenderProfile independent=fixture("unrelated",5000);Process unrelated=new ProcessBuilder(independent.getCommand()).start();
        try {
            DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(fixture("timeout-child",1800)).convert(docx("Owned renderer child")));
            assertEquals(DraftPdfConversionException.Code.CONVERSION_TIMEOUT,failure.getCode());assertTrue(unrelated.isAlive());
            Path childPid;try(java.util.stream.Stream<Path> files=Files.walk(temporary.resolve("work"))) {childPid=files.filter(p->p.getFileName().toString().equals("owned-child.pid")).findFirst().orElseThrow(()->new AssertionError("Fixture must prove a renderer child was started."));}
            long pid=Long.parseLong(new String(Files.readAllBytes(childPid),StandardCharsets.UTF_8));Class<?> handles=Class.forName("java.lang.ProcessHandle");
            Optional<?> child=(Optional<?>)handles.getMethod("of",long.class).invoke(null,pid);
            assertTrue(!child.isPresent()||!(Boolean)handles.getMethod("isAlive").invoke(child.get()),"Owned renderer child must be stopped.");
        }finally{unrelated.destroyForcibly();unrelated.waitFor(2,TimeUnit.SECONDS);}
    }
    @Test void rendererIdentificationAndConversionShareOneRequestTimeoutBudget() throws Exception {
        long started=System.nanoTime();
        DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->new DraftPdfConverter(fixture("timeout-budget",1100)).convert(docx("One deadline")));
        assertEquals(DraftPdfConversionException.Code.CONVERSION_TIMEOUT,failure.getCode());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<4000);
    }
}
