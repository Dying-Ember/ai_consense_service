package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the production intake seam; no candidate is constructed from an answer oracle. */
class DraftDesignEvidenceRegressionTest {
    @Test void genericResponsibilityRolesRequireTheirActualContractorOwner() throws Exception {
        String records="Component | Scope | Design | Execution\nfootings | Youth centre footings | Yes | Yes";
        accepted(rows(row("footings",true,true,"Youth centre footings")),"The Contractor's responsibility schedule is:\n"+records);
        rejected(rows(row("footings",true,true,"Youth centre footings")),"The Architect's responsibility schedule is:\n"+records);
        rejected(rows(row("footings",true,true,"Youth centre footings")),"The responsibility schedule is:\n"+records);
        String labelled="component: footings; scope: Youth centre footings; design: Yes; execution: Yes";
        accepted(rows(row("footings",true,true,"Youth centre footings")),"The Contractor's responsibility schedule is:\n"+labelled);
        rejected(rows(row("footings",true,true,"Youth centre footings")),"The Architect's responsibility schedule is:\n"+labelled);
    }

    @Test void anUnselectedConditionalPrefaceDoesNotAssertTheFollowingResponsibilityTable() throws Exception {
        String records="Component | Scope | Contractor designs | Contractor executes\nfootings | Youth centre footings | No | Yes";
        accepted(rows(row("footings",false,true,"Youth centre footings")),"The responsibility table is adopted:\n"+records);
        rejected(rows(row("footings",false,true,"Youth centre footings")),"If the option is selected:\n"+records);
        accepted(rows(row("footings",true,true,null)),"The responsibility statement is adopted:\nThe Contractor will design and construct the footings.");
        rejected(rows(row("footings",true,true,null)),"If the option is selected:\nThe Contractor will design and construct the footings.");
    }

    @Test void aPassiveEngineerSubjectKeepsItsFootingsOutsideTheContractorsJointObject() throws Exception {
        String source="The Contractor will design and construct the piles, and the footings will be designed and constructed solely by the Engineer.";
        accepted(rows(row("piling",true,true,null)),source);
        rejected(rows(row("footings",true,true,null)),source);
    }

    @Test void anExpresslyCompleteScheduleCannotSupportAPrefixCandidateThatOmitsAnActualRow() throws Exception {
        String source=table("Component | Scope | Contractor designs | Contractor executes",
                "piling | Centre piles | Yes | Yes\npilecaps | Centre pile caps | Yes | Yes\n"
                +"footings | Centre footings | Yes | Yes\nother | Internal partitions | No | Yes");
        accepted(rows(row("piling",true,true,"Centre piles"),row("pilecaps",true,true,"Centre pile caps"),
                row("footings",true,true,"Centre footings"),row("other",false,true,"Internal partitions")),source);
        rejected(rows(row("piling",true,true,"Centre piles"),row("pilecaps",true,true,"Centre pile caps"),
                row("footings",true,true,"Centre footings")),source);
    }
    @Test void headerRolesMustBeRespectedWhenExecutionComesFirst() throws Exception {
        String source=table("Component | Actual work scope | Contractor executes | Contractor designs",
                "footings | Youth centre footings | Yes | No");
        accepted(rows(row("footings",false,true,"Youth centre footings")),source);
        rejected(rows(row("footings",true,false,"Youth centre footings")),source);
    }

    @Test void allColumnPermutationsBindTheSameTuple() throws Exception {
        String[] headers={"Component","Actual work scope","Contractor designs","Contractor executes"};
        String[] cells={"footings","Youth centre footings","No","Yes"};
        for(int a=0;a<4;a++)for(int b=0;b<4;b++)for(int c=0;c<4;c++)for(int d=0;d<4;d++) {
            Set<Integer> permutation=new HashSet<>(Arrays.asList(a,b,c,d));if(permutation.size()!=4)continue;
            String source=table(String.join(" | ",headers[a],headers[b],headers[c],headers[d]),
                    String.join(" | ",cells[a],cells[b],cells[c],cells[d]));
            accepted(rows(row("footings",false,true,"Youth centre footings")),source);
            rejected(rows(row("footings",true,false,"Youth centre footings")),source);
        }
    }

