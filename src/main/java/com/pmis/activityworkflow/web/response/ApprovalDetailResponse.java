package com.pmis.activityworkflow.web.response;


import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Everything the "Approval Request" detail screen needs in one shape.
 *
 * <p>Sections mirror the UI:</p>
 * <ul>
 *   <li>Header — activityDisplayCode, activityName, yourStatus</li>
 *   <li>Project — projectName, projectCode</li>
 *   <li>Activity Details — organization, owner, your division, dates,
 *       submitted, description</li>
 *   <li>Organization Submissions — list of (comment + attachments) pulled
 *       from the upstream comments API</li>
 *   <li>Your Status — per-division voting status</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalDetailResponse {

    /* ----- header ----- */
    private String activityId;
    private String activityDisplayCode;          // "A2.1"
    private String activityName;                  // "API Implementation Documentation"

    /** Top-right pill. Echoes the current user's voteStatus on this state. */
    private String yourStatus;                    // PENDING / APPROVED / REJECTED

    /* ----- project ----- */
    private String projectId;
    private String projectName;
    private String projectCode;

    /* ----- activity details ----- */
    private String organizationId;
    private String organizationName;              // resolved from project.vendors[] by activity.vendorId

    private String activityOwnerDivision;         // ownerDivision e.g. "tmd2"
    private String yourDivisionCode;              // the participant row's division_code for current user
    private String yourDivisionName;

    private String startDate;                     // ISO from upstream
    private String endDate;
    private Long   submittedAt;                   // ms epoch of latest SUBMIT
    private String description;

    /* ----- organization submissions (comments + attachments) ----- */
    private List<OrganizationSubmission> organizationSubmissions;

    /* ----- per-division status ----- */
    private List<DivisionStatus> yourStatusBreakdown;

    /* ============================================================ */

    /**
     * One comment posted to the activity. Maps to one element of the
     * upstream comments API's {@code _embedded.elements[]}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OrganizationSubmission {
        private String commentId;                  // upstream comment uuid
        private String body;                       // comment body text
        private CommentAuthor author;
        private String createdAt;                  // ISO timestamp from upstream
        private List<Attachment> attachments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CommentAuthor {
        private String id;
        private String login;
        private String firstName;
        private String lastName;
        private String email;
        private String displayName;                // "firstName lastName", or login if both null
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Attachment {
        private String fileName;
        private String mimeType;
        private Long   sizeBytes;
        private String url;
        private String uploadedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DivisionStatus {
        private String divisionCode;
        private String divisionName;
        private String approverUserUuid;
        private String approverName;
        private String voteStatus;                 // PENDING / APPROVED / REJECTED
        private Long   votedAt;
        /** True if this row represents the user making the request. */
        private Boolean isYou;
    }
}