package com.consense.service.sources;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Public native transport contract. Actual OOXML/CAS proof is checked by the registry on every read. */
public final class NativeCanonicalContract {
    private NativeCanonicalContract(){}
    public static final String SCHEMA="consense.canonical-projection.v2";
    public static final String PRODUCER="native_docx";
    public static final String VERSION="source-reviewed-native-cas-public-projection-v1";
    public static final String STORAGE="cas-native-observation-adopted-public-v1";
    public static final String GEOMETRY="not_defined_for_native_docx";
    public static final String VISUAL="unavailable_pending_verified_rendition";
    public static final List<String> ALGORITHMS=Collections.unmodifiableList(Arrays.asList(
            "native_source_registry.py","oer_native_server.py","native_canonical_projection.py",
            "native_projection_transport.py","native_observation.py","source_reference.py"));
    static boolean actualArtifactReadPolicy(JsonNode value){
        return value.isTextual()&&("actual-cas-every-read".equals(value.asText())
                ||"actual-cas-unique-per-validation-session-with-exit-rehash".equals(value.asText()));
    }
    public static boolean isNative(JsonNode snapshot){return SCHEMA.equals(snapshot.path("schema").asText());}

    public static void validateCapability(JsonNode oer){
        JsonNode nativeCapability=oer.path("nativeCanonical");
        require(SCHEMA.equals(nativeCapability.path("schema").asText())
                &&PRODUCER.equals(nativeCapability.path("producerKind").asText())
                &&VERSION.equals(nativeCapability.path("projectionVersion").asText())
                &&STORAGE.equals(nativeCapability.path("storageShape").asText())
                &&"/project/native".equals(nativeCapability.path("projectionEndpoint").asText())
                &&actualArtifactReadPolicy(nativeCapability.path("artifactReadValidation")),
                "OER_NATIVE_CAPABILITY_MISMATCH");
    }
    public static void validateAlgorithms(JsonNode algorithms){
        for(String name:ALGORITHMS)require(algorithms.path(name).isTextual()&&algorithms.path(name).asText().matches("[a-f0-9]{64}"),"OER_NATIVE_ALGORITHM_IDENTITY_MISMATCH");
    }

    public static void validateEnvelope(JsonNode snapshot){
        JsonNode identity=snapshot.path("projectionIdentity"),source=snapshot.path("source");
        String sha=source.path("sourceSha256").asText();
        require(PRODUCER.equals(snapshot.path("producerKind").asText())&&STORAGE.equals(snapshot.path("storageShape").asText())
                &&PRODUCER.equals(identity.path("producerKind").asText())&&STORAGE.equals(identity.path("storageShape").asText())
                &&VERSION.equals(identity.path("version").asText()),"CANONICAL_NATIVE_VERSION_INVALID");
        require("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(source.path("mediaType").asText())
                &&falseBoolean(snapshot.path("semanticScopeVerified"))&&VISUAL.equals(snapshot.path("visualReturnStatus").asText())
                &&empty(snapshot.path("assetRefs"))&&!snapshot.has("nativeObservationBundle")&&!snapshot.has("sourceReference"),
                "CANONICAL_NATIVE_ENVELOPE_INVALID");
        require("cold_full_cas_and_internal_reconstruction".equals(snapshot.path("transportValidation").path("mode").asText())
                &&"not_implemented".equals(snapshot.path("transportValidation").path("derivedValidationCache").asText())
                &&falseBoolean(snapshot.path("transportValidation").path("qualityCertified")),"CANONICAL_NATIVE_PROOF_INVALID");
        for(String field:Arrays.asList("projectionCodeSha256","internalProjectionSha256","internalProjectionCodeSha256",
                "internalSnapshotBytesSha256","nativeBundleBytesSha256","observerIdentitySha256"))validSha(identity.path(field));
        descriptor(snapshot.path("transportCodeRef"));
        require(identity.path("projectionCodeSha256").equals(snapshot.path("transportCodeRef").path("sha256")),"CANONICAL_NATIVE_PROOF_INVALID");
        JsonNode internal=snapshot.path("internalProjectionRef"),observation=snapshot.path("nativeObservationRef");
        descriptor(internal.path("artifactDescriptor"));descriptor(observation.path("artifactDescriptor"));
        require(identity.path("internalProjectionSha256").equals(internal.path("projectionSha256"))
                &&identity.path("internalSnapshotBytesSha256").equals(internal.path("artifactDescriptor").path("sha256"))
                &&identity.path("nativeBundleBytesSha256").equals(observation.path("artifactDescriptor").path("sha256"))
                &&identity.path("bundleSha256").equals(observation.path("bundleSha256")),"CANONICAL_NATIVE_PROOF_INVALID");
        JsonNode captures=snapshot.path("nativeCaptureRefs"),observer=snapshot.path("observerIdentity");
        for(String field:Arrays.asList("sourcePackage","adapterSource","rawObservation"))descriptor(captures.path(field));
        require(sha.equals(captures.path("sourcePackage").path("sha256").asText())
                &&captures.path("adapterSource").path("sha256").equals(observer.path("adapterCodeSha256"))
                &&"ooxml-observation-2".equals(observer.path("version").asText()),"CANONICAL_NATIVE_CAPTURE_INVALID");
        JsonNode parts=captures.path("rawParts");require(parts.isArray()&&parts.size()>0,"CANONICAL_NATIVE_CAPTURE_INVALID");
        Set<String> names=new HashSet<>();for(JsonNode part:parts){require(nonempty(part.path("name"))&&names.add(part.path("name").asText()),"CANONICAL_NATIVE_CAPTURE_INVALID");descriptor(part.path("artifactDescriptor"));}
        JsonNode reference=snapshot.path("sourceReferenceRef"),manifest=snapshot.path("manifest");
        require(falseBoolean(reference.path("humanConfirmed"))&&"codex".equals(reference.path("actor").asText())
                &&"original_source_review".equals(reference.path("method").asText())
                &&sha.equals(reference.path("sourceSha256").asText()),"CANONICAL_NATIVE_REFERENCE_INVALID");
        for(Iterator<String> fields=manifest.path("reference").fieldNames();fields.hasNext();){String field=fields.next();require(reference.path(field).equals(manifest.path("reference").path(field)),"CANONICAL_NATIVE_REFERENCE_INVALID");}
        require(empty(manifest.path("assets"))&&manifest.path("scope").path("requiredPartNames").isArray()
                &&manifest.path("scope").path("requiredPartNames").size()>0&&!manifest.path("scope").has("requiredPhysicalPages"),"CANONICAL_NATIVE_SCOPE_INVALID");
        for(JsonNode name:manifest.path("scope").path("requiredPartNames"))require(name.isTextual()&&names.contains(name.asText()),"CANONICAL_NATIVE_SCOPE_INVALID");
        noPdfAliases(snapshot);noPdfAliases(identity);noPdfAliases(source);
    }

