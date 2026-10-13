package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.VettingCorpus;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Refresh reviewed original identity without reparsing, archive hydration, or changing user decisions. */
@Service @RequiredArgsConstructor
public class OerSourceRoutingRefreshService {
    private final SourceDocumentRepository documents;
    private final ConsenseProperties properties;

    @Data public static class Result {
        private String projectId, originalSourceSha256, javaParsedSourceHash, previousRoutingSha256, routingSha256;
        private Long documentId;
        private OerDocumentIdentity identity;
        private boolean changed;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
    public Result refresh(String projectId, Long id, String expectedOriginalSha, String expectedParsedHash, String expectedRoutingHash) {
        require(validSha(expectedOriginalSha)&&validSha(expectedParsedHash)&&validSha(expectedRoutingHash),
                HttpStatus.BAD_REQUEST,"SOURCE_REFRESH_HASH_INVALID");
        SourceDocument before=snapshot(id);
        require(Objects.equals(projectId,before.getProjectId()),HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE");
        require(ProjectDocumentScope.reviewCategory(before),HttpStatus.FORBIDDEN,"SOURCE_REFRESH_REVIEW_ONLY");
        require(ProjectDocumentScope.parseUsable(before),HttpStatus.CONFLICT,"SOURCE_PARSE_UNAVAILABLE");
        require(Objects.equals(expectedOriginalSha,before.getSourceSha256())
                &&Objects.equals(expectedParsedHash,VettingCorpus.sourceHash(before))
                &&Objects.equals(expectedRoutingHash,CanonicalEvidenceService.routingHash(before)),
                HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        try { OerSourceMetadata.identity(before); }
        catch(RuntimeException invalid) { throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ROUTING_STALE"); }
        assertCurrent(before);
        verifyOriginal(before);
        OerDocumentIdentity identity=OerSourceMetadata.refreshedFixedIdentity(before,expectedOriginalSha);
        require("sha_verified".equals(identity.getIdentityStatus())&&identity.getEligibleModules()!=null,
                HttpStatus.FORBIDDEN,"SOURCE_ROUTING_IDENTITY_UNRESOLVED");
        require(Objects.equals(before.getFileKey(),identity.getKind()),HttpStatus.CONFLICT,"SOURCE_ROUTING_KIND_CHANGED");
        require(identity.getEligibleModules().contains("vetting")&&identity.getEligibleModules().contains("advisory"),
                HttpStatus.FORBIDDEN,"SOURCE_MODULE_ELIGIBILITY_UNRESOLVED_OR_EXCLUDED");
        SourceDocument proposed=snapshot(id);
        requireSameSnapshot(before,proposed);
        proposed.setOerIdentityJson(JsonUtils.write(identity));
        require(ProjectDocumentScope.exclusionReason(proposed,"fullset","vetting")==null
                &&ProjectDocumentScope.exclusionReason(proposed,"fullset","advisory")==null,
                HttpStatus.FORBIDDEN,"SOURCE_MODULE_EXCLUDED");
        String next=proposed.getOerIdentityJson();
        boolean changed=!Objects.equals(before.getOerIdentityJson(),next);
        if(changed) require(documents.compareAndSetRoutingIdentity(id,projectId,expectedOriginalSha,before.getOerIdentityJson(),next)==1,
                HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        SourceDocument after=snapshot(id);
        require(Objects.equals(after.getOerIdentityJson(),next),HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
        // The update owns the row lock. A concurrent earlier content/role edit cannot pass this check.
        after.setOerIdentityJson(before.getOerIdentityJson());
        requireSameSnapshot(before,after);
        after.setOerIdentityJson(next);
        assertCurrent(after);
        verifyOriginal(after);
        Result result=new Result();result.setProjectId(projectId);result.setDocumentId(id);
        result.setOriginalSourceSha256(expectedOriginalSha);result.setJavaParsedSourceHash(expectedParsedHash);
        result.setPreviousRoutingSha256(expectedRoutingHash);result.setRoutingSha256(CanonicalEvidenceService.routingHash(after));
        result.setIdentity(identity);result.setChanged(changed);return result;
    }

    private SourceDocument snapshot(Long id) {
        require(id!=null,HttpStatus.BAD_REQUEST,"SOURCE_DOCUMENT_ID_REQUIRED");
        SourceDocumentRepository.VerificationMetadata m=documents.findVerificationMetadataById(id)
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE"));
        SourceDocument d=new SourceDocument();d.setId(m.getId());d.setProjectId(m.getProjectId());d.setCategory(m.getCategory());
        d.setFileName(m.getFileName());d.setFileKey(m.getFileKey());d.setReviewRole(m.getReviewRole());d.setDocumentScope(m.getDocumentScope());
        d.setParseStatus(m.getParseStatus());d.setSourceSha256(m.getSourceSha256());d.setStoragePath(m.getStoragePath());
        d.setOerIdentityJson(m.getOerIdentityJson());d.setTextContent(m.getTextContent());d.setStructuredContentJson(m.getStructuredContentJson());
        return d;
    }

    private void assertCurrent(SourceDocument d) {
        List<SourceDocument> catalog=new ArrayList<>();
        for(SourceDocumentRepository.CatalogMetadata m:documents.findCatalogMetadataByProjectId(d.getProjectId())) {
            SourceDocument s=new SourceDocument();s.setId(m.getId());s.setProjectId(m.getProjectId());s.setCategory(m.getCategory());
            s.setFileName(m.getFileName());s.setFileKey(m.getFileKey());catalog.add(s);
        }
        require(ProjectDocumentScope.currentCatalog(d.getProjectId(),catalog).stream().anyMatch(s->Objects.equals(s.getId(),d.getId())),
                HttpStatus.CONFLICT,"SOURCE_DOCUMENT_SUPERSEDED");
    }

    private void verifyOriginal(SourceDocument d) {
        try {
            Path root=Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize().toRealPath();
            Path original=Paths.get(d.getStoragePath()).toAbsolutePath().normalize().toRealPath();
            require(original.startsWith(root)&&Files.isRegularFile(original),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream stream=Files.newInputStream(original)){byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1)hash.update(buffer,0,count);}
            StringBuilder digest=new StringBuilder();for(byte b:hash.digest())digest.append(String.format("%02x",b&255));
            require(Objects.equals(d.getSourceSha256(),digest.toString()),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_BYTES_CHANGED");
        }catch(CanonicalEvidenceException failure){throw failure;}
        catch(Exception failure){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");}
    }

    private static void requireSameSnapshot(SourceDocument a,SourceDocument b) {
        require(Objects.equals(a.getId(),b.getId())&&Objects.equals(a.getProjectId(),b.getProjectId())
                &&Objects.equals(a.getCategory(),b.getCategory())&&Objects.equals(a.getFileName(),b.getFileName())
                &&Objects.equals(a.getFileKey(),b.getFileKey())&&Objects.equals(a.getReviewRole(),b.getReviewRole())
                &&Objects.equals(a.getDocumentScope(),b.getDocumentScope())&&Objects.equals(a.getParseStatus(),b.getParseStatus())
                &&Objects.equals(a.getStoragePath(),b.getStoragePath())&&Objects.equals(a.getSourceSha256(),b.getSourceSha256())
                &&Objects.equals(a.getOerIdentityJson(),b.getOerIdentityJson())&&Objects.equals(a.getTextContent(),b.getTextContent())
                &&Objects.equals(a.getStructuredContentJson(),b.getStructuredContentJson()),HttpStatus.CONFLICT,"SOURCE_REFRESH_SNAPSHOT_STALE");
    }
    private static boolean validSha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static void require(boolean condition,HttpStatus status,String reason){if(!condition)throw new CanonicalEvidenceException(status,reason);}
}
