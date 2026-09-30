package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.vetting.VettingService;
import com.consense.web.dto.DraftingDtos.UploadResultVO;
import com.consense.web.dto.VettingDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/vetting/{projectId}")
@RequiredArgsConstructor
public class VettingController {

    private final VettingService vettingService;

    @GetMapping("/files")
    public ApiResponse<List<VettingFileVO>> files(@PathVariable String projectId) {
        return ApiResponse.ok(vettingService.listFiles(projectId));
    }

    @PostMapping("/package/upload")
    public ApiResponse<UploadResultVO> upload(@PathVariable String projectId,
                                              @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(vettingService.uploadPackage(projectId, files));
    }

    @PostMapping("/run")
    public ApiResponse<RunResultVO> run(@PathVariable String projectId,
                                        @RequestParam(defaultValue = "zh-Hans") String lang) {
        return ApiResponse.ok(vettingService.run(projectId, lang));
    }

    @GetMapping("/findings")
    public ApiResponse<List<FindingVO>> findings(@PathVariable String projectId,
                                                 @RequestParam(required = false) String search,
                                                 @RequestParam(required = false) String group,
                                                 @RequestParam(required = false) String scope,
                                                 @RequestParam(required = false) String fileKey,
                                                 @RequestParam(required = false) String page) {
        return ApiResponse.ok(vettingService.listFindings(projectId, search, group, scope, fileKey, page));
    }

    @GetMapping("/metrics")
    public ApiResponse<MetricsVO> metrics(@PathVariable String projectId) {
        return ApiResponse.ok(vettingService.metrics(projectId));
    }

    @PostMapping("/findings/{code}/status")
    public ApiResponse<FindingVO> updateStatus(@PathVariable String projectId,
                                               @PathVariable String code,
                                               @RequestParam String status) {
        return ApiResponse.ok(vettingService.updateStatus(projectId, code, status));
    }

    @GetMapping("/findings/{code}/evidence")
    public ApiResponse<EvidenceVO> evidence(@PathVariable String projectId,
                                            @PathVariable String code) {
        return ApiResponse.ok(vettingService.evidence(projectId, code));
    }

    @GetMapping("/report.pdf")
    public ResponseEntity<ByteArrayResource> report(@PathVariable String projectId,
                                                    @RequestParam(defaultValue = "zh-Hans") String lang) {
        byte[] content = vettingService.exportPdf(projectId, lang);
        String encoded = urlEncode(vettingService.pdfFileName(projectId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new ByteArrayResource(content));
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return value.replace("\"", "_");
        }
    }
}
