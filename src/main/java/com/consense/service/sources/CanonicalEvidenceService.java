package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.repository.CanonicalSourceBindingRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.VettingCorpus;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Source-bound registry adoption and asset return; does not alter either retrieval pipeline. */
@Service
public class CanonicalEvidenceService {
    private final CanonicalSourceBindingRepository bindings;
    private final CanonicalEvidenceBackend backend;
    private final CanonicalCurrentSourceReader sources;
    @Autowired private org.springframework.beans.factory.ObjectProvider<CanonicalEvidenceService> boundaryProxy;

    @Autowired public CanonicalEvidenceService(CanonicalSourceBindingRepository bindings,CanonicalEvidenceBackend backend,CanonicalCurrentSourceReader sources){
        this.bindings=bindings;this.backend=backend;this.sources=sources;
    }

    /** Direct fixtures/probes do not apply Spring transaction advice; production uses the injected reader proxy. */
    public CanonicalEvidenceService(SourceDocumentRepository documents,CanonicalSourceBindingRepository bindings,CanonicalEvidenceBackend backend,ConsenseProperties properties){
        this(bindings,backend,new CanonicalCurrentSourceReader(documents,bindings,properties));
    }
    private static final List<String> MODULES=Arrays.asList("drafting","vetting","advisory");
    private static final String INPUT_CONTRACT="canonical-source-chunks-upstream-scope-and-parser-projection-v1";
    private static final String ASSET_CONTRACT="source-and-projection-bound-content-addressed-assets-v1";

