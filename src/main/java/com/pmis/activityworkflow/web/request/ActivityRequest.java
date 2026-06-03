package com.pmis.activityworkflow.web.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmis.activityworkflow.web.models.ActivityDTO;
import com.pmis.activityworkflow.web.models.RequestInfo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request envelope. JSON contract matches Digit's BusinessService shape so
 * existing clients can call this endpoint unchanged:
 *
 * <pre>
 * {
 *   "RequestInfo": { ... },
 *   "BusinessServices": [ ... ]
 * }
 * </pre>
 */
@Schema(description = "Create-request envelope (Digit BusinessService format)")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActivityRequest {

    @JsonProperty("RequestInfo")
    private RequestInfo requestInfo;

    @NotEmpty
    @Valid
    @JsonProperty("BusinessServices")
    private List<ActivityDTO> activities;
}
