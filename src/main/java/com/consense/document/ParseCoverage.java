package com.consense.document;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Physical extraction coverage. Completion does not verify OCR text or page relationships. */
@Data
public class ParseCoverage {
    private int totalPages;
    private int parsedPages;
    private int ocrPages;
    private List<Integer> blankPages = new ArrayList<>();
    private List<Integer> failedPages = new ArrayList<>();
    private boolean complete;
    /** Null for sources without OCR; no confidence threshold can establish verified text quality. */
    private String ocrQualityStatus;
    /** Physical pages known to contain successful, independently unverified OCR extraction. */
    private List<Integer> needsReviewPages = new ArrayList<>();
    /** Legacy OCR declarations may lack the individual physical-page identities. */
    private boolean ocrQualityPageScopeUnknown;
    private List<String> limitations = new ArrayList<>();
}
