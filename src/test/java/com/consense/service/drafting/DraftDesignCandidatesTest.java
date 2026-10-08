package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.CandidateVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Selects an existing full source-backed answer; never combines facts into a new answer. */
class DraftDesignCandidatesTest {
    private static final DraftBlueprint.InputSpec SPEC=DraftBlueprint.find("designResponsibilities");
    private static final String FULL="[{\"component\":\"piling\",\"design\":true,\"execution\":true,\"scope\":\"North building piles\"},{\"component\":\"footings\",\"design\":false,\"execution\":true,\"scope\":\"South building footings\"},{\"component\":\"other\",\"design\":false,\"execution\":true,\"scope\":\"Internal partitions\"}]";
    private static final String PARTIAL="[{\"component\":\"piling\",\"design\":true,\"execution\":true}]";
    private static final String COMPLETE_QUOTE="The complete component responsibility schedule is:\nComponent | Actual work scope | Contractor designs | Contractor executes\npiling | North building piles | Yes | Yes\nfootings | South building footings | No | Yes\nother | Internal partitions | No | Yes";

    @Test void capturedFoundationFactsAreDominatedByTheExistingCompleteResponsibilitySchedule() throws Exception {
        List<CandidateVO> candidates=captured();String full=candidates.get(1).getValue(),before=JsonUtils.write(candidates);
        for(int i=0;i<2;i++) {
            assertEquals(full,DraftDesignCandidates.merge(SPEC,candidates));
            Collections.reverse(candidates);
        }
        assertEquals(before,JsonUtils.write(candidates),"Values, citations, source identities and candidate order remain unchanged.");
    }

    @Test void missingOptionalScopeAndCompatibleSubsetsChooseAnExistingFullAnswerInEitherOrder() {
        for(String declared:new String[]{COMPLETE_QUOTE,COMPLETE_QUOTE.replace("complete","full"),COMPLETE_QUOTE.replace("complete","entire")}) {
            CandidateVO partial=candidate(PARTIAL,"The contractor designs and executes the piling works for this project."),full=candidate(FULL,declared);
            assertEquals(FULL,DraftDesignCandidates.merge(SPEC,Arrays.asList(partial,full)));
            assertEquals(FULL,DraftDesignCandidates.merge(SPEC,Arrays.asList(full,partial)));
        }
    }

