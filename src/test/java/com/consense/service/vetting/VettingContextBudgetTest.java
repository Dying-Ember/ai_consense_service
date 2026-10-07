package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingContextBudgetTest {
    @Test void addsAllLocatedParentContinuationsWithoutChangingEarlierSourceBytes() {
        List<Chunk> corpus = parent(4000);
        VettingContextBudget.Result result = build(corpus, Collections.singletonList(corpus.get(0)), 10000, 20000);
        assertTrue(result.isExpansionAttempted()); assertTrue(result.isExpanded()); assertTrue(result.isInitialChunksPreserved());
        assertEquals(10000, result.getInitialBudgetChars()); assertEquals(20000, result.getEffectiveBudgetChars());
        assertEquals(12000, result.getSelection().getContentChars()); assertTrue(result.getFinalMissingTargetIds().isEmpty());
        assertFalse(result.getInitialMissingTargetIds().isEmpty()); assertEquals(Arrays.asList("a", "b", "c"), ids(result));
        assertTrue(result.getSelection().getReferences().get(0).isFullParentContextSelected());
        assertFalse(result.getSelection().getReferences().get(0).isExactSubclauseVerified());
        assertTrue(result.getSelection().getUnresolvedComparisonIds().contains("a"));
        for (Chunk selected : result.getSelection().getChunks()) assertSame(corpus.stream().filter(c -> c.getId().equals(selected.getId())).findFirst().get(), selected);
    }

    @Test void ceilingAtOrBelowInitialBudgetDisablesExpansionAndPreservesUnknowns() {
        for (int ceiling : new int[] {0, 9000, 10000}) {
            List<Chunk> corpus = parent(4000);
            VettingContextBudget.Result result = build(corpus, Collections.singletonList(corpus.get(0)), 10000, ceiling);
            assertFalse(result.isExpansionAttempted()); assertFalse(result.isExpanded());
            assertEquals(result.getInitialMissingTargetIds(), result.getFinalMissingTargetIds());
            assertFalse(result.getFinalMissingTargetIds().isEmpty()); assertTrue(result.getSelection().getContentChars() <= 10000);
        }
    }

    @Test void anAbsentOrAmbiguousTargetCannotBeInventedByMoreContextBudget() {
        Chunk origin = chunk("a", "XYZ1", "The submission shall comply with XYZ99(2).", 150);
        VettingContextBudget.Result result = build(Collections.singletonList(origin), Collections.singletonList(origin), 1000, 20000);
        assertFalse(result.isExpansionAttempted()); assertTrue(result.getInitialMissingTargetIds().isEmpty());
        assertEquals("missing_target", result.getSelection().getReferences().get(0).getStatus());
        assertTrue(result.getSelection().getUnresolvedComparisonIds().contains("a")); assertEquals(Collections.singletonList("a"), ids(result));
    }

    @Test void aWindowWithoutLiteralReferencesDoesNotExpandJustToFillTheCeiling() {
        Chunk a = chunk("a", "XYZ1", "The original register shall be retained.", 300);
        VettingContextBudget.Result result = build(Collections.singletonList(a), Collections.singletonList(a), 1000, 20000);
        assertFalse(result.isExpansionAttempted()); assertEquals(1000, result.getEffectiveBudgetChars());
        assertEquals(300, result.getSelection().getContentChars());
    }

    @Test void expansionCannotTradeAwayAnAlreadySubmittedExceptionForAReferenceTarget() {
        Chunk origin = chunk("a", "XYZ1", "The record shall comply with XYZ8(1).", 1000);
        Chunk qualifier = chunk("q", "XYZ1", "Except when records are archived, retain the original exception.", 1000);
        qualifier.setClauseHeadingLocation(origin.getClauseHeadingLocation());
        Chunk ranked = chunk("ranked", "XYZ2", "The separate register shall be retained.", 5000);
        Chunk first = chunk("b", "XYZ8", "The target definition begins here.", 4000);
        Chunk continuation = chunk("c", "XYZ8", "The target condition ends here.", 4000);
        continuation.setClauseHeadingLocation(first.getClauseHeadingLocation());
        List<Chunk> corpus = Arrays.asList(origin, qualifier, ranked, first, continuation);
        // Supply the ordinary non-monotonic candidate directly to retain this source-preservation guard.
        VettingContextBuilder builder = new VettingContextBuilder(corpus);
        VettingContextBuilder.Selection initial = builder.build("submission requirements", Arrays.asList(origin, ranked), Collections.emptyMap(), 7000);
        VettingContextBudget.Result result = new VettingContextBudget.Result();
        result.setSelection(initial); result.setInitialBudgetChars(7000); result.setEffectiveBudgetChars(7000);
        result.setExpansionCeilingChars(14000); result.setExpansionAttempted(true);
        initial.getReferences().stream().filter(t -> t.isInitiallySubmitted() && t.isOriginSubmitted()).forEach(t -> result.getInitialMissingTargetIds().addAll(t.getMissingTargetIds()));
        result.setFinalMissingTargetIds(new LinkedHashSet<>(result.getInitialMissingTargetIds()));
        VettingContextBudget.evaluateExpansion(result, initial, builder.build("submission requirements", Arrays.asList(origin, ranked), Collections.emptyMap(), 14000), 14000);
        assertTrue(result.isExpansionAttempted()); assertFalse(result.isExpanded()); assertFalse(result.isInitialChunksPreserved());
        assertEquals("expansion_would_remove_initial_chunks", result.getDecision());
        assertTrue(ids(result).contains("q")); assertEquals(7000, result.getEffectiveBudgetChars());
        assertFalse(result.getFinalMissingTargetIds().isEmpty());
    }

    @Test void boundedExpansionThatCannotAddTargetsKeepsTheOriginalWindow() {
        List<Chunk> corpus = parent(8000);
        VettingContextBudget.Result result = build(corpus, Collections.singletonList(corpus.get(0)), 10000, 12000);
        assertTrue(result.isExpansionAttempted()); assertFalse(result.isExpanded());
        assertEquals(result.getInitialMissingTargetIds(), result.getFinalMissingTargetIds());
        assertEquals(10000, result.getEffectiveBudgetChars()); assertEquals(Collections.singletonList("a"), ids(result));
    }

    private static VettingContextBudget.Result build(List<Chunk> corpus, List<Chunk> ranked, int initial, int ceiling) {
        return VettingContextBudget.build(new VettingContextBuilder(corpus), "submission requirements", ranked, Collections.emptyMap(), initial, ceiling);
    }
    private static List<String> ids(VettingContextBudget.Result result) {
        return result.getSelection().getChunks().stream().map(Chunk::getId).collect(Collectors.toList());
    }
    static List<Chunk> parent(int length) {
        Chunk a = chunk("a", "XYZ22.304", "The submission shall comply with XYZ22.304(6).", length);
        Chunk b = chunk("b", "XYZ22.304", "The intermediate subsection (7) is defined here.", length);
        Chunk c = chunk("c", "XYZ22.304", "The referenced subsection (6) is defined here.", length);
        b.setClauseHeadingLocation(a.getClauseHeadingLocation()); c.setClauseHeadingLocation(a.getClauseHeadingLocation());
        return Arrays.asList(a,b,c);
    }
    static Chunk chunk(String id, String clause, String text, int length) {
        Chunk c = new Chunk(); c.setId(id); c.setDocumentId("native-fixture"); c.setSourceHash("fixture-revision-one");
        c.setRole("tender"); c.setFileKey("XYZ"); c.setFileName("fixture.docx"); c.setClauseId(clause);
        c.setClauseHeadingLocation("body/"+id); c.setAnchor("body/"+id);
        StringBuilder content = new StringBuilder(text); while (content.length()<length) content.append(' ');
        c.setContent(content.toString()); return c;
    }
}
