package com.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditDetailsDTO {

    private String createdBy;
    private Long createdTime;
    private String lastModifiedBy;
    private Long lastModifiedTime;
}
