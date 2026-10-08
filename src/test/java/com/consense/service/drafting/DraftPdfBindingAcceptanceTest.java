package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real bookmarked competition document through the public rendering seam. */
class DraftPdfBindingAcceptanceTest {
    @Test void savedParagraphBookmarksResolveInsideTheExactRenderedArtifact() throws Exception {
        String executable = System.getProperty("consense.converter.executable");
        String boundDocx = System.getProperty("consense.converter.boundDocx");
        assumeTrue(executable != null && boundDocx != null, "Requires an explicit renderer and actual bookmarked template.");
        Path input = Paths.get(boundDocx);
        byte[] before = Files.readAllBytes(input);
        List<String> names = bookmarkNames(before);
        assertTrue(names.size() > 2, "Pilot must cover multiple actual paragraphs.");
        Path evidence = Paths.get(System.getProperty("consense.converter.evidenceDir", "target/pdf-binding-acceptance"));
        Files.createDirectories(evidence);
        Path work = Paths.get(System.getProperty("consense.converter.workDir", evidence.resolve("work").toString()));
        DraftPdfConverter converter = new DraftPdfConverter(DraftPdfRenderProfile.libreOffice(Paths.get(executable), work, 120000));
        DraftPdfConversionResult result = converter.convert(before);
        Files.write(evidence.resolve("bound.pdf"), result.getPdfBytes(), StandardOpenOption.CREATE_NEW);
        Files.write(evidence.resolve("manifest.json"), JsonUtils.write(result.getManifest()).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        assertArrayEquals(before, Files.readAllBytes(input), "Renderer must not change the saved DOCX.");
        assertEquals(DraftPdfConverter.sha256(before), result.getDocxSha256());
        List<Map<String,Object>> locations = new ArrayList<>();
        try (PDDocument pdf = PDDocument.load(result.getPdfBytes())) {
            for (String name : names) {
                PDPageDestination destination = pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination(name));
                assertNotNull(destination, "Saved paragraph has no PDF destination: " + name);
                int page = destination.retrievePageNumber();
                assertTrue(page >= 0 && page < pdf.getNumberOfPages(), "Destination must reference a physical page.");
                assertTrue(destination instanceof PDPageXYZDestination, "An exact insertion anchor requires point coordinates.");
                PDPageXYZDestination point = (PDPageXYZDestination) destination;
                assertTrue(point.getTop() >= 0 && point.getTop() <= pdf.getPage(page).getMediaBox().getHeight());
                assertTrue(point.getLeft() >= 0 && point.getLeft() <= pdf.getPage(page).getMediaBox().getWidth());
                Map<String,Object> location = new LinkedHashMap<>();
                location.put("bindingId", name); location.put("page", page + 1); location.put("left", point.getLeft()); location.put("top", point.getTop());
                locations.add(location);
            }
        }
        Files.write(evidence.resolve("locations.json"), JsonUtils.write(locations).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }

    private static List<String> bookmarkNames(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(entry.getName())) continue;
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                Document xml = factory.newDocumentBuilder().parse(zip);
                NodeList markers = xml.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "bookmarkStart");
                List<String> names = new ArrayList<>();
                for (int index = 0; index < markers.getLength(); index++) {
                    String name = ((Element) markers.item(index)).getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "name");
                    if (name.matches("CS[a-f0-9]{28}")) names.add(name);
                }
                return names;
            }
        }
        throw new AssertionError("Actual DOCX has no main document.");
    }
}
