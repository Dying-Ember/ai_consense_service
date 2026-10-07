package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingSubstantiveEvidenceTest {
    @ParameterizedTest @ValueSource(strings={"APPENDIX PRE.B6/III","APPENDIX PRE.B6/IV","ANNEX C-1", "SCHEDULE 3", "appendix xyz/Ⅳ", " ANNEX C TO APPENDIX A ","APPENDIX\u00a0XYZ/A"})
    void bareIdentifiersDoNotEstablishTargetContents(String text) {
        Chunk c=VettingContextBudgetTest.chunk("label","XYZ1",text,text.length());
        assertTrue(VettingSubstantiveEvidence.standaloneLabel(text));
        assertFalse(VettingSubstantiveEvidence.substantive(c,quote(c,text)));
    }
    @ParameterizedTest @ValueSource(strings={"The works shall follow APPENDIX XYZ/A.","APPENDIX XYZ/A\nFunctions | Hardcopy | E-copy", "XYZ.APPEND4.9 FORM FOR RECORD OF FLUSHING", "The contract period shall be 39 months.", "A label may be part of a substantive obligation."})
    void actualSuppliedProvisionsRemainUsable(String text) {
        Chunk c=VettingContextBudgetTest.chunk("content","XYZ2",text,text.length());
        assertFalse(VettingSubstantiveEvidence.standaloneLabel(text));
        assertTrue(VettingSubstantiveEvidence.substantive(c,quote(c,text)));
    }
    @Test void quotingOnlyTheLabelOfAFullTableStillDoesNotQuoteItsSubstance() {
        String text="APPENDIX XYZ/A\nFunctions | Hardcopy | E-copy";
        Chunk c=VettingContextBudgetTest.chunk("table","XYZ2",text,text.length());
        assertFalse(VettingSubstantiveEvidence.substantive(c,quote(c,"APPENDIX XYZ/A")));
        assertTrue(VettingSubstantiveEvidence.substantive(c,quote(c,"Functions | Hardcopy | E-copy")));
    }
    @Test void onlyLocatedSubstantiveAnchorsCountAndDuplicatesDoNotInflateComparison() {
        Chunk a=VettingContextBudgetTest.chunk("a","XYZ1","The arrangement shall be as stated in APPENDIX XYZ/A.",70);
        Chunk label=VettingContextBudgetTest.chunk("b","XYZ2","APPENDIX XYZ/A",15);
        Chunk target=VettingContextBudgetTest.chunk("c","XYZ3","The relevant table describes a different purpose: flushing.",75);
        Quote q1=quote(a,a.getContent().trim()),q2=quote(label,label.getContent().trim()),q3=quote(target,target.getContent().trim());
        Map<String,Chunk> byId=new LinkedHashMap<>();for(Chunk c:Arrays.asList(a,label,target))byId.put(c.getId(),c);
        List<Quote> weak=Arrays.asList(q1,q2,q1);
        List<FindingEvidence> located=new ArrayList<>();for(Quote q:weak)located.add(VettingCorpus.evidence(byId.get(q.getChunkId()),"source",q.getQuote()));
        assertEquals(1,VettingSubstantiveEvidence.distinct(weak,located,byId));
        assertEquals(2,VettingSubstantiveEvidence.distinct(Arrays.asList(q1,q3),Arrays.asList(VettingCorpus.evidence(a,"source",q1.getQuote()),VettingCorpus.evidence(target,"source",q3.getQuote())),byId));
        assertEquals(0,VettingSubstantiveEvidence.distinct(Collections.singletonList(q1),Collections.singletonList(VettingCorpus.evidence(a,"source","This quote was never supplied in the source.")),byId));
    }
    @ParameterizedTest @ValueSource(strings={"project_fact","standard"})
    void aBareRoleLabelCannotBorrowAnUnrelatedPassageToSatisfyComparisonBasis(String role) throws Exception {
        Chunk origin=VettingContextBudgetTest.chunk("origin","PRE1","Implementation arrangements shall follow XYZ clause 9.",75);origin.setFileKey("PRE");
        Chunk unrelated=VettingContextBudgetTest.chunk("unrelated","OTHER1","The unrelated field specifies a contact person's postal address.",80);unrelated.setFileKey("OTHER");unrelated.setRole("standard");
        Chunk label=VettingContextBudgetTest.chunk("label","XYZ9","APPENDIX XYZ/A",15);label.setRole(role);
        List<Quote> quotes=Arrays.asList(quote(origin,origin.getContent().trim()),quote(unrelated,unrelated.getContent().trim()),quote(label,label.getContent().trim()));
        Map<String,Chunk> byId=new LinkedHashMap<>();for(Chunk c:Arrays.asList(origin,unrelated,label))byId.put(c.getId(),c);
        List<FindingEvidence> evidence=new ArrayList<>();for(Quote q:quotes)evidence.add(VettingCorpus.evidence(byId.get(q.getChunkId()),"source",q.getQuote()));
        assertEquals(2,VettingSubstantiveEvidence.distinct(quotes,evidence,byId));
        java.lang.reflect.Method basis=VettingSemanticReview.class.getDeclaredMethod("hasComparisonBasis",String.class,List.class,List.class,Map.class);basis.setAccessible(true);
        assertEquals(false,basis.invoke(null,"reference",quotes,evidence,byId));
    }
    @ParameterizedTest @ValueSource(strings={"project_fact","standard"})
    void aBareTenderLabelDoesNotEstablishAnAffectedTenderProvision(String supportingRole) throws Exception {
        Chunk bare=VettingContextBudgetTest.chunk("bare","PRE1","APPENDIX SCC1",15);bare.setFileKey("PRE");
        Chunk target=VettingContextBudgetTest.chunk("target","SCC1","The declared supporting source contains substantive text for this field.",85);target.setFileKey("SCC");target.setRole(supportingRole);
        Chunk unrelated=VettingContextBudgetTest.chunk("other","OTHER1","The unrelated source describes contact postal addresses only.",75);unrelated.setFileKey("OTHER");unrelated.setRole("standard");
        assertFalse(basis(bare,target,unrelated));
    }
    @Test void anUnrelatedTenderPassageCannotLendItsRoleToALabelOnlyLiteralOrigin() throws Exception {
        Chunk bare=VettingContextBudgetTest.chunk("bare","PRE1","APPENDIX SCC1",15);bare.setFileKey("PRE");
        Chunk otherTender=VettingContextBudgetTest.chunk("tender","PRE2","The contractor shall provide the specified postal contact address.",80);otherTender.setFileKey("PRE");
        Chunk target=VettingContextBudgetTest.chunk("target","SCC1","The supporting provision concerns another substantive arrangement.",80);target.setFileKey("SCC");target.setRole("standard");
        assertFalse(basis(bare,otherTender,target));
    }
    private static boolean basis(Chunk... chunks) throws Exception {
        List<Quote> quotes=new ArrayList<>();List<FindingEvidence> evidence=new ArrayList<>();Map<String,Chunk> byId=new LinkedHashMap<>();
        for(Chunk c:chunks){Quote q=quote(c,c.getContent().trim());quotes.add(q);byId.put(c.getId(),c);evidence.add(VettingCorpus.evidence(c,"source",q.getQuote()));}
        assertEquals(2,VettingSubstantiveEvidence.distinct(quotes,evidence,byId));
        java.lang.reflect.Method method=VettingSemanticReview.class.getDeclaredMethod("hasComparisonBasis",String.class,List.class,List.class,Map.class);method.setAccessible(true);
        return (Boolean)method.invoke(null,"reference",quotes,evidence,byId);
    }
    private static Quote quote(Chunk c,String text){Quote q=new Quote();q.setChunkId(c.getId());q.setSide("source");q.setQuote(text);return q;}
}
