package com.consense.document;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** Original PDF operators and widgets, never a claim of visually verified text. */
@Data
public class PdfPageEvidence {
    private String schemaVersion = "pdf-native-evidence-v1";
    private int pageNo, rotation;
    private double[] cropBox;
    private double[] mediaBox;
    private double userUnit;
    private String coordinateSpace = "normalized_rendered_crop_top_left";
    private List<PdfGlyph> glyphs = new ArrayList<>();
    private List<PdfFormField> formFields = new ArrayList<>();
    private List<DocumentBlock> nativeBlocks = new ArrayList<>();
    private List<String> limitations = new ArrayList<>();

    @Data public static class PdfGlyph {
        private String id, unicode, fontName, visibilityStatus;
        private String coordinateSpace = "normalized_rendered_crop_top_left";
        /** A ligature/cluster may represent multiple Unicode characters with one glyph box. */
        private int[] characterCodes;
        private int renderingMode;
        private double fillAlpha, strokeAlpha;
        private double[] bbox, textMatrix;
        /** Actual PDFBox operator/text-state values; none establish raster visibility. */
        private double[] currentTransformationMatrix, pdfboxPosition, clippingBoundsPdf;
        private double[] fillColorComponents, strokeColorComponents;
        private String fillColorSpace, strokeColorSpace, fontSubtype;
        private Boolean fontEmbedded;
        private double fontSize, fontSizeInPt, xScale, yScale, widthOfSpace, direction;
        private double textStateFontSize, characterSpacing, wordSpacing, horizontalScaling, leading, rise, lineWidth;
        private String effectiveVisibilityStatus = "unknown_not_raster_verified";
        private List<String> limitations = new ArrayList<>();
        private boolean eligibleForNativeText;
    }

    @Data public static class PdfFormField {
        private String id, fieldName, fieldType, fieldValue, appearanceValue, appearanceState;
        private String visibilityStatus, valueStatus;
        private Integer pageNo;
        private Integer annotationFlags;
        private double[] bbox, rectanglePdf, appearanceBbox, appearanceMatrix;
        private boolean needAppearances, appearancePresent, conflict;
        private String appearanceRawStreamBytesBase64, appearanceDecodedStreamBytesBase64;
        private String appearanceStreamObservationStatus = "not_present";
        private String appearanceGlyphObservationStatus = "not_present";
        private List<PdfGlyph> appearanceGlyphs = new ArrayList<>();
        private List<String> limitations = new ArrayList<>();
    }
}
