package com.consense.document;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Decodes explicitly verified Word symbol-font mappings, retaining the source attributes.
 * A w:sym code is a code in its named font, not an arbitrary Unicode code point.
 * See https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.symbolchar
 * and https://support.microsoft.com/en-us/word/insert-a-check-mark-symbol.
 * Revision/strikethrough filtering and source location belong to the document parser.
 */
public final class WordSymbolDecoder {
    public static final String RESOLVED = "resolved";
    public static final String WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String STRICT_WORD_NAMESPACE = "http://purl.oclc.org/ooxml/wordprocessingml/main";
    private static final String CHECKMARK_SOURCE = "https://support.microsoft.com/en-us/word/insert-a-check-mark-symbol";

    private WordSymbolDecoder() { }

    /** Never substitutes a guessed glyph for missing, malformed, or unsupported source data. */
    public static Symbol decode(Node symbolNode) {
        if (symbolNode == null || symbolNode.getNodeType() != Node.ELEMENT_NODE
                || !"sym".equals(symbolNode.getLocalName()) || !isWordNamespace(symbolNode.getNamespaceURI())) {
            return unresolved("", "", "unresolved_not_word_symbol");
        }
        String namespace = symbolNode.getNamespaceURI();
        return decode(attribute(symbolNode, namespace, "font"), attribute(symbolNode, namespace, "char"));
    }

    /** Used when the caller has already obtained the two OOXML symbol attributes. */
    public static Symbol decode(String font, String hexCode) {
        String originalFont = font == null ? "" : font;
        String originalCode = hexCode == null ? "" : hexCode;
        String normalizedFont = originalFont.trim();
        String normalizedCode = originalCode.trim();
        if (normalizedFont.isEmpty()) return unresolved(originalFont, originalCode, "unresolved_missing_font");
        if (normalizedCode.isEmpty()) return unresolved(originalFont, originalCode, "unresolved_missing_character_code");
        // w:char is ST_ShortHexNumber: two bytes written as exactly four hex digits.
        if (!normalizedCode.matches("[0-9a-fA-F]{4}")) {
            return unresolved(originalFont, originalCode, "unresolved_malformed_character_code");
        }
        if (!"Wingdings".equalsIgnoreCase(normalizedFont)) {
            return unresolved(originalFont, originalCode, "unresolved_unsupported_font");
        }
        int code = Integer.parseInt(normalizedCode, 16);
        // Microsoft identifies Wingdings decimal 252 (00FC) as the plain tick.
        // Word's documented F03A -> 003A legacy symbol encoding also occurs as F0FC here.
        // Restrict this allowance to the verified font/code pair, not every private-use code.
        if (code == 0x00FC || code == 0xF0FC) {
            return new Symbol(originalFont, originalCode, "\u2713", RESOLVED, CHECKMARK_SOURCE);
        }
        return unresolved(originalFont, originalCode, "unresolved_unsupported_character_code");
    }

    private static boolean isWordNamespace(String namespace) {
        return WORD_NAMESPACE.equals(namespace) || STRICT_WORD_NAMESPACE.equals(namespace);
    }

    private static String attribute(Node node, String namespace, String localName) {
        NamedNodeMap attributes = node.getAttributes();
        Node value = attributes == null ? null : attributes.getNamedItemNS(namespace, localName);
        return value == null ? "" : value.getNodeValue();
    }

    private static Symbol unresolved(String font, String hexCode, String status) {
        return new Symbol(font, hexCode, "", status, null);
    }

    /** Serializable provenance. Empty decodedText requires an explicit unresolved status. */
    public static final class Symbol {
        private final String font;
        private final String hexCode;
        private final String decodedText;
        private final String resolutionStatus;
        private final String mappingSource;

        @JsonCreator
        public Symbol(@JsonProperty("font") String font,
                      @JsonProperty("hexCode") String hexCode,
                      @JsonProperty("decodedText") String decodedText,
                      @JsonProperty("resolutionStatus") String resolutionStatus,
                      @JsonProperty("mappingSource") String mappingSource) {
            this.font = font == null ? "" : font;
            this.hexCode = hexCode == null ? "" : hexCode;
            this.decodedText = decodedText == null ? "" : decodedText;
            this.resolutionStatus = resolutionStatus;
            this.mappingSource = mappingSource;
        }

        public String getFont() { return font; }
        public String getHexCode() { return hexCode; }
        public String getDecodedText() { return decodedText; }
        public String getResolutionStatus() { return resolutionStatus; }
        public String getMappingSource() { return mappingSource; }

        @JsonIgnore
        public boolean isResolved() { return RESOLVED.equals(resolutionStatus); }
    }
}
