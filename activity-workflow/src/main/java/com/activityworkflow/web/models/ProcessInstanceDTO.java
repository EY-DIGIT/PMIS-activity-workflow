package com.activityworkflow.web.models;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors Digit's ProcessInstance shape so existing clients work unchanged.
 *
 * Important difference from Digit's payload:
 * <ul>
 *   <li>{@code action} is a plain string (e.g. "APPROVE"), NOT a nested
 *       {@code {action: {action: "APPROVE"}}} object — matches Digit's
 *       ProcessInstance domain class exactly.</li>
 *   <li>{@code state} is the full {@link StateDTO} (set in the response;
 *       can also be a string on input, see service layer).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"id"})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessInstanceDTO {

    @Size(max = 64)
    private String id;

    /** Carried for backward-compat with Digit clients; ignored by the engine. */
    @Size(max = 128)
    private String tenantId;

    /** Workflow definition name — matches aw_activity.activity_name. */
    @NotBlank
    @Size(max = 128)
    private String businessService;

    /** Record being moved (e.g. "TL-TEST-1"). */
    @NotBlank
    @Size(max = 128)
    private String businessId;

    /** The action name being fired (e.g. "SUBMIT", "APPROVE"). */
    @NotBlank
    @Size(max = 128)
    private String action;

    /** Free-form module identifier (e.g. "activity-workflow"). */
    @Size(max = 64)
    private String moduleName;

    /** Set by the engine to the resultant state after the transition. */
    @Valid
    private StateDTO state;

    @Size(max = 1024)
    private String comment;

    @Valid
    private List<Document> documents;

    /** The user who fired the action — populated from RequestInfo.userInfo. */
    private UserInfo assigner;

    /** Optional list of users the record is being assigned to next. */
    private List<UserInfo> assignes;

    /** Populated in the response with the next allowed actions from the resultant state. */
    @Valid
    private List<ActionDTO> nextActions;

    private Long stateSla;
    private Long businesssServiceSla;

    @Size(max = 128)
    private String previousStatus;

    /** Free-form attachment that survives the round-trip (Digit pattern). */
    private Object entity;

    private AuditDetailsDTO auditDetails;

    private Integer rating;

    @Builder.Default
    private Boolean escalated = false;

    /* ===================  Digit-style add* helpers  =================== */

    public ProcessInstanceDTO addDocumentsItem(Document doc) {
        if (this.documents == null) this.documents = new ArrayList<>();
        if (!this.documents.contains(doc)) this.documents.add(doc);
        return this;
    }

    public ProcessInstanceDTO addNextActionsItem(ActionDTO action) {
        if (this.nextActions == null) this.nextActions = new ArrayList<>();
        this.nextActions.add(action);
        return this;
    }

    public ProcessInstanceDTO addAssigneesItem(UserInfo user) {
        if (this.assignes == null) this.assignes = new ArrayList<>();
        if (!this.assignes.contains(user)) this.assignes.add(user);
        return this;
    }
}