package com.activityworkflow.service;



import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.activityworkflow.transition.ProcessStateAndAction;
import com.activityworkflow.transition.StatusUpdateService;
import com.activityworkflow.transition.TransitionEnrichmentService;
import com.activityworkflow.transition.TransitionLookupService;
import com.activityworkflow.transition.TransitionValidator;
import com.activityworkflow.web.models.ProcessInstanceDTO;
import com.activityworkflow.web.request.TransitionRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrator — same four steps as Digit's {@code WorkflowService.transition(...)}:
 *
 *   1. getProcessStateAndActions  (lookup workflow + current state + matched action)
 *   2. enrichProcessRequest        (uuids, audit, assigner, nextActions, SLA)
 *   3. validateRequest             (roles)
 *   4. updateStatus                (flip state, persist via JPA — replaces Kafka)
 *
 * The intermediate {@code ProcessStateAndAction} list carries everything
 * downstream, exactly like Digit.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowTransitionService {

    private final TransitionLookupService transitionService;
    private final TransitionEnrichmentService enrichmentService;
    private final TransitionValidator workflowValidator;
    private final StatusUpdateService statusUpdateService;

    @Transactional
    public List<ProcessInstanceDTO> transition(TransitionRequest request) {

        List<ProcessStateAndAction> tuples =
                transitionService.getProcessStateAndActions(request.getProcessInstances(), true);

        enrichmentService.enrichProcessRequest(request.getRequestInfo(), tuples);
        workflowValidator.validateRequest(request.getRequestInfo(), tuples);
        statusUpdateService.updateStatus(request.getRequestInfo(), tuples);

        return request.getProcessInstances();
    }
}
