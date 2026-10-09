package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A sole immediately previous business subject can qualify its own decision with Its/Their. */
class IndependentPossessivePendingScopeProbeTest {
    @Test void soleFootingSubjectTheirPendingClassificationIsNotAnAdoptedScopeAnswer() {
        String quote="The footings serve buildings.";
        check("footingsServeBuildingsOrMajorExternalStructures",quote,quote+" Their classification remains pending.","rejected");
    }
    @Test void soleDomesticBlockItsPendingClassificationIsNotAnAdoptedScopeAnswer() {
        String quote="The staff housing is a domestic residential block.";
        check("domesticBlocks",quote,quote+" Its classification remains unconfirmed.","rejected");
    }
    @Test void soleProjectSubjectItsPendingClassificationIsNotAnAdoptedLocationAnswer() {
        String quote="The project is in Tin Shui Wai.";
        check("projectInTinShuiWai",quote,quote+" Its classification remains pending.","rejected");
    }
    @Test void dependentPossessiveQualificationDoesNotDisappearAtBlankLine() {
        String quote="The footings serve buildings.";
        check("footingsServeBuildingsOrMajorExternalStructures",quote,quote+"\n\nTheir classification remains pending.","rejected");
    }
    @Test void unrelatedWarrantiesAntecedentDoesNotPoisonTheFootingAnswer() {
        String quote="The footings serve buildings.";
        String source=quote+" The roofing and waterproofing warranties remain under discussion. Their classification remains pending.";
        check("footingsServeBuildingsOrMajorExternalStructures",quote,source,"accepted");
    }
    @Test void anIndependentPossessiveWarrantyQualificationInTheNextTopicDoesNotPoisonLocation() {
        String quote="The project is in Tin Shui Wai.";
        String source=quote+"\n\nThe roofing warranty is being discussed. Its classification remains pending.";
        check("projectInTinShuiWai",quote,source,"accepted");
    }
    private static void check(String key,String quote,String source,String expected){
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",true);item.put("sourceQuote",quote);item.put("confidence",.98);
        ExtractionPartVO part=new ExtractionPartVO("possessive-pending:0",1L,"source.txt","source-hash",0,source,new ArrayList<>());
        ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,source);
        assertEquals(JsonUtils.write(true),JsonUtils.write(d.getRawValue()));assertEquals(expected,d.getStatus(),key+" "+source+" "+d.getCodes());
    }
}
