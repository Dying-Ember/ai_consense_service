package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.sources.CanonicalEvidenceException;
import com.consense.service.sources.OerSourceRoutingRefreshService;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Existing reviewed source only. Requests cannot submit identity, roles, evidence, or a parser result. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/evidence/{projectId}/documents/{documentId}/routing")
public class OerSourceRoutingRefreshController {
    private final OerSourceRoutingRefreshService routing;
    @Data public static class Request {
        @NotNull @Pattern(regexp="[a-f0-9]{64}") private String expectedOriginalSha256;
        @NotNull @Pattern(regexp="[a-f0-9]{64}") private String expectedParsedSourceHash;
        @NotNull @Pattern(regexp="[a-f0-9]{64}") private String expectedRoutingSha256;
    }
    @PostMapping("/refresh")
    public ApiResponse<OerSourceRoutingRefreshService.Result> refresh(@PathVariable String projectId,@PathVariable Long documentId,
            @Valid @RequestBody Request request) {
        return ApiResponse.ok(routing.refresh(projectId,documentId,request.getExpectedOriginalSha256(),
                request.getExpectedParsedSourceHash(),request.getExpectedRoutingSha256()));
    }
    @ExceptionHandler(CanonicalEvidenceException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(CanonicalEvidenceException failure) {
        return ResponseEntity.status(failure.getStatus()).body(ApiResponse.fail(4300,failure.getMessage()));
    }
}
