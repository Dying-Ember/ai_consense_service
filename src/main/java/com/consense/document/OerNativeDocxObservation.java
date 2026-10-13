package com.consense.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.Version;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Opt-in OOXML observation, separate from normalized legacy parsing and canonical adoption.
 * Exact package/part bytes are retained. No Word page, glyph, effective edit state or clause
 * relationship is inferred. Paragraph/run channels overlap by design; callers must adopt an
 * explicit node/channel, never concatenate both or flatten nested tables into outer cells.
 */
public final class OerNativeDocxObservation {
    public static final String SCHEMA = "consense.native-observation.v1";
    public static final String VERSION = "ooxml-observation-2";
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String STRICT_W = "http://purl.oclc.org/ooxml/wordprocessingml/main";
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String PATH_CACHE_KEY = "consense.native.observation.expanded.path";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PACKAGE_BYTES = 128 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_PART_BYTES = 512L * 1024 * 1024;
    private static final int MAX_PARTS = 100000;
    private final String sourceSha;
    private final ArrayNode nodes = JSON.createArrayNode();
    private final ArrayNode tables = JSON.createArrayNode();
    private final Map<Element, ObjectNode> emitted = new HashMap<>();
    private long order;

    private OerNativeDocxObservation(String sourceSha) { this.sourceSha = sourceSha; }

