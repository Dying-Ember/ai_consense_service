package com.consense.web;

import com.consense.service.vetting.VettingInspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VettingInspectionControllerTest {
    private Map<String,Object> data(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    @Test void fullNativeChunkAndWindowTreesReachTheEnvelopeWithoutExcerpting() throws Exception {
        VettingInspectionService service=mock(VettingInspectionService.class);
        Map<String,Object> slice=data("cellSlices",Arrays.asList(data("text","原生长单元格")));
        Map<String,Object> part=data("startOffset",1400,"endOffset",1500,"tableSlice",slice);
        Map<String,Object> chunk=data("id","fixture-parent","content","全文 mixed 🧭","parts",Arrays.asList(part),"nativeRows",Arrays.asList(data("cells",Arrays.asList("left","right"))));
        when(service.chunk("fixture-dataset","fixture-parent")).thenReturn(data("chunk",chunk,"windows",Arrays.asList(data("id","fixture-window","parentId","fixture-parent","content","全文 mixed 🧭")),"vectorStatus","saved_persisted_metadata"));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new VettingInspectionController(service)).build();
        mvc.perform(get("/api/vetting-inspection/chunks/fixture-parent").param("datasetId","fixture-dataset")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.chunk.content").value("全文 mixed 🧭")).andExpect(jsonPath("$.data.chunk.parts[0].tableSlice.cellSlices[0].text").value("原生长单元格")).andExpect(jsonPath("$.data.windows[0].parentId").value("fixture-parent"));
        verify(service).chunk("fixture-dataset","fixture-parent");verifyNoMoreInteractions(service);
    }
    @Test void missingSentContextIsUnavailableRatherThanAnEmptySuccessfulDispatch() throws Exception {
        VettingInspectionService service=mock(VettingInspectionService.class);when(service.task("fixture-run","7","sent",2,20)).thenReturn(data("items",Collections.emptyList(),"total",0,"status","unavailable","unavailableReason","generation_context_not_recorded"));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new VettingInspectionController(service)).build();mvc.perform(get("/api/vetting-inspection/runs/fixture-run/tasks/7").param("stage","sent").param("page","2").param("pageSize","20")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("unavailable")).andExpect(jsonPath("$.data.unavailableReason").value("generation_context_not_recorded"));verify(service).task("fixture-run","7","sent",2,20);
    }
    @Test void filteringPreservesDatasetAndDocumentIdentityAndMissingDatasetIsBadRequest() throws Exception {
        VettingInspectionService service=mock(VettingInspectionService.class);when(service.chunks("fixture-dataset",3,15,"query","9","tender")).thenReturn(data("items",Collections.emptyList(),"total",0));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new VettingInspectionController(service)).build();mvc.perform(get("/api/vetting-inspection/chunks").param("datasetId","fixture-dataset").param("page","3").param("pageSize","15").param("q","query").param("documentId","9").param("role","tender")).andExpect(status().isOk());verify(service).chunks("fixture-dataset",3,15,"query","9","tender");mvc.perform(get("/api/vetting-inspection/chunks")).andExpect(status().isBadRequest());
    }
    @Test void allInspectionMutationsAreRejectedWithoutCallingTheService() throws Exception {
        VettingInspectionService service=mock(VettingInspectionService.class);MockMvc mvc=MockMvcBuilders.standaloneSetup(new VettingInspectionController(service)).build();
        mvc.perform(post("/api/vetting-inspection/catalog")).andExpect(status().isMethodNotAllowed());mvc.perform(put("/api/vetting-inspection/chunks/a")).andExpect(status().isMethodNotAllowed());mvc.perform(delete("/api/vetting-inspection/documents/a/pages")).andExpect(status().isMethodNotAllowed());verifyNoInteractions(service);
    }
}
