package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.sources.NativePdfSourceFamilyContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Source-only counterpart of the producer's declared whole-unit descendant graph. */
final class OerCanonicalHierarchyContext {
    static final String POLICY = "same-source-declared-typed-parent-descendant-context-v1";
    private static final List<String> REVISION_FIELDS = Arrays.asList("originalSourceSha256", "projectionRevisionSha256", "sourceRevisionSha256", "scopeSha256");
    private final Map<String,JsonNode> parents;
    private final Map<JsonNode,String> scopeErrors = new LinkedHashMap<>();
    private final Map<String,String> nodeErrors = new LinkedHashMap<>();
    private final Set<JsonNode> active = new LinkedHashSet<>();
    private final Map<String,Map<String,JsonNode>> children = new LinkedHashMap<>();
    private final Set<String> associated = new LinkedHashSet<>();

    OerCanonicalHierarchyContext(Map<String,JsonNode> parents) { this.parents = parents; build(); }

    private static ObjectNode identity(JsonNode parent) {
        ObjectNode out=OerCanonicalSourceUnits.identity(parent);
        for(String field:REVISION_FIELDS) out.set(field,OerCanonicalSourceUnits.nullable(parent,field));
        return out;
    }
    private void invalid(JsonNode key,String reason,Collection<String> ids) {
        if(ids.isEmpty())scopeErrors.putIfAbsent(key,reason);
        else for(String id:ids){associated.add(id);nodeErrors.putIfAbsent(id,reason);}
    }
    private void build() {
        Map<JsonNode,Map<JsonNode,List<JsonNode>>> nodes=new LinkedHashMap<>();
        for(JsonNode p:parents.values())if(nativePdf(p))nodes.computeIfAbsent(identity(p),k->new LinkedHashMap<>())
                .computeIfAbsent(OerCanonicalSourceUnits.nullable(p,"unitId"),k->new ArrayList<>()).add(p);
        Map<String,Set<String>> incoming=new LinkedHashMap<>();
        Map<List<Object>,JsonNode> edges=new LinkedHashMap<>();
        for(JsonNode owner:parents.values()) {
            if(!nativePdf(owner))continue;JsonNode key=identity(owner);
            for(JsonNode relation:owner.path("relations")) {
                if(!"parent".equals(relation.path("type").asText())||!"between_whole_units".equals(relation.path("scope").asText()))continue;
                active.add(key);
                Map<JsonNode,List<JsonNode>> sourceNodes=nodes.get(key);
                List<JsonNode> childNodes=sourceNodes.getOrDefault(OerCanonicalSourceUnits.nullable(relation,"fromUnitId"),Collections.emptyList());
                List<JsonNode> parentNodes=sourceNodes.getOrDefault(OerCanonicalSourceUnits.nullable(relation,"toUnitId"),Collections.emptyList());
                Set<String> boundary=new LinkedHashSet<>();childNodes.forEach(p->boundary.add(p.path("id").asText()));parentNodes.forEach(p->boundary.add(p.path("id").asText()));
                JsonNode ownerUnit=OerCanonicalSourceUnits.nullable(owner,"unitId");
                if(ownerUnit.equals(OerCanonicalSourceUnits.nullable(relation,"fromUnitId"))||ownerUnit.equals(OerCanonicalSourceUnits.nullable(relation,"toUnitId"))
                        ||contains(owner.path("adoption").path("regionIds"),OerCanonicalSourceUnits.nullable(relation,"fromRegionId"))||contains(owner.path("adoption").path("regionIds"),OerCanonicalSourceUnits.nullable(relation,"toRegionId")))boundary.add(owner.path("id").asText());
                boolean trusted=relation.path("humanConfirmed").isBoolean()&&"source_reviewed_declared".equals(relation.path("status").asText());
                for(String field:REVISION_FIELDS)trusted&=owner.path(field).isTextual()&&owner.path(field).asText().matches("[0-9a-f]{64}");
                for(String field:Arrays.asList("sourceReview","basis","fromUnitId","toUnitId","fromRegionId","toRegionId"))trusted&=text(relation.path(field));
                if(!trusted){invalid(key,"hierarchy_parent_declaration_untrusted",boundary);continue;}
                if(childNodes.size()!=1||parentNodes.size()!=1){invalid(key,"hierarchy_parent_endpoint_missing_ambiguous_or_cross_scope",boundary);continue;}
                JsonNode child=childNodes.get(0),parent=parentNodes.get(0);
                if(NativePdfSourceFamilyContract.PRODUCER.equals(owner.path("producerKind").asText())
                        &&(!NativePdfSourceFamilyContract.sameComponent(owner,child)||!NativePdfSourceFamilyContract.sameComponent(owner,parent))){invalid(key,"hierarchy_family_cross_component_unsupported",boundary);continue;}
                if(!contains(child.path("adoption").path("regionIds"),relation.path("fromRegionId"))
                        ||!contains(parent.path("adoption").path("regionIds"),relation.path("toRegionId"))){invalid(key,"hierarchy_parent_region_endpoint_differs",boundary);continue;}
                String childId=child.path("id").asText(),parentId=parent.path("id").asText();
                if(childId.equals(parentId)){invalid(key,"hierarchy_between_whole_unit_self_edge",boundary);continue;}
                List<Object> address=Arrays.asList(key,childId,parentId);JsonNode previous=edges.get(address);
                if(previous!=null&&!previous.path("relation").equals(relation)){invalid(key,"hierarchy_parent_declarations_conflict",boundary);continue;}
                ObjectNode edge=JsonUtils.mapper().createObjectNode();edge.put("edgeId",digest(relation));edge.set("relation",relation.deepCopy());
                edges.put(address,edge);children.computeIfAbsent(parentId,k->new LinkedHashMap<>()).put(childId,edge);
                incoming.computeIfAbsent(childId,k->new LinkedHashSet<>()).add(parentId);associated.add(childId);associated.add(parentId);
            }
        }
        for(Map.Entry<String,Set<String>> entry:incoming.entrySet())if(entry.getValue().size()!=1){Set<String> boundary=new LinkedHashSet<>(entry.getValue());boundary.add(entry.getKey());invalid(identity(parents.get(entry.getKey())),"hierarchy_multiple_whole_unit_parents",boundary);}
        Set<String> visited=new HashSet<>(),visiting=new HashSet<>();List<String> path=new ArrayList<>();
        for(String id:parents.keySet())if(associated.contains(id))visit(id,visited,visiting,path);
    }
    private void visit(String id,Set<String> visited,Set<String> visiting,List<String> path) {
        if(visiting.contains(id)){invalid(identity(parents.get(id)),"hierarchy_whole_unit_cycle",path.subList(path.indexOf(id),path.size()));return;}
        if(visited.contains(id))return;visiting.add(id);path.add(id);
        for(String child:children.getOrDefault(id,Collections.emptyMap()).keySet())visit(child,visited,visiting,path);
        visiting.remove(id);path.remove(path.size()-1);visited.add(id);
    }

