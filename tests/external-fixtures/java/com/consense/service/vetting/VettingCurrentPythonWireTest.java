package com.consense.service.vetting;
import com.consense.ai.HttpSupport;import com.consense.config.ConsenseProperties;import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;import org.junit.jupiter.api.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class VettingCurrentPythonWireTest {
 static WireFixtureTestSupport wire;static int ordinal;
 @BeforeAll static void start()throws Exception{wire=new WireFixtureTestSupport("client");}
 @AfterAll static void stop()throws Exception{wire.close();}
 static ConsenseProperties props(String route){ConsenseProperties p=VettingStrictHybridTest.strict();p.getVetting().setRetrievalUrl(wire.url+"/"+route);return p;}
 static List<Chunk> corpus(){return Collections.singletonList(VettingStrictHybridTest.source("synthetic-lantern","tender","Blue lantern laboratory observation. 蓝灯记录 😀"));}
 static void record(String scenario,Object result)throws Exception{WireFixtureTestSupport.save(wire.output.resolve("java-"+(++ordinal)+"-"+scenario+".json"),VettingReviewProbe.map("scenario",scenario,"result",result,"actualNeuralOrIndex",0,"transportFixtureOnly",true));}
 @Test void currentRuntimeEmptyScopeRemainsValidEmptyWithoutChangingModeOrRepair()throws Exception{
  List<Chunk> c=corpus();ConsenseProperties p=props("normal");VettingRetrievalClient client=new VettingRetrievalClient(new HttpSupport(),p);client.index("wire-empty",c);
  List<Chunk> result=client.retrieve("wire-empty","generic laboratory observations","standard",c);assertTrue(result.isEmpty());record("valid-empty",VettingReviewProbe.map("returned",0,"failure",false,"strict",true));
 }
 @Test void actualPythonDtoWholeParentPayloadRoundTripsExact()throws Exception{
  List<Chunk> c=corpus();c.get(0).setPageNo("P12");ConsenseProperties p=props("hit");VettingRetrievalClient client=new VettingRetrievalClient(new HttpSupport(),p);client.index("wire-hit",c);
  List<Chunk> result=client.retrieve("wire-hit","generic laboratory observations","tender",c);assertEquals(c,result);assertSame(c.get(0),result.get(0));record("original-parent",JsonUtils.parse(JsonUtils.write(result)));
 }
 @Test void wrongVersionRecipeContractIndexAndResponseIdentitiesAreExplicitFailures()throws Exception{
  for(String route:Arrays.asList("wrong_version","bad_recipe","wrong_contract","bad_index_contract","index_fail","retrieve_fail","bad_mode","legacy_mode","wrong_signature","changed_payload")){
   List<Chunk>c=corpus();VettingRetrievalClient client=new VettingRetrievalClient(new HttpSupport(),props(route));String project="wire-"+route;
   RuntimeException failure=assertThrows(RuntimeException.class,()->{client.index(project,c);client.retrieve(project,"generic laboratory observations","tender",c);},route);
   record(route,VettingReviewProbe.map("failureClass",failure.getClass().getName(),"failure",failure.getMessage(),"fallback",false));
  }
 }
 @Test void runtimeRecipeChangeRequiresReindexBeforeQuery()throws Exception{
  List<Chunk>c=corpus();ConsenseProperties p=props("normal");VettingRetrievalClient client=new VettingRetrievalClient(new HttpSupport(),p);client.index("wire-changed-recipe",c);p.getVetting().setRetrievalUrl(wire.url+"/changed_recipe");
  IllegalStateException error=assertThrows(IllegalStateException.class,()->client.retrieve("wire-changed-recipe","generic observations","tender",c));assertTrue(error.getMessage().contains("recipe changed"));
  p.getVetting().setRetrievalUrl(wire.url+"/normal");assertThrows(IllegalStateException.class,()->client.retrieve("wire-changed-recipe","generic observations","tender",c));record("changed-recipe-invalidates",error.getMessage());
 }
}
