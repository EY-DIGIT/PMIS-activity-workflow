package com.pmis.activityworkflow.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * One uploaded artifact. The file itself lives in your external storage
 * (e.g. file-store, S3); this row keeps the references plus who/when/why.
 *
 * <p>One comment per document — embedded directly on the row, no separate
 * comment table.</p>
 */
@Entity
@Table(name = "aw_document",
       indexes = {
           @Index(name = "idx_doc_activity_id",  columnList = "activity_id"),
           @Index(name = "idx_doc_project_id",   columnList = "project_id"),
           @Index(name = "idx_doc_activity_id",  columnList = "activity_id"),
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

    /* ----- external storage refs (returned by the file-store API) ----- */

    @Column(name = "doc_id", nullable = false, length = 256)
    private String docId;

    @Column(name = "store_id", length = 256)
    private String storeId;

    @Column(name = "file_url", length = 1024)
    private String fileUrl;

    /* ----- file metadata (recorded at upload time) ----- */

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    /** Free-form tag — e.g. "PROOF_OF_DELIVERY", "INVOICE". */
    @Column(name = "document_type", length = 128)
    private String documentType;

    /* ----- workflow correlation ----- */

    @Column(name = "project_id", length = 256)
    private String projectId;

    @Column(name = "activity_id", length = 256)
    private String activityId;

    @Column(name = "business_service", length = 256)
    private String businessService;

    @Column(name = "process_instance_id", length = 64)
    private String processInstanceId;

    /* ----- uploader ----- */

    @Column(name = "uploaded_by_uuid", nullable = false, length = 64)
    private String uploadedByUuid;

    @Column(name = "uploaded_by_username", length = 256)
    private String uploadedByUsername;

    @Column(name = "uploaded_by_roles", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> uploadedByRoles;

    /* ----- comment (one per document) ----- */

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    /* ----- timestamps ----- */

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;
}
