package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.FixedBaselineProperties;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Faithful v1/signature-3 wire shapes with authored source text; no model/network/DB calls. */
class FixedBaselineRetrievalClientTest {
    @TempDir Path temp;
    private static final String ORIGINAL=sha("authored source"), SOURCE=sha("authored parser snapshot"), SIGNATURE=sha("authored index");
    private final class Fixture {
        final HttpSupport http=mock(HttpSupport.class);
        final FixedBaselineProperties p=new FixedBaselineProperties();
        final ObjectNode first=parent("parent-a","1.1 Authored complete condition",1), second=parent("parent-b","Exception: authored qualifier",2);
        final ObjectNode health=JsonUtils.mapper().createObjectNode(), response=JsonUtils.mapper().createObjectNode();
        final Path corpus=temp.resolve("corpus.jsonl");
        final Path original=temp.resolve("original.pdf");
        final FixedBaselineRetrievalClient client;
        Fixture(boolean table) throws Exception {
            if(table) {
                ObjectNode artifact=JsonUtils.mapper().createObjectNode();artifact.put("id","parent-b");artifact.put("originalSourceSha256",ORIGINAL);artifact.put("sourceQualityAccepted",false);
                ArrayNode matrix=artifact.putObject("table").putArray("extractedMatrix");matrix.addArray().add("Heading").addNull().add("");matrix.addArray().add("Authored value").add("Exception").add("Cell");
                Path artifactPath=temp.resolve("table.json");write(artifactPath,artifact);second.set("tableArtifact",descriptor(artifactPath));
            }
            ObjectNode rawFirst=first.deepCopy();rawFirst.putNull("observedNodeId");
            Files.write(corpus,(JsonUtils.write(rawFirst)+"\n"+JsonUtils.write(second)+"\n").getBytes(StandardCharsets.UTF_8));
            ObjectNode runtime=runtime("embedding",16),rr=runtime("reranker",4);
            health.put("status","ok");health.put("mode","local");health.put("offline",true);health.put("device","cuda");health.put("indexSignatureVersion",3);
            ObjectNode models=health.putObject("models");models.set("embedding",model("embedding"));models.set("reranker",model("reranker"));
            ObjectNode runs=health.putObject("runtime");runs.set("embedding",runtime);runs.set("reranker",rr);
            ObjectNode retrieval=health.putObject("retrieval");retrieval.put("roleFilter",true);ObjectNode windows=retrieval.putObject("sourceWindows");
            windows.put("contract","source-bound-window-points-full-parent-results-v1");windows.put("denseAggregation","max");windows.put("rerankAggregation","max");
            ObjectNode recipe=JsonUtils.mapper().createObjectNode();recipe.put("max_tokens",512);recipe.put("overlap_tokens",64);recipe.put("max_windows_per_parent",10000);recipe.put("aggregation","max");recipe.put("version","source-bound-token-windows-v1");
            windows.set("embeddingWindows",recipe);windows.set("rerankWindows",recipe);windows.set("rerankRuntime",rr);windows.putObject("algorithms");
            windows.putObject("structuralSelection").put("policy","unique-source-structural-unit-seeds-v1").put("closureMembersAreRanked",false);
            ObjectNode plan=JsonUtils.mapper().createObjectNode();plan.put("projectId","authored-fixed");plan.put("indexSignatureSha256",SIGNATURE);
            ObjectNode sig=plan.putObject("signature");sig.set("embedding",model("embedding"));sig.set("runtime",runtime);sig.set("windowRecipe",recipe);sig.putObject("algorithms");
            Path planPath=temp.resolve("plan.json");write(planPath,plan);
            ObjectNode manifest=JsonUtils.mapper().createObjectNode();manifest.put("schema","consense.frozen-fixed-development-baseline.v1");manifest.put("developmentBaseline",true);
            manifest.put("finalParsingAccepted",false);manifest.put("businessAccepted",false);manifest.put("version","authored-freeze-05");manifest.put("indexSignature",SIGNATURE);
            Files.write(original,"authored source".getBytes(StandardCharsets.UTF_8));
            manifest.putArray("scope").add("GCC").add("GCT").add("all-applicable-SL-technical");manifest.putObject("originalSources").set("gccSource",descriptor(original));
            manifest.set("runtimePlan",descriptor(planPath));manifest.set("corpus",descriptor(corpus));manifest.putObject("coverage").put("totalParents",2);
            Path manifestPath=temp.resolve("manifest.json");write(manifestPath,manifest);
            p.setEnabled(true);p.setManifestPath(manifestPath.toString());p.setManifestSha256(sha(Files.readAllBytes(manifestPath)));p.setProjectId("authored-fixed");p.setIndexSignature(SIGNATURE);
            response.put("projectId","authored-fixed");response.put("indexSignature",SIGNATURE);response.put("mode","source-bound-window-points-full-parent-results-v1");
            response.putArray("hits").addObject().put("id","parent-a").put("score",.8).set("payload",first);
            ObjectNode source=response.putObject("sourceUnits");source.put("policy","unique-source-structural-unit-seeds-v1");source.put("scope","canonical_observed_structure_only");
            source.put("derivedMembersCanBecomeOrigins",false);source.put("qualifiersComplete","unknown");source.put("semanticScopeVerified",false);
            ObjectNode unit=source.putArray("units").addObject();unit.put("unitId",sha("authored group"));unit.put("kind","exact_clause_heading");unit.put("status","complete_observed_unit");
            unit.putArray("originIds").add("parent-a");unit.putArray("requiredMemberIds").add("parent-a").add("parent-b");unit.putArray("members").add(first).add(second);
            unit.putObject("sourceIdentity").put("documentId","GCC:GCC").put("sourceHash",SOURCE).put("role","standard");
            unit.put("derivedMembersCanBecomeOrigins",false);unit.put("qualifiersComplete","unknown");unit.put("semanticScopeVerified",false);
            when(http.get(endsWith("/health"),anyLong())).thenAnswer(c->JsonUtils.write(health));
            when(http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenAnswer(c->JsonUtils.write(response));
            client=new FixedBaselineRetrievalClient(http,p);
        }
        ObjectNode unit(){return (ObjectNode)response.path("sourceUnits").path("units").get(0);}
        List<VectorStore.SearchHit> search(){return client.search("advisory","authored question",1);}
        void enableMigration() throws Exception {
            Path mp=Paths.get(p.getManifestPath());ObjectNode manifest=(ObjectNode)JsonUtils.parse(new String(Files.readAllBytes(mp),StandardCharsets.UTF_8));
            Path pp=Paths.get(manifest.path("runtimePlan").path("path").asText());ObjectNode plan=(ObjectNode)JsonUtils.parse(new String(Files.readAllBytes(pp),StandardCharsets.UTF_8));
            ObjectNode sig=(ObjectNode)plan.path("signature"),windows=(ObjectNode)health.path("retrieval").path("sourceWindows");
            sig.put("contract","source-bound-window-points-full-parent-results-v1");sig.put("corpus",sha("authored canonical parents"));
            sig.put("signatureVersion",3);sig.put("normalized",true);sig.put("structuralSelectionPolicy","unique-source-structural-unit-seeds-v1");sig.putObject("embeddingPreprocessing").put("strip",true);
            ObjectNode tokenizer=sig.putObject("tokenizerAndModelFiles");tokenizer.set("model",model("embedding"));tokenizer.putArray("files").addObject().put("relativePath","authored.bin").put("bytes",1).put("sha256",sha("weight"));
            tokenizer.put("backendSha256",sha("tokenizer"));tokenizer.put("tokenizerClass","AuthoredFastTokenizer");tokenizer.put("fast",true);tokenizer.put("modelMaxLength",512);
            write(pp,plan);manifest.set("runtimePlan",descriptor(pp));manifest.put("collection","authored_local");manifest.put("persistedVectorCount",2);
            Path metadata=temp.resolve("metadata.json");write(metadata,JsonUtils.mapper().createObjectNode().put("authored","immutable metadata"));manifest.set("persistedMetadata",descriptor(metadata));
            ObjectNode seal=manifest.putObject("vectorIntegrity");seal.put("schema","consense.persisted-vector-index-integrity.v1");seal.put("dimension",2);seal.put("dtype","float32-le");seal.put("pointCount",2);seal.put("embeddingSignatureSha256",SIGNATURE);seal.put("pointsSha256",sha("warm cosine values"));seal.put("persistedPointsSha256",sha("complete vectors"));
            ObjectNode persisted=seal.deepCopy();persisted.remove("persistedPointsSha256");persisted.put("pointsSha256",sha("complete vectors"));
            ObjectNode publications=manifest.putObject("modelPublications");
            for(String kind:Arrays.asList("embedding","reranker")) {ObjectNode publication=JsonUtils.mapper().createObjectNode();publication.set("model",model(kind));publication.set("files",tokenizer.path("files").deepCopy());publication.put("publicationSha256",sha(kind+" release"));Path pub=temp.resolve(kind+"-publication.json");write(pub,publication);publications.set(kind,descriptor(pub));}
            write(mp,manifest);p.setManifestSha256(sha(Files.readAllBytes(mp)));
            ObjectNode migration=JsonUtils.mapper().createObjectNode();migration.put("schema","consense.qdrant-server-migration.v1");migration.put("status","completed_full_storage_readback_candidate_not_adopted");
            migration.put("targetAdopted",false);migration.put("activeConfigurationChanged",false);migration.put("allOriginalPersistentStateBytesUnchanged",true);
            migration.set("frozenManifest",descriptor(mp));migration.set("originalMetadata",descriptor(metadata));migration.set("candidateMetadata",descriptor(metadata));migration.set("embeddingSignature",sig.deepCopy());
            migration.put("indexSignature",SIGNATURE);migration.put("parentCount",2);migration.put("vectorWindowCount",2);migration.set("targetVectorIntegrity",persisted.deepCopy());migration.set("sourceVectorIntegrity",persisted.deepCopy());
            migration.putObject("target").put("url","http://127.0.0.1:16333").put("collection","oer_server_authored");
            Path receipt=temp.resolve("migration.json");write(receipt,migration);p.setMigrationReceiptPath(receipt.toString());p.setMigrationReceiptSha256(sha(Files.readAllBytes(receipt)));p.setBaseUrl("http://127.0.0.1:18878");
            health.put("mode","server");health.put("device","cpu");
            for(String kind:Arrays.asList("embedding","reranker")) {ObjectNode run=(ObjectNode)health.path("runtime").path(kind);run.put("device","cpu");run.put("dtype","float32");run.put("batchSize",1);}
            ObjectNode algorithms=(ObjectNode)windows.path("algorithms");
            for(String name:Arrays.asList("server.py","window_runtime.py","migrated_qdrant_index.py","migrate_qdrant_server.py","fixed_development_baseline_runtime.py","verify_retrieval_offline.py","token_windows.py","index_integrity.py","retrieval_source_units.py","checkpoint_publication.py","fixed_vector_cache.py","qdrant_backend.py","workspace_root.py","embedding_compatibility.py","structural_retrieval.py","preparation_cache.py","checkpoint_identity.py"))algorithms.put(name,sha(name));
            ObjectNode identity=sig.deepCopy();identity.set("runtime",health.path("runtime").path("embedding").deepCopy());
            ((ObjectNode)identity.path("tokenizerAndModelFiles")).putObject("checkpointPublication").put("manifestSha256",publications.path("embedding").path("sha256").asText()).put("publicationSha256",sha("embedding release"));
            ObjectNode recipe=windows.deepCopy(),rrTokenizer=tokenizer.deepCopy();recipe.set("rerankTokenizerAndModelFiles",rrTokenizer);rrTokenizer.set("model",model("reranker"));rrTokenizer.putObject("checkpointPublication").put("manifestSha256",publications.path("reranker").path("sha256").asText()).put("publicationSha256",sha("reranker release"));
            recipe.set("rerankRuntime",health.path("runtime").path("reranker").deepCopy());
            ObjectNode query=JsonUtils.mapper().createObjectNode();query.put("schema","consense.migrated-server-query-runtime.v1");query.put("migrationReceiptSha256",p.getMigrationReceiptSha256());query.put("queryApiBaseUrl",p.getBaseUrl());
            query.put("indexSignature",SIGNATURE);query.put("readOnly",true);query.put("runtimeCompatibilityVerified",false);query.set("target",migration.path("target"));query.set("fullVectorIntegrity",persisted);
            query.set("queryIdentity",identity);query.set("queryRecipe",recipe);query.set("health",health.deepCopy());
            java.lang.reflect.Method digest=FixedBaselineRetrievalClient.class.getDeclaredMethod("queryDigest",JsonNode.class);digest.setAccessible(true);query.put("queryRuntimeSignature",(String)digest.invoke(null,identity));
            Path qp=temp.resolve("query.json");write(qp,query);p.setQueryRuntimePath(qp.toString());p.setQueryRuntimeSha256(sha(Files.readAllBytes(qp)));
            ObjectNode access=response.putObject("indexAccess");access.put("mode","pinned-migrated-server-read-only");access.put("readOnly",true);access.put("runtimeCompatibilityVerified",false);access.put("migrationReceiptSha256",p.getMigrationReceiptSha256());
            for(String field:Arrays.asList("target","queryIdentity","queryRecipe","queryRuntimeSignature","fullVectorIntegrity"))access.set(field,query.path(field).deepCopy());
        }
    }
    @Test void disabledDoesNotReadOrCall() {
        HttpSupport http=mock(HttpSupport.class);FixedBaselineRetrievalClient c=new FixedBaselineRetrievalClient(http,new FixedBaselineProperties());
        assertFalse(c.enabled());assertThrows(IllegalStateException.class,()->c.search("vetting","q",1));verifyNoInteractions(http);
    }
    @Test void fullSourceGroupAndTopLevelNullWireArePreserved() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();assertEquals(2,hits.size());
        JsonNode material=JsonUtils.parse(f.client.toModelMaterial(hits,100000));assertEquals("reference_only",material.path("basisScope").asText());
        assertEquals(2,material.path("references").size());assertEquals(f.second.path("content"),material.path("references").get(1).path("content"));
        assertFalse(material.toString().contains("bbox"));assertFalse(hits.get(0).getPayload().containsKey("canonicalEvidence"));
        assertThrows(IllegalStateException.class,()->f.client.toModelMaterial(Collections.singletonList(hits.get(0)),100000));
        assertThrows(IllegalStateException.class,()->f.client.toModelMaterial(hits,20));
        assertEquals(f.second.path("content").asText(),f.client.readOnlyHitById("parent-b").text());
    }
    @Test void refusesChangedPayloadPartsScopeAndSignature() throws Exception {
        Fixture f=new Fixture(false);f.first.put("content","changed content");assertThrows(IllegalStateException.class,f::search);
        f.first.put("content","1.1 Authored complete condition");((ObjectNode)f.first.path("parts").get(0)).put("text","changed part");assertThrows(IllegalStateException.class,f::search);
        ((ObjectNode)f.first.path("parts").get(0)).put("text","1.1 Authored complete condition");f.first.put("role","tender");assertThrows(IllegalStateException.class,f::search);
        f.first.put("role","standard");f.response.put("indexSignature",sha("wrong signature"));assertThrows(IllegalStateException.class,f::search);
    }
    @Test void refusesIncompleteClosureAndUnknownSource() throws Exception {
        Fixture f=new Fixture(false);((ArrayNode)f.unit().path("members")).remove(1);assertThrows(IllegalStateException.class,f::search);
        ((ArrayNode)f.unit().path("members")).add(f.second);f.second.put("originalSourceSha256",sha("wrong original"));assertThrows(IllegalStateException.class,f::search);
    }
    @Test void migrationPinsAllowDeclaredCpuQueryAndKeepCompleteGroupGuard() throws Exception {
        Fixture f=new Fixture(false);f.enableMigration();assertEquals(2,f.search().size());
        ((ArrayNode)f.unit().path("members")).remove(1);assertThrows(IllegalStateException.class,f::search);
    }
    @Test void missingChangedOrRemovedMigrationPinsFailBeforeHttp() throws Exception {
        Fixture partial=new Fixture(false);partial.p.setMigrationReceiptPath("partial");assertThrows(IllegalStateException.class,partial::search);verifyNoInteractions(partial.http);
        Fixture wrong=new Fixture(false);wrong.enableMigration();wrong.p.setMigrationReceiptSha256(sha("wrong pin"));assertThrows(IllegalStateException.class,wrong::search);verifyNoInteractions(wrong.http);
        Fixture f=new Fixture(false);f.enableMigration();f.search();clearInvocations(f.http);
        Files.write(Paths.get(f.p.getQueryRuntimePath()),"{}".getBytes(StandardCharsets.UTF_8));assertThrows(IllegalStateException.class,f::search);verifyNoInteractions(f.http);
    }
    @Test void migratedRuntimeUrlVectorAndHelperDriftAreRejected() throws Exception {
        Fixture f=new Fixture(false);f.enableMigration();ObjectNode access=(ObjectNode)f.response.path("indexAccess"),target=(ObjectNode)access.path("target");
        target.put("url","http://127.0.0.1:16334");assertThrows(IllegalStateException.class,f::search);target.put("url","http://127.0.0.1:16333");
        ObjectNode seal=(ObjectNode)access.path("fullVectorIntegrity");seal.put("pointsSha256",sha("same count corruption"));assertThrows(IllegalStateException.class,f::search);seal.put("pointsSha256",sha("complete vectors"));
        ((ObjectNode)access.path("queryRecipe").path("algorithms")).put("migrated_qdrant_index.py",sha("changed helper"));assertThrows(IllegalStateException.class,f::search);
        ((ObjectNode)f.health.path("runtime").path("embedding")).put("dtype","float16");assertThrows(IllegalStateException.class,f::search);
    }
    @Test void defaultLocalGuardStillRejectsServerHealth() throws Exception {
        Fixture f=new Fixture(false);f.health.put("mode","server");assertThrows(IllegalStateException.class,f::search);verify(f.http,never()).postJson(anyString(),anyString(),anyLong());
    }
    @Test void querySignatureMatchesActualPythonIntegerJsonGolden() throws Exception {
        JsonNode value=JsonUtils.parse("{\"modelMaxLength\":1000000000000000019884624838656,\"files\":[{\"bytes\":10}]}");
        java.lang.reflect.Method digest=FixedBaselineRetrievalClient.class.getDeclaredMethod("queryDigest",JsonNode.class);digest.setAccessible(true);
        assertEquals("1f9b27100148c6e0e18d728e25673b67120895fb703d61ac59c390ed2b963b49",digest.invoke(null,value));
    }
    @Test void repinnedWrongOriginalColdOrTargetVectorSealFailsBeforeHttp() throws Exception {
        for(String kind:Arrays.asList("sourceVectorIntegrity","targetVectorIntegrity")) {
            Fixture f=new Fixture(false);f.enableMigration();Path path=Paths.get(f.p.getMigrationReceiptPath());
            ObjectNode receipt=(ObjectNode)JsonUtils.parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8));
            ((ObjectNode)receipt.path(kind)).put("pointsSha256",sha("same count wrong vector"));write(path,receipt);
            f.p.setMigrationReceiptSha256(sha(Files.readAllBytes(path)));
            assertTrue(assertThrows(IllegalStateException.class,f::search).getMessage().contains("migration_original_persisted_vectors_changed"));verifyNoInteractions(f.http);
        }
    }
    @Test void tableNullAndEmptyRemainDistinctAndBodyIsNotRepeated() throws Exception {
        Fixture f=new Fixture(true);List<VectorStore.SearchHit> hits=f.search();JsonNode material=JsonUtils.parse(f.client.toModelMaterial(hits,100000));
        JsonNode table=material.path("references").get(1);assertFalse(table.has("content"));JsonNode matrix=table.path("completeObservedTable").path("matrix");
        assertTrue(matrix.get(0).get(1).isNull());assertEquals("",matrix.get(0).get(2).asText());assertEquals("unknown",table.path("completeObservedTable").path("trueBlankAccepted").asText());
        Files.write(temp.resolve("table.json"),"{}".getBytes(StandardCharsets.UTF_8));assertThrows(IllegalStateException.class,()->f.client.assertCurrent(hits));
    }
    @Test void rejectsCorpusOrConfigurationChangeAfterCatalog() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();f.p.setIndexSignature(sha("new signature"));assertThrows(IllegalStateException.class,()->f.client.assertCurrent(hits));
        f.p.setIndexSignature(SIGNATURE);Files.write(f.corpus,"extra".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);assertThrows(IllegalStateException.class,()->f.client.frozenManifest());
    }
    @Test void rejectsSameSizeOriginalReplacementEvenWithRestoredTimestamp() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();
        java.nio.file.attribute.FileTime before=Files.getLastModifiedTime(f.original);
        Files.write(f.original,"modified source".getBytes(StandardCharsets.UTF_8));Files.setLastModifiedTime(f.original,before);
        assertEquals(15,Files.size(f.original));
        assertThrows(IllegalStateException.class,f::search);
        assertThrows(IllegalStateException.class,()->f.client.assertCurrent(hits));
        assertThrows(IllegalStateException.class,()->f.client.toModelMaterial(hits,100000));
        assertThrows(IllegalStateException.class,()->f.client.readOnlyHitById("parent-a"));
    }
    @Test void rejectsSameSizeSelectedCorpusReplacementWithRestoredTimestamp() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();
        java.nio.file.attribute.FileTime before=Files.getLastModifiedTime(f.corpus);
        String content=new String(Files.readAllBytes(f.corpus),StandardCharsets.UTF_8);
        Files.write(f.corpus,content.replace("Authored complete","Altered complete").getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(f.corpus,before);
        assertThrows(IllegalStateException.class,f::search);
        assertThrows(IllegalStateException.class,()->f.client.assertCurrent(hits));
        assertThrows(IllegalStateException.class,()->f.client.readOnlyHitById("parent-a"));
    }
    private static ObjectNode parent(String id,String text,int page) {
        ObjectNode p=JsonUtils.mapper().createObjectNode();p.put("id",id);p.put("content",text);p.put("role","standard");p.put("fileKey","GCC");p.put("documentId","GCC:GCC");p.put("anchor","GCC/P"+page);p.put("pageNo",page);
        p.put("sourceHash",SOURCE);p.put("originalSourceSha256",ORIGINAL);p.put("sourceQualityHash",sha("authored quality"));p.put("fullObservedParentPreserved",true);p.put("fixedDevelopmentBaseline",true);
        p.put("qualityAdopted",false);p.put("legalScopeAccepted",false);p.put("semanticScopeVerified",false);p.put("qualifiersComplete","unknown");p.putObject("sourceQuality").put("family","GCC").put("finalQualityAccepted",false);
        ObjectNode part=p.putArray("parts").addObject();part.put("blockId",id+"-block");part.put("pageNo",page);part.put("text",text);part.putArray("bbox").add(.1).add(.2).add(.3).add(.4);return p;
    }
    private static ObjectNode model(String kind) {ObjectNode n=JsonUtils.mapper().createObjectNode();n.put("name","embedding".equals(kind)?"BAAI/bge-m3":"BAAI/bge-reranker-v2-m3");n.put("revision","embedding".equals(kind)?"5617a9f61b028005a4858fdac845db406aefb181":"953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e");return n;}
    private static ObjectNode runtime(String kind,int batch) {ObjectNode n=model(kind);n.put("maxTokens",512);n.put("batchSize",batch);n.put("device","cuda");n.put("dtype","float16");n.put("serialOffload",true);return n;}
    private static void write(Path path,JsonNode n) throws Exception {Files.write(path,JsonUtils.write(n).getBytes(StandardCharsets.UTF_8));}
    private static ObjectNode descriptor(Path path) throws Exception {ObjectNode n=JsonUtils.mapper().createObjectNode();n.put("path",path.toString());n.put("bytes",Files.size(path));n.put("sha256",sha(Files.readAllBytes(path)));return n;}
    private static String sha(String v){return sha(v.getBytes(StandardCharsets.UTF_8));}
    private static String sha(byte[] v){try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(v))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
