package com.pmis.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class StateDTO {

    @Size(max = 64)
    private String uuid;

    @NotNull
    @Size(max = 256)
    @JsonProperty("state")
    private String stateName;

    @Size(max = 256)
    private String applicationStatus;

    private Long sla;
    private Boolean docUploadRequired;
    private Boolean isStartState;
    private Boolean isTerminateState;
    private Boolean isStateUpdatable;

    @Valid
    @Builder.Default
    private List<ActionDTO> actions = new ArrayList<>();

    private AuditDetailsDTO auditDetails;
}
