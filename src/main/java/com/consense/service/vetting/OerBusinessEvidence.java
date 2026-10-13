package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalSourcePageService;
import com.consense.service.sources.NativeCanonicalContract;
import com.consense.service.sources.NativePdfCanonicalContract;
import com.consense.service.sources.NativePdfSourceFamilyContract;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/** Shared current-source corpus, with full sealed evidence beside a model/citation read-only view. */
@Service @RequiredArgsConstructor
public class OerBusinessEvidence {
    private final CanonicalEvidenceService evidence;
    private final OerCanonicalRetrievalClient retrieval;
    private final CanonicalEvidenceProperties properties;
    @Autowired(required=false) private CanonicalSourcePageService sourcePages;

    public boolean enabled(){return !JsonUtils.isBlankText(properties.getBaseUrl());}
    public static final class Session {
        public final String projectId,module,indexKey;
        public final List<Long> documentIds;
        private final String bindingFingerprint;
        private final Map<String,String> sourceRenderPins;
        private final Map<String,Chunk> byId;
        private final Map<String,String> viewPayloads;
        private final Corpus chunks;
        private volatile boolean invalidated;
        private Session(String project,String module,List<Long> ids,List<CanonicalSourceBinding> bindings,Map<String,Chunk> views,Map<String,String> pins){
            this.projectId=project;this.module=module;this.documentIds=Collections.unmodifiableList(new ArrayList<>(ids));
            this.bindingFingerprint=fingerprint(bindings);this.byId=views;
            this.sourceRenderPins=Collections.unmodifiableMap(new TreeMap<>(pins));
            this.viewPayloads=new LinkedHashMap<>();views.forEach((id,view)->viewPayloads.put(id,JsonUtils.write(view)));
            Object projections=bindings.stream().map(CanonicalSourceBinding::getProjectionSha256).collect(Collectors.toList());
            this.indexKey=project+"::canonical::"+module+"::"+VettingCorpus.hash(JsonUtils.write(pins.isEmpty()?projections:Arrays.asList(projections,sourceRenderPins)));
            this.chunks=new Corpus(this,new ArrayList<>(views.values()));
        }
        public List<Chunk> chunks(){return chunks;}
        public int chunkCount(){return chunks.size();}
        public String identitySha256(){return renderFingerprint(bindingFingerprint,sourceRenderPins);}
    }
    private static final class Corpus extends AbstractList<Chunk>{
        final Session session;private final List<Chunk> views;
        Corpus(Session s,List<Chunk> views){this.views=Collections.unmodifiableList(views);session=s;}
        public Chunk get(int i){return views.get(i);}public int size(){return views.size();}
    }
    public static boolean canonicalCorpus(List<Chunk> corpus){return corpus instanceof Corpus;}
    public static Session session(List<Chunk> corpus){if(!(corpus instanceof Corpus))throw new IllegalArgumentException("Canonical corpus session required");return ((Corpus)corpus).session;}


    private static final class CurrentStage implements AutoCloseable {
        final CanonicalEvidenceService.VerifiedBindingFrame frame;final List<CanonicalSourceBinding> bindings;Session session;
        CurrentStage(CanonicalEvidenceService.VerifiedBindingFrame frame,List<CanonicalSourceBinding> bindings){this.frame=frame;this.bindings=bindings;}
        @Override public void close(){if(frame!=null)try{frame.close();}catch(RuntimeException changed){if(session!=null)session.invalidated=true;throw changed;}}
    }
    private CurrentStage stage(String project,String module,List<Long> ids){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(project,module,ids);
        // Direct legacy test adapters expose untrusted lists, never a reusable
        // source verdict. That path retains all original fresh reads.
        if(frame==null)return new CurrentStage(null,evidence.currentBindings(project,module,ids));
        try{evidence.assertFrameOwner(frame,project,module);return new CurrentStage(frame,frame.bindings());}
        catch(RuntimeException failure){frame.close();throw failure;}
    }
    private CurrentStage stage(Session session){
        require(!session.invalidated,"canonical session invalidated; reload current sources");
        CurrentStage stage=null;
        try{stage=stage(session.projectId,session.module,session.documentIds);stage.session=session;checkViews(session,stage.bindings);return stage;}
        catch(RuntimeException changed){session.invalidated=true;if(stage!=null)stage.close();throw changed;}
    }
    private void checkViews(Session session,List<CanonicalSourceBinding> bindings){
        require(session.bindingFingerprint.equals(fingerprint(bindings)),"current bindings changed since corpus load");
        require(session.sourceRenderPins.equals(sourceRenderPins(bindings)),"source render identities changed since corpus load");
        for(Chunk view:session.chunks)require(Objects.equals(session.viewPayloads.get(view.getId()),JsonUtils.write(view))
                &&canonicalViewStatus(view)==null,"selected canonical view changed");
    }

