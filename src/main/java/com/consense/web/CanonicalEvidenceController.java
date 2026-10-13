package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.sources.*;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Read-only business seam. No public endpoint accepts a snapshot, adoption decision or backend URL. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/evidence/{projectId}/documents/{documentId}/canonical/{projectionSha256}")
public class CanonicalEvidenceController {
    private final CanonicalEvidenceService evidence;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CanonicalSourcePageService sourcePages;
    @GetMapping("/units")
    public ApiResponse<EvidencePointer> pointerByQuery(@PathVariable String projectId,@PathVariable Long documentId,
            @PathVariable String projectionSha256,@RequestParam String unitId,@RequestParam String module){
        return ApiResponse.ok(sourcePages==null?evidence.pointer(projectId,documentId,projectionSha256,unitId,module):sourcePages.pointer(projectId,documentId,projectionSha256,unitId,module));
    }
    @GetMapping("/units/{unitId}")
    public ApiResponse<EvidencePointer> pointer(@PathVariable String projectId,@PathVariable Long documentId,
            @PathVariable String projectionSha256,@PathVariable String unitId,@RequestParam String module){
        return ApiResponse.ok(sourcePages==null?evidence.pointer(projectId,documentId,projectionSha256,unitId,module):sourcePages.pointer(projectId,documentId,projectionSha256,unitId,module));
    }
    @GetMapping("/assets/{assetId}")
    public ResponseEntity<byte[]> asset(@PathVariable String projectId,@PathVariable Long documentId,
            @PathVariable String projectionSha256,@PathVariable String assetId,@RequestParam String module){
        CanonicalEvidenceBackend.Reply reply=evidence.asset(projectId,documentId,projectionSha256,assetId,module);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(reply.contentType)).contentLength(reply.bytes.length)
                .header(HttpHeaders.ETAG,reply.etag).header(HttpHeaders.CACHE_CONTROL,"private, no-cache")
                .header("X-Content-Type-Options","nosniff").header("X-Source-Sha256",reply.sourceSha256)
                .header("X-Projection-Sha256",reply.projectionSha256).body(reply.bytes);
    }
    @GetMapping("/source-pages/{assetId}")
    public ResponseEntity<byte[]> sourcePage(@PathVariable String projectId,@PathVariable Long documentId,
            @PathVariable String projectionSha256,@PathVariable String assetId,@RequestParam String unitId,@RequestParam String evidence,@RequestParam String module){
        if(sourcePages==null)throw new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"CANONICAL_SOURCE_PAGE_UNAVAILABLE");
        CanonicalEvidenceBackend.Reply reply=sourcePages.asset(projectId,documentId,projectionSha256,unitId,assetId,evidence,module);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(reply.contentType)).contentLength(reply.bytes.length)
            .header(HttpHeaders.ETAG,reply.etag).header("X-Source-Sha256",reply.sourceSha256).header("X-Projection-Sha256",reply.projectionSha256)
            .header("X-Source-Revision-Sha256",reply.sourceRevisionSha256)
            .header("X-Evidence-Identity-Sha256",reply.evidenceIdentitySha256).header("X-Content-Type-Options","nosniff").body(reply.bytes);
    }
    @ExceptionHandler(CanonicalEvidenceException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(CanonicalEvidenceException error){return ResponseEntity.status(error.getStatus()).body(ApiResponse.fail(4300,error.getMessage()));}
}
