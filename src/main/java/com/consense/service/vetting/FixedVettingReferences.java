package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.vector.VectorStore.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.stream.Collectors;

/** A topic-local citation catalog. Fixed standards never become project documents or canonical evidence. */
final class FixedVettingReferences {
    static final String SOURCE_CLASS = "fixed_development_baseline_standard";
    private final FixedBaselineRetrievalClient client;
    private final Map<String, SearchHit> hits = new LinkedHashMap<>();
    private final Map<String, String> views = new LinkedHashMap<>();

    private FixedVettingReferences(FixedBaselineRetrievalClient client, List<SearchHit> selected) {
        this.client = client;
        for (SearchHit hit : selected) {
            FixedReferenceEvidence evidence = client.evidence(hit);
            JsonNode payload = evidence.getPayload();
            Chunk view = JsonUtils.read(JsonUtils.write(payload), Chunk.class);
            if (!Objects.equals(hit.getId(), view.getId()) || !"standard".equals(view.getRole())
                    || view.getCanonicalEvidence() != null || view.getCanonicalPayload() != null)
                throw new IllegalArgumentException("Fixed reference must retain its own standard identity");
            view.setSourceClass(SOURCE_CLASS);
            view.setSourcePurpose("fixed_reference");
            if (hits.putIfAbsent(view.getId(), hit) != null)
                throw new IllegalArgumentException("Duplicate fixed reference ID");
            views.put(view.getId(), JsonUtils.write(view));
        }
    }

    static FixedVettingReferences empty() { return new FixedVettingReferences(null, Collections.emptyList()); }
    static FixedVettingReferences retrieve(FixedBaselineRetrievalClient client, String topic, int limit) {
        return client == null || !client.enabled() ? empty()
                : new FixedVettingReferences(client, client.search("vetting", topic, limit));
    }
    boolean isEmpty() { return hits.isEmpty(); }
    JsonNode pointer(String id) {SearchHit hit=hits.get(id);if(hit==null)throw new IllegalArgumentException("Fixed reference is outside this packet catalog");return client.evidence(hit).pointer();}
    static boolean isFixed(Chunk chunk) { return chunk != null && SOURCE_CLASS.equals(chunk.getSourceClass()); }

