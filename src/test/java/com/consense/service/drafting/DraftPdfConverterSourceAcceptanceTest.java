package com.consense.service.drafting;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.consense.common.JsonUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual installed renderer and actual competition packages; no test gateway/renderer double. */
class DraftPdfConverterSourceAcceptanceTest {
    @Test void allThreeActualCompetitionDocxPackagesConvertWithoutChangingTheirOriginalBytes() throws Exception {
        String executable=System.getProperty("consense.converter.executable"),sourceDir=System.getProperty("consense.acceptance.sourceDir");
        assumeTrue(executable!=null&&sourceDir!=null,"Enable with explicit renderer executable and actual competition source directory.");
        Path evidence=Paths.get(System.getProperty("consense.converter.evidenceDir","target/draft-pdf-source-acceptance"));Files.createDirectories(evidence);
        DraftPdfRenderProfile profile=DraftPdfRenderProfile.libreOffice(Paths.get(executable),evidence.resolve("work"),120000);
        DraftPdfConverter converter=new DraftPdfConverter(profile);
        List<String> files=Arrays.asList("01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx");
        List<Map<String,Object>> manifests=new ArrayList<>();
        for(String name:files) {
            Path original=Paths.get(sourceDir).resolve(name);byte[] before=Files.readAllBytes(original);String key=name.contains("NTT")?"NTT":name.contains("SCT")?"SCT":"SCC";
            DraftPdfConversionResult result=converter.convert(before);assertArrayEquals(before,Files.readAllBytes(original));assertEquals(DraftPdfConverter.sha256(before),result.getDocxSha256());
            assertTrue(result.getRendererVersion().startsWith("LibreOffice "));assertEquals("UNVERIFIED",result.getFieldStatus());
            try(PDDocument document=PDDocument.load(result.getPdfBytes())) {
                assertTrue(document.getNumberOfPages()>0);assertTrue(new PDFTextStripper().getText(document).length()>1000);
            }
            Files.write(evidence.resolve(key+".pdf"),result.getPdfBytes());Map<String,Object> manifest=new LinkedHashMap<>(result.getManifest());manifest.put("sourceFile",original.toString());manifests.add(manifest);
            Files.write(evidence.resolve(key+"-manifest.json"),JsonUtils.write(manifest).getBytes(StandardCharsets.UTF_8));
        }
        Files.write(evidence.resolve("all-source-manifests.json"),JsonUtils.write(manifests).getBytes(StandardCharsets.UTF_8));
    }
}
