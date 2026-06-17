package com.pmis.activityworkflow.web.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Per-division payload inside a {@link RequestDivisionApprovalRequest}.
 * Each concerned division gets its own independent comment and list of
 * pre-uploaded document store IDs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DivisionApprovalInput {

    /** Division code — must match a seeded participant row (e.g. "tmd-i", "TMD-II"). */
    @NotBlank
    private String divisionId;

    /** UUID of the reviewer for this division (from aw_parallel_participant.approver_user_uuid). */
    private String userUuid;

    /** Optional comment visible only to this division's reviewer. */
    private String comment;

    /**
     * IDs of files already uploaded to the document store via
     * {@code POST /activities/documents/upload}. Each ID is the upstream
     * comment id returned by that endpoint.
     */
    private List<String> documentStoreIds;
}