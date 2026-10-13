package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Complete selected groups; geometry/audit/raw fields stay out of the model prompt. */
public final class FixedBaselineModelMaterial {
    private FixedBaselineModelMaterial() {}
    public static String render(List<FixedReferenceEvidence> evidence, int maxCharacters) {
        if (maxCharacters <= 0) throw new IllegalArgumentException("fixed_reference_budget_invalid");
        ObjectNode packet = JsonUtils.mapper().createObjectNode();
        packet.put("basisScope", "reference_only"); packet.put("role", "standard");
        packet.put("projectApplicability", "unconfirmed_requires_real_project_adoption");
        packet.put("quality", "development_baseline_known_gaps_and_unknowns_retained");
        ArrayNode rows = packet.putArray("references");
        Set<String> ids = new HashSet<>();
        for (FixedReferenceEvidence e : evidence) {
            if (!ids.add(e.getId())) throw new IllegalStateException("fixed_reference_duplicate_selected_parent");
        }
        for (FixedReferenceEvidence e : evidence) {
            JsonNode unit = e.getSourceUnit();
            if (unit != null) for (JsonNode id : unit.path("requiredMemberIds"))
                if (!ids.contains(id.asText())) throw new IllegalStateException("fixed_reference_incomplete_selected_group");
            JsonNode p = e.getPayload(); ObjectNode row = rows.addObject();
            row.put("id", e.getId()); row.put("file", e.getFileLabel()); row.put("family", e.getSourceFamily());
            for (String key : Arrays.asList("documentId", "pageNo", "originalPageNos", "anchor", "clauseId", "originalSourceSha256", "sourceHash", "qualifiersComplete", "semanticScopeVerified", "legalScopeAccepted", "qualityAdopted", "observedNodeKind"))
                if (p.has(key)) row.set(key, p.get(key));
            row.put("sourceBlockComplete", "unknown"); row.put("sourceQualityAccepted", false);
            if (unit != null) {
                ObjectNode group = row.putObject("observedGroup");
                for (String key : Arrays.asList("unitId", "kind", "status", "requiredMemberIds", "originIds", "qualifiersComplete", "semanticScopeVerified"))
                    if (unit.has(key)) group.set(key, unit.get(key));
            }
            JsonNode artifact = e.getTableArtifact();
            if (artifact != null) {
                ObjectNode table = row.putObject("completeObservedTable");
                table.put("semanticOwner", "unknown"); table.put("footnoteOwner", "unknown");
                table.put("crossPageGroup", "unknown"); table.put("trueBlankAccepted", "unknown");
                if (artifact.has("observedCells")) {
                    ArrayNode cells = table.putArray("cells");
                    for (JsonNode cell : artifact.path("observedCells")) {
                        ObjectNode c = cells.addObject();
                        for (String key : Arrays.asList("row", "column", "rowSpan", "columnSpan", "text"))
                            if (cell.has(key)) c.set(key, cell.get(key));
                    }
                    table.set("declaredRows", artifact.path("declaredRows"));
                    table.set("declaredColumns", artifact.path("declaredColumns"));
                } else if (artifact.path("table").path("extractedMatrix").isArray())
                    table.set("matrix", artifact.path("table").path("extractedMatrix"));
                else throw new IllegalStateException("fixed_reference_table_material_missing");
            } else row.put("content", e.getContent());
        }
        String json = JsonUtils.write(packet);
        if (json.length() > maxCharacters) throw new IllegalStateException("fixed_reference_complete_groups_exceed_budget:" + json.length() + ":" + maxCharacters);
        return json;
    }
}
