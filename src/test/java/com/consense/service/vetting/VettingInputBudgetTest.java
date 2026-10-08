package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingInputBudgetTest {
    private VettingInputBudget.Input input(){return VettingInputBudget.input("fixture-provider","fixture-model","Exact system","Native cells ['', 'Room | East 😀 中']",JsonUtils.parse("{\"type\":\"array\"}"),100,VettingCorpus.hash("source snapshot"));}
    static VettingInputBudget.Observation fixtureObservation(VettingInputBudget.Input in) {
        VettingInputBudget.Observation o=new VettingInputBudget.Observation();o.setModel(in.getModel());o.setSerializedInputSha256(in.getSerializedInputSha256());o.setTokenizerIdentitySha256(VettingCorpus.hash("SYNTHETIC_ONLY_tokenizer_fixture"));o.setChatTemplateIdentitySha256(VettingCorpus.hash("SYNTHETIC_ONLY_chat_template_fixture"));o.setEffectiveContextIdentitySha256(VettingCorpus.hash("SYNTHETIC_ONLY_context_fixture"));o.setInputTokens(100);o.setEffectiveContextTokens(100000);o.setOutputReserveTokens(in.getOutputReserveTokens());o.setCompleteChatTemplateAndSchemaObserved(true);return o;
    }
    @Test void completeGatewayEnvelopeMessagesSchemaAndSourceAreBoundWithoutTruncation() {
        VettingInputBudget.Input in=input();assertTrue(in.getMessages().get(0).getContent().endsWith("Return only a JSON array conforming to this schema:\n{\"type\":\"array\"}"));assertEquals("Native cells ['', 'Room | East 😀 中']",in.getMessages().get(1).getContent());
        assertNotEquals(in.getSerializedInputSha256(),VettingInputBudget.input("fixture-provider","different-model","Exact system","Native cells ['', 'Room | East 😀 中']",in.getSchema(),100,in.getSourceSnapshotSha256()).getSerializedInputSha256());
        assertNotEquals(in.getSerializedInputSha256(),VettingInputBudget.input("fixture-provider","fixture-model","Exact system","Native cells ['', 'Room | East 😀 中'] ",in.getSchema(),100,in.getSourceSnapshotSha256()).getSerializedInputSha256());
        assertNotEquals(in.getSerializedInputSha256(),VettingInputBudget.input("fixture-provider","fixture-model","Exact system",in.getMessages().get(1).getContent(),in.getSchema(),101,in.getSourceSnapshotSha256()).getSerializedInputSha256());
    }
    @Test void unknownObserverFailsClosedAndBoundSyntheticObservationIsExplicit() {
        assertFalse(VettingInputBudget.check(input(),null).isExtraDispatchPermitted());assertEquals("budget_unknown",VettingInputBudget.check(input(),null).getStatus());
        VettingInputBudget.Result r=VettingInputBudget.check(input(),VettingInputBudgetTest::fixtureObservation);assertEquals("observed_tokens",r.getStatus());assertTrue(r.isExtraDispatchPermitted());
    }
    @Test void wrongModelTokenizerTemplateContextIdentityReserveOrInputCannotPermitDispatch() {
        for(int choice=0;choice<7;choice++){final int n=choice;VettingInputBudget.Result r=VettingInputBudget.check(input(),in->{VettingInputBudget.Observation o=fixtureObservation(in);switch(n){case 0:o.setModel("other");break;case 1:o.setSerializedInputSha256(VettingCorpus.hash("other input"));break;case 2:o.setTokenizerIdentitySha256(null);break;case 3:o.setChatTemplateIdentitySha256("unknown");break;case 4:o.setEffectiveContextIdentitySha256(null);break;case 5:o.setOutputReserveTokens(99);break;case 6:o.setCompleteChatTemplateAndSchemaObserved(false);break;}return o;});assertEquals("budget_unknown",r.getStatus());assertFalse(r.isExtraDispatchPermitted());}
    }
    @Test void totalInputAndOutputReserveMustFitExactEffectiveContextBoundary() {
        VettingInputBudget.Result fit=VettingInputBudget.check(input(),in->{VettingInputBudget.Observation o=fixtureObservation(in);o.setEffectiveContextTokens(200);return o;});assertTrue(fit.isExtraDispatchPermitted());
        VettingInputBudget.Result over=VettingInputBudget.check(input(),in->{VettingInputBudget.Observation o=fixtureObservation(in);o.setEffectiveContextTokens(199);return o;});assertEquals("over_budget",over.getStatus());assertFalse(over.isExtraDispatchPermitted());
    }
    @Test void observerMutationOrFailureCannotAlterActualPromptOrEnableDispatch() {
        VettingInputBudget.Input in=input();String before=JsonUtils.write(in);VettingInputBudget.Result r=VettingInputBudget.check(in,s->{VettingInputBudget.Observation o=fixtureObservation(s);s.getMessages().set(1,com.consense.ai.LlmClient.ChatTurn.user("truncated"));return o;});assertEquals("token_input_binding_changed",r.getReason());assertFalse(r.isExtraDispatchPermitted());assertEquals(before,JsonUtils.write(in));
        r=VettingInputBudget.check(in,s->{throw new IllegalStateException("fixture unavailable");});assertFalse(r.isExtraDispatchPermitted());assertEquals("budget_unknown",r.getStatus());
    }
    @Test void prechangedInputOrZeroTokenCountIsUnknownRatherThanAnEstimate() {
        VettingInputBudget.Input in=input();in.getMessages().set(1,com.consense.ai.LlmClient.ChatTurn.user("changed before counter"));assertFalse(VettingInputBudget.check(in,VettingInputBudgetTest::fixtureObservation).isExtraDispatchPermitted());
        assertFalse(VettingInputBudget.check(input(),s->{VettingInputBudget.Observation o=fixtureObservation(s);o.setInputTokens(0);return o;}).isExtraDispatchPermitted());
    }
}
