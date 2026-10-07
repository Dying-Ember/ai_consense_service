package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Fixed six real snapshots; standalone converter seam, not a semantic or Word-layout oracle. */
class DraftDesktopPdfAcceptanceTest {
    @ParameterizedTest @CsvSource({"NTT-source,11","NTT-draft,11","SCT-source,27","SCT-draft,22","SCC-source,81","SCC-draft,78"})
    void sixFrozenOriginalAndSavedPackagesRemainImmutableThroughExplicitFreeConversion(String name,int observedPages)throws Exception {
        String sources=System.getProperty("consense.acceptance.bindingSourceDir"),exe=System.getProperty("consense.acceptance.desktopExecutable"),assets=System.getProperty("consense.acceptance.desktopAssetRoot"),fonts=System.getProperty("consense.acceptance.desktopFontCache"),root=System.getProperty("consense.acceptance.desktopEvidence");
        assumeTrue(sources!=null&&exe!=null&&assets!=null&&fonts!=null&&root!=null,"Explicit frozen snapshots/free runtime/evidence root required");
        Path evidence=Files.createDirectories(Paths.get(root,"frozen-six",name));byte[] input=Files.readAllBytes(Paths.get(sources,name+".docx")),before=input.clone();
        DraftPdfConverter converter=new DraftPdfConverter(DraftPdfRenderProfile.desktopX2t(Paths.get(exe),evidence.resolve("renderer"),120000,Paths.get(assets),Paths.get(fonts)));long started=System.nanoTime();String profile=converter.currentProfileHash();DraftPdfConversionResult result=converter.convert(input);
        assertArrayEquals(before,input);assertArrayEquals(before,Files.readAllBytes(Paths.get(sources,name+".docx")));assertEquals(DraftPdfConverter.sha256(before),result.getDocxSha256());assertEquals(profile,result.getRenderProfileHash());assertEquals(DraftPdfConverter.sha256(result.getPdfBytes()),result.getPdfSha256());assertEquals("9.4.0.129",result.getRendererVersion());
        try(PDDocument pdf=PDDocument.load(result.getPdfBytes())){assertEquals(observedPages,pdf.getNumberOfPages(),"Frozen observed counts are a regression check, not layout acceptance");for(PDPage page:pdf.getPages())for(PDAnnotation annotation:page.getAnnotations())if(annotation instanceof PDAnnotationLink&&((PDAnnotationLink)annotation).getAction() instanceof PDActionURI)assertFalse(((PDActionURI)((PDAnnotationLink)annotation).getAction()).getURI().toLowerCase(Locale.ROOT).startsWith("consense-binding:"));}
        Files.write(evidence.resolve(name+".pdf"),result.getPdfBytes());Files.write(evidence.resolve("manifest.json"),JsonUtils.write(result.getManifest()).getBytes(StandardCharsets.UTF_8));Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("docxSha256",result.getDocxSha256());receipt.put("pdfSha256",result.getPdfSha256());receipt.put("profile",profile);receipt.put("elapsedMillis",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));receipt.put("sourceUnchanged",true);receipt.put("pages",observedPages);Files.write(evidence.resolve("receipt.json"),JsonUtils.write(receipt).getBytes(StandardCharsets.UTF_8));
    }
}
