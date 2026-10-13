package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingSourceMetadataWireTest {
    private JsonNode roundtrip(Object source) {
        String before = JsonUtils.write(source), wire = VettingSourceMetadataWire.write(source);
        assertEquals(before, JsonUtils.write(VettingSourceMetadataWire.decode(wire)));
        assertEquals(before, JsonUtils.write(source), "Source audit tree must not mutate");
        return VettingSourcePromptWire.decode(wire);
    }
    @Test void requestWideSharingCrossesPartsSpansAndTablesWithoutDroppingAnything() {
        String sha = String.join("", Collections.nCopies(64, "a"));
        String path = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}document/body/table/row/cell/paragraph";
        String identity = "{\"sourceSha256\":\"" + sha + "\",\"path\":\"" + path + "\",\"unknown\":null}";
        JsonNode source = JsonUtils.parse("[{\"id\":\"one\",\"content\":\"Required 😀\",\"parts\":[{\"sourceIdentity\":" + identity + ",\"text\":\"Required 😀\",\"start\":0,\"end\":11}],\"sourceSpans\":[{\"position\":" + identity + "}],\"tableGroup\":{\"identity\":" + identity + ",\"cells\":[\"\",\"Required 😀\"]}},{\"id\":\"two\",\"content\":\"Exception\",\"parts\":[{\"sourceIdentity\":" + identity + ",\"text\":\"Exception\"}],\"sourceSpans\":[{\"position\":" + identity + "}],\"unknown\":[\"legal_adoption_unknown\"]}]");
        JsonNode envelope = roundtrip(source);
        assertTrue(envelope.get("metadataValues").size() > 0);
        assertTrue(VettingSourceMetadataWire.write(source).length() < VettingSourcePromptWire.write(source).length());
        assertTrue(VettingSourceMetadataWire.write(source).contains("Required 😀"));
        assertFalse(JsonUtils.write(envelope.get("metadataValues")).contains("Required 😀"));
    }
    @Test void literalSubtreesAndNestedMarkerLookingObjectsRemainLiteral() {
        roundtrip(JsonUtils.parse("{\"content\":{\"metadataRef\":-5},\"rawXml\":{\"recordEncoding\":\"unknown\"},\"cells\":[\"\",null,\"😀𠮷\"],\"parts\":[{\"text\":\"{\\\"metadataRef\\\":0}\"}]}"));
    }
    @Test void unknownReservedMetadataObjectRetainsItsOriginalOrderedFields() {
        roundtrip(JsonUtils.parse("{\"metadataRef\":2,\"future\":{\"metadataEncoding\":null},\"text\":\"literal\"}"));
        roundtrip(JsonUtils.parse("{\"metadataEncoding\":\"source-metadata-object-v1\",\"entries\":[[\"metadataEncoding\",\"unknown\"]]}"));
    }
    @Test void typesOrderNullMissingUnicodeAndWholeBlankTableArePreserved() {
        roundtrip(JsonUtils.parse("[{\"z\":{\"b\":2,\"a\":1},\"flag\":true,\"number\":1,\"unknown\":null},{\"z\":{\"a\":1,\"b\":2},\"flag\":1,\"number\":1.0},{\"cells\":[[\"\",\"\"],[\"😀\",\"𠮷\"]],\"relations\":[{\"parent\":null}],\"quality\":\"needs_review\"}]") );
    }
    @Test void exactDecimalMetadataScaleAndPrecisionSurviveBothEncodings() {
        Map<String,Object> source = new LinkedHashMap<>();
        source.put("precise", new java.math.BigDecimal("1.123456789012345678901234567890"));
        source.put("scale", new java.math.BigDecimal("1.0000"));
        source.put("exponent", new java.math.BigDecimal("1.25E-30")); roundtrip(source);
    }
    @ParameterizedTest
    @ValueSource(strings={"rawFuture", "content", "cells", "text", "quote"})
    void literalDecimalNumbersAndMarkerLikeTextKeepTheirSpelling(String key) {
        Map<String,Object> literal = new LinkedHashMap<>();
        literal.put("precise", new java.math.BigDecimal("1.123456789012345678901234567890"));
        literal.put("scale", new java.math.BigDecimal("1.0000"));
        literal.put("exponent", new java.math.BigDecimal("1E+100"));
        literal.put("text", "{\"metadataRef\":0,\"value\":1.0000}");
        Map<String,Object> source = new LinkedHashMap<>(); source.put(key, literal); roundtrip(source);
    }
    @ParameterizedTest
    @ValueSource(doubles={-0.0,1.0e20,1.0e-20,1.0})
    void literalFloatNumbersKeepTheirJsonLexemes(double value) {
        Map<String,Object> source = new LinkedHashMap<>();
        source.put("rawNumbers", Arrays.asList(value, true, 1, "1.0")); roundtrip(source);
    }
    @Test void cycleInvalidIndexUnknownMarkerAndUnusedLiteralDictionaryAreRejected() {
        String prefix = "{\"metadataEncoding\":\"" + VettingSourceMetadataWire.VERSION + "\",\"metadataValues\":";
        for (String values : Arrays.asList("[{\"metadataRef\":0}]", "[{\"text\":\"cannot hide source literal\"}]", "[{\"metadataEncoding\":\"unknown\"}]"))
            assertThrows(IllegalArgumentException.class, () -> VettingSourceMetadataWire.decode(prefix + values + ",\"source\":null}"));
        for (String id : Arrays.asList("-1", "0", "true", "0.0", "2147483648"))
            assertThrows(IllegalArgumentException.class, () -> VettingSourceMetadataWire.decode(prefix + "[],\"source\":{\"metadataRef\":" + id + "}}"));
    }
    @Test void malformedEnvelopeDuplicateEntriesAndDuplicateJsonCannotPass() {
        assertThrows(IllegalArgumentException.class, () -> VettingSourceMetadataWire.decode("{\"metadataEncoding\":\"" + VettingSourceMetadataWire.VERSION + "\",\"metadataValues\":[],\"source\":{},\"extra\":true}"));
        assertThrows(IllegalArgumentException.class, () -> VettingSourceMetadataWire.decode("{\"metadataEncoding\":\"" + VettingSourceMetadataWire.VERSION + "\",\"metadataValues\":[],\"source\":{\"metadataEncoding\":\"source-metadata-object-v1\",\"entries\":[[\"a\",1],[\"a\",2]]}}"));
        assertThrows(IllegalArgumentException.class, () -> VettingSourceMetadataWire.decode("{\"a\":1,\"a\":2}"));
    }
}
