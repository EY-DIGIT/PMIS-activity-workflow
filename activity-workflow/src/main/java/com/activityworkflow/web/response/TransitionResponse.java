package com.activityworkflow.web.response;

import com.activityworkflow.web.models.ProcessInstanceDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "Transition response envelope (Digit ProcessInstance format)")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransitionResponse {

    @JsonProperty("ProcessInstances")
    private List<ProcessInstanceDTO> processInstances;
}
