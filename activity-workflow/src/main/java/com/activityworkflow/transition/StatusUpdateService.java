package com.activityworkflow.transition;

import com.activityworkflow.entity.AuditDetails;
import com.activityworkflow.entity.ProcessInstanceEntity;
import com.activityworkflow.mapper.ActivityMapper;
import com.activityworkflow.repository.ProcessInstanceRepository;
import com.activityworkflow.web.models.AuditDetailsDTO;
import com.activityworkflow.web.models.ProcessInstanceDTO;
import com.activityworkflow.web.models.RequestInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
 
/**
 * Replaces Digit's {@code StatusUpdateService.updateStatus(...)} +
 * Kafka producer. Two responsibilities:
 *
 *   1. Flip {@code previousStatus} = old state uuid; set {@code state} = resultant state.
 *   2. Persist directly via JPA (instead of pushing to {@code save-transition} topic).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StatusUpdateService {
 
    private final ProcessInstanceRepository processRepository;
    private final ActivityMapper mapper;
 
    @Transactional
    public void updateStatus(RequestInfo requestInfo,
                             List<ProcessStateAndAction> tuples) {
 
        List<ProcessInstanceEntity> rows = new ArrayList<>(tuples.size());
 
        for (ProcessStateAndAction tuple : tuples) {
            ProcessInstanceDTO req = tuple.getProcessInstanceFromRequest();
 
            // previousStatus = uuid of the state we were in BEFORE the action
            if (req.getState() != null && req.getState().getUuid() != null) {
                req.setPreviousStatus(req.getState().getUuid());
            } else if (tuple.getCurrentState() != null) {
                req.setPreviousStatus(tuple.getCurrentState().getUuid());
            }
 
            // state = the resultant state (full StateDTO mirroring Digit)
            req.setState(mapper.toStateDTO(tuple.getResultantState()));
 
            rows.add(toEntity(tuple));
        }
 
        processRepository.saveAll(rows);
 
        log.info("Persisted {} ProcessInstance transition(s) via JPA", rows.size());
    }
 
    /* =====================  DTO -> Entity  ===================== */
 
    private ProcessInstanceEntity toEntity(ProcessStateAndAction tuple) {
        ProcessInstanceDTO req = tuple.getProcessInstanceFromRequest();
        AuditDetailsDTO ad = req.getAuditDetails();
 
        return ProcessInstanceEntity.builder()
                .uuid(req.getId())
                .businessService(req.getBusinessService())
                .businessId(req.getBusinessId())
                .moduleName(req.getModuleName())
                .currentState(tuple.getResultantState().getStateName())
                .previousState(tuple.getCurrentState() == null
                        ? null : tuple.getCurrentState().getStateName())
                .actionName(tuple.getAction().getActionName())
                .comment(req.getComment())
                .assignee(extractFirstAssigneeUuid(req))
                .sla(req.getStateSla() == null ? 0L : req.getStateSla())
                .terminateState(Boolean.TRUE.equals(tuple.getResultantState().getIsTerminateState()))
                .roles(tuple.getAction().getRoles() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(tuple.getAction().getRoles()))
                .auditDetails(AuditDetails.builder()
                        .createdBy(ad == null ? null : ad.getCreatedBy())
                        .createdTime(ad == null ? null : ad.getCreatedTime())
                        .lastModifiedBy(ad == null ? null : ad.getLastModifiedBy())
                        .lastModifiedTime(ad == null ? null : ad.getLastModifiedTime())
                        .build())
                .build();
    }
 
    private String extractFirstAssigneeUuid(ProcessInstanceDTO req) {
        if (req.getAssignes() == null || req.getAssignes().isEmpty()) return null;
        return req.getAssignes().get(0).getUuid();
    }
}