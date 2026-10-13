package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.sources.NativePdfSourceFamilyContract;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Selected business observations only. Complete pointers, CAS and verifier proofs stay outside model material. */
public final class OerModelMaterial {
    public static final String VERSION="selected-canonical-model-material-v1";
    public static final String SOURCE_VERSION="source-and-projection-model-declaration-v1";
    public static final String DICTIONARY_VERSION="exact-source-projection-unit-model-dictionary-v1";
    public static final String LEGEND="\nModel material version="+VERSION+". sourceDeclarationRef addresses a request-local source+projection declaration emitted once, not an external source. "
            +"Only selected complete adoption-unit observations are projected. Original evidence pointers retain the full capture, CAS and software-verification tree outside this model input. "
            +"Selected unit unknowns stay literal; other-unit unknown locations are outside this material and their statuses/reasons are summarized in the source declaration. "
            +"Neither selection, source-reviewed relations nor model material establishes legal closure, applicability, whole-source coverage or human confirmation.\n";
    public static final String WIRE_LEGEND=VettingSourceMetadataWire.LEGEND.replace("All original source fields, whole tables, blanks, relationships and unknowns remain present", "All supplied model-material fields, whole tables, blanks, relationships and unknowns remain present");
    public static final String DICTIONARY_LEGEND="\nModel dictionary version="+DICTIONARY_VERSION+". materialDictionary entries carry modelUnitAddress; ordered hitMembership.memberMaterialRefs refer to those exact entries. "
            +"Sharing requires the same complete source pointer, canonical member payload and role; it never merges differing revisions, adoption declarations, text or roles. "
            +"Each hit retains its ranked seed and original closure membership/order. Dictionary members receive no independent relevance score.\n";
    private OerModelMaterial() { }

