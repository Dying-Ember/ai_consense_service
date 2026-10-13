package com.consense.service.sources;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Exact declared read policies only; no claim health proves actual source bytes. */
class NativeValidationSessionCapabilityAuthorTest {
    static final String SESSION="actual-cas-unique-per-validation-session-with-exit-rehash";
    static ObjectNode oer()throws Exception {ObjectNode root=JsonUtils.mapper().createObjectNode();root.set("nativeCanonical",NativeCanonicalFixture.capability());root.set("nativePdfCanonical",NativePdfCanonicalFixture.capability());root.set("nativeDocumentCollection",NativeDocumentCollectionFixture.capability());return root;}
    static ObjectNode algorithms(){ObjectNode a=JsonUtils.mapper().createObjectNode();for(String n:Arrays.asList("native_projection_collection.py","native_collection_registry.py","oer_native_collection_server.py","native_collection_envelope.py"))a.put(n,"a".repeat(64));return a;}
    static void all(ObjectNode root){NativeCanonicalContract.validateCapability(root);NativePdfCanonicalContract.validateCapability(root);NativeDocumentCollectionContract.validateCapability(root,algorithms());}
    @Test void bothExactLegacyAndValidationSessionDeclarationsAreAccepted()throws Exception {ObjectNode legacy=oer();assertDoesNotThrow(()->all(legacy));ObjectNode modern=oer();for(String n:Arrays.asList("nativeCanonical","nativePdfCanonical","nativeDocumentCollection"))((ObjectNode)modern.path(n)).put("artifactReadValidation",SESSION);assertDoesNotThrow(()->all(modern));}
    @Test void missingOrWeakenedReadPoliciesRemainRejectedInAllThreeContracts()throws Exception {
        for(String name:Arrays.asList("nativeCanonical","nativePdfCanonical","nativeDocumentCollection"))for(String value:Arrays.asList("unknown","cached-across-requests","actual-cas-unique-per-validation-session","")){
            ObjectNode bad=oer();((ObjectNode)bad.path(name)).put("artifactReadValidation",value);assertThrows(RuntimeException.class,()->all(bad),name+"/"+value);
        }
    }
}
