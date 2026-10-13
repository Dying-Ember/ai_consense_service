package com.consense.vector;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentScopedVectorStoreTest {
    @Test void documentRestrictionPrecedesTopKSoAnExcludedPerfectMatchCannotStarveAllowedEvidence() {
        InMemoryVectorStore store=new InMemoryVectorStore(new ConsenseProperties.VectorCfg());
        store.upsert(Arrays.asList(point("forbidden","P","OLD",new float[]{1,0}),point("allowed","P","CURRENT",new float[]{.8f,.2f}),point("other-project","Q","CURRENT",new float[]{1,0})));
        List<VectorStore.SearchHit> found=store.search("P",Collections.singleton("CURRENT"),new float[]{1,0},1,0);
        assertEquals(1,found.size());assertEquals("allowed",found.get(0).getId());
        assertTrue(store.search("P",Collections.emptySet(),new float[]{1,0},1,0).isEmpty());
    }
    @Test void qdrantRequestCarriesBothProjectAndAllowedDocumentsBeforeLimit() {
        ConsenseProperties.VectorCfg config=new ConsenseProperties.VectorCfg();HttpSupport http=mock(HttpSupport.class);
        when(http.get(anyString(),anyLong())).thenReturn("{}");when(http.postJson(anyString(),anyString(),anyLong())).thenReturn("{\"result\":[]}");
        QdrantVectorStore store=new QdrantVectorStore(config,http);store.search("P",new HashSet<>(Arrays.asList("12","34")),new float[]{1,0},1,0);
        org.mockito.ArgumentCaptor<String> request=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(http).postJson(anyString(),request.capture(),anyLong());
        com.fasterxml.jackson.databind.JsonNode body=JsonUtils.parse(request.getValue());
        assertEquals(1,body.path("limit").asInt());assertEquals("P",body.path("filter").path("must").get(0).path("match").path("value").asText());
        assertEquals("documentId",body.path("filter").path("must").get(1).path("key").asText());
        assertEquals("12",body.path("filter").path("must").get(1).path("match").path("any").get(0).asText());
        assertEquals("34",body.path("filter").path("must").get(1).path("match").path("any").get(1).asText());
    }
    private VectorStore.VectorPoint point(String id,String project,String document,float[] vector){Map<String,Object> payload=new HashMap<>();payload.put("projectId",project);payload.put("documentId",document);return new VectorStore.VectorPoint(id,vector,payload);}
}
