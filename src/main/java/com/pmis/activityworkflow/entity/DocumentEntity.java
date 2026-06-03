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

    /* ----- comment ----- */

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    /* ----- timestamps ----- */

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;
}