    public Session load(String projectId,String module,List<SourceDocument> selected) {
        Map<Long,SourceDocument> documents=selectedDocuments(projectId,selected);
        CurrentStage loaded=stage(projectId,module,new ArrayList<>(documents.keySet()));Session session;
        try(CurrentStage held=loaded){session=loadSession(projectId,module,documents,held);}
        complete(session,loaded);return session;
    }
    private Map<Long,SourceDocument> selectedDocuments(String projectId,List<SourceDocument> selected){
        if(!enabled())throw new IllegalStateException("OER canonical business backend is not configured");
        require(selected!=null&&!selected.isEmpty(),"no permitted current documents");
        Map<Long,SourceDocument> documents=new LinkedHashMap<>();for(SourceDocument d:selected)
            require(d!=null&&d.getId()!=null&&Objects.equals(projectId,d.getProjectId())&&documents.putIfAbsent(d.getId(),d)==null,"selected document identity differs");
        return documents;
    }
    private Session loadSession(String projectId,String module,Map<Long,SourceDocument> documents,CurrentStage stage){
        List<Long> ids=new ArrayList<>(documents.keySet());
        List<CanonicalSourceBinding> bindings=stage.bindings;
        Map<String,String> pins=sourceRenderPins(bindings);
        require(pins.isEmpty()||sourcePages!=null,"source page validator required for reviewed render identities");
        require(ids.equals(bindings.stream().map(CanonicalSourceBinding::getDocumentId).collect(Collectors.toList())),"selected binding order differs");
        Map<String,Chunk> views=new LinkedHashMap<>();for(CanonicalSourceBinding binding:bindings) {
            JsonNode snapshot=JsonUtils.parse(binding.getSnapshotJson());SourceDocument source=documents.get(binding.getDocumentId());
            // A large native document has one root binding. Complete batches
            // are verified by the source service; the root is not a chunk.
            JsonNode payloads="consense.native-document.v1".equals(snapshot.path("schema").asText())
                    ?(stage.frame==null?evidence.chunks(binding,module):stage.frame.snapshot(binding.getDocumentId(),binding.getProjectionSha256(),module).path("chunks")):snapshot.path("chunks");
            Map<String,EvidencePointer> pointers=new LinkedHashMap<>();
            for(EvidencePointer verified:stage.frame==null?evidence.pointers(projectId,binding.getDocumentId(),binding.getProjectionSha256(),module)
                    :stage.frame.pointers(binding.getDocumentId(),binding.getProjectionSha256(),module)){
                EvidencePointer pointer=sourcePages==null?verified:sourcePages.enrich(verified);
                require(Objects.equals(pins.get(binding.getProjectionSha256()),pointer.getSourceRenderEvidenceIdentitySha256()),"source render pointer differs from reviewed identity");
                require(pointers.putIfAbsent(pointer.getCanonicalChunkId(),pointer)==null,"duplicate canonical pointer");
            }
            require(payloads!=null&&payloads.isArray()&&pointers.size()==payloads.size(),"canonical pointer count differs");
            for(JsonNode payload:payloads) {
                EvidencePointer pointer=pointers.get(payload.path("id").asText());require(pointer!=null,"canonical pointer missing");
                if(NativePdfSourceFamilyContract.isFamily(snapshot))require(NativePdfSourceFamilyContract.displayDeclarations(snapshot).equals(pointer.getNativePdfSourceFamilyEvidence()),"native PDF family display declarations differ");
                else if(NativePdfCanonicalContract.isNativePdf(snapshot))require(NativePdfCanonicalContract.displayDeclarations(snapshot).equals(pointer.getNativePdfEvidence()),"native PDF display declarations differ");
                Chunk view=view(payload,pointer,source);require(views.putIfAbsent(view.getId(),view)==null,"duplicate canonical chunk");
                require(canonicalViewStatus(view)==null,"canonical pointer and selected snapshot differ");
            }
        }
        return new Session(projectId,module,ids,bindings,views,pins);
    }
    /** Scalar result only: the verified frame and its source verdict never leave this request. */
    public static final class CurrentIndexStatus {
        public final String indexKey;public final int chunks,documentCount;
        public final OerCanonicalRetrievalClient.IndexState state;
        private CurrentIndexStatus(Session session,OerCanonicalRetrievalClient.IndexState state){
            this.indexKey=session.indexKey;this.chunks=session.chunkCount();this.documentCount=session.documentIds.size();this.state=state;
        }
    }
    /** A status read owns one fresh frame; the selected catalog is fetched before health and after close. */
    public CurrentIndexStatus currentIndexStatus(String projectId,String module,Supplier<List<SourceDocument>> currentSelected){
        require(currentSelected!=null,"current selected scope reader required");
        Map<Long,SourceDocument> documents=selectedDocuments(projectId,currentSelected.get());
        CurrentStage loaded=stage(projectId,module,new ArrayList<>(documents.keySet()));Session session;
        OerCanonicalRetrievalClient.IndexState state;
        try(CurrentStage held=loaded){
            session=loadSession(projectId,module,documents,held);held.session=session;
            assertSelectedScope(session,currentSelected.get());
            state=held.frame==null?retrieval.indexStatus(session.indexKey,module,held.bindings)
                    :retrieval.indexStatus(session.indexKey,module,held.frame);
        }
        complete(session,loaded);
        // A new eligible member can appear during the exit CAS wait as well as health.
        assertSelectedScope(session,currentSelected.get());
        return new CurrentIndexStatus(session,state);
    }
    private void complete(Session session,CurrentStage stage){
        if(stage.frame==null){current(session);return;}
        try{checkViews(session,stage.bindings);}catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    public void index(Session session){
        CurrentStage loaded=stage(session);
        try(CurrentStage stage=loaded){if(stage.frame==null)retrieval.index(session.indexKey,session.module,stage.bindings);
            else retrieval.index(session.indexKey,session.module,stage.frame);}
        complete(session,loaded);
    }
    public OerCanonicalRetrievalClient.IndexState indexStatus(Session session){
        OerCanonicalRetrievalClient.IndexState state;
        CurrentStage loaded=stage(session);
        try(CurrentStage stage=loaded){state=stage.frame==null?retrieval.indexStatus(session.indexKey,session.module,stage.bindings)
                :retrieval.indexStatus(session.indexKey,session.module,stage.frame);}
        complete(session,loaded);return state;
    }
    /** One fresh selected-scope stage directly precedes the external health wait. */
    public OerCanonicalRetrievalClient.IndexState indexStatusSelected(Session session,List<SourceDocument> selected){
        try{assertSelectedScope(session,selected);
            return indexStatus(session);
        }catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    /** Pure selected-scope identity check; callers fetch the current catalog anew at a remote boundary. */
    public void assertSelectedScope(Session session,List<SourceDocument> selected){
        try{require(!session.invalidated,"canonical session invalidated; reload current sources");
            require(selected!=null&&selected.stream().allMatch(d->d!=null&&session.projectId.equals(d.getProjectId())),"selected scope identity differs");
            require(session.documentIds.equals(selected.stream().map(SourceDocument::getId).collect(Collectors.toList())),"current selected scope changed");
        }catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    public void ensureIndex(Session session){if(!indexStatus(session).ready)index(session);}
    public void assertCurrent(Session session){current(session);}
    public String currentIdentity(String projectId,String module,List<SourceDocument> selected){
        List<CanonicalSourceBinding> bindings=evidence.currentBindings(projectId,module,selected.stream().map(SourceDocument::getId).collect(Collectors.toList()));
        return renderFingerprint(fingerprint(bindings),sourceRenderPins(bindings));
    }
    /** Callers obtain the complete selected scope anew; fixed IDs alone cannot detect new package members. */
    public void assertSelected(Session session,List<SourceDocument> selected){
        try {
            require(selected!=null&&selected.stream().allMatch(d->d!=null&&session.projectId.equals(d.getProjectId())),"selected scope identity differs");
            require(session.documentIds.equals(selected.stream().map(SourceDocument::getId).collect(Collectors.toList())),"current selected scope changed");current(session);
        } catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    private List<CanonicalSourceBinding> current(Session session){
        require(!session.invalidated,"canonical session invalidated; reload current sources");
        try {
            List<CanonicalSourceBinding> bindings=evidence.currentBindings(session.projectId,session.module,session.documentIds);
            checkViews(session,bindings);
            return bindings;
        } catch(RuntimeException invalid){session.invalidated=true;throw invalid;}
    }
    private static String fingerprint(List<CanonicalSourceBinding> bindings){
        List<List<Object>> identities=new ArrayList<>();for(CanonicalSourceBinding b:bindings)
            identities.add(Arrays.asList(b.getProjectId(),b.getDocumentId(),b.getOriginalSourceSha256(),b.getJavaParsedSourceHash(),
                    b.getRoutingSha256(),b.getSourceRevisionSha256(),b.getProjectionSha256(),b.getProjectionRevisionSha256(),
                    b.getCanonicalSourceHash(),b.getScopePolicySha256(),b.getAdoptionManifestSha256(),b.getReviewReferenceSha256(),b.getFamilyCompositionSha256(),b.getFamilyComponentSetSha256(),
                    b.getDeclaredModule(),b.getEligibleModulesJson(),b.getSnapshotBytesSha256(),VettingCorpus.hash(b.getSnapshotJson())));
        return VettingCorpus.hash(JsonUtils.write(identities));
    }
    private Map<String,String> sourceRenderPins(List<CanonicalSourceBinding> bindings){
        Map<String,String> pins=new TreeMap<>();for(CanonicalSourceBinding binding:bindings){
            String identity=properties.getSourcePageRenderEvidenceIdentities().get(binding.getProjectionSha256());
            if(identity!=null){require(identity.matches("[a-f0-9]{64}"),"reviewed source render identity invalid");pins.put(binding.getProjectionSha256(),identity);}
        }return pins;
    }
    private static String renderFingerprint(String bindings,Map<String,String> pins){
        return pins.isEmpty()?bindings:VettingCorpus.hash(JsonUtils.write(Arrays.asList(bindings,pins)));
    }
    public List<Chunk> retrieve(Session session,String query,String role){
        JsonNode response=response(session,query,role);List<Chunk> seeds=new ArrayList<>();List<VettingRetrievalSourceUnits.Unit> units=new ArrayList<>();
        for(JsonNode hit:response.path("hits"))seeds.add(copy(session.byId.get(hit.path("id").asText())));
        for(JsonNode raw:response.path("sourceUnits").path("units")) {
            ObjectNode traceNode=raw.deepCopy();traceNode.remove("members");
            if(traceNode.path("hierarchyContext").isObject())((ObjectNode)traceNode.path("hierarchyContext")).remove("members");
            if(traceNode.path("attachmentContext").isObject())((ObjectNode)traceNode.path("attachmentContext")).remove("members");
            Map<String,Object> trace=JsonUtils.readMap(JsonUtils.write(traceNode));
            trace.put("selectionPolicy",response.path("sourceUnits").path("policy").asText());
            JsonNode transport=transportUnit(raw,canonicalParents(session));List<String> ids=new ArrayList<>();transport.path("requiredMemberIds").forEach(id->ids.add(id.asText()));
            units.add(new VettingRetrievalSourceUnits.Unit(raw.path("originIds").get(0).asText(),completeTransport(transport)?"complete_observed_unit":"unknown",
                    transport.path("reason").isNull()?null:transport.path("reason").asText(),ids,trace));
        }
        return new VettingRetrievalSourceUnits.RankedChunks(seeds,units);
    }
    public JsonNode response(Session session,String query,String role){
        JsonNode response;
        CurrentStage loaded=stage(session);
        try(CurrentStage stage=loaded){response=stage.frame==null?retrieval.retrieve(session.indexKey,session.module,query,role,stage.bindings)
                :retrieval.retrieve(session.indexKey,session.module,query,role,stage.frame);}
        complete(session,loaded);return response;
    }

    /** One ranked hit carries the whole observed unit; its members receive no invented rerank scores. */
    public List<VectorStore.SearchHit> adviceHits(Session session,String question) {
        JsonNode result=response(session,question,null);List<VectorStore.SearchHit> out=new ArrayList<>();
        for(int i=0;i<result.path("hits").size();i++) {
            JsonNode hit=result.path("hits").get(i),unit=result.path("sourceUnits").path("units").get(i);
            out.add(businessHit(session,hit.path("id").asText(),unit,hit.path("score").asDouble(),"reranked_seed"));
        }return out;
    }
    /** Pure observed closure for explicitly selected origins in this same immutable corpus. */
    public List<VectorStore.SearchHit> sourceHits(Session session,List<String> originIds){
        List<CanonicalSourceBinding> bindings=current(session);require(originIds!=null&&new HashSet<>(originIds).size()==originIds.size(),"context origins invalid");
        String policy=retrieval.sourceUnitPolicy(session.indexKey,session.module,bindings);
        require(VettingRetrievalSourceUnits.supportsPolicy(policy),"context source-unit capability is unknown");
        Map<String,JsonNode> parents=new LinkedHashMap<>();session.byId.forEach((id,view)->parents.put(id,view.getCanonicalPayload()));
        List<VectorStore.SearchHit> out=new ArrayList<>();Set<String> used=new HashSet<>();
        for(String id:originIds){require(parents.containsKey(id),"context origin outside selected corpus");
            JsonNode unit=VettingRetrievalSourceUnits.attachmentPolicy(policy)
                    ?OerCanonicalSourceUnits.resolveAttached(parents,parents.get(id),VettingRetrievalSourceUnits.SHARED_NOTE_POLICY.equals(policy))
                    :VettingRetrievalSourceUnits.TYPED_PARENT_POLICY.equals(policy)?OerCanonicalSourceUnits.resolve(parents,parents.get(id))
                    :OerCanonicalSourceUnits.resolveObserved(parents,parents.get(id));if(used.add(unit.path("unitId").asText()))
                out.add(businessHit(session,id,unit,0d,"context_only"));
        }current(session);return out;
    }
    /** Deterministic checks traverse only accepted observations, retaining source-bound original anchors. */
    public List<VettingRuleEngine.Source> ruleSources(Session session){
        current(session);Map<String,List<Chunk>> byDocument=new LinkedHashMap<>();for(Chunk c:session.chunks)
            if("tender".equals(c.getRole()))byDocument.computeIfAbsent(c.getDocumentId(),key->new ArrayList<>()).add(c);
        List<VettingRuleEngine.Source> out=new ArrayList<>();for(List<Chunk> chunks:byDocument.values()){
            Chunk first=chunks.get(0);List<com.consense.document.DocumentBlock> blocks=new ArrayList<>();Set<String> observed=new HashSet<>();
            for(Chunk c:chunks)for(Part p:c.getParts())if(observed.add(p.getBlockId()+"|"+p.getStartOffset()+"|"+p.getEndOffset())){
                com.consense.document.DocumentBlock b=new com.consense.document.DocumentBlock();b.setId(p.getBlockId());b.setLocation(p.getAnchor());
                b.setText(p.getText());b.setOriginalText(p.getText());b.setSource("source_reviewed_canonical_observation");
                if(p.getPageNo()!=null)b.setPageNo(Integer.valueOf(p.getPageNo().substring(1)));
                List<Double> box=p.getBbox();if(box!=null)b.setBbox(box.stream().mapToDouble(Double::doubleValue).toArray());blocks.add(b);
            }
            out.add(new VettingRuleEngine.Source(first.getDocumentId(),first.getFileKey(),first.getFileName(),first.getCanonicalEvidence().getJavaParsedSourceHash(),
                    blocks,blocks.stream().map(com.consense.document.DocumentBlock::getText).collect(Collectors.joining("\n"))));
        }return out;
    }
    /** Rebind a literal rule quote to its accepted part; ambiguous locations do not receive a pointer. */
    public void bindRuleEvidence(Session session,List<com.consense.web.dto.VettingDtos.FindingEvidence> quotes){
        current(session);for(com.consense.web.dto.VettingDtos.FindingEvidence e:quotes){
            List<Chunk> matches=session.chunks.stream().filter(c->c.getDocumentId().equals(e.getDocumentId())&&Objects.equals(c.getCanonicalEvidence().getJavaParsedSourceHash(),e.getSourceHash())&&c.getParts().stream()
                    .anyMatch(p->(Objects.equals(p.getAnchor(),e.getAnchor())||Objects.equals(c.getClauseId(),e.getAnchor()))
                            &&e.getQuote()!=null&&!e.getQuote().trim().isEmpty()&&VettingCorpus.normalize(p.getText()).contains(VettingCorpus.normalize(e.getQuote())))).collect(Collectors.toList());
            if(matches.size()==1){EvidencePointer p=matches.get(0).getCanonicalEvidence();e.setCanonicalEvidence(JsonUtils.read(JsonUtils.write(p),EvidencePointer.class));e.setSourceHash(p.getJavaParsedSourceHash());e.setOriginalSourceSha256(p.getOriginalSourceSha256());}
            else {e.setLocated(false);e.setCanonicalEvidence(null);}
        }
    }
    /** UTF16 union of accepted block ranges, never whole original-document coverage. */
    public static int observedChars(List<Chunk> selected){
        Map<String,List<int[]>> groups=new LinkedHashMap<>();for(Chunk c:selected){require(canonicalViewStatus(c)==null,"canonical coverage view differs");
            for(Part p:c.getParts())groups.computeIfAbsent(c.getDocumentId()+"|"+c.getSourceHash()+"|"+p.getBlockId(),key->new ArrayList<>()).add(new int[]{p.getStartOffset(),p.getEndOffset()});
        }long total=0;for(List<int[]> ranges:groups.values()){
            ranges.sort(Comparator.comparingInt(a->a[0]));int start=-1,end=-1;for(int[] r:ranges){if(start<0){start=r[0];end=r[1];}
                else if(r[0]<=end)end=Math.max(end,r[1]);else{total+=end-start;start=r[0];end=r[1];}}if(start>=0)total+=end-start;
        }require(total<=Integer.MAX_VALUE,"canonical coverage overflow");return (int)total;
    }
    /** The new context is a separate observed transport envelope, never a ranked seed. */
    private static Map<String,JsonNode> canonicalParents(Session session){Map<String,JsonNode> parents=new LinkedHashMap<>();session.byId.forEach((id,view)->parents.put(id,view.getCanonicalPayload()));return parents;}
    private static boolean completeTransport(JsonNode unit){return Arrays.asList("complete_observed_unit","complete_observed_hierarchy_context","complete_observed_attachment_context","complete_observed_context_union").contains(unit.path("status").asText());}
    private static JsonNode transportUnit(JsonNode unit,Map<String,JsonNode> parents){
        JsonNode attachment=unit.path("attachmentContext");
        JsonNode context=unit.path("hierarchyContext");
        // Preserve the v2 consumer behavior when no v3 attachment is declared.
        if(!attachment.isObject())return context.isObject()&&"complete_observed_hierarchy_context".equals(context.path("status").asText())?context:unit;
        if(context.isObject()&&!completeTransport(context))return context;
        if(!completeTransport(attachment))return attachment;
        if(!context.isObject())return attachment;
        Set<String> needed=new LinkedHashSet<>();Map<String,JsonNode> supplied=new LinkedHashMap<>();
        for(JsonNode envelope:Arrays.asList(context,attachment))for(JsonNode member:envelope.path("members")){
            String id=member.path("id").asText();require(parents.containsKey(id)&&parents.get(id).equals(member),"transport context member differs from source");
            require(!supplied.containsKey(id)||supplied.get(id).equals(member),"transport contexts disagree");supplied.put(id,member);needed.add(id);
        }
        // This envelope describes the union. Keep each original edge declaration
        // in its independently validated context on the unchanged source unit.
        String unionPolicy=OerCanonicalAttachmentContext.unionPolicy(attachment.path("policy").asText());
        ObjectNode union=JsonUtils.mapper().createObjectNode();union.put("policy",unionPolicy);union.put("status","complete_observed_context_union");union.putNull("reason");
        union.set("sourceIdentity",attachment.path("sourceIdentity"));union.set("seedObservedUnitId",unit.path("unitId"));
        union.set("hierarchyContextId",context.path("contextId"));union.set("attachmentContextId",attachment.path("contextId"));
        union.put("memberOrder","canonical_source_input");union.put("derivedMembersCanBecomeOrigins",false);union.putNull("memberScores");
        union.put("qualifiersComplete","unknown");union.put("semanticScopeVerified",false);union.put("legalClosureVerified",false);
        com.fasterxml.jackson.databind.node.ArrayNode ids=union.putArray("requiredMemberIds"),members=union.putArray("members"),hashes=union.putArray("memberPayloadSha256");
        parents.forEach((id,member)->{if(needed.contains(id)){ids.add(id);members.add(member.deepCopy());hashes.addObject().put("id",id).put("sha256",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(member))));}});
        require(!members.isEmpty(),"complete transport union has no source members");
        SortedSet<String> blocks=new TreeSet<>();for(JsonNode member:members)for(JsonNode part:member.path("parts"))blocks.add(part.path("blockId").asText());
        com.fasterxml.jackson.databind.node.ArrayNode blockIds=union.putArray("blockIds"),ranges=union.putArray("observedRanges");
        for(String block:blocks){blockIds.add(block);ranges.add(OerCanonicalSourceUnits.blockRange(parents,OerCanonicalSourceUnits.identity(members.get(0)),block));}
        ObjectNode address=JsonUtils.mapper().createObjectNode();address.put("policy",unionPolicy);address.set("observedUnitId",unit.path("unitId"));address.set("hierarchyContextId",context.path("contextId"));address.set("attachmentContextId",attachment.path("contextId"));address.set("memberPayloadSha256",hashes);
        union.put("contextId",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(address))));return union;
    }
    private static VectorStore.SearchHit businessHit(Session session,String id,JsonNode unit,double score,String kind){
            Chunk seed=session.byId.get(id);require(seed!=null,"source-unit seed outside session");List<Chunk> members=new ArrayList<>();
            JsonNode transport=transportUnit(unit,canonicalParents(session));
            if(completeTransport(transport))for(JsonNode raw:transport.path("members")){
                Chunk member=session.byId.get(raw.path("id").asText());require(member!=null&&raw.equals(member.getCanonicalPayload()),"context member differs from selected canonical payload");members.add(member);
            }
            else members.add(seed);
            members.sort(Comparator.comparingInt(c->session.chunks.indexOf(c)));
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("projectId",session.projectId);payload.put("documentId",seed.getDocumentId());
            payload.put("sourceHash",seed.getCanonicalEvidence().getJavaParsedSourceHash());payload.put("sourceRole",seed.getRole());
            SortedSet<Integer> pages=new TreeSet<>();for(Chunk member:members)pages.addAll(member.getCanonicalEvidence().getPhysicalPages());
            payload.put("fileLabel",seed.getFileKey()+" · "+seed.getFileName());payload.put("pageNo",pages.stream().map(p->"P"+p).collect(Collectors.joining(", ")));
            payload.put("anchor",seed.getAnchor());payload.put("canonicalPrimaryAnchorScope","ranked_seed_only");
            payload.put("content",members.stream().map(Chunk::getContent).collect(Collectors.joining("\n")));
            payload.put("canonicalEvidence",members.stream().map(c->JsonUtils.read(JsonUtils.write(c.getCanonicalEvidence()),EvidencePointer.class)).collect(Collectors.toList()));
            payload.put("canonicalMembers",members.stream().map(c->c.getCanonicalPayload().deepCopy()).collect(Collectors.toList()));
            payload.put("canonicalSourceUnit",unit.deepCopy());payload.put("applicabilityContextComplete",null);
            payload.put("selectionKind",kind);payload.put("scoreKind","context_only".equals(kind)?"unranked_context":"actual_reranked_seed");
            return new VectorStore.SearchHit(seed.getId(),score,payload);
    }