    ObjectNode resolve(JsonNode seed,JsonNode observed) {
        JsonNode key=identity(seed);if(!nativePdf(seed)||!active.contains(key))return null;
        List<String> base=new ArrayList<>();observed.path("requiredMemberIds").forEach(id->base.add(id.asText()));
        if(!scopeErrors.containsKey(key)&&base.stream().noneMatch(associated::contains))return null;
        ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("policy",POLICY);out.put("status","unknown");out.putNull("reason");out.set("sourceIdentity",key);
        out.set("seedObservedUnitId",observed.path("unitId"));ArrayNode roots=out.putArray("rootWholeUnitIds");
        for(Map.Entry<String,JsonNode> entry:parents.entrySet())if(base.contains(entry.getKey()))roots.add(OerCanonicalSourceUnits.nullable(entry.getValue(),"unitId"));
        for(String field:Arrays.asList("requiredMemberIds","members","memberPayloadSha256","edgeDeclarations","blockIds","observedRanges"))out.putArray(field);
        out.put("memberOrder","canonical_source_input");out.put("derivedMembersCanBecomeOrigins",false);out.putNull("memberScores");out.put("qualifiersComplete","unknown");out.put("semanticScopeVerified",false);out.put("legalClosureVerified",false);
        try {
            observed(!scopeErrors.containsKey(key),scopeErrors.get(key));
            observed("complete_observed_unit".equals(observed.path("status").asText()),"hierarchy_exact_observed_unit_unknown");
            Set<String> needed=new LinkedHashSet<>(base);Deque<String> queue=new ArrayDeque<>(base);SortedMap<String,JsonNode> declarations=new TreeMap<>();
            while(!queue.isEmpty()){
                String origin=queue.removeLast();observed(!nodeErrors.containsKey(origin),nodeErrors.get(origin));
                for(Map.Entry<String,JsonNode> child:children.getOrDefault(origin,Collections.emptyMap()).entrySet()) {
                    declarations.put(child.getValue().path("edgeId").asText(),child.getValue());if(needed.add(child.getKey()))queue.addLast(child.getKey());
                }
            }
            for(String id:needed) {
                JsonNode member=parents.get(id);
                observed(identity(member).equals(key)&&member.path("sourceQuality").equals(seed.path("sourceQuality"))&&"whole_text_unit".equals(member.path("canonicalScope").asText()),"hierarchy_member_scope_quality_or_whole_unit_differs");
                OerCanonicalSourceUnits.source(parents,member);OerCanonicalSourceUnits.mapped(member);
                observed("complete_observed_unit".equals(OerCanonicalSourceUnits.resolveObserved(parents,member).path("status").asText()),"hierarchy_member_observed_unit_unknown");
            }
            ArrayNode ids=JsonUtils.mapper().createArrayNode(),members=JsonUtils.mapper().createArrayNode(),hashes=JsonUtils.mapper().createArrayNode();SortedSet<String> blocks=new TreeSet<>();
            for(Map.Entry<String,JsonNode> entry:parents.entrySet())if(needed.contains(entry.getKey())) {
                JsonNode member=entry.getValue();ids.add(entry.getKey());members.add(member.deepCopy());
                ObjectNode hash=hashes.addObject();hash.put("id",entry.getKey());hash.put("sha256",digest(member));
                for(JsonNode part:member.path("parts"))blocks.add(part.path("blockId").asText());
            }
            ArrayNode ranges=JsonUtils.mapper().createArrayNode();for(String block:blocks)ranges.add(OerCanonicalSourceUnits.blockRange(parents,OerCanonicalSourceUnits.identity(seed),block));
            out.put("status","complete_observed_hierarchy_context");out.set("requiredMemberIds",ids);out.set("members",members);out.set("memberPayloadSha256",hashes);
            ArrayNode edgeRows=out.putArray("edgeDeclarations");declarations.values().forEach(edgeRows::add);blocks.forEach(out.putArray("blockIds")::add);out.set("observedRanges",ranges);
        }catch(OerCanonicalSourceUnits.ObservationUnknown unknown){out.put("reason",unknown.getMessage());}
        ObjectNode address=JsonUtils.mapper().createObjectNode();
        for(String field:Arrays.asList("policy","status","reason","sourceIdentity","seedObservedUnitId","rootWholeUnitIds","requiredMemberIds","memberPayloadSha256"))address.set(field,out.path(field));
        ArrayNode edgeIds=address.putArray("edgeIds");out.path("edgeDeclarations").forEach(edge->edgeIds.add(edge.path("edgeId")));
        out.put("contextId",digest(address));return out;
    }
    private static boolean nativePdf(JsonNode node){return "native_pdf".equals(node.path("producerKind").asText())||NativePdfSourceFamilyContract.PRODUCER.equals(node.path("producerKind").asText());}
    private static boolean text(JsonNode node){return node.isTextual()&&!node.asText().trim().isEmpty();}
    private static boolean contains(JsonNode array,JsonNode value){if(!array.isArray())return false;for(JsonNode row:array)if(row.equals(value))return true;return false;}
    private static String digest(JsonNode node){return VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(node)));}
    private static void observed(boolean ok,String reason){if(!ok)throw new OerCanonicalSourceUnits.ObservationUnknown(reason);}
}
