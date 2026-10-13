package com.consense.service.advice;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit Root-saved complete prompts only. Prepares files; never counts/generates via a provider. */
class AdviceResponsesPreparedReplayTest {
    static String sourceText(JsonNode descriptor)throws Exception{
        assertTrue(descriptor.isObject());Path p=Paths.get(descriptor.path("path").asText());assertTrue(p.isAbsolute());
        byte[] bytes=Files.readAllBytes(p);String text=new String(bytes,StandardCharsets.UTF_8);assertArrayEquals(bytes,text.getBytes(StandardCharsets.UTF_8),"Malformed UTF8 prompt");
        assertEquals(descriptor.path("sha256").asText(),VettingCorpus.hash(text),"Frozen full prompt identity differs");return text;
    }
    @Test void rootSavedFullSystemQuestionMaterialsAndRetryPrepareWithoutAnyProviderDispatch()throws Exception{
        String selected=System.getenv("CONSENSE_ADVICE_RESPONSES_REPLAY_INPUT");Assumptions.assumeTrue(selected!=null&&!selected.trim().isEmpty(),"Root-saved Advice full-wire replay not selected");
        byte[] inputBytes=Files.readAllBytes(Paths.get(selected));JsonNode input=JsonUtils.parse(new String(inputBytes,StandardCharsets.UTF_8));
        assertEquals("consense.advice.responses-prepared-replay-input.v1",input.path("schema").asText());assertFalse(input.has("apiKey"));
        String system=sourceText(input.path("system")),user=sourceText(input.path("user")),source=input.path("sourceSnapshotSha256").asText();
        JsonNode config=input.path("selectedChatProfile"),response=input.path("responsesOperationPolicy");assertEquals("minimax-cn",config.path("profile").asText());
        ConsenseProperties d=new ConsenseProperties();ConsenseProperties.Llm c=d.getMinimaxCn();c.setProvider(config.path("provider").asText());c.setBaseUrl(config.path("baseUrl").asText());c.setChatModel(config.path("model").asText());
        c.setTemperature(config.path("temperature").asDouble());c.setNumCtx(config.path("numCtx").asInt());c.setStructuredMaxTokens(config.path("structuredMaxTokens").asInt());c.setStructuredOpenAiThinkingMode(config.path("thinkingMode").asText());
        c.setStructuredThinking(config.hasNonNull("structuredThinking")?config.path("structuredThinking").asBoolean():null);
        c.setStructuredOpenAiReasoningSplit(config.hasNonNull("reasoningSplit")?config.path("reasoningSplit").asBoolean():null);c.setApiKey("file-only-no-real-key");
        assertEquals(config.path("configurationSha256").asText(),LlmProfiles.configurationSha256(c),"Saved safe profile SHA differs");
        AdviceModelProperties a=new AdviceModelProperties();a.setMinimaxWire("responses");ConsenseProperties.Responses r=a.getResponses();r.setEnabled(true);r.setModel(c.getChatModel());r.setBaseUrl(c.getBaseUrl());r.setTemperature(c.getTemperature());r.setMaxOutputTokens(c.getStructuredMaxTokens());
        r.setConfiguredContextTokens(response.path("configuredContextTokens").asLong());r.setSafetyMarginTokens(response.path("safetyMarginTokens").asLong());r.setEstimatePolicy(response.path("estimatePolicy").asText());
        r.setCounterTimeoutMs(response.path("counterTimeoutMs").asLong(10000));r.setGenerationTimeoutMs(response.path("generationTimeoutMs").asLong(300000));
        r.setCounterMaxResponseBytes(response.path("counterMaxResponseBytes").asInt(1048576));r.setGenerationMaxResponseBytes(response.path("generationMaxResponseBytes").asInt(4194304));
        LlmProfiles profiles=new LlmProfiles(d,mock(LlmClient.class),mock(LlmClient.class));AdviceResponsesTransport transport=new AdviceResponsesTransport(d,a,(endpoint,body,timeout,cap,auth)->{throw new AssertionError("File-only replay must never count or generate");});
        AdviceResponsesTransport.Operation operation=transport.capture(profiles.resolve("minimax-cn"));Path output=Paths.get(input.path("outputDirectory").asText());assertTrue(output.isAbsolute());Files.createDirectories(output);
        List<Map<String,Object>> artifacts=new ArrayList<>();for(int attempt=0;attempt<2;attempt++){
            AdviceResponsesTransport.PreparedRequest prepared=operation.prepare(system,user,source,attempt);
            assertEquals(system+AdviceResponsesTransport.JSON_INSTRUCTION,JsonUtils.parse(prepared.getGenerationJson()).path("instructions").asText());
            assertEquals(user+(attempt==1?AdviceResponsesTransport.RETRY_INSTRUCTION:""),JsonUtils.parse(prepared.getGenerationJson()).path("input").get(0).path("content").asText());
            for(String kind:Arrays.asList("generation","counter")){
                String body="generation".equals(kind)?prepared.getGenerationJson():prepared.getCounterJson();Path p=output.resolve("attempt-"+attempt+"-"+kind+".json");Files.write(p,body.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
                Map<String,Object> item=new LinkedHashMap<>();item.put("attempt",attempt);item.put("kind",kind);item.put("path",p.toString());item.put("sha256",VettingCorpus.hash(body));item.put("javaChars",body.length());item.put("utf8Bytes",body.getBytes(StandardCharsets.UTF_8).length);item.put("bindingSha256",prepared.getBindingSha256());item.put("sourceSnapshotSha256",prepared.getSourceSnapshotSha256());item.put("profileSha256",prepared.getProfileSha256());item.put("contextPolicySha256",prepared.getContextPolicySha256());item.put("configuredOperationContextTokens",prepared.getConfiguredContextTokens());item.put("outputReserveTokens",prepared.getOutputReserveTokens());item.put("safetyMarginTokens",prepared.getSafetyMarginTokens());artifacts.add(item);
            }
        }
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("schema","consense.advice.responses-prepared-wire-receipt.v1");receipt.put("replayInputBytesSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(inputBytes)));receipt.put("modelIdentity",operation.getIdentity());receipt.put("artifacts",artifacts);receipt.put("inputTokens",null);receipt.put("tokenAdmission","budget_unknown_not_observed");receipt.put("providerRequests",0);receipt.put("questionAnswerGeneration",false);receipt.put("currentHostedChatTemplateEquivalence","unknown");receipt.put("currentSourceSessionObserved",false);receipt.put("sourceSnapshotProvenance","caller_supplied_frozen_file_replay_not_live_source_validation");receipt.put("fixtureOrigin",input.path("fixtureOrigin").asText("root_supplied_frozen_replay_current_identity_not_observed_here"));receipt.put("responsePolicyIsDeclaredOperationLimitNotMeasuredProviderMaximum",true);
        Files.write(output.resolve("prepared-wire-receipt.json"),JsonUtils.write(receipt).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
}
