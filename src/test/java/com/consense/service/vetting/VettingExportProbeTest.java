package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParseProbe;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** File-only/in-memory report observations. No actual Word pagination, OCR, model or HTTP calls. */
class VettingExportProbeTest {
    @TempDir Path root;

    @Test
    void disabledObserverDoesNotRequireRunIdentityOrCreateArtifactsOrAlterBytes() throws Exception {
        ConsenseProperties props=new ConsenseProperties();VettingExportProbe probe=VettingExportProbe.start(props,"project","json","en");
        assertFalse(probe.enabled());byte[] original="{\"value\":\"Original text\"}".getBytes(StandardCharsets.UTF_8),before=original.clone();
        probe.bindRun(null);try(VettingExportProbe.Timer timer=probe.measure("fixture","Original operation")){probe.returned(original);}
        probe.finish("returned_normally");assertArrayEquals(before,original);
        try(java.util.stream.Stream<Path> files=Files.list(root)){assertEquals(0,files.count());}
    }

    @Test
    void observedWritersKeepOriginalWordStructurePdfTextAndExactReturnedArtifacts() throws Exception {
        ConsenseProperties props=props();VettingReportWriter writer=new VettingReportWriter();Project project=project();VettingJobVO job=job();
        for(String format:Arrays.asList("docx","pdf")) {
            byte[] baseline="docx".equals(format)?writer.docx(project,Collections.emptyList(),job,"en"):writer.pdf(project,Collections.emptyList(),job,"en");
            VettingExportProbe probe=VettingExportProbe.start(props,project.getId(),format,"en");probe.bindRun(job.getId());
            byte[] observed="docx".equals(format)?writer.docx(project,Collections.emptyList(),job,"en",probe):writer.pdf(project,Collections.emptyList(),job,"en",probe);
            byte[] before=observed.clone();probe.returned(observed);probe.finish("returned_normally");assertArrayEquals(before,observed);
            Path directory=findOperation(format);JsonNode returned=event(directory,"actual_returned_report_bytes");
            assertArrayEquals(observed,Files.readAllBytes(directory.resolve(returned.path("artifact").path("relativePath").asText())));
            assertEquals(DocumentParseProbe.sha256(observed),returned.path("artifact").path("sha256").asText());
            assertEquals(observed.length,returned.path("artifact").path("bytes").asInt());assertEquals(job.getId(),returned.path("runId").asText());
            assertEquals("returned_normally",json(directory.resolve("probe_overhead_manifest.json")).path("status").asText());
            List<JsonNode> records=events(directory);assertTrue(records.stream().allMatch(record->job.getId().equals(record.path("runId").asText())));
            assertTrue(records.stream().anyMatch(record->"report_outline_construction".equals(record.path("observations").path("stage").asText())));
            JsonNode timing=records.stream().filter(record->(format.equals("docx")?"docx_ooxml_generation":"pdf_native_generation").equals(record.path("observations").path("stage").asText())).findFirst().get();
            assertTrue(timing.path("observations").path("wallNanos").asLong()>=0);assertTrue(timing.path("observations").path("probeOverheadNanos").asLong()>=0);
            if("docx".equals(format)) {
                try(XWPFDocument first=new XWPFDocument(new ByteArrayInputStream(baseline));XWPFDocument second=new XWPFDocument(new ByteArrayInputStream(observed))) {
                    assertEquals(first.getDocument().xmlText(),second.getDocument().xmlText(),"Same original OOXML body structure; this is not Word pagination verification");
                    assertEquals(first.getFooterList().get(0).getText(),second.getFooterList().get(0).getText());
                }
                assertTrue(timing.path("observations").path("boundary").asText().contains("not Microsoft Word pagination"));
            } else {
                try(PDDocument first=PDDocument.load(baseline);PDDocument second=PDDocument.load(observed)) {
                    assertEquals(first.getNumberOfPages(),second.getNumberOfPages());
                    assertEquals(new PDFTextStripper().getText(first),new PDFTextStripper().getText(second));
                }
            }
            assertTrue(json(directory.resolve("probe_overhead_manifest.json")).path("layoutQualityAccepted").isNull());
        }
    }

