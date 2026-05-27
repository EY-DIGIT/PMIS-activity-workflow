package com.activityworkflow.web.request;

import com.activityworkflow.web.models.ActivityDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

   

    @NotEmpty
    @Valid
    @JsonProperty("BusinessServices")
    private List<ActivityDTO> activities;
}
