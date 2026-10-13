package com.consense.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class OerNativeDocxObservationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String CODE = OerNativeDocxObservation.sha256("test adapter source bytes".getBytes(StandardCharsets.UTF_8));

    @Test void originalCharactersControlsAndDirectEditChannelsAreExactAndEffectiveStateRemainsUnknown() throws Exception {
        String body = "<w:p><w:pPr><w:pStyle w:val=\"inheritedStrikeUnknown\"/></w:pPr><w:r><w:t xml:space=\"preserve\">  A\u00a0😀 </w:t>"
                + "<w:tab/><w:t>B</w:t><w:br w:type=\"page\"/><w:cr/><w:t>C</w:t></w:r>"
                + "<w:r><w:rPr><w:strike/></w:rPr><w:t> struck </w:t></w:r>"
                + "<w:del w:id=\"7\"><w:r><w:delText> deleted </w:delText></w:r></w:del>"
                + "<w:r><w:rPr><w:strike w:val=\"0\"/></w:rPr><w:t>not struck</w:t></w:r>"
                + "<w:r><w:instrText xml:space=\"preserve\"> DOCPROPERTY X </w:instrText><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></w:r></w:p>";
        ObjectNode observed = observe(body);
        JsonNode paragraph = first(observed, "paragraph");
        assertEquals("  A\u00a0😀 \tB\n\rC struck  deleted not struck", paragraph.path("textChannels").path("observedRaw").asText());
        assertEquals(" deleted ", paragraph.path("textChannels").path("deleted").asText());
        assertEquals(" struck ", paragraph.path("textChannels").path("directStrike").asText());
        assertEquals("  A\u00a0😀 \tB\n\rCnot struck", paragraph.path("textChannels").path("nonDeletedNonDirectStrike").asText());
        assertFalse(paragraph.path("textChannels").has("effectiveCandidate"));
        assertEquals("unknown_style_inheritance_and_visibility_not_resolved", paragraph.path("editingState").path("effectiveCandidateStatus").asText());
        assertTrue(paragraph.path("editingState").path("containsUnsupportedDisplayTokens").asBoolean());
        JsonNode firstToken = paragraph.path("tokens").get(0);
        assertEquals(7, firstToken.path("channelOffsets").path("observedRaw").path("end").asInt());
        assertEquals("literal_dom_character_data", firstToken.path("valueOrigin").asText());
        assertEquals("\t", paragraph.path("tokens").get(1).path("encodedText").asText());
        JsonNode sym = null;
        for (JsonNode token : paragraph.path("tokens")) if ("sym".equals(token.path("kind").asText())) sym = token;
        assertNotNull(sym); assertEquals("F0FC", sym.path("rawAttributes").path("{" + W + "}char").asText());
        assertEquals("", sym.path("encodedText").asText());
    }

    @Test void completeBlankMergeAndNestedInventoryDoesNotFlattenInnerTable() throws Exception {
        String body = "<w:tbl><w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"1000\"/></w:tblGrid>"
                + "<w:tr><w:trPr><w:gridBefore w:val=\"invalid\"/></w:trPr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:vMerge w:val=\"restart\"/></w:tcPr>"
                + p("outer") + "<w:tbl><w:tr><w:tc>" + p("inner") + "</w:tc></w:tr></w:tbl><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr>"
                + "<w:tr><w:tc><w:tcPr><w:vMerge/><w:hMerge w:val=\"restart\"/></w:tcPr><w:p/></w:tc></w:tr></w:tbl>";
        ObjectNode observed = observe(body); JsonNode outer = observed.path("tableInventory").get(0), inner = observed.path("tableInventory").get(1);
        assertEquals(2, observed.path("tableInventory").size()); assertEquals(2, outer.path("rows").size());
        assertEquals(2, outer.path("rows").get(0).path("cells").size());
        assertEquals(1, inner.path("rows").get(0).path("cells").size());
        JsonNode outerCell = outer.path("rows").get(0).path("cells").get(0);
        assertEquals("invalid", outer.path("rows").get(0).path("gridBeforeRaw").path("val").asText());
        assertEquals("2", outerCell.path("gridSpanRaw").path("val").asText());
        assertEquals("restart", outerCell.path("vMergeRaw").path("val").asText());
        assertTrue(outer.path("rows").get(1).path("cells").get(0).path("vMergeRaw").path("present").asBoolean());
        assertTrue(outer.path("rows").get(1).path("cells").get(0).path("vMergeRaw").path("val").isNull());
        assertEquals(outerCell.path("cellId").asText(), inner.path("parentCellId").asText());
        assertEquals(outer.path("tableId").asText(), inner.path("parentTableId").asText());
        assertEquals(inner.path("tableId").asText(), outerCell.path("nestedTableIds").get(0).asText());
        assertEquals(2, outerCell.path("childParagraphIds").size());
        assertFalse(outerCell.has("rowSpan")); assertFalse(outerCell.has("gridColumn"));
        for (JsonNode node : observed.path("nativeNodes")) if (Arrays.asList("table", "row", "cell").contains(node.path("kind").asText()))
            assertEquals("", node.path("textChannels").path("observedRaw").asText());
        assertEquals("", byId(observed, outer.path("rows").get(0).path("cells").get(1).path("cellId").asText()).path("textChannels").path("observedRaw").asText());
        assertTrue(outer.path("inventoryComplete").asBoolean());
    }

    @Test void wrappedRowsAndCellsAreObservedWithActualNamespacePathsAndParentIds() throws Exception {
        ObjectNode observed = observe("<w:tbl><w:sdt><w:sdtContent><w:tr><w:customXml><w:tc>" + p("wrapped")
                + "</w:tc></w:customXml></w:tr></w:sdtContent></w:sdt></w:tbl>");
        JsonNode table = observed.path("tableInventory").get(0), row = table.path("rows").get(0), cell = row.path("cells").get(0);
        JsonNode cn = byId(observed, cell.path("cellId").asText());
        assertEquals(table.path("tableId").asText(), cn.path("parentTableId").asText());
        assertEquals(row.path("rowId").asText(), cn.path("parentRowId").asText());
        assertTrue(cn.path("sourcePosition").path("nodePath").asText().contains("}sdtContent[1]"));
        assertTrue(cn.path("sourcePosition").path("nodePath").asText().contains("}customXml[1]"));
        assertTrue(cn.path("limitations").toString().contains("content_control_semantics_unresolved"));
    }

    @Test void everyStoryUsesActualPartIdentityAndUnsupportedStoryRetainsBytes() throws Exception {
        Map<String, byte[]> extra = new LinkedHashMap<>();
        extra.put("word/oddHeader7.xml", xml("hdr", p("header exact")));
        extra.put("word/footerDifferent.xml", xml("ftr", p("footer exact")));
        extra.put("word/footnotes.xml", xml("footnotes", "<w:footnote w:id=\"43\">" + p("footnote exact") + "</w:footnote>"));
        extra.put("word/endnotes.xml", xml("endnotes", "<w:endnote w:id=\"61\">" + p("endnote exact") + "</w:endnote>"));
        extra.put("word/comments.xml", xml("comments", "<w:comment w:id=\"0\">" + p("comment raw only") + "</w:comment>"));
        byte[] bytes = packageBytes(p("body exact"), extra);
        ObjectNode observed = OerNativeDocxObservation.observe("actual.docx", bytes, CODE);
        assertEquals(6, observed.path("storyInventory").size()); assertEquals(1, observed.path("unsupportedStories").size());
        Set<String> seen = new HashSet<>();
        for (JsonNode node : observed.path("nativeNodes")) if ("paragraph".equals(node.path("kind").asText())) seen.add(node.path("sourcePosition").path("partName").asText());
        assertEquals(new HashSet<>(Arrays.asList("word/document.xml", "word/oddHeader7.xml", "word/footerDifferent.xml", "word/footnotes.xml", "word/endnotes.xml")), seen);
        assertEquals("word/comments.xml", observed.path("unsupportedStories").get(0).path("partName").asText());
        for (JsonNode part : observed.path("rawParts")) if (extra.containsKey(part.path("name").asText()))
            assertArrayEquals(extra.get(part.path("name").asText()), Base64.getDecoder().decode(part.path("bytesBase64").asText()));
    }

    @Test void packagePartsNodeIdsAndOrdersAreRepeatableAndDoNotAcquirePdfAliases() throws Exception {
        byte[] bytes = packageBytes(p("unchanged😀"), new LinkedHashMap<>());
        ObjectNode first = OerNativeDocxObservation.observe("file.docx", bytes, CODE), second = OerNativeDocxObservation.observe("file.docx", bytes, CODE);
        assertEquals(first, second); assertArrayEquals(bytes, Base64.getDecoder().decode(first.path("sourcePackage").path("bytesBase64").asText()));
        Map<String, byte[]> raw = unzip(bytes); assertEquals(raw.size(), first.path("rawParts").size());
        for (JsonNode part : first.path("rawParts")) {
            byte[] actual = raw.get(part.path("name").asText()); assertNotNull(actual);
            assertArrayEquals(actual, Base64.getDecoder().decode(part.path("bytesBase64").asText()));
            assertEquals(OerNativeDocxObservation.sha256(actual), part.path("sha256").asText());
        }
        long previous = 0; Set<String> ids = new HashSet<>();
        for (JsonNode node : first.path("nativeNodes")) {
            assertTrue(node.path("sourceOrder").asLong() > previous); previous = node.path("sourceOrder").asLong();
            assertTrue(ids.add(node.path("nodeId").asText())); JsonNode pos = node.path("sourcePosition");
            assertEquals(OerNativeDocxObservation.nodeId(node.path("kind").asText(), pos.path("sourceSha256").asText(), pos.path("partName").asText(), pos.path("partSha256").asText(), pos.path("nodePath").asText()), node.path("nodeId").asText());
            for (String alias : Arrays.asList("pageNo", "physicalPageNo", "bbox")) { assertFalse(node.has(alias)); assertFalse(pos.has(alias)); }
        }
        assertEquals(OerNativeDocxObservation.sha256(OerNativeDocxObservation.canonicalBytes(first.path("observerIdentity").path("config"))), first.path("observerIdentity").path("configSha256").asText());
        assertFalse(first.path("quality").path("humanConfirmed").asBoolean());
        assertEquals("not_performed", first.path("quality").path("canonicalAdoption").asText());
    }

    @Test void sourceOrActualAdapterChangeProducesNewRevisionAndRejectsUnboundIdentity() throws Exception {
        byte[] original = packageBytes(p("original"), new LinkedHashMap<>()), changed = packageBytes(p("changed"), new LinkedHashMap<>());
        ObjectNode a = OerNativeDocxObservation.observe("file.docx", original, CODE), b = OerNativeDocxObservation.observe("file.docx", changed, CODE);
        ObjectNode c = OerNativeDocxObservation.observe("file.docx", original, OerNativeDocxObservation.sha256(new byte[]{4, 5}));
        assertNotEquals(a.path("source").path("sourceRevisionSha256"), b.path("source").path("sourceRevisionSha256"));
        assertNotEquals(a.path("source").path("sourceRevisionSha256"), c.path("source").path("sourceRevisionSha256"));
        assertThrows(IllegalArgumentException.class, () -> OerNativeDocxObservation.observe("file.docx", original, null));
    }

    @Test void alternateContentAndInvalidStrikeRemainExplicitlyUnknownWithoutChoosingBranch() throws Exception {
        ObjectNode observed = observe("<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"><mc:Choice Requires=\"w\">"
                + p("choice") + "</mc:Choice><mc:Fallback>" + p("fallback") + "</mc:Fallback></mc:AlternateContent>"
                + "<w:p><w:r><w:rPr><w:strike w:val=\"invalid\"/></w:rPr><w:t>state unknown</w:t></w:r></w:p>");
        List<JsonNode> paragraphs = kinds(observed, "paragraph"); assertEquals(3, paragraphs.size());
        assertTrue(paragraphs.get(0).path("limitations").toString().contains("alternate_content_branch_unresolved"));
        assertTrue(paragraphs.get(1).path("limitations").toString().contains("alternate_content_branch_unresolved"));
        assertEquals("unknown_invalid_value", paragraphs.get(2).path("tokens").get(0).path("directStrikeProperty").path("interpretation").asText());
        assertEquals("unknown_style_inheritance_and_visibility_not_resolved", paragraphs.get(2).path("editingState").path("effectiveCandidateStatus").asText());
    }

    @Test void xmlDoctypeIsRejectedWithoutExternalResourceFetch() throws Exception {
        byte[] bytes = packageBytes(p("text"), new LinkedHashMap<>());
        Map<String, byte[]> parts = unzip(bytes);
        String raw = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        parts.put("word/document.xml", raw.replace("<w:document", "<!DOCTYPE document [<!ENTITY outside SYSTEM \"file:///definitely-unavailable-conSense-test.txt\">]><w:document").getBytes(StandardCharsets.UTF_8));
        byte[] unsafe = zip(parts);
        Exception error = assertThrows(Exception.class, () -> OerNativeDocxObservation.observe("file.docx", unsafe, CODE));
        assertTrue(error.getMessage().contains("DOCTYPE"));
    }

    @Test void highlyCompressibleRawEmfIsRetainedWithoutChangingGlobalPoiRatio() throws Exception {
        Map<String, byte[]> parts = unzip(packageBytes(p("image reference only"), new LinkedHashMap<>()));
        byte[] compressedMedia = new byte[2 * 1024 * 1024];
        parts.put("word/media/highlyCompressible.emf", compressedMedia);
        String types = new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8);
        parts.put("[Content_Types].xml", types.replace("</Types>", "<Override PartName=\"/word/media/highlyCompressible.emf\" ContentType=\"image/x-emf\"/></Types>").getBytes(StandardCharsets.UTF_8));
        double before = ZipSecureFile.getMinInflateRatio();
        ObjectNode observed = OerNativeDocxObservation.observe("media.docx", zip(parts), CODE);
        assertEquals(before, ZipSecureFile.getMinInflateRatio());
        JsonNode captured = null;
        for (JsonNode part : observed.path("rawParts")) if ("word/media/highlyCompressible.emf".equals(part.path("name").asText())) captured = part;
        assertNotNull(captured); assertEquals("image/x-emf", captured.path("mediaType").asText());
        assertArrayEquals(compressedMedia, Base64.getDecoder().decode(captured.path("bytesBase64").asText()));
    }

    @Test void byteIdenticalStoryPartsHaveDistinctNativeIdsThroughExactPartName() throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        byte[] identical = xml("hdr", p("same header bytes"));
        parts.put("word/oddHeader7.xml", identical); parts.put("word/oddHeader9.xml", identical);
        ObjectNode observed = OerNativeDocxObservation.observe("same-stories.docx", packageBytes(p("body"), parts), CODE);
        Set<String> allIds = new HashSet<>();
        List<JsonNode> headerParas = new ArrayList<>();
        for (JsonNode node : observed.path("nativeNodes")) {
            assertTrue(allIds.add(node.path("nodeId").asText()));
            if ("paragraph".equals(node.path("kind").asText()) && node.path("sourcePosition").path("partName").asText().contains("Header")) headerParas.add(node);
        }
        assertEquals(2, headerParas.size());
        assertEquals(headerParas.get(0).path("sourcePosition").path("partSha256"), headerParas.get(1).path("sourcePosition").path("partSha256"));
        assertEquals(headerParas.get(0).path("sourcePosition").path("nodePath"), headerParas.get(1).path("sourcePosition").path("nodePath"));
        assertNotEquals(headerParas.get(0).path("nodeId"), headerParas.get(1).path("nodeId"));
    }

    private static ObjectNode observe(String body) throws Exception { return OerNativeDocxObservation.observe("fixture.docx", packageBytes(body, new LinkedHashMap<>()), CODE); }
    private static String p(String text) { return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>"; }
    private static byte[] xml(String root, String body) { return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:" + root + " xmlns:w=\"" + W + "\">" + body + "</w:" + root + ">").getBytes(StandardCharsets.UTF_8); }
    private static byte[] packageBytes(String body, Map<String, byte[]> extras) throws Exception {
        ByteArrayOutputStream seed = new ByteArrayOutputStream(); try (XWPFDocument doc = new XWPFDocument()) { doc.createParagraph(); doc.write(seed); }
        Map<String, byte[]> entries = unzip(seed.toByteArray());
        String doc = new String(entries.get("word/document.xml"), StandardCharsets.UTF_8);
        entries.put("word/document.xml", doc.replaceAll("(?s)<w:body>.*?</w:body>", "<w:body>" + body + "</w:body>").getBytes(StandardCharsets.UTF_8));
        String types = new String(entries.get("[Content_Types].xml"), StandardCharsets.UTF_8);
        for (Map.Entry<String, byte[]> extra : extras.entrySet()) {
            entries.put(extra.getKey(), extra.getValue());
            String type = extra.getKey().contains("Header") ? "header" : extra.getKey().contains("footer") ? "footer"
                    : extra.getKey().contains("footnotes") ? "footnotes" : extra.getKey().contains("endnotes") ? "endnotes" : "comments";
            types = types.replace("</Types>", "<Override PartName=\"/" + extra.getKey() + "\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml." + type + "+xml\"/></Types>");
        }
        entries.put("[Content_Types].xml", types.getBytes(StandardCharsets.UTF_8)); return zip(entries);
    }
    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int count;
                while ((count = input.read(buf)) != -1) out.write(buf, 0, count); parts.put(entry.getName(), out.toByteArray());
            }
        } return parts;
    }
    private static byte[] zip(Map<String, byte[]> parts) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) { for (Map.Entry<String, byte[]> part : parts.entrySet()) { zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue()); zip.closeEntry(); } }
        return output.toByteArray();
    }
    private static JsonNode first(JsonNode observed, String kind) { return kinds(observed, kind).get(0); }
    private static List<JsonNode> kinds(JsonNode observed, String kind) {
        List<JsonNode> out = new ArrayList<>(); for (JsonNode node : observed.path("nativeNodes")) if (kind.equals(node.path("kind").asText())) out.add(node); return out;
    }
    private static JsonNode byId(JsonNode observed, String id) { for (JsonNode node : observed.path("nativeNodes")) if (id.equals(node.path("nodeId").asText())) return node; throw new AssertionError("node missing: " + id); }
}
