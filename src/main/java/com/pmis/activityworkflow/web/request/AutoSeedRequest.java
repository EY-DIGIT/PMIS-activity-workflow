package com.pmis.activityworkflow.web.request;

import com.pmis.activityworkflow.web.models.RequestInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Auto-seed the parallel gate by pulling division approvers + users from
 * the upstream activity-assignments API. No manual participant list — we
 * fetch and shape it ourselves.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AutoSeedRequest {

    @JsonProperty("RequestInfo")
    private RequestInfo requestInfo;

    @NotBlank
    private String businessService;

    @NotBlank
    private String activityId;

    private String projectId;

    @NotBlank
    private String stateName;
}
