package com.consense.service.vetting;
import com.consense.ai.AiGateway;import com.consense.config.ConsenseProperties;import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;import com.consense.domain.*;import com.consense.repository.*;import com.consense.web.dto.VettingDtos.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;import org.springframework.test.context.*;import org.springframework.test.annotation.DirtiesContext;import java.nio.file.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
/** Real async service/repositories/H2 and real Java/Python loopback HTTP; no review model/parser/neural. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory","consense.vetting.semantic-topics=1","consense.vetting.project-reference-comparisons=0"})
@ActiveProfiles({"h2","vetting-local"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class VettingCurrentPythonWireWorkflowTest {
 static final String DB=UUID.randomUUID().toString().replace("-","");static WireFixtureTestSupport wire;
 static synchronized WireFixtureTestSupport wire(){if(wire==null)try{wire=new WireFixtureTestSupport("workflow");}catch(Exception e){throw new RuntimeException(e);}return wire;}
 @DynamicPropertySource static void isolated(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",()->"jdbc:h2:mem:strict_wire_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
  r.add("consense.storage-root",()->Paths.get("target","wire-uploads",DB).toAbsolutePath().toString());r.add("consense.vetting.retrieval-url",()->wire().url+"/normal");
 }
 @Autowired VettingService service;@Autowired ConsenseProperties props;@Autowired ProjectRepository projects;@Autowired SourceDocumentRepository documents;@Autowired VettingFindingRepository findings;@MockBean AiGateway ai;
 @BeforeEach void offline(){reset(ai);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("uninvoked-wire-test");props.getVetting().setRetrievalUrl(wire().url+"/normal");props.getVetting().setProbeDirectory(wire().output.resolve("review-probes").toString());assertTrue(props.getVetting().isRequireHybridRetrieval());}
 @AfterAll static void stop()throws Exception{if(wire!=null)wire.close();}
 String project(String scenario){
  String id="wire-workflow-"+UUID.randomUUID();Project p=new Project();p.setId(id);p.setNameEn("Synthetic lantern "+scenario);p.setNameZhHans("合成蓝灯");p.setNameZhHant("合成藍燈");p.setContractNo("SYNTHETIC");projects.saveAndFlush(p);
  String text="Blue lantern laboratory observation. 蓝灯记录 😀";DocumentBlock b=new DocumentBlock();b.setId("synthetic:block:0");b.setKind("paragraph");b.setLocation("body/0/paragraph");b.setText(text);b.setOriginalText(text);b.setSource("docx");
  SourceDocument d=new SourceDocument();d.setProjectId(id);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("OTHER");d.setReviewRole("tender");d.setFileName("synthetic-lantern.docx");d.setTextContent(text);d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(b)));d.setParseStatus("PARSED");d.setPageCount(0);d.setOcrUsed(false);d.setParseCoverageJson("{\"totalPages\":0,\"parsedPages\":0,\"ocrPages\":0,\"complete\":true,\"needsReviewPages\":[]}");documents.saveAndFlush(d);return id;
 }
 VettingJobVO completed(String id)throws Exception{VettingJobVO job=service.startRun(id,"en");for(int n=0;n<1000;n++){job=service.getRun(id,job.getId());if(Arrays.asList("COMPLETED","FAILED").contains(job.getStatus()))return job;Thread.sleep(10);}throw new AssertionError("Isolated async run did not terminate");}
 void noModel(){verify(ai,never()).completeStructuredJsonList(anyString(),anyString(),any(),any(),anyList());verify(ai,never()).completeStructuredJsonList(anyString(),anyString(),any(),any(),anyList(),anyMap());}
 @Test void validEmptyCurrentWirePersistsCompletedButNoSemanticCoverageOrModelCall()throws Exception{
  String id=project("empty");VettingJobVO job=completed(id);assertEquals("COMPLETED",job.getStatus());assertTrue(findings.findByProjectIdOrderByCodeAsc(id).isEmpty());
  assertEquals(1,job.getCoverage().getSemanticTopics().size());assertEquals("not_submitted",job.getCoverage().getSemanticTopics().get(0).getStatus());assertTrue(job.getCoverage().getSemanticTopics().get(0).getSubmittedChunkIds().isEmpty());noModel();
  WireFixtureTestSupport.save(wire.output.resolve("workflow-empty-result.json"),VettingReviewProbe.map("job",job,"effectiveStrict",props.getVetting().isRequireHybridRetrieval(),"reviewModelCalls",0,"fixtureIndexOnly",true,"freshH2",true));
 }
 @Test void healthIndexQueryAndPayloadFailuresPersistFailedWithoutFindingsOrModelCalls()throws Exception{
  for(String route:Arrays.asList("wrong_version","bad_recipe","bad_index_contract","retrieve_fail","wrong_signature","changed_payload")){
   reset(ai);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("uninvoked-wire-test");props.getVetting().setRetrievalUrl(wire.url+"/"+route);String id=project(route);VettingJobVO job=completed(id);
   assertEquals("FAILED",job.getStatus(),route);assertTrue(job.getError().contains("Required hybrid retrieval"),job.getError());assertTrue(findings.findByProjectIdOrderByCodeAsc(id).isEmpty());noModel();
   WireFixtureTestSupport.save(wire.output.resolve("workflow-"+route+"-result.json"),VettingReviewProbe.map("job",job,"effectiveStrict",true,"reviewModelCalls",0,"fixtureIndexOnly",true,"freshH2",true));
  }
 }
}
