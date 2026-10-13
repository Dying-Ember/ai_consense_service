package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Request-wide sharing of metadata only; the complete source tree remains in this wire. */
public final class VettingSourceMetadataWire {
    public static final String VERSION = "lossless-source-metadata-dictionary-v3";
    private static final String TAG = "metadataEncoding", REF = "metadataRef", ESCAPE = "source-metadata-object-v1";
    private static final com.fasterxml.jackson.databind.ObjectMapper TREE = JsonUtils.mapper().copy()
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    public static final String LEGEND = VettingSourcePromptWire.LEGEND
            + "\nRequest metadata encoding: metadataEncoding=" + VERSION + " contains metadataValues and source. "
            + "First reconstruct the record tables described above. A one-field {metadataRef: integer} object refers to metadataValues[integer] "
            + "by zero-based index; substitute that exact value, including nested metadata references. "
            + "metadataEncoding=" + ESCAPE + " is an original source object stored as ordered [key,value] entries. "
            + "References share metadata only. content/cells/text/quote/raw fields and every descendant stay literal, including objects shaped like references. "
            + "All original source fields, whole tables, blanks, relationships and unknowns remain present; sharing does not establish source correctness or applicability.\n";

    private VettingSourceMetadataWire() { }

    public static String write(Object source) { return JsonUtils.write(encode(source)); }

