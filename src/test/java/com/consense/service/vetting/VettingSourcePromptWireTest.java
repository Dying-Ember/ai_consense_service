package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingSourcePromptWireTest {
    private JsonNode roundtrip(String source) {
        JsonNode original=JsonUtils.parse(source);String before=JsonUtils.write(original);
        JsonNode wire=VettingSourcePromptWire.encode(original),decoded=VettingSourcePromptWire.decode(JsonUtils.write(wire));
        assertEquals(before,JsonUtils.write(decoded));assertEquals(before,JsonUtils.write(original),"Encoder must not mutate full audit tree");return wire;
    }
    @Test void unknownLiteralReservedMarkerCannotCollideWithRecordDecoder() {
        JsonNode wire=roundtrip("{\"futureMetadata\":{\"recordEncoding\":\""+VettingSourcePromptWire.VERSION+"\",\"unknown\":null}}");
        assertEquals("source-literal-object-v1",wire.path("futureMetadata").path("recordEncoding").asText());
    }
    @Test void aSourceObjectShapedLikeAnEncodedTableRemainsAnObject() {
        String source="{\"recordEncoding\":\""+VettingSourcePromptWire.VERSION+"\",\"keys\":[\"a\"],\"shared\":{\"a\":null},\"columns\":[],\"valueTables\":{},\"rows\":[[]]}";
        JsonNode decoded=VettingSourcePromptWire.decode(VettingSourcePromptWire.encode(JsonUtils.parse(source)));assertTrue(decoded.isObject());assertEquals(source,JsonUtils.write(decoded));
    }
    @Test void escapedTagLookingValuesAndNestedEscapeTagsDecodeOnlyOneLayer() {
        roundtrip("{\"recordEncoding\":\"source-literal-object-v1\",\"entries\":[[\"recordEncoding\",\""+VettingSourcePromptWire.VERSION+"\"]],\"child\":{\"recordEncoding\":null},\"null\":null}");
    }
    @Test void booleanIntegerAndFloatMetadataNeverBecomeSharedByLooseEquality() {
        String pad=String.join("",Collections.nCopies(500,"metadata"));
        JsonNode wire=roundtrip("[{\"flag\":true,\"number\":1,\"stable\":\""+pad+"\",\"text\":\"literal a\"},{\"flag\":1,\"number\":1.0,\"stable\":\""+pad+"\",\"text\":\"literal b\"}]");
        assertEquals(VettingSourcePromptWire.VERSION,wire.path("recordEncoding").asText());assertFalse(wire.path("shared").has("flag"));assertFalse(wire.path("shared").has("number"));
    }
    @Test void sourceFieldOrderMissingNullAndArbitraryArrayShapesAreReversible() {
        roundtrip("[{\"z\":{\"b\":2,\"a\":1},\"future\":null},{\"z\":{\"a\":1,\"b\":2},\"future\":null},{\"z\":null}]");
        roundtrip("[null,{},[],[true,1,1.0,\"😀𠮷\"],{\"future\":{\"anything\":[null,{},[]]}}]");
    }
    @Test void contentCellsTextRawAndTheirSubtreesStayLiteralWithoutDictionaryOrJsonParsing() {
        String repeated=String.join("",Collections.nCopies(500,"untrusted"));
        String json="[{\"id\":1,\"content\":\"{\\\"recordEncoding\\\":\\\""+VettingSourcePromptWire.VERSION+"\\\"}\",\"cells\":[\"\",\"Room | East 😀\"],\"parts\":[{\"text\":\""+repeated+"\"}],\"rawFuture\":{\"recordEncoding\":\"unknown-source\"}},{\"id\":2,\"content\":\"{\\\"recordEncoding\\\":\\\""+VettingSourcePromptWire.VERSION+"\\\"}\",\"cells\":[\"\",\"Room | East 😀\"],\"parts\":[{\"text\":\""+repeated+"\"}],\"rawFuture\":{\"recordEncoding\":\"unknown-source\"}}]";
        JsonNode wire=roundtrip(json);assertFalse(wire.path("valueTables").has("content"));assertFalse(wire.path("valueTables").has("cells"));assertFalse(wire.path("valueTables").has("parts"));assertFalse(wire.path("valueTables").has("rawFuture"));
        assertTrue(JsonUtils.write(wire).contains("Room | East 😀"));assertTrue(JsonUtils.write(wire).contains(repeated));
    }
    @Test void compressionKeepsUtf16RangesAndUnknownQualityExactly() {
        String stable=String.join("",Collections.nCopies(500,"source provenance"));
        String source="[{\"id\":\"a\",\"stable\":\""+stable+"\",\"quality\":\"needs_review\",\"text\":\"😀𠮷\",\"start\":0,\"end\":4},{\"id\":\"b\",\"stable\":\""+stable+"\",\"quality\":\"needs_review\",\"text\":\"繁體\",\"start\":4,\"end\":6}]";
        JsonNode wire=roundtrip(source);assertTrue(JsonUtils.write(wire).length()<source.length());JsonNode decoded=VettingSourcePromptWire.decode(wire);assertEquals(4,decoded.get(0).get("text").asText().length());assertEquals("needs_review",decoded.get(0).get("quality").asText());
    }
    @Test void futureDecimalMetadataKeepsScaleAndPrecisionBeyondDoubleAfterSerializedWire() {
        Map<String,Object> unknown=new LinkedHashMap<>();unknown.put("precise",new java.math.BigDecimal("1.123456789012345678901234567890"));unknown.put("scale",new java.math.BigDecimal("1.0000"));unknown.put("exponent",new java.math.BigDecimal("1.25E-30"));
        String original=JsonUtils.write(unknown),wire=VettingSourcePromptWire.write(unknown);assertEquals(original,JsonUtils.write(VettingSourcePromptWire.decode(wire)));assertTrue(wire.contains("source-literal-decimal-v1"));
    }
    @Test void malformedTagsColumnsRowsIndicesAndDuplicateJsonAreRejected() {
        String common="\"recordEncoding\":\""+VettingSourcePromptWire.VERSION+"\",\"keys\":[\"a\"],\"shared\":{},\"columns\":[\"a\"],\"valueTables\":{\"a\":[null]},";
        for(String rows:Arrays.asList("[[true]]","[[-1]]","[[1]]","[[]]"))assertThrows(IllegalArgumentException.class,()->VettingSourcePromptWire.decode("{"+common+"\"rows\":"+rows+"}"));
        assertThrows(IllegalArgumentException.class,()->VettingSourcePromptWire.decode("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class,()->VettingSourcePromptWire.decode("{} []"));
        assertThrows(IllegalArgumentException.class,()->VettingSourcePromptWire.decode("{\"recordEncoding\":\"unknown\"}"));
    }
}
