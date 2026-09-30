package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.SkillService;
import com.consense.service.SkillService.SkillVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public ApiResponse<List<SkillVO>> list() {
        return ApiResponse.ok(skillService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<SkillVO> get(@PathVariable String id) {
        return ApiResponse.ok(skillService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<SkillVO> save(@PathVariable String id, @RequestBody SkillVO vo) {
        return ApiResponse.ok(skillService.save(id, vo));
    }

    @PostMapping("/{id}/reset")
    public ApiResponse<SkillVO> reset(@PathVariable String id) {
        return ApiResponse.ok(skillService.reset(id));
    }
}
