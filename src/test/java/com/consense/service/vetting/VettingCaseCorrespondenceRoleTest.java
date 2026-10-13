package com.consense.service.vetting;

import com.consense.domain.SourceDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingCaseCorrespondenceRoleTest {
    @Test void projectCocAdoptionSheetIsTenderEvidenceWhileGeneralConditionsBaselineIsStandard() {
        SourceDocument d = new SourceDocument();
        d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setFileKey("GCC"); d.setFileName("09 - CoC.docx");
        d.setTextContent("Conditions of Contract for use with this Contract No. 20250101: GCC 2013 version 1.1 and the SCC and Appendices.");
        assertEquals("tender", VettingCorpus.sourceRole(d), "Project CoC adoption is a project contract provision");
        d.setFileName("05_General Conditions of Contract (GCC).pdf");
        assertEquals("standard", VettingCorpus.sourceRole(d));
        d.setFileName("09 - CoC.docx"); d.setReviewRole("standard");
        assertEquals("standard", VettingCorpus.sourceRole(d), "Explicit baseline role remains authoritative");
    }
    @Test void caseWordEmailsAreFactsAndExplicitTenderRoleStillWins() {
        for (String name : new String[]{"2024-12-19_from QS to Architect and Structrual Engineer.docx",
                "2024-12-22_from Structural Engineer to QS.docx", "2024-12-23_from Architect to QS.docx"}) {
            SourceDocument d = new SourceDocument(); d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE); d.setFileName(name);
            assertEquals("project_fact", VettingCorpus.sourceRole(d));
            d.setReviewRole("tender"); assertEquals("tender", VettingCorpus.sourceRole(d));
        }
    }
}
