package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.document.DocumentParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftClauseRulesTest {
    private static final String SOURCE = "10. Sureties\nAppendix *G1/*G1a to Conditions.\n11. Next clause\nKeep eleven.\n13. Subcontractors\n*Such requirement does not apply to engagement of Nominated Sub-contractor defined in GCC Clause 1.1 and works contractor selected by the Contractor from the relevant list given or referred to in the Specification, but it shall apply to the engagement of sub-contractor of the Nominated Sub-contractor and sub-contractor of the works contractor selected by the Contractor from the relevant list given or referred to in the Specification.\n(*Amend if NSC is not applicable)\n14. Next\nKeep fourteen.";
    private Map<String,Object> decisions(boolean g1a, boolean nsc) { Map<String,Object> d=new HashMap<>();d.put("g1aTrigger",g1a);d.put("nscApplicable",nsc);return d; }
    @Test void selectsG1aOnlyForTrueTriggerAndPreservesG301() {
        assertTrue(DraftClauseRules.apply(SOURCE,decisions(true,true)).contains("Appendix G1a to"));
        assertTrue(DraftClauseRules.apply(SOURCE,decisions(false,true)).contains("Appendix G1 to"));
        assertTrue(DraftClauseRules.apply(SOURCE.replace("*G1/*G1a","G301"),decisions(true,true)).contains("Appendix G301 to"));
    }
    @Test void nscInstructionRemovesOnlyNominatedException() {
        String output=DraftClauseRules.apply(SOURCE,decisions(false,false));
        assertFalse(output.contains("Nominated Sub-contractor"));
        assertTrue(output.contains("works contractor selected by the Contractor"));
        assertTrue(output.contains("sub-contractor of the works contractor"));
        assertTrue(output.contains("13. Subcontractors"));
        assertTrue(output.contains("Keep eleven."));assertTrue(output.contains("Keep fourteen."));
    }
    @Test void missingInstructionLeavesClause13Unchanged() {
        String source=SOURCE.replace("(*Amend if NSC is not applicable)","");
        String output=DraftClauseRules.apply(source,decisions(false,false));
        assertEquals(source.substring(source.indexOf("13.")),output.substring(output.indexOf("13.")));
    }
    @Test void protectedClausesCannotBeRewrittenByModel() {
        String source=DraftClauseRules.apply(SOURCE,decisions(true,false));
        String hallucinated=source.replace("Appendix G1a", "Appendix G301").replace("works contractor selected", "everyone selected");
        assertEquals(source,DraftClauseRules.protect(source,hallucinated));
        assertThrows(BizException.class,()->DraftClauseRules.protect(source,"Unnumbered summary"));
    }
    @Test void completeChunksRetainTailAndRejectSummaries() {
        String source=String.join("\n",Collections.nCopies(400,"The original unrelated condition must remain in the contract."))+"\nTAIL SENTINEL";
        List<String> chunks=DraftClauseRules.chunks(source,8000);
        assertEquals(source,String.join("",chunks));assertTrue(chunks.size()>1);
        assertThrows(BizException.class,()->DraftClauseRules.requireCoverage(chunks.get(0),"Summary only"));
        assertThrows(BizException.class,()->DraftClauseRules.requireCoverage("10. An original clause\n11. Another clause", "10. An original clause\nThis is another long clause without its number."));
    }
}
