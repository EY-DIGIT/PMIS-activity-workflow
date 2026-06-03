package com.pmis.activityworkflow.service.assignments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Top-level envelope: {@code { data, message, error, status }}.
 * Only the {@code data} block is meaningful for us.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssignmentResponse {
    private AssignmentData data;
    private String message;
    private String error;
    private Integer status;
}
