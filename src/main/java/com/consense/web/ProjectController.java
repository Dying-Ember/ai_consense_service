package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.ProjectService;
import com.consense.web.dto.ProjectDtos.ProjectRequest;
import com.consense.web.dto.ProjectDtos.ProjectVO;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ApiResponse<List<ProjectVO>> list() {
        return ApiResponse.ok(projectService.list());
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectVO> get(@PathVariable String projectId) {
        return ApiResponse.ok(projectService.get(projectId));
    }

    @PostMapping
    public ApiResponse<ProjectVO> save(@Valid @RequestBody ProjectRequest request) {
        return ApiResponse.ok(projectService.save(request));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> delete(@PathVariable String projectId) {
        projectService.delete(projectId);
        return ApiResponse.ok();
    }
}
