package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Real public harness and gateway; the sole external double is LlmClient.chat. */
final class DraftHarnessTestIntake {
    private DraftHarnessTestIntake() { }

    static ExtractionDecisionVO primaryDecision(JsonNode item,ExtractionPartVO part,String originalSource) {
        List<ExtractionDecisionVO> decisions=primaryDecisions(JsonUtils.write(Collections.singletonList(item)),part,originalSource);
        assertEquals(1,decisions.size(),"The single frozen model item must reach the public primary intake.");
        return decisions.get(0);
    }

    static List<ExtractionDecisionVO> primaryDecisions(String raw,ExtractionPartVO part,String originalSource) {
        DraftExtractionHarness prompts=new DraftExtractionHarness(null);
        return primaryDecisions(raw,part,originalSource,1,prompts.systemPrompt(""),prompts.userPrompt("",part));
    }

    /** Frozen scheduling maps a captured answer's ordinal without bypassing its public decoding/intake. */
    static List<ExtractionDecisionVO> primaryDecisions(String raw,ExtractionPartVO sourcePart,String originalSource,
            int capturedAttemptIndex,String systemPrompt,String userPrompt) {
        assertTrue(capturedAttemptIndex>0);
        LlmClient client=mock(LlmClient.class);
        when(client.available()).thenReturn(true);when(client.chatModel()).thenReturn("test-only-frozen-answer");
        // Additional real repair/coverage dispatches receive no new items. They cannot rescue a bad assertion.
        when(client.chat(anyList())).thenReturn(raw,"[]");
        DraftExtractionHarness harness=new DraftExtractionHarness(new AiGateway(client));
        ExtractionPartVO part=new ExtractionPartVO(sourcePart.getPartId(),sourcePart.getSourceDocumentId(),sourcePart.getFileName(),
                sourcePart.getSourceHash(),sourcePart.getPartIndex(),sourcePart.getSourceText(),new ArrayList<>());
        part.setContext(sourcePart.getContext());
        // Ordinal placeholders belong only to this disposable trace; they are never published or model replies.
        for(int index=1;index<capturedAttemptIndex;index++)
            part.getAttempts().add(new ExtractionAttemptVO(index,"scheduling-placeholder","","",null,"not-dispatched",null));
        ExtractTraceVO trace=new ExtractTraceVO("test-only-frozen-answer",null,systemPrompt,userPrompt,new ArrayList<>());
        trace.getParts().add(part);
        harness.extractPart(trace,part,systemPrompt,userPrompt,originalSource);
        assertFalse(trace.getRawResponses().isEmpty());assertEquals(raw,trace.getRawResponses().get(0));
        for(int index=1;index<trace.getRawResponses().size();index++)assertEquals("[]",trace.getRawResponses().get(index));
        ExtractionAttemptVO primary=part.getAttempts().get(capturedAttemptIndex-1);
        assertEquals("primary",primary.getKind());assertEquals("completed",primary.getStatus());
        verify(client,atLeastOnce()).chat(anyList());
        return trace.getDecisions().stream().filter(decision->decision.getAttemptIndex()==capturedAttemptIndex).collect(Collectors.toList());
    }
}