    /** Caller supplies the actual adapter source SHA; CLI verifies it from the actual file bytes. */
    public static ObjectNode observe(String sourceFilename, byte[] sourceBytes, String adapterCodeSha256) throws Exception {
        if (sourceFilename == null || sourceFilename.isEmpty() || sourceBytes == null || sourceBytes.length == 0)
            throw new IllegalArgumentException("An explicit filename and actual DOCX package bytes are required");
        if (sourceBytes.length > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("DOCX observation package exceeds explicit byte budget");
        if (adapterCodeSha256 == null || !adapterCodeSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Actual adapter source SHA256 is required");
        String sourceSha = sha256(sourceBytes);
        Map<String, byte[]> entries = zipEntries(sourceBytes);
        Map<String, String> types = new HashMap<>();
        ArrayNode relationships = JSON.createArrayNode();
        // OPC validates the package and identifies actual content types and relationship IDs.
        // All raw bytes below come from the original ZIP, never OPC serialization or save().
        // InputStream mode eagerly inflates every media item through POI's ratio guard, including
        // legitimate highly compressible EMF. READ file mode observes package metadata only.
        // The private temporary package is the exact supplied bytes, not an edited source or
        // a change to POI's process-wide ZipSecureFile settings.
        Path temporaryPackage = Files.createTempFile("consense-ooxml-observation-", ".docx");
        try {
            Files.write(temporaryPackage, sourceBytes);
            try (OPCPackage opc = OPCPackage.open(temporaryPackage.toFile(), PackageAccess.READ)) {
            for (PackagePart part : opc.getParts()) {
                String name = withoutSlash(part.getPartName().getName());
                types.put(name, part.getContentType());
                if (!part.isRelationshipPart()) {
                    for (PackageRelationship rel : part.getRelationships())
                        relationships.add(relationship(name, rel));
                }
            }
            for (PackageRelationship rel : opc.getRelationships())
                relationships.add(relationship("/", rel));
            }
        } finally {
            Files.deleteIfExists(temporaryPackage);
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("schema", SCHEMA); out.put("producerKind", "native_docx");
        ObjectNode config = JSON.createObjectNode();
        config.put("partOrdering", "main_document_then_lexicographic_package_part");
        config.put("pathConvention", "namespace_clark_name_with_same_expanded_name_sibling_index_1_based");
        config.put("textConvention", "decoded_dom_character_data_and_explicit_ooxml_control_tokens_no_normalization");
        config.put("textOffsetConvention", "utf16_code_units_per_channel_start_inclusive_end_exclusive");
        config.put("tableTextConvention", "structural_nodes_empty_channels_paragraph_run_overlap_explicit_adoption_required");
        config.put("nodeIdConvention", "native_sha256_consense.native-node.v1_lf_kind_sourceSha_partName_partSha_nodePath");
        config.put("externalResources", "never_fetched");
        config.put("xmlDtdAndEntities", "disallowed");
        config.put("opcMode", "read_only_exact_private_temporary_package_metadata_only");
        config.put("maxPackageBytes", MAX_PACKAGE_BYTES); config.put("maxExpandedBytes", MAX_EXPANDED_BYTES);
        config.put("maxPartBytes", MAX_PART_BYTES); config.put("maxParts", MAX_PARTS);
        ObjectNode identity = JSON.createObjectNode();
        identity.put("version", VERSION); identity.put("adapterCodeSha256", adapterCodeSha256);
        ObjectNode deps = identity.putObject("dependencyVersions");
        deps.put("apachePoi", Version.getVersion()); deps.put("jacksonDatabind", JSON.version().toFullString());
        deps.put("javaRuntime", System.getProperty("java.runtime.version"));
        deps.put("xmlParserFactory", secureXmlFactory().getClass().getName());
        identity.set("config", config); identity.put("configSha256", sha256(canonicalBytes(config)));
        out.set("observerIdentity", identity);
        ObjectNode revisionInput = JSON.createObjectNode(); revisionInput.put("sourceSha256", sourceSha);
        revisionInput.set("observerIdentity", identity.deepCopy());
        ObjectNode source = out.putObject("source");
        source.put("sourceFilename", sourceFilename); source.put("sourceSha256", sourceSha);
        source.put("sourceRevisionSha256", sha256(canonicalBytes(revisionInput)));
        source.put("mediaType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        out.set("sourcePackage", artifact(sourceFilename, source.path("mediaType").asText(), sourceBytes));
        ArrayNode rawParts = out.putArray("rawParts");
        ArrayNode stories = out.putArray("storyInventory");
        ArrayNode unsupported = out.putArray("unsupportedStories");
        OerNativeDocxObservation observation = new OerNativeDocxObservation(sourceSha);
        List<String> names = new ArrayList<>(entries.keySet());
        Collections.sort(names, Comparator.comparing((String n) -> !"word/document.xml".equals(n)).thenComparing(n -> n));
        boolean mainFound = false;
        for (String name : names) {
            byte[] bytes = entries.get(name);
            String type = types.get(name);
            if (type == null) type = name.endsWith(".rels") ? "application/vnd.openxmlformats-package.relationships+xml"
                    : "[Content_Types].xml".equals(name) ? "application/xml" : "application/octet-stream";
            rawParts.add(artifact(name, type, bytes));
            if (!isStoryContentType(type)) continue;
            ObjectNode story = JSON.createObjectNode();
            story.put("partName", name); story.put("partSha256", sha256(bytes)); story.put("mediaType", type);
            Document dom = parseXml(bytes);
            Element root = dom.getDocumentElement();
            cachePaths(root, "/" + expandedName(root) + "[1]");
            String storyKind = storyKind(root);
            story.put("rootExpandedName", expandedName(root)); story.put("storyKind", storyKind);
            boolean supported = Arrays.asList("document", "hdr", "ftr", "footnotes", "endnotes").contains(storyKind);
            story.put("observationStatus", supported ? "observed_native_structure_unreviewed" : "unsupported_story_raw_preserved");
            story.put("semanticQuality", "unknown");
            stories.add(story);
            if (supported) {
                if ("document".equals(storyKind)) mainFound = true;
                long before = observation.nodes.size();
                observation.walk(root, name, story.path("partSha256").asText(), null);
                story.put("nativeNodeCount", observation.nodes.size() - before);
            } else unsupported.add(story.deepCopy());
        }
        if (!mainFound) throw new IllegalArgumentException("Package has no supported Word main document story");
        observation.finishTables();
        out.set("nativeNodes", observation.nodes); out.set("tableInventory", observation.tables);
        out.set("relationshipInventory", relationships);
        ObjectNode quality = out.putObject("quality");
        quality.put("semanticQuality", "unknown"); quality.put("humanConfirmed", false);
        quality.put("canonicalAdoption", "not_performed");
        quality.put("originalGeometryStatus", "not_defined_for_native_docx");
        quality.put("effectiveRenderingStatus", "unknown");
        ArrayNode limitations = quality.putArray("limitations");
        for (String value : Arrays.asList("no_original_word_physical_pages_or_bbox", "style_inheritance_and_effective_hidden_or_strike_state_unresolved",
                "auto_numbering_and_symbol_font_glyphs_unresolved", "fields_alternate_content_and_content_controls_unresolved",
                "paragraph_and_run_channels_overlap_do_not_concatenate", "story_relationship_applicability_and_reading_order_unreviewed",
                "merge_properties_observed_no_inferred_grid_or_rowspan", "rendition_assets_and_native_to_visual_mapping_not_produced")) limitations.add(value);
        return out;
    }

    /** Standalone local export; output is CREATE_NEW so old/failed evidence is never overwritten. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: source.docx fresh-output.json actual-adapter-source.java");
        Path source = Paths.get(args[0]), target = Paths.get(args[1]), code = Paths.get(args[2]);
        ObjectNode value = observe(source.getFileName().toString(), Files.readAllBytes(source), sha256(Files.readAllBytes(code)));
        try (java.io.OutputStream output = new BufferedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
            JSON.writeValue(output, value);
        }
        System.out.println(target.toAbsolutePath());
    }

    private void walk(Element element, String part, String partSha, String parentNodeId) {
        String kind = nativeKind(element);
        String thisParent = parentNodeId;
        if (kind != null) {
            ObjectNode node = JSON.createObjectNode();
            String id = nodeId(kind, sourceSha, part, partSha, path(element));
            node.put("nodeId", id); node.put("kind", kind); node.put("sourceOrder", ++order);
            node.put("xmlLocalName", element.getLocalName()); node.put("xmlNamespaceUri", element.getNamespaceURI());
            node.set("sourcePosition", position(element, part, partSha));
            if (parentNodeId != null) node.put("parentNativeNodeId", parentNodeId);
            node.put("textOwnership", ("paragraph".equals(kind) || "run".equals(kind)) ? "own_paragraph_or_run_tokens" : "structural_no_direct_text");
            node.put("originalGeometryStatus", "not_defined_for_native_docx");
            node.set("rawAttributes", attributes(element));
            ObjectNode properties = node.putObject("rawProperties");
            for (Element child : children(element))
                if (isW(child) && Arrays.asList("pPr", "rPr", "tcPr", "trPr", "tblPr", "tblGrid").contains(child.getLocalName()))
                    properties.set(expandedName(child), domEvidence(child));
            ArrayNode nodeLimitations = node.putArray("limitations");
            nodeLimitations.add("effective_rendering_and_inherited_style_state_unknown");
            if (ancestor(element, MC, "AlternateContent") != null) nodeLimitations.add("alternate_content_branch_unresolved");
            if (ancestorW(element, "sdt") != null) nodeLimitations.add("content_control_semantics_unresolved");
            if ("paragraph".equals(kind) || "run".equals(kind)) addText(element, node, part, partSha);
            else emptyText(node);
            emitted.put(element, node); nodes.add(node); thisParent = id;
        }
        for (Element child : children(element)) walk(child, part, partSha, thisParent);
    }

    private void addText(Element owner, ObjectNode output, String part, String partSha) {
        List<Element> tokenElements = new ArrayList<>();
        collectTokenElements(owner, owner, tokenElements);
        StringBuilder raw = new StringBuilder(), deleted = new StringBuilder(), struck = new StringBuilder(), candidate = new StringBuilder();
        ArrayNode tokens = output.putArray("tokens");
        boolean unsupported = false;
        for (Element element : tokenElements) {
            String local = element.getLocalName();
            String text = tokenText(element);
            boolean removed = ancestorW(element, "del") != null || ancestorW(element, "moveFrom") != null
                    || "delText".equals(local) || "delInstrText".equals(local);
            Element run = ancestorW(element, "r");
            ObjectNode strikeProperty = directToggle(run, "strike"), doubleStrikeProperty = directToggle(run, "dstrike");
            boolean directStruck = "true".equals(strikeProperty.path("interpretation").asText())
                    || "true".equals(doubleStrikeProperty.path("interpretation").asText());
            boolean field = "instrText".equals(local) || "delInstrText".equals(local);
            // Instructions are observed separately, not invented as displayed native text.
            String channelText = field ? "" : text;
            ObjectNode token = JSON.createObjectNode();
            token.put("kind", local); token.put("rawDomText", element.getTextContent());
            token.put("encodedText", channelText);
            token.put("valueOrigin", Arrays.asList("t", "delText", "instrText", "delInstrText").contains(local)
                    ? "literal_dom_character_data" : "explicit_ooxml_control_encoding");
            token.set("sourcePosition", position(element, part, partSha));
            token.set("rawAttributes", attributes(element)); token.put("deletedOrMovedFrom", removed);
            token.put("directStruck", directStruck); token.set("directStrikeProperty", strikeProperty);
            token.set("directDoubleStrikeProperty", doubleStrikeProperty);
            token.put("effectiveRenderingStatus", "unknown");
            ObjectNode offsets = token.putObject("channelOffsets");
            offsets.set("observedRaw", offset(raw.length(), raw.length() + channelText.length(), true));
            offsets.set("deleted", offset(deleted.length(), deleted.length() + (removed ? channelText.length() : 0), removed));
            offsets.set("directStrike", offset(struck.length(), struck.length() + (directStruck ? channelText.length() : 0), directStruck));
            boolean includeCandidate = !removed && !directStruck;
            offsets.set("nonDeletedNonDirectStrike", offset(candidate.length(), candidate.length() + (includeCandidate ? channelText.length() : 0), includeCandidate));
            raw.append(channelText); if (removed) deleted.append(channelText); if (directStruck) struck.append(channelText);
            if (includeCandidate) candidate.append(channelText);
            if (field) { token.put("fieldInstruction", text); unsupported = true; }
            if (Arrays.asList("sym", "drawing", "object", "pict", "fldChar", "footnoteReference", "endnoteReference",
                    "commentReference", "annotationRef", "separator", "continuationSeparator", "lastRenderedPageBreak").contains(local)) {
                token.put("displayInterpretation", "unknown_raw_token_only"); unsupported = true;
            }
            tokens.add(token);
        }
        ObjectNode channels = output.putObject("textChannels");
        channels.put("observedRaw", raw.toString()); channels.put("deleted", deleted.toString());
        channels.put("directStrike", struck.toString()); channels.put("nonDeletedNonDirectStrike", candidate.toString());
        ObjectNode editing = output.putObject("editingState");
        editing.put("effectiveCandidateStatus", "unknown_style_inheritance_and_visibility_not_resolved");
        editing.put("directChannelsStatus", "observed_not_rendered_effective_text");
        editing.put("containsUnsupportedDisplayTokens", unsupported);
        output.put("semanticQuality", "unknown");
    }

    private static void emptyText(ObjectNode node) {
        ObjectNode channels = node.putObject("textChannels");
        for (String name : Arrays.asList("observedRaw", "deleted", "directStrike", "nonDeletedNonDirectStrike")) channels.put(name, "");
        node.putArray("tokens"); node.putObject("editingState").put("effectiveCandidateStatus", "not_applicable_structural_node_no_direct_text");
        node.put("semanticQuality", "unknown");
    }

    private void finishTables() {
        for (Map.Entry<Element, ObjectNode> pair : emitted.entrySet()) {
            Element element = pair.getKey(); ObjectNode node = pair.getValue();
            Element parentCell = ancestorW(element, "tc"), parentTable = ancestorW(element, "tbl");
            if (parentCell != null && emitted.containsKey(parentCell)) node.put("parentCellId", emitted.get(parentCell).path("nodeId").asText());
            if (parentTable != null && emitted.containsKey(parentTable)) {
                node.put("parentTableId", emitted.get(parentTable).path("nodeId").asText());
                node.put("tableId", emitted.get(parentTable).path("nodeId").asText());
            }
            Element parentRow = ancestorW(element, "tr");
            if (parentRow != null && emitted.containsKey(parentRow)) node.put("parentRowId", emitted.get(parentRow).path("nodeId").asText());
            if ("table".equals(node.path("kind").asText())) node.put("tableId", node.path("nodeId").asText());
        }
        // Emission/source order, never HashMap order, defines stable table inventory ordering.
        List<Element> tableElements = new ArrayList<>();
        for (Map.Entry<Element, ObjectNode> pair : emitted.entrySet()) if ("table".equals(pair.getValue().path("kind").asText())) tableElements.add(pair.getKey());
        Collections.sort(tableElements, Comparator.comparingLong(e -> emitted.get(e).path("sourceOrder").asLong()));
        for (Element table : tableElements) {
            ObjectNode node = emitted.get(table), inventory = JSON.createObjectNode();
            String tableId = node.path("nodeId").asText(); inventory.put("tableId", tableId);
            inventory.set("sourcePosition", node.path("sourcePosition").deepCopy());
            if (node.has("parentCellId")) inventory.put("parentCellId", node.path("parentCellId").asText());
            Element parentTable = ancestorW(table, "tbl");
            if (parentTable != null) inventory.put("parentTableId", emitted.get(parentTable).path("nodeId").asText());
            inventory.put("topologyStatus", "all_native_rows_and_cells_observed_no_rendered_grid_claim");
            inventory.put("inventoryComplete", true); inventory.putArray("limitations").add("native_inventory_only_not_rendered_grid_or_visual_table_group");
            inventory.put("semanticQuality", "unknown"); inventory.put("rowSpanStatus", "not_inferred");
            ArrayNode grid = inventory.putArray("gridColumns");
            Element tblGrid = directW(table, "tblGrid");
            if (tblGrid != null) for (Element col : children(tblGrid)) if (isW(col) && "gridCol".equals(col.getLocalName())) grid.add(domEvidence(col));
            ArrayNode rows = inventory.putArray("rows"), nested = inventory.putArray("nestedTableIds");
            List<Element> ownedRows = ownedDescendants(table, "tr", "tbl", table);
            for (int ri = 0; ri < ownedRows.size(); ri++) {
                Element row = ownedRows.get(ri); ObjectNode rowNode = emitted.get(row);
                ObjectNode value = JSON.createObjectNode(); value.put("rowId", rowNode.path("nodeId").asText()); value.put("rowIndex", ri);
                rowNode.put("rowIndex", ri); value.set("sourcePosition", rowNode.path("sourcePosition").deepCopy());
                value.set("gridBeforeRaw", propertyValue(row, "trPr", "gridBefore")); value.set("gridAfterRaw", propertyValue(row, "trPr", "gridAfter"));
                ArrayNode cells = value.putArray("cells");
                List<Element> ownedCells = ownedDescendants(row, "tc", "tr", row);
                for (int ci = 0; ci < ownedCells.size(); ci++) {
                    Element cell = ownedCells.get(ci); ObjectNode cellNode = emitted.get(cell), cv = JSON.createObjectNode();
                    String cellId = cellNode.path("nodeId").asText();
                    cellNode.put("cellId", cellId); cellNode.put("rowIndex", ri); cellNode.put("cellIndex", ci);
                    cv.put("cellId", cellId); cv.put("rowIndex", ri); cv.put("cellIndex", ci);
                    cv.set("sourcePosition", cellNode.path("sourcePosition").deepCopy());
                    cv.set("gridSpanRaw", propertyValue(cell, "tcPr", "gridSpan"));
                    cv.set("vMergeRaw", propertyValue(cell, "tcPr", "vMerge")); cv.set("hMergeRaw", propertyValue(cell, "tcPr", "hMerge"));
                    cv.put("gridInterpretationStatus", "unknown_not_inferred");
                    ArrayNode paras = cv.putArray("childParagraphIds"), childTables = cv.putArray("nestedTableIds");
                    for (Element p : ownedDescendants(cell, "p", "tc", cell)) paras.add(emitted.get(p).path("nodeId").asText());
                    for (Element childTable : ownedDescendants(cell, "tbl", "tc", cell)) {
                        String childId = emitted.get(childTable).path("nodeId").asText(); childTables.add(childId); nested.add(childId);
                    }
                    cells.add(cv);
                }
                rows.add(value);
            }
            tables.add(inventory);
        }
    }

    private static ObjectNode propertyValue(Element owner, String properties, String property) {
        ObjectNode value = JSON.createObjectNode(); Element props = directW(owner, properties), element = props == null ? null : directW(props, property);
        value.put("present", element != null);
        if (element != null) { value.set("rawNode", domEvidence(element)); String val = attrW(element, "val"); if (val == null) value.putNull("val"); else value.put("val", val); }
        else value.putNull("val");
        return value;
    }

    private static ObjectNode directToggle(Element run, String name) {
        ObjectNode value = propertyValue(run, "rPr", name);
        if (!value.path("present").asBoolean()) value.put("interpretation", "absent_inheritance_unknown");
        else if (value.path("val").isNull()) value.put("interpretation", "true");
        else {
            String raw = value.path("val").asText();
            value.put("interpretation", Arrays.asList("true", "1", "on").contains(raw) ? "true"
                    : Arrays.asList("false", "0", "off").contains(raw) ? "false" : "unknown_invalid_value");
        }
        return value;
    }

    private static ObjectNode offset(int start, int end, boolean included) {
        ObjectNode value = JSON.createObjectNode(); value.put("start", start); value.put("end", end); value.put("included", included); return value;
    }

    private static void collectTokenElements(Element owner, Element cursor, List<Element> out) {
        if (cursor != owner && isW(cursor)) {
            // A nested paragraph/run (e.g. text box) has its own owner and is never flattened.
            if ("p".equals(cursor.getLocalName()) || ("r".equals(owner.getLocalName()) && "r".equals(cursor.getLocalName()))) return;
            if (isToken(cursor)) { out.add(cursor); return; }
        }
        for (Element child : children(cursor)) collectTokenElements(owner, child, out);
    }

    private static boolean isToken(Element e) { return Arrays.asList("t", "delText", "tab", "ptab", "br", "cr", "noBreakHyphen", "softHyphen",
            "sym", "instrText", "delInstrText", "fldChar", "drawing", "object", "pict", "footnoteReference", "endnoteReference", "commentReference",
            "annotationRef", "separator", "continuationSeparator", "lastRenderedPageBreak").contains(e.getLocalName()); }

    private static String tokenText(Element element) {
        switch (element.getLocalName()) {
            case "t": case "delText": case "instrText": case "delInstrText": return element.getTextContent();
            case "tab": case "ptab": return "\t";
            case "br": return "\n";
            case "cr": return "\r";
            case "noBreakHyphen": return "\u2011";
            case "softHyphen": return "\u00ad";
            default: return "";
        }
    }

    private ObjectNode position(Element e, String part, String partSha) {
        ObjectNode p = JSON.createObjectNode(); p.put("kind", "docx_native"); p.put("sourceSha256", sourceSha);
        p.put("partName", part); p.put("partSha256", partSha); p.put("nodePath", path(e)); return p;
    }

    /** Exact formula also exposed for independent producer validation. */
    public static String nodeId(String kind, String sourceSha, String partName, String partSha, String nodePath) {
        return "native-" + sha256(("consense.native-node.v1\n" + kind + "\n" + sourceSha + "\n" + partName + "\n" + partSha + "\n" + nodePath).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String path(Element e) {
        Object cached = e.getUserData(PATH_CACHE_KEY);
        if (cached instanceof String) return (String) cached;
        int index = 1;
        for (Node sibling = e.getPreviousSibling(); sibling != null; sibling = sibling.getPreviousSibling())
            if (sibling instanceof Element && expandedName((Element) sibling).equals(expandedName(e))) index++;
        String own = "/" + expandedName(e) + "[" + index + "]";
        return e.getParentNode() instanceof Element ? path((Element) e.getParentNode()) + own : own;
    }
    private static void cachePaths(Element element, String ownPath) {
        // One exact DOM walk avoids rescanning thousands of prior siblings for every token.
        // User data never changes DOM text/attributes, source ZIP bytes or raw parts.
        element.setUserData(PATH_CACHE_KEY, ownPath, null);
        Map<String, Integer> counts = new HashMap<>();
        for (Element child : children(element)) {
            String name = expandedName(child); int index = counts.getOrDefault(name, 0) + 1; counts.put(name, index);
            cachePaths(child, ownPath + "/" + name + "[" + index + "]");
        }
    }

    private static String expandedName(Element e) { return "{" + (e.getNamespaceURI() == null ? "" : e.getNamespaceURI()) + "}" + e.getLocalName(); }
    private static boolean isW(Element e) { return W.equals(e.getNamespaceURI()) || STRICT_W.equals(e.getNamespaceURI()); }
    private static String nativeKind(Element e) {
        if (!isW(e)) return null;
        switch (e.getLocalName()) {
            case "tbl": return "table"; case "tr": return "row"; case "tc": return "cell";
            case "p": return "paragraph"; case "r": return "run";
            default: return null;
        }
    }
    private static String storyKind(Element root) { return isW(root) ? root.getLocalName() : "unsupported_namespace_or_root"; }
    private static boolean isStoryContentType(String type) {
        return type.contains("wordprocessingml") && (type.contains(".document.main+xml") || type.contains(".header+xml")
                || type.contains(".footer+xml") || type.contains(".footnotes+xml") || type.contains(".endnotes+xml")
                || type.contains(".comments+xml") || type.contains(".document.glossary+xml"));
    }
    private static List<Element> children(Element e) {
        List<Element> values = new ArrayList<>(); if (e == null) return values;
        for (Node child = e.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element) values.add((Element) child);
        return values;
    }
    private static Element directW(Element e, String name) {
        for (Element child : children(e)) if (isW(child) && name.equals(child.getLocalName())) return child; return null;
    }
    private static Element ancestorW(Element e, String name) {
        if (e == null) return null;
        for (Node p = e.getParentNode(); p instanceof Element; p = p.getParentNode())
            if (isW((Element) p) && name.equals(((Element) p).getLocalName())) return (Element) p; return null;
    }
    private static Element ancestor(Element e, String ns, String name) {
        for (Node p = e.getParentNode(); p instanceof Element; p = p.getParentNode())
            if (ns.equals(p.getNamespaceURI()) && name.equals(p.getLocalName())) return (Element) p; return null;
    }
    private static List<Element> ownedDescendants(Element root, String name, String ownerName, Element expectedOwner) {
        List<Element> values = new ArrayList<>(); collectOwned(root, name, ownerName, expectedOwner, values); return values;
    }
    private static void collectOwned(Element root, String name, String ownerName, Element owner, List<Element> out) {
        for (Element child : children(root)) {
            if (isW(child) && name.equals(child.getLocalName()) && ancestorW(child, ownerName) == owner) out.add(child);
            collectOwned(child, name, ownerName, owner, out);
        }
    }
    private static String attrW(Element e, String local) {
        if (e.hasAttributeNS(W, local)) return e.getAttributeNS(W, local);
        if (e.hasAttributeNS(STRICT_W, local)) return e.getAttributeNS(STRICT_W, local); return null;
    }
    private static ObjectNode attributes(Element e) {
        ObjectNode attrs = JSON.createObjectNode(); NamedNodeMap raw = e.getAttributes(); List<Node> sorted = new ArrayList<>();
        for (int i = 0; i < raw.getLength(); i++) sorted.add(raw.item(i));
        Collections.sort(sorted, Comparator.comparing(n -> "{" + n.getNamespaceURI() + "}" + n.getLocalName()));
        for (Node a : sorted) attrs.put("{" + (a.getNamespaceURI() == null ? "" : a.getNamespaceURI()) + "}" + (a.getLocalName() == null ? a.getNodeName() : a.getLocalName()), a.getNodeValue());
        return attrs;
    }
    private static ObjectNode domEvidence(Element e) {
        ObjectNode node = JSON.createObjectNode(); node.put("expandedName", expandedName(e)); node.put("nodePath", path(e));
        node.set("attributes", attributes(e)); ArrayNode children = node.putArray("children");
        for (Node child = e.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) children.add(domEvidence((Element) child));
            else { ObjectNode text = JSON.createObjectNode(); text.put("domNodeType", child.getNodeType()); text.put("value", child.getNodeValue()); children.add(text); }
        }
        return node;
    }
    private static ObjectNode relationship(String sourcePartName, PackageRelationship rel) {
        ObjectNode out = JSON.createObjectNode(); out.put("sourcePartName", sourcePartName); out.put("relationshipId", rel.getId());
        out.put("relationshipType", rel.getRelationshipType()); out.put("targetMode", rel.getTargetMode().name());
        out.put("targetUri", rel.getTargetURI().toString()); out.put("resolvedApplicability", "unknown_unreviewed"); return out;
    }
    private static ObjectNode artifact(String name, String mediaType, byte[] bytes) {
        ObjectNode out = JSON.createObjectNode(); out.put("name", name); out.put("mediaType", mediaType);
        out.put("sha256", sha256(bytes)); out.put("byteCount", bytes.length); out.put("bytesBase64", Base64.getEncoder().encodeToString(bytes)); return out;
    }
    private static String withoutSlash(String value) { return value.startsWith("/") ? value.substring(1) : value; }
    private static Map<String, byte[]> zipEntries(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entries.containsKey(entry.getName())) throw new IOException("Duplicate ZIP part identity: " + entry.getName());
                if (entries.size() >= MAX_PARTS) throw new IOException("DOCX package exceeds explicit part count budget");
                if (entry.getName().startsWith("/") || Arrays.asList(entry.getName().split("/")).contains(".."))
                    throw new IOException("Unsafe package part identity");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int count;
                while ((count = zip.read(block)) != -1) {
                    total += count;
                    if (total > MAX_EXPANDED_BYTES || (long) buffer.size() + count > MAX_PART_BYTES)
                        throw new IOException("DOCX package exceeds explicit expanded byte budget");
                    buffer.write(block, 0, count);
                }
                entries.put(entry.getName(), buffer.toByteArray());
            }
        }
        if (entries.isEmpty()) throw new IOException("DOCX ZIP has no package parts"); return entries;
    }
    private static DocumentBuilderFactory secureXmlFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false); return factory;
    }
    private static Document parseXml(byte[] bytes) throws Exception {
        javax.xml.parsers.DocumentBuilder builder = secureXmlFactory().newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        });
        return builder.parse(new ByteArrayInputStream(bytes));
    }
    public static String sha256(byte[] bytes) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException("Cannot calculate actual byte SHA256", e); }
    }
    static byte[] canonicalBytes(JsonNode value) throws IOException { return JSON.writeValueAsBytes(sorted(value)); }
    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode(); List<String> names = new ArrayList<>(); value.fieldNames().forEachRemaining(names::add);
            Collections.sort(names); for (String name : names) result.set(name, sorted(value.get(name))); return result;
        }
        if (value.isArray()) { ArrayNode result = JSON.createArrayNode(); for (JsonNode row : value) result.add(sorted(row)); return result; }
        return value;
    }
}
