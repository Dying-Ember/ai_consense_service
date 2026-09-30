package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.advice.AdviceService;
import com.consense.web.dto.AdviceDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/advice/{projectId}")
@RequiredArgsConstructor
public class AdviceController {

    private final AdviceService adviceService;

    @PostMapping("/index/rebuild")
    public ApiResponse<IndexStatusVO> rebuild(@PathVariable String projectId) {
        return ApiResponse.ok(adviceService.rebuildIndex(projectId));
    }

    @GetMapping("/index/status")
    public ApiResponse<IndexStatusVO> status(@PathVariable String projectId) {
        return ApiResponse.ok(adviceService.status(projectId));
    }

    @GetMapping("/messages")
    public ApiResponse<List<ChatMessageVO>> messages(@PathVariable String projectId) {
        return ApiResponse.ok(adviceService.history(projectId));
    }

    @DeleteMapping("/messages")
    public ApiResponse<Void> clear(@PathVariable String projectId) {
        adviceService.clearHistory(projectId);
        return ApiResponse.ok();
    }

    @PostMapping("/ask")
    public ApiResponse<AskResponseVO> ask(@PathVariable String projectId,
                                          @RequestBody AskRequest request) {
        return ApiResponse.ok(adviceService.ask(projectId, request));
    }

    @GetMapping("/quick-questions")
    public ApiResponse<List<QuickQuestionVO>> quickQuestions(@PathVariable String projectId) {
        return ApiResponse.ok(adviceService.quickQuestions(projectId));
    }
}
