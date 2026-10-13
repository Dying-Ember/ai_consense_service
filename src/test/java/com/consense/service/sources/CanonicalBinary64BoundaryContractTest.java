package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** CPython-authored binary64 boundary bytes, no source/adoption/model acceptance. */
class CanonicalBinary64BoundaryContractTest {
    private static final String SHA="43a3d4784cf7c35097ea49cfb508f3c62fb14645fbaa4f755602904f85d1d3b1";
    private static JsonNode oracle()throws Exception {
        byte[] bytes=NativeCanonicalFixture.bytes("authored-python-binary64-boundary-oracle.json");
        assertEquals(SHA,CanonicalJson.sha(bytes));return JsonUtils.parse(new String(bytes,StandardCharsets.UTF_8));
    }
    @Test void everyFinitePowerOfTwoAndImmediateNeighborsUseExactPythonCanonicalToken()throws Exception {
        JsonNode fixture=oracle();assertEquals(12616,fixture.path("positive").size());
        for(JsonNode row:fixture.path("positive")){
            String token=row.path("canonical").asText();
            assertEquals(token,CanonicalJson.strictCanonical(token),row.path("family").asText()+" bits="+row.path("bits").asText());
        }
    }
    @Test void sameBinary64BitsNeverPermitExtraDecimalDigitsOrKnownLongerPowerOfTwoTokens()throws Exception {
        JsonNode fixture=oracle();assertEquals(12616,fixture.path("negative").size());
        for(JsonNode row:fixture.path("negative")){
            String token=row.path("token").asText();
            assertEquals(Double.doubleToRawLongBits(Double.parseDouble(row.path("canonical").asText())),Double.doubleToRawLongBits(Double.parseDouble(token)));
            assertThrows(CanonicalEvidenceException.class,()->CanonicalJson.strictCanonical(token),row.path("family").asText());
        }
        assertEquals(12,fixture.path("independentCounterexamples").size());
        for(JsonNode row:fixture.path("independentCounterexamples")){
            String token=row.path("token").asText();
            if(row.path("pythonCanonical").asBoolean())assertEquals(token,CanonicalJson.strictCanonical(token));
            else assertThrows(CanonicalEvidenceException.class,()->CanonicalJson.strictCanonical(token),token);
        }
    }
}
