package com.consense.ai;

import com.consense.common.BizException;

/** Fixed diagnostics only; raw model/source text belongs to the existing response probe. */
public final class StructuredOutputValidationException extends BizException {
    public enum Reason {
        INVALID_SCHEMA,
        UNSUPPORTED_SCHEMA,
        INVALID_JSON,
        SCHEMA_MISMATCH,
        ELEMENT_MAPPING_FAILED
    }

    private final Reason reason;

    public StructuredOutputValidationException(Reason reason) {
        super(5002, message(reason));
        this.reason = reason;
    }

    public Reason getReason() { return reason; }

    public String getFailureKind() { return "structured_output_invalid"; }

    private static String message(Reason reason) {
        if (reason == null) throw new IllegalArgumentException("A fixed validation reason is required");
        switch (reason) {
            case INVALID_SCHEMA: return "Structured output schema is invalid for the supported subset.";
            case UNSUPPORTED_SCHEMA: return "Structured output schema uses an unsupported keyword, type or keyword form.";
            case INVALID_JSON: return "Structured output must be one complete JSON value without duplicate keys or surrounding text.";
            case SCHEMA_MISMATCH: return "Structured output does not conform to the supplied schema and flat-array contract.";
            case ELEMENT_MAPPING_FAILED: return "Validated structured output cannot be mapped to the requested element type.";
            default: throw new IllegalArgumentException("Unknown validation reason");
        }
    }
}
