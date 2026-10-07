package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;

/** Decoding shape only: supplied IDs and verbatim source verification are checked separately. */
final class VettingOutputSchema {
    private VettingOutputSchema() { }

    static JsonNode forChunkIds(Collection<String> chunkIds) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("type", "array"); root.put("maxItems", 3);
        ObjectNode item = root.putObject("items"); item.put("type", "object"); item.put("additionalProperties", false);
        ObjectNode fields = item.putObject("properties");
        fields.set("assessment", choice("consistent", "insufficient_context", "issue"));
        fields.set("type", choice("reference", "conflict", "language", "risk"));
        fields.set("severity", choice("high", "medium", "low"));
        fields.set("title", text(1, 160)); fields.set("comment", text(1, 1200));
        fields.set("impact", text(1, 600)); fields.set("suggestion", text(1, 600));
        ObjectNode evidence = fields.putObject("evidence");
        evidence.put("type", "array"); evidence.put("minItems", 1); evidence.put("maxItems", 4);
        ObjectNode quote = evidence.putObject("items"); quote.put("type", "object"); quote.put("additionalProperties", false);
        ObjectNode quoteFields = quote.putObject("properties");
        quoteFields.set("chunkId", choice(chunkIds.toArray(new String[0])));
        quoteFields.set("side", choice("source", "reference")); quoteFields.set("quote", text(12, 1200));
        required(quote, "chunkId", "side", "quote");
        required(item, "assessment", "type", "severity", "title", "comment", "impact", "suggestion", "evidence");
        return root;
    }

    private static ObjectNode text(int min, int max) {
        ObjectNode node = JsonUtils.mapper().createObjectNode(); node.put("type", "string");
        node.put("minLength", min); node.put("maxLength", max); return node;
    }
    private static ObjectNode choice(String... values) {
        ObjectNode node = JsonUtils.mapper().createObjectNode(); node.put("type", "string");
        ArrayNode allowed = node.putArray("enum"); for (String value : values) allowed.add(value); return node;
    }
    private static void required(ObjectNode node, String... fields) {
        ArrayNode required = node.putArray("required"); for (String field : fields) required.add(field);
    }
}
