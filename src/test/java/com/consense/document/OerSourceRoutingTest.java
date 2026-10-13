package com.consense.document;

import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OerSourceRoutingTest {
    private DocumentParser.ParsedDocument parse(String text) {
        return new DocumentParser(new ConsenseProperties(), mock(OcrClient.class)).parse("source.txt", text.getBytes(StandardCharsets.UTF_8));
    }
    @Test void exactTrustedTemplateIdentityOverridesMisleadingFilenameAndRetainsDraftingOnly() {
        DocumentParser.ParsedDocument source = parse("Notice to Tenderers\nUnfilled reference template");
        OerSourceRouting router = new OerSourceRouting(Collections.singletonList(new OerSourceRouting.FixedSource(source.getSourceSha256(), "NTT", "standard_template", "standard", "v1")));
        OerDocumentIdentity identity = router.route("project-GCC.pdf", source.getSourceSha256(), source);
        assertEquals("NTT", identity.getKind()); assertEquals("sha_verified", identity.getIdentityStatus());
        assertEquals("standard_template", identity.getSourcePurpose()); assertEquals(Collections.singletonList("drafting"), identity.getEligibleModules());
    }
    @Test void actualProjectNttAllowsReviewAndAdvice() {
        DocumentParser.ParsedDocument source = parse("NOTICE TO TENDERERS\nContract No: TEST/2046/02\nTender submission requirements");
        OerDocumentIdentity identity = new OerSourceRouting().route("NTT.docx", source.getSourceSha256(), source);
        assertEquals("project_document", identity.getSourcePurpose()); assertTrue(identity.getEligibleModules().containsAll(Arrays.asList("vetting", "advisory")));
    }
    @Test void splitParticularSpecificationCoverDoesNotExcludeProjectPre() {
        DocumentParser.ParsedDocument source = parse("PARTICULAR SPECIFICATION\nPRELIMINARIES\nContract No: TEST/2046/02\nMaterials and workmanship");
        OerDocumentIdentity identity = new OerSourceRouting().route("PRE.docx", source.getSourceSha256(), source);
        assertEquals("PRE", identity.getKind()); assertEquals("project_document", identity.getSourcePurpose()); assertTrue(identity.getEligibleModules().contains("vetting"));
    }
    @Test void pricingAndTechnicalPssRequireTitleEvidenceBeforeExclusion() {
        for (String title : Arrays.asList("BILLS OF QUANTITIES", "GENERAL SUMMARY", "PARTICULAR SPECIFICATION - STRUCTURAL WORKS")) {
            DocumentParser.ParsedDocument source = parse(title + "\nContract No: TEST/2046/02\nMaterials construction price quantity amount total");
            assertEquals(Collections.emptyList(), new OerSourceRouting().route("source.pdf", source.getSourceSha256(), source).getEligibleModules());
        }
        DocumentParser.ParsedDocument body = parse("NOTICE TO TENDERERS\nContract No: TEST/2046/02\nThe General Summary and Bills of Quantities shall be submitted separately.");
        assertEquals("NTT", new OerSourceRouting().route("source.pdf", body.getSourceSha256(), body).getKind());
    }
    @Test void filenameAndBodyKeywordAloneStayUnresolvedAndRetainCandidates() {
        DocumentParser.ParsedDocument source = parse("The notice to tenderers refers to the general summary.\nUnrelated descriptive material.");
        OerDocumentIdentity identity = new OerSourceRouting().route("NTT.docx", source.getSourceSha256(), source);
        assertEquals("UNKNOWN", identity.getKind()); assertNull(identity.getEligibleModules()); assertFalse(identity.getCandidates().isEmpty());
    }
    @Test void unfilledTitleAndPlaceholderProjectNumberDoNotProveTemplateOrProject() {
        DocumentParser.ParsedDocument source = parse("NOTICE TO TENDERERS\nContract No: not provided\nTender submission requirements");
        OerDocumentIdentity identity = new OerSourceRouting().route("NTT.docx", source.getSourceSha256(), source);
        assertEquals("unknown", identity.getSourcePurpose()); assertNull(identity.getEligibleModules());
        assertTrue(identity.getCandidates().stream().anyMatch(c -> "standard_template".equals(c.getSourcePurpose())));
    }
    @Test void wrongOriginalByteIdentityFailsClosed() {
        DocumentParser.ParsedDocument source = parse("GENERAL CONDITIONS OF CONTRACT\nConditions");
        OerDocumentIdentity identity = new OerSourceRouting().route("GCC.pdf", String.join("", Collections.nCopies(64, "a")), source);
        assertEquals("source_identity_mismatch", identity.getIdentityStatus()); assertNull(identity.getEligibleModules());
    }
    @Test void gccReferenceRetainedAndInvalidTemplateRegistryRejected() {
        DocumentParser.ParsedDocument source = parse("GENERAL CONDITIONS OF CONTRACT\nStandard edition 2026");
        OerDocumentIdentity identity = new OerSourceRouting().route("GCC.pdf", source.getSourceSha256(), source);
        assertEquals("reference_standard", identity.getSourcePurpose()); assertEquals("standard", identity.getRole());
        assertTrue(identity.getEligibleModules().contains("advisory"));
        assertThrows(IllegalArgumentException.class, () -> new OerSourceRouting(Collections.singletonList(new OerSourceRouting.FixedSource(source.getSourceSha256(), "GCC", "standard_template", "standard", "v1"))));
    }
}
