package com.pmis.activityworkflow.service.transition;

import com.pmis.activityworkflow.mapper.ActivityMapper;
import com.pmis.activityworkflow.web.models.ActionDTO;
import com.pmis.activityworkflow.web.models.AuditDetailsDTO;
import com.pmis.activityworkflow.web.models.Document;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.models.UserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mirrors Digit's {@code EnrichmentService.enrichProcessRequest}:
 *
 *   - sets id (uuid) on each ProcessInstance + each Document
 *   - sets auditDetails from RequestInfo.userInfo
 *   - sets assigner = the caller
 *   - sets stateSla when the state is actually changing
 *   - populates nextActions (the actions allowed on the resultant state)
 *
 * "*"-role expansion already happened in TransitionLookupService.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransitionEnrichmentService {

    private final ActivityMapper mapper;

    public void enrichProcessRequest(RequestInfo requestInfo,
                                     List<ProcessStateAndAction> tuples) {

        String callerUuid = Optional.ofNullable(requestInfo)
                .map(RequestInfo::getUserInfo)
                .map(UserInfo::getUuid)
                .orElse("system");

        long now = System.currentTimeMillis();
        AuditDetailsDTO baseAudit = AuditDetailsDTO.builder()
                .createdBy(callerUuid)
                .createdTime(now)
                .lastModifiedBy(callerUuid)
                .lastModifiedTime(now)
                .build();

        tuples.forEach(tuple -> {
            ProcessInstanceDTO req = tuple.getProcessInstanceFromRequest();

            // ---- id ----
            req.setId(UUID.randomUUID().toString());

            // ---- auditDetails ----
            // Same-state transitions inherit the original createdBy/createdTime
            // from the DB row, matching Digit's behaviour.
            AuditDetailsDTO audit = baseAudit;
            boolean sameStateAction = tuple.getAction() != null
                    && tuple.getAction().getNextState() != null
                    && tuple.getCurrentState() != null
                    && tuple.getAction().getNextState()
                            .equalsIgnoreCase(tuple.getCurrentState().getStateName());

            if (sameStateAction && tuple.getProcessInstanceFromDb() != null) {
                var dbAudit = tuple.getProcessInstanceFromDb().getAuditDetails();
                audit = AuditDetailsDTO.builder()
                        .createdBy(dbAudit.getCreatedBy())
                        .createdTime(dbAudit.getCreatedTime())
                        .lastModifiedBy(callerUuid)
                        .lastModifiedTime(now)
                        .build();
            }
            req.setAuditDetails(audit);

            // ---- assigner = caller ----
            if (requestInfo != null) {
                req.setAssigner(requestInfo.getUserInfo());
            }

            // ---- document enrichment ----
            if (!CollectionUtils.isEmpty(req.getDocuments())) {
                for (Document doc : req.getDocuments()) {
                    doc.setAuditDetails(audit);
                    if (doc.getId() == null) {
                        doc.setId(UUID.randomUUID().toString());
                    }
                }
            }

            // ---- stateSla (only when state actually changes) ----
            boolean stateChanging = !sameStateAction;
            if (stateChanging && tuple.getResultantState() != null) {
                req.setStateSla(tuple.getResultantState().getSla());
            }

            // ---- nextActions populated from resultant state ----
            setNextActions(tuple);
        });
    }

    /**
     * Populate {@link ProcessInstanceDTO#getNextActions()} with the action list
     * the caller is allowed to fire from the resultant state.
     *
     * The role-based filtering happens later in TransitionValidator;
     * here we just expose what's structurally available.
     */
    private void setNextActions(ProcessStateAndAction tuple) {
        if (tuple.getResultantState() == null
                || CollectionUtils.isEmpty(tuple.getResultantState().getActions())) {
            return;
        }
        List<ActionDTO> next = tuple.getResultantState().getActions().stream()
                .map(mapper::toActionDTO)
                .toList();
        tuple.getProcessInstanceFromRequest().setNextActions(next);
    }
}