    private static Chunk view(JsonNode raw,EvidencePointer pointer,SourceDocument source) {
        boolean nativeDocx=NativeCanonicalContract.SCHEMA.equals(pointer.getProjectionSchema());
        Chunk c=new Chunk();c.setId(raw.path("id").asText());c.setContent(raw.path("content").asText());c.setDocumentId(raw.path("documentId").asText());
        c.setRole(raw.path("role").asText());c.setFileKey(source.getFileKey()==null?"OTHER":source.getFileKey());c.setFileName(pointer.getSourceFilename().asText());
        c.setSourceHash(raw.path("sourceHash").asText());c.setOriginalSourceSha256(raw.path("originalSourceSha256").asText());c.setSourceSha256(raw.path("sourceSha256").asText());
        c.setSourcePurpose(raw.path("sourcePurpose").asText());c.setSourceClass(raw.path("sourceClass").asText());
        c.setEligibleModules(JsonUtils.readList(JsonUtils.write(raw.path("eligibleModules")),String.class));
        c.setClauseId(raw.path("clauseId").isTextual()?raw.path("clauseId").asText():null);
        c.setClauseHeadingLocation(raw.path("clauseHeadingLocation").isTextual()?raw.path("clauseHeadingLocation").asText():null);
        c.setMetadataVersion(raw.path("metadataVersion").asText());c.setSegmentationVersion(raw.path("segmentationVersion").asText());
        c.setNativeTableMetadataVersion(raw.path("nativeTableMetadataVersion").asText());c.setSourceQualityMetadataVersion(raw.path("sourceQualityMetadataVersion").asText());
        c.setSourceQualityHash(raw.path("sourceQualityHash").asText());
        c.setSourceQuality(unknownQuality());
        List<Part> parts=new ArrayList<>();for(JsonNode p:raw.path("parts")) {
            Part part=new Part();part.setText(p.path("text").asText());part.setAnchor(p.path("anchor").asText());part.setBlockId(p.path("blockId").asText());
            part.setStartOffset(p.path("startOffset").asInt());part.setEndOffset(p.path("endOffset").asInt());
            part.setPageNo(nativeDocx?null:"P"+p.path("physicalPageNo").asInt());
            part.setBbox(nativeDocx?null:xywh(p.path("bbox")));part.setExtractionSource("source_reviewed_canonical");parts.add(part);
        }
        c.setParts(parts);c.setAnchor(raw.path("anchor").isTextual()?raw.path("anchor").asText():parts.get(0).getAnchor());
        List<Integer> pages=pointer.getPhysicalPages();c.setPageNo(nativeDocx?null:pages.size()==1?"P"+pages.get(0):"P"+pages.get(0)+"–P"+pages.get(pages.size()-1));
        c.setBbox(parts.size()==1?parts.get(0).getBbox():null);c.setCanonicalPayload(raw.deepCopy());c.setCanonicalEvidence(pointer);return c;
    }
    private static List<Double> xywh(JsonNode b){if(!b.isArray()||b.size()!=4)return null;return Arrays.asList(b.get(0).asDouble(),b.get(1).asDouble(),b.get(2).asDouble()-b.get(0).asDouble(),b.get(3).asDouble()-b.get(1).asDouble());}
    private static Chunk copy(Chunk c){require(c!=null,"ranked canonical chunk missing");return JsonUtils.read(JsonUtils.write(c),Chunk.class);}

