package com.consense.service.vetting;

/** Explicit synthetic budget identity for fixture gateways; no real tokenizer or model acceptance. */
public final class VettingTokenFixture {
    private VettingTokenFixture() { }
    public static VettingSemanticReview review(com.consense.ai.AiGateway gateway,com.consense.config.ConsenseProperties properties,VettingRetrievalClient retrieval) {
        VettingSemanticReview review=new VettingSemanticReview(gateway,properties,retrieval);
        review.setInputBudgetObserver(VettingTokenFixture::observe);return review;
    }
    public static VettingInputBudget.Observation observe(VettingInputBudget.Input input) {
        VettingInputBudget.Observation observed=new VettingInputBudget.Observation();
        observed.setModel(input.getModel());observed.setSerializedInputSha256(input.getSerializedInputSha256());
        observed.setTokenizerIdentitySha256(VettingCorpus.hash("SYNTHETIC_ONLY_tokenizer_fixture"));
        observed.setChatTemplateIdentitySha256(VettingCorpus.hash("SYNTHETIC_ONLY_chat_template_fixture"));
        observed.setEffectiveContextIdentitySha256(VettingCorpus.hash("SYNTHETIC_ONLY_context_fixture"));
        observed.setInputTokens(100);observed.setEffectiveContextTokens(524288);observed.setOutputReserveTokens(input.getOutputReserveTokens());
        observed.setCompleteChatTemplateAndSchemaObserved(true);return observed;
    }
}
