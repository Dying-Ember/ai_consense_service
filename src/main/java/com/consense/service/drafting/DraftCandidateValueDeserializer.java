package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Adapts model values to the existing string input contract without changing business validation. */
public final class DraftCandidateValueDeserializer extends JsonDeserializer<String> {
    @Override public String deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(parser.currentToken()==JsonToken.VALUE_STRING)return parser.getText();
        if(parser.currentToken()==JsonToken.VALUE_NULL)return null;
        JsonNode value=JsonUtils.mapper().readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .readValue(parser);
        return value.toString();
    }
}
