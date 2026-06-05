package com.pmis.activityworkflow.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * Append-only audit trail. ONE row per transition attempt — including
 * attempts that failed validation, role checks, or hit an invalid action.
 *
 * <p>This is distinct from {@code aw_process_instance}, which only records
 * successful state changes. The audit table answers "who tried to do what,
 * when, from where, and what happened" for every single request.</p>
 */
@Entity
@Table(name = "aw_workflow_audit",
       indexes = {
           @Index(name = "idx_audit_activity_id",      columnList = "activity_id"),
           @Index(name = "idx_audit_project_id",       columnList = "project_id"),
           @Index(name = "idx_audit_business_service",  columnList = "business_service"),
           @Index(name = "idx_audit_outcome",           columnList = "outcome"),
           @Index(name = "idx_audit_created_time",      columnList = "created_time"),
           @Index(name = "idx_audit_performed_by",      columnList = "performed_by_uuid")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class WorkflowAuditEntity {

    @Id
    @Column(name = "uuid", nullable = false, length = 64)
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "business_service", length = 256)
    private String businessService;

    @Column(name = "activity_id", length = 256)
    private String activityId;

    /** Parent project — one project has many activities. */
    @Column(name = "project_id", length = 256)
    private String projectId;

    @Column(name = "module_name", length = 256)
    private String moduleName;

    /** The action that was attempted (e.g. "APPROVE"). */
    @Column(name = "action_name", length = 256)
    private String actionName;

    /** State the record was in before the attempt. Null if it couldn't be determined. */
    @Column(name = "previous_state", length = 256)
    private String previousState;

    /** State the record moved to. Null on failed attempts. */
    @Column(name = "resultant_state", length = 256)
    private String resultantState;

    /** SUCCESS or FAILED. */
    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    /** Populated only when outcome = FAILED. */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /* ---- who ---- */

    @Column(name = "performed_by_uuid", length = 64)
    private String performedByUuid;

    @Column(name = "performed_by_username", length = 256)
    private String performedByUsername;

    /** The caller's actual roles at the time of the attempt. */
    @Column(name = "performed_by_roles", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> performedByRoles;

    @Column(name = "comment", length = 2048)
    private String comment;

    /* ---- where (best effort) ---- */

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** Full request envelope as JSON, with authToken redacted. */
//    @Column(name = "request_payload", columnDefinition = "text")
//    private String requestPayload;

    /* ---- when ---- */

    @Column(name = "created_time", nullable = false)
    private Long createdTime;
}