    public static void validateChunk(JsonNode chunk,String sourceSha){
        require(VERSION.equals(chunk.path("projectionVersion").asText())&&VERSION.equals(chunk.path("metadataVersion").asText())
                &&STORAGE.equals(chunk.path("storageShape").asText())&&GEOMETRY.equals(chunk.path("originalGeometryStatus").asText())
                &&empty(chunk.path("physicalPages"))&&empty(chunk.path("assetRefs")),"CANONICAL_NATIVE_CHUNK_INVALID");
        noPdfAliases(chunk);JsonNode parts=chunk.path("parts");require(parts.isArray()&&parts.size()>0,"CANONICAL_PARTS_INVALID");
        Set<String> ids=new HashSet<>();List<String> texts=new ArrayList<>();
        for(JsonNode part:parts){
            require(nonempty(part.path("blockId"))&&ids.add(part.path("blockId").asText())
                    &&part.path("nativeBlockId").equals(part.path("blockId"))&&trueBoolean(part.path("native"))
                    &&sourceSha.equals(part.path("sourceSha256").asText())&&part.path("text").isTextual()
                    &&integer(part.path("startOffset"))&&part.path("startOffset").asInt()==0
                    &&integer(part.path("endOffset"))&&part.path("endOffset").asInt()==part.path("text").asText().length()
                    &&GEOMETRY.equals(part.path("originalGeometryStatus").asText())
                    &&"native_observed_unverified".equals(part.path("candidateTextStatus").asText()),"CANONICAL_NATIVE_PART_INVALID");
            noPdfAliases(part);position(part.path("sourcePosition"),sourceSha);
            JsonNode transform=part.path("textTransform"),channels=part.path("nativeTextChannels");String channel=transform.path("textChannel").asText();
            require("native-channel-identity-utf16-v1".equals(transform.path("version").asText())&&"identity".equals(transform.path("mode").asText())
                    &&transform.path("sourceStartUtf16").equals(part.path("startOffset"))&&transform.path("sourceEndUtf16").equals(part.path("endOffset"))
                    &&channels.path(channel).equals(part.path("text"))&&part.path("nativeEditingState").isObject()
                    &&part.path("nativeLimitations").isArray()&&"unknown".equals(part.path("nativeSemanticQuality").asText()),"CANONICAL_NATIVE_CHANNEL_INVALID");
            JsonNode nodeRef=part.path("nativeNodeRef");reference(nodeRef,sourceSha);
            require(nodeRef.path("sourcePosition").equals(part.path("sourcePosition"))&&nodeRef.path("nativeNodeId").equals(part.path("blockId")),"CANONICAL_NATIVE_PART_INVALID");
            String anchor="docx-native:"+part.path("sourcePosition").path("partName").asText()+":"+part.path("sourcePosition").path("nodePath").asText();
            require(anchor.equals(part.path("anchor").asText()),"CANONICAL_NATIVE_PART_INVALID");
            reference(part.path("nativePropertiesRef"),sourceSha);reference(part.path("nativeTokensRef"),sourceSha);
            texts.add(part.path("text").asText());
        }
        require(chunk.path("content").isTextual()&&String.join("\n",texts).equals(chunk.path("content").asText())
                &&texts.stream().anyMatch(text->!text.trim().isEmpty()),"CANONICAL_NATIVE_TEXT_INVALID");
        JsonNode spans=chunk.path("sourceSpans");require(spans.isArray()&&spans.size()==parts.size(),"CANONICAL_NATIVE_SPANS_INVALID");
        for(int i=0;i<parts.size();i++){JsonNode span=spans.get(i),part=parts.get(i);noPdfAliases(span);
            for(String field:Arrays.asList("blockId","sourcePosition","startOffset","endOffset","sourceSha256"))require(span.path(field).equals(part.path(field)),"CANONICAL_NATIVE_SPANS_INVALID");}
        if("table_group".equals(chunk.path("canonicalScope").asText())){
            JsonNode group=chunk.path("tableGroup");require(group.isObject()&&empty(group.path("physicalPages"))
                    &&VISUAL.equals(group.path("visualReturnStatus").asText())&&group.path("nativeTableTopology").isArray()
                    &&group.path("nativeTableTopology").size()>0,"CANONICAL_NATIVE_TABLE_GROUP_INVALID");noPdfAliases(group);
            reference(group.path("nativeTablesRef"),sourceSha);
            require(group.path("nativeTablesRef").equals(chunk.path("adoption").path("nativeTablesRef")),"CANONICAL_NATIVE_TABLE_GROUP_INVALID");
            reference(chunk.path("adoption").path("nativeTableNodesRef"),sourceSha);
            for(JsonNode table:group.path("nativeTableTopology")){position(table.path("sourcePosition"),sourceSha);reference(table.path("nativeInventoryRef"),sourceSha);require(table.path("rows").isArray(),"CANONICAL_NATIVE_TABLE_GROUP_INVALID");
                for(JsonNode row:table.path("rows")){position(row.path("sourcePosition"),sourceSha);require(row.path("cells").isArray(),"CANONICAL_NATIVE_TABLE_GROUP_INVALID");for(JsonNode cell:row.path("cells"))position(cell.path("sourcePosition"),sourceSha);}}
        }else require(!chunk.has("tableGroup"),"CANONICAL_NATIVE_TABLE_GROUP_INVALID");
    }
    private static void reference(JsonNode ref,String source){descriptor(ref.path("artifactDescriptor"));validSha(ref.path("payloadSha256"));validSha(ref.path("sourceRevisionSha256"));validSha(ref.path("observerIdentitySha256"));require(source.equals(ref.path("sourceSha256").asText())&&ref.path("jsonPointer").isTextual(),"CANONICAL_NATIVE_PROOF_INVALID");}
    private static void descriptor(JsonNode value){validSha(value.path("sha256"));String sha=value.path("sha256").asText();require("consense.artifact-descriptor.v1".equals(value.path("schema").asText())&&"content_addressed_disk".equals(value.path("storage").asText())
            &&value.path("byteCount").isIntegralNumber()&&value.path("byteCount").asLong()>=0&&("sha256/"+sha.substring(0,2)+"/"+sha+".blob").equals(value.path("relativePath").asText())&&"exact_supplied_bytes".equals(value.path("identityScope").asText()),"CANONICAL_NATIVE_PROOF_INVALID");}
    private static void position(JsonNode value,String source){require("docx_native".equals(value.path("kind").asText())&&source.equals(value.path("sourceSha256").asText())&&nonempty(value.path("partName"))&&nonempty(value.path("nodePath"))&&value.path("nodePath").asText().startsWith("/{"),"CANONICAL_NATIVE_POSITION_INVALID");validSha(value.path("partSha256"));noPdfAliases(value);}
    private static void noPdfAliases(JsonNode value){for(String key:Arrays.asList("pageNo","physicalPageNo","physicalPageStart","physicalPageEnd","bbox","bboxConvention"))require(!value.has(key),"CANONICAL_NATIVE_PDF_ALIAS_INVALID");}
    private static boolean nonempty(JsonNode node){return node.isTextual()&&!node.asText().isEmpty();}
    private static boolean empty(JsonNode node){return node.isArray()&&node.size()==0;}
    private static boolean integer(JsonNode node){return node.isIntegralNumber()&&node.canConvertToInt();}
    private static boolean trueBoolean(JsonNode node){return node.isBoolean()&&node.asBoolean();}
    private static boolean falseBoolean(JsonNode node){return node.isBoolean()&&!node.asBoolean();}
    private static void validSha(JsonNode node){require(node.isTextual()&&node.asText().matches("[a-f0-9]{64}"),"CANONICAL_NATIVE_PROOF_INVALID");}
    private static void require(boolean ok,String reason){if(!ok)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,reason);}
}
