package com.consense.web;
import com.consense.ai.LlmProfiles;
import com.consense.common.ApiResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class LlmProfilesController {
    private final LlmProfiles profiles;
    @GetMapping("/api/system/llm-profiles")
    public ApiResponse<Map<String,Object>> profiles(){return ApiResponse.ok(profiles.metadata());}
}
