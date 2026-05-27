package com.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionDTO {

    @Size(max = 64)
    private String uuid;

    private Boolean active;

    @NotNull
    @Size(max = 256)
    @JsonProperty("action")
    private String actionName;

    @Size(max = 64)
    private String nextState;

    /** Transient — set by enrichment from the parent state's uuid. */
    @Size(max = 64)
    private String currentState;

    private List<String> roles;

    private AuditDetailsDTO auditDetails;
}