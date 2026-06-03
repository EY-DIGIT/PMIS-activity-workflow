package com.pmis.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * BusinessService-shaped DTO. Java field names stay clean; JSON property
 * names match the Digit contract via {@link JsonProperty} aliases.
 *
 * Unknown fields (like Digit's "tenantId") are silently ignored.
 */
@Schema(description = "An Activity workflow definition (Digit BusinessService shape)")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActivityDTO {

    @Size(max = 64)
    private String uuid;

    @NotNull
    @Size(max = 256)
    @JsonProperty("businessService")
    private String activityName;

    @Size(max = 256)
    @JsonProperty("business")
    private String businessModule;

    @JsonProperty("businessServiceSla")
    private Long activitySla;

    @Size(max = 512)
    private String getUri;

    @Size(max = 512)
    private String postUri;

    @NotEmpty
    @Valid
    @Builder.Default
    private List<StateDTO> states = new ArrayList<>();

    private AuditDetailsDTO auditDetails;
}