    @Test void markdownSeparatorsDoNotBecomeEvidenceRows() throws Exception {
        String source="| Scope | Contractor executes | Component | Contractor designs |\n"
                +"| --- | --- | --- | --- |\n| Youth centre footings | Yes | footings | No |";
        accepted(rows(row("footings",false,true,"Youth centre footings")),source);
        rejected(rows(row("footings",true,false,"Youth centre footings")),source);
    }

    @Test void booleansCannotBeBorrowedFromAnotherComponentOrScope() throws Exception {
        String source=table("Component | Actual work scope | Contractor designs | Contractor executes",
                "piling | Youth centre piles | Yes | No\n"
                +"footings | Youth centre footings | No | Yes\n"
                +"footings | Elderly centre footings | Yes | No");
        accepted(rows(row("piling",true,false,"Youth centre piles"),row("footings",false,true,"Youth centre footings"),
                row("footings",true,false,"Elderly centre footings")),source);
        rejected(rows(row("piling",true,false,"Youth centre piles"),row("footings",true,true,"Youth centre footings"),
                row("footings",true,false,"Elderly centre footings")),source);
        rejected(rows(row("piling",true,false,"Youth centre piles"),row("footings",true,false,"Youth centre footings"),
                row("footings",true,false,"Elderly centre footings")),source);
        rejected(rows(row("piling",true,false,"Youth centre piles"),row("footings",true,false,"Unknown site footings"),
                row("footings",true,false,"Elderly centre footings")),source);
        rejected(rows(row("footings",false,true,null)),source);
    }

    @Test void contradictoryRowsWithTheSameIdentityCannotBeSelectedByConvenience() throws Exception {
        String source=table("Component | Scope | Contractor designs | Contractor executes",
                "footings | Youth centre footings | No | Yes\nfootings | Youth centre footings | Yes | No");
        rejected(rows(row("footings",false,true,"Youth centre footings")),source);
        rejected(rows(row("footings",true,false,"Youth centre footings")),source);
    }

    @Test void aMissingCandidateRoleIsNotSupportedByMentioningTheComponent() throws Exception {
        String source=table("Component | Scope | Contractor designs | Contractor executes",
                "footings | Youth centre footings | No | Yes");
        Map<String,Object> missing=row("footings",false,true,"Youth centre footings");missing.remove("design");
        rejected(rows(missing),source);
        missing=row("footings",false,true,"Youth centre footings");missing.put("execution",null);
        rejected(rows(missing),source);
    }

    @Test void missingOrPendingSourceRolesCannotBecomeFalse() throws Exception {
        for(String cell:Arrays.asList("","Pending","Unknown","not supplied","Please confirm","No?","Proposed: No")) {
            String source=table("Component | Scope | Contractor designs | Contractor executes",
                    "footings | Youth centre footings | "+cell+" | Yes");
            rejected(rows(row("footings",false,true,"Youth centre footings")),source);
        }
        rejected(rows(row("footings",false,true,"Youth centre footings")),
                table("Component | Scope | Contractor executes","footings | Youth centre footings | Yes"));
    }

    @Test void tableQuestionsProposalsAndNegatedAdoptionAreUnanswered() throws Exception {
        for(String prefix:Arrays.asList("Please confirm the following responsibility table:",
                "The proposed responsibility table is:","The following responsibility table is not adopted:",
                "The responsibility table remains pending:","The responsibility table is not confirmed:")) {
            String source=prefix+"\nComponent | Scope | Contractor designs | Contractor executes\n"
                    +"footings | Youth centre footings | No | Yes";
            rejected(rows(row("footings",false,true,"Youth centre footings")),source);
        }
    }

