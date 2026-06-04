package com.pmis.activityworkflow.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Collaborator users attached to a division for an activity. These are
 * NOT approvers — they don't vote and don't get notified. The table
 * exists for record-keeping so you can later answer "which users from
 * division X were assigned to activity Y at state Z".
 *
 * <p>For approvers, see {@link ParallelParticipantEntity}.</p>
 */
@Entity
@Table(name = "aw_division_user",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_div_user_record_state_user",
               columnNames = {"business_service", "activity_id", "state_name",
                              "division_code", "user_uuid"}),
       indexes = {
           @Index(name = "idx_div_user_lookup",
                  columnList = "business_service,activity_id,state_name"),
           @Index(name = "idx_div_user_division", columnList = "division_code"),
           @Index(name = "idx_div_user_user",     columnList = "user_uuid")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DivisionUserEntity {

    @Id
    @Column(name = "uuid", nullable = false, length = 64)
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "business_service", nullable = false, length = 256)
    private String businessService;

    @Column(name = "activity_id", nullable = false, length = 256)
    private String activityId;

    @Column(name = "project_id", length = 256)
    private String projectId;

    @Column(name = "state_name", nullable = false, length = 256)
    private String stateName;

    @Column(name = "division_code", nullable = false, length = 128)
    private String divisionCode;

    @Column(name = "division_name", length = 256)
    private String divisionName;

    /* ----- the user ----- */

    @Column(name = "user_uuid", nullable = false, length = 64)
    private String userUuid;

    @Column(name = "user_email", length = 256)
    private String userEmail;

    @Column(name = "user_name", length = 256)
    private String userName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
