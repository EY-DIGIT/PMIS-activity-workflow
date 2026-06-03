package com.pmis.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row in the approval inbox screen. Mirrors the columns shown to the
 * user:
 *   ACTIVITY  - displayCode + name
 *   PROJECT   - name + projectCode
 *   ORG       - the activity's vendor, matched against the project's vendors
 *   SUBMITTED - when SUBMIT was fired
 *   STATUS    - the current approver's vote_status
 *
 * <p>Everything is best-effort — if an upstream call fails for one row,
 * we still return it with the fields we could resolve and a {@code warning}
 * note.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InboxRow {

    /* ---- identifiers ---- */

    private String activityId;
    private String projectId;
    private String divisionCode;
    private String divisionName;

    /* ---- ACTIVITY column ---- */

    private String activityDisplayCode;   // e.g. "A2.1"
    private String activityName;          // e.g. "API Implementation Documentation"

    /* ---- PROJECT column ---- */

    private String projectName;           // e.g. "Aadhaar Authentication API v3.0 Upgrade"
    private String projectCode;           // e.g. "PRJ-2026-018"

    /* ---- ORGANIZATION column ---- */

    private String organizationId;        // activity.vendorId
    private String organizationName;      // matched from project.vendors

    /* ---- SUBMITTED column ---- */

    private Long submittedAtEpochMs;      // most recent SUBMIT transition time

    /* ---- STATUS column ---- */

    private String voteStatus;            // PENDING / APPROVED / REJECTED

    /* ---- approver context ---- */

    private String approverUserUuid;
    private String approverEmail;
    private String approverName;

    /* ---- diagnostics ---- */

    /** Non-null when one of the upstream calls failed; the row is still returned. */
    private String warning;
}
