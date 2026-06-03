package com.pmis.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-reviewer info when seeding a parallel approval gate.
 *
 * <p>Note that activityId and projectId are NOT on this DTO — they live on
 * the enclosing {@code SeedParticipantsRequest} since they're the same for
 * all reviewers of one seed.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParticipantInput {

    @NotBlank
    private String approverUserUuid;

    @Email
    private String approverEmail;

    private String approverName;
}
