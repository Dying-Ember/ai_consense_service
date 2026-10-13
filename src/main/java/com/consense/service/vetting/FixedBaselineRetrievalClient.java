package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.config.FixedBaselineProperties;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only v1/signature-3 bridge. The live Python owner alone opens its vector database. */
@Component
public class FixedBaselineRetrievalClient {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ObjectReader READER = JSON.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> LOCAL_FIELDS = Arrays.asList("fixedBaselineManifestSha256", "fixedBaselineVersion", "fixedReferenceSourceUnit", "fixedReferenceOrigin", "fileLabel");
    private final HttpSupport http;
    private final FixedBaselineProperties properties;
    private volatile Catalog catalog;
    private final Set<String> verifiedSourceUnits = Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
    private static final class Row {
        final long offset; final int length; final String rawSha, normalizedSha;
        Row(long offset,int length,String rawSha,String normalizedSha) { this.offset=offset;this.length=length;this.rawSha=rawSha;this.normalizedSha=normalizedSha; }
    }
    private static final class Pin {
        final Path path; final long bytes, modified; final Object fileKey;
        Pin(Path p) throws IOException { path=p; BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class);bytes=a.size();modified=a.lastModifiedTime().toMillis();fileKey=a.fileKey(); }
        void current() throws IOException {
            BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class);
            require(a.isRegularFile() && a.size()==bytes && a.lastModifiedTime().toMillis()==modified && Objects.equals(a.fileKey(),fileKey),"frozen_file_changed");
        }
    }
    private static final class Catalog {
        final JsonNode manifest, plan; final Pin manifestPin, corpusPin; final Map<String,Row> rows;
        final String configIdentity;
        Catalog(JsonNode m,JsonNode p,Pin mp,Pin cp,Map<String,Row> r,String ci) { manifest=m;plan=p;manifestPin=mp;corpusPin=cp;rows=r;configIdentity=ci; }
    }
    public FixedBaselineRetrievalClient(HttpSupport http,FixedBaselineProperties properties) { this.http=http;this.properties=properties; }
    public boolean enabled() { return properties.isEnabled(); }
    public JsonNode frozenManifest() { Catalog c=ready();checkCurrent(c);return c.manifest.deepCopy(); }
    public List<VectorStore.SearchHit> search(String module,String query,int limit) {
        List<VectorStore.SearchHit> hits=new ArrayList<>();
        for(FixedReferenceEvidence e:searchEvidence(module,query,limit)) hits.add(e.asHit());
        return hits;
    }
    public List<FixedReferenceEvidence> searchEvidence(String module,String query,int limit) {
        require(Arrays.asList("vetting","advisory","advice").contains(module),"module_outside_fixed_reference_scope");
        require(query!=null&&!query.trim().isEmpty()&&limit>0&&limit<=50,"invalid_fixed_reference_query");
        Catalog c=ready();checkCurrent(c);
        JsonNode health=parse(http.get(baseUrl()+"/health",properties.getTimeoutMs())); validateHealth(c,health);
        ObjectNode request=JSON.createObjectNode();request.put("projectId",properties.getProjectId());request.put("query",query);
        request.put("role","standard");request.put("candidates",Math.max(limit,properties.getCandidates()));request.put("limit",limit);
        JsonNode response=parse(http.postJson(baseUrl()+"/retrieve",write(request),properties.getTimeoutMs()));
        if(migrationConfigured()) validateMigratedResponse(c,response);
        require(properties.getProjectId().equals(response.path("projectId").asText())&&properties.getIndexSignature().equals(response.path("indexSignature").asText()),"retrieval_identity_mismatch");
        require("source-bound-window-points-full-parent-results-v1".equals(response.path("mode").asText())&&response.path("hits").isArray(),"retrieval_shape_invalid");
        Map<String,JsonNode> seedPayload=new LinkedHashMap<>();Map<String,Double> scores=new HashMap<>();
        for(JsonNode h:response.path("hits")) {
            String id=h.path("id").asText();JsonNode p=h.path("payload");validateParent(c,id,p);
            require(h.path("score").isNumber()&&Double.isFinite(h.path("score").asDouble())&&!seedPayload.containsKey(id),"retrieval_score_or_duplicate_invalid");
            seedPayload.put(id,p);scores.put(id,h.path("score").asDouble());
        }
        JsonNode source=response.path("sourceUnits");
        require("unique-source-structural-unit-seeds-v1".equals(source.path("policy").asText())&&"canonical_observed_structure_only".equals(source.path("scope").asText())
                &&source.path("units").isArray()&&source.path("units").size()==seedPayload.size(),"source_bound_units_missing");
        require(!source.path("derivedMembersCanBecomeOrigins").asBoolean(true)&&"unknown".equals(source.path("qualifiersComplete").asText())&&!source.path("semanticScopeVerified").asBoolean(true),"source_unit_quality_claim_changed");
        Set<String> origins=new HashSet<>();Map<String,FixedReferenceEvidence> all=new LinkedHashMap<>();
        for(JsonNode unit:source.path("units")) {
            if("unknown".equals(unit.path("status").asText())) {
                require("unknown".equals(unit.path("kind").asText())&&unit.path("originIds").isArray()&&unit.path("originIds").size()==1
                        &&unit.path("members").isArray()&&unit.path("members").size()==0&&unit.path("requiredMemberIds").isArray()&&unit.path("requiredMemberIds").size()==0
                        &&text(unit.path("unitId"))&&text(unit.path("reason"))&&!unit.path("derivedMembersCanBecomeOrigins").asBoolean(true)
                        &&"unknown".equals(unit.path("qualifiersComplete").asText())&&!unit.path("semanticScopeVerified").asBoolean(true),"unknown_source_unit_shape_changed");
                String id=unit.path("originIds").get(0).asText();require(seedPayload.containsKey(id)&&origins.add(id)&&!all.containsKey(id),"unknown_source_unit_seed_invalid");
                JsonNode parent=seedPayload.get(id);require(unit.path("sourceIdentity").isObject(),"unknown_source_unit_identity_missing");
                for(Iterator<Map.Entry<String,JsonNode>> it=unit.path("sourceIdentity").fields();it.hasNext();) {Map.Entry<String,JsonNode> identity=it.next();require(parent.path(identity.getKey()).equals(identity.getValue()),"unknown_source_unit_identity_changed");}
                ObjectNode compact=((ObjectNode)unit).deepCopy();compact.remove("members");compact.remove("observedRanges");compact.remove("blockIds");compact.remove("memberScores");
                verifiedSourceUnits.add(normalizedDigest(compact));all.put(id,makeEvidence(c,id,scores.get(id),parent,compact,true));continue;
            }
            require(unit.path("members").isArray()&&unit.path("members").size()>0&&unit.path("requiredMemberIds").isArray()&&unit.path("originIds").isArray(),"source_bound_unit_incomplete");
            Set<String> required=stringSet(unit.path("requiredMemberIds")),members=new HashSet<>();double score=Double.NEGATIVE_INFINITY;
            for(JsonNode origin:unit.path("originIds")) {
                String id=origin.asText();require(seedPayload.containsKey(id)&&origins.add(id),"source_unit_origin_invalid");score=Math.max(score,scores.get(id));
            }
            require(Double.isFinite(score)&&unit.path("sourceIdentity").isObject()&&text(unit.path("unitId")),"source_unit_identity_missing");
            require(!unit.path("derivedMembersCanBecomeOrigins").asBoolean(true)&&"unknown".equals(unit.path("qualifiersComplete").asText())&&!unit.path("semanticScopeVerified").asBoolean(true),"source_unit_qualification_invalid");
            ObjectNode compact=((ObjectNode)unit).deepCopy();compact.remove("members");compact.remove("observedRanges");compact.remove("blockIds");compact.remove("memberScores");
            for(JsonNode p:unit.path("members")) {
                String id=p.path("id").asText();validateParent(c,id,p);require(members.add(id),"source_unit_duplicate_member");
                for(Iterator<Map.Entry<String,JsonNode>> it=unit.path("sourceIdentity").fields();it.hasNext();) {
                    Map.Entry<String,JsonNode> identity=it.next();require(p.path(identity.getKey()).equals(identity.getValue()),"source_unit_member_identity_mismatch");
                }
                if(seedPayload.containsKey(id)) require(normalizedDigest(seedPayload.get(id)).equals(normalizedDigest(p)),"source_unit_seed_payload_mismatch");
                require(!all.containsKey(id),"overlapping_source_units_require_explicit_grouping");
                all.put(id,makeEvidence(c,id,score,p,compact,seedPayload.containsKey(id)));
            }
            require(required.equals(members)&&members.containsAll(stringSet(unit.path("originIds"))),"source_unit_required_members_incomplete");
            verifiedSourceUnits.add(normalizedDigest(compact));
        }
        require(origins.equals(seedPayload.keySet()),"source_unit_seeds_not_complete");
        List<JsonNode> selected=new ArrayList<>();for(FixedReferenceEvidence item:all.values())selected.add(item.getPayload());
        assertOriginals(c,selected);checkCurrent(c);
        return new ArrayList<>(all.values());
    }
    public FixedReferenceEvidence evidence(VectorStore.SearchHit hit) {
        require(hit!=null&&hit.getPayload()!=null,"fixed_reference_hit_missing");Catalog c=ready();checkCurrent(c);
        JsonNode p=JSON.valueToTree(hit.getPayload());
        require(properties.getManifestSha256().equals(p.path("fixedBaselineManifestSha256").asText())&&c.manifest.path("version").asText().equals(p.path("fixedBaselineVersion").asText()),"fixed_reference_hit_pin_mismatch");
        ObjectNode original=((ObjectNode)p).deepCopy();for(String key:LOCAL_FIELDS) original.remove(key);
        validateParent(c,hit.getId(),original);readParent(c,hit.getId());
        JsonNode unit=p.get("fixedReferenceSourceUnit");
        if(unit!=null) require(unit.path("requiredMemberIds").isArray()&&(stringSet(unit.path("requiredMemberIds")).contains(hit.getId())
                ||"unknown".equals(unit.path("status").asText())&&unit.path("requiredMemberIds").size()==0&&stringSet(unit.path("originIds")).contains(hit.getId()))
                &&verifiedSourceUnits.contains(normalizedDigest(unit)),"fixed_reference_group_missing_or_changed");
        return makeEvidence(c,hit.getId(),hit.getScore(),original,unit,p.path("fixedReferenceOrigin").asBoolean());
    }
    public void assertCurrent(List<VectorStore.SearchHit> hits) {
        Catalog c=ready();checkCurrent(c);List<JsonNode> selected=new ArrayList<>();
        for(VectorStore.SearchHit h:hits)selected.add(evidence(h).getPayload());
        assertOriginals(c,selected);checkCurrent(c);
    }
    public String toModelMaterial(List<VectorStore.SearchHit> hits,int maxCharacters) {
        assertCurrent(hits);
        List<FixedReferenceEvidence> rows=new ArrayList<>();for(VectorStore.SearchHit h:hits)rows.add(evidence(h));
        return FixedBaselineModelMaterial.render(rows,maxCharacters);
    }
    public VectorStore.SearchHit readOnlyHitById(String id) {
        Catalog c=ready();checkCurrent(c);JsonNode p=readParent(c,id);assertOriginals(c,Collections.singletonList(p));checkCurrent(c);
        return makeEvidence(c,id,0,p,null,false).asHit();
    }
    private FixedReferenceEvidence makeEvidence(Catalog c,String id,double score,JsonNode original,JsonNode unit,boolean origin) {
        ObjectNode p=((ObjectNode)original).deepCopy();
        p.put("fixedBaselineManifestSha256",properties.getManifestSha256());p.put("fixedBaselineVersion",c.manifest.path("version").asText());
        p.put("fixedReferenceOrigin",origin);p.put("fileLabel",original.path("fileKey").asText());if(unit!=null)p.set("fixedReferenceSourceUnit",unit);
        JsonNode artifact=null;
        if(original.has("tableArtifact")) {
            artifact=readDescriptor(original.get("tableArtifact"),properties.getMaxRecordBytes());
            require(id.equals(artifact.path("id").asText())&&artifact.path("originalSourceSha256").equals(original.path("originalSourceSha256")),"table_artifact_identity_mismatch");
            require(artifact.path("observedCells").isArray()||artifact.path("table").path("extractedMatrix").isArray(),"table_artifact_incomplete");
            require(!artifact.path("sourceQualityAccepted").asBoolean(true),"table_artifact_quality_claim_changed");
        }
        return new FixedReferenceEvidence(id,score,p,artifact,unit);
    }
    private synchronized Catalog ready() {
        require(enabled(),"fixed_reference_disabled");if(catalog!=null)return catalog;
        require(properties.getManifestPath()!=null&&shaText(properties.getManifestSha256())&&shaText(properties.getIndexSignature()),"fixed_reference_configuration_missing");
        require(properties.getCandidates()>0&&properties.getCandidates()<=1000&&properties.getTimeoutMs()>0&&properties.getMaxRecordBytes()>0&&properties.getMaxResponseBytes()>0,"fixed_reference_configuration_invalid");
        try {
            Path mp=Paths.get(properties.getManifestPath()).toAbsolutePath().normalize();Pin manifestPin=new Pin(mp);
            require(manifestPin.bytes<=properties.getMaxResponseBytes(),"manifest_too_large");byte[] raw=Files.readAllBytes(mp);
            require(hash(raw).equals(properties.getManifestSha256()),"frozen_manifest_sha_mismatch");JsonNode manifest=parse(raw);
            require("consense.frozen-fixed-development-baseline.v1".equals(manifest.path("schema").asText())&&manifest.path("developmentBaseline").asBoolean()
                    &&!manifest.path("finalParsingAccepted").asBoolean(true)&&!manifest.path("businessAccepted").asBoolean(true),"frozen_manifest_acceptance_invalid");
            require(stringSet(manifest.path("scope")).equals(new HashSet<>(Arrays.asList("GCC","GCT","all-applicable-SL-technical"))),"frozen_manifest_scope_invalid");
            require(properties.getIndexSignature().equals(manifest.path("indexSignature").asText()),"frozen_manifest_index_signature_mismatch");
            JsonNode plan=readDescriptor(manifest.path("runtimePlan"),properties.getMaxResponseBytes());
            require(properties.getProjectId().equals(plan.path("projectId").asText())&&properties.getIndexSignature().equals(plan.path("indexSignatureSha256").asText()),"runtime_plan_identity_mismatch");
            JsonNode desc=manifest.path("corpus");Path cp=Paths.get(desc.path("path").asText()).toAbsolutePath().normalize();Pin corpusPin=new Pin(cp);
            require(desc.path("bytes").isIntegralNumber()&&desc.path("bytes").asLong()==corpusPin.bytes&&shaText(desc.path("sha256").asText()),"frozen_corpus_descriptor_invalid");
            Map<String,Row> rows=new HashMap<>();MessageDigest digest=sha();long offset=0;ByteArrayOutputStream line=new ByteArrayOutputStream();
            try(InputStream input=new BufferedInputStream(Files.newInputStream(cp))) {
                byte[] buffer=new byte[65536];int n;
                while((n=input.read(buffer))!=-1) {digest.update(buffer,0,n);
                    for(int i=0;i<n;i++) {
                        byte b=buffer[i];if(b=='\n') {byte[] bytes=line.toByteArray();addRow(manifest,rows,offset,bytes);offset+=bytes.length+1;line.reset();}
                        else {line.write(b);require(line.size()<=properties.getMaxRecordBytes(),"frozen_parent_too_large");}
                    }
                }
                if(line.size()>0)addRow(manifest,rows,offset,line.toByteArray());
            }
            require(hex(digest.digest()).equals(desc.path("sha256").asText()),"frozen_corpus_sha_mismatch");
            require(rows.size()==manifest.path("coverage").path("totalParents").asInt(-1),"frozen_corpus_parent_count_mismatch");
            manifestPin.current();corpusPin.current();catalog=new Catalog(manifest,plan,manifestPin,corpusPin,Collections.unmodifiableMap(rows),configIdentity());return catalog;
        }catch(IOException e){throw new IllegalStateException("fixed_reference_initialization_failed",e);}
    }
    private void addRow(JsonNode manifest,Map<String,Row> rows,long offset,byte[] bytes) {
        require(bytes.length>0,"frozen_corpus_empty_record");JsonNode p=normalizeParent(parse(bytes));validateSource(manifest,p);
        String id=p.path("id").asText();require(!rows.containsKey(id),"frozen_corpus_duplicate_parent");rows.put(id,new Row(offset,bytes.length,hash(bytes),normalizedDigest(p)));
    }
    private void validateParent(Catalog c,String id,JsonNode p) {
        Row row=c.rows.get(id);require(row!=null&&id.equals(p.path("id").asText()),"retrieval_unknown_or_mismatched_parent");
        require(normalizedDigest(p).equals(row.normalizedSha),"retrieval_parent_payload_or_parts_changed");validateSource(c.manifest,p);
    }
    private static void validateSource(JsonNode manifest,JsonNode p) {
        require(p.isObject()&&text(p.path("id"))&&text(p.path("content"))&&"standard".equals(p.path("role").asText())&&text(p.path("documentId"))&&text(p.path("anchor"))&&p.path("pageNo").isIntegralNumber()&&p.path("pageNo").asInt()>0,"frozen_parent_source_incomplete");
        String family=p.path("sourceQuality").path("family").asText();String sourceKey="GCC".equals(family)?"gccSource":"GCT".equals(family)?"gctSource":"SL".equals(family)?"slParent":null;
        require(sourceKey!=null&&manifest.path("originalSources").path(sourceKey).path("sha256").equals(p.path("originalSourceSha256")),"frozen_parent_outside_source_scope");
        require(shaText(p.path("sourceHash").asText())&&shaText(p.path("sourceQualityHash").asText())&&p.path("fullObservedParentPreserved").asBoolean()&&p.path("fixedDevelopmentBaseline").asBoolean(),"frozen_parent_binding_incomplete");
        require(p.path("parts").isArray()&&p.path("parts").size()>0&&!p.path("qualityAdopted").asBoolean(true)&&!p.path("legalScopeAccepted").asBoolean(true)&&!p.path("semanticScopeVerified").asBoolean(true),"frozen_parent_quality_or_parts_invalid");
        for(JsonNode part:p.path("parts"))require(text(part.path("blockId"))&&part.path("text").isTextual()&&part.path("pageNo").isIntegralNumber()&&part.path("pageNo").asInt()>0,"frozen_parent_part_binding_missing");
    }
    private JsonNode readParent(Catalog c,String id) {
        Row row=c.rows.get(id);require(row!=null,"fixed_reference_parent_not_found");byte[] bytes=new byte[row.length];
        try(RandomAccessFile file=new RandomAccessFile(c.corpusPin.path.toFile(),"r")){file.seek(row.offset);file.readFully(bytes);}catch(IOException e){throw new IllegalStateException("fixed_reference_parent_read_failed",e);}
        require(row.rawSha.equals(hash(bytes)),"frozen_parent_bytes_changed");JsonNode p=normalizeParent(parse(bytes));validateParent(c,id,p);return p;
    }
    private void checkCurrent(Catalog c) {
        require(c.configIdentity.equals(configIdentity()),"fixed_reference_configuration_changed");
        try{c.manifestPin.current();c.corpusPin.current();}catch(IOException e){throw new IllegalStateException("fixed_reference_frozen_files_unavailable",e);}
        if(migrationConfigured()) migrationQuery(c);
    }
    /** Re-hash only selected source originals; metadata timestamps cannot attest byte identity. */
    private void assertOriginals(Catalog c,Collection<JsonNode> selected) {
        Set<String> checked=new HashSet<>();
        for(JsonNode parent:selected) {
            readParent(c,parent.path("id").asText());
            String expected=parent.path("originalSourceSha256").asText();if(!checked.add(expected))continue;
            JsonNode descriptor=null;
            for(JsonNode source:c.manifest.path("originalSources"))if(expected.equals(source.path("sha256").asText())) {
                require(descriptor==null,"fixed_original_descriptor_ambiguous");descriptor=source;
            }
            require(descriptor!=null&&text(descriptor.path("path"))&&descriptor.path("bytes").isIntegralNumber()
                    &&descriptor.path("bytes").asLong()>0&&shaText(expected),"fixed_original_descriptor_invalid");
            try {
                Path path=Paths.get(descriptor.path("path").asText()).toAbsolutePath().normalize();Pin before=new Pin(path);
                require(Files.isRegularFile(path)&&before.bytes==descriptor.path("bytes").asLong(),"fixed_original_size_changed");
                MessageDigest digest=sha();long count=0;byte[] buffer=new byte[65536];
                try(InputStream input=new BufferedInputStream(Files.newInputStream(path))) {
                    int n;while((n=input.read(buffer))!=-1){digest.update(buffer,0,n);count+=n;}
                }
                before.current();require(count==before.bytes&&expected.equals(hex(digest.digest())),"fixed_original_sha_changed");
            }catch(IOException e){throw new IllegalStateException("fixed_reference_original_unavailable",e);}
        }
    }
    private void validateHealth(Catalog c,JsonNode h) {
        if(migrationConfigured()) { validateMigratedHealth(c,h);return; }
        JsonNode signature=c.plan.path("signature"),w=h.path("retrieval").path("sourceWindows");
        require("ok".equals(h.path("status").asText())&&"local".equals(h.path("mode").asText())&&h.path("offline").asBoolean()&&"cuda".equals(h.path("device").asText())&&h.path("indexSignatureVersion").asInt()==3,"fixed_runtime_health_invalid");
        require("source-bound-window-points-full-parent-results-v1".equals(w.path("contract").asText())&&"max".equals(w.path("denseAggregation").asText())&&"max".equals(w.path("rerankAggregation").asText()),"fixed_runtime_contract_invalid");
        require(normalizedDigest(signature.path("embedding")).equals(normalizedDigest(h.path("models").path("embedding")))&&normalizedDigest(signature.path("runtime")).equals(normalizedDigest(h.path("runtime").path("embedding"))),"fixed_embedding_recipe_changed");
        require("BAAI/bge-reranker-v2-m3".equals(h.path("models").path("reranker").path("name").asText())&&"953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e".equals(h.path("models").path("reranker").path("revision").asText()),"fixed_reranker_identity_changed");
        JsonNode rr=h.path("runtime").path("reranker");require("cuda".equals(rr.path("device").asText())&&"float16".equals(rr.path("dtype").asText())&&rr.path("maxTokens").asInt()==512&&rr.path("batchSize").asInt()==4&&rr.path("serialOffload").asBoolean()&&rr.equals(w.path("rerankRuntime")),"fixed_reranker_recipe_changed");
        for(String key:Arrays.asList("embeddingWindows","rerankWindows")) require(normalizedDigest(signature.path("windowRecipe")).equals(normalizedDigest(w.path(key))),"fixed_window_recipe_changed");
        for(Iterator<Map.Entry<String,JsonNode>> it=signature.path("algorithms").fields();it.hasNext();){Map.Entry<String,JsonNode> a=it.next();require(a.getValue().equals(w.path("algorithms").path(a.getKey())),"fixed_runtime_algorithm_changed:"+a.getKey());}
        require("unique-source-structural-unit-seeds-v1".equals(w.path("structuralSelection").path("policy").asText())&&!w.path("structuralSelection").path("closureMembersAreRanked").asBoolean(true)&&h.path("retrieval").path("roleFilter").asBoolean(),"fixed_source_unit_policy_changed");
    }
    private JsonNode readDescriptor(JsonNode d,int maximum) {
        require(d.isObject()&&text(d.path("path"))&&d.path("bytes").isIntegralNumber()&&d.path("bytes").asLong()>=0&&d.path("bytes").asLong()<=maximum&&shaText(d.path("sha256").asText()),"fixed_artifact_descriptor_invalid");
        try {Path p=Paths.get(d.path("path").asText());require(Files.size(p)==d.path("bytes").asLong(),"fixed_artifact_size_changed");byte[] raw=Files.readAllBytes(p);require(hash(raw).equals(d.path("sha256").asText()),"fixed_artifact_sha_changed");return parse(raw);}catch(IOException e){throw new IllegalStateException("fixed_artifact_unavailable",e);}
    }
    private String configIdentity(){return properties.getManifestPath()+"|"+properties.getManifestSha256()+"|"+properties.getProjectId()+"|"+properties.getIndexSignature()+"|"+baseUrl()+"|"+properties.getMigrationReceiptPath()+"|"+properties.getMigrationReceiptSha256()+"|"+properties.getQueryRuntimePath()+"|"+properties.getQueryRuntimeSha256();}
    private boolean migrationConfigured() {
        return properties.getMigrationReceiptPath()!=null||properties.getMigrationReceiptSha256()!=null
                ||properties.getQueryRuntimePath()!=null||properties.getQueryRuntimeSha256()!=null;
    }
    private JsonNode pinned(String path,String expected) {
        require(path!=null&&!path.trim().isEmpty()&&shaText(expected),"migration_pin_configuration_missing");
        try {require(Files.size(Paths.get(path))<=properties.getMaxResponseBytes(),"migration_pin_too_large");byte[] raw=Files.readAllBytes(Paths.get(path));
            require(hash(raw).equals(expected),"migration_pin_bytes_changed");return parse(raw);
        }catch(IOException e){throw new IllegalStateException("migration_pin_unavailable",e);}
    }
    private void verifyArtifact(JsonNode descriptor) {
        require(text(descriptor.path("path"))&&descriptor.path("bytes").isIntegralNumber()&&descriptor.path("bytes").asLong()>0
                &&shaText(descriptor.path("sha256").asText()),"migration_artifact_descriptor_invalid");
        try {Path path=Paths.get(descriptor.path("path").asText());Pin before=new Pin(path);MessageDigest d=sha();long count=0;
            try(InputStream stream=new BufferedInputStream(Files.newInputStream(path))) {byte[] bytes=new byte[65536];int n;while((n=stream.read(bytes))!=-1){d.update(bytes,0,n);count+=n;}}
            before.current();require(count==descriptor.path("bytes").asLong()&&hex(d.digest()).equals(descriptor.path("sha256").asText()),"migration_artifact_changed");
        }catch(IOException e){throw new IllegalStateException("migration_artifact_unavailable",e);}
    }
    /** Two externally pinned stages: historical storage migration and current query recipe. */
    private JsonNode migrationQuery(Catalog c) {
        JsonNode m=pinned(properties.getMigrationReceiptPath(),properties.getMigrationReceiptSha256());
        JsonNode q=pinned(properties.getQueryRuntimePath(),properties.getQueryRuntimeSha256());
        require("consense.qdrant-server-migration.v1".equals(m.path("schema").asText())
                &&"completed_full_storage_readback_candidate_not_adopted".equals(m.path("status").asText())
                &&m.path("targetAdopted").isBoolean()&&!m.path("targetAdopted").asBoolean()
                &&m.path("activeConfigurationChanged").isBoolean()&&!m.path("activeConfigurationChanged").asBoolean()
                &&m.path("allOriginalPersistentStateBytesUnchanged").asBoolean(),"migration_receipt_incomplete");
        require(properties.getManifestSha256().equals(m.path("frozenManifest").path("sha256").asText())
                &&properties.getIndexSignature().equals(m.path("indexSignature").asText())
                &&m.path("embeddingSignature").equals(c.plan.path("signature"))
                &&m.path("originalMetadata").equals(c.manifest.path("persistedMetadata"))
                &&m.path("parentCount").equals(c.manifest.path("coverage").path("totalParents"))
                &&m.path("vectorWindowCount").equals(c.manifest.path("persistedVectorCount")),"migration_source_identity_changed");
        String collection=m.path("target").path("collection").asText(),url=m.path("target").path("url").asText();
        require(collection.startsWith("oer_server_")&&!collection.equals(c.manifest.path("collection").asText())
                &&url.matches("http://(127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]+"),"migration_target_invalid");
        for(String field:Arrays.asList("schema","dimension","dtype","pointCount","embeddingSignatureSha256"))
            require(m.path("targetVectorIntegrity").path(field).equals(c.manifest.path("vectorIntegrity").path(field))
                    &&m.path("sourceVectorIntegrity").path(field).equals(c.manifest.path("vectorIntegrity").path(field)),"migration_vector_seal_changed:"+field);
        // Local warm cosine values and cold stored float32 values have separately recorded exact hashes.
        require(shaText(c.manifest.path("vectorIntegrity").path("persistedPointsSha256").asText())
                &&m.path("sourceVectorIntegrity").path("pointsSha256").equals(c.manifest.path("vectorIntegrity").path("persistedPointsSha256"))
                &&m.path("targetVectorIntegrity").equals(m.path("sourceVectorIntegrity")),"migration_original_persisted_vectors_changed");
        require("consense.migrated-server-query-runtime.v1".equals(q.path("schema").asText())
                &&properties.getMigrationReceiptSha256().equals(q.path("migrationReceiptSha256").asText())
                &&baseUrl().equals(q.path("queryApiBaseUrl").asText())&&q.path("target").equals(m.path("target"))
                &&properties.getIndexSignature().equals(q.path("indexSignature").asText())
                &&q.path("readOnly").asBoolean()&&q.path("runtimeCompatibilityVerified").isBoolean()&&!q.path("runtimeCompatibilityVerified").asBoolean()
                &&q.path("fullVectorIntegrity").equals(m.path("targetVectorIntegrity")),"migration_query_binding_changed");
        JsonNode identity=q.path("queryIdentity"),recipe=q.path("queryRecipe"),sig=c.plan.path("signature");
        for(String field:Arrays.asList("contract","embedding","corpus","windowRecipe","embeddingPreprocessing","normalized","structuralSelectionPolicy","signatureVersion"))
            require(identity.path(field).equals(sig.path(field)),"migration_query_source_recipe_changed:"+field);
        for(String field:Arrays.asList("model","files","backendSha256","tokenizerClass","fast","modelMaxLength"))
            require(identity.path("tokenizerAndModelFiles").path(field).equals(sig.path("tokenizerAndModelFiles").path(field)),"migration_query_model_changed:"+field);
        for(String kind:Arrays.asList("embedding","reranker")) {
            JsonNode tokenizer="embedding".equals(kind)?identity.path("tokenizerAndModelFiles"):recipe.path("rerankTokenizerAndModelFiles");
            JsonNode publication=readDescriptor(c.manifest.path("modelPublications").path(kind),properties.getMaxResponseBytes());
            require(c.manifest.path("modelPublications").path(kind).path("sha256").equals(tokenizer.path("checkpointPublication").path("manifestSha256"))
                    &&shaText(tokenizer.path("checkpointPublication").path("manifestSha256").asText())
                    &&publication.path("model").equals(tokenizer.path("model"))&&publication.path("files").equals(tokenizer.path("files"))
                    &&publication.path("publicationSha256").equals(tokenizer.path("checkpointPublication").path("publicationSha256"))
                    &&shaText(tokenizer.path("backendSha256").asText())&&text(tokenizer.path("tokenizerClass"))
                    &&tokenizer.path("fast").asBoolean()&&tokenizer.path("modelMaxLength").isIntegralNumber(),"migration_query_publication_changed:"+kind);
        }
        for(String name:Arrays.asList("server.py","window_runtime.py","migrated_qdrant_index.py","migrate_qdrant_server.py","fixed_development_baseline_runtime.py","verify_retrieval_offline.py","token_windows.py","index_integrity.py","retrieval_source_units.py","checkpoint_publication.py","fixed_vector_cache.py","qdrant_backend.py","workspace_root.py","embedding_compatibility.py","structural_retrieval.py","preparation_cache.py","checkpoint_identity.py"))
            require(shaText(recipe.path("algorithms").path(name).asText()),"migration_query_software_closure_missing:"+name);
        require(queryDigest(identity).equals(q.path("queryRuntimeSignature").asText()),"migration_query_signature_changed");
        verifyArtifact(m.path("candidateMetadata"));return q;
    }
    private void validateMigratedHealth(Catalog c,JsonNode actual) {
        JsonNode q=migrationQuery(c),expected=q.path("health"),w=actual.path("retrieval").path("sourceWindows"),recipe=q.path("queryRecipe");
        require("ok".equals(actual.path("status").asText())&&"server".equals(actual.path("mode").asText())&&actual.path("offline").asBoolean()
                &&actual.path("indexSignatureVersion").asInt()==3,"migrated_runtime_health_invalid");
        for(String field:Arrays.asList("device","models","runtime","retrieval"))
            require(actual.path(field).equals(expected.path(field)),"migrated_runtime_health_changed:"+field);
        require(actual.path("runtime").path("embedding").equals(q.path("queryIdentity").path("runtime"))
                &&actual.path("runtime").path("reranker").equals(recipe.path("rerankRuntime"))
                &&actual.path("models").path("embedding").equals(q.path("queryIdentity").path("embedding"))
                &&actual.path("models").path("reranker").equals(recipe.path("rerankTokenizerAndModelFiles").path("model")),"migrated_runtime_profile_changed");
        for(String field:Arrays.asList("contract","embeddingWindows","rerankWindows","denseAggregation","rerankAggregation","algorithms","structuralSelection"))
            require(w.path(field).equals(recipe.path(field)),"migrated_runtime_recipe_changed:"+field);
        require(w.path("embeddingWindows").equals(c.plan.path("signature").path("windowRecipe"))
                &&w.path("rerankWindows").equals(c.plan.path("signature").path("windowRecipe")),"migrated_window_recipe_changed");
    }
    private void validateMigratedResponse(Catalog c,JsonNode response) {
        JsonNode q=migrationQuery(c),access=response.path("indexAccess");
        require("pinned-migrated-server-read-only".equals(access.path("mode").asText())&&access.path("readOnly").asBoolean()
                &&access.path("runtimeCompatibilityVerified").isBoolean()&&!access.path("runtimeCompatibilityVerified").asBoolean()
                &&properties.getMigrationReceiptSha256().equals(access.path("migrationReceiptSha256").asText()),"migrated_response_access_invalid");
        for(String field:Arrays.asList("target","queryIdentity","queryRecipe","queryRuntimeSignature","fullVectorIntegrity"))
            require(access.path(field).equals(q.path(field)),"migrated_response_identity_changed:"+field);
    }
    private String baseUrl(){String u=properties.getBaseUrl();require(u!=null&&u.matches("https?://[^/]+/?"),"fixed_reference_base_url_invalid");return u.endsWith("/")?u.substring(0,u.length()-1):u;}
    private JsonNode parse(String text){require(text!=null&&text.getBytes(StandardCharsets.UTF_8).length<=properties.getMaxResponseBytes(),"fixed_wire_too_large");return parse(text.getBytes(StandardCharsets.UTF_8));}
    private static JsonNode parse(byte[] bytes){try{return READER.readValue(bytes);}catch(IOException e){throw new IllegalStateException("fixed_json_invalid",e);}}
    private static ObjectNode normalizeParent(JsonNode p){require(p.isObject(),"fixed_parent_not_object");ObjectNode r=((ObjectNode)p).deepCopy();List<String> nulls=new ArrayList<>();r.fields().forEachRemaining(e->{if(e.getValue().isNull())nulls.add(e.getKey());});nulls.forEach(r::remove);return r;}
    private static String normalizedDigest(JsonNode n){return hash(write(sorted(n)).getBytes(StandardCharsets.UTF_8));}
    /** Python query signatures retain integer JSON encoding, including large tokenizer limits. */
    private static String queryDigest(JsonNode n){return hash(write(signatureJson(n)).getBytes(StandardCharsets.UTF_8));}
    private static JsonNode signatureJson(JsonNode n) {
        if(n.isObject()){ObjectNode o=JSON.createObjectNode();TreeMap<String,JsonNode> fields=new TreeMap<>();n.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));fields.forEach((k,v)->o.set(k,signatureJson(v)));return o;}
        if(n.isArray()){ArrayNode a=JSON.createArrayNode();n.forEach(v->a.add(signatureJson(v)));return a;}
        require(!n.isFloatingPointNumber(),"migration_query_signature_noninteger");return n;
    }
    private static JsonNode sorted(JsonNode n){if(n.isObject()){ObjectNode o=JSON.createObjectNode();TreeMap<String,JsonNode> map=new TreeMap<>();n.fields().forEachRemaining(e->map.put(e.getKey(),e.getValue()));map.forEach((k,v)->o.set(k,sorted(v)));return o;}if(n.isArray()){ArrayNode a=JSON.createArrayNode();n.forEach(v->a.add(sorted(v)));return a;}if(n.isNumber())return DecimalNode.valueOf(n.decimalValue().stripTrailingZeros());return n;}
    private static Set<String> stringSet(JsonNode array){require(array.isArray(),"fixed_identity_array_missing");Set<String>s=new HashSet<>();for(JsonNode v:array){require(text(v)&&s.add(v.asText()),"fixed_identity_array_duplicate_or_invalid");}return s;}
    private static boolean text(JsonNode n){return n.isTextual()&&!n.asText().trim().isEmpty();}
    private static boolean shaText(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static String write(JsonNode n){try{return JSON.writeValueAsString(n);}catch(IOException e){throw new IllegalStateException("fixed_json_write_failed",e);}}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hash(byte[] value){return hex(sha().digest(value));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException("Fixed development reference rejected: "+reason);}
}
