package com.consense.domain;

import java.time.Instant;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Immutable renderer output keyed by the exact DOCX bytes and assessed render profile. */
@Getter @Setter @Entity @org.hibernate.annotations.Immutable
@Table(name="draft_pdf_artifact")
public class DraftPdfArtifact {
    @Id @Column(length=64) private String id;
    @Column(name="docx_sha256",length=64,nullable=false) private String docxSha256;
    @Column(name="pdf_sha256",length=64,nullable=false) private String pdfSha256;
    @Column(name="render_profile_hash",length=64,nullable=false) private String renderProfileHash;
    @Lob @Column(name="pdf_bytes",columnDefinition="LONGBLOB",nullable=false) private byte[] pdfBytes;
    @Lob @Column(name="manifest_json",columnDefinition="LONGTEXT",nullable=false) private String manifestJson;
    @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
    public byte[] getPdfBytes(){return pdfBytes==null?null:pdfBytes.clone();}
    public void setPdfBytes(byte[] bytes){pdfBytes=bytes==null?null:bytes.clone();}
}
