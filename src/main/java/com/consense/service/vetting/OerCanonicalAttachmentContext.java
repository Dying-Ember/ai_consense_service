package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.sources.NativePdfSourceFamilyContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Validates direct source-reviewed attachments; no text, proximity or ancestry inference. */
final class OerCanonicalAttachmentContext {
    static final String POLICY="same-source-reviewed-direct-margin-sidebar-context-v1";
    static final String UNION_POLICY="independent-observed-hierarchy-and-direct-attachment-union-v1";
    static final String SHARED_POLICY="same-source-reviewed-direct-shared-note-star-context-v2";
    static final String SHARED_UNION_POLICY="independent-observed-hierarchy-and-direct-shared-note-union-v2";
    static String unionPolicy(String policy){
        if(POLICY.equals(policy))return UNION_POLICY;
        if(SHARED_POLICY.equals(policy))return SHARED_UNION_POLICY;
        throw new IllegalStateException("Canonical attachment union policy is unknown");
    }
    private static final List<String> EXTRA_FIELDS=Arrays.asList("originalSourceSha256","projectionRevisionSha256","sourceRevisionSha256","sourceSha256","scopeSha256","producerKind");
    private final Map<String,JsonNode> parents;
    private final Map<String,String> errors=new LinkedHashMap<>();
    private final Map<String,Map<String,ObjectNode>> incident=new LinkedHashMap<>();
    private final Map<String,Map<String,ObjectNode>> groups=new LinkedHashMap<>();
    private final boolean sharedNotes;

