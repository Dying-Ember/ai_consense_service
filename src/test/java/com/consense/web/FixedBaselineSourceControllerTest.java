package com.consense.web;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.service.vetting.FixedBaselineRetrievalClient;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixedBaselineSourceControllerTest {
    @TempDir Path temporary;
    private JsonNode descriptor(Path file,String hash,long bytes) {
        return JsonUtils.mapper().createObjectNode().put("path",file.toString()).put("sha256",hash).put("bytes",bytes);
    }
    private static final String ABC_HASH="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test void servesOnlyAWhitelistedFrozenOriginalAndRejectsSameSizeMutation() throws Exception {
        Path original=temporary.resolve("original.pdf");Files.write(original,new byte[]{97,98,99});
        FixedBaselineRetrievalClient client=mock(FixedBaselineRetrievalClient.class);
        JsonNode manifest=JsonUtils.mapper().createObjectNode().set("originalSources",
                JsonUtils.mapper().createObjectNode().set("gccSource",descriptor(original,ABC_HASH,3)));
        when(client.frozenManifest()).thenReturn(manifest);
        FixedBaselineSourceController controller=new FixedBaselineSourceController(client);
        assertArrayEquals(new byte[]{97,98,99},controller.original(ABC_HASH).getBody().getByteArray());
        assertThrows(BizException.class,()->controller.original("/etc/passwd"));
        assertThrows(BizException.class,()->controller.original(String.join("",Collections.nCopies(64,"0"))));
        Files.write(original,new byte[]{100,101,102});
        assertThrows(BizException.class,()->controller.original(ABC_HASH));
    }

    @Test void tableUsesCatalogIdentityAndRetainsExactArtifactBytes() throws Exception {
        Path table=temporary.resolve("table.json");Files.write(table,new byte[]{97,98,99});
        FixedBaselineRetrievalClient client=mock(FixedBaselineRetrievalClient.class);
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("tableArtifact",JsonUtils.mapper().convertValue(descriptor(table,ABC_HASH,3),Map.class));
        when(client.readOnlyHitById("table:1")).thenReturn(new VectorStore.SearchHit("table:1",1,payload));
        when(client.readOnlyHitById("text:1")).thenReturn(new VectorStore.SearchHit("text:1",1,Collections.emptyMap()));
        FixedBaselineSourceController controller=new FixedBaselineSourceController(client);
        assertArrayEquals(new byte[]{97,98,99},controller.table("table:1").getBody().getByteArray());
        assertThrows(BizException.class,()->controller.table("text:1"));
        verify(client).readOnlyHitById("table:1");
    }

    @Test void disabledStatusDoesNotReadAFalseBaselineOrTriggerRetrieval() {
        FixedBaselineRetrievalClient client=mock(FixedBaselineRetrievalClient.class);
        Map<String,Object> state=new FixedBaselineSourceController(client).status().getData();
        assertEquals(false,state.get("configured"));assertEquals(false,state.get("retrievalLiveVerified"));
        verify(client,never()).frozenManifest();verify(client,never()).searchEvidence(anyString(),anyString(),anyInt());
    }
}
