package com.consense.ai;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Stream;

import static com.consense.ai.StructuredOutputValidationException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Schema/JSON protocol fixtures only; acceptance does not assert source truth or model quality. */
class StructuredJsonValidatorTest {
    private static JsonNode actualSchema(String... ids) {
        try {
            Method method=Class.forName("com.consense.service.vetting.VettingOutputSchema")
                    .getDeclaredMethod("forChunkIds", Collection.class);
            method.setAccessible(true);
            return (JsonNode)method.invoke(null, Arrays.asList(ids));
        } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
    }

    private static ObjectNode issue() {
        return (ObjectNode)JsonUtils.parse("{\"assessment\":\"issue\",\"type\":\"reference\",\"severity\":\"medium\",\"title\":\"Synthetic schema test\",\"comment\":\"Synthetic fixture, no contract-quality claim\",\"impact\":\"Fixture impact\",\"suggestion\":\"Fixture suggestion\",\"evidence\":[{\"chunkId\":\"submitted-1\",\"side\":\"source\",\"quote\":\"Twelve source characters\"}]}");
    }

    private static ObjectNode evidence(ObjectNode issue) { return (ObjectNode)issue.path("evidence").get(0); }
    private static String array(JsonNode node) { return "["+node+"]"; }
    private static String repeat(String value,int count) { return String.join("",Collections.nCopies(count,value)); }

    private static LlmClient provider(String raw) {
        LlmClient provider=mock(LlmClient.class);
        when(provider.available()).thenReturn(true);
        when(provider.chatStructured(anyList(),any())).thenReturn(raw);
        return provider;
    }

    private static List<JsonNode> decode(String raw) {
        return new AiGateway(provider(raw)).completeStructuredJsonList("Synthetic system","Synthetic fixture",JsonNode.class,actualSchema("submitted-1"));
    }

    @Test void exactProductionSchemaAcceptsEmptyArrayAndValidTypedIssueWithoutCertifyingSourceTruth() {
        assertTrue(decode("[]").isEmpty());
        LlmClient provider=provider(array(issue()));
        List<DecodedIssue> parsed=new AiGateway(provider).completeStructuredJsonList("system","source",DecodedIssue.class,actualSchema("submitted-1"));
        assertEquals(1,parsed.size()); assertEquals("issue",parsed.get(0).assessment);
        assertEquals("submitted-1",parsed.get(0).evidence.get(0).chunkId);
        assertEquals("Twelve source characters",parsed.get(0).evidence.get(0).quote);
        verify(provider,times(1)).chatStructured(anyList(),any());verify(provider,never()).chat(anyList());
    }

    @Test void exactProductionMaximaAndMinimumQuoteLengthAreInclusive() {
        ObjectNode item=issue();item.put("title",repeat("a",160));item.put("comment",repeat("c",1200));
        item.put("impact",repeat("i",600));item.put("suggestion",repeat("s",600));
        evidence(item).put("quote",repeat("q",1200));
        ArrayNode quotes=(ArrayNode)item.get("evidence");
        for(int i=1;i<4;i++)quotes.add(quotes.get(0).deepCopy());
        ((ObjectNode)quotes.get(1)).put("quote",repeat("q",12));
        ArrayNode rows=JsonUtils.mapper().createArrayNode();for(int i=0;i<3;i++)rows.add(item.deepCopy());
        assertEquals(3,decode(rows.toString()).size());
    }

