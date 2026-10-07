package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.ocr.OcrClient;
import com.consense.service.drafting.DraftingService;
import com.consense.web.dto.DraftingDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/drafting/{projectId}")
@RequiredArgsConstructor
public class DraftingController {

    private final DraftingService draftingService;
    private final OcrClient ocrClient;

    @GetMapping("/catalog")
    public ApiResponse<Map<String,Object>> catalog(@PathVariable String projectId) {
        return ApiResponse.ok(draftingService.catalog(projectId));
    }

    @GetMapping("/plan")
    public ApiResponse<Map<String,Object>> plan(@PathVariable String projectId) {
        return ApiResponse.ok(draftingService.plan(projectId, null));
    }

    @PostMapping("/plan")
    public ApiResponse<Map<String,Object>> previewPlan(@PathVariable String projectId, @RequestBody PlanPreview body) {
        return ApiResponse.ok(draftingService.plan(projectId, body.getValues()));
    }

    @GetMapping("/templates")
    public ApiResponse<List<TemplateVO>> templates(@PathVariable String projectId) {
        return ApiResponse.ok(draftingService.listTemplates(projectId));
    }

    @PostMapping("/templates/upload")
    public ApiResponse<UploadResultVO> uploadTemplates(@PathVariable String projectId,
                                                       @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(draftingService.uploadTemplates(projectId, files));
    }

    @PostMapping("/templates/{fileKey}/replace")
    public ApiResponse<UploadResultVO> replaceTemplate(@PathVariable String projectId,
                                                       @PathVariable String fileKey,
                                                       @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(draftingService.replaceTemplate(projectId, fileKey, file));
    }

    @GetMapping("/inputs")
    public ApiResponse<List<EvidenceVO>> inputs(@PathVariable String projectId) {
        return ApiResponse.ok(draftingService.listInputs(projectId));
    }

    @PostMapping("/inputs/upload")
    public ApiResponse<UploadResultVO> uploadInputs(@PathVariable String projectId,
                                                    @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(draftingService.uploadInputs(projectId, files));
    }

    @DeleteMapping("/inputs/{id}")
    public ApiResponse<Void> deleteInput(@PathVariable String projectId,@PathVariable Long id) {
        draftingService.deleteInput(projectId,id);return ApiResponse.ok();
    }

    @GetMapping("/variables")
    public ApiResponse<List<VariableVO>> variables(@PathVariable String projectId) {
        return ApiResponse.ok(draftingService.listVariables(projectId));
    }

    @PostMapping("/variables/extract")
    public ApiResponse<List<VariableVO>> extract(@PathVariable String projectId) {
        return ApiResponse.ok(draftingService.extractVariables(projectId));
    }

    @GetMapping("/variables/extract-trace")
    public ApiResponse<ExtractTraceVO> extractTrace(@PathVariable String projectId) {
        return ApiResponse.ok(draftingService.lastExtractTrace(projectId));
    }

    @GetMapping("/variables/extract-traces")
    public ApiResponse<List<ExtractRunSummaryVO>> extractTraceHistory(@PathVariable String projectId,@RequestParam(defaultValue="10") int limit) {
        return ApiResponse.ok(draftingService.extractTraceHistory(projectId,limit));
    }

    @GetMapping("/variables/extract-traces/{runId}")
    public ApiResponse<ExtractTraceVO> extractTraceRun(@PathVariable String projectId,@PathVariable String runId) {
        return ApiResponse.ok(draftingService.extractTraceRun(projectId,runId));
    }

    @PutMapping("/variables/{key}")
    public ApiResponse<VariableVO> update(@PathVariable String projectId,
                                          @PathVariable String key,
                                          @RequestBody VariablePatch patch) {
        return ApiResponse.ok(draftingService.updateVariable(projectId, key, patch));
    }

    @PostMapping("/variables")
    public ApiResponse<VariableVO> create(@PathVariable String projectId,
                                          @RequestBody VariableCreate body) {
        return ApiResponse.ok(draftingService.createVariable(projectId, body));
    }

    @PostMapping("/variables/confirm-all")
    public ApiResponse<List<VariableVO>> confirmAll(@PathVariable String projectId,
                                                    @RequestParam(defaultValue = "INPUT") String scope,
                                                    @RequestParam(required = false) String fileKey) {
        return ApiResponse.ok(draftingService.confirmAll(projectId, scope, fileKey));
    }

    @GetMapping("/progress")
    public ApiResponse<ProgressVO> progress(@PathVariable String projectId) {
        return ApiResponse.ok(draftingService.progress(projectId));
    }

    @PostMapping("/generate")
    public ApiResponse<List<DraftDocumentVO>> generate(@PathVariable String projectId,
                                                       @RequestParam(defaultValue = "zh-Hans") String lang) {
        return ApiResponse.ok(draftingService.generate(projectId, lang));
    }

    @GetMapping("/documents")
    public ApiResponse<List<DraftDocumentVO>> documents(@PathVariable String projectId) {
        return ApiResponse.ok(draftingService.listDocuments(projectId));
    }

    @PutMapping("/documents/{fileKey}")
    public ApiResponse<DraftDocumentVO> updateDocument(@PathVariable String projectId,
                                                       @PathVariable String fileKey,
                                                       @RequestBody DocumentPatch patch) {
        return ApiResponse.ok(draftingService.updateDocument(projectId, fileKey, patch));
    }

