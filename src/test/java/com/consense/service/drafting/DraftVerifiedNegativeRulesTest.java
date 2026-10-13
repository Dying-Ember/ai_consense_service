package com.consense.service.drafting;

import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Portable catalog contract with synthetic facts; no local original, captured answers or model. */
class DraftVerifiedNegativeRulesTest {
    static String source(){StringJoiner out=new StringJoiner("\n");for(int n=1;n<=2074;n++)out.add(DraftBusinessRules.standardParagraph("SCC",n));return out.toString();}
    static Map<String,Object> action(Map<String,Object> plan,String id){return DraftBusinessRules.list(plan.get("actions")).stream().map(DraftBusinessRules::asMap).filter(a->id.equals(a.get("id"))).findFirst().orElseThrow();}
    static Map<String,Object> selected(Map<String,Object> facts,String... ids){Map<String,Object> plan=DraftBusinessRules.plan(facts);Set<String> chosen=new HashSet<>(Arrays.asList(ids));plan.put("actions",DraftBusinessRules.list(plan.get("actions")).stream().filter(a->chosen.contains(DraftBusinessRules.asMap(a).get("id"))).collect(Collectors.toList()));return plan;}
    @Test void falseAdvanceAndLatePossessionUseTheReviewedNotUsedForm(){
        Map<String,Object> p=selected(DraftBusinessRules.map("advancePaymentAdopted",false,"latePossessionAdopted",false),"scc-advance-adoption","scc-late-possession");String actual=DraftClauseRules.apply(source(),"SCC",p);
        for(String id:Arrays.asList("scc-advance-adoption","scc-late-possession")){Map<String,Object> a=action(p,id);assertEquals("applied",a.get("applicationState"));assertTrue(actual.contains(a.get("clause")+" Not used"));assertTrue(String.valueOf(a.get("appliedAnchor")).contains("TOC P"));}
        assertTrue(actual.contains(DraftBusinessRules.standardParagraph("SCC",1224)));assertFalse(actual.contains(DraftBusinessRules.standardParagraph("SCC",1220)));
    }
    @Test void adjacentDefinitionsAndOvtClausesRemainOrderIndependentAtTheirActualBoundaries(){
        String[] ids={"scc-ovt-definitions","scc-ovt-aftercare","scc-ovt-protection","scc-permit-definition","scc-all-provisional-definition"};Map<String,Object> p=selected(DraftBusinessRules.map("oldValuableTrees",Collections.emptyList(),"excavationPermitRequired",false,"pricingScheme","SOR"),ids);String actual=DraftClauseRules.apply(source(),"SCC",p);
        for(String id:ids){assertEquals("applied",action(p,id).get("applicationState"),String.valueOf(action(p,id)));assertTrue(actual.contains(action(p,id).get("clause")+" Not used"));}
        assertTrue(actual.contains(DraftBusinessRules.standardParagraph("SCC",198)));
    }
    @Test void demolitionRemovalStopsBeforeTheNextChapterAndExcisionClause(){
        Map<String,Object> p=selected(DraftBusinessRules.map("policeTrainingAdopted",false),"scc-demolition-training");String actual=DraftClauseRules.apply(source(),"SCC",p);assertEquals("applied",action(p,"scc-demolition-training").get("applicationState"));assertTrue(actual.contains("SCC8.308 Not used"));assertTrue(actual.contains(DraftBusinessRules.standardParagraph("SCC",949)));assertTrue(actual.contains(DraftBusinessRules.standardParagraph("SCC",951)));assertEquals("P925–948; TOC P52",action(p,"scc-demolition-training").get("appliedAnchor"));
    }
    @Test void changedProjectTextAndTocCacheStayPending(){
        for(String text:Arrays.asList(source().replace(DraftBusinessRules.standardParagraph("SCC",1220),"Changed project repayment requirement."),source().replace(DraftBusinessRules.standardParagraph("SCC",71),"SCC14.305Changed TOC44"))){Map<String,Object> p=selected(DraftBusinessRules.map("advancePaymentAdopted",false),"scc-advance-adoption");assertEquals(text,DraftClauseRules.apply(text,"SCC",p));assertEquals("unresolved",action(p,"scc-advance-adoption").get("applicationState"));}
    }
    @Test void unknownPairingsAndPositiveDecisionsDoNotEnterTheNegativeWhitelist(){
        Map<String,Object> p=selected(DraftBusinessRules.map("advancePaymentAdopted",false),"scc-advance-adoption");Map<String,Object> a=action(p,"scc-advance-adoption");a.put("clause","SCC14.304");assertNull(DraftClauseRules.negativeScope(a));assertEquals(source(),DraftClauseRules.apply(source(),"SCC",p));assertEquals("unresolved",a.get("applicationState"));
        Map<String,Object> positive=selected(DraftBusinessRules.map("advancePaymentAdopted",true),"scc-advance-adoption");assertNull(DraftClauseRules.negativeScope(action(positive,"scc-advance-adoption")));assertFalse(DraftClauseRules.apply(source(),"SCC",positive).contains("SCC14.305 Not used"));
    }
    @Test void oldFoundationReferenceNeedsTheAppliedSctDeletionRatherThanAConstructedSccMapping(){
        Map<String,Object> p=selected(DraftBusinessRules.map("contractorDesignedFoundations",false),"scc-foundation-version-reference");assertEquals(source(),DraftClauseRules.apply(source(),"SCC",p));assertEquals("unresolved",action(p,"scc-foundation-version-reference").get("applicationState"));assertFalse(String.valueOf(action(p,"scc-foundation-version-reference").get("sourceMapping")).contains("SCC8.304"));
    }
    @Test void invalidTreeBooleanDoesNotStandInForAnExplicitEmptyIdentityList(){
        Map<String,Object> p=selected(DraftBusinessRules.map("oldValuableTrees",false),"scc-ovt-definitions");assertEquals(source(),DraftClauseRules.apply(source(),"SCC",p));assertEquals("pending",action(p,"scc-ovt-definitions").get("applicationState"));assertNull(DraftClauseRules.negativeScope(action(p,"scc-ovt-definitions")));
    }
}
