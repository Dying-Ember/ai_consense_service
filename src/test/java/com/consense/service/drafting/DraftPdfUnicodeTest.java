package com.consense.service.drafting;

import com.consense.domain.Project;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftPdfUnicodeTest {
    @Test void wrapsEnglishAtWordBoundaries() throws Exception {
        Project project = new Project(); project.setId("word-wrap");
        String sentence = "The contractor shall coordinate the construction programme and maintain communication with the Engineer throughout the development.";
        byte[] pdf = new DraftDocPdfWriter().write(project, "NTT", "Notes", sentence);
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertTrue(text.contains(sentence), text);
        }
    }
    @Test void exportsAccentedLatinAlongsideChineseWithoutLosingTheLine() throws Exception {
        Project project = new Project();
        project.setId("pdf-unicode");
        project.setNameZhHans("测试项目");
        byte[] pdf = new DraftDocPdfWriter().write(project, "NTT", "Notes to Tenderers", "Façade works — café.\nContract 20250101.");
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Façade works"));
            assertTrue(text.contains("café"));
            assertTrue(text.contains("20250101"));
        }
    }
    @Test void switchesBetweenChineseAndLatinFontsWithinOneLine() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(new java.io.File("C:/Windows/Fonts/simhei.ttf").exists());
        Project project = new Project(); project.setId("mixed-fonts");
        byte[] pdf = new DraftDocPdfWriter().write(project, "NTT", "Notes", "外墙工程：Façade works — café.");
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("外墙工程"));
            assertTrue(text.contains("Façade works"));
            assertTrue(text.contains("café"));
        }
    }
}
