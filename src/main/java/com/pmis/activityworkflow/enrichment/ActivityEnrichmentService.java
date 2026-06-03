package com.pmis.activityworkflow.enrichment;

import com.pmis.activityworkflow.web.models.ActivityDTO;
import com.pmis.activityworkflow.web.models.AuditDetailsDTO;
import com.pmis.activityworkflow.web.request.ActivityRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Enriches the incoming request with generated values before persistence:
 *   - assigns uuids to Activity / State / Action that don't have one
 *   - sets currentState on each Action from its parent State's uuid
 *   - stamps audit details (createdBy/createdTime/...) from the
 *     authenticated principal
 *
 * Replaces the original Digit enrichment that depended on
 * {@code RequestInfo.userInfo.uuid}. The username is supplied externally
 * by the security layer so this stays framework-agnostic.
 */
@Component
public class ActivityEnrichmentService {

    public void enrichForCreate(ActivityRequest request, String createdBy) {
        if (request == null || request.getActivities() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        AuditDetailsDTO audit = AuditDetailsDTO.builder()
                .createdBy(createdBy)
                .createdTime(now)
                .lastModifiedBy(createdBy)
                .lastModifiedTime(now)
                .build();

        request.getActivities().forEach(activity -> {
            if (isBlank(activity.getUuid())) {
                activity.setUuid(UUID.randomUUID().toString());
            }
            activity.setAuditDetails(audit);

            if (activity.getStates() == null) return;

            activity.getStates().forEach(state -> {
                if (isBlank(state.getUuid())) {
                    state.setUuid(UUID.randomUUID().toString());
                }
                state.setAuditDetails(audit);

                if (state.getActions() == null) return;

                state.getActions().forEach(action -> {
                    if (isBlank(action.getUuid())) {
                        action.setUuid(UUID.randomUUID().toString());
                    }
                    if (action.getActive() == null) {
                        action.setActive(Boolean.TRUE);
                    }
                    action.setCurrentState(state.getUuid());     // wires the FK
                    action.setAuditDetails(audit);
                });
            });

            enrichNextStateRefs(activity);
        });
    }

    /**
     * Replace the human-friendly state-name written in {@code nextState}
     * with the uuid of the matching state so the persisted graph references
     * states by id.
     *
     * Skips quietly if nextState is already a uuid (length 36) or unknown.
     */
    private void enrichNextStateRefs(ActivityDTO activity) {
        if (activity.getStates() == null) return;

        activity.getStates().forEach(state -> {
            if (state.getActions() == null) return;

            state.getActions().forEach(action -> {
                String nextStateRef = action.getNextState();
                if (isBlank(nextStateRef) || isUuid(nextStateRef)) return;

                activity.getStates().stream()
                        .filter(s -> nextStateRef.equalsIgnoreCase(s.getStateName()))
                        .findFirst()
                        .ifPresent(target -> action.setNextState(target.getUuid()));
            });
        });
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isUuid(String s) {
        return s != null && s.length() == 36 && s.charAt(8) == '-';
    }
}
