package com.pmis.activityworkflow.web.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.models.RequestInfo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "Transition request envelope (Digit ProcessInstance format)")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitionRequest {

    @JsonProperty("RequestInfo")
    private RequestInfo requestInfo;

    @NotEmpty
    @Valid
    @JsonProperty("ProcessInstances")
    private List<ProcessInstanceDTO> processInstances;
}
