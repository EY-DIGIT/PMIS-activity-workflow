package com.pmis.activityworkflow.service.assignments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Subset of the {@code GET /projects/{projectId}} response we use to build
 * the approval inbox.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectDetails {

    private String id;
    private String projectCode;       // "UIDAI-PR26..." for the PROJECT column subtitle
    private String name;              // "project one" — for the PROJECT column title
    private List<Vendor> vendors;     // matched against Activity.vendorId for ORGANIZATION

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Vendor {
        private String id;
        private String name;
    }
}
