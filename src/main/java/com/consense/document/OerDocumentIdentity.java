package com.consense.document;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/** Original-byte identity is distinct from a canonical text/structure sourceHash. */
@Data
public class OerDocumentIdentity {
    private String schemaVersion = "oer-source-identity-v1";
    private String sourceSha256, kind = "UNKNOWN", role, sourcePurpose = "unknown", identityStatus = "unknown", version;
    /** null means unresolved eligibility; [] means a positively identified exclusion. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> eligibleModules;
    private List<Evidence> identityEvidence = new ArrayList<>();
    private List<Candidate> candidates = new ArrayList<>();

    @Data public static class Evidence {
        private String basis, location, reason;
        public Evidence() {}
        public Evidence(String basis, String location, String reason) { this.basis = basis; this.location = location; this.reason = reason; }
    }
    @Data public static class Candidate {
        private String kind, sourcePurpose, reason;
        public Candidate() {}
        public Candidate(String kind, String sourcePurpose, String reason) { this.kind = kind; this.sourcePurpose = sourcePurpose; this.reason = reason; }
    }
}
