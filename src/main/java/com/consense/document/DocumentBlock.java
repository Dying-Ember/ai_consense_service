package com.consense.document;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** A source location, not an inferred Word page. PDF boxes use top-left normalized coordinates. */
@Data
public class DocumentBlock {
    private String id;
    private String kind;
    private String location;
    private Integer pageNo;
    private String text = "";
    private String originalText = "";
    private String deletedText = "";
    private String strikeText = "";
    private List<String> cells = new ArrayList<>();
    /** x, y, width, height, each relative to the physical page/image dimensions. */
    private double[] bbox;
    private Double confidence;
    private String source;
    private String paragraphStyle;
    /** Resolved OOXML style name; style IDs are often numeric and carry no semantics. */
    private String paragraphStyleName;
    /** Derivative parser identity; the original DOCX byte hash is recorded separately. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String wordStructureVersion;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<WordNumbering> wordNumbering = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<WordSymbol> wordSymbols = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> wordWarnings = new ArrayList<>();

    @Data public static class WordNumbering {
        private String location, numId, abstractNumId, format, levelText, label, suffix, resolutionStatus, reason, source;
        private Integer level;
        private Long value;
        private boolean appliedToEffectiveText;
    }
    @Data public static class WordSymbol {
        private String location, font, hexCode, decodedText, resolutionStatus, mappingSource;
        private boolean deleted, struck;
    }
}
