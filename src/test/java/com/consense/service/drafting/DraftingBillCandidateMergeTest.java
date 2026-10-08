package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.consense.web.dto.DraftingDtos.CandidateVO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Calls the production merge seam with captured browser-exam candidates; no model/API/DB. */
class DraftingBillCandidateMergeTest {
    private static String capturedValue(String name) throws Exception {
        try(InputStream stream=DraftingBillCandidateMergeTest.class.getResourceAsStream("/drafting/harness/captured-bill-qualification/fixture.json")) {
            assertNotNull(stream);
            for(JsonNode item:JsonUtils.mapper().readTree(stream).path("cases"))if(name.equals(item.path("name").asText()))
                return DraftInputRules.normalizeSuggestion(DraftBlueprint.find("billNos"),JsonUtils.write(item.path("item").path("value")));
            throw new IllegalArgumentException(name);
        }
    }

    private static String merge(String first,String second) throws Exception {
        Constructor<?> constructor=DraftingService.class.getDeclaredConstructors()[0];
        DraftingService service=(DraftingService)constructor.newInstance(new Object[constructor.getParameterCount()]);
        Method merge=DraftingService.class.getDeclaredMethod("mergeListInput",DraftBlueprint.InputSpec.class,String.class,String.class);merge.setAccessible(true);
        return (String)merge.invoke(service,DraftBlueprint.find("billNos"),first,second);
    }

    private static String normalized(String value) {
        return DraftInputRules.normalizeSuggestion(DraftBlueprint.find("billNos"),value);
    }

    @Test void capturedUntypedQualifierCannotAppendAFourteenthBillBesideTheTypedParent() throws Exception {
        String formal=capturedValue("formal-thirteen-bill-table"),qualifier=capturedValue("qualification-misread-as-formal-description");
        assertEquals(13,JsonUtils.parse(formal).size());
        assertNull(merge(formal,qualifier),"An untyped conflicting identity must remain a selectable conflict, not an additional parent Bill.");
        assertNull(merge(qualifier,formal),"Upload/candidate order cannot choose the namespace.");
    }

    @Test void anExactPartialIdentityWithoutTypeOrGeneratedIdDoesNotDuplicateTheCompleteTable() throws Exception {
        String formal=capturedValue("formal-thirteen-bill-table");
        ObjectNode partial=((ObjectNode)JsonUtils.parse(formal).get(4)).deepCopy();
        partial.remove("id");partial.remove("type");partial.remove("purpose");partial.remove("trade");
        String incompleteMetadata=normalized(JsonUtils.write(JsonUtils.mapper().createArrayNode().add(partial)));
        for(String merged:new String[]{merge(formal,incompleteMetadata),merge(incompleteMetadata,formal)}) {
            assertNotNull(merged);JsonNode rows=JsonUtils.parse(merged);assertEquals(13,rows.size());
            assertEquals(JsonUtils.parse(formal),rows,"Only the compatible identity is reused; the original complete table and its metadata remain intact.");
        }
    }

    @Test void explicitlyDifferentPricingNamespacesRemainSeparate() throws Exception {
        String first=normalized("[{\"type\":\"BQ\",\"number\":\"1\",\"description\":\"Preliminaries\"}]");
        String second=normalized("[{\"type\":\"SOR\",\"number\":\"1\",\"description\":\"Safety & Environmental Payments\"},{\"type\":\"BQ\",\"number\":\"2\",\"description\":\"Building Works\"}]");
        JsonNode merged=JsonUtils.parse(merge(first,second));assertEquals(3,merged.size());
        assertEquals("BQ",merged.get(0).path("type").asText());assertEquals("SOR",merged.get(1).path("type").asText());
        String ambiguous=normalized("[{\"number\":\"1\",\"description\":\"Preliminaries\"}]");
        assertNull(merge(JsonUtils.write(merged),ambiguous),"An unknown namespace must not silently create a fourth identity.");
    }

    @Test void compatibleMetadataCanFillMissingCellsWithoutCreatingAConflictFromGeneratedIds() throws Exception {
        String first=normalized("[{\"type\":\"BQ\",\"number\":\"7\",\"description\":\"Specified building works\",\"purpose\":\"general\"}]");
        String second=normalized("[{\"number\":\"7\",\"description\":\"Specified building works\",\"trade\":\"Electrical\"}]");
        String result=merge(first,second);assertNotNull(result);JsonNode row=JsonUtils.parse(result).get(0);
        assertEquals(1,JsonUtils.parse(result).size());assertEquals("general",row.path("purpose").asText());
        assertEquals("Electrical",row.path("trade").asText());assertEquals(JsonUtils.parse(first).get(0).path("id"),row.path("id"));
        String contradicting=normalized("[{\"number\":\"7\",\"description\":\"Specified building works\",\"purpose\":\"preambles\"}]");
        assertNull(merge(first,contradicting),"Two sourced nonempty metadata values disagree; no implicit priority chooses one.");
    }

    @Test void sameNamespaceDisagreementsAndUnknownNamespaceDuplicatesRequireHumanSelection() throws Exception {
        String typed=normalized("[{\"type\":\"BQ\",\"number\":\"A4\",\"description\":\"Exact formal description\"}]");
        String otherTyped=normalized("[{\"type\":\"BQ\",\"number\":\"a4\",\"description\":\"Different formal description\"}]");
        assertNull(merge(typed,otherTyped));
        String unknown=normalized("[{\"number\":\"A4\",\"description\":\"Exact formal description\"}]");
        assertEquals(1,JsonUtils.parse(merge(unknown,unknown)).size());
        String otherUnknown=normalized("[{\"number\":\"a4\",\"description\":\"Different formal description\"}]");
        assertNull(merge(unknown,otherUnknown));
    }

