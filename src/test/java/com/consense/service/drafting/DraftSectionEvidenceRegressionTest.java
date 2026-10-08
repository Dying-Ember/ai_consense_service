package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Formal Sections are source tuples, never inferred from ordinary building descriptions. */
class DraftSectionEvidenceRegressionTest {
    private static final String HEADER="Section designation | Works types | Site or location";
    private static final String A="Section A | building, foundation | Community hall at the north site";
    private static final String B="Section B | demolition | Former workshop at the south site";
    private static final String C="Section C | building, foundation | Sports building at the east site";
    private static final String COMPLETE="The complete adopted Sections are:\n"+HEADER+"\n"+A+"\n"+B+"\n"+C;

    @Test void genuineRecordedThreeRowTableRemainsAcceptedWithOriginalEditorIds() throws Exception {
        ExtractionDecisionVO d=captured(80);
        assertEquals("accepted",d.getStatus(),d.getCodes().toString());
        JsonNode value=JsonUtils.mapper().readTree(d.getNormalizedValue());
        assertEquals(3,value.size());assertEquals("Section X",value.get(0).path("designation").asText());
        assertEquals("Section X",JsonUtils.mapper().valueToTree(d.getRawValue()).get(0).path("id").asText());
    }

    @Test void genuineRecordedDomesticBlockRecallDoesNotBecomeAFormalSection() throws Exception {
        ExtractionDecisionVO d=captured(109);
        assertEquals("rejected",d.getStatus(),d.getCodes().toString());
        assertTrue(d.getCodes().contains("quote_value_mismatch"));
    }

    @Test void completeSourceTableAcceptsAllSuppliedRows() { check(rows(),COMPLETE,"accepted"); }
    @Test void theSameSectionTuplesMayBeReturnedInAnotherOrder() {
        List<Map<String,Object>> value=rows();Collections.reverse(value);check(value,COMPLETE,"accepted");
    }
    @Test void duplicateSourceDesignationsStayUnresolved() {
        check(rows(),COMPLETE+"\nSection A | building, foundation | Community hall at the north site","rejected");
    }
    @Test void omissionFromACompleteSuppliedTableIsRejected() { check(rows().subList(0,2),COMPLETE,"rejected"); }
    @Test void typeCannotBeBorrowedFromTheNextSourceRow() {
        List<Map<String,Object>> value=rows();value.get(1).put("workTypes",Arrays.asList("building","foundation"));check(value,COMPLETE,"rejected");
    }
    @Test void locationCannotBeBorrowedFromTheNextSourceRow() {
        List<Map<String,Object>> value=rows();value.get(0).put("location",value.get(1).get("location"));check(value,COMPLETE,"rejected");
    }
    @Test void ordinaryBuildingLocationCannotEstablishSectionOrWorksType() {
        check(Collections.singletonList(row("domestic block",Collections.singletonList("building"),"Sha Tin")),"The domestic block is in Sha Tin.","rejected");
    }
    @Test void explicitSectionIdentityHeaderAllowsActualNames() {
        check(Collections.singletonList(row("Northern phase",Collections.singletonList("foundation"),"North site")),HEADER+"\nNorthern phase | foundation | North site","accepted");
    }
    @Test void genericBuildingTableDoesNotEstablishContractSections() {
        check(Collections.singletonList(row("Domestic block",Collections.singletonList("building"),"North site")),"Building | Works types | Location\nDomestic block | building | North site","rejected");
    }
    @Test void modelRowWithoutASectionHeaderCannotBorrowRoleFromUnrelatedParagraph() {
        String source="A separate contract adopts Sections A and B.\n\nDomestic block | building | North site";
        check(Collections.singletonList(row("Domestic block",Collections.singletonList("building"),"North site")),source,"rejected");
    }
    @Test void explicitlyAdoptedNoSectionsRemainsAnEmptyList() {
        check(Collections.emptyList(),"Sections: Works type and location: Explicitly none","accepted");
    }
    @Test void pendingNoSectionsDoesNotEstablishAbsence() {
        check(Collections.emptyList(),"Please confirm whether no Sections are designated; this decision remains pending.","rejected");
    }
    @Test void pendingTableCannotSupplyAnAdoptedCandidate() { check(rows(),"The Section decision remains pending:\n"+HEADER+"\n"+A+"\n"+B+"\n"+C,"rejected"); }
    @Test void explicitlyIncompleteSectionTableDoesNotBecomeACompleteList() { check(rows(),"This is an incomplete Section table; more rows will follow:\n"+HEADER+"\n"+A+"\n"+B+"\n"+C,"rejected"); }
    @Test void proposedTableCannotSupplyAnAdoptedCandidate() { check(rows(),"The proposed Sections are not adopted:\n"+HEADER+"\n"+A+"\n"+B+"\n"+C,"rejected"); }
    @Test void requestTableCannotSupplyAnAdoptedCandidate() { check(rows(),"Please confirm the following Sections:\n"+HEADER+"\n"+A+"\n"+B+"\n"+C,"rejected"); }
    @Test void trailingUnconfirmedDecisionKeepsTheTableUnresolved() { check(rows(),COMPLETE+"\nThis decision remains unconfirmed.","rejected"); }
    @Test void sourceTypeNegationDoesNotSupportThePositiveType() {
        check(Collections.singletonList(row("Section A",Collections.singletonList("building"),"North site")),HEADER+"\nSection A | not building | North site","rejected");
    }
    @Test void literalBuildingWorksAliasesHaveTheSameBoundedTypeMeaning() {
        check(Collections.singletonList(row("Section A",Arrays.asList("building","foundation"),"North site")),HEADER+"\nSection A | building works and foundation works | North site","accepted");
    }
    @Test void conflictingCompleteTableCannotBeUsedToManufactureOneAnswer() {
        check(rows(),COMPLETE+"\n\nThe complete adopted Sections are:\n"+HEADER+"\nSection A | demolition | Community hall at the north site","rejected");
    }
    @ParameterizedTest @ValueSource(strings={"012","021","102","120","201","210"})
    void headerBindsAllSixColumnOrders(String order) {
        String[] headings={"Section designation","Works types","Site or location"};
        String[][] cells={{"Section A","building, foundation","Community hall at the north site"},{"Section B","demolition","Former workshop at the south site"},{"Section C","building, foundation","Sports building at the east site"}};
        StringBuilder source=new StringBuilder("The complete adopted Sections are:\n");
        source.append(reorder(headings,order));for(String[] line:cells)source.append('\n').append(reorder(line,order));check(rows(),source.toString(),"accepted");
    }

