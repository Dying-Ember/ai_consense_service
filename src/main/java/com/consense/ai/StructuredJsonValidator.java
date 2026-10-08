package com.consense.ai;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

import static com.consense.ai.StructuredOutputValidationException.Reason.*;

/**
 * Deliberately bounded schema subset used by VettingOutputSchema, not a general JSON Schema engine.
 * Supports type=array/object/string; array items/minItems/maxItems; object properties/required/
 * boolean additionalProperties; and string enum/minLength/maxLength. Every other keyword, type,
 * boolean schema, union, reference, or schema-valued additionalProperties is rejected before dispatch.
 * Each schema node requires a supported type. An omitted items/properties constraint is unrestricted.
 * Bounds are nonnegative 32-bit integer nodes; schema nesting beyond 64 levels is unsupported.
 * String length counts Unicode code points, including supplementary characters, rather than UTF-16 units.
 * The gateway additionally requires a direct array with no null or nested-array top-level elements.
 * Evidence/source truth and semantic quality remain separate checks after this structural gate.
 */
final class StructuredJsonValidator {
    // Independent mapper: ordinary/Drafting JSON extraction and mapping retain their existing settings.
    private static final ObjectMapper STRICT_MAPPER = JsonUtils.mapper().copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ARRAY_KEYS = keys("type", "items", "minItems", "maxItems");
    private static final Set<String> OBJECT_KEYS = keys("type", "properties", "required", "additionalProperties");
    private static final Set<String> STRING_KEYS = keys("type", "enum", "minLength", "maxLength");
    private static final int MAX_SCHEMA_DEPTH = 64;

    private StructuredJsonValidator() { }

    static Schema compile(JsonNode schema) {
        Schema compiled = compileNode(schema, 0);
        if (!"array".equals(compiled.type)) throw failure(INVALID_SCHEMA);
        return compiled;
    }

    static <T> List<T> decode(String raw, Class<T> elementType, Schema schema) {
        if (raw == null || raw.trim().isEmpty()) throw failure(INVALID_JSON);
        JsonNode root;
        try { root = STRICT_MAPPER.readTree(raw); }
        catch (Exception invalid) { throw failure(INVALID_JSON); }
        if (root == null || !root.isArray()) throw failure(SCHEMA_MISMATCH);
        for (JsonNode item : root) {
            if (item == null || item.isNull() || item.isArray()) throw failure(SCHEMA_MISMATCH);
        }
        validate(root, schema);
        try {
            return STRICT_MAPPER.convertValue(root,
                    STRICT_MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception incompatibleElementType) { throw failure(ELEMENT_MAPPING_FAILED); }
    }

    private static Schema compileNode(JsonNode node, int depth) {
        if (depth > MAX_SCHEMA_DEPTH) throw failure(UNSUPPORTED_SCHEMA);
        if (node == null || !node.isObject() || !node.path("type").isTextual()) throw failure(INVALID_SCHEMA);
        String type = node.get("type").textValue();
        Set<String> supported;
        switch (type) {
            case "array": supported = ARRAY_KEYS; break;
            case "object": supported = OBJECT_KEYS; break;
            case "string": supported = STRING_KEYS; break;
            default: throw failure(UNSUPPORTED_SCHEMA);
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) if (!supported.contains(names.next())) throw failure(UNSUPPORTED_SCHEMA);
        Schema schema = new Schema(type);
        if ("array".equals(type)) {
            schema.minimum = bound(node, "minItems"); schema.maximum = bound(node, "maxItems");
            if (node.has("items")) schema.items = compileNode(node.get("items"), depth + 1);
        } else if ("object".equals(type)) {
            if (node.has("properties")) {
                JsonNode properties = node.get("properties");
                if (!properties.isObject()) throw failure(INVALID_SCHEMA);
                Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    schema.properties.put(field.getKey(), compileNode(field.getValue(), depth + 1));
                }
            }
            if (node.has("required")) {
                JsonNode required = node.get("required");
                if (!required.isArray()) throw failure(INVALID_SCHEMA);
                for (JsonNode field : required) {
                    if (!field.isTextual() || !schema.required.add(field.textValue())) throw failure(INVALID_SCHEMA);
                }
            }
            if (node.has("additionalProperties")) {
                if (!node.get("additionalProperties").isBoolean()) throw failure(UNSUPPORTED_SCHEMA);
                schema.allowAdditional = node.get("additionalProperties").booleanValue();
            }
        } else {
            schema.minimum = bound(node, "minLength"); schema.maximum = bound(node, "maxLength");
            if (node.has("enum")) {
                JsonNode allowed = node.get("enum");
                if (!allowed.isArray()) throw failure(INVALID_SCHEMA);
                schema.allowedStrings = new HashSet<>();
                // Empty supplied-ID enum is meaningful: [] may pass, but no evidence ID can pass.
                for (JsonNode value : allowed) {
                    if (!value.isTextual() || !schema.allowedStrings.add(value.textValue())) throw failure(INVALID_SCHEMA);
                }
            }
        }
        if (schema.minimum != null && schema.maximum != null && schema.minimum > schema.maximum) throw failure(INVALID_SCHEMA);
        return schema;
    }

    private static Integer bound(JsonNode node, String name) {
        if (!node.has(name)) return null;
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw failure(INVALID_SCHEMA);
        return value.intValue();
    }

    private static void validate(JsonNode value, Schema schema) {
        switch (schema.type) {
            case "array":
                if (!value.isArray()) throw failure(SCHEMA_MISMATCH);
                checkBounds(value.size(), schema);
                if (schema.items != null) for (JsonNode item : value) validate(item, schema.items);
                break;
            case "object":
                if (!value.isObject()) throw failure(SCHEMA_MISMATCH);
                for (String required : schema.required) if (!value.has(required)) throw failure(SCHEMA_MISMATCH);
                Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    Schema property = schema.properties.get(field.getKey());
                    if (property != null) validate(field.getValue(), property);
                    else if (!schema.allowAdditional) throw failure(SCHEMA_MISMATCH);
                }
                break;
            case "string":
                if (!value.isTextual()) throw failure(SCHEMA_MISMATCH);
                String text = value.textValue();
                checkBounds(text.codePointCount(0, text.length()), schema);
                if (schema.allowedStrings != null && !schema.allowedStrings.contains(text)) throw failure(SCHEMA_MISMATCH);
                break;
            default: throw failure(UNSUPPORTED_SCHEMA);
        }
    }

    private static void checkBounds(int size, Schema schema) {
        if ((schema.minimum != null && size < schema.minimum) || (schema.maximum != null && size > schema.maximum))
            throw failure(SCHEMA_MISMATCH);
    }

    private static StructuredOutputValidationException failure(StructuredOutputValidationException.Reason reason) {
        return new StructuredOutputValidationException(reason);
    }

    private static Set<String> keys(String... names) { return new HashSet<>(Arrays.asList(names)); }

    static final class Schema {
        final String type;
        Integer minimum, maximum;
        Schema items;
        final Map<String, Schema> properties = new LinkedHashMap<>();
        final Set<String> required = new HashSet<>();
        Set<String> allowedStrings;
        boolean allowAdditional = true;
        Schema(String type) { this.type = type; }
    }
}
