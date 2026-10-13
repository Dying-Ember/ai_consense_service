package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** AC12: kills only Process objects created here, restarts their worker and reads public HTTP results. */
class VettingProcessRecoveryTest {
    @org.junit.jupiter.api.Test
    void crashAfterSecondReservationCannotResetAllowanceOrEraseFirstInvalidRaw()throws Exception {
        Path root=Paths.get("target","process-recovery",UUID.randomUUID().toString(),"SCHEMA_RETRY_RESERVED").toAbsolutePath();Files.createDirectories(root);
        Process first=launch(root,"SCHEMA_RETRY_RESERVED");JsonNode ready;
        try{awaitFile(first,root.resolve("SCHEMA_RETRY_RESERVED-ready.json"));awaitFile(first,root.resolve("blocked.txt"));ready=JsonUtils.parse(Files.readString(root.resolve("SCHEMA_RETRY_RESERVED-ready.json")));}
        finally{stop(first);}
        String url="jdbc:h2:file:"+root.resolve("database").toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        try(Connection db=openStoppedDatabase(url)) {
            try(Statement sql=db.createStatement();ResultSet rows=sql.executeQuery("SELECT result_json FROM vetting_packet_checkpoint")) {
                assertTrue(rows.next());JsonNode attempts=JsonUtils.parse(rows.getString(1)).path("packet").path("attempts");
                assertEquals(2,attempts.size());assertEquals("[]}",attempts.get(0).path("rawResponse").asText());assertEquals(VettingCorpus.hash("[]}"),attempts.get(0).path("rawResponseSha256").asText());assertEquals("INVALID_JSON",attempts.get(0).path("validationReason").asText());assertEquals("started",attempts.get(1).path("status").asText());
            }
            try(PreparedStatement expire=db.prepareStatement("UPDATE vetting_run SET lease_until=? WHERE id=?")){expire.setTimestamp(1,Timestamp.from(java.time.Instant.now().minusSeconds(1)));expire.setString(2,ready.path("runId").asText());assertEquals(1,expire.executeUpdate());}
        }
        int callsBefore=Files.readAllLines(root.resolve("calls.log")).size();assertEquals(2,callsBefore);
        Process resumed=launch(root,"RESUME");
        try {
            awaitFile(resumed,root.resolve("RESUME-ready.json"));JsonNode restarted=JsonUtils.parse(Files.readString(root.resolve("RESUME-ready.json")));
            JsonNode run=awaitRun(resumed,restarted.path("baseUrl").asText()+"/api/vetting/"+restarted.path("projectId").asText()+"/runs/"+restarted.path("runId").asText());
            assertEquals("FAILED",run.path("status").asText());assertEquals("NEEDS_RERUN",run.path("recoveryStatus").asText());
            // The next planned packet may still execute; the exhausted packet must not call again.
            assertEquals(1,Files.readAllLines(root.resolve("calls.log")).size()-callsBefore);
            JsonNode attempts=run.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).path("attempts");assertEquals(2,attempts.size());assertEquals("[]}",attempts.get(0).path("rawResponse").asText());assertEquals("started",attempts.get(1).path("status").asText());
        }finally{stop(resumed);}
    }
    @ParameterizedTest @ValueSource(strings={"QUEUED","BEFORE_CALL","BEFORE_SAVE","AFTER_PACKET","BEFORE_PUBLISH"})
    void realRestartAtCallDecodeCheckpointAndPublicationBoundaries(String boundary)throws Exception {
            Path root=Paths.get("target","process-recovery",UUID.randomUUID().toString(),boundary).toAbsolutePath();Files.createDirectories(root);
            Process first=launch(root,boundary);JsonNode ready;
            try{awaitFile(first,root.resolve(boundary+"-ready.json"));awaitFile(first,root.resolve("blocked.txt"));ready=JsonUtils.parse(Files.readString(root.resolve(boundary+"-ready.json")));}
            finally{stop(first);}
            String url="jdbc:h2:file:"+root.resolve("database").toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
            int saved;
            try(Connection database=openStoppedDatabase(url)){try(Statement sql=database.createStatement();ResultSet rows=sql.executeQuery("SELECT COUNT(*) FROM vetting_packet_checkpoint WHERE status='SAVED'")){rows.next();saved=rows.getInt(1);}assertEquals("BEFORE_PUBLISH".equals(boundary)?2:"AFTER_PACKET".equals(boundary)?1:0,saved);
                try(PreparedStatement expire=database.prepareStatement("UPDATE vetting_run SET lease_until=? WHERE id=?")){expire.setTimestamp(1,Timestamp.from(java.time.Instant.now().minusSeconds(1)));expire.setString(2,ready.path("runId").asText());assertEquals(1,expire.executeUpdate());}}
            int callsBefore=Files.readAllLines(root.resolve("calls.log")).size();Process resumed=launch(root,"RESUME");
            try{awaitFile(resumed,root.resolve("RESUME-ready.json"));JsonNode restarted=JsonUtils.parse(Files.readString(root.resolve("RESUME-ready.json")));String base=restarted.path("baseUrl").asText(),project=restarted.path("projectId").asText(),run=restarted.path("runId").asText();assertEquals(ready.path("runId").asText(),run);
                JsonNode completed=awaitRun(resumed,base+"/api/vetting/"+project+"/runs/"+run);assertEquals("COMPLETED",completed.path("status").asText());assertEquals(2,completed.path("reliablePackets").size());assertEquals(2,completed.path("result").path("total").asInt());assertEquals(2-saved,Files.readAllLines(root.resolve("calls.log")).size()-callsBefore,"Saved packets must not call the gateway again at "+boundary);
                JsonNode report=JsonUtils.parse(VettingRecoveryProcessFixture.request(base+"/api/vetting/"+project+"/report.json","GET"));assertEquals(run,report.path("job").path("id").asText());assertEquals(2,report.path("findings").size());assertTrue(report.path("reportSnapshotId").asText().matches("[a-f0-9]{64}"));
                JsonNode progress=JsonUtils.parse(VettingRecoveryProcessFixture.request(base+"/api/vetting/"+project+"/runs/"+run+"/progress","GET")).path("data");assertFalse(progress.has("coverage"));assertFalse(progress.has("reliablePackets"));assertEquals("COMPLETED",progress.path("status").asText());
            }finally{stop(resumed);}
    }
    private static Connection openStoppedDatabase(String url)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(true){try{return DriverManager.getConnection(url,"sa","");}catch(SQLException delayedRelease){if(delayedRelease.getErrorCode()!=90020||System.nanoTime()>=deadline)throw delayedRelease;Thread.sleep(50);}}}
    private static Process launch(Path root,String mode)throws Exception{String executable=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";String java=Paths.get(System.getProperty("java.home"),"bin",executable).toString();String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));return new ProcessBuilder(java,"-cp",classpath,VettingRecoveryProcessFixture.class.getName(),root.toString(),mode).redirectErrorStream(true).redirectOutput(root.resolve(mode+".log").toFile()).start();}
    private static void awaitFile(Process process,Path file)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(!Files.isRegularFile(file)&&process.isAlive()&&System.nanoTime()<deadline)Thread.sleep(30);assertTrue(Files.isRegularFile(file),"Child fixture did not create "+file+"; logs="+file.getParent());}
    private static JsonNode awaitRun(Process process,String url)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);JsonNode run=null;while(process.isAlive()&&System.nanoTime()<deadline){run=JsonUtils.parse(VettingRecoveryProcessFixture.request(url,"GET")).path("data");if("COMPLETED".equals(run.path("status").asText())||"FAILED".equals(run.path("status").asText()))return run;Thread.sleep(30);}fail("Restart did not finish: "+run);return null;}
    private static void stop(Process process)throws Exception{if(process.isAlive())process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS),"Owned child fixture did not exit");}
}
