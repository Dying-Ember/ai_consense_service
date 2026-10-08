package com.consense.web;

import com.consense.service.vetting.VettingService;
import com.consense.web.dto.DraftingDtos.UploadResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VettingUploadRoleTest {
    @Test void multipartRoleQueryReachesTheService() throws Exception {
        VettingService service = mock(VettingService.class);
        when(service.uploadPackage(eq("p"), anyList(), eq("project_fact")))
                .thenReturn(new UploadResultVO(1, 1, 0, Collections.emptyList()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new VettingController(service)).build();
        mvc.perform(multipart("/api/vetting/p/package/upload").file(file()).param("sourceRole", "project_fact"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(service).uploadPackage(eq("p"), argThat(files -> files.size() == 1
                && "notes.txt".equals(files.get(0).getOriginalFilename())), eq("project_fact"));
    }

    @Test void legacyMultipartRequestRetainsAutomaticRoleInference() throws Exception {
        VettingService service = mock(VettingService.class);
        when(service.uploadPackage(eq("p"), anyList(), isNull()))
                .thenReturn(new UploadResultVO(1, 1, 0, Collections.emptyList()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new VettingController(service)).build();
        mvc.perform(multipart("/api/vetting/p/package/upload").file(file()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(service).uploadPackage(eq("p"), anyList(), isNull());
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("files", "notes.txt", "text/plain", "Project evidence".getBytes(StandardCharsets.UTF_8));
    }
}
