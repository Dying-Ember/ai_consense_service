package com.consense.service.drafting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftDocumentIdentityTest {
    @Test void includesConfirmedIdentityWithoutChangingSourceClauses() {
        String source = "10. Sureties\nAppendix G1.\n13. Sub-contracting\nKeep the source clause.";
        String result = DraftDocumentIdentity.include(source, "{\"number\":\"20250101\",\"title\":\"Tung Chung Area 98\"}");
        assertTrue(result.startsWith("Contract No.: 20250101\nContract Title: Tung Chung Area 98\n\n"));
        assertTrue(result.endsWith(source));
        assertEquals(result, DraftDocumentIdentity.include(result, "{\"number\":\"20250101\",\"title\":\"Tung Chung Area 98\"}"));
    }
}
