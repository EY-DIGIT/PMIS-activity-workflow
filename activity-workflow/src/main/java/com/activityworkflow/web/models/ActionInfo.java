package com.activityworkflow.web.models;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wraps the action name. Digit's transition payload uses
 * {@code "action": { "action": "APPROVE" }}, so we model the wrapper
 * explicitly rather than flatten it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionInfo {

    /** The action name itself — e.g. "INITIATE", "APPROVE". */
    private String action;
}
