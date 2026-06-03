package com.pmis.activityworkflow.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * One row per state transition for a given {@code activityId}.
 *
 * The table is append-only — every action fired creates a new row. The
 * "current state" for a business record is the row with the highest
 * created_time for that activityId/businessService.
 */
@Entity
@Table(name = "aw_process_instance",
       indexes = {
           @Index(name = "idx_pi_activity_id",      columnList = "activity_id"),
           @Index(name = "idx_pi_project_id",       columnList = "project_id"),
           @Index(name = "idx_pi_business_service", columnList = "business_service"),
           @Index(name = "idx_pi_lookup",           columnList = "business_service,activity_id,created_time")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProcessInstanceEntity {

    @Id
    @Column(name = "uuid", nullable = false, length = 64)
    @EqualsAndHashCode.Include
    private String uuid;

    /** Workflow definition this instance belongs to — references aw_activity.activity_name. */
    @Column(name = "business_service", nullable = false, length = 256)
    private String businessService;

    /** The actual record being moved (e.g. "TL-TEST-1"). */
    @Column(name = "activity_id", nullable = false, length = 256)
    private String activityId;

    /** Parent project — one project has many activities. */
    @Column(name = "project_id", length = 256)
    private String projectId;

    /** Free-form module identifier from the caller (e.g. "tl-services"). */
    @Column(name = "module_name", length = 256)
    private String moduleName;

    @Column(name = "current_state", length = 256)
    private String currentState;

    @Column(name = "previous_state", length = 256)
    private String previousState;

    /** Name of the action that produced this transition. Null on the initial seed row. */
    @Column(name = "action_name", length = 256)
    private String actionName;

    @Column(name = "comment", length = 1024)
    private String comment;

    @Column(name = "assignee", length = 64)
    private String assignee;

    @Column(name = "sla", nullable = false)
    private Long sla;

    @Column(name = "is_terminate_state", nullable = false)
    private Boolean terminateState;

    /** Roles permitted on the action that produced this transition. */
    @Column(name = "roles", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> roles;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "createdBy",        column = @Column(name = "created_by")),
            @AttributeOverride(name = "createdTime",      column = @Column(name = "created_time")),
            @AttributeOverride(name = "lastModifiedBy",   column = @Column(name = "last_modified_by")),
            @AttributeOverride(name = "lastModifiedTime", column = @Column(name = "last_modified_time"))
    })
    private AuditDetails auditDetails;
}
