package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntFunction;

/** Bounded document root and whole-unit batches. The root never becomes a forged old public snapshot. */
public final class NativeDocumentCollectionContract {
    private NativeDocumentCollectionContract(){}
    public static final String SCHEMA="consense.native-document.v1";
    public static final String BATCH_SCHEMA="consense.native-document-batch.v1";
    public static final String VERSION="whole-native-units-ordered-cas-document-v1";
    public static final int MAX_BYTES=16777216;
    private static final List<String> GLOBAL=Arrays.asList("publicProjectionSha256","documentId","sourceSha256","sourceHash","projectionRevisionSha256");
    private static final List<String> REFERENCES=Arrays.asList("manifest","canonical","unknowns","limitations","nativeObservationRef",
            "nativeCaptureRefs","sourceReferenceRef","captureCompleteness","nativeObservationQuality");
    private static final List<String> ALGORITHMS=Arrays.asList("native_projection_collection.py","native_collection_registry.py","oer_native_collection_server.py","native_collection_envelope.py");

    public static boolean isCollection(JsonNode snapshot){return SCHEMA.equals(snapshot.path("schema").asText());}
    public static void validateCapability(JsonNode oer,JsonNode algorithms){
        JsonNode capability=oer.path("nativeDocumentCollection");
        require(SCHEMA.equals(capability.path("schema").asText())&&BATCH_SCHEMA.equals(capability.path("batchSchema").asText())
                &&"/project/native-collection".equals(capability.path("projectionEndpoint").asText())
                &&"/canonical/{projectionSha256}/batches/{ordinal}".equals(capability.path("batchEndpoint").asText())
                &&"one-document-root".equals(capability.path("registrationIdentity").asText())
                &&integer(capability.path("maximumResponseBytes"))&&capability.path("maximumResponseBytes").asInt()==MAX_BYTES
                &&"whole-adopted-unit-source-order".equals(capability.path("unitPolicy").asText())
                &&NativeCanonicalContract.actualArtifactReadPolicy(capability.path("artifactReadValidation"))
                &&NativeDocumentCollectionEnvelopeContract.SCHEMA.equals(capability.path("envelopeBatchSchema").asText())
                &&NativeDocumentCollectionEnvelopeContract.VERSION.equals(capability.path("envelopeVersion").asText())
                &&"/canonical/{projectionSha256}/envelopes/{ordinal}".equals(capability.path("envelopeBatchEndpoint").asText())
                &&"root-enumerated-whole-metadata-record-batches".equals(capability.path("completeHeaderTransport").asText()),"OER_NATIVE_COLLECTION_CAPABILITY_MISMATCH");
        for(String name:ALGORITHMS)require(sha(algorithms.path(name)),"OER_NATIVE_COLLECTION_ALGORITHM_IDENTITY_MISMATCH");
    }

