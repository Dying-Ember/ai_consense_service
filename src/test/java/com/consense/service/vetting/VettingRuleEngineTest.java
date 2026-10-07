package com.consense.service.vetting;

import com.consense.document.DocumentBlock;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VettingRuleEngineTest {
    private final VettingRuleEngine engine = new VettingRuleEngine();

    @Test void inclusiveClauseRangesAggregateExplicitInactiveSiblingsWithoutClaimingReinstatement() {
        VettingRuleEngine.Output issue = only(engine.review(Arrays.asList(source("clauses", "ZZZ",
                block("inactive1", "ZZZ83.42 Not used."), block("inactive2", "ZZZ83.46Not used"),
                block("contents", "ZZZ83.44Not used 7"),
                block("range", "Include Clauses ZZZ83.41 through ZZZ83.48 in the downstream agreement.")))), "inactive_clause_range_coordination");
        assertEquals(3, issue.getEvidence().size());
        assertTrue(issue.getSummary().contains("ZZZ83.42"));
        assertTrue(issue.getSummary().contains("ZZZ83.46"));
        assertTrue(issue.getSummary().contains("does not establish"));
        assertFalse(issue.getEvidence().stream().anyMatch(e -> "contents".equals(e.getBlockId())));
    }

    @Test void inclusiveClauseRangesRespectNamespaceSiblingDepthAndInactiveMetadata() {
        DocumentBlock inactive = block("inactive", "Current applicable wording.");
        inactive.setDeletedText("XYZ71.3(4) Not used."); inactive.setStrikeText("XYZ71.3(4) Not used.");
        assertTrue(engine.review(Arrays.asList(source("a", "XYZ", inactive,
                block("other", "ABC71.3(4) Not used."),
                block("different-notation", "XYZ71.3.4 Not used."),
                block("different-parent", "XYZ72.3(4) Not used."),
                block("range", "Apply Clauses XYZ71.3(2) to XYZ71.3(6).")))).isEmpty());
        VettingRuleEngine.Output nested = only(engine.review(Arrays.asList(source("a", "XYZ",
                block("inactive", "XYZ71.3(4) Not used."),
                block("range", "Apply Clauses XYZ71.3(2) to 71.3(6).")))), "inactive_clause_range_coordination");
        assertEquals(2, nested.getEvidence().size());
        assertTrue(engine.review(Arrays.asList(source("a", "XYZ",
                block("inactive", "XYZ71.3 Not used."),
                block("different-level", "Apply Clauses XYZ71.1(1) to XYZ71.1(9)."),
                block("reversed", "Apply Clauses XYZ71.8 to XYZ71.1.")))).isEmpty());
        assertTrue(engine.review(Arrays.asList(source("a", "XYZ",
                block("inactive", "XYZ71.3(ix) Not used."),
                block("range", "Apply Clauses XYZ71.3(i) to XYZ71.3(v).")))).isEmpty(), "Roman clause ix is outside i-v");
    }

    @Test void inactiveRangeDeclarationsRequireKnownOwnerAndExcludeContentsStyles() {
        Random random = new Random(827119);
        for (int i = 0; i < 16; i++) {
            String owner = "" + (char) ('H' + random.nextInt(10)) + (char) ('H' + random.nextInt(10)) + "Z";
            String prefix = owner + ".Q" + (80 + random.nextInt(90)) + ".";
            DocumentBlock contents = block("contents", prefix + "4 Not used."); contents.setParagraphStyleName(i % 2 == 0 ? "TOC 3" : "Table of Contents");
            DocumentBlock reference = block("reference", "Apply Clauses " + prefix + "1 through " + prefix + "8.");
            assertTrue(engine.review(Arrays.asList(source("directory", "ABC", block("foreign-owner", prefix + "4 Not used.")),
                    source("contract", owner, contents, reference))).isEmpty(), "Neither foreign-owner references nor owned TOC entries establish operative status");
            assertTrue(engine.review(Collections.singletonList(source("unknown", "OTHER", block("status", prefix + "4 Not used."), reference))).isEmpty(), "An unknown file key cannot establish clause ownership");
            VettingRuleEngine.Output real = only(engine.review(Collections.singletonList(source("contract", owner,
                    contents, block("actual", prefix + "4 Not used."), reference))), "inactive_clause_range_coordination");
            assertTrue(real.getEvidence().stream().anyMatch(e -> "actual".equals(e.getBlockId())));
            assertFalse(real.getEvidence().stream().anyMatch(e -> "contents".equals(e.getBlockId())));
        }
    }

    @Test void inclusiveRangesPreferSameDocumentOwnerDeclarationIndependentOfSourceOrder() {
        VettingRuleEngine.Source other = source("other-version", "ZXQ", block("other", "ZXQ71.4 Not used."));
        VettingRuleEngine.Source actual = source("actual", "ZXQ", block("own", "ZXQ71.4 Not used."),
                block("range", "Apply Clauses ZXQ71.1 to ZXQ71.8."));
        for (List<VettingRuleEngine.Source> ordered : Arrays.asList(Arrays.asList(other, actual), Arrays.asList(actual, other))) {
            VettingRuleEngine.Output finding = only(engine.review(ordered), "inactive_clause_range_coordination");
            assertEquals(2, finding.getEvidence().size());
            assertTrue(finding.getEvidence().stream().allMatch(e -> "actual".equals(e.getDocumentId())));
        }
    }

    @Test void differentExplicitAbbreviationExpansionsRequireBothOriginalPassages() {
        DocumentBlock name = block("name", "Technical Review Officer (TRO) attends the coordination meeting.");
        DocumentBlock repeated = block("repeated", "The retained contact list includes Technical Review Officer (TRO), Project Manager and other personnel.");
        DocumentBlock glossary = table("glossary", "body/42/table-row/1", "TRO", "-Transport Routing Operations.");
        VettingRuleEngine.Output issue = only(engine.review(Arrays.asList(source("a", "XYZ", block("scope", "XYZ43.7 ABBREVIATIONS"), name, repeated, glossary))), "abbreviation_ambiguity");
        assertEquals(2, issue.getEvidence().size());
        assertTrue(issue.getEvidence().stream().anyMatch(e -> name.getText().equals(e.getQuote())));
        assertTrue(issue.getEvidence().stream().anyMatch(e -> glossary.getText().equals(e.getQuote())));
        assertTrue(issue.getSummary().contains("same identified source scope"));
        assertTrue(issue.getSummary().contains("do not establish conflicting contract obligations"));
        assertFalse(issue.getEvidence().stream().anyMatch(e -> "repeated".equals(e.getBlockId())));
    }

    @Test void sameScopeExplicitDefinitionsSupportVariedOwnersAndDefinitionFormats() {
        Random random = new Random(683029);
        String[] firstNames = {"Amber", "Beryl", "Cobalt"};
        String[] otherNames = {"Arctic", "Bay", "Cargo"};
        for (int i = 0; i < 24; i++) {
            int word = random.nextInt(firstNames.length);
            String acronym = firstNames[word].substring(0, 1) + "RO";
            String owner = "" + (char) ('D' + random.nextInt(12)) + (char) ('D' + random.nextInt(12)) + "X";
            String clause = owner + (20 + random.nextInt(300)) + "." + (1 + random.nextInt(90));
            DocumentBlock name = block("name", firstNames[word] + " Review Officer (" + acronym + ") attends the meeting.");
            DocumentBlock definition;
            if (i % 3 == 0) definition = block("definition", acronym + " : " + otherNames[word] + " Routing Operations.");
            else if (i % 3 == 1) definition = table("definition", "body/8/table-row/1", acronym, "—" + otherNames[word] + " Routing Operations.");
            else definition = table("definition", "body/8/table-row/1", acronym, ":", otherNames[word] + " Routing Operations.");
            String sourceId = "s" + i;
            VettingRuleEngine.Output issue = only(engine.review(Collections.singletonList(source(sourceId, owner,
                    block("scope", clause + " Abbreviations"), name, definition))), "abbreviation_ambiguity");
            assertEquals("language", issue.getType());
            assertEquals(2, issue.getEvidence().size());
            assertTrue(issue.getEvidence().stream().anyMatch(e -> e.getQuote().equals(name.getText())));
            assertTrue(issue.getEvidence().stream().anyMatch(e -> e.getQuote().equals(definition.getText())));
            assertTrue(issue.getEvidence().stream().allMatch(e -> e.getSourceHash().equals("hash-" + sourceId)), "Original source digest is retained");
        }
    }

    @Test void separateOrUnknownDefinitionScopesDoNotCreateSpeculativeAbbreviationFindings() {
        DocumentBlock name = block("name", "Copper Review Officer (CRO) attends the meeting.");
        DocumentBlock glossary = table("definition", "body/8/table-row/1", "CRO", "- Cargo Routing Operations.");
        assertTrue(engine.review(Collections.singletonList(source("s", "XYZ",
                block("staff", "XYZ27.3 Personnel"), name,
                block("standards", "XYZ28.9 Abbreviations"), glossary))).isEmpty(), "Different identified clauses may define local meanings");
        assertTrue(engine.review(Arrays.asList(source("a", "XYZ", block("scope", "XYZ27.3 Abbreviations"), name),
                source("b", "XYZ", block("scope", "XYZ27.3 Abbreviations"), glossary))).isEmpty(), "Same file key and clause label do not imply one document scope");
        assertTrue(engine.review(Collections.singletonList(source("unknown", "XYZ", name,
                table("header", "body/8/table-row/0", "Abbreviation", "Full name"), glossary))).isEmpty(), "Unknown scope is not assumed global");
    }

    @Test void anActualGlossaryHeaderEstablishesTableScopeWhileDrawingColumnsDoNot() {
        DocumentBlock first = table("first", "body/8/table-row/1", "CRO", "- Copper Review Officer.");
        DocumentBlock second = table("second", "body/8/table-row/2", "CRO", "- Cargo Routing Operations.");
        assertTrue(engine.review(Collections.singletonList(source("drawing", "XYZ",
                table("heading", "body/8/table-row/0", "Drawing No.", "Drawing Title"), first, second))).isEmpty(), "A dash and two table cells alone are not a definition");
        VettingRuleEngine.Output issue = only(engine.review(Collections.singletonList(source("glossary", "XYZ",
                table("heading", "body/8/table-row/0", "Short forms", "Meanings"), first, second))), "abbreviation_ambiguity");
        assertEquals(2, issue.getEvidence().size());
    }

    @Test void equivalentHyphenPunctuationConjunctionAndPluralLongFormsAreCompatible() {
        assertTrue(engine.review(Collections.singletonList(source("s", "XYZ", block("scope", "XYZ83.5 Abbreviations"),
                table("a", "body/8/table-row/1", "MIVEC", "- Multitrade Integrated Ventilation, Electrical and Cooling."),
                table("b", "body/8/table-row/2", "MIVEC", "- Multi-trade Integrated Ventilation and Electrical and Cooling."),
                table("c", "body/8/table-row/3", "TRO", "- Technical Review Officer."),
                table("d", "body/8/table-row/4", "TRO", "- Technical Review Officers for the Site.")))).isEmpty());
        VettingRuleEngine.Output different = only(engine.review(Collections.singletonList(source("s", "XYZ", block("scope", "XYZ83.5 Abbreviations"),
                table("a", "body/8/table-row/1", "MIVEC", "- Multitrade Integrated Ventilation, Electrical and Cooling."),
                table("b", "body/8/table-row/2", "MIVEC", "- Multitrade Integrated Ventilation, Electrical and Construction.")))), "abbreviation_ambiguity");
        assertEquals(2, different.getEvidence().size(), "A different substantive word is not normalized away");
    }

    @Test void ocrDefinitionRequiresGlossaryContextAndCompleteWordsButKeepsPhysicalEvidence() {
        DocumentBlock heading = ocr("heading", "Abbreviations", 17);
        DocumentBlock first = ocr("first", "QXO - Quality Exchange Operations.", 17);
        DocumentBlock second = ocr("second", "QXO - Quarry Excavation Oversight.", 17);
        VettingRuleEngine.Output issue = only(engine.review(Collections.singletonList(source("scan", "XYZ", heading, first, second))), "abbreviation_ambiguity");
        assertEquals(2, issue.getEvidence().size());
        assertTrue(issue.getEvidence().stream().allMatch(e -> e.getPageNo() == 17 && Arrays.equals(first.getBbox(), e.getBbox())));
        assertTrue(engine.review(Collections.singletonList(source("scan", "XYZ", first, second))).isEmpty(), "OCR drawing text without a glossary heading is not a definition");
        assertTrue(engine.review(Collections.singletonList(source("scan", "XYZ", heading, first,
                ocr("fragment1", "QXO - I I S", 17), ocr("fragment2", "QXO - I I NS", 17), ocr("fragment3", "QXO - I I DNS", 17)))).isEmpty(), "Fragments are not complete long names even under a glossary heading");
        assertTrue(engine.review(Collections.singletonList(source("scan", "XYZ", heading, first,
                ocr("next-page", "QXO - Quarry Excavation Oversight.", 18)))).isEmpty(), "A loose scanned glossary marker cannot establish scope on a different page");
    }

    private DocumentBlock ocr(String id, String text, int page) {
        DocumentBlock b = block(id, text); b.setLocation("physical-page/" + page + "/ocr/" + id); b.setPageNo(page);
        b.setKind("ocr_line"); b.setSource("ocr"); b.setConfidence(0.92); b.setBbox(new double[]{0.4, 0.6, 0.3, 0.03}); return b;
    }

    @Test void abbreviationReferencesEquivalentLongFormsAndInactiveMetadataDoNotCreateAmbiguity() {
        DocumentBlock ordinary = block("ordinary", "The Contractor shall contact the TRO.");
        ordinary.setDeletedText("Transport Routing Operations (TRO)"); ordinary.setStrikeText("Transport Routing Operations (TRO)");
        assertTrue(engine.review(Arrays.asList(source("a", "PRE", ordinary,
                block("name", "Technical Review Officer (TRO) attends the meeting."),
                table("same", "body/1/table-row/1", "TRO", "-Technical Review Officer."),
                table("extended", "body/1/table-row/2", "TRO", "-Technical Review Officers for the Site."),
                table("ordinary-table", "body/2/table-row/1", "TRO", "Approval for Transport Routing Operations.")))).isEmpty());
        assertTrue(engine.review(Arrays.asList(source("a", "PRE",
                block("name1", "Technical Review Officer (TRO) attends the meeting."),
                block("name2", "Transport Routing Operations (TRO) are documented for the delivery.")))).isEmpty(),
                "This narrow coordination check requires an explicit glossary expansion");
        assertTrue(engine.review(Arrays.asList(source("a", "PRE",
                block("partial-name", "The Monthly Statement for Payment of Technical Review Officers (TRO) is retained."),
                table("glossary", "body/3/table-row/0", "TRO", "-Transport Routing Operations.")))).isEmpty(),
                "A sentence prefix before an acronym is not a verified full expansion");
        assertTrue(engine.review(Arrays.asList(source("a", "PRE",
                block("hyphenated-compound", "MiC-BK installation programme;"),
                block("name", "Guidelines for Modular Integrated Construction (MiC) Method.")))).isEmpty(),
                "A hyphenated compound is not a glossary dash definition");
    }

    @Test void bareTableClauseUsesOnlyItsOwnExplicitGccColumnHeader() {
        DocumentBlock header = table("header", "body/9/table-row/0", "Information", "GCC Clause No.");
        DocumentBlock retained = table("retained", "body/9/table-row/1", "Certificate threshold", "83.4(2)");
        DocumentBlock otherTable = table("unrelated", "body/10/table-row/0", "Certificate threshold", "83.4(2)");
        VettingRuleEngine.Source amendment = source("scc", "SCC", block("delete", "Clause 83.4(2) of the General Conditions of Contract is deleted and replaced by Not used."));
        VettingRuleEngine.Output issue = only(engine.review(Arrays.asList(amendment, source("pre", "PRE", header, retained, otherTable))), "deleted_clause_reference");
        assertEquals(3, issue.getEvidence().size());
        assertTrue(issue.getEvidence().stream().anyMatch(e -> e.getQuote().equals(header.getText())));
        assertTrue(issue.getEvidence().stream().anyMatch(e -> e.getQuote().equals(retained.getText())));
        assertFalse(issue.getEvidence().stream().anyMatch(e -> e.getQuote().equals(otherTable.getText()) && e.getBlockId().equals("unrelated")));
        assertTrue(engine.review(Arrays.asList(amendment, source("pre", "PRE", otherTable))).isEmpty());
    }

    private DocumentBlock table(String id, String location, String... cells) {
        DocumentBlock b = block(id, String.join(" | ", cells)); b.setLocation(location); b.setKind("table_row"); b.setCells(Arrays.asList(cells)); return b;
    }

    @Test
    void deletedProvisionIsFoundBeyondFormerPromptBudgetWithExactEvidence() {
        String declaration = "Clause 93.8(3)(z) of the General Conditions of Contract is deleted and replaced by ‘Not used’.";
        String reference = "The Contractor shall give notice under GCC Clause 93.8(3)(z).";
        List<DocumentBlock> references = new ArrayList<>();
        for (int i = 0; i < 600; i++) references.add(block("filler" + i, "Unrelated ordinary tender wording retained for full traversal."));
        references.add(block("reference", reference));
        VettingRuleEngine.Source scc = source("scc", "SCC", block("amendment", declaration));
        VettingRuleEngine.Source pre = source("pre", "PRE", references.toArray(new DocumentBlock[0]));
        VettingRuleEngine.ReviewResult result = engine.scan(Arrays.asList(scc, pre));
        assertEquals(602, result.getBlocksScanned());
        assertTrue(result.getCharactersScanned() > 30000);
        VettingRuleEngine.Output output = only(result.getOutputs(), "deleted_clause_reference");
        assertEquals(2, output.getEvidence().size());
        assertTrue(output.getEvidence().stream().anyMatch(e -> declaration.equals(e.getQuote())));
        assertTrue(output.getEvidence().stream().anyMatch(e -> reference.equals(e.getQuote())));
        assertTrue(output.getEvidence().stream().allMatch(e -> e.getSourceHash().equals("hash-" + e.getDocumentId())));
    }

    @Test
    void replacementMapsNotUsedToOnlyMatchingSubclauseAndIgnoresBaseGcc() {
        VettingRuleEngine.Source scc = source("scc", "SCC",
                block("h", "SCC19.901 Amendments"),
                block("amend", "Clauses 83.4(1) and 83.4(2) of the General Conditions of Contract are amended by substituting with the following:-"),
                block("active", "(1) The parties shall retain their records."), block("inactive", "(2) Not used."));
        List<VettingRuleEngine.Output> output = engine.review(Arrays.asList(scc,
                source("gcc", "GCC", block("base", "GCC83.4(2) Original general condition is retained.")),
                source("pre", "PRE", block("ref", "Comply with Clause 83.4(2) of the General Conditions of Contract."),
                        block("still-active", "Comply with GCC83.4(1)."))));
        VettingRuleEngine.Output issue = only(output, "deleted_clause_reference");
        assertTrue(issue.getSummary().contains("83.4(2)"));
        assertFalse(issue.getEvidence().stream().anyMatch(e -> "gcc".equals(e.getDocumentId())));
        assertTrue(issue.getEvidence().stream().anyMatch(e -> "(2) Not used.".equals(e.getQuote())));
    }

    @Test
    void inactiveTextMetadataDoesNotBecomeAnEffectiveReference() {
        DocumentBlock inactive = block("inactive", "The Contractor shall follow the current notice procedure.");
        inactive.setDeletedText("Use GCC55.3(7).");
        inactive.setStrikeText("Use GCC55.3(7).");
        assertTrue(engine.review(Arrays.asList(
                source("scc", "SCC", block("a", "Clause 55.3(7) of the General Conditions of Contract is deleted.")),
                source("pre", "PRE", inactive))).isEmpty());
    }

    @Test
    void minimumIsCompatibleWithLargerExactNumberAndTwoMinimaAreCompatible() {
        assertTrue(staff("Review Officer (RO) total 2 nos;", "Employ at least one Review Officer (RO).").isEmpty());
        assertTrue(staff("Employ at least two Review Officer (RO).", "Employ not less than one Review Officer (RO).").isEmpty());
        assertTrue(staff("73.2 Review Officer (RO) functions", "One (1) Number of Review Officer (RO);").isEmpty());
    }

    @Test
    void twoExplicitDifferentNumbersRequireCoordinationButUnspecifiedRoleDoesNot() {
        VettingRuleEngine.Output issue = only(staff("Review Officer (RO) total 2 nos;",
                "One (1) Number of Review Officer (RO);"), "personnel_requirement_coordination");
        assertEquals(2, issue.getEvidence().size());
        assertTrue(issue.getSummary().contains("stated 2"));
        assertTrue(issue.getSummary().contains("stated 1"));
        assertEquals("risk", issue.getType());
        assertTrue(issue.getSummary().contains("scope and period have not been established as identical"));
        assertTrue(staff("Structural Inspection Coordinator (SIC);",
                "Two (2) Number of Structural Inspection Coordinator (SIC);").isEmpty());
    }

    @Test void deadlinesCopyCountsContentsAndAliasSentencesAreNotPersonnelRequirements() {
        assertTrue(engine.review(Arrays.asList(source("pre", "PRE",
                block("alias", "The statement shall be submitted to the Contract Manager (CM)."),
                block("deadline", "Submit to the CM at least 14 days before the works."),
                block("copies", "Submit to the CM 3 copies of the plan."),
                block("index1", "2 CONTRACT MANAGER AND CONTRACT MANAGER'S REPRESENTATIVE"),
                block("index2", "4 CONTRACT MANAGER AND CONTRACT MANAGER'S REPRESENTATIVE")))).isEmpty());
        assertEquals(1, staff("Employ 2 Review Officer (RO).", "Employ 3 Review Officer (RO).").size());
    }

    @Test void scopedSubcontractDefinitionsDoNotConflictWithGlobalContractDefinitions() {
        assertTrue(engine.review(Arrays.asList(source("gcc", "GCC", block("global", "“Contract” means the accepted Tender and Agreement.")),
                source("pre", "PRE", block("heading", "PRE.B9.101 Mandatory provisions"),
                        block("scope", "In these Mandatory Sub-contract Provisions, the following words have these meanings."),
                        block("local", "“Contract” means the main contract made between the Employer and Contractor.")))).isEmpty());
        assertTrue(engine.review(Arrays.asList(source("scc", "SCC",
                block("global", "“Index Figure” means the published number for general work."),
                block("local", "For the purpose of this Clause SCC90.102, “Index Figure” means the specialist-work index.")))).isEmpty());
    }

    @Test
    void sameTermDifferentDefinitionsCreatesReviewHintWithBothSourcePassages() {
        VettingRuleEngine.Output issue = only(engine.review(Arrays.asList(
                source("a", "NTT", block("term", "“Project Representative” means the Employer’s site officer.")),
                source("b", "PRE", block("term", "“Project Representative” means the Contractor’s quality supervisor.")))),
                "definition_coordination");
        assertEquals("language", issue.getType());
        assertEquals(2, issue.getEvidence().size());
        assertTrue(issue.getSummary().contains("intentional"));
    }

    @Test
    void expressSccReplacementDoesNotFlagUnamendedGccDefinitionAsConflict() {
        assertTrue(engine.review(Arrays.asList(
                source("scc", "SCC", block("amend", "Clause 91.1 of the General Conditions of Contract is amended by substituting with the following:-"),
                        block("term", "“Project Representative” means the Contractor’s quality supervisor.")),
                source("gcc", "GCC", block("term", "“Project Representative” means the Employer’s site officer.")))).isEmpty());
    }

    private List<VettingRuleEngine.Output> staff(String a, String b) {
        return engine.review(Arrays.asList(source("a", "PRE", block("staff", a)), source("b", "PRE", block("staff", b))));
    }
    private VettingRuleEngine.Output only(List<VettingRuleEngine.Output> output, String rule) {
        List<VettingRuleEngine.Output> selected = new ArrayList<>();
        for (VettingRuleEngine.Output value : output) if (rule.equals(value.getRuleId())) selected.add(value);
        assertEquals(1, selected.size(), output.toString());
        return selected.get(0);
    }
    private VettingRuleEngine.Source source(String id, String key, DocumentBlock... blocks) {
        return new VettingRuleEngine.Source(id, key, id + ".docx", "hash-" + id, Arrays.asList(blocks), "");
    }
    private DocumentBlock block(String id, String text) {
        DocumentBlock block = new DocumentBlock();
        block.setId(id); block.setKind("paragraph"); block.setLocation("body/" + id); block.setText(text);
        return block;
    }
}
