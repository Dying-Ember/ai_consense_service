package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Edition identity for reading, taken from the existing verified source bindings. */
final class DraftTemplateReadingSources {
    private static final Map<String,String> VERIFIED_HASHES=verifiedHashes();
    private DraftTemplateReadingSources() {}

    static boolean catalogueSourceVerified(String fileKey,String sourceHash) {
        return sourceHash!=null&&sourceHash.equals(VERIFIED_HASHES.get(fileKey));
    }

    private static Map<String,String> verifiedHashes() {
        try(InputStream in=DraftTemplateReadingSources.class.getResourceAsStream("/drafting/guidance-compiler-bindings.json")) {
            if(in==null)throw new IOException("Missing verified template source bindings.");
            JsonNode documents=JsonUtils.mapper().readTree(in).path("originalSourceDocuments");
            Map<String,String> hashes=new HashMap<>();
            for(Iterator<Map.Entry<String,JsonNode>> fields=documents.fields();fields.hasNext();) {
                Map.Entry<String,JsonNode> field=fields.next();String hash=field.getValue().path("sourceSha256").asText();
                if(hash.matches("[0-9a-f]{64}"))hashes.put(field.getKey(),hash);
            }
            return Collections.unmodifiableMap(hashes);
        }catch(IOException failure) {throw new IllegalStateException("Verified template source bindings unavailable.",failure);}
    }
}
