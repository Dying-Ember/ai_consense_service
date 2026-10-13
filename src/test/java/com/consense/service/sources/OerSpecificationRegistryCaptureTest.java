package com.consense.service.sources;

import com.consense.document.DocumentParser;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual original bytes plus an explicitly authored parsed-state fixture exercise
 * the production default-registry capture seam. No original parsing, OCR, DB,
 * legal adoption, reference-edition matching or whole-source quality is claimed.
 */
class OerSpecificationRegistryCaptureTest {
    private static final String ORIGINAL_SHA = "3fa27b8e63e55f881260800a56e7a07218c1c1ed927e98292decd54af924b24e";
    private static final String FIXTURE_TEXT = "The Specification\n"
            + "Specification Library (August 2024)\n"
            + "AUTHORED parsed-state capture seam fixture; not a fresh parse result.";
    private static byte[] original;

    @BeforeAll static void require_exact_original_bytes() throws Exception {
        String source = System.getProperty("consense.test.specification-source");
        // Real-source cases are opt-in so default CI does not require private assets.
        if (source == null || source.isEmpty()) return;
        original = Files.readAllBytes(Paths.get(source));
        assertEquals(29528, original.length);
        assertEquals(ORIGINAL_SHA, OerSourceMetadata.originalSha256(original));
    }

    private static SourceDocument document() {
        SourceDocument d = new SourceDocument();
        d.setId(17L); d.setProjectId("AUTHORED-q13-capture-project");
        d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setFileName("24 - The Specification.docx");
        d.setFileKey("SP"); d.setReviewRole("tender"); d.setDocumentScope("package");
        d.setParseStatus("PARSED"); d.setTextContent(FIXTURE_TEXT);
        return d;
    }

    private static DocumentParser.ParsedDocument parsed(String sha) {
        DocumentParser.ParsedDocument fixture = new DocumentParser.ParsedDocument(
                FIXTURE_TEXT, Collections.emptyList(), 0, false, "AUTHORED capture seam only");
        return new DocumentParser.ParsedDocument(fixture.getText(), fixture.getPages(), 0,
                false, fixture.getMessage(), Collections.emptyList(), fixture.getCoverage(),
                sha, Collections.emptyList());
    }

    @EnabledIfSystemProperty(named = "consense.test.specification-source", matches = ".+")
    @Test void exact_original_uses_packaged_registry_as_project_composition() {
        SourceDocument d = document();
        DocumentParser.ParsedDocument p = parsed(ORIGINAL_SHA);
        OerSourceMetadata.capture(d, p, original);
        OerDocumentIdentity identity = OerSourceMetadata.identity(d);
        assertEquals(ORIGINAL_SHA, d.getSourceSha256());
        assertEquals("SP", identity.getKind());
        assertEquals("SP", d.getFileKey());
        assertEquals("project_document", identity.getSourcePurpose());
        assertEquals("sha_verified", identity.getIdentityStatus());
        assertEquals("tender", identity.getRole());
        assertEquals("tender", VettingCorpus.sourceRole(d));
        assertEquals("provided-2b-specification-composition-20260904", identity.getVersion());
        assertTrue(identity.getEligibleModules().containsAll(Arrays.asList("vetting", "advisory")));
        assertNull(ProjectDocumentScope.exclusionReason(d, "fullset", "vetting"));
        assertNull(ProjectDocumentScope.exclusionReason(d, "fullset", "advisory"));
        assertEquals(FIXTURE_TEXT, p.getText());
        assertEquals(FIXTURE_TEXT, d.getTextContent());
        assertFalse(identity.getVersion().contains("April2024"));
    }

    @EnabledIfSystemProperty(named = "consense.test.specification-source", matches = ".+")
    @Test void a_changed_original_does_not_inherit_eligibility_from_its_filename() {
        byte[] changed = original.clone(); changed[changed.length - 1] ^= 1;
        String changedSha = OerSourceMetadata.originalSha256(changed);
        SourceDocument d = document();
        OerSourceMetadata.capture(d, parsed(changedSha), changed);
        OerDocumentIdentity identity = OerSourceMetadata.identity(d);
        assertNotEquals(ORIGINAL_SHA, changedSha);
        assertEquals(changedSha, d.getSourceSha256());
        assertEquals("UNKNOWN", identity.getKind());
        assertEquals("unknown", identity.getSourcePurpose());
        assertNull(identity.getEligibleModules());
    }

    @EnabledIfSystemProperty(named = "consense.test.specification-source", matches = ".+")
    @Test void parser_original_identity_mismatch_is_rejected_before_identity_persistence() {
        SourceDocument d = document();
        String wrongSha = String.join("", Collections.nCopies(64, "a"));
        assertThrows(IllegalArgumentException.class,
                () -> OerSourceMetadata.capture(d, parsed(wrongSha), original));
        assertNull(d.getOerIdentityJson());
    }

    @Test void authored_parsed_identity_uses_packaged_registry_without_original_asset() {
        // This default CI case supplies an authored parser identity. It is not
        // fresh parsing, original-byte verification, adoption or source quality.
        SourceDocument d = document();
        DocumentParser.ParsedDocument fixture = parsed(ORIGINAL_SHA);
        OerSourceMetadata.captureParsed(d, fixture);
        OerDocumentIdentity identity = OerSourceMetadata.identity(d);
        assertEquals(ORIGINAL_SHA, d.getSourceSha256());
        assertEquals("SP", identity.getKind());
        assertEquals("project_document", identity.getSourcePurpose());
        assertEquals("sha_verified", identity.getIdentityStatus());
        assertEquals("tender", identity.getRole());
        assertEquals("provided-2b-specification-composition-20260904", identity.getVersion());
        assertTrue(identity.getEligibleModules().containsAll(Arrays.asList("vetting", "advisory")));
        assertNull(ProjectDocumentScope.exclusionReason(d, "fullset", "vetting"));
        assertNull(ProjectDocumentScope.exclusionReason(d, "fullset", "advisory"));
        assertEquals(FIXTURE_TEXT, d.getTextContent());
        assertFalse(identity.getVersion().contains("April2024"));
    }

    @Test void existing_three_standard_templates_remain_drafting_only() {
        // Authored parser identities test the unchanged fixed registry entries;
        // this does not claim a fresh parse or source-quality acceptance of 2a.
        String[] templateShas = {
                "60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c",
                "7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4",
                "9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"};
        for (String sha : templateShas) {
            SourceDocument d = document();
            OerSourceMetadata.captureParsed(d, parsed(sha));
            OerDocumentIdentity identity = OerSourceMetadata.identity(d);
            assertEquals("standard_template", identity.getSourcePurpose());
            assertEquals(Collections.singletonList("drafting"), identity.getEligibleModules());
            assertEquals("drafting_standard_template", OerSourceMetadata.moduleExclusion(d, "vetting"));
            assertEquals("drafting_standard_template", OerSourceMetadata.moduleExclusion(d, "advisory"));
        }
    }
}
