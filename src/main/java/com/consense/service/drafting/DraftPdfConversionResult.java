package com.consense.service.drafting;

import java.util.*;

/** Frozen conversion bytes and manifest; successful conversion is not field-refresh certification. */
public final class DraftPdfConversionResult {
    private final byte[] pdf;
    private final Map<String,Object> manifest;
    DraftPdfConversionResult(byte[] pdf,Map<String,Object> manifest) {this.pdf=pdf.clone();this.manifest=Collections.unmodifiableMap(new LinkedHashMap<>(manifest));}
    public byte[] getPdfBytes() {return pdf.clone();}
    public String getDocxSha256() {return String.valueOf(manifest.get("docxSha256"));}
    public String getPdfSha256() {return String.valueOf(manifest.get("pdfSha256"));}
    public String getRendererVersion() {return String.valueOf(manifest.get("rendererVersion"));}
    public String getRenderProfileHash() {return String.valueOf(manifest.get("renderProfileHash"));}
    public String getPrivateProfileId() {return String.valueOf(manifest.get("privateProfileId"));}
    public String getFieldStatus() {return String.valueOf(manifest.get("fieldStatus"));}
    public Map<String,Object> getManifest() {return manifest;}
}
