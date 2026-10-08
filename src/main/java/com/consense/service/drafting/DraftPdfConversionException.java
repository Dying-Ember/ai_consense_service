package com.consense.service.drafting;

/** A layout conversion failure; callers must not replace it with a plain-text renderer. */
public final class DraftPdfConversionException extends RuntimeException {
    public enum Code { NOT_CONFIGURED, INVALID_DOCX, FONT_CAPABILITY_MISSING, FONT_CAPABILITY_UNVERIFIED,
        CONVERSION_TIMEOUT, CONVERSION_FAILED, OUTPUT_MISSING, INVALID_PDF, OUTPUT_TOO_LARGE }
    private final Code code;
    public DraftPdfConversionException(Code code,String message) { super(message);this.code=code; }
    public DraftPdfConversionException(Code code,String message,Throwable cause) { super(message,cause);this.code=code; }
    public Code getCode() { return code; }
}