    static Stream<Arguments> schemaViolations() {
        List<Arguments> cases=new ArrayList<>();
        for(String field:Arrays.asList("assessment","type","severity")) {
            ObjectNode item=issue();item.put(field,"invalid");cases.add(Arguments.of("enum_"+field,array(item)));
        }
        for(String field:Arrays.asList("assessment","type","severity","title","comment","impact","suggestion","evidence")) {
            ObjectNode missing=issue();missing.remove(field);cases.add(Arguments.of("missing_"+field,array(missing)));
            ObjectNode nil=issue();nil.putNull(field);cases.add(Arguments.of("null_"+field,array(nil)));
        }
        ObjectNode unknown=issue();evidence(unknown).put("chunkId","not-submitted");cases.add(Arguments.of("unknown_chunk_id",array(unknown)));
        ObjectNode side=issue();evidence(side).put("side","not-a-side");cases.add(Arguments.of("invalid_side",array(side)));
        for(String field:Arrays.asList("chunkId","side","quote")) {
            ObjectNode item=issue();evidence(item).remove(field);cases.add(Arguments.of("missing_evidence_"+field,array(item)));
        }
        for(String field:Arrays.asList("title","comment","impact","suggestion")) {
            ObjectNode item=issue();item.put(field,"");cases.add(Arguments.of("min_length_"+field,array(item)));
        }
        for(String field:Arrays.asList("title","comment","impact","suggestion","quote")) {
            int max="title".equals(field)?160:"comment".equals(field)||"quote".equals(field)?1200:600;
            ObjectNode item=issue();if("quote".equals(field))evidence(item).put(field,repeat("x",max+1));
            else item.put(field,repeat("x",max+1));cases.add(Arguments.of("max_length_"+field,array(item)));
        }
        ObjectNode shortQuote=issue();evidence(shortQuote).put("quote",repeat("q",11));cases.add(Arguments.of("quote_min_length",array(shortQuote)));
        ObjectNode extra=issue();extra.put("unknown","synthetic_private_fragment");cases.add(Arguments.of("additional_property",array(extra)));
        ObjectNode extraQuote=issue();evidence(extraQuote).put("unknown","synthetic_private_fragment");cases.add(Arguments.of("nested_additional_property",array(extraQuote)));
        ObjectNode noQuotes=issue();noQuotes.putArray("evidence");cases.add(Arguments.of("min_items_evidence",array(noQuotes)));
        ObjectNode fiveQuotes=issue();ArrayNode quotes=(ArrayNode)fiveQuotes.get("evidence");for(int i=1;i<5;i++)quotes.add(quotes.get(0).deepCopy());
        cases.add(Arguments.of("max_items_evidence",array(fiveQuotes)));
        cases.add(Arguments.of("max_items_records","["+issue()+","+issue()+","+issue()+","+issue()+"]"));
        ObjectNode wrongType=issue();wrongType.put("title",123);cases.add(Arguments.of("number_in_string",array(wrongType)));
        ObjectNode objectEvidence=issue();objectEvidence.set("evidence",evidence(objectEvidence).deepCopy());cases.add(Arguments.of("object_in_array",array(objectEvidence)));
        ObjectNode stringQuote=issue();((ArrayNode)stringQuote.get("evidence")).removeAll().add("quote");cases.add(Arguments.of("string_in_object_array",array(stringQuote)));
        return cases.stream();
    }

