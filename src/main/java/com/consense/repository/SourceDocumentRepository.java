package com.consense.repository;

import com.consense.domain.SourceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.time.Instant;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, Long> {

    List<SourceDocument> findByProjectIdAndCategoryOrderByIdAsc(String projectId, String category);

    List<SourceDocument> findByProjectIdOrderByIdAsc(String projectId);

    /** Supersession catalog only; never hydrate source payloads or eligibility metadata. */
    interface CatalogMetadata {
        Long getId();
        String getProjectId();
        String getCategory();
        String getFileKey();
        String getFileName();
    }

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName from SourceDocument d "
            + "where d.projectId = :projectId order by d.id asc")
    List<CatalogMetadata> findCatalogMetadataByProjectId(@Param("projectId") String projectId);

    /** File display and scope inputs only. Complete native archives/structure are not listing inputs. */
    interface FileDisplayMetadata extends CatalogMetadata {
        String getReviewRole();
        String getDocumentScope();
        String getParseStatus();
        String getParseMessage();
        String getOerIdentityJson();
        String getSourceSha256();
        Integer getPageCount();
        Boolean getOcrUsed();
        String getTextContent();
        String getParseCoverageJson();
    }

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.parseMessage as parseMessage, "
            + "d.oerIdentityJson as oerIdentityJson, d.sourceSha256 as sourceSha256, "
            + "d.pageCount as pageCount, d.ocrUsed as ocrUsed, d.textContent as textContent, "
            + "d.parseCoverageJson as parseCoverageJson from SourceDocument d "
            + "where d.projectId = :projectId order by d.id asc")
    List<FileDisplayMetadata> findFileDisplayMetadataByProjectId(@Param("projectId") String projectId);

    /** Complete business inputs; the retained native archive is not consumed by these readers. */
    interface BusinessSourceMetadata extends FileDisplayMetadata {
        String getStoragePath();
        String getStructuredContentJson();
        String getContentType();
        Long getSizeBytes();
        Instant getCreatedAt();
    }

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.parseMessage as parseMessage, "
            + "d.oerIdentityJson as oerIdentityJson, d.sourceSha256 as sourceSha256, "
            + "d.pageCount as pageCount, d.ocrUsed as ocrUsed, d.textContent as textContent, "
            + "d.parseCoverageJson as parseCoverageJson, d.storagePath as storagePath, "
            + "d.structuredContentJson as structuredContentJson, d.contentType as contentType, "
            + "d.sizeBytes as sizeBytes, d.createdAt as createdAt from SourceDocument d "
            + "where d.projectId = :projectId order by d.id asc")
    List<BusinessSourceMetadata> findBusinessSourceMetadataByProjectId(@Param("projectId") String projectId);

    /** Detached, exact complete business snapshots. Never save these archive-free read inputs. */
    default List<SourceDocument> findBusinessSourcesByProjectId(String projectId) {
        List<SourceDocument> result=new ArrayList<>();
        for(BusinessSourceMetadata metadata:findBusinessSourceMetadataByProjectId(projectId)) {
            SourceDocument d=new SourceDocument();d.setId(metadata.getId());d.setProjectId(metadata.getProjectId());d.setCategory(metadata.getCategory());
            d.setFileKey(metadata.getFileKey());d.setFileName(metadata.getFileName());d.setReviewRole(metadata.getReviewRole());d.setDocumentScope(metadata.getDocumentScope());
            d.setParseStatus(metadata.getParseStatus());d.setParseMessage(metadata.getParseMessage());d.setOerIdentityJson(metadata.getOerIdentityJson());d.setSourceSha256(metadata.getSourceSha256());
            d.setPageCount(metadata.getPageCount());d.setOcrUsed(metadata.getOcrUsed());d.setTextContent(metadata.getTextContent());d.setParseCoverageJson(metadata.getParseCoverageJson());
            d.setStoragePath(metadata.getStoragePath());d.setStructuredContentJson(metadata.getStructuredContentJson());d.setContentType(metadata.getContentType());
            d.setSizeBytes(metadata.getSizeBytes());d.setCreatedAt(metadata.getCreatedAt());result.add(d);
        }
        return result;
    }

    /** Exact fresh verification inputs; complete text/structure hashes remain independent of native archives. */
    interface VerificationMetadata extends CatalogMetadata {
        String getReviewRole();
        String getDocumentScope();
        String getParseStatus();
        String getOerIdentityJson();
        String getSourceSha256();
        String getStoragePath();
        String getTextContent();
        String getStructuredContentJson();
    }

    @Query("select d.id as id, d.projectId as projectId, d.category as category, "
            + "d.fileKey as fileKey, d.fileName as fileName, d.reviewRole as reviewRole, "
            + "d.documentScope as documentScope, d.parseStatus as parseStatus, d.oerIdentityJson as oerIdentityJson, "
            + "d.sourceSha256 as sourceSha256, d.storagePath as storagePath, d.textContent as textContent, "
            + "d.structuredContentJson as structuredContentJson from SourceDocument d where d.id = :id")
    Optional<VerificationMetadata> findVerificationMetadataById(@Param("id") Long id);

    /** Compare-and-set routing only; never merge an archive-free read snapshot into a managed entity. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SourceDocument d set d.oerIdentityJson = :identity where d.id = :id "
            + "and d.projectId = :projectId and d.sourceSha256 = :originalSha "
            + "and ((d.oerIdentityJson is null and :previousIdentity is null) or d.oerIdentityJson = :previousIdentity)")
    int compareAndSetRoutingIdentity(@Param("id") Long id, @Param("projectId") String projectId,
            @Param("originalSha") String originalSha, @Param("previousIdentity") String previousIdentity,
            @Param("identity") String identity);

    Optional<SourceDocument> findFirstByProjectIdAndFileKey(String projectId, String fileKey);

    Optional<SourceDocument> findFirstByProjectIdAndFileKeyAndCategory(String projectId, String fileKey, String category);

    @Modifying
    @Query("delete from SourceDocument d where d.projectId = :projectId and d.category in :categories")
    void deleteByProjectIdAndCategories(@Param("projectId") String projectId,
                                        @Param("categories") List<String> categories);

    @Modifying
    @Query("delete from SourceDocument d where d.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
