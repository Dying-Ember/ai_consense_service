package com.consense.web;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingService;
import com.consense.web.dto.VettingDtos.FindingVO;
import com.consense.web.dto.VettingDtos.ReviewUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VettingReviewControllerTest {
    @Test
    void humanReviewRequestAndResponseKeepRawTextAndUseServerOwnedTimestamp() throws Exception {
        VettingService service=mock(VettingService.class);
        String remarks="  项目团队回复 😀\r\nPlease retain spaces.  ",action="  Draft amendment reviewed.\nNot issued.  ";
        Instant saved=Instant.parse("2026-10-02T03:30:00.123Z");
        when(service.updateReview(eq("p"),eq("VT-001"),any(ReviewUpdateRequest.class))).thenAnswer(call -> {
            ReviewUpdateRequest request=call.getArgument(2);
            assertEquals(remarks,request.getReviewRemarks());assertEquals(action,request.getActionTaken());assertFalse(request.getAddendumRequired());
            FindingVO response=new FindingVO();response.setCode("VT-001");response.setStatus("Open");
            response.setReviewRemarks(request.getReviewRemarks());response.setActionTaken(request.getActionTaken());
            response.setAddendumRequired(request.getAddendumRequired());response.setReviewUpdatedAt(saved);
            return response;
        });
        MockMvc mvc=mvc(service);
        Map<String,Object> body=new LinkedHashMap<>();body.put("reviewRemarks",remarks);body.put("actionTaken",action);body.put("addendumRequired",false);
        body.put("reviewUpdatedAt","2000-01-01T00:00:00Z");
        mvc.perform(put("/api/vetting/p/findings/VT-001/review").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reviewRemarks").value(remarks)).andExpect(jsonPath("$.data.actionTaken").value(action))
                .andExpect(jsonPath("$.data.addendumRequired").value(false)).andExpect(jsonPath("$.data.status").value("Open"))
                .andExpect(jsonPath("$.data.reviewUpdatedAt").value(saved.toString()));
        verify(service).updateReview(eq("p"),eq("VT-001"),any(ReviewUpdateRequest.class));
        verifyNoMoreInteractions(service);
    }

    @Test
    void emptyObjectReachesTheServiceAsAFullNullableReplacement() throws Exception {
        VettingService service=mock(VettingService.class);
        when(service.updateReview(eq("p"),eq("VT-001"),any(ReviewUpdateRequest.class))).thenReturn(new FindingVO());
        MockMvc mvc=mvc(service);
        mvc.perform(put("/api/vetting/p/findings/VT-001/review").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(service).updateReview(eq("p"),eq("VT-001"),argThat(request -> request.getReviewRemarks()==null
                && request.getActionTaken()==null && request.getAddendumRequired()==null));
    }

    @Test
    void absentNullMalformedAndOversizedBodiesCannotCallTheService() throws Exception {
        VettingService service=mock(VettingService.class);
        MockMvc mvc=mvc(service);
        mvc.perform(put("/api/vetting/p/findings/VT-001/review").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/vetting/p/findings/VT-001/review").contentType(MediaType.APPLICATION_JSON).content("null")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/vetting/p/findings/VT-001/review").contentType(MediaType.APPLICATION_JSON).content("{\"addendumRequired\":\"unknown\"}"))
                .andExpect(status().isBadRequest());
        String oversized=String.join("",Collections.nCopies(2000,"😀"))+"x";
        Map<String,Object> body=new LinkedHashMap<>();body.put("reviewRemarks",oversized);body.put("actionTaken","Must not partially save");
        mvc.perform(put("/api/vetting/p/findings/VT-001/review").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body)))
                .andExpect(status().isBadRequest());
        body.clear();body.put("reviewRemarks","Must not partially save");body.put("actionTaken",oversized);
        mvc.perform(put("/api/vetting/p/findings/VT-001/review").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static MockMvc mvc(VettingService service) {
        // Match the application's ISO Instant serialization rather than standalone MVC defaults.
        return MockMvcBuilders.standaloneSetup(new VettingController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JsonUtils.mapper().copy())).build();
    }
}