    private static String reorder(String[] cells,String order) { return cells[order.charAt(0)-'0']+" | "+cells[order.charAt(1)-'0']+" | "+cells[order.charAt(2)-'0']; }
    private static Map<String,Object> row(String designation,List<String> types,String location) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("designation",designation);result.put("workTypes",types);result.put("location",location);return result;
    }
    private static List<Map<String,Object>> rows() {
        return new ArrayList<>(Arrays.asList(row("Section A",Arrays.asList("building","foundation"),"Community hall at the north site"),row("Section B",Collections.singletonList("demolition"),"Former workshop at the south site"),row("Section C",Arrays.asList("building","foundation"),"Sports building at the east site")));
    }
    private static void check(Object value,String source,String expected) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key","sections");item.put("value",value);item.put("sourceQuote",source);item.put("reason","Independent formal-Section source-role probe");item.put("confidence",.95);
        ExtractionPartVO part=new ExtractionPartVO("section-probe:0",1L,"section-probe.txt","source",0,source,new ArrayList<>());
        part.setContext(new ExtractionContextVO("section-probe",Collections.singletonList("sections"),0,source.length(),source,null));
        ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,source);
        assertEquals(JsonUtils.write(value),JsonUtils.write(d.getRawValue()));assertEquals(source,d.getSourceQuote());
        System.out.println("SECTION_PUBLIC_PROBE "+JsonUtils.write(Arrays.asList(source,expected,d.getStatus(),d.getCodes())));
        assertEquals(expected,d.getStatus(),source+" "+d.getCodes());
    }
    private static ExtractionDecisionVO captured(int index) throws Exception {
        JsonNode fixture;try(InputStream in=DraftSectionEvidenceRegressionTest.class.getResourceAsStream("/drafting/harness/captured-round1-sections.json")) {assertNotNull(in);fixture=JsonUtils.mapper().readTree(in);}
        assertEquals("12d06aec-a541-4e21-a400-0632b80d0d3e",fixture.path("provenance").path("runId").asText());
        for(JsonNode record:fixture.path("records"))if(record.path("decisionIndex").asInt()==index) {
            ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
            int ordinal=record.path("capturedAttemptIndex").asInt(),itemIndex=record.path("capturedItemIndex").asInt();
            ExtractionDecisionVO actual=DraftHarnessTestIntake.primaryDecisions(record.path("raw").asText(),part,record.path("originalSource").asText(),ordinal,record.path("systemPrompt").asText(),record.path("userPrompt").asText())
                    .stream().filter(d->d.getItemIndex()==itemIndex).findFirst().orElseThrow(AssertionError::new);
            assertEquals(record.path("capturedDecision").path("rawValue"),JsonUtils.mapper().valueToTree(actual.getRawValue()));
            assertEquals(record.path("capturedDecision").path("sourceQuote").asText(),actual.getSourceQuote());
            System.out.println("SECTION_CAPTURE "+index+" "+actual.getStatus()+" "+actual.getCodes());return actual;
        }
        throw new AssertionError(index);
    }
}
