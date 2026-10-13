package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import java.util.*;
import java.util.stream.Collectors;

/** Admit complete observed closures of original retrieved seeds, without expanding derived members. */
final class VettingCanonicalContext {
    private VettingCanonicalContext(){}
    static void admit(VettingContextBuilder.Selection selected,List<Chunk> corpus,List<VettingRetrievalSourceUnits.Unit> units,int limit){
        Map<String,Chunk> byId=new LinkedHashMap<>();for(Chunk c:corpus)byId.put(c.getId(),c);
        Set<String> present=selected.getChunks().stream().map(Chunk::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> observed=new HashSet<>();
        for(VettingRetrievalSourceUnits.Unit unit:units){
            String key=String.valueOf(unit.trace.get("unitId"));if(!observed.add(key))continue;
            VettingContextBuilder.GroupTrace trace=new VettingContextBuilder.GroupTrace();trace.setId("canonical-unit:"+key);
            trace.setRelation("retrieved_structural_unit");trace.setRelationStrength("complete_observed_closure_only_qualifiers_unknown");
            trace.setCoreIds(Collections.singletonList(unit.originId));trace.setContextIds(unit.ids.stream().filter(id->!unit.originId.equals(id)).collect(Collectors.toList()));
            selected.getGroups().add(trace);
            if(!"complete_observed_unit".equals(unit.status)){trace.setStatus("unknown");continue;}
            long added=0;for(String id:unit.ids){Chunk c=byId.get(id);if(c==null)throw new IllegalArgumentException("Canonical closure member outside corpus");
                if(!present.contains(id))added+=c.getContent().length();}
            if(added+(long)selected.getContentChars()>limit){trace.setStatus("dropped_budget");continue;}
            // Source order is preserved; no member is passed back into the literal/relationship builder.
            for(Chunk c:corpus)if(unit.ids.contains(c.getId())&&present.add(c.getId())){
                selected.getChunks().add(JsonUtils.read(JsonUtils.write(c),Chunk.class));selected.getDroppedIds().remove(c.getId());
            }
            selected.setContentChars((int)(selected.getContentChars()+added));trace.setStatus("selected");trace.setFullySubmitted(true);
        }
    }
}