    @GetMapping("/documents/{fileKey}/preview.pdf")
    public ResponseEntity<ByteArrayResource> previewPdf(@PathVariable String projectId,
                                                        @PathVariable String fileKey,@RequestParam(required=false) String revisionId) {
        byte[] bytes = draftingService.previewPdf(projectId, fileKey,revisionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + fileKey + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(new ByteArrayResource(bytes));
    }

    @GetMapping("/templates/{fileKey}/preview.pdf")
    public ResponseEntity<ByteArrayResource> previewTemplate(@PathVariable String projectId,
                                                             @PathVariable String fileKey,@RequestParam(required=false) String sourceSha256) {
        byte[] bytes = draftingService.previewTemplate(projectId, fileKey,sourceSha256);
        String fileName = draftingService.previewTemplateFileName(projectId, fileKey);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + urlEncode(fileName))
                .contentType(MediaType.APPLICATION_PDF)
                .body(new ByteArrayResource(bytes));
    }

    @GetMapping("/templates/{fileKey}/text")
    public ApiResponse<TemplateTextVO> getTemplateText(@PathVariable String projectId,
                                                       @PathVariable String fileKey) {
        return ApiResponse.ok(draftingService.getTemplateText(projectId, fileKey));
    }

    @GetMapping("/templates/{fileKey}/source")
    public ResponseEntity<ByteArrayResource> templateSource(@PathVariable String projectId,@PathVariable String fileKey) {
        DraftingService.TemplateSourceFile source=draftingService.templateSource(projectId,fileKey);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename*=UTF-8''"+urlEncode(source.getFileName()))
                .contentType(MediaType.parseMediaType(source.getContentType()))
                .body(new ByteArrayResource(source.getBytes()));
    }

    @GetMapping("/templates/{fileKey}/reading")
    public ApiResponse<TemplateReadingVO> templateReading(@PathVariable String projectId,@PathVariable String fileKey) {
        return ApiResponse.ok(draftingService.templateReading(projectId,fileKey));
    }

    @GetMapping("/templates/{fileKey}/bindings")
    public ApiResponse<DocumentBindingsVO> templateBindings(@PathVariable String projectId,@PathVariable String fileKey,@RequestParam(required=false) String sourceSha256) {
        return ApiResponse.ok(draftingService.templateBindings(projectId,fileKey,sourceSha256));
    }

    @GetMapping("/documents/{fileKey}/bindings")
    public ApiResponse<DocumentBindingsVO> documentBindings(@PathVariable String projectId,@PathVariable String fileKey,@RequestParam(required=false) String revisionId,@RequestParam(required=false) String docxSha256) {
        return ApiResponse.ok(draftingService.documentBindings(projectId,fileKey,revisionId,docxSha256));
    }

    @PutMapping("/templates/{fileKey}/text")
    public ApiResponse<TemplateTextVO> updateTemplateText(@PathVariable String projectId,
                                                          @PathVariable String fileKey,
                                                          @RequestBody TemplateTextVO body) {
        return ApiResponse.ok(draftingService.updateTemplateText(projectId, fileKey, body.getText()));
    }

    /**
     * 第 3 步「PDF 文本层为空时自动 OCR 增强」（后端兜底，主方案为前端 tesseract.js）：
     * 前端把单页 canvas 转 PNG 上传 → 后端调 PaddleOCR → 返回每行文字 + bbox。
     * 返回 bbox 是图像坐标系（与上传 PNG 同尺寸），由前端换算到 PDF.js viewport 坐标。
     */
    @PostMapping(value = "/templates/{fileKey}/ocr-page", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OcrPageVO> ocrPage(@PathVariable String projectId,
                                          @PathVariable String fileKey,
                                          @RequestParam("file") MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        OcrClient.OcrResult res = ocrClient.recognize(bytes);
        List<OcrLineVO> lines = new ArrayList<>();
        for (OcrClient.OcrLine ln : res.getLines()) {
            double[] b = ln.getBbox();
            lines.add(new OcrLineVO(
                    ln.getText(),
                    ln.getConfidence(),
                    b == null ? null : new double[]{b[0], b[1], b[2], b[3]}));
        }
        return ApiResponse.ok(new OcrPageVO(res.getText(), res.getConfidence(), lines, bytes.length));
    }

    @GetMapping("/documents/{fileKey}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String projectId,
                                                      @PathVariable String fileKey) {
        byte[] content = draftingService.download(projectId, fileKey);
        String fileName = draftingService.downloadFileName(projectId, fileKey);
        String encoded = urlEncode(fileName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(new ByteArrayResource(content));
    }

    @GetMapping("/documents/{fileKey}/export.{format}")
    public ResponseEntity<ByteArrayResource> export(@PathVariable String projectId, @PathVariable String fileKey, @PathVariable String format,@RequestParam(required=false) String revisionId) {
        if (!"pdf".equals(format) && !"docx".equals(format)) throw new com.consense.common.BizException(4007, "仅支持 Word 和 PDF");
        byte[] bytes = "pdf".equals(format) ? draftingService.previewPdf(projectId, fileKey,revisionId) : draftingService.exportWord(projectId, fileKey,revisionId);
        MediaType type = "pdf".equals(format) ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + urlEncode("ConSense_" + fileKey + "." + format))
                .contentType(type).body(new ByteArrayResource(bytes));
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return value.replace("\"", "_");
        }
    }
}
