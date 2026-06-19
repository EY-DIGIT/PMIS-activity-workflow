package com.pmis.activityworkflow.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * One reviewer in a parallel-approval gate. The (business_service, activity_id,
 * state_name, approver_user_uuid) tuple is unique — a single user can only
 * be a reviewer once for a given record at a given state.
 *
 * <p>Lifecycle of a row:
 * <ol>
 *   <li>Created when the record enters a parallel state, with
 *       {@code voteStatus = PENDING}, {@code notifyStatus = PENDING}.</li>
 *   <li>NotificationClient sends the external notification, then flips
 *       {@code notifyStatus} to SENT or FAILED.</li>
 *   <li>The reviewer votes — service flips {@code voteStatus} to
 *       APPROVED or REJECTED and stamps {@code votedAt}.</li>
 *   <li>If the gate is re-evaluated (after a reject + re-submit), rows
 *       whose status is already APPROVED stay untouched ("skipped"
 *       in the diagram). PENDING rows remain. The REJECTED row is
 *       reset to PENDING and re-notified.</li>
 * </ol>
 */
@Entity
@Table(name = "aw_parallel_participant",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_participant_record_state_user",
               columnNames = {"business_service", "activity_id", "state_name", "approver_user_uuid"}),
       indexes = {
           @Index(name = "idx_participant_lookup",
                  columnList = "business_service,activity_id,state_name"),
           @Index(name = "idx_participant_vote_status", columnList = "vote_status"),
           @Index(name = "idx_participant_user",        columnList = "approver_user_uuid"),
           @Index(name = "idx_participant_division",    columnList = "division_code")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ParallelParticipantEntity {

    @Id
    @Column(name = "uuid", nullable = false, length = 64)
    @EqualsAndHashCode.Include
    private String uuid;

    /* ----- what record + which gate ----- */

    @Column(name = "business_service", nullable = false, length = 256)
    private String businessService;

    @Column(name = "activity_id", nullable = false, length = 256)
    private String activityId;

    /** The parallel state this participant belongs to (e.g. PENDINGATCONCERNEDDIVISION). */
    @Column(name = "state_name", nullable = false, length = 256)
    private String stateName;

    /* ----- the reviewer ----- */

    @Column(name = "approver_user_uuid", nullable = false, length = 64)
    private String approverUserUuid;

    @Column(name = "approver_email", length = 256)
    private String approverEmail;

    @Column(name = "approver_name", length = 256)
    private String approverName;

    /** Optional — caller can attach project correlation ID. */
    @Column(name = "project_id", length = 256)
    private String projectId;

    /** Which division this approver represents (e.g. "tmd2", "admin"). */
    @Column(name = "division_code", length = 128)
    private String divisionCode;

    /** Human-readable division name. Optional. */
    @Column(name = "division_name", length = 256)
    private String divisionName;

    /* ----- vote ----- */

    /** PENDING / APPROVED / REJECTED / SKIPPED. */
    @Column(name = "vote_status", nullable = false, length = 16)
    private String voteStatus;

    @Column(name = "vote_comment", length = 1024)
    private String voteComment;

    @Column(name = "voted_at")
    private Long votedAt;

    /* ----- notification ----- */

    /** PENDING / SENT / FAILED — set by NotificationClient. */
    @Column(name = "notify_status", nullable = false, length = 16)
    private String notifyStatus;

    @Column(name = "notify_attempted_at")
    private Long notifyAttemptedAt;

    @Column(name = "notify_error", length = 1024)
    private String notifyError;

    /* ----- audit ----- */

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    /**
     * Epoch-ms when the last pending-approval reminder email was sent to this
     * approver. Null until the first reminder fires. Used by the reminder
     * scheduler to enforce the 30-day re-send window.
     */
    @Column(name = "last_reminder_sent_at")
    private Long lastReminderSentAt;
}