    /** Internal implementation API only. All expectations must come from the explicit source-review handoff. */
    public static class ReviewedRegistration {
        public final String projectionSha256,originalSourceSha256,javaParsedSourceHash;
        public final String sourceRevisionSha256,scopePolicySha256,adoptionManifestSha256,reviewReferenceSha256,module;
        public final String familyCompositionSha256,familyComponentSetSha256;
        public ReviewedRegistration(String projection,String original,String parsed,String revision,String policy,String adoption,String reference,String module){
            this(projection,original,parsed,revision,policy,adoption,reference,null,null,module);
        }
        public ReviewedRegistration(String projection,String original,String parsed,String revision,String policy,String adoption,String reference,String composition,String componentSet,String module){
            this.projectionSha256=projection;this.originalSourceSha256=original;this.javaParsedSourceHash=parsed;this.sourceRevisionSha256=revision;
            this.scopePolicySha256=policy;this.adoptionManifestSha256=adoption;this.reviewReferenceSha256=reference;
            this.familyCompositionSha256=composition;this.familyComponentSetSha256=componentSet;this.module=module;
        }
    }
    public CanonicalSourceBinding registerReviewed(String projectId,Long documentId,ReviewedRegistration review) {
        require(review!=null,HttpStatus.BAD_REQUEST,"EXPLICIT_SOURCE_REVIEW_REQUIRED");
        for(String sha:Arrays.asList(review.projectionSha256,review.originalSourceSha256,review.javaParsedSourceHash,
                review.sourceRevisionSha256,review.scopePolicySha256))validSha(sha);
        validModule(review.module);
        SourceDocument document=currentDocument(projectId,documentId,review.module);
        require(review.originalSourceSha256.equals(document.getSourceSha256())&&review.javaParsedSourceHash.equals(VettingCorpus.sourceHash(document)),
                HttpStatus.CONFLICT,"SOURCE_REVIEW_REVISION_STALE");
        JsonNode healthRoot=parse(backend.health()),health=healthRoot.path("oer");
        require(INPUT_CONTRACT.equals(health.path("inputContract").asText())&&ASSET_CONTRACT.equals(health.path("assetReturnContract").asText())
                &&"/project".equals(health.path("canonicalProjectionEndpoint").asText())
                &&review.scopePolicySha256.equals(health.path("scopePolicy").path("manifestSha256").asText()),HttpStatus.BAD_GATEWAY,"OER_CANONICAL_CAPABILITY_MISMATCH");
        String raw=backend.snapshot(review.projectionSha256,review.module);JsonNode snapshot=validateSnapshot(raw,review.projectionSha256);
        if(NativePdfSourceFamilyContract.isFamily(snapshot)){
            NativePdfSourceFamilyContract.validateCapability(health);
            NativePdfSourceFamilyContract.validateAlgorithms(healthRoot.path("retrieval").path("sourceWindows").path("algorithms"));
        }else if(NativePdfCanonicalContract.isNativePdf(snapshot)){
            NativePdfCanonicalContract.validateCapability(health);
            NativePdfCanonicalContract.validateAlgorithms(healthRoot.path("retrieval").path("sourceWindows").path("algorithms"),snapshot.path("projectionIdentity"));
        }
        if(NativeCanonicalContract.isNative(snapshot)||NativeDocumentCollectionContract.isCollection(snapshot)){
            NativeCanonicalContract.validateCapability(health);
            NativeCanonicalContract.validateAlgorithms(healthRoot.path("retrieval").path("sourceWindows").path("algorithms"));
            if(NativeDocumentCollectionContract.isCollection(snapshot)){
                NativeDocumentCollectionContract.validateCapability(health,healthRoot.path("retrieval").path("sourceWindows").path("algorithms"));
                NativeDocumentCollectionContract.readDocument(raw,review.projectionSha256,
                    ordinal->backend.batch(review.projectionSha256,ordinal,review.module).bytes,
                    ordinal->backend.envelope(review.projectionSha256,ordinal,review.module).bytes);
            }
        }
        JsonNode identity=snapshot.path("projectionIdentity"),manifest=manifest(snapshot);
        // The producer must use the actual database ID. Matching source bytes alone never aliases two uploaded documents.
        require(String.valueOf(documentId).equals(identity.path("documentId").asText())&&String.valueOf(documentId).equals((NativePdfSourceFamilyContract.isFamily(snapshot)?manifest.path("spec"):manifest).path("documentId").asText()),
                HttpStatus.CONFLICT,"CANONICAL_DOCUMENT_BINDING_MISMATCH");
        require(review.originalSourceSha256.equals(snapshot.path("source").path("sourceSha256").asText())
                &&review.sourceRevisionSha256.equals(snapshot.path("source").path("sourceRevisionSha256").asText())
                &&review.scopePolicySha256.equals(manifest.path("scope").path("scopePolicySha256").asText())
                &&"codex".equals(manifest.path("reference").path("actor").asText()),HttpStatus.CONFLICT,"CANONICAL_SOURCE_REVIEW_MISMATCH");
        if(NativePdfSourceFamilyContract.isFamily(snapshot)){
            validSha(review.familyCompositionSha256);validSha(review.familyComponentSetSha256);
            require(review.adoptionManifestSha256==null&&review.reviewReferenceSha256==null
                    &&review.familyCompositionSha256.equals(identity.path("compositionSha256").asText())
                    &&review.familyComponentSetSha256.equals(identity.path("componentSetSha256").asText())
                    &&"component_identity_composition_review".equals(manifest.path("reference").path("method").asText()),HttpStatus.CONFLICT,"CANONICAL_FAMILY_REVIEW_MISMATCH");
        }else{
            validSha(review.adoptionManifestSha256);validSha(review.reviewReferenceSha256);
            require(review.familyCompositionSha256==null&&review.familyComponentSetSha256==null
                    &&review.adoptionManifestSha256.equals(adoptionHash(snapshot,raw))
                    &&review.adoptionManifestSha256.equals(identity.path(NativePdfCanonicalContract.isNativePdf(snapshot)?"adoptionManifestSha256":"manifestSha256").asText())
                    &&review.reviewReferenceSha256.equals(manifest.path("reference").path("evidenceSha256").asText())
                    &&"original_source_review".equals(manifest.path("reference").path("method").asText()),HttpStatus.CONFLICT,"CANONICAL_SOURCE_REVIEW_MISMATCH");
        }
        OerDocumentIdentity routed=OerSourceMetadata.identity(document);
        require(Objects.equals(routed.getSourcePurpose(),identity.path("sourcePurpose").asText())
                &&Objects.equals(VettingCorpus.sourceRole(document),identity.path("role").asText()),HttpStatus.CONFLICT,"CANONICAL_ROUTING_MISMATCH");
        Set<String> declared=modules(identity.path("eligibleModules"));
        require(declared.contains(review.module)&&routed.getEligibleModules().containsAll(declared)
                &&MODULES.contains(identity.path("module").asText()),HttpStatus.FORBIDDEN,"CANONICAL_MODULE_NOT_ELIGIBLE");
        CanonicalSourceBinding binding=new CanonicalSourceBinding();
        binding.setProjectId(projectId);binding.setDocumentId(documentId);binding.setProjectionSha256(review.projectionSha256);
        binding.setOriginalSourceSha256(review.originalSourceSha256);binding.setJavaParsedSourceHash(review.javaParsedSourceHash);
        binding.setRoutingSha256(routingHash(document));binding.setSourceRevisionSha256(review.sourceRevisionSha256);
        binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());
        binding.setScopePolicySha256(review.scopePolicySha256);binding.setAdoptionManifestSha256(review.adoptionManifestSha256);
        binding.setReviewReferenceSha256(review.reviewReferenceSha256);binding.setFamilyCompositionSha256(review.familyCompositionSha256);binding.setFamilyComponentSetSha256(review.familyComponentSetSha256);binding.setDeclaredModule(identity.path("module").asText());
        binding.setEligibleModulesJson(identity.path("eligibleModules").toString());binding.setSnapshotJson(raw);
        binding.setSnapshotBytesSha256(CanonicalJson.sha(raw.getBytes(StandardCharsets.UTF_8)));
        // Recheck bytes and current catalog after the remote call; a failed adoption never creates a binding.
        sources.assertSourceCurrent(binding,review.module);
        Optional<CanonicalSourceBinding> existing=bindings.findByProjectIdAndDocumentIdAndProjectionSha256(projectId,documentId,review.projectionSha256);
        if(existing.isPresent()){assertCurrent(existing.get(),review.module);return sameBinding(existing.get(),binding);}
        try{return bindings.saveAndFlush(binding);}catch(DataIntegrityViolationException race){
            Optional<CanonicalSourceBinding> concurrent=bindings.findByProjectIdAndDocumentIdAndProjectionSha256(projectId,documentId,review.projectionSha256);
            if(!concurrent.isPresent())throw race;return sameBinding(concurrent.get(),binding);
        }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public String snapshot(String projectId,Long documentId,String projection,String module){return readBinding(projectId,documentId,projection,module).getSnapshotJson();}

