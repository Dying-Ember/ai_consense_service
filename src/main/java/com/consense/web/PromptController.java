package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.prompt.PromptService;
import com.consense.web.dto.PromptDtos.PromptSaveRequest;
import com.consense.web.dto.PromptDtos.PromptVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提示词配置接口：前端「提示词配置」页面据此读取 / 保存 / 恢复默认。
 * 改动立即对后续模型调用生效（无需重启）。
 */
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;

    @GetMapping
    public ApiResponse<List<PromptVO>> list() {
        return ApiResponse.ok(promptService.list());
    }

    @PutMapping("/{key}")
    public ApiResponse<PromptVO> save(@PathVariable String key, @RequestBody PromptSaveRequest request) {
        return ApiResponse.ok(promptService.save(key, request.getSystemText(), request.getUserTemplate()));
    }

    @PostMapping("/{key}/reset")
    public ApiResponse<PromptVO> reset(@PathVariable String key) {
        return ApiResponse.ok(promptService.reset(key));
    }
}
