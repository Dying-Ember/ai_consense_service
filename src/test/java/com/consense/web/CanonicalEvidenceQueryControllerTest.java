package com.consense.web;

import com.consense.service.sources.CanonicalEvidenceException;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP parameter identity and dispatch only; the existing real-service suites verify source/CAS gates. */
class CanonicalEvidenceQueryControllerTest {
    private static final String PROJECT="project-a";
    private static final long DOCUMENT=1L;
    private static final String PROJECTION="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER_PROJECTION="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String UNIT="GCC/physical-65/16.2";
    private static final String PREFIX="/api/evidence/"+PROJECT+"/documents/"+DOCUMENT+"/canonical/";
    private CanonicalEvidenceService evidence;
    private MockMvc http;

    @BeforeEach void prepare(){
        evidence=mock(CanonicalEvidenceService.class);
        http=MockMvcBuilders.standaloneSetup(new CanonicalEvidenceController(evidence)).build();
    }

    @Test void percentEncodedQueryKeepsTheWholeSourceUnitId() throws Exception {
        EvidencePointer pointer=new EvidencePointer();pointer.setProjectId(PROJECT);pointer.setDocumentId(DOCUMENT);
        pointer.setProjectionSha256(PROJECTION);pointer.setUnitId(UNIT);pointer.setModule("advisory");
        when(evidence.pointer(PROJECT,DOCUMENT,PROJECTION,UNIT,"advisory")).thenReturn(pointer);
        http.perform(get(URI.create(PREFIX+PROJECTION+"/units?unitId=GCC%2Fphysical-65%2F16.2&module=advisory")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.unitId").value(UNIT)).andExpect(jsonPath("$.data.projectionSha256").value(PROJECTION));
        verify(evidence).pointer(PROJECT,DOCUMENT,PROJECTION,UNIT,"advisory");verifyNoMoreInteractions(evidence);
    }

    @Test void queryDispatchRetainsTheModuleGateFailure() throws Exception {
        when(evidence.pointer(PROJECT,DOCUMENT,PROJECTION,UNIT,"drafting"))
                .thenThrow(new CanonicalEvidenceException(HttpStatus.FORBIDDEN,"CANONICAL_MODULE_NOT_ELIGIBLE"));
        http.perform(get(PREFIX+PROJECTION+"/units").param("unitId",UNIT).param("module","drafting"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(4300))
                .andExpect(jsonPath("$.message").value("CANONICAL_MODULE_NOT_ELIGIBLE"));
        verify(evidence).pointer(PROJECT,DOCUMENT,PROJECTION,UNIT,"drafting");verifyNoMoreInteractions(evidence);
    }

    @Test void queryDispatchRetainsTheChangedProjectionGateFailure() throws Exception {
        when(evidence.pointer(PROJECT,DOCUMENT,OTHER_PROJECTION,UNIT,"advisory"))
                .thenThrow(new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_REVISION_STALE"));
        http.perform(get(PREFIX+OTHER_PROJECTION+"/units").param("unitId",UNIT).param("module","advisory"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(4300))
                .andExpect(jsonPath("$.message").value("CANONICAL_REVISION_STALE"));
        verify(evidence).pointer(PROJECT,DOCUMENT,OTHER_PROJECTION,UNIT,"advisory");verifyNoMoreInteractions(evidence);
    }

    @Test void missingUnitIdDoesNotSelectADefaultUnit() throws Exception {
        http.perform(get(PREFIX+PROJECTION+"/units").param("module","advisory")).andExpect(status().isBadRequest());
        verifyNoInteractions(evidence);
    }

    @Test void missingModuleDoesNotSelectADefaultModule() throws Exception {
        http.perform(get(PREFIX+PROJECTION+"/units").param("unitId",UNIT)).andExpect(status().isBadRequest());
        verifyNoInteractions(evidence);
    }

    @Test void existingSingleSegmentRouteKeepsTheSameGate() throws Exception {
        EvidencePointer pointer=new EvidencePointer();pointer.setUnitId("unit-a");
        when(evidence.pointer(PROJECT,DOCUMENT,PROJECTION,"unit-a","advisory")).thenReturn(pointer);
        http.perform(get(PREFIX+PROJECTION+"/units/unit-a").param("module","advisory"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unitId").value("unit-a"));
        verify(evidence).pointer(PROJECT,DOCUMENT,PROJECTION,"unit-a","advisory");verifyNoMoreInteractions(evidence);
    }
}
