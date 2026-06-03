package com.pmis.activityworkflow.exception;

/**
 * Marker for exceptions that represent a real workflow decision and
 * therefore deserve a row in {@code aw_workflow_audit}.
 *
 * <p>Things that ARE workflow-relevant:
 * <ul>
 *   <li>Action not allowed in the current state (InvalidTransitionException)</li>
 *   <li>Role-check denial when ENFORCE_ROLES=true</li>
 *   <li>Workflow definition not found for the businessService</li>
 *   <li>nextState not found on the workflow</li>
 *   <li>Record already in a terminal state</li>
 * </ul>
 *
 * <p>Things that are NOT workflow-relevant (and should not be audited):
 * <ul>
 *   <li>Missing or malformed request payload</li>
 *   <li>Missing userInfo / authentication 401-style failures</li>
 *   <li>Bean validation errors on the request body</li>
 *   <li>Database / network / framework exceptions</li>
 * </ul>
 */
public interface WorkflowAuditable {
}
