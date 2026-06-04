package com.pmis.activityworkflow.service.notification;

/**
 * Kinds of workflow notifications. Each one maps to a template key
 * resolved by {@link NotificationTemplateService}.
 */
public enum NotificationEvent {

    /** Concerned-division reviewer is asked to approve (first time). */
    APPROVAL_REQUESTED,

    /** Concerned-division reviewer is asked to approve again after a re-submission. */
    APPROVAL_RESUBMITTED,

    /** Concerned-division reviewer rejected — informs the requester. */
    REJECTED_BY_REVIEWER,

    /** All concerned-division reviewers approved — informs the owner. */
    READY_FOR_OWNER_REVIEW,

    /** Owner approved — informs the requester. */
    OWNER_APPROVED,

    /** Owner rejected — informs the requester. */
    OWNER_REJECTED,

    /**
     * Owner explicitly sent the activity back to the concerned divisions
     * for re-evaluation (not a full restart). Notifies the requester +
     * every concerned-division approver so they know to re-vote.
     */
    OWNER_RETURNED_TO_DIVISIONS,

    /** Workflow reached terminal state. */
    COMPLETED
}
