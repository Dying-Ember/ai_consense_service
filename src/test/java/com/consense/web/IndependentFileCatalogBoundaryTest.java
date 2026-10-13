package com.consense.web;

import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Additional source-boundary cases, using the original real MVC/JPA fixture. */
class IndependentFileCatalogBoundaryTest extends VettingFileCatalogJpaHttpTest {
    @Test void newerPricingSupplementSuppressesEarlierEligibleSameNamedContract() throws Exception {
        source("10 - SCC.docx", "SCC", "Old eligible contractual source must not return.");
        SourceDocument newer=source(" 10 - scc.DOCX ", "SCC", "New pricing upload of same named source.");
        newer.setCategory(SourceDocument.CATEGORY_VETTING_SUPPLEMENT);
        newer.setDocumentScope("pricing");
        newer.setReviewRole("tender");
        documents.saveAndFlush(newer);
        source("GCC.pdf", "GCC", "Another complete current source.");
        clear();
        JsonNode rows=data();
        assertEquals(1, rows.size());
        assertEquals("GCC", rows.get(0).path("key").asText());
        assertNoArchiveHydration();
    }

    @Test void excludedIdentityPurposesRemainExcludedDespiteExplicitTenderAndEligibleList() throws Exception {
        SourceDocument template=source("10 - SCC.docx", "SCC", "Template body cannot become a review source.");
        template.setReviewRole("tender");
        template.setDocumentScope("package");
        template.setSourceSha256("a".repeat(64));
        template.setOerIdentityJson("{\"sourceSha256\":\""+"a".repeat(64)+"\",\"sourcePurpose\":\"standard_template\",\"eligibleModules\":[\"vetting\",\"advisory\"]}");
        documents.saveAndFlush(template);
        SourceDocument answer=source("03 - NTT.docx", "NTT", "Answer body cannot become a review source.");
        answer.setReviewRole("tender");
        answer.setDocumentScope("package");
        answer.setSourceSha256("b".repeat(64));
        answer.setOerIdentityJson("{\"sourceSha256\":\""+"b".repeat(64)+"\",\"sourcePurpose\":\"evaluation_answer\",\"eligibleModules\":[\"vetting\",\"advisory\"]}");
        documents.saveAndFlush(answer);
        source("GCC.pdf", "GCC", "Remaining eligible source.");
        clear();
        JsonNode rows=data();
        assertEquals(1, rows.size());
        assertEquals("GCC", rows.get(0).path("key").asText());
        assertNoArchiveHydration();
    }
}
