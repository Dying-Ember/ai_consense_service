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

    @GetMapping("/sessions")
    public ApiResponse<List<SessionVO>> sessions(@PathVariable String projectId) {
        return ApiResponse.ok(adviceService.sessions(projectId));
    }

    @PostMapping("/sessions")
    public ApiResponse<SessionVO> createSession(@PathVariable String projectId, @RequestBody SessionRequest request) {
        return ApiResponse.ok(adviceService.createSession(projectId, request));
    }

    @GetMapping("/sources")
    public ApiResponse<List<AdviceSourceVO>> sources(@PathVariable String projectId,
                                                    @RequestParam(defaultValue = "fullset") String scope) {
        return ApiResponse.ok(adviceService.sources(projectId,scope));
    }

    @PostMapping("/index/rebuild")
    public ApiResponse<IndexStatusVO> rebuild(@PathVariable String projectId,
                                             @RequestParam(defaultValue = "fullset") String scope) {
        return ApiResponse.ok(adviceService.rebuildIndex(projectId,scope));
    }

    @GetMapping("/index/status")
    public ApiResponse<IndexStatusVO> status(@PathVariable String projectId,
                                            @RequestParam(defaultValue = "fullset") String scope) {
        return ApiResponse.ok(adviceService.status(projectId,scope));
    }

    @GetMapping("/messages")
    public ApiResponse<List<ChatMessageVO>> messages(@PathVariable String projectId,
                                                    @RequestParam(required = false) String sessionId) {
        return ApiResponse.ok(adviceService.history(projectId, sessionId));
    }

    @DeleteMapping("/messages")
    public ApiResponse<Void> clear(@PathVariable String projectId,
                                   @RequestParam(required = false) String sessionId) {
        adviceService.clearHistory(projectId, sessionId);
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
