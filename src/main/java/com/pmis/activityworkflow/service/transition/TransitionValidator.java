package com.pmis.activityworkflow.service.transition;

import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.models.Role;
import com.pmis.activityworkflow.web.models.UserInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Mirrors Digit's {@code WorkflowValidator.validateRequest}.
 *
 * Confirms the caller has at least one of the roles permitted by the
 * matched action. {@link #ENFORCE_ROLES} controls strict vs. log-only mode.
 *
 * Document validation, docUploadRequired enforcement, etc. can be plugged
 * in here later — kept minimal for now to match what you actually use.
 */
@Service
@Slf4j
public class TransitionValidator {

    /** true = reject when roles don't match; false = warn but proceed. */
    private static final boolean ENFORCE_ROLES = true;

    /** Action role that means "any authenticated user with any role". */
    private static final String WILDCARD_ROLE = "*";

    public void validateRequest(RequestInfo requestInfo,
                                List<ProcessStateAndAction> tuples) {

        List<String> callerRoles = extractRoles(requestInfo);

        for (ProcessStateAndAction tuple : tuples) {
            List<String> required = tuple.getAction().getRoles();
            if (required == null || required.isEmpty()) continue;

            // Wildcard support — if the workflow declares "*" in the
            // required-roles list for this action, any non-anonymous caller
            // passes. This is how system-fired actions (e.g. ALL_APPROVED
            // triggered by an admin's "Request Owner Approval" click) get
            // past the role check even when the admin doesn't carry the
            // division_approver role.
            if (required.contains(WILDCARD_ROLE)) {
                if (!callerRoles.isEmpty()) continue;
                // No roles at all — even wildcard requires the caller to be
                // identified. Fall through to the failure path so this is
                // still surfaced.
            }

            boolean allowed = callerRoles.stream().anyMatch(required::contains);
            if (allowed) continue;

            String msg = String.format(
                    "User roles %s lack any of %s required by action '%s' on activityId '%s'",
                    callerRoles, required,
                    tuple.getAction().getActionName(),
                    tuple.getProcessInstanceFromRequest().getActivityId());

            if (ENFORCE_ROLES) {
                throw new InvalidTransitionException("UNAUTHORIZED: " + msg);
            }
            log.warn("[role-check soft-fail] {}", msg);
        }
    }

    private List<String> extractRoles(RequestInfo requestInfo) {
        return Optional.ofNullable(requestInfo)
                .map(RequestInfo::getUserInfo)
                .map(UserInfo::getRoles)
                .orElse(Collections.emptyList())
                .stream()
                .map(Role::getCode)
                .filter(c -> c != null && !c.isBlank())
                .toList();
    }
}