    /** Complete selected-document corpus, in caller order; never fall back from a newer stale adoption. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public List<CanonicalSourceBinding> currentBindings(String projectId,String module,List<Long> selectedDocumentIds){
        validModule(module);require(selectedDocumentIds!=null&&!selectedDocumentIds.isEmpty()
                &&!selectedDocumentIds.contains(null)&&new HashSet<>(selectedDocumentIds).size()==selectedDocumentIds.size(),HttpStatus.BAD_REQUEST,"CANONICAL_SELECTED_DOCUMENTS_INVALID");
        List<CanonicalSourceBinding> result=new ArrayList<>();
        for(Long documentId:selectedDocumentIds){
            List<CanonicalSourceBinding> revisions=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(projectId,documentId);
            require(!revisions.isEmpty(),HttpStatus.CONFLICT,"CANONICAL_SELECTED_DOCUMENT_UNREGISTERED");
            result.add(readBinding(projectId,documentId,revisions.get(0).getProjectionSha256(),module));
        }
        return result;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public EvidencePointer pointer(String projectId,Long documentId,String projection,String unitId,String module){
        BindingView view=readBindingView(projectId,documentId,projection,module);CanonicalSourceBinding binding=view.binding;
        JsonNode snapshot=view.snapshot,chunk=null;JsonNode candidates=snapshot.path("chunks");
        for(JsonNode candidate:candidates)if(unitId.equals(candidate.path("unitId").asText())){require(chunk==null,HttpStatus.CONFLICT,"CANONICAL_UNIT_AMBIGUOUS");chunk=candidate;}
        require(chunk!=null,HttpStatus.NOT_FOUND,"CANONICAL_UNIT_UNAVAILABLE");
        assertCurrent(binding,module);
        return pointer(binding,snapshot,chunk,module,view.collection?NativeDocumentCollectionContract.unitUnknowns(snapshot,unitId):unknowns(snapshot));
    }

    /** One source validation for all addresses, instead of hashing a large original once per chunk. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public List<EvidencePointer> pointers(String projectId,Long documentId,String projection,String module){
        BindingView view=readBindingView(projectId,documentId,projection,module);CanonicalSourceBinding binding=view.binding;
        JsonNode snapshot=view.snapshot;List<EvidencePointer> result=new ArrayList<>();
        if(view.collection){
            for(JsonNode chunk:snapshot.path("chunks"))result.add(pointer(binding,snapshot,chunk,module,NativeDocumentCollectionContract.unitUnknowns(snapshot,chunk.path("unitId").asText())));
            assertCurrent(binding,module);
        }else for(JsonNode chunk:snapshot.path("chunks"))result.add(pointer(binding,snapshot,chunk,module));
        return result;
    }

    /** Ordered complete parents from one immutable root. The stored DB snapshot remains the root only. */
    public JsonNode chunks(CanonicalSourceBinding binding,String module){
        validModule(module);
        require(binding!=null&&binding.getSnapshotJson()!=null,HttpStatus.BAD_REQUEST,"CANONICAL_BINDING_UNAVAILABLE");
        require(CanonicalJson.sha(binding.getSnapshotJson().getBytes(StandardCharsets.UTF_8)).equals(binding.getSnapshotBytesSha256()),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED");
        JsonNode snapshot=validateSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());validateBindingPins(binding,snapshot);
        if(!NativeDocumentCollectionContract.isCollection(snapshot)){
            if(NativePdfSourceFamilyContract.isFamily(snapshot)||NativePdfCanonicalContract.isNativePdf(snapshot))recheckNativeSnapshot(binding,module);
            return snapshot.path("chunks").deepCopy();
        }
        assertCurrent(binding,module);
        String current=backend.snapshot(binding.getProjectionSha256(),module);validateSnapshot(current,binding.getProjectionSha256());
        require(CanonicalJson.hash(current,null).equals(CanonicalJson.hash(binding.getSnapshotJson(),null)),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED");
        JsonNode result=NativeDocumentCollectionContract.readDocument(current,binding.getProjectionSha256(),
            ordinal->backend.batch(binding.getProjectionSha256(),ordinal,module).bytes,
            ordinal->backend.envelope(binding.getProjectionSha256(),ordinal,module).bytes).publicProjection.path("chunks");
        assertCurrent(binding,module);return result;
    }
    private static JsonNode manifest(JsonNode snapshot){return snapshot.path(NativeDocumentCollectionContract.isCollection(snapshot)?"manifestMetadata":"manifest");}
    private static String adoptionHash(JsonNode snapshot,String raw){
        if(NativePdfCanonicalContract.isNativePdf(snapshot))return snapshot.path("adoptionManifestRef").path("sha256").asText();
        return NativeDocumentCollectionContract.isCollection(snapshot)?snapshot.path("manifestSha256").asText():CanonicalJson.fieldHash(raw,"manifest");
    }
    private static JsonNode unknowns(JsonNode snapshot){return NativePdfSourceFamilyContract.isFamily(snapshot)?NativePdfSourceFamilyContract.unknowns(snapshot):NativePdfCanonicalContract.isNativePdf(snapshot)?snapshot.path("captureCompleteness").path("reference").path("unknowns"):snapshot.path("unknowns");}

    private static EvidencePointer pointer(CanonicalSourceBinding binding,JsonNode snapshot,JsonNode chunk,String module){
        return pointer(binding,snapshot,chunk,module,unknowns(snapshot));
    }
    private static EvidencePointer pointer(CanonicalSourceBinding binding,JsonNode snapshot,JsonNode chunk,String module,JsonNode unknowns){
        EvidencePointer pointer=new EvidencePointer();pointer.setProjectId(binding.getProjectId());pointer.setDocumentId(binding.getDocumentId());
        pointer.setOriginalSourceSha256(binding.getOriginalSourceSha256());pointer.setJavaParsedSourceHash(binding.getJavaParsedSourceHash());pointer.setSourceRevisionSha256(binding.getSourceRevisionSha256());
        pointer.setCanonicalSourceHash(binding.getCanonicalSourceHash());pointer.setProjectionSha256(binding.getProjectionSha256());pointer.setProjectionRevisionSha256(binding.getProjectionRevisionSha256());
        pointer.setModule(module);pointer.setUnitId(chunk.path("unitId").asText());pointer.setCanonicalChunkId(chunk.path("id").asText());
        boolean family=NativePdfSourceFamilyContract.isFamily(snapshot),nativePdf=!family&&NativePdfCanonicalContract.isNativePdf(snapshot);
        pointer.setSourceFilename(snapshot.path("source").path(nativePdf||family?"filename":"sourceFilename").deepCopy());pointer.setSourceOrder(chunk.path("sourceOrder").deepCopy());
        boolean collection=NativeDocumentCollectionContract.isCollection(snapshot);
        pointer.setProjectionSchema(collection?NativeCanonicalContract.SCHEMA:snapshot.path("schema").asText());
        if(family){
            pointer.setProducerKind(NativePdfSourceFamilyContract.PRODUCER);pointer.setStorageShape(NativePdfSourceFamilyContract.STORAGE);
            pointer.setSourceRevisionKind(NativePdfSourceFamilyContract.REVISION);pointer.setFamilyCompositionSha256(binding.getFamilyCompositionSha256());pointer.setFamilyComponentSetSha256(binding.getFamilyComponentSetSha256());
            pointer.setSourceFamilyOriginRef(chunk.path("sourceFamilyOriginRef").deepCopy());pointer.setNativePdfSourceFamilyEvidence(NativePdfSourceFamilyContract.displayDeclarations(snapshot));
            pointer.setOriginalGeometryStatus(chunk.path("parts").get(0).path("geometryStatus").asText());pointer.setVisualReturnStatus(snapshot.path("visualReturnStatus").asText());
            pointer.setBboxConvention(chunk.path("parts").get(0).path("bboxConvention").asText());pointer.setPhysicalPages(new ArrayList<>(pages(chunk.path("physicalPages"))));
        }else if(nativePdf){
            pointer.setProducerKind(NativePdfCanonicalContract.PRODUCER);pointer.setStorageShape(snapshot.path("storageShape").asText());
            pointer.setOriginalGeometryStatus(chunk.path("parts").get(0).path("geometryStatus").asText());pointer.setVisualReturnStatus(snapshot.path("visualReturnStatus").asText());
            pointer.setBboxConvention(chunk.path("parts").get(0).path("bboxConvention").asText());pointer.setPhysicalPages(new ArrayList<>(pages(chunk.path("physicalPages"))));
            pointer.setNativePdfEvidence(NativePdfCanonicalContract.displayDeclarations(snapshot));
        }else if(NativeCanonicalContract.isNative(snapshot)||collection){
            pointer.setProducerKind(NativeCanonicalContract.PRODUCER);pointer.setStorageShape(collection?NativeCanonicalContract.STORAGE:snapshot.path("storageShape").asText());
            pointer.setOriginalGeometryStatus(chunk.path("originalGeometryStatus").asText());pointer.setVisualReturnStatus(snapshot.path("visualReturnStatus").asText());
            pointer.setBboxConvention(null);pointer.setPhysicalPages(new ArrayList<>());
        }else pointer.setPhysicalPages(new ArrayList<>(pages(chunk.path("physicalPages"))));
        pointer.setEligibleModules(chunk.path("eligibleModules").deepCopy());pointer.setParts(chunk.path("parts").deepCopy());pointer.setSourceSpans(chunk.path("sourceSpans").deepCopy());
        pointer.setTableGroup(chunk.has("tableGroup")?chunk.get("tableGroup").deepCopy():null);pointer.setAssetRefs(chunk.path("assetRefs").deepCopy());pointer.setRelations(chunk.path("relations").deepCopy());
        pointer.setAdoption(chunk.path("adoption").deepCopy());pointer.setSourceQuality(chunk.path("sourceQuality").deepCopy());pointer.setUnknowns(unknowns.deepCopy());
        return pointer;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public CanonicalEvidenceBackend.Reply asset(String projectId,Long documentId,String projection,String assetId,String module){
        CanonicalSourceBinding binding=readBinding(projectId,documentId,projection,module);JsonNode reference=null;
        for(JsonNode candidate:parse(binding.getSnapshotJson()).path("assetRefs"))if(assetId.equals(candidate.path("assetId").asText())){
            require(reference==null,HttpStatus.CONFLICT,"CANONICAL_ASSET_AMBIGUOUS");reference=candidate;
        }
        require(reference!=null,HttpStatus.NOT_FOUND,"CANONICAL_ASSET_NOT_BOUND");
        CanonicalEvidenceBackend.Reply reply=backend.asset(projection,binding.getOriginalSourceSha256(),assetId,module);
        String type=reply.contentType==null?"":reply.contentType.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        require(binding.getOriginalSourceSha256().equals(reply.sourceSha256)&&projection.equals(reply.projectionSha256)
                &&("\""+reference.path("sha256").asText()+"\"").equals(reply.etag)
                &&type.equals(reference.path("mediaType").asText())&&reply.bytes.length==reference.path("byteCount").asLong(-1)
                &&CanonicalJson.sha(reply.bytes).equals(reference.path("sha256").asText())&&magic(type,reply.bytes),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_BYTES_MISMATCH");
        assertCurrent(binding,module);return reply;
    }


    /** Exact source proof is owned by one synchronous stage, never a request cache. */
    public static final class VerifiedBindingFrame implements AutoCloseable {
        private final CanonicalEvidenceService owner,boundary;
        private final Thread thread=Thread.currentThread();
        private final String project,module;
        private final Map<Long,BindingView> views=new LinkedHashMap<>();
        private boolean closed;
        private VerifiedBindingFrame(CanonicalEvidenceService owner,CanonicalEvidenceService boundary,String project,String module,List<BindingView> input){
            this.owner=owner;this.boundary=boundary;this.project=project;this.module=module;
            for(BindingView view:input){CanonicalSourceBinding binding=copyBinding(view.binding);
                require(views.putIfAbsent(binding.getDocumentId(),new BindingView(binding,view.snapshot.deepCopy(),view.collection))==null,
                        HttpStatus.CONFLICT,"CANONICAL_FRAME_DOCUMENT_AMBIGUOUS");}
        }
        private void active(){require(Thread.currentThread()==thread&&!closed,HttpStatus.CONFLICT,"CANONICAL_FRAME_CLOSED_OR_WRONG_THREAD");}
        private BindingView view(Long id,String projection,String module){
            active();BindingView view=views.get(id);
            require(this.module.equals(module)&&view!=null&&Objects.equals(projection,view.binding.getProjectionSha256()),
                    HttpStatus.CONFLICT,"CANONICAL_FRAME_SOURCE_OR_MODULE_MISMATCH");return view;
        }
        public List<CanonicalSourceBinding> bindings(){active();List<CanonicalSourceBinding> out=new ArrayList<>();
            for(BindingView view:views.values())out.add(copyBinding(view.binding));return Collections.unmodifiableList(out);}
        public JsonNode snapshot(Long documentId,String projection,String module){return view(documentId,projection,module).snapshot.deepCopy();}
        public List<EvidencePointer> pointers(Long documentId,String projection,String module){
            BindingView view=view(documentId,projection,module);List<EvidencePointer> out=new ArrayList<>();
            for(JsonNode chunk:view.snapshot.path("chunks"))out.add(pointer(view.binding,view.snapshot,chunk,module,
                    view.collection?NativeDocumentCollectionContract.unitUnknowns(view.snapshot,chunk.path("unitId").asText()):unknowns(view.snapshot)));
            return out;
        }
        public void requireOwner(CanonicalEvidenceService service,String project,String module){active();
            require(owner==service&&this.project.equals(project)&&this.module.equals(module),HttpStatus.CONFLICT,"CANONICAL_FRAME_OWNER_MISMATCH");}
        public void beforeWait(){active();boundary.assertFrameSourcesCurrent(this);}
        public void revalidate(){active();boundary.revalidateFrame(this);}
        @Override public void close(){
            if(closed)return;active();
            try{revalidate();}
            finally{closed=true;views.clear();}
        }
    }
    private static CanonicalSourceBinding copyBinding(CanonicalSourceBinding binding){
        // Every entity field is an immutable scalar/string/Instant; this detached
        // copy never exposes the managed entity or an internally retained node.
        CanonicalSourceBinding result=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(binding,result);return result;
    }
    /** Proxy calls arrive at the owning service target; do not compare the Spring proxy to its target. */
    public void assertFrameOwner(VerifiedBindingFrame frame,String project,String module){
        require(frame!=null,HttpStatus.CONFLICT,"CANONICAL_FRAME_UNAVAILABLE");frame.requireOwner(this,project,module);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public VerifiedBindingFrame verifiedBindings(String projectId,String module,List<Long> ids){
        validModule(module);require(ids!=null&&!ids.isEmpty()&&!ids.contains(null)&&new HashSet<>(ids).size()==ids.size(),
                HttpStatus.BAD_REQUEST,"CANONICAL_SELECTED_DOCUMENTS_INVALID");
        List<BindingView> input=new ArrayList<>();
        for(Long id:ids){List<CanonicalSourceBinding> revisions=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(projectId,id);
            require(!revisions.isEmpty(),HttpStatus.CONFLICT,"CANONICAL_SELECTED_DOCUMENT_UNREGISTERED");
            input.add(readBindingView(projectId,id,revisions.get(0).getProjectionSha256(),module));}
        VerifiedBindingFrame frame=new VerifiedBindingFrame(this,boundaryProxy==null?this:boundaryProxy.getObject(),projectId,module,input);
        try{frame.beforeWait();return frame;}
        catch(RuntimeException changed){frame.closed=true;frame.views.clear();throw changed;}
    }

    /** Fresh committed original/catalog/binding guard immediately before an external wait. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void assertFrameSourcesCurrent(VerifiedBindingFrame frame){
        frame.requireOwner(this,frame.project,frame.module);
        for(BindingView retained:frame.views.values()){assertCurrent(retained.binding,frame.module);
            List<CanonicalSourceBinding> latest=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(frame.project,retained.binding.getDocumentId());
            require(!latest.isEmpty()&&JsonUtils.write(copyBinding(latest.get(0))).equals(JsonUtils.write(retained.binding)),
                    HttpStatus.CONFLICT,"CANONICAL_FRAME_BINDING_CHANGED");}
    }
    /** Fresh CAS plus committed source guards after waits; a frame never certifies another stage. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void revalidateFrame(VerifiedBindingFrame frame){
        frame.requireOwner(this,frame.project,frame.module);
        for(BindingView retained:frame.views.values()){
            List<CanonicalSourceBinding> latest=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(frame.project,retained.binding.getDocumentId());
            require(!latest.isEmpty()&&JsonUtils.write(copyBinding(latest.get(0))).equals(JsonUtils.write(retained.binding)),
                    HttpStatus.CONFLICT,"CANONICAL_FRAME_BINDING_CHANGED");
            BindingView fresh=readBindingView(frame.project,retained.binding.getDocumentId(),latest.get(0).getProjectionSha256(),frame.module);
            require(fresh.snapshot.equals(retained.snapshot)&&fresh.collection==retained.collection,HttpStatus.CONFLICT,"CANONICAL_FRAME_SNAPSHOT_CHANGED");
        }
        // The binding loaded before a CAS wait may still be in this transaction's
        // JPA identity map. A separate proxy transaction must re-read every field
        // after that wait; an unchanged projection SHA alone is insufficient.
        frame.beforeWait();
    }

    private static final class BindingView {
        final CanonicalSourceBinding binding;final JsonNode snapshot;final boolean collection;
        BindingView(CanonicalSourceBinding binding,JsonNode snapshot,boolean collection){this.binding=binding;this.snapshot=snapshot;this.collection=collection;}
    }
    private CanonicalSourceBinding readBinding(String projectId,Long documentId,String projection,String module){
        return readBindingView(projectId,documentId,projection,module).binding;
    }
    private BindingView readBindingView(String projectId,Long documentId,String projection,String module){
        validSha(projection);validModule(module);
        CanonicalSourceBinding binding=bindings.findByProjectIdAndDocumentIdAndProjectionSha256(projectId,documentId,projection)
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"CANONICAL_BINDING_UNAVAILABLE"));
        require(binding.getSnapshotJson()!=null&&CanonicalJson.sha(binding.getSnapshotJson().getBytes(StandardCharsets.UTF_8)).equals(binding.getSnapshotBytesSha256()),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED");
        JsonNode snapshot=validateSnapshot(binding.getSnapshotJson(),projection);validateBindingPins(binding,snapshot);boolean collection=NativeDocumentCollectionContract.isCollection(snapshot);
        if(NativePdfSourceFamilyContract.isFamily(snapshot)||NativePdfCanonicalContract.isNativePdf(snapshot)||NativeCanonicalContract.isNative(snapshot)||NativeDocumentCollectionContract.isCollection(snapshot)){
            // Native compact snapshots refer to private proof. Re-read the registry so every
            // public operation revalidates the actual CAS bytes, even for a persisted binding.
            snapshot=recheckNativeSnapshot(binding,module);
        }else assertCurrent(binding,module); // Native reads already have fresh pre/post gates around the registry call.
        return new BindingView(binding,snapshot,collection);
    }
    private JsonNode recheckNativeSnapshot(CanonicalSourceBinding binding,String module){
        assertCurrent(binding,module);String current=backend.snapshot(binding.getProjectionSha256(),module);JsonNode snapshot=validateSnapshot(current,binding.getProjectionSha256());
        require(CanonicalJson.hash(current,null).equals(CanonicalJson.hash(binding.getSnapshotJson(),null)),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED");
        if(NativeDocumentCollectionContract.isCollection(snapshot))snapshot=NativeDocumentCollectionContract.readDocument(current,binding.getProjectionSha256(),
            ordinal->backend.batch(binding.getProjectionSha256(),ordinal,module).bytes,
            ordinal->backend.envelope(binding.getProjectionSha256(),ordinal,module).bytes).publicProjection;
        assertCurrent(binding,module);return snapshot;
    }
    private void assertCurrent(CanonicalSourceBinding binding,String module){sources.assertCurrent(binding,module);}
    private SourceDocument currentDocument(String projectId,Long id,String module){return sources.currentDocument(projectId,id,module);}
    static String routingHash(SourceDocument d){return CanonicalJson.sha(JsonUtils.write(Arrays.asList(d.getCategory(),d.getFileKey(),d.getReviewRole(),d.getDocumentScope(),d.getParseStatus(),d.getOerIdentityJson())).getBytes(StandardCharsets.UTF_8));}
    public static void validateBindingPins(CanonicalSourceBinding binding,JsonNode snapshot){
        JsonNode identity=snapshot.path("projectionIdentity");
        boolean family=NativePdfSourceFamilyContract.isFamily(snapshot);
        require(family?binding.getAdoptionManifestSha256()==null&&binding.getReviewReferenceSha256()==null
                &&Objects.equals(binding.getFamilyCompositionSha256(),identity.path("compositionSha256").asText())
                &&Objects.equals(binding.getFamilyComponentSetSha256(),identity.path("componentSetSha256").asText())
                :binding.getFamilyCompositionSha256()==null&&binding.getFamilyComponentSetSha256()==null
                &&Objects.equals(binding.getAdoptionManifestSha256(),adoptionHash(snapshot,binding.getSnapshotJson()))
                &&Objects.equals(binding.getReviewReferenceSha256(),manifest(snapshot).path("reference").path("evidenceSha256").asText()),HttpStatus.CONFLICT,"CANONICAL_BINDING_TYPED_PINS_CHANGED");
    }
    private static CanonicalSourceBinding sameBinding(CanonicalSourceBinding existing,CanonicalSourceBinding candidate){
        validateBindingPins(existing,validateSnapshot(existing.getSnapshotJson(),existing.getProjectionSha256()));
        validateBindingPins(candidate,validateSnapshot(candidate.getSnapshotJson(),candidate.getProjectionSha256()));
        require(Objects.equals(existing.getOriginalSourceSha256(),candidate.getOriginalSourceSha256())&&Objects.equals(existing.getJavaParsedSourceHash(),candidate.getJavaParsedSourceHash())
                &&Objects.equals(existing.getRoutingSha256(),candidate.getRoutingSha256())
                &&CanonicalJson.hash(existing.getSnapshotJson(),null).equals(CanonicalJson.hash(candidate.getSnapshotJson(),null)),HttpStatus.CONFLICT,"CANONICAL_BINDING_IMMUTABLE_CONFLICT");return existing;
    }
    private static JsonNode parse(String value){try{return JsonUtils.parse(value);}catch(Exception invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}}
    private static void validModule(String module){require(MODULES.contains(module),HttpStatus.BAD_REQUEST,"CANONICAL_MODULE_INVALID");}
    private static void validSha(String sha){require(sha!=null&&sha.matches("[a-f0-9]{64}"),HttpStatus.BAD_REQUEST,"CANONICAL_SHA_INVALID");}
    private static void require(boolean condition,HttpStatus status,String reason){if(!condition)throw new CanonicalEvidenceException(status,reason);}
    private static Set<String> modules(JsonNode value){require(value.isArray()&&value.size()>0,HttpStatus.BAD_GATEWAY,"CANONICAL_ELIGIBILITY_INVALID");Set<String> result=new LinkedHashSet<>();for(JsonNode item:value){require(item.isTextual()&&MODULES.contains(item.asText())&&result.add(item.asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_ELIGIBILITY_INVALID");}return result;}
    private static Set<Integer> pages(JsonNode value){require(value.isArray()&&value.size()>0,HttpStatus.BAD_GATEWAY,"CANONICAL_PAGES_INVALID");Set<Integer> result=new TreeSet<>();int previous=0;for(JsonNode p:value){require(p.isIntegralNumber()&&p.canConvertToInt()&&p.asInt()>previous,HttpStatus.BAD_GATEWAY,"CANONICAL_PAGES_INVALID");previous=p.asInt();result.add(previous);}return result;}
    private static boolean magic(String media,byte[] bytes){return ("image/png".equals(media)&&starts(bytes,new byte[]{(byte)137,80,78,71,13,10,26,10}))
            ||("image/jpeg".equals(media)&&starts(bytes,new byte[]{(byte)255,(byte)216,(byte)255}))
            ||("application/pdf".equals(media)&&starts(bytes,"%PDF-".getBytes(StandardCharsets.US_ASCII)))
            ||("image/webp".equals(media)&&bytes.length>=12&&starts(bytes,"RIFF".getBytes(StandardCharsets.US_ASCII))&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP"));}
    private static boolean starts(byte[] actual,byte[] prefix){if(actual.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(actual[i]!=prefix[i])return false;return true;}

    /** Shared version gate; source freshness and actual CAS reads belong to the service/registry. */
    public static JsonNode validateSnapshot(String raw,String address){
        require(raw!=null&&raw.getBytes(StandardCharsets.UTF_8).length<=NativePdfCanonicalContract.MAX_BYTES,HttpStatus.BAD_GATEWAY,"CANONICAL_SNAPSHOT_TOO_LARGE");
        JsonNode snapshot=parse(raw);
        if(NativePdfSourceFamilyContract.isFamily(snapshot))return NativePdfSourceFamilyContract.validateEnvelope(raw,address);
        if(NativePdfCanonicalContract.isNativePdf(snapshot)){
            require(address!=null&&address.equals(snapshot.path("projectionSha256").asText())&&address.equals(CanonicalJson.hash(raw,"projectionSha256")),HttpStatus.BAD_GATEWAY,"CANONICAL_SEAL_MISMATCH");
            NativePdfCanonicalContract.validateEnvelope(snapshot);return snapshot;
        }
        if(NativeDocumentCollectionContract.isCollection(snapshot))return NativeDocumentCollectionContract.validateRoot(raw,address);
        boolean nativeDocx=NativeCanonicalContract.isNative(snapshot);
        require(snapshot.isObject()&&(nativeDocx||"consense.canonical-projection.v1".equals(snapshot.path("schema").asText()))
                &&address.equals(snapshot.path("projectionSha256").asText())&&address.equals(CanonicalJson.hash(raw,"projectionSha256")),HttpStatus.BAD_GATEWAY,"CANONICAL_SEAL_MISMATCH");
        validSha(snapshot.path("sourceHash").asText());validSha(snapshot.path("projectionRevisionSha256").asText());
        require(snapshot.path("humanConfirmed").isBoolean()&&!snapshot.path("humanConfirmed").asBoolean()
                &&snapshot.path("chunks").isArray()&&snapshot.path("chunks").size()>0&&snapshot.path("assetRefs").isArray(),HttpStatus.BAD_GATEWAY,"CANONICAL_SNAPSHOT_INVALID");
        String source=snapshot.path("source").path("sourceSha256").asText();validSha(source);
        JsonNode identity=snapshot.path("projectionIdentity");Set<String> eligible=modules(identity.path("eligibleModules"));
        require(source.equals(identity.path("sourceSha256").asText())&&snapshot.path("source").path("sourceRevisionSha256").equals(identity.path("sourceRevisionSha256"))
                &&identity.path("documentId").equals(snapshot.path("manifest").path("documentId"))
                &&identity.path("module").equals(snapshot.path("manifest").path("module"))
                &&identity.path("role").equals(snapshot.path("manifest").path("role"))
                &&identity.path("sourcePurpose").equals(snapshot.path("manifest").path("sourcePurpose"))
                &&identity.path("eligibleModules").equals(snapshot.path("manifest").path("eligibleModules"))
                &&source.equals(snapshot.path("manifest").path("reference").path("sourceSha256").asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_IDENTITY_MISMATCH");
        if(nativeDocx)NativeCanonicalContract.validateEnvelope(snapshot);
        Set<String> assets=new HashSet<>(),units=new HashSet<>(),chunkIds=new HashSet<>();
        for(JsonNode asset:snapshot.path("assetRefs")){
            String id=asset.path("assetId").asText();require(id.matches("asset:[a-f0-9]{64}")&&assets.add(id),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_INVALID");
            validSha(asset.path("sha256").asText());
            require(source.equals(asset.path("sourceSha256").asText())&&asset.path("byteCount").isIntegralNumber()&&asset.path("byteCount").asLong()>0
                    &&asset.path("sha256").equals(asset.path("artifactDescriptor").path("sha256"))
                    &&asset.path("byteCount").equals(asset.path("artifactDescriptor").path("byteCount")),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_DESCRIPTOR_MISMATCH");
            require(pages(asset.path("physicalPages")).size()==1&&asset.path("sourceRegions").isArray()&&asset.path("sourceRegions").size()>0,HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_REGIONS_INVALID");
            for(JsonNode region:asset.path("sourceRegions"))validateBox(region);
        }
        for(JsonNode chunk:snapshot.path("chunks")){
            String unit=chunk.path("unitId").asText();require(!unit.trim().isEmpty()&&units.add(unit)&&chunk.path("id").asText().matches("canonical:[a-f0-9]{64}")&&chunkIds.add(chunk.path("id").asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_UNIT_INVALID");
            require(source.equals(chunk.path("sourceSha256").asText())&&source.equals(chunk.path("originalSourceSha256").asText())
                    &&chunk.path("documentId").equals(identity.path("documentId"))&&chunk.path("module").equals(identity.path("module"))
                    &&chunk.path("role").equals(identity.path("role"))&&chunk.path("sourcePurpose").equals(identity.path("sourcePurpose"))
                    &&chunk.path("sourceRevisionSha256").equals(snapshot.path("source").path("sourceRevisionSha256"))
                    &&eligible.equals(modules(chunk.path("eligibleModules")))&&chunk.path("sourceHash").equals(snapshot.path("sourceHash"))
                    &&chunk.path("projectionRevisionSha256").equals(snapshot.path("projectionRevisionSha256"))
                    &&chunk.path("sourceQuality").path("semanticQuality").asText().equals("unknown")
                    &&chunk.path("humanConfirmed").isBoolean()&&!chunk.path("humanConfirmed").asBoolean(),HttpStatus.BAD_GATEWAY,"CANONICAL_CHUNK_BINDING_MISMATCH");
            if(nativeDocx){NativeCanonicalContract.validateChunk(chunk,source);continue;}
            Set<Integer> physical=pages(chunk.path("physicalPages"));Set<Integer> partsPages=new TreeSet<>();Set<String> blocks=new HashSet<>();
            require(chunk.path("parts").isArray()&&chunk.path("parts").size()>0&&chunk.path("assetRefs").isArray(),HttpStatus.BAD_GATEWAY,"CANONICAL_PARTS_INVALID");
            for(JsonNode part:chunk.path("parts")){validateBox(part);require(source.equals(part.path("sourceSha256").asText())&&part.path("physicalPageNo").isIntegralNumber()
                    &&blocks.add(part.path("blockId").asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_PART_BINDING_MISMATCH");partsPages.add(part.path("physicalPageNo").asInt());}
            require(physical.equals(partsPages),HttpStatus.BAD_GATEWAY,"CANONICAL_PAGE_SPAN_MISMATCH");
            Set<Integer> assetPages=new TreeSet<>();Set<String> assetBlocks=new HashSet<>();List<JsonNode> expectedAssets=new ArrayList<>();
            for(JsonNode asset:snapshot.path("assetRefs"))if(unit.equals(asset.path("unitId").asText())){expectedAssets.add(asset);assetPages.addAll(pages(asset.path("physicalPages")));for(JsonNode region:asset.path("sourceRegions"))assetBlocks.add(region.path("blockId").asText());}
            require(expectedAssets.size()==chunk.path("assetRefs").size(),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_GROUP_MISMATCH");
            int at=0;for(JsonNode asset:chunk.path("assetRefs"))require(asset.equals(expectedAssets.get(at++)),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_GROUP_MISMATCH");
            if("table_group".equals(chunk.path("canonicalScope").asText())){
                require(physical.equals(pages(chunk.path("tableGroup").path("physicalPages"))),HttpStatus.BAD_GATEWAY,"CANONICAL_TABLE_GROUP_INVALID");
                // A table with no assets is a text-only unit. Once assets are declared, the complete group is mandatory.
                require(expectedAssets.isEmpty()||(physical.equals(assetPages)&&blocks.equals(assetBlocks)),HttpStatus.BAD_GATEWAY,"CANONICAL_TABLE_GROUP_ASSETS_INCOMPLETE");
            }
        }
        for(JsonNode asset:snapshot.path("assetRefs"))require(units.contains(asset.path("unitId").asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_UNIT_UNBOUND");
        return snapshot;
    }
    private static void validateBox(JsonNode node){JsonNode box=node.path("bbox");require("normalized_xyxy_original_page".equals(node.path("bboxConvention").asText())&&box.isArray()&&box.size()==4,HttpStatus.BAD_GATEWAY,"CANONICAL_COORDINATES_INVALID");double[] b=new double[4];for(int i=0;i<4;i++){b[i]=box.get(i).asDouble(Double.NaN);require(box.get(i).isNumber()&&Double.isFinite(b[i])&&b[i]>=0&&b[i]<=1,HttpStatus.BAD_GATEWAY,"CANONICAL_COORDINATES_INVALID");}require(b[0]<=b[2]&&b[1]<=b[3],HttpStatus.BAD_GATEWAY,"CANONICAL_COORDINATES_INVALID");}
}
