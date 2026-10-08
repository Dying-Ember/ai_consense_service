package com.consense.service.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Diagnostic text is evidence about an experiment, never a source revision. */
class VettingInspectionOcrDiagnosticsBindingTest {
    @TempDir Path root;
    VettingInspectionOcrReviewTest f;
    Map<String,Object> experiment, raw;
    void fixture() throws Exception {
        f=new VettingInspectionOcrReviewTest();f.root=root;f.fixture();
        Map<String,Object> image=new LinkedHashMap<>((Map<String,Object>)f.page.get("originalImage"));
        Map<String,Object> imageArtifact=(Map<String,Object>)((Map<?,?>)((Map<?,?>)f.manifest.get("assets")).get(image.get("assetId"))).get("artifact");
        List<Object> lines=Arrays.asList(f.m("text","literal *not | 🧭", "confidence",.49));
        Map<String,Object> transform=f.m("clockwiseRotationDegrees",0);
        raw=f.m("configurationId","generic-control", "recognitionAttempts",1,"recognitionCompleted",1,
            "rawLines",lines,"rawJoinedText","literal *not | 🧭","transform",transform,"inputImage",imageArtifact);
        experiment=f.m("id","generic-control","status","actual_ocr_completed_unapplied","applied",false,
            "qualityStatus","needs_review","notSourceBlockRevision",true,"notNativeTableCells",true,
            "rawLines",lines,"rawJoinedText",raw.get("rawJoinedText"),"transform",transform,"image",image);
        f.page.put("experiments",Arrays.asList(experiment));
        save();
    }
    void save() throws Exception {experiment.put("rawResult",f.file("control.json",raw));f.replacePage();}
    void stage() throws Exception {
        Map<String,Object> payload=f.m("schema","rapidocr-line-flow-observation-v1","metadataErrors",Collections.emptyList(),
            "pages",Arrays.asList(f.m("complete",false,"mappingVerified",false,"lines",Arrays.asList(
                f.m("lineId","page-0:det-0","recognition",f.m("text","uncertain *not", "confidence",.49),"terminalStage","below_text_score")))));
        Map<String,Object> artifact=f.file("stage.json",payload);
        raw.put("lineStageProbe",artifact);experiment.put("lineStageDiagnosticsArtifact",artifact);
        experiment.put("lineStageDiagnostics",payload);save();
    }
    Map<String,Object> table() throws Exception {
        Map<String,Object> cell=f.m("id","cell","text","literal *not | 🧭","nativeCell",false,"headerStatus","unknown");
        Map<String,Object> payload=f.m("rawResult",experiment.get("rawResult"),"inputImage",raw.get("inputImage"),
            "algorithmModule",f.raw("geometry.py",new byte[]{1,2}),"coordinateFrame","exact OCR input raster",
            "originalPageTransform",raw.get("transform"),"sourceSnapshotOrIndexWrites",0,"newOcrOrNeuralOrGenerationCalls",0,
            "result",f.m("tables",Arrays.asList(f.m("status","needs_review","nativeTable",false,
                "semanticStructureVerified",false,"cells",Arrays.asList(cell)))));
        experiment.put("rasterTableCandidatesArtifact",f.file("tables.json",payload));
        experiment.put("rasterTableCandidates",payload);f.replacePage();return payload;
    }
    void invalid(){assertEquals("ocr_review_source_or_artifact_binding_invalid",f.attach().path("unavailableReason").asText());}
    @Test void preservesDroppedRawTextAndIncompleteMappingWithoutPromotingAccuracy() throws Exception {
        fixture();stage();JsonNode view=f.attach();assertEquals("available",view.path("status").asText());
        assertFalse(view.path("textAccuracyPromoted").asBoolean());
        assertEquals("uncertain *not",view.path("experiments").get(0).path("lineStageDiagnostics").path("pages").get(0).path("lines").get(0).path("recognition").path("text").asText());
        assertFalse(view.path("experiments").get(0).path("lineStageDiagnostics").path("pages").get(0).path("complete").asBoolean());
    }
    @Test void rejectsStageFromAnotherExperimentEvenWhenItsFileHashIsValid() throws Exception {
        fixture();stage();raw.remove("lineStageProbe");save();invalid();
    }
    @Test void rejectsChangedInlineStageWithoutChangingDeclaredArtifact() throws Exception {
        fixture();stage();experiment.put("lineStageDiagnostics",f.m("schema","other"));f.replacePage();invalid();
    }
    @Test void rejectsDiagnosticsPayloadWithoutArtifactBinding() throws Exception {
        fixture();stage();experiment.remove("lineStageDiagnosticsArtifact");f.replacePage();invalid();
    }
    @Test void preservesCandidateCellsAsUnknownUnappliedEvidence() throws Exception {
        fixture();table();JsonNode view=f.attach();assertEquals("available",view.path("status").asText());
        JsonNode t=view.path("experiments").get(0).path("rasterTableCandidates").path("result").path("tables").get(0);
        assertFalse(t.path("nativeTable").asBoolean());assertEquals("unknown",t.path("cells").get(0).path("headerStatus").asText());
    }
    @Test void rejectsTableFromDifferentRawResult() throws Exception {
        fixture();Map<String,Object> p=table();p.put("rawResult",f.file("other-control.json",raw));
        experiment.put("rasterTableCandidatesArtifact",f.file("tables.json",p));f.replacePage();invalid();
    }
    @Test void rejectsTableFromDifferentImageOrTransform() throws Exception {
        fixture();Map<String,Object> p=table();p.put("originalPageTransform",f.m("clockwiseRotationDegrees",180));
        experiment.put("rasterTableCandidatesArtifact",f.file("tables.json",p));f.replacePage();invalid();
    }
    @Test void rejectsNativeOrVerifiedPromotionInsideRasterCandidate() throws Exception {
        fixture();Map<String,Object> p=table();((Map<String,Object>)((List<?>)((Map<?,?>)p.get("result")).get("tables")).get(0)).put("nativeTable",true);
        experiment.put("rasterTableCandidatesArtifact",f.file("tables.json",p));f.replacePage();invalid();
    }
}