    /** Checks an opaque canonical payload against the read-only model view; no legacy-version substitution. */
    static String canonicalViewStatus(Chunk c) {
        JsonNode raw=c.getCanonicalPayload();EvidencePointer p=c.getCanonicalEvidence();if(raw==null||p==null)return "canonical_view_missing";
        boolean nativeDocx=NativeCanonicalContract.SCHEMA.equals(p.getProjectionSchema());
        boolean family=NativePdfSourceFamilyContract.isFamily(raw)||NativePdfSourceFamilyContract.SCHEMA.equals(p.getProjectionSchema())||NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind());
        boolean nativePdf=!family&&(NativePdfCanonicalContract.isNativePdf(raw)||NativePdfCanonicalContract.SCHEMA.equals(p.getProjectionSchema())||NativePdfCanonicalContract.PRODUCER.equals(p.getProducerKind()));
        if(family){
            try{NativePdfSourceFamilyContract.validateChunk(raw,c.getOriginalSourceSha256());NativePdfSourceFamilyContract.validateOriginDisplay(raw,p.getNativePdfSourceFamilyEvidence());}catch(RuntimeException rejected){return "canonical_view_native_pdf_family_contract_differs";}
            JsonNode d=p.getNativePdfSourceFamilyEvidence();JsonNode physical=raw.path("physicalPages");String pageView=physical.size()==1?"P"+physical.get(0).asInt():"P"+physical.get(0).asInt()+"–P"+physical.get(physical.size()-1).asInt();
            if(!NativePdfSourceFamilyContract.SCHEMA.equals(p.getProjectionSchema())||!NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind())
                    ||!NativePdfSourceFamilyContract.STORAGE.equals(p.getStorageShape())||!NativePdfSourceFamilyContract.REVISION.equals(p.getSourceRevisionKind())
                    ||!NativePdfCanonicalContract.GEOMETRY.equals(p.getOriginalGeometryStatus())||!NativePdfSourceFamilyContract.VISUAL.equals(p.getVisualReturnStatus())
                    ||!NativePdfCanonicalContract.BBOX.equals(p.getBboxConvention())||!Objects.equals(raw.path("sourceFamilyOriginRef"),p.getSourceFamilyOriginRef())
                    ||!Objects.equals(p.getFamilyCompositionSha256(),d.path("compositionSha256").asText())||!Objects.equals(p.getFamilyComponentSetSha256(),d.path("componentSetSha256").asText())
                    ||!raw.path("sourceQuality").equals(d.path("sourceQuality"))||!NativePdfSourceFamilyContract.unknowns(d).equals(p.getUnknowns())||p.getNativePdfEvidence()!=null
                    ||!Objects.equals(pageView,c.getPageNo())||!Objects.equals(c.getBbox(),raw.path("parts").size()==1?xywh(raw.path("parts").get(0).path("bbox")):null))return "canonical_view_native_pdf_family_contract_differs";
        }else if(nativePdf){
            try{NativePdfCanonicalContract.validateChunk(raw,c.getOriginalSourceSha256());NativePdfCanonicalContract.validateDisplayDeclarations(p.getNativePdfEvidence());}catch(RuntimeException rejected){return "canonical_view_native_pdf_contract_differs";}
            JsonNode declarations=p.getNativePdfEvidence();
            JsonNode physical=raw.path("physicalPages");String pageView=physical.size()==1?"P"+physical.get(0).asInt():"P"+physical.get(0).asInt()+"–P"+physical.get(physical.size()-1).asInt();
            if(!NativePdfCanonicalContract.SCHEMA.equals(p.getProjectionSchema())||!NativePdfCanonicalContract.PRODUCER.equals(p.getProducerKind())
                    ||!NativePdfCanonicalContract.STORAGE.equals(p.getStorageShape())||!NativePdfCanonicalContract.GEOMETRY.equals(p.getOriginalGeometryStatus())
                    ||!NativePdfCanonicalContract.VISUAL.equals(p.getVisualReturnStatus())||!NativePdfCanonicalContract.BBOX.equals(p.getBboxConvention())
                    ||!Objects.equals(pageView,c.getPageNo())||!Objects.equals(c.getBbox(),raw.path("parts").size()==1?xywh(raw.path("parts").get(0).path("bbox")):null)
                    ||declarations==null||!declarations.isObject()||!declarations.path("semanticScopeVerified").isBoolean()||declarations.path("semanticScopeVerified").asBoolean()
                    ||!declarations.path("humanConfirmed").isBoolean()||declarations.path("humanConfirmed").asBoolean()
                    ||!"unknown".equals(declarations.path("wholeOriginalSourceCoverage").asText())
                    ||!NativePdfCanonicalContract.VISUAL.equals(declarations.path("visualReturnStatus").asText())
                    ||!declarations.path("limitations").isArray()||declarations.path("limitations").isEmpty())return "canonical_view_native_pdf_contract_differs";
        }else if(nativeDocx){
            try{NativeCanonicalContract.validateChunk(raw,c.getOriginalSourceSha256());}catch(RuntimeException rejected){return "canonical_view_native_contract_differs";}
            if(!NativeCanonicalContract.PRODUCER.equals(p.getProducerKind())||!NativeCanonicalContract.STORAGE.equals(p.getStorageShape())
                    ||!NativeCanonicalContract.GEOMETRY.equals(p.getOriginalGeometryStatus())||!NativeCanonicalContract.VISUAL.equals(p.getVisualReturnStatus())
                    ||p.getBboxConvention()!=null||c.getPageNo()!=null||c.getBbox()!=null)return "canonical_view_native_contract_differs";
        }else if(!"consense.canonical-projection.v1".equals(p.getProjectionSchema())||raw.has("storageShape"))return "canonical_view_identity_differs";
        if(!Objects.equals(c.getId(),raw.path("id").asText())||!Objects.equals(c.getId(),p.getCanonicalChunkId())
                ||!Objects.equals(c.getSourceHash(),raw.path("sourceHash").asText())||!Objects.equals(c.getSourceHash(),p.getCanonicalSourceHash())
                ||!Objects.equals(c.getDocumentId(),raw.path("documentId").asText())||!Objects.equals(c.getDocumentId(),String.valueOf(p.getDocumentId()))||!Objects.equals(c.getContent(),raw.path("content").asText())
                ||!Objects.equals(c.getRole(),raw.path("role").asText())||!Objects.equals(c.getSourceQualityHash(),raw.path("sourceQualityHash").asText())
                ||!Objects.equals(c.getMetadataVersion(),raw.path("metadataVersion").asText())||!Objects.equals(c.getSegmentationVersion(),raw.path("segmentationVersion").asText())
                ||!Objects.equals(c.getNativeTableMetadataVersion(),raw.path("nativeTableMetadataVersion").asText())||!Objects.equals(c.getSourceQualityMetadataVersion(),raw.path("sourceQualityMetadataVersion").asText())
                ||!Objects.equals(c.getSourceSha256(),raw.path("sourceSha256").asText())||!Objects.equals(c.getOriginalSourceSha256(),raw.path("originalSourceSha256").asText())
                ||!Objects.equals(c.getOriginalSourceSha256(),p.getOriginalSourceSha256())||!Objects.equals(p.getSourceRevisionSha256(),raw.path("sourceRevisionSha256").asText())
                ||!Objects.equals(p.getProjectionRevisionSha256(),raw.path("projectionRevisionSha256").asText())||!Objects.equals(p.getUnitId(),raw.path("unitId").asText())
                ||!Objects.equals(c.getSourcePurpose(),raw.path("sourcePurpose").asText())||!Objects.equals(c.getSourceClass(),raw.path("sourceClass").asText())
                ||!Objects.equals(c.getClauseId(),textOrNull(raw,"clauseId"))||!Objects.equals(c.getClauseHeadingLocation(),textOrNull(raw,"clauseHeadingLocation"))
                ||!Objects.equals(c.getSourceQuality(),unknownQuality())||!Objects.equals(c.getFileName(),p.getSourceFilename().asText())
                ||!"canonical_adoption_unit".equals(p.getAddressKind())||(!nativeDocx&&!nativePdf&&!family&&!"normalized_xyxy_original_page".equals(p.getBboxConvention()))
                ||!Objects.equals(raw.path("eligibleModules"),JsonUtils.mapper().valueToTree(c.getEligibleModules()))
                ||!Objects.equals(raw.path("physicalPages"),JsonUtils.mapper().valueToTree(p.getPhysicalPages()))
                ||!raw.path("eligibleModules").equals(p.getEligibleModules())||!raw.path("parts").equals(p.getParts())
                ||!raw.path("sourceSpans").equals(p.getSourceSpans())||!Objects.equals(raw.get("tableGroup"),p.getTableGroup())
                ||!raw.path("assetRefs").equals(p.getAssetRefs())||!raw.path("relations").equals(p.getRelations())
                ||!raw.path("adoption").equals(p.getAdoption())||!raw.path("sourceQuality").equals(p.getSourceQuality())
                ||!raw.path("sourceOrder").equals(p.getSourceOrder())
                ||p.isHumanConfirmed()||!"unknown".equals(raw.path("sourceQuality").path("semanticQuality").asText()))return "canonical_view_identity_differs";
        if(c.getParts()==null||c.getParts().size()!=raw.path("parts").size())return "canonical_view_parts_differ";
        for(int i=0;i<c.getParts().size();i++){Part part=c.getParts().get(i);JsonNode original=raw.path("parts").get(i);
            if(!Objects.equals(part.getText(),original.path("text").asText())||!Objects.equals(part.getAnchor(),original.path("anchor").asText())
                    ||!Objects.equals(part.getBlockId(),original.path("blockId").asText())||part.getStartOffset()!=original.path("startOffset").asInt()
                    ||part.getEndOffset()!=original.path("endOffset").asInt()||part.getEndOffset()-part.getStartOffset()!=part.getText().length()
                    ||!Objects.equals(part.getBbox(),nativeDocx?null:xywh(original.path("bbox")))||!Objects.equals(part.getPageNo(),nativeDocx?null:"P"+original.path("physicalPageNo").asInt())
                    ||!"source_reviewed_canonical".equals(part.getExtractionSource()))return "canonical_view_parts_differ";
        }return null;
    }
    private static String textOrNull(JsonNode raw,String field){return raw.path(field).isTextual()?raw.path(field).asText():null;}
    private static VettingSourceQuality.Info unknownQuality(){VettingSourceQuality.Info info=new VettingSourceQuality.Info();
        info.getLimitations().add("canonical_adoption_does_not_establish_whole_source_semantics_or_coverage");return info;}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException("Canonical business evidence rejected: "+why);}
}
