package com.consense.service.vetting;

import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.document.DocumentParseProbe;
import com.consense.document.DocumentBlock;
import com.consense.document.ParseCoverage;
import com.consense.domain.*;
import com.consense.repository.SourceDocumentRepository;
import com.consense.repository.VettingFindingRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Candidate;
import com.consense.web.dto.DraftingDtos.UploadResultVO;
import com.consense.web.dto.VettingDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Bounded background review, immutable evidence snapshots and preserved human disposition. */
@Slf4j @Service @RequiredArgsConstructor
public class VettingService {
    public static final String EVIDENCE_UNVERIFIED = "unverified";
    private static final List<String> FILE_ORDER = Arrays.asList("NTT", "SCT", "SCC", "GCT", "FT", "AA", "GCC", "SL", "PRE", "SP", "BQ", "GS");
    private static final List<String> ACTIVE_STATES = Arrays.asList("QUEUED", "RUNNING");
    private final ProjectService projectService;
    private final StorageService storageService;
    private final DocumentParser documentParser;
    private final ConsenseProperties props;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final VettingFindingRepository findingRepository;
    private final VettingRunRepository runRepository;
    private final VettingSemanticReview semantic;
    private final VettingReportWriter reportWriter;
    private final PlatformTransactionManager transactionManager;
    private final com.consense.ai.LlmProfiles llmProfiles;
    private final ConcurrentMap<String,Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String,CompletableFuture<RunResultVO>> completions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String,VettingServiceProbe> serviceProbes = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),
            task -> { Thread t = new Thread(task,"consense-vetting"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        List<VettingServiceProbe> recovery=new ArrayList<>();long started=System.nanoTime();
        try {
            tx().execute(status -> {
                for (VettingRun run : runRepository.findByStatusIn(ACTIVE_STATES)) {
                    VettingServiceProbe probe=VettingServiceProbe.start(props,run.getId(),run.getProjectId(),"restart_recovery");recovery.add(probe);
                    probe.observe("interrupted_run_before_recovery",()->VettingServiceProbe.map("run",run));
                    run.setStatus("FAILED"); run.setPhase("interrupted"); run.setFinishedAt(Instant.now());
                    run.setError("服务重启导致审查中断。已有人工处置保留，请重新运行审查。"); run.setMessage(run.getError()); runRepository.save(run);
                    probe.observe("interrupted_run_pending_commit",()->VettingServiceProbe.map("run",run,"committed",false));
                } return null;
            });
            long elapsed=System.nanoTime()-started;
            for(VettingServiceProbe probe:recovery){probe.observe("restart_recovery_transaction_returned",()->VettingServiceProbe.returnedTransaction("inclusiveTransactionWallNanos",elapsed,
                    "records",probe.reference("interrupted_run_pending_commit"),"boundary","Original REQUIRED recovery callback/transaction returned, including observers; no extra DAO read; outer commit may remain unknown"));probe.finish("recovery_transaction_returned",null);}
        } catch(RuntimeException|Error e){for(VettingServiceProbe probe:recovery){probe.originalFailure("restart_recovery_failed",e);probe.finish("recovery_failed",e);}throw e;}
    }
    @PreDestroy public void stopWorker() {
        worker.shutdownNow();
        for(VettingServiceProbe probe:serviceProbes.values())probe.observe("worker_shutdown_requested",()->VettingServiceProbe.map("boundary","Original shutdownNow returned; this event does not assert an interrupted run transaction was committed"));
    }

    public List<VettingFileVO> listFiles(String projectId) {
        projectService.require(projectId); List<VettingFileVO> out = new ArrayList<>();
        for (SourceDocument d : sourceSet(projectId)) {
            List<String> notes = parseWarnings(d);
            if (excluded(d.getFileName())) notes.add("评语、清单或问答参考不纳入被审查源集。");
            out.add(new VettingFileVO(fileKey(d),roleLabel(VettingCorpus.sourceRole(d)),d.getFileName(),nvl(d.getParseMessage()),
                    d.getParseStatus(),false,reviewable(d),d.getPageCount()==null?0:d.getPageCount(),d.getParseStatus(),d.getParseMessage(),nvl(d.getTextContent()).length(),Boolean.TRUE.equals(d.getOcrUsed()),notes,VettingCorpus.sourceRole(d)));
        } return out;
    }

    /** No transaction is held during parsing or OCR. */
    public UploadResultVO uploadPackage(String projectId,List<MultipartFile> files) {
        return uploadPackage(projectId, files, null);
    }
    public UploadResultVO uploadPackage(String projectId,List<MultipartFile> files,String sourceRole) {
        projectService.require(projectId);
        if (!projectId.matches("[A-Za-z0-9_-]+")) throw new BizException(4101,"项目编号含无效路径字符");
        String role = validatedRole(sourceRole);
        int accepted=0,parsed=0,failed=0; List<String> messages=new ArrayList<>();
        for (MultipartFile file : files) {
            if (file==null || file.isEmpty()) continue; accepted++;
            StorageService.StoredFile stored=storageService.store(projectId,SourceDocument.CATEGORY_VETTING_PACKAGE,file);
            SourceDocument d=new SourceDocument(); d.setProjectId(projectId); d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE); d.setFileKey(matchFileKey(stored.getOriginalName()));
            d.setFileName(stored.getOriginalName()); d.setContentType(stored.getContentType()); d.setSizeBytes(stored.getSize()); d.setStoragePath(stored.getPath()); d.setCreatedAt(Instant.now()); d.setParseStatus("PARSING");
            d.setReviewRole(role == null ? VettingCorpus.sourceRole(d) : role);
            if ("package_manifest".equals(d.getReviewRole()) && "OTHER".equals(d.getFileKey())) d.setFileKey("INDEX");
            sourceDocumentRepository.saveAndFlush(d);
            String parseJobId=null,rawSha256=null;
            try {
                byte[] sourceBytes=storageService.read(stored.getPath());
                DocumentParser.ParsedDocument p;
                if(DocumentParseProbe.configured(props)) {
                    rawSha256=DocumentParseProbe.sha256(sourceBytes);
                    parseJobId="parse-"+rawSha256.substring(0,12)+"-"+UUID.randomUUID();
                    p=documentParser.parse(stored.getOriginalName(),sourceBytes,parseJobId,String.valueOf(d.getId()),rawSha256);
                }else p=documentParser.parse(stored.getOriginalName(),sourceBytes);
                d.setParseStatus(p.getParseStatus()); d.setParseMessage(truncate(p.getMessage(),1024)); d.setPageCount(p.getPageCount()); d.setOcrUsed(p.isOcrUsed()); d.setTextContent(p.getText());
                d.setStructuredContentJson(JsonUtils.write(p.getBlocks())); d.setParseCoverageJson(JsonUtils.write(p.getCoverage()));
                if ("FAILED".equals(p.getParseStatus())) failed++; else parsed++;
                messages.add(d.getFileName()+" · "+p.getParseStatus()+" · "+p.getMessage());
            } catch(Exception e) { d.setParseStatus("FAILED"); d.setParseMessage(truncate(brief(e),1024)); failed++; messages.add(d.getFileName()+" 解析失败："+brief(e)); }
            sourceDocumentRepository.saveAndFlush(d);
            if(parseJobId!=null)DocumentParseProbe.bindPersisted(props,parseJobId,String.valueOf(d.getId()),rawSha256,VettingCorpus.sourceHash(d),d.getParseStatus());
        } return new UploadResultVO(accepted,parsed,failed,messages);
    }

    public VettingJobVO startRun(String projectId,String lang) {
        projectService.require(projectId);
        synchronized(lock(projectId)) {
            VettingRun latest=runRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId).orElse(null);
            if(latest!=null && ACTIVE_STATES.contains(latest.getStatus())) return jobVO(latest);
            com.consense.ai.LlmOperation operation=llmProfiles.capture();
            List<SourceDocument> snapshot=sourceSet(projectId);
            if(snapshot.stream().noneMatch(this::reviewable)) throw new BizException(4101,"审查源集没有可用正文，请上传并检查解析状态");
            VettingRun run=new VettingRun(); run.setId(UUID.randomUUID().toString()); run.setProjectId(projectId); run.setLang(normalizeLang(lang)); run.setStartedAt(Instant.now());
            if(operation.isExplicit()||!props.getVetting().getResponses().isEnabled())run.setModelIdentityJson(JsonUtils.write(operation.getIdentity()));
            run.setStatus("QUEUED"); run.setPhase("queued"); run.setTotalUnits(topicCount()+2); run.setMessage("审查已排队，人工处置按问题指纹保留。");
            run.setCoverageJson(JsonUtils.write(coverage(snapshot,Collections.emptyList(),Collections.emptySet(),Collections.emptyList(),false)));
            // Original REQUIRED insert call before dispatch; a caller-owned outer transaction can remain active.
            tx().execute(status -> runRepository.saveAndFlush(run));
            CompletableFuture<RunResultVO> done=new CompletableFuture<>(); completions.put(run.getId(),done);
            VettingServiceProbe lifecycle=VettingServiceProbe.start(props,run.getId(),projectId,"queued_transaction_returned");
            if(lifecycle.enabled())serviceProbes.put(run.getId(),lifecycle);
            lifecycle.observe("queued_transaction_returned",()->VettingServiceProbe.returnedTransaction("run",run,"sourceSnapshot",sourceMetadata(snapshot),
                    "boundary","Original REQUIRED queued insert returned; outer transaction commit can remain unknown. Snapshots do not enter corpus/prompts"));
            try { worker.execute(() -> {try(com.consense.ai.LlmProfiles.Scope ignored=llmProfiles.bind(operation)){executeRun(run.getId(),projectId,run.getLang(),snapshot,done,lifecycle);}}); }
            catch(RejectedExecutionException e) {
                lifecycle.originalFailure("worker_dispatch_rejected",e);
                try{failRun(run.getId(),"审查队列已满，请稍后重试。",done,lifecycle);}finally{lifecycle.finish("queue_rejected",e);serviceProbes.remove(run.getId(),lifecycle);}
            }
            return jobVO(runRepository.findById(run.getId()).orElse(run));
        }
    }
    public RunResultVO run(String projectId,String lang) {
        VettingJobVO job=startRun(projectId,lang); CompletableFuture<RunResultVO> done=completions.get(job.getId());
        if(done==null) { VettingJobVO saved=getRun(projectId,job.getId()); if("COMPLETED".equals(saved.getStatus())) return saved.getResult(); throw new BizException(4101,available(saved.getError(),"任务无法继续")); }
        try { return done.get(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new BizException(4101,"等待审查已中断，后台任务仍可通过任务接口查看"); }
        catch(ExecutionException e) { throw new BizException(4101,brief(e.getCause())); }
    }
    public VettingJobVO latestRun(String projectId) { projectService.require(projectId); return runRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId).map(this::jobVO).orElse(null); }
    public VettingJobVO getRun(String projectId,String id) { projectService.require(projectId); return runRepository.findById(id).filter(r -> projectId.equals(r.getProjectId())).map(this::jobVO).orElseThrow(() -> new BizException(4102,"审查任务不存在")); }

    private void executeRun(String id,String projectId,String lang,List<SourceDocument> snapshot,CompletableFuture<RunResultVO> done,VettingServiceProbe probe) {
        Throwable originalFailure=null;String outcome="aborted",stage="rules_progress";long stageStarted=System.nanoTime(),stageOverhead=probe.overheadNanos();
        long runStarted=stageStarted,runOverhead=stageOverhead;
        try {
            progress(id,0,"rules","对可解析的招标合同条款执行规则检查。");
            stage="rule_sources_construction";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            List<SourceDocument> docs=snapshot.stream().filter(this::reviewable).collect(Collectors.toList());
            List<VettingRuleEngine.Source> sources=docs.stream().filter(this::contractSource).map(d -> new VettingRuleEngine.Source(String.valueOf(d.getId()),fileKey(d),d.getFileName(),VettingCorpus.sourceHash(d),VettingCorpus.blocks(d),d.getTextContent())).collect(Collectors.toList());
            probe.elapsed(stage,stageStarted,stageOverhead,"Original reviewable/contractSource filters and Source construction only",true);
            probe.observe("rule_sources_constructed",()->VettingServiceProbe.map("sources",sources,"ruleSourceCount",sources.size(),"reviewableDocumentCount",docs.size(),
                    "boundary","Exact original RuleEngine inputs; non-contract roles are not operative rule sources"));
            stage="rule_engine_review";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            List<VettingRuleEngine.Output> rawRules=new VettingRuleEngine().review(sources);
            probe.elapsed(stage,stageStarted,stageOverhead,"Original RuleEngine.review invocation, before observer serialization",true);
            probe.observe("rule_engine_raw_outputs",()->VettingServiceProbe.map("outputs",rawRules));
            List<Candidate> candidates=new ArrayList<>(); for(VettingRuleEngine.Output o:rawRules) candidates.add(ruleCandidate(o));
            progress(id,1,"semantic","规则检查完成，开始按主题检索语义审查片段。");
            stage="corpus_and_semantic_review";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            List<Chunk> chunks=VettingCorpusConstructionProbe.chunks(props,id,projectId,docs);
            VettingSemanticReview.Result semanticResult=JsonUtils.isBlankText(props.getVetting().getProbeDirectory())
                    ? semantic.reviewWithProgress(projectId,chunks,lang,(count,total,message) -> progress(id,count+1,total+2,"semantic",message))
                    : semantic.reviewWithProgress(id,projectId,chunks,lang,(count,total,message) -> progress(id,count+1,total+2,"semantic",message));
            probe.elapsed(stage,stageStarted,stageOverhead,"Original corpus and SemanticReview call, inclusive of its independent probes; not pure model latency",true);
            probe.observe("semantic_result_raw_candidates",()->VettingServiceProbe.map("result",semanticResult,
                    "boundary","Actual returned candidates, warnings, reviewed IDs and topic audits before service sorting/source normalization"));
            candidates.addAll(semanticResult.getCandidates());
            IdentityHashMap<Candidate,List<Integer>> originalOrdinals=new IdentityHashMap<>();
            if(probe.enabled())for(int i=0;i<candidates.size();i++)originalOrdinals.computeIfAbsent(candidates.get(i),value->new ArrayList<>()).add(i);
            probe.observe("combined_candidates_before_sort",()->VettingServiceProbe.map("candidates",candidateOrder(candidates,originalOrdinals)));
            stage="candidate_sort_dedup_risk_cap";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            candidates.sort(Comparator.comparingInt(c -> severityRank(c.getSeverity())));
            probe.observe("combined_candidates_after_sort",()->VettingServiceProbe.map("candidates",candidateOrder(candidates,originalOrdinals),"ordering","Original stable severityRank comparator"));
            List<String> warnings=new ArrayList<>(semanticResult.getWarnings()); Map<String,Candidate> unique=new LinkedHashMap<>(); int risk=0,skipped=0;
            int sortedOrdinal=0;
            for(Candidate c:candidates) {
                final int ordinal=sortedOrdinal++;final String sourceBefore=c.getSource();
                c.setSource("rule".equalsIgnoreCase(c.getSource())?"rule":"model"); String fingerprint=fingerprint(c);
                final int riskBefore=risk;
                if(unique.containsKey(fingerprint)) {
                    probe.observe("candidate_filter_decision",()->VettingServiceProbe.map("candidate",c,"originalOrdinals",originalOrdinals.get(c),"sortedOrdinal",ordinal,
                            "sourceBeforeNormalization",sourceBefore,"fingerprint",fingerprint,"decision","dropped","reason","duplicate_fingerprint",
                            "retainedCandidate",unique.get(fingerprint),"riskCounterBefore",riskBefore,"riskCounterAfter",riskBefore));
                    continue;
                }
                if("risk".equals(group(c.getType())) && risk++>=Math.max(0,props.getVetting().getMaxRiskFindings())) {
                    skipped++;final int riskAfter=risk;
                    probe.observe("candidate_filter_decision",()->VettingServiceProbe.map("candidate",c,"originalOrdinals",originalOrdinals.get(c),"sortedOrdinal",ordinal,
                            "sourceBeforeNormalization",sourceBefore,"fingerprint",fingerprint,"decision","dropped","reason","risk_cap",
                            "riskCounterBefore",riskBefore,"riskCounterAfter",riskAfter,"configuredMaxRiskFindings",props.getVetting().getMaxRiskFindings()));
                    continue;
                }
                unique.put(fingerprint,c);final int riskAfter=risk;
                probe.observe("candidate_filter_decision",()->VettingServiceProbe.map("candidate",c,"originalOrdinals",originalOrdinals.get(c),"sortedOrdinal",ordinal,
                        "sourceBeforeNormalization",sourceBefore,"fingerprint",fingerprint,"decision","retained","reason","first_unique_within_original_risk_cap",
                        "riskCounterBefore",riskBefore,"riskCounterAfter",riskAfter));
            }
            if(skipped>0) warnings.add("主观风险按严重程度排序后限制为 "+props.getVetting().getMaxRiskFindings()+" 条，另有 "+skipped+" 条未列入。");
            probe.elapsed(stage,stageStarted,stageOverhead,"Original comparator, source normalization, fingerprint dedup and postincrement risk cap; observer wall excluded",true);
            final int skippedFinal=skipped,riskFinal=risk;
            probe.observe("final_selected_candidates",()->VettingServiceProbe.map("candidatesByFingerprint",unique,"riskCounter",riskFinal,"riskSkippedCount",skippedFinal,"warnings",warnings));
            stage="coverage_construction";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            CoverageVO coverage=coverage(snapshot,chunks,semanticResult.getReviewedChunkIds(),warnings,true);
            coverage.setSemanticTopics(semanticResult.getTopicAudits());
            probe.elapsed(stage,stageStarted,stageOverhead,"Original coverage construction and semantic-topic assignment",true);
            probe.observe("coverage_constructed",()->VettingServiceProbe.map("coverage",coverage,"boundary","Submission coverage, not professional review acceptance"));
            if(!JsonUtils.isBlankText(semanticResult.getModelFailure())) {
                tx().execute(status->{VettingRun run=runRepository.findById(id).orElseThrow(()->new BizException(4101,"Review run unavailable"));run.setCoverageJson(JsonUtils.write(coverage));runRepository.saveAndFlush(run);return null;});
                throw new BizException(4101,semanticResult.getModelFailure());
            }
            progress(id,semanticResult.getPlannedCallCount()+1,semanticResult.getPlannedCallCount()+2,"saving","保存证据快照和发现，保留人工状态。");
            RunResultVO result;stage="merge_and_complete_transaction";
            synchronized(lock(projectId)) {
                stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
                result=tx().execute(status -> mergeAndComplete(id,projectId,unique,coverage,semanticResult.getModel(),probe));
                probe.elapsed(stage,stageStarted,stageOverhead,"Actual original REQUIRED tx.execute; includes own commit when no outer participant, otherwise outer commit is unobserved; lock acquisition excluded; observer wall excluded",true);
            }
            probe.observe("merge_and_complete_transaction_returned",()->VettingServiceProbe.returnedTransaction("result",result,
                    "finalRecords",probe.reference("merge_pending_commit_records"),"boundary","Original REQUIRED TransactionTemplate returned; see commit observation. Referenced records are after original saves/flush, before transaction completion; no extra DAO read."));
            done.complete(result);outcome="completed";
        } catch(Exception e) {
            originalFailure=e;probe.elapsed(stage,stageStarted,stageOverhead,"Original operation failed in this stage; observer wall excluded",false);
            probe.originalFailure("service_run_failure",e);log.error("审查任务 {} 失败",id,e);failRun(id,brief(e),done,probe);
        } catch(Error e) {
            originalFailure=e;probe.elapsed(stage,stageStarted,stageOverhead,"Original Error in this stage; original Error propagates unchanged",false);probe.originalFailure("service_run_failure",e);throw e;
        } finally {
            probe.elapsed("execute_run_total",runStarted,runOverhead,"Original background execution inclusive of original stages; observer wall excluded",originalFailure==null&&"completed".equals(outcome));
            probe.finish(outcome,originalFailure);completions.remove(id,done);serviceProbes.remove(id,probe);
        }
    }
    private List<Map<String,Object>> candidateOrder(List<Candidate> candidates,IdentityHashMap<Candidate,List<Integer>> originalOrdinals){
        List<Map<String,Object>> out=new ArrayList<>();for(int i=0;i<candidates.size();i++){Candidate c=candidates.get(i);out.add(VettingServiceProbe.map("order",i,"originalOrdinals",originalOrdinals.get(c),"candidate",c));}return out;
    }
    private List<Map<String,Object>> sourceMetadata(List<SourceDocument> documents){
        List<Map<String,Object>> out=new ArrayList<>();
        for(SourceDocument d:documents)out.add(VettingServiceProbe.map("documentId",String.valueOf(d.getId()),"projectId",d.getProjectId(),"category",d.getCategory(),
                "fileKey",fileKey(d),"fileName",d.getFileName(),"reviewRole",VettingCorpus.sourceRole(d),"parseStatus",d.getParseStatus(),"pageCount",d.getPageCount(),
                "ocrUsed",d.getOcrUsed(),"textCharsUtf16",nvl(d.getTextContent()).length(),"structuredJsonCharsUtf16",nvl(d.getStructuredContentJson()).length(),
                "boundary","Identity metadata only; full rule-source text/blocks are recorded separately. Corpus/source snapshots have their independent probe."));
        return out;
    }
    private RunResultVO mergeAndComplete(String id,String projectId,Map<String,Candidate> candidates,CoverageVO coverage,String model,VettingServiceProbe probe) {
        projectService.require(projectId); VettingRun run=runRepository.findById(id).orElseThrow(() -> new BizException(4102,"审查任务已移除"));
        if(!ACTIVE_STATES.contains(run.getStatus())) throw new BizException(4101,"任务已停止，不能覆盖结果");
        List<VettingFinding> previous=findingRepository.findByProjectIdOrderByCodeAsc(projectId);
        Map<String,Map<String,VettingFinding>> byFingerprintAndRevision=new LinkedHashMap<>(); int next=1;
        probe.observe("merge_previous_records",()->VettingServiceProbe.map("records",previous,"boundary","Original lookup before inactive marking/generated-field refresh; includes original human notes/decision/time"));
        for(VettingFinding f:previous) {
            if(!JsonUtils.isBlankText(f.getFingerprint())) {
                Map<String,VettingFinding> revisions=byFingerprintAndRevision.computeIfAbsent(f.getFingerprint(),key->new LinkedHashMap<>());
                String revision=generatedRevision(f);VettingFinding existing=revisions.get(revision);
                if(existing==null||preferExistingFinding(f,existing)) revisions.put(revision,f);
            }
            Matcher matcher=Pattern.compile("VT-(\\d+)").matcher(nvl(f.getCode())); if(matcher.matches()) next=Math.max(next,Integer.parseInt(matcher.group(1))+1);
        }
        previous.forEach(f->f.setActive(false));
        probe.observe("merge_previous_records_after_inactive_marking",()->VettingServiceProbe.map("records",previous,"committed",false));
        List<VettingFinding> current=new ArrayList<>();
        for(Map.Entry<String,Candidate> entry:candidates.entrySet()) {
            VettingFinding projection=new VettingFinding();apply(projection,entry.getValue());String revision=generatedRevision(projection);
            VettingFinding f=byFingerprintAndRevision.getOrDefault(entry.getKey(),Collections.emptyMap()).get(revision);
            boolean reused=f!=null;
            if(f==null) { f=projection; f.setProjectId(projectId); f.setCode(String.format("VT-%03d",next++)); f.setStatus(VettingFinding.STATUS_OPEN); f.setCreatedAt(Instant.now()); }
            apply(f,entry.getValue()); f.setFingerprint(entry.getKey()); f.setRunId(id); f.setActive(true); current.add(f);
            String code=f.getCode();probe.observe("finding_generated_revision_match",()->VettingServiceProbe.map("fingerprint",entry.getKey(),
                    "generatedRevision",revision,"protocol","exact-generated-fields-v1","reusedExistingRecord",reused,"code",code,
                    "boundary","Human disposition is reused only for identical generated fields and evidence metadata; this is not semantic verification"));
        }
        findingRepository.saveAll(previous); findingRepository.saveAll(current);
        RunResultVO result=new RunResultVO(current.size(),metricsOf(current),coverage.getWarnings(),model);
        result.setModelIdentity(JsonUtils.read(run.getModelIdentityJson(),com.consense.ai.ModelIdentity.class));
        run.setStatus("COMPLETED"); run.setPhase("completed"); run.setCompletedUnits(run.getTotalUnits()); run.setFinishedAt(Instant.now()); run.setMessage("审查完成，共 "+current.size()+" 条发现；覆盖与限制见报告。");
        run.setResultJson(JsonUtils.write(result)); run.setCoverageJson(JsonUtils.write(coverage)); runRepository.saveAndFlush(run);
        probe.observe("merge_pending_commit_records",()->VettingServiceProbe.map("committed",false,"run",run,"currentRecords",current,
                "findingVos",current.stream().map(this::toVO).collect(Collectors.toList()),"previousRecordsAfterMergeRefresh",previous,"result",result,
                "boundary","Actual entities after original saveAll(previous), saveAll(current), and run.saveAndFlush; enclosing transaction has not committed yet"));
        return result;
    }
    private void progress(String id,int units,String phase,String message) {
        progress(id,units,0,phase,message);
    }
    private void progress(String id,int units,int totalUnits,String phase,String message) {
        VettingServiceProbe probe=serviceProbes.get(id);long started=System.nanoTime(),overhead=probe==null?0:probe.overheadNanos();
        try {
            VettingRun changed=tx().execute(status -> { VettingRun run=runRepository.findById(id).orElseThrow(() -> new BizException(4102,"审查任务已移除"));
                if(!ACTIVE_STATES.contains(run.getStatus())) throw new BizException(4101,"任务已停止");
                if(totalUnits>0)run.setTotalUnits(totalUnits);
                run.setStatus("RUNNING"); run.setCompletedUnits(units); run.setPhase(phase); run.setMessage(truncate(message,2048)); runRepository.saveAndFlush(run); return run; });
            if(probe!=null){probe.elapsed("progress_transaction",started,overhead,"Original REQUIRED progress tx.execute; own commit or still-active outer participation as recorded",true);
                probe.observe("progress_transaction_returned",()->VettingServiceProbe.returnedTransaction("status",changed.getStatus(),"phase",changed.getPhase(),
                        "completedUnits",changed.getCompletedUnits(),"totalUnits",changed.getTotalUnits(),"message",changed.getMessage()));}
        } catch(RuntimeException|Error e){if(probe!=null){probe.elapsed("progress_transaction",started,overhead,"Original progress transaction failed",false);probe.originalFailure("progress_transaction_failure",e);}throw e;}
    }
    private void failRun(String id,String message,CompletableFuture<RunResultVO> done,VettingServiceProbe probe) {
        long started=System.nanoTime(),overhead=probe.overheadNanos();
        try {
            tx().execute(status -> { runRepository.findById(id).ifPresent(run -> { if("COMPLETED".equals(run.getStatus())) return; run.setStatus("FAILED"); run.setPhase("failed"); run.setError(truncate(message,2048)); run.setMessage(truncate(message,2048)); run.setFinishedAt(Instant.now()); runRepository.saveAndFlush(run);
                probe.observe("failed_run_pending_commit",()->VettingServiceProbe.map("run",run,"committed",false)); }); return null; });
            probe.elapsed("fail_run_transaction",started,overhead,"Original REQUIRED failure-persistence tx.execute; own commit or still-active outer participation as recorded",true);
            probe.observe("fail_run_transaction_returned",()->VettingServiceProbe.returnedTransaction("records",probe.reference("failed_run_pending_commit"),
                    "boundary","Original failure policy skips an already-completed run; missing records are not fabricated"));
        } catch(RuntimeException|Error e){probe.elapsed("fail_run_transaction",started,overhead,"Original failure persistence transaction failed",false);probe.originalFailure("fail_run_transaction_failure",e);throw e;}
        finally { done.completeExceptionally(new BizException(4101,message)); completions.remove(id,done); }
    }
    private Candidate ruleCandidate(VettingRuleEngine.Output output) {
        Candidate c=new Candidate(); c.setRuleId(output.getRuleId()); c.setType(output.getType()); c.setTitle(output.getTitle()); c.setComment(output.getSummary()); c.setSuggestion(output.getRecommendation()); c.setSeverity(output.getRisk()); c.setSource("rule");
        List<FindingEvidence> sides=new ArrayList<>(); int index=0;
        for(VettingRuleEngine.Evidence e:output.getEvidence()) sides.add(new FindingEvidence(index++==0?"source":"reference",e.getDocumentId(),e.getFileKey(),e.getFileName(),e.getPageNo()==null?null:"P"+e.getPageNo(),available(e.getAnchor(),e.getLocation()),e.getQuote(),true,e.getSourceHash(),e.getBbox()==null?null:Arrays.stream(e.getBbox()).boxed().collect(Collectors.toList())));
        c.setEvidence(sides); return c;
    }
    private void apply(VettingFinding f,Candidate c) {
        // Refresh generated observations only. Human review records are owned by updateReview.
        List<FindingEvidence> sides=c.getEvidence()==null?Collections.emptyList():c.getEvidence(); FindingEvidence primary=sides.isEmpty()?null:sides.get(0);
        String group=group(c.getType()); f.setGroupKey(group); f.setTypes(typeCode(c.getType())); f.setSeverity(normalizeSeverity(c.getSeverity())); f.setScope(sides.stream().map(FindingEvidence::getDocumentId).filter(Objects::nonNull).distinct().count()>1?"inter":"intra");
        f.setTitleZhHans(c.getTitle()); f.setTitleZhHant(c.getTitle()); f.setTitleEn(c.getTitle()); f.setBodyZhHans(c.getComment()); f.setBodyZhHant(c.getComment()); f.setBodyEn(c.getComment());
        f.setImpactZhHans(c.getImpact()); f.setImpactZhHant(c.getImpact()); f.setImpactEn(c.getImpact()); f.setSuggestionZhHans(c.getSuggestion()); f.setSuggestionZhHant(c.getSuggestion()); f.setSuggestionEn(c.getSuggestion());
        f.setSource(c.getSource()); f.setEvidenceJson(JsonUtils.write(sides)); long located=sides.stream().filter(FindingEvidence::isLocated).count();
        f.setVerification(sides.isEmpty()||located==0?"unverified":located==sides.size()?"verified":"partial"); f.setEvidenceId("verified".equals(f.getVerification())?"snapshot:"+nvl(primary.getDocumentId()):EVIDENCE_UNVERIFIED);
        f.setFileKey(primary==null?"OTHER":truncate(available(primary.getFileKey(),"OTHER"),16)); f.setPageNo(primary==null?null:truncate(primary.getPageNo(),16)); f.setPatternText(primary==null?null:truncate(primary.getQuote(),512));
        f.setRefs(truncate(sides.stream().map(FindingEvidence::getAnchor).filter(Objects::nonNull).distinct().collect(Collectors.joining(" / ")),512));
        f.setLocation(truncate(sides.stream().map(e -> e.getFileName()+" · "+available(e.getPageNo(),nvl(e.getAnchor()))).collect(Collectors.joining("; ")),512)); f.setExpected(null); f.setBucketKey("reference".equals(group)?"missing":"language".equals(group)?"grammar":"particulars");
    }
    static String fingerprint(Candidate c) {
        List<String> sides=(c.getEvidence()==null?Collections.<FindingEvidence>emptyList():c.getEvidence()).stream().map(e -> nvl(e.getFileName()).toLowerCase(Locale.ROOT)+"|"+nvl(e.getSourceHash())+"|"+nvl(e.getAnchor())+"|"+VettingCorpus.normalize(e.getQuote())).sorted().collect(Collectors.toList());
        String legacy=nvl(c.getRuleId())+"\n"+String.join("\n",sides);
        if("rule".equalsIgnoreCase(c.getSource()))return VettingCorpus.hash(legacy);
        boolean anyPacket=!JsonUtils.isBlankText(c.getPacketId())||!JsonUtils.isBlankText(c.getSourceSnapshotSha256())
                ||(c.getEvidence()!=null&&c.getEvidence().stream().anyMatch(e->!JsonUtils.isBlankText(e.getPacketId())||!JsonUtils.isBlankText(e.getPacketSourceSnapshotSha256())));
        if(!anyPacket)return VettingCorpus.hash(legacy);
        if(JsonUtils.isBlankText(c.getPacketId())||!nvl(c.getSourceSnapshotSha256()).matches("[a-f0-9]{64}")||!c.getPacketId().endsWith(c.getSourceSnapshotSha256())
                ||c.getEvidence()==null||c.getEvidence().isEmpty()||c.getEvidence().stream().anyMatch(e->!e.isLocated()||!Objects.equals(c.getPacketId(),e.getPacketId())||!Objects.equals(c.getSourceSnapshotSha256(),e.getPacketSourceSnapshotSha256())))throw new IllegalArgumentException("Model candidate packet/source provenance is incomplete or inconsistent");
        return VettingCorpus.hash(legacy+"\npacket_scope_v1|"+c.getPacketId()+"|"+c.getSourceSnapshotSha256());
    }
    /** Exact persisted generated content, excluding every human-owned and run identity field. */
    static String generatedRevision(VettingFinding f) {
        return VettingCorpus.hash(JsonUtils.write(Arrays.asList("exact-generated-fields-v1",f.getGroupKey(),f.getTypes(),f.getSeverity(),f.getScope(),
                f.getTitleZhHans(),f.getTitleZhHant(),f.getTitleEn(),f.getBodyZhHans(),f.getBodyZhHant(),f.getBodyEn(),
                f.getImpactZhHans(),f.getImpactZhHant(),f.getImpactEn(),f.getSuggestionZhHans(),f.getSuggestionZhHant(),f.getSuggestionEn(),
                f.getSource(),f.getEvidenceJson(),f.getVerification(),f.getEvidenceId(),f.getFileKey(),f.getPageNo(),f.getPatternText(),
                f.getRefs(),f.getLocation(),f.getExpected(),f.getBucketKey())));
    }
    private static boolean preferExistingFinding(VettingFinding candidate,VettingFinding existing) {
        boolean candidateActive=Boolean.TRUE.equals(candidate.getActive()),existingActive=Boolean.TRUE.equals(existing.getActive());
        if(candidateActive!=existingActive)return candidateActive;
        Instant candidateTime=candidate.getCreatedAt()==null?Instant.EPOCH:candidate.getCreatedAt();
        Instant existingTime=existing.getCreatedAt()==null?Instant.EPOCH:existing.getCreatedAt();
        int timeOrder=candidateTime.compareTo(existingTime);if(timeOrder!=0)return timeOrder>0;
        return candidate.getId()!=null&&(existing.getId()==null||candidate.getId()>existing.getId());
    }
    private CoverageVO coverage(List<SourceDocument> snapshot,List<Chunk> chunks,Set<String> reviewed,List<String> extra,boolean rulesScanned) {
        List<String> warnings=new ArrayList<>(extra); List<DocumentCoverageVO> documents=new ArrayList<>(); int processed=0;
        if(snapshot.stream().noneMatch(d -> "BQ".equals(fileKey(d)) && reviewable(d) && contractSource(d))) warnings.add("未提供可解析的 Bills of Quantities，不能确认数量、提交范围及金额协调。");
        if(snapshot.stream().noneMatch(d -> "GS".equals(fileKey(d)) && reviewable(d) && contractSource(d))) warnings.add("未提供可解析的 General Summary，无法核对汇总金额。");
        warnings.add(rulesScanned?"规则扫描全部可解析招标合同条款；标准文件仅作参考对照，项目事实和文件目录仅作上下文，均未当作实际合同执行规则检查。语义覆盖仅计实际成功的模型片段，未检索片段不代表完成语义审查。":"任务尚未完成规则和语义审查，当前覆盖仅反映源文件解析状态。");
        if(props.getVetting().isDiffAgainstBaseline()) warnings.add("本次未执行标准模板差异过滤，不能解读为只审查项目改动内容。");
        for(SourceDocument d:snapshot) {
            List<String> notes=parseWarnings(d); boolean usable=reviewable(d); if(excluded(d.getFileName())) notes.add("评语、清单或问答参考被排除，避免答案泄漏。");
            List<Chunk> parts=chunks.stream().filter(c -> String.valueOf(d.getId()).equals(c.getDocumentId())).collect(Collectors.toList()); List<Chunk> checked=parts.stream().filter(c -> reviewed.contains(c.getId())).collect(Collectors.toList());
            int chars=nvl(d.getTextContent()).length(),covered=Math.min(chars,reviewedChars(d,checked));
            notes.add("资料类型：" + roleLabel(VettingCorpus.sourceRole(d)).getZhHans());
            if(usable) notes.add(rulesScanned?(contractSource(d)?"规则已扫描全部可解析招标合同正文。":
                    "standard".equals(VettingCorpus.sourceRole(d))?"标准文件仅作参考对照，规则未当作实际合同执行。":"项目事实或文件目录不作为有效合同条款执行规则扫描。" ):"规则和语义审查尚未完成。");
            if(usable && rulesScanned && checked.size()<parts.size()) notes.add("语义仅覆盖 "+checked.size()+"/"+parts.size()+" 片段，其他片段未进入成功的模型调用。");
            if(usable && !checked.isEmpty()) processed++;
            documents.add(new DocumentCoverageVO(String.valueOf(d.getId()),fileKey(d),d.getFileName(),d.getParseStatus(),chars,covered,parts.size(),checked.size(),notes));
        } return new CoverageVO(snapshot.size(),processed,documents,new ArrayList<>(new LinkedHashSet<>(warnings)));
    }

    /** Count the union of source block ranges; overlapping model chunks do not inflate coverage. */
    static int reviewedChars(SourceDocument document,List<Chunk> checked) {
        List<DocumentBlock> blocks=VettingCorpus.blocks(document); Map<String,List<int[]>> ranges=new LinkedHashMap<>();
        Map<String, Integer> blockIndices = new HashMap<>(); Set<String> ambiguousIds = new HashSet<>();
        for (int index = 0; index < blocks.size(); index++) {
            String id = blocks.get(index).getId();
            if (id == null || id.isEmpty()) continue;
            if (blockIndices.putIfAbsent(id, index) != null) ambiguousIds.add(id);
        }
        for(Chunk c:checked) for(VettingCorpus.Part p:c.getParts()==null?Collections.<VettingCorpus.Part>emptyList():c.getParts()) {
            if (!JsonUtils.isBlankText(p.getBlockId())) {
                Integer index = blockIndices.get(p.getBlockId());
                if (index == null || ambiguousIds.contains(p.getBlockId())) continue;
                String text = nvl(blocks.get(index).getText()); int start = p.getStartOffset(), end = p.getEndOffset();
                if (start < 0 || end < start || end > text.length() || !text.substring(start, end).equals(p.getText())) continue;
                ranges.computeIfAbsent(String.valueOf(index), k -> new ArrayList<>()).add(new int[]{start, end});
                continue;
            }
            for(int index=0;index<blocks.size();index++) {
                DocumentBlock b=blocks.get(index); String text=nvl(b.getText()),location=nvl(b.getLocation()),anchor=nvl(p.getAnchor()); int start=0;
                if(text.isEmpty()||location.isEmpty()) continue;
                if(anchor.endsWith(location)) { if(!text.equals(p.getText())) continue; }
                else { Matcher offset=Pattern.compile(Pattern.quote(location)+" @(\\d+)$").matcher(anchor); if(!offset.find()) continue; start=Integer.parseInt(offset.group(1));
                    if(start>text.length()||!text.startsWith(nvl(p.getText()),start)) continue; }
                ranges.computeIfAbsent(String.valueOf(index),k -> new ArrayList<>()).add(new int[]{start,Math.min(text.length(),start+nvl(p.getText()).length())}); break;
            }
        }
        int total=0; for(List<int[]> intervals:ranges.values()) {
            intervals.sort(Comparator.comparingInt(a -> a[0])); int start=-1,end=-1;
            for(int[] a:intervals) { if(start<0) {start=a[0];end=a[1];} else if(a[0]<=end) end=Math.max(end,a[1]); else {total+=end-start;start=a[0];end=a[1];} }
            if(start>=0) total+=end-start;
        } return total;
    }
    private List<SourceDocument> sourceSet(String projectId) {
        Map<String,SourceDocument> latest=new LinkedHashMap<>();
        for(SourceDocument d:sourceDocumentRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if(!SourceDocument.CATEGORY_VETTING_PACKAGE.equals(d.getCategory()) && !SourceDocument.CATEGORY_VETTING_SUPPLEMENT.equals(d.getCategory())) continue;
            if(JsonUtils.isBlankText(d.getFileKey())) d.setFileKey(matchFileKey(d.getFileName())); latest.put(d.getCategory()+"|"+nvl(d.getFileName()).toLowerCase(Locale.ROOT),d);
        }
        List<SourceDocument> out=new ArrayList<>(latest.values()); out.sort(Comparator.comparingInt(d -> {int at=FILE_ORDER.indexOf(fileKey(d));return at<0?FILE_ORDER.size():at;})); return out;
    }
    private boolean reviewable(SourceDocument d) { return ("PARSED".equals(d.getParseStatus())||"PARTIAL".equals(d.getParseStatus())) && !JsonUtils.isBlankText(d.getTextContent()) && !excluded(d.getFileName()); }
    private boolean contractSource(SourceDocument d) { return "tender".equals(VettingCorpus.sourceRole(d)); }
    private static String validatedRole(String role) {
        String value = nvl(role).trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "auto".equals(value)) return null;
        if (Arrays.asList("tender", "standard", "project_fact", "package_manifest").contains(value)) return value;
        throw new BizException(4101, "资料类型必须为 auto、tender、standard、project_fact 或 package_manifest");
    }
    private static LocalizedText roleLabel(String role) {
        if ("standard".equals(role)) return LocalizedText.of("标准参考文件", "標準參考文件", "Standard reference");
        if ("project_fact".equals(role)) return LocalizedText.of("项目事实资料", "項目事實資料", "Project facts");
        if ("package_manifest".equals(role)) return LocalizedText.of("文件目录及范围清单", "文件目錄及範圍清單", "Package manifest");
        return LocalizedText.of("招标合同条款", "招標合同條款", "Tender provisions");
    }
    private boolean excluded(String name) { String s=nvl(name).toLowerCase(Locale.ROOT).replace('_',' '); return s.contains("vetting comment")||s.contains("checklist")||s.contains("q&a")||s.contains("q & a")||s.contains("contractual advice")||s.contains("answer sheet")||s.contains("questions and answers"); }
    private List<String> parseWarnings(SourceDocument d) {
        List<String> out=new ArrayList<>();
        if(!JsonUtils.isBlankText(d.getParseCoverageJson())) try { ParseCoverage c=JsonUtils.read(d.getParseCoverageJson(),ParseCoverage.class); out.addAll(c.getLimitations()); if(!c.getFailedPages().isEmpty()) out.add("未完成解析的物理页："+c.getFailedPages()); if(!c.isComplete()) out.add("文档解析不完整，未解析部分不纳入审查。"); }
        catch(Exception e) { out.add("解析覆盖记录无法读取，请重新解析。"); }
        else out.add("旧文件缺结构和覆盖记录，仅使用字符锚点，不推测Word页码。");
        if(Boolean.TRUE.equals(d.getOcrUsed())) out.add("OCR 不识别删除线、表格行列关系及图形含义；引文、删除状态和表格对应关系须对照原始物理页复核。");
        if(!"PARSED".equals(d.getParseStatus())&&!"PARTIAL".equals(d.getParseStatus())) out.add("没有可用的已解析正文："+nvl(d.getParseMessage())); return out;
    }

    public List<FindingVO> listFindings(String projectId,String search,String group,String scope,String fileKey,String page) {
        projectService.require(projectId); String keyword=nvl(search).trim().toLowerCase(Locale.ROOT);
        return activeFindings(projectId).stream().filter(f -> filter(group,f.getGroupKey())).filter(f -> filter(scope,f.getScope()))
                .filter(f -> filter(fileKey,f.getFileKey())||readEvidence(f).stream().anyMatch(e -> filter(fileKey,e.getFileKey())))
                .filter(f -> filter(page,f.getPageNo())||readEvidence(f).stream().anyMatch(e -> filter(page,e.getPageNo())))
                .filter(f -> keyword.isEmpty()||searchable(f).toLowerCase(Locale.ROOT).contains(keyword)).map(this::toVO).collect(Collectors.toList());
    }
    public MetricsVO metrics(String projectId) { projectService.require(projectId); return metricsOf(activeFindings(projectId)); }
    public FindingVO updateStatus(String projectId,String code,String status) {
        projectService.require(projectId); String normalized;
        if("open".equalsIgnoreCase(status)) normalized=VettingFinding.STATUS_OPEN; else if("handled".equalsIgnoreCase(status)) normalized=VettingFinding.STATUS_HANDLED; else if("assigned".equalsIgnoreCase(status)) normalized=VettingFinding.STATUS_ASSIGNED; else throw new BizException(4102,"状态必须为 Open、Handled 或 Assigned");
        synchronized(lock(projectId)) { return tx().execute(tx -> { VettingFinding f=findingRepository.findByProjectIdAndCode(projectId,code).orElseThrow(() -> new BizException(4102,"审查发现不存在")); f.setStatus(normalized); findingRepository.saveAndFlush(f); return toVO(f); }); }
    }
    public FindingVO updateReview(String projectId,String code,ReviewUpdateRequest request) {
        projectService.require(projectId);
        if(request==null) throw new BizException(4102,"人工审查记录不能为空请求体");
        String remarks=request.getReviewRemarks(),action=request.getActionTaken();
        Boolean decision=request.getAddendumRequired();
        // Validate the complete replacement before mutating any persisted field. Never trim user text.
        if(remarks!=null && remarks.length()>4000)
            throw new BizException(4102,"项目团队备注不能超过 4000 字符");
        if(action!=null && action.length()>4000)
            throw new BizException(4102,"实际处理记录不能超过 4000 字符");
        synchronized(lock(projectId)) { return tx().execute(tx -> {
            VettingFinding finding=findingRepository.findByProjectIdAndCode(projectId,code)
                    .orElseThrow(() -> new BizException(4102,"审查发现不存在"));
            finding.setReviewRemarks(remarks);
            finding.setActionTaken(action);
            finding.setAddendumRequired(decision);
            finding.setReviewUpdatedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            findingRepository.saveAndFlush(finding);
            return toVO(finding);
        }); }
    }
    public EvidenceVO evidence(String projectId,String code) {
        projectService.require(projectId); VettingFinding f=findingRepository.findByProjectIdAndCode(projectId,code).orElseThrow(() -> new BizException(4102,"审查发现不存在"));
        List<FindingEvidence> saved=readEvidence(f); List<EvidenceItemVO> items=new ArrayList<>();
        for(FindingEvidence e:saved) items.add(new EvidenceItemVO(nvl(e.getFileKey())+" · "+nvl(e.getFileName()),e.getPageNo(),e.getQuote(),e.getSide(),e.getDocumentId(),e.getFileKey(),e.getFileName(),e.getAnchor(),e.getQuote(),e.isLocated(),e.getSourceHash(),e.getBbox(),e.getPacketId(),e.getPacketSourceSnapshotSha256()));
        return new EvidenceVO(code+" · "+nvl(f.getRefs()),!saved.isEmpty()&&saved.stream().allMatch(FindingEvidence::isLocated),items);
    }
    /** Original upload for visual review. A parsed source revision is distinct from the raw file digest. */
    public OriginalSource originalSource(String projectId,long documentId,String expectedSourceHash) {
        projectService.require(projectId);
        SourceDocument d=sourceDocumentRepository.findById(documentId).filter(source -> projectId.equals(source.getProjectId())
                && (SourceDocument.CATEGORY_VETTING_PACKAGE.equals(source.getCategory()) || SourceDocument.CATEGORY_VETTING_SUPPLEMENT.equals(source.getCategory())))
                .orElseThrow(() -> new BizException(4102,"审查原始文件不存在"));
        String revision=VettingCorpus.sourceHash(d);
        if(JsonUtils.isBlankText(expectedSourceHash)||!revision.equals(expectedSourceHash))
            throw new BizException(4102,"原文版本与证据快照不一致，请刷新审查结果");
        if(JsonUtils.isBlankText(d.getStoragePath())) throw new BizException(4102,"审查原始文件不可用");
        try {
            Path root=Paths.get(props.getStorageRoot()).toAbsolutePath().normalize().toRealPath();
            Path path=Paths.get(d.getStoragePath()).toAbsolutePath().normalize().toRealPath();
            if(!path.startsWith(root)||!Files.isRegularFile(path)) throw new BizException(4102,"审查原始文件不可用");
            return new OriginalSource(path,d.getFileName(),Files.size(path),revision);
        } catch(java.io.IOException | java.nio.file.InvalidPathException e) { throw new BizException(4102,"审查原始文件不可用"); }
    }
    @lombok.Value public static class OriginalSource { Path path; String fileName; long size; String sourceRevision; }
    public byte[] exportPdf(String projectId,String lang) { return exportObserved(projectId,lang,"pdf"); }
    public byte[] exportDocx(String projectId,String lang) { return exportObserved(projectId,lang,"docx"); }
    public byte[] exportJson(String projectId,String lang) { return exportObserved(projectId,lang,"json"); }
    private byte[] exportObserved(String projectId,String lang,String format) {
        VettingExportProbe probe=VettingExportProbe.start(props,projectId,format,lang);boolean returnedNormally=false;Throwable operationFailure=null;
        try {
            ReportContext c;long started=System.nanoTime();
            try{c=reportContext(projectId);}
            catch(RuntimeException|Error e){probe.originalFailureElapsed("report_context_lookup_failed_elapsed",e,System.nanoTime()-started);throw e;}
            long contextElapsed=System.nanoTime()-started;
            probe.bindRun(c.job.getId());
            probe.event("report_context_lookup_elapsed",DocumentParseProbe.map("wallNanos",contextElapsed,"seconds",contextElapsed/1_000_000_000.0,
                    "boundary","Actual unchanged reportContext lookup and payload DTO construction, measured before probe I/O"));
            probe.event("report_payload",DocumentParseProbe.map("project",c.project,"job",c.job,"findings",c.findings));
            byte[] bytes;
            if("pdf".equals(format))bytes=probe.enabled()?reportWriter.pdf(c.project,c.findings,c.job,lang,probe):reportWriter.pdf(c.project,c.findings,c.job,lang);
            else if("docx".equals(format))bytes=probe.enabled()?reportWriter.docx(c.project,c.findings,c.job,lang,probe):reportWriter.docx(c.project,c.findings,c.job,lang);
            else {
                Map<String,Object> out;
                try(VettingExportProbe.Timer timer=probe.measure("report_json_payload_construction","Original JSON report DTO/map construction")){
                    out=new LinkedHashMap<>();out.put("schemaVersion","1.0");out.put("language",normalizeLang(lang));out.put("project",projectService.toVO(c.project));out.put("job",c.job);out.put("findings",c.findings);
                }
                try(VettingExportProbe.Timer timer=probe.measure("report_json_serialization","Original JsonUtils.write and UTF8 encoding of returned JSON report")){bytes=JsonUtils.write(out).getBytes(StandardCharsets.UTF_8);}
            }
            probe.returned(bytes);returnedNormally=true;return bytes;
        }catch(RuntimeException|Error e){operationFailure=e;probe.originalFailure("report_export_failure",e);throw e;}
        finally{probe.finish(returnedNormally?"returned_normally":"aborted",operationFailure);}
    }
    private ReportContext reportContext(String projectId) {
        projectService.require(projectId); synchronized(lock(projectId)) { return tx().execute(status -> {
            Project p=projectService.require(projectId); VettingRun run=runRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId).orElseThrow(() -> new BizException(4103,"尚未完成审查，无法导出报告"));
            if(!"COMPLETED".equals(run.getStatus())) throw new BizException(4103,"最近审查任务尚未成功完成，请等待或重新运行");
            List<FindingVO> findings=activeFindings(projectId).stream().filter(f -> run.getId().equals(f.getRunId())).map(this::toVO).collect(Collectors.toList()); return new ReportContext(p,jobVO(run),findings);
        }); }
    }
    public String pdfFileName(String id) { return reportFileName(id,"pdf"); }
    public String reportFileName(String id,String format) { return "ConSense_Vetting_Report_"+nvl(projectService.require(id).getContractNo()).replaceAll("[\\\\/:*?\"<>|]","_")+"."+format; }
    private static final class ReportContext { final Project project; final VettingJobVO job; final List<FindingVO> findings; ReportContext(Project p,VettingJobVO j,List<FindingVO> f){project=p;job=j;findings=f;} }
    private VettingJobVO jobVO(VettingRun r) { VettingJobVO job=new VettingJobVO(r.getId(),r.getStatus(),r.getPhase(),r.getCompletedUnits(),r.getTotalUnits(),r.getMessage(),r.getStartedAt(),r.getFinishedAt(),JsonUtils.read(r.getResultJson(),RunResultVO.class),r.getError(),JsonUtils.read(r.getCoverageJson(),CoverageVO.class));job.setModelIdentity(JsonUtils.read(r.getModelIdentityJson(),com.consense.ai.ModelIdentity.class));return job; }
    private List<VettingFinding> activeFindings(String id) { return findingRepository.findByProjectIdOrderByCodeAsc(id).stream().filter(f -> !Boolean.FALSE.equals(f.getActive())).collect(Collectors.toList()); }
    private List<FindingEvidence> readEvidence(VettingFinding f) { try{return JsonUtils.readList(f.getEvidenceJson(),FindingEvidence.class);}catch(Exception e){log.warn("发现 {} 证据快照不可读",f.getCode());return Collections.emptyList();} }
    private FindingVO toVO(VettingFinding f) {
        List<FindingEvidence> e=readEvidence(f); long located=e.stream().filter(FindingEvidence::isLocated).count(); String v=e.isEmpty()||located==0?"unverified":located==e.size()?"verified":"partial";
        return new FindingVO(f.getCode(),JsonUtils.isBlankText(f.getTypes())?Collections.emptyList():Arrays.asList(f.getTypes().split(",")),f.getGroupKey(),f.getScope(),f.getSeverity(),f.getStatus(),LocalizedText.of(f.getTitleZhHans(),f.getTitleZhHant(),f.getTitleEn()),LocalizedText.of(f.getBodyZhHans(),f.getBodyZhHant(),f.getBodyEn()),LocalizedText.of(f.getImpactZhHans(),f.getImpactZhHant(),f.getImpactEn()),LocalizedText.of(f.getSuggestionZhHans(),f.getSuggestionZhHant(),f.getSuggestionEn()),f.getPatternText(),f.getRefs(),f.getLocation(),f.getExpected(),"verified".equals(v)?f.getEvidenceId():EVIDENCE_UNVERIFIED,f.getFileKey(),f.getPageNo(),f.getBucketKey(),f.getFingerprint(),f.getRunId(),f.getSource(),v,e,f.getReviewRemarks(),f.getActionTaken(),f.getAddendumRequired(),f.getReviewUpdatedAt());
    }
    private MetricsVO metricsOf(List<VettingFinding> f){return new MetricsVO(count(f,"reference"),count(f,"conflict"),count(f,"language"),count(f,"risk"),f.size(),(int)f.stream().filter(v -> "inter".equals(v.getScope())).count());}
    private int count(List<VettingFinding> f,String group){return (int)f.stream().filter(v -> group.equals(v.getGroupKey())).count();}
    private String searchable(VettingFinding f){return Stream.of(f.getCode(),f.getTitleZhHans(),f.getTitleEn(),f.getBodyZhHans(),f.getBodyEn(),f.getRefs(),f.getLocation(),f.getPatternText(),f.getEvidenceJson()).filter(Objects::nonNull).collect(Collectors.joining(" "));}
    private boolean filter(String v,String a){return JsonUtils.isBlankText(v)||"all".equalsIgnoreCase(v)||v.equalsIgnoreCase(nvl(a));}
    private Object lock(String id){return locks.computeIfAbsent(id,k -> new Object());}
    private TransactionTemplate tx(){return new TransactionTemplate(transactionManager);}
    private int topicCount(){return Math.min(VettingSemanticReview.maxTopics(),Math.max(0,props.getVetting().getSemanticTopics()));}
    private String normalizeLang(String lang){return "en".equalsIgnoreCase(lang)?"en":"zh-Hant".equalsIgnoreCase(lang)?"zh-Hant":"zh-Hans";}
    private String group(String type){if(Arrays.asList("reference","conflict","language","risk").contains(type))return type;if("f2-iii".equals(type)||"f2-iv".equals(type))return "reference";if(Arrays.asList("f2-i","f2-vi","f2-viii").contains(type))return "conflict";if("f2-v".equals(type)||"f2-ix".equals(type))return "language";return "risk";}
    private String typeCode(String type){return nvl(type).startsWith("f2-")?type:"reference".equals(type)?"f2-iii":"conflict".equals(type)?"f2-viii":"language".equals(type)?"f2-ix":"f2-ii";}
    private int severityRank(String value){String s=normalizeSeverity(value);return "high".equals(s)?0:"medium".equals(s)?1:2;}
    private String normalizeSeverity(String s){String v=nvl(s).toLowerCase(Locale.ROOT);return Arrays.asList("high","medium","low").contains(v)?v:"medium";}
    private String fileKey(SourceDocument d){return JsonUtils.isBlankText(d.getFileKey())?matchFileKey(d.getFileName()):d.getFileKey();}
    static String matchFileKey(String filename) {
        String n=nvl(filename).toUpperCase(Locale.ROOT).replace('_',' '); Matcher ap=Pattern.compile("\\b(AP[A-Z])\\b").matcher(n); if(ap.find())return ap.group(1);
        if(n.contains("BILL")&&n.contains("QUANTIT")||n.matches(".*\\b(?:BQ|BOQ)\\b.*"))return "BQ";
        if(n.contains("GENERAL SUMMARY"))return "GS";
        if(n.contains("APPENDIX")){Matcher a=Pattern.compile("APPENDIX\\s+([A-Z]\\d{0,3})").matcher(n);if(a.find())return "APP-"+a.group(1);}
        if(n.matches(".*\\bFOT\\b.*")||n.contains("FORM OF TENDER"))return "FT";if(n.matches(".*\\bAOA\\b.*")||n.contains("ARTICLES OF AGREEMENT"))return "AA";
        if(n.matches(".*\\bCOC\\b.*")||n.contains("GENERAL CONDITIONS OF CONTRACT"))return "GCC";if(n.contains("GENERAL CONDITIONS OF TENDER"))return "GCT";
        if(n.contains("SPECIFICATION LIBRARY"))return "SL";if(n.contains("SPECIAL CONDITIONS OF CONTRACT"))return "SCC";if(n.contains("SPECIAL CONDITIONS OF TENDER")||n.contains("SPECIAL CONDITION OF TENDER"))return "SCT";if(n.contains("NOTES TO TENDERER"))return "NTT";
        for(String key:FILE_ORDER)if(Pattern.compile("\\b"+key+"\\b").matcher(n).find())return key;if(n.contains("PRELIMINAR"))return "PRE";if(n.contains("SPECIFICATION"))return "SP";return "OTHER";
    }
    private static String truncate(String v,int max){return v==null||v.length()<=max?v:v.substring(0,max);}
    private static String available(String v,String fallback){return JsonUtils.isBlankText(v)?fallback:v;}
    private static String nvl(String v){return v==null?"":v;}
    private static String brief(Throwable e){return e==null?"Unknown error":truncate(available(e.getMessage(),e.getClass().getSimpleName()),2048);}
}
