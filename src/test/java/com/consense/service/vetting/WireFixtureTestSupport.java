package com.consense.service.vetting;
import com.consense.ai.HttpSupport;import com.consense.common.JsonUtils;import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.io.*;import java.util.*;import java.util.concurrent.TimeUnit;
/** Real loopback Python HTTP process; its index and nonempty ranking are explicit fixtures. */
final class WireFixtureTestSupport implements AutoCloseable {
 final Path output;final Process process;final String url;
 WireFixtureTestSupport(String scope)throws Exception{
  Path stage=Paths.get(System.getProperty("user.dir")).toAbsolutePath();output=stage.resolve("target/wire-observations/"+scope+"-"+UUID.randomUUID());Files.createDirectories(output.getParent());
  Path fixture=configuredFixture(stage);
  ProcessBuilder builder=new ProcessBuilder(configuredPython(stage),fixture.toString(),output.toString());
  builder.redirectError(output.resolveSibling(output.getFileName()+"-stderr.txt").toFile());process=builder.start();
  BufferedReader stdout=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));String line=stdout.readLine();
  if(line==null)throw new AssertionError("Python fixture failed to start");url=JsonUtils.parse(line).path("url").asText();if(!url.matches("http://127\\.0\\.0\\.1:[0-9]+"))throw new AssertionError("Loopback-only fixture required");
 }
 static Path configuredFixture(Path stage){
  String configured=System.getProperty("consense.test.wireFixture");
  if(configured!=null&&configured.trim().isEmpty())throw new IllegalArgumentException("consense.test.wireFixture must not be blank");
  Path fixture=(configured==null?stage.resolve("tools/vetting_eval/wire_fixture_server.py"):Paths.get(configured)).toAbsolutePath().normalize();
  if(!Files.isRegularFile(fixture))throw new IllegalStateException("Missing loopback fixture: "+fixture+"; configure -Dconsense.test.wireFixture when using an isolated layout");
  return fixture;
 }
 static String configuredPython(Path stage){
  String configured=System.getProperty("consense.test.python");
  if(configured==null)configured=System.getenv("CONSENSE_TEST_PYTHON");
  if(configured!=null){if(configured.trim().isEmpty())throw new IllegalArgumentException("The test Python interpreter must not be blank");return configured;}
  for(String relative:Arrays.asList(".venv/Scripts/python.exe",".venv/bin/python")){Path candidate=stage.resolve(relative);if(Files.isRegularFile(candidate))return candidate.toAbsolutePath().toString();}
  return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"python":"python3";
 }
 static void save(Path path,Object value)throws Exception {Files.write(path,(JsonUtils.write(value)+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
 public void close()throws Exception{
  new HttpSupport().get(url+"/shutdown",5000);if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Python fixture did not exit");}
  if(process.exitValue()!=0)throw new AssertionError("Python fixture failed: "+process.exitValue());
  JsonNode result=JsonUtils.parse(new String(Files.readAllBytes(output.resolve("server_final.json")),StandardCharsets.UTF_8));
  for(String field:Arrays.asList("actualIndexCalls","actualNeuralForward","actualTokenizerLoads","actualVectorDb","actualOCR"))if(result.path("counts").path(field).asLong()!=0)throw new AssertionError("Unexpected real operation: "+field);
 }
}