    @Test void missingExplicitlyEmptyAndConflictingAbsenceAreDifferentStates() throws Exception {
        assertEquals("",DraftBillCandidates.merge(DraftBlueprint.find("billNos"),Collections.emptyList()));
        assertEquals("[]",merge("[]","[]"),"A source-confirmed absence remains an explicit empty list.");
        String present=normalized("[{\"type\":\"BQ\",\"number\":\"8\",\"description\":\"Specified building works\"}]");
        assertNull(merge("[]",present));assertNull(merge(present,"[]"));
    }

    @Test void allSourceCandidatesAreCollectedBeforeResolvingAnUnknownPricingNamespace() throws Exception {
        String untyped=normalized("[{\"number\":\"7\",\"description\":\"Exact formal description\"}]");
        String bq=normalized("[{\"type\":\"BQ\",\"number\":\"7\",\"description\":\"Exact formal description\"}]");
        String sor=normalized("[{\"type\":\"SOR\",\"number\":\"7\",\"description\":\"Independent schedule description\"}]");
        List<CandidateVO> candidates=new ArrayList<>();
        candidates.add(new CandidateVO(untyped,1L,"partial.docx","source-1","Original partial identity.","Fixture candidate",.95));
        candidates.add(new CandidateVO(bq,2L,"bq.docx","source-2","Original BQ identity.","Fixture candidate",.95));
        candidates.add(new CandidateVO(sor,3L,"sor.docx","source-3","Original SOR identity.","Fixture candidate",.95));
        String before=JsonUtils.write(candidates);
        for(int i=0;i<3;i++) {
            String ordered=JsonUtils.write(candidates);
            assertNull(DraftBillCandidates.merge(DraftBlueprint.find("billNos"),candidates));
            assertEquals(ordered,JsonUtils.write(candidates));Collections.rotate(candidates,1);
        }
        assertEquals(before,JsonUtils.write(candidates),"Value, quote, source identity and candidate order are left untouched.");
    }

    @Test void aMixedTypedAndUnresolvedTableKeepsItsOriginalRowOrder() throws Exception {
        String mixed=normalized("[{\"number\":\"3\",\"description\":\"Unknown pricing document\"},{\"type\":\"BQ\",\"number\":\"4\",\"description\":\"Specified building works\"}]");
        assertEquals(JsonUtils.parse(mixed),JsonUtils.parse(merge(mixed,mixed)));
    }

    @Test void addingPricingMetadataToAnUntypedCompleteTableKeepsItsDefiningOrder() throws Exception {
        String complete=normalized("[{\"number\":\"1\",\"description\":\"First formal identity\"},{\"number\":\"2\",\"description\":\"Second formal identity\"},{\"number\":\"3\",\"description\":\"Third formal identity\"}]");
        String partial=normalized("[{\"type\":\"BQ\",\"number\":\"2\",\"description\":\"Second formal identity\"}]");
        for(String merged:new String[]{merge(complete,partial),merge(partial,complete)}) {
            assertNotNull(merged);JsonNode rows=JsonUtils.parse(merged);assertEquals(3,rows.size());
            for(int i=0;i<3;i++)assertEquals(String.valueOf(i+1),rows.get(i).path("number").asText(),"Filling an optional type does not move the middle Bill to the end or beginning.");
            assertEquals("BQ",rows.get(1).path("type").asText());
            assertEquals("Second formal identity",rows.get(1).path("description").asText());
        }
    }

    @Test void missingRequiredIdentityCellsCannotBeTreatedAsOptionalMetadata() throws Exception {
        String complete=normalized("[{\"type\":\"BQ\",\"number\":\"6\",\"description\":\"Exact formal identity\"}]");
        assertNull(merge(complete,normalized("[{\"type\":\"BQ\",\"number\":\"6\"}]")));
        assertNull(merge(complete,normalized("[{\"type\":\"BQ\",\"description\":\"Exact formal identity\"}]")));
    }

    public static void main(String[] args) throws Exception {
        DraftingBillCandidateMergeTest test=new DraftingBillCandidateMergeTest();
        test.capturedUntypedQualifierCannotAppendAFourteenthBillBesideTheTypedParent();
        test.anExactPartialIdentityWithoutTypeOrGeneratedIdDoesNotDuplicateTheCompleteTable();
        test.explicitlyDifferentPricingNamespacesRemainSeparate();
        test.compatibleMetadataCanFillMissingCellsWithoutCreatingAConflictFromGeneratedIds();
        test.sameNamespaceDisagreementsAndUnknownNamespaceDuplicatesRequireHumanSelection();
        test.missingExplicitlyEmptyAndConflictingAbsenceAreDifferentStates();
        test.allSourceCandidatesAreCollectedBeforeResolvingAnUnknownPricingNamespace();
        test.aMixedTypedAndUnresolvedTableKeepsItsOriginalRowOrder();
        test.addingPricingMetadataToAnUntypedCompleteTableKeepsItsDefiningOrder();
        test.missingRequiredIdentityCellsCannotBeTreatedAsOptionalMetadata();
        System.out.println("DraftingBillCandidateMergeTest production merge replay PASS");
    }
}
