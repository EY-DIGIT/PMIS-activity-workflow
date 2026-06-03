package com.pmis.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Document {

    @Size(max = 64)
    private String id;

    @Size(max = 256)
    private String documentType;

    @Size(max = 256)
    private String fileStoreId;

    @Size(max = 256)
    private String documentUid;

    @Size(max = 1024)
    private String additionalDetails;

    private AuditDetailsDTO auditDetails;
}