    /** One request, never a cached validation verdict. */
    public static final class Request {
        private final Map<String,JsonNode> sourceUnknowns=new LinkedHashMap<>(),sourceLimits=new LinkedHashMap<>();
        private final Set<String> materialIds=new HashSet<>();
        public ObjectNode project(EvidencePointer p){return project(p,null,null);}
        public ObjectNode project(EvidencePointer p,JsonNode canonicalMember,String role) {
            Objects.requireNonNull(p,"Canonical evidence pointer required");
            ObjectNode identity=object();identity.put("projectId",p.getProjectId());identity.put("module",p.getModule());identity.put("documentId",p.getDocumentId());identity.put("originalSourceSha256",p.getOriginalSourceSha256());
            identity.put("sourceRevisionSha256",p.getSourceRevisionSha256());identity.put("canonicalSourceHash",p.getCanonicalSourceHash());
            identity.put("projectionSha256",p.getProjectionSha256());identity.put("projectionRevisionSha256",p.getProjectionRevisionSha256());
            identity.put("projectionSchema",p.getProjectionSchema());identity.put("producerKind",p.getProducerKind());
            identity.put("role",role);
            if(NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind())){identity.put("sourceRevisionKind",p.getSourceRevisionKind());identity.put("familyCompositionSha256",p.getFamilyCompositionSha256());identity.put("familyComponentSetSha256",p.getFamilyComponentSetSha256());}
            String ref="model-source:"+VettingCorpus.hash(JsonUtils.write(identity));
            JsonNode unknown=p.getUnknowns()==null?array():p.getUnknowns();require(unknown.isArray(),"source_unknowns_array_required");
            ObjectNode limits=sourceLimits(p);boolean first=!sourceUnknowns.containsKey(ref);
            if(first){sourceUnknowns.put(ref,unknown.deepCopy());sourceLimits.put(ref,limits.deepCopy());}
            else require(sourceUnknowns.get(ref).equals(unknown)&&sourceLimits.get(ref).equals(limits),"same_source_projection_declarations_differ");
            UnknownProjection selected=unknowns(p,unknown);
            ObjectNode fullIdentity=identity.deepCopy();fullIdentity.set("completePointer",JsonUtils.mapper().valueToTree(p));fullIdentity.set("canonicalMember",copy(canonicalMember));
            String address="model-unit:"+VettingCorpus.hash(JsonUtils.write(fullIdentity));
            if(!materialIds.add(address)){
                ObjectNode refOnly=object();refOnly.put("modelMaterialVersion",VERSION);refOnly.put("modelUnitRef",address);refOnly.put("sourceDeclarationRef",ref);
                refOnly.put("unitId",p.getUnitId());refOnly.put("canonicalChunkId",p.getCanonicalChunkId());return refOnly;
            }
            ObjectNode structure=object();structure.put("modelMaterialVersion",VERSION);structure.put("sourceDeclarationRef",ref);
            structure.put("modelUnitAddress",address);
            structure.put("unitId",p.getUnitId());structure.put("canonicalChunkId",p.getCanonicalChunkId());
            structure.put("projectionSchema",p.getProjectionSchema());structure.put("producerKind",p.getProducerKind());
            structure.put("storageShape",p.getStorageShape());structure.put("originalGeometryStatus",p.getOriginalGeometryStatus());
            structure.put("visualReturnStatus",p.getVisualReturnStatus());structure.put("bboxConvention",p.getBboxConvention());
            structure.set("physicalPages",JsonUtils.mapper().valueToTree(p.getPhysicalPages()));
            structure.set("sourceOrder",copy(p.getSourceOrder()));structure.set("parts",parts(p.getParts()));
            structure.set("sourceSpans",spans(p.getSourceSpans()));structure.set("tableGroup",table(p.getTableGroup()));
            structure.set("relations",relations(p.getRelations()));structure.set("assetRefs",table(p.getAssetRefs()));
            structure.set("sourceQuality",fields(p.getSourceQuality(),"attributes","semanticQuality","notes","reviewMeaning","parseStatus","version","humanConfirmed","wholeSourceAccepted","legalQualityAccepted","meaning","components"));
            if(NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind()))structure.set("sourceFamilyOrigin",fields(p.getSourceFamilyOriginRef(),"componentOrdinal","childProjectionSha256","childChunkId","childUnitId","fullChildChunkSha256"));
            structure.set("adoption",fields(p.getAdoption(),"scope","unitId","clause","clauseHeadingLocation","nodeId","regionIds","referenceRegionIds","physicalPages","partSeparator","textChannel","reviewStatus","reviewedAttributes","humanConfirmed","originalGeometryStatus"));
            structure.set("unknowns",selected.units);
            if(first){
                ObjectNode declaration=identity.deepCopy();declaration.put("declarationVersion",SOURCE_VERSION);declaration.put("sourceDeclarationRef",ref);
                declaration.set("sourceFilename",copy(p.getSourceFilename()));declaration.set("limits",limits);
                declaration.set("sourceUnknowns",selected.source);declaration.set("unknownStatusReasonSummary",selected.summary);
                declaration.put("declaredUnknownCount",unknown.size());declaration.put("unknownProjectionScope","selected unitId or exact region path; other-unit locations remain in complete evidence pointers");
                structure.set("sourceDeclaration",declaration);
            }
            return structure;
        }
    }

    private static ObjectNode sourceLimits(EvidencePointer p) {
        ObjectNode out=object();out.put("humanConfirmed",p.isHumanConfirmed());out.put("wholeSourceSemantics","unknown");
        out.put("originalGeometryStatus",p.getOriginalGeometryStatus());out.put("visualReturnStatus",p.getVisualReturnStatus());
        JsonNode pdf=p.getNativePdfEvidence();
        if("native_pdf".equals(p.getProducerKind())){
            com.consense.service.sources.NativePdfCanonicalContract.validateDisplayDeclarations(pdf);
            out.setAll(fields(pdf,"humanConfirmed","semanticScopeVerified","wholeOriginalSourceCoverage","visualReturnStatus","limitations"));
        }
        if(NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind())){
            JsonNode d=p.getNativePdfSourceFamilyEvidence();NativePdfSourceFamilyContract.validateDisplayDeclarations(d);
            out.setAll(fields(d,"humanConfirmed","semanticScopeVerified","wholeOriginalSourceCoverage","visualReturnStatus","limitations","sourceRevisionKind","compositionSha256","componentSetSha256"));
            out.set("sourceQuality",fields(d.path("sourceQuality"),"semanticQuality","humanConfirmed","wholeSourceAccepted","legalQualityAccepted","meaning","components"));
        }
        return out;
    }
    private static ArrayNode parts(JsonNode raw) {
        require(raw!=null&&raw.isArray(),"canonical_parts_required");ArrayNode out=array();
        for(JsonNode part:raw){ObjectNode p=fields(part,"anchor","blockId","startOffset","endOffset","offsetUnit","physicalPageNo","bbox","bboxConvention","referenceRegionId","text","textChannel","sourceSha256","native","nativeBlockId","nativeEditingState","nativeSemanticQuality","nativeLimitations","qualityAttributes","candidateTextStatus","originalGeometryStatus","nativeEffectiveVisibility","geometryStatus","apTextAdoption");
            if(part.has("sourcePosition"))p.set("sourcePosition",position(part.get("sourcePosition")));
            if(part.has("textTransform"))p.set("textTransform",fields(part.get("textTransform"),"kind","textChannel","offsetUnit","sourceChannel","normalization","status"));
            if(part.has("cells"))p.set("cells",table(part.get("cells")));
            out.add(p);
        }return out;
    }
    private static ArrayNode spans(JsonNode raw){require(raw!=null&&raw.isArray(),"canonical_source_spans_required");ArrayNode out=array();
        for(JsonNode span:raw){ObjectNode s=fields(span,"blockId","cellId","startOffset","endOffset","offsetUnit","sourceSha256","physicalPageNo");
            if(span.has("sourcePosition"))s.set("sourcePosition",position(span.get("sourcePosition")));out.add(s);}return out;}
    private static ObjectNode position(JsonNode raw){return fields(raw,"kind","physicalPageNo","partName","nodePath","partSha256","sourceSha256","story","tableId","rowIndex","cellIndex");}
    private static ArrayNode relations(JsonNode raw){require(raw!=null&&raw.isArray(),"canonical_relations_required");ArrayNode out=array();
        for(JsonNode r:raw)out.add(fields(r,"type","status","scope","sameParentClause","sourceReview","basis","humanConfirmed","fromUnitId","toUnitId","fromRegionId","toRegionId","fromBlockId","toBlockId","fromCellId","toCellId","condition","qualifiers","qualifiersComplete","exceptions"));return out;}

    /** All table/cell/grid/merge/nesting values remain. Only known software/CAS proof expansions become artifact identities. */
    private static JsonNode table(JsonNode raw){
        if(raw==null)return com.fasterxml.jackson.databind.node.NullNode.instance;
        if(raw.isArray()){ArrayNode a=array();for(JsonNode x:raw)a.add(table(x));return a;}
        if(!raw.isObject())return raw.deepCopy();ObjectNode out=object();Iterator<String> names=raw.fieldNames();
        while(names.hasNext()){String k=names.next();JsonNode v=raw.get(k);
            if(Arrays.asList("observerIdentitySha256","internalProjectionSha256","transportValidation","rawNode","softwareProof","reviewActor","referenceRelationIndex").contains(k))continue;
            if(k.equals("sourcePosition")){out.set(k,position(v));continue;}
            if(v.isObject()&&v.has("artifactDescriptor")){out.set(k,reference(v));continue;}
            out.set(k,table(v));
        }return out;
    }
    private static ObjectNode reference(JsonNode raw){ObjectNode out=fields(raw,"kind","jsonPointer","payloadSha256","sourceSha256","sourceRevisionSha256");
        JsonNode d=raw.path("artifactDescriptor");out.set("artifactIdentity",fields(d,"sha256","schema","identityScope"));return out;}

    private static final class UnknownProjection{final ArrayNode units=array(),source=array(),summary=array();}
    private static UnknownProjection unknowns(EvidencePointer p,JsonNode raw){
        UnknownProjection out=new UnknownProjection();Set<String> regions=new LinkedHashSet<>();
        if(p.getAdoption()!=null)for(String key:Arrays.asList("regionIds","referenceRegionIds"))for(JsonNode r:p.getAdoption().path(key))if(r.isTextual())regions.add(r.asText());
        for(JsonNode part:p.getParts())if(part.path("referenceRegionId").isTextual())regions.add(part.path("referenceRegionId").asText());
        Map<String,Integer> summary=new LinkedHashMap<>();Map<String,JsonNode> summaries=new LinkedHashMap<>();
        for(JsonNode u:raw){require(u.isObject(),"unknown_entry_object_required");Set<String> keys=new HashSet<>();u.fieldNames().forEachRemaining(keys::add);
            if(keys.equals(new HashSet<>(Arrays.asList("componentOrdinal","childProjectionSha256","childSourceRevisionSha256","unknown")))){
                require(NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind())&&u.path("componentOrdinal").isIntegralNumber()&&u.path("childProjectionSha256").isTextual()&&u.path("childSourceRevisionSha256").isTextual(),"family_unknown_identity_invalid");
                JsonNode nested=u.path("unknown");require(nested.isObject()&&nested.size()==3&&nested.path("path").isTextual()&&nested.path("status").isTextual()&&nested.path("reason").isTextual(),"family_unknown_fields_invalid");
                JsonNode origin=p.getSourceFamilyOriginRef();boolean component=u.path("componentOrdinal").equals(origin.path("componentOrdinal"))&&u.path("childProjectionSha256").equals(origin.path("childProjectionSha256"));
                String path=nested.path("path").asText();boolean local=false;for(String region:regions)if(path.equals("regions."+region)||path.startsWith("regions."+region+".")){local=true;break;}
                if(component&&local)out.units.add(u.deepCopy());else if(!path.startsWith("regions."))out.source.add(u.deepCopy());
                ObjectNode summaryRow=fields(u,"componentOrdinal","childProjectionSha256","childSourceRevisionSha256");summaryRow.setAll(fields(nested,"status","reason"));String key=JsonUtils.write(summaryRow);summary.merge(key,1,Integer::sum);summaries.put(key,summaryRow);
            }else if(keys.equals(new HashSet<>(Arrays.asList("path","status","reason")))){
                require(u.path("path").isTextual()&&u.path("status").isTextual()&&u.path("reason").isTextual(),"pdf_unknown_fields_invalid");
                String path=u.path("path").asText();boolean local=false;
                for(String region:regions)if(path.equals("regions."+region)||path.startsWith("regions."+region+".")){local=true;break;}
                if(local)out.units.add(u.deepCopy());else if(!path.startsWith("regions."))out.source.add(u.deepCopy());
                ObjectNode s=fields(u,"status","reason");String key=JsonUtils.write(s);summary.merge(key,1,Integer::sum);summaries.put(key,s);
            }else if(keys.equals(new HashSet<>(Arrays.asList("unitId","attributes")))){
                require(u.path("unitId").isTextual()&&u.path("attributes").isObject(),"docx_unknown_fields_invalid");
                if(Objects.equals(p.getUnitId(),u.path("unitId").asText()))out.units.add(u.deepCopy());
                ObjectNode s=object();s.set("attributes",u.get("attributes").deepCopy());String key=JsonUtils.write(s);summary.merge(key,1,Integer::sum);summaries.put(key,s);
            }else throw new IllegalArgumentException("Model material unknown_schema_not_supported; complete source evidence unchanged");
        }
        for(String key:summary.keySet()){ObjectNode s=(ObjectNode)summaries.get(key).deepCopy();s.put("declaredEntries",summary.get(key));out.summary.add(s);}return out;
    }
    private static ObjectNode fields(JsonNode raw,String... keys){ObjectNode out=object();if(raw==null||raw.isNull())return out;
        require(raw.isObject(),"model_structure_object_required");for(String key:keys)if(raw.has(key))out.set(key,raw.get(key).deepCopy());return out;}
    private static JsonNode copy(JsonNode n){return n==null?com.fasterxml.jackson.databind.node.NullNode.instance:n.deepCopy();}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static ArrayNode array(){return JsonUtils.mapper().createArrayNode();}
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalArgumentException("Model material rejected: "+reason);}
}
