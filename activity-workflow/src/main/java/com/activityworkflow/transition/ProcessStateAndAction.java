package com.activityworkflow.transition;

import com.activityworkflow.entity.ActionEntity;
import com.activityworkflow.entity.ProcessInstanceEntity;
import com.activityworkflow.entity.StateEntity;
import com.activityworkflow.web.models.ProcessInstanceDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Working tuple used across the transition pipeline — mirrors Digit's
 * {@code ProcessStateAndAction}.
 *
 * <p>For each {@link ProcessInstanceDTO} in the request we build one of
 * these, then enrichment + validation + persistence all operate on the
 * list of tuples rather than walking the DTOs and entities separately.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessStateAndAction {

    /** The transition event coming in from the client. */
    private ProcessInstanceDTO processInstanceFromRequest;

    /** The latest persisted transition for this businessId — null on first transition. */
    private ProcessInstanceEntity processInstanceFromDb;

    /** State the record is in BEFORE the action — start state on first transition. */
    private StateEntity currentState;

    /** The matched action from currentState.actions. */
    private ActionEntity action;

    /** State the record will be in AFTER the action — i.e. action.nextState resolved. */
    private StateEntity resultantState;
}
