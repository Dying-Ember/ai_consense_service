package com.consense.service.advice;

import com.consense.ai.LlmClient;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.VettingInputBudget;
import com.consense.service.vetting.VettingTokenBudgetCliObserver;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Bound authored observations for isolated contract tests; never a real tokenizer or model acceptance. */
public final class AdviceBudgetFixture {
    private AdviceBudgetFixture() { }
    public static void bind(VettingTokenBudgetCliObserver counter,LlmClient... clients) {
        doAnswer(call->observation(call.getArgument(0))).when(counter).observe(any());
        for(LlmClient client:clients)doAnswer(call->client.chat(call.getArgument(0))).when(client).chatStructured(anyList(),any());
    }
    public static VettingInputBudget.Observation observation(VettingInputBudget.Input input) {
        VettingInputBudget.Observation result=new VettingInputBudget.Observation();result.setModel(input.getModel());result.setSerializedInputSha256(input.getSerializedInputSha256());
        result.setTokenizerIdentitySha256(VettingCorpus.hash("AUTHORED TOKENIZER TEST ONLY"));result.setChatTemplateIdentitySha256(VettingCorpus.hash("AUTHORED TEMPLATE TEST ONLY"));result.setEffectiveContextIdentitySha256(VettingCorpus.hash("AUTHORED CONTEXT TEST ONLY"));
        result.setCompleteChatTemplateAndSchemaObserved(true);result.setOutputReserveTokens(input.getOutputReserveTokens());result.setEffectiveContextTokens(262144);result.setInputTokens(1000);return result;
    }
}
