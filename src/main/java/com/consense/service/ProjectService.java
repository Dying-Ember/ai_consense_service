package com.consense.service;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.repository.*;
import com.consense.web.dto.ProjectDtos.ProjectRequest;
import com.consense.web.dto.ProjectDtos.ProjectVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final DraftVariableRepository draftVariableRepository;
    private final DraftDocumentRepository draftDocumentRepository;
    private final VettingFindingRepository findingRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final EvidenceChunkRepository evidenceChunkRepository;

    public List<ProjectVO> list() {
        return projectRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    public Project require(String projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BizException(4004, "项目不存在: " + projectId));
    }

    public ProjectVO get(String projectId) {
        return toVO(require(projectId));
    }

    @Transactional
    public ProjectVO save(ProjectRequest request) {
        Project project = projectRepository.findById(request.getId()).orElseGet(Project::new);
        boolean isNew = project.getId() == null;
        project.setId(request.getId());
        project.setNameZhHans(request.getNameZhHans());
        project.setNameZhHant(blankToFallback(request.getNameZhHant(), request.getNameZhHans()));
        project.setNameEn(blankToFallback(request.getNameEn(), request.getNameZhHans()));
        project.setContractNo(request.getContractNo());
        project.setPackageRef(request.getPackageRef());
        project.setOutputReferenceFile(request.getOutputReferenceFile());
        project.setPages(request.getPages());
        project.setNttRange(request.getNttRange());
        project.setSctRange(request.getSctRange());
        project.setSccRange(request.getSccRange());
        project.setSpecification(request.getSpecification());
        if (isNew) {
            project.setCreatedAt(Instant.now());
            project.setTitleLinesJson(JsonUtils.write(Arrays.asList(
                    LocalizedText.same("CONTRACT PACKAGE"),
                    LocalizedText.same(request.getContractNo() == null ? "" : request.getContractNo()))));
        }
        project.setUpdatedAt(Instant.now());
        projectRepository.save(project);
        return toVO(project);
    }

    @Transactional
    public void delete(String projectId) {
        require(projectId);
        findingRepository.deleteByProjectId(projectId);
        evidenceChunkRepository.deleteByProjectId(projectId);
        chatMessageRepository.deleteByProjectId(projectId);
        draftDocumentRepository.deleteByProjectId(projectId);
        draftVariableRepository.deleteByProjectId(projectId);
        sourceDocumentRepository.deleteByProjectIdAndCategories(projectId, Arrays.asList(
                "STANDARD_TEMPLATE", "PROJECT_INPUT", "VETTING_PACKAGE", "VETTING_SUPPLEMENT"));
        projectRepository.deleteById(projectId);
        log.info("已删除项目 {}", projectId);
    }

    public ProjectVO toVO(Project project) {
        List<LocalizedText> titles = new ArrayList<>();
        if (!JsonUtils.isBlankText(project.getTitleLinesJson())) {
            try {
                titles = JsonUtils.readList(project.getTitleLinesJson(), LocalizedText.class);
            } catch (Exception e) {
                log.debug("解析 titleLines 失败: {}", e.getMessage());
            }
        }
        return new ProjectVO(project.getId(),
                LocalizedText.of(project.getNameZhHans(), project.getNameZhHant(), project.getNameEn()),
                project.getContractNo(),
                project.getPackageRef(),
                project.getOutputReferenceFile(),
                project.getPages(),
                project.getNttRange(),
                project.getSctRange(),
                project.getSccRange(),
                project.getSpecification(),
                titles);
    }

    private String blankToFallback(String value, String fallback) {
        return JsonUtils.isBlankText(value) ? fallback : value;
    }
}
