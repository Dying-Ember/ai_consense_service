package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the production intake seam; these values are source examples, never model answers from an oracle. */
class DraftScalarEvidenceRegressionTest {
    @Test void EnglishRepaymentNumbersRetainTheirFieldRoles() throws Exception {
        String quote="The advance payment is repaid over eight consecutive months starting with the fifth interim payment certificate.";
        assertAccepted("advanceRepaymentMonths",8,quote);
        assertAccepted("advanceRepaymentFirstCertificate",5,quote);
        assertRejected("advanceRepaymentMonths",5,quote);
        assertRejected("advanceRepaymentFirstCertificate",8,quote);
    }

    @Test void NumberWordsAreGeneralRepresentationsRatherThanFrozenEightAndFive() throws Exception {
        assertAccepted("advanceRepaymentMonths",12,"Repayment of the advance spans twelve consecutive months.");
        assertAccepted("advanceRepaymentFirstCertificate",21,"Advance repayment starts with the twenty-first interim payment certificate.");
        assertAccepted("contractPeriodMonths",44,"The accepted contract period is forty-four months.");
        assertRejected("contractPeriodMonths",4,"The accepted contract period is forty-four months.");
    }

    @Test void NumericRepaymentRolesCannotBeSwappedEither() throws Exception {
        String quote="Advance repayment lasts 8 months and starts with payment certificate number 5.";
        assertAccepted("advanceRepaymentMonths",8,quote);
        assertAccepted("advanceRepaymentFirstCertificate",5,quote);
        assertRejected("advanceRepaymentMonths",5,quote);
        assertRejected("advanceRepaymentFirstCertificate",8,quote);
    }

    @Test void PendingQuestionsAndNegatedNumbersAreNotDecisions() throws Exception {
        for(String quote:Arrays.asList("Please confirm whether advance repayment lasts eight months.",
                "Advance repayment of eight months remains pending.",
                "Advance repayment is not eight months.",
                "Advance repayment is proposed for eight months.",
                "Advance repayment lasts between eight and twelve months."))
            assertRejected("advanceRepaymentMonths",8,quote);
    }

    @Test void ResidentialModifiersAreStillActualScope() throws Exception {
        assertAccepted("domesticBlocks",true,"The Works include one domestic accommodation block.");
        assertAccepted("domesticBlocks",true,"The Works comprise a domestic caretaker accommodation block.");
        assertAccepted("domesticBlocks",false,"The Works exclude domestic caretaker accommodation blocks.");
    }

    @Test void ResidentialNeighboursAndPossibleOptionsRemainUnsupported() throws Exception {
        for(String quote:Arrays.asList("The adjacent domestic caretaker accommodation block is existing.",
                "Please confirm whether the Works include a domestic accommodation block.",
                "One option includes a residential accommodation block."))
            assertRejected("domesticBlocks",true,quote);
    }

    @Test void NegationOfOtherFacilitiesCannotReverseResidentialScope() throws Exception {
        assertAccepted("domesticBlocks",true,"The Works include a domestic accommodation block and no swimming pool.");
        assertAccepted("domesticBlocks",true,"The Works have no swimming pool and include a residential block.");
        assertAccepted("domesticBlocks",false,"The Works do not include a residential accommodation block.");
        assertAccepted("domesticBlocks",false,"The residential accommodation block is not included in the Works.");
    }

    @Test void MonthsAndCertificatesNeedTheirBusinessRoleAndCannotIgnoreSigns() throws Exception {
        String quote="The contract period is forty-four months. Advance repayment lasts eight consecutive months.";
        assertAccepted("contractPeriodMonths",44,quote);
        assertAccepted("advanceRepaymentMonths",8,quote);
        assertRejected("contractPeriodMonths",8,quote);
        assertRejected("advanceRepaymentMonths",44,quote);
        assertRejected("advanceRepaymentMonths",6,"The defects liability period is six months.");
        assertRejected("advanceRepaymentFirstCertificate",5,"The fifth interim payment certificate was signed by the Architect.");
        assertRejected("advanceRepaymentMonths",8,"Advance repayment lasts -8 months.");
        assertRejected("advanceRepaymentMonths",8,"Advance repayment lasts +8 months.");
    }

    @Test void PhotocopySizeRolesCannotBorrowEachOthersRates() throws Exception {
        String quote="The photocopy charge per page up to A3 is HK$2.40; above A3 is HK$5.80.";
        assertAccepted("photocopyRateUpToA3",2.4,quote);
        assertAccepted("photocopyRateAboveA3",5.8,quote);
        assertRejected("photocopyRateUpToA3",5.8,quote);
        assertRejected("photocopyRateAboveA3",2.4,quote);
    }

    @Test void AllBqStatusNeedsAStatementAboutAllQuantities() throws Exception {
        assertAccepted("allBqQuantitiesProvisional",true,"All BQ quantities are provisional for this Contract.");
        assertAccepted("allBqQuantitiesProvisional",false,"Not all BQ quantities are provisional for this Contract.");
        assertRejected("allBqQuantitiesProvisional",false,"Only the formal description of BQ5 includes (All Provisional); BQ1 is named Preliminaries.");
        assertRejected("allBqQuantitiesProvisional",true,"Bill No. | Formal description\n5 | Community Hall Finishes (All Provisional)");
        assertRejected("allBqQuantitiesProvisional",true,"Please confirm whether all BQ quantities are provisional.");
        assertRejected("allBqQuantitiesProvisional",true,"All BQ quantities are provisional. Not all BQ quantities are provisional.");
        assertRejected("allBqQuantitiesProvisional",false,"All BQ quantities are not fixed.");
        assertRejected("allBqQuantitiesProvisional",false,"Not all BQ quantities are firm.");
        assertRejected("allBqQuantitiesProvisional",true,"All BQ quantities are provisional except the entrance canopy quantities.");
    }

    @Test void PhotocopySizeLimitNegationDoesNotNegateTheSuppliedRate() throws Exception {
        for(String size:new String[]{"no larger than A3","not exceeding A3"}) {
            String quote="The photocopy rate per page for sizes "+size+" is HK$1.50.";
            assertAccepted("photocopyRateUpToA3",1.5,quote);
            assertRejected("photocopyRateAboveA3",1.5,quote);
            assertRejected("photocopyRateUpToA3",1.5,"The photocopy rate per page for sizes "+size+" is not HK$1.50.");
        }
        assertRejected("photocopyRateUpToA3",1.5,"There is no charge of HK$1.50 for photocopies up to A3.");
        assertRejected("photocopyRateUpToA3",1.5,"The photocopy rate per page for sizes up to A3 is not HK$1.50.");
    }

    private static void assertAccepted(String key,Object value,String source) throws Exception {
        ExtractionDecisionVO result=decide(key,value,source);
        assertEquals("accepted",result.getStatus(),key+" "+source+" "+result.getCodes());
    }
    private static void assertRejected(String key,Object value,String source) throws Exception {
        ExtractionDecisionVO result=decide(key,value,source);
        assertEquals("rejected",result.getStatus(),key+" "+source+" "+result.getCodes());
    }
    private static ExtractionDecisionVO decide(String key,Object value,String source) throws Exception {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);
        item.put("sourceQuote",source);item.put("reason","An offline source-intake regression.");item.put("confidence",0.95);
        ExtractionContextVO context=new ExtractionContextVO("scalar-regression",Collections.emptyList(),0,source.length(),source,null);
        ExtractionPartVO part=new ExtractionPartVO("scalar:0",1L,"scalar-regression.txt","source",0,source,new ArrayList<>());
        part.setContext(context);
        return DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,source);
    }
}
