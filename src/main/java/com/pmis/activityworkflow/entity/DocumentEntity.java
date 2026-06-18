package com.pmis.activityworkflow.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * One upload reference. The file itself lives in the upstream comments
 * service — we only persist the upstream comment id + uploader/comment
 * context so we can list "who uploaded what for which activity".
 *
 * <p>Fields we deliberately do NOT store anymore:
 * fileName / contentType / fileSize / fileUrl / store_id — the upstream
 * comment id is enough to fetch any of that on demand.</p>
 */
@Entity
@Table(name = "aw_document",
       indexes = {
           @Index(name = "idx_doc_activity_id",  columnList = "activity_id"),
           @Index(name = "idx_doc_project_id",   columnList = "project_id"),
           @Index(name = "idx_doc_uploaded_by",  columnList = "uploaded_by_uuid"),
           @Index(name = "idx_doc_created_at",   columnList = "created_at")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DocumentEntity {

    @Id
    @Column(name = "uuid", nullable = false, length = 64)
    @EqualsAndHashCode.Include
    private String uuid;

    /* ----- upstream reference ----- */

    /** Upstream comment id (e.g. "35d154e9-1a33-409d-9426-383a4c71aad3"). */
    @Column(name = "doc_id", nullable = false, length = 256)
    private String docId;

    /* ----- workflow correlation ----- */

    @Column(name = "activity_id", length = 256)
    private String activityId;

    @Column(name = "project_id", length = 256)
    private String projectId;

    @Column(name = "business_service", length = 256)
    private String businessService;

    @Column(name = "process_instance_id", length = 64)
    private String processInstanceId;

    /** Free-form tag — e.g. "DIVISION_APPROVAL_REQUEST", "OWNER_APPROVAL_REQUEST". */
    @Column(name = "document_type", length = 128)
    private String documentType;

    /* ----- uploader ----- */

    @Column(name = "uploaded_by_uuid", nullable = false, length = 64)
    private String uploadedByUuid;

    @Column(name = "uploaded_by_username", length = 256)
    private String uploadedByUsername;

    @Column(name = "uploaded_by_email", length = 256)
    private String uploadedByEmail;

    @Column(name = "uploaded_by_roles", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> uploadedByRoles;

    /**
     * Division this document belongs to (e.g. "tmd-i", "TMD-II", "OWNER").
     * Null for legacy flat uploads where there was no per-division isolation.
     */
    @Column(name = "division_code", length = 128)
    private String divisionCode;

    /**
     * UUID of the division reviewer this document is intended for.
     * Set from {@code divisionApprovals[].userUuid} in the division-approval
     * request. Used by the inbox to filter: user X sees only documents
     * where {@code reviewer_uuid = X}.
     */
    @Column(name = "reviewer_uuid", length = 64)
    private String reviewerUuid;

    /**
     * High-level category: {@code CONCERNED_DIVISION} for documents uploaded
     * for a concerned-division reviewer; {@code OWNER_DIVISION} for documents
     * uploaded for the owner-division approver.
     * Null for legacy flat uploads.
     */
    @Column(name = "document_category", length = 64)
    private String documentCategory;

    /* ----- file metadata (populated when a file is attached; null for comment-only uploads) ----- */

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "file_url", length = 2048)
    private String fileUrl;

    /* ----- comment ----- */

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    /* ----- timestamps ----- */

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;
}
