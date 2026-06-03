package com.pmis.activityworkflow.exception;

/**
 * Thrown when a workflow transition is impossible given the current state
 * of the record — e.g. the action isn't allowed from the current state,
 * the record is already terminal, or roles don't match.
 *
 * Implements {@link WorkflowAuditable} so failures of this kind land in
 * the audit table (it IS a workflow decision the user should see).
 */
public class InvalidTransitionException extends RuntimeException implements WorkflowAuditable {

    public InvalidTransitionException(String message) {
        super(message);
    }
}