    @Test
    void failureBeforeReportContextBindingRecordsUnknownRunAndPreservesOriginalExceptionChain() throws Exception {
        VettingExportProbe probe=VettingExportProbe.start(props(),"project","json","en");
        RuntimeException cause=new IllegalArgumentException("Original context lookup cause"),original=new IllegalStateException("Original report failure",cause);
        RuntimeException actual=assertThrows(RuntimeException.class,()->{
            try{throw original;}catch(RuntimeException failure){probe.originalFailureElapsed("report_context_lookup_failed_elapsed",failure,17L);probe.finish("aborted",failure);throw failure;}
        });
        assertSame(original,actual);assertSame(cause,actual.getCause());Path directory=findOperation("json");
        JsonNode failure=event(directory,"report_context_lookup_failed_elapsed");assertTrue(failure.path("runId").isNull());
        assertFalse(failure.path("observations").path("runIdentityKnown").asBoolean());
        assertEquals(17L,failure.path("observations").path("wallNanos").asLong());assertEquals(2,failure.path("observations").path("failureChain").size());
        assertEquals(cause.getMessage(),failure.path("observations").path("failureChain").get(1).path("message").asText());
        assertTrue(json(directory.resolve("probe_overhead_manifest.json")).path("runId").isNull());
    }

    @Test
    void observerWriteAndFinalFlushFailuresAreSuppressedWithoutReplacingOriginalOrOldArtifacts() throws Exception {
        VettingExportProbe probe=VettingExportProbe.start(props(),"project","json","en");probe.bindRun("actual-run");
        assertThrows(IllegalStateException.class,()->probe.bindRun("different-run"));Path directory=findOperation("json");
        byte[] sentinel="immutable previous bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("00002-writer_failure.json"),sentinel,StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve("probe_overhead_manifest.json"),sentinel,StandardOpenOption.CREATE_NEW);
        RuntimeException original=new IllegalStateException("Original downstream error",new IllegalArgumentException("Original cause"));
        probe.originalFailure("writer_failure",original);probe.finish("aborted",original);
        assertEquals(2,original.getSuppressed().length);assertTrue(Arrays.stream(original.getSuppressed()).allMatch(f->f instanceof DocumentParseProbe.Failure));
        assertEquals("Original cause",original.getCause().getMessage());assertArrayEquals(sentinel,Files.readAllBytes(directory.resolve("00002-writer_failure.json")));
        assertArrayEquals(sentinel,Files.readAllBytes(directory.resolve("probe_overhead_manifest.json")));
    }

    private ConsenseProperties props(){ConsenseProperties props=new ConsenseProperties();props.getVetting().setExportProbeDirectory(root.toString());return props;}
    private Path findOperation(String format)throws Exception {
        try(java.util.stream.Stream<Path> dirs=Files.list(root)) {
            return dirs.filter(Files::isDirectory).filter(directory->{try{return events(directory).stream().anyMatch(event->format.equals(event.path("format").asText()));}catch(Exception e){throw new RuntimeException(e);}}).findFirst().get();
        }
    }
    private static JsonNode json(Path path)throws Exception{return JsonUtils.parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8));}
    private static List<JsonNode> events(Path directory)throws Exception {
        try(java.util.stream.Stream<Path> files=Files.list(directory)) {
            return files.filter(path->path.getFileName().toString().matches("[0-9]{5}-[a-z_]+\\.json")).sorted().map(path->{try{return json(path);}catch(Exception e){throw new RuntimeException(e);}}).collect(Collectors.toList());
        }
    }
    private static JsonNode event(Path directory,String phase)throws Exception{return events(directory).stream().filter(record->phase.equals(record.path("phase").asText())).findFirst().get();}
    private static Project project(){Project p=new Project();p.setId("fixture-project");p.setContractNo("OBS-001");p.setNameEn("Original observed report");return p;}
    private static VettingJobVO job(){VettingJobVO job=new VettingJobVO();job.setId("fixture-run");job.setStatus("COMPLETED");job.setFinishedAt(Instant.parse("2026-10-02T02:00:00Z"));
        job.setResult(new RunResultVO(0,null,Collections.emptyList(),"fixture-model"));job.setCoverage(new CoverageVO(0,0,Collections.emptyList(),Collections.emptyList()));return job;}
}
