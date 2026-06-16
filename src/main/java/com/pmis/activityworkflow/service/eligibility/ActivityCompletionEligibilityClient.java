package com.pmis.activityworkflow.service.eligibility;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pmis.activityworkflow.config.CompletionEligibilityProperties;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Calls GET /projects/api/v3/activities/{activityId}/completion-eligibility
 * and returns the parsed result.  Auth is forwarded from the inbound request,
 * matching the pattern used by {@link com.pmis.activityworkflow.service.assignments.ActivityAssignmentsClient}.
 */
@Service
@Slf4j
public class ActivityCompletionEligibilityClient {

    private final CompletionEligibilityProperties props;
    private final RestClient eligibilityRestClient;

    public ActivityCompletionEligibilityClient(
            CompletionEligibilityProperties props,
            @Qualifier("eligibilityRestClient") RestClient eligibilityRestClient) {
        this.props = props;
        this.eligibilityRestClient = eligibilityRestClient;
    }

    /**
     * Check whether {@code activityId} is eligible for completion.
     * Uses the Authorization header from the current servlet request.
     */
    public EligibilityResult check(String activityId) {
        return check(activityId, currentAuthHeader());
    }

    public EligibilityResult check(String activityId, String authHeader) {
        if (!props.isEnabled()) {
            log.debug("Completion-eligibility check disabled; skipping for activity={}", activityId);
            return EligibilityResult.eligible(activityId);
        }
        if (!StringUtils.hasText(activityId)) {
            throw new InvalidTransitionException("activityId is required");
        }
        if (!StringUtils.hasText(authHeader)) {
            throw new InvalidTransitionException(
                    "Authorization header is required to call the completion-eligibility API");
        }

        String url = props.getUrlTemplate().replace("{activityId}", activityId);
        log.debug("Checking completion eligibility for activity={} url={}", activityId, url);

        try {
            ApiResponse body = eligibilityRestClient.get()
                    .uri(url)
                    .header("accept", "application/json")
                    .header("Authorization", authHeader)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        throw new InvalidTransitionException(String.format(
                                "Completion-eligibility API returned %s for activity %s",
                                resp.getStatusCode(), activityId));
                    })
                    .body(ApiResponse.class);

            if (body == null || body.getData() == null) {
                log.warn("Completion-eligibility API returned empty body for activity {}; assuming eligible", activityId);
                return EligibilityResult.eligible(activityId);
            }
            return body.getData();

        } catch (InvalidTransitionException rethrow) {
            throw rethrow;
        } catch (Exception ex) {
            log.error("Completion-eligibility API call failed for activity {}: {}", activityId, ex.getMessage());
            throw new InvalidTransitionException(
                    "Could not check completion eligibility for activity " + activityId + ": " + ex.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Response DTOs                                                         */
    /* ------------------------------------------------------------------ */

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiResponse {
        private EligibilityResult data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EligibilityResult {
        private String activityId;
        private boolean eligible;
        private List<BlockingDependency> blockingDependencies = Collections.emptyList();

        static EligibilityResult eligible(String activityId) {
            EligibilityResult r = new EligibilityResult();
            r.activityId = activityId;
            r.eligible = true;
            return r;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BlockingDependency {
        private String id;
        private String name;
        private String status;
    }

    /* ------------------------------------------------------------------ */

    private String currentAuthHeader() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(attrs -> attrs.getRequest().getHeader("Authorization"))
                .orElse(null);
    }
}
