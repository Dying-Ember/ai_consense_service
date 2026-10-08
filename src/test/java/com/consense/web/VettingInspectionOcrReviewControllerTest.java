package com.consense.web;

import com.consense.service.vetting.*;
import com.consense.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.IOException;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VettingInspectionOcrReviewControllerTest {
    @Test void pageKeepsOriginalSourceAndShowsExplicitReviewFailure()throws Exception {
        VettingInspectionService service=mock(VettingInspectionService.class);VettingInspectionOcrReview reader=mock(VettingInspectionOcrReview.class);
        Map<String,Object> page=new LinkedHashMap<>();page.put("text","unchanged source");page.put("pageNo",3);page.put("blocks",Collections.emptyList());
        when(service.page("dataset","8",3)).thenReturn(page);Map<String,Object> unavailable=new LinkedHashMap<>();unavailable.put("status","unavailable");unavailable.put("unavailableReason","ocr_review_source_or_artifact_binding_invalid");
        when(reader.attach(eq("dataset"),eq("8"),eq(3),anyMap())).thenReturn(unavailable);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new VettingInspectionController(service,reader)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/vetting-inspection/documents/8/pages/3").param("datasetId","dataset")).andExpect(status().isOk()).andExpect(jsonPath("$.data.text").value("unchanged source")).andExpect(jsonPath("$.data.ocrReview.status").value("unavailable")).andExpect(jsonPath("$.data.ocrReview.unavailableReason").value("ocr_review_source_or_artifact_binding_invalid"));
        org.junit.jupiter.api.Assertions.assertFalse(page.containsKey("ocrReview"),"Source page map must not be mutated");
    }
    @Test void pngBridgeServesOnlyTheReaderValidatedBytesWithNosniff()throws Exception {
        VettingInspectionService service=mock(VettingInspectionService.class);VettingInspectionOcrReview reader=mock(VettingInspectionOcrReview.class);Map<String,Object> page=Collections.singletonMap("pageNo",3);byte[] bytes={(byte)137,80,78,71,13,10,26,10};
        when(service.page("dataset","8",3)).thenReturn(page);when(reader.image("dataset","8",3,"png-known",page)).thenReturn(bytes);
        MockMvcBuilders.standaloneSetup(new VettingInspectionController(service,reader)).setControllerAdvice(new GlobalExceptionHandler()).build().perform(get("/api/vetting-inspection/documents/8/pages/3/ocr-assets/png-known").param("datasetId","dataset")).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(content().bytes(bytes));
    }
    @Test void missingOrWrongAssetHasNoFallbackToArbitraryFile()throws Exception {
        VettingInspectionService service=mock(VettingInspectionService.class);VettingInspectionOcrReview reader=mock(VettingInspectionOcrReview.class);Map<String,Object> page=Collections.singletonMap("pageNo",3);when(service.page("dataset","8",3)).thenReturn(page);when(reader.image("dataset","8",3,"unknown",page)).thenThrow(new IOException("private path is not exposed"));
        MockMvcBuilders.standaloneSetup(new VettingInspectionController(service,reader)).setControllerAdvice(new GlobalExceptionHandler()).build().perform(get("/api/vetting-inspection/documents/8/pages/3/ocr-assets/unknown").param("datasetId","dataset")).andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print()).andExpect(status().isNotFound()).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(content().bytes(new byte[0]));
    }
    @Test void sourcePageIoFailureReturnsAnEmpty404WithRealGlobalAdvice()throws Exception {
        VettingInspectionService service=mock(VettingInspectionService.class);VettingInspectionOcrReview reader=mock(VettingInspectionOcrReview.class);
        when(service.page("dataset","8",3)).thenThrow(new IOException("private source metadata path must not be exposed"));
        MockMvcBuilders.standaloneSetup(new VettingInspectionController(service,reader)).setControllerAdvice(new GlobalExceptionHandler()).build().perform(get("/api/vetting-inspection/documents/8/pages/3/ocr-assets/png-known").param("datasetId","dataset")).andExpect(status().isNotFound()).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(content().bytes(new byte[0]));
        verifyNoInteractions(reader);
    }
}