    public static JsonNode encode(Object source) {
        JsonNode original = TREE.valueToTree(source);
        LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
        collect(original, candidates);
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
            Candidate c = entry.getValue();
            // Account for the full reference wrapper, dictionary entry and envelope overhead.
            if (c.count >= 2 && (long)(c.count - 1) * c.json.length() > (long)c.count * 64 + 32)
                ids.put(entry.getKey(), ids.size());
        }
        ArrayNode values = array();
        for (String identity : ids.keySet()) values.add(pack(candidates.get(identity).value, ids, false));
        ObjectNode envelope = object(); envelope.put(TAG, VERSION); envelope.set("metadataValues", values);
        envelope.set("source", pack(original, ids, true));
        JsonNode encoded = VettingSourcePromptWire.encode(envelope);
        if (!JsonUtils.write(original).equals(JsonUtils.write(decode(encoded))))
            throw new IllegalArgumentException("Metadata sharing changed ordered source JSON");
        return encoded;
    }

    public static JsonNode decode(String wire) { return unpackEnvelope(VettingSourcePromptWire.decode(wire)); }
    public static JsonNode decode(JsonNode wire) { return unpackEnvelope(VettingSourcePromptWire.decode(wire)); }

    private static JsonNode unpackEnvelope(JsonNode value) {
        fields(value, TAG, "metadataValues", "source");
        if (!VERSION.equals(value.path(TAG).asText()) || !value.path("metadataValues").isArray())
            throw new IllegalArgumentException("Metadata envelope identity differs");
        Decoder decoder = new Decoder(value.get("metadataValues"));
        // Validate all dictionary entries, including an unused or malicious entry.
        for (int i = 0; i < decoder.values.size(); i++) decoder.entry(i);
        return decoder.unpack(value.get("source"));
    }

    private static void collect(JsonNode value, Map<String, Candidate> candidates) {
        if (eligible(value)) {
            String json = JsonUtils.write(value);
            if (json.length() >= 64) {
                String key = identity(value); Candidate candidate = candidates.get(key);
                if (candidate == null) { candidate = new Candidate(value, json); candidates.put(key, candidate); }
                candidate.count++;
            }
        }
        if (value.isObject()) {
            Iterator<String> keys = value.fieldNames();
            while (keys.hasNext()) { String key = keys.next(); if (!literal(key)) collect(value.get(key), candidates); }
        } else if (value.isArray()) for (JsonNode child : value) collect(child, candidates);
    }

    private static JsonNode pack(JsonNode value, Map<String, Integer> ids, boolean useReference) {
        Integer id = useReference && eligible(value) ? ids.get(identity(value)) : null;
        if (id != null) { ObjectNode ref = object(); ref.put(REF, id); return ref; }
        if (value.isObject()) {
            if (value.has(TAG) || value.has(REF)) {
                ObjectNode escaped = object(); escaped.put(TAG, ESCAPE); ArrayNode entries = array();
                Iterator<String> keys = value.fieldNames();
                while (keys.hasNext()) {
                    String key = keys.next(); ArrayNode pair = array(); pair.add(key);
                    pair.add(literal(key) ? value.get(key).deepCopy() : pack(value.get(key), ids, true)); entries.add(pair);
                }
                escaped.set("entries", entries); return escaped;
            }
            ObjectNode out = object(); Iterator<String> keys = value.fieldNames();
            while (keys.hasNext()) { String key = keys.next(); out.set(key, literal(key) ? value.get(key).deepCopy() : pack(value.get(key), ids, true)); }
            return out;
        }
        if (value.isArray()) { ArrayNode out = array(); for (JsonNode child : value) out.add(pack(child, ids, true)); return out; }
        return value.deepCopy();
    }

    private static boolean eligible(JsonNode value) {
        return (value.isTextual() || value.isObject() || value.isArray()) && !hasLiteral(value);
    }
    private static boolean hasLiteral(JsonNode value) {
        if (value.isObject()) {
            Iterator<String> keys = value.fieldNames();
            while (keys.hasNext()) { String key = keys.next(); if (literal(key) || hasLiteral(value.get(key))) return true; }
        } else if (value.isArray()) for (JsonNode child : value) if (hasLiteral(child)) return true;
        return false;
    }
    private static boolean literal(String key) {
        return Arrays.asList("content", "cells", "text", "quote").contains(key) || key.toLowerCase(Locale.ROOT).startsWith("raw");
    }
    private static String identity(JsonNode value) { return value.getNodeType() + "|" + JsonUtils.write(value); }
    private static void fields(JsonNode value, String... expected) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("Metadata object required");
        Set<String> actual = new HashSet<>(); value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(new HashSet<>(Arrays.asList(expected)))) throw new IllegalArgumentException("Metadata fields differ");
    }
    private static ObjectNode object() { return JsonUtils.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonUtils.mapper().createArrayNode(); }
    private static final class Candidate {
        final JsonNode value; final String json; int count;
        Candidate(JsonNode value, String json) { this.value = value; this.json = json; }
    }
    private static final class Decoder {
        final JsonNode values; final JsonNode[] decoded; final boolean[] active;
        Decoder(JsonNode values) { this.values = values; decoded = new JsonNode[values.size()]; active = new boolean[values.size()]; }
        JsonNode entry(int id) {
            if (id < 0 || id >= values.size() || active[id]) throw new IllegalArgumentException("Metadata reference invalid or cyclic");
            if (decoded[id] == null) {
                active[id] = true; JsonNode result = unpack(values.get(id)); active[id] = false;
                if (!eligible(result)) throw new IllegalArgumentException("Literal source subtree cannot be a metadata value");
                decoded[id] = result;
            }
            return decoded[id].deepCopy();
        }
        JsonNode unpack(JsonNode value) {
            if (value == null) throw new IllegalArgumentException("Metadata value missing");
            if (value.isObject()) {
                if (value.has(REF)) {
                        fields(value, REF); JsonNode id = value.get(REF);
                        if (!id.isIntegralNumber() || !id.canConvertToInt()) throw new IllegalArgumentException("Metadata index must be an integer");
                        return entry(id.intValue());
                }
                if (value.has(TAG)) {
                    if (!ESCAPE.equals(value.path(TAG).asText())) throw new IllegalArgumentException("Unknown metadata marker");
                    fields(value, TAG, "entries"); if (!value.get("entries").isArray()) throw new IllegalArgumentException("Ordered entries required");
                    ObjectNode out = object();
                    for (JsonNode pair : value.get("entries")) {
                        if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isTextual() || out.has(pair.get(0).asText()))
                            throw new IllegalArgumentException("Metadata literal entry differs");
                        String key = pair.get(0).asText(); out.set(key, literal(key) ? pair.get(1).deepCopy() : unpack(pair.get(1)));
                    }
                    return out;
                }
                ObjectNode out = object(); Iterator<String> keys = value.fieldNames();
                while (keys.hasNext()) { String key = keys.next(); out.set(key, literal(key) ? value.get(key).deepCopy() : unpack(value.get(key))); }
                return out;
            }
            if (value.isArray()) { ArrayNode out = array(); for (JsonNode child : value) out.add(unpack(child)); return out; }
            return value.deepCopy();
        }
    }
}
