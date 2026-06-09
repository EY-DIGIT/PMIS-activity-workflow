package com.pmis.activityworkflow.service.assignments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Parsed payload from the upstream activity-assignments API.
 *
 * <p>Maps to the {@code data} object of the response. Fields not in this
 * file are silently ignored (see {@code @JsonIgnoreProperties}).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssignmentData {

    /** Activity owner — supporting user(s). Not used for approval routing. */
    private List<UserRef> owner;

    /** Owner-division approver (single approver). We use ownerApprover[0]. */
    private List<UserRef> ownerApprover;

    /** Division code -> list of users assigned to that division (collaborators). */
    private Map<String, List<UserRef>> divisionUsers;

    /** Division code -> list of approvers for that division (we use [0]). */
    private Map<String, List<UserRef>> divisionApprovers;

    /**
     * One user reference. Matches the fields in your API response —
     * other fields like login are ignored.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserRef {
        private String id;
        private String login;
        private String email;
        /** Upstream-provided full name. Some older endpoints returned
         *  firstName+lastName instead — both shapes are supported. */
        private String fullName;
        private String firstName;
        private String lastName;

        /**
         * Best display name we can produce for this user, with fallback:
         * upstream {@code fullName} first, then "{firstName} {lastName}",
         * finally {@code login}. Returns a trimmed non-empty string or null.
         */
        public String displayName() {
            if (fullName != null && !fullName.trim().isEmpty()) {
                return fullName.trim();
            }
            String f = firstName == null ? "" : firstName.trim();
            String l = lastName  == null ? "" : lastName.trim();
            String name = (f + " " + l).trim();
            return name.isEmpty() ? login : name;
        }
    }
}
