package com.consense.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Data;
import java.util.List;

/** Display views never substitute for the complete persisted sealed envelope. */
public final class CanonicalEvidenceDtos {
    private CanonicalEvidenceDtos(){}
    @Data public static class EvidencePointer {
        private String addressKind="canonical_adoption_unit";
        private String projectId,originalSourceSha256,javaParsedSourceHash,sourceRevisionSha256;
        private Long documentId;
        private String canonicalSourceHash,projectionSha256,projectionRevisionSha256;
        private String unitId,canonicalChunkId,module,bboxConvention="normalized_xyxy_original_page";
        /** The verified producer selects a position contract; Word never inherits the PDF frame. */
        private String projectionSchema="consense.canonical-projection.v1";
        private String producerKind,storageShape,originalGeometryStatus,visualReturnStatus;
        private String sourceRevisionKind,familyCompositionSha256,familyComponentSetSha256;
        @JsonSetter(nulls=Nulls.SKIP)
        private JsonNode sourceFamilyOriginRef,nativePdfSourceFamilyEvidence;
        private List<Integer> physicalPages;
        private JsonNode sourceFilename,sourceOrder,eligibleModules,parts,sourceSpans,assetRefs,relations,adoption,sourceQuality,unknowns;
        /** Native PDF capture/limitations stay declarations; this view never certifies referenced CAS or pixels. */
        @JsonSetter(nulls=Nulls.SKIP)
        private JsonNode nativePdfEvidence;
        /** An absent optional table group remains Java null through model-view transport. */
        @JsonSetter(nulls=Nulls.SKIP)
        private JsonNode tableGroup;
        /** Independently verified full original page assets, never provider model input aliases. */
        @JsonSetter(nulls=Nulls.SKIP)
        private JsonNode sourcePageRenderRefs,sourceRenderCaptureCompleteness,sourceRenderLimitations;
        private String sourceRenderEvidenceIdentitySha256;
        private boolean humanConfirmed=false;
    }
}
