package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.SuggestionSnapshot;
import com.consense.web.dto.DraftingDtos.VariableVO;
import java.util.LinkedHashMap;
import java.util.Map;

/** Existing integration scenarios submit the same displayed identity snapshot as the frontend. */
final class DraftAdoptionTestPayload {
    private DraftAdoptionTestPayload() { }
    static String candidate(DraftingService service,String project,String key,int index) {
        VariableVO variable=variable(service,project,key);Map<String,Object> patch=new LinkedHashMap<>();
        patch.put("candidateIndex",index);patch.put("candidateSnapshot",variable.getCandidates().get(index));return JsonUtils.write(patch);
    }
    static String reviewed(DraftingService service,String project,String key,Boolean confirmed) {
        VariableVO variable=variable(service,project,key);Map<String,Object> patch=new LinkedHashMap<>();
        patch.put("reviewed",true);if(confirmed!=null)patch.put("confirmed",confirmed);
        patch.put("suggestionSnapshot",new SuggestionSnapshot(variable.getValue(),variable.getSource(),variable.getCandidates(),variable.isReviewRequired()));return JsonUtils.write(patch);
    }
    private static VariableVO variable(DraftingService service,String project,String key) {
        return service.listVariables(project).stream().filter(v->key.equals(v.getKey())).findFirst().orElseThrow(()->new IllegalArgumentException(key));
    }
}
