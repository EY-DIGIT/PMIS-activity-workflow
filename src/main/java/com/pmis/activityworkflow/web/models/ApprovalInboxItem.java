package com.pmis.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row in the Approval Inbox screen. Shape matches the columns the
 * UI renders (Activity / Project / Organization / Submitted / Status / Action).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalInboxItem {

    // --- Activity column ---
    /** e.g. "A2.1" */
    private String activityDisplayCode;
    /** e.g. "API Implementation Documentation" */
    private String activityName;
    /** UUID — used to deep-link into the Review screen. */
    private String activityId;

    // --- Project column ---
    private String projectId;
    /** e.g. "Aadhaar Authentication API v3.0 Upgrade" */
    private String projectName;
    /** e.g. "PRJ-2026-018" */
    private String projectCode;

    // --- Organization column ---
    /** Vendor uuid */
    private String vendorId;
    /** Vendor display name, e.g. "TechSolutions India Pvt Ltd". */
    private String organization;

    // --- Submitted column ---
    private Long submittedAt;            // epoch ms

    // --- Status column ---
    /** From aw_parallel_participant.vote_status — PENDING / APPROVED / REJECTED. */
    private String status;

    /** The state this approval sits at — useful for the Review deep link. */
    private String stateName;

    /** The division this row belongs to. */
    private String divisionCode;
    private String divisionName;
}