    @Test void labelledRecordsBindRolesWithoutJoiningDifferentRows() throws Exception {
        String source="component: other; execution: Yes; scope: Roof waterproofing; design: No\n"
                +"component: other; execution: No; scope: Facade anchors; design: Yes";
        accepted(rows(row("other",false,true,"Roof waterproofing")),source);
        rejected(rows(row("other",true,true,"Roof waterproofing")),source);
        rejected(rows(row("other",true,false,"Roof waterproofing")),source);
        rejected(rows(row("other",false,true,null)),source);
        rejected(rows(row("other",false,true,"Roof waterproofing")),
                "component: other; scope: Roof waterproofing; design: pending; execution: Yes");
    }

    @Test void aScopeOrComponentMentionAloneDoesNotAssertBothRoles() throws Exception {
        rejected(rows(row("footings",true,true,null)),"Youth centre footings are confirmed.");
        rejected(rows(row("footings",false,true,null)),"Contractor design responsibilities for footings are unknown.");
        rejected(rows(row("other",true,true,"Roof waterproofing")),"Roof waterproofing is required.");
    }

    @Test void jointProseRolesUseOneSharedObjectAndRequireAContractorSubject() throws Exception {
        accepted(rows(row("piling",true,true,null),row("pilecaps",true,true,null),row("footings",true,true,null)),
                "The appointed Contractor will design and construct the piles, pile caps and shallow footings for the buildings within the same contract, together with the building works.");
        accepted(rows(row("footings",true,true,"Youth centre footings")),
                "The Contractor shall design and execute the Youth centre footings.");
        rejected(rows(row("footings",true,false,"Youth centre footings")),
                "The Contractor shall design and execute the Youth centre footings.");
        rejected(rows(row("footings",true,true,null)),"The Architect will design and construct the footings.");
        rejected(rows(row("piling",true,true,"North Annex piles")),
                "The Contractor will design North Annex piles and construct South Annex piles.");
    }

    @Test void questionsNegationAndOptionsDoNotBecomePositiveProseEvidence() throws Exception {
        for(String source:Arrays.asList("Please confirm whether the Contractor will design and construct footings.",
                "The Contractor may design and construct footings.","The Contractor will not design and construct footings.",
                "The Contractor will design and construct footings if the option is selected.",
                "The Contractor will design and construct footings; this remains pending."))
            rejected(rows(row("footings",true,true,null)),source);
    }

    @Test void aLaterSubjectCannotLendItsComponentToAnEarlierContractorClaim() throws Exception {
        String source="The Contractor will design and construct the piles; the Architect designs the footings.";
        accepted(rows(row("piling",true,true,null)),source);
        rejected(rows(row("footings",true,true,null)),source);
        accepted(rows(row("piling",true,true,null)),
                "The Contractor will design and construct the piles; no refuge floor installation is included.");
        for(String exclusion:Arrays.asList(", excluding the footings",", except the footings",", but not the footings")) {
            String excluded="The Contractor will design and construct the piles"+exclusion+".";
            accepted(rows(row("piling",true,true,null)),excluded);
            rejected(rows(row("footings",true,true,null)),excluded);
        }
    }

    @Test void aNamedStructuralComponentCannotBeReclassifiedAsGenericOther() throws Exception {
        String source="The Contractor will design and construct the Youth centre footings.";
        accepted(rows(row("footings",true,true,"Youth centre footings")),source);
        rejected(rows(row("other",true,true,"Youth centre footings")),source);
    }

    @Test void anUnansweredPrefixAlsoAppliesToTheFollowingLabelledRows() throws Exception {
        rejected(rows(row("other",true,true,"Roof waterproofing"),row("other",true,true,"Facade anchors")),
                "Please confirm:\ncomponent: other; design: Yes; execution: Yes; scope: Roof waterproofing\n"
                +"component: other; design: Yes; execution: Yes; scope: Facade anchors");
        rejected(rows(row("footings",false,true,"Youth centre footings")),
                "Please confirm:\nComponent | Scope | Contractor designs | Contractor executes\nfootings | Youth centre footings | No | Yes");
    }

