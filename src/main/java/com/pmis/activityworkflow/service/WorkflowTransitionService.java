package com.pmis.activityworkflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pmis.activityworkflow.exception.WorkflowAuditable;
import com.pmis.activityworkflow.service.audit.WorkflowAuditService;
import com.pmis.activityworkflow.service.transition.ProcessStateAndAction;
import com.pmis.activityworkflow.service.transition.StatusUpdateService;
import com.pmis.activityworkflow.service.transition.TransitionEnrichmentService;
import com.pmis.activityworkflow.service.transition.TransitionLookupService;
import com.pmis.activityworkflow.service.transition.TransitionValidator;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.request.TransitionRequest;

import java.util.List;

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

    @Transactional
    public List<ProcessInstanceDTO> transition(TransitionRequest request) {
        try {
            List<ProcessStateAndAction> tuples =
                    transitionService.getProcessStateAndActions(request.getProcessInstances(), true);

            enrichmentService.enrichProcessRequest(request.getRequestInfo(), tuples);
            workflowValidator.validateRequest(request.getRequestInfo(), tuples);
            statusUpdateService.updateStatus(request.getRequestInfo(), tuples);

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
}
