package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.repository.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Test-only child process: its own H2 file/server/worker; every model/retrieval call is a local fixture. */
public final class VettingRecoveryProcessFixture {
    private static Path root;
    private static String mode;
    private static final AtomicReference<List<VettingCorpus.Chunk>> corpus=new AtomicReference<>();
    private static final AtomicInteger calls=new AtomicInteger();
    private static final AtomicBoolean running=new AtomicBoolean();
    public static void main(String[] args)throws Exception {
        root=Paths.get(args[0]).toAbsolutePath();mode=args[1];Files.createDirectories(root);
        Files.write(root.resolve("calls.log"),new byte[0],StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        String url="jdbc:h2:file:"+root.resolve("database").toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        try(ConfigurableApplicationContext context=new SpringApplicationBuilder(Application.class,Fixture.class).run(
                "--spring.profiles.active=h2","--server.address=127.0.0.1","--server.port=0","--spring.datasource.url="+url,
                "--spring.jpa.hibernate.ddl-auto=update","--spring.flyway.enabled=false","--consense.storage-root="+root.resolve("uploads"),
                "--consense.llm.enabled=false","--consense.minimax-cn.enabled=false","--consense.ocr.enabled=false","--consense.vector.provider=memory",
                "--consense.llm.num-ctx=524288","--consense.llm.structured-thinking=false",
                "--consense.fixed-baseline.enabled=false","--consense.oer.enabled=false","--consense.vetting.semantic-topics=2","--consense.vetting.project-reference-comparisons=0",
                "--logging.level.root=WARN","--logging.level.com.consense=WARN")) {
            String project="process-recovery-fixture";ProjectRepository projects=context.getBean(ProjectRepository.class);
            if(!projects.existsById(project)){Project p=new Project();p.setId(project);p.setNameEn("Process recovery fixture");p.setContractNo("TEST");projects.saveAndFlush(p);
                SourceDocument d=new SourceDocument();d.setProjectId(project);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("SCC");d.setFileName("SCC.txt");d.setParseStatus("PARSED");d.setTextContent("SCC 9.1 The contractor shall protect the works and maintain insurance throughout the contract.");d.setOcrUsed(false);context.getBean(SourceDocumentRepository.class).saveAndFlush(d);}
            int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();String base="http://127.0.0.1:"+port;
            if("QUEUED".equals(mode)){
                // Test-only saturation of this fixture's actual worker; the run still enters through HTTP.
                java.lang.reflect.Field executor=VettingService.class.getDeclaredField("worker");executor.setAccessible(true);
                ((java.util.concurrent.ThreadPoolExecutor)executor.get(org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(VettingService.class)))).execute(()->block("owned fixture worker occupied; run queued"));
            }
            running.set(true);
            if(!"RESUME".equals(mode))request(base+"/api/vetting/"+project+"/runs","POST");
            com.consense.domain.VettingRun run=context.getBean(VettingRunRepository.class).findFirstByProjectIdOrderByStartedAtDesc(project).orElseThrow(IllegalStateException::new);
            Map<String,Object> ready=new LinkedHashMap<>();ready.put("baseUrl",base);ready.put("projectId",project);ready.put("runId",run.getId());Files.write(root.resolve(mode+"-ready.json"),JsonUtils.write(ready).getBytes(StandardCharsets.UTF_8));
            new CountDownLatch(1).await();
        }
    }
    @org.springframework.boot.SpringBootConfiguration @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.boot.autoconfigure.domain.EntityScan("com.consense.domain")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories("com.consense.repository")
    @org.springframework.scheduling.annotation.EnableScheduling
    @org.springframework.scheduling.annotation.EnableAsync
    @org.springframework.boot.context.properties.EnableConfigurationProperties(com.consense.config.ConsenseProperties.class)
    @ComponentScan(basePackages="com.consense",excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern=".*(?:Test|Fixture|ConSenseApplication).*"))
    public static class Application { }
    private static void block(String boundary){try{Files.write(root.resolve("blocked.txt"),boundary.getBytes(StandardCharsets.UTF_8));new CountDownLatch(1).await();}catch(Exception e){throw new IllegalStateException(e);}}
    static String request(String url,String method)throws Exception{java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();connection.setRequestMethod(method);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);try(java.io.InputStream in=connection.getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}finally{connection.disconnect();}}
    @TestConfiguration
    public static class Fixture {
        @Bean @Primary VettingInputBudget.Observer fixtureTokens(){return VettingTokenFixture::observe;}
        @Bean org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory fixtureServer(){org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory server=new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory();server.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");return server;}
        @Bean @Primary AiGateway fixtureGateway(){AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("fixture-only");
            when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{int ordinal=calls.incrementAndGet();String prompt=call.getArgument(1);Files.write(root.resolve("calls.log"),(prompt.substring(0,prompt.indexOf('\n'))+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                if("SCHEMA_RETRY_RESERVED".equals(mode)) {
                    if(ordinal==1){((List<String>)call.getArgument(4)).add("[]}");throw new com.consense.ai.StructuredOutputValidationException(com.consense.ai.StructuredOutputValidationException.Reason.INVALID_JSON);}
                    if(ordinal==2)block("first invalid result saved; second attempt durably consumed before return");
                }
                if("BEFORE_CALL".equals(mode)&&ordinal==1)block("before first result");if("AFTER_PACKET".equals(mode)&&ordinal==2)block("first packet saved; second call uncertain");
                VettingCorpus.Chunk source=corpus.get().stream().filter(c->"tender".equals(c.getRole())).findFirst().orElseThrow(IllegalStateException::new);
                VettingSemanticReview.Record record="BEFORE_SAVE".equals(mode)&&ordinal==1?new VettingSemanticReview.Record(){@Override public String getSuggestion(){block("result decoded; before verified checkpoint save");return "fixture suggestion";}}:new VettingSemanticReview.Record();
                record.setAssessment("issue");record.setType("risk");record.setSeverity("medium");record.setTitle("Fixture assessment");record.setComment("Fixture record; no live model quality claim");VettingSemanticReview.Quote quote=new VettingSemanticReview.Quote();quote.setChunkId(source.getId());quote.setSide("source");quote.setQuote(source.getContent().trim());record.setEvidence(Collections.singletonList(quote));return Collections.singletonList(record);});return ai;}
        @Bean @Primary VettingRetrievalClient fixtureRetrieval(){VettingRetrievalClient client=mock(VettingRetrievalClient.class);doAnswer(call->{corpus.set(call.getArgument(1));return null;}).when(client).index(anyString(),anyList());
            when(client.retrieve(anyString(),anyString(),anyString(),anyList())).thenAnswer(call->((List<VettingCorpus.Chunk>)call.getArgument(3)).stream().filter(c->call.getArgument(2).equals(c.getRole())).collect(java.util.stream.Collectors.toList()));return client;}
        @Bean @Primary VettingFindingRepository fixtureFindings(@Qualifier("vettingFindingRepository") VettingFindingRepository delegate){VettingFindingRepository repository=mock(VettingFindingRepository.class,org.mockito.AdditionalAnswers.delegatesTo(delegate));
            doAnswer(call->{if("BEFORE_PUBLISH".equals(mode)&&running.get())block("verified packets saved; before final findings transaction commit");return delegate.saveAll(call.getArgument(0));}).when(repository).saveAll(any());return repository;}
    }
}
