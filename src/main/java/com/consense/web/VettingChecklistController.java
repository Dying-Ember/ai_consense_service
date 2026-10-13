package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.vetting.VettingChecklistService;
import com.consense.web.dto.VettingChecklistDtos.ChecklistVO;
import com.consense.web.dto.VettingChecklistDtos.UpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/vetting/{projectId}/runs/{runId}/checklist")
@RequiredArgsConstructor
public class VettingChecklistController {
    private final VettingChecklistService checklistService;

    @GetMapping
    public ApiResponse<ChecklistVO> get(@PathVariable String projectId,@PathVariable String runId) {
        return ApiResponse.ok(checklistService.get(projectId,runId));
    }

    @PutMapping
    public ApiResponse<ChecklistVO> update(@PathVariable String projectId,@PathVariable String runId,
                                           @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(checklistService.update(projectId,runId,request));
    }
}