    @Test void aLongerCandidateWithoutAnExpressFullSourceDeclarationCannotDominate() {
        for(String quote:new String[]{"Component responsibility schedule:","The partial component responsibility schedule is:",
                "This is not a complete component responsibility schedule.","The proposed complete component responsibility schedule is:",
                "The complete component responsibility schedule remains pending approval.","The complete component responsibility schedule is not provided.",
                "Please confirm whether this complete component responsibility schedule is adopted?"})
            assertNull(DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(PARTIAL,"Piling responsibility confirmed."),candidate(FULL,quote))),quote);
    }

    @Test void booleanScopeAndOutsideComponentDisagreementsRetainAConflict() {
        for(String incompatible:new String[]{PARTIAL.replace("\"design\":true","\"design\":false"),
                PARTIAL.replace("\"execution\":true","\"execution\":false"),
                "[{\"component\":\"piling\",\"design\":true,\"execution\":true,\"scope\":\"Different tower piles\"}]",
                PARTIAL.replace("piling","pilecaps"),"[]"})
            assertNull(DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(incompatible,"Explicit sourced component fact."),candidate(FULL,COMPLETE_QUOTE))),incompatible);
    }

    @Test void twoDifferentCompleteSchedulesDoNotResolveByCandidateOrder() {
        CandidateVO first=candidate(FULL,COMPLETE_QUOTE),second=candidate(FULL.replace("North building piles","West building piles"),COMPLETE_QUOTE.replace("North building piles","West building piles"));
        assertNull(DraftDesignCandidates.merge(SPEC,Arrays.asList(first,second)));
        assertNull(DraftDesignCandidates.merge(SPEC,Arrays.asList(second,first)));
        assertNull(DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(PARTIAL,COMPLETE_QUOTE),first)),"An asserted complete subset contradicts a larger asserted complete schedule.");
    }

    @Test void duplicateComponentsAndMissingResponsibilityCellsCannotDominate() {
        for(String broken:new String[]{FULL.replace("\"component\":\"other\"","\"component\":\"piling\""),
                FULL.replace("\"design\":true,",""),FULL.replace("\"execution\":true,",""),
                FULL.replace("\"component\":\"piling\"","\"component\":\"invented\"")})
            assertNull(DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(PARTIAL,"Piling confirmed."),candidate(broken,COMPLETE_QUOTE))),broken);
    }

    @Test void existingIdenticalAndEmptySuggestionsKeepTheirOriginalBehavior() {
        assertEquals("",DraftDesignCandidates.merge(SPEC,Collections.emptyList()));
        assertEquals("[]",DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate("[]","No contractor design responsibilities apply."))));
        assertEquals(PARTIAL,DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(PARTIAL,"Piling confirmed."),candidate(PARTIAL,"Piling is confirmed in this source too."))));
    }

    @Test void capturedLegacyOtherComponentsUseDistinctWorkScopesAsTheirBusinessIdentities() throws Exception {
        try(InputStream input=DraftDesignCandidatesTest.class.getResourceAsStream("/drafting/harness/ordered-list/legacy-design-candidates.json")) {
            assertNotNull(input);List<CandidateVO> candidates=new ArrayList<>();
            for(JsonNode record:JsonUtils.mapper().readTree(input))candidates.add(JsonUtils.mapper().treeToValue(record,CandidateVO.class));
            assertEquals(1,candidates.size());assertEquals(3,JsonUtils.parse(candidates.get(0).getValue()).size());
            assertEquals(candidates.get(0).getValue(),DraftDesignCandidates.merge(SPEC,candidates));
        }
    }

    @Test void explicitlyScopedRepeatedComponentsAreDistinctAndUnscopedFragmentsRemainAmbiguous() {
        String full="[{\"component\":\"other\",\"design\":true,\"execution\":true,\"scope\":\"East Annex finishes\"},{\"component\":\"other\",\"design\":true,\"execution\":true,\"scope\":\"West Annex finishes\"}]";
        String scoped="[{\"component\":\"other\",\"design\":true,\"execution\":true,\"scope\":\"East Annex finishes\"}]";
        assertEquals(full,DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(scoped,"East Annex finishes are included."),candidate(full,COMPLETE_QUOTE))));
        assertNull(DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(PARTIAL.replace("piling","other"),"All other components for this project."),candidate(full,COMPLETE_QUOTE))));
        for(String broken:new String[]{full.replace("West Annex finishes","East Annex finishes"),full.replace("\"scope\":\"East Annex finishes\"", "\"scope\":\"\"")})
            assertNull(DraftDesignCandidates.merge(SPEC,Collections.singletonList(candidate(broken,COMPLETE_QUOTE))));
    }

    @Test void missingScopesCannotCrossLocalWorkBoundariesEvenWithinTheSameProject() {
        CandidateVO complete=candidate(FULL,COMPLETE_QUOTE);
        for(String local:new String[]{"The contractor designs and executes East Annex piling for this project.",
                "The contractor designs and executes piling in the East Annex under the same contract.",
                "The contractor designs and executes piling for Block A within the same contract.",
                "The contractor designs and executes piling within the same contract for another project.",
                "The contractor designs and executes piling, not for this project.",
                "The contractor designs and executes the piling works."})
            assertNull(DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(PARTIAL,local),complete)),local);
        String quote="The contractor designs and executes the piling works.";
        assertEquals(FULL,DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(PARTIAL,quote),candidate(FULL,COMPLETE_QUOTE+"\n"+quote))));
        assertEquals(FULL,DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(PARTIAL,"The contractor designs and executes piling at the site for this project."),complete)),"A generic site noun is not a named local restriction.");
        String eastFull=FULL.replace("North building piles","East Annex piling");
        assertEquals(eastFull,DraftDesignCandidates.merge(SPEC,Arrays.asList(candidate(PARTIAL,"The contractor designs and executes East Annex piling within the same contract."),candidate(eastFull,COMPLETE_QUOTE.replace("North building piles","East Annex piling")))));
    }

    private static CandidateVO candidate(String value,String quote) {
        return new CandidateVO(value,1L,"source.docx","frozen-source-hash",quote,"Source-backed fixture candidate",.95);
    }
    private static List<CandidateVO> captured() throws Exception {
        try(InputStream input=DraftDesignCandidatesTest.class.getResourceAsStream("/drafting/harness/ordered-list/alternate-design-candidates.json")) {
            assertNotNull(input);JsonNode records=JsonUtils.mapper().readTree(input);List<CandidateVO> result=new ArrayList<>();
            for(JsonNode record:records)result.add(JsonUtils.mapper().treeToValue(record,CandidateVO.class));
            return result;
        }
    }
}
