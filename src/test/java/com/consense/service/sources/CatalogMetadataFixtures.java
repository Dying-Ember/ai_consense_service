package com.consense.service.sources;

import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import java.util.ArrayList;
import java.util.List;

/** Authored metadata snapshots for repository doubles; never silently return an empty catalog. */
final class CatalogMetadataFixtures {
    private CatalogMetadataFixtures() {}
    static List<SourceDocumentRepository.CatalogMetadata> from(List<SourceDocument> documents) {
        List<SourceDocumentRepository.CatalogMetadata> result=new ArrayList<>();
        for(SourceDocument d:documents){
            final Long id=d.getId();final String project=d.getProjectId(),category=d.getCategory(),key=d.getFileKey(),name=d.getFileName();
            result.add(new SourceDocumentRepository.CatalogMetadata(){
                public Long getId(){return id;}public String getProjectId(){return project;}public String getCategory(){return category;}
                public String getFileKey(){return key;}public String getFileName(){return name;}
            });
        }
        return result;
    }
    static SourceDocumentRepository.VerificationMetadata verification(SourceDocument d){
        final Long id=d.getId();final String project=d.getProjectId(),category=d.getCategory(),key=d.getFileKey(),name=d.getFileName();
        final String role=d.getReviewRole(),scope=d.getDocumentScope(),status=d.getParseStatus(),identity=d.getOerIdentityJson();
        final String sha=d.getSourceSha256(),path=d.getStoragePath(),text=d.getTextContent(),structure=d.getStructuredContentJson();
        return new SourceDocumentRepository.VerificationMetadata(){
            public Long getId(){return id;}public String getProjectId(){return project;}public String getCategory(){return category;}
            public String getFileKey(){return key;}public String getFileName(){return name;}public String getReviewRole(){return role;}
            public String getDocumentScope(){return scope;}public String getParseStatus(){return status;}public String getOerIdentityJson(){return identity;}
            public String getSourceSha256(){return sha;}public String getStoragePath(){return path;}public String getTextContent(){return text;}
            public String getStructuredContentJson(){return structure;}
        };
    }
}
