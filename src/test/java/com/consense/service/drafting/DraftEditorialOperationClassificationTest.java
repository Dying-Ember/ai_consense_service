package com.consense.service.drafting;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Existing saved operation receipts must not bypass source and target identity. */
class DraftEditorialOperationClassificationTest {
    private static final String SOURCE="7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4";
    @Test void exactCatalogOperationAndItsCompiledScopeSuffixAreEditorial() {
        String id="SCT-editorial-note-12-P163";
        assertEquals(Arrays.asList(id),DraftFormattedRules.editorialOperations(SOURCE,163,Arrays.asList(id,"unknown-cleanup")));
        String removal="SCT-editorial-note-01-P55-remove-scope";
        assertEquals(Arrays.asList(removal),DraftFormattedRules.editorialOperations(SOURCE,55,Arrays.asList(removal)));
    }
    @Test void differentSourceOrdinalOrOperationIsNeverPromotedToVerified() {
        assertTrue(DraftFormattedRules.editorialOperations("changed-edition",163,Arrays.asList("SCT-editorial-note-12-P163")).isEmpty());
        assertTrue(DraftFormattedRules.editorialOperations(SOURCE,164,Arrays.asList("SCT-editorial-note-12-P163")).isEmpty());
        assertTrue(DraftFormattedRules.editorialOperations(SOURCE,null,Arrays.asList("SCT-editorial-note-12-P163")).isEmpty());
        assertTrue(DraftFormattedRules.editorialOperations(SOURCE,163,Arrays.asList("unknown-cleanup","SCT-editorial-note-12-P163x","SCT-editorial-note-12-P163-unknown-suffix","SCT-editorial-note-12-P163-remove-scope")).isEmpty());
    }
    @Test void readingAnExistingSavedBundleClassifiesOnlyActualOperationReceipts() {
        Map<String,Object> row=DraftBusinessRules.map("sourceParagraphOrdinal",163,"operationIds",Arrays.asList("SCT-editorial-note-12-P163","unknown-cleanup"),"locationStatus","removed");
        Map<String,Object> actual=DraftDocumentBindings.bundle("SCT","result",SOURCE,"revision","docx",Arrays.asList(row)).getBindings().get(0);
        assertEquals(Arrays.asList("SCT-editorial-note-12-P163"),actual.get("editorialOperationIds"));
        assertEquals(Arrays.asList("SCT-editorial-note-12-P163","unknown-cleanup"),actual.get("operationIds"));
    }
    @Test void retainedOptionalHeadingMarkersRequireTheirExactSourceOrdinalAndActualReceipt() {
        String ntt="60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c";
        for(int p:new int[]{435,550,569,1015}) {
            String key=p==435?"NTT":"SCT",source=p==435?ntt:SOURCE,id=key+"-prefix-star-P"+p;
            assertEquals(Arrays.asList(id),DraftFormattedRules.editorialOperations(source,p,Arrays.asList(id)));
            assertTrue(DraftFormattedRules.editorialOperations(source,p,Collections.emptyList()).isEmpty());
            assertTrue(DraftFormattedRules.editorialOperations("other-edition",p,Arrays.asList(id)).isEmpty());
            assertTrue(DraftFormattedRules.editorialOperations(source,p+1,Arrays.asList(id)).isEmpty());
            assertTrue(DraftFormattedRules.editorialOperations(source,p,Arrays.asList(id+"-unverified-span")).isEmpty());
        }
    }
    @Test void unresolvedSpecialistBillOrScheduleChoiceMarkersAreNeverEditorialCleanup() {
        String scc="9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208";
        for(int p:new int[]{1428,1548,1777})assertTrue(DraftFormattedRules.editorialOperations(scc,p,Arrays.asList("SCC-prefix-star-P"+p)).isEmpty());
    }
}