    List<Chunk> chunks() {
        return views.values().stream().map(json -> JsonUtils.read(json, Chunk.class)).collect(Collectors.toList());
    }
    List<Chunk> topicCorpus(List<Chunk> project) {
        List<Chunk> result = new ArrayList<>(project);
        Set<String> ids = project.stream().map(Chunk::getId).collect(Collectors.toSet());
        for (Chunk fixed : chunks()) {
            if (!ids.add(fixed.getId())) throw new IllegalArgumentException("Fixed and project citation IDs collide");
            result.add(fixed);
        }
        return result;
    }
    /** Preserve complete observed groups without conferring an adopted-clause or literal-origin status. */
    void admit(VettingContextBuilder.Selection selection, int maximumCharacters) {
        Map<String,List<String>> groups = new LinkedHashMap<>();
        for (SearchHit hit : hits.values()) {
            JsonNode unit = client.evidence(hit).getSourceUnit();
            List<String> required = new ArrayList<>();
            if (unit != null) for (JsonNode id : unit.path("requiredMemberIds")) required.add(id.asText());
            if (required.isEmpty()) required.add(hit.getId());
            if (!hits.keySet().containsAll(required)) throw new IllegalArgumentException("Fixed retrieved group is incomplete");
            String key = unit == null ? hit.getId() : unit.path("unitId").asText(hit.getId());
            List<String> prior = groups.putIfAbsent(key, required);
            if (prior != null && !prior.equals(required)) throw new IllegalArgumentException("Fixed observed group member identities differ");
        }
        Set<String> present = selection.getChunks().stream().map(Chunk::getId).collect(Collectors.toSet());
        boolean tenderPresent = selection.getChunks().stream().anyMatch(c -> "tender".equals(c.getRole()));
        for (Map.Entry<String,List<String>> group : groups.entrySet()) {
            VettingContextBuilder.GroupTrace trace = new VettingContextBuilder.GroupTrace();
            trace.setId("fixed-reference:"+group.getKey());trace.setRelation("fixed_reference_observed_group");
            trace.setRelationStrength("retrieved_reference_project_adoption_unconfirmed");
            trace.setContextIds(new ArrayList<>(group.getValue()));trace.setScopeCoverage("complete_saved_observed_reference_only");
            selection.getGroups().add(trace);
            long added = 0;
            for (String id : group.getValue()) if (!present.contains(id)) added += JsonUtils.read(views.get(id),Chunk.class).getContent().length();
            if (!tenderPresent || added + (long)selection.getContentChars() > maximumCharacters) {
                trace.setStatus(tenderPresent ? "dropped_budget" : "not_submitted_without_tender");
                selection.getDroppedIds().addAll(group.getValue());continue;
            }
            for (String id : group.getValue()) if (present.add(id)) selection.getChunks().add(JsonUtils.read(views.get(id),Chunk.class));
            selection.setContentChars((int)(selection.getContentChars()+added));
            trace.setIncrementalChars((int)added);trace.setStatus("selected");trace.setFullySubmitted(true);
            selection.getDroppedIds().removeAll(group.getValue());
        }
    }
    private List<SearchHit> selectedHits(List<Chunk> selected) {
        List<SearchHit> result = new ArrayList<>();
        for (Chunk chunk : selected) {
            if (!isFixed(chunk)) {
                if (hits.containsKey(chunk.getId())) throw new IllegalArgumentException("Fixed reference lost its source class");
                continue;
            }
            if (!Objects.equals(views.get(chunk.getId()), JsonUtils.write(chunk)))
                throw new IllegalArgumentException("Fixed reference view changed or is outside this topic catalog");
            result.add(hits.get(chunk.getId()));
        }
        return result;
    }
    Map<String, Map<String, Object>> modelExcerpts(List<Chunk> selected) {
        List<SearchHit> chosen = selectedHits(selected);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (chosen.isEmpty()) return result;
        // The shared projector checks complete parent/table groups; never serialize raw sourceUnits into the prompt.
        JsonNode material = JsonUtils.parse(client.toModelMaterial(chosen, Integer.MAX_VALUE));
        JsonNode references = material.path("references");
        if (!references.isArray()) throw new IllegalArgumentException("Fixed reference model material is missing");
        for (JsonNode reference : references) {
            String id = reference.path("id").asText();
            if (!hits.containsKey(id) || result.putIfAbsent(id, JsonUtils.readMap(JsonUtils.write(reference))) != null)
                throw new IllegalArgumentException("Fixed model reference IDs differ from the topic catalog");
        }
        Set<String> expected = chosen.stream().map(SearchHit::getId).collect(Collectors.toSet());
        if (!result.keySet().equals(expected)) throw new IllegalArgumentException("Fixed reference group was not returned completely");
        for (Map<String, Object> excerpt : result.values()) {
            excerpt.put("role", "standard");
            excerpt.put("evidenceBackend", "fixed_development_baseline");
            excerpt.put("projectApplicability", "unconfirmed_requires_real_project_adoption");
            excerpt.put("qualifiersComplete", "unknown");
            excerpt.put("semanticScopeVerified", false);
            excerpt.put("sourceQualityAccepted", false);
            excerpt.put("nativeRows", Collections.emptyList());
            excerpt.put("unresolvedNativeRowParts", Collections.emptyList());
        }
        return result;
    }
    void assertCurrent(List<Chunk> selected) {
        List<SearchHit> chosen = selectedHits(selected);
        if (!chosen.isEmpty()) client.assertCurrent(chosen);
    }
    boolean quoted(List<VettingSemanticReview.Quote> quotes, Map<String, Chunk> submitted) {
        return quotes.stream().anyMatch(q -> isFixed(submitted.get(q.getChunkId())));
    }
}
