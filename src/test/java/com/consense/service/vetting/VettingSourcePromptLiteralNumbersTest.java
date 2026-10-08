package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingSourcePromptLiteralNumbersTest {
    @ParameterizedTest
    @ValueSource(strings={"rawFuture","content","cells","text","quote"})
    void serializedLiteralNumbersKeepPrecisionScaleAndNeverParseText(String key) {
        Map<String,Object> raw=new LinkedHashMap<>();
        raw.put("precise",new BigDecimal("1.123456789012345678901234567890"));
        raw.put("scaled",new BigDecimal("1.0000"));raw.put("exponent",new BigDecimal("1E+100"));
        raw.put("text","{\"recordEncoding\":\"untrusted\",\"value\":1.0000}");
        Map<String,Object> source=new LinkedHashMap<>();source.put(key,raw);source.put("quality","unknown");
        String before=JsonUtils.write(source),encoded=VettingSourcePromptWire.write(source);
        assertEquals(before,JsonUtils.write(VettingSourcePromptWire.decode(encoded)));
        assertEquals(before,JsonUtils.write(source));assertTrue(encoded.contains("1.123456789012345678901234567890"));
        assertEquals(raw.get("text"),VettingSourcePromptWire.decode(encoded).get(key).get("text").asText());
    }
    @ParameterizedTest
    @ValueSource(doubles={-0.0,1.0e20,1.0e-20,1.0})
    void literalFloatJsonLexemesStayExact(double value) {
        Map<String,Object> source=new LinkedHashMap<>();source.put("rawNumbers",Arrays.asList(value,true,1,"1.0"));
        String encoded=VettingSourcePromptWire.write(source);
        assertEquals(JsonUtils.write(source),JsonUtils.write(VettingSourcePromptWire.decode(encoded)));
    }
}
