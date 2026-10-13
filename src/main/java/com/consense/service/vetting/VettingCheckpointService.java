package com.consense.service.vetting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.domain.VettingRun;
import com.consense.domain.VettingPacketCheckpoint;
import com.consense.repository.VettingRunRepository;
import com.consense.repository.VettingPacketCheckpointRepository;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.consense.web.dto.VettingDtos.ReliablePacketVO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** SQL owns publication; JVM locks and a restarted worker cannot confer ownership. */
@Service @RequiredArgsConstructor
public class VettingCheckpointService {
    static final long LEASE_SECONDS=120;
    static final int MAX_MODEL_ATTEMPTS=2;
    private final VettingRunRepository runs;
    private final VettingPacketCheckpointRepository packets;
    private final PlatformTransactionManager transactionManager;

    public boolean claim(String id,String owner) {
        return tx().execute(status->{VettingRun run=runs.lockById(id).orElse(null);Instant now=Instant.now();
            if(run==null||!active(run)||run.getLeaseUntil()!=null&&run.getLeaseUntil().isAfter(now))return false;
            run.setExecutionOwner(owner);run.setLeaseUntil(now.plusSeconds(LEASE_SECONDS));runs.saveAndFlush(run);return true;});
    }
    public void renew(String id,String owner) { tx().execute(status->{owned(id,owner);return null;}); }
    public void release(String id,String owner) { tx().execute(status->{runs.lockById(id).ifPresent(run->{if(owner.equals(run.getExecutionOwner())){run.setExecutionOwner(null);run.setLeaseUntil(Instant.now());runs.saveAndFlush(run);}});return null;}); }
    /** Must be called in the same transaction as progress/final publication. */
    public VettingRun owned(String id,String owner) {
        VettingRun run=runs.lockById(id).orElseThrow(()->new BizException(4102,"审查任务已移除"));
        if(!active(run)||!owner.equals(run.getExecutionOwner())||run.getLeaseUntil()==null||!run.getLeaseUntil().isAfter(Instant.now()))
            throw new BizException(4101,"审查执行权已失效，旧执行者不能保存或发布结果");
        run.setLeaseUntil(Instant.now().plusSeconds(LEASE_SECONDS));return run;
    }
    public Session session(String runId,String owner){return session(runId,owner,()->{});}
    public Session session(String runId,String owner,Runnable assertSourcesCurrent){return new Session(runId,owner,assertSourcesCurrent);}
    public final class Session {
        private final String runId,owner;
        private final Runnable assertSourcesCurrent;
        private Session(String runId,String owner,Runnable assertSourcesCurrent){this.runId=runId;this.owner=owner;this.assertSourcesCurrent=assertSourcesCurrent;}
        public Saved loadOrBegin(SemanticPacketVO packet) {
            assertSourcesCurrent.run();
            return tx().execute(status->{owned(runId,owner);String observation=observation(packet);
                VettingPacketCheckpoint row=packets.findByRunIdAndObservationSha256(runId,observation).orElse(null);
                if(row!=null){compatible(row,packet);if("SAVED".equals(row.getStatus()))return verified(row);
                    if(row.getResultJson()!=null){SemanticPacketVO history=payload(row).getPacket();packet.setAttempts(history.getAttempts());packet.setActualGatewayCallStarted(history.isActualGatewayCallStarted());}
                    packet.setCheckpointStatus("retrying_uncertain_packet");
                }else{row=new VettingPacketCheckpoint();row.setId(UUID.randomUUID().toString());row.setRunId(runId);row.setObservationSha256(observation);
                    row.setPacketId(packet.getPacketId());row.setSourceSnapshotSha256(packet.getSourceSnapshotSha256());row.setPromptEnvelopeSha256(packet.getPromptEnvelopeSha256());packet.setCheckpointStatus("new_packet");}
                row.setStatus("CALLING");row.setUpdatedAt(Instant.now());packets.saveAndFlush(row);return null;});
        }
        /** Commit consumption before entering the gateway; a crash cannot restore the allowance. */
        public void reserveAttempt(SemanticPacketVO packet) {
            assertSourcesCurrent.run();
            tx().execute(status->{owned(runId,owner);
                VettingPacketCheckpoint row=packets.findByRunIdAndObservationSha256(runId,observation(packet)).orElseThrow(()->new BizException(4101,"审查检查点不存在"));
                compatible(row,packet);int consumed=row.getResultJson()==null?0:payload(row).getPacket().getAttempts().size();
                if("SAVED".equals(row.getStatus())||consumed>=MAX_MODEL_ATTEMPTS||packet.getAttempts().size()!=consumed+1)throw new BizException(4101,"审查来源包模型尝试次数已耗尽或不兼容");
                Saved saved=new Saved();saved.setPacket(packet);String json=JsonUtils.write(saved);row.setResultJson(json);row.setResultSha256(VettingCorpus.hash(json));row.setStatus("CALLING");row.setUpdatedAt(Instant.now());packets.saveAndFlush(row);return null;});
        }
        public void saveAttempt(SemanticPacketVO packet) {Saved saved=new Saved();saved.setPacket(packet);save(saved);}
        public void save(Saved saved) {
            assertSourcesCurrent.run();
            tx().execute(status->{owned(runId,owner);SemanticPacketVO packet=saved.getPacket();
                VettingPacketCheckpoint row=packets.findByRunIdAndObservationSha256(runId,observation(packet)).orElseThrow(()->new BizException(4101,"审查检查点不存在"));
                compatible(row,packet);if("SAVED".equals(row.getStatus()))return null;
                String payload=JsonUtils.write(saved);row.setResultJson(payload);row.setResultSha256(VettingCorpus.hash(payload));
                row.setStatus(completed(packet)?"SAVED":"FAILED");row.setUpdatedAt(Instant.now());packets.saveAndFlush(row);return null;});
        }
    }
    public List<ReliablePacketVO> reliable(String runId) {
        return packets.findByRunIdOrderByUpdatedAtAsc(runId).stream().filter(row->"SAVED".equals(row.getStatus())).map(row->{Saved saved=verified(row);
            ReliablePacketVO out=new ReliablePacketVO();out.setPacket(saved.getPacket());out.setCandidates(saved.getCandidates());out.setWarnings(saved.getWarnings());out.setSavedAt(row.getUpdatedAt());out.setResultSha256(row.getResultSha256());return out;}).collect(Collectors.toList());
    }
    private static Saved payload(VettingPacketCheckpoint row){
        if(row.getResultJson()==null||!VettingCorpus.hash(row.getResultJson()).equals(row.getResultSha256()))throw new BizException(4101,"审查检查点校验失败，需要重新运行");
        Saved saved=JsonUtils.read(row.getResultJson(),Saved.class);compatible(row,saved.getPacket());
        List<com.consense.web.dto.VettingDtos.SemanticAttemptVO> attempts=saved.getPacket().getAttempts();
        if(attempts==null||attempts.size()>MAX_MODEL_ATTEMPTS)throw new BizException(4101,"审查尝试账本无效，需要重新运行");
        for(int i=0;i<attempts.size();i++)if(attempts.get(i)==null||attempts.get(i).getOrdinal()!=i+1)throw new BizException(4101,"审查尝试顺序无效，需要重新运行");
        return saved;
    }
    private static Saved verified(VettingPacketCheckpoint row){
        Saved saved=payload(row);
        if(!completed(saved.getPacket()))throw new BizException(4101,"审查检查点未完成，需要重新运行");return saved;
    }
    private static boolean active(VettingRun run){return "QUEUED".equals(run.getStatus())||"RUNNING".equals(run.getStatus());}
    private static boolean completed(SemanticPacketVO packet){return Arrays.asList("completed","completed_empty","completed_with_rejections").contains(packet.getStatus());}
    private static String observation(SemanticPacketVO packet){return VettingCorpus.hash(packet.getSourceObservationPacketId());}
    private static void compatible(VettingPacketCheckpoint row,SemanticPacketVO packet){
        if(packet==null||!Objects.equals(row.getPacketId(),packet.getPacketId())||!Objects.equals(row.getSourceSnapshotSha256(),packet.getSourceSnapshotSha256())||!Objects.equals(row.getPromptEnvelopeSha256(),packet.getPromptEnvelopeSha256()))
            throw new BizException(4101,"审查检查点输入或来源不兼容，需要重新运行");
    }
    private TransactionTemplate tx(){TransactionTemplate tx=new TransactionTemplate(transactionManager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);return tx;}
    @Data public static class Saved {
        private SemanticPacketVO packet;
        private List<VettingSemanticReview.Candidate> candidates=new ArrayList<>();
        private List<String> warnings=new ArrayList<>();
        private Set<String> reviewedChunkIds=new LinkedHashSet<>();
    }
}
