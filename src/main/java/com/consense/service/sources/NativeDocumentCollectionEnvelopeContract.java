package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.core.*;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntFunction;

/** Complete source-bound envelope/public reconstruction from exact bounded Python wire bytes. */
public final class NativeDocumentCollectionEnvelopeContract {
    private NativeDocumentCollectionEnvelopeContract(){}
    public static final String SCHEMA="consense.native-document-envelope-batch.v1";
    public static final String VERSION="complete-header-whole-metadata-records-v1";
    private static final String MANIFEST_SCHEMA="consense.native-document-envelope-manifest.v1";
    private static final List<String> GLOBAL=Arrays.asList("publicProjectionSha256","documentId","sourceSha256","sourceHash","projectionRevisionSha256");
    private static final List<String> ARRAYS=Arrays.asList("/assetRefs","/canonical/assetRefs","/canonical/relations","/canonical/units","/limitations",
        "/manifest/assets","/manifest/relations","/manifest/tableGroups","/manifest/units","/nativeCaptureRefs/rawParts","/unknowns");
    private static final List<String> REFS=Arrays.asList("manifest","canonical","unknowns","limitations","nativeObservationRef","nativeCaptureRefs",
        "sourceReferenceRef","captureCompleteness","nativeObservationQuality");
    // Raw tokens and exact seals are kept separately. The business tree uses
    // the same binary64/BigInteger node types as the retrieval HTTP decoder.
    private static final ObjectMapper BUSINESS=JsonUtils.mapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public static final class Document {
        public final JsonNode root,header,publicProjection;
        public final String headerJson,publicJson;
        private Document(JsonNode root,String header,String projection){this.root=root;this.headerJson=header;this.publicJson=projection;
            this.header=parse(header);this.publicProjection=parse(projection);}
    }
    static void validateManifest(JsonNode root){
        descriptor(root.path("envelopeCodeRef"));JsonNode m=root.path("completeEnvelope"),policy=root.path("batchPolicy");
        require(MANIFEST_SCHEMA.equals(m.path("schema").asText())&&VERSION.equals(m.path("version").asText())
            &&m.path("maximumBatchBytes").equals(policy.path("maximumBatchBytes"))
            &&"whole-header-core-or-complete-array-record".equals(m.path("recordBoundary").asText())
            &&"reject-without-splitting".equals(m.path("oversizeRecord").asText()),"CANONICAL_COLLECTION_ENVELOPE_MANIFEST_INVALID");
        require(sha(m.path("coreSha256"))&&sha(m.path("recordOrderSha256"))&&integer(m.path("recordCount"))&&m.path("recordCount").asInt()>0
            &&m.path("headerSha256").equals(root.path("headerRef").path("payloadSha256"))
            &&m.path("headerSha256").equals(root.path("headerRef").path("artifactDescriptor").path("sha256"))
            &&m.path("headerByteCount").equals(root.path("headerRef").path("artifactDescriptor").path("byteCount")),"CANONICAL_COLLECTION_HEADER_IDENTITY_INVALID");
        JsonNode arrays=m.path("arrays");require(arrays.isArray(),"CANONICAL_COLLECTION_ARRAY_MANIFEST_INVALID");
        String previous="";long total=1;
        for(JsonNode array:arrays){String pointer=array.path("jsonPointer").asText();
            require(ARRAYS.contains(pointer)&&pointer.compareTo(previous)>0&&integer(array.path("itemCount"))&&array.path("itemCount").asInt()>=0
                &&sha(array.path("payloadSha256")),"CANONICAL_COLLECTION_ARRAY_MANIFEST_INVALID");
            previous=pointer;total+=array.path("itemCount").asInt();require(total<=Integer.MAX_VALUE,"CANONICAL_COLLECTION_RECORD_CLOSURE_INVALID");}
        require(total==m.path("recordCount").asInt(),"CANONICAL_COLLECTION_RECORD_CLOSURE_INVALID");
        JsonNode rows=root.path("envelopeBatches");require(rows.isArray()&&rows.size()>0,"CANONICAL_COLLECTION_ENVELOPES_INVALID");
        long first=0;int ordinal=0;
        for(JsonNode row:rows){descriptor(row.path("artifactDescriptor"));
            require(integer(row.path("ordinal"))&&row.path("ordinal").asInt()==ordinal++&&integer(row.path("firstRecordOrdinal"))
                &&row.path("firstRecordOrdinal").asLong()==first&&integer(row.path("recordCount"))&&row.path("recordCount").asInt()>0
                &&sha(row.path("recordOrderSha256"))&&sha(row.path("projectionSha256"))
                &&row.path("artifactDescriptor").path("byteCount").asLong()<=policy.path("maximumBatchBytes").asInt(),"CANONICAL_COLLECTION_ENVELOPE_ORDER_INVALID");
            first+=row.path("recordCount").asInt();require(first<=Integer.MAX_VALUE,"CANONICAL_COLLECTION_RECORD_CLOSURE_INVALID");}
        require(first==total,"CANONICAL_COLLECTION_RECORD_CLOSURE_INVALID");
    }
    static Document read(String rootRaw,String address,IntFunction<byte[]> chunksReader,IntFunction<byte[]> envelopeReader){
        try{return consume(rootRaw,address,chunksReader,envelopeReader);}
        catch(CanonicalEvidenceException known){throw known;}
        catch(RuntimeException malformed){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_COLLECTION_COMPLETE_WIRE_INVALID");}
    }
    private static Document consume(String rootRaw,String address,IntFunction<byte[]> chunksReader,IntFunction<byte[]> envelopeReader){
        JsonNode root=NativeDocumentCollectionContract.validateRoot(rootRaw,address),m=root.path("completeEnvelope");
        Map<String,String> rootFields=CanonicalJson.rawFields(rootRaw);
        List<String> records=new ArrayList<>();
        for(JsonNode description:root.path("envelopeBatches")){
            byte[] bytes=envelopeReader.apply(description.path("ordinal").asInt());String raw=verifiedBytes(root,description,bytes);
            JsonNode batch=parse(raw);
            require(SCHEMA.equals(batch.path("schema").asText())&&VERSION.equals(batch.path("version").asText())
                &&description.path("projectionSha256").equals(batch.path("projectionSha256"))
                &&description.path("projectionSha256").asText().equals(CanonicalJson.hash(raw,"projectionSha256")),"CANONICAL_COLLECTION_ENVELOPE_SEAL_INVALID");
            for(String field:GLOBAL)require(root.path(field).equals(batch.path(field)),"CANONICAL_COLLECTION_ENVELOPE_IDENTITY_INVALID");
            for(String field:Arrays.asList("ordinal","firstRecordOrdinal","recordCount","recordOrderSha256"))
                require(description.path(field).equals(batch.path(field)),"CANONICAL_COLLECTION_ENVELOPE_ORDER_INVALID");
            List<String> batchRecords=CanonicalJson.rawElements(CanonicalJson.rawFields(raw).get("records"));
            require(batch.path("headerSha256").equals(m.path("headerSha256"))&&batchRecords.size()==description.path("recordCount").asInt()
                &&orderHash(batchRecords).equals(description.path("recordOrderSha256").asText()),"CANONICAL_COLLECTION_ENVELOPE_RECORD_INVALID");
            records.addAll(batchRecords);
        }
        require(records.size()==m.path("recordCount").asInt()&&orderHash(records).equals(m.path("recordOrderSha256").asText()),"CANONICAL_COLLECTION_RECORD_CLOSURE_INVALID");
        JsonNode first=parse(records.get(0));Map<String,String> coreRecord=CanonicalJson.rawFields(records.get(0));String headerRaw=coreRecord.get("value");
        require("header_core".equals(first.path("kind").asText())&&first.path("jsonPointer").isTextual()&&first.path("jsonPointer").asText().isEmpty()
            &&integer(first.path("itemOrdinal"))&&first.path("itemOrdinal").asInt()==0&&digest(headerRaw).equals(m.path("coreSha256").asText()),"CANONICAL_COLLECTION_HEADER_CORE_INVALID");
        int cursor=1;
        for(JsonNode array:m.path("arrays")){String pointer=array.path("jsonPointer").asText();
            require("[]".equals(rawAt(headerRaw,pointer)),"CANONICAL_COLLECTION_HEADER_CORE_ARRAY_INVALID");List<String> values=new ArrayList<>();
            for(int ordinal=0;ordinal<array.path("itemCount").asInt();ordinal++){
                String raw=records.get(cursor++);JsonNode record=parse(raw);
                require("array_item".equals(record.path("kind").asText())&&pointer.equals(record.path("jsonPointer").asText())
                    &&integer(record.path("itemOrdinal"))&&ordinal==record.path("itemOrdinal").asInt(),"CANONICAL_COLLECTION_ARRAY_ORDER_INVALID");
                values.add(CanonicalJson.rawFields(raw).get("value"));}
            String payload=CanonicalJson.rawArray(values);require(digest(payload).equals(array.path("payloadSha256").asText()),"CANONICAL_COLLECTION_ARRAY_PAYLOAD_INVALID");
            headerRaw=replace(headerRaw,pointer,payload);
        }
        require(cursor==records.size(),"CANONICAL_COLLECTION_RECORD_CLOSURE_INVALID");
        byte[] headerBytes=headerRaw.getBytes(StandardCharsets.UTF_8);JsonNode headerDescriptor=root.path("headerRef").path("artifactDescriptor");
        require(headerBytes.length==headerDescriptor.path("byteCount").asLong()&&CanonicalJson.sha(headerBytes).equals(headerDescriptor.path("sha256").asText()),"CANONICAL_COLLECTION_COMPLETE_HEADER_INVALID");
        JsonNode header=parse(headerRaw);List<String> expectedRecords=new ArrayList<>(),arrayManifest=new ArrayList<>();String core=headerRaw;
        for(String pointer:ARRAYS){JsonNode value=header.at(pointer);if(value.isMissingNode())continue;
            require(value.isArray(),"CANONICAL_COLLECTION_ARRAY_MANIFEST_INVALID");String raw=rawAt(headerRaw,pointer);
            List<String> values=CanonicalJson.rawElements(raw);Map<String,String> descriptor=new HashMap<>();descriptor.put("jsonPointer",CanonicalJson.scalar(pointer));
            descriptor.put("itemCount",String.valueOf(values.size()));descriptor.put("payloadSha256",CanonicalJson.scalar(digest(raw)));arrayManifest.add(CanonicalJson.rawObject(descriptor));
            core=replace(core,pointer,"[]");}
        expectedRecords.add(record("header_core","",0,core));
        for(String pointer:ARRAYS){if(header.at(pointer).isMissingNode())continue;int ordinal=0;
            for(String value:CanonicalJson.rawElements(rawAt(headerRaw,pointer)))expectedRecords.add(record("array_item",pointer,ordinal++,value));}
        require(records.equals(expectedRecords)&&CanonicalJson.rawArray(arrayManifest).equals(CanonicalJson.rawFields(rootFields.get("completeEnvelope")).get("arrays")),"CANONICAL_COLLECTION_COMPLETE_RECORD_MANIFEST_INVALID");
        List<String> chunks=new ArrayList<>(),unknowns=new ArrayList<>();
        for(JsonNode description:root.path("batches")){byte[] bytes=chunksReader.apply(description.path("ordinal").asInt());
            NativeDocumentCollectionContract.readBatch(root,description,bytes);Map<String,String> fields=CanonicalJson.rawFields(new String(bytes,StandardCharsets.UTF_8));
            chunks.addAll(CanonicalJson.rawElements(fields.get("chunks")));unknowns.addAll(CanonicalJson.rawElements(fields.get("unknowns")));}
        Map<String,String> publicFields=CanonicalJson.rawFields(headerRaw);
        require(CanonicalJson.rawArray(unknowns).equals(publicFields.get("unknowns")),"CANONICAL_COLLECTION_UNKNOWNS_INVALID");
        publicFields.put("chunks",CanonicalJson.rawArray(chunks));String publicRaw=CanonicalJson.rawObject(publicFields);JsonNode projection=parse(publicRaw);
        require(projection.path("projectionSha256").equals(root.path("publicProjectionSha256"))
            &&CanonicalJson.hash(publicRaw,"projectionSha256").equals(root.path("publicProjectionSha256").asText()),"CANONICAL_COLLECTION_COMPLETE_PUBLIC_INVALID");
        for(String field:GLOBAL){JsonNode actual="publicProjectionSha256".equals(field)?projection.path("projectionSha256")
                :"documentId".equals(field)?projection.path("projectionIdentity").path("documentId")
                :"sourceSha256".equals(field)?projection.path("source").path("sourceSha256"):projection.path(field);
            require(root.path(field).equals(actual),"CANONICAL_COLLECTION_PUBLIC_IDENTITY_INVALID");}
        for(String field:Arrays.asList("projectionIdentity","source","observerIdentity","transportCodeRef","internalProjectionRef","nativeObservationQuality",
                "humanConfirmed","semanticScopeVerified","visualReturnStatus"))require(Objects.equals(rootFields.get(field),publicFields.get(field)),"CANONICAL_COLLECTION_PUBLIC_METADATA_INVALID");
        Map<String,String> manifest=CanonicalJson.rawFields(publicFields.get("manifest")),metadata=new HashMap<>(manifest);
        for(String field:Arrays.asList("units","tableGroups","relations","assets"))metadata.remove(field);
        require(CanonicalJson.rawObject(metadata).equals(rootFields.get("manifestMetadata")),"CANONICAL_COLLECTION_MANIFEST_METADATA_INVALID");
        Map<String,String> reference=new HashMap<>();reference.put("artifactDescriptor",descriptorRaw(headerBytes));reference.put("jsonPointer","\"\"");reference.put("payloadSha256",CanonicalJson.scalar(digest(headerRaw)));
        for(String field:GLOBAL)reference.put(field,rootFields.get(field));
        require(CanonicalJson.rawObject(reference).equals(rootFields.get("headerRef")),"CANONICAL_COLLECTION_HEADER_REFERENCE_INVALID");
        Map<String,String> references=CanonicalJson.rawFields(rootFields.get("envelopeRefs"));
        for(String field:REFS){reference.put("jsonPointer",CanonicalJson.scalar("/"+field));reference.put("payloadSha256",CanonicalJson.scalar(digest(publicFields.get(field))));
            require(CanonicalJson.rawObject(reference).equals(references.get(field)),"CANONICAL_COLLECTION_ENVELOPE_REFERENCE_INVALID");}
        require(digest(publicFields.get("manifest")).equals(root.path("manifestSha256").asText())
            &&root.path("manifestSha256").equals(projection.path("projectionIdentity").path("manifestSha256")),"CANONICAL_COLLECTION_MANIFEST_IDENTITY_INVALID");
        Map<String,String> canonical=CanonicalJson.rawFields(publicFields.get("canonical"));JsonNode closure=root.path("canonicalClosure");
        require(digest(canonical.get("units")).equals(closure.path("commitmentsSha256").asText())
            &&CanonicalJson.rawElements(canonical.get("relations")).size()==closure.path("relationCount").asInt()
            &&digest(canonical.get("relations")).equals(closure.path("relationsSha256").asText())
            &&digest(publicFields.get("canonical")).equals(closure.path("canonicalSha256").asText()),"CANONICAL_COLLECTION_CANONICAL_CLOSURE_INVALID");
        List<String> commitments=new ArrayList<>();int previousOrder=-1;
        for(String chunkRaw:chunks){JsonNode chunk=parse(chunkRaw);Map<String,String> body=CanonicalJson.rawFields(chunkRaw);
            require(integer(chunk.path("sourceOrder"))&&chunk.path("sourceOrder").asInt()>previousOrder,"CANONICAL_COLLECTION_SOURCE_ORDER_INVALID");previousOrder=chunk.path("sourceOrder").asInt();
            Map<String,String> id=new HashMap<>();id.put("revision",rootFields.get("projectionRevisionSha256"));id.put("unitId",body.get("unitId"));
            require(("canonical:"+digest(CanonicalJson.rawObject(id))).equals(chunk.path("id").asText()),"CANONICAL_COLLECTION_CHUNK_IDENTITY_INVALID");
            body.remove("id");body.remove("sourceHash");body.remove("projectionRevisionSha256");Map<String,String> unit=new HashMap<>();
            unit.put("unitId",body.get("unitId"));unit.put("scope",body.get("canonicalScope"));unit.put("partsSha256",CanonicalJson.scalar(digest(body.get("parts"))));
            unit.put("adoptionSha256",CanonicalJson.scalar(digest(body.get("adoption"))));unit.put("chunkPreIdentitySha256",CanonicalJson.scalar(digest(CanonicalJson.rawObject(body))));
            if(body.containsKey("tableGroup"))unit.put("tableGroupSha256",CanonicalJson.scalar(digest(body.get("tableGroup"))));commitments.add(CanonicalJson.rawObject(unit));}
        require(CanonicalJson.rawArray(commitments).equals(canonical.get("units")),"CANONICAL_COLLECTION_CHUNK_COMMITMENTS_INVALID");
        Map<String,String> sourceHash=new HashMap<>();sourceHash.put("projectionIdentity",publicFields.get("projectionIdentity"));sourceHash.put("canonical",publicFields.get("canonical"));
        require(digest(CanonicalJson.rawObject(sourceHash)).equals(root.path("sourceHash").asText()),"CANONICAL_COLLECTION_SOURCE_HASH_INVALID");
        Map<String,String> revision=new HashMap<>();revision.put("sourceHash",rootFields.get("sourceHash"));revision.put("projectionIdentity",publicFields.get("projectionIdentity"));
        require(digest(CanonicalJson.rawObject(revision)).equals(root.path("projectionRevisionSha256").asText()),"CANONICAL_COLLECTION_REVISION_INVALID");
        NativeCanonicalContract.validateEnvelope(projection);return new Document(root,headerRaw,publicRaw);
    }
    private static String verifiedBytes(JsonNode root,JsonNode description,byte[] bytes){
        require(bytes!=null&&bytes.length<=NativeDocumentCollectionContract.MAX_BYTES&&bytes.length<=root.path("batchPolicy").path("maximumBatchBytes").asInt()
            &&bytes.length==description.path("artifactDescriptor").path("byteCount").asLong()
            &&CanonicalJson.sha(bytes).equals(description.path("artifactDescriptor").path("sha256").asText()),"CANONICAL_COLLECTION_ENVELOPE_BYTES_INVALID");
        String raw=new String(bytes,StandardCharsets.UTF_8);require(Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8)),"CANONICAL_JSON_INVALID");CanonicalJson.requireCanonical(raw);return raw;
    }
    private static String orderHash(List<String> records){List<String> identities=new ArrayList<>();
        for(String raw:records){JsonNode row=parse(raw);require(row.path("kind").isTextual()&&row.path("jsonPointer").isTextual()&&integer(row.path("itemOrdinal"))&&row.has("value"),"CANONICAL_COLLECTION_RECORD_INVALID");
            Map<String,String> value=CanonicalJson.rawFields(raw),identity=new HashMap<>();for(String key:Arrays.asList("kind","jsonPointer","itemOrdinal"))identity.put(key,value.get(key));
            identity.put("payloadSha256",CanonicalJson.scalar(digest(value.get("value"))));identities.add(CanonicalJson.rawObject(identity));}
        return digest(CanonicalJson.rawArray(identities));}
    private static String record(String kind,String pointer,int ordinal,String value){Map<String,String> row=new HashMap<>();row.put("kind",CanonicalJson.scalar(kind));row.put("jsonPointer",CanonicalJson.scalar(pointer));row.put("itemOrdinal",String.valueOf(ordinal));row.put("value",value);return CanonicalJson.rawObject(row);}
    private static String rawAt(String raw,String pointer){String value=raw;for(String name:pointer.substring(1).split("/"))value=CanonicalJson.rawFields(value).get(name);return value;}
    private static String replace(String raw,String pointer,String value){return replacePath(raw,pointer.substring(1).split("/"),0,value);}
    private static String replacePath(String raw,String[] path,int index,String value){Map<String,String> fields=CanonicalJson.rawFields(raw);require(fields.containsKey(path[index]),"CANONICAL_COLLECTION_ARRAY_PATH_INVALID");
        fields.put(path[index],index==path.length-1?value:replacePath(fields.get(path[index]),path,index+1,value));return CanonicalJson.rawObject(fields);}
    private static String descriptorRaw(byte[] bytes){String sha=CanonicalJson.sha(bytes);Map<String,String> fields=new HashMap<>();fields.put("schema","\"consense.artifact-descriptor.v1\"");fields.put("storage","\"content_addressed_disk\"");
        fields.put("identityScope","\"exact_supplied_bytes\"");fields.put("sha256",CanonicalJson.scalar(sha));fields.put("byteCount",String.valueOf(bytes.length));fields.put("relativePath",CanonicalJson.scalar("sha256/"+sha.substring(0,2)+"/"+sha+".blob"));return CanonicalJson.rawObject(fields);}
    private static void descriptor(JsonNode value){require(value.isObject()&&sha(value.path("sha256")),"CANONICAL_COLLECTION_DESCRIPTOR_INVALID");String sha=value.path("sha256").asText();
        require("consense.artifact-descriptor.v1".equals(value.path("schema").asText())&&"content_addressed_disk".equals(value.path("storage").asText())
            &&"exact_supplied_bytes".equals(value.path("identityScope").asText())&&value.path("byteCount").isIntegralNumber()&&value.path("byteCount").canConvertToLong()&&value.path("byteCount").asLong()>0
            &&("sha256/"+sha.substring(0,2)+"/"+sha+".blob").equals(value.path("relativePath").asText()),"CANONICAL_COLLECTION_DESCRIPTOR_INVALID");}
    private static JsonNode parse(String raw){try{return BUSINESS.readTree(raw);}
        catch(Exception invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}}
    private static String digest(String raw){return CanonicalJson.sha(raw.getBytes(StandardCharsets.UTF_8));}
    private static boolean integer(JsonNode value){return value.isIntegralNumber()&&value.canConvertToInt();}
    private static boolean sha(JsonNode value){return value.isTextual()&&value.asText().matches("[a-f0-9]{64}");}
    private static void require(boolean condition,String reason){if(!condition)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,reason);}
}
