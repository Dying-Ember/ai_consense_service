package com.consense.service.vetting;

import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reads bound saved OCR observations only. No OCR, raster rendering or source writes. */
@Service
public class VettingInspectionOcrReview {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String REGISTERED="tmp/vetting_foundation_hardening/inspection-ocr-risks-20261005/manifest.json";
    private final Path workspace, manifest;
    public VettingInspectionOcrReview(){this(findWorkspace());}
    public VettingInspectionOcrReview(Path workspace){this(workspace,registeredManifest(workspace));}
    public VettingInspectionOcrReview(Path workspace,Path manifest){this.workspace=workspace.toAbsolutePath().normalize();this.manifest=manifest.toAbsolutePath().normalize();}
    private static Path findWorkspace(){String explicit=System.getenv("CONSENSE_WORKSPACE_ROOT");Path p=Paths.get(explicit==null||explicit.trim().isEmpty()?System.getProperty("user.dir"):explicit).toAbsolutePath();for(Path c=p;c!=null;c=c.getParent())if(Files.isDirectory(c.resolve("ai_consense_service")))return c;return p;}
    private static Path registeredManifest(Path workspace){String explicit=System.getenv("CONSENSE_OCR_REVIEW_MANIFEST");return explicit==null||explicit.trim().isEmpty()?workspace.resolve(REGISTERED):Paths.get(explicit);}
    private static Map<String,Object> unavailable(String reason){Map<String,Object> out=new LinkedHashMap<>();out.put("status","unavailable");out.put("unavailableReason",reason);out.put("applied",false);return out;}
    public Object attach(String dataset,String doc,int number,Map<String,Object> currentPage){
        if(!Files.isRegularFile(manifest))return unavailable("ocr_review_manifest_not_registered");
        try{return bound(dataset,doc,number,currentPage).page;}catch(NotRecordedForDataset e){return unavailable("ocr_review_not_recorded_for_dataset");}catch(NotRecordedForPage e){return unavailable("ocr_review_not_recorded_for_page");}catch(IOException|RuntimeException e){return unavailable("ocr_review_source_or_artifact_binding_invalid");}
    }
    private static final class NotRecordedForDataset extends IOException {}
    private static final class NotRecordedForPage extends IOException {}
    static final class Bound {final JsonNode manifest;final ObjectNode page;Bound(JsonNode m,ObjectNode p){manifest=m;page=p;}}
    private Bound bound(String dataset,String doc,int number,Map<String,Object> currentPage)throws IOException {
        inside(manifest);JsonNode m=JSON.readTree(Files.readAllBytes(manifest));
        if(!"inspection-ocr-risk-sidecar-v1".equals(m.path("protocol").asText())||!m.path("datasetId").isTextual()||m.path("datasetId").asText().trim().isEmpty()||!m.path("readOnly").isBoolean()||!m.path("sourceApplied").isBoolean()||!m.path("humanConfirmed").isBoolean()||!m.path("textAccuracyPromoted").isBoolean()||!m.path("readOnly").asBoolean()||m.path("sourceApplied").asBoolean()||m.path("humanConfirmed").asBoolean()||m.path("textAccuracyPromoted").asBoolean())throw invalid();
        if(!dataset.equals(m.path("datasetId").asText()))throw new NotRecordedForDataset();
        JsonNode pageRow=null;for(JsonNode row:m.path("pages"))if(doc.equals(row.path("documentId").asText())&&number==row.path("physicalPage").asInt()){if(pageRow!=null)throw invalid();pageRow=row;}
        JsonNode proposal=read(m.path("datasetBindingProposal"));
        if(!dataset.equals(proposal.path("datasetId").asText())||!proposal.path("sourceDocuments").equals(m.path("sourceDocuments"))||!proposal.path("canonicalCorpus").equals(m.path("canonicalCorpus"))||!proposal.path("result").equals(m.path("closedIndexResult")))throw invalid();
        JsonNode result=read(m.path("closedIndexResult"));
        if(!"completed".equals(result.path("status").asText())||!result.path("qdrantClose").path("completed").asBoolean()||result.path("actualRetrievalAttempts").asInt(-1)!=0||result.path("actualGenerationCalls").asInt(-1)!=0)throw invalid();
        JsonNode actual=JSON.valueToTree(currentPage),provenance=actual.path("provenance");
        if(number!=actual.path("pageNo").asInt()||!dataset.equals(provenance.path("datasetId").asText())||!m.path("canonicalCorpus").path("sha256").asText().equals(provenance.path("corpusSha256").asText())||!result.path("indexSignature").equals(provenance.path("indexSignature"))||!result.path("projectId").asText().equals(provenance.path("projectId").asText()))throw invalid();
        if(pageRow==null)throw new NotRecordedForPage();
        ObjectNode page=(ObjectNode)read(pageRow.path("pageArtifact"));JsonNode binding=page.path("sourceBinding");
        if(!dataset.equals(binding.path("datasetId").asText())||!doc.equals(binding.path("documentId").asText())||number!=binding.path("physicalPage").asInt()||!pageRow.path("sourceHash").equals(binding.path("sourceHash"))||!m.path("sourceDocuments").path("sha256").equals(binding.path("sourceSnapshotSha256"))||!m.path("canonicalCorpus").path("sha256").equals(binding.path("canonicalCorpusSha256"))||!actual.path("blocks").equals(page.path("savedOcr").path("blocks")))throw invalid();
        SourceDocument source=source(m.path("sourceDocuments"),doc);
        if(source==null||!VettingCorpus.sourceHash(source).equals(binding.path("sourceHash").asText())||!Objects.equals(source.getReviewRole(),binding.path("role").asText())||!binding.path("rawPdfSha256").equals(page.path("rawPdf").path("sha256"))||!new File(source.getStoragePath()).toPath().toAbsolutePath().normalize().equals(artifact(page.path("rawPdf"))))throw invalid();
        Set<String> blockIds=new HashSet<>();for(JsonNode block:actual.path("blocks"))if(!blockIds.add(block.path("id").asText()))throw invalid();
        JsonNode review=page.path("visualReview");if(review.path("applied").asBoolean(true)||review.path("humanConfirmed").asBoolean(true)||review.path("textAccuracyPromoted").asBoolean(true)||!page.path("needsReview").asBoolean())throw invalid();
        ObjectNode original=(ObjectNode)page.path("originalImage");bindImage(m,original,dataset,doc,number);
        for(JsonNode item:page.path("experiments")) {
            if(!"actual_ocr_completed_unapplied".equals(item.path("status").asText())||item.path("applied").asBoolean(true)||!"needs_review".equals(item.path("qualityStatus").asText())||!item.path("notSourceBlockRevision").asBoolean()||!item.path("notNativeTableCells").asBoolean())throw invalid();
            JsonNode raw=read(item.path("rawResult"));
            if(!raw.path("configurationId").equals(item.path("id"))||raw.path("recognitionAttempts").asInt()!=1||raw.path("recognitionCompleted").asInt()!=1||!raw.path("rawLines").equals(item.path("rawLines"))||!raw.path("rawJoinedText").equals(item.path("rawJoinedText"))||!raw.path("transform").equals(item.path("transform")))throw invalid();
            bindDiagnostics(item,raw);
            bindImage(m,(ObjectNode)item.path("image"),dataset,doc,number);
        }
        page.put("status","available");page.put("applied",false);page.put("humanConfirmed",false);page.put("textAccuracyPromoted",false);
        return new Bound(m,page);
    }
    /** Optional observations must belong to this exact completed experiment. */
    private void bindDiagnostics(JsonNode experiment,JsonNode raw)throws IOException {
        if(experiment.has("lineStageDiagnostics")||experiment.has("lineStageDiagnosticsArtifact")) {
            JsonNode descriptor=experiment.path("lineStageDiagnosticsArtifact"),stage=experiment.path("lineStageDiagnostics");
            if(!descriptor.isObject()||!stage.isObject()||!descriptor.equals(raw.path("lineStageProbe"))||!read(descriptor).equals(stage)||
                !"rapidocr-line-flow-observation-v1".equals(stage.path("schema").asText())||!stage.path("pages").isArray()||!stage.path("metadataErrors").isArray())throw invalid();
            // Incomplete/unsupported mappings remain visible as observations.
            // No metadata field can promote low-scoring text to source content.
        }
        if(experiment.has("rasterTableCandidates")||experiment.has("rasterTableCandidatesArtifact")) {
            JsonNode descriptor=experiment.path("rasterTableCandidatesArtifact"),candidate=experiment.path("rasterTableCandidates");
            if(!descriptor.isObject()||!candidate.isObject()||!read(descriptor).equals(candidate)||
                !candidate.path("rawResult").equals(experiment.path("rawResult"))||
                !candidate.path("inputImage").equals(raw.path("inputImage"))||
                !candidate.path("inputImage").path("sha256").equals(experiment.path("image").path("sha256"))||
                !candidate.path("originalPageTransform").equals(raw.path("transform"))||
                !"exact OCR input raster".equals(candidate.path("coordinateFrame").asText())||
                !zero(candidate.path("sourceSnapshotOrIndexWrites"))||!zero(candidate.path("newOcrOrNeuralOrGenerationCalls"))||
                !candidate.path("result").path("tables").isArray())throw invalid();
            artifact(candidate.path("inputImage"));artifact(candidate.path("algorithmModule"));
            for(JsonNode table:candidate.path("result").path("tables")) {
                if(!"needs_review".equals(table.path("status").asText())||!explicitFalse(table.path("nativeTable"))||
                    !explicitFalse(table.path("semanticStructureVerified"))||!table.path("cells").isArray())throw invalid();
                for(JsonNode cell:table.path("cells"))if(!explicitFalse(cell.path("nativeCell"))||!"unknown".equals(cell.path("headerStatus").asText()))throw invalid();
            }
        }
    }
    private static boolean explicitFalse(JsonNode value){return value.isBoolean()&&!value.asBoolean();}
    private static boolean zero(JsonNode value){return value.isIntegralNumber()&&value.canConvertToLong()&&value.asLong()==0;}
    private static String url(String dataset,String doc,int number,String asset)throws IOException{return "/api/vetting-inspection/documents/"+URLEncoder.encode(doc,"UTF-8")+"/pages/"+number+"/ocr-assets/"+asset+"?datasetId="+URLEncoder.encode(dataset,"UTF-8");}
    private void bindImage(JsonNode m,ObjectNode image,String dataset,String doc,int number)throws IOException {
        String id=image.path("assetId").asText();if(!id.matches("png-[a-f0-9]{20}")||!m.path("assets").has(id))throw invalid();
        JsonNode entry=m.path("assets").path(id);if(!"image/png".equals(entry.path("contentType").asText())||!entry.path("artifact").path("sha256").equals(image.path("sha256")))throw invalid();
        png(entry.path("artifact"));image.put("assetUrl",url(dataset,doc,number,id));
    }
    public byte[] image(String dataset,String doc,int number,String assetId,Map<String,Object> currentPage)throws IOException {
        Bound bound=bound(dataset,doc,number,currentPage);Set<String> allowed=new HashSet<>();allowed.add(bound.page.path("originalImage").path("assetId").asText());for(JsonNode e:bound.page.path("experiments"))allowed.add(e.path("image").path("assetId").asText());
        if(!allowed.contains(assetId))throw invalid();return png(bound.manifest.path("assets").path(assetId).path("artifact"));
    }
    private byte[] png(JsonNode descriptor)throws IOException {if(descriptor.path("bytes").asLong()>30L*1024*1024)throw invalid();byte[] bytes=Files.readAllBytes(artifact(descriptor));byte[] header={(byte)137,80,78,71,13,10,26,10};if(bytes.length<8)throw invalid();for(int i=0;i<8;i++)if(bytes[i]!=header[i])throw invalid();return bytes;}
    private SourceDocument source(JsonNode descriptor,String id)throws IOException {Path p=artifact(descriptor);SourceDocument found=null;try(JsonParser parser=JSON.getFactory().createParser(p.toFile())){if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_ARRAY)throw invalid();ObjectReader element=JSON.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY){JsonNode n=element.readValue(parser);if(id.equals(n.path("id").asText())){if(found!=null)throw invalid();found=new SourceDocument();found.setTextContent(n.path("textContent").asText(null));found.setStructuredContentJson(n.path("structuredContentJson").asText(null));found.setReviewRole(n.path("reviewRole").asText(null));found.setStoragePath(n.path("storagePath").asText(null));}}if(parser.nextToken()!=null)throw invalid();}return found;}
    private JsonNode read(JsonNode descriptor)throws IOException {return JSON.readTree(Files.readAllBytes(artifact(descriptor)));}
    private void inside(Path p)throws IOException {if(!p.startsWith(workspace)||!p.toRealPath().startsWith(workspace.toRealPath()))throw invalid();}
    private Path artifact(JsonNode d)throws IOException {Path p=Paths.get(d.path("path").asText()).toAbsolutePath().normalize();inside(p);if(!Files.isRegularFile(p)||Files.size(p)!=d.path("bytes").asLong(-1))throw invalid();try{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[] buffer=new byte[1024*1024];for(int n;(n=in.read(buffer))>=0;)hash.update(buffer,0,n);}StringBuilder text=new StringBuilder();for(byte b:hash.digest())text.append(String.format("%02x",b&255));if(!text.toString().equals(d.path("sha256").asText()))throw invalid();return p;}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}}
    private static IOException invalid(){return new IOException("Saved OCR review source/artifact binding differs");}
}
