package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.web.dto.DraftingDtos.*;
import java.nio.file.Paths;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Replays the observed broad-scope conflict through public extraction and persisted variables. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory",
        "logging.level.com.consense.ai.AiGateway=WARN"})
@ActiveProfiles("h2")
class DraftingDesignScopeIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final String BROAD="The appointed Contractor will design and construct the piles, pile caps and shallow footings for the buildings within the same contract, together with the building works.";
    private static final String FULL_QUOTE="The complete component responsibility schedule is:\nComponent | Actual work scope | Contractor designs | Contractor executes\n"
        +"piling | Bored piles for the community hall and library | Yes | Yes\n"
        +"pilecaps | Pile caps for the community hall and library | Yes | Yes\n"
        +"footings | Shallow footings for the sports building and entrance canopy | Yes | Yes\n"
        +"other | Architect designed internal partitions | No | Yes";
    private static final String FULL="[{\"component\":\"piling\",\"design\":true,\"execution\":true,\"scope\":\"Bored piles for the community hall and library\"},"
        +"{\"component\":\"pilecaps\",\"design\":true,\"execution\":true,\"scope\":\"Pile caps for the community hall and library\"},"
        +"{\"component\":\"footings\",\"design\":true,\"execution\":true,\"scope\":\"Shallow footings for the sports building and entrance canopy\"},"
        +"{\"component\":\"other\",\"design\":false,\"execution\":true,\"scope\":\"Architect designed internal partitions\"}]";

    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:design_scope_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","design-scope-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired DraftingService service;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @MockBean LlmClient model;
    private ExtractTraceVO lastTrace;

    @Test void aSameContractBuildingSummaryDoesNotHideTheExistingCompleteTableInEitherOrder() {
        for(boolean reverse:Arrays.asList(false,true)) {
            VariableVO result=extract(BROAD,broadRows(),FULL_QUOTE,reverse);
            assertEquals(JsonUtils.parse(FULL),JsonUtils.parse(result.getValue()),"Show the already-existing complete candidate, without synthesizing rows.");
            assertEquals("suggested",result.getAdoptionState());
            assertEquals(FULL_QUOTE,result.getSource(),"Displayed value, source and note must refer to the same complete candidate.");
            assertEquals(2,result.getCandidates().size(),"The original summary remains inspectable evidence.");
            assertFalse(result.isConfirmed());assertFalse(result.isManuallyEdited());
        }
    }

    @Test void localRestrictionsKeepConflictsWhileOtherProjectEvidenceIsRejected() {
        String local=BROAD.replace("within the same contract","in the East Annex within the same contract");
        VariableVO restricted=extract(local,broadRows(),FULL_QUOTE,false);
        assertEquals("conflict",restricted.getAdoptionState(),local);
        assertTrue(restricted.getValue()==null||restricted.getValue().isEmpty());
        for(String quote:Arrays.asList(BROAD.replace("within the same contract","for another project within the same contract"),
                BROAD.replace("within the same contract","within a different contract"))) {
            VariableVO result=extract(quote,broadRows(),FULL_QUOTE,false);
            assertEquals("suggested",result.getAdoptionState(),quote);
            assertEquals(JsonUtils.parse(FULL),JsonUtils.parse(result.getValue()));
            assertEquals(FULL_QUOTE,result.getSource());
            assertEquals(1,result.getCandidates().size(),"Another project's rejected answer must not become a candidate.");
            assertRejectedSource(quote,broadRows());
        }
    }

    @Test void aDifferentProjectsCompleteTableIsRejectedAndAnActualRoleConflictKeepsHumanSelection() {
        String otherProject=FULL_QUOTE.replace("schedule is:","schedule for another project is:");
        VariableVO outside=extract(BROAD,broadRows(),otherProject,false);
        assertEquals("suggested",outside.getAdoptionState());
        assertEquals(JsonUtils.parse(broadRows()),JsonUtils.parse(outside.getValue()));
        assertEquals(BROAD,outside.getSource());
        assertEquals(1,outside.getCandidates().size());
        assertRejectedSource(otherProject,FULL);
        String quote="The responsibility record applies for this project.\ncomponent: footings; scope: for the buildings; design: No; execution: Yes";
        String conflicting="[{\"component\":\"footings\",\"scope\":\"for the buildings\",\"design\":false,\"execution\":true}]";
        VariableVO roles=extract(quote,conflicting,FULL_QUOTE,false);
        assertEquals("conflict",roles.getAdoptionState());
        assertTrue(roles.getValue()==null||roles.getValue().isEmpty());
    }

    @Test void aLowercaseNamedSectionRestrictionCannotBeErasedAndMatchingScopesRemainSuggestions() {
        String broad="The Contractor will design and construct the footings for the buildings within the same contract, limited to section a.";
        String summary="[{\"component\":\"footings\",\"design\":true,\"execution\":true,\"scope\":\"for the buildings\"}]";
        for(String section:Arrays.asList("A","B")) {
            String fullQuote="The complete component responsibility schedule for this project is:\n"
                +"Component | Actual work scope | Contractor designs | Contractor executes\nfootings | Section "+section+" footings | Yes | Yes";
            String fullValue="[{\"component\":\"footings\",\"design\":true,\"execution\":true,\"scope\":\"Section "+section+" footings\"}]";
            VariableVO result=extract(broad,summary,fullQuote,fullValue,false);
            assertEquals(2,result.getCandidates().size(),"Retain both sourced records.");
            if("A".equals(section)) {
                assertEquals("suggested",result.getAdoptionState());
                assertEquals(JsonUtils.parse(fullValue),JsonUtils.parse(result.getValue()));
                assertEquals(fullQuote,result.getSource());
            } else {
                assertEquals("conflict",result.getAdoptionState(),"Section a cannot expand to Section B.");
                assertTrue(result.getValue()==null||result.getValue().isEmpty());
            }
            assertFalse(result.isConfirmed());assertFalse(result.isManuallyEdited());
        }
    }

    private String broadRows() {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String component:Arrays.asList("piling","pilecaps","footings")) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("component",component);
            row.put("design",true);row.put("execution",true);row.put("scope","for the buildings");rows.add(row);
        }
        return JsonUtils.write(rows);
    }
    private VariableVO extract(String broadQuote,String broadValue,String completeQuote,boolean reverse) {
        return extract(broadQuote,broadValue,completeQuote,FULL,reverse);
    }
    private VariableVO extract(String broadQuote,String broadValue,String completeQuote,String completeValue,boolean reverse) {
        String id="design-scope-"+UUID.randomUUID();Project project=new Project();project.setId(id);
        project.setNameEn(id);project.setNameZhHans(id);projects.save(project);
        if(reverse){source(id,"complete.txt",completeQuote);source(id,"summary.txt",broadQuote);}
        else{source(id,"summary.txt",broadQuote);source(id,"complete.txt",completeQuote);}
        when(model.available()).thenReturn(true);when(model.chatModel()).thenReturn("controlled-design-scope");
        doAnswer(invocation->{
            List<LlmClient.ChatTurn> turns=invocation.getArgument(0);String user=turns.get(turns.size()-1).getContent();
            if(!user.contains("<correspondence-part>"))return "[]";
            if(user.contains("Source document: summary.txt;"))return item(broadValue,broadQuote);
            if(user.contains("Source document: complete.txt;"))return item(completeValue,completeQuote);
            return "[]";
        }).when(model).chat(anyList());
        service.extractVariables(id);
        VariableVO result=service.listVariables(id).stream().filter(row->"designResponsibilities".equals(row.getKey())).findFirst().get();
        assertTrue(((Map<?,?>)service.plan(id,null).get("effectiveValues")).isEmpty());
        lastTrace=service.lastExtractTrace(id);
        assertEquals("completed",lastTrace.getStatus());
        assertFalse(result.isConfirmed());assertFalse(result.isManuallyEdited());
        return result;
    }
    private void assertRejectedSource(String quote,String value) {
        ExtractionDecisionVO rejected=lastTrace.getDecisions().stream()
                .filter(decision->"designResponsibilities".equals(decision.getKey())&&quote.equals(decision.getSourceQuote())&&"rejected".equals(decision.getStatus()))
                .findFirst().orElseThrow(()->new AssertionError("Keep the rejected other-project answer in the trace."));
        assertEquals(JsonUtils.parse(value),JsonUtils.mapper().valueToTree(rejected.getRawValue()));
        assertTrue(rejected.getCodes().contains("quote_value_mismatch"));
    }
    private void source(String project,String name,String text) {
        SourceDocument source=new SourceDocument();source.setProjectId(project);source.setFileName(name);
        source.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);source.setParseStatus("PARSED");source.setTextContent(text);sources.save(source);
    }
    private String item(String value,String quote) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key","designResponsibilities");item.put("value",JsonUtils.parse(value));
        item.put("sourceQuote",quote);item.put("confidence",.98);item.put("reason","Controlled replay of observed responsibility evidence");
        return JsonUtils.write(Collections.singletonList(item));
    }
}
