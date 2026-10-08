package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** New grammar cannot discard the original subject, paragraph, project or decision role. */
class DraftRoundTwoScopeBoundaryTest {
    private static final String DOMESTIC="The staff housing is a domestic residential block.";
    private static final String FOOTINGS="The listed footings are exclusively for small freestanding garden signboards. They do not serve a building or any major external structure.";
    private static final String SEPARATED="Building and demolition sites are separated; no parcel contains both types of work.";

    @Test void residentialTypeAndConstructionMethodKeepTheirAffirmativePolarity() {
        check("domesticBlocks",true,DOMESTIC,"accepted");
        check("domesticBlocks",false,DOMESTIC,"rejected");
        check("domesticBlocks",true,"The domestic staff block uses conventional in situ construction.","accepted");
        check("domesticBlocks",true,"The staff housing is not a domestic residential block.","rejected");
        check("domesticBlocks",false,"The staff housing is not a domestic residential block.","rejected");
        check("domesticBlocks",true,"The domestic block does not use conventional in situ construction.","rejected");
    }
    @Test void residentialTypeNeedsThisProjectsActualScope() {
        for(String q:Arrays.asList("The adjacent staff housing is a domestic residential block.",
                "The existing domestic staff block uses conventional in situ construction.",
                "For another project, "+DOMESTIC,"One option: "+DOMESTIC,
                "If selected, "+DOMESTIC,"Please confirm whether "+DOMESTIC))check("domesticBlocks",true,q,"rejected");
    }
    @Test void residentialQuoteCannotDiscardAnUnseenQuestionOrForeignPrefix() {
        for(String prefix:Arrays.asList("Please confirm whether ","For another project, ")) {
            String s=prefix+DOMESTIC;check("domesticBlocks",true,DOMESTIC,DOMESTIC,s,"rejected");
        }
    }
    @Test void residentialRoleHeadingsGovernAcrossLinesAndParagraphs() {
        for(String prefix:Arrays.asList("For another project only:","Do not use this answer:","Please confirm:"))
            for(String gap:Arrays.asList("\n","\n\n")) {
                String s=prefix+gap+DOMESTIC;check("domesticBlocks",true,DOMESTIC,s,s,"rejected");
            }
    }
    @Test void residentialDependentPendingUnitMustRemainVisibleAndUnresolved() {
        String s=DOMESTIC+"\n\nThis decision is adopted.\n\nThis classification remains unconfirmed.";
        check("domesticBlocks",true,DOMESTIC,s,s,"rejected");
        check("domesticBlocks",true,DOMESTIC,DOMESTIC,s,"rejected");
        s=DOMESTIC+"\n\nThe roofing warranty remains pending.";
        check("domesticBlocks",true,DOMESTIC,s,s,"accepted");
    }
    @Test void residentialScopeRetainsOldExplicitExclusions() {
        check("domesticBlocks",false,"The Works do not include a residential accommodation block.","accepted");
        check("domesticBlocks",false,"The residential accommodation block is not included in the Works.","accepted");
        check("domesticBlocks",true,"The Works include a domestic block. Another project has no domestic block.","accepted");
        check("domesticBlocks",true,"The Works include a domestic block. The Works exclude domestic blocks.","rejected");
    }
    @Test void footingsPronounRequiresAnExplicitImmediatelyPriorFootingsSubject() {
        check("footingsServeBuildingsOrMajorExternalStructures",false,FOOTINGS,"accepted");
        check("footingsServeBuildingsOrMajorExternalStructures",true,FOOTINGS,"rejected");
        check("footingsServeBuildingsOrMajorExternalStructures",false,"The footings do not serve a building or a major external structure.","accepted");
        check("footingsServeBuildingsOrMajorExternalStructures",true,"The listed footings are for the sports hall. They serve the building.","accepted");
    }
    @Test void mentioningFootingsAsAnActorsObjectCannotResolveThey() {
        for(String q:Arrays.asList("The Contractor inspects the footings. They do not serve a building or any major external structure.",
                "The Architect designs the footings. They serve the building.",
                "The footings and piles are for garden signs. They do not serve a building or any major external structure.",
                "The footings are for signs. The Contractor reviews supports. They do not serve a building or any major external structure.",
                "The footings are beside the piles. They do not serve a building or any major external structure."))
            check("footingsServeBuildingsOrMajorExternalStructures",q.contains("They serve")?true:false,q,"rejected");
    }
    @Test void pronounsCannotCrossAParagraphOrHeadingBoundary() {
        for(String gap:Arrays.asList("\n\n","\nLocation:\n")) {
            String q="The footings are for garden signs."+gap+"They do not serve a building or any major external structure.";
            check("footingsServeBuildingsOrMajorExternalStructures",false,q,"rejected");
        }
    }
    @Test void footingsPronounRequiresItsAntecedentInActualContextAndKeepsPendingClassification() {
        String q="They do not serve a building or any major external structure.";
        // A short citation may bind the unique adjacent subject when both actual source windows retain it.
        check("footingsServeBuildingsOrMajorExternalStructures",false,q,FOOTINGS,FOOTINGS,"accepted");
        check("footingsServeBuildingsOrMajorExternalStructures",false,q,q,FOOTINGS,"rejected");
        String s=FOOTINGS+"\n\nThis classification remains pending.";
        check("footingsServeBuildingsOrMajorExternalStructures",false,FOOTINGS,s,s,"rejected");
        check("footingsServeBuildingsOrMajorExternalStructures",false,FOOTINGS,FOOTINGS,s,"rejected");
    }
    @Test void footingsPronounCannotBorrowAForeignOrUnconfirmedDecision() {
        for(String prefix:Arrays.asList("For another project only:\n","Please confirm whether ","Do not use this answer:\n"))
            check("footingsServeBuildingsOrMajorExternalStructures",false,prefix+FOOTINGS,"rejected");
    }
    @Test void aFollowingPronounCannotHideConflictingFootingsUses() {
        String q="The footings serve buildings. They do not serve a building or any major external structure.";
        check("footingsServeBuildingsOrMajorExternalStructures",true,q,"rejected");
        check("footingsServeBuildingsOrMajorExternalStructures",false,q,"rejected");
    }
    @Test void separationRequiresTheRelationshipBetweenBothWorkSites() {
        check("buildingDemolitionSitesSeparated",true,SEPARATED,"accepted");
        check("buildingDemolitionSitesSeparated",false,SEPARATED,"rejected");
        check("buildingDemolitionSitesSeparated",true,"The building sites and demolition site are separated: building work is at the north and east sites, while demolition is at the south site.","accepted");
        check("buildingDemolitionSitesSeparated",false,"The building and demolition sites are not separated.","accepted");
        check("buildingDemolitionSitesSeparated",false,"For this scenario only, the building and demolition sites do not form the separate, detached arrangement needed for SCC8.303.","accepted");
    }
    @Test void demolitionObjectAndOneSitesIsolationCannotProveSiteSeparation() {
        for(String q:Arrays.asList("The obsolete stores are a standalone building, detached from all retained structures.",
                "The demolition building is detached from the retained building.","The demolition site is detached.",
                "The building sites are separated.","The demolition site is separated from all retained structures."))
            check("buildingDemolitionSitesSeparated",true,q,"rejected");
    }
    @Test void separationCannotDiscardAnOriginalQuestionOrOtherProjectRole() {
        for(String prefix:Arrays.asList("Please confirm whether ","For another project, ")) {
            String s=prefix+SEPARATED;check("buildingDemolitionSitesSeparated",true,SEPARATED,s,s,"rejected");
            check("buildingDemolitionSitesSeparated",true,SEPARATED,SEPARATED,s,"rejected");
        }
    }
    @Test void separationHeadingsAndDependentQualifiersStayBinding() {
        for(String prefix:Arrays.asList("For another project only:","Do not use this answer:","Please confirm:"))
            for(String gap:Arrays.asList("\n","\n\n")) {
                String s=prefix+gap+SEPARATED;check("buildingDemolitionSitesSeparated",true,SEPARATED,s,s,"rejected");
            }
        String s=SEPARATED+"\n\nThis remains pending.";
        check("buildingDemolitionSitesSeparated",true,SEPARATED,s,s,"rejected");
        check("buildingDemolitionSitesSeparated",true,SEPARATED,SEPARATED,s,"rejected");
    }
    @Test void separateTrueEvidenceCannotWashAWrongRoleSameValueCandidate() {
        String wrong="The obsolete stores are a standalone building, detached from all retained structures.";
        String source=SEPARATED+"\n\n"+wrong;
        check("buildingDemolitionSitesSeparated",true,SEPARATED,source,source,"accepted");
        check("buildingDemolitionSitesSeparated",true,wrong,source,source,"rejected");
    }
    @Test void aDirectUnrelatedNegativeDoesNotReverseSeparation() {
        check("buildingDemolitionSitesSeparated",true,SEPARATED+" The roofing warranty is not adopted.","accepted");
        check("buildingDemolitionSitesSeparated",true,SEPARATED+" Another project has adjoining sites.","accepted");
        check("buildingDemolitionSitesSeparated",true,SEPARATED+" The building and demolition sites are not separated.","rejected");
    }
    @Test void neutralHeadingParagraphsRetainTheOuterProjectRole() {
        for(String key:Arrays.asList("domesticBlocks","footingsServeBuildingsOrMajorExternalStructures","buildingDemolitionSitesSeparated")) {
            String q=scopeQuote(key),s="For another project only:\n\nScope:\n\n"+q;
            check(key,true,q,s,s,"rejected");check(key,true,q,q,s,"rejected");
        }
    }
    @Test void forbiddenAndPendingHeadingChainsCannotBeAdoptedByACurrentProjectLabel() {
        for(String key:Arrays.asList("domesticBlocks","footingsServeBuildingsOrMajorExternalStructures","buildingDemolitionSitesSeparated"))
            for(String role:Arrays.asList("Do not use this answer:","Please confirm:")) {
                String q=scopeQuote(key),s=role+"\n\nFor this project:\n\nScope:\n\n"+q;
                check(key,true,q,s,s,"rejected");check(key,true,q,q,s,"rejected");
            }
    }
    @Test void anExplicitCurrentProjectHeadingResetsOnlyTheOtherProjectRole() {
        for(String key:Arrays.asList("domesticBlocks","footingsServeBuildingsOrMajorExternalStructures","buildingDemolitionSitesSeparated")) {
            String q=scopeQuote(key),s="For another project only:\n\nScope:\n\nFor this project:\n\nScope:\n\n"+q;
            check(key,true,q,s,s,"accepted");
            s="For this project:\n\nFor another project only:\n\nScope:\n\n"+q;
            check(key,true,q,s,s,"rejected");
        }
    }
    @Test void aCompleteIndependentBodyEndsThePreviousHeadingUnit() {
        for(String key:Arrays.asList("domesticBlocks","footingsServeBuildingsOrMajorExternalStructures","buildingDemolitionSitesSeparated")) {
            String q=scopeQuote(key),s="Do not use this answer:\n\nThe old roof description is obsolete.\n\nFor this project:\n\nScope:\n\n"+q;
            check(key,true,q,s,s,"accepted");
        }
    }
    @Test void anotherFieldsPendingClassificationCannotInvalidateAnAssertedScope() {
        for(String key:Arrays.asList("domesticBlocks","footingsServeBuildingsOrMajorExternalStructures","buildingDemolitionSitesSeparated","projectInTinShuiWai")) {
            String q="projectInTinShuiWai".equals(key)?"The project is in Tin Shui Wai.":scopeQuote(key);
            for(String pending:Arrays.asList("Combined-foundation classification remains pending until the drawings are checked.",
                    "The roof design decision remains unconfirmed.","For another project, the footings classification remains pending.")) {
                String s=q+"\n"+pending;check(key,true,q,s,s,"accepted");
            }
        }
    }
    @Test void anUnqualifiedPendingClassificationStillGovernsItsOwnScope() {
        for(String key:Arrays.asList("domesticBlocks","footingsServeBuildingsOrMajorExternalStructures","buildingDemolitionSitesSeparated","projectInTinShuiWai")) {
            String q="projectInTinShuiWai".equals(key)?"The project is in Tin Shui Wai.":scopeQuote(key);
            for(String pending:Arrays.asList("The classification remains pending.","The decision remains unconfirmed.")) {
                String s=q+"\n"+pending;check(key,true,q,s,s,"rejected");
            }
        }
    }
    private static String scopeQuote(String key) {
        return "domesticBlocks".equals(key)?DOMESTIC:"footingsServeBuildingsOrMajorExternalStructures".equals(key)?"The footings serve buildings.":SEPARATED;
    }
    private static void check(String key,Object value,String q,String expected){check(key,value,q,q,q,expected);}
    private static void check(String key,Object value,String q,String supplied,String original,String expected) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",q);item.put("reason","Source relationship boundary");item.put("confidence",.98);
        ExtractionPartVO part=new ExtractionPartVO("round2-boundary:0",1L,"round2-source.txt","source",0,supplied,new ArrayList<>());
        part.setContext(new ExtractionContextVO("round2-boundary",Collections.singletonList(key),0,supplied.length(),supplied,null));
        ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
        assertEquals(JsonUtils.write(value),JsonUtils.write(d.getRawValue()));assertEquals(q,d.getSourceQuote());
        assertEquals(expected,d.getStatus(),key+" "+supplied+" "+d.getCodes());
    }
}