    @ParameterizedTest(name="{0}") @MethodSource("schemaViolations")
    void productionSchemaRejectsAllBadRecordsAtomicallyWithNoRetry(String name,String raw) {
        LlmClient provider=provider(raw);List<String> captured=new ArrayList<>();Map<String,Long> timings=new LinkedHashMap<>();
        StructuredOutputValidationException failure=assertThrows(StructuredOutputValidationException.class,
                ()->new AiGateway(provider).completeStructuredJsonList("system","source",JsonNode.class,actualSchema("submitted-1"),captured,timings),name);
        assertEquals(SCHEMA_MISMATCH,failure.getReason(),name);assertEquals("structured_output_invalid",failure.getFailureKind());
        assertEquals(Collections.singletonList(raw),captured);assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("synthetic_private_fragment"));
        assertTrue(timings.containsKey("structured_schema_validation"));assertTrue(timings.containsKey("parse_list"));
        verify(provider,times(1)).chatStructured(anyList(),any());verify(provider,never()).chat(anyList());
    }

    static Stream<Arguments> badWholeDocuments() {
        String row=issue().toString();
        return Stream.of(
                Arguments.of("null Java content",null,INVALID_JSON),Arguments.of("empty content","",INVALID_JSON),
                Arguments.of("whitespace only"," \t\r\n",INVALID_JSON),Arguments.of("literal null","null",SCHEMA_MISMATCH),
                Arguments.of("object wrapper","{\"items\":["+row+"]}",SCHEMA_MISMATCH),
                Arguments.of("array wrapper","[{\"items\":["+row+"]}]",SCHEMA_MISMATCH),
                Arguments.of("mixed wrapper","["+row+",{\"items\":["+row+"]}]",SCHEMA_MISMATCH),
                Arguments.of("null record","[null]",SCHEMA_MISMATCH),
                Arguments.of("mixed valid and null","["+row+",null]",SCHEMA_MISMATCH),
                Arguments.of("nested array","[["+row+"]]",SCHEMA_MISMATCH),
                Arguments.of("scalar root","42",SCHEMA_MISMATCH),Arguments.of("scalar item","[42]",SCHEMA_MISMATCH),
                Arguments.of("leading prose","Result is ["+row+"]",INVALID_JSON),
                Arguments.of("trailing prose","["+row+"] explanation",INVALID_JSON),
                Arguments.of("trailing JSON","["+row+"] []",INVALID_JSON),
                Arguments.of("markdown fence","```json\n["+row+"]\n```",INVALID_JSON),
                Arguments.of("truncated array","["+row,INVALID_JSON),Arguments.of("truncated record","[{\"assessment\":",INVALID_JSON),
                Arguments.of("duplicate record key","[{\"title\":\"first\","+row.substring(1)+"]",INVALID_JSON),
                Arguments.of("duplicate nested key",array(issue()).replace("\"side\":\"source\"","\"side\":\"source\",\"side\":\"reference\""),INVALID_JSON),
                Arguments.of("escaped equivalent key",array(issue()).replace("\"side\":\"source\"","\"side\":\"source\",\"\\u0073ide\":\"reference\""),INVALID_JSON),
                Arguments.of("comment syntax","/*comment*/[]",INVALID_JSON),
                Arguments.of("non-JSON numeric token","[NaN]",INVALID_JSON));
    }

    @ParameterizedTest(name="{0}") @MethodSource("badWholeDocuments")
    void strictParserNeverExtractsUnwrapsOrReturnsPartialRows(String name,String raw,StructuredOutputValidationException.Reason reason) {
        LlmClient provider=provider(raw);List<String> captured=new ArrayList<>();
        StructuredOutputValidationException failure=assertThrows(StructuredOutputValidationException.class,
                ()->new AiGateway(provider).completeStructuredJsonList("system","source",JsonNode.class,actualSchema("submitted-1"),captured),name);
        assertEquals(reason,failure.getReason(),name);assertEquals(Collections.singletonList(raw),captured);
        verify(provider,times(1)).chatStructured(anyList(),any());verify(provider,never()).chat(anyList());
    }

    static Stream<Arguments> unsupportedOrInvalidSchemas() {
        return Stream.of(
                Arguments.of("pattern","{\"type\":\"array\",\"items\":{\"type\":\"string\",\"pattern\":\"x\"}}",UNSUPPORTED_SCHEMA),
                Arguments.of("reference","{\"type\":\"array\",\"$ref\":\"#/definitions/row\"}",UNSUPPORTED_SCHEMA),
                Arguments.of("annotation","{\"type\":\"array\",\"description\":\"annotation unsupported\"}",UNSUPPORTED_SCHEMA),
                Arguments.of("allOf","{\"type\":\"array\",\"allOf\":[]}",UNSUPPORTED_SCHEMA),
                Arguments.of("uniqueItems","{\"type\":\"array\",\"uniqueItems\":true}",UNSUPPORTED_SCHEMA),
                Arguments.of("number type","{\"type\":\"array\",\"items\":{\"type\":\"number\"}}",UNSUPPORTED_SCHEMA),
                Arguments.of("union type","{\"type\":[\"array\",\"null\"]}",INVALID_SCHEMA),
                Arguments.of("boolean schema","true",INVALID_SCHEMA),Arguments.of("missing type","{}",INVALID_SCHEMA),
                Arguments.of("non-array root schema","{\"type\":\"object\"}",INVALID_SCHEMA),
                Arguments.of("schema-valued additionalProperties","{\"type\":\"array\",\"items\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}}",UNSUPPORTED_SCHEMA),
                Arguments.of("mismatched keyword type","{\"type\":\"array\",\"minLength\":1}",UNSUPPORTED_SCHEMA),
                Arguments.of("negative bounds","{\"type\":\"array\",\"minItems\":-1}",INVALID_SCHEMA),
                Arguments.of("fractional bounds","{\"type\":\"array\",\"maxItems\":1.5}",INVALID_SCHEMA),
                Arguments.of("string bounds","{\"type\":\"array\",\"maxItems\":\"3\"}",INVALID_SCHEMA),
                Arguments.of("overflow bounds","{\"type\":\"array\",\"maxItems\":2147483648}",INVALID_SCHEMA),
                Arguments.of("inverted bounds","{\"type\":\"array\",\"minItems\":4,\"maxItems\":3}",INVALID_SCHEMA),
                Arguments.of("nonobject items","{\"type\":\"array\",\"items\":[]}",INVALID_SCHEMA),
                Arguments.of("nonobject properties","{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":[]}}",INVALID_SCHEMA),
                Arguments.of("nonarray required","{\"type\":\"array\",\"items\":{\"type\":\"object\",\"required\":\"x\"}}",INVALID_SCHEMA),
                Arguments.of("duplicate required","{\"type\":\"array\",\"items\":{\"type\":\"object\",\"required\":[\"x\",\"x\"]}}",INVALID_SCHEMA),
                Arguments.of("nonstring enum","{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[1]}}",INVALID_SCHEMA),
                Arguments.of("duplicate enum","{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"x\",\"x\"]}}",INVALID_SCHEMA));
    }

    @ParameterizedTest(name="{0}") @MethodSource("unsupportedOrInvalidSchemas")
    void schemaErrorsFailBeforeAvailabilityAndDispatch(String name,String json,StructuredOutputValidationException.Reason reason) {
        LlmClient provider=mock(LlmClient.class);Map<String,Long> timings=new LinkedHashMap<>();
        StructuredOutputValidationException failure=assertThrows(StructuredOutputValidationException.class,
                ()->new AiGateway(provider).completeStructuredJsonList("system","source",JsonNode.class,JsonUtils.parse(json),new ArrayList<>(),timings),name);
        assertEquals(reason,failure.getReason(),name);verifyNoInteractions(provider);
        assertEquals(Collections.singleton("structured_schema_validation"),timings.keySet());
    }

    @Test void nullSchemaIsAnExplicitSchemaFailureWithoutAnyProviderCall() {
        LlmClient provider=mock(LlmClient.class);
        assertEquals(INVALID_SCHEMA,assertThrows(StructuredOutputValidationException.class,
                ()->new AiGateway(provider).completeStructuredJsonList("system","source",JsonNode.class,null)).getReason());
        verifyNoInteractions(provider);
    }

    @Test void unicodeLengthsCountCodePointsAndNotUtf16OrGraphemes() {
        String supplementary="\uD83D\uDE00";
        ObjectNode item=issue();item.put("title",repeat(supplementary,160));evidence(item).put("quote",repeat(supplementary,12));
        assertEquals(1,decode(array(item)).size());
        item.put("title",repeat(supplementary,161));
        assertEquals(SCHEMA_MISMATCH,assertThrows(StructuredOutputValidationException.class,()->decode(array(item))).getReason());
        item.put("title","title");evidence(item).put("quote",repeat(supplementary,6));
        assertEquals(12,evidence(item).get("quote").textValue().length());
        assertThrows(StructuredOutputValidationException.class,()->decode(array(item)));
        evidence(item).put("quote",repeat("e\u0301",6));assertEquals(1,decode(array(item)).size());
        evidence(item).put("quote",repeat(supplementary,1200));assertEquals(1,decode(array(item)).size());
        evidence(item).put("quote",repeat(supplementary,1201));assertThrows(StructuredOutputValidationException.class,()->decode(array(item)));
    }

    @Test void emptyIdEnumAllowsEmptyResultButNoFabricatedEvidenceId() {
        LlmClient provider=provider("[]");
        assertTrue(new AiGateway(provider).completeStructuredJsonList("system","source",JsonNode.class,actualSchema()).isEmpty());
        LlmClient fabricated=provider(array(issue()));
        assertEquals(SCHEMA_MISMATCH,assertThrows(StructuredOutputValidationException.class,
                ()->new AiGateway(fabricated).completeStructuredJsonList("system","source",JsonNode.class,actualSchema())).getReason());
    }

    @Test void compiledSchemaSnapshotCannotBeWeakenedByProviderMutation() {
        JsonNode schema=actualSchema("submitted-1");JsonNode original=schema.deepCopy();
        ObjectNode bad=issue();bad.put("assessment","not-allowed");
        LlmClient provider=mock(LlmClient.class);when(provider.available()).thenReturn(true);
        when(provider.chatStructured(anyList(),any())).thenAnswer(call->{
            ObjectNode sent=call.getArgument(1);((ObjectNode)sent.path("items").path("properties").path("assessment")).putArray("enum").add("not-allowed");
            return array(bad);
        });
        assertEquals(SCHEMA_MISMATCH,assertThrows(StructuredOutputValidationException.class,
                ()->new AiGateway(provider).completeStructuredJsonList("system","source",JsonNode.class,schema)).getReason());
        assertEquals(original,schema);
    }

    @Test void incompatibleElementMappingIsASeparateFixedFailureAndDoesNotRetry() {
        LlmClient provider=provider(array(issue()));List<String> raw=new ArrayList<>();
        assertEquals(ELEMENT_MAPPING_FAILED,assertThrows(StructuredOutputValidationException.class,
                ()->new AiGateway(provider).completeStructuredJsonList("system","source",String.class,actualSchema("submitted-1"),raw)).getReason());
        assertEquals(Collections.singletonList(array(issue())),raw);verify(provider,times(1)).chatStructured(anyList(),any());
    }

    @Test void booleanAdditionalPropertiesTrueAndDefaultSemanticsAreSupported() {
        for(String additional:Arrays.asList("",",\"additionalProperties\":true")) {
            JsonNode schema=JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]"+additional+"}}");
            assertEquals(1,new AiGateway(provider("[{\"name\":\"x\",\"extra\":{\"any\":[null,42]}}]"))
                    .completeStructuredJsonList("system","source",JsonNode.class,schema).size());
        }
    }

    @Test void arrayMinimumAndOptionalConstraintsUseSupportedSubsetSemantics() {
        JsonNode schema=JsonUtils.parse("{\"type\":\"array\",\"minItems\":1,\"maxItems\":1,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1,\"enum\":[\"x\"]}}");
        assertEquals(Collections.singletonList("x"),new AiGateway(provider("[\"x\"]")).completeStructuredJsonList("system","source",String.class,schema));
        assertThrows(StructuredOutputValidationException.class,()->new AiGateway(provider("[]")).completeStructuredJsonList("system","source",String.class,schema));
        JsonNode unrestrictedItems=JsonUtils.parse("{\"type\":\"array\"}");
        assertTrue(new AiGateway(provider("[]")).completeStructuredJsonList("system","source",JsonNode.class,unrestrictedItems).isEmpty());
        assertThrows(StructuredOutputValidationException.class,()->new AiGateway(provider("[[1]]")).completeStructuredJsonList("system","source",JsonNode.class,unrestrictedItems));
        assertThrows(StructuredOutputValidationException.class,()->new AiGateway(provider("[null]")).completeStructuredJsonList("system","source",JsonNode.class,unrestrictedItems));
    }

    @Test void ordinaryListRetainsWrapperFenceExtractionAndRetryCompatibility() {
        LlmClient provider=mock(LlmClient.class);when(provider.available()).thenReturn(true);
        when(provider.chat(anyList())).thenReturn("```json\n{\"items\":[\"ordinary\"]}\n``` trailing prose","[{broken", "{\"items\":[\"retried\"]}");
        AiGateway gateway=new AiGateway(provider);
        assertEquals(Collections.singletonList("ordinary"),gateway.completeJsonList("system","source",String.class));
        assertEquals(Collections.singletonList("retried"),gateway.completeJsonList("system","source",String.class));
        verify(provider,times(3)).chat(anyList());verify(provider,never()).chatStructured(anyList(),any());
        assertEquals("last",JsonUtils.parse("{\"ordinary\":\"first\",\"ordinary\":\"last\"} trailing").path("ordinary").asText());
    }

    public static class DecodedIssue {
        public String assessment,type,severity,title,comment,impact,suggestion;
        public List<DecodedQuote> evidence;
    }
    public static class DecodedQuote { public String chunkId,side,quote; }
}
