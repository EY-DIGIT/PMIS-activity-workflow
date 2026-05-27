package com.activityworkflow.transition;

import com.activityworkflow.exception.InvalidTransitionException;
import com.activityworkflow.web.models.RequestInfo;
import com.activityworkflow.web.models.Role;
import com.activityworkflow.web.models.UserInfo;
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

    public void validateRequest(RequestInfo requestInfo,
                                List<ProcessStateAndAction> tuples) {

        List<String> callerRoles = extractRoles(requestInfo);

        for (ProcessStateAndAction tuple : tuples) {
            List<String> required = tuple.getAction().getRoles();
            if (required == null || required.isEmpty()) continue;

            boolean allowed = callerRoles.stream().anyMatch(required::contains);
            if (allowed) continue;

            String msg = String.format(
                    "User roles %s lack any of %s required by action '%s' on businessId '%s'",
                    callerRoles, required,
                    tuple.getAction().getActionName(),
                    tuple.getProcessInstanceFromRequest().getBusinessId());

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
