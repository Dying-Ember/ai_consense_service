package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.repository.CanonicalSourceBindingRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.VettingCorpus;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Fresh committed source checks, including after asset HTTP; never inherit a chat's persistence context. */
@Service @RequiredArgsConstructor
public class CanonicalCurrentSourceReader {
    private final SourceDocumentRepository documents;
    private final CanonicalSourceBindingRepository bindings;
    private final ConsenseProperties properties;

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public SourceDocument currentDocument(String projectId,Long id,String module){return readDocument(projectId,id,module);}

    /** Registration has not saved its new adoption yet; verify committed source and original only. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void assertSourceCurrent(CanonicalSourceBinding binding,String module){checkSource(binding,module);}

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void assertCurrent(CanonicalSourceBinding binding,String module){
        checkSource(binding,module);
        List<CanonicalSourceBinding> current=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(binding.getProjectId(),binding.getDocumentId());
        require(!current.isEmpty()&&Objects.equals(current.get(0).getProjectionSha256(),binding.getProjectionSha256())
                &&Objects.equals(current.get(0).getSnapshotBytesSha256(),binding.getSnapshotBytesSha256()),
                HttpStatus.CONFLICT,"CANONICAL_ADOPTION_SUPERSEDED");
    }

    private void checkSource(CanonicalSourceBinding binding,String module){
        SourceDocument document=readDocument(binding.getProjectId(),binding.getDocumentId(),module,true);
        require(Objects.equals(binding.getOriginalSourceSha256(),document.getSourceSha256())
                &&Objects.equals(binding.getJavaParsedSourceHash(),VettingCorpus.sourceHash(document))
                &&Objects.equals(binding.getRoutingSha256(),CanonicalEvidenceService.routingHash(document)),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        com.fasterxml.jackson.databind.JsonNode eligibility;
        try{eligibility=JsonUtils.parse(binding.getEligibleModulesJson());}catch(RuntimeException invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
        require(eligibility.isArray()&&eligibility.size()>0,HttpStatus.BAD_GATEWAY,"CANONICAL_ELIGIBILITY_INVALID");
        Set<String> declared=new HashSet<>();for(com.fasterxml.jackson.databind.JsonNode item:eligibility)
            require(item.isTextual()&&Arrays.asList("drafting","vetting","advisory").contains(item.asText())&&declared.add(item.asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_ELIGIBILITY_INVALID");
        require(declared.contains(module),HttpStatus.FORBIDDEN,"CANONICAL_MODULE_NOT_ELIGIBLE");
    }

    private SourceDocument readDocument(String projectId,Long id,String module){
        return readDocument(projectId,id,module,false);
    }
    private SourceDocument readDocument(String projectId,Long id,String module,boolean verificationOnly){
        require(Arrays.asList("drafting","vetting","advisory").contains(module),HttpStatus.BAD_REQUEST,"CANONICAL_MODULE_INVALID");
        // Vetting/advisory registration consumes the same complete verification fields.
        // Drafting keeps its ordinary entity path; native archives stay stored unchanged.
        SourceDocument document=verificationOnly||!"drafting".equals(module)?verificationDocument(id):documents.findById(id).orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE"));
        require(Objects.equals(projectId,document.getProjectId()),HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE");
        require(!SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(document.getCategory())||"drafting".equals(module),HttpStatus.FORBIDDEN,"SOURCE_TEMPLATE_DRAFTING_ONLY");
        OerDocumentIdentity identity;
        try{identity=OerSourceMetadata.identity(document);}catch(IllegalArgumentException invalid){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ROUTING_STALE");}
        require(identity!=null&&identity.getEligibleModules()!=null&&identity.getEligibleModules().contains(module)
                &&OerSourceMetadata.moduleExclusion(document,module)==null,HttpStatus.FORBIDDEN,"SOURCE_MODULE_ELIGIBILITY_UNRESOLVED_OR_EXCLUDED");
        if(!"drafting".equals(module))require(ProjectDocumentScope.exclusionReason(document,"fullset",module)==null,HttpStatus.FORBIDDEN,"SOURCE_MODULE_EXCLUDED");
        List<SourceDocument> catalog=new ArrayList<>();
        for(SourceDocumentRepository.CatalogMetadata metadata:documents.findCatalogMetadataByProjectId(projectId)){
            // Nonpersistent shells preserve currentCatalog semantics without hydrating unrelated LOBs.
            SourceDocument entry=new SourceDocument();entry.setId(metadata.getId());entry.setProjectId(metadata.getProjectId());
            entry.setCategory(metadata.getCategory());entry.setFileKey(metadata.getFileKey());entry.setFileName(metadata.getFileName());
            catalog.add(entry);
        }
        List<SourceDocument> current="drafting".equals(module)?catalog:ProjectDocumentScope.currentCatalog(projectId,catalog);
        for(SourceDocument candidate:current)if(Objects.equals(normalizedName(candidate),normalizedName(document))&&candidate.getId()!=null&&candidate.getId()>id)
            throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_DOCUMENT_SUPERSEDED");
        require(document.getSourceSha256()!=null&&document.getSourceSha256().matches("[a-f0-9]{64}"),HttpStatus.BAD_REQUEST,"CANONICAL_SHA_INVALID");
        try{
            Path root=Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize().toRealPath();
            Path original=Paths.get(document.getStoragePath()).toAbsolutePath().normalize().toRealPath();
            require(original.startsWith(root)&&Files.isRegularFile(original),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream stream=Files.newInputStream(original)){byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1)hash.update(buffer,0,n);}
            StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format("%02x",b&255));
            require(document.getSourceSha256().equals(hex.toString()),HttpStatus.CONFLICT,"SOURCE_ORIGINAL_BYTES_CHANGED");
        }catch(CanonicalEvidenceException e){throw e;}catch(Exception error){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ORIGINAL_UNAVAILABLE");}
        return document;
    }
    private SourceDocument verificationDocument(Long id){
        org.springframework.util.Assert.notNull(id,"The given id must not be null!");
        SourceDocumentRepository.VerificationMetadata metadata=documents.findVerificationMetadataById(id)
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"SOURCE_DOCUMENT_UNAVAILABLE"));
        // Detached scalar snapshot only; all shared source gates consume these exact committed fields.
        SourceDocument document=new SourceDocument();document.setId(metadata.getId());document.setProjectId(metadata.getProjectId());
        document.setCategory(metadata.getCategory());document.setFileKey(metadata.getFileKey());document.setFileName(metadata.getFileName());
        document.setReviewRole(metadata.getReviewRole());document.setDocumentScope(metadata.getDocumentScope());document.setParseStatus(metadata.getParseStatus());
        document.setOerIdentityJson(metadata.getOerIdentityJson());document.setSourceSha256(metadata.getSourceSha256());document.setStoragePath(metadata.getStoragePath());
        document.setTextContent(metadata.getTextContent());document.setStructuredContentJson(metadata.getStructuredContentJson());return document;
    }
    private static String normalizedName(SourceDocument document){return document.getFileName()==null?"":document.getFileName().trim().toLowerCase(Locale.ROOT);}
    private static void require(boolean condition,HttpStatus status,String reason){if(!condition)throw new CanonicalEvidenceException(status,reason);}
}
