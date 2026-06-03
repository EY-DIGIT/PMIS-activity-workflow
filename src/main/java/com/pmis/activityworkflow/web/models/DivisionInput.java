package com.pmis.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * One division participating in a parallel approval gate.
 *
 * <ul>
 *   <li>{@link #approver} — the single user who votes on behalf of this
 *       division. Required. Gets the approval-request notification.</li>
 *   <li>{@link #users} — collaborator users from the same division.
 *       Stored for record-keeping. Do NOT vote, do NOT get notified.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DivisionInput {

    @NotBlank
    private String divisionCode;

    private String divisionName;

    @NotNull
    @Valid
    private ParticipantInput approver;

    @Valid
    @Builder.Default
    private List<DivisionUserInput> users = new ArrayList<>();
}
