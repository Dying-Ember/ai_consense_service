package com.consense.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.consense.common.LocalizedText;
import java.time.Instant;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public final class VettingChecklistDtos {
    private VettingChecklistDtos() { }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ChecklistVO {
        private String projectId;
        private String runId;
        /** Binding to immutable completed-run coverage, not a claim about semantic accuracy. */
        private String runSnapshotSha256;
        private boolean writable;
        private List<ItemVO> items;
        private String sourceSnapshotSha256;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ItemVO {
        private String id;
        private String section;
        private String clause;
        private String description;
        private int templatePage;
        private String reviewScope;
        private String scopeNote;
        private LocalizedText scopeNoteText;
        private String status;
        private String remarks;
        private Instant updatedAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateRequest {
        @NotBlank @Size(max=64)
        private String runSnapshotSha256;
        /** Updates only these rows; unrelated human counterchecks remain unchanged. */
        @NotNull @Size(min=1,max=100) @Valid
        private List<UpdateItem> items;
        private String expectedRunId;
        private String expectedSourceSnapshotSha256;
        public UpdateRequest(String binding,List<UpdateItem> items){this.runSnapshotSha256=binding;this.items=items;}
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateItem {
        @NotBlank @Size(max=64)
        private String id;
        @NotBlank @Pattern(regexp="unchecked|fulfilled|not_applicable")
        private String status;
        @Size(max=4000)
        private String remarks;
    }
}
