package com.activityworkflow.web.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Digit-style request metadata. We use {@code userInfo.uuid} for audit
 * stamping and {@code userInfo.roles} for the soft role-check policy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequestInfo {

    private String apiId;
    private String action;
    private Integer did;
    private String key;
    private String msgId;
    private String requesterId;
    /** Loose typing — the field is sometimes a number, sometimes an empty string. */
    private Object ts;
    private String ver;
    private String authToken;
    private UserInfo userInfo;
}

