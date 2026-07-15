package com.pmis.activityworkflow.service;

import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.pmis.activityworkflow.exception.WorkflowAuditable;
import com.pmis.activityworkflow.service.assignments.ActivityAssignmentsClient;
import com.pmis.activityworkflow.service.assignments.AssignmentData;
import com.pmis.activityworkflow.service.audit.WorkflowAuditService;
import com.pmis.activityworkflow.service.eligibility.ActivityCompletionEligibilityClient;
import com.pmis.activityworkflow.service.eligibility.ActivityCompletionEligibilityClient.EligibilityResult;
import com.pmis.activityworkflow.service.transition.ActivityStatusUpdateClient;
import com.pmis.activityworkflow.service.transition.ProcessStateAndAction;
import com.pmis.activityworkflow.service.transition.StatusUpdateService;
import com.pmis.activityworkflow.service.transition.TransitionEnrichmentService;
import com.pmis.activityworkflow.service.transition.TransitionLookupService;
import com.pmis.activityworkflow.service.transition.TransitionValidator;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.request.TransitionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrator — same four steps as Digit's {@code WorkflowService.transition(...)},
 * now wrapped with audit capture:
 *
 *   1. getProcessStateAndActions  (lookup workflow + current state + matched action)
 *   2. enrichProcessRequest        (uuids, audit, assigner, nextActions, SLA)
 *   3. validateRequest             (roles)
 *   4. updateStatus                (flip state, persist via JPA — replaces Kafka)
 *
 * <p><b>Audit policy</b>:
 * <ul>
 *   <li>Success → audit row written in the same transaction as the state change.</li>
 *   <li>Failure → audit row only when the exception implements
 *       {@link WorkflowAuditable}. Other failures (auth/401, validation,
 *       framework noise) belong in regular request logs and are NOT
 *       written to the workflow audit trail.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowTransitionService {

    private final TransitionLookupService transitionService;
    private final TransitionEnrichmentService enrichmentService;
    private final TransitionValidator workflowValidator;
    private final StatusUpdateService statusUpdateService;
    private final WorkflowAuditService auditService;
    private final ActivityAssignmentsClient assignmentsClient;
    private final ActivityCompletionEligibilityClient eligibilityClient;
    private final ActivityStatusUpdateClient activityStatusUpdateClient;

    @Transactional
    public List<ProcessInstanceDTO> transition(TransitionRequest request) {
        try {
            checkAssignments(request.getProcessInstances());
            checkCompletionEligibility(request.getProcessInstances());

            List<ProcessStateAndAction> tuples =
                    transitionService.getProcessStateAndActions(request.getProcessInstances(), true);

            enrichmentService.enrichProcessRequest(request.getRequestInfo(), tuples);
            workflowValidator.validateRequest(request.getRequestInfo(), tuples);
            statusUpdateService.updateStatus(request.getRequestInfo(), tuples);

            // If any instance reached ACTIVITYCOMPLETED, notify the upstream projects API.
            request.getProcessInstances().stream()
                    .filter(pi -> pi.getState() != null
                            && "ACTIVITYCOMPLETED".equals(pi.getState().getStateName()))
                    .forEach(pi -> activityStatusUpdateClient.markCompleted(pi.getActivityId()));

            // atomic with the transition
            auditService.recordSuccess(request, tuples);

            return request.getProcessInstances();

        } catch (RuntimeException ex) {
            // Only record failures that represent a real workflow decision.
            // Auth/validation/framework noise gets logged normally and surfaces
            // through the GlobalExceptionHandler — it doesn't pollute the
            // workflow audit table.
            if (ex instanceof WorkflowAuditable) {
                auditService.recordFailure(request, ex);
            } else {
                log.debug("Non-workflow exception, not auditing: {}", ex.toString());
            }
            throw ex;
        }
    }

    /**
     * Fetches assignments for each activity and verifies that both an owner
     * approver and at least one division approver are present.
     * Throws {@link InvalidTransitionException} with a clear message if either
     * is missing so the caller can assign the required people before retrying.
     */
    private void checkAssignments(List<ProcessInstanceDTO> processInstances) {
        for (ProcessInstanceDTO pi : processInstances) {
            AssignmentData data = assignmentsClient.fetch(pi.getActivityId());

            boolean ownerApproverMissing = CollectionUtils.isEmpty(data.getOwnerApprover());
            boolean divisionApproverMissing = CollectionUtils.isEmpty(data.getDivisionApprovers())
                    || data.getDivisionApprovers().values().stream().allMatch(CollectionUtils::isEmpty);

            if (ownerApproverMissing || divisionApproverMissing) {
                log.warn("Activity {} is missing assignments: ownerApproverMissing={} divisionApproverMissing={}",
                        pi.getActivityId(), ownerApproverMissing, divisionApproverMissing);
                throw new InvalidTransitionException(
                        "Please assign users for Approver and Member");
            }
        }
    }

    /**
     * Calls the completion-eligibility API for every activity in the request.
     * Throws {@link InvalidTransitionException} if any activity has unmet
     * blocking dependencies, listing their names and statuses.
     */
    private void checkCompletionEligibility(List<ProcessInstanceDTO> processInstances) {
        for (ProcessInstanceDTO pi : processInstances) {
            EligibilityResult result = eligibilityClient.check(pi.getActivityId());
            if (!result.isEligible()) {
                String blocking = result.getBlockingDependencies().stream()
                        .map(b -> String.format("'%s' (status: %s)", b.getName(), b.getStatus()))
                        .collect(Collectors.joining(", "));
                throw new InvalidTransitionException(
                        "Activity '" + pi.getActivityId() + "' cannot be transitioned because the " +
                        "following dependencies are not completed: " + blocking);
            }
        }
    }
}
