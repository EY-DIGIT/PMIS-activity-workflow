package com.pmis.activityworkflow.mapper;

import com.pmis.activityworkflow.entity.ActionEntity;
import com.pmis.activityworkflow.entity.ActivityEntity;
import com.pmis.activityworkflow.entity.AuditDetails;
import com.pmis.activityworkflow.entity.StateEntity;
import com.pmis.activityworkflow.web.models.ActionDTO;
import com.pmis.activityworkflow.web.models.ActivityDTO;
import com.pmis.activityworkflow.web.models.AuditDetailsDTO;
import com.pmis.activityworkflow.web.models.StateDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Converts between the web DTO graph (ActivityDTO / StateDTO / ActionDTO)
 * and the JPA entity graph.
 *
 * <p>Use {@code addState(...)} / {@code addAction(...)} on the parent
 * entities — never {@code setStates(list)} — so the FK columns get filled
 * by the cascade insert.</p>
 */
@Component
public class ActivityMapper {

    /* ===================  DTO -> Entity  =================== */

    public ActivityEntity toEntity(ActivityDTO src) {
        if (src == null) return null;

        ActivityEntity entity = ActivityEntity.builder()
                .uuid(src.getUuid())
                .activityName(src.getActivityName())
                .businessModule(src.getBusinessModule())
                .activitySla(src.getActivitySla())
                .getUri(src.getGetUri())
                .postUri(src.getPostUri())
                .auditDetails(toAuditEntity(src.getAuditDetails()))
                .states(new ArrayList<>())
                .build();

        if (src.getStates() != null) {
            src.getStates().stream()
                    .filter(Objects::nonNull)
                    .map(this::toStateEntity)
                    .forEach(entity::addState);
        }
        return entity;
    }

    public StateEntity toStateEntity(StateDTO src) {
        if (src == null) return null;

        StateEntity entity = StateEntity.builder()
                .uuid(src.getUuid())
                .stateName(src.getStateName())
                .applicationStatus(src.getApplicationStatus())
                .sla(src.getSla())
                .docUploadRequired(src.getDocUploadRequired())
                .isStartState(src.getIsStartState())
                .isTerminateState(src.getIsTerminateState())
                .isStateUpdatable(src.getIsStateUpdatable())
                .auditDetails(toAuditEntity(src.getAuditDetails()))
                .actions(new ArrayList<>())
                .build();

        if (src.getActions() != null) {
            src.getActions().stream()
                    .filter(Objects::nonNull)
                    .map(this::toActionEntity)
                    .forEach(entity::addAction);
        }
        return entity;
    }

    public ActionEntity toActionEntity(ActionDTO src) {
        if (src == null) return null;

        return ActionEntity.builder()
                .uuid(src.getUuid())
                .active(src.getActive())
                .actionName(src.getActionName())
                .nextState(src.getNextState())
                .roles(src.getRoles() == null ? new ArrayList<>() : new ArrayList<>(src.getRoles()))
                .auditDetails(toAuditEntity(src.getAuditDetails()))
                .build();
    }

    public AuditDetails toAuditEntity(AuditDetailsDTO src) {
        if (src == null) return null;
        return AuditDetails.builder()
                .createdBy(src.getCreatedBy())
                .createdTime(src.getCreatedTime())
                .lastModifiedBy(src.getLastModifiedBy())
                .lastModifiedTime(src.getLastModifiedTime())
                .build();
    }

    /* ===================  Entity -> DTO  =================== */

    public ActivityDTO toDTO(ActivityEntity entity) {
        if (entity == null) return null;

        List<StateDTO> states = entity.getStates() == null
                ? new ArrayList<>()
                : entity.getStates().stream()
                        .filter(Objects::nonNull)
                        .map(this::toStateDTO)
                        .collect(Collectors.toList());

        return ActivityDTO.builder()
                .uuid(entity.getUuid())
                .activityName(entity.getActivityName())
                .businessModule(entity.getBusinessModule())
                .activitySla(entity.getActivitySla())
                .getUri(entity.getGetUri())
                .postUri(entity.getPostUri())
                .auditDetails(toAuditDTO(entity.getAuditDetails()))
                .states(states)
                .build();
    }

    public StateDTO toStateDTO(StateEntity entity) {
        if (entity == null) return null;

        List<ActionDTO> actions = entity.getActions() == null
                ? new ArrayList<>()
                : entity.getActions().stream()
                        .filter(Objects::nonNull)
                        .map(this::toActionDTO)
                        .collect(Collectors.toList());

        return StateDTO.builder()
                .uuid(entity.getUuid())
                .stateName(entity.getStateName())
                .applicationStatus(entity.getApplicationStatus())
                .sla(entity.getSla())
                .docUploadRequired(entity.getDocUploadRequired())
                .isStartState(entity.getIsStartState())
                .isTerminateState(entity.getIsTerminateState())
                .isStateUpdatable(entity.getIsStateUpdatable())
                .auditDetails(toAuditDTO(entity.getAuditDetails()))
                .actions(actions)
                .build();
    }

    public ActionDTO toActionDTO(ActionEntity entity) {
        if (entity == null) return null;

        return ActionDTO.builder()
                .uuid(entity.getUuid())
                .active(entity.getActive())
                .actionName(entity.getActionName())
                .nextState(entity.getNextState())
                .currentState(entity.getCurrentState() != null ? entity.getCurrentState().getUuid() : null)
                .roles(entity.getRoles() == null ? new ArrayList<>() : new ArrayList<>(entity.getRoles()))
                .auditDetails(toAuditDTO(entity.getAuditDetails()))
                .build();
    }

    public AuditDetailsDTO toAuditDTO(AuditDetails entity) {
        if (entity == null) return null;
        return AuditDetailsDTO.builder()
                .createdBy(entity.getCreatedBy())
                .createdTime(entity.getCreatedTime())
                .lastModifiedBy(entity.getLastModifiedBy())
                .lastModifiedTime(entity.getLastModifiedTime())
                .build();
    }

    /* ===================  Bulk helpers  =================== */

    public List<ActivityEntity> toEntityList(List<ActivityDTO> dtos) {
        if (dtos == null) return new ArrayList<>();
        return dtos.stream().filter(Objects::nonNull).map(this::toEntity).collect(Collectors.toList());
    }

    public List<ActivityDTO> toDTOList(List<ActivityEntity> entities) {
        if (entities == null) return new ArrayList<>();
        return entities.stream().filter(Objects::nonNull).map(this::toDTO).collect(Collectors.toList());
    }
}