    OerCanonicalAttachmentContext(Map<String,JsonNode> parents){this(parents,false);}
    OerCanonicalAttachmentContext(Map<String,JsonNode> parents,boolean sharedNotes){this.parents=parents;this.sharedNotes=sharedNotes;build();}
    private static ObjectNode identity(JsonNode parent){ObjectNode out=OerCanonicalSourceUnits.identity(parent);for(String field:EXTRA_FIELDS)out.set(field,OerCanonicalSourceUnits.nullable(parent,field));return out;}
    private static boolean nativePdf(JsonNode value){return "native_pdf".equals(value.path("producerKind").asText())||NativePdfSourceFamilyContract.PRODUCER.equals(value.path("producerKind").asText());}
    private static boolean text(JsonNode value){return value.isTextual()&&!value.asText().trim().isEmpty();}
    private static boolean sha(JsonNode value){return value.isTextual()&&value.asText().matches("[0-9a-f]{64}");}
    private static boolean contains(JsonNode array,JsonNode value){if(!array.isArray())return false;for(JsonNode row:array)if(row.equals(value))return true;return false;}
    private static String digest(JsonNode node){return VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(node)));}
    private static void observed(boolean ok,String reason){if(!ok)throw new OerCanonicalSourceUnits.ObservationUnknown(reason);}
    private void invalid(Collection<String> ids,String reason){for(String id:ids)errors.putIfAbsent(id,reason);}
    private void declaration(JsonNode owner,JsonNode relation,JsonNode left,JsonNode right){
        for(JsonNode member:Arrays.asList(owner,left,right)){
            for(String field:Arrays.asList("originalSourceSha256","projectionRevisionSha256","sourceRevisionSha256","sourceSha256"))observed(sha(member.path(field)),"attachment_source_revision_identity_unknown");
            OerCanonicalSourceUnits.source(parents,member);OerCanonicalSourceUnits.mapped(member);
            boolean parts=true;for(JsonNode part:member.path("parts"))parts&=!part.has("sourceSha256")||part.path("sourceSha256").equals(member.path("sourceSha256"));
            observed(identity(member).equals(identity(owner))&&member.path("sourceQuality").equals(owner.path("sourceQuality"))&&parts,"attachment_source_revision_quality_or_part_source_differs");
        }
        if(NativePdfSourceFamilyContract.PRODUCER.equals(owner.path("producerKind").asText()))observed(
                NativePdfSourceFamilyContract.sameComponent(owner,left)&&NativePdfSourceFamilyContract.sameComponent(owner,right),"attachment_family_cross_component_unsupported");
        observed(owner.path("id").equals(left.path("id"))||owner.path("id").equals(right.path("id")),"attachment_declaration_owner_not_endpoint");
        String status=relation.path("status").asText();
        if("explicit_source_reviewed".equals(status)){
            JsonNode reference=relation.path("referenceSha256");observed(sha(reference),"attachment_review_reference_unknown");
            for(int i=0;i<2;i++){
                JsonNode member=i==0?left:right,adoption=member.path("adoption");String side=i==0?"from":"to";
                observed(!nativePdf(member)&&"explicit_source_reviewed_selection".equals(adoption.path("reviewStatus").asText())&&reference.equals(adoption.path("referenceSha256"))&&adoption.path("unitId").equals(member.path("unitId")),"attachment_review_reference_or_adoption_differs");
                JsonNode block=relation.path(side+"BlockId");boolean owns=false;for(JsonNode part:member.path("parts"))owns|=block.equals(part.path("blockId"));
                observed(block.isTextual()&&owns,"attachment_block_endpoint_not_owned");
                JsonNode cell=OerCanonicalSourceUnits.nullable(relation,side+"CellId");if(!cell.isNull()){
                    boolean ownCell=false;for(JsonNode part:member.path("parts"))for(JsonNode entry:part.path("cells"))ownCell|=cell.equals(entry.path("cellId"));
                    observed(ownCell,"attachment_cell_endpoint_not_owned");
                }
            }
        }else if("source_reviewed_declared".equals(status)){
            boolean trusted=relation.path("humanConfirmed").isBoolean();for(String field:Arrays.asList("sourceReview","basis","fromRegionId","toRegionId"))trusted&=text(relation.path(field));
            trusted&=(left.path("id").equals(right.path("id"))?"within_whole_unit":"between_whole_units").equals(relation.path("scope").asText());
            observed(trusted,"attachment_native_declaration_untrusted");Set<JsonNode> references=new HashSet<>();
            for(int i=0;i<2;i++){
                JsonNode member=i==0?left:right,adoption=member.path("adoption"),region=relation.path((i==0?"from":"to")+"RegionId"),reference=member.path("referenceSha256");
                observed(nativePdf(member)&&"whole_text_unit".equals(member.path("canonicalScope").asText())&&"whole_text_unit".equals(adoption.path("scope").asText())&&sha(member.path("scopeSha256"))&&sha(reference),"attachment_native_source_review_identity_unknown");
                references.add(reference);boolean mapped=false;for(JsonNode part:member.path("parts"))mapped|=region.equals(part.path("referenceRegionId"));
                observed(contains(adoption.path("regionIds"),region)&&mapped,"attachment_region_endpoint_not_owned");
            }
            observed(references.size()==1,"attachment_native_reference_differs");
        }else observed(false,"attachment_declaration_unreviewed");
    }
    private void build(){
        Map<JsonNode,Map<JsonNode,List<JsonNode>>> nodes=new LinkedHashMap<>();
        for(JsonNode member:parents.values()){
            ObjectNode scope=identity(member);boolean validShape=true;
            for(JsonNode value:scope)validShape&=value.isNull()||value.isTextual();
            if(!validShape){invalid(Collections.singleton(member.path("id").asText()),"attachment_source_identity_shape_invalid");continue;}
            JsonNode unit=OerCanonicalSourceUnits.nullable(member,"unitId");
            if(!unit.isNull()&&!unit.isTextual()){invalid(Collections.singleton(member.path("id").asText()),"attachment_unit_identity_shape_invalid");continue;}
            nodes.computeIfAbsent(scope,k->new LinkedHashMap<>()).computeIfAbsent(unit,k->new ArrayList<>()).add(member);
        }
        Map<List<Object>,ObjectNode> edges=new LinkedHashMap<>();Map<String,Set<String>> targets=new LinkedHashMap<>();
        for(JsonNode owner:parents.values()){
            JsonNode relations=owner.path("relations");if(relations.isMissingNode())continue;
            if(!relations.isArray()){invalid(Collections.singleton(owner.path("id").asText()),"attachment_declarations_not_a_list");continue;}
            for(JsonNode relation:relations){
                if(!relation.isObject()){invalid(Collections.singleton(owner.path("id").asText()),"attachment_declaration_not_an_object");continue;}
                if(!Arrays.asList("margin_note","sidebar").contains(relation.path("type").asText()))continue;
                if(errors.containsKey(owner.path("id").asText())||!text(relation.path("fromUnitId"))||!text(relation.path("toUnitId"))){
                    invalid(Collections.singleton(owner.path("id").asText()),"attachment_endpoint_identity_shape_invalid");continue;
                }
                JsonNode key=identity(owner);Map<JsonNode,List<JsonNode>> scopeNodes=nodes.get(key);
                List<JsonNode> lefts=scopeNodes.getOrDefault(OerCanonicalSourceUnits.nullable(relation,"fromUnitId"),Collections.emptyList()),rights=scopeNodes.getOrDefault(OerCanonicalSourceUnits.nullable(relation,"toUnitId"),Collections.emptyList());
                Set<String> boundary=new LinkedHashSet<>();boundary.add(owner.path("id").asText());lefts.forEach(p->boundary.add(p.path("id").asText()));rights.forEach(p->boundary.add(p.path("id").asText()));
                try{
                    observed(text(relation.path("fromUnitId"))&&text(relation.path("toUnitId"))&&lefts.size()==1&&rights.size()==1,"attachment_endpoint_missing_ambiguous_or_cross_scope");
                    JsonNode left=lefts.get(0),right=rights.get(0);declaration(owner,relation,left,right);
                    List<JsonNode> endpoints=new ArrayList<>();for(String field:Arrays.asList("fromBlockId","toBlockId","fromCellId","toCellId","fromRegionId","toRegionId"))endpoints.add(OerCanonicalSourceUnits.nullable(relation,field));
                    List<Object> address=Arrays.asList(key,relation.path("type"),left.path("id"),right.path("id"),endpoints);ObjectNode previous=edges.get(address);
                    observed(previous==null||previous.path("relation").equals(relation),"attachment_declarations_conflict");
                    ObjectNode edge=JsonUtils.mapper().createObjectNode();String edgeId=digest(relation);edge.put("edgeId",edgeId);edge.set("relation",relation.deepCopy());edge.putArray("memberIds").add(left.path("id")).add(right.path("id"));edges.put(address,edge);
                    String leftId=left.path("id").asText(),rightId=right.path("id").asText();incident.computeIfAbsent(leftId,k->new LinkedHashMap<>()).put(edgeId,edge);incident.computeIfAbsent(rightId,k->new LinkedHashMap<>()).put(edgeId,edge);
                    if(!leftId.equals(rightId))targets.computeIfAbsent(leftId,k->new LinkedHashSet<>()).add(rightId);
                }catch(OerCanonicalSourceUnits.ObservationUnknown unknown){invalid(boundary,unknown.getMessage());}
            }
        }
        for(ObjectNode edge:edges.values())groups.computeIfAbsent(edge.path("memberIds").get(0).asText(),k->new LinkedHashMap<>()).put(edge.path("edgeId").asText(),edge);
        if(!sharedNotes)for(Map.Entry<String,Set<String>> entry:targets.entrySet())if(entry.getValue().size()>1){Set<String> affected=new LinkedHashSet<>(entry.getValue());affected.add(entry.getKey());invalid(affected,"attachment_note_has_multiple_targets");}
    }
    ObjectNode resolve(JsonNode seed,JsonNode observed){
        Set<String> roots=new LinkedHashSet<>();observed.path("requiredMemberIds").forEach(id->roots.add(id.asText()));if(roots.isEmpty())roots.add(seed.path("id").asText());
        if(roots.stream().noneMatch(id->incident.containsKey(id)||errors.containsKey(id)))return null;
        ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("policy",sharedNotes?SHARED_POLICY:POLICY);out.put("status","unknown");out.putNull("reason");out.set("sourceIdentity",identity(seed));out.set("seedObservedUnitId",observed.path("unitId"));
        ArrayNode whole=out.putArray("rootWholeUnitIds");parents.forEach((id,p)->{if(roots.contains(id))whole.add(OerCanonicalSourceUnits.nullable(p,"unitId"));});
        for(String field:Arrays.asList("requiredMemberIds","members","memberPayloadSha256","edgeDeclarations","blockIds","observedRanges"))out.putArray(field);
        out.put("memberOrder","canonical_source_input");out.put("derivedMembersCanBecomeOrigins",false);out.putNull("memberScores");out.put("qualifiersComplete","unknown");out.put("semanticScopeVerified",false);out.put("legalClosureVerified",false);
        try{
            observed("complete_observed_unit".equals(observed.path("status").asText()),"attachment_exact_observed_unit_unknown");Set<String> needed=new LinkedHashSet<>(roots);SortedMap<String,ObjectNode> declarations=new TreeMap<>();
            // Only original observed roots nominate a note group; derived targets never become origins.
            for(String id:roots){
                observed(!errors.containsKey(id),errors.get(id));
                for(ObjectNode direct:incident.getOrDefault(id,Collections.emptyMap()).values()){
                    Collection<ObjectNode> declared=sharedNotes?groups.get(direct.path("memberIds").get(0).asText()).values():Collections.singletonList(direct);
                    for(ObjectNode edge:declared){declarations.put(edge.path("edgeId").asText(),edge);edge.path("memberIds").forEach(member->needed.add(member.asText()));}
                }
            }
            for(String id:new ArrayList<>(needed)){
                JsonNode member=parents.get(id);observed(!errors.containsKey(id),errors.get(id));observed(identity(member).equals(identity(seed))&&member.path("sourceQuality").equals(seed.path("sourceQuality")),"attachment_member_source_revision_or_quality_differs");
                JsonNode exact=OerCanonicalSourceUnits.resolveObserved(parents,member);observed("complete_observed_unit".equals(exact.path("status").asText()),"attachment_member_observed_unit_unknown");exact.path("requiredMemberIds").forEach(value->needed.add(value.asText()));
            }
            SortedSet<String> blocks=new TreeSet<>();ArrayNode ids=JsonUtils.mapper().createArrayNode(),members=JsonUtils.mapper().createArrayNode(),hashes=JsonUtils.mapper().createArrayNode();
            for(Map.Entry<String,JsonNode> entry:parents.entrySet())if(needed.contains(entry.getKey())){
                JsonNode member=entry.getValue();observed(!errors.containsKey(entry.getKey())&&identity(member).equals(identity(seed))&&member.path("sourceQuality").equals(seed.path("sourceQuality")),"attachment_exact_member_scope_quality_or_declaration_unknown");OerCanonicalSourceUnits.source(parents,member);OerCanonicalSourceUnits.mapped(member);
                ids.add(entry.getKey());members.add(member.deepCopy());hashes.addObject().put("id",entry.getKey()).put("sha256",digest(member));for(JsonNode part:member.path("parts"))blocks.add(part.path("blockId").asText());
            }
            ArrayNode ranges=JsonUtils.mapper().createArrayNode();for(String block:blocks)ranges.add(OerCanonicalSourceUnits.blockRange(parents,OerCanonicalSourceUnits.identity(seed),block));
            out.put("status","complete_observed_attachment_context");out.set("requiredMemberIds",ids);out.set("members",members);out.set("memberPayloadSha256",hashes);out.set("observedRanges",ranges);blocks.forEach(out.putArray("blockIds")::add);
            ArrayNode edgeRows=out.putArray("edgeDeclarations");declarations.values().forEach(edge->{ObjectNode declared=edge.deepCopy();declared.remove("memberIds");edgeRows.add(declared);});
        }catch(OerCanonicalSourceUnits.ObservationUnknown unknown){out.put("reason",unknown.getMessage());}
        ObjectNode address=JsonUtils.mapper().createObjectNode();for(String field:Arrays.asList("policy","status","reason","sourceIdentity","seedObservedUnitId","rootWholeUnitIds","requiredMemberIds","memberPayloadSha256"))address.set(field,out.path(field));
        ArrayNode edgeIds=address.putArray("edgeIds");out.path("edgeDeclarations").forEach(edge->edgeIds.add(edge.path("edgeId")));out.put("contextId",digest(address));return out;
    }
}
