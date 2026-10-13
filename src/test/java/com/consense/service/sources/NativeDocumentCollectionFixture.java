package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Exact Python collection built from the same real observer-authored DOCX as NativeCanonicalFixture. */
public final class NativeDocumentCollectionFixture {
    private NativeDocumentCollectionFixture(){}
    public static final String ROOT_BYTES_SHA="cea8aedae8a14e747530b4c1c5cc3397525b465f89e1d395404a30eed6e02595";
    public static String rootRaw() throws IOException{
        byte[] bytes=NativeCanonicalFixture.bytes("authored-native-collection-root-v1.json");
        if(!ROOT_BYTES_SHA.equals(CanonicalJson.sha(bytes)))throw new IOException("Authored native collection root bytes changed");
        return new String(bytes,StandardCharsets.UTF_8);
    }
    public static ObjectNode root() throws IOException{return (ObjectNode)JsonUtils.parse(rootRaw());}
    public static byte[] batch(int ordinal) throws IOException{return NativeCanonicalFixture.bytes("authored-native-collection-batch-"+ordinal+"-v1.json");}
    public static CanonicalEvidenceBackend.Reply reply(byte[] bytes){return new CanonicalEvidenceBackend.Reply(bytes,"application/json",null,null,null);}
    public static ObjectNode capability(){ObjectNode result=JsonUtils.mapper().createObjectNode();
        result.put("schema",NativeDocumentCollectionContract.SCHEMA);result.put("batchSchema",NativeDocumentCollectionContract.BATCH_SCHEMA);
        result.put("projectionEndpoint","/project/native-collection");result.put("batchEndpoint","/canonical/{projectionSha256}/batches/{ordinal}");
        result.put("registrationIdentity","one-document-root");result.put("maximumResponseBytes",NativeDocumentCollectionContract.MAX_BYTES);
        result.put("unitPolicy","whole-adopted-unit-source-order");result.put("artifactReadValidation","actual-cas-every-read");
        result.put("envelopeBatchSchema","consense.native-document-envelope-batch.v1");result.put("envelopeVersion","complete-header-whole-metadata-records-v1");
        result.put("envelopeBatchEndpoint","/canonical/{projectionSha256}/envelopes/{ordinal}");
        result.put("completeHeaderTransport","root-enumerated-whole-metadata-record-batches");return result;
    }
}
