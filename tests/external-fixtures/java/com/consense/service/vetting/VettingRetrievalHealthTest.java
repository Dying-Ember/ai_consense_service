package com.consense.service.vetting;
import com.consense.common.JsonUtils;import com.consense.config.ConsenseProperties;import com.consense.ai.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;import org.springframework.boot.env.YamlPropertySourceLoader;import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.context.properties.bind.*;import org.springframework.core.env.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
class VettingRetrievalHealthTest {
 @Test void actualLocalProfileBindsStrictCurrentContractAndWindowLimits()throws Exception{
  StandardEnvironment env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
  for(PropertySource<?>s:new YamlPropertySourceLoader().load("local",new ClassPathResource("application-vetting-local.yml")))env.getPropertySources().addLast(s);
  ConsenseProperties p=new ConsenseProperties();Binder.get(env).bind("consense",Bindable.ofInstance(p));
  assertTrue(p.getVetting().isRequireHybridRetrieval());assertEquals(3,p.getVetting().getExpectedRetrievalSignatureVersion());
  assertEquals(512,p.getVetting().getExpectedEmbeddingWindowMaxTokens());assertEquals(512,p.getVetting().getExpectedRerankWindowMaxTokens());assertEquals(64,p.getVetting().getExpectedWindowOverlapTokens());
  VettingRetrievalHealth.validate(VettingStrictHybridTest.currentHealth(),p.getVetting());assertFalse(new ConsenseProperties().getVetting().isRequireHybridRetrieval());
 }
 @Test void capturedCurrentRuntimeHealthIsAcceptedAndDynamicLoadedMemoryFlagsDoNotChangeRecipe(){
  JsonNode original=VettingStrictHybridTest.currentHealth();VettingRetrievalHealth.Identity a=VettingRetrievalHealth.validate(original,new ConsenseProperties().getVetting());
  ObjectNode changed=original.deepCopy();changed.put("cudaAllocatedMiB",8000);((ObjectNode)changed.path("loaded")).put("embedding",true);
  assertEquals(a.fingerprint,VettingRetrievalHealth.validate(changed,new ConsenseProperties().getVetting()).fingerprint);
 }
 @Test void versionsCapsOverlapModelAndAlgorithmCorruptionsCannotPass(){
  for(String bad:Arrays.asList("version","versionOverflow","contract","cap","capOverflow","overlap","aggregation","model","modelRevision","rerankRuntime","dtype","algorithm","roleFilter","rrf","bm25")){
   ObjectNode h=VettingStrictHybridTest.currentHealth().deepCopy();ObjectNode w=(ObjectNode)h.path("retrieval").path("sourceWindows");
   if(bad.equals("version"))h.put("indexSignatureVersion",2);
   if(bad.equals("versionOverflow"))h.put("indexSignatureVersion",new java.math.BigInteger("4294967299"));
   if(bad.equals("contract"))w.put("contract","legacy-parent-vector");
   if(bad.equals("cap"))((ObjectNode)w.path("embeddingWindows")).put("max_tokens",513);
   if(bad.equals("capOverflow"))((ObjectNode)w.path("embeddingWindows")).put("max_tokens",new java.math.BigInteger("4294967808"));
   if(bad.equals("overlap"))((ObjectNode)w.path("rerankWindows")).put("overlap_tokens",512);
   if(bad.equals("aggregation"))w.put("denseAggregation","sum");
   if(bad.equals("model"))((ObjectNode)h.path("runtime").path("embedding")).put("name","different");
   if(bad.equals("modelRevision"))((ObjectNode)h.path("models").path("embedding")).remove("revision");
   if(bad.equals("rerankRuntime"))((ObjectNode)w.path("rerankRuntime")).put("batchSize",99);
   if(bad.equals("dtype"))((ObjectNode)h.path("runtime").path("embedding")).put("dtype","float16");
   if(bad.equals("algorithm"))((ObjectNode)w.path("algorithms")).put("server.py","unbound");
   if(bad.equals("roleFilter"))((ObjectNode)h.path("retrieval")).put("roleFilter",false);
   if(bad.equals("rrf"))((ObjectNode)h.path("retrieval")).put("rrfConstant",0);
   if(bad.equals("bm25"))((ObjectNode)h.path("retrieval").path("bm25")).put("b",2);
   assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,new ConsenseProperties().getVetting()),bad);
  }
 }
 @Test void externalRecipeSealMustMatchObservedPublicIdentity(){
  ConsenseProperties p=new ConsenseProperties();JsonNode h=VettingStrictHybridTest.currentHealth();String fingerprint=VettingRetrievalHealth.validate(h,p.getVetting()).fingerprint;
  p.getVetting().setExpectedRetrievalRecipeFingerprint(fingerprint);assertEquals(fingerprint,VettingRetrievalHealth.validate(h,p.getVetting()).fingerprint);
  p.getVetting().setExpectedRetrievalRecipeFingerprint(String.join("",Collections.nCopies(64,"0")));assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,p.getVetting()));
 }
 @Test void strictHealthFailurePreventsAnyIndexRequest(){
  HttpSupport http=mock(HttpSupport.class);when(http.get(endsWith("/health"),anyLong())).thenReturn("{}");ConsenseProperties p=VettingStrictHybridTest.strict();
  assertThrows(IllegalStateException.class,()->new VettingRetrievalClient(http,p).index("p",Collections.singletonList(VettingStrictHybridTest.source("a","tender","source"))));verify(http,never()).postJson(anyString(),anyString(),anyLong());
 }
 @Test void recipeChangeAfterHandshakeInvalidatesReceiptBeforeQueryAndCannotResumeWithoutReindex(){
  HttpSupport http=mock(HttpSupport.class);ConsenseProperties p=VettingStrictHybridTest.strict();VettingStrictHybridTest.indexResponse(http,VettingStrictHybridTest.receipt(1));
  ObjectNode changed=VettingStrictHybridTest.currentHealth().deepCopy();((ObjectNode)changed.path("retrieval")).put("rrfConstant",61);
  when(http.get(endsWith("/health"),anyLong())).thenReturn(JsonUtils.write(VettingStrictHybridTest.currentHealth()),JsonUtils.write(changed),JsonUtils.write(VettingStrictHybridTest.currentHealth()));
  VettingCorpus.Chunk c=VettingStrictHybridTest.source("a","tender","source");VettingRetrievalClient client=new VettingRetrievalClient(http,p);client.index("p",Collections.singletonList(c));
  assertThrows(IllegalStateException.class,()->client.retrieve("p","source","tender",Collections.singletonList(c)));
  assertThrows(IllegalStateException.class,()->client.retrieve("p","source","tender",Collections.singletonList(c)));verify(http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
 }
}
