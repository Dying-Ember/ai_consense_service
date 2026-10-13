package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Python health/selector captures; HTTP and neural scores are explicit fixtures. */
class VettingDualStructureHealthTest {
    static JsonNode resource(String name) throws Exception {
        try(InputStream in=VettingDualStructureHealthTest.class.getResourceAsStream("/vetting/"+name)) {
            assertNotNull(in);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
            for(int n;(n=in.read(buffer))>=0;)out.write(buffer,0,n);
            return JsonUtils.parse(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    static ObjectNode health() throws Exception {return (ObjectNode)resource("current_structural_window_health.json");}
    static ConsenseProperties expected() {
        ConsenseProperties p=new ConsenseProperties();p.getVetting().setRequireHybridRetrieval(true);
        p.getVetting().setExpectedRetrievalContract(VettingRetrievalHealth.STRUCTURAL_CONTRACT);
        p.getVetting().setExpectedRetrievalSignatureVersion(4);
        p.getVetting().setExpectedRetrievalGranularity("dual-structure-v1");
        return p;
    }
    @Test void capturedActualHealthRequiresExplicitDualContractVersionAndGranularity() throws Exception {
        ObjectNode h=health();assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,new ConsenseProperties().getVetting()));
        VettingRetrievalHealth.Identity identity=VettingRetrievalHealth.validate(h,expected().getVetting());
        assertEquals(4,identity.signatureVersion);assertEquals(VettingRetrievalHealth.STRUCTURAL_CONTRACT,identity.contract);
        assertEquals(VettingRetrievalSourceUnits.POLICY,identity.sourceUnitPolicy);
        ConsenseProperties p=expected();p.getVetting().setExpectedRetrievalGranularity("windows-v1");
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,p.getVetting()));
        p.getVetting().setExpectedRetrievalGranularity("dual-structure-v1");p.getVetting().setExpectedRetrievalSignatureVersion(3);
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,p.getVetting()));
    }
    @Test void actualOerProfileBindsSeparateShortAndLongBudgets() throws Exception {
        StandardEnvironment environment=new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for(PropertySource<?> source:new YamlPropertySourceLoader().load("oer",new ClassPathResource("application-oer.yml")))environment.getPropertySources().addLast(source);
        ConsenseProperties p=new ConsenseProperties();Binder.get(environment).bind("consense",Bindable.ofInstance(p));
        assertTrue(p.getVetting().isRequireHybridRetrieval());assertEquals("http://127.0.0.1:8868",p.getVetting().getRetrievalUrl());
        assertEquals(512,p.getVetting().getExpectedEmbeddingWindowMaxTokens());assertEquals(512,p.getVetting().getExpectedRerankWindowMaxTokens());
        assertEquals(2048,p.getVetting().getExpectedStructuralEmbeddingMaxTokens());assertEquals(2048,p.getVetting().getExpectedStructuralRerankMaxTokens());
        assertTrue(p.getVetting().isExpectedStructuralRerankStrict());assertEquals(VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_CONTRACT,p.getVetting().getExpectedRetrievalContract());VettingRetrievalHealth.validate(OerAttachmentHealthTest.authoredHealth(),p.getVetting());
        ConsenseProperties defaults=new ConsenseProperties();assertEquals(3,defaults.getVetting().getExpectedRetrievalSignatureVersion());
        assertEquals("windows-v1",defaults.getVetting().getExpectedRetrievalGranularity());assertFalse(defaults.getVetting().isRequireHybridRetrieval());
    }
    @Test void structuralModeVersionStrictTypesCapsAndIdentityCannotBeWeakened() throws Exception {
        for(String mutation:Arrays.asList("mode","version","strictType","strictValue","embedCap","rerankCap","advertisedEmbedCap","advertisedRerankCap",
                "overflow","missingField","extraField","dense","rerank","structureHash","compatibilityHash","sourceUnitHash","selection","scope","tokenizerObserved")) {
            ObjectNode h=health(),window=(ObjectNode)h.path("retrieval").path("sourceWindows"),recipe=(ObjectNode)window.path("structuralRecipe");
            if("mode".equals(mutation))recipe.put("mode","windows-v1");
            if("version".equals(mutation))recipe.put("version","unbound-v2");
            if("strictType".equals(mutation))recipe.put("strict_context","true");
            if("strictValue".equals(mutation))recipe.put("strict_context",false);
            if("embedCap".equals(mutation))recipe.put("long_embedding_tokens",1024);
            if("rerankCap".equals(mutation))recipe.put("context_rerank_tokens",1024);
            if("advertisedEmbedCap".equals(mutation))window.put("longEmbeddingMaxTokens",512);
            if("advertisedRerankCap".equals(mutation))window.put("contextRerankMaxTokens",512);
            if("overflow".equals(mutation))recipe.put("long_embedding_tokens",new java.math.BigInteger("4294969344"));
            if("missingField".equals(mutation))recipe.remove("strict_context");
            if("extraField".equals(mutation))recipe.put("allow_unknown_sources",true);
            if("dense".equals(mutation))window.put("denseAggregation","max");
            if("rerank".equals(mutation))window.put("rerankAggregation","max");
            if("structureHash".equals(mutation))((ObjectNode)window.path("algorithms")).remove("structural_retrieval.py");
            if("compatibilityHash".equals(mutation))((ObjectNode)window.path("algorithms")).put("embedding_compatibility.py","unbound");
            if("sourceUnitHash".equals(mutation))((ObjectNode)window.path("algorithms")).remove("retrieval_source_units.py");
            if("selection".equals(mutation))window.remove("structuralSelection");
            if("scope".equals(mutation))((ObjectNode)window.path("structuralSelection")).put("scope","inferred_cross_page");
            if("tokenizerObserved".equals(mutation))window.put("tokenizerIdentityObserved",true);
            assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,expected().getVetting()),mutation);
        }
    }
    @Test void invalidConfiguredBudgetsAndImplicitNonStrictFallbackCannotPass() throws Exception {
        ObjectNode h=health();ConsenseProperties p=expected();p.getVetting().setExpectedStructuralEmbeddingMaxTokens(256);
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,p.getVetting()));
        p.getVetting().setExpectedStructuralEmbeddingMaxTokens(2048);p.getVetting().setExpectedStructuralRerankMaxTokens(8193);
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,p.getVetting()));
        p.getVetting().setExpectedStructuralRerankMaxTokens(2048);p.getVetting().setExpectedStructuralRerankStrict(false);
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,p.getVetting()));
        ((ObjectNode)h.path("retrieval").path("sourceWindows").path("structuralRecipe")).put("strict_context",false);
        VettingRetrievalHealth.validate(h,p.getVetting());
    }
    @Test void fullStructuralRecipeStillBindsExternalFingerprint() throws Exception {
        ObjectNode h=health();ConsenseProperties p=expected();String fingerprint=VettingRetrievalHealth.validate(h,p.getVetting()).fingerprint;
        p.getVetting().setExpectedRetrievalRecipeFingerprint(fingerprint);assertEquals(fingerprint,VettingRetrievalHealth.validate(h,p.getVetting()).fingerprint);
        ((ObjectNode)h.path("retrieval").path("sourceWindows").path("algorithms")).put("structural_retrieval.py",VettingCorpus.hash("different actual implementation"));
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,p.getVetting()));
    }
    @Test void baselineCannotMasqueradeAsDualOrCarryStructuralRecipe() throws Exception {
        JsonNode baseline=VettingStrictHybridTest.currentHealth();assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(baseline,expected().getVetting()));
        ObjectNode changed=baseline.deepCopy();((ObjectNode)changed.path("retrieval").path("sourceWindows")).set("structuralRecipe",health().path("retrieval").path("sourceWindows").path("structuralRecipe"));
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(changed,new ConsenseProperties().getVetting()));
    }
    @Test void actualPurePythonEpisodeFitsUnchangedClientAndSourceUnitReader() throws Exception {
        JsonNode wire=resource("current_structural_source_units.json");List<Chunk> corpus=new ArrayList<>();
        for(JsonNode parent:wire.path("parents"))corpus.add(JsonUtils.read(JsonUtils.write(parent),Chunk.class));
        HttpSupport http=mock(HttpSupport.class);ConsenseProperties p=expected();p.getVetting().setTopK(1);
        when(http.get(endsWith("/health"),anyLong())).thenReturn(JsonUtils.write(health()));
        when(http.postJson(endsWith("/index"),anyString(),anyLong())).thenReturn(JsonUtils.write(wire.path("indexReceipt")));
        when(http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenReturn(JsonUtils.write(wire.path("response")));
        VettingRetrievalClient client=new VettingRetrievalClient(http,p);client.index("p",corpus);
        List<Chunk> ranked=client.retrieve("p","register conditions","tender",corpus);
        assertEquals(1,ranked.size());assertEquals("head",ranked.get(0).getId());
        List<VettingRetrievalSourceUnits.Unit> units=VettingRetrievalSourceUnits.attached(ranked);
        assertEquals(1,units.size());assertEquals("complete_observed_unit",units.get(0).status);
        assertEquals(Arrays.asList("head","tail"),units.get(0).ids);
    }
    @Test void structuralRecipeChangeInvalidatesExistingClientReceiptBeforeQuery() throws Exception {
        JsonNode wire=resource("current_structural_source_units.json");List<Chunk> corpus=new ArrayList<>();
        for(JsonNode parent:wire.path("parents"))corpus.add(JsonUtils.read(JsonUtils.write(parent),Chunk.class));
        ObjectNode first=health(),changed=first.deepCopy();((ObjectNode)changed.path("retrieval").path("sourceWindows").path("algorithms")).put("structural_retrieval.py",VettingCorpus.hash("changed structure algorithm"));
        HttpSupport http=mock(HttpSupport.class);when(http.get(endsWith("/health"),anyLong())).thenReturn(JsonUtils.write(first),JsonUtils.write(changed));
        when(http.postJson(endsWith("/index"),anyString(),anyLong())).thenReturn(JsonUtils.write(wire.path("indexReceipt")));
        VettingRetrievalClient client=new VettingRetrievalClient(http,expected());client.index("p",corpus);
        assertThrows(IllegalStateException.class,()->client.retrieve("p","register","tender",corpus));
        verify(http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }
}
