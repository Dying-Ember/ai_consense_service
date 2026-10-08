package com.consense.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonUtilsReasoningTest {
    @Test void skipsJsonExamplesInsideReasoning() {
        String raw = "<think>Consider {\"wrong\":true} or [1,2].</think>\n```json\n[{\"key\":\"periodAtLeast39Months\",\"value\":\"false\"}]\n```";
        assertEquals("periodAtLeast39Months", JsonUtils.parse(JsonUtils.extractJson(raw)).get(0).get("key").asText());
    }
    @Test void rejectsUnfinishedReasoningInsteadOfAcceptingItsExample() {
        assertThrows(BizException.class, () -> JsonUtils.extractJson("<think>Example {\"content\":\"not the answer\"}"));
    }
    @Test void preservesThinkTextInsideAnActualJsonString() {
        String raw = "{\"content\":\"The text <think> is literal.\"}";
        assertEquals(raw, JsonUtils.extractJson(raw));
    }
}