    @Test void aNewActorClauseWithoutASemicolonAlsoKeepsItsOwnObject() throws Exception {
        for(String separator:Arrays.asList(", and the "," and the ",", whereas the ")) {
            String source="The Contractor will design and construct the piles"+separator+"Architect designs the footings.";
            accepted(rows(row("piling",true,true,null)),source);
            rejected(rows(row("footings",true,true,null)),source);
        }
        accepted(rows(row("footings",true,true,"Youth centre footings")),
                "The Contractor will design and construct Youth centre footings and Architect designed internal partitions.");
    }

    @Test void anExplicitContradictionOrDependentPendingQualifierCannotBeIgnored() throws Exception {
        rejected(rows(row("footings",true,true,null)),
                "The Contractor will design and construct the footings. The Contractor will not design or construct the footings.");
        rejected(rows(row("footings",true,true,null)),
                "The Contractor will design and construct the footings. This remains pending.");
        accepted(rows(row("piling",true,true,null)),
                "The Contractor will design and construct the piles. Refuge floor installation remains pending.");
    }

    @Test void everyAcceptedDesignCandidateFromBothRealCapturedRunsKeepsItsSourceEvidence() throws Exception {
        int checked=0;
        for(String name:Arrays.asList("captured-alternate-values","captured-legacy-values")) {
            try(InputStream stream=getClass().getResourceAsStream("/drafting/harness/"+name+"/fixture.json")) {
                assertNotNull(stream);JsonNode fixture=JsonUtils.mapper().readTree(stream);
                for(JsonNode decision:fixture.path("trace").path("decisions"))
                    if("designResponsibilities".equals(decision.path("key").asText())&&"accepted".equals(decision.path("status").asText())) {
                        String quote=decision.path("sourceQuote").asText();
                        Object value=JsonUtils.mapper().convertValue(JsonUtils.parse(decision.path("normalizedValue").asText()),Object.class);
                        accepted(value,quote);checked++;
                    }
            }
        }
        assertEquals(3,checked,"All accepted design decisions in the two captured real runs must be replayed.");
    }

    private static String table(String header,String records) {return "The complete component responsibility schedule for this project is:\n"+header+"\n"+records;}
    @SafeVarargs private static List<Map<String,Object>> rows(Map<String,Object>... records) {return Arrays.asList(records);}
    private static Map<String,Object> row(String component,boolean design,boolean execution,String scope) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("component",component);result.put("design",design);result.put("execution",execution);
        if(scope!=null)result.put("scope",scope);return result;
    }
    private static void accepted(Object value,String source) throws Exception {
        ExtractionDecisionVO result=decide(value,source);assertEquals("accepted",result.getStatus(),source+" "+result.getCodes());
    }
    private static void rejected(Object value,String source) throws Exception {
        ExtractionDecisionVO result=decide(value,source);assertEquals("rejected",result.getStatus(),source+" "+result.getCodes());
        assertTrue(result.getCodes().contains("quote_value_mismatch"),result.getCodes().toString());
    }
    private static ExtractionDecisionVO decide(Object value,String source) throws Exception {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key","designResponsibilities");item.put("value",value);
        item.put("sourceQuote",source);item.put("reason","Offline component and role evidence regression");item.put("confidence",.95);
        ExtractionContextVO context=new ExtractionContextVO("design-role-regression",Collections.emptyList(),0,source.length(),source,null);
        ExtractionPartVO part=new ExtractionPartVO("design:0",1L,"design-role-regression.txt","source",0,source,new ArrayList<>());part.setContext(context);
        return DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,source);
    }
}
