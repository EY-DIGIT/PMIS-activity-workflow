package com.pmis.activityworkflow.web.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmis.activityworkflow.web.models.DivisionInput;
import com.pmis.activityworkflow.web.models.RequestInfo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Seed the parallel approval gate for one record at one parallel state.
 *
 * The body is now a list of <b>divisions</b>. Each division contributes
 * exactly one approver (who votes) and any number of collaborator users
 * (stored, not voting, not notified).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeedParticipantsRequest {

    @JsonProperty("RequestInfo")
    private RequestInfo requestInfo;

    @NotBlank
    private String businessService;

    @NotBlank
    private String activityId;

    /** Parent project — one project has many activities. */
    private String projectId;

    /** The parallel state to seed (e.g. PENDINGATCONCERNEDDIVISION). */
    @NotBlank
    private String stateName;

    @NotEmpty
    @Valid
    private List<DivisionInput> divisions;
}