    public static JsonNode validateRoot(String raw,String address){
        require(raw!=null&&raw.getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES,"CANONICAL_COLLECTION_ROOT_TOO_LARGE");
        require(address!=null&&address.matches("[a-f0-9]{64}"),"CANONICAL_COLLECTION_IDENTITY_INVALID");
        CanonicalJson.requireCanonical(raw);
        JsonNode root=parse(raw);
        require(root.isObject()&&isCollection(root)&&VERSION.equals(root.path("version").asText())&&sha(root.path("projectionSha256"))
                &&address.equals(root.path("projectionSha256").asText())&&address.equals(CanonicalJson.hash(raw,"projectionSha256")),"CANONICAL_COLLECTION_SEAL_MISMATCH");
        for(String field:GLOBAL)require("documentId".equals(field)?nonempty(root.path(field)):sha(root.path(field)),"CANONICAL_COLLECTION_IDENTITY_INVALID");
        require(!root.path("publicProjectionSha256").equals(root.path("projectionSha256")),"CANONICAL_COLLECTION_IDENTITY_INVALID");
        JsonNode identity=root.path("projectionIdentity"),source=root.path("source"),manifest=root.path("manifestMetadata");
        require(identity.isObject()&&source.isObject()&&manifest.isObject()
                &&"manifest-without-units-tableGroups-relations-assets-v1".equals(root.path("manifestMetadataShape").asText())
                &&NativeCanonicalContract.PRODUCER.equals(identity.path("producerKind").asText())
                &&NativeCanonicalContract.STORAGE.equals(identity.path("storageShape").asText())
                &&NativeCanonicalContract.VERSION.equals(identity.path("version").asText()),"CANONICAL_COLLECTION_ENVELOPE_INVALID");
        for(String field:Arrays.asList("documentId","sourceSha256"))require(root.path(field).equals(identity.path(field)),"CANONICAL_COLLECTION_IDENTITY_INVALID");
        require(root.path("sourceSha256").equals(source.path("sourceSha256"))&&identity.path("sourceRevisionSha256").equals(source.path("sourceRevisionSha256"))
                &&sha(source.path("sourceRevisionSha256"))&&nonempty(source.path("sourceFilename"))
                &&"application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(source.path("mediaType").asText()),"CANONICAL_COLLECTION_SOURCE_INVALID");
        for(String field:Arrays.asList("documentId","sourceSha256","sourceRevisionSha256","module","role","sourcePurpose","sourceClass","eligibleModules"))
            require(identity.path(field).equals(manifest.path(field)),"CANONICAL_COLLECTION_MANIFEST_INVALID");
        require(modules(identity.path("eligibleModules")).contains(identity.path("module").asText()),"CANONICAL_COLLECTION_MANIFEST_INVALID");
        for(String field:Arrays.asList("units","tableGroups","relations","assets"))require(!manifest.has(field),"CANONICAL_COLLECTION_MANIFEST_INVALID");
        require(sha(root.path("manifestSha256"))&&root.path("manifestSha256").equals(identity.path("manifestSha256"))
                &&sha(manifest.path("scope").path("scopePolicySha256"))
                &&manifest.path("scope").path("requiredPartNames").isArray()&&manifest.path("scope").path("requiredPartNames").size()>0
                &&"unknown".equals(manifest.path("quality").path("semanticQuality").asText())
                &&"codex".equals(manifest.path("reference").path("actor").asText())
                &&"original_source_review".equals(manifest.path("reference").path("method").asText())
                &&root.path("sourceSha256").equals(manifest.path("reference").path("sourceSha256"))
                &&sha(manifest.path("reference").path("evidenceSha256")),"CANONICAL_COLLECTION_MANIFEST_INVALID");
        require(falseBoolean(root.path("humanConfirmed"))&&falseBoolean(root.path("semanticScopeVerified"))
                &&NativeCanonicalContract.VISUAL.equals(root.path("visualReturnStatus").asText())
                &&!root.has("chunks")&&!root.has("assetRefs")&&!root.has("manifest"),"CANONICAL_COLLECTION_ENVELOPE_INVALID");
        descriptor(root.path("collectionCodeRef"));descriptor(root.path("transportCodeRef"));
        require(root.path("transportCodeRef").path("sha256").equals(identity.path("projectionCodeSha256")),"CANONICAL_COLLECTION_REFERENCE_INVALID");
        JsonNode internal=root.path("internalProjectionRef");descriptor(internal.path("artifactDescriptor"));
        require(internal.path("artifactDescriptor").path("sha256").equals(identity.path("internalSnapshotBytesSha256"))
                &&internal.path("projectionSha256").equals(identity.path("internalProjectionSha256")),"CANONICAL_COLLECTION_REFERENCE_INVALID");
        JsonNode header=root.path("headerRef");reference(root,header,"");
        require(header.path("payloadSha256").equals(header.path("artifactDescriptor").path("sha256")),"CANONICAL_COLLECTION_REFERENCE_INVALID");
        for(String name:REFERENCES){JsonNode ref=root.path("envelopeRefs").path(name);reference(root,ref,"/"+name);
            require(ref.path("artifactDescriptor").equals(header.path("artifactDescriptor")),"CANONICAL_COLLECTION_REFERENCE_INVALID");}
        require(root.path("manifestSha256").equals(root.path("envelopeRefs").path("manifest").path("payloadSha256"))
                &&CanonicalJson.fieldHash(raw,"nativeObservationQuality").equals(root.path("envelopeRefs").path("nativeObservationQuality").path("payloadSha256").asText()),"CANONICAL_COLLECTION_REFERENCE_INVALID");
        JsonNode policy=root.path("batchPolicy"),closure=root.path("canonicalClosure"),validation=root.path("transportValidation");
        require(integer(policy.path("maximumBatchBytes"))&&policy.path("maximumBatchBytes").asInt()>0&&policy.path("maximumBatchBytes").asInt()<=MAX_BYTES
                &&integer(policy.path("maximumRootBytes"))&&policy.path("maximumRootBytes").asInt()==MAX_BYTES
                &&"complete-adopted-native-unit".equals(policy.path("unitBoundary").asText())
                &&"unchanged-public-chunk-order".equals(policy.path("sourceOrder").asText())
                &&"reject-without-splitting".equals(policy.path("oversizeUnit").asText()),"CANONICAL_COLLECTION_BATCH_POLICY_INVALID");
        require("cold-full-source-cas-every-collection-read".equals(validation.path("mode").asText())
                &&"not_implemented".equals(validation.path("derivedValidationCache").asText())&&falseBoolean(validation.path("qualityCertified"))
                &&"internal-memory-only-not-a-bounded-wire-snapshot".equals(validation.path("fullPublicHydration").asText()),"CANONICAL_COLLECTION_PROOF_INVALID");
        require(integer(closure.path("unitCount"))&&closure.path("unitCount").asInt()>0&&integer(closure.path("relationCount"))&&closure.path("relationCount").asInt()>=0
                &&sha(closure.path("commitmentsSha256"))&&sha(closure.path("relationsSha256"))&&sha(closure.path("canonicalSha256"))
                &&closure.path("canonicalSha256").equals(root.path("envelopeRefs").path("canonical").path("payloadSha256")),"CANONICAL_COLLECTION_CLOSURE_INVALID");
        JsonNode batches=root.path("batches");require(batches.isArray()&&batches.size()>0,"CANONICAL_COLLECTION_BATCHES_INVALID");
        ArrayNode ordered=JsonUtils.mapper().createArrayNode();Set<String> ids=new HashSet<>();int ordinal=0,first=0;
        for(JsonNode batch:batches){
            require(integer(batch.path("ordinal"))&&batch.path("ordinal").asInt()==ordinal++
                    &&integer(batch.path("firstUnitOrdinal"))&&batch.path("firstUnitOrdinal").asInt()==first
                    &&integer(batch.path("unitCount"))&&batch.path("unitCount").asInt()>0&&sha(batch.path("projectionSha256")),"CANONICAL_COLLECTION_BATCH_ORDER_INVALID");
            descriptor(batch.path("artifactDescriptor"));
            require(batch.path("artifactDescriptor").path("byteCount").asLong()<=policy.path("maximumBatchBytes").asInt()
                    &&batch.path("unitIds").isArray()&&batch.path("unitIds").size()==batch.path("unitCount").asInt(),"CANONICAL_COLLECTION_BATCHES_INVALID");
            for(JsonNode id:batch.path("unitIds")){require(nonempty(id)&&ids.add(id.asText()),"CANONICAL_COLLECTION_UNIT_DUPLICATE");ordered.add(id.deepCopy());}
            require(CanonicalJson.hash(JsonUtils.write(batch.path("unitIds")),null).equals(batch.path("unitOrderSha256").asText()),"CANONICAL_COLLECTION_UNIT_ORDER_INVALID");
            require(first<=Integer.MAX_VALUE-batch.path("unitCount").asInt(),"CANONICAL_COLLECTION_CLOSURE_INVALID");first+=batch.path("unitCount").asInt();
        }
        require(first==closure.path("unitCount").asInt()&&CanonicalJson.hash(JsonUtils.write(ordered),null).equals(closure.path("unitOrderSha256").asText()),"CANONICAL_COLLECTION_CLOSURE_INVALID");
        NativeDocumentCollectionEnvelopeContract.validateManifest(root);
        return root;
    }

    /** Full remote wire consumption; its public JSON is internal memory only, never a >cap response. */
    public static NativeDocumentCollectionEnvelopeContract.Document readDocument(String raw,String address,
            IntFunction<byte[]> batchReader,IntFunction<byte[]> envelopeReader){
        return NativeDocumentCollectionEnvelopeContract.read(raw,address,batchReader,envelopeReader);
    }

    /** Return original ordered chunks only; no header, root or document identifier is rewritten. */
    public static JsonNode readChunks(JsonNode root,IntFunction<byte[]> reader){
        ArrayNode chunks=JsonUtils.mapper().createArrayNode();Set<String> ids=new HashSet<>();
        for(JsonNode description:root.path("batches")){
            JsonNode batch=readBatch(root,description,reader.apply(description.path("ordinal").asInt()));
            for(JsonNode chunk:batch.path("chunks")){require(ids.add(chunk.path("id").asText()),"CANONICAL_COLLECTION_CHUNK_DUPLICATE");chunks.add(chunk.deepCopy());}
        }
        require(chunks.size()==root.path("canonicalClosure").path("unitCount").asInt(),"CANONICAL_COLLECTION_CLOSURE_INVALID");return chunks;
    }
    public static JsonNode readUnitBatch(JsonNode root,String unitId,IntFunction<byte[]> reader){return unitBatch(root,unitId,reader).path("chunks");}
    public static JsonNode unitUnknowns(JsonNode batch,String unitId){
        ArrayNode out=JsonUtils.mapper().createArrayNode();for(JsonNode unknown:batch.path("unknowns"))if(unitId.equals(unknown.path("unitId").asText()))out.add(unknown.deepCopy());
        require(out.size()==1,"CANONICAL_COLLECTION_UNKNOWNS_INVALID");return out;
    }
    public static JsonNode unitBatch(JsonNode root,String unitId,IntFunction<byte[]> reader){
        JsonNode selected=null;for(JsonNode description:root.path("batches"))for(JsonNode id:description.path("unitIds"))if(unitId.equals(id.asText())){
            require(selected==null,"CANONICAL_COLLECTION_UNIT_DUPLICATE");selected=description;
        }
        if(selected==null)throw new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"CANONICAL_UNIT_UNAVAILABLE");
        return readBatch(root,selected,reader.apply(selected.path("ordinal").asInt()));
    }
    static JsonNode readBatch(JsonNode root,JsonNode description,byte[] bytes){
        require(bytes!=null&&bytes.length<=MAX_BYTES&&bytes.length<=root.path("batchPolicy").path("maximumBatchBytes").asInt()
                &&bytes.length==description.path("artifactDescriptor").path("byteCount").asLong()
                &&CanonicalJson.sha(bytes).equals(description.path("artifactDescriptor").path("sha256").asText()),"CANONICAL_COLLECTION_BATCH_BYTES_MISMATCH");
        String raw=new String(bytes,StandardCharsets.UTF_8);JsonNode batch=parse(raw);
        require(Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8)),"CANONICAL_JSON_INVALID");
        CanonicalJson.requireCanonical(raw);
        require(BATCH_SCHEMA.equals(batch.path("schema").asText())&&VERSION.equals(batch.path("version").asText())
                &&description.path("projectionSha256").equals(batch.path("projectionSha256"))
                &&description.path("projectionSha256").asText().equals(CanonicalJson.hash(raw,"projectionSha256")),"CANONICAL_COLLECTION_BATCH_SEAL_MISMATCH");
        for(String field:GLOBAL)require(root.path(field).equals(batch.path(field)),"CANONICAL_COLLECTION_BATCH_IDENTITY_INVALID");
        for(String field:Arrays.asList("ordinal","firstUnitOrdinal","unitCount","unitOrderSha256"))require(description.path(field).equals(batch.path(field)),"CANONICAL_COLLECTION_BATCH_ORDER_INVALID");
        require(batch.path("chunks").isArray()&&batch.path("chunks").size()==description.path("unitCount").asInt()
                &&batch.path("unknowns").isArray()&&batch.path("unknowns").size()==description.path("unitCount").asInt(),"CANONICAL_COLLECTION_BATCH_COUNT_INVALID");
        int index=0;Set<String> ids=new HashSet<>();JsonNode identity=root.path("projectionIdentity");
        for(JsonNode chunk:batch.path("chunks")){
            JsonNode unknown=batch.path("unknowns").get(index);
            require(unknown.path("unitId").equals(chunk.path("unitId"))&&unknown.path("attributes").isObject(),"CANONICAL_COLLECTION_UNKNOWNS_INVALID");
            for(String field:Arrays.asList("blankMeaning","crossPageRelations","editState","headerRelations","qualifiersComplete","semanticQuality"))
                require("unknown".equals(unknown.path("attributes").path(field).asText()),"CANONICAL_COLLECTION_UNKNOWNS_INVALID");
            require(chunk.path("unitId").equals(description.path("unitIds").get(index++))&&chunk.path("id").asText().matches("canonical:[a-f0-9]{64}")
                    &&ids.add(chunk.path("id").asText())&&falseBoolean(chunk.path("humanConfirmed")),"CANONICAL_COLLECTION_UNIT_ORDER_INVALID");
            for(String field:Arrays.asList("documentId","sourceSha256","sourceHash","projectionRevisionSha256"))require(root.path(field).equals(chunk.path(field)),"CANONICAL_COLLECTION_CHUNK_IDENTITY_INVALID");
            for(String field:Arrays.asList("sourceRevisionSha256","module","role","sourcePurpose","sourceClass","eligibleModules"))require(identity.path(field).equals(chunk.path(field)),"CANONICAL_COLLECTION_CHUNK_IDENTITY_INVALID");
            require(root.path("sourceSha256").equals(chunk.path("originalSourceSha256"))&&"unknown".equals(chunk.path("sourceQuality").path("semanticQuality").asText()),"CANONICAL_COLLECTION_CHUNK_IDENTITY_INVALID");
            NativeCanonicalContract.validateChunk(chunk,root.path("sourceSha256").asText());
        }return batch;
    }
    private static void reference(JsonNode root,JsonNode reference,String pointer){
        descriptor(reference.path("artifactDescriptor"));require(pointer.equals(reference.path("jsonPointer").asText())&&sha(reference.path("payloadSha256")),"CANONICAL_COLLECTION_REFERENCE_INVALID");
        for(String field:GLOBAL)require(root.path(field).equals(reference.path(field)),"CANONICAL_COLLECTION_REFERENCE_INVALID");
    }
    private static void descriptor(JsonNode value){
        String sha=value.path("sha256").asText();require(value.isObject()&&"consense.artifact-descriptor.v1".equals(value.path("schema").asText())
                &&"content_addressed_disk".equals(value.path("storage").asText())&&sha(value.path("sha256"))
                &&"exact_supplied_bytes".equals(value.path("identityScope").asText())
                &&value.path("byteCount").isIntegralNumber()&&value.path("byteCount").canConvertToLong()&&value.path("byteCount").asLong()>0
                &&("sha256/"+sha.substring(0,2)+"/"+sha+".blob").equals(value.path("relativePath").asText()),"CANONICAL_COLLECTION_DESCRIPTOR_INVALID");
    }
    private static Set<String> modules(JsonNode value){require(value.isArray()&&value.size()>0,"CANONICAL_COLLECTION_MODULE_INVALID");Set<String> out=new HashSet<>();
        for(JsonNode module:value)require(module.isTextual()&&Arrays.asList("drafting","vetting","advisory").contains(module.asText())&&out.add(module.asText()),"CANONICAL_COLLECTION_MODULE_INVALID");return out;}
    private static boolean falseBoolean(JsonNode value){return value.isBoolean()&&!value.asBoolean();}
    private static boolean integer(JsonNode value){return value.isIntegralNumber()&&value.canConvertToInt();}
    private static boolean nonempty(JsonNode value){return value.isTextual()&&!value.asText().trim().isEmpty();}
    private static boolean sha(JsonNode value){return value.isTextual()&&value.asText().matches("[a-f0-9]{64}");}
    private static JsonNode parse(String raw){try{return JsonUtils.parse(raw);}catch(RuntimeException invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}}
    private static void require(boolean condition,String reason){if(!condition)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,reason);}
}
