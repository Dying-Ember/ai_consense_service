package com.consense.service.drafting;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Formal names and descriptive qualifications have different roles in the same Bill passage. */
class DraftBillDescriptionSlotTest {
    @Test void classificationOrReferenceDoesNotEstablishAFormalName() {
        for(String source:Arrays.asList(
                "Bill 7 being labelled Zero Rated does not change the formal parent description.",
                "Bill 7 is classified as Zero Rated for pricing only.",
                "Bill 7 refers to Zero Rated work.",
                "Bill 7 Other Works | classification Zero Rated"))
            assertFalse(supported("Zero Rated",source),source);
    }

    @Test void fullNameCannotBeTruncatedAtAParenthesisOrConjunction() {
        assertFalse(supported("Other Works","Bill 7 Other Works (External Areas) | BQ"));
        assertFalse(supported("Other Works","Bill 7 Other Works and Drainage | BQ"));
        assertTrue(supported("Other Works (External Areas)","Bill 7 Other Works (External Areas) | BQ"));
        assertTrue(supported("Other Works and Drainage","Bill 7 Other Works and Drainage | BQ"));
    }

    @Test void formalCellsAndExplicitNamingStatementsRemainSupported() {
        for(String source:Arrays.asList("Bill 7 - Other Works | BQ.",
                "Bill 7 is formally named \"Other Works\".",
                "Bill 7 titled 'Other Works'.", "Bill 7 description: Other Works",
                "Bill 7 Other Works is a measured Bill."))
            assertTrue(supported("Other Works",source),source);
    }

    @Test void punctuationInsideTheActualNameIsRetained() {
        assertTrue(supported("Works A; Works B","Bill 7 Works A; Works B | BQ"));
        assertTrue(supported("Works A.","Bill 7 Works A. | BQ"));
        assertFalse(supported("Works A","Bill 7 Works A; Works B | BQ"));
    }

    @Test void pendingOrRequestedNameRemainsUnsupported() {
        assertFalse(supported("Other Works","Please confirm Bill 7 Other Works."));
        assertFalse(supported("Other Works","Bill 7 Other Works is pending."));
        assertFalse(supported("Other Works","Bill 7 Other Works: Is this the approved description?"));
    }

    @Test void subtitleCannotBeSilentlyDiscardedAsMetadata() {
        assertFalse(supported("Other Works","Bill 7 Other Works: External Roads."));
        assertTrue(supported("Other Works: External Roads","Bill 7 Other Works: External Roads."));
        // The identity can precede an unanswered type question; the separate type guard rejects that cell.
        assertTrue(supported("Other Works","Bill 7 Other Works: Is this a BQ?"));
    }

    private static boolean supported(String name,String source) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("number","7");row.put("description",name);
        return DraftCandidateGrounding.billIdentitiesSupported(Arrays.asList(row),source);
    }
}
