package com.pmis.activityworkflow.service.assignments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Subset of the {@code GET /activities/{activityId}} response we use to
 * build the approval inbox. Extra upstream fields are ignored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActivityDetails {

    private String id;
    private String displayCode;       // "A1.1" etc — for the ACTIVITY column
    private String name;              // "Workflow Activity Implementations"
    private String projectId;
    private String milestoneId;
    private String vendorId;          // matched against project.vendors[].id for ORGANIZATION
}
