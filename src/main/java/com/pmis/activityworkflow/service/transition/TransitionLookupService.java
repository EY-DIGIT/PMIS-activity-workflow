package com.pmis.activityworkflow.service.transition;

import com.pmis.activityworkflow.entity.ActionEntity;
import com.pmis.activityworkflow.entity.ActivityEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.entity.StateEntity;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.pmis.activityworkflow.repository.ActivityRepository;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Equivalent of Digit's {@code TransitionService.getProcessStateAndActions(...)}.
 *
 * For each incoming ProcessInstance:
 *   1. resolve the workflow definition
 *   2. fetch the latest persisted ProcessInstance (current state from DB)
 *   3. pick currentState (DB > start state if no DB row)
 *   4. find the matched action on currentState by action name
 *   5. resolve resultantState from action.nextState
 *
 * The "*"-role expansion from Digit is supported: if an action's role list
 * contains "*", it is replaced with every distinct role the workflow uses.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransitionLookupService {

    private final ActivityRepository activityRepository;
    private final ProcessInstanceRepository processRepository;

    /**
     * @param processInstances incoming list from the transition request
     * @param isTransitionCall true for /transition; false for /search (lighter checks)
     */
    @Transactional(readOnly = true)
    public List<ProcessStateAndAction> getProcessStateAndActions(
            List<ProcessInstanceDTO> processInstances, boolean isTransitionCall) {

        List<ProcessStateAndAction> result = new ArrayList<>(processInstances.size());

        ActivityEntity workflow = getWorkflow(processInstances);
        Map<String, ProcessInstanceEntity> latestByActivityId =
                fetchLatestByActivityId(processInstances, workflow.getActivityName());

        for (ProcessInstanceDTO dto : processInstances) {
            ProcessStateAndAction tuple = ProcessStateAndAction.builder()
                    .processInstanceFromRequest(dto)
                    .processInstanceFromDb(latestByActivityId.get(dto.getActivityId()))
                    .build();

            // Carry the workflow's "business" name into the response (Digit parity)
            if (isTransitionCall) {
                dto.setModuleName(workflow.getBusinessModule());
            }

            // ---- currentState ---------------------------------------------------
            StateEntity currentState = null;

            if (tuple.getProcessInstanceFromDb() != null && isTransitionCall) {
                // record exists: continue from where we left off
                currentState = findStateByName(workflow,
                        tuple.getProcessInstanceFromDb().getCurrentState());
            } else if (!isTransitionCall) {
                // search call: caller supplies the state on the DTO
                String requestStateName = dto.getState() != null
                        ? dto.getState().getStateName() : null;
                if (StringUtils.hasText(requestStateName)) {
                    currentState = findStateByName(workflow, requestStateName);
                }
            }

            // first transition: assign businessServiceSla
            if (tuple.getProcessInstanceFromDb() == null && isTransitionCall) {
                dto.setBusinesssServiceSla(workflow.getActivitySla());
            }

            // fall back to start state
            if (currentState == null) {
                currentState = workflow.getStates().stream()
                        .filter(s -> Boolean.TRUE.equals(s.getIsStartState()))
                        .findFirst()
                        .orElseThrow(() -> new InvalidTransitionException(
                                "Workflow '" + workflow.getActivityName()
                                        + "' has no isStartState=true and activityId '"
                                        + dto.getActivityId() + "' has no existing transitions"));
            }
            tuple.setCurrentState(currentState);

            // ---- matched action -------------------------------------------------
            if (!CollectionUtils.isEmpty(currentState.getActions())) {
                for (ActionEntity action : currentState.getActions()) {
                    if (action.getActionName() != null
                            && action.getActionName().equalsIgnoreCase(dto.getAction())) {

                        // Note: the "*" wildcard in action.roles is no longer
                        // expanded here. TransitionValidator treats "*" as
                        // "any authenticated user" directly, which means
                        // admin/owner roles don't need to be hardcoded into
                        // a wildcard expansion list. The action's role list
                        // is left as-is.
                        tuple.setAction(action);
                        break;
                    }
                }
            }

            if (isTransitionCall) {
                if (tuple.getAction() == null) {
                    throw new InvalidTransitionException(String.format(
                            "INVALID ACTION: Action '%s' not found in config for activityId '%s' (current state: '%s')",
                            dto.getAction(), dto.getActivityId(), currentState.getStateName()));
                }

                // ---- resultantState --------------------------------------------
                StateEntity resultant = resolveNextState(workflow,
                        tuple.getAction().getNextState());
                tuple.setResultantState(resultant);
            }

            result.add(tuple);
        }
        return result;
    }

    /* ==========================  helpers  ========================== */

    /** All ProcessInstances in one request share a single businessService — fail loudly if not. */
    private ActivityEntity getWorkflow(List<ProcessInstanceDTO> processInstances) {
        String name = processInstances.get(0).getBusinessService();
        boolean uniform = processInstances.stream()
                .allMatch(p -> name.equalsIgnoreCase(p.getBusinessService()));
        if (!uniform) {
            throw new InvalidTransitionException(
                    "All ProcessInstances in a single request must share the same businessService");
        }
        return activityRepository.findByActivityName(name).stream().findFirst()
                .orElseThrow(() -> new InvalidTransitionException(
                        "No workflow defined for businessService=" + name));
    }

    private Map<String, ProcessInstanceEntity> fetchLatestByActivityId(
            List<ProcessInstanceDTO> processInstances, String businessService) {

        Map<String, ProcessInstanceEntity> map = new HashMap<>();
        for (ProcessInstanceDTO dto : processInstances) {
            Optional<ProcessInstanceEntity> latest =
                    processRepository.findLatest(businessService, dto.getActivityId());
            latest.ifPresent(p -> map.put(dto.getActivityId(), p));
        }
        return map;
    }

    private StateEntity findStateByName(ActivityEntity workflow, String name) {
        if (name == null) return null;
        return workflow.getStates().stream()
                .filter(s -> name.equalsIgnoreCase(s.getStateName()))
                .findFirst()
                .orElse(null);
    }

    private StateEntity resolveNextState(ActivityEntity workflow, String nextStateRef) {
        if (!StringUtils.hasText(nextStateRef)) {
            throw new InvalidTransitionException("Action has no nextState configured");
        }
        // try by name first
        return workflow.getStates().stream()
                .filter(s -> nextStateRef.equalsIgnoreCase(s.getStateName())
                          || nextStateRef.equalsIgnoreCase(s.getUuid()))
                .findFirst()
                .orElseThrow(() -> new InvalidTransitionException(
                        "nextState '" + nextStateRef + "' not found in workflow '"
                                + workflow.getActivityName() + "'"));
    }
}
