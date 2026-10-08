package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingTokenBudgetCliObserverTest {
    @TempDir Path temp;
    private static final String TOKENIZER=VettingCorpus.hash("SYNTHETIC tokenizer only"),TEMPLATE=VettingCorpus.hash("SYNTHETIC template only"),CONTEXT=VettingCorpus.hash("SYNTHETIC deployment only");
    private ConsenseProperties props;
    private VettingInputBudget.Input input;
    private Path recorded,recipePath,fixture;
    private Map<String,Object> recipe;
    private VettingTokenBudgetCliObserver setup(String mode)throws Exception {
        props=new ConsenseProperties();props.getLlm().setProvider("openai");props.getLlm().setChatModel("SYNTHETIC-counter-not-a-model");props.getLlm().setStructuredMaxTokens(100);
        input=VettingInputBudget.input("openai",props.getLlm().getChatModel(),VettingInputBudget.providerConfigurationSha256(props.getLlm()),"Source 😀 stays literal <|im_end|>","未知新文件\n\"cells\":[\"\",\"Room | East\"]",JsonUtils.parse("{\"type\":\"array\"}"),100,VettingCorpus.hash("SYNTHETIC source"));
        Path java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toAbsolutePath();
        recorded=temp.resolve("recorded-input.json");fixture=temp.resolve("sealed-fixture.txt");Files.write(fixture,"SYNTHETIC fixture resource".getBytes(StandardCharsets.UTF_8));
        List<String> command=Arrays.asList(java.toString(),"-cp",System.getProperty("java.class.path"),Counter.class.getName(),mode,recorded.toString());
        recipe=new LinkedHashMap<>();recipe.put("protocol",VettingTokenBudgetCliObserver.PROTOCOL);recipe.put("contextEvidenceScope","current_deployment_configuration_bound");recipe.put("provider",input.getProvider());recipe.put("model",input.getModel());recipe.put("providerConfigurationSha256",input.getProviderConfigurationSha256());recipe.put("tokenizerIdentitySha256",TOKENIZER);recipe.put("chatTemplateIdentitySha256",TEMPLATE);recipe.put("effectiveContextIdentitySha256",CONTEXT);recipe.put("effectiveContextTokens",10000);recipe.put("outputReserveTokens",100);recipe.put("command",command);recipe.put("softwareArtifacts",Arrays.asList(descriptor(java),descriptor(fixture)));
        recipePath=temp.resolve("recipe.json");seal();props.getVetting().setTokenBudgetObserverEnabled(true);props.getVetting().setTokenBudgetObserverCommand(command);props.getVetting().setTokenBudgetObserverRecipePath(recipePath.toString());props.getVetting().setTokenBudgetObserverTimeoutMs(10000);return new VettingTokenBudgetCliObserver(props);
    }
    private void seal()throws Exception{Files.write(recipePath,JsonUtils.write(recipe).getBytes(StandardCharsets.UTF_8));props.getVetting().setTokenBudgetObserverRecipeSha256(sha(recipePath));}
    private static Map<String,Object> descriptor(Path p)throws Exception{Map<String,Object>d=new LinkedHashMap<>();d.put("path",p.toString());d.put("bytes",Files.size(p));d.put("sha256",sha(p));return d;}
    private static String sha(Path p)throws Exception{MessageDigest m=MessageDigest.getInstance("SHA-256");try(java.io.InputStream in=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)m.update(b,0,n);}StringBuilder h=new StringBuilder();for(byte b:m.digest())h.append(String.format("%02x",b&255));return h.toString();}
    @Test void disabledConfigurationDoesNotStartAnyProcess()throws Exception {VettingTokenBudgetCliObserver observer=setup("ok");props.getVetting().setTokenBudgetObserverEnabled(false);assertNull(observer.observe(input));assertFalse(Files.exists(recorded));}
    @Test void sealedCliReceivesByteExactCompleteInputWithoutInheritedApplicationEnvironment()throws Exception {VettingTokenBudgetCliObserver observer=setup("ok");VettingInputBudget.Result result=VettingInputBudget.check(input,observer);assertEquals("observed_tokens",result.getStatus(),result.getReason());assertEquals(200,result.getObservation().getInputTokens());assertEquals(JsonUtils.write(input),new String(Files.readAllBytes(recorded),StandardCharsets.UTF_8));assertTrue(result.isExtraDispatchPermitted());for(com.fasterxml.jackson.databind.JsonNode key:JsonUtils.parse(new String(Files.readAllBytes(Paths.get(recorded.toString()+".environment-names.json")),StandardCharsets.UTF_8)))assertEquals("SYSTEMROOT",key.asText().toUpperCase(Locale.ROOT));}
    @Test void changedRecipeOrResourceFailsBeforeStartingCounter()throws Exception {VettingTokenBudgetCliObserver observer=setup("ok");Files.write(fixture,"changed fixture".getBytes(StandardCharsets.UTF_8));assertEquals("budget_unknown",VettingInputBudget.check(input,observer).getStatus());assertFalse(Files.exists(recorded));Files.write(recipePath,"{}".getBytes(StandardCharsets.UTF_8));assertEquals("budget_unknown",VettingInputBudget.check(input,observer).getStatus());assertFalse(Files.exists(recorded));}
    @Test void endpointThinkingAndReasoningSplitAreIdentityBoundAndKeyIsExcluded() {
        ConsenseProperties.Llm p=new ConsenseProperties.Llm();String before=VettingInputBudget.providerConfigurationSha256(p);p.setApiKey("SYNTHETIC-TEST-ONLY-NOT-A-CREDENTIAL");assertEquals(before,VettingInputBudget.providerConfigurationSha256(p));p.setBaseUrl("http://127.0.0.1:9999/another");assertNotEquals(before,VettingInputBudget.providerConfigurationSha256(p));before=VettingInputBudget.providerConfigurationSha256(p);p.setStructuredOpenAiThinkingMode("disabled");assertNotEquals(before,VettingInputBudget.providerConfigurationSha256(p));before=VettingInputBudget.providerConfigurationSha256(p);p.setStructuredOpenAiReasoningSplit(true);assertNotEquals(before,VettingInputBudget.providerConfigurationSha256(p));
    }
    @Test void unknownConfigurationCommandMismatchAndOfflinePlanNeverStartProductionObserver()throws Exception {VettingTokenBudgetCliObserver observer=setup("ok");input.setProviderConfigurationSha256(null);assertEquals("budget_unknown",VettingInputBudget.check(input,observer).getStatus());assertFalse(Files.exists(recorded));observer=setup("ok");recipe.put("contextEvidenceScope","offline_plan");seal();assertEquals("budget_unknown",VettingInputBudget.check(input,observer).getStatus());assertFalse(Files.exists(recorded));observer=setup("ok");props.getVetting().setTokenBudgetObserverCommand(Collections.singletonList("unbound-command"));assertEquals("budget_unknown",VettingInputBudget.check(input,observer).getStatus());assertFalse(Files.exists(recorded));}
    @ParameterizedTest @ValueSource(strings={"wrongIdentity","trailingJson","extraField","error","tooMuch","duplicateCount","duplicateIdentity"})
    void failedOrMalformedCounterCannotPermitDispatch(String mode)throws Exception {VettingTokenBudgetCliObserver observer=setup(mode);props.getVetting().setTokenBudgetObserverMaxOutputBytes(1024);VettingInputBudget.Result result=VettingInputBudget.check(input,observer);assertEquals("budget_unknown",result.getStatus());assertFalse(result.isExtraDispatchPermitted());assertFalse(result.getReason().contains("cells"));}
    @Test void timedOutCounterIsStoppedAndRemainsUnknown()throws Exception {VettingTokenBudgetCliObserver observer=setup("stall");props.getVetting().setTokenBudgetObserverTimeoutMs(100);long start=System.nanoTime();assertEquals("budget_unknown",VettingInputBudget.check(input,observer).getStatus());assertTrue(System.nanoTime()-start<8_000_000_000L);}
    @Test void duplicateNestedRecipeIdentityCannotBecomeASealedValidArtifact()throws Exception {
        VettingTokenBudgetCliObserver observer=setup("ok");String raw=JsonUtils.write(recipe),actual=sha(fixture);raw=raw.replace("\"sha256\":\""+actual+"\"","\"sha256\":\""+String.join("",Collections.nCopies(64,"0"))+"\",\"sha256\":\""+actual+"\"");Files.write(recipePath,raw.getBytes(StandardCharsets.UTF_8));props.getVetting().setTokenBudgetObserverRecipeSha256(sha(recipePath));assertEquals("budget_unknown",VettingInputBudget.check(input,observer).getStatus());assertFalse(Files.exists(recorded));
    }
    public static class Counter {
        /** Pure process fixture: no model, HTTP, application DB, GPU or actual tokenizer. */
        public static void main(String[] args)throws Exception {
            byte[] bytes=readInput();Files.write(Paths.get(args[1]),bytes);List<String> environmentNames=new ArrayList<>(System.getenv().keySet());Files.write(Paths.get(args[1]+".environment-names.json"),JsonUtils.write(environmentNames).getBytes(StandardCharsets.UTF_8));for(String name:environmentNames)if(!"SYSTEMROOT".equalsIgnoreCase(name))System.exit(91);
            if("stall".equals(args[0])){Thread.sleep(10000);return;}if("error".equals(args[0])){System.err.print("SYNTHETIC diagnostic");System.exit(12);}if("tooMuch".equals(args[0])){System.out.print(String.join("",Collections.nCopies(5000,"x")));return;}
            JsonNodeInput in=new JsonNodeInput(JsonUtils.parse(new String(bytes,StandardCharsets.UTF_8)));Map<String,Object> o=new LinkedHashMap<>();o.put("model",in.model);o.put("serializedInputSha256","wrongIdentity".equals(args[0])?VettingCorpus.hash("different input"):in.sha);o.put("tokenizerIdentitySha256",TOKENIZER);o.put("chatTemplateIdentitySha256",TEMPLATE);o.put("effectiveContextIdentitySha256",CONTEXT);o.put("inputTokens",200);o.put("effectiveContextTokens",10000);o.put("outputReserveTokens",100);o.put("completeChatTemplateAndSchemaObserved",true);o.put("scope","complete_serialized_messages_with_gateway_schema_envelope");if("extraField".equals(args[0]))o.put("unbound","extra");String result=JsonUtils.write(o);if("duplicateCount".equals(args[0]))result=result.replace("\"inputTokens\":200","\"inputTokens\":20000,\"inputTokens\":200");if("duplicateIdentity".equals(args[0]))result=result.replace("\"model\":", "\"model\":\"foreign-model\",\"model\":");System.out.print(result);if("trailingJson".equals(args[0]))System.out.print("{}");
        }
        private static byte[] readInput()throws Exception {java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=System.in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
        private static class JsonNodeInput {String model,sha;JsonNodeInput(com.fasterxml.jackson.databind.JsonNode n){model=n.path("model").asText();sha=n.path("serializedInputSha256").asText();}}
    }
}
