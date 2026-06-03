package com.pmis.activityworkflow.web.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmis.activityworkflow.web.models.RequestInfo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One reviewer's vote on a record sitting in a parallel state.
 *
 * The reviewer's identity is taken from {@code RequestInfo.userInfo.uuid}
 * — it must match a participant row for this (businessService, activityId,
 * stateName) tuple, otherwise the vote is rejected.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CastVoteRequest {

    @JsonProperty("RequestInfo")
    private RequestInfo requestInfo;

    @NotBlank
    private String businessService;

    @NotBlank
    private String activityId;

    /** Parent project — one project has many activities. */
    private String projectId;

    @NotBlank
    private String stateName;

    /** "APPROVED" or "REJECTED". */
    @NotBlank
    @Pattern(regexp = "APPROVED|REJECTED",
             message = "vote must be APPROVED or REJECTED")
    private String vote;

    private String comment;
}
