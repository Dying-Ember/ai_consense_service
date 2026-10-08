package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.vetting.VettingService;
import com.consense.web.dto.DraftingDtos.UploadResultVO;
import com.consense.web.dto.VettingDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.validation.Valid;

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
                                              @RequestParam("files") List<MultipartFile> files,
                                              @RequestParam(required = false) String sourceRole) {
        return ApiResponse.ok(vettingService.uploadPackage(projectId, files, sourceRole));
    }

    @PostMapping("/run")
    public ApiResponse<RunResultVO> run(@PathVariable String projectId,
                                        @RequestParam(defaultValue = "zh-Hans") String lang) {
        return ApiResponse.ok(vettingService.run(projectId, lang));
    }

    @PostMapping("/runs")
    public ApiResponse<VettingJobVO> startRun(@PathVariable String projectId,
                                             @RequestParam(defaultValue = "zh-Hans") String lang) {
        return ApiResponse.ok(vettingService.startRun(projectId, lang));
    }

    @GetMapping("/runs/latest")
    public ApiResponse<VettingJobVO> latestRun(@PathVariable String projectId) {
        return ApiResponse.ok(vettingService.latestRun(projectId));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<VettingJobVO> getRun(@PathVariable String projectId, @PathVariable String id) {
        return ApiResponse.ok(vettingService.getRun(projectId, id));
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

    @PutMapping("/findings/{code}/review")
    public ApiResponse<FindingVO> updateReview(@PathVariable String projectId,
                                               @PathVariable String code,
                                               @Valid @RequestBody ReviewUpdateRequest request) {
        return ApiResponse.ok(vettingService.updateReview(projectId, code, request));
    }

    @GetMapping("/findings/{code}/evidence")
    public ApiResponse<EvidenceVO> evidence(@PathVariable String projectId,
                                            @PathVariable String code) {
        return ApiResponse.ok(vettingService.evidence(projectId, code));
    }

    @GetMapping("/sources/{documentId}/original")
    public ResponseEntity<FileSystemResource> original(@PathVariable String projectId,@PathVariable long documentId,
                                                       @RequestParam String sourceHash) {
        VettingService.OriginalSource source=vettingService.originalSource(projectId,documentId,sourceHash);
        boolean pdf=source.getFileName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
        return ResponseEntity.ok().contentLength(source.getSize())
                .contentType(pdf?MediaType.APPLICATION_PDF:MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,(pdf?"inline":"attachment")+"; filename*=UTF-8''"+urlEncode(source.getFileName()))
                .header("X-Source-Revision",source.getSourceRevision()).header("X-Content-Type-Options","nosniff")
                .body(new FileSystemResource(source.getPath()));
    }

    @GetMapping("/report.pdf")
    public ResponseEntity<ByteArrayResource> report(@PathVariable String projectId,
                                                    @RequestParam(defaultValue = "zh-Hans") String lang) {
        return download(projectId, "pdf", MediaType.APPLICATION_PDF, vettingService.exportPdf(projectId, lang));
    }

    @GetMapping("/report.docx")
    public ResponseEntity<ByteArrayResource> reportDocx(@PathVariable String projectId,
                                                      @RequestParam(defaultValue = "zh-Hans") String lang) {
        return download(projectId, "docx", MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                vettingService.exportDocx(projectId, lang));
    }

    @GetMapping("/report.json")
    public ResponseEntity<ByteArrayResource> reportJson(@PathVariable String projectId,
                                                      @RequestParam(defaultValue = "zh-Hans") String lang) {
        return download(projectId, "json", MediaType.APPLICATION_JSON, vettingService.exportJson(projectId, lang));
    }

    private ResponseEntity<ByteArrayResource> download(String projectId, String format, MediaType type, byte[] content) {
        String encoded = urlEncode(vettingService.reportFileName(projectId, format));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentLength(content.length)
                .contentType(type)
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
