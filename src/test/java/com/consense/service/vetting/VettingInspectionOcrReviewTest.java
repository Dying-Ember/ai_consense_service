package com.consense.service.vetting;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

class VettingInspectionOcrReviewTest {
    @TempDir Path root;
    static final ObjectMapper J=new ObjectMapper();
    static final byte[] PNG={(byte)137,80,78,71,13,10,26,10,1};
    Map<String,Object> m(Object...kv){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)out.put((String)kv[i],kv[i+1]);return out;}
    String sha(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b&255));return s.toString();}
    Map<String,Object> raw(String name,byte[] bytes)throws Exception{Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.write(p,bytes);return m("path",p.toString(),"bytes",bytes.length,"sha256",sha(bytes));}
    Map<String,Object> file(String name,Object body)throws Exception{return raw(name,J.writeValueAsBytes(body));}
    Map<String,Object> current, manifest, page, source;Path manifestPath;Map<String,Object> sourceFile,pageFile;
    void fixture()throws Exception {
        Map<String,Object> block=m("id","one","pageNo",2,"text","source 🧭 | text","bbox",Arrays.asList(.1,.2,.3,.4),"source","ocr");
        Map<String,Object> pdf=raw("original.pdf",new byte[]{1,2,3});
        source=m("id",8,"reviewRole","tender","storagePath",pdf.get("path"),"textContent","source 🧭 | text","structuredContentJson",J.writeValueAsString(Arrays.asList(block)));
        String sourceHash=VettingCorpus.hash(source.get("textContent")+"\n"+source.get("structuredContentJson"));
        sourceFile=file("sources.json",Arrays.asList(m("id",6,"textContent","unrelated"),source));
        Map<String,Object> corpus=raw("corpus.jsonl",new byte[]{4});
        Map<String,Object> result=file("result.json",m("status","completed","qdrantClose",m("completed",true),"actualRetrievalAttempts",0,"actualGenerationCalls",0,"indexSignature","sig","projectId","p"));
        Map<String,Object> proposal=file("binding.json",m("datasetId","generic-dataset","sourceDocuments",sourceFile,"canonicalCorpus",corpus,"result",result));
        Map<String,Object> image=raw("page.png",PNG);String asset="png-"+image.get("sha256").toString().substring(0,20);
        page=m("sourceBinding",m("datasetId","generic-dataset","documentId","8","physicalPage",2,"sourceHash",sourceHash,"rawPdfSha256",pdf.get("sha256"),"role","tender","sourceSnapshotSha256",sourceFile.get("sha256"),"canonicalCorpusSha256",corpus.get("sha256")),
            "originalImage",m("assetId",asset,"sha256",image.get("sha256")),"savedOcr",m("blocks",Arrays.asList(block)),
            "visualReview",m("applied",false,"humanConfirmed",false,"textAccuracyPromoted",false),"needsReview",true,"rawPdf",pdf,"experiments",Collections.emptyList());
        pageFile=file("page.json",page);
        manifest=m("protocol","inspection-ocr-risk-sidecar-v1","datasetId","generic-dataset","readOnly",true,"sourceApplied",false,"humanConfirmed",false,"textAccuracyPromoted",false,
            "datasetBindingProposal",proposal,"sourceDocuments",sourceFile,"canonicalCorpus",corpus,"closedIndexResult",result,
            "pages",Arrays.asList(m("documentId","8","physicalPage",2,"sourceHash",sourceHash,"pageArtifact",pageFile)),"assets",m(asset,m("contentType","image/png","artifact",image)));
        manifestPath=root.resolve("manifest.json");writeManifest();
        current=m("pageNo",2,"blocks",Arrays.asList(block),"quality",m("textAccuracy","unverified"),"textRevisions",Collections.emptyList(),
            "provenance",m("datasetId","generic-dataset","corpusSha256",corpus.get("sha256"),"indexSignature","sig","projectId","p"));
    }
    void writeManifest()throws Exception{Files.write(manifestPath,J.writeValueAsBytes(manifest));}
    void replacePage()throws Exception{pageFile=file("page.json",page);((Map<String,Object>)((List<?>)manifest.get("pages")).get(0)).put("pageArtifact",pageFile);writeManifest();}
    VettingInspectionOcrReview reader(){return new VettingInspectionOcrReview(root,manifestPath);}
    JsonNode attach(){return J.valueToTree(reader().attach("generic-dataset","8",2,current));}
    @Test void boundCurrentPageExposesSavedImageWithoutSourceOrAccuracyPromotion()throws Exception{fixture();JsonNode view=attach();assertEquals("available",view.path("status").asText());assertFalse(view.path("applied").asBoolean());assertFalse(view.path("humanConfirmed").asBoolean());assertTrue(view.path("originalImage").path("assetUrl").asText().startsWith("/api/vetting-inspection/"));assertEquals("unverified",((Map<?,?>)current.get("quality")).get("textAccuracy"));}
    @Test void missingRegistrationReturnsExplicitUnavailable()throws Exception{fixture();Files.delete(manifestPath);assertEquals("ocr_review_manifest_not_registered",attach().path("unavailableReason").asText());}
    @Test void unobservedPageIsNotClassifiedAsCorruptedBinding()throws Exception{fixture();manifest.put("pages",Collections.emptyList());writeManifest();assertEquals("ocr_review_not_recorded_for_page",attach().path("unavailableReason").asText());((Map<String,Object>)current.get("provenance")).put("indexSignature","wrong");assertEquals("ocr_review_source_or_artifact_binding_invalid",attach().path("unavailableReason").asText());}
    @Test void oldDatasetCannotBorrowMatchingPageText()throws Exception{fixture();JsonNode view=J.valueToTree(reader().attach("old-dataset","8",2,current));assertEquals("unavailable",view.path("status").asText());assertEquals("ocr_review_not_recorded_for_dataset",view.path("unavailableReason").asText());assertFalse(view.has("savedOcr"));}
    @Test void malformedHeaderIsNotClassifiedAsUnobservedDataset()throws Exception{fixture();manifest.put("readOnly","true");writeManifest();assertEquals("ocr_review_source_or_artifact_binding_invalid",J.valueToTree(reader().attach("old-dataset","8",2,current)).path("unavailableReason").asText());}
    @Test void differentCurrentIndexIsRejected()throws Exception{fixture();((Map<String,Object>)current.get("provenance")).put("indexSignature","other");assertEquals("unavailable",attach().path("status").asText());}
    @Test void blockTextOrBboxChangeIsRejectedRatherThanReassigned()throws Exception{fixture();((Map<String,Object>)((List<?>)current.get("blocks")).get(0)).put("text","changed");assertEquals("unavailable",attach().path("status").asText());}
    @Test void duplicatedPageIdentityCannotChooseLast()throws Exception{fixture();List<Object> pages=new ArrayList<>((List<?>)manifest.get("pages"));pages.add(pages.get(0));manifest.put("pages",pages);writeManifest();assertEquals("unavailable",attach().path("status").asText());}
    @Test void appliedTrialCannotMasqueradeAsReadOnlyObservation()throws Exception{fixture();((Map<String,Object>)page.get("visualReview")).put("applied",true);replacePage();assertEquals("unavailable",attach().path("status").asText());}
    @Test void mutatedImageBytesCannotReuseItsDeclaredHash()throws Exception{fixture();Files.write(root.resolve("page.png"),new byte[]{9});assertEquals("unavailable",attach().path("status").asText());}
    @Test void unknownAssetAndWrongPageCannotReadOtherFiles()throws Exception{fixture();assertThrows(java.io.IOException.class,()->reader().image("generic-dataset","8",2,"../../original.pdf",current));assertThrows(java.io.IOException.class,()->reader().image("generic-dataset","8",3,"png-anything",current));}
    @Test void boundPngBytesAreServedExactly()throws Exception{fixture();String id=((Map<?,?>)page.get("originalImage")).get("assetId").toString();assertArrayEquals(PNG,reader().image("generic-dataset","8",2,id,current));}
    @Test void sourceSnapshotHashFailureReturnsUnavailable()throws Exception{fixture();Files.write(root.resolve("sources.json"),new byte[]{0});assertEquals("unavailable",attach().path("status").asText());}
    @Test void changedSourceRevisionIsRejectedEvenIfCurrentBlocksWereCopied()throws Exception{fixture();source.put("textContent","different full source");Map<String,Object> newSources=file("sources.json",Arrays.asList(source));manifest.put("sourceDocuments",newSources);JsonNode proposal=J.readTree(root.resolve("binding.json").toFile());((com.fasterxml.jackson.databind.node.ObjectNode)proposal).set("sourceDocuments",J.valueToTree(newSources));manifest.put("datasetBindingProposal",file("binding.json",proposal));((Map<String,Object>)page.get("sourceBinding")).put("sourceSnapshotSha256",newSources.get("sha256"));replacePage();assertEquals("unavailable",attach().path("status").asText());}
}
