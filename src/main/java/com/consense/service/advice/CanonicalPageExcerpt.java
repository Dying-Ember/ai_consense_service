package com.consense.service.advice;

import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Adds observed positions only when every literal part reproduces the selected excerpt exactly. */
final class CanonicalPageExcerpt {
    private CanonicalPageExcerpt() {}

    static String render(String literal,List<EvidencePointer> pointers) {
        if(literal==null||pointers==null||pointers.isEmpty())return null;
        List<JsonNode> parts=new ArrayList<>();Map<String,JsonNode> blocks=new LinkedHashMap<>();Map<String,String> owners=new HashMap<>();
        Set<JsonNode> relations=new LinkedHashSet<>();List<String> texts=new ArrayList<>();
        for(EvidencePointer pointer:pointers) {
            if(pointer==null||"native_docx".equals(pointer.getProducerKind())||"native_pdf".equals(pointer.getProducerKind()))return null;
            JsonNode observed=pointer.getParts();if(observed==null||!observed.isArray()||observed.isEmpty())return null;
            for(JsonNode part:observed) {
                JsonNode page=part.path("physicalPageNo");String block=part.path("blockId").asText();
                if(!page.isIntegralNumber()||!page.canConvertToInt()||page.asInt()<=0||block.isEmpty()||!part.path("text").isTextual()
                        ||pointer.getPhysicalPages()==null||!pointer.getPhysicalPages().contains(page.asInt())
                        ||blocks.putIfAbsent(block,part)!=null)return null;
                owners.put(block,pointer.getUnitId());parts.add(part);texts.add(part.path("text").asText());
            }
            if(pointer.getRelations()!=null&&pointer.getRelations().isArray())pointer.getRelations().forEach(relations::add);
        }
        // A prefix, suffix, differing channel or extra member must never disappear from the prompt.
        if(!literal.equals(String.join("\n",texts)))return null;
        StringBuilder out=new StringBuilder("Observed original page fragments; source completeness, semantics and applicability remain unknown.\n");
        for(JsonNode part:parts)out.append("Observed part: physicalPage=").append(part.path("physicalPageNo").asInt())
                .append(" | blockId=").append(part.path("blockId").asText()).append("\n")
                .append(part.path("text").asText()).append("\n");
        for(JsonNode relation:relations) {
            out.append("Declared source relation: type=").append(relation.path("type").asText("unknown"))
                    .append(" | status=").append(relation.path("status").asText("unknown"))
                    .append(" | from ").append(endpoint(blocks,owners,relation,"from"))
                    .append(" | to ").append(endpoint(blocks,owners,relation,"to")).append("\n");
        }
        return out.toString();
    }

    private static String endpoint(Map<String,JsonNode> blocks,Map<String,String> owners,JsonNode relation,String direction) {
        String blockId=relation.path(direction+"BlockId").asText(),cellId=relation.path(direction+"CellId").asText();
        JsonNode part=blocks.get(blockId);if(part==null)return "unresolved endpoint";
        JsonNode declaredOwner=relation.path(direction+"UnitId");
        if(!declaredOwner.isMissingNode()&&!declaredOwner.isNull()
                &&(!declaredOwner.isTextual()||!Objects.equals(declaredOwner.asText(),owners.get(blockId))))return "unresolved endpoint";
        String page="physicalPage="+part.path("physicalPageNo").asInt();
        if(cellId.isEmpty())return page+" blockId="+blockId;
        JsonNode matched=null;for(JsonNode cell:part.path("cells"))if(cellId.equals(cell.path("cellId").asText())) {
            if(matched!=null)return "unresolved endpoint";matched=cell;
        }
        return matched==null?"unresolved endpoint":page+" row="+matched.path("row").asText("unknown")
                +" column="+matched.path("column").asText("unknown")+" cellId="+cellId;
    }
}
