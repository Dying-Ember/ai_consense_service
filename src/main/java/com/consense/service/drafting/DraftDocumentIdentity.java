package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

/** The confirmed identity must appear even when a standard template has no cover fields. */
public final class DraftDocumentIdentity {
    private DraftDocumentIdentity() { }
    public static String include(String content, String confirmedValue) {
        JsonNode identity = JsonUtils.parse(confirmedValue);
        String header = "Contract No.: " + identity.path("number").asText()
                + "\nContract Title: " + identity.path("title").asText() + "\n\n";
        return content.startsWith(header) ? content : header + content;
    }
}
