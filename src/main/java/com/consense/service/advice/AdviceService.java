package com.consense.service.advice;

import com.consense.ai.AiGateway;
import com.consense.ai.ModelIdentity;
import com.consense.ai.LlmOperation;
import com.consense.ai.LlmClient;
import com.consense.ai.IncompleteModelResponseException;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.config.ConsenseProperties;
import com.consense.domain.ChatMessage;
import com.consense.domain.AdviceSession;
import com.consense.domain.EvidenceChunk;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.repository.ChatMessageRepository;
import com.consense.repository.AdviceSessionRepository;
import com.consense.repository.EvidenceChunkRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.sources.ProjectDocumentScope;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.OerBusinessEvidence;
import com.consense.service.vetting.OerCanonicalRetrievalClient;
import com.consense.service.vetting.FixedBaselineRetrievalClient;
import com.consense.service.vetting.FixedReferenceEvidence;
import com.consense.service.vetting.VettingInputBudget;
import com.consense.service.vetting.VettingTokenBudgetCliObserver;
import com.consense.service.prompt.PromptCatalog;
import com.consense.service.prompt.PromptService;
import com.consense.vector.VectorStore;
import com.consense.web.dto.AdviceDtos.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 咨询模块：证据索引 + 检索增强问答。
 *
 * 对应设计稿的 guardrail：回答必须带条款或摘录依据，
 * 检索不到依据时返回 "I don't know"，不做任何补全与推断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdviceService {

    private final ProjectService projectService;
    private final AiGateway ai;
    private final VectorStore vectorStore;
    private final OcrClient ocrClient;
    private final ConsenseProperties props;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final EvidenceChunkRepository chunkRepository;
    private final ChatMessageRepository chatMessageRepository;
    /** 提示词从数据库取（支持前端在线编辑），DB 无值时回退出厂默认 */
    private final PromptService promptService;
    private final OerBusinessEvidence canonical;
    private final PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AdviceResponsesTransport adviceResponses;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private FixedBaselineRetrievalClient fixedBaseline;
    @org.springframework.beans.factory.annotation.Autowired
    private AdviceSessionRepository adviceSessions;
    @org.springframework.beans.factory.annotation.Autowired
    private AdviceModelProperties adviceSettings;
    @org.springframework.beans.factory.annotation.Autowired
    private VettingTokenBudgetCliObserver tokenObserver;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private StorageService storageService;

    /** Character admission guard for whole fixed groups; never a text truncation limit. */
    private static final int FIXED_REFERENCE_MATERIAL_CHARACTERS=200000;

    // ------------------------------------------------------------ 索引

    public IndexStatusVO rebuildIndex(String projectId) {
        return rebuildIndex(projectId,"fullset");
    }

    public IndexStatusVO rebuildIndex(String projectId,String scope) {
        projectService.require(projectId);
        String normalized=ProjectDocumentScope.normalizeScope(scope);
        if(canonical.enabled()) {
            OerBusinessEvidence.Session session=canonicalSession(projectId,normalized);
            try{canonical.index(session);}catch(RuntimeException rejected){throw new BizException(4201,"Canonical advice indexing failed. Verify the current source bindings and advisory retrieval service; no legacy index was used.");}
            // canonicalStatus checks the fresh selected scope before and after its remote status read.
            return canonicalStatus(projectId,normalized,session);
        }
        List<SourceDocument> documents = selectedDocuments(projectId,"fullset").stream()
                .filter(ProjectDocumentScope::parseUsable)
                .collect(Collectors.toList());
        if (documents.isEmpty()) {
            throw new BizException(4200, "No parsed documents in the current review package. Upload the tender/contract package in Vetting first.");
        }
        if (!vectorStore.available()) {
            throw new BizException(4201, "向量库不可用（" + props.getVector().getBaseUrl()
                    + "）。请启动 Qdrant，或把 consense.vector.provider 改为 memory 先跑通流程。");
        }

        if(!ai.embeddingAvailable())throw new BizException(4201,"Deployment embedding model "+ai.embedModel()+" is unavailable. Restore that model before refreshing the advice evidence index.");
        vectorStore.deleteByProject(projectId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status->chunkRepository.deleteByProjectId(projectId));

        int total = 0;
        int index = 0;

        for (SourceDocument document : documents) {
            List<VettingCorpus.Chunk> nativeChunks=VettingCorpus.chunks(Collections.singletonList(document));
            List<String> pieces = nativeChunks.stream().map(VettingCorpus.Chunk::getContent).collect(Collectors.toList());
            if (pieces.isEmpty()) {
                continue;
            }
            List<float[]> vectors = ai.embed(pieces);
            if(vectors.size()!=pieces.size()||vectors.stream().anyMatch(v->v==null||v.length==0))throw new BizException(4201,"Embedding adapter did not index every permitted source passage.");
            String documentHash=VettingCorpus.sourceHash(document),documentRole=VettingCorpus.sourceRole(document);
            List<EvidenceChunk> chunks = new ArrayList<>();
            List<VectorStore.VectorPoint> points = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) {
                EvidenceChunk chunk = new EvidenceChunk();
                chunk.setProjectId(projectId);
                chunk.setDocumentId(document.getId());
                chunk.setSourceHash(documentHash);
                chunk.setSourceRole(documentRole);
                chunk.setChunkIndex(index++);
                chunk.setFileLabel(nvl(document.getFileKey()) + " · " + nvl(document.getFileName()));
                chunk.setPageNo(nativeChunks.get(i).getPageNo());
                String anchor=nativeChunks.get(i).getAnchor();
                chunk.setAnchor(anchor==null?null:anchor.substring(0,Math.min(anchor.length(),255)));
                chunk.setContent(pieces.get(i));
                chunk.setCreatedAt(Instant.now());
                chunkRepository.save(chunk);

                String pointId = document.getId() + "-" + i;
                chunk.setPointId(pointId);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("projectId", projectId);
                payload.put("documentId", String.valueOf(document.getId()));
                payload.put("sourceHash",chunk.getSourceHash());
                payload.put("sourceRole",chunk.getSourceRole());
                payload.put("fileLabel", chunk.getFileLabel());
                payload.put("pageNo", chunk.getPageNo());
                payload.put("anchor", nvl(chunk.getAnchor()));
                payload.put("content", pieces.get(i));
                points.add(new VectorStore.VectorPoint(pointId,
                        vectors.get(i), payload));
                chunks.add(chunk);
            }
            chunkRepository.saveAll(chunks);
            if (!points.isEmpty()) {
                vectorStore.upsert(points);
            }
            total += pieces.size();
        }
        log.info("项目 {} 证据索引重建完成，共 {} 个切片", projectId, total);
        return status(projectId);
    }

    public IndexStatusVO status(String projectId) {
        return status(projectId,"fullset");
    }

    public IndexStatusVO status(String projectId,String scope) {
        projectService.require(projectId);
        String normalized=ProjectDocumentScope.normalizeScope(scope);
        if(canonical.enabled()) {
            try{return canonicalStatus(projectId,normalized);}
            catch(RuntimeException unavailable){
                IndexStatusVO result=canonicalUnavailable(normalized);
                result.setEligibleDocuments(freshSelectedDocuments(projectId,normalized).size());
                return result;
            }
        }
        List<EvidenceChunk> records=chunkRepository.findByProjectIdOrderByChunkIndexAsc(projectId);
        int chunks = records.size();
        List<SourceDocument> documents=selectedDocuments(projectId,"fullset").stream().filter(ProjectDocumentScope::parseUsable).collect(Collectors.toList());
        boolean stale=chunks>0&&!freshIndex(records,documents);
        boolean vectorReady = vectorStore.available();
        int indexed = vectorReady ? vectorStore.count(projectId) : 0;
        boolean llmReady = ai.available();
        boolean embeddingReady=ai.embeddingAvailable();
        boolean ocrReady = props.getOcr().isEnabled() && ocrClient.available();

        LocalizedText message;
        if(!embeddingReady) {
            message=LocalizedText.of("部署的向量模型 "+ai.embedModel()+" 不可用。请恢复该模型后再刷新咨询依据索引。","部署的向量模型 "+ai.embedModel()+" 不可用。請恢復該模型後再刷新諮詢依據索引。","Deployment embedding model "+ai.embedModel()+" is unavailable. Restore that model before refreshing the advice evidence index.");
        } else if (chunks == 0) {
            message = LocalizedText.of("尚未建立证据索引，请先点击「刷新证据索引」。",
                    "尚未建立證據索引，請先點擊「刷新證據索引」。",
                    "No evidence index yet — click 'Refresh evidence index' to build one.");
        } else if(stale) {
            message=LocalizedText.of("审查文件包或资料版本已变化；请刷新咨询依据索引，旧索引不可用于新的回答。","審查文件包或資料版本已變化；請刷新諮詢依據索引，舊索引不可用於新的回答。","The review package or source revision changed. Refresh the advice evidence index before asking.");
        } else if (!vectorReady) {
            message = LocalizedText.of("向量库不可达，检索将无法工作。",
                    "向量庫不可達，檢索將無法工作。",
                    "Vector store unreachable; retrieval is unavailable.");
        } else {
            message = LocalizedText.of("索引就绪，共 " + chunks + " 个切片。",
                    "索引就緒，共 " + chunks + " 個切片。",
                    chunks + " chunks indexed and ready.");
        }
        return new IndexStatusVO(chunks, indexed,
                props.getVector().getProvider(), props.getVector().getCollection(),
                ai.embedModel(), ai.chatModel(), llmReady, vectorReady, ocrReady, message,stale,documents.size(),(int)records.stream().map(EvidenceChunk::getDocumentId).distinct().count(),embeddingReady);
    }

    // ------------------------------------------------------------ 问答

    public AskResponseVO ask(String projectId, AskRequest request) {
        Project project = projectService.require(projectId);
        String question = request == null ? "" : nvl(request.getQuestion()).trim();
        if (question.isEmpty()) {
            throw new BizException(4202, "请先输入问题");
        }
        LlmOperation selectedOperation=ai.capture();
        final AdviceResponsesTransport.Operation responsesOperation=adviceResponses!=null&&adviceResponses.selected(selectedOperation)
                ?adviceResponses.capture(selectedOperation):null;
        String scope = ProjectDocumentScope.normalizeScope(request.getScope());
        String requestedBasis=requestedBasisScope(request);
        request=new AskRequest(question,scope,request.getReplyToMessageId(),requestedBasis,request.getSessionId());
        final AdviceSession conversation=request.getSessionId()==null?null:requireConversation(projectId,request.getSessionId());
        if(conversation!=null)request.setSessionId(conversation.getId());
        if(conversation!=null&&(!scope.equals(conversation.getScope())||!requestedBasis.equals(conversation.getBasisScope())))
            throw new BizException(4202,"Advice scope and basisScope must match the selected session. Create a separate session to change scope.");
        boolean fixedReferenceOnly="reference_only".equals(requestedBasis);
        if(fixedReferenceOnly&&!fixedBaselineEnabled())throw new BizException(4204,"Fixed standard-reference retrieval is not enabled. Configure a reviewed development baseline before asking an explicit reference-only question.");
        QuestionContext questionContext=clarificationContext(projectId,scope,request.getReplyToMessageId(),question,requestedBasis,request.getSessionId());

        // Configuration errors are model-action errors even when no index has been built.
        if (responsesOperation==null&&ai.explicitProfileSelected() && ai.capture().getUnavailableReason() != null) {
            ai.requireAvailable();
        }

        String retrievalQuestion=questionContext.originalQuestion+(questionContext.background.isEmpty()?"":"\nUser supplied clarification: "+String.join("\n",questionContext.background));
        OerBusinessEvidence.Session session=!fixedReferenceOnly&&canonical.enabled()?canonicalSession(projectId,scope):null;
        final String sourceSnapshot=conversationSourceIdentity(projectId,scope,fixedReferenceOnly,session);
        List<VectorStore.SearchHit> hits;
        if(fixedReferenceOnly) {
            hits=fixedReferenceHits(retrievalQuestion);
        } else if(session!=null) {
            try {
                canonical.ensureIndex(session);
                hits=new ArrayList<>(canonical.adviceHits(session,retrievalQuestion));
            }catch(RuntimeException rejected){throw new BizException(4204,"Canonical advice retrieval failed. Refresh the selected scope and verify current source bindings and advisory retrieval; no legacy evidence was used.");}
            assertCurrentSelection(session,scope);
            if(fixedBaselineEnabled())hits.addAll(fixedReferenceHits(retrievalQuestion));
            hits=withCanonicalIncorporationContext(session,hits);
        } else hits=withIncorporationContext(projectId,scope,retrieve(projectId,scope,retrievalQuestion));
        final List<VectorStore.SearchHit> selectedFixedHits=hits.stream().filter(this::fixedBaselineHit).collect(Collectors.toList());
        final String fixedSourceIdentity=selectedFixedHits.isEmpty()?null:fixedSourceIdentity(selectedFixedHits);
        final ModelIdentity capturedIdentity=responsesOperation!=null?responsesOperation.getIdentity():selectedOperation==null?null:selectedOperation.getIdentity();
        final Runnable currentGuard=()->{
            if(session!=null)assertCurrentSelection(session,scope);
            assertFixedReferencesCurrent(selectedFixedHits,fixedSourceIdentity);
            if(sourceSnapshot!=null)assertConversationSources(projectId,scope,fixedReferenceOnly,session,sourceSnapshot);
            if(conversation!=null)assertConversationCurrent(conversation);
            if(responsesOperation==null)assertCapturedChatIdentity(capturedIdentity);
        };
        currentGuard.run();
        if(conversation!=null&&request.getReplyToMessageId()!=null&&!chatMessageRepository.findById(request.getReplyToMessageId())
                .filter(m->Objects.equals(sourceSnapshot,m.getSourceSnapshotSha256())).isPresent())
            throw new BizException(4204,"The clarification was issued against a different source version. Start a new question against the current evidence.");
        assertFixedReferencesCurrent(selectedFixedHits,fixedSourceIdentity);
        List<CitationVO> sources = new ArrayList<>();
        for(int i=0;i<hits.size();i++){CitationVO source=toCitation(hits.get(i));source.setEvidenceId("["+(i+1)+"]");sources.add(source);}

        if (hits.isEmpty()) {
            AskResponseVO response = unknown(projectId, scope, sources, Collections.<String>emptyList(), false);
            persistExchange(projectId,scope,question,request,questionContext,response,session,selectedFixedHits,fixedSourceIdentity,conversation,sourceSnapshot);
            return response;
        }

        if (responsesOperation==null&&ai.explicitProfileSelected()) {
            ai.requireAvailable();
        }

        String excerpts = renderExcerpts(hits);
        String userPrompt = PromptService.format(promptService.userTemplate(PromptCatalog.KEY_ADVICE),
                fixedReferenceOnly?"Fixed standard reference only / 固定标准参考解释":scopeLabel(scope), excerpts, questionContext.originalQuestion);
        if(fixedReferenceOnly)userPrompt+="\n\nExplicit answer scope: reference_only. Explain only the cited standard text in this frozen development baseline. Do not assert that this standard, edition or requirement is adopted by the project, and do not return project_contract. Parsing, legal ownership and completeness limitations remain unverified.";
        if(!questionContext.background.isEmpty())userPrompt+="\n\nuser-supplied-background (clarification replies, not contractual evidence):\n"+String.join("\n",questionContext.background)
                +"\nAnswer the original question in light of these user-stated assumptions. Do not cite this background as a contract document or established contractual fact.";
        String systemPrompt=promptService.system(PromptCatalog.KEY_ADVICE)+"\n\nOnly when a flowchart is requested, use a fenced ```mermaid block with flowchart TD or flowchart LR, simple alphanumeric node IDs, and short double-quoted labels for every node and decision; encode internal quotes as #quot;. Preserve only evidence-supported steps and citations; no HTML, links, directives or styling.";
        if(responsesOperation!=null&&responsesOperation.getConfiguredContextTokens()<262144)
            throw new BizException(4205,"Advice budget_unknown: generation requires at least 256K configured tokens.");
        if(conversation!=null)userPrompt+=boundedHistory(conversation,sourceSnapshot,capturedIdentity,selectedOperation,responsesOperation,currentGuard);

        AnswerRecord answer;
        try {
            if(responsesOperation!=null){
                if(session==null&&fixedSourceIdentity==null)throw new AdviceResponsesTransport.RequestFailure("budget_unknown","current_source_identity_required",null);
                String requestSourceIdentity=fixedSourceIdentity==null?session.identitySha256():VettingCorpus.hash((session==null?"explicit-reference-only":session.identitySha256())+"|fixed-baseline|"+fixedSourceIdentity);
                answer=responsesOperation.completeJson(systemPrompt,userPrompt,requestSourceIdentity,AnswerRecord.class,currentGuard).value;
            }else answer=completeSessionChat(systemPrompt,userPrompt,selectedOperation,sourceSnapshot,currentGuard);
        } catch (AdviceResponsesTransport.RequestFailure bounded) {
            throw bounded;
        } catch (IncompleteModelResponseException incomplete) {
            throw new BizException(4205,"Advice output_incomplete: "+incomplete.getFailureKind().name().toLowerCase(java.util.Locale.ROOT)+". No answer was published; retry the question.");
        } catch (Exception e) {
            // Responses checks current sources around counting and generation inside this call.
            // Preserve that safe source failure; it is not a provider/model failure.
            if(e instanceof BizException&&Arrays.asList(4202,4204,4205).contains(((BizException)e).getCode()))throw (BizException)e;
            // External exception text may contain credentials; retain only the safe failure type/profile.
            log.warn("Advice model call failed ({}).", e.getClass().getSimpleName());
            ModelIdentity selected = ai.modelIdentity();
            throw new BizException(4203, "Selected LLM profile "
                    + (selected == null ? "legacy deployment" : selected.getProfileId())
                    + " failed; the operation was not rerouted.");
        }
        if(session!=null)assertCurrentSelection(session,scope);
        assertFixedReferencesCurrent(selectedFixedHits,fixedSourceIdentity);

        List<String> clarifications=answer==null?Collections.emptyList():cleanClarifications(answer.getClarifications());
        List<String> citations=validatedCitations(answer==null?null:answer.getCitations(),sources);
        String basisScope=fixedReferenceOnly?"reference_only":answer!=null&&"reference_only".equals(nvl(answer.getBasisScope()).trim().toLowerCase(java.util.Locale.ROOT))?"reference_only":"project_contract";
        if(fixedReferenceOnly&&answer!=null&&!nvl(answer.getBasisScope()).trim().isEmpty()&&!"reference_only".equals(nvl(answer.getBasisScope()).trim().toLowerCase(java.util.Locale.ROOT))){clarifications=new ArrayList<>(clarifications);clarifications.add("The requested standard explanation does not establish project adoption, applicable edition or project amendments.");}
        boolean basisSupported=incorporationSupported(basisScope,citations,sources);
        if(!basisSupported){clarifications=new ArrayList<>(clarifications);clarifications.add("Please provide or identify the adopted contractual provisions, including standard incorporation, applicable editions and any project amendments where relevant. Project correspondence and a document list alone do not establish a contractual requirement.");}
        if (answer == null || !answer.isGrounded() || !clarifications.isEmpty() || JsonUtils.isBlankText(answer.getAnswer())||citations.isEmpty()||!basisSupported) {
            List<String> extra = answer != null && answer.getMissingReason() != null
                    ? Arrays.asList(answer.getMissingReason())
                    : Collections.<String>emptyList();
            AskResponseVO response = unknown(projectId, scope, sources, extra, true);
            response.setClarifications(clarifications);
            response.setBasisScope("insufficient");
            if(capturedIdentity!=null){response.setModel(capturedIdentity.getModel());response.setModelIdentity(capturedIdentity);}
            persistExchange(projectId,scope,question,request,questionContext,response,session,selectedFixedHits,fixedSourceIdentity,conversation,sourceSnapshot);
            return response;
        }

        AskResponseVO response = new AskResponseVO(true,
                LocalizedText.of("带依据的回答", "帶依據的回答", "Answer with basis"),
                LocalizedText.same(answer.getAnswer()),
                null,
                citations,
                evidenceIdOf(sources),
                sources,
                capturedIdentity==null?ai.chatModel():capturedIdentity.getModel(),
                capturedIdentity==null?ai.modelIdentity():capturedIdentity);
        response.setClarifications(clarifications);response.setSourceScope(scope);
        response.setBasisScope(basisScope);
        persistExchange(projectId,scope,question,request,questionContext,response,session,selectedFixedHits,fixedSourceIdentity,conversation,sourceSnapshot);
        log.info("项目 {} 问答完成，命中 {} 条证据", projectId, hits.size());
        return response;
    }

    /**
     * 混合检索：
     *  1) 证据量小（总字符 ≤ stuffingLimit）→ 全量喂入（stuffing），跳过向量检索，
     *     检索环节零误差，所有切片连同引用信息直接进 prompt；
     *  2) 证据量大 → 向量检索，topK 提到 retrieveTopKFloor（默认 20）扩大召回，
     *     弱化"检索没捞到导致模型看不到"的问题。
     */
    private List<VectorStore.SearchHit> retrieve(String projectId, String scope, String question) {
        List<EvidenceChunk> chunks = chunkRepository.findByProjectIdOrderByChunkIndexAsc(projectId);
        if (chunks.isEmpty()) {
            throw new BizException(4204, "该项目尚未建立证据索引，请先在咨询页点击「刷新证据索引」");
        }
        List<SourceDocument> current=selectedDocuments(projectId,"fullset").stream().filter(ProjectDocumentScope::parseUsable).collect(Collectors.toList());
        if(!freshIndex(chunks,current))
            throw new BizException(4204,"The review package or source revision changed. Refresh the advice evidence index before asking.");
        Set<String> allowedIds=selectedDocuments(projectId,scope).stream().filter(ProjectDocumentScope::parseUsable).map(d->String.valueOf(d.getId())).collect(Collectors.toSet());
        chunks=chunks.stream().filter(c->allowedIds.contains(String.valueOf(c.getDocumentId()))).collect(Collectors.toList());
        if(chunks.isEmpty())return Collections.emptyList();

        int totalChars = 0;
        for (EvidenceChunk chunk : chunks) {
            totalChars += nvl(chunk.getContent()).length();
        }
        if (totalChars <= props.getVector().getStuffingLimit()) {
            log.info("项目 {} 证据总量 {} 字符（≤ {}），全量喂入（stuffing），共 {} 个切片",
                    projectId, totalChars, props.getVector().getStuffingLimit(), chunks.size());
            return chunks.stream().map(this::chunkToHit).collect(Collectors.toList());
        }

        // 大证据量：走向量检索，扩召回
        if (!ai.embeddingAvailable()) {
            if (ai.explicitProfileSelected()) {
                throw new BizException(4203,
                        "The deployment embedding adapter is unavailable; the selected chat profile was not rerouted.");
            }
            return Collections.emptyList();
        }
        if (!vectorStore.available()) {
            return Collections.emptyList();
        }
        List<float[]> vectors = ai.embed(Collections.singletonList(question));
        if (vectors.isEmpty() || vectors.get(0).length == 0) {
            return Collections.emptyList();
        }
        int topK = Math.max(props.getVector().getTopK(), props.getVector().getRetrieveTopKFloor());
        List<VectorStore.SearchHit> hits=vectorStore.search(projectId,allowedIds,vectors.get(0),topK,props.getVector().getScoreThreshold());
        // Validate external payloads against the current persisted chunk; never trust provider text or scope labels.
        Map<String,EvidenceChunk> byPoint=new LinkedHashMap<>();for(EvidenceChunk c:chunks){byPoint.put(nvl(c.getPointId()),c);byPoint.put(java.util.UUID.nameUUIDFromBytes(nvl(c.getPointId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),c);}
        List<VectorStore.SearchHit> verified=new ArrayList<>();
        for(VectorStore.SearchHit hit:hits){Map<String,Object> payload=hit.getPayload();if(payload==null)continue;
            if(!projectId.equals(String.valueOf(payload.get("projectId"))))continue;
            String documentId=String.valueOf(payload.get("documentId"));if(!allowedIds.contains(documentId))continue;
            EvidenceChunk stored=byPoint.get(hit.getId());
            if(stored==null||!documentId.equals(String.valueOf(stored.getDocumentId()))||!Objects.equals(stored.getSourceHash(),String.valueOf(payload.get("sourceHash"))))continue;
            VectorStore.SearchHit canonical=chunkToHit(stored);verified.add(new VectorStore.SearchHit(hit.getId(),hit.getScore(),canonical.getPayload()));
        }return verified;
    }

    /** EvidenceChunk → SearchHit（stuffing 模式构造全量命中，score 置 1.0） */
    private VectorStore.SearchHit chunkToHit(EvidenceChunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", chunk.getProjectId());
        payload.put("documentId", String.valueOf(chunk.getDocumentId()));
        payload.put("sourceHash",chunk.getSourceHash());payload.put("sourceRole",chunk.getSourceRole());
        payload.put("fileLabel", nvl(chunk.getFileLabel()));
        payload.put("pageNo", chunk.getPageNo());
        payload.put("anchor", nvl(chunk.getAnchor()));
        payload.put("content", nvl(chunk.getContent()));
        return new VectorStore.SearchHit(nvl(chunk.getPointId()), 1.0, payload);
    }

    private AskResponseVO unknown(String projectId, String scope, List<CitationVO> sources,
                                  List<String> extraReasons, boolean chatDispatched) {
        List<String> citations = new ArrayList<>();
        if (sources.isEmpty()) {
            citations.add("Searched selected scope · no basis");
        } else {
            sources.stream().map(CitationVO::getFileLabel).distinct().limit(3).forEach(citations::add);
        }
        citations.addAll(extraReasons);
        AskResponseVO response=new AskResponseVO(false,
                LocalizedText.of("I don't know", "I don't know", "I don't know"),
                LocalizedText.empty(),
                scope,
                citations,
                evidenceIdOf(sources),
                sources,
                chatDispatched ? ai.chatModel() : null, chatDispatched ? ai.modelIdentity() : null);
        response.setSourceScope(scope);response.setClarifications(Collections.emptyList());response.setBasisScope("insufficient");return response;
    }

    /**
     * 与设计稿 unknownAnswer(scope) 完全一致的兜底文案，由前端按当前语言取值。
     */
    public static LocalizedText unknownAnswerText(String scopeKey, String scopeEn) {
        return LocalizedText.of(
                "在选定范围（" + scopeKey + "）内未找到充分条款依据。系统不会超出合约包进行推断。",
                "在選定範圍（" + scopeKey + "）內未找到充分條款依據。系統不會超出合約包進行推斷。",
                "No sufficient provision was found in the selected scope (" + scopeEn
                        + "). The system will not infer beyond the contract package.");
    }

    private String scopeLabel(String scope) {
        if ("tender".equals(nvl(scope))) {
            return "Tender documents only / 仅招标文件";
        }
        if ("contract".equals(nvl(scope))) {
            return "Conditions of Contract only / 仅合约条件";
        }
        return "Selected package conditions / 选定合约条件";
    }

    private String renderExcerpts(List<VectorStore.SearchHit> hits) {
        StringBuilder builder = new StringBuilder();
        List<VectorStore.SearchHit> fixedHits=hits.stream().filter(this::fixedBaselineHit).collect(Collectors.toList());
        String fixedMaterial=fixedHits.isEmpty()?null:fixedModelMaterial(fixedHits);
        builder.append("Selected source excerpts for this question. These excerpts do not establish that the entire standard/library or project package is complete. ")
                .append("A passage omitted from these excerpts does not prove that its document or clause is absent from the supplied files. ")
                .append("Project wording on adopting or amending standards, if included below, states only what those passages say about incorporation; ")
                .append("it does not guarantee that the underlying standard text or every amendment has been supplied or reviewed.\n\n");
        int index = 1;
        com.consense.service.vetting.OerModelMaterial.Request modelMaterial=new com.consense.service.vetting.OerModelMaterial.Request();
        if(hits.stream().anyMatch(hit->canonicalUnit(hit)!=null))builder.append(com.consense.service.vetting.OerModelMaterial.LEGEND);
        List<JsonNode> modelStructures=new ArrayList<>();
        List<Map<String,Object>> hitMembership=new ArrayList<>();
        Set<List<String>> nativeLimits=new LinkedHashSet<>();
        for (VectorStore.SearchHit hit : hits) {
            builder.append("[").append(index++).append("] ")
                    .append(hit.fileLabel())
                    .append(" | documentId=").append(hit.getPayload().get("documentId"))
                    .append(" | role=").append(hit.getPayload().get("sourceRole"))
                    .append(" | sourceRevision=").append(hit.getPayload().get("sourceHash"))
                    .append(" | physicalPage=").append(hit.pageNo().isEmpty()?"not available":hit.pageNo())
                    .append(" | location=").append(hit.anchor())
                    .append("\n");
            JsonNode unit=canonicalUnit(hit);
            List<com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer> pointers=unit==null?Collections.emptyList():canonicalPointers(hit);
            if(unit!=null) {
                builder.append("Observed source unit: ").append(unit.path("status").asText("unknown"))
                        .append("; qualifiersComplete=").append(unit.path("qualifiersComplete").asText("unknown"))
                        .append("; semanticScopeVerified=false; this is observed structure, not whole-source or applicability acceptance.\n");
                if(unit.path("reason").isTextual())builder.append("Unresolved structure: ").append(unit.path("reason").asText()).append("\n");
                JsonNode hierarchy=unit.path("hierarchyContext");
                if(hierarchy.isObject()){
                    builder.append("Declared same-source hierarchy context: ").append(hierarchy.path("status").asText("unknown"))
                            .append("; contextId=").append(hierarchy.path("contextId").asText())
                            .append("; qualifiersComplete=unknown; legalClosureVerified=false; descendants are unranked source context, not independent relevance hits.\n");
                    if(hierarchy.path("reason").isTextual())builder.append("Unresolved hierarchy: ").append(hierarchy.path("reason").asText()).append("\n");
                }
                builder.append("Evidence selection: ").append(String.valueOf(hit.getPayload().get("scoreKind"))).append("; a relevance score is not contractual validity.\n");
                JsonNode attachment=unit.path("attachmentContext");if(attachment.isObject()){
                    builder.append("Declared direct margin/sidebar context: ").append(attachment.path("status").asText("unknown"))
                            .append("; contextId=").append(attachment.path("contextId").asText())
                            .append("; qualifiersComplete=unknown; legalClosureVerified=false; attachment members are unranked source context.\n");
                    if(attachment.path("reason").isTextual())builder.append("Unresolved attachment: ").append(attachment.path("reason").asText()).append("\n");
                }
                List<String> materialRefs=new ArrayList<>();int memberOrdinal=0;
                JsonNode canonicalMembers=JsonUtils.mapper().valueToTree(hit.getPayload().get("canonicalMembers"));
                for(com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer:pointers) {
                    JsonNode projected=modelMaterial.project(pointer,canonicalMembers.isArray()&&memberOrdinal<canonicalMembers.size()?canonicalMembers.get(memberOrdinal):null,String.valueOf(hit.getPayload().get("sourceRole")));
                    memberOrdinal++;
                    if(projected.has("modelUnitAddress")){modelStructures.add(projected);materialRefs.add(projected.path("modelUnitAddress").asText());}
                    else materialRefs.add(projected.path("modelUnitRef").asText());
                    boolean firstLimits=nativeLimits.add(Arrays.asList(pointer.getProducerKind(),pointer.getVisualReturnStatus()));
                    if(firstLimits&&"native_docx".equals(pointer.getProducerKind()))builder.append("Native DOCX observations: original physical pages and PDF boxes are not defined; visualReturnStatus=")
                            .append(pointer.getVisualReturnStatus()).append("; editing state and applicability remain unknown.\n");
                    if(firstLimits&&"native_pdf".equals(pointer.getProducerKind())){
                        builder.append("Native PDF source-reviewed regions: geometry and raster mapping remain declared; effective visibility and whole-source semantics remain unknown; visualReturnStatus=")
                                .append(pointer.getVisualReturnStatus()).append("\n");
                    }
                }
                Map<String,Object> membership=new LinkedHashMap<>();membership.put("hitOrdinal",index-1);membership.put("rankedSeedId",hit.getId());membership.put("sourceUnitId",unit.path("unitId").asText());
                membership.put("hierarchyContextId",unit.path("hierarchyContext").path("contextId").asText(null));membership.put("attachmentContextId",unit.path("attachmentContext").path("contextId").asText(null));
                membership.put("memberMaterialRefs",materialRefs);hitMembership.add(membership);
            }
            if(fixedBaselineHit(hit)) {
                builder.append("Fixed development standard reference: id=").append(hit.getId())
                        .append("; read this parent's complete entry in selectedFixedStandardMaterial below. role=standard; projectApplicability=unconfirmed; qualifiersComplete=unknown; sourceQualityAccepted=false. No project canonical pointer is claimed.\n\n");
            } else if(unit!=null&&canonicalBodyMatches(hit.text(),pointers)) {
                builder.append("Canonical body: hitMembership[").append(hitMembership.size()-1)
                        .append("] -> ordered memberMaterialRefs -> materialDictionary.modelUnitAddress -> complete literal parts.text.\n\n");
            } else {
                String pageExcerpt=unit==null?null:CanonicalPageExcerpt.render(hit.text(),pointers);
                builder.append(pageExcerpt==null?hit.text():pageExcerpt).append("\n\n");
            }
        }
        if(!modelStructures.isEmpty()){
            Map<String,Object> dictionary=new LinkedHashMap<>();dictionary.put("dictionaryVersion",com.consense.service.vetting.OerModelMaterial.DICTIONARY_VERSION);
            dictionary.put("materialDictionary",modelStructures);dictionary.put("hitMembership",hitMembership);
            builder.append(com.consense.service.vetting.OerModelMaterial.DICTIONARY_LEGEND).append(com.consense.service.vetting.OerModelMaterial.WIRE_LEGEND)
                .append("Canonical-body reading: use each hit's ordered memberMaterialRefs. Read every referenced unit's complete parts.text in part order; multi-part native DOCX uses newline separators, and native PDF retains adoption.partSeparator.text. Member bodies are joined by a newline. Source identities, positions and limits are in those same entries and sourceDeclarationRef.\n")
                .append("\nSelected canonical model material (untrusted observations):\n")
                .append(com.consense.service.vetting.VettingSourceMetadataWire.write(dictionary)).append("\n");
        }
        if(fixedMaterial!=null)builder.append("\nSelected fixed standard material (untrusted development observations; each numbered reference above maps by the exact parent id):\n").append(fixedMaterial).append("\n");
        // Canonical literal excerpts include source whitespace; trim only the legacy view.
        return hits.stream().anyMatch(hit->canonicalUnit(hit)!=null)?builder.toString():builder.toString().trim();
    }

    /** Deduplicate only an exactly reproducible literal body; unmatched channels keep the existing display. */
    private boolean canonicalBodyMatches(String literal,List<com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer> pointers) {
        if(literal==null||pointers.isEmpty())return false;
        List<String> members=new ArrayList<>();
        for(com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer:pointers) {
            JsonNode parts=pointer.getParts();if(parts==null||!parts.isArray()||parts.size()==0)return false;
            List<String> texts=new ArrayList<>();
            for(JsonNode part:parts){if(!part.path("text").isTextual())return false;texts.add(part.path("text").asText());}
            String separator="";
            if(texts.size()>1){
                JsonNode declared=pointer.getAdoption()==null?null:pointer.getAdoption().get("partSeparator");
                if(declared!=null&&!declared.isNull()){
                    if(!declared.isObject()||!declared.path("text").isTextual())return false;
                    separator=declared.path("text").asText();
                }else if("native_docx".equals(pointer.getProducerKind()))separator="\n";
                else return false;
            }
            members.add(String.join(separator,texts));
        }
        return literal.equals(String.join("\n",members));
    }

    private CitationVO toCitation(VectorStore.SearchHit hit) {
        Map<String,Object> payload=hit.getPayload();
        if(fixedBaselineHit(hit)) {
            FixedReferenceEvidence fixed=fixedBaseline.evidence(hit);
            JsonNode identity=fixed.getPayload();
            CitationVO citation=new CitationVO(fixed.getFileLabel(),String.valueOf(fixed.getPageNo()),hit.anchor(),fixed.getContent(),hit.getScore(),null,
                    null,identity.path("sourceHash").asText(),"standard",false);
            com.fasterxml.jackson.databind.node.ObjectNode pointer=JsonUtils.mapper().createObjectNode();
            pointer.put("evidenceBackend","fixed_development_baseline");pointer.put("parentId",fixed.getId());
            pointer.put("manifestSha256",identity.path("fixedBaselineManifestSha256").asText());
            pointer.put("baselineVersion",identity.path("fixedBaselineVersion").asText());
            pointer.put("sourceFamily",fixed.getSourceFamily());pointer.put("sourceRole","standard");
            pointer.put("originalSourceSha256",identity.path("originalSourceSha256").asText(identity.path("sourceHash").asText()));
            if(identity.has("originalPageNos"))pointer.set("originalPageNos",identity.get("originalPageNos").deepCopy());
            pointer.put("pageNo",fixed.getPageNo());pointer.put("fullObservedParentPreserved",true);
            pointer.put("sourceQualityAccepted",false);pointer.put("projectApplicability","unconfirmed");
            pointer.put("qualifiersComplete","unknown");pointer.put("legalOwnershipAccepted",false);
            pointer.put("evidenceUrl","/api/oer/fixed-baseline/evidence?id="+queryParameter(fixed.getId()));
            pointer.put("originalUrl","/api/oer/fixed-baseline/original?sourceSha256="+queryParameter(pointer.path("originalSourceSha256").asText()));
            if(fixed.getTableArtifact()!=null)pointer.put("tableUrl","/api/oer/fixed-baseline/table?id="+queryParameter(fixed.getId()));
            if(fixed.getSourceUnit()!=null)pointer.set("sourceUnit",fixed.getSourceUnit());
            citation.setFixedBaselineEvidence(pointer);citation.setScoreMeaning("actual_reranked_fixed_development_reference");
            return citation;
        }
        Object page=payload.get("pageNo");
        CitationVO citation=new CitationVO(hit.fileLabel(), page==null?null:String.valueOf(page), hit.anchor(), hit.text(), hit.getScore(),null,
                String.valueOf(payload.get("documentId")),String.valueOf(payload.get("sourceHash")),String.valueOf(payload.get("sourceRole")),
                payload.get("applicabilityContextComplete") instanceof Boolean?(Boolean)payload.get("applicabilityContextComplete"):null);
        if(canonicalUnit(hit)!=null) {
            citation.setCanonicalEvidence(canonicalPointers(hit));
            citation.setCanonicalSourceUnit(canonicalUnit(hit).deepCopy());
            citation.setScoreMeaning(String.valueOf(payload.get("scoreKind")));
        }
        return citation;
    }

    private JsonNode canonicalUnit(VectorStore.SearchHit hit) {
        Object unit=hit.getPayload().get("canonicalSourceUnit");
        return unit instanceof JsonNode?(JsonNode)unit:unit==null?null:JsonUtils.mapper().valueToTree(unit);
    }
    private List<com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer> canonicalPointers(VectorStore.SearchHit hit) {
        Object pointers=hit.getPayload().get("canonicalEvidence");
        return pointers==null?Collections.emptyList():JsonUtils.readList(JsonUtils.write(pointers),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);
    }

    private String evidenceIdOf(List<CitationVO> sources) {
        if(sources.isEmpty())return null;
        CitationVO first=sources.get(0);
        return first.getFixedBaselineEvidence()==null?"document:"+first.getDocumentId():"fixed-baseline:"+first.getFixedBaselineEvidence().path("parentId").asText();
    }

    // ------------------------------------------------------------ 历史

    public List<ChatMessageVO> history(String projectId) {return history(projectId,null);}

    public List<ChatMessageVO> history(String projectId,String sessionId) {
        projectService.require(projectId);
        if(sessionId!=null)requireConversation(projectId,sessionId);
        List<ChatMessageVO> result = new ArrayList<>();
        List<ChatMessage> messages=sessionId==null?chatMessageRepository.findByProjectIdAndSessionIdIsNullOrderByIdAsc(projectId)
                :chatMessageRepository.findByProjectIdAndSessionIdOrderByIdAsc(projectId,sessionId);
        for (ChatMessage message : messages) {
            List<String> citations = message.getCitationsJson() == null
                    ? Collections.<String>emptyList()
                    : safeList(message.getCitationsJson());
            result.add(new ChatMessageVO(message.getId(),
                    message.getRole(),
                    message.getTitle() == null ? null : LocalizedText.same(message.getTitle()),
                    message.getContent() == null ? null : LocalizedText.same(message.getContent()),
                    message.getUnknownScope(),
                    citations,
                    message.getEvidenceId(),
                    safeSources(message.getSourcesJson()),
                    message.getModelIdentityJson() == null ? null
                            : JsonUtils.read(message.getModelIdentityJson(), ModelIdentity.class),
                    message.getClarificationsJson()==null?Collections.emptyList():safeList(message.getClarificationsJson()),message.getSourceScope(),message.getBasisScope(),message.getSessionId()));
        }
        return result;
    }

    @Transactional
    public void clearHistory(String projectId) {clearHistory(projectId,null);}

    @Transactional
    public void clearHistory(String projectId,String sessionId) {
        projectService.require(projectId);
        if(sessionId==null)chatMessageRepository.deleteLegacyByProjectId(projectId);
        else {
            AdviceSession session=lockConversation(projectId,sessionId);
            chatMessageRepository.deleteBySession(projectId,sessionId);
            session.setRevision(session.getRevision()+1);adviceSessions.save(session);
        }
    }

    public List<SessionVO> sessions(String projectId) {
        projectService.require(projectId);
        return adviceSessions.findByProjectIdOrderByCreatedAtAscIdAsc(projectId).stream().map(this::sessionVO).collect(Collectors.toList());
    }

    @Transactional
    public SessionVO createSession(String projectId,SessionRequest request) {
        projectService.require(projectId);
        if(request==null)throw new BizException(4202,"Advice session scope is required.");
        AdviceSession session=new AdviceSession();session.setId(java.util.UUID.randomUUID().toString());session.setProjectId(projectId);
        session.setScope(ProjectDocumentScope.normalizeScope(request.getScope()));
        session.setBasisScope(requestedBasisScope(new AskRequest(null,null,null,request.getBasisScope())));
        return sessionVO(adviceSessions.save(session));
    }

    private SessionVO sessionVO(AdviceSession session){return new SessionVO(session.getId(),session.getProjectId(),session.getScope(),session.getBasisScope(),session.getCreatedAt());}
    private AdviceSession requireConversation(String projectId,String sessionId) {
        if(sessionId==null||sessionId.trim().isEmpty()||sessionId.length()>64)throw new BizException(4202,"A valid advice sessionId is required.");
        TransactionTemplate read=new TransactionTemplate(transactionManager);read.setReadOnly(true);read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return read.execute(status->{entityManager.clear();return adviceSessions.findById(sessionId).filter(s->projectId.equals(s.getProjectId()))
                .orElseThrow(()->new BizException(4202,"Advice session does not belong to this project."));});
    }
    private AdviceSession lockConversation(String projectId,String sessionId) {
        entityManager.clear();
        return adviceSessions.lockSession(projectId,sessionId).orElseThrow(()->new BizException(4202,"Advice session does not belong to this project."));
    }
    private void assertConversationCurrent(AdviceSession expected) {
        AdviceSession current=requireConversation(expected.getProjectId(),expected.getId());
        if(current.getRevision()!=expected.getRevision())throw new BizException(4202,"This advice session changed or was cleared while the question was running. No late answer was published; submit again in the current session.");
    }

    private String conversationSourceIdentity(String projectId,String scope,boolean referenceOnly,OerBusinessEvidence.Session selected) {
        Map<String,Object> binding=new LinkedHashMap<>();binding.put("projectId",projectId);binding.put("scope",scope);binding.put("referenceOnly",referenceOnly);
        if(!referenceOnly) {
            if(selected!=null){assertCurrentSelection(selected,scope);binding.put("canonical",selected.identitySha256());}
            else {
                List<SourceDocument> documents=freshSelectedDocuments(projectId,scope);
                for(SourceDocument document:documents)if(storageService==null||!storageService.matchesStoredBytes(document.getStoragePath(),document.getSourceSha256()))
                    throw new BizException(4204,"A selected advice original is missing or its bytes changed. Restore and review the current sources before asking; no answer was published.");
                binding.put("documents",documents.stream().map(d->Arrays.asList(String.valueOf(d.getId()),d.getSourceSha256(),VettingCorpus.sourceHash(d),VettingCorpus.sourceRole(d),d.getParseStatus()))
                        .sorted(java.util.Comparator.comparing(row->row.get(0))).collect(Collectors.toList()));
            }
        }
        binding.put("fixedBaseline",fixedBaselineEnabled()?fixedBaseline.frozenManifest():null);
        return VettingCorpus.hash(JsonUtils.write(binding));
    }
    private void assertConversationSources(String projectId,String scope,boolean referenceOnly,OerBusinessEvidence.Session selected,String expected) {
        if(!Objects.equals(expected,conversationSourceIdentity(projectId,scope,referenceOnly,selected)))
            throw new BizException(4204,"The advice source version changed during this question. No answer was published; refresh and ask again.");
    }

    private static final String SESSION_JSON_INSTRUCTION="\n\n【输出要求】只输出一个合法的 JSON，不要 Markdown 代码块，不要任何解释文字。";
    private static final String SESSION_JSON_RETRY="\n\n重要：上一次输出不是合法 JSON。请严格只输出 JSON。";
    private List<LlmClient.ChatTurn> sessionTurns(String system,String user,int attempt) {
        return Arrays.asList(LlmClient.ChatTurn.system(system+SESSION_JSON_INSTRUCTION),LlmClient.ChatTurn.user(user+(attempt==0?"":SESSION_JSON_RETRY)));
    }
    private VettingInputBudget.Result sessionBudget(LlmOperation operation,List<LlmClient.ChatTurn> turns,String sourceSnapshot) {
        assertSessionProfile(operation);
        ModelIdentity identity=operation.getIdentity();
        VettingInputBudget.Input input=VettingInputBudget.chatInput(identity.getProvider(),identity.getModel(),identity.getConfigurationSha256(),turns,operation.getStructuredMaxTokens(),sourceSnapshot);
        VettingInputBudget.Result budget=VettingInputBudget.check(input,tokenObserver);
        if(!budget.isExtraDispatchPermitted())throw new BizException(4205,"Advice "+budget.getStatus()+": "+budget.getReason()+". No model request was submitted.");
        if(budget.getObservation().getEffectiveContextTokens()<262144)
            throw new BizException(4205,"Advice budget_unknown: generation requires a verified context of at least 256K tokens.");
        if("ollama".equals(identity.getProvider())&&operation.getNumCtx()!=budget.getObservation().getEffectiveContextTokens())
            throw new BizException(4205,"Advice budget_unknown: observed_context_differs_from_configured_ollama_context.");
        return budget;
    }
    private void assertSessionProfile(LlmOperation operation) {
        if(operation==null||operation.getIdentity()==null||operation.getUnavailableReason()!=null)
            throw new BizException(4205,"Advice budget_unknown: captured_model_profile_required.");
        ModelIdentity identity=operation.getIdentity();
        assertCapturedChatIdentity(identity);
        ConsenseProperties.Llm configured="minimax-cn".equals(identity.getProfileId())?props.getMinimaxCn():props.getLlm();
        if("ollama".equals(identity.getProvider())&&!Boolean.FALSE.equals(configured.getStructuredThinking()))
            throw new BizException(4205,"Advice budget_unknown: the bound native Chat observer requires explicit thinking=false.");
    }
    private void assertCapturedChatIdentity(ModelIdentity identity) {
        if(identity!=null&&"configured_profile".equals(identity.getIdentityScope())) {
            ConsenseProperties.Llm configured="minimax-cn".equals(identity.getProfileId())?props.getMinimaxCn():props.getLlm();
            if(!Objects.equals(identity.getConfigurationSha256(),com.consense.ai.LlmProfiles.configurationSha256(configured)))
                throw new BizException(4205,"Advice budget_unknown: captured_model_configuration_changed.");
        }
    }
    private AnswerRecord completeSessionChat(String system,String user,LlmOperation operation,String sourceSnapshot,Runnable guard) {
        JsonNode schema=JsonUtils.mapper().createObjectNode();
        for(int attempt=0;attempt<2;attempt++) {
            List<LlmClient.ChatTurn> turns=sessionTurns(system,user,attempt);guard.run();sessionBudget(operation,turns,sourceSnapshot);guard.run();
            if(operation.getClient()==null||!operation.getClient().available())throw new BizException(4203,"The captured advice model is unavailable; no alternate model was used.");
            guard.run();String raw=operation.getClient().chatStructured(turns,schema);guard.run();
            try{return JsonUtils.read(JsonUtils.extractJson(raw),AnswerRecord.class);}
            catch(Exception invalid){if(attempt==1)throw new BizException(4205,"Advice output_invalid: JSON parsing failed after one freshly admitted retry. No answer was published.");}
        }
        throw new IllegalStateException("Advice JSON attempts exhausted");
    }
    private String boundedHistory(AdviceSession conversation,String sourceSnapshot,ModelIdentity identity,LlmOperation chat,
                                  AdviceResponsesTransport.Operation responses,Runnable guard) {
        int exchanges=adviceSettings.getHistoryMaxExchanges(),messages=adviceSettings.getHistoryMaxMessages(),tokens=adviceSettings.getHistoryMaxTokens();
        if(exchanges<0||exchanges>100||messages<0||messages>200||tokens<0||tokens>65536)
            throw new BizException(4205,"Advice budget_unknown: invalid bounded history profile.");
        int limit=Math.min(exchanges,messages/2);
        if(limit==0||tokens==0)return "";
        List<ChatMessage> recent=chatMessageRepository.findByProjectIdAndSessionIdOrderByIdDesc(conversation.getProjectId(),conversation.getId(),org.springframework.data.domain.PageRequest.of(0,limit*2));
        Collections.reverse(recent);List<List<LlmClient.ChatTurn>> pairs=new ArrayList<>();
        for(int index=0;index+1<recent.size();index++) {
            ChatMessage user=recent.get(index),assistant=recent.get(index+1);
            if(!ChatMessage.ROLE_USER.equals(user.getRole())||!ChatMessage.ROLE_ASSISTANT.equals(assistant.getRole())||!Objects.equals(assistant.getQuestionMessageId(),user.getId())
                    ||!conversation.getScope().equals(user.getSourceScope())||!conversation.getScope().equals(assistant.getSourceScope())||!conversation.getBasisScope().equals(user.getBasisScope())
                    ||!sourceSnapshot.equals(user.getSourceSnapshotSha256())||!sourceSnapshot.equals(assistant.getSourceSnapshotSha256())
                    ||identity==null||!Objects.equals(JsonUtils.write(identity),assistant.getModelIdentityJson()))continue;
            String answer=assistant.getContent();
            if(JsonUtils.isBlankText(answer))answer="Insufficient evidence. Clarification questions: "+JsonUtils.write(safeList(nvl(assistant.getClarificationsJson())));
            pairs.add(Arrays.asList(LlmClient.ChatTurn.user(nvl(user.getContent())),LlmClient.ChatTurn.assistant(answer)));index++;
        }
        while(!pairs.isEmpty()) {
            List<LlmClient.ChatTurn> history=new ArrayList<>();for(List<LlmClient.ChatTurn> pair:pairs)history.addAll(pair);
            String material="\n\nConversation context only (same session, source version and model profile). User messages are assumptions, and prior answers are not contractual evidence or valid current citations. Answer only from the current evidence IDs above.\n"+JsonUtils.write(history);
            guard.run();long observed;
            if(responses!=null) {
                AdviceResponsesTransport.BudgetResult budget=responses.observe(responses.prepare("Conversation context only; do not generate.",material,sourceSnapshot,0),guard);
                if("budget_unknown".equals(budget.getStatus()))throw new AdviceResponsesTransport.RequestFailure(budget.getStatus(),budget.getReason(),null);
                if(budget.getConfiguredContextTokens()<262144)throw new BizException(4205,"Advice budget_unknown: explicit sessions require at least 256K configured tokens.");
                observed=budget.getInputTokens();
            } else observed=sessionBudget(chat,sessionTurns("Conversation context only; do not generate.",material,0),sourceSnapshot).getObservation().getInputTokens();
            if(observed<=tokens)return material;
            pairs.remove(0);
        }
        return "";
    }

    public List<QuickQuestionVO> quickQuestions(String projectId) {
        return Arrays.asList(
                new QuickQuestionVO(LocalizedText.of(
                        "SCC4.1 下的文件优先次序是什么？",
                        "SCC4.1 下的文件優先次序是什麼？",
                        "What is the order of precedence under SCC4.1?"), Boolean.FALSE, "SCC4.1"),
                new QuickQuestionVO(LocalizedText.of(
                        "信封一与信封二在投标提交中如何使用？",
                        "信封一與信封二在投標提交中如何使用？",
                        "How are Envelope 1 and Envelope 2 used in the tender submission?"), Boolean.FALSE, "SCT5"),
                new QuickQuestionVO(LocalizedText.of(
                        "本文件包中 L10Pro 的用途是什么？",
                        "本文件包中 L10Pro 的用途是什麼？",
                        "What is L10Pro used for in this package?"), Boolean.FALSE, "L10Pro"),
                new QuickQuestionVO(LocalizedText.of(
                        "平台转换层需要什么混凝土等级？",
                        "平台轉換層需要什麼混凝土等級？",
                        "What concrete grade is required for the podium transfer slab?"), Boolean.TRUE, null),
                new QuickQuestionVO(LocalizedText.of(
                        "这份组装好的合约文件包属于哪个项目？",
                        "這份組裝好的合約文件包屬於哪個項目？",
                        "Which project does this assembled contract package relate to?"), Boolean.FALSE, null));
    }

    // ------------------------------------------------------------ 内部

    private ChatMessage saveMessage(String projectId, String role, String title, String content,
                             String unknownScope, List<String> citations, String evidenceId, ModelIdentity modelIdentity) {
        ChatMessage message = new ChatMessage();
        message.setProjectId(projectId);
        message.setRole(role);
        message.setTitle(title);
        message.setContent(content);
        message.setUnknownScope(unknownScope);
        message.setCitationsJson(citations == null ? null : JsonUtils.write(citations));
        message.setEvidenceId(evidenceId);
        message.setModelIdentityJson(modelIdentity == null ? null : JsonUtils.write(modelIdentity));
        message.setCreatedAt(Instant.now());
        return chatMessageRepository.save(message);
    }

    private void persistAssistant(String projectId, AskResponseVO response,Long questionMessageId,String sourceSnapshot) {
        String content = response.isGrounded()
                ? (response.getContent() == null ? "" : response.getContent().getZhHans())
                : null;
        ChatMessage message=saveMessage(projectId, ChatMessage.ROLE_ASSISTANT,
                response.getTitle() == null ? null : response.getTitle().getZhHans(),
                content,
                response.getUnknownScope(),
                response.getCitations(),
                response.getEvidenceId(), response.getModelIdentity());
        message.setSessionId(response.getSessionId());message.setSourceSnapshotSha256(sourceSnapshot);
        message.setSourcesJson(JsonUtils.write(response.getSources()));message.setClarificationsJson(JsonUtils.write(response.getClarifications()));message.setSourceScope(response.getSourceScope());message.setBasisScope(response.getBasisScope());message.setQuestionMessageId(questionMessageId);chatMessageRepository.save(message);response.setMessageId(message.getId());
    }

    /** Remote retrieval/chat completes before this short, atomic history write. */
    private void persistExchange(String projectId,String scope,String question,AskRequest request,QuestionContext context,
                                 AskResponseVO response,OerBusinessEvidence.Session session,List<VectorStore.SearchHit> fixedHits,String fixedIdentity,AdviceSession conversation,String sourceSnapshot) {
        if(session!=null)assertCurrentSelection(session,scope);
        assertFixedReferencesCurrent(fixedHits,fixedIdentity);
        if(session==null&&sourceSnapshot!=null)assertConversationSources(projectId,scope,"reference_only".equals(request.getBasisScope()),null,sourceSnapshot);
        TransactionTemplate transaction=new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status->{
            if(session==null&&sourceSnapshot!=null)assertConversationSources(projectId,scope,"reference_only".equals(request.getBasisScope()),null,sourceSnapshot);
            if(conversation!=null) {
                AdviceSession current=lockConversation(projectId,conversation.getId());
                if(current.getRevision()!=conversation.getRevision())throw new BizException(4202,"The advice session changed while this answer was running; no late answer was published.");
                assertConversationSources(projectId,scope,"reference_only".equals(request.getBasisScope()),session,sourceSnapshot);
                assertCapturedChatIdentity(response.getModelIdentity());
                current.setRevision(current.getRevision()+1);adviceSessions.save(current);response.setSessionId(conversation.getId());
            }
            ChatMessage user=saveMessage(projectId,ChatMessage.ROLE_USER,null,question,null,null,null,null);
            user.setSessionId(request.getSessionId());user.setSourceSnapshotSha256(sourceSnapshot);
            user.setReplyToMessageId(request.getReplyToMessageId());user.setSourceScope(scope);
            user.setBasisScope(requestedBasisScope(request));
            user.setOriginalQuestion(context.originalQuestion);user.setUserBackgroundJson(JsonUtils.write(context.background));
            chatMessageRepository.save(user);persistAssistant(projectId,response,user.getId(),sourceSnapshot);
        });
    }

    private List<String> safeList(String json) {
        try {
            return JsonUtils.readList(json, String.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 模型返回的回答记录 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnswerRecord {
        private boolean grounded;
        private String answer;
        private List<String> citations;
        private String missingReason;
        private List<String> clarifications;
        private String basisScope;
        public AnswerRecord(boolean grounded,String answer,List<String> citations,String missingReason){this(grounded,answer,citations,missingReason,null,null);}
        public AnswerRecord(boolean grounded,String answer,List<String> citations,String missingReason,List<String> clarifications){this(grounded,answer,citations,missingReason,clarifications,null);}
    }

    public List<AdviceSourceVO> sources(String projectId,String scope) {
        projectService.require(projectId);String normalized=ProjectDocumentScope.normalizeScope(scope);
        return ProjectDocumentScope.currentCatalog(projectId,sourceDocumentRepository.findBusinessSourcesByProjectId(projectId)).stream().map(d->{String exclusion=ProjectDocumentScope.exclusionReason(d,normalized,"advisory");
            return new AdviceSourceVO(String.valueOf(d.getId()),d.getFileKey(),d.getFileName(),VettingCorpus.sourceRole(d),d.getDocumentScope(),d.getParseStatus(),VettingCorpus.sourceHash(d),exclusion==null,exclusion);}).collect(Collectors.toList());
    }
    private List<SourceDocument> selectedDocuments(String projectId,String scope){return ProjectDocumentScope.selected(projectId,sourceDocumentRepository.findBusinessSourcesByProjectId(projectId),scope,"advisory");}
    private List<SourceDocument> freshSelectedDocuments(String projectId,String scope) {
        TransactionTemplate transaction=new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status->{
            // OpenEntityManagerInView may otherwise retain an old source entity across separate reads.
            entityManager.clear();return selectedDocuments(projectId,scope);
        });
    }
    private OerBusinessEvidence.Session canonicalSession(String projectId,String scope) {
        List<SourceDocument> selected=freshSelectedDocuments(projectId,scope);
        if(selected.isEmpty())throw new BizException(4200,"No permitted documents in the selected advice scope.");
        try{return canonical.load(projectId,"advisory",selected);}
        catch(RuntimeException unavailable){throw new BizException(4204,"Current canonical source bindings are unavailable for the selected advice scope. Complete source review and registration before asking.");}
    }
    private void assertCurrentSelection(OerBusinessEvidence.Session session,String scope) {
        try {
            canonical.assertSelected(session,freshSelectedDocuments(session.projectId,scope));
            // assertSelected already checks current bindings and the immutable views.
        }catch(RuntimeException changed){throw new BizException(4204,"The selected advice source package changed during this operation. Refresh the scope and ask again; the previous evidence was not saved as a new answer.");}
    }
    private IndexStatusVO canonicalStatus(String projectId,String scope) {
        OerBusinessEvidence.CurrentIndexStatus current=canonical.currentIndexStatus(projectId,"advisory",()->freshSelectedDocuments(projectId,scope));
        return canonicalStatusResult(scope,current.indexKey,current.chunks,current.documentCount,current.state);
    }
    private IndexStatusVO canonicalStatus(String projectId,String scope,OerBusinessEvidence.Session session) {
        OerCanonicalRetrievalClient.IndexState state;
        try{state=canonical.indexStatusSelected(session,freshSelectedDocuments(session.projectId,scope));
            canonical.assertSelectedScope(session,freshSelectedDocuments(session.projectId,scope));}
        catch(RuntimeException changed){throw new BizException(4204,"The selected advice source package changed during this operation. Refresh the scope and ask again; the previous evidence was not saved as a new answer.");}
        return canonicalStatusResult(scope,session.indexKey,session.chunkCount(),session.documentIds.size(),state);
    }
    private IndexStatusVO canonicalStatusResult(String scope,String indexKey,int chunks,int documentCount,OerCanonicalRetrievalClient.IndexState state) {
        boolean ready=state.ready;
        String reason=ready?null:"canonical_index_receipt_missing".equals(state.reason)?state.reason:"canonical_index_unavailable";
        LocalizedText message=ready?LocalizedText.of("当前范围的规范证据索引就绪，共 "+state.chunkCount+" 个来源切片。","當前範圍的規範證據索引就緒，共 "+state.chunkCount+" 個來源切片。","Canonical evidence index ready for the selected scope: "+state.chunkCount+" chunks.")
                :LocalizedText.of("当前范围的规范证据索引未就绪，请刷新索引并核对来源及检索服务。","當前範圍的規範證據索引未就緒，請刷新索引並核對來源及檢索服務。","Canonical evidence index is not ready for the selected scope. Refresh it and check current source bindings and the retrieval service.");
        IndexStatusVO result=new IndexStatusVO(chunks,ready?state.chunkCount:0,"canonical-oer",indexKey,
                "BAAI/bge-m3",ai.chatModel(),ai.available(),ready,false,message,!ready&&!"canonical_index_receipt_missing".equals(reason),
                documentCount,ready?documentCount:0,ready);
        result.setEvidenceBackend("canonical");result.setSourceScope(scope);result.setIndexKey(indexKey);
        result.setIndexSignature(state.signature);result.setIndexReason(reason);return result;
    }
    private IndexStatusVO canonicalUnavailable(String scope) {
        IndexStatusVO result=new IndexStatusVO(0,0,"canonical-oer",null,"BAAI/bge-m3",ai.chatModel(),ai.available(),false,false,
                LocalizedText.of("当前范围的规范来源绑定不可用；请完成来源解析、核验及登记后再刷新索引。","當前範圍的規範來源綁定不可用；請完成來源解析、核驗及登記後再刷新索引。","Current canonical source bindings are unavailable for this scope. Complete source parsing, review and registration before refreshing the index."),true,0,0,false);
        result.setEvidenceBackend("canonical");result.setSourceScope(scope);result.setIndexReason("current_sources_unavailable");return result;
    }
    private boolean freshIndex(List<EvidenceChunk> chunks,List<SourceDocument> documents) {
        Map<String,String> hashes=new LinkedHashMap<>(),roles=new LinkedHashMap<>();
        for(SourceDocument d:documents){String id=String.valueOf(d.getId());hashes.put(id,VettingCorpus.sourceHash(d));roles.put(id,VettingCorpus.sourceRole(d));}
        if(!chunks.stream().map(c->String.valueOf(c.getDocumentId())).collect(Collectors.toSet()).equals(hashes.keySet()))return false;
        return chunks.stream().allMatch(c->Objects.equals(c.getSourceHash(),hashes.get(String.valueOf(c.getDocumentId())))&&Objects.equals(c.getSourceRole(),roles.get(String.valueOf(c.getDocumentId()))));
    }
    private List<CitationVO> safeSources(String json){if(JsonUtils.isBlankText(json))return Collections.emptyList();try{return JsonUtils.readList(json,CitationVO.class);}catch(Exception e){return Collections.emptyList();}}
    private List<String> cleanClarifications(List<String> questions){if(questions==null)return Collections.emptyList();return questions.stream().filter(Objects::nonNull).map(String::trim).filter(s->!s.isEmpty()).distinct().limit(12).collect(Collectors.toList());}
    private List<String> validatedCitations(List<String> requested,List<CitationVO> submitted) {
        if(requested==null||requested.isEmpty())return Collections.emptyList();List<String> accepted=new ArrayList<>();
        for(String citation:requested){String value=nvl(citation).trim();List<CitationVO> matches=submitted.stream().filter(s->value.equals(s.getEvidenceId())||value.equals(s.getFileLabel())).collect(Collectors.toList());
            if(matches.size()!=1)return Collections.emptyList();String id=matches.get(0).getEvidenceId();if(!accepted.contains(id))accepted.add(id);
        }return accepted;
    }
    /** Library retrieval must also show current incorporation/edition/override provisions, if available. */
    private List<VectorStore.SearchHit> withCanonicalIncorporationContext(OerBusinessEvidence.Session session,List<VectorStore.SearchHit> hits) {
        List<VectorStore.SearchHit> libraries=hits.stream().filter(h->"standard".equals(String.valueOf(h.getPayload().get("sourceRole")))).collect(Collectors.toList());
        if(libraries.isEmpty())return hits;
        List<String> origins=new ArrayList<>();
        for(VettingCorpus.Chunk chunk:session.chunks()) {
            if(!"tender".equals(chunk.getRole()))continue;
            String name=nvl(chunk.getFileName()).toLowerCase(java.util.Locale.ROOT).replace('_',' ').replace('-',' ');
            boolean composition=name.contains("the specification")||name.contains("specification composition");
            if(composition||libraries.stream().anyMatch(h->referenceMention(chunk.getContent(),h.fileLabel())))origins.add(chunk.getId());
        }
        List<VectorStore.SearchHit> contexts=origins.isEmpty()?Collections.emptyList():canonical.sourceHits(session,origins);
        List<VectorStore.SearchHit> result=new ArrayList<>(hits);Set<String> units=new LinkedHashSet<>(),representedOrigins=new LinkedHashSet<>();
        for(VectorStore.SearchHit hit:hits){JsonNode unit=canonicalUnit(hit);if(unit!=null){units.add(unit.path("unitId").asText());recordCompleteMembers(unit,representedOrigins);}}
        boolean complete=!origins.isEmpty();int contextChars=0;
        for(VectorStore.SearchHit context:contexts) {
            JsonNode unit=canonicalUnit(context);
            if(unit==null||!"complete_observed_unit".equals(unit.path("status").asText()))complete=false;
            String unitId=unit==null?context.getId():unit.path("unitId").asText();
            if(units.contains(unitId))continue;
            if(contextChars+context.text().length()>12000){complete=false;continue;}
            units.add(unitId);result.add(context);contextChars+=context.text().length();recordCompleteMembers(unit,representedOrigins);
        }
        complete=complete&&representedOrigins.containsAll(origins);
        // This flag concerns the bounded observed context only, never standard adoption or whole-source quality.
        List<VectorStore.SearchHit> annotated=new ArrayList<>();
        for(VectorStore.SearchHit hit:result) {
            Map<String,Object> payload=new LinkedHashMap<>(hit.getPayload());
            if("standard".equals(payload.get("sourceRole"))&&!fixedBaselineHit(hit))payload.put("applicabilityContextComplete",complete);
            annotated.add(new VectorStore.SearchHit(hit.getId(),hit.getScore(),payload));
        }
        return annotated;
    }

    private void recordCompleteMembers(JsonNode unit,Set<String> representedOrigins) {
        if(unit!=null&&"complete_observed_unit".equals(unit.path("status").asText()))
            for(JsonNode member:unit.path("requiredMemberIds"))representedOrigins.add(member.asText());
    }

    private List<VectorStore.SearchHit> withIncorporationContext(String projectId,String scope,List<VectorStore.SearchHit> hits) {
        List<VectorStore.SearchHit> libraries=hits.stream().filter(h->"standard".equals(String.valueOf(h.getPayload().get("sourceRole")))).collect(Collectors.toList());
        if(libraries.isEmpty())return hits;
        List<SourceDocument> selected=selectedDocuments(projectId,scope).stream().filter(ProjectDocumentScope::parseUsable).collect(Collectors.toList());
        Set<String> allowedIds=selected.stream().map(d->String.valueOf(d.getId())).collect(Collectors.toSet());
        Set<String> compositionIds=selected.stream().filter(d->{String name=nvl(d.getFileName()).toLowerCase(java.util.Locale.ROOT).replace('_',' ').replace('-',' ');return "tender".equals(VettingCorpus.sourceRole(d))&&(name.contains("the specification")||name.contains("specification composition"));}).map(d->String.valueOf(d.getId())).collect(Collectors.toSet());
        List<VectorStore.SearchHit> result=new ArrayList<>(hits);Set<String> present=result.stream().map(this::evidenceIdentity).collect(Collectors.toSet());int chars=0;
        List<EvidenceChunk> ordered=chunkRepository.findByProjectIdOrderByChunkIndexAsc(projectId);
        ordered.sort(java.util.Comparator.comparingInt(c->compositionIds.contains(String.valueOf(c.getDocumentId()))?0:1));
        for(EvidenceChunk chunk:ordered){
            if(!allowedIds.contains(String.valueOf(chunk.getDocumentId()))||!"tender".equals(chunk.getSourceRole()))continue;
            boolean relevant=compositionIds.contains(String.valueOf(chunk.getDocumentId()))||libraries.stream().anyMatch(h->referenceMention(chunk.getContent(),h.fileLabel()));
            if(!relevant||chars+nvl(chunk.getContent()).length()>12000)continue;
            VectorStore.SearchHit context=chunkToHit(chunk);if(present.add(evidenceIdentity(context))){result.add(context);chars+=nvl(chunk.getContent()).length();}
        }
        boolean complete=!compositionIds.isEmpty()&&ordered.stream().filter(c->compositionIds.contains(String.valueOf(c.getDocumentId()))).map(this::chunkToHit).map(this::evidenceIdentity).allMatch(present::contains);
        List<VectorStore.SearchHit> annotated=new ArrayList<>();for(VectorStore.SearchHit hit:result){Map<String,Object> payload=new LinkedHashMap<>(hit.getPayload());if("standard".equals(payload.get("sourceRole")))payload.put("applicabilityContextComplete",complete);annotated.add(new VectorStore.SearchHit(hit.getId(),hit.getScore(),payload));}return annotated;
    }
    private boolean incorporationSupported(String basisScope,List<String> citations,List<CitationVO> sources) {
        List<CitationVO> cited=sources.stream().filter(s->citations.contains(s.getEvidenceId())).collect(Collectors.toList());
        if(cited.stream().anyMatch(s->s.getCanonicalSourceUnit()!=null&&!"complete_observed_unit".equals(s.getCanonicalSourceUnit().path("status").asText())))return false;
        if("reference_only".equals(basisScope))return cited.stream().anyMatch(s->"standard".equals(s.getSourceRole()));
        if(cited.stream().noneMatch(s->"tender".equals(s.getSourceRole())))return false;
        for(CitationVO reference:cited)if("standard".equals(reference.getSourceRole())
                &&(!Boolean.TRUE.equals(reference.getApplicabilityContextComplete())||cited.stream().noneMatch(s->"tender".equals(s.getSourceRole())&&referenceMention(s.getContent(),reference.getFileLabel()))))return false;
        return true;
    }
    private boolean referenceMention(String passage,String referenceLabel) {
        String text=nvl(passage).toLowerCase(java.util.Locale.ROOT),label=nvl(referenceLabel).toLowerCase(java.util.Locale.ROOT);
        if(label.startsWith("sl ")||label.contains("specification library")||label.contains("general specification"))
            return text.contains("specification library")||text.contains("general specification")||text.matches("(?s).*\\b(?:sl[ -]?20\\d\\d|gscew\\d*|gs[ -]?20\\d\\d)\\b.*");
        if(label.startsWith("gcc ")||label.contains("general conditions of contract"))return text.contains("general conditions of contract")||text.matches("(?s).*\\bgcc\\b.*");
        String key=label.split("[ ·]",2)[0];return !key.isEmpty()&&text.matches("(?s).*\\b"+java.util.regex.Pattern.quote(key)+"\\b.*");
    }
    private String evidenceIdentity(VectorStore.SearchHit hit){return hit.getPayload().get("documentId")+"|"+hit.anchor()+"|"+VettingCorpus.hash(hit.text());}
    private QuestionContext clarificationContext(String projectId,String scope,Long replyTo,String text,String requestedBasis,String sessionId) {
        if(replyTo==null)return new QuestionContext(text,Collections.emptyList());
        ChatMessage parent=chatMessageRepository.findById(replyTo).filter(m->projectId.equals(m.getProjectId())&&ChatMessage.ROLE_ASSISTANT.equals(m.getRole())
                &&Objects.equals(sessionId,m.getSessionId())&&scope.equals(m.getSourceScope())&&"insufficient".equals(m.getBasisScope())&&m.getQuestionMessageId()!=null&&!safeList(nvl(m.getClarificationsJson())).isEmpty())
                .orElseThrow(()->new BizException(4202,"Reply target must be an unanswered clarification in the same project and evidence scope."));
        ChatMessage original=chatMessageRepository.findById(parent.getQuestionMessageId()).filter(m->projectId.equals(m.getProjectId())&&Objects.equals(sessionId,m.getSessionId())&&ChatMessage.ROLE_USER.equals(m.getRole())&&scope.equals(m.getSourceScope()))
                .orElseThrow(()->new BizException(4202,"The clarification's original question is no longer available. Start a new question."));
        String originalBasis=JsonUtils.isBlankText(original.getBasisScope())?"project_contract":original.getBasisScope();
        if(!Objects.equals(originalBasis,requestedBasis))throw new BizException(4202,"A clarification reply must retain the original project-contract or reference-only basis scope.");
        List<String> background=new ArrayList<>(safeList(nvl(original.getUserBackgroundJson())));background.add(text);
        if(background.size()>5||background.stream().mapToInt(String::length).sum()>6000)throw new BizException(4202,"This clarification thread exceeds the bounded context. Start a new question with the relevant facts.");
        return new QuestionContext(JsonUtils.isBlankText(original.getOriginalQuestion())?original.getContent():original.getOriginalQuestion(),background);
    }
    private static final class QuestionContext {final String originalQuestion;final List<String> background;QuestionContext(String question,List<String> background){this.originalQuestion=question;this.background=background;}}

    private boolean fixedBaselineEnabled(){return fixedBaseline!=null&&fixedBaseline.enabled();}
    private boolean fixedBaselineHit(VectorStore.SearchHit hit){return hit!=null&&hit.getPayload()!=null&&Boolean.TRUE.equals(hit.getPayload().get("fixedDevelopmentBaseline"));}
    private String requestedBasisScope(AskRequest request){
        String value=request==null?"":nvl(request.getBasisScope()).trim().toLowerCase(java.util.Locale.ROOT);
        if(value.isEmpty())return "project_contract";
        if("project_contract".equals(value)||"reference_only".equals(value))return value;
        throw new BizException(4202,"Advice basisScope must be project_contract or reference_only.");
    }
    private List<VectorStore.SearchHit> fixedReferenceHits(String question){
        try {
            List<VectorStore.SearchHit> hits=fixedBaseline.search("advisory",question,10);
            if(hits==null)throw new IllegalStateException("fixed_reference_result_missing");
            for(VectorStore.SearchHit hit:hits)if(!fixedBaselineHit(hit))throw new IllegalStateException("fixed_reference_identity_missing");
            fixedBaseline.assertCurrent(hits);return hits;
        }catch(RuntimeException unavailable){throw new BizException(4204,"The selected fixed standard-reference baseline is unavailable or changed. No project or legacy evidence fallback was used.");}
    }
    private String fixedSourceIdentity(List<VectorStore.SearchHit> hits){
        try {
            Map<String,Object> identity=new LinkedHashMap<>();identity.put("backend","fixed_development_baseline");
            identity.put("manifest",fixedBaseline.frozenManifest());identity.put("selectedParents",hits.stream().map(VectorStore.SearchHit::getId).collect(Collectors.toList()));
            return VettingCorpus.hash(JsonUtils.write(identity));
        }catch(RuntimeException changed){throw new BizException(4204,"The selected fixed standard-reference baseline identity changed; ask again with the current frozen baseline.");}
    }
    private String fixedModelMaterial(List<VectorStore.SearchHit> hits){
        try{return fixedBaseline.toModelMaterial(hits,FIXED_REFERENCE_MATERIAL_CHARACTERS);}
        catch(RuntimeException incomplete){throw new BizException(4204,"The selected fixed standard groups cannot be submitted completely within this question's material budget. Narrow the question or restore the complete frozen sources; no text or table rows were truncated.");}
    }
    private void assertFixedReferencesCurrent(List<VectorStore.SearchHit> hits,String expectedIdentity){
        if(hits==null||hits.isEmpty())return;
        try {
            if(!fixedBaselineEnabled())throw new IllegalStateException("fixed_reference_disabled");
            fixedBaseline.assertCurrent(hits);
            if(!Objects.equals(expectedIdentity,fixedSourceIdentity(hits)))throw new IllegalStateException("fixed_reference_identity_changed");
        }catch(RuntimeException changed){throw new BizException(4204,"The selected fixed standard-reference baseline changed during this operation. The previous evidence was not saved as a new answer.");}
    }
    private String queryParameter(String value){
        try{return java.net.URLEncoder.encode(nvl(value),java.nio.charset.StandardCharsets.UTF_8.name());}
        catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}
    }
}